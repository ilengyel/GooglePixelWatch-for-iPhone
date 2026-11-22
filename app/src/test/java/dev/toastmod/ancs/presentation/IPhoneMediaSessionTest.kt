package dev.toastmod.ancs.presentation

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35])
class IPhoneMediaSessionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val commands = mutableListOf<Pair<Byte, Boolean>>()
    private val errors = mutableListOf<String>()
    private lateinit var bridge: IPhoneMediaSession
    private lateinit var platform: MediaSession
    private val playing = AMSState(
        available = true,
        track = AMSTrackInfo("Track", "Artist", "Album", 180f),
        player = AMSPlayerInfo(isPlaying = true, elapsedTime = 12f, volume = 0.4f,
            playbackUpdatedAt = 1000),
        supportedCommands = setOf(AMSCommand.TOGGLE_PLAY_PAUSE, AMSCommand.NEXT_TRACK,
            AMSCommand.VOLUME_UP, AMSCommand.VOLUME_DOWN, AMSCommand.SKIP_FORWARD,
            AMSCommand.ADVANCE_SHUFFLE)
    )

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        platform = spy(MediaSession(context, "test"))
        bridge = IPhoneMediaSession(context, { command, volume -> commands += command to volume },
            errors::add, platform)
    }

    @After
    fun tearDown() = bridge.close()

    private fun notification(): Notification = manager.activeNotifications
        .single { it.id == IPhoneMediaSession.NOTIFICATION_ID }.notification
    private fun playback(): PlaybackState {
        val captor = ArgumentCaptor.forClass(PlaybackState::class.java)
        verify(platform, atLeastOnce()).setPlaybackState(captor.capture())
        return captor.value
    }

    @Test
    fun publishesRemoteMetadataCapabilitiesAndTimestampWithoutResettingProgressOnVolumeUpdates() {
        bridge.update(playing)
        assertTrue(bridge.session.isActive)
        val metadataCaptor = ArgumentCaptor.forClass(MediaMetadata::class.java)
        verify(platform).setMetadata(metadataCaptor.capture())
        val metadata = metadataCaptor.value
        assertEquals("Track", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertEquals("Album", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM))
        assertEquals(180_000L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
        val playback = playback()
        assertEquals(PlaybackState.STATE_PLAYING, playback.state)
        assertEquals(12_000L, playback.position)
        assertEquals(1000L, playback.lastPositionUpdateTime)
        assertTrue(playback.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
        assertEquals(0L, playback.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        assertEquals(0L, playback.actions and PlaybackState.ACTION_SEEK_TO)
        assertEquals(listOf("Skip forward", "Shuffle: Unknown"), playback.customActions.map { it.name.toString() })
        verify(platform).setPlaybackToRemote(bridge.volumeProvider)
        assertEquals(VolumeProvider.VOLUME_CONTROL_RELATIVE, bridge.volumeProvider.volumeControl)
        assertEquals(40, bridge.volumeProvider.currentVolume)
        assertNotNull(notification().extras.getParcelable(Notification.EXTRA_MEDIA_SESSION,
            android.media.session.MediaSession.Token::class.java))
        assertEquals(listOf("Pause", "Next"), notification().actions.map { it.title.toString() })
        bridge.update(playing.copy(player = playing.player.copy(volume = 0.5f)))
        assertEquals(1000L, playback().lastPositionUpdateTime)
        assertEquals(50, bridge.volumeProvider.currentVolume)
        bridge.update(playing.copy(player = playing.player.copy(isPlaying = false)))
        assertTrue(bridge.session.isActive)
        assertEquals(PlaybackState.STATE_PAUSED, playback().state)
        assertEquals("Play", notification().actions.first().title)
    }

    @Test
    fun callbacksForwardOnlySupportedCommandsAndNeverGuessThatAToggleSucceeded() {
        bridge.update(playing)
        bridge.callback.onPause()
        bridge.callback.onPause()
        bridge.callback.onPlay()
        bridge.callback.onSkipToNext()
        bridge.callback.onSkipToPrevious()
        val skip = playback().customActions.first().action
        bridge.callback.onCustomAction(skip, null)
        assertEquals(listOf(AMSCommand.TOGGLE_PLAY_PAUSE to false, AMSCommand.NEXT_TRACK to false,
            AMSCommand.SKIP_FORWARD to false), commands)
        assertEquals(1, errors.size)
        assertEquals(PlaybackState.STATE_PLAYING, playback().state)
        bridge.update(playing.copy(supportedCommands = setOf(AMSCommand.PLAY, AMSCommand.PAUSE)))
        commands.clear()
        bridge.callback.onPlay()
        bridge.callback.onPause()
        assertEquals(listOf(AMSCommand.PLAY to false, AMSCommand.PAUSE to false), commands)
    }

    @Test
    fun notificationButtonsKeepTheirIntentAndShareToggleDebouncingWithNativeControls() {
        bridge.update(playing)
        val pause = notification().actions.first().actionIntent
        bridge.callback.onPause()
        bridge.update(playing.copy(player = playing.player.copy(elapsedTime = 13f, playbackUpdatedAt = 2000)))
        bridge.handleIntent(shadowOf(pause).savedIntent)
        assertEquals(listOf(AMSCommand.TOGGLE_PLAY_PAUSE to false), commands)
        assertEquals(PlaybackState.STATE_PLAYING, playback().state)

        bridge.update(playing.copy(player = playing.player.copy(isPlaying = false)))
        val play = notification().actions.first().actionIntent
        assertFalse(pause == play)
        bridge.handleIntent(shadowOf(pause).savedIntent)
        assertEquals(1, commands.size)
        bridge.handleIntent(shadowOf(play).savedIntent)
        assertEquals(List(2) { AMSCommand.TOGGLE_PLAY_PAUSE to false }, commands)
        assertEquals(PlaybackState.STATE_PAUSED, playback().state)

        bridge.update(playing)
        bridge.handleIntent(shadowOf(play).savedIntent)
        assertEquals(2, commands.size)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun anUnacknowledgedToggleCanBeRetriedAfterFiveSeconds() {
        bridge.update(playing)
        bridge.callback.onPause()
        ShadowSystemClock.advanceBy(Duration.ofMillis(4_999))
        bridge.callback.onPause()
        assertEquals(listOf(AMSCommand.TOGGLE_PLAY_PAUSE to false), commands)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        bridge.callback.onPause()
        assertEquals(List(2) { AMSCommand.TOGGLE_PLAY_PAUSE to false }, commands)
    }

    @Test
    fun nonVisibleUpdatesDoNotBringBackADismissedNotificationButVisibleChangesDo() {
        val paused = playing.copy(player = playing.player.copy(isPlaying = false))
        bridge.update(paused)
        assertEquals("Play", notification().actions.first().title)
        manager.cancel(IPhoneMediaSession.NOTIFICATION_ID)

        val refreshed = paused.copy(
            player = paused.player.copy(volume = 0.5f, elapsedTime = 13f, playbackUpdatedAt = 2000),
            track = paused.track.copy(album = "Updated album", duration = 181f)
        )
        bridge.update(refreshed)
        assertTrue(manager.activeNotifications.none { it.id == IPhoneMediaSession.NOTIFICATION_ID })
        assertEquals(50, bridge.volumeProvider.currentVolume)
        assertEquals(13_000L, playback().position)

        bridge.update(refreshed.copy(supportedCommands = setOf(AMSCommand.TOGGLE_PLAY_PAUSE)))
        assertEquals(listOf("Play"), notification().actions.map { it.title.toString() })
    }

    @Test
    fun volumeUsesRelativePhoneCommandsAndReportedValuesNotWatchAudioOrOptimisticUpdates() {
        bridge.update(playing.copy(player = playing.player.copy(volume = null)))
        assertEquals(VolumeProvider.VOLUME_CONTROL_FIXED, bridge.volumeProvider.volumeControl)
        bridge.update(playing.copy(supportedCommands = setOf(AMSCommand.VOLUME_UP)))
        bridge.volumeProvider.onAdjustVolume(AudioManager.ADJUST_RAISE)
        bridge.volumeProvider.onAdjustVolume(AudioManager.ADJUST_LOWER)
        bridge.volumeProvider.onAdjustVolume(AudioManager.ADJUST_SAME)
        assertEquals(listOf(AMSCommand.VOLUME_UP to true), commands)
        assertEquals(1, errors.size)
        assertEquals(40, bridge.volumeProvider.currentVolume)
        bridge.update(playing.copy(supportedCommands = emptySet()))
        assertEquals(VolumeProvider.VOLUME_CONTROL_FIXED, bridge.volumeProvider.volumeControl)
    }

    @Test
    fun disconnectClearsNativeSurfacesAndOldNotificationActionsCannotControlANewConnection() {
        bridge.update(playing)
        val pending = notification().actions.first().actionIntent
        val intent = shadowOf(pending).savedIntent
        assertTrue(pending.isImmutable)
        bridge.handleIntent(intent)
        assertEquals(listOf(AMSCommand.TOGGLE_PLAY_PAUSE to false), commands)
        bridge.update(AMSState())
        assertFalse(bridge.session.isActive)
        assertTrue(manager.activeNotifications.none { it.id == IPhoneMediaSession.NOTIFICATION_ID })
        assertEquals(PlaybackState.STATE_NONE, playback().state)
        commands.clear()
        bridge.callback.onSkipToNext()
        bridge.update(playing)
        bridge.handleIntent(intent)
        bridge.handleIntent(Intent(IPhoneMediaSession.INTENT_ACTION))
        assertTrue(commands.isEmpty())
        assertEquals(3, errors.size)
    }

    @Test
    fun closingAppleMusicClearsTheSessionAndNotificationWithoutDisconnecting() {
        val client = AMSClient()
        client.setAvailable(true)
        client.onSupportedCommands(byteArrayOf(AMSCommand.PLAY, AMSCommand.TOGGLE_PLAY_PAUSE))
        client.onEntityUpdate(byteArrayOf(2, 2, 0) + "Track".encodeToByteArray())
        client.onEntityUpdate(byteArrayOf(0, 1, 0) + "1,1.0,12".encodeToByteArray())
        bridge.update(client.state.value)
        assertEquals(PlaybackState.STATE_PLAYING, playback().state)
        assertEquals("Pause", notification().actions.first().title)

        client.onEntityUpdate(byteArrayOf(0, 1, 0) + "0,,".encodeToByteArray())
        bridge.update(client.state.value)
        assertTrue(bridge.session.isActive)
        assertEquals(PlaybackState.STATE_PAUSED, playback().state)
        assertEquals(PlaybackState.PLAYBACK_POSITION_UNKNOWN, playback().position)
        client.onEntityUpdate(byteArrayOf(2, 2, 0))
        bridge.update(client.state.value)
        assertTrue(client.state.value.available)
        assertFalse(bridge.session.isActive)
        assertEquals(PlaybackState.STATE_NONE, playback().state)
        assertTrue(manager.activeNotifications.none { it.id == IPhoneMediaSession.NOTIFICATION_ID })
    }

    @Test
    fun noPlayerOrRevokedNotificationPermissionDoesNotAdvertiseAGhostSession() {
        bridge.update(AMSState(available = true, supportedCommands = setOf(AMSCommand.PLAY)))
        assertFalse(bridge.session.isActive)
        assertTrue(manager.activeNotifications.none { it.id == IPhoneMediaSession.NOTIFICATION_ID })
        bridge.update(playing)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        bridge.update(playing)
        assertFalse(bridge.session.isActive)
        assertTrue(manager.activeNotifications.none { it.id == IPhoneMediaSession.NOTIFICATION_ID })
        assertEquals(1, errors.size)
    }

    @Test
    fun seekingStatesAndModeChangesRemainPhoneReportedAndPauseStillStopsActivePlayback() {
        bridge.update(playing.copy(player = playing.player.copy(isPlaying = false, playbackState = 2),
            queue = AMSQueueInfo(shuffle = AMSMode.All)))
        assertEquals(PlaybackState.STATE_REWINDING, playback().state)
        assertEquals("Pause", notification().actions.first().title)
        assertEquals("Shuffle: All", playback().customActions.last().name)
        bridge.callback.onPause()
        assertEquals(listOf(AMSCommand.TOGGLE_PLAY_PAUSE to false), commands)
        assertEquals(PlaybackState.STATE_REWINDING, playback().state)
        bridge.update(playing.copy(player = playing.player.copy(isPlaying = false, playbackState = 3)))
        assertEquals(PlaybackState.STATE_FAST_FORWARDING, playback().state)
    }

    @Test
    fun nativeEntryUsesTheInstalledSystemLauncherWithoutGuessingAnActivityName() {
        assertNull(NativeMediaControls.intent(context))
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(NativeMediaControls.PACKAGE)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = NativeMediaControls.PACKAGE
                name = "$packageName.ControlsActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, info)
        val launch = requireNotNull(NativeMediaControls.intent(context))
        assertEquals(info.activityInfo.packageName, launch.component?.packageName)
        assertEquals(info.activityInfo.name, launch.component?.className)
    }
}
