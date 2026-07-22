package io.github.zapretkvn.android.updates

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.zapretkvn.android.ui.UpdateChannel
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class UpdateController(
    context: Context,
    repository: String,
    private val currentVersionName: String,
    private val currentVersionCode: Long,
    private val source: UpdateReleaseSource = GitHubUpdateSource(repository, context.packageName),
    private val http: UpdateHttpClient = GitHubHttpsClient(),
    private val verifier: ApkUpdateVerifier = AndroidApkUpdateVerifier(context),
) {
    private val appContext = context.applicationContext
    private val root = File(appContext.cacheDir, "updates")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var operation: Job? = null
    private var readyFile: File? = null
    private val automaticCheckStarted = AtomicBoolean(false)

    init {
        cleanupStaleFiles()
    }

    fun check(channel: UpdateChannel) {
        replaceOperation {
            cleanupFiles()
            mutableState.value = UpdateState.Checking(channel.name)
            try {
                val candidate = source.latest(channel)
                coroutineContext.ensureActive()
                mutableState.value = if (candidate.metadata.versionCode > currentVersionCode) {
                    UpdateState.Available(candidate)
                } else {
                    UpdateState.UpToDate(candidate.release.tag, currentVersionName)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: UpdateException) {
                mutableState.value = UpdateState.Failure(error.message ?: "Не удалось проверить обновления.")
            } catch (_: Throwable) {
                mutableState.value = UpdateState.Failure("Не удалось проверить обновления.")
            }
        }
    }

    /** Runs at most once per app process; no worker, alarm or periodic polling is created. */
    fun checkOnce(channel: UpdateChannel) {
        if (automaticCheckStarted.compareAndSet(false, true)) check(channel)
    }

    fun download() {
        val candidate = when (val current = mutableState.value) {
            is UpdateState.Available -> current.candidate
            is UpdateState.Failure -> current.candidate
            else -> null
        } ?: return
        replaceOperation {
            cleanupFiles()
            root.mkdirs()
            val partial = File(root, "${candidate.metadata.apkFile}.part")
            val complete = File(root, candidate.metadata.apkFile)
            var lastPublishedAt = 0L
            try {
                val downloadJob = coroutineContext[Job]
                mutableState.value = UpdateState.Downloading(candidate, 0, candidate.metadata.apkSize)
                http.download(
                    candidate.apkAsset.downloadUrl,
                    partial,
                    candidate.metadata.apkSize,
                ) { downloaded ->
                    downloadJob?.ensureActive()
                    val now = System.nanoTime()
                    if (downloaded == candidate.metadata.apkSize || now - lastPublishedAt >= 250_000_000L) {
                        lastPublishedAt = now
                        mutableState.value = UpdateState.Downloading(
                            candidate,
                            downloaded,
                            candidate.metadata.apkSize,
                        )
                    }
                }
                coroutineContext.ensureActive()
                if (sha256(partial) != candidate.metadata.apkSha256) {
                    throw UpdateException("SHA-256 загруженного APK не совпадает с опубликованным.")
                }
                coroutineContext.ensureActive()
                if (!partial.renameTo(complete)) throw UpdateException("Не удалось завершить временный APK.")
                verifier.verify(complete, candidate.metadata)
                coroutineContext.ensureActive()
                readyFile = complete
                mutableState.value = UpdateState.Ready(candidate)
            } catch (cancelled: CancellationException) {
                cleanupFiles()
                throw cancelled
            } catch (error: UpdateException) {
                cleanupFiles()
                mutableState.value = UpdateState.Failure(
                    error.message ?: "Не удалось загрузить обновление.",
                    candidate,
                )
            } catch (_: Throwable) {
                cleanupFiles()
                mutableState.value = UpdateState.Failure("Не удалось загрузить обновление.", candidate)
            }
        }
    }

    fun cancelAndDelete() {
        val previous = operation
        operation = scope.launch {
            previous?.cancelAndJoin()
            cleanupFiles()
            mutableState.value = UpdateState.Idle
        }
    }

    @Suppress("DEPRECATION")
    fun createInstallIntent(): Intent {
        val file = readyFile?.takeIf(File::isFile)
            ?: throw UpdateException("Проверенный APK больше недоступен.")
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, APK_MIME)
            clipData = ClipData.newRawUri("Zapret KVN update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
    }

    fun onInstallerFinished(installed: Boolean) {
        val candidate = (mutableState.value as? UpdateState.Ready)?.candidate
        cleanupFiles()
        mutableState.value = if (installed) {
            UpdateState.Idle
        } else {
            UpdateState.Failure("Установка отменена или не завершена.", candidate)
        }
    }

    fun failInstallation(message: String) {
        val candidate = (mutableState.value as? UpdateState.Ready)?.candidate
        cleanupFiles()
        mutableState.value = UpdateState.Failure(message, candidate)
    }

    fun cleanupStaleFiles() {
        cleanupFiles()
    }

    private fun replaceOperation(block: suspend () -> Unit) {
        operation?.cancel()
        operation = scope.launch { block() }
    }

    private fun cleanupFiles() {
        readyFile = null
        root.listFiles()?.forEach { file ->
            if (file.isFile) file.delete()
        }
        root.delete()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
