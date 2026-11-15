package dev.toastmod.ancs.presentation

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ANCSAndroidServiceTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var phone: TestBluetoothDevice
    private lateinit var controller: ServiceController<ANCSAndroidService>
    private lateinit var service: ANCSAndroidService
    private var destroyed = false

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS)
        phone = TestBluetoothDevice()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setBondedDevices(setOf(phone.device))
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        controller = Robolectric.buildService(ANCSAndroidService::class.java).create()
        service = controller.get()
    }

    @After
    fun tearDown() {
        destroyService()
        Dispatchers.resetMain()
    }

    private fun destroyService() {
        if (!destroyed) {
            controller.destroy()
            scheduler.runCurrent()
            destroyed = true
        }
    }

    private fun serviceTest(block: suspend TestScope.() -> Unit) =
        runTest(StandardTestDispatcher(scheduler)) {
            try {
                block()
            } finally {
                destroyService()
            }
        }

    private fun intent(address: String = phone.device.address) =
        Intent(context, ANCSAndroidService::class.java)
            .putExtra(ANCSAndroidService.TARGET_ADDRESS, address)

    private fun preferences() =
        context.getSharedPreferences(ANCSAndroidService.PREFERENCES, Context.MODE_PRIVATE)

    @Test
    fun forgettingWaitsForBondRemovalAndStopsReconnectsWithoutRemovingOtherSelections() =
        serviceTest {
            service.onStartCommand(intent(), 0, 1)
            val connection = phone.connections.single()
            `when`(phone.device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
            var requestedAddress: String? = null
            val forgetJob = launch {
                service.forgetSelectedPhone(phone.device.address) {
                    requestedAddress = it.address
                    launch {
                        delay(1_000)
                        `when`(it.bondState).thenReturn(BluetoothDevice.BOND_NONE)
                    }
                    true
                }
            }
            scheduler.runCurrent()
            assertTrue(connection.closed)
            assertEquals(phone.device.address, requestedAddress)
            assertEquals(phone.device.address,
                preferences().getString(ANCSAndroidService.TARGET_ADDRESS, null))
            assertEquals(Service.START_STICKY, service.onStartCommand(intent(), 0, 2))
            scheduler.advanceTimeBy(1_000)
            scheduler.runCurrent()
            forgetJob.join()
            assertFalse(preferences().contains(ANCSAndroidService.TARGET_ADDRESS))
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(1, phone.connections.size)
        }

    @Test
    fun rejectedOrUnconfirmedUnpairRestoresTheSelectedConnection() =
        serviceTest {
            service.onStartCommand(intent(), 0, 1)
            `when`(phone.device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
            for (accepted in listOf(false, true)) {
                val oldConnection = phone.connections.last()
                var failure: IllegalStateException? = null
                try {
                    service.forgetSelectedPhone(phone.device.address) { accepted }
                } catch (error: IllegalStateException) {
                    failure = error
                }
                assertTrue(failure != null)
                assertEquals(phone.device.address,
                    preferences().getString(ANCSAndroidService.TARGET_ADDRESS, null))
                assertTrue(oldConnection.closed)
                assertFalse(phone.connections.last().closed)
                assertTrue(oldConnection !== phone.connections.last())
                assertEquals(PhoneConnectionState.CONNECTING, service.connectionState.value)
                assertFalse(shadowOf(service).isStoppedBySelf)
            }
        }

    @Test
    fun forgettingAStaleSelectionNeverInvokesUnpair() = serviceTest {
        service.onStartCommand(intent(), 0, 1)
        var invoked = false
        var failed = false
        try {
            service.forgetSelectedPhone("00:11:22:33:44:66") {
                invoked = true
                true
            }
        } catch (error: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        assertFalse(invoked)
        assertFalse(phone.connections.single().closed)
        assertEquals(phone.device.address,
            preferences().getString(ANCSAndroidService.TARGET_ADDRESS, null))
    }

    @Test
    fun cancellingTheCallerDoesNotInterruptAcceptedUnpairing() =
        serviceTest {
            service.onStartCommand(intent(), 0, 1)
            `when`(phone.device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
            val caller = launch {
                service.forgetSelectedPhone(phone.device.address) { true }
            }
            scheduler.runCurrent()
            assertTrue(service.forgettingPhone.value)
            caller.cancel()
            caller.join()
            assertTrue(service.forgettingPhone.value)

            `when`(phone.device.bondState).thenReturn(BluetoothDevice.BOND_NONE)
            scheduler.advanceTimeBy(250)
            scheduler.runCurrent()

            assertFalse(service.forgettingPhone.value)
            assertFalse(preferences().contains(ANCSAndroidService.TARGET_ADDRESS))
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertEquals(1, phone.connections.size)
        }

    @Test
    fun permissionDeniedDuringUnpairRestoresTheConnectionAndReportsTheFailure() =
        serviceTest {
            service.onStartCommand(intent(), 0, 1)
            `when`(phone.device.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
            var failure: SecurityException? = null
            try {
                service.forgetSelectedPhone(phone.device.address) {
                    throw SecurityException("Unpair denied")
                }
            } catch (error: SecurityException) {
                failure = error
            }
            assertEquals("Unpair denied", failure?.message)
            assertEquals(2, phone.connections.size)
            assertTrue(phone.connections.first().closed)
            assertFalse(phone.connections.last().closed)
            assertEquals(PhoneConnectionState.CONNECTING, service.connectionState.value)
        }

    @Test
    fun repeatedStartsForTheSameDeviceReuseTheConnectionIncludingStickyRestarts() {
        assertEquals(Service.START_STICKY, service.onStartCommand(intent(), 0, 1))
        val connection = phone.connections.single()
        assertEquals(Service.START_STICKY, service.onStartCommand(intent(), 0, 2))
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 3))
        scheduler.runCurrent()

        assertEquals(1, phone.connections.size)
        assertFalse(connection.closed)
        assertEquals(phone.device.address,
            preferences().getString(ANCSAndroidService.TARGET_ADDRESS, null))
        assertEquals("Connecting to iPhone", service.status.value)
        assertEquals(PhoneConnectionState.CONNECTING, service.connectionState.value)
    }

    @Test
    fun switchingDevicesClosesTheOldConnectionAndPersistsTheNewSelection() {
        val other = TestBluetoothDevice("00:11:22:33:44:66")
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setBondedDevices(setOf(phone.device, other.device))
        service.onStartCommand(intent(), 0, 1)
        val old = phone.connections.single()

        assertEquals(Service.START_STICKY,
            service.onStartCommand(intent(other.device.address), 0, 2))
        scheduler.runCurrent()
        assertTrue(old.closed)
        assertEquals(1, other.connections.size)
        assertFalse(other.connections.single().closed)
        assertEquals(other.device.address,
            preferences().getString(ANCSAndroidService.TARGET_ADDRESS, null))
    }

    private fun assertRevokedPermissionStops(permission: String) {
        service.onStartCommand(intent(), 0, 1)
        val connection = phone.connections.single()
        shadowOf(context).denyPermissions(permission)

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent(), 0, 2))
        assertTrue(connection.closed)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals("Bluetooth and notification permissions required", service.status.value)
        assertEquals(PhoneConnectionState.DISCONNECTED, service.connectionState.value)
        scheduler.advanceTimeBy(120_000)
        scheduler.runCurrent()
        assertEquals(1, phone.connections.size)
        assertEquals("Bluetooth and notification permissions required", service.status.value)
    }

    @Test
    fun revokedBluetoothPermissionStopsAndClosesTheConnection() {
        assertRevokedPermissionStops(Manifest.permission.BLUETOOTH_CONNECT)
    }

    @Test
    fun revokedNotificationPermissionStopsAndClosesTheConnection() {
        assertRevokedPermissionStops(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun missingPermissionsPreventAnyConnection() {
        shadowOf(context).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent(), 0, 1))
        assertTrue(phone.connections.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals("Bluetooth and notification permissions required", service.status.value)
    }

    @Test
    fun anUnbondedSavedDeviceDoesNotFallBackToAnotherBondedDevice() {
        preferences().edit().putString(ANCSAndroidService.TARGET_ADDRESS, "00:11:22:33:44:77").apply()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(phone.connections.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals("Select a paired iPhone", service.status.value)
        assertEquals(PhoneConnectionState.DISCONNECTED, service.connectionState.value)
    }

    @Test
    fun losingTheSelectedBondStopsAnExistingConnection() {
        service.onStartCommand(intent(), 0, 1)
        val connection = phone.connections.single()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setBondedDevices(emptySet())

        context.sendBroadcast(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, phone.device)
            .putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(connection.closed)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertEquals("Select a paired iPhone", service.status.value)
        scheduler.advanceTimeBy(120_000)
        scheduler.runCurrent()
        assertEquals(1, phone.connections.size)
    }

    @Test
    fun losingAnotherDevicesBondDoesNotStopTheSelectedPhone() {
        service.onStartCommand(intent(), 0, 1)
        val other = TestBluetoothDevice("00:11:22:33:44:66")
        context.sendBroadcast(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            .putExtra(BluetoothDevice.EXTRA_DEVICE, other.device)
            .putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse(phone.connections.single().closed)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun stickyRestartWithBluetoothOffWaitsAndConnectsWhenEnabled() {
        preferences().edit().putString(ANCSAndroidService.TARGET_ADDRESS, phone.device.address).apply()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        shadowOf(adapter).setBondedDevices(emptySet())

        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(phone.connections.isEmpty())
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals("Bluetooth disabled; waiting for Bluetooth", service.status.value)

        shadowOf(adapter).setBondedDevices(setOf(phone.device))
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        context.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
            .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        scheduler.runCurrent()

        assertEquals(1, phone.connections.size)
        assertEquals(PhoneConnectionState.CONNECTING, service.connectionState.value)
    }

    @Test
    fun togglingBluetoothPausesRetriesAndResumesOnlyOneConnection() {
        service.onStartCommand(intent(), 0, 1)
        val oldConnection = phone.connections.single()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        context.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
            .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(oldConnection.closed)
        scheduler.advanceTimeBy(120_000)
        scheduler.runCurrent()
        assertEquals(1, phone.connections.size)
        assertFalse(shadowOf(service).isStoppedBySelf)

        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        context.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
            .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        scheduler.runCurrent()
        assertEquals(2, phone.connections.size)
        assertFalse(phone.connections.last().closed)
    }
}
