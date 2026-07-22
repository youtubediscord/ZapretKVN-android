package io.github.zapretkvn.android.diagnostics

enum class DiagnosticErrorType(
    val code: String,
    val title: String,
    val supportCode: String,
) {
    Permission("permission", "Разрешение VPN", "VPN-101"),
    Profile("profile", "Профиль или JSON", "CFG-101"),
    SystemDns("system_dns", "Системный DNS", "DNS-100"),
    PrivateDns("private_dns", "Private DNS", "DNS-110"),
    VpnServer("vpn_server", "VPN-сервер", "SRV-100"),
    VpnDns("vpn_dns", "DNS через VPN", "DNS-200"),
    CaptivePortal("captive_portal", "Авторизация Wi-Fi", "NET-110"),
    Core("core", "Ядро sing-box", "CORE-100"),
    AndroidNetwork("android_network", "Сеть Android", "NET-100"),
    Unknown("unknown", "Неизвестная ошибка", "VPN-000"),
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
    val supportCode: String,
    val message: String,
    val technicalDetail: String? = null,
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

enum class DiagnosticStageStatus(val code: String) {
    Running("running"),
    Success("success"),
    Failed("failed"),
    Cancelled("cancelled"),
}

enum class DiagnosticAttemptOutcome(val code: String) {
    Running("running"),
    Connected("connected"),
    Failed("failed"),
    Cancelled("cancelled"),
}

data class DiagnosticStageTiming(
    val key: String,
    val label: String,
    val startedAtEpochMillis: Long,
    internal val startedAtElapsedRealtimeMillis: Long,
    val durationMillis: Long? = null,
    val status: DiagnosticStageStatus = DiagnosticStageStatus.Running,
)

data class DiagnosticConnectionAttempt(
    val generation: Long,
    val trigger: String,
    val startedAtEpochMillis: Long,
    internal val startedAtElapsedRealtimeMillis: Long,
    val totalDurationMillis: Long? = null,
    val outcome: DiagnosticAttemptOutcome = DiagnosticAttemptOutcome.Running,
    val stages: List<DiagnosticStageTiming> = emptyList(),
    val startupCoreLogs: List<DiagnosticLogLine> = emptyList(),
    val failure: DiagnosticFailure? = null,
) {
    val slowestCompletedStage: DiagnosticStageTiming?
        get() = stages
            .asSequence()
            .filter { it.durationMillis != null }
            .maxByOrNull { it.durationMillis ?: -1L }
}

data class DiagnosticState(
    val generation: Long = 0,
    val lastFailure: DiagnosticFailure? = null,
    val applicationLogs: List<DiagnosticLogLine> = emptyList(),
    val coreLogs: List<DiagnosticLogLine> = emptyList(),
    val logStreamActive: Boolean = false,
    val network: DiagnosticNetworkState? = null,
    val vpnPolicy: DiagnosticVpnPolicy? = null,
    val effectiveOverlay: String? = null,
    val connectionAttempt: DiagnosticConnectionAttempt? = null,
    val previousConnectionAttempts: List<DiagnosticConnectionAttempt> = emptyList(),
    val previousCrash: AppCrashRecord? = null,
) {
    val recentConnectionAttempts: List<DiagnosticConnectionAttempt>
        get() = (previousConnectionAttempts + listOfNotNull(connectionAttempt))
            .takeLast(MAX_DIAGNOSTIC_ATTEMPTS)

    val logs: List<DiagnosticLogLine>
        get() = (applicationLogs + coreLogs)
            .sortedBy(DiagnosticLogLine::receivedAtEpochMillis)
            .takeLast(MAX_DIAGNOSTIC_LOG_LINES)
}

internal fun List<DiagnosticLogLine>.appendBounded(
    line: DiagnosticLogLine,
    limit: Int = MAX_DIAGNOSTIC_LOG_LINES,
): List<DiagnosticLogLine> = (this + line).takeLast(limit)

const val MAX_DIAGNOSTIC_LOG_LINES = 80
const val MAX_DIAGNOSTIC_STARTUP_LOG_LINES = 40
const val MAX_DIAGNOSTIC_ATTEMPTS = 3
const val MAX_DIAGNOSTIC_STAGES = 20
