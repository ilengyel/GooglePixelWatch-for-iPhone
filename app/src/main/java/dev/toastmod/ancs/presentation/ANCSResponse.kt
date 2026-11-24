package dev.toastmod.ancs.presentation

data class ANCSAttributes(
    val appIdentifier: String,
    val title: String,
    val subtitle: String,
    val message: String,
    val positiveActionLabel: String = "",
    val negativeActionLabel: String = ""
)

internal fun ByteArray.notificationUid(): Int {
    require(size == 4) { "ANCS UID must contain four bytes" }
    return indices.fold(0) { value, index ->
        value or ((this[index].toInt() and 0xff) shl (index * 8))
    }
}

internal class ANCSResponse(uid: ByteArray, requested: Set<Int> = setOf(0, 1, 2, 3)) {
    private val response = ANCSAttributeResponse(
        byteArrayOf(0) + uid,
        requested.associateWith { if (it == 0 || it in 6..7) 1024 else 511 }
    )

    init {
        require(uid.size == 4) { "ANCS UID must contain four bytes" }
        require(requested.isNotEmpty() && requested.all { it in 0..3 || it in 6..7 })
    }

    fun append(fragment: ByteArray): ANCSAttributes? = response.append(fragment)?.let {
        ANCSAttributes(it[0].orEmpty(), it[1].orEmpty(), it[2].orEmpty(), it[3].orEmpty(),
            it[6].orEmpty(), it[7].orEmpty())
    }
}

internal class ANCSAppResponse(appIdentifier: String) {
    private val response = ANCSAttributeResponse(
        byteArrayOf(1) + appIdentifier.encodeToByteArray() + byteArrayOf(0), mapOf(0 to 1024)
    )

    init {
        require(appIdentifier.isNotEmpty() && '\u0000' !in appIdentifier &&
            appIdentifier.encodeToByteArray().size <= 1024) { "Invalid ANCS app identifier" }
    }

    fun append(fragment: ByteArray): String? = response.append(fragment)?.getValue(0)
}

private class ANCSAttributeResponse(
    private val header: ByteArray,
    private val limits: Map<Int, Int>
) {
    private var buffer = ByteArray(64)
    private var size = 0
    private var index = 0
    private var headerValidated = false
    private val attributes = mutableMapOf<Int, String>()
    private var attributeId = -1
    private var attributeLength = 0

    fun append(fragment: ByteArray): Map<Int, String>? {
        require(fragment.size <= 4096 - size) { "ANCS response exceeds size limit" }
        val newSize = size + fragment.size
        if (newSize > buffer.size) {
            buffer = buffer.copyOf(maxOf(newSize, buffer.size * 2).coerceAtMost(4096))
        }
        fragment.copyInto(buffer, size)
        size = newSize
        if (!headerValidated) {
            require((0 until minOf(size, header.size)).all { buffer[it] == header[it] }) {
                "ANCS response command or identifier mismatch"
            }
            if (size < header.size) return null
            headerValidated = true
            index = header.size
        }

        while (index < size || attributeId != -1) {
            if (attributeId == -1) {
                if (size - index < 3) return null
                val id = buffer[index].toInt() and 0xff
                require(id in limits && id !in attributes) { "Unexpected or duplicate ANCS attribute $id" }
                val length = (buffer[index + 1].toInt() and 0xff) or
                    ((buffer[index + 2].toInt() and 0xff) shl 8)
                require(length <= limits.getValue(id)) { "ANCS attribute $id exceeds requested limit" }
                attributeId = id
                attributeLength = length
                index += 3
            }
            if (size - index < attributeLength) return null
            attributes[attributeId] = buffer.decodeToString(index, index + attributeLength)
            index += attributeLength
            attributeId = -1
        }
        return attributes.takeIf { it.size == limits.size }
    }
}
