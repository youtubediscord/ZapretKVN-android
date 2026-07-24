package io.github.zapretkvn.android.vpn

import io.github.zapretkvn.android.diagnostics.DiagnosticLogLine
import io.github.zapretkvn.android.diagnostics.DiagnosticLogSource
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnAccessFailureClassifierTest {
    @Test
    fun `explicit core authentication rejection gets a separate code`() {
        val failure = VpnConnectionState.Error(
            message = "HTTPS-проверка через VPN не прошла: истёк тайм-аут.",
            code = "VPN-200",
        )
        val logs = listOf(
            coreLog(
                "outbound/hysteria2[proxy]: authentication failed, status code: 404",
            ),
        )

        val refined = VpnAccessFailureClassifier.refine(failure, logs)

        assertEquals("AUTH-100", refined.code)
        assertEquals(
            "VPN-сервер явно отклонил ключ или учётные данные. " +
                "Обновите профиль или получите новый ключ.",
            refined.message,
        )
        assertEquals("core_auth_rejection", refined.technicalDetail)
    }

    @Test
    fun `silent vless close remains generic vpn traffic failure`() {
        val failure = VpnConnectionState.Error(
            message = "HTTPS-проверка через VPN не прошла: google:истёк тайм-аут.",
            code = "VPN-200",
        )
        val logs = listOf(
            coreLog("outbound/vless[proxy]: connection closed: EOF"),
            coreLog("exchange6: context deadline exceeded"),
        )

        assertEquals(failure, VpnAccessFailureClassifier.refine(failure, logs))
    }

    @Test
    fun `wifi authorization wording is not treated as rejected vpn key`() {
        val failure = VpnConnectionState.Error(
            message = "Интернет требует авторизации в Wi-Fi.",
            code = "NET-110",
        )

        assertEquals(failure, VpnAccessFailureClassifier.refine(failure, emptyList()))
    }

    private fun coreLog(message: String) = DiagnosticLogLine(
        level = 2,
        message = message,
        receivedAtEpochMillis = 1L,
        source = DiagnosticLogSource.Core,
    )
}
