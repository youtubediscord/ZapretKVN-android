package io.github.zapretkvn.android.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

enum class PrivateDnsMode {
    Off,
    Automatic,
    Strict,
}

internal object PrivateDnsClassifier {
    fun classify(apiLevel: Int, active: Boolean, serverName: String?): PrivateDnsMode = when {
        apiLevel < Build.VERSION_CODES.P -> PrivateDnsMode.Off
        !serverName.isNullOrBlank() -> PrivateDnsMode.Strict
        active -> PrivateDnsMode.Automatic
        else -> PrivateDnsMode.Off
    }
}

data class UnderlyingNetworkState(
    val network: Network? = null,
    val transport: String = "other",
    val interfaceName: String? = null,
    val interfaceIndex: Int = -1,
    val metered: Boolean = false,
    val validated: Boolean = false,
    val captivePortal: Boolean = false,
    val privateDnsMode: PrivateDnsMode = PrivateDnsMode.Off,
    val privateDnsActive: Boolean = false,
    val privateDnsServerName: String? = null,
) {
    val identity: String?
        get() = network?.let { "${it.networkHandle}:${interfaceName.orEmpty()}" }
}

/** One callback per VPN session. It never accepts the VPN network as its underlying network. */
class DefaultNetworkMonitor(context: Context) : AutoCloseable {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<(UnderlyingNetworkState) -> Unit>()
    private val candidates = ConcurrentHashMap<Network, Candidate>()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    var current: UnderlyingNetworkState = UnderlyingNetworkState()
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update(network)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            update(network, capabilities = capabilities)

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            update(network, linkProperties = linkProperties)

        override fun onLost(network: Network) {
            candidates.remove(network)
            publish(selectBestCandidate()?.state ?: UnderlyingNetworkState())
        }
    }

    fun start() {
        check(!closed.get()) { "Наблюдение за сетью уже закрыто." }
        if (!started.compareAndSet(false, true)) return
        registerCallback()
        VpnRuntimeMetrics.callbackOpened()
        @Suppress("DEPRECATION")
        connectivity.allNetworks.forEach(::update)
    }

    fun observe(observer: (UnderlyingNetworkState) -> Unit): AutoCloseable {
        check(!closed.get()) { "Наблюдение за сетью уже закрыто." }
        observers += observer
        observer(current)
        return AutoCloseable { observers -= observer }
    }

    suspend fun awaitUnderlying(timeoutMillis: Long = 5_000): UnderlyingNetworkState =
        withTimeout(timeoutMillis) {
            current.takeIf { it.network != null } ?: suspendCancellableCoroutine { continuation ->
                var registration: AutoCloseable? = null
                registration = observe { state ->
                    if (state.network != null && continuation.isActive) {
                        registration?.close()
                        continuation.resume(state)
                    }
                }
                if (!continuation.isActive) registration.close()
                continuation.invokeOnCancellation { registration.close() }
            }
        }

    @SuppressLint("MissingPermission")
    private fun registerCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
            else -> connectivity.registerNetworkCallback(request, callback, handler)
        }
    }

    private fun update(
        network: Network,
        capabilities: NetworkCapabilities? = connectivity.getNetworkCapabilities(network),
        linkProperties: LinkProperties? = connectivity.getLinkProperties(network),
    ) {
        if (closed.get() || capabilities == null || linkProperties == null) return
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return
        val interfaceName = linkProperties.interfaceName ?: return
        val interfaceIndex = runCatching {
            NetworkInterface.getByName(interfaceName)?.index ?: -1
        }.getOrDefault(-1)
        if (interfaceIndex < 0) return
        val privateDnsName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            linkProperties.privateDnsServerName?.trim()?.takeIf(String::isNotEmpty)
        } else {
            null
        }
        val privateDnsActive =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && linkProperties.isPrivateDnsActive
        val privateDnsMode = PrivateDnsClassifier.classify(
            apiLevel = Build.VERSION.SDK_INT,
            active = privateDnsActive,
            serverName = privateDnsName,
        )
        candidates[network] = Candidate(
            state = UnderlyingNetworkState(
                network = network,
                transport = transportName(capabilities),
                interfaceName = interfaceName,
                interfaceIndex = interfaceIndex,
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                captivePortal = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                privateDnsMode = privateDnsMode,
                privateDnsActive = privateDnsActive,
                privateDnsServerName = privateDnsName,
            ),
            score = candidateScore(capabilities),
        )
        publish(selectBestCandidate()?.state ?: UnderlyingNetworkState())
    }

    private fun selectBestCandidate(): Candidate? = candidates.values.maxWithOrNull(
        compareBy<Candidate> { it.score }
            .thenBy { it.state.network?.networkHandle ?: Long.MIN_VALUE },
    )

    private fun candidateScore(capabilities: NetworkCapabilities): Int {
        var score = 0
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 1_000
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) score += 100
        score += when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 40
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 30
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 20
            else -> 10
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) score += 1
        return score
    }

    private fun transportName(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        else -> "other"
    }

    private fun publish(next: UnderlyingNetworkState) {
        if (next == current) return
        current = next
        observers.forEach { observer -> observer(next) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        observers.clear()
        candidates.clear()
        if (started.compareAndSet(true, false)) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            VpnRuntimeMetrics.callbackClosed()
        }
        current = UnderlyingNetworkState()
    }

    private data class Candidate(
        val state: UnderlyingNetworkState,
        val score: Int,
    )
}
