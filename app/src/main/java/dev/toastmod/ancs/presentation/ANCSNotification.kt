package dev.toastmod.ancs.presentation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
    private var actionIdentities = emptyList<ANCSNotificationAction>()
    val appIdentifier: String? get() = lastContent?.attributes?.appIdentifier

    private data class RenderedContent(
        val attributes: ANCSAttributes,
        val appName: String,
        val text: String,
        val silent: Boolean,
        val actions: List<ANCSNotificationAction>
    )

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun spawnAndroidNotif(
        context: Context,
        attributes: ANCSAttributes,
        appName: String = attributes.appIdentifier,
        actions: List<ANCSNotificationAction> = emptyList()
    ) {
        val text = listOf(attributes.subtitle, attributes.message)
            .filter { it.isNotBlank() }.joinToString("\n")
        val normalized = attributes.copy(subtitle = "", message = text)
        val content = RenderedContent(normalized, appName, text, silent, actions)
        if (content == lastContent) return
        val previous = lastContent
        if (previous != null &&
            content.copy(actions = actions.map { it.copy(revision = 0) }) ==
            previous.copy(actions = previous.actions.map { it.copy(revision = 0) })
        ) {
            // Refresh immutable intent payloads without reposting identical visible content.
            actions.zip(actionIdentities).forEach { (action, identity) ->
                actionPendingIntent(context, action, identity)
            }
            lastContent = content
            return
        }
        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle(attributes.title)
            .setContentText(text)
            .setSubText(appName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setGroup(attributes.appIdentifier)
            .setOnlyAlertOnce(true)
            .setSilent(silent || posted)
            .apply {
                actions.forEach { action ->
                    addAction(NotificationCompat.Action.Builder(
                        0, action.label, actionPendingIntent(context, action, action)
                    ).build())
                }
            }.build()
        NotificationManagerCompat.from(context).notify(TAG, androidNotifHandle, notification)
        posted = true
        lastContent = content
        actionIdentities = actions
    }

    private fun actionPendingIntent(
        context: Context,
        action: ANCSNotificationAction,
        identity: ANCSNotificationAction
    ): PendingIntent = PendingIntent.getService(context, 0,
        action.toIntent(context).setData(identity.toIntent(context).data),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun isActive(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .any { it.tag == TAG && it.id == androidNotifHandle }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun updateAppName(context: Context, name: String) {
        if (!isActive(context)) return
        lastContent?.let { spawnAndroidNotif(context, it.attributes, name, it.actions) }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun updateActions(context: Context, actions: List<ANCSNotificationAction>) {
        if (!isActive(context)) return
        lastContent?.let { spawnAndroidNotif(context, it.attributes, it.appName, actions) }
    }

    fun despawnAndroidNotif(context: Context) {
        NotificationManagerCompat.from(context).cancel(TAG, androidNotifHandle)
        lastContent = null
        actionIdentities = emptyList()
    }

    companion object {
        internal const val TAG = "ANCS"
    }
}
