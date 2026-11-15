package dev.toastmod.ancs.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun swipingBackPopsEachPhoneScreenButLeavesRootDismissalToThePlatform() {
        var depth by mutableIntStateOf(2)
        compose.setContent {
            PhoneScreenBackNavigation(
                hasPreviousScreen = depth > 0,
                navigationBlocked = false,
                onBack = { depth-- }
            ) {
                Box(Modifier.fillMaxSize().testTag("screen-$depth"))
            }
        }

        compose.onNodeWithTag("screen-2").performTouchInput { swipeRight() }
        compose.onNodeWithTag("screen-1").assertExists()
        compose.onNodeWithTag("screen-1").performTouchInput { swipeRight() }
        compose.onNodeWithTag("screen-0").assertExists()
        compose.onNodeWithTag("screen-0").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(0, depth) }
    }

    @Test
    fun unpairingBlocksSwipeBackUntilTheTransactionFinishes() {
        var blocked by mutableStateOf(true)
        var backCount = 0
        compose.setContent {
            PhoneScreenBackNavigation(
                hasPreviousScreen = true,
                navigationBlocked = blocked,
                onBack = { backCount++ }
            ) {
                Box(Modifier.fillMaxSize().testTag("diagnostics"))
            }
        }

        compose.onNodeWithTag("diagnostics").performTouchInput { swipeRight() }
        compose.runOnIdle {
            assertEquals(0, backCount)
            blocked = false
        }
        compose.onNodeWithTag("diagnostics").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(1, backCount) }
    }
}
