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
    fun assemblesRequestedActionLabelsAtEveryFragmentBoundary() {
        val bytes = byteArrayOf(0) + uid + attribute(1, "Call") + attribute(2, "") +
            attribute(3, "") + attribute(6, "R\u00e9pondre") + attribute(7, "Decline")
        val expected = ANCSAttributes("", "Call", "", "", "R\u00e9pondre", "Decline")
        for (split in 1 until bytes.size) {
            val parser = ANCSResponse(uid, setOf(1, 2, 3, 6, 7))
            assertNull(parser.append(bytes.copyOfRange(0, split)))
            assertEquals(expected, parser.append(bytes.copyOfRange(split, bytes.size)))
        }
        assertEquals(ANCSAttributes("", "", "", ""),
            ANCSResponse(uid, setOf(6)).append(byteArrayOf(0) + uid + attribute(6, "")))
        assertThrows(IllegalArgumentException::class.java) {
            ANCSResponse(uid, setOf(6)).append(byteArrayOf(0) + uid + attribute(7, "Wrong action"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ANCSResponse(uid, setOf(6)).append(byteArrayOf(0) + uid + attribute(6, "x".repeat(1025)))
        }
    }

    @Test
    fun completesOnlyTheRequestedNotificationSubset() {
        val identity = byteArrayOf(0) + uid + attribute(0, "com.example.app")
        val text = byteArrayOf(0) + uid + attribute(1, "Title") +
            attribute(2, "") + attribute(3, "Message")
        assertEquals(ANCSAttributes("com.example.app", "", "", ""),
            ANCSResponse(uid, setOf(0)).append(identity))
        assertEquals(ANCSAttributes("", "Title", "", "Message"),
            ANCSResponse(uid, setOf(1, 2, 3)).append(text))
        assertThrows(IllegalArgumentException::class.java) {
            ANCSResponse(uid, setOf(0)).append(text)
        }
    }

    @Test
    fun assemblesAppNamesAtEveryFragmentBoundaryIncludingEmptyAndMultibyteNames() {
        for (name in listOf("", "Caf\u00e9 \ud83c\udfb5")) {
            val bytes = byteArrayOf(1) + "com.example.app".encodeToByteArray() +
                byteArrayOf(0) + attribute(0, name)
            for (split in 1 until bytes.size) {
                val parser = ANCSAppResponse("com.example.app")
                assertNull(parser.append(bytes.copyOfRange(0, split)))
                assertEquals(name, parser.append(bytes.copyOfRange(split, bytes.size)))
            }
            val parser = ANCSAppResponse("com.example.app")
            bytes.dropLast(1).forEach { assertNull(parser.append(byteArrayOf(it))) }
            assertEquals(name, parser.append(byteArrayOf(bytes.last())))
        }
    }

    @Test
    fun rejectsMalformedAppResponsesAndOversizedNames() {
        val header = byteArrayOf(1) + "app".encodeToByteArray() + byteArrayOf(0)
        for (bytes in listOf(
            byteArrayOf(0) + header.drop(1).toByteArray(),
            byteArrayOf(1) + "other".encodeToByteArray() + byteArrayOf(0),
            header + attribute(1, "Wrong attribute"),
            header + attribute(0, "") + attribute(0, ""),
            header + attribute(0, "x".repeat(1025)),
            ByteArray(4097)
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ANCSAppResponse("app").append(bytes)
            }
        }
        val parser = ANCSAppResponse("app")
        assertNull(parser.append(header + byteArrayOf(0)))
        assertEquals("x".repeat(1024), parser.append(
            byteArrayOf(0, 4) + "x".repeat(1024).encodeToByteArray()))
    }

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
