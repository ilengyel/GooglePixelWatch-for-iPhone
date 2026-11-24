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
import android.os.SystemClock
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
import kotlinx.coroutines.selects.select
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
    private val onConnectionState: (PhoneConnectionState) -> Unit = {},
    private val onActionError: (String) -> Unit = {}
) : BluetoothGattCallback() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var gatt: BluetoothGatt? = null
    private var sessionJob: Job? = null
    private var connectionJob: Job? = null
    private var retryDelay = 1_000L
    private var queue: Channel<Operation>? = null
    private var commandQueue: Channel<Operation>? = null
    private val pendingActions = mutableSetOf<Int>()
    private var crownCommand: Pair<Byte, Long>? = null
    private var crownQueued = false
    private var lastCrownWrite: Long? = null
    private val requestedNotifications = linkedMapOf<Int, ByteArray>()
    private var notificationQueued = false
    private val attributeVersions = mutableMapOf<Pair<Byte, Byte>, Int>()
    private var pendingCallback: PendingCallback? = null
    private var pendingData: PendingData? = null
    private val queuedAppNames = mutableSetOf<String>()
    private var refreshQueued = false
    private var connectedStatus = "Connected"
    private var servicesInvalidated: ServicesInvalidated? = null

    private sealed interface Operation {
        data object Notification : Operation
        data class AppName(val identifier: String) : Operation
        data class Command(val command: Byte) : Operation
        data class NotificationAction(val action: ANCSNotificationAction) : Operation
        data object CrownVolume : Operation
        data class Attribute(val entity: Byte, val attribute: Byte, val version: Int) : Operation
        data object RefreshTrack : Operation
    }

    private enum class CallbackKind { SERVICES, WRITE, READ, DESCRIPTOR }

    private data class PendingCallback(
        val kind: CallbackKind,
        val uuid: UUID?,
        val result: CompletableDeferred<ByteArray>
    )

    private data class PendingData(
        val result: CompletableDeferred<*>,
        val append: (ByteArray) -> Unit
    )

    private class GattFailure(message: String) : Exception(message)
    private class GattStatusFailure(val status: Int) : Exception("GATT status $status")
    private class ServicesInvalidated : Exception()

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
        val previous = gatt
        gatt = null
        clearSession()
        try {
            previous?.close()
        } catch (error: SecurityException) {
            Log.e("ANCS", "Permission revoked while closing Bluetooth", error)
        }
    }

    private fun clearSession() {
        queue?.close()
        queue = null
        commandQueue?.close()
        commandQueue = null
        pendingActions.clear()
        crownCommand = null
        crownQueued = false
        lastCrownWrite = null
        pendingCallback?.result?.cancel()
        pendingCallback = null
        pendingData?.result?.cancel()
        pendingData = null
        queuedAppNames.clear()
        requestedNotifications.clear()
        notificationQueued = false
        attributeVersions.clear()
        refreshQueued = false
        servicesInvalidated = null
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
            if (gatt === this@ANCSGattServiceClient.gatt) invalidateServices()
        }
    }

    private fun invalidateServices() {
        if (queue == null || servicesInvalidated != null) return
        val invalidated = ServicesInvalidated()
        servicesInvalidated = invalidated
        Log.i("ANCS", "iPhone services changed; rediscovering on the existing link")
        onConnectionState(PhoneConnectionState.CONNECTING)
        onStatus("iPhone services changed; rediscovering")
        // Wake idle work or a data response wait, but let any active GATT request drain.
        commandQueue?.close(invalidated)
        queue?.close(invalidated)
        pendingData?.result?.completeExceptionally(invalidated)
    }

    private fun startSession(gatt: BluetoothGatt) {
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch {
            try {
                while (true) {
                    try {
                        runSession(gatt)
                    } catch (_: ServicesInvalidated) {
                        clearSession()
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

    private suspend fun runSession(gatt: BluetoothGatt) {
        val operations = Channel<Operation>(64)
        val commands = Channel<Operation>(64)
        queue = operations
        commandQueue = commands
        awaitCallback(CallbackKind.SERVICES) { gatt.discoverServices() }
        connectedStatus = setup(gatt)
        retryDelay = 1_000
        onConnectionState(PhoneConnectionState.CONNECTED)
        onStatus(connectedStatus)
        Log.i("ANCS", connectedStatus)
        while (true) {
            val operation = commands.tryReceive().getOrNull() ?: select<Operation> {
                commands.onReceive { it }
                operations.onReceive { it }
            }
            servicesInvalidated?.let { throw it }
            when (operation) {
                Operation.Notification -> {
                    notificationQueued = false
                    val uid = requestedNotifications.values.firstOrNull()
                    if (uid != null) receiveNotification(gatt, uid)
                    scheduleNotification()
                }
                is Operation.AppName -> {
                    try {
                        receiveAppName(gatt, operation.identifier)
                    } finally {
                        queuedAppNames.remove(operation.identifier)
                    }
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
                is Operation.NotificationAction -> performNotificationAction(gatt, operation.action)
                Operation.CrownVolume -> {
                    val pending = crownCommand
                    crownCommand = null
                    crownQueued = false
                    if (pending != null) {
                        val (command, timestamp) = pending
                        val state = amsClient.state.value
                        val now = SystemClock.elapsedRealtime()
                        val lastWrite = lastCrownWrite
                        if (now - timestamp <= 500 && (lastWrite == null || now - lastWrite >= 200) &&
                            state.available && command in state.supportedCommands
                        ) {
                            lastCrownWrite = now
                            write(gatt, characteristic(gatt, AMS_SERVICE, AMS_REMOTE_COMMAND),
                                byteArrayOf(command))
                        } else {
                            Log.d("AMS", "Dropping expired, rate-limited or unavailable crown volume command")
                        }
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
    }

    private suspend fun setup(gatt: BluetoothGatt): String {
        // Android manages Service Changed indications and delivers onServiceChanged.
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
            AMSTrackAttributeID.Artist.b, AMSTrackAttributeID.Album.b,
            AMSTrackAttributeID.Title.b, AMSTrackAttributeID.Duration.b))
        write(gatt, update, byteArrayOf(AMSEntityID.Player.b,
            AMSPlayerAttributeID.PlaybackInfo.b, AMSPlayerAttributeID.Volume.b))
        write(gatt, update, byteArrayOf(AMSEntityID.Queue.b,
            AMSQueueAttributeID.ShuffleMode.b, AMSQueueAttributeID.RepeatMode.b))
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
        servicesInvalidated?.let { throw it }
        check(pendingCallback == null)
        val pending = PendingCallback(kind, uuid, CompletableDeferred())
        pendingCallback = pending
        try {
            if (!start()) throw GattFailure("Could not start Bluetooth $kind operation")
            val value = withTimeout(10_000) { pending.result.await() }
            servicesInvalidated?.let { throw it }
            return value
        } catch (error: GattStatusFailure) {
            throw servicesInvalidated ?: error
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
        if (servicesInvalidated != null) return
        val sent = if (operation is Operation.Command ||
            operation is Operation.NotificationAction || operation == Operation.CrownVolume
        ) {
            commandQueue?.trySend(operation)?.isSuccess
        } else {
            queue?.trySend(operation)?.isSuccess
        }
        if (sent != true) {
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
        if (servicesInvalidated != null || !state.available || command !in state.supportedCommands) {
            Log.w("AMS", "Cannot send unavailable media command $command")
            onStatus("Media command unavailable")
            return
        }
        enqueue(Operation.Command(command))
    }

    fun sendCrownVolume(command: Byte) {
        val state = amsClient.state.value
        if (servicesInvalidated != null || command !in setOf(AMSCommand.VOLUME_UP, AMSCommand.VOLUME_DOWN) ||
            !state.available || command !in state.supportedCommands
        ) {
            Log.w("AMS", "Cannot send unavailable crown volume command")
            onStatus("Crown volume unavailable")
            return
        }
        crownCommand = command to SystemClock.elapsedRealtime()
        if (!crownQueued) {
            crownQueued = true
            enqueue(Operation.CrownVolume)
        }
    }

    fun sendNotificationAction(action: ANCSNotificationAction) {
        if (gatt == null || commandQueue == null || servicesInvalidated != null ||
            !client.isActionAvailable(action)
        ) {
            actionError("Notification action is no longer available")
            return
        }
        if (!pendingActions.add(action.uid)) {
            Log.d("ANCS", "Notification action already pending")
            return
        }
        enqueue(Operation.NotificationAction(action))
    }

    private fun actionError(message: String) {
        Log.w("ANCS", message)
        onStatus(message)
        onActionError(message)
    }

    private suspend fun performNotificationAction(gatt: BluetoothGatt, action: ANCSNotificationAction) {
        try {
            if (!client.isActionAvailable(action)) {
                actionError("Notification action is no longer available")
                return
            }
            val uid = ByteArray(4) { (action.uid ushr (it * 8)).toByte() }
            write(gatt, characteristic(gatt, ANCS_SERVICE, ANCS_CONTROL_POINT),
                byteArrayOf(2) + uid + byteArrayOf(action.action))
            client.completeAction(action)
        } catch (error: GattStatusFailure) {
            if (error.status !in 0xa0..0xa3) throw error
            actionError("iPhone rejected notification action (GATT ${error.status})")
        } finally {
            pendingActions.remove(action.uid)
        }
    }

    fun setAppEnabled(identifier: String, enabled: Boolean) {
        client.apps.setEnabled(identifier, enabled)
        client.applyFilters()
        requestedNotifications.entries.removeAll {
            client.appIdentifier(it.value) != null && !client.canFetch(it.value)
        }
    }

    private suspend fun <T> requestData(
        gatt: BluetoothGatt,
        command: ByteArray,
        append: (ByteArray) -> T?
    ): T {
        val result = CompletableDeferred<T>()
        val pending = PendingData(result) { fragment ->
            append(fragment)?.let { result.complete(it) }
        }
        check(pendingData == null)
        pendingData = pending
        try {
            write(gatt, characteristic(gatt, ANCS_SERVICE, ANCS_CONTROL_POINT), command)
            return withTimeout(10_000) { result.await() }
        } finally {
            if (pendingData === pending) pendingData = null
        }
    }

    private suspend fun receiveNotification(gatt: BluetoothGatt, uid: ByteArray) {
        val key = uid.notificationUid()
        requestedNotifications.remove(key)
        val revision = client.revision(uid) ?: return
        try {
            val identifier = client.appIdentifier(uid)
            if (identifier == null) {
                val parser = ANCSResponse(uid, setOf(0))
                val response = requestData(gatt, byteArrayOf(0) + uid + byteArrayOf(0), parser::append)
                if (client.revision(uid) != revision) return
                if (response.appIdentifier.isBlank() || '\u0000' in response.appIdentifier) {
                    Log.w("ANCS", "Notification has no valid app identifier")
                    return
                }
                client.identify(uid, response.appIdentifier)
                if (client.canFetch(uid)) requestedNotifications[key] = uid
            } else if (client.canFetch(uid)) {
                val actionAttributes = client.actionAttributeIds(uid)
                val parser = ANCSResponse(uid, setOf(1, 2, 3) + actionAttributes)
                val attributes = byteArrayOf(1, 0xff.toByte(), 1, 2, 0xff.toByte(), 1, 3, 0xff.toByte(), 1) +
                    actionAttributes.map { it.toByte() }.toByteArray()
                val response = requestData(gatt, byteArrayOf(0) + uid + attributes, parser::append)
                if (client.revision(uid) != revision || !client.canFetch(uid)) return
                client.onAttributes(uid, response.copy(appIdentifier = identifier))
                if (client.apps.needsName(identifier) && queuedAppNames.add(identifier)) {
                    enqueue(Operation.AppName(identifier))
                }
            }
        } catch (error: GattStatusFailure) {
            // A notification can disappear on the phone before its queued request reaches it.
            if (error.status != 0xa2) throw error
            Log.w("ANCS", "iPhone no longer has the requested notification")
        }
    }

    private suspend fun receiveAppName(gatt: BluetoothGatt, identifier: String) {
        if (!client.apps.isEnabled(identifier) || !client.apps.needsName(identifier)) return
        val parser = ANCSAppResponse(identifier)
        val name = try {
            requestData(gatt, byteArrayOf(1) + identifier.encodeToByteArray() + byteArrayOf(0, 0),
                parser::append)
        } catch (error: GattStatusFailure) {
            if (error.status != 0xa2) throw error
            Log.w("ANCS", "iPhone no longer has the requested app")
            ""
        }
        if (name.isBlank()) Log.w("ANCS", "App display name unavailable; using identifier")
        client.onAppName(identifier, name)
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
            if (gatt !== this@ANCSGattServiceClient.gatt || queue == null || servicesInvalidated != null) {
                return@launch
            }
            when (characteristic.uuid) {
                ANCS_NOTIFICAITON_SOURCE -> {
                    if (data.size != 8) {
                        reconnect("Invalid ANCS notification event")
                        return@launch
                    }
                    val uid = data.copyOfRange(4, 8)
                    if (client.onNotificationSource(data[0], data[1], data[2], uid) &&
                        (client.appIdentifier(uid) == null || client.canFetch(uid))
                    ) {
                        requestedNotifications[uid.notificationUid()] = uid
                    }
                    requestedNotifications.entries.removeAll { !client.contains(it.value) }
                    scheduleNotification()
                }
                ANCS_DATA_SOURCE -> {
                    val pending = pendingData
                    if (pending == null) {
                        Log.w("ANCS", "Ignoring notification data without an active request")
                        return@launch
                    }
                    try {
                        pending.append(data)
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
                SERVICE_CHANGED -> invalidateServices()
            }
        }
    }

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val SERVICE_CHANGED = UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")
    }
}
