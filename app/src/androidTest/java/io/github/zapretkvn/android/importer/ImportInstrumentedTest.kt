package io.github.zapretkvn.android.importer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.journeyapps.barcodescanner.ScanContract
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.config.LibboxConfigValidator
import io.github.zapretkvn.android.profiles.ManagedProfileFactory
import io.github.zapretkvn.android.profiles.ProfileSource
import io.github.zapretkvn.android.profiles.ProfileStore
import io.github.zapretkvn.android.profiles.ProfilesViewModel
import io.github.zapretkvn.android.profiles.ProtocolOutboundBuilders
import io.github.zapretkvn.android.profiles.TlsSettings
import io.github.zapretkvn.android.profiles.TransportSettings
import io.github.zapretkvn.android.vpn.BootstrapCache
import io.github.zapretkvn.android.vpn.VpnController
import io.nekohasekai.libbox.Libbox
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun filePickerUsesSystemOpenDocumentContract() {
        val intent = ActivityResultContracts.OpenDocument().createIntent(
            context,
            arrayOf("application/json", "text/plain"),
        )

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertTrue(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.contains("application/json") == true)
    }

    @Test
    fun selectedDocumentIsReadThroughContentResolver() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val resourceId = testContext.resources.getIdentifier(
            "import_profile",
            "raw",
            testContext.packageName,
        )
        val uri = Uri.parse("android.resource://${testContext.packageName}/$resourceId")

        val raw = AndroidImportReader(testContext).readDocument(uri)

        assertTrue(ImportParser.parse(raw, ProfileSource.File) is ImportCandidate.RawJson)
    }

    @Test
    fun clipboardIsReadOnlyAfterExplicitReaderCall() {
        var imported = ""
        val canary = "clipboard-r8-${UUID.randomUUID()}"
        val clipboardValue = """{"x-clipboard-canary":"$canary"}"""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val focusDeadline = SystemClock.uptimeMillis() + 10_000
            var hasWindowFocus = false
            while (!hasWindowFocus && SystemClock.uptimeMillis() < focusDeadline) {
                scenario.onActivity { activity ->
                    hasWindowFocus = activity.hasWindowFocus()
                }
                if (!hasWindowFocus) SystemClock.sleep(50)
            }
            assertTrue("MainActivity never received window focus", hasWindowFocus)
            scenario.onActivity { activity ->
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("profile", clipboardValue))
                imported = AndroidImportReader(activity).readClipboardAfterUserAction()
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }

        assertEquals(clipboardValue, imported)
        assertTrue(ImportParser.parse(imported, ProfileSource.Clipboard) is ImportCandidate.RawJson)
        val privateRoots = listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir)
        val persisted = privateRoots.asSequence()
            .flatMap { root -> root.walkTopDown().asSequence() }
            .filter { file -> file.isFile && file.length() <= 2L * 1024L * 1024L }
            .any { file -> runCatching { canary in file.readText() }.getOrDefault(false) }
        assertTrue("Clipboard reader must not create app-private history", !persisted)
    }

    @Test
    fun managedSingleAndSubscriptionPassLibboxCheckConfig() {
        val one = vless("One", "one.example")
        val two = vless("Two", "two.example")

        Libbox.checkConfig(ManagedProfileFactory.single(one))
        Libbox.checkConfig(ManagedProfileFactory.subscription(listOf(one, two)))
    }

    @Test
    fun commonProtocolBuildersPassLibboxCheckConfig() {
        val servers = listOf(
            ProtocolOutboundBuilders.vless(
                displayName = "VLESS WS",
                server = "vless.example",
                serverPort = 443,
                uuid = "11111111-1111-4111-8111-111111111111",
                tls = TlsSettings(enabled = true, serverName = "vless.example"),
                transport = TransportSettings(type = "ws", path = "/vpn", host = "vless.example"),
            ),
            ProtocolOutboundBuilders.vmess(
                displayName = "VMess",
                server = "vmess.example",
                serverPort = 443,
                uuid = "22222222-2222-4222-8222-222222222222",
                tls = TlsSettings(enabled = true, serverName = "vmess.example"),
            ),
            ProtocolOutboundBuilders.trojan(
                displayName = "Trojan",
                server = "trojan.example",
                serverPort = 443,
                password = "test-password",
            ),
            ProtocolOutboundBuilders.shadowsocks(
                displayName = "Shadowsocks",
                server = "ss.example",
                serverPort = 8388,
                method = "aes-128-gcm",
                password = "test-password",
            ),
            ProtocolOutboundBuilders.hysteria2(
                displayName = "Hysteria2",
                server = "hy.example",
                serverPort = 443,
                password = "test-password",
            ),
            ProtocolOutboundBuilders.tuic(
                displayName = "TUIC",
                server = "tuic.example",
                serverPort = 443,
                uuid = "33333333-3333-4333-8333-333333333333",
                password = "test-password",
                congestionControl = "bbr",
            ),
        )

        Libbox.checkConfig(ManagedProfileFactory.subscription(servers))
    }

    @Test
    fun allShareLinkProtocolsParseToConfigAcceptedByPinnedLibbox() {
        val links = listOf(
            "vless://11111111-1111-4111-8111-111111111111@vless.example:443?security=tls#VLESS",
            "trojan://secret@trojan.example:443?sni=trojan.example#Trojan",
            "hy2://secret@hy.example:443?sni=hy.example#HY2",
            "tuic://33333333-3333-4333-8333-333333333333:secret@tuic.example:443?sni=tuic.example#TUIC",
        ).joinToString("\n")
        val candidate = ImportParser.parse(links, ProfileSource.Clipboard) as ImportCandidate.Managed

        Libbox.checkConfig(candidate.buildJson())
    }

    @Test
    fun qrContractIsExplicitAndCameraPermissionIsDeclared() {
        val intent = ScanContract().createIntent(
            context,
            qrImportScanOptions(),
        )
        val permissions = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty()

        assertEquals(QrCaptureActivity::class.java.name, intent.component?.className)
        assertTrue(Manifest.permission.CAMERA in permissions)
    }

    @Test
    fun profilePersistsAcrossStoreRecreation() = runBlocking {
        val root = File(context.filesDir, "profiles-instrumented-${System.nanoTime()}")
        try {
            val validator = LibboxConfigValidator()
            val first = ProfileStore(root, validator)
            first.initialize()
            val metadata = first.create("Persisted", VALID_DIRECT, ProfileSource.File)

            val reopened = ProfileStore(root, validator)
            reopened.initialize()
            assertEquals(VALID_DIRECT, reopened.read(metadata.id).json)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedManualRefreshNeverChangesStoredProfile() = runBlocking {
        val root = File(context.cacheDir, "refresh-instrumented-${System.nanoTime()}")
        val testViewModelStore = ViewModelStore()
        try {
            val profileStore = ProfileStore(File(root, "profiles"), LibboxConfigValidator())
            profileStore.initialize()
            val metadata = profileStore.create("Existing", VALID_DIRECT, ProfileSource.Subscription)
            val sourceStore = SubscriptionSourceStore(File(root, "subscriptions"))
            sourceStore.put(metadata.id, "https://subscription.example/profile?token=secret")
            val application = context.applicationContext as ZapretApplication
            val owner = object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = testViewModelStore
            }
            val viewModel = ViewModelProvider(
                owner,
                ProfilesViewModel.Factory(
                    store = profileStore,
                    settingsStore = application.container.uiSettingsStore,
                    validator = LibboxConfigValidator(),
                    importReader = AndroidImportReader(context),
                    subscriptionFetcher = SubscriptionFetcher { "{broken" },
                    subscriptionSourceStore = sourceStore,
                    vpnController = VpnController(context),
                    bootstrapCache = BootstrapCache(File(root, "network")),
                ),
            )[ProfilesViewModel::class.java]

            viewModel.refreshSubscription(metadata.id)
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (viewModel.state.value.message == null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(25)
            }

            assertTrue(viewModel.state.value.message != null)
            assertEquals(VALID_DIRECT, profileStore.read(metadata.id).json)
        } finally {
            testViewModelStore.clear()
            root.deleteRecursively()
        }
    }

    private fun vless(name: String, host: String) = ProtocolOutboundBuilders.vless(
        displayName = name,
        server = host,
        serverPort = 443,
        uuid = "11111111-1111-4111-8111-111111111111",
        tls = TlsSettings(enabled = true, serverName = host),
    )

    private companion object {
        const val VALID_DIRECT =
            """{"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}"""
    }
}
