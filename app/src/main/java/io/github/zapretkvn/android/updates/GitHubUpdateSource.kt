package io.github.zapretkvn.android.updates

import io.github.zapretkvn.android.ui.UpdateChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

interface UpdateHttpClient {
    fun readText(url: String, maxBytes: Int): String
    fun download(url: String, target: File, expectedBytes: Long, onProgress: (Long) -> Unit)
}

fun interface UpdateReleaseSource {
    fun latest(channel: UpdateChannel): UpdateCandidate
}

class GitHubHttpsClient : UpdateHttpClient {
    override fun readText(url: String, maxBytes: Int): String = request(url) { connection ->
        val declared = connection.contentLengthLong
        if (declared > maxBytes) throw UpdateException("Ответ GitHub слишком большой.")
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw UpdateException("Ответ GitHub слишком большой.")
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    override fun download(
        url: String,
        target: File,
        expectedBytes: Long,
        onProgress: (Long) -> Unit,
    ) = request(url) { connection ->
        if (expectedBytes <= 0 || expectedBytes > UpdateJson.MAX_APK_BYTES) {
            throw UpdateException("Некорректный размер APK.")
        }
        val declared = connection.contentLengthLong
        if (declared > 0 && declared != expectedBytes) {
            throw UpdateException("Размер APK не совпадает с GitHub release.")
        }
        target.outputStream().buffered().use { output ->
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > expectedBytes || total > UpdateJson.MAX_APK_BYTES) {
                        throw UpdateException("Загруженный APK больше опубликованного размера.")
                    }
                    output.write(buffer, 0, count)
                    onProgress(total)
                }
                output.flush()
                if (total != expectedBytes) throw UpdateException("Загрузка APK прервана.")
            }
        }
    }

    private fun <T> request(rawUrl: String, block: (HttpsURLConnection) -> T): T {
        var current = validatedUrl(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            val connection = URL(current).openConnection() as? HttpsURLConnection
                ?: throw UpdateException("Для обновлений разрешён только HTTPS.")
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                connection.setRequestProperty("User-Agent", "Zapret-KVN-Android-Updater")
                when (val status = connection.responseCode) {
                    in REDIRECTS -> {
                        if (redirectIndex == MAX_REDIRECTS) throw UpdateException("Слишком много перенаправлений GitHub.")
                        val location = connection.getHeaderField("Location")
                            ?: throw UpdateException("GitHub вернул перенаправление без адреса.")
                        current = validatedUrl(URI(current).resolve(location).toString())
                        return@repeat
                    }
                    in 200..299 -> return block(connection)
                    403 -> throw UpdateException("GitHub временно ограничил число проверок. Повторите позже.")
                    404 -> throw UpdateException("GitHub Release пока не опубликован.")
                    else -> throw UpdateException("GitHub вернул HTTP $status.")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw UpdateException("Не удалось получить файл GitHub Release.")
    }

    companion object {
        fun validatedUrl(raw: String): String {
            val uri = try {
                URI(raw)
            } catch (error: Exception) {
                throw UpdateException("GitHub вернул некорректный URL.", error)
            }
            val host = uri.host?.lowercase()
            val allowedHost = host == "api.github.com" || host == "github.com" ||
                host?.endsWith(".githubusercontent.com") == true
            if (uri.scheme != "https" || !allowedHost || uri.userInfo != null ||
                uri.fragment != null || uri.port !in setOf(-1, 443)
            ) {
                throw UpdateException("Перенаправление за пределы GitHub запрещено.")
            }
            return uri.toASCIIString()
        }

        private const val MAX_REDIRECTS = 5
        private const val TIMEOUT_MILLIS = 30_000
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}

class GitHubUpdateSource(
    repository: String,
    private val applicationId: String,
    private val http: UpdateHttpClient = GitHubHttpsClient(),
) : UpdateReleaseSource {
    private val repository = repository.also {
        if (!REPOSITORY.matches(it)) throw IllegalArgumentException("Invalid GitHub repository: $it")
    }

    override fun latest(channel: UpdateChannel): UpdateCandidate {
        val endpoint = when (channel) {
            UpdateChannel.Stable -> "https://api.github.com/repos/$repository/releases/latest"
            UpdateChannel.Beta -> "https://api.github.com/repos/$repository/releases?per_page=10"
        }
        val releases = UpdateJson.releases(http.readText(endpoint, MAX_RELEASE_JSON_BYTES))
            .filterNot(GitHubRelease::draft)
            .filter { channel == UpdateChannel.Beta || !it.prerelease }
        if (releases.isEmpty()) throw UpdateException("В выбранном канале нет GitHub Releases.")

        return candidate(releases.first())
    }

    private fun candidate(release: GitHubRelease): UpdateCandidate {
        val metadataAsset = release.assets.singleOrNull { it.name == METADATA_FILE }
            ?: throw UpdateException("Release должен содержать ровно один $METADATA_FILE.")
        val metadata = UpdateJson.metadata(http.readText(metadataAsset.downloadUrl, MAX_METADATA_BYTES))
        if (metadata.applicationId != applicationId) {
            throw UpdateException("Release предназначен для другого Android package.")
        }
        if (metadata.versionName != release.tag.removePrefix("v")) {
            throw UpdateException("Версия metadata не совпадает с GitHub tag.")
        }
        if (metadata.abi != listOf("arm64-v8a")) {
            throw UpdateException("Release содержит неподдерживаемый набор ABI.")
        }
        val apk = release.assets.singleOrNull { it.name == metadata.apkFile }
            ?: throw UpdateException("Release должен содержать ровно один заявленный APK.")
        if (apk.size != metadata.apkSize) throw UpdateException("Размер APK не совпадает с metadata.")
        apk.digest?.let { digest ->
            if (UpdateJson.normalizedSha256(digest) != metadata.apkSha256) {
                throw UpdateException("GitHub digest APK не совпадает с metadata.")
            }
        }
        val checksum = release.assets.singleOrNull { it.name == "${metadata.apkFile}.sha256" }
            ?: throw UpdateException("Release не содержит SHA-256 asset.")
        val published = UpdateJson.checksum(
            http.readText(checksum.downloadUrl, MAX_CHECKSUM_BYTES),
            metadata.apkFile,
        )
        if (published != metadata.apkSha256) throw UpdateException("Опубликованные SHA-256 не совпадают.")
        return UpdateCandidate(release, metadata, apk, checksum)
    }

    private companion object {
        const val METADATA_FILE = "release-metadata.json"
        const val MAX_RELEASE_JSON_BYTES = 1024 * 1024
        const val MAX_METADATA_BYTES = 64 * 1024
        const val MAX_CHECKSUM_BYTES = 4 * 1024
        val REPOSITORY = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    }
}
