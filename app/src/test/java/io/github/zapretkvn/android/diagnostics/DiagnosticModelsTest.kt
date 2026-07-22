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
        assertEquals(
            DiagnosticErrorType.SystemDns,
            DiagnosticFailureClassifier.classify("Системный DNS не ответил вовремя"),
        )
        assertEquals(
            DiagnosticErrorType.VpnTraffic,
            DiagnosticFailureClassifier.classify(
                "HTTPS-проверка через VPN не прошла: истёк тайм-аут 5000 мс.",
            ),
        )
        assertEquals("VPN-200", DiagnosticErrorType.VpnTraffic.supportCode)
        assertEquals("DNS-100", DiagnosticErrorType.SystemDns.supportCode)
        assertEquals(
            DiagnosticErrorType.entries.size,
            DiagnosticErrorType.entries.map(DiagnosticErrorType::supportCode).distinct().size,
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

    @Test
    fun `startup log window preserves first and last evidence while history stays bounded`() {
        val startup = (0 until 60).fold(emptyList<DiagnosticLogLine>()) { lines, index ->
            lines.appendStartupWindow(
                DiagnosticLogLine(5, "startup-$index", index.toLong()),
                MAX_DIAGNOSTIC_STARTUP_LOG_LINES,
            )
        }
        val attempts = (1L..4L).map { generation ->
            DiagnosticConnectionAttempt(
                generation = generation,
                trigger = "attempt-$generation",
                startedAtEpochMillis = generation,
                startedAtElapsedRealtimeMillis = generation,
                outcome = DiagnosticAttemptOutcome.Connected,
                startupCoreLogs = startup,
            )
        }
        val state = DiagnosticState(
            previousConnectionAttempts = attempts.take(3),
            connectionAttempt = attempts.last(),
        )

        assertEquals(MAX_DIAGNOSTIC_STARTUP_LOG_LINES, startup.size)
        assertEquals("startup-0", startup.first().message)
        assertEquals("startup-19", startup[19].message)
        assertEquals("startup-40", startup[20].message)
        assertEquals("startup-59", startup.last().message)
        assertEquals(listOf(2L, 3L, 4L), state.recentConnectionAttempts.map { it.generation })
    }

    @Test
    fun `connection attempt exposes the slowest completed stage`() {
        val attempt = DiagnosticConnectionAttempt(
            generation = 7,
            trigger = "user_start",
            startedAtEpochMillis = 1_000,
            startedAtElapsedRealtimeMillis = 100,
            totalDurationMillis = 900,
            outcome = DiagnosticAttemptOutcome.Connected,
            stages = listOf(
                DiagnosticStageTiming(
                    key = "profile",
                    label = "Профиль",
                    startedAtEpochMillis = 1_000,
                    startedAtElapsedRealtimeMillis = 100,
                    durationMillis = 150,
                    status = DiagnosticStageStatus.Success,
                ),
                DiagnosticStageTiming(
                    key = "core_tun",
                    label = "Core и TUN",
                    startedAtEpochMillis = 1_150,
                    startedAtElapsedRealtimeMillis = 250,
                    durationMillis = 610,
                    status = DiagnosticStageStatus.Success,
                ),
                DiagnosticStageTiming(
                    key = "finalize",
                    label = "Финализация",
                    startedAtEpochMillis = 1_760,
                    startedAtElapsedRealtimeMillis = 860,
                ),
            ),
        )

        assertEquals("core_tun", attempt.slowestCompletedStage?.key)
        assertEquals(610L, attempt.slowestCompletedStage?.durationMillis)
    }
}
