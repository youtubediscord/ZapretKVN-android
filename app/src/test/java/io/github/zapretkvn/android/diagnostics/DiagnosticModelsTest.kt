package io.github.zapretkvn.android.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticModelsTest {
    @Test
    fun `failure classifier exposes a short stable type`() {
        assertEquals(
            DiagnosticErrorType.PrivateDns,
            DiagnosticFailureClassifier.classify("Strict Private DNS не отвечает"),
        )
        assertEquals(
            DiagnosticErrorType.VpnDns,
            DiagnosticFailureClassifier.classify("DNS через VPN заблокирован"),
        )
        assertEquals(
            DiagnosticErrorType.CaptivePortal,
            DiagnosticFailureClassifier.classify("Интернет требует авторизации в Wi-Fi"),
        )
        assertEquals(
            DiagnosticErrorType.Permission,
            DiagnosticFailureClassifier.classify("Android Always-on/Lockdown не поддерживается"),
        )
    }

    @Test
    fun `visible log view is bounded to newest eighty lines`() {
        val application = (0 until 60).map { DiagnosticLogLine(5, "app-$it", it.toLong()) }
        val core = (60 until 120).map { DiagnosticLogLine(3, "core-$it", it.toLong()) }

        val logs = DiagnosticState(applicationLogs = application, coreLogs = core).logs

        assertEquals(MAX_DIAGNOSTIC_LOG_LINES, logs.size)
        assertFalse(logs.any { it.message == "app-39" })
        assertTrue(logs.any { it.message == "app-40" })
        assertEquals("core-119", logs.last().message)
    }

    @Test
    fun `individual ring also drops its oldest lines`() {
        val result = (0 until 100).fold(emptyList<DiagnosticLogLine>()) { lines, index ->
            lines.appendBounded(DiagnosticLogLine(5, "line-$index", index.toLong()))
        }

        assertEquals(MAX_DIAGNOSTIC_LOG_LINES, result.size)
        assertEquals("line-20", result.first().message)
        assertEquals("line-99", result.last().message)
    }
}
