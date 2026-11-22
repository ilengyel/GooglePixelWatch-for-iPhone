package dev.toastmod.ancs.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BridgeControlsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nativeMediaEntryReplacesTransportButtonsAndRequiresAnActivePhoneSession() {
        val media = MutableStateFlow(AMSState())
        var launches = 0
        compose.setContent { WearApp(media, { launches++ }) }
        compose.onNodeWithText("Media controls").assertIsNotEnabled()
        compose.onNodeWithText("Use Wear OS media controls", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Play").assertDoesNotExist()
        compose.onNodeWithContentDescription("Volume up").assertDoesNotExist()
        compose.runOnIdle {
            media.value = AMSState(available = true, player = AMSPlayerInfo(isPlaying = true))
        }
        compose.onNodeWithText("iPhone media").assertExists()
        compose.onNodeWithText("Media controls").assertIsEnabled()
        compose.runOnIdle {
            media.value = AMSState(available = true, track = AMSTrackInfo(title = "Track"))
        }
        compose.onNodeWithText("Media controls").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, launches)
            media.value = AMSState()
        }
        compose.onNodeWithText("Media controls").assertIsNotEnabled()
    }

    @Test
    @Config(fontScale = 1.3f)
    fun nativeEntryUsesAvailableWidthOnASmallRoundScreen() {
        compose.setContent { WearApp(MutableStateFlow(AMSState()), {}) }
        val bounds = compose.onNodeWithText("Media controls").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= 150 * compose.density.density)
        assertTrue(bounds.left >= 0)
        assertTrue(bounds.right <= 192 * compose.density.density)
    }

    @Test
    fun appSwitchWaitsForSavedStateAndDisconnectDisablesChanges() {
        var apps by mutableStateOf(listOf(NotificationApp("app", "Friendly app")))
        var connected by mutableStateOf(true)
        val changes = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            AppFilterScreen(apps, connected) { id, enabled -> changes += id to enabled }
        }
        compose.onNodeWithText("Friendly app").assertIsOn().performClick()
        compose.runOnIdle { assertEquals(listOf("app" to false), changes) }
        compose.onNodeWithText("Friendly app").assertIsOn()
        compose.runOnIdle { apps = listOf(NotificationApp("app", "Friendly app", false)) }
        compose.onNodeWithText("Friendly app").assertIsOff()
        compose.runOnIdle { connected = false }
        compose.onNodeWithText("Friendly app").assertIsNotEnabled()
    }
}
