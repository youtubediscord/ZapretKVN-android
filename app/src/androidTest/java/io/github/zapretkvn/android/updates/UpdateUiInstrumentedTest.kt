package io.github.zapretkvn.android.updates

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.ui.UpdateChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UpdateUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val container
        get() = (composeRule.activity.application as ZapretApplication).container

    @After
    fun cleanup() = runBlocking {
        container.updateController.cancelAndDelete()
        container.uiSettingsStore.setUpdateChannel(UpdateChannel.Stable)
    }

    @Test
    fun settingsPersistChannelAndNeverCheckUntilExplicitButton() {
        composeRule.onNode(hasText("Настройки") and hasClickAction()).performClick()
        composeRule.onNodeWithTag("settings-list").performScrollToNode(hasText("Обновления"))
        composeRule.onNodeWithText("Обновления").assertExists()
        composeRule.onNodeWithText("Проверить обновления").assertExists()
        assertEquals(UpdateState.Idle, container.updateController.state.value)

        composeRule.onNode(hasText("Beta") and hasClickAction()).performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { container.uiSettingsStore.settings.first().updateChannel == UpdateChannel.Beta }
        }
        assertEquals(UpdateState.Idle, container.updateController.state.value)
    }
}
