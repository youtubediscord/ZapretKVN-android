package io.github.zapretkvn.android.vpn

import io.github.zapretkvn.android.diagnostics.DiagnosticLogLine

/**
 * Promotes a generic data-plane failure only when the core reports an explicit
 * authentication rejection. Silent closes and timeouts are intentionally not
 * treated as proof of an invalid or revoked key.
 */
internal object VpnAccessFailureClassifier {
    private const val FAILURE_CODE = "AUTH-100"
    private const val USER_MESSAGE =
        "VPN-сервер явно отклонил ключ или учётные данные. " +
            "Обновите профиль или получите новый ключ."

    private val explicitRejectionMarkers = listOf(
        "authentication failed, status code:",
        "reality authentication failed",
        "ssh: unable to authenticate",
        "password authentication failed",
        "public key authentication failed",
        "tls: access denied",
    )

    fun refine(
        failure: VpnConnectionState.Error,
        startupCoreLogs: List<DiagnosticLogLine>,
    ): VpnConnectionState.Error {
        val evidence = sequenceOf(failure.message) +
            startupCoreLogs.asSequence().map(DiagnosticLogLine::message)
        if (evidence.none(::isExplicitRejection)) return failure
        return VpnConnectionState.Error(
            message = USER_MESSAGE,
            code = FAILURE_CODE,
            technicalDetail = "core_auth_rejection",
        )
    }

    private fun isExplicitRejection(message: String): Boolean {
        val value = message.lowercase()
        return explicitRejectionMarkers.any(value::contains)
    }
}
