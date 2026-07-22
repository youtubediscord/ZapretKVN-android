package io.github.zapretkvn.android.updates

import io.github.zapretkvn.android.config.JsonConfig
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

data class GitHubAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val digest: String?,
)

data class GitHubRelease(
    val tag: String,
    val title: String,
    val pageUrl: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<GitHubAsset>,
)

data class ReleaseMetadata(
    val versionName: String,
    val versionCode: Long,
    val applicationId: String,
    val coreTag: String,
    val coreCommit: String,
    val abi: List<String>,
    val apkFile: String,
    val apkSha256: String,
    val apkSize: Long,
)

data class UpdateCandidate(
    val release: GitHubRelease,
    val metadata: ReleaseMetadata,
    val apkAsset: GitHubAsset,
    val checksumAsset: GitHubAsset,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Checking(val channel: String) : UpdateState
    data class UpToDate(val checkedTag: String, val currentVersion: String) : UpdateState
    data class Available(val candidate: UpdateCandidate) : UpdateState
    data class Downloading(
        val candidate: UpdateCandidate,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateState
    data class Ready(val candidate: UpdateCandidate) : UpdateState
    data class Failure(val message: String, val candidate: UpdateCandidate? = null) : UpdateState
}

class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

object UpdateJson {
    fun releases(raw: String): List<GitHubRelease> {
        val element = try {
            JsonConfig.parse(raw)
        } catch (error: Exception) {
            throw UpdateException("GitHub вернул некорректный JSON.", error)
        }
        val objects = when (element) {
            is JsonObject -> listOf(element)
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            else -> throw UpdateException("GitHub вернул неожиданный формат release.")
        }
        return objects.map(::release)
    }

    fun metadata(raw: String): ReleaseMetadata {
        val root = try {
            JsonConfig.parse(raw) as? JsonObject
                ?: throw UpdateException("release-metadata.json должен быть объектом.")
        } catch (error: UpdateException) {
            throw error
        } catch (error: Exception) {
            throw UpdateException("release-metadata.json повреждён.", error)
        }
        if (root.requiredInt("schema") != 1) {
            throw UpdateException("Версия release metadata не поддерживается.")
        }
        val apkFile = root.requiredString("apk_file")
        if (!APK_FILE.matches(apkFile)) throw UpdateException("Некорректное имя APK в metadata.")
        val sha256 = normalizedSha256(root.requiredString("apk_sha256"))
        val abi = (root["abi"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.takeIf(List<String>::isNotEmpty)
            ?: throw UpdateException("ABI отсутствует в release metadata.")
        return ReleaseMetadata(
            versionName = root.requiredString("version_name"),
            versionCode = root.requiredLong("version_code").also {
                if (it <= 0) throw UpdateException("Некорректный versionCode в metadata.")
            },
            applicationId = root.requiredString("application_id"),
            coreTag = root.requiredString("core_tag"),
            coreCommit = root.requiredString("core_commit").also {
                if (!COMMIT.matches(it)) throw UpdateException("Некорректный commit ядра в metadata.")
            },
            abi = abi,
            apkFile = apkFile,
            apkSha256 = sha256,
            apkSize = root.requiredLong("apk_size").also {
                if (it <= 0 || it > MAX_APK_BYTES) throw UpdateException("Некорректный размер APK.")
            },
        )
    }

    fun checksum(raw: String, expectedFile: String): String {
        val line = raw.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
            ?: throw UpdateException("Файл SHA-256 пуст.")
        val match = CHECKSUM_LINE.matchEntire(line)
            ?: throw UpdateException("Файл SHA-256 имеет неизвестный формат.")
        val fileName = match.groupValues[2].removePrefix("*")
        if (fileName != expectedFile) throw UpdateException("SHA-256 относится к другому APK.")
        return normalizedSha256(match.groupValues[1])
    }

    private fun release(root: JsonObject): GitHubRelease {
        val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { value ->
            val asset = value as? JsonObject ?: return@mapNotNull null
            GitHubAsset(
                name = asset.requiredString("name"),
                downloadUrl = asset.requiredString("browser_download_url"),
                size = asset.requiredLong("size"),
                digest = asset.string("digest"),
            )
        }
        return GitHubRelease(
            tag = root.requiredString("tag_name"),
            title = root.string("name")?.takeIf(String::isNotBlank) ?: root.requiredString("tag_name"),
            pageUrl = root.requiredString("html_url"),
            draft = root.requiredBoolean("draft"),
            prerelease = root.requiredBoolean("prerelease"),
            assets = assets,
        )
    }

    fun normalizedSha256(raw: String): String {
        val value = raw.removePrefix("sha256:").lowercase(Locale.ROOT)
        if (!SHA256.matches(value)) throw UpdateException("Некорректный SHA-256.")
        return value
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.requiredString(name: String): String =
        string(name)?.takeIf(String::isNotBlank)
            ?: throw UpdateException("Поле $name отсутствует в release metadata.")

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        (get(name) as? JsonPrimitive)?.booleanOrNull
            ?: throw UpdateException("Поле $name отсутствует в GitHub release.")

    private fun JsonObject.requiredInt(name: String): Int =
        (get(name) as? JsonPrimitive)?.intOrNull
            ?: throw UpdateException("Поле $name отсутствует в release metadata.")

    private fun JsonObject.requiredLong(name: String): Long =
        (get(name) as? JsonPrimitive)?.longOrNull
            ?: throw UpdateException("Поле $name отсутствует в release metadata.")

    const val MAX_APK_BYTES = 512L * 1024 * 1024
    private val APK_FILE = Regex("[A-Za-z0-9._-]+\\.apk")
    private val COMMIT = Regex("[0-9a-f]{40}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val CHECKSUM_LINE = Regex("([0-9A-Fa-f]{64})[ \\t]+([*]?[A-Za-z0-9._-]+\\.apk)")
}
