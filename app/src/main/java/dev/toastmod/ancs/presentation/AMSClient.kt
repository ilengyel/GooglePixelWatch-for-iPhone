package dev.toastmod.ancs.presentation

import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

val AMS_SERVICE: UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")
val AMS_REMOTE_COMMAND: UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")
val AMS_ENTITY_UPDATE: UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")
val AMS_ENTITY_ATTRIBUTE: UUID = UUID.fromString("C6B2F38C-23AB-46D8-A6AB-A3A870BBD5D7")

object AMSCommand {
    const val PLAY: Byte = 0
    const val PAUSE: Byte = 1
    const val TOGGLE_PLAY_PAUSE: Byte = 2
    const val NEXT_TRACK: Byte = 3
    const val PREVIOUS_TRACK: Byte = 4
    const val VOLUME_UP: Byte = 5
    const val VOLUME_DOWN: Byte = 6
    const val ADVANCE_REPEAT: Byte = 7
    const val ADVANCE_SHUFFLE: Byte = 8
    const val SKIP_FORWARD: Byte = 9
    const val SKIP_BACKWARD: Byte = 10
}

enum class AMSEntityID(val b: Byte) {
    Player(0), Queue(1), Track(2);

    companion object {
        fun fromByte(b: Byte): AMSEntityID? = entries.firstOrNull { it.b == b }
    }
}

enum class AMSTrackAttributeID(val b: Byte) {
    Artist(0), Album(1), Title(2), Duration(3);
}

enum class AMSPlayerAttributeID(val b: Byte) {
    Name(0), PlaybackInfo(1), Volume(2);
}

enum class AMSQueueAttributeID(val b: Byte) {
    ShuffleMode(2), RepeatMode(3);
}

enum class AMSMode(val value: Int) {
    Off(0), One(1), All(2);
}

data class AMSQueueInfo(
    val shuffle: AMSMode? = null,
    val repeat: AMSMode? = null
)

data class AMSTrackInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Float = 0f
) {
    /** True once at least one text attribute has arrived from the AMS Track entity. */
    val hasData: Boolean get() = title.isNotBlank() || artist.isNotBlank() || album.isNotBlank()
}

data class AMSPlayerInfo(
    val isPlaying: Boolean = false,
    val playbackRate: Float = 1f,
    val elapsedTime: Float = 0f,
    val volume: Float? = null,
    val playbackState: Int? = null,
    val playbackUpdatedAt: Long? = null
)

data class AMSState(
    val available: Boolean = false,
    val track: AMSTrackInfo = AMSTrackInfo(),
    val player: AMSPlayerInfo = AMSPlayerInfo(),
    val supportedCommands: Set<Byte> = emptySet(),
    val queue: AMSQueueInfo = AMSQueueInfo()
)

class AMSClient {

    private val _state = MutableStateFlow(AMSState())
    val state: StateFlow<AMSState> = _state.asStateFlow()

    fun setAvailable(available: Boolean) {
        _state.value = _state.value.copy(available = available)
    }

    fun reset() {
        _state.value = AMSState()
    }

    fun onSupportedCommands(commands: ByteArray) {
        _state.value = _state.value.copy(supportedCommands = commands.toSet())
    }

    /**
     * Called when a notification arrives on the AMS Entity Update characteristic.
     * Format: EntityID(1) | AttributeID(1) | EntityUpdateFlags(1) | Value(UTF-8 string)
     */
    fun onEntityUpdate(data: ByteArray) {
        if (data.size < 3) {
            Log.w("AMS", "Entity Update too short (${data.size} bytes)")
            return
        }
        val entityId = AMSEntityID.fromByte(data[0])
        val attributeId = data[1]
        val value = if (data.size > 3) data.copyOfRange(3, data.size).decodeToString() else ""

        when (entityId) {
            AMSEntityID.Track -> {
                val cur = _state.value.track
                val updated = when (attributeId) {
                    AMSTrackAttributeID.Title.b  -> cur.copy(title = value)
                    AMSTrackAttributeID.Artist.b -> cur.copy(artist = value)
                    AMSTrackAttributeID.Album.b  -> cur.copy(album = value)
                    AMSTrackAttributeID.Duration.b -> {
                        val duration = if (value.isEmpty()) 0f else value.toFloatOrNull()
                        if (duration != null && duration.isFinite() && duration >= 0f) {
                            cur.copy(duration = duration)
                        } else {
                            Log.w("AMS", "Invalid track duration")
                            cur
                        }
                    }
                    else -> cur
                }
                _state.value = _state.value.copy(track = updated)
            }

            AMSEntityID.Player -> {
                if (attributeId == AMSPlayerAttributeID.PlaybackInfo.b) {
                    // Apple Music reports paused with no timing data when the player closes.
                    if (value == "0,," && (data[2].toInt() and 1) == 0) {
                        _state.value = _state.value.copy(player = _state.value.player.copy(
                            isPlaying = false,
                            playbackRate = 0f,
                            elapsedTime = 0f,
                            playbackState = 0,
                            playbackUpdatedAt = null
                        ))
                        return
                    }
                    val parts = value.split(",")
                    val playbackState = parts.getOrNull(0)?.toIntOrNull()
                    val rate = parts.getOrNull(1)?.toFloatOrNull()
                    val elapsed = parts.getOrNull(2)?.toFloatOrNull()
                    if (playbackState != null && playbackState in 0..3 && rate != null && rate.isFinite() &&
                        elapsed != null && elapsed.isFinite() && elapsed >= 0f
                    ) {
                        _state.value = _state.value.copy(
                            player = _state.value.player.copy(
                                isPlaying = playbackState == 1,
                                playbackRate = rate,
                                elapsedTime = elapsed,
                                playbackState = playbackState,
                                playbackUpdatedAt = SystemClock.elapsedRealtime()
                            )
                        )
                    } else {
                        Log.w("AMS", "Invalid playback information")
                    }
                } else if (attributeId == AMSPlayerAttributeID.Volume.b) {
                    val volume = value.toFloatOrNull()
                    if (volume != null && volume.isFinite() && volume in 0f..1f) {
                        _state.value = _state.value.copy(player = _state.value.player.copy(volume = volume))
                    } else {
                        Log.w("AMS", "Invalid player volume")
                    }
                }
            }

            AMSEntityID.Queue -> {
                if (attributeId == AMSQueueAttributeID.ShuffleMode.b ||
                    attributeId == AMSQueueAttributeID.RepeatMode.b
                ) {
                    val mode = value.toIntOrNull()?.let { number -> AMSMode.entries.find { it.value == number } }
                    if (mode == null) {
                        Log.w("AMS", "Invalid queue mode")
                    } else {
                        val queue = _state.value.queue
                        _state.value = _state.value.copy(queue =
                            if (attributeId == AMSQueueAttributeID.ShuffleMode.b) queue.copy(shuffle = mode)
                            else queue.copy(repeat = mode))
                    }
                }
            }

            else -> {}
        }
    }
}
