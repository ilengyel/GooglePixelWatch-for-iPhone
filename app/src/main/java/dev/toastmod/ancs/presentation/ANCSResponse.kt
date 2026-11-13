package dev.toastmod.ancs.presentation

import java.io.ByteArrayOutputStream

data class ANCSAttributes(
    val appIdentifier: String,
    val title: String,
    val subtitle: String,
    val message: String
)

internal fun ByteArray.notificationUid(): Int {
    require(size == 4) { "ANCS UID must contain four bytes" }
    return indices.fold(0) { value, index ->
        value or ((this[index].toInt() and 0xff) shl (index * 8))
    }
}

internal class ANCSResponse(private val uid: ByteArray) {
    private val buffer = ByteArrayOutputStream()

    init {
        require(uid.size == 4) { "ANCS UID must contain four bytes" }
    }

    fun append(fragment: ByteArray): ANCSAttributes? {
        require(buffer.size() + fragment.size <= 4096) { "ANCS response exceeds size limit" }
        buffer.write(fragment)
        val data = buffer.toByteArray()
        if (data.size < 5) return null
        require(data[0] == 0.toByte()) { "Unexpected ANCS response command" }
        require(data.copyOfRange(1, 5).contentEquals(uid)) { "ANCS response UID mismatch" }

        val attributes = mutableMapOf<Int, String>()
        var index = 5
        while (index < data.size) {
            if (data.size - index < 3) return null
            val id = data[index].toInt() and 0xff
            require(id in 0..3 && id !in attributes) { "Unexpected or duplicate ANCS attribute $id" }
            val length = (data[index + 1].toInt() and 0xff) or
                ((data[index + 2].toInt() and 0xff) shl 8)
            require(length <= if (id == 0) 1024 else 511) { "ANCS attribute $id exceeds requested limit" }
            if (data.size - index - 3 < length) return null
            attributes[id] = data.copyOfRange(index + 3, index + 3 + length).decodeToString()
            index += 3 + length
        }
        if (attributes.size != 4) return null
        return ANCSAttributes(
            attributes.getValue(0), attributes.getValue(1),
            attributes.getValue(2), attributes.getValue(3)
        )
    }
}
