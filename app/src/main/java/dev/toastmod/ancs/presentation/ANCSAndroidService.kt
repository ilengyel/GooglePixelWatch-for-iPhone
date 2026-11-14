package dev.toastmod.ancs.presentation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.toastmod.ancs.R
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
    private val binder = LocalBinder()
    private var gattClient: ANCSGattServiceClient? = null
    private var targetAddress: String? = null
    private var forgettingPhone = false

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (forgettingPhone) return START_NOT_STICKY
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("ANCS", "Cannot start: Bluetooth and notification permissions are required")
            closeClient()
            mutableStatus.value = "Bluetooth and notification permissions required"
            stopSelf()
            return START_NOT_STICKY
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

        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val address = intent?.getStringExtra(TARGET_ADDRESS)
            ?: preferences.getString(TARGET_ADDRESS, null)
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        val device = adapter?.bondedDevices?.firstOrNull { it.address == address }
        if (device == null) {
            Log.e("ANCS", "No selected bonded iPhone; select a device in the app")
            closeClient()
            mutableStatus.value = "Select a paired iPhone"
            stopSelf()
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
        gattClient = ANCSGattServiceClient(
            this, device, ANCSClient(this, channel), amsClient,
            onStatus = { mutableStatus.value = it },
            onConnectionState = { mutableConnectionState.value = it }
        ).also { it.connect() }
        return START_STICKY
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendAMSCommand(command: Byte) {
        val client = gattClient
        if (client == null) {
            Log.w("AMS", "Cannot send command without an active client")
            mutableStatus.value = "No active iPhone connection"
            return
        }
        client.sendAMSCommand(command)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    internal suspend fun forgetSelectedPhone(
        address: String,
        removeBond: (BluetoothDevice) -> Boolean = ::requestBondRemoval
    ) {
        check(!forgettingPhone) { "A forget request is already in progress" }
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        check(preferences.getString(TARGET_ADDRESS, null) == address) {
            "The selected phone has changed; reopen connection information"
        }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        check(adapter != null && adapter.isEnabled) { "Enable Bluetooth before forgetting the phone" }
        val device = adapter.bondedDevices.firstOrNull { it.address == address }
            ?: adapter.getRemoteDevice(address)
        forgettingPhone = true
        closeClient()
        mutableStatus.value = "Forgetting selected phone"
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
            check(preferences.edit().remove(TARGET_ADDRESS).commit()) {
                "Pairing removed, but the saved phone selection could not be cleared"
            }
            mutableStatus.value = "Phone forgotten; pair again in Bluetooth settings"
            stopSelf()
        } catch (error: IllegalStateException) {
            Log.e("ANCS", "Could not forget selected phone", error)
            mutableStatus.value = error.message ?: "Could not forget selected phone"
            throw error
        } catch (error: SecurityException) {
            Log.e("ANCS", "Unpair permission denied", error)
            mutableStatus.value = "Android denied permission to forget this phone"
            throw error
        } finally {
            forgettingPhone = false
        }
    }

    @android.annotation.SuppressLint("PrivateApi")
    private fun requestBondRemoval(device: BluetoothDevice): Boolean {
        try {
            return BluetoothDevice::class.java.getMethod("removeBond").invoke(device) == true
        } catch (error: NoSuchMethodException) {
            throw IllegalStateException("Individual unpair is unavailable on this watch", error)
        } catch (error: IllegalAccessException) {
            throw IllegalStateException("Android blocked access to individual unpair", error)
        } catch (error: InvocationTargetException) {
            throw IllegalStateException(
                "Individual unpair failed: ${error.cause?.message ?: error.message}", error
            )
        }
    }

    override fun onDestroy() {
        closeClient()
        mutableStatus.value = "Disconnected"
        super.onDestroy()
    }

    private fun closeClient() {
        gattClient?.close()
        gattClient = null
        targetAddress = null
        mutableConnectionState.value = PhoneConnectionState.DISCONNECTED
    }

    companion object {
        const val PREFERENCES = "iphone-connection"
        const val TARGET_ADDRESS = "target-address"
        private const val SERVICE_CHANNEL = "ANCSAndroidService"
    }
}
