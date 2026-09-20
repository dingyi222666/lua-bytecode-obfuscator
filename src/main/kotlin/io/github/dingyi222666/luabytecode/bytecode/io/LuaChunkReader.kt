package io.github.dingyi222666.luabytecode.bytecode.io

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaVarintStyle

import java.nio.ByteOrder


/**
 * Low level reader over the raw bytes of a Lua binary chunk.
 *
 * Every method that consumes bytes raises a [LuaBytecodeFormatException] when
 * the chunk is truncated, so a malformed file can never read outside the array.
 * The reader keeps track of [position] to produce readable error offsets.
 */
class LuaChunkReader(
    private val data: ByteArray,
    /** Byte order of the chunk; the loader switches it after reading `LUAC_INT`. */
    var byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
) {

    /** Current read offset inside [data]. */
    var position: Int = 0
        private set

    /** Total size of the underlying data. */
    val size: Int
        get() = data.size

    /** Number of bytes that have not been consumed yet. */
    val remaining: Int
        get() = data.size - position

    /** Moves the read cursor to [newPosition] (used for endianness probing). */
    fun seek(newPosition: Int) {
        if (newPosition < 0 || newPosition > data.size) {
            throw LuaBytecodeFormatException(
                "cannot seek to $newPosition, the chunk has ${data.size} bytes",
                position,
            )
        }
        position = newPosition
    }

    /** Reads one unsigned byte. */
    fun readByte(): Int {
        require(1)
        return data[position++].toInt() and 0xFF
    }

    /** Reads [count] bytes, returning a fresh array. */
    fun readBytes(count: Int): ByteArray {
        if (count < 0) {
            throw LuaBytecodeFormatException("negative byte count $count", position)
        }
        require(count)
        val result = data.copyOfRange(position, position + count)
        position += count
        return result
    }

    /**
     * Reads an unsigned integer of [width] bytes (1..8) in [byteOrder].
     */
    fun readUnsigned(width: Int): Long {
        if (width < 1 || width > 8) {
            throw LuaBytecodeFormatException("unsupported integer width $width", position)
        }
        val bytes = readBytes(width)
        var value = 0L
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            for (i in 0 until width) {
                value = (value shl 8) or (bytes[i].toLong() and 0xFF)
            }
        } else {
            for (i in width - 1 downTo 0) {
                value = (value shl 8) or (bytes[i].toLong() and 0xFF)
            }
        }
        return value
    }

    /** Reads a two's complement signed integer of [width] bytes (1..8). */
    fun readSigned(width: Int): Long {
        val raw = readUnsigned(width)
        if (width >= 8) {
            return raw
        }
        val shift = 64 - width * 8
        return (raw shl shift) shr shift
    }

    /** Reads a fixed size 32 bit signed `int`. */
    fun readInt32(): Int = readSigned(4).toInt()

    /** Reads a `lua_Number` of [width] bytes (4 = float, 8 = double). */
    fun readLuaNumber(width: Int): Double = when (width) {
        4 -> Float.fromBits(readUnsigned(4).toInt()).toDouble()
        8 -> Double.fromBits(readUnsigned(8))
        else -> throw LuaBytecodeFormatException("unsupported lua_Number width $width", position)
    }

    /**
     * Reads a variable length integer: 7 bits per byte, most significant group
     * first.
     *
     * [style] selects which byte carries the `0x80` marker:
     *  * [LuaVarintStyle.TERMINATOR_FLAG] — Lua 5.4, the last byte is marked;
     *  * [LuaVarintStyle.CONTINUATION_FLAG] — Lua 5.5, every other byte is marked.
     *
     * [limit] mirrors the C `limit` argument: the result is guaranteed to stay
     * below it, otherwise the chunk is rejected. Comparisons are **unsigned**:
     * the 5.5 integer constants pass `LUA_MAXUNSIGNED` (`-1L` = all bits set)
     * and encode values up to `2^64 - 2`.
     */
    fun readVarint(
        limit: Long = Long.MAX_VALUE,
        style: LuaVarintStyle = LuaVarintStyle.TERMINATOR_FLAG,
    ): Long {
        // `limit` is interpreted as an unsigned 64 bit value.
        val capped = limit ushr 7
        var value = 0L
        while (true) {
            val b = readByte()
            value = (value shl 7) or (b and 0x7F).toLong()
            val finished = when (style) {
                LuaVarintStyle.TERMINATOR_FLAG -> b and 0x80 != 0
                LuaVarintStyle.CONTINUATION_FLAG -> b and 0x80 == 0
            }
            // `l_carry guard` of `loadVarint`: `if (x > limit >> 7) error` before
            // shifting in the NEXT group (unsigned compare). A value == capped is
            // still legal when the next group is the last one (it fills the low
            // 7 bits exactly: `0x7fffffffffffffff` dumps as ten 5.5 bytes
            // `81 ff ff ff ff ff ff ff ff 7e`).
            if (!finished && value.compareUnsigned(capped) > 0) {
                throw LuaBytecodeFormatException("integer overflow while reading a varint", position)
            }
            if (finished) {
                if (value.compareUnsigned(limit) > 0) {
                    throw LuaBytecodeFormatException("integer overflow while reading a varint", position)
                }
                return value
            }
        }
    }

    /** Unsigned comparison of two Longs treated as 64 bit patterns. */
    private fun Long.compareUnsigned(other: Long): Int =
        (this xor Long.MIN_VALUE).compareTo(other xor Long.MIN_VALUE)

    private fun require(count: Int) {
        if (count > remaining) {
            throw LuaBytecodeFormatException(
                "truncated chunk: needed $count more byte(s) but only $remaining left",
                position,
            )
        }
    }
}
