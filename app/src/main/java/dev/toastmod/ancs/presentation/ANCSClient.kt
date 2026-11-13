package dev.toastmod.ancs.presentation

import android.Manifest
import android.app.NotificationChannel
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission

class ANCSClient(
    private val context: Context,
    private val channel: NotificationChannel
) {
    private val notifications = linkedMapOf<Int, ANCSNotification>()

    fun onNotificationSource(eventId: Byte, flags: Byte, category: Byte, uid: ByteArray): Boolean {
        val key = uid.notificationUid()
        when (EventID.fromByte(eventId)) {
            EventID.NotificationAdded, EventID.NotificationModified -> {
                if (eventId == EventID.NotificationModified.b && key !in notifications) {
                    Log.d("ANCS", "Ignoring update for an unretained notification")
                    return false
                }
                val notification = notifications.getOrPut(key) {
                    if (notifications.size >= 20) remove(notifications.keys.first())
                    ANCSNotification(channel, key, CategoryID.fromByte(category))
                }
                notification.silent = (flags.toInt() and ((1 shl 0) or (1 shl 2))) != 0
                return true
            }
            EventID.NotificationRemoved -> remove(key)
            else -> Log.w("ANCS", "Ignoring reserved notification event $eventId")
        }
        return false
    }

    fun contains(uid: ByteArray): Boolean = uid.notificationUid() in notifications

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun onAttributes(uid: ByteArray, attributes: ANCSAttributes) {
        notifications[uid.notificationUid()]?.spawnAndroidNotif(context, attributes)
    }

    private fun remove(uid: Int) {
        notifications.remove(uid)?.despawnAndroidNotif(context)
    }

    fun clear() {
        notifications.keys.toList().forEach(::remove)
    }
}
