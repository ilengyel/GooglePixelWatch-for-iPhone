package dev.toastmod.ancs.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AMSClientTest {
    @Test
    fun volumeAndPlaybackUpdatesPreserveEachOtherAndRejectInvalidValues() {
        val client = AMSClient()
        client.onEntityUpdate(byteArrayOf(0, 2, 0) + "0.42".encodeToByteArray())
        client.onEntityUpdate(byteArrayOf(0, 1, 0) + "1,1.0,12".encodeToByteArray())
        assertEquals(0.42f, client.state.value.player.volume)
        for (value in listOf("", "x", "NaN", "Infinity", "-0.1", "1.1")) {
            val expected = client.state.value
            client.onEntityUpdate(byteArrayOf(0, 2, 0) + value.encodeToByteArray())
            assertEquals(expected, client.state.value)
        }
        for (volume in listOf(0f, 1f)) {
            client.onEntityUpdate(byteArrayOf(0, 2, 0) + volume.toString().encodeToByteArray())
            assertEquals(volume, client.state.value.player.volume)
            assertTrue(client.state.value.player.isPlaying)
        }
        client.reset()
        assertEquals(null, client.state.value.player.volume)
    }

    @Test
    fun queueModesFollowReportedValuesAndResetWithoutAcceptingReservedModes() {
        val client = AMSClient()
        for (mode in AMSMode.entries) {
            client.onEntityUpdate(byteArrayOf(1, 2, 0) + mode.value.toString().encodeToByteArray())
            client.onEntityUpdate(byteArrayOf(1, 3, 0) + mode.value.toString().encodeToByteArray())
            assertEquals(AMSQueueInfo(mode, mode), client.state.value.queue)
        }
        val expected = client.state.value
        for (value in listOf("", "-1", "3", "x", "1.0")) {
            for (attribute in listOf<Byte>(2, 3)) {
                client.onEntityUpdate(byteArrayOf(1, attribute, 0) + value.encodeToByteArray())
                assertEquals(expected, client.state.value)
            }
        }
        client.reset()
        assertEquals(AMSQueueInfo(), client.state.value.queue)
    }
    @Test
    fun sharedStateReceivesMetadataAndPlaybackAndResetsOnDisconnect() {
        val client = AMSClient()
        client.setAvailable(true)
        client.onEntityUpdate(byteArrayOf(2, 2, 0) + "Track".encodeToByteArray())
        client.onEntityUpdate(byteArrayOf(2, 0, 0) + "Artist".encodeToByteArray())
        client.onEntityUpdate(byteArrayOf(0, 1, 0) + "1,1.0,12.5".encodeToByteArray())
        assertEquals("Track", client.state.value.track.title)
        assertEquals("Artist", client.state.value.track.artist)
        assertTrue(client.state.value.player.isPlaying)
        assertEquals(12.5f, client.state.value.player.elapsedTime, 0f)
        client.reset()
        assertEquals(AMSState(), client.state.value)
    }

    @Test
    fun supportedCommandsAreReplacedRatherThanAccumulated() {
        val client = AMSClient()
        client.onSupportedCommands(byteArrayOf(2, 3, 4))
        assertTrue(AMSCommand.NEXT_TRACK in client.state.value.supportedCommands)
        client.onSupportedCommands(byteArrayOf(2))
        assertFalse(AMSCommand.NEXT_TRACK in client.state.value.supportedCommands)
        client.onSupportedCommands(byteArrayOf())
        assertTrue(client.state.value.supportedCommands.isEmpty())
    }

    @Test
    fun reservedEntitiesDoNotOverwritePlayerState() {
        val client = AMSClient()
        client.onEntityUpdate(byteArrayOf(0, 1, 0) + "1,1.0,10".encodeToByteArray())
        client.onEntityUpdate(byteArrayOf(42, 1, 0) + "0,0,0".encodeToByteArray())
        assertTrue(client.state.value.player.isPlaying)
    }

    @Test
    fun shortPacketsAndInvalidPlaybackPreserveTheLastValidState() {
        val client = AMSClient()
        client.onEntityUpdate(byteArrayOf(2, 2, 0) + "Track".encodeToByteArray())
        client.onEntityUpdate(byteArrayOf(0, 1, 0) + "1,1.0,12.5".encodeToByteArray())
        val expected = client.state.value
        for (size in 0..2) {
            client.onEntityUpdate(ByteArray(size))
            assertEquals("Packet size $size", expected, client.state.value)
        }
        val invalid = listOf("", "1", "1,1.0", "x,1.0,12", "-1,1.0,12", "4,1.0,12",
            "1,x,12", "1,1.0,x", "1,NaN,12", "1,Infinity,12", "1,-Infinity,12",
            "1,1.0,NaN", "1,1.0,Infinity", "1,1.0,-Infinity")
        for (value in invalid) {
            client.onEntityUpdate(byteArrayOf(0, 1, 0) + value.encodeToByteArray())
            assertEquals("Playback value '$value'", expected, client.state.value)
        }
    }

    @Test
    fun pausedStoppedAndSeekingStatesDoNotReportPlaying() {
        val client = AMSClient()
        for (state in listOf(0, 2, 3)) {
            client.onEntityUpdate(byteArrayOf(0, 1, 0) + "1,1.0,12".encodeToByteArray())
            client.onEntityUpdate(byteArrayOf(0, 1, 0) + "$state,0.5,15".encodeToByteArray())
            assertFalse("Playback state $state", client.state.value.player.isPlaying)
            assertEquals(0.5f, client.state.value.player.playbackRate, 0f)
            assertEquals(15f, client.state.value.player.elapsedTime, 0f)
        }
    }

    @Test
    fun unknownAttributesDoNotOverwriteMetadataOrPlayback() {
        val client = AMSClient()
        client.onEntityUpdate(byteArrayOf(2, 2, 0) + "Track".encodeToByteArray())
        client.onEntityUpdate(byteArrayOf(2, 0, 0) + "Artist".encodeToByteArray())
        client.onEntityUpdate(byteArrayOf(0, 1, 0) + "1,1.0,12".encodeToByteArray())
        val expected = client.state.value
        for (entity in listOf(0, 1, 2, 42)) {
            client.onEntityUpdate(byteArrayOf(entity.toByte(), 42, 0) + "Unexpected".encodeToByteArray())
            assertEquals("Entity $entity", expected, client.state.value)
        }
    }
}
