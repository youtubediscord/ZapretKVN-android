package io.github.zapretkvn.android.vpn

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.annotation.TargetApi
import android.system.OsConstants
import android.system.ErrnoException
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.Func
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asExecutor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BootstrapResolver {
    suspend fun resolve(
        network: Network,
        hostname: String,
        noCacheLookup: Boolean = false,
    ): List<InetAddress> {
        require(hostname.isNotBlank()) { "Пустое имя сервера." }
        if (VpnTestHooks.consumeBootstrapResolutionFailure()) {
            throw UnknownHostException("Тестовая недоступность системного DNS: $hostname")
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolveModern(network, hostname, noCacheLookup)
        } else {
            withContext(Dispatchers.IO) { network.getAllByName(hostname).distinctBy(InetAddress::getHostAddress) }
        }.ifEmpty { throw UnknownHostException(hostname) }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private suspend fun resolveModern(
        network: Network,
        hostname: String,
        noCacheLookup: Boolean,
    ): List<InetAddress> = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        val flags = if (noCacheLookup) DnsResolver.FLAG_NO_CACHE_LOOKUP else DnsResolver.FLAG_EMPTY
        DnsResolver.getInstance().query(
            network,
            hostname,
            flags,
            DIRECT_EXECUTOR,
            cancellation,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    if (!continuation.isActive) return
                    if (rcode == 0 && answer.isNotEmpty()) continuation.resume(answer.distinctBy(InetAddress::getHostAddress))
                    else continuation.resumeWithException(UnknownHostException("$hostname (DNS rcode $rcode)"))
                }

                override fun onError(error: DnsResolver.DnsException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            },
        )
        continuation.invokeOnCancellation { cancellation.cancel() }
    }

    private companion object {
        val DIRECT_EXECUTOR = Executor(Runnable::run)
    }
}

/** Implements the exact LocalDNSTransport ABI of the pinned libbox AAR. */
internal class AndroidLocalDnsTransport(
    private val networkProvider: () -> Network?,
) : LocalDNSTransport {
    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun lookup(context: ExchangeContext, network: String, domain: String) {
        val underlying = networkProvider() ?: error("Нет underlying network для Android DNS.")
        val completed = AtomicBoolean(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cancellation = CancellationSignal()
            val latch = CountDownLatch(1)
            context.onCancel(
                Func {
                    if (completed.compareAndSet(false, true)) {
                        cancellation.cancel()
                        latch.countDown()
                    }
                },
            )
            val type = if (network == "ip4") DnsResolver.TYPE_A else DnsResolver.TYPE_AAAA
            DnsResolver.getInstance().query(
                underlying,
                domain,
                type,
                DnsResolver.FLAG_NO_RETRY,
                DNS_EXECUTOR,
                cancellation,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        if (!completed.compareAndSet(false, true)) return
                        try {
                            if (rcode != 0) context.errorCode(rcode)
                            else context.success(answer.joinToString("\n") { it.hostAddress.orEmpty() })
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        if (!completed.compareAndSet(false, true)) return
                        try {
                            context.errnoCode((error.cause as? ErrnoException)?.errno ?: OsConstants.EIO)
                        } finally {
                            latch.countDown()
                        }
                    }
                },
            )
            latch.await()
        } else {
            val addresses = underlying.getAllByName(domain).filter { address ->
                (network == "ip4" && address is Inet4Address) ||
                    (network == "ip6" && address is Inet6Address)
            }
            context.success(addresses.joinToString("\n") { it.hostAddress.orEmpty() })
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    override fun exchange(context: ExchangeContext, message: ByteArray) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val underlying = networkProvider() ?: error("Нет underlying network для Android DNS.")
        val cancellation = CancellationSignal()
        val completed = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        context.onCancel(
            Func {
                if (completed.compareAndSet(false, true)) {
                    cancellation.cancel()
                    latch.countDown()
                }
            },
        )
        DnsResolver.getInstance().rawQuery(
            underlying,
            message,
            DnsResolver.FLAG_NO_RETRY,
            DNS_EXECUTOR,
            cancellation,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (!completed.compareAndSet(false, true)) return
                    try {
                        if (rcode != 0) context.errorCode(rcode) else context.rawSuccess(answer)
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onError(error: DnsResolver.DnsException) {
                    if (!completed.compareAndSet(false, true)) return
                    try {
                        context.errnoCode((error.cause as? ErrnoException)?.errno ?: OsConstants.EIO)
                    } finally {
                        latch.countDown()
                    }
                }
            },
        )
        latch.await()
    }

    private companion object {
        val DNS_EXECUTOR = Dispatchers.IO.asExecutor()
    }
}
