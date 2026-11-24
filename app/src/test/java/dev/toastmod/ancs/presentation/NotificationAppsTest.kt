package dev.toastmod.ancs.presentation

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NotificationAppsTest {
    private val preferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("app-test", Context.MODE_PRIVATE)

    @Test
    fun filtersPersistPerPhoneButNamesAndCatalogAreSessionOnly() {
        val apps = NotificationApps(preferences, "phone-a")
        apps.observe("app")
        apps.onName("app", "Friendly name")
        apps.setEnabled("app", false)
        assertEquals(listOf(NotificationApp("app", "Friendly name", false)), apps.state.value)

        apps.clearSession()
        assertTrue(apps.state.value.isEmpty())
        assertFalse(apps.isEnabled("app"))
        apps.observe("app")
        assertTrue(apps.needsName("app"))
        assertEquals("app", apps.displayName("app"))
        assertFalse(NotificationApps(preferences, "phone-a").isEnabled("app"))
        assertTrue(NotificationApps(preferences, "phone-b").isEnabled("app"))
        apps.setEnabled("app", true)
        assertTrue(NotificationApps(preferences, "phone-a").isEnabled("app"))
    }

    @Test
    fun emptyNamesAreCachedAsIdentifierAndSessionCatalogIsBounded() {
        val apps = NotificationApps(preferences, "phone")
        apps.observe("app")
        apps.onName("app", "")
        assertFalse(apps.needsName("app"))
        assertEquals("app", apps.displayName("app"))
        repeat(128) { apps.observe("app-$it") }
        assertEquals(128, apps.state.value.size)
        assertFalse(apps.state.value.any { it.identifier == "app" })
    }

    @Test
    fun failedPreferenceWriteDoesNotReportTheAppAsBlocked() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putStringSet(NotificationApps.blockedKey("phone"), setOf("app"))).thenReturn(editor)
        `when`(editor.commit()).thenReturn(false)
        val apps = NotificationApps(preferences, "phone")
        apps.observe("app")
        assertThrows(IllegalStateException::class.java) { apps.setEnabled("app", false) }
        assertTrue(apps.isEnabled("app"))
        assertTrue(apps.state.value.single().enabled)
    }
}
