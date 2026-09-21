package io.github.dingyi222666.luabytecode.bytecode

/**
 * A Lua string value, kept as raw bytes.
 *
 * Lua strings are byte sequences, not text: keeping the original bytes allows a
 * chunk to be re-emitted byte for byte. Use [utf8] for a best effort text view
 * and [latin1] for a lossless one-byte-per-char view.
 */
class LuaString(val bytes: ByteArray) {

    val size: Int
        get() = bytes.size

    val isEmpty: Boolean
        get() = bytes.isEmpty()

    /** Best effort UTF-8 decoding (malformed sequences are replaced with U+FFFD). */
    val utf8: String by lazy(LazyThreadSafetyMode.NONE) {
        bytes.decodeToString()
    }

    /** Lossless decoding: byte `b` becomes the char `U+00b`. */
    val latin1: String by lazy(LazyThreadSafetyMode.NONE) {
        val chars = CharArray(bytes.size)
        for (i in bytes.indices) {
            chars[i] = (bytes[i].toInt() and 0xFF).toChar()
        }
        chars.concatToString()
    }

    operator fun get(index: Int): Byte = bytes[index]

    override fun equals(other: Any?): Boolean =
        this === other || (other is LuaString && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = utf8

    companion object {
        fun of(text: String): LuaString = LuaString(text.encodeToByteArray())

        fun of(bytes: ByteArray): LuaString = LuaString(bytes)
    }
}
