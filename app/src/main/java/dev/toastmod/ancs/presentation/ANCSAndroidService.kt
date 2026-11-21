package dev.toastmod.ancs.presentation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.toastmod.ancs.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.InvocationTargetException

class ANCSAndroidService : Service() {
    private val amsClient = AMSClient()
    val mediaState = amsClient.state
    private val mutableStatus = MutableStateFlow("Disconnected")
    val status = mutableStatus.asStateFlow()
    private val mutableConnectionState = MutableStateFlow(PhoneConnectionState.DISCONNECTED)
    val connectionState = mutableConnectionState.asStateFlow()
    private val mutableNotificationApps = MutableStateFlow<List<NotificationApp>>(emptyList())
    val notificationApps = mutableNotificationApps.asStateFlow()
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var gattClient: ANCSGattServiceClient? = null
    private var targetAddress: String? = null
    private val mutableForgettingPhone = MutableStateFlow(false)
    val forgettingPhone = mutableForgettingPhone.asStateFlow()

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @android.annotation.SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (targetAddress == null || forgettingPhone.value) return
            if (ActivityCompat.checkSelfPermission(this@ANCSAndroidService,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                onStartCommand(null, 0, 0)
                return
            }
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                            closeClient()
                            mutableStatus.value = "Bluetooth disabled; waiting for Bluetooth"
                        }
                        BluetoothAdapter.STATE_ON -> onStartCommand(
                            Intent().putExtra(TARGET_ADDRESS, targetAddress), 0, 0
                        )
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                    )
                    val adapter = getSystemService(BluetoothManager::class.java).adapter
                    if (device?.address == targetAddress && adapter?.isEnabled == true &&
                        intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) ==
                        BluetoothDevice.BOND_NONE
                    ) {
                        Log.i("ANCS", "Selected iPhone was unpaired; stopping its connection")
                        stopConnection("Select a paired iPhone")
                    }
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): ANCSAndroidService = this@ANCSAndroidService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.tag == ANCSNotification.TAG }.forEach {
            manager.cancel(it.tag, it.id)
        }
        ContextCompat.registerReceiver(this, bluetoothReceiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("ANCS", "Cannot start: Bluetooth and notification permissions are required")
            stopConnection("Bluetooth and notification permissions required")
            if (intent?.action == ANCSNotificationAction.INTENT_ACTION) {
                showActionError("Bluetooth and notification permissions required")
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ANCSNotificationAction.INTENT_ACTION) {
            val action = ANCSNotificationAction.fromIntent(intent)
            val client = gattClient
            if (action == null || client == null) {
                Log.w("ANCS", "Notification action has no valid active connection")
                showActionError(getString(R.string.notification_action_unavailable))
            } else {
                client.sendNotificationAction(action)
            }
            if (targetAddress == null) stopSelfResult(startId)
            return if (targetAddress == null) START_NOT_STICKY else START_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, "ANCS connection", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("iPhone connection")
            .setContentText("Receiving notifications and media information")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        ServiceCompat.startForeground(this, 9002, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (forgettingPhone.value) return START_STICKY

        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val address = intent?.getStringExtra(TARGET_ADDRESS)
            ?: preferences.getString(TARGET_ADDRESS, null)
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (address != null && adapter != null && !adapter.isEnabled) {
            closeClient()
            targetAddress = address
            mutableStatus.value = "Bluetooth disabled; waiting for Bluetooth"
            return START_STICKY
        }
        val device = adapter?.bondedDevices?.firstOrNull { it.address == address }
        if (device == null) {
            Log.e("ANCS", "No selected bonded iPhone; select a device in the app")
            stopConnection("Select a paired iPhone")
            return START_NOT_STICKY
        }
        if (gattClient != null && targetAddress == device.address) {
            gattClient?.connect()
            return START_STICKY
        }

        closeClient()
        targetAddress = device.address
        preferences.edit().putString(TARGET_ADDRESS, device.address).apply()
        val channel = NotificationChannel(NOTIF_CHANNEL_ID, "Apple", NotificationManager.IMPORTANCE_DEFAULT)
            .apply {
                description = "Apple Notification Center Service"
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
        val apps = NotificationApps(preferences, device.address) {
            mutableNotificationApps.value = it
        }
        gattClient = ANCSGattServiceClient(
            this, device, ANCSClient(this, channel, apps), amsClient,
            onStatus = { mutableStatus.value = it },
            onConnectionState = { mutableConnectionState.value = it },
            onActionError = ::showActionError
        ).also { it.connect() }
        return START_STICKY
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendAMSCommand(command: Byte, fromCrown: Boolean = false) {
        val client = gattClient
        if (client == null) {
            Log.w("AMS", "Cannot send command without an active client")
            mutableStatus.value = "No active iPhone connection"
            return
        }
        if (fromCrown) client.sendCrownVolume(command) else client.sendAMSCommand(command)
    }

    fun cancelCrownVolume() {
        gattClient?.cancelCrownVolume()
    }

    private fun showActionError(message: String) {
        mutableStatus.value = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    fun setNotificationAppEnabled(identifier: String, enabled: Boolean) {
        val client = gattClient
        check(client != null) { "No active iPhone connection" }
        try {
            client.setAppEnabled(identifier, enabled)
        } catch (error: IllegalStateException) {
            Log.e("ANCS", "Could not update notification filter", error)
            mutableStatus.value = error.message ?: "Could not update notification filter"
            throw error
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    internal suspend fun forgetSelectedPhone(
        address: String,
        removeBond: (BluetoothDevice) -> Boolean = ::requestBondRemoval
    ): Unit = serviceScope.async {
        check(!forgettingPhone.value) { "A forget request is already in progress" }
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        check(preferences.getString(TARGET_ADDRESS, null) == address) {
            "The selected phone has changed; reopen connection information"
        }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        check(adapter != null && adapter.isEnabled) { "Enable Bluetooth before forgetting the phone" }
        val device = adapter.bondedDevices.firstOrNull { it.address == address }
            ?: adapter.getRemoteDevice(address)
        mutableForgettingPhone.value = true
        closeClient()
        mutableStatus.value = "Forgetting selected phone"
        var forgotten = false
        try {
            if (device.bondState != BluetoothDevice.BOND_NONE) {
                check(removeBond(device)) { "Android rejected the individual unpair request" }
                val removed = withTimeoutOrNull(15_000) {
                    while (device.bondState != BluetoothDevice.BOND_NONE) delay(250)
                    true
                }
                check(removed == true) {
                    "Unpair was requested, but removal was not confirmed. Check Bluetooth settings before retrying"
                }
            }
            check(preferences.edit().remove(TARGET_ADDRESS)
                .remove(NotificationApps.blockedKey(address)).commit()) {
                "Pairing removed, but the saved phone selection could not be cleared"
            }
            forgotten = true
            stopConnection("Phone forgotten; pair again in Bluetooth settings")
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            Log.e("ANCS", "Could not forget selected phone", error)
            mutableStatus.value = error.message ?: "Could not forget selected phone"
            throw error
        } catch (error: SecurityException) {
            Log.e("ANCS", "Unpair permission denied", error)
            mutableStatus.value = "Android denied permission to forget this phone"
            throw error
        } finally {
            mutableForgettingPhone.value = false
            if (!forgotten && currentCoroutineContext().isActive) {
                onStartCommand(null, 0, 0)
            }
        }
    }.await()

    @android.annotation.SuppressLint("PrivateApi")
    private fun requestBondRemoval(device: BluetoothDevice): Boolean {
        try {
            return BluetoothDevice::class.java.getMethod("removeBond").invoke(device) == true
        } catch (error: NoSuchMethodException) {
            throw IllegalStateException("Individual unpair is unavailable on this watch", error)
        } catch (error: IllegalAccessException) {
            throw IllegalStateException("Android blocked access to individual unpair", error)
        } catch (error: InvocationTargetException) {
            val cause = error.cause
            if (cause is SecurityException) throw cause
            throw IllegalStateException(
                "Individual unpair failed: ${error.cause?.message ?: error.message}", error
            )
        }
    }

    override fun onDestroy() {
        unregisterReceiver(bluetoothReceiver)
        serviceScope.cancel()
        closeClient()
        targetAddress = null
        mutableStatus.value = "Disconnected"
        super.onDestroy()
    }

    private fun closeClient() {
        gattClient?.close()
        gattClient = null
        mutableConnectionState.value = PhoneConnectionState.DISCONNECTED
    }

    private fun stopConnection(status: String) {
        closeClient()
        targetAddress = null
        mutableStatus.value = status
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val PREFERENCES = "iphone-connection"
        const val TARGET_ADDRESS = "target-address"
        private const val SERVICE_CHANNEL = "ANCSAndroidService"
    }
}
