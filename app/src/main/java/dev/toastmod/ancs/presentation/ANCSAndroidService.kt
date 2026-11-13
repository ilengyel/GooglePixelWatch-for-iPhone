package dev.toastmod.ancs.presentation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
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

class ANCSAndroidService : Service() {
    private val amsClient = AMSClient()
    val mediaState = amsClient.state
    private val mutableStatus = MutableStateFlow("Disconnected")
    val status = mutableStatus.asStateFlow()
    private val binder = LocalBinder()
    private var gattClient: ANCSGattServiceClient? = null
    private var targetAddress: String? = null

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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
        gattClient = ANCSGattServiceClient(this, device, ANCSClient(this, channel), amsClient) {
            mutableStatus.value = it
        }.also { it.connect() }
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

    override fun onDestroy() {
        closeClient()
        mutableStatus.value = "Disconnected"
        super.onDestroy()
    }

    private fun closeClient() {
        gattClient?.close()
        gattClient = null
        targetAddress = null
    }

    companion object {
        const val PREFERENCES = "iphone-connection"
        const val TARGET_ADDRESS = "target-address"
        private const val SERVICE_CHANNEL = "ANCSAndroidService"
    }
}
