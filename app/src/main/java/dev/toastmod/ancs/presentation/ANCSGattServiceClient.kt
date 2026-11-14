package dev.toastmod.ancs.presentation

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID

enum class PhoneConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

@SuppressLint("MissingPermission")
class ANCSGattServiceClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val client: ANCSClient,
    private val amsClient: AMSClient,
    private val onStatus: (String) -> Unit,
    private val onConnectionState: (PhoneConnectionState) -> Unit = {}
) : BluetoothGattCallback() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var gatt: BluetoothGatt? = null
    private var sessionJob: Job? = null
    private var connectionJob: Job? = null
    private var retryDelay = 1_000L
    private var queue: Channel<Operation>? = null
    private val requestedNotifications = linkedMapOf<Int, ByteArray>()
    private var notificationQueued = false
    private val attributeVersions = mutableMapOf<Pair<Byte, Byte>, Int>()
    private var pendingCallback: PendingCallback? = null
    private var pendingNotification: PendingNotification? = null
    private var refreshQueued = false
    private var connectedStatus = "Connected"

    private sealed interface Operation {
        data object Notification : Operation
        data class Command(val command: Byte) : Operation
        data class Attribute(val entity: Byte, val attribute: Byte, val version: Int) : Operation
        data object RefreshTrack : Operation
    }

    private enum class CallbackKind { SERVICES, WRITE, READ, DESCRIPTOR }

    private data class PendingCallback(
        val kind: CallbackKind,
        val uuid: UUID?,
        val result: CompletableDeferred<ByteArray>
    )

    private data class PendingNotification(
        val response: ANCSResponse,
        val result: CompletableDeferred<ANCSAttributes>
    )

    private class GattFailure(message: String) : Exception(message)
    private class GattStatusFailure(val status: Int) : Exception("GATT status $status")

    init {
        scope.launch {
            amsClient.state.map { it.available && it.player.isPlaying && !it.track.hasData }
                .distinctUntilChanged()
                .collectLatest { missing ->
                    if (missing) {
                        repeat(2) { attempt ->
                            delay(3_000L * (attempt + 1))
                            if (!refreshQueued) {
                                refreshQueued = true
                                enqueue(Operation.RefreshTrack)
                            }
                        }
                        delay(3_000)
                        Log.w("AMS", "Track metadata still absent after bounded recovery")
                        onStatus("Connected; iPhone has not supplied track information")
                    } else if (amsClient.state.value.available) {
                        onStatus(connectedStatus)
                    }
                }
        }
    }

    fun connect() {
        if (gatt != null) return
        connectionJob?.cancel()
        onConnectionState(PhoneConnectionState.CONNECTING)
        onStatus("Connecting to iPhone")
        try {
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                reconnect("Could not create Bluetooth connection")
                return
            }
            connectionJob = scope.launch {
                delay(20_000)
                reconnect("Bluetooth connection timed out")
            }
        } catch (error: SecurityException) {
            Log.e("ANCS", "Bluetooth permission unavailable", error)
            onConnectionState(PhoneConnectionState.DISCONNECTED)
            onStatus("Bluetooth permission required; reopen the app")
        }
    }

    fun close() {
        scope.cancel()
        disposeConnection()
    }

    private fun disposeConnection() {
        onConnectionState(PhoneConnectionState.DISCONNECTED)
        connectionJob?.cancel()
        sessionJob?.cancel()
        sessionJob = null
        queue?.close()
        queue = null
        pendingCallback?.result?.cancel()
        pendingCallback = null
        pendingNotification?.result?.cancel()
        pendingNotification = null
        requestedNotifications.clear()
        notificationQueued = false
        attributeVersions.clear()
        refreshQueued = false
        val previous = gatt
        gatt = null
        try {
            previous?.close()
        } catch (error: SecurityException) {
            Log.e("ANCS", "Permission revoked while closing Bluetooth", error)
        }
        amsClient.reset()
        client.clear()
    }

    private fun reconnect(reason: String) {
        Log.w("ANCS", reason)
        disposeConnection()
        onStatus("$reason; retrying in ${retryDelay / 1000}s")
        val wait = retryDelay
        retryDelay = (retryDelay * 2).coerceAtMost(60_000)
        connectionJob = scope.launch {
            delay(wait)
            connect()
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        scope.launch {
            if (gatt !== this@ANCSGattServiceClient.gatt) return@launch
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        reconnect("Bluetooth connection failed: $status")
                        return@launch
                    }
                    connectionJob?.cancel()
                    startSession(gatt)
                }
                BluetoothProfile.STATE_DISCONNECTED -> reconnect("Bluetooth disconnected: $status")
            }
        }
    }

    override fun onServiceChanged(gatt: BluetoothGatt) {
        scope.launch {
            if (gatt === this@ANCSGattServiceClient.gatt) {
                // Recreate the connection so no operation can use an invalidated service handle.
                reconnect("iPhone services changed; rediscovering")
            }
        }
    }

    private fun startSession(gatt: BluetoothGatt) {
        if (sessionJob?.isActive == true) return
        val operations = Channel<Operation>(64)
        queue = operations
        sessionJob = scope.launch {
            try {
                awaitCallback(CallbackKind.SERVICES) { gatt.discoverServices() }
                connectedStatus = setup(gatt)
                retryDelay = 1_000
                onConnectionState(PhoneConnectionState.CONNECTED)
                onStatus(connectedStatus)
                for (operation in operations) {
                    when (operation) {
                        Operation.Notification -> {
                            notificationQueued = false
                            val uid = requestedNotifications.values.firstOrNull()
                            if (uid != null) receiveNotification(gatt, uid)
                            scheduleNotification()
                        }
                        is Operation.Command -> {
                            if (operation.command in amsClient.state.value.supportedCommands) {
                                write(gatt, characteristic(gatt, AMS_SERVICE, AMS_REMOTE_COMMAND),
                                    byteArrayOf(operation.command))
                            } else {
                                Log.w("AMS", "Command is no longer supported: ${operation.command}")
                                onStatus("This media command is not supported by the iPhone")
                            }
                        }
                        is Operation.Attribute -> readAttribute(gatt, operation)
                        Operation.RefreshTrack -> {
                            try {
                                val state = amsClient.state.value
                                if (state.available && state.player.isPlaying && !state.track.hasData) {
                                    val update = characteristic(gatt, AMS_SERVICE, AMS_ENTITY_UPDATE)
                                    subscribe(gatt, update, false)
                                    subscribe(gatt, update)
                                    registerMedia(gatt, update)
                                }
                            } finally {
                                refreshQueued = false
                            }
                        }
                    }
                }
            } catch (error: TimeoutCancellationException) {
                reconnect("Bluetooth operation timed out")
            } catch (error: GattFailure) {
                reconnect(error.message ?: "Bluetooth operation failed")
            } catch (error: GattStatusFailure) {
                reconnect("Bluetooth operation failed: ${error.status}")
            } catch (error: SecurityException) {
                Log.e("ANCS", "Bluetooth or notification permission revoked", error)
                disposeConnection()
                onStatus("Permission revoked; reopen the app")
            }
        }
    }

    private suspend fun setup(gatt: BluetoothGatt): String {
        val serviceChanged = gatt.getService(GENERIC_ATTRIBUTE)
            ?.getCharacteristic(SERVICE_CHANGED)
        if (serviceChanged?.getDescriptor(CCCD) != null) subscribe(gatt, serviceChanged)

        val ancs = gatt.getService(ANCS_SERVICE)
        val ams = gatt.getService(AMS_SERVICE)
        if (ancs == null && ams == null) {
            throw GattFailure("Selected device does not expose ANCS or AMS; check iPhone sharing settings")
        }
        if (ancs != null) {
            subscribe(gatt, characteristic(gatt, ANCS_SERVICE, ANCS_DATA_SOURCE))
            subscribe(gatt, characteristic(gatt, ANCS_SERVICE, ANCS_NOTIFICAITON_SOURCE))
        } else {
            Log.w("ANCS", "iPhone notification service is not available")
        }
        if (ams != null) {
            val remote = ams.getCharacteristic(AMS_REMOTE_COMMAND)
            if (remote != null && remote.getDescriptor(CCCD) != null) subscribe(gatt, remote)
            val update = ams.getCharacteristic(AMS_ENTITY_UPDATE)
            if (update != null && update.getDescriptor(CCCD) != null) {
                subscribe(gatt, update)
                registerMedia(gatt, update)
                amsClient.setAvailable(true)
            } else {
                Log.w("AMS", "iPhone does not expose media updates")
            }
        }
        return when {
            ancs == null -> "Connected; notifications unavailable; check iPhone sharing settings"
            !amsClient.state.value.available -> "Connected; media unavailable"
            else -> "Connected"
        }
    }

    private suspend fun registerMedia(gatt: BluetoothGatt, update: BluetoothGattCharacteristic) {
        write(gatt, update, byteArrayOf(AMSEntityID.Track.b,
            AMSTrackAttributeID.Artist.b, AMSTrackAttributeID.Title.b))
        write(gatt, update, byteArrayOf(AMSEntityID.Player.b, AMSPlayerAttributeID.PlaybackInfo.b))
    }

    private fun characteristic(gatt: BluetoothGatt, service: UUID, uuid: UUID): BluetoothGattCharacteristic =
        gatt.getService(service)?.getCharacteristic(uuid)
            ?: throw GattFailure("Missing Bluetooth characteristic $uuid")

    private suspend fun subscribe(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean = true
    ) {
        val descriptor = characteristic.getDescriptor(CCCD)
            ?: throw GattFailure("Missing subscription descriptor for ${characteristic.uuid}")
        if (enabled && !gatt.setCharacteristicNotification(characteristic, true)) {
            throw GattFailure("Could not enable local notifications for ${characteristic.uuid}")
        }
        val value = when {
            !enabled -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        awaitCallback(CallbackKind.DESCRIPTOR, characteristic.uuid) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        }
    }

    private suspend fun write(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray
    ) {
        val type = when {
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> throw GattFailure("Bluetooth characteristic is not writable: ${characteristic.uuid}")
        }
        // Android reports local write completion even for WRITE_TYPE_NO_RESPONSE.
        awaitCallback(CallbackKind.WRITE, characteristic.uuid) {
            gatt.writeCharacteristic(characteristic, data, type) == BluetoothStatusCodes.SUCCESS
        }
    }

    private suspend fun awaitCallback(
        kind: CallbackKind,
        uuid: UUID? = null,
        start: () -> Boolean
    ): ByteArray {
        check(pendingCallback == null)
        val pending = PendingCallback(kind, uuid, CompletableDeferred())
        pendingCallback = pending
        try {
            if (!start()) throw GattFailure("Could not start Bluetooth $kind operation")
            return withTimeout(10_000) { pending.result.await() }
        } finally {
            if (pendingCallback === pending) pendingCallback = null
        }
    }

    private fun complete(
        gatt: BluetoothGatt,
        kind: CallbackKind,
        uuid: UUID?,
        status: Int,
        value: ByteArray = byteArrayOf()
    ) {
        scope.launch {
            if (gatt !== this@ANCSGattServiceClient.gatt) return@launch
            val pending = pendingCallback
            if (pending == null || pending.kind != kind || pending.uuid != uuid) {
                Log.w("ANCS", "Ignoring unmatched Bluetooth callback: $kind $uuid")
                return@launch
            }
            if (status == BluetoothGatt.GATT_SUCCESS) pending.result.complete(value)
            else pending.result.completeExceptionally(GattStatusFailure(status))
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
        complete(gatt, CallbackKind.SERVICES, null, status)

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
        complete(gatt, CallbackKind.DESCRIPTOR, descriptor.characteristic.uuid, status)

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
        complete(gatt, CallbackKind.WRITE, characteristic.uuid, status)

    override fun onCharacteristicRead(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int
    ) = complete(gatt, CallbackKind.READ, characteristic.uuid, status, value.copyOf())

    private fun enqueue(operation: Operation) {
        if (queue?.trySend(operation)?.isSuccess != true) {
            reconnect("Bluetooth request queue is unavailable or full")
        }
    }

    private fun scheduleNotification() {
        if (requestedNotifications.isNotEmpty() && !notificationQueued) {
            notificationQueued = true
            enqueue(Operation.Notification)
        }
    }

    fun sendAMSCommand(command: Byte) {
        val state = amsClient.state.value
        if (!state.available || command !in state.supportedCommands) {
            Log.w("AMS", "Cannot send unavailable media command $command")
            onStatus("Media command unavailable")
            return
        }
        enqueue(Operation.Command(command))
    }

    private suspend fun receiveNotification(gatt: BluetoothGatt, uid: ByteArray) {
        val key = uid.notificationUid()
        requestedNotifications.remove(key)
        if (!client.contains(uid)) return
        val pending = PendingNotification(ANCSResponse(uid), CompletableDeferred())
        pendingNotification = pending
        try {
            val attributes = byteArrayOf(0, 1, 0xff.toByte(), 1, 2, 0xff.toByte(), 1, 3, 0xff.toByte(), 1)
            write(gatt, characteristic(gatt, ANCS_SERVICE, ANCS_CONTROL_POINT),
                byteArrayOf(0) + uid + attributes)
            val response = withTimeout(10_000) { pending.result.await() }
            client.onAttributes(uid, response)
        } catch (error: GattStatusFailure) {
            // A notification can disappear on the phone before its queued request reaches it.
            if (error.status != 0xa2) throw error
            Log.w("ANCS", "iPhone no longer has the requested notification")
        } finally {
            if (pendingNotification === pending) pendingNotification = null
        }
    }

    private suspend fun readAttribute(gatt: BluetoothGatt, operation: Operation.Attribute) {
        val key = operation.entity to operation.attribute
        if (attributeVersions[key] != operation.version) return
        val attribute = gatt.getService(AMS_SERVICE)?.getCharacteristic(AMS_ENTITY_ATTRIBUTE)
        if (attribute == null) {
            Log.w("AMS", "Full media attribute reads are not supported by the iPhone")
            onStatus("iPhone supplied shortened media information")
            return
        }
        try {
            write(gatt, attribute, byteArrayOf(operation.entity, operation.attribute))
            val value = awaitCallback(CallbackKind.READ, attribute.uuid) { gatt.readCharacteristic(attribute) }
            if (attributeVersions[key] == operation.version) {
                amsClient.onEntityUpdate(byteArrayOf(operation.entity, operation.attribute, 0) + value)
            }
        } catch (error: GattStatusFailure) {
            if (error.status != 0xa2) throw error
            Log.w("AMS", "Requested media attribute is absent")
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
    ) {
        val data = value.copyOf()
        scope.launch {
            if (gatt !== this@ANCSGattServiceClient.gatt) return@launch
            when (characteristic.uuid) {
                ANCS_NOTIFICAITON_SOURCE -> {
                    if (data.size != 8) {
                        reconnect("Invalid ANCS notification event")
                        return@launch
                    }
                    val uid = data.copyOfRange(4, 8)
                    if (client.onNotificationSource(data[0], data[1], data[2], uid)) {
                        requestedNotifications[uid.notificationUid()] = uid
                    }
                    requestedNotifications.entries.removeAll { !client.contains(it.value) }
                    scheduleNotification()
                }
                ANCS_DATA_SOURCE -> {
                    val pending = pendingNotification
                    if (pending == null) {
                        Log.w("ANCS", "Ignoring notification data without an active request")
                        return@launch
                    }
                    try {
                        pending.response.append(data)?.let { pending.result.complete(it) }
                    } catch (error: IllegalArgumentException) {
                        pending.result.completeExceptionally(GattFailure(error.message ?: "Invalid ANCS response"))
                    }
                }
                AMS_REMOTE_COMMAND -> amsClient.onSupportedCommands(data)
                AMS_ENTITY_UPDATE -> {
                    if (data.size < 3) {
                        Log.w("AMS", "Ignoring incomplete media update")
                        return@launch
                    }
                    val key = data[0] to data[1]
                    val version = (attributeVersions[key] ?: 0) + 1
                    attributeVersions[key] = version
                    amsClient.onEntityUpdate(data)
                    if (data[2].toInt() and 1 != 0) {
                        enqueue(Operation.Attribute(data[0], data[1], version))
                    }
                }
                SERVICE_CHANGED -> reconnect("iPhone services changed; rediscovering")
            }
        }
    }

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val GENERIC_ATTRIBUTE = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
        private val SERVICE_CHANGED = UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")
    }
}
