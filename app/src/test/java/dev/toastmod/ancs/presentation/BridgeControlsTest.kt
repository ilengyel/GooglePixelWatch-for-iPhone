package dev.toastmod.ancs.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.toastmod.ancs.presentation.theme.ANCSTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BridgeControlsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun crownInputBoundsCommandsWithoutAccumulatingRotationsOrGuessingSupport() {
        val input = CrownVolumeInput()
        val supported = setOf(AMSCommand.VOLUME_UP, AMSCommand.VOLUME_DOWN)
        val commands = (0L until 1000).mapNotNull { input.command(100f, it, supported) }
        assertEquals(List(5) { AMSCommand.VOLUME_UP }, commands)
        assertEquals(AMSCommand.VOLUME_DOWN, input.command(-100f, 1000, supported))
        assertNull(input.command(0f, 1200, supported))
        assertNull(input.command(-100f, 1200, setOf(AMSCommand.VOLUME_UP)))
        input.reset()
        assertEquals(AMSCommand.VOLUME_UP, input.command(1f, 1, supported))
        assertThrows(IllegalArgumentException::class.java) { input.command(Float.NaN, 1, supported) }
    }

    @Test
    fun crownModeRequiresExplicitFocusAndEndsOnPauseOrMediaLoss() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                currentState = Lifecycle.State.RESUMED
            }
        }
        var state by mutableStateOf(AMSState(
            available = true,
            supportedCommands = setOf(AMSCommand.VOLUME_UP, AMSCommand.VOLUME_DOWN)
        ))
        val commands = mutableListOf<Byte>()
        var cancellations = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                ANCSTheme {
                    Column {
                        CrownVolumeControl(state, commands::add, { cancellations++ }) { modifier ->
                            Box(modifier.fillMaxWidth().height(48.dp).testTag("crown"))
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Use crown for volume").performClick()
        compose.onNodeWithText("Crown volume on").assertExists()
        compose.onNodeWithTag("crown").performRotaryScrollInput { rotateToScrollVertically(10f) }
        compose.runOnIdle {
            assertEquals(listOf(AMSCommand.VOLUME_UP), commands)
            ShadowSystemClock.advanceBy(Duration.ofMillis(200))
        }
        compose.onNodeWithTag("crown").performRotaryScrollInput { rotateToScrollVertically(-10f) }
        compose.runOnIdle {
            assertEquals(listOf(AMSCommand.VOLUME_UP, AMSCommand.VOLUME_DOWN), commands)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
        }
        compose.onNodeWithText("Use crown for volume").assertExists()
        compose.onNodeWithTag("crown").performRotaryScrollInput { rotateToScrollVertically(10f) }
        compose.runOnIdle {
            assertTrue(cancellations > 0)
            assertEquals(2, commands.size)
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithText("Use crown for volume").performClick()
        compose.runOnIdle { state = state.copy(available = false) }
        compose.onNodeWithText("Use crown for volume").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, commands.size) }
    }

    @Test
    fun extraMediaControlsDispatchOnlySupportedCommandsAndWaitForReportedState() {
        var state by mutableStateOf(AMSState(
            available = true,
            player = AMSPlayerInfo(volume = 0.4f),
            supportedCommands = setOf(AMSCommand.VOLUME_UP, AMSCommand.SKIP_FORWARD,
                AMSCommand.ADVANCE_SHUFFLE, AMSCommand.ADVANCE_REPEAT)
        ))
        val commands = mutableListOf<Byte>()
        compose.setContent {
            ANCSTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    NowPlayingScreen(state, {}, {}, {}, commands::add)
                }
            }
        }
        compose.onNodeWithContentDescription("Volume down").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Volume up").assertIsEnabled()
            .performScrollTo().performClick()
        compose.onNodeWithText("40%").assertExists()
        compose.onNodeWithText("More controls").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Skip backward").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Skip forward").performScrollTo().performClick()
        compose.onNodeWithText("Shuffle: Unknown").performScrollTo().performClick()
        compose.onNodeWithText("Repeat: Unknown").performScrollTo().performClick()
        compose.onNodeWithText("Shuffle: Unknown").assertExists()
        compose.onNodeWithText("Repeat: Unknown").assertExists()
        compose.runOnIdle {
            assertEquals(listOf(AMSCommand.VOLUME_UP, AMSCommand.SKIP_FORWARD,
                AMSCommand.ADVANCE_SHUFFLE, AMSCommand.ADVANCE_REPEAT), commands)
            state = state.copy(
                player = state.player.copy(volume = 0.5f),
                queue = AMSQueueInfo(AMSMode.All, AMSMode.One)
            )
        }
        compose.onNodeWithText("50%").assertExists()
        compose.onNodeWithText("Shuffle: All").assertExists()
        compose.onNodeWithText("Repeat: One").assertExists()
        compose.runOnIdle { state = state.copy(available = false) }
        compose.onNodeWithText("Shuffle: All").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Volume up").assertIsNotEnabled()
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
        compose.runOnIdle {
            assertEquals(listOf("app" to false), changes)
        }
        compose.onNodeWithText("Friendly app").assertIsOn()
        compose.runOnIdle { apps = listOf(NotificationApp("app", "Friendly app", false)) }
        compose.onNodeWithText("Friendly app").assertIsOff()
        compose.runOnIdle { connected = false }
        compose.onNodeWithText("Friendly app").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "w192dp-h192dp-round", fontScale = 1.3f)
    fun volumeButtonsRemainWithinSmallScreenBoundsAtLargeFontSize() {
        compose.setContent {
            ANCSTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    NowPlayingScreen(AMSState(), {}, {}, {})
                }
            }
        }
        val down = compose.onNodeWithContentDescription("Volume down").fetchSemanticsNode().boundsInRoot
        val up = compose.onNodeWithContentDescription("Volume up").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            org.junit.Assert.assertTrue(down.left >= 0)
            org.junit.Assert.assertTrue(down.right <= up.left)
            org.junit.Assert.assertTrue(up.right <= 192 * compose.density.density)
        }
        compose.onNodeWithText("More controls").performScrollTo().performClick()
        compose.onNodeWithText("Repeat: Unknown").performScrollTo().assertExists()
    }
}
