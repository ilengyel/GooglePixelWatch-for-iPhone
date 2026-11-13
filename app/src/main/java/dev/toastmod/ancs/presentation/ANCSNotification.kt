package dev.toastmod.ancs.presentation

import android.Manifest
import android.app.NotificationChannel
import android.content.Context
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.toastmod.ancs.R

class ANCSNotification(
    private val channel: NotificationChannel,
    private val androidNotifHandle: Int,
    val category: CategoryID
) {
    var silent = false
    private var posted = false

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun spawnAndroidNotif(context: Context, attributes: ANCSAttributes) {
        val text = listOf(attributes.subtitle, attributes.message)
            .filter { it.isNotBlank() }.joinToString("\n")
        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(attributes.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setGroup(attributes.appIdentifier)
            .setOnlyAlertOnce(true)
            .setSilent(silent || posted)
            .build()
        NotificationManagerCompat.from(context).notify(TAG, androidNotifHandle, notification)
        posted = true
    }

    fun despawnAndroidNotif(context: Context) {
        NotificationManagerCompat.from(context).cancel(TAG, androidNotifHandle)
    }

    companion object {
        internal const val TAG = "ANCS"
    }
}
