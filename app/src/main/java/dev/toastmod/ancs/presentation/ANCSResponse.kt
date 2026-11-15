package dev.toastmod.ancs.presentation

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
    private var buffer = ByteArray(64)
    private var size = 0
    private var index = 0
    private var headerValidated = false
    private val attributes = arrayOfNulls<String>(4)
    private var attributeCount = 0
    private var attributeId = -1
    private var attributeLength = 0

    init {
        require(uid.size == 4) { "ANCS UID must contain four bytes" }
    }

    fun append(fragment: ByteArray): ANCSAttributes? {
        require(fragment.size <= 4096 - size) { "ANCS response exceeds size limit" }
        val newSize = size + fragment.size
        if (newSize > buffer.size) {
            buffer = buffer.copyOf(maxOf(newSize, buffer.size * 2).coerceAtMost(4096))
        }
        fragment.copyInto(buffer, size)
        size = newSize
        if (!headerValidated) {
            if (size < 5) return null
            require(buffer[0] == 0.toByte()) { "Unexpected ANCS response command" }
            require(uid.indices.all { buffer[it + 1] == uid[it] }) { "ANCS response UID mismatch" }
            headerValidated = true
            index = 5
        }

        while (index < size || attributeId != -1) {
            if (attributeId == -1) {
                if (size - index < 3) return null
                val id = buffer[index].toInt() and 0xff
                require(id in 0..3 && attributes[id] == null) { "Unexpected or duplicate ANCS attribute $id" }
                val length = (buffer[index + 1].toInt() and 0xff) or
                    ((buffer[index + 2].toInt() and 0xff) shl 8)
                require(length <= if (id == 0) 1024 else 511) { "ANCS attribute $id exceeds requested limit" }
                attributeId = id
                attributeLength = length
                index += 3
            }
            if (size - index < attributeLength) return null
            attributes[attributeId] = buffer.decodeToString(index, index + attributeLength)
            index += attributeLength
            attributeCount++
            attributeId = -1
        }
        if (attributeCount != 4) return null
        return ANCSAttributes(
            requireNotNull(attributes[0]), requireNotNull(attributes[1]),
            requireNotNull(attributes[2]), requireNotNull(attributes[3])
        )
    }
}
