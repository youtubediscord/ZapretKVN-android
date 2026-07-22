package io.github.zapretkvn.android.profiles

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.ui.UpdateChannel
import io.github.zapretkvn.android.vpn.VpnConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val container
        get() = (composeRule.activity.application as ZapretApplication).container

    @Before
    fun clearProfiles() = runBlocking {
        resetDiagnostics()
        container.profileStore.initialize()
        container.profileStore.profiles.value.forEach { container.profileStore.delete(it.id) }
        container.uiSettingsStore.setActiveProfile(null)
        container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
        container.uiSettingsStore.setUpdateChannel(UpdateChannel.Stable)
    }

    @After
    fun cleanProfiles() = runBlocking {
        resetDiagnostics()
        container.profileStore.profiles.value.forEach { container.profileStore.delete(it.id) }
        container.uiSettingsStore.setActiveProfile(null)
        container.uiSettingsStore.setDnsMode(DnsMode.FromJson)
        container.uiSettingsStore.setUpdateChannel(UpdateChannel.Stable)
    }

    @Test
    fun userImportsValidatesEditsSavesAndReopensProfile() {
        composeRule.runOnUiThread {
            val clipboard = composeRule.activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("profile", VALID_DIRECT))
        }

        composeRule.onNode(hasText("Профили") and hasClickAction()).performClick()
        composeRule.onNodeWithText("Буфер").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            runCatching {
                composeRule.onNodeWithText("Предпросмотр импорта").fetchSemanticsNode()
            }.isSuccess
        }
        composeRule.onNodeWithText("Предпросмотр импорта").assertExists()
        composeRule.onNodeWithText("Новый профиль").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            container.profileStore.profiles.value.isNotEmpty()
        }
        composeRule.onNodeWithText("Позже").performClick()
        composeRule.onNodeWithText("Профиль из буфера").assertExists()
        composeRule.onNodeWithText("Буфер обмена").assertExists()
        composeRule.onNodeWithText("Обновлено:", substring = true).assertExists()
        composeRule.onNodeWithText("JSON").performClick()
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2
        }
        composeRule.onAllNodes(hasSetTextAction())[1].performTextReplacement(UPDATED_DIRECT)
        composeRule.onNodeWithText("Validate").performClick()
        composeRule.onNodeWithText("Конфигурация корректна.").assertExists()
        composeRule.onNodeWithText("Сохранить").performClick()
        val profileId = container.profileStore.profiles.value.single().id
        composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MILLIS) {
            runBlocking { container.profileStore.read(profileId).json == UPDATED_DIRECT }
        }
        composeRule.onNodeWithContentDescription("Назад").performClick()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNode(hasText("Профили") and hasClickAction()).performClick()
        composeRule.onNodeWithText("Профиль из буфера").assertExists()
        composeRule.onNodeWithText("JSON").performClick()
        composeRule.onAllNodes(hasSetTextAction())[1].assertTextContains(UPDATED_DIRECT)
    }

    @Test
    fun settingsExposeAllFourDnsModesAndPersistSelection() {
        composeRule.onNode(hasText("Настройки") and hasClickAction()).performClick()
        val modes = listOf(
            "Автоматически",
            "DNS Android",
            "Защищённый через VPN",
            "Из JSON",
        )
        modes.forEach { label ->
            composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText(label))
            composeRule.onNodeWithText(label).assertExists()
        }

        composeRule.onNodeWithTag("settings-list")
            .performScrollToNode(hasText("Защищённый через VPN"))
        composeRule.onNodeWithText("Защищённый через VPN").performClick()
        composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
            runBlocking { container.uiSettingsStore.settings.first().dnsMode == DnsMode.Secure }
        }
        composeRule.onNodeWithText(
            "Перехватывается TCP/UDP 53; встроенный DoH, DoT и mDNS не перехватываются.",
        ).assertExists()

        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Beta"))
        composeRule.onNodeWithText("Beta").performClick()
        composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
            runBlocking {
                container.uiSettingsStore.settings.first().updateChannel == UpdateChannel.Beta
            }
        }
    }

    @Test
    fun settingsSubpagesHaveBackNavigationAndCommunityIsIsolated() {
        composeRule.onNode(hasText("Настройки") and hasClickAction()).performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Сообщество"))
        composeRule.onNodeWithText("Сообщество").performClick()
        composeRule.onNodeWithText("Zapret KVN").assertExists()
        composeRule.onNodeWithText("VPN Discord YouTube").assertExists()
        composeRule.onNodeWithText("Zapret VPN bot").assertExists()
        composeRule.onNodeWithContentDescription("Назад").performClick()

        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Диагностика"))
        composeRule.runOnUiThread {
            val token = container.vpnController.nextGeneration()
            container.vpnController.publish(
                token,
                VpnConnectionState.Error("DNS через VPN заблокирован token=visible-secret"),
            )
            container.vpnController.publishCoreDiagnosticLog(token, 3, "token=visible-secret")
        }
        composeRule.onNodeWithText("Диагностика").performClick()
        composeRule.onNodeWithText("Текущее состояние").assertExists()
        composeRule.onNodeWithText("DNS через VPN").assertExists()
        composeRule.onNodeWithText("DNS через VPN заблокирован token=•••").assertExists()
        composeRule.onNodeWithText("visible-secret", substring = true).assertDoesNotExist()
        composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
            container.vpnController.diagnosticsVisible.value
        }
        composeRule.onNodeWithTag("diagnostic-logs-toggle").performScrollTo().performClick()
        composeRule.onNodeWithText("Скрыть", substring = true).assertExists()
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
            !container.vpnController.diagnosticsVisible.value
        }

        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("О приложении"))
        composeRule.onNodeWithText("О приложении").performClick()
        composeRule.onNodeWithText("Ядро").assertExists()
        composeRule.onNodeWithText("Известные ограничения MVP").assertExists()
        composeRule.onNodeWithText("Clash YAML", substring = true).assertExists()
    }

    private fun resetDiagnostics() {
        container.vpnController.setDiagnosticsVisible(false)
        val token = container.vpnController.nextGeneration()
        container.vpnController.publish(token, VpnConnectionState.Starting("", "Сброс теста"))
        container.vpnController.publish(token, VpnConnectionState.Stopped)
    }

    private companion object {
        const val UI_TIMEOUT_MILLIS = 120_000L
        const val VALID_DIRECT =
            """{"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}"""
        const val UPDATED_DIRECT =
            """{"outbounds":[{"type":"direct","tag":"edited"}],"route":{"final":"edited"}}"""
    }
}
