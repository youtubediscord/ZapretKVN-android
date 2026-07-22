package io.github.zapretkvn.android.vpn

import android.net.Network
import io.github.zapretkvn.android.config.BootstrapConfig
import io.github.zapretkvn.android.config.BootstrapHostOverlay
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.ProxyBootstrapTarget
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.util.concurrent.ThreadLocalRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class HealthCheckResult(
    val externalIpProbeAllowed: Boolean,
)

enum class VpnHealthStage(
    val diagnosticKey: String,
    val diagnosticLabel: String,
) {
    AwaitVpnNetwork("vpn_network", "Ожидание VPN-сети Android"),
    DnsUdpProbe("dns_udp", "DNS через TUN (UDP)"),
    DnsTcpProbe("dns_tcp", "DNS через TUN (TCP fallback)"),
    DnsAndroidProbe("dns_android", "DNS через Android VPN network"),
    HttpsProbe("https_probe", "HTTPS-проверка через VPN"),
}

enum class VpnHealthStageOutcome {
    Success,
    Recovered,
    Failed,
}

data class PreparedBootstrap(
    val target: ProxyBootstrapTarget?,
    val addresses: List<InetAddress>,
    val resolvedAtEpochMillis: Long,
    val overlay: BootstrapHostOverlay? = null,
)

class ProxyBootstrapper(
    private val resolver: BootstrapResolver,
    private val cache: BootstrapCache,
) {
    suspend fun prepare(
        profileId: String,
        rawJson: String,
        underlying: Network,
        noCacheLookup: Boolean = false,
    ): PreparedBootstrap {
        val target = BootstrapConfig.target(rawJson) ?: return PreparedBootstrap(null, emptyList(), 0)
        val now = System.currentTimeMillis()
        if (!target.requiresDns) {
            val literal = numericAddresses(listOf(target.hostname))
            if (literal.isEmpty()) error("Некорректный IP-адрес VPN-сервера.")
            if (target.tcpPreflightSupported && firstReachable(underlying, target, literal) == null) {
                error("VPN-сервер не отвечает: ${target.hostname}:${target.port}.")
            }
            return PreparedBootstrap(target, literal, now)
        }
        val lkg = cache.find(profileId, target.hostname, now)
        val resolved = try {
            Result.success(resolver.resolve(underlying, target.hostname, noCacheLookup))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
        if (resolved.isSuccess) {
            val addresses = resolved.getOrThrow()
            if (!target.tcpPreflightSupported || firstReachable(underlying, target, addresses) != null) {
                return PreparedBootstrap(target, addresses, now)
            }
            if (target.staleAddressAllowed && lkg?.isFreshAt(now) == true) {
                val cached = numericAddresses(lkg.addresses)
                if (firstReachable(underlying, target, cached) != null) {
                    return PreparedBootstrap(target, cached, lkg.resolvedAtEpochMillis, lkg.overlay(target))
                }
            }
            error("VPN-сервер не отвечает: ${target.hostname}:${target.port}.")
        }

        if (target.staleAddressAllowed && lkg?.isUsableAt(now) == true) {
            val cached = numericAddresses(lkg.addresses)
            if (!target.tcpPreflightSupported || firstReachable(underlying, target, cached) != null) {
                return PreparedBootstrap(target, cached, lkg.resolvedAtEpochMillis, lkg.overlay(target))
            }
        }
        throw checkNotNull(resolved.exceptionOrNull())
    }

    suspend fun recordSuccess(profileId: String, prepared: PreparedBootstrap) {
        val target = prepared.target ?: return
        if (!target.staleAddressAllowed || prepared.addresses.isEmpty()) return
        cache.recordSuccess(
            profileId = profileId,
            hostname = target.hostname,
            addresses = prepared.addresses,
            resolvedAtEpochMillis = prepared.resolvedAtEpochMillis,
        )
    }

    private suspend fun firstReachable(
        network: Network,
        target: ProxyBootstrapTarget,
        addresses: List<InetAddress>,
    ): InetAddress? = withContext(Dispatchers.IO) {
        addresses.take(MAX_SOCKET_CANDIDATES).firstOrNull { address ->
            runCatching {
                network.socketFactory.createSocket().use { socket ->
                    socket.connect(InetSocketAddress(address, target.port), SOCKET_TIMEOUT_MILLIS)
                }
            }.isSuccess
        }
    }

    private fun numericAddresses(values: List<String>): List<InetAddress> = values.mapNotNull { value ->
        if (':' !in value && !IPV4.matches(value)) return@mapNotNull null
        runCatching { InetAddress.getByName(value) }.getOrNull()
    }

    private fun BootstrapCacheEntry.overlay(target: ProxyBootstrapTarget) = BootstrapHostOverlay(
        outboundTag = target.outboundTag,
        hostname = target.hostname,
        addresses = addresses,
    )

    private companion object {
        const val SOCKET_TIMEOUT_MILLIS = 1_500
        const val MAX_SOCKET_CANDIDATES = 3
        val IPV4 = Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")
    }
}

class VpnHealthPipeline(
    private val vpnNetworks: VpnNetworkProvider,
) {
    private val resolver = BootstrapResolver()

    suspend fun verify(
        mode: DnsMode,
        internalDnsServer: String,
        onStageStarted: (VpnHealthStage) -> Unit = {},
        onStageFinished: (VpnHealthStage, VpnHealthStageOutcome, String?) -> Unit = { _, _, _ -> },
    ): HealthCheckResult {
        onStageStarted(VpnHealthStage.AwaitVpnNetwork)
        if (VpnTestHooks.consumeHealthFailureOverride()) {
            onStageFinished(
                VpnHealthStage.AwaitVpnNetwork,
                VpnHealthStageOutcome.Failed,
                "test_override",
            )
            error("Тестовая ошибка health-check DNS/HTTPS.")
        }
        if (VpnTestHooks.consumeHealthSuccessOverride()) {
            onStageFinished(
                VpnHealthStage.AwaitVpnNetwork,
                VpnHealthStageOutcome.Success,
                "test_override",
            )
            return HealthCheckResult(externalIpProbeAllowed = false)
        }
        return withTimeout(HEALTH_TIMEOUT_MILLIS) {
            val vpnNetwork = try {
                vpnNetworks.awaitActive().also(vpnNetworks::requireActive)
            } catch (error: Throwable) {
                onStageFinished(
                    VpnHealthStage.AwaitVpnNetwork,
                    VpnHealthStageOutcome.Failed,
                    rootCauseName(error),
                )
                throw error
            }
            onStageFinished(
                VpnHealthStage.AwaitVpnNetwork,
                VpnHealthStageOutcome.Success,
                "active=true",
            )
            if (VpnTestHooks.consumeDnsProbeFailure()) {
                val failedStage = if (mode == DnsMode.Automatic || mode == DnsMode.Secure) {
                    VpnHealthStage.DnsUdpProbe
                } else {
                    VpnHealthStage.DnsAndroidProbe
                }
                onStageStarted(failedStage)
                onStageFinished(failedStage, VpnHealthStageOutcome.Failed, "test_override")
                error("DNS через VPN не отвечает: тестовый внутренний DNS недоступен.")
            }
            if (mode == DnsMode.Automatic || mode == DnsMode.Secure) {
                rawDnsProbe(internalDnsServer, HEALTH_HOST, onStageStarted, onStageFinished)
            } else {
                onStageStarted(VpnHealthStage.DnsAndroidProbe)
                try {
                    val addresses = resolver.resolve(vpnNetwork, HEALTH_HOST)
                    onStageFinished(
                        VpnHealthStage.DnsAndroidProbe,
                        VpnHealthStageOutcome.Success,
                        "answers=${addresses.size}",
                    )
                } catch (error: Throwable) {
                    onStageFinished(
                        VpnHealthStage.DnsAndroidProbe,
                        VpnHealthStageOutcome.Failed,
                        rootCauseName(error),
                    )
                    throw IllegalStateException(
                        "DNS через VPN не отвечает: ${error.message ?: error.javaClass.simpleName}.",
                        error,
                    )
                }
            }
            onStageStarted(VpnHealthStage.HttpsProbe)
            if (VpnTestHooks.consumeHttpsProbeFailure()) {
                onStageFinished(
                    VpnHealthStage.HttpsProbe,
                    VpnHealthStageOutcome.Failed,
                    "test_override",
                )
                error("HTTPS-проверка через VPN не прошла: тестовый endpoint недоступен.")
            }
            try {
                val status = httpsProbe(vpnNetwork)
                onStageFinished(
                    VpnHealthStage.HttpsProbe,
                    VpnHealthStageOutcome.Success,
                    "status=$status",
                )
            } catch (error: Throwable) {
                onStageFinished(
                    VpnHealthStage.HttpsProbe,
                    VpnHealthStageOutcome.Failed,
                    rootCauseName(error),
                )
                throw error
            }
            HealthCheckResult(externalIpProbeAllowed = true)
        }
    }

    private suspend fun rawDnsProbe(
        dnsServer: String,
        hostname: String,
        onStageStarted: (VpnHealthStage) -> Unit,
        onStageFinished: (VpnHealthStage, VpnHealthStageOutcome, String?) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            val query = dnsQuery(hostname)
            onStageStarted(VpnHealthStage.DnsUdpProbe)
            val udp = try {
                udpDns(dnsServer, query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onStageFinished(
                    VpnHealthStage.DnsUdpProbe,
                    VpnHealthStageOutcome.Recovered,
                    rootCauseName(error),
                )
                null
            }
            if (udp != null) {
                try {
                    val rcode = validateDnsResponse(query, udp)
                    onStageFinished(
                        VpnHealthStage.DnsUdpProbe,
                        VpnHealthStageOutcome.Success,
                        "response_bytes=${udp.size} rcode=$rcode",
                    )
                    return@withContext
                } catch (error: Throwable) {
                    onStageFinished(
                        VpnHealthStage.DnsUdpProbe,
                        VpnHealthStageOutcome.Failed,
                        rootCauseName(error),
                    )
                    throw error
                }
            }

            onStageStarted(VpnHealthStage.DnsTcpProbe)
            val tcp = try {
                tcpDns(dnsServer, query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onStageFinished(
                    VpnHealthStage.DnsTcpProbe,
                    VpnHealthStageOutcome.Failed,
                    rootCauseName(error),
                )
                throw IllegalStateException(
                    "DNS через VPN не отвечает: ${error.message ?: error.javaClass.simpleName}.",
                    error,
                )
            }
            try {
                val rcode = validateDnsResponse(query, tcp)
                onStageFinished(
                    VpnHealthStage.DnsTcpProbe,
                    VpnHealthStageOutcome.Success,
                    "response_bytes=${tcp.size} rcode=$rcode",
                )
            } catch (error: Throwable) {
                onStageFinished(
                    VpnHealthStage.DnsTcpProbe,
                    VpnHealthStageOutcome.Failed,
                    rootCauseName(error),
                )
                throw error
            }
        }

    private fun udpDns(dnsServer: String, query: ByteArray): ByteArray {
        DatagramSocket().use { socket ->
            socket.soTimeout = DNS_TIMEOUT_MILLIS
            val endpoint = InetSocketAddress(InetAddress.getByName(dnsServer), 53)
            socket.send(DatagramPacket(query, query.size, endpoint))
            val buffer = ByteArray(MAX_DNS_PACKET)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            return response.data.copyOf(response.length)
        }
    }

    private fun tcpDns(dnsServer: String, query: ByteArray): ByteArray {
        Socket().use { socket ->
            socket.soTimeout = DNS_TIMEOUT_MILLIS
            socket.connect(InetSocketAddress(InetAddress.getByName(dnsServer), 53), DNS_TIMEOUT_MILLIS)
            DataOutputStream(socket.getOutputStream()).apply {
                writeShort(query.size)
                write(query)
                flush()
            }
            val input = DataInputStream(socket.getInputStream())
            val size = input.readUnsignedShort()
            require(size in 12..MAX_DNS_PACKET) { "Некорректный размер DNS-ответа." }
            return ByteArray(size).also(input::readFully)
        }
    }

    private fun validateDnsResponse(query: ByteArray, response: ByteArray): Int {
        if (response.size < 12 || response[0] != query[0] || response[1] != query[1]) {
            error("DNS через VPN вернул некорректный ответ.")
        }
        val flags = ((response[2].toInt() and 0xff) shl 8) or (response[3].toInt() and 0xff)
        if (flags and 0x8000 == 0 || flags and 0x000f != 0) {
            error("DNS через VPN вернул ошибку ${flags and 0x000f}.")
        }
        return flags and 0x000f
    }

    private suspend fun httpsProbe(vpnNetwork: Network): Int = withContext(Dispatchers.IO) {
        val connection = vpnNetwork.openConnection(URL(HEALTH_URL)) as HttpsURLConnection
        try {
            connection.connectTimeout = HTTPS_TIMEOUT_MILLIS
            connection.readTimeout = HTTPS_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Connection", "close")
            connection.requestMethod = "GET"
            val status = connection.responseCode
            if (status != 204) throw UnexpectedHttpsStatus(status)
            status
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw IllegalStateException(
                "HTTPS-проверка через VPN не прошла: ${httpsFailureReason(error)}.",
                error,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun httpsFailureReason(error: Throwable): String {
        val cause = generateSequence(error) { it.cause }.last()
        return when (cause) {
            is UnexpectedHttpsStatus -> "тестовый узел вернул HTTP ${cause.status} вместо 204"
            is SocketTimeoutException -> "истёк тайм-аут ${HTTPS_TIMEOUT_MILLIS} мс"
            is UnknownHostException -> "Android не разрешил имя тестового узла"
            is ConnectException -> "TCP-соединение с тестовым узлом не установлено"
            is SSLException -> "ошибка TLS (${cause.javaClass.simpleName})"
            is IOException -> "ошибка ввода-вывода (${cause.javaClass.simpleName})"
            else -> cause.javaClass.simpleName.ifBlank { "неизвестная сетевая ошибка" }
        }
    }

    private fun rootCauseName(error: Throwable): String =
        generateSequence(error) { it.cause }
            .last()
            .javaClass
            .simpleName
            .take(80)

    private class UnexpectedHttpsStatus(val status: Int) : IOException()

    private fun dnsQuery(hostname: String): ByteArray {
        val id = ThreadLocalRandom.current().nextInt(0x10000)
        val labels = hostname.trimEnd('.').split('.')
        val size = 12 + labels.sumOf { 1 + it.encodeToByteArray().size } + 1 + 4
        val output = ByteArray(size)
        output[0] = (id ushr 8).toByte()
        output[1] = id.toByte()
        output[2] = 0x01
        output[5] = 0x01
        var offset = 12
        labels.forEach { label ->
            val bytes = label.encodeToByteArray()
            require(bytes.size in 1..63)
            output[offset++] = bytes.size.toByte()
            bytes.copyInto(output, offset)
            offset += bytes.size
        }
        output[offset++] = 0
        output[offset++] = 0
        output[offset++] = 1
        output[offset++] = 0
        output[offset] = 1
        return output
    }

    private companion object {
        const val HEALTH_HOST = "connectivitycheck.gstatic.com"
        const val HEALTH_URL = "https://connectivitycheck.gstatic.com/generate_204"
        const val HEALTH_TIMEOUT_MILLIS = 10_000L
        const val DNS_TIMEOUT_MILLIS = 2_500
        const val HTTPS_TIMEOUT_MILLIS = 5_000
        const val MAX_DNS_PACKET = 65_535
    }
}
