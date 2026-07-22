package io.github.zapretkvn.android.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import io.github.zapretkvn.android.BuildConfig
import io.github.zapretkvn.android.config.JsonConfig
import io.github.zapretkvn.android.ui.UiSettingsStore
import io.github.zapretkvn.android.vpn.DefaultNetworkMonitor
import io.github.zapretkvn.android.vpn.PrivateDnsMode
import io.github.zapretkvn.android.vpn.UnderlyingNetworkState
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.VpnController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DiagnosticExporter(
    context: Context,
    private val settingsStore: UiSettingsStore,
    private val vpnController: VpnController,
    private val crashStore: AppCrashStore,
) {
    private val appContext = context.applicationContext
    private val exportDirectory = File(appContext.cacheDir, DIRECTORY_NAME)
    private val exportMutex = Mutex()

    fun cleanupStaleFiles() {
        exportDirectory.listFiles().orEmpty().forEach { file ->
            if (file.isFile) file.delete()
        }
        exportDirectory.delete()
    }

    suspend fun createShareIntent(): Intent = exportMutex.withLock {
        val report = createReport()
        val file = withContext(Dispatchers.IO) {
            if (!exportDirectory.exists() && !exportDirectory.mkdirs()) {
                error("Не удалось создать временный каталог диагностики.")
            }
            val target = File(exportDirectory, FILE_NAME)
            try {
                target.writeText(report, Charsets.UTF_8)
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
            target
        }
        val uri = FileProvider.getUriForFile(
            appContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )
        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Zapret KVN — диагностика")
            clipData = ClipData.newRawUri("Zapret KVN diagnostic", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun createReport(): String {
        val settings = settingsStore.settings.first()
        val diagnostics = vpnController.diagnostics.value
        val network = readCurrentNetwork(diagnostics.network)
        val now = System.currentTimeMillis()
        val root = buildJsonObject {
            put("report_version", 2)
            put("created_at", isoTimestamp(now))
            put("created_at_epoch_ms", now)
            put(
                "app",
                buildJsonObject {
                    put("version_name", BuildConfig.VERSION_NAME)
                    put("version_code", BuildConfig.VERSION_CODE)
                },
            )
            put(
                "core",
                buildJsonObject {
                    put("tag", BuildConfig.CORE_TAG)
                    put("revision", BuildConfig.CORE_COMMIT)
                },
            )
            put(
                "android",
                buildJsonObject {
                    put("release", Build.VERSION.RELEASE.orEmpty())
                    put("api", Build.VERSION.SDK_INT)
                },
            )
            put("vpn", vpnStateJson(vpnController.state.value))
            put(
                "vpn_system_policy",
                buildJsonObject {
                    val policy = diagnostics.vpnPolicy
                    put("status_available", policy?.statusAvailable == true)
                    put("always_on", policy?.alwaysOn == true)
                    put("lockdown", policy?.lockdown == true)
                    put("supported_by_app", false)
                },
            )
            put("network", networkJson(network))
            put(
                "dns",
                buildJsonObject {
                    put("mode", settings.dnsMode.name)
                    put("proxy_ipv4_only", settings.proxyIpv4Only)
                    put("private_dns_mode", network.privateDnsMode)
                    put("private_dns_active", network.privateDnsActive)
                },
            )
            put("last_error", failureJson(diagnostics.lastFailure))
            put(
                "connection_attempt",
                diagnostics.connectionAttempt?.let(::connectionAttemptJson) ?: JsonNull,
            )
            put("previous_crash", crashJson(crashStore.read()))
            put(
                "effective_overlay",
                diagnostics.effectiveOverlay
                    ?.let { runCatching { JsonConfig.parse(it) }.getOrNull() }
                    ?: JsonNull,
            )
            put(
                "logs",
                buildJsonArray {
                    diagnostics.logs.forEach { line ->
                        add(
                            buildJsonObject {
                                put("received_at_epoch_ms", line.receivedAtEpochMillis)
                                put("level", line.levelName)
                                put("message", line.message)
                            },
                        )
                    }
                },
            )
            put(
                "privacy",
                buildJsonObject {
                    put("raw_profile_included", false)
                    put("credentials_included", false)
                    put("installed_packages_included", false)
                    put("external_ip_included", false)
                },
            )
        }
        return DiagnosticReportRedactor.redact(JsonConfig.format(root))
    }

    private suspend fun readCurrentNetwork(fallback: DiagnosticNetworkState?): DiagnosticNetworkState {
        val monitor = DefaultNetworkMonitor(appContext)
        return try {
            monitor.start()
            monitor.awaitUnderlying(NETWORK_TIMEOUT_MILLIS).toDiagnosticState()
        } catch (_: TimeoutCancellationException) {
            fallback ?: DiagnosticNetworkState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            fallback ?: DiagnosticNetworkState()
        } finally {
            monitor.close()
        }
    }

    private fun vpnStateJson(state: VpnConnectionState): JsonObject = buildJsonObject {
        put(
            "state",
            when (state) {
                VpnConnectionState.Stopped -> "stopped"
                is VpnConnectionState.Starting -> "starting"
                is VpnConnectionState.Connected -> "connected"
                is VpnConnectionState.Stopping -> "stopping"
                is VpnConnectionState.Error -> "error"
            },
        )
        if (state is VpnConnectionState.Connected) {
            put("connected_at_epoch_ms", state.connectedAtEpochMillis)
        }
    }

    private fun networkJson(network: DiagnosticNetworkState): JsonObject = buildJsonObject {
        put("available", network.available)
        put("transport", network.transport)
        network.interfaceName?.let { put("interface", it) }
        put("metered", network.metered)
        put("validated", network.validated)
        put("captive_portal", network.captivePortal)
    }

    private fun failureJson(failure: DiagnosticFailure?): JsonObject = buildJsonObject {
        put("present", failure != null)
        failure?.let {
            put("type", it.type.code)
            put("title", it.type.title)
            put("support_code", it.supportCode)
            put("message", it.message)
            it.technicalDetail?.let { detail -> put("technical_detail", detail) }
            put("occurred_at_epoch_ms", it.occurredAtEpochMillis)
        }
    }

    private fun connectionAttemptJson(attempt: DiagnosticConnectionAttempt): JsonObject =
        buildJsonObject {
            put("generation", attempt.generation)
            put("trigger", attempt.trigger)
            put("started_at_epoch_ms", attempt.startedAtEpochMillis)
            put("outcome", attempt.outcome.code)
            attempt.totalDurationMillis?.let { put("total_duration_ms", it) }
            attempt.slowestCompletedStage?.let { slowest ->
                put(
                    "slowest_stage",
                    buildJsonObject {
                        put("key", slowest.key)
                        put("label", slowest.label)
                        put("duration_ms", checkNotNull(slowest.durationMillis))
                    },
                )
            }
            put(
                "stages",
                buildJsonArray {
                    attempt.stages.forEach { stage ->
                        add(
                            buildJsonObject {
                                put("key", stage.key)
                                put("label", stage.label)
                                put("started_at_epoch_ms", stage.startedAtEpochMillis)
                                stage.durationMillis?.let { put("duration_ms", it) }
                                put("status", stage.status.code)
                            },
                        )
                    }
                },
            )
        }

    private fun crashJson(crash: AppCrashRecord?): JsonObject = buildJsonObject {
        put("present", crash != null)
        crash?.let { record ->
            put("occurred_at_epoch_ms", record.occurredAtEpochMillis)
            put("thread", record.threadName)
            put("exception", record.exceptionType)
            record.message?.let { put("message", it) }
            put("causes", buildJsonArray { record.causes.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put(
                "stack",
                buildJsonArray {
                    record.stack.forEach { frame ->
                        add(
                            buildJsonObject {
                                put("class", frame.className)
                                put("method", frame.methodName)
                                put("line", frame.lineNumber)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun isoTimestamp(epochMillis: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(epochMillis))

    private fun UnderlyingNetworkState.toDiagnosticState() = DiagnosticNetworkState(
        available = network != null,
        transport = transport,
        interfaceName = interfaceName,
        metered = metered,
        validated = validated,
        captivePortal = captivePortal,
        privateDnsMode = when (privateDnsMode) {
            PrivateDnsMode.Off -> "off"
            PrivateDnsMode.Automatic -> "automatic"
            PrivateDnsMode.Strict -> "strict"
        },
        privateDnsActive = privateDnsActive,
    )

    companion object {
        const val DIRECTORY_NAME = "diagnostics"
        const val FILE_NAME = "zapret-kvn-diagnostic.json"
        private const val NETWORK_TIMEOUT_MILLIS = 2_000L
    }
}
