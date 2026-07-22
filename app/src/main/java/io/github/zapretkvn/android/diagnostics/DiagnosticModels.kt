package io.github.zapretkvn.android.diagnostics

enum class DiagnosticErrorType(
    val code: String,
    val title: String,
) {
    Permission("permission", "Разрешение VPN"),
    Profile("profile", "Профиль или JSON"),
    SystemDns("system_dns", "Системный DNS"),
    PrivateDns("private_dns", "Private DNS"),
    VpnServer("vpn_server", "VPN-сервер"),
    VpnDns("vpn_dns", "DNS через VPN"),
    CaptivePortal("captive_portal", "Авторизация Wi-Fi"),
    Core("core", "Ядро sing-box"),
    AndroidNetwork("android_network", "Сеть Android"),
    Unknown("unknown", "Неизвестная ошибка"),
}

object DiagnosticFailureClassifier {
    fun classify(message: String): DiagnosticErrorType {
        val value = message.lowercase()
        return when {
            "always-on" in value || "lockdown" in value -> DiagnosticErrorType.Permission
            "private dns" in value -> DiagnosticErrorType.PrivateDns
            "авторизац" in value || "captive" in value -> DiagnosticErrorType.CaptivePortal
            "разрешение" in value && "vpn" in value -> DiagnosticErrorType.Permission
            ("dns" in value && "vpn" in value) ||
                "внутренний dns" in value ||
                "внутреннего dns" in value ||
                "doh" in value -> DiagnosticErrorType.VpnDns
            "системный dns" in value ||
                "системного dns" in value ||
                "bootstrap" in value ||
                "разрешить адрес" in value -> DiagnosticErrorType.SystemDns
            "vpn-сервер" in value ||
                "proxy socket" in value ||
                "сервер не отвечает" in value -> DiagnosticErrorType.VpnServer
            "профил" in value ||
                "json" in value ||
                "конфигурац" in value ||
                "checkconfig" in value -> DiagnosticErrorType.Profile
            "sing-box" in value || "libbox" in value || "ядр" in value -> DiagnosticErrorType.Core
            "сеть android" in value ||
                "underlying" in value ||
                "интернет" in value -> DiagnosticErrorType.AndroidNetwork
            else -> DiagnosticErrorType.Unknown
        }
    }
}

data class DiagnosticFailure(
    val type: DiagnosticErrorType,
    val message: String,
    val occurredAtEpochMillis: Long,
)

data class DiagnosticLogLine(
    val level: Int,
    val message: String,
    val receivedAtEpochMillis: Long,
) {
    val levelName: String
        get() = when (level) {
            0 -> "PANIC"
            1 -> "FATAL"
            2 -> "ERROR"
            3 -> "WARN"
            4 -> "NOTICE"
            5 -> "INFO"
            6 -> "DEBUG"
            7 -> "TRACE"
            else -> "LOG"
        }
}

data class DiagnosticNetworkState(
    val available: Boolean = false,
    val transport: String = "none",
    val interfaceName: String? = null,
    val metered: Boolean = false,
    val validated: Boolean = false,
    val captivePortal: Boolean = false,
    val privateDnsMode: String = "off",
    val privateDnsActive: Boolean = false,
)

data class DiagnosticVpnPolicy(
    val statusAvailable: Boolean,
    val alwaysOn: Boolean,
    val lockdown: Boolean,
)

data class DiagnosticState(
    val generation: Long = 0,
    val lastFailure: DiagnosticFailure? = null,
    val applicationLogs: List<DiagnosticLogLine> = emptyList(),
    val coreLogs: List<DiagnosticLogLine> = emptyList(),
    val logStreamActive: Boolean = false,
    val network: DiagnosticNetworkState? = null,
    val vpnPolicy: DiagnosticVpnPolicy? = null,
    val effectiveOverlay: String? = null,
) {
    val logs: List<DiagnosticLogLine>
        get() = (applicationLogs + coreLogs)
            .sortedBy(DiagnosticLogLine::receivedAtEpochMillis)
            .takeLast(MAX_DIAGNOSTIC_LOG_LINES)
}

internal fun List<DiagnosticLogLine>.appendBounded(line: DiagnosticLogLine): List<DiagnosticLogLine> =
    (this + line).takeLast(MAX_DIAGNOSTIC_LOG_LINES)

const val MAX_DIAGNOSTIC_LOG_LINES = 80
