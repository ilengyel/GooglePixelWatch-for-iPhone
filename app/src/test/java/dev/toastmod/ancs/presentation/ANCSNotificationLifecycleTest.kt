package dev.toastmod.ancs.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
