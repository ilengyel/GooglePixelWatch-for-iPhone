package dev.toastmod.ancs.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ANCSNotificationLifecycleTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val channel = NotificationChannel("test", "Test", NotificationManager.IMPORTANCE_DEFAULT)

    private fun uid(id: Int) = byteArrayOf(id.toByte(), 0, 0, 0)

    private fun attributes(title: String) = ANCSAttributes("com.example.app", title, "", "Message")

    private fun client(): ANCSClient {
        manager.createNotificationChannel(channel)
        return ANCSClient(context, channel)
    }

    @Test
    fun modifyingAnEvictedNotificationDoesNotResurrectItOrEvictAnother() {
        val client = client()
        for (id in 0..20) {
            assertTrue(client.onNotificationSource(EventID.NotificationAdded.b, 0, 0, uid(id)))
            client.onAttributes(uid(id), attributes("Notification $id"))
        }
        assertFalse(client.contains(uid(0)))
        assertFalse(client.onNotificationSource(EventID.NotificationModified.b, 0, 0, uid(0)))
        client.onAttributes(uid(0), attributes("Resurrected"))

        assertFalse(client.contains(uid(0)))
        for (id in 1..20) assertTrue(client.contains(uid(id)))
        assertEquals((1..20).toSet(), manager.activeNotifications.map { it.id }.toSet())
    }

    @Test
    fun modifyingARetainedNotificationUpdatesTheSameAndroidNotification() {
        val client = client()
        client.onNotificationSource(EventID.NotificationAdded.b, 0, 0, uid(1))
        client.onAttributes(uid(1), attributes("Original"))
        assertTrue(client.onNotificationSource(EventID.NotificationModified.b, 0, 0, uid(1)))
        client.onAttributes(uid(1), attributes("Updated"))

        val notification = manager.activeNotifications.single()
        assertEquals(1, notification.id)
        assertEquals("Updated", notification.notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun unchangedRenderedContentIsNotRepostedButContentAndFlagChangesAre() {
        val client = client()
        client.onNotificationSource(EventID.NotificationAdded.b, 0, 0, uid(1))
        client.onAttributes(uid(1), attributes("Title"))
        val original = manager.activeNotifications.single().notification

        client.onNotificationSource(EventID.NotificationModified.b, 0, 0, uid(1))
        client.onAttributes(uid(1), ANCSAttributes("com.example.app", "Title", "  ", "Message"))
        assertSame(original, manager.activeNotifications.single().notification)

        client.onNotificationSource(EventID.NotificationModified.b, 1, 0, uid(1))
        client.onAttributes(uid(1), attributes("Title"))
        val silentUpdate = manager.activeNotifications.single().notification
        assertNotSame(original, silentUpdate)
        assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, silentUpdate.groupAlertBehavior)
        client.onAttributes(uid(1), attributes("Title"))
        assertSame(silentUpdate, manager.activeNotifications.single().notification)

        client.onAttributes(uid(1), attributes("Changed title"))
        val changed = manager.activeNotifications.single().notification
        assertNotSame(silentUpdate, changed)
        assertEquals("Changed title", changed.extras.getString(Notification.EXTRA_TITLE))
        client.onAttributes(uid(1), ANCSAttributes("other.app", "Changed title", "", "Changed message"))
        assertEquals("other.app", manager.activeNotifications.single().notification.group)
        assertEquals("Changed message",
            manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun cancellingClearsTheDeduplicationState() {
        manager.createNotificationChannel(channel)
        val notification = ANCSNotification(channel, 1, CategoryID.Other)
        notification.spawnAndroidNotif(context, attributes("Title"))
        notification.despawnAndroidNotif(context)
        assertTrue(manager.activeNotifications.isEmpty())
        notification.spawnAndroidNotif(context, attributes("Title"))
        assertEquals("Title", manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun removalBeforeAttributesArriveDoesNotRecreateTheNotification() {
        val client = client()
        client.onNotificationSource(EventID.NotificationAdded.b, 0, 0, uid(1))
        client.onAttributes(uid(1), attributes("Original"))
        client.onNotificationSource(EventID.NotificationModified.b, 0, 0, uid(1))
        client.onNotificationSource(EventID.NotificationRemoved.b, 0, 0, uid(1))
        client.onAttributes(uid(1), attributes("Late update"))
        assertFalse(client.contains(uid(1)))
        assertTrue(manager.activeNotifications.isEmpty())

        client.onNotificationSource(EventID.NotificationAdded.b, 0, 0, uid(2))
        client.onNotificationSource(EventID.NotificationRemoved.b, 0, 0, uid(2))
        client.onAttributes(uid(2), attributes("Late first response"))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun silentAndPreExistingFlagsSuppressTheFirstAlert() {
        val client = client()
        for ((id, flags) in listOf(1 to 1, 2 to 4, 3 to 5, 4 to 0)) {
            client.onNotificationSource(EventID.NotificationAdded.b, flags.toByte(), 0, uid(id))
            client.onAttributes(uid(id), attributes("Notification $id"))
            val notification = manager.activeNotifications.single { it.id == id }.notification
            assertEquals("Flags $flags",
                if (flags == 0) NotificationCompat.GROUP_ALERT_ALL else NotificationCompat.GROUP_ALERT_SUMMARY,
                notification.groupAlertBehavior)
            assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        }
    }

    @Test
    fun updatesRemainSilentEvenWhenTheSilentFlagIsCleared() {
        val client = client()
        for ((id, flags) in listOf(1 to 0, 2 to 1, 3 to 4)) {
            client.onNotificationSource(EventID.NotificationAdded.b, flags.toByte(), 0, uid(id))
            client.onAttributes(uid(id), attributes("Original"))
            client.onNotificationSource(EventID.NotificationModified.b, 0, 0, uid(id))
            client.onAttributes(uid(id), attributes("Updated"))
            val notification = manager.activeNotifications.single { it.id == id }.notification
            assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
            assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        }
    }

    @Test
    fun clearingNotificationsPreservesUnrelatedNotificationsAndAllowsUidReuse() {
        val client = client()
        client.onNotificationSource(EventID.NotificationAdded.b, 0, 0, uid(1))
        client.onAttributes(uid(1), attributes("Old session"))
        val unrelated = Notification.Builder(context, channel.id)
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()
        manager.notify(9002, unrelated)
        manager.notify("Other", 1, unrelated)

        client.clear()
        assertFalse(client.contains(uid(1)))
        assertEquals(setOf(null to 9002, "Other" to 1),
            manager.activeNotifications.map { it.tag to it.id }.toSet())
        client.onAttributes(uid(1), attributes("Late old session"))
        assertEquals(2, manager.activeNotifications.size)

        assertTrue(client.onNotificationSource(EventID.NotificationAdded.b, 0, 0, uid(1)))
        client.onAttributes(uid(1), attributes("New session"))
        val notification = manager.activeNotifications.single { it.tag == ANCSNotification.TAG }.notification
        assertEquals("New session", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(NotificationCompat.GROUP_ALERT_ALL, notification.groupAlertBehavior)
    }

    @Test
    fun updatingTheOldestNotificationDoesNotChangeEvictionOrder() {
        val client = client()
        for (id in 0 until 20) {
            client.onNotificationSource(EventID.NotificationAdded.b, 0, 0, uid(id))
            client.onAttributes(uid(id), attributes("Notification $id"))
        }
        client.onNotificationSource(EventID.NotificationModified.b, 0, 0, uid(0))
        client.onAttributes(uid(0), attributes("Updated oldest"))
        client.onNotificationSource(EventID.NotificationAdded.b, 0, 0, uid(20))
        client.onAttributes(uid(20), attributes("Newest"))

        assertFalse(client.contains(uid(0)))
        for (id in 1..20) assertTrue(client.contains(uid(id)))
        assertEquals((1..20).toSet(), manager.activeNotifications.map { it.id }.toSet())
    }

    @Test
    fun serviceCreationRemovesOnlyStaleMirroredNotifications() {
        manager.createNotificationChannel(channel)
        val notification = Notification.Builder(context, channel.id)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Existing")
            .build()
        manager.notify(ANCSNotification.TAG, 9002, notification)
        manager.notify(ANCSNotification.TAG, 1, notification)
        manager.notify(9002, notification)
        manager.notify("Other", 1, notification)

        val service = Robolectric.buildService(ANCSAndroidService::class.java).create()
        try {
            assertEquals(setOf(null to 9002, "Other" to 1),
                manager.activeNotifications.map { it.tag to it.id }.toSet())
        } finally {
            service.destroy()
        }
    }
}
