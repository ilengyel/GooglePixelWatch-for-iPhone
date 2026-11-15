package dev.toastmod.ancs.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ANCSResponseTest {
    private val uid = byteArrayOf(0x12, 0x34, 0x56, 0x78)
    private val expected = ANCSAttributes("com.example.app", "Title", "Subtitle", "Message")

    private fun attribute(id: Int, text: String): ByteArray {
        val bytes = text.encodeToByteArray()
        return byteArrayOf(id.toByte(), bytes.size.toByte(), (bytes.size shr 8).toByte()) + bytes
    }

    private fun response() = byteArrayOf(0) + uid +
        attribute(0, expected.appIdentifier) + attribute(1, expected.title) +
        attribute(2, expected.subtitle) + attribute(3, expected.message)

    @Test
    fun waitsForEveryAttributeAtEveryPossibleFragmentBoundary() {
        val bytes = response()
        for (split in 1 until bytes.size) {
            val parser = ANCSResponse(uid)
            assertNull("Split $split completed prematurely", parser.append(bytes.copyOfRange(0, split)))
            assertEquals(expected, parser.append(bytes.copyOfRange(split, bytes.size)))
        }
    }

    @Test
    fun assemblesSingleByteFragmentsIncludingMultibyteText() {
        val text = "Caf\u00e9 \ud83c\udfb5"
        val bytes = byteArrayOf(0) + uid + attribute(0, "") +
            attribute(1, text) + attribute(2, "") + attribute(3, "")
        val parser = ANCSResponse(uid)
        bytes.dropLast(1).forEach { assertNull(parser.append(byteArrayOf(it))) }
        assertEquals(ANCSAttributes("", text, "", ""), parser.append(byteArrayOf(bytes.last())))
    }

    @Test
    fun rejectsResponsesForAnotherNotificationOrCommand() {
        assertThrows(IllegalArgumentException::class.java) {
            ANCSResponse(uid).append(response().also { it[1] = 0 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            ANCSResponse(uid).append(response().also { it[0] = 1 })
        }
    }

    @Test
    fun rejectsDuplicateAndUnexpectedAttributes() {
        assertThrows(IllegalArgumentException::class.java) {
            ANCSResponse(uid).append(byteArrayOf(0) + uid + attribute(0, "") + attribute(0, ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ANCSResponse(uid).append(byteArrayOf(0) + uid + attribute(4, ""))
        }
    }

    @Test
    fun enforcesRequestedTextLengthAndBufferLimit() {
        val parser = ANCSResponse(uid)
        val prefix = byteArrayOf(0) + uid + attribute(0, "") + attribute(1, "x".repeat(511)) +
            attribute(2, "")
        assertNull(parser.append(prefix))
        assertEquals("x".repeat(511), parser.append(attribute(3, ""))?.title)
        assertThrows(IllegalArgumentException::class.java) {
            ANCSResponse(uid).append(byteArrayOf(0) + uid + attribute(1, "x".repeat(512)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ANCSResponse(uid).append(ByteArray(4097))
        }
    }

    @Test
    fun assemblesMaximumLengthAttributesOutOfOrderOneByteAtATime() {
        val expected = ANCSAttributes("a".repeat(1024), "t".repeat(511), "s".repeat(511), "m".repeat(511))
        val bytes = byteArrayOf(0) + uid + attribute(3, expected.message) +
            attribute(1, expected.title) + attribute(0, expected.appIdentifier) + attribute(2, expected.subtitle)
        val parser = ANCSResponse(uid)
        for (index in 0 until bytes.lastIndex) {
            assertNull(parser.append(byteArrayOf(bytes[index])))
        }
        assertEquals(expected, parser.append(byteArrayOf(bytes.last())))
    }

    @Test
    fun rejectsDuplicateAttributeAcrossFragments() {
        val parser = ANCSResponse(uid)
        assertNull(parser.append(byteArrayOf(0) + uid + attribute(0, "App")))
        assertNull(parser.append(byteArrayOf(0)))
        assertThrows(IllegalArgumentException::class.java) {
            parser.append(byteArrayOf(0, 0))
        }
    }
}
