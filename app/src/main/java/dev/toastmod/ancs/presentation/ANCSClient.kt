package dev.toastmod.ancs.presentation

import android.Manifest
import android.app.NotificationChannel
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.UUID

class ANCSClient(
    private val context: Context,
    private val channel: NotificationChannel,
    val apps: NotificationApps = NotificationApps(
        context.getSharedPreferences(ANCSAndroidService.PREFERENCES, Context.MODE_PRIVATE), ""
    )
) {
    private val notifications = linkedMapOf<Int, ANCSNotification>()
    private data class Entry(
        var silent: Boolean,
        val category: CategoryID,
        var revision: Long,
        var filterRevision: Long,
        var appIdentifier: String? = null,
        var flags: Byte = 0,
        var actions: List<ANCSNotificationAction> = emptyList(),
        var actionConsumed: Boolean = false
    )
    private val entries = linkedMapOf<Int, Entry>()
    private var revision = 0L
    private var session = UUID.randomUUID().toString()

    fun onNotificationSource(eventId: Byte, flags: Byte, category: Byte, uid: ByteArray): Boolean {
        val key = uid.notificationUid()
        when (EventID.fromByte(eventId)) {
            EventID.NotificationAdded, EventID.NotificationModified -> {
                if (eventId == EventID.NotificationModified.b && key !in entries) {
                    Log.d("ANCS", "Ignoring update for an unretained notification")
                    return false
                }
                val entry = entries.getOrPut(key) {
                    if (entries.size >= 256) {
                        val oldest = entries.keys.firstOrNull { it !in notifications }
                            ?: entries.keys.first()
                        remove(oldest)
                    }
                    Entry(false, CategoryID.fromByte(category), 0, apps.revision)
                }
                entry.silent = (flags.toInt() and ((1 shl 0) or (1 shl 2))) != 0
                entry.revision = ++revision
                entry.filterRevision = apps.revision
                entry.flags = flags
                entry.actions = emptyList()
                entry.actionConsumed = false
                return true
            }
            EventID.NotificationRemoved -> remove(key)
            else -> Log.w("ANCS", "Ignoring reserved notification event $eventId")
        }
        return false
    }

    fun contains(uid: ByteArray): Boolean = uid.notificationUid() in entries

    fun revision(uid: ByteArray): Long? = entries[uid.notificationUid()]?.revision

    fun appIdentifier(uid: ByteArray): String? = entries[uid.notificationUid()]?.appIdentifier

    fun identify(uid: ByteArray, identifier: String) {
        entries[uid.notificationUid()]?.appIdentifier = identifier
        apps.observe(identifier)
    }

    fun canFetch(uid: ByteArray): Boolean {
        val entry = entries[uid.notificationUid()] ?: return false
        val identifier = entry.appIdentifier ?: return false
        return apps.isEnabled(identifier) && !apps.changedAfter(identifier, entry.filterRevision)
    }

    fun actionAttributeIds(uid: ByteArray): List<Int> {
        val flags = entries[uid.notificationUid()]?.flags?.toInt() ?: return emptyList()
        return listOfNotNull(
            6.takeIf { flags and (1 shl 3) != 0 },
            7.takeIf { flags and (1 shl 4) != 0 }
        )
    }

    fun isActionAvailable(action: ANCSNotificationAction): Boolean {
        val entry = entries[action.uid] ?: return false
        return action.session == session && action.revision == entry.revision &&
            !entry.actionConsumed && action in entry.actions && action.uid in notifications &&
            entry.appIdentifier?.let(apps::isEnabled) == true
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun completeAction(action: ANCSNotificationAction) {
        if (!isActionAvailable(action)) return
        entries.getValue(action.uid).actionConsumed = true
        notifications[action.uid]?.updateActions(context, emptyList())
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun onAttributes(uid: ByteArray, attributes: ANCSAttributes) {
        val key = uid.notificationUid()
        val entry = entries[key] ?: return
        identify(uid, attributes.appIdentifier)
        if (!apps.isEnabled(attributes.appIdentifier)) return
        val notification = notifications.getOrPut(key) {
            if (notifications.size >= 20) remove(notifications.keys.first())
            ANCSNotification(channel, key, entry.category)
        }
        notification.silent = entry.silent
        val labels = mapOf(6 to attributes.positiveActionLabel, 7 to attributes.negativeActionLabel)
        entry.actions = if (entry.actionConsumed) emptyList() else actionAttributeIds(uid).mapNotNull { id ->
            labels.getValue(id).takeIf { it.isNotBlank() }?.let { label ->
                ANCSNotificationAction(key, (id - 6).toByte(), session, entry.revision, label)
            }
        }
        notification.spawnAndroidNotif(context, attributes, apps.displayName(attributes.appIdentifier),
            entry.actions)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun onAppName(identifier: String, name: String) {
        apps.onName(identifier, name)
        notifications.values.filter { it.appIdentifier == identifier }
            .forEach { it.updateAppName(context, apps.displayName(identifier)) }
    }

    fun applyFilters() {
        entries.forEach { (key, entry) ->
            if (entry.appIdentifier?.let { !apps.isEnabled(it) } == true) {
                notifications.remove(key)?.despawnAndroidNotif(context)
                entry.revision = ++revision
                entry.actions = emptyList()
            }
        }
    }

    private fun remove(uid: Int) {
        notifications.remove(uid)?.despawnAndroidNotif(context)
        entries.remove(uid)
    }

    fun clear() {
        entries.keys.toList().forEach(::remove)
        apps.clearSession()
        session = UUID.randomUUID().toString()
    }
}
