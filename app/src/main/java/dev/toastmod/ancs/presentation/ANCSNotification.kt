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
    private var lastContent: RenderedContent? = null

    private data class RenderedContent(
        val appIdentifier: String,
        val title: String,
        val text: String,
        val silent: Boolean
    )

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun spawnAndroidNotif(context: Context, attributes: ANCSAttributes) {
        val text = listOf(attributes.subtitle, attributes.message)
            .filter { it.isNotBlank() }.joinToString("\n")
        val content = RenderedContent(attributes.appIdentifier, attributes.title, text, silent)
        if (content == lastContent) return
        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle(attributes.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setGroup(attributes.appIdentifier)
            .setOnlyAlertOnce(true)
            .setSilent(silent || posted)
            .build()
        NotificationManagerCompat.from(context).notify(TAG, androidNotifHandle, notification)
        posted = true
        lastContent = content
    }

    fun despawnAndroidNotif(context: Context) {
        NotificationManagerCompat.from(context).cancel(TAG, androidNotifHandle)
        lastContent = null
    }

    companion object {
        internal const val TAG = "ANCS"
    }
}
