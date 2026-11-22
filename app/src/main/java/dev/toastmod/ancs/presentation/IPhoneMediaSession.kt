package dev.toastmod.ancs.presentation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.toastmod.ancs.R
import java.util.UUID
import kotlin.math.roundToInt

internal fun AMSState.hasNativeMedia(): Boolean =
    available && (track.hasData || player.isActivePlayback())

private fun AMSPlayerInfo.isActivePlayback(): Boolean =
    isPlaying || playbackState == 2 || playbackState == 3

internal object NativeMediaControls {
    const val PACKAGE = "com.google.android.wearable.media.sessions"

    fun intent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(PACKAGE)
}

internal class IPhoneMediaSession(
    private val context: Context,
    private val onCommand: (Byte, Boolean) -> Unit,
    private val onError: (String) -> Unit,
    internal val session: MediaSession = MediaSession(context, "iPhone")
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var state = AMSState()
    private var generation: String? = null
    private var togglePendingUntil = 0L
    private var postedNotification: NotificationContent? = null
    private var volumeControl = VolumeProvider.VOLUME_CONTROL_FIXED

    private data class NotificationContent(
        val title: String,
        val artist: String,
        val ongoing: Boolean,
        val transport: List<Byte>
    )

    internal val callback = object : MediaSession.Callback() {
        override fun onPlay() = requestPlayback(AMSCommand.PLAY)
        override fun onPause() = requestPlayback(AMSCommand.PAUSE)
        override fun onSkipToNext() = command(AMSCommand.NEXT_TRACK)
        override fun onSkipToPrevious() = command(AMSCommand.PREVIOUS_TRACK)

        override fun onCustomAction(action: String, extras: Bundle?) {
            val command = extraCommands.firstOrNull { action == customAction(it) }
            if (command == null) error(context.getString(R.string.media_command_unavailable))
            else command(command)
        }
    }

    internal var volumeProvider: VolumeProvider = newVolumeProvider(volumeControl)
        private set

    init {
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, context.getString(R.string.iphone_media), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) })
        session.setCallback(callback)
        session.setPlaybackToRemote(volumeProvider)
    }

    private fun newVolumeProvider(control: Int) = object : VolumeProvider(control, 100, 0) {
        override fun onAdjustVolume(direction: Int) {
            when (direction) {
                AudioManager.ADJUST_RAISE -> command(AMSCommand.VOLUME_UP, volume = true)
                AudioManager.ADJUST_LOWER -> command(AMSCommand.VOLUME_DOWN, volume = true)
                AudioManager.ADJUST_SAME -> Unit
                else -> error(context.getString(R.string.media_command_unavailable))
            }
        }
    }

    fun update(value: AMSState) {
        if (value.player.isActivePlayback() != state.player.isActivePlayback()) togglePendingUntil = 0L
        state = value
        if (!value.hasNativeMedia()) {
            deactivate()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            deactivate()
            error(context.getString(R.string.media_notification_permission))
            return
        }
        if (generation == null) generation = UUID.randomUUID().toString()
        val player = value.player
        val track = value.track
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE,
                track.title.ifBlank { context.getString(R.string.media_unknown_title) })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
        if (track.duration.isFinite() && track.duration > 0f) {
            metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, (track.duration * 1000.0).toLong())
        }
        session.setMetadata(metadata.build())
        val playback = PlaybackState.Builder()
            .setState(
                when (player.playbackState) {
                    2 -> PlaybackState.STATE_REWINDING
                    3 -> PlaybackState.STATE_FAST_FORWARDING
                    else -> if (player.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
                },
                if (player.playbackUpdatedAt == null) PlaybackState.PLAYBACK_POSITION_UNKNOWN
                else (player.elapsedTime * 1000.0).toLong(),
                if (player.isActivePlayback()) player.playbackRate else 0f,
                player.playbackUpdatedAt ?: SystemClock.elapsedRealtime()
            )
            .setActions(actions(value.supportedCommands))
        extraCommands.filter { it in value.supportedCommands }.forEach {
            playback.addCustomAction(customAction(it), label(it), icon(it))
        }
        session.setPlaybackState(playback.build())
        val control = if (player.volume != null && (AMSCommand.VOLUME_UP in value.supportedCommands ||
            AMSCommand.VOLUME_DOWN in value.supportedCommands)
        ) VolumeProvider.VOLUME_CONTROL_RELATIVE else VolumeProvider.VOLUME_CONTROL_FIXED
        if (control != volumeControl) {
            volumeControl = control
            volumeProvider = newVolumeProvider(control)
            session.setPlaybackToRemote(volumeProvider)
        }
        player.volume?.let { volumeProvider.setCurrentVolume((it * 100).roundToInt()) }
        session.setActive(true)
        publishNotification()
    }

    private fun actions(commands: Set<Byte>): Long {
        var actions = 0L
        val toggle = AMSCommand.TOGGLE_PLAY_PAUSE in commands
        if (toggle || AMSCommand.PLAY in commands) actions = actions or PlaybackState.ACTION_PLAY
        if (toggle || AMSCommand.PAUSE in commands) actions = actions or PlaybackState.ACTION_PAUSE
        if (toggle || (AMSCommand.PLAY in commands && AMSCommand.PAUSE in commands)) {
            actions = actions or PlaybackState.ACTION_PLAY_PAUSE
        }
        if (AMSCommand.NEXT_TRACK in commands) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (AMSCommand.PREVIOUS_TRACK in commands) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        return actions
    }

    private fun publishNotification() {
        val commands = state.supportedCommands
        val playing = state.player.isActivePlayback()
        val playbackCommand = if (playing) AMSCommand.PAUSE else AMSCommand.PLAY
        val content = NotificationContent(
            title = state.track.title.ifBlank { context.getString(R.string.iphone_media) },
            artist = state.track.artist,
            ongoing = playing,
            transport = buildList {
                if (AMSCommand.PREVIOUS_TRACK in commands) add(AMSCommand.PREVIOUS_TRACK)
                if (playbackCommand in commands || AMSCommand.TOGGLE_PLAY_PAUSE in commands) add(playbackCommand)
                if (AMSCommand.NEXT_TRACK in commands) add(AMSCommand.NEXT_TRACK)
            }
        )
        if (content == postedNotification) return

        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle(content.title)
            .setContentText(content.artist)
            .setOnlyAlertOnce(true)
            .setOngoing(content.ongoing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
        val launchIntent = NativeMediaControls.intent(context)
            ?: Intent(context, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(context, 9003, launchIntent, PendingIntent.FLAG_IMMUTABLE)
        builder.setContentIntent(contentIntent)
        session.setSessionActivity(contentIntent)
        content.transport.forEach { command ->
            val intent = Intent(context, ANCSAndroidService::class.java)
                .setAction(INTENT_ACTION)
                .setData(Uri.Builder().scheme("ancs-media").authority(generation)
                    .appendPath(command.toString()).build())
                .putExtra(EXTRA_GENERATION, generation)
                .putExtra(EXTRA_COMMAND, command)
            builder.addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, icon(command)),
                label(command),
                PendingIntent.getService(context, command.toInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            ).build())
        }
        builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(*content.transport.indices.toList().toIntArray()))
        manager.notify(NOTIFICATION_ID, builder.build())
        postedNotification = content
    }

    fun handleIntent(intent: Intent) {
        if (generation == null || intent.getStringExtra(EXTRA_GENERATION) != generation) {
            error(context.getString(R.string.media_command_unavailable))
            return
        }
        when (val command = intent.getByteExtra(EXTRA_COMMAND, (-1).toByte())) {
            AMSCommand.PLAY, AMSCommand.PAUSE -> if (playbackChangeNeeded(command)) requestPlayback(command)
            AMSCommand.PREVIOUS_TRACK, AMSCommand.NEXT_TRACK -> command(command)
            else -> error(context.getString(R.string.media_command_unavailable))
        }
    }

    private fun playbackChangeNeeded(command: Byte) =
        (command == AMSCommand.PLAY) != state.player.isActivePlayback()

    private fun requestPlayback(command: Byte) {
        if (command in state.supportedCommands) {
            command(command)
        } else if (playbackChangeNeeded(command)) {
            command(AMSCommand.TOGGLE_PLAY_PAUSE)
        }
    }

    private fun command(command: Byte, volume: Boolean = false) {
        if (!state.hasNativeMedia() || command !in state.supportedCommands) {
            error(context.getString(R.string.media_command_unavailable))
            return
        }
        if (command == AMSCommand.TOGGLE_PLAY_PAUSE) {
            val now = SystemClock.elapsedRealtime()
            if (now < togglePendingUntil) return
            // Leave playback unchanged until the phone reports it, but allow retries for ignored writes.
            togglePendingUntil = now + TOGGLE_RETRY_MS
        }
        onCommand(command, volume)
    }

    private fun label(command: Byte): String = context.getString(when (command) {
        AMSCommand.PLAY -> R.string.media_play
        AMSCommand.PAUSE -> R.string.media_pause
        AMSCommand.PREVIOUS_TRACK -> R.string.media_previous
        AMSCommand.NEXT_TRACK -> R.string.media_next
        AMSCommand.SKIP_BACKWARD -> R.string.skip_backward
        AMSCommand.SKIP_FORWARD -> R.string.skip_forward
        AMSCommand.ADVANCE_SHUFFLE -> R.string.shuffle_mode
        else -> R.string.repeat_mode
    }, modeLabel(if (command == AMSCommand.ADVANCE_SHUFFLE) state.queue.shuffle else state.queue.repeat))

    private fun modeLabel(mode: AMSMode?): String = context.getString(when (mode) {
        AMSMode.Off -> R.string.mode_off
        AMSMode.One -> R.string.mode_one
        AMSMode.All -> R.string.mode_all
        null -> R.string.mode_unknown
    })

    private fun icon(command: Byte): Int = when (command) {
        AMSCommand.PLAY -> android.R.drawable.ic_media_play
        AMSCommand.PAUSE -> android.R.drawable.ic_media_pause
        AMSCommand.PREVIOUS_TRACK -> android.R.drawable.ic_media_previous
        AMSCommand.NEXT_TRACK -> android.R.drawable.ic_media_next
        AMSCommand.SKIP_BACKWARD -> android.R.drawable.ic_media_rew
        AMSCommand.SKIP_FORWARD -> android.R.drawable.ic_media_ff
        AMSCommand.ADVANCE_SHUFFLE -> android.R.drawable.ic_menu_rotate
        else -> android.R.drawable.ic_menu_revert
    }

    private fun error(message: String) {
        Log.w("AMS", message)
        onError(message)
    }

    private fun deactivate() {
        generation = null
        togglePendingUntil = 0L
        postedNotification = null
        session.setActive(false)
        session.setMetadata(null)
        session.setPlaybackState(PlaybackState.Builder().setState(
            PlaybackState.STATE_NONE, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f
        ).build())
        manager.cancel(NOTIFICATION_ID)
    }

    fun close() {
        state = AMSState()
        deactivate()
        session.release()
    }

    companion object {
        const val INTENT_ACTION = "dev.toastmod.ancs.MEDIA_COMMAND"
        const val NOTIFICATION_ID = 9003
        private const val CHANNEL = "iphone-media"
        private const val EXTRA_COMMAND = "media-command"
        private const val EXTRA_GENERATION = "media-generation"
        private const val TOGGLE_RETRY_MS = 5_000L
        private val extraCommands = listOf(AMSCommand.SKIP_BACKWARD, AMSCommand.SKIP_FORWARD,
            AMSCommand.ADVANCE_SHUFFLE, AMSCommand.ADVANCE_REPEAT)
        private fun customAction(command: Byte) = "dev.toastmod.ancs.media.$command"
    }
}
