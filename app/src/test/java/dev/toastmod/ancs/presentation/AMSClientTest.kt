package dev.toastmod.ancs.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AMSClientTest {
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
}
