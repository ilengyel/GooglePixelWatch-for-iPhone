package dev.toastmod.ancs.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ANCSGattServiceClientTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var phone: TestBluetoothDevice
    private lateinit var media: AMSClient
    private lateinit var notifications: ANCSClient
    private lateinit var client: ANCSGattServiceClient
    private val statuses = mutableListOf<String>()
    private val connectionStates = mutableListOf<PhoneConnectionState>()
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        phone = TestBluetoothDevice()
        media = AMSClient()
        val channel = NotificationChannel("test", "Test", NotificationManager.IMPORTANCE_DEFAULT)
        manager.createNotificationChannel(channel)
        notifications = ANCSClient(context, channel)
        client = ANCSGattServiceClient(context, phone.device, notifications, media,
            statuses::add, connectionStates::add)
        scheduler.runCurrent()
    }

    @After
    fun tearDown() {
        client.close()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun advance(milliseconds: Long) {
        scheduler.advanceTimeBy(milliseconds)
        scheduler.runCurrent()
    }

    private fun startConnection(): TestBluetoothDevice.Connection {
        client.connect()
        return phone.connections.last().also {
            it.callback.onConnectionStateChange(it.gatt, BluetoothGatt.GATT_SUCCESS,
                BluetoothProfile.STATE_CONNECTED)
            scheduler.runCurrent()
        }
    }

    private fun drain(connection: TestBluetoothDevice.Connection) {
        scheduler.runCurrent()
        var count = 0
        while (connection.pending.isNotEmpty()) {
            check(++count <= 30) { "Bluetooth operation loop did not settle" }
            connection.finishNext()
            scheduler.runCurrent()
        }
    }

    private fun connected() = startConnection().also {
        drain(it)
        assertEquals("Connected", statuses.last())
        assertTrue(media.state.value.available)
    }

    private fun disconnect(connection: TestBluetoothDevice.Connection) {
        connection.callback.onConnectionStateChange(connection.gatt, 133,
            BluetoothProfile.STATE_DISCONNECTED)
        scheduler.runCurrent()
    }

    private fun playing(connection: TestBluetoothDevice.Connection) {
        connection.notify(connection.update, byteArrayOf(0, 1, 0) + "1,1.0,12".encodeToByteArray())
        scheduler.runCurrent()
    }

    private fun refreshCount(connection: TestBluetoothDevice.Connection) =
        connection.operations.count { it.kind == "descriptor" && it.uuid == AMS_ENTITY_UPDATE &&
            it.value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) }

    @Test
    fun connectionIndicatorWaitsForSetupAndTracksDisconnectRetryAndClose() {
        val first = startConnection()
        assertEquals(PhoneConnectionState.CONNECTING, connectionStates.last())
        drain(first)
        assertEquals(PhoneConnectionState.CONNECTED, connectionStates.last())

        disconnect(first)
        assertEquals(PhoneConnectionState.DISCONNECTED, connectionStates.last())
        advance(999)
        assertEquals(PhoneConnectionState.DISCONNECTED, connectionStates.last())
        advance(1)
        assertEquals(PhoneConnectionState.CONNECTING, connectionStates.last())
        val next = phone.connections.last()
        next.callback.onConnectionStateChange(next.gatt, BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_CONNECTED)
        scheduler.runCurrent()
        assertEquals(PhoneConnectionState.CONNECTING, connectionStates.last())
        drain(next)
        assertEquals(PhoneConnectionState.CONNECTED, connectionStates.last())

        client.close()
        assertEquals(PhoneConnectionState.DISCONNECTED, connectionStates.last())
        val expectedStates = connectionStates.toList()
        first.callback.onConnectionStateChange(first.gatt, BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_CONNECTED)
        advance(120_000)
        assertEquals(expectedStates, connectionStates)
    }

    @Test
    fun reconnectBackoffCapsAtSixtySecondsAndResetsAfterSetup() {
        client.connect()
        for (wait in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L)) {
            val connection = phone.connections.last()
            val count = phone.connections.size
            disconnect(connection)
            assertTrue(connection.closed)
            assertTrue(statuses.last().endsWith("retrying in ${wait / 1000}s"))
            advance(wait - 1)
            assertEquals(count, phone.connections.size)
            advance(1)
            assertEquals(count + 1, phone.connections.size)
        }
        val connection = phone.connections.last()
        connection.callback.onConnectionStateChange(connection.gatt, BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_CONNECTED)
        drain(connection)
        assertEquals("Connected", statuses.last())
        disconnect(connection)
        val count = phone.connections.size
        advance(999)
        assertEquals(count, phone.connections.size)
        advance(1)
        assertEquals(count + 1, phone.connections.size)
    }

    @Test
    fun closingCancelsPendingReconnectsAndConnectionTimeouts() {
        client.connect()
        disconnect(phone.connections.single())
        client.close()
        val expectedStatuses = statuses.toList()
        advance(120_000)
        assertEquals(1, phone.connections.size)
        assertEquals(expectedStatuses, statuses)
    }

    @Test
    fun connectionTimeoutClosesTheGattAndRetries() {
        client.connect()
        assertEquals(PhoneConnectionState.CONNECTING, connectionStates.last())
        val connection = phone.connections.single()
        advance(19_999)
        assertFalse(connection.closed)
        advance(1)
        assertTrue(connection.closed)
        assertEquals("Bluetooth connection timed out; retrying in 1s", statuses.last())
        assertEquals(PhoneConnectionState.DISCONNECTED, connectionStates.last())
        advance(1_000)
        assertEquals(2, phone.connections.size)
        assertEquals(PhoneConnectionState.CONNECTING, connectionStates.last())
    }

    @Test
    fun oldConnectionCallbacksCannotCompleteOrMutateTheNewSession() {
        val old = startConnection()
        disconnect(old)
        advance(1_000)
        val current = phone.connections.last()
        current.callback.onConnectionStateChange(current.gatt, BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_CONNECTED)
        scheduler.runCurrent()
        assertEquals("services", current.pending.single().kind)
        val expectedStatuses = statuses.toList()
        val expectedStates = connectionStates.toList()

        old.finishNext()
        old.notify(old.update, byteArrayOf(2, 2, 0) + "Stale track".encodeToByteArray())
        old.notify(old.remote, byteArrayOf(2, 3, 4))
        old.notify(old.source, byteArrayOf(0, 0, 0, 0, 1, 0, 0, 0))
        old.callback.onServiceChanged(old.gatt)
        old.callback.onConnectionStateChange(old.gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
        scheduler.runCurrent()

        assertEquals(AMSState(), media.state.value)
        assertFalse(notifications.contains(byteArrayOf(1, 0, 0, 0)))
        assertEquals(expectedStatuses, statuses)
        assertEquals(expectedStates, connectionStates)
        assertEquals("services", current.pending.single().kind)
        assertEquals(1, current.operations.size)
        drain(current)
        assertEquals("Connected", statuses.last())
    }

    @Test
    fun operationsAreSerializedAndUnmatchedCallbacksDoNotAdvanceTheQueue() {
        val connection = connected()
        connection.notify(connection.remote, byteArrayOf(2, 3))
        scheduler.runCurrent()
        val before = connection.operations.size
        client.sendAMSCommand(2)
        client.sendAMSCommand(3)
        scheduler.runCurrent()
        assertEquals(before + 1, connection.operations.size)
        assertEquals(1, connection.pending.size)
        assertTrue(connection.pending.single().value.contentEquals(byteArrayOf(2)))

        connection.callback.onCharacteristicWrite(connection.gatt, connection.attribute,
            BluetoothGatt.GATT_SUCCESS)
        connection.callback.onCharacteristicRead(connection.gatt, connection.remote, byteArrayOf(),
            BluetoothGatt.GATT_SUCCESS)
        scheduler.runCurrent()
        assertEquals(before + 1, connection.operations.size)

        connection.finishNext()
        scheduler.runCurrent()
        assertEquals(before + 2, connection.operations.size)
        assertTrue(connection.pending.single().value.contentEquals(byteArrayOf(3)))
        connection.finishNext()
        scheduler.runCurrent()
        assertTrue(connection.pending.isEmpty())
    }

    @Test
    fun mediaCommandsRunInOrderBeforeTheNextNotificationWithoutInterruptingTheActiveRequest() {
        val connection = connected()
        connection.notify(connection.remote, byteArrayOf(2, 3))
        connection.notify(connection.source, byteArrayOf(0, 0, 0, 0, 1, 0, 0, 0))
        scheduler.runCurrent()
        assertEquals(ANCS_CONTROL_POINT, connection.pending.single().uuid)
        connection.finishNext()
        scheduler.runCurrent()
        assertTrue(connection.pending.isEmpty())

        connection.notify(connection.source, byteArrayOf(0, 0, 0, 0, 2, 0, 0, 0))
        scheduler.runCurrent()
        client.sendAMSCommand(2)
        client.sendAMSCommand(3)
        scheduler.runCurrent()
        assertTrue("Commands must wait for the active notification response", connection.pending.isEmpty())

        // Four empty attributes complete the active ANCS response.
        connection.notify(connection.dataSource,
            byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 0, 2, 0, 0, 3, 0, 0))
        scheduler.runCurrent()
        assertEquals(AMS_REMOTE_COMMAND, connection.pending.single().uuid)
        assertTrue(connection.pending.single().value.contentEquals(byteArrayOf(2)))
        connection.finishNext()
        scheduler.runCurrent()
        assertEquals(AMS_REMOTE_COMMAND, connection.pending.single().uuid)
        assertTrue(connection.pending.single().value.contentEquals(byteArrayOf(3)))
        connection.finishNext()
        scheduler.runCurrent()
        assertEquals(ANCS_CONTROL_POINT, connection.pending.single().uuid)
        assertTrue(connection.pending.single().value.copyOfRange(1, 5).contentEquals(byteArrayOf(2, 0, 0, 0)))
    }

    @Test
    fun operationTimeoutClosesTheSessionAndClearsState() {
        val connection = connected()
        connection.notify(connection.remote, byteArrayOf(2))
        scheduler.runCurrent()
        client.sendAMSCommand(2)
        scheduler.runCurrent()
        advance(9_999)
        assertFalse(connection.closed)
        advance(1)
        assertTrue(connection.closed)
        assertEquals(AMSState(), media.state.value)
        assertEquals("Bluetooth operation timed out; retrying in 1s", statuses.last())
        advance(1_000)
        assertEquals(2, phone.connections.size)
    }

    @Test
    fun failedCallbacksAndRejectedWritesTriggerRecovery() {
        val connection = connected()
        connection.notify(connection.remote, byteArrayOf(2))
        scheduler.runCurrent()
        client.sendAMSCommand(2)
        scheduler.runCurrent()
        connection.finishNext(status = 133)
        scheduler.runCurrent()
        assertTrue(connection.closed)
        assertEquals("Bluetooth operation failed: 133; retrying in 1s", statuses.last())

        advance(1_000)
        val next = phone.connections.last()
        next.callback.onConnectionStateChange(next.gatt, BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_CONNECTED)
        drain(next)
        next.notify(next.remote, byteArrayOf(2))
        scheduler.runCurrent()
        next.writeStartStatus = BluetoothStatusCodes.ERROR_UNKNOWN
        client.sendAMSCommand(2)
        scheduler.runCurrent()
        assertTrue(next.closed)
        assertEquals("Could not start Bluetooth WRITE operation; retrying in 1s", statuses.last())
    }

    @Test
    fun fullAttributeReadsAreSerializedAndCannotOverwriteNewerMetadata() {
        val connection = connected()
        connection.notify(connection.update, byteArrayOf(2, 2, 1) + "Old truncated".encodeToByteArray())
        scheduler.runCurrent()
        assertEquals("write", connection.pending.single().kind)
        assertEquals(AMS_ENTITY_ATTRIBUTE, connection.pending.single().uuid)
        assertTrue(connection.pending.single().value.contentEquals(byteArrayOf(2, 2)))
        connection.finishNext()
        scheduler.runCurrent()
        assertEquals("read", connection.pending.single().kind)

        connection.notify(connection.update, byteArrayOf(2, 2, 1) + "New truncated".encodeToByteArray())
        scheduler.runCurrent()
        assertEquals(1, connection.pending.size)
        connection.finishNext(value = "Old full title".encodeToByteArray())
        scheduler.runCurrent()
        assertEquals("New truncated", media.state.value.track.title)
        assertEquals("write", connection.pending.single().kind)
        connection.finishNext()
        scheduler.runCurrent()
        connection.finishNext(value = "New full title".encodeToByteArray())
        scheduler.runCurrent()
        assertEquals("New full title", media.state.value.track.title)

        connection.notify(connection.update, byteArrayOf(2, 2, 1) + "Another partial".encodeToByteArray())
        scheduler.runCurrent()
        connection.finishNext()
        scheduler.runCurrent()
        connection.notify(connection.update, byteArrayOf(2, 2, 0) + "Latest complete".encodeToByteArray())
        scheduler.runCurrent()
        connection.finishNext(value = "Obsolete full title".encodeToByteArray())
        scheduler.runCurrent()
        assertEquals("Latest complete", media.state.value.track.title)
        assertTrue(connection.pending.isEmpty())
    }

    @Test
    fun queuedAttributeReadsAreSkippedWhenACompleteUpdateArrives() {
        val connection = connected()
        connection.notify(connection.remote, byteArrayOf(2))
        scheduler.runCurrent()
        client.sendAMSCommand(2)
        scheduler.runCurrent()
        connection.notify(connection.update, byteArrayOf(2, 2, 1) + "Partial".encodeToByteArray())
        connection.notify(connection.update, byteArrayOf(2, 2, 0) + "Complete".encodeToByteArray())
        scheduler.runCurrent()
        val before = connection.operations.size
        connection.finishNext()
        scheduler.runCurrent()
        assertEquals(before, connection.operations.size)
        assertTrue(connection.pending.isEmpty())
        assertEquals("Complete", media.state.value.track.title)
    }

    @Test
    fun missingMetadataRecoveryIsLimitedToTwoAttemptsPerEpisode() {
        val connection = connected()
        playing(connection)
        advance(2_999)
        assertEquals(0, refreshCount(connection))
        advance(1)
        drain(connection)
        assertEquals(1, refreshCount(connection))
        advance(5_999)
        assertEquals(1, refreshCount(connection))
        advance(1)
        drain(connection)
        assertEquals(2, refreshCount(connection))
        advance(3_000)
        assertEquals("Connected; iPhone has not supplied track information", statuses.last())
        assertEquals(PhoneConnectionState.CONNECTED, connectionStates.last())
        advance(60_000)
        drain(connection)
        assertEquals(2, refreshCount(connection))

        connection.notify(connection.update, byteArrayOf(2, 2, 0) + "Track".encodeToByteArray())
        scheduler.runCurrent()
        assertEquals("Connected", statuses.last())
        connection.notify(connection.update, byteArrayOf(2, 2, 0))
        scheduler.runCurrent()
        advance(3_000)
        drain(connection)
        assertEquals(3, refreshCount(connection))
    }

    @Test
    fun receivingMetadataOrPausingCancelsRecovery() {
        val connection = connected()
        playing(connection)
        advance(2_000)
        connection.notify(connection.update, byteArrayOf(2, 0, 0) + "Artist".encodeToByteArray())
        scheduler.runCurrent()
        advance(15_000)
        drain(connection)
        assertEquals(0, refreshCount(connection))
        assertEquals("Connected", statuses.last())

        connection.notify(connection.update, byteArrayOf(2, 0, 0))
        scheduler.runCurrent()
        advance(3_000)
        drain(connection)
        assertEquals(1, refreshCount(connection))
        connection.notify(connection.update, byteArrayOf(0, 1, 0) + "0,0,12".encodeToByteArray())
        scheduler.runCurrent()
        advance(15_000)
        drain(connection)
        assertEquals(1, refreshCount(connection))
        assertEquals("Connected", statuses.last())
    }

    @Test
    fun disconnectCancelsMetadataRecoveryAndClearsOnlyMirroredNotifications() {
        val connection = connected()
        val uid = byteArrayOf(1, 0, 0, 0)
        notifications.onNotificationSource(EventID.NotificationAdded.b, 0, 0, uid)
        notifications.onAttributes(uid, ANCSAttributes("app", "Old", "", "Message"))
        val unrelated = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()
        manager.notify(9002, unrelated)
        manager.notify("Other", 1, unrelated)
        playing(connection)
        advance(2_000)
        disconnect(connection)
        val expectedStatuses = statuses.toList()
        advance(15_000)

        assertEquals(0, refreshCount(connection))
        assertTrue(connection.closed)
        assertEquals(AMSState(), media.state.value)
        assertFalse(notifications.contains(uid))
        assertEquals(setOf(null to 9002, "Other" to 1),
            manager.activeNotifications.map { it.tag to it.id }.toSet())
        assertFalse(statuses.drop(expectedStatuses.size).any { it.contains("track information") })
    }

    @Test
    fun commandsWithdrawnWhileQueuedAreNotSentAndUnavailableCommandsAreRejected() {
        val connection = connected()
        val before = connection.operations.size
        client.sendAMSCommand(2)
        scheduler.runCurrent()
        assertEquals("Media command unavailable", statuses.last())
        assertEquals(PhoneConnectionState.CONNECTED, connectionStates.last())
        assertEquals(before, connection.operations.size)

        connection.notify(connection.remote, byteArrayOf(2, 3))
        scheduler.runCurrent()
        client.sendAMSCommand(2)
        client.sendAMSCommand(3)
        scheduler.runCurrent()
        connection.notify(connection.remote, byteArrayOf(2))
        scheduler.runCurrent()
        connection.finishNext()
        scheduler.runCurrent()
        assertEquals("This media command is not supported by the iPhone", statuses.last())
        assertEquals(PhoneConnectionState.CONNECTED, connectionStates.last())
        assertEquals(before + 1, connection.operations.size)
        assertTrue(connection.pending.isEmpty())
    }

    @Test
    fun closingDuringAnOperationCancelsTimeoutsAndIgnoresLateCompletion() {
        val connection = connected()
        connection.notify(connection.remote, byteArrayOf(2))
        scheduler.runCurrent()
        client.sendAMSCommand(2)
        scheduler.runCurrent()
        client.close()
        val expectedStatuses = statuses.toList()
        connection.finishNext()
        advance(120_000)
        assertTrue(connection.closed)
        assertEquals(1, phone.connections.size)
        assertEquals(expectedStatuses, statuses)
        assertEquals(AMSState(), media.state.value)
    }
}
