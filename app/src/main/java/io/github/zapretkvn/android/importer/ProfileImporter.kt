package io.github.zapretkvn.android.importer

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ManagedServer
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.profiles.ProtocolOutboundBuilders
import io.github.zapretkvn.android.profiles.TlsSettings
import io.github.zapretkvn.android.profiles.TransportSettings
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

sealed interface ImportCandidate {
    val suggestedName: String
    val source: ProfileSource

    data class RawJson(
        val json: String,
        override val suggestedName: String,
        override val source: ProfileSource,
    ) : ImportCandidate

    data class Managed(
        val servers: List<ManagedServer>,
        override val suggestedName: String,
        override val source: ProfileSource,
    ) : ImportCandidate {
        fun buildJson(): String = if (servers.size == 1) {
            ManagedProfileFactory.single(servers.single())
        } else {
            ManagedProfileFactory.subscription(servers)
        }
    }
}

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

object ImportParser {
    fun parse(
        input: String,
        source: ProfileSource,
        suggestedName: String = "Импортированный профиль",
    ): ImportCandidate {
        val text = input.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) throw ImportException("Источник импорта пуст.")
        if (text.startsWith('{')) {
            try {
                JsonConfig.parse(text)
            } catch (error: Exception) {
                throw ImportException("Файл не содержит корректный JSON.", error)
            }
            return ImportCandidate.RawJson(
                json = JsonConfig.format(text),
                suggestedName = suggestedName,
                source = source,
            )
        }

        val linkText = decodeSubscriptionIfNeeded(text)
        val links = linkText.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toList()
        if (links.isEmpty()) throw ImportException("Подписка не содержит ссылок.")
        links.firstOrNull { link -> SUPPORTED_SCHEMES.none { link.startsWith(it, true) } }
            ?.let { throw ImportException("Подписка содержит неизвестную строку; импорт остановлен.") }
        val servers = links.mapIndexed { index, link -> ShareLinkParser.parse(link, index) }
        return ImportCandidate.Managed(
            servers = servers,
            suggestedName = if (servers.size == 1) servers.single().displayName else suggestedName,
            source = if (servers.size == 1) {
                when (source) {
                    ProfileSource.Qr -> ProfileSource.Qr
                    ProfileSource.Url -> ProfileSource.Url
                    else -> ProfileSource.Link
                }
            } else {
                ProfileSource.Subscription
            },
        )
    }

    private fun decodeSubscriptionIfNeeded(text: String): String {
        val plainLines = text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toList()
        if (plainLines.any { line -> SUPPORTED_SCHEMES.any { line.startsWith(it, true) } }) {
            return text
        }
        val decoded = runCatching { decodeBase64(text.filterNot(Char::isWhitespace)) }.getOrNull()
            ?: throw ImportException(SUPPORTED_MESSAGE)
        val decodedLines = decoded.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toList()
        if (decodedLines.none { line -> SUPPORTED_SCHEMES.any { line.startsWith(it, true) } }) {
            throw ImportException("В подписке нет поддерживаемых ссылок.")
        }
        return decoded
    }

    private const val SUPPORTED_MESSAGE =
        "Поддерживаются JSON, VLESS, VMess, Trojan, Shadowsocks, Hysteria2 и TUIC."
    private val SUPPORTED_SCHEMES = listOf(
        "vless://",
        "vmess://",
        "trojan://",
        "ss://",
        "hysteria2://",
        "hy2://",
        "tuic://",
    )
}

object ShareLinkParser {
    fun parse(link: String, index: Int = 0): ManagedServer = try {
        when {
            link.startsWith("vless://", ignoreCase = true) -> parseVless(link, index)
            link.startsWith("vmess://", ignoreCase = true) -> parseVmess(link, index)
            link.startsWith("trojan://", ignoreCase = true) -> parseTrojan(link, index)
            link.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(link, index)
            link.startsWith("hysteria2://", ignoreCase = true) ||
                link.startsWith("hy2://", ignoreCase = true) -> parseHysteria2(link, index)
            link.startsWith("tuic://", ignoreCase = true) -> parseTuic(link, index)
            else -> throw ImportException("Неподдерживаемый тип ссылки.")
        }
    } catch (error: ImportException) {
        throw error
    } catch (error: Exception) {
        throw ImportException("Не удалось разобрать ссылку №${index + 1}.", error)
    }

    private fun parseVless(link: String, index: Int): ManagedServer {
        val uri = URI(link)
        val host = requireHost(uri)
        val query = query(uri)
        val name = displayName(uri, "VLESS ${index + 1}")
        val uuid = decode(uri.rawUserInfo).takeIf(String::isNotBlank)
            ?: throw ImportException("В VLESS отсутствует UUID.")
        return ProtocolOutboundBuilders.vless(
            displayName = name,
            server = host,
            serverPort = requirePort(uri),
            uuid = uuid,
            flow = query["flow"],
            tls = tls(query, host),
            transport = transport(query),
        )
    }

    private fun parseTrojan(link: String, index: Int): ManagedServer {
        val uri = URI(link)
        val host = requireHost(uri)
        val query = query(uri)
        val password = decode(uri.rawUserInfo).takeIf(String::isNotBlank)
            ?: throw ImportException("В Trojan отсутствует пароль.")
        return ProtocolOutboundBuilders.trojan(
            displayName = displayName(uri, "Trojan ${index + 1}"),
            server = host,
            serverPort = requirePort(uri),
            password = password,
            tls = tls(query, host, defaultEnabled = true),
            transport = transport(query),
        )
    }

    private fun parseVmess(link: String, index: Int): ManagedServer {
        val encoded = link.substringAfter("vmess://").substringBefore('#').trim()
        val data = JsonConfig.parse(decodeBase64(encoded)) as? JsonObject
            ?: throw ImportException("VMess payload должен быть JSON-объектом.")
        val host = data.text("add") ?: throw ImportException("В VMess отсутствует сервер.")
        val port = data.number("port") ?: throw ImportException("В VMess отсутствует порт.")
        val uuid = data.text("id") ?: throw ImportException("В VMess отсутствует UUID.")
        val network = data.text("net").orEmpty()
        val transport = when (network) {
            "", "tcp" -> null
            "ws", "http", "httpupgrade" -> TransportSettings(
                type = network,
                path = data.text("path"),
                host = data.text("host"),
            )
            "grpc" -> TransportSettings(type = network, serviceName = data.text("path"))
            else -> throw ImportException("VMess transport '$network' пока не поддерживается.")
        }
        val tlsEnabled = data.text("tls")?.lowercase() in setOf("tls", "reality")
        return ProtocolOutboundBuilders.vmess(
            displayName = data.text("ps") ?: "VMess ${index + 1}",
            server = host,
            serverPort = port,
            uuid = uuid,
            security = data.text("scy") ?: "auto",
            alterId = data.number("aid") ?: 0,
            tls = TlsSettings(
                enabled = tlsEnabled,
                serverName = data.text("sni") ?: host,
                insecure = data.text("allowInsecure") == "1",
                utlsFingerprint = data.text("fp"),
            ),
            transport = transport,
        )
    }

    private fun parseShadowsocks(link: String, index: Int): ManagedServer {
        val body = link.substringAfter("ss://")
        val fragment = body.substringAfter('#', "")
        val beforeFragment = body.substringBefore('#')
        val rawQuery = beforeFragment.substringAfter('?', "")
        if (rawQuery.split('&').any { decode(it.substringBefore('=')).equals("plugin", true) }) {
            throw ImportException("Shadowsocks plugin в URI пока не поддерживается.")
        }
        val withoutFragment = beforeFragment.substringBefore('?')
        val expanded = if ('@' in withoutFragment) withoutFragment else decodeBase64(withoutFragment)
        val credentialPart = expanded.substringBeforeLast('@')
        val serverPart = expanded.substringAfterLast('@', "")
        if (serverPart.isBlank()) throw ImportException("В Shadowsocks отсутствует сервер.")
        val credentials = decodeCredentials(credentialPart)
        val method = credentials.substringBefore(':')
        val password = credentials.substringAfter(':', "")
        if (method.isBlank() || password.isBlank()) {
            throw ImportException("В Shadowsocks отсутствуют method или password.")
        }
        val serverUri = URI("ss://placeholder@$serverPart")
        return ProtocolOutboundBuilders.shadowsocks(
            displayName = decode(fragment).ifBlank { "Shadowsocks ${index + 1}" },
            server = requireHost(serverUri),
            serverPort = requirePort(serverUri),
            method = method,
            password = password,
        )
    }

    private fun parseHysteria2(link: String, index: Int): ManagedServer {
        val normalized = if (link.startsWith("hy2://", true)) {
            "hysteria2://" + link.substringAfter("://")
        } else {
            link
        }
        val uri = URI(normalized)
        val host = requireHost(uri)
        val query = query(uri)
        val password = decode(uri.rawUserInfo).takeIf(String::isNotBlank)
            ?: query["auth"]?.takeIf(String::isNotBlank)
            ?: throw ImportException("В Hysteria2 отсутствует пароль.")
        val obfsType = query["obfs"]?.lowercase()
        if (obfsType != null && obfsType !in setOf("none", "salamander")) {
            throw ImportException("Hysteria2 obfs '$obfsType' пока не поддерживается.")
        }
        return ProtocolOutboundBuilders.hysteria2(
            displayName = displayName(uri, "Hysteria2 ${index + 1}"),
            server = host,
            serverPort = requirePort(uri),
            password = password,
            tls = TlsSettings(
                enabled = true,
                serverName = query["sni"] ?: query["peer"] ?: host,
                insecure = query.boolean("insecure") || query.boolean("allowInsecure"),
                alpn = query.csv("alpn"),
            ),
            obfsPassword = if (obfsType == "salamander") {
                query["obfs-password"] ?: query["obfs_password"]
            } else {
                null
            },
            upMbps = query["upmbps"]?.toIntOrNull(),
            downMbps = query["downmbps"]?.toIntOrNull(),
        )
    }

    private fun parseTuic(link: String, index: Int): ManagedServer {
        val uri = URI(link)
        val host = requireHost(uri)
        val query = query(uri)
        val credentials = decode(uri.rawUserInfo)
        val uuid = credentials.substringBefore(':').takeIf(String::isNotBlank)
            ?: throw ImportException("В TUIC отсутствует UUID.")
        val password = credentials.substringAfter(':', "").takeIf(String::isNotBlank)
            ?: throw ImportException("В TUIC отсутствует пароль.")
        return ProtocolOutboundBuilders.tuic(
            displayName = displayName(uri, "TUIC ${index + 1}"),
            server = host,
            serverPort = requirePort(uri),
            uuid = uuid,
            password = password,
            congestionControl = query["congestion_control"] ?: query["congestion-control"],
            udpRelayMode = query["udp_relay_mode"] ?: query["udp-relay-mode"],
            zeroRttHandshake = query.boolean("zero_rtt_handshake") || query.boolean("zero-rtt-handshake"),
            heartbeat = query["heartbeat"],
            tls = TlsSettings(
                enabled = true,
                serverName = query["sni"] ?: host,
                insecure = query.boolean("allow_insecure") ||
                    query.boolean("allowInsecure") ||
                    query.boolean("insecure"),
                alpn = query.csv("alpn"),
            ),
        )
    }

    private fun decodeCredentials(value: String): String {
        val decoded = decode(value)
        return if (':' in decoded) decoded else decodeBase64(value)
    }

    private fun tls(
        query: Map<String, String>,
        host: String,
        defaultEnabled: Boolean = false,
    ): TlsSettings {
        val security = query["security"]?.lowercase()
        val enabled = defaultEnabled || security == "tls" || security == "reality"
        return TlsSettings(
            enabled = enabled,
            serverName = query["sni"] ?: query["serverName"] ?: if (enabled) host else null,
            insecure = query["allowInsecure"] == "1",
            utlsFingerprint = query["fp"],
            realityPublicKey = query["pbk"] ?: query["publicKey"],
            realityShortId = query["sid"] ?: query["shortId"],
        )
    }

    private fun transport(query: Map<String, String>): TransportSettings? = when (
        val type = query["type"]?.lowercase().orEmpty()
    ) {
        "", "tcp", "none" -> null
        "ws", "http", "httpupgrade" -> TransportSettings(
            type = type,
            path = query["path"],
            host = query["host"],
        )
        "grpc" -> TransportSettings(
            type = type,
            serviceName = query["serviceName"] ?: query["service_name"],
        )
        else -> throw ImportException("Transport '$type' пока не поддерживается.")
    }

    private fun query(uri: URI): Map<String, String> = uri.rawQuery
        .orEmpty()
        .split('&')
        .filter(String::isNotBlank)
        .associate { part ->
            val key = decode(part.substringBefore('='))
            val value = decode(part.substringAfter('=', ""))
            key to value
        }

    private fun displayName(uri: URI, fallback: String): String =
        decode(uri.rawFragment.orEmpty()).ifBlank { fallback }

    private fun Map<String, String>.boolean(key: String): Boolean =
        this[key]?.lowercase() in setOf("1", "true", "yes", "on")

    private fun Map<String, String>.csv(key: String): List<String> =
        this[key].orEmpty().split(',').map(String::trim).filter(String::isNotBlank)

    private fun requireHost(uri: URI): String = uri.host
        ?.takeIf(String::isNotBlank)
        ?.removeSurrounding("[", "]")
        ?: throw ImportException("В ссылке отсутствует сервер.")

    private fun requirePort(uri: URI): Int = uri.port
        .takeIf { it in 1..65535 }
        ?: throw ImportException("В ссылке отсутствует корректный порт.")

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.number(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
    }
}

class AndroidImportReader(private val context: Context) {
    fun readDocument(uri: Uri): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImportException("Не удалось открыть выбранный файл.")
        return try {
            input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_IMPORT_BYTES) {
                        throw ImportException("Файл больше 4 МБ.")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(StandardCharsets.UTF_8.name())
            }
        } catch (error: IOException) {
            throw ImportException("Ошибка чтения выбранного файла.", error)
        }
    }

    fun readClipboardAfterUserAction(): String {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: throw ImportException("Буфер обмена недоступен.")
        val clip = clipboard.primaryClip
            ?: throw ImportException("Буфер обмена пуст.")
        if (clip.itemCount == 0) throw ImportException("Буфер обмена пуст.")
        val text = clip.getItemAt(0).coerceToText(context)?.toString()
            ?.takeIf(String::isNotBlank)
            ?: throw ImportException("В буфере нет текста.")
        if (text.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_BYTES) {
            throw ImportException("Текст в буфере больше 4 МБ.")
        }
        return text
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 4 * 1024 * 1024
    }
}

internal fun decodeBase64(input: String): String {
    val compact = input.filterNot(Char::isWhitespace)
    val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
    val decoder = if ('-' in padded || '_' in padded) Base64.getUrlDecoder() else Base64.getDecoder()
    return String(decoder.decode(padded), Charsets.UTF_8)
}

private fun decode(value: String): String =
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
