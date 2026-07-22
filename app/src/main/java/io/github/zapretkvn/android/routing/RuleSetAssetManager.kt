package io.github.zapretkvn.android.routing

import android.content.Context
import io.github.zapretkvn.android.config.JsonConfig
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class InstalledRuleSets(
    val version: Int,
    val paths: Map<String, String>,
) {
    fun requirePath(tag: String): String = paths[tag]
        ?: error("Встроенный rule-set '$tag' отсутствует.")
}

fun interface RuleSetAssetSource {
    fun open(relativePath: String): InputStream
}

class RuleSetAssetManager(context: Context) {
    private val installer = RuleSetAssetInstaller(
        root = File(context.filesDir, "rule-sets"),
        source = RuleSetAssetSource { relative ->
            context.assets.open("rule-sets/$relative")
        },
    )

    suspend fun ensureInstalled(): InstalledRuleSets = installer.ensureInstalled()
}

class RuleSetAssetInstaller(
    private val root: File,
    private val source: RuleSetAssetSource,
) {
    private val mutex = Mutex()

    suspend fun ensureInstalled(): InstalledRuleSets = mutex.withLock {
        ensureRoot()
        root.listFiles().orEmpty()
            .filter { it.name.endsWith(TEMP_SUFFIX) }
            .forEach(File::delete)

        val manifestBytes = source.open(MANIFEST_NAME).use(InputStream::readBytes)
        val manifest = parseManifest(manifestBytes)
        val installedManifest = File(root, MANIFEST_NAME)
        val current = installedManifest.isFile &&
            installedManifest.readBytes().contentEquals(manifestBytes) &&
            manifest.entries.all { entry ->
                val target = File(root, entry.file)
                target.isFile && sha256(target) == entry.sha256
            }

        if (!current) {
            manifest.entries.forEach(::installEntry)
            writeManifestLast(installedManifest, manifestBytes)
        }

        val paths = manifest.entries.associate { entry ->
            entry.tag to File(root, entry.file).absolutePath
        }
        InstalledRuleSets(manifest.version, paths)
    }

    private fun installEntry(entry: ManifestEntry) {
        val target = File(root, entry.file)
        val temporary = File(root, entry.file + TEMP_SUFFIX)
        temporary.delete()
        try {
            source.open(entry.file).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(sha256(temporary) == entry.sha256) {
                "SHA-256 встроенного rule-set '${entry.tag}' не совпадает с manifest."
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun writeManifestLast(file: File, bytes: ByteArray) {
        val temporary = File(root, MANIFEST_NAME + TEMP_SUFFIX)
        temporary.delete()
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun parseManifest(bytes: ByteArray): RuleSetManifest {
        val root = JsonConfig.parse(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: error("Manifest rule-set должен быть JSON-объектом.")
        val version = (root["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: error("Manifest rule-set не содержит version.")
        val entries = (root["sets"] as? JsonArray).orEmpty().map { element ->
            val item = element as? JsonObject ?: error("Некорректная запись manifest rule-set.")
            ManifestEntry(
                tag = item.requiredString("tag"),
                file = item.requiredString("file"),
                sha256 = item.requiredString("sha256"),
            )
        }
        check(entries.map(ManifestEntry::tag).toSet() == REQUIRED_TAGS) {
            "Manifest должен содержать только обязательные RU rule-set."
        }
        check(entries.all { it.tag.startsWith("zapret-") }) {
            "Встроенный rule-set использует незарезервированный tag."
        }
        check(entries.all { it.file.matches(SAFE_FILE) && it.sha256.matches(SHA256) }) {
            "Manifest rule-set содержит небезопасное имя или SHA-256."
        }
        return RuleSetManifest(version, entries)
    }

    private fun ensureRoot() {
        if (!root.exists() && !root.mkdirs()) error("Не удалось создать каталог rule-set.")
        check(root.isDirectory) { "Каталог rule-set недоступен." }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.requiredString(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: error("Manifest rule-set не содержит '$key'.")

    private data class RuleSetManifest(val version: Int, val entries: List<ManifestEntry>)
    private data class ManifestEntry(val tag: String, val file: String, val sha256: String)

    private companion object {
        const val MANIFEST_NAME = "manifest.json"
        const val TEMP_SUFFIX = ".tmp"
        val REQUIRED_TAGS = setOf("zapret-ru-domains", "zapret-ru-ip")
        val SAFE_FILE = Regex("[a-z0-9-]+\\.srs")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
