package io.github.zapretkvn.android.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.R
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.config.OutboundDescription
import io.github.zapretkvn.android.config.RuntimeConfigOptions
import io.github.zapretkvn.android.config.RuntimeConfigBuilder
import io.github.zapretkvn.android.config.SelectorGroup
import io.github.zapretkvn.android.diagnostics.EffectiveOverlaySummary
import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.config.RuntimeConfigResult
import io.github.zapretkvn.android.hardening.VpnRuntimeHardening
import io.github.zapretkvn.android.routing.RoutingConfigEditor
import io.github.zapretkvn.networkbootstrap.CodedFailure
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class UnderlyingPolicyKey(
    val identity: String?,
    val captivePortal: Boolean,
    val strictPrivateDns: Boolean,
    val strictPrivateDnsServerName: String?,
    val strictPrivateDnsReady: Boolean,
)

private fun UnderlyingNetworkState.policyKey() = UnderlyingPolicyKey(
    identity = identity,
    captivePortal = captivePortal,
    strictPrivateDns = privateDnsMode == PrivateDnsMode.Strict,
    strictPrivateDnsServerName = privateDnsServerName.takeIf { privateDnsMode == PrivateDnsMode.Strict },
    strictPrivateDnsReady = privateDnsMode != PrivateDnsMode.Strict || (privateDnsActive && validated),
)

class ZapretVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceLock = Mutex()
    private val foregroundActive = AtomicBoolean(false)

    private val container by lazy { (application as ZapretApplication).container }
    private val controller by lazy { container.vpnController }
    private var activeSession: ActiveSession? = null
    private var terminalError = false
    private val restartScheduleLock = Any()
    private var networkRestartJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundActive.get()) showForeground(ForegroundNotificationState.Preparing)
        when (intent?.action) {
            ACTION_START -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
                requestStart(profileId, startId)
            }
            ACTION_SELECT -> requestSelect(
                profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty(),
                groupTag = intent.getStringExtra(EXTRA_GROUP_TAG).orEmpty(),
                outboundTag = intent.getStringExtra(EXTRA_OUTBOUND_TAG).orEmpty(),
                startId = startId,
            )
            ACTION_RESTART -> requestRestart(
                profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty(),
                reason = intent.getStringExtra(EXTRA_REASON).orEmpty().ifBlank { "Перезапуск VPN" },
                startId = startId,
                noCacheLookup = false,
            )
            ACTION_CLEAR_DNS_CACHE -> requestClearDnsCache(startId)
            ACTION_PING -> requestPing(startId)
            ACTION_PING_GROUP -> requestGroupPing(
                profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty(),
                groupTag = intent.getStringExtra(EXTRA_GROUP_TAG).orEmpty(),
                startId = startId,
            )
            ACTION_STOP -> requestStop(startId, null)
            else -> {
                val policy = VpnSystemPolicyDetector.detect(this)
                requestStop(startId, policy.blockingMessage, policy)
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        cancelScheduledNetworkRestart()
        controller.cancelCurrentConnectionDiagnostic()
        val token = controller.nextGeneration()
        terminalError = true
        controller.publish(token, VpnConnectionState.Stopping(activeSession?.profileId))
        runBlocking(Dispatchers.IO) {
            serviceLock.withLock {
                activeSession?.close()
                activeSession = null
            }
        }
        finishForeground()
        stopSelf()
        controller.publish(token, VpnConnectionState.Error("Разрешение Android VPN отозвано."))
        super.onRevoke()
    }

    override fun onDestroy() {
        cancelScheduledNetworkRestart()
        controller.cancelCurrentConnectionDiagnostic()
        runBlocking {
            serviceLock.withLock {
                activeSession?.close()
                activeSession = null
            }
        }
        serviceScope.cancel()
        finishForeground()
        if (!terminalError) {
            controller.publish(controller.currentGeneration(), VpnConnectionState.Stopped)
        }
        super.onDestroy()
    }

    private fun requestStart(profileId: String, startId: Int) {
        val token = controller.nextGeneration()
        terminalError = false
        controller.beginConnectionDiagnostic(token, "user_start")
        controller.startConnectionDiagnosticStage(token, "profile", "Профиль и область приложений")
        controller.publish(token, VpnConnectionState.Starting(profileId, "Проверка профиля"))
        showForeground(ForegroundNotificationState.ValidatingProfile)
        serviceScope.launch {
            serviceLock.withLock {
                activeSession?.close()
                activeSession = null
                if (token != controller.currentGeneration()) return@withLock
                try {
                    startLocked(token, profileId)
                } catch (error: Throwable) {
                    if (token == controller.currentGeneration()) {
                        failLocked(token, error, startId)
                    }
                }
            }
        }
    }

    private suspend fun startLocked(
        token: Long,
        profileId: String,
        noCacheLookup: Boolean = false,
    ) {
        require(profileId.isNotBlank()) { "Профиль не выбран." }
        val systemPolicy = VpnSystemPolicyDetector.detect(this)
        controller.publishVpnSystemPolicy(token, systemPolicy)
        systemPolicy.blockingMessage?.let(::error)
        container.libboxRuntime.initialize().getOrThrow()
        container.profileStore.initialize()
        var profile = container.profileStore.read(profileId)
        if (RoutingConfigEditor.usesManagedLocalRuleSets(profile.json)) {
            val installed = container.ruleSetAssetManager.ensureInstalled()
            val rebound = RoutingConfigEditor.rebindManagedRuleSetPaths(profile.json, installed)
            if (rebound != profile.json) {
                container.profileStore.update(profileId, rebound)
                profile = container.profileStore.read(profileId)
            }
        }
        val uiSettings = container.uiSettingsStore.settings.first()
        val dnsMode = uiSettings.dnsMode
        val vpnHiding = uiSettings.vpnHiding
        val appSelection = container.appSelectionStore.selection.first()
        val selectedPackages = appSelection.allowedPackages
        val preflight = container.vpnAppScopePreflight.apply(
            selectedPackages = selectedPackages,
            mode = appSelection.mode,
            allowedSink = AllowedApplicationSink { },
            disallowedSink = DisallowedApplicationSink { },
        )
        val effectivePackages = when (preflight) {
            is VpnAppScopeResult.Ready -> {
                if (preflight.skippedPackages.isNotEmpty()) {
                    controller.publishDiagnosticWarning(
                        "Пропущены недоступные приложения: " +
                            preflight.skippedPackages.joinToString(),
                    )
                }
                preflight.effectivePackages
            }
            VpnAppScopeResult.EmptyAllowlist -> error(
                if (appSelection.mode == AppScopeMode.Include) {
                    "Выберите хотя бы одно приложение для VPN."
                } else {
                    "Выберите хотя бы одно приложение для прямого доступа вне VPN; пустой список заблокирован."
                },
            )
            is VpnAppScopeResult.MissingApplications -> error(
                "Не осталось доступных выбранных приложений. Выберите хотя бы одно.",
            )
            is VpnAppScopeResult.BuilderFailure -> error(
                "Android отклонил приложение ${preflight.packageName}: ${preflight.reason}",
            )
        }

        controller.startConnectionDiagnosticStage(token, "android_network", "Сеть и политика Android")
        controller.publish(token, VpnConnectionState.Starting(profileId, "Проверка сети Android"))
        showForeground(ForegroundNotificationState.CheckingNetwork)
        val networkMonitor = DefaultNetworkMonitor(this).also(DefaultNetworkMonitor::start)
        controller.startConnectionDiagnosticStage(token, "bootstrap", "Bootstrap DNS и доступность сервера")
        val networkBootstrap = try {
            networkMonitor.runOnStableNetwork { candidate ->
                val underlying = if (VpnTestHooks.consumeCaptivePortalOverride()) {
                    candidate.copy(captivePortal = true, validated = false)
                } else {
                    candidate
                }
                controller.publishDiagnosticNetwork(token, underlying)
                if (underlying.captivePortal) {
                    error("Интернет требует авторизации в Wi-Fi.")
                }
                if (underlying.privateDnsMode == PrivateDnsMode.Strict &&
                    (dnsMode == DnsMode.Automatic || dnsMode == DnsMode.Secure)
                ) {
                    error("Strict Private DNS несовместим с этим режимом. Выберите «DNS Android» или «Из JSON».")
                }
                if (underlying.privateDnsMode == PrivateDnsMode.Strict &&
                    dnsMode == DnsMode.Android &&
                    (!underlying.privateDnsActive || !underlying.validated)
                ) {
                    error("Strict Private DNS не отвечает. Исправьте системную настройку или выберите «Из JSON».")
                }
                container.proxyBootstrapper.prepare(
                    profileId = profileId,
                    rawJson = profile.json,
                    underlying = checkNotNull(underlying.network),
                    noCacheLookup = noCacheLookup,
                )
            }
        } catch (error: Throwable) {
            networkMonitor.close()
            throw error
        }
        val underlying = networkBootstrap.network
        val preparedBootstrap = networkBootstrap.value

        controller.startConnectionDiagnosticStage(token, "runtime_config", "Runtime overlay")
        val runtimeJson = try {
            when (
                val runtime = RuntimeConfigBuilder.build(
                    profile.json,
                    enableTrafficStats = true,
                    options = RuntimeConfigOptions(
                        dnsMode = dnsMode,
                        proxyIpv4Only = uiSettings.proxyIpv4Only,
                        bootstrapHost = preparedBootstrap.overlay,
                        vpnHiding = vpnHiding,
                    ),
                )
            ) {
                is RuntimeConfigResult.Ready -> runtime.json
                is RuntimeConfigResult.Invalid -> error(runtime.message)
            }
        } catch (error: Throwable) {
            networkMonitor.close()
            throw error
        }
        controller.publishEffectiveOverlay(
            token,
            EffectiveOverlaySummary.create(runtimeJson, dnsMode),
        )
        controller.startConnectionDiagnosticStage(token, "check_config", "Проверка конфигурации ядром")
        controller.publish(token, VpnConnectionState.Starting(profileId, "Проверка sing-box"))
        showForeground(ForegroundNotificationState.ValidatingCore)
        try {
            Libbox.checkConfig(runtimeJson)
            check(token == controller.currentGeneration()) { "Запуск отменён." }
        } catch (error: Throwable) {
            networkMonitor.close()
            throw error
        }

        controller.startConnectionDiagnosticStage(token, "core_tun", "Запуск core и создание TUN")
        controller.publish(token, VpnConnectionState.Starting(profileId, "Создание TUN"))
        showForeground(ForegroundNotificationState.CreatingTun)
        val resources = ActiveSession(
            profileId = profileId,
            profileName = profile.metadata.name,
            generation = token,
            networkMonitor = networkMonitor,
            networkPolicyKey = underlying.policyKey(),
            outboundDescriptions = ConfigAnalyzer.outboundDescriptions(profile.json),
            selectorGroups = ConfigAnalyzer.selectorGroups(profile.json),
            controller = controller,
        )
        try {
            resources.platform = AndroidPlatformAdapter(
                service = this,
                selectedPackages = selectedPackages,
                scopeMode = appSelection.mode,
                expectedPackages = effectivePackages,
                scopePreflight = container.vpnAppScopePreflight,
                networkMonitor = networkMonitor,
                sessionName = VpnRuntimeHardening.sessionName(vpnHiding),
            )
            resources.server = Libbox.newCommandServer(
                ServerHandler(this),
                resources.platform,
            ).also(CommandServer::start)
            resources.server?.startOrReloadService(
                runtimeJson,
                OverrideOptions().apply {
                    includePackage = ListStringIterator(
                        if (appSelection.mode == AppScopeMode.Include) effectivePackages else emptyList(),
                    )
                    excludePackage = ListStringIterator(
                        if (appSelection.mode == AppScopeMode.Exclude) effectivePackages else emptyList(),
                    )
                    autoRedirect = false
                },
            )
            resources.markLibboxStarted()
            check(token == controller.currentGeneration()) { "Запуск отменён." }

            resources.client = Libbox.newCommandClient(
                GroupClientHandler(
                    controller,
                    token,
                    resources.outboundDescriptions,
                ),
                CommandClientOptions().apply {
                    addCommand(Libbox.CommandGroup)
                },
            ).also(CommandClient::connect)
            check(token == controller.currentGeneration()) { "Запуск отменён." }
            controller.publish(token, VpnConnectionState.Starting(profileId, "Проверка DNS и HTTPS"))
            showForeground(ForegroundNotificationState.CheckingHealth)
            val dnsServer = resources.platform?.internalDnsServer
                ?: error("libbox не передал внутренний DNS TUN.")
            val health = container.vpnHealthPipeline.verify(dnsMode, dnsServer) { stage ->
                controller.startConnectionDiagnosticStage(
                    token,
                    stage.diagnosticKey,
                    stage.diagnosticLabel,
                )
            }
            check(token == controller.currentGeneration()) { "Запуск отменён." }
            controller.startConnectionDiagnosticStage(token, "finalize", "Финализация сессии")
            container.proxyBootstrapper.recordSuccess(profileId, preparedBootstrap)
            activeSession = resources
            resources.networkObserver = networkMonitor.observe { state ->
                onUnderlyingNetworkEvent(resources, state)
            }
            controller.publish(
                token,
                VpnConnectionState.Connected(
                    profileId = profileId,
                    profileName = profile.metadata.name,
                    connectedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            startHomeStatusObserver(resources)
            startDiagnosticsObserver(resources)
            if (health.externalIpProbeAllowed) startConnectionIdentityProbe(resources)
        } catch (error: Throwable) {
            resources.close()
            throw error
        }

            showForeground(ForegroundNotificationState.Connected)
    }

    private fun requestRestart(
        profileId: String,
        reason: String,
        startId: Int,
        noCacheLookup: Boolean,
    ) {
        val token = controller.nextGeneration()
        serviceScope.launch {
            serviceLock.withLock {
                if (token != controller.currentGeneration()) return@withLock
                val targetProfile = activeSession?.profileId ?: profileId
                if (targetProfile.isBlank()) {
                    controller.publishMessage("VPN выключен; перезапуск не требуется.")
                    finishForeground()
                    stopSelfResult(startId)
                    return@withLock
                }
                terminalError = false
                controller.beginConnectionDiagnostic(token, "restart")
                controller.startConnectionDiagnosticStage(
                    token,
                    "profile",
                    "Профиль и область приложений",
                )
                controller.publish(token, VpnConnectionState.Starting(targetProfile, reason))
                showForeground(ForegroundNotificationState.Restarting)
                activeSession?.close()
                activeSession = null
                try {
                    startLocked(token, targetProfile, noCacheLookup)
                } catch (error: Throwable) {
                    if (token == controller.currentGeneration()) failLocked(token, error, startId)
                }
            }
        }
    }

    private fun requestClearDnsCache(startId: Int) {
        serviceScope.launch {
            container.bootstrapCache.clear()
            val profileId = serviceLock.withLock { activeSession?.profileId.orEmpty() }
            if (profileId.isBlank()) {
                controller.publishMessage("Bootstrap cache очищен; системный DNS-кэш Android не изменён.")
                finishForeground()
                stopSelfResult(startId)
            } else {
                requestRestart(
                    profileId = profileId,
                    reason = "Сброс DNS-состояния",
                    startId = startId,
                    noCacheLookup = true,
                )
            }
        }
    }

    private fun onUnderlyingNetworkEvent(session: ActiveSession, state: UnderlyingNetworkState) {
        if (activeSession !== session) return
        controller.publishDiagnosticNetwork(session.generation, state)
        val nextPolicyKey = state.policyKey()
        if (nextPolicyKey == session.networkPolicyKey) return
        session.networkPolicyKey = nextPolicyKey
        synchronized(restartScheduleLock) {
            networkRestartJob?.cancel()
            networkRestartJob = serviceScope.launch {
                delay(NETWORK_RESTART_DEBOUNCE_MILLIS)
                val current = activeSession
                if (current !== session || current.generation != controller.currentGeneration()) return@launch
                requestRestart(
                    profileId = current.profileId,
                    reason = "Смена сети Android",
                    startId = 0,
                    noCacheLookup = false,
                )
            }
        }
    }

    private fun requestStop(
        startId: Int,
        errorMessage: String?,
        systemPolicy: VpnSystemPolicy? = null,
    ) {
        cancelScheduledNetworkRestart()
        controller.cancelCurrentConnectionDiagnostic()
        val token = controller.nextGeneration()
        systemPolicy?.let { controller.publishVpnSystemPolicy(token, it) }
        terminalError = errorMessage != null
        val profileId = activeSession?.profileId
        controller.publish(token, VpnConnectionState.Stopping(profileId))
        showForeground(ForegroundNotificationState.Stopping)
        serviceScope.launch {
            serviceLock.withLock {
                activeSession?.close()
                activeSession = null
                finishForeground()
                if (startId > 0) stopSelfResult(startId) else stopSelf()
                if (errorMessage == null) {
                    controller.publish(token, VpnConnectionState.Stopped)
                } else {
                    controller.publish(token, VpnConnectionState.Error(errorMessage))
                }
            }
        }
    }

    private fun requestSelect(
        profileId: String,
        groupTag: String,
        outboundTag: String,
        startId: Int,
    ) {
        serviceScope.launch {
            serviceLock.withLock {
                val session = activeSession
                if (session == null || session.profileId != profileId) {
                    failLocked(
                        controller.currentGeneration(),
                        IllegalStateException("Активный VPN-профиль не найден."),
                        startId,
                    )
                    return@withLock
                }
                try {
                    selectLocked(session, groupTag, outboundTag)
                    showForeground(ForegroundNotificationState.Connected)
                    controller.clearConnectionIdentity(session.generation)
                    startConnectionIdentityProbe(session)
                } catch (runtimeSwitchError: RuntimeSwitchException) {
                    val restartToken = controller.nextGeneration()
                    controller.beginConnectionDiagnostic(restartToken, "server_switch_restart")
                    controller.startConnectionDiagnosticStage(
                        restartToken,
                        "profile",
                        "Профиль и область приложений",
                    )
                    controller.publish(
                        restartToken,
                        VpnConnectionState.Starting(profileId, "Перезапуск после смены сервера"),
                    )
                    showForeground(ForegroundNotificationState.Restarting)
                    activeSession?.close()
                    activeSession = null
                    try {
                        startLocked(restartToken, profileId)
                    } catch (restartError: Throwable) {
                        if (restartToken == controller.currentGeneration()) {
                            restartError.addSuppressed(runtimeSwitchError)
                            failLocked(restartToken, restartError, startId)
                        }
                    }
                } catch (validationError: Throwable) {
                    controller.publishMessage(safeError(validationError).message)
                    showForeground(ForegroundNotificationState.Connected)
                }
            }
        }
    }

    private fun requestPing(startId: Int) {
        serviceScope.launch {
            val session = serviceLock.withLock { activeSession }
            if (session == null) {
                controller.publishMessage("Сначала подключите VPN.")
                finishForeground()
                stopSelfResult(startId)
                return@launch
            }
            val target = session.selectedPingTarget(controller.selectorGroups.value)
            runCatching {
                requireNotNull(target) { "У выбранного VPN-сервера нет адреса для ICMP." }
                measureServerPing(session, target)
            }
                .onSuccess { ping ->
                    if (activeSession === session) {
                        controller.publishServerPing(session.generation, checkNotNull(target).outboundTag, ping)
                        controller.publishPing(session.generation, ping)
                    }
                }
                .onFailure {
                    if (activeSession === session && target != null) {
                        controller.publishServerPing(session.generation, target.outboundTag, null)
                        controller.publishPing(session.generation, null)
                    }
                    controller.publishMessage("Не удалось измерить пинг: ${safeError(it).message}")
                }
        }
    }

    private fun requestGroupPing(profileId: String, groupTag: String, startId: Int) {
        serviceScope.launch {
            val session = serviceLock.withLock { activeSession }
            if (session == null || session.profileId != profileId) {
                controller.publishMessage("Активный VPN-профиль не найден.")
                if (session == null) {
                    finishForeground()
                    stopSelfResult(startId)
                }
                return@launch
            }
            runCatching {
                require(groupTag.isNotBlank()) { "Группа серверов не выбрана." }
                val targets = session.groupPingTargets(groupTag, controller.selectorGroups.value)
                require(targets.isNotEmpty()) { "В группе нет серверов с адресом для ICMP." }
                val results = session.networkMonitor.runOnStableNetwork { underlying ->
                    val network = requireNotNull(underlying.network) { "Основная сеть Android недоступна." }
                    val concurrency = Semaphore(GROUP_PING_CONCURRENCY)
                    coroutineScope {
                        targets.map { target ->
                            async {
                                concurrency.withPermit {
                                    target to runCatching { container.icmpPingProbe.measure(network, target) }.getOrNull()
                                }
                            }
                        }.awaitAll()
                    }
                }.value
                results.forEach { (target, ping) ->
                    controller.publishServerPing(session.generation, target.outboundTag, ping)
                }
                val selected = session.selectedPingTarget(controller.selectorGroups.value)
                results.firstOrNull { it.first.outboundTag == selected?.outboundTag }?.let { (_, ping) ->
                    controller.publishPing(session.generation, ping)
                }
            }.onFailure {
                controller.publishMessage("Не удалось проверить серверы: ${safeError(it).message}")
            }
        }
    }

    private fun startHomeStatusObserver(session: ActiveSession) {
        session.statusObserver = serviceScope.launch {
            controller.homeVisible.collect { visible ->
                if (activeSession !== session) return@collect
                if (visible) {
                    session.openStatusClient(controller)
                } else {
                    session.closeStatusClient(controller)
                }
            }
        }
    }

    private fun startDiagnosticsObserver(session: ActiveSession) {
        session.diagnosticsObserver = serviceScope.launch {
            controller.diagnosticsVisible.collect { visible ->
                if (activeSession !== session) return@collect
                if (visible) {
                    session.openLogClient(controller)
                } else {
                    session.closeLogClient(controller)
                }
            }
        }
    }

    private fun startConnectionIdentityProbe(session: ActiveSession) {
        session.identityJob?.cancel()
        session.identityJob = serviceScope.launch {
            coroutineScope {
                launch {
                    val target = session.selectedPingTarget(controller.selectorGroups.value)
                    val ping = target?.let { runCatching { measureServerPing(session, it) }.getOrNull() }
                    if (activeSession === session && target != null) {
                        controller.publishServerPing(session.generation, target.outboundTag, ping)
                        controller.publishPing(session.generation, ping)
                    }
                }
                launch {
                    val externalIp = runCatching { container.vpnExternalIpProbe.fetch() }.getOrNull()
                    if (externalIp != null && activeSession === session) {
                        controller.publishExternalIp(session.generation, externalIp)
                    }
                }
            }
        }
    }

    private suspend fun measureServerPing(
        session: ActiveSession,
        target: ServerPingTarget,
    ): Long = session.networkMonitor.runOnStableNetwork { underlying ->
        val network = requireNotNull(underlying.network) { "Основная сеть Android недоступна." }
        container.icmpPingProbe.measure(network, target)
    }.value

    private suspend fun selectLocked(
        session: ActiveSession,
        groupTag: String,
        outboundTag: String,
    ) {
        require(groupTag.isNotBlank() && outboundTag.isNotBlank()) { "Сервер не выбран." }
        val stored = container.profileStore.read(session.profileId)
        val candidate = ConfigAnalyzer.selectServer(stored.json, groupTag, outboundTag)
        withContext(Dispatchers.Default) { Libbox.checkConfig(candidate) }
        container.profileStore.update(session.profileId, candidate)
        val client = session.client ?: error("Клиент управления sing-box недоступен.")
        try {
            client.selectOutbound(groupTag, outboundTag)
        } catch (error: Throwable) {
            throw RuntimeSwitchException(error)
        }
        controller.publishSelection(session.generation, groupTag, outboundTag)
    }

    private suspend fun failLocked(token: Long, error: Throwable, startId: Int) {
        cancelScheduledNetworkRestart()
        activeSession?.close()
        activeSession = null
        terminalError = true
        val failure = safeError(error)
        finishForeground()
        if (startId > 0) stopSelfResult(startId) else stopSelf()
        controller.publish(token, failure)
    }

    internal fun requestStopFromCore() {
        requestStop(0, "sing-box остановил VPN-сервис.")
    }

    private fun showForeground(state: ForegroundNotificationState) {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle("Zapret KVN")
            .setContentText(state.text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Открыть", openIntent)
            .addAction(0, "Остановить", stopIntent)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        foregroundActive.set(true)
    }

    private fun cancelScheduledNetworkRestart() {
        synchronized(restartScheduleLock) {
            networkRestartJob?.cancel()
            networkRestartJob = null
        }
    }

    private fun finishForeground() {
        if (!foregroundActive.compareAndSet(true, false)) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Состояние VPN и действие остановки"
                setShowBadge(false)
            },
        )
    }

    private fun safeError(error: Throwable): VpnConnectionState.Error {
        val causes = generateSequence(error) { it.cause }.toList()
        val coded = causes.filterIsInstance<CodedFailure>().firstOrNull()
        val raw = coded?.userMessage ?: causes
            .mapNotNull { it.message }
            .firstOrNull(String::isNotBlank)
            ?: "Не удалось запустить VPN."
        val message = raw
            .let(SecretRedactor::redactInline)
            .replace(NEW_LINES, " ")
            .trim()
            .take(360)
        val technicalDetail = coded?.technicalDetail
            ?.let(SecretRedactor::redactInline)
            ?.replace(NEW_LINES, " ")
            ?.trim()
            ?.take(240)
        return VpnConnectionState.Error(
            message = message,
            code = coded?.failureCode.orEmpty(),
            technicalDetail = technicalDetail,
        )
    }

    private class ActiveSession(
        val profileId: String,
        val profileName: String,
        val generation: Long,
        val networkMonitor: DefaultNetworkMonitor,
        @Volatile var networkPolicyKey: UnderlyingPolicyKey,
        val outboundDescriptions: Map<String, OutboundDescription>,
        selectorGroups: List<SelectorGroup>,
        private val controller: VpnController,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val libboxStarted = AtomicBoolean(false)
        var platform: AndroidPlatformAdapter? = null
        var server: CommandServer? = null
        var client: CommandClient? = null
        var networkObserver: AutoCloseable? = null
        var statusObserver: Job? = null
        var diagnosticsObserver: Job? = null
        var identityJob: Job? = null
        private var statusClient: CommandClient? = null
        private var statusClientCounted = false
        private var logClient: CommandClient? = null
        private var logClientCounted = false
        private val pingTargetResolver = ServerPingTargetResolver(outboundDescriptions, selectorGroups)

        init {
            VpnRuntimeMetrics.sessionOpened()
        }

        fun selectedPingTarget(groups: List<RuntimeSelectorGroup>): ServerPingTarget? =
            pingTargetResolver.selected(groups)

        fun groupPingTargets(
            groupTag: String,
            groups: List<RuntimeSelectorGroup>,
        ): List<ServerPingTarget> = pingTargetResolver.group(groupTag, groups)

        fun markLibboxStarted() {
            if (libboxStarted.compareAndSet(false, true)) VpnRuntimeMetrics.libboxOpened()
        }

        @Synchronized
        fun openStatusClient(controller: VpnController) {
            if (closed.get() || statusClient != null) return
            val candidate = Libbox.newCommandClient(
                StatusClientHandler(controller, generation),
                CommandClientOptions().apply {
                    addCommand(Libbox.CommandStatus)
                    statusInterval = STATUS_INTERVAL_NANOS
                },
            )
            try {
                candidate.connect()
                if (closed.get()) {
                    runCatching { candidate.disconnect() }
                    return
                }
                statusClient = candidate
                statusClientCounted = true
                VpnRuntimeMetrics.statusClientOpened()
                controller.publishStatusStream(generation, true)
            } catch (_: Throwable) {
                runCatching { candidate.disconnect() }
                controller.publishStatusStream(generation, false)
            }
        }

        @Synchronized
        fun closeStatusClient(controller: VpnController) {
            val current = statusClient
            statusClient = null
            runCatching { current?.disconnect() }
            if (statusClientCounted) {
                statusClientCounted = false
                VpnRuntimeMetrics.statusClientClosed()
            }
            controller.publishStatusStream(generation, false)
        }

        @Synchronized
        fun openLogClient(controller: VpnController) {
            if (closed.get() || logClient != null) return
            val candidate = Libbox.newCommandClient(
                DiagnosticLogClientHandler(controller, generation),
                CommandClientOptions().apply { addCommand(Libbox.CommandLog) },
            )
            try {
                candidate.connect()
                if (closed.get()) {
                    runCatching { candidate.disconnect() }
                    return
                }
                logClient = candidate
                logClientCounted = true
                VpnRuntimeMetrics.logClientOpened()
                controller.publishDiagnosticLogStream(generation, true)
            } catch (_: Throwable) {
                runCatching { candidate.disconnect() }
                controller.publishDiagnosticLogStream(generation, false)
            }
        }

        @Synchronized
        fun closeLogClient(controller: VpnController) {
            val current = logClient
            logClient = null
            runCatching { current?.disconnect() }
            if (logClientCounted) {
                logClientCounted = false
                VpnRuntimeMetrics.logClientClosed()
            }
            controller.publishDiagnosticLogStream(generation, false)
        }

        @Synchronized
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            statusObserver?.cancel()
            statusObserver = null
            diagnosticsObserver?.cancel()
            diagnosticsObserver = null
            identityJob?.cancel()
            identityJob = null
            closeStatusClient(controller)
            closeLogClient(controller)
            runCatching { client?.disconnect() }
            client = null
            runCatching { networkObserver?.close() }
            networkObserver = null
            runCatching { server?.closeService() }
            if (libboxStarted.compareAndSet(true, false)) VpnRuntimeMetrics.libboxClosed()
            runCatching { platform?.close() }
            platform = null
            runCatching { server?.close() }
            server = null
            runCatching { networkMonitor.close() }
            VpnRuntimeMetrics.sessionClosed()
        }
    }

    private class RuntimeSwitchException(cause: Throwable) : Exception(cause)

    private class ServerHandler(
        private val service: ZapretVpnService,
    ) : CommandServerHandler {
        override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
            available = false
            enabled = false
        }

        override fun serviceReload() = throw UnsupportedOperationException("Reload выполняет Android-сервис.")
        override fun serviceStop() = service.requestStopFromCore()
        override fun setSystemProxyEnabled(enabled: Boolean) {
            check(!enabled) { "Системный proxy не поддерживается." }
        }
        override fun writeDebugMessage(message: String) = Unit
    }

    private abstract class BaseClientHandler : CommandClientHandler {
        override fun connected() = Unit
        override fun disconnected(message: String) = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun clearLogs() = Unit
        override fun writeLogs(messageList: LogIterator) = Unit
        override fun writeStatus(message: StatusMessage) = Unit
        override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
        override fun updateClashMode(newMode: String) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents) = Unit
        override fun writeGroups(message: OutboundGroupIterator) = Unit
    }

    private class StatusClientHandler(
        private val controller: VpnController,
        private val generation: Long,
    ) : BaseClientHandler() {
        override fun writeStatus(message: StatusMessage) {
            if (generation != controller.currentGeneration() || !message.trafficAvailable) return
            VpnRuntimeMetrics.updateTraffic(message.uplinkTotal, message.downlinkTotal)
            controller.publishTraffic(
                generation = generation,
                uploadDelta = message.uplink,
                downloadDelta = message.downlink,
                uploadTotal = message.uplinkTotal,
                downloadTotal = message.downlinkTotal,
            )
        }
    }

    private class DiagnosticLogClientHandler(
        private val controller: VpnController,
        private val generation: Long,
    ) : BaseClientHandler() {
        override fun disconnected(message: String) {
            controller.publishDiagnosticLogStream(generation, false)
        }

        override fun clearLogs() {
            controller.clearCoreDiagnosticLogs(generation)
        }

        override fun writeLogs(messageList: LogIterator) {
            while (messageList.hasNext()) {
                val entry = messageList.next()
                controller.publishCoreDiagnosticLog(generation, entry.level, entry.message)
            }
        }
    }

    private class GroupClientHandler(
        private val controller: VpnController,
        private val generation: Long,
        private val descriptions: Map<String, OutboundDescription>,
    ) : BaseClientHandler() {

        override fun writeGroups(message: OutboundGroupIterator) {
            val groups = buildList {
                while (message.hasNext()) {
                    val group = message.next()
                    val items = buildList {
                        val iterator = group.items
                        while (iterator.hasNext()) {
                            val item = iterator.next()
                            val description = descriptions[item.tag]
                            add(
                                RuntimeOutboundItem(
                                    tag = item.tag,
                                    type = item.type.ifBlank { description?.type ?: "unknown" },
                                    endpoint = description?.endpoint,
                                    pingMillis = null,
                                    pingMeasuredAtEpochSeconds = null,
                                ),
                            )
                        }
                    }
                    add(
                        RuntimeSelectorGroup(
                            tag = group.tag,
                            type = group.type,
                            selected = group.selected,
                            selectable = group.selectable,
                            items = items,
                        ),
                    )
                }
            }
            controller.publishGroups(generation, groups)
        }
    }

    private enum class ForegroundNotificationState(val text: String) {
        Preparing("Подготовка VPN"),
        ValidatingProfile("Проверка профиля"),
        CheckingNetwork("Проверка сети Android"),
        ValidatingCore("Проверка sing-box"),
        CreatingTun("Создание TUN"),
        CheckingHealth("Проверка DNS и HTTPS"),
        Connected("Подключено"),
        Restarting("Перезапуск VPN"),
        Stopping("Отключение"),
    }

    companion object {
        private const val STATUS_INTERVAL_NANOS = 1_000_000_000L
        private const val GROUP_PING_CONCURRENCY = 4
        private const val ACTION_START = "io.github.zapretkvn.android.vpn.START"
        private const val ACTION_STOP = "io.github.zapretkvn.android.vpn.STOP"
        private const val ACTION_SELECT = "io.github.zapretkvn.android.vpn.SELECT"
        private const val ACTION_RESTART = "io.github.zapretkvn.android.vpn.RESTART"
        private const val ACTION_CLEAR_DNS_CACHE = "io.github.zapretkvn.android.vpn.CLEAR_DNS_CACHE"
        private const val ACTION_PING = "io.github.zapretkvn.android.vpn.PING"
        private const val ACTION_PING_GROUP = "io.github.zapretkvn.android.vpn.PING_GROUP"
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val EXTRA_GROUP_TAG = "group_tag"
        private const val EXTRA_OUTBOUND_TAG = "outbound_tag"
        private const val EXTRA_REASON = "reason"
        private const val NOTIFICATION_CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 1001
        private const val NETWORK_RESTART_DEBOUNCE_MILLIS = 750L
        private val NEW_LINES = Regex("[\\r\\n\\t]+")

        fun startIntent(context: Context, profileId: String): Intent =
            Intent(context, ZapretVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROFILE_ID, profileId)

        fun stopIntent(context: Context): Intent =
            Intent(context, ZapretVpnService::class.java).setAction(ACTION_STOP)

        fun selectIntent(
            context: Context,
            profileId: String,
            groupTag: String,
            outboundTag: String,
        ): Intent = Intent(context, ZapretVpnService::class.java)
            .setAction(ACTION_SELECT)
            .putExtra(EXTRA_PROFILE_ID, profileId)
            .putExtra(EXTRA_GROUP_TAG, groupTag)
            .putExtra(EXTRA_OUTBOUND_TAG, outboundTag)

        fun restartIntent(context: Context, profileId: String, reason: String): Intent =
            Intent(context, ZapretVpnService::class.java)
                .setAction(ACTION_RESTART)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_REASON, reason)

        fun clearDnsCacheIntent(context: Context): Intent =
            Intent(context, ZapretVpnService::class.java).setAction(ACTION_CLEAR_DNS_CACHE)

        fun pingIntent(context: Context): Intent =
            Intent(context, ZapretVpnService::class.java).setAction(ACTION_PING)

        fun pingGroupIntent(context: Context, profileId: String, groupTag: String): Intent =
            Intent(context, ZapretVpnService::class.java)
                .setAction(ACTION_PING_GROUP)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_GROUP_TAG, groupTag)
    }
}
