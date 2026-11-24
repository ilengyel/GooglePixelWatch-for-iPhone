package dev.toastmod.ancs.presentation

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ActivityScenario
import dev.toastmod.ancs.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityNavigationTest {
    @get:Rule
    val compose = createComposeRule()

    private class ScreenLifecycle : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
    }

    @Test
    fun mediaCollectionStopsWithTheScreenAndResumesWithTheLatestState() {
        val owner = ScreenLifecycle()
        val media = MutableStateFlow(AMSState())
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                WearApp(media, {})
            }
        }
        compose.runOnIdle {
            assertEquals(1, media.subscriptionCount.value)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
        }
        compose.runOnIdle {
            assertEquals(0, media.subscriptionCount.value)
            media.value = AMSState(available = true, track = AMSTrackInfo(title = "Latest track"))
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithText("Latest track").assertExists()
        compose.runOnIdle { assertEquals(1, media.subscriptionCount.value) }
    }

    @Test
    fun diagnosticsRefreshOnDeviceBroadcastsOnlyWhileStarted() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val phone = TestBluetoothDevice().device
        `when`(phone.alias).thenReturn("Original phone")
        `when`(phone.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        val owner = ScreenLifecycle()
        val media = MutableStateFlow(AMSState())
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PhoneDiagnosticsScreen(phone, PhoneConnectionState.DISCONNECTED, "", media,
                    selected = true, forgetting = false, message = "", onForget = {})
            }
        }
        compose.onNodeWithText("Original phone").assertExists()
        compose.runOnIdle {
            assertEquals(1, media.subscriptionCount.value)
            clearInvocations(phone)
            context.sendBroadcast(Intent(BluetoothDevice.ACTION_NAME_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, TestBluetoothDevice("00:11:22:33:44:66").device))
        }
        compose.runOnIdle {
            verify(phone, never()).alias
            `when`(phone.alias).thenReturn("Renamed phone")
            context.sendBroadcast(Intent(BluetoothDevice.ACTION_ALIAS_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, phone))
        }
        compose.onNodeWithText("Renamed phone").assertExists()
        compose.runOnIdle {
            verify(phone).bondState
            owner.lifecycle.currentState = Lifecycle.State.CREATED
        }
        compose.runOnIdle {
            assertEquals(0, media.subscriptionCount.value)
            clearInvocations(phone)
            context.sendBroadcast(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, phone))
        }
        compose.runOnIdle {
            verify(phone, never()).bondState
            `when`(phone.alias).thenReturn("Changed while stopped")
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithText("Changed while stopped").assertExists()
        compose.runOnIdle {
            verify(phone).bondState
            assertEquals(1, media.subscriptionCount.value)
        }
    }

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityReopenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun reopeningKeepsNotificationAppsUntilTwoMinutesAndThenReturnsToMain() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS
        )
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val phone = TestBluetoothDevice().device
        `when`(phone.alias).thenReturn("Paired iPhone")
        shadowOf(adapter).setBondedDevices(setOf(phone))
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)

        val notificationApps = context.getString(R.string.notification_apps)
        val bell = compose.onNodeWithContentDescription(notificationApps)
        val connection = compose.onNodeWithContentDescription(context.getString(R.string.choose_iphone))
        val notificationHeader = compose.onNodeWithText(notificationApps)
        val pairedPhone = compose.onNodeWithText("Paired iPhone")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            pairedPhone.assertExists()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

            val phoneBounds = connection.fetchSemanticsNode().boundsInRoot
            val bellBounds = bell.fetchSemanticsNode().boundsInRoot
            assertEquals(phoneBounds.center.y, bellBounds.center.y, 0.5f)
            assertTrue(phoneBounds.right < bellBounds.left)
            bell.performClick()
            notificationHeader.assertExists()

            scenario.moveToState(Lifecycle.State.CREATED)
            ShadowSystemClock.advanceBy(Duration.ofMillis(119_999))
            scenario.moveToState(Lifecycle.State.RESUMED)
            notificationHeader.assertExists()

            scenario.moveToState(Lifecycle.State.CREATED)
            ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
            scenario.moveToState(Lifecycle.State.RESUMED)
            notificationHeader.assertDoesNotExist()
            bell.assertExists()
            connection.performClick()
            pairedPhone.assertExists()
        }
    }
}
