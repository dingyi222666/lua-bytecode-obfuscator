package io.github.dingyi222666.luabytecode.bytecode.io

import io.github.dingyi222666.luabytecode.bytecode.LuaByteOrder
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaVarintStyle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue


class LuaChunkReaderTest {

    private fun reader(vararg bytes: Int, byteOrder: LuaByteOrder = LuaByteOrder.LITTLE) =
        LuaChunkReader(ByteArray(bytes.size) { bytes[it].toByte() }, byteOrder)

    @Test
    fun `reads 5_4 variable length integers`() {
        assertEquals(0L, reader(0x80).readVarint())
        assertEquals(0x7FL, reader(0xFF).readVarint())
        assertEquals(300L, reader(0x02, 0xAC).readVarint())
        assertEquals(1_000_000L, reader(0x3D, 0x04, 0xC0).readVarint())
        assertEquals(1L, reader(0x81).readVarint())
    }

    @Test
    fun `reads 5_5 continuation flagged variable length integers`() {
        val style = LuaVarintStyle.CONTINUATION_FLAG
        assertEquals(0L, reader(0x00).readVarint(style = style))
        assertEquals(0x7FL, reader(0x7F).readVarint(style = style))
        assertEquals(300L, reader(0x82, 0x2C).readVarint(style = style))
        assertEquals(1_000_000L, reader(0xBD, 0x84, 0x40).readVarint(style = style))
        assertFailsWith<LuaBytecodeFormatException> { reader(0x80).readVarint(style = style) }
    }

    @Test
    fun `rejects overflowing variable length integers`() {
        // 0x7F 0x7F 0x7F 0x7F 0x80 decodes to 2^35 - 1, far above INT_MAX.
        val error = assertFailsWith<LuaBytecodeFormatException> {
            reader(0x7F, 0x7F, 0x7F, 0x7F, 0x80).readVarint(Int.MAX_VALUE.toLong())
        }
        assertTrue(error.message!!.contains("overflow"))
    }

    @Test
    fun `rejects a truncated variable length integer`() {
        assertFailsWith<LuaBytecodeFormatException> { reader(0x00).readVarint() }
    }

    @Test
    fun `reads little endian signed integers with sign extension`() {
        assertEquals(128L, reader(0x80, 0x00).readSigned(2))
        assertEquals(-32768L, reader(0x00, 0x80).readSigned(2))
        assertEquals(-1L, reader(0xFF).readSigned(1))
        assertEquals(Int.MIN_VALUE.toLong(), reader(0x00, 0x00, 0x00, 0x80).readSigned(4))
    }

    @Test
    fun `reads big endian signed integers`() {
        assertEquals(
            -32768L,
            reader(0x80, 0x00, byteOrder = LuaByteOrder.BIG).readSigned(2),
        )
        assertEquals(
            0x5678L,
            reader(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x56, 0x78, byteOrder = LuaByteOrder.BIG)
                .readSigned(8),
        )
    }

    @Test
    fun `reads lua numbers`() {
        assertEquals(370.5, reader(0x00, 0x00, 0x00, 0x00, 0x00, 0x28, 0x77, 0x40).readLuaNumber(8), 0.0)
        assertEquals(0.5, reader(0x00, 0x00, 0x00, 0x3F).readLuaNumber(4), 0.000001)
    }

    @Test
    fun `tracks the position and rejects out of range reads`() {
        val reader = reader(0x01, 0x02, 0x03)
        assertEquals(0, reader.position)
        assertEquals(3, reader.remaining)
        reader.readByte()
        reader.readBytes(2)
        assertEquals(3, reader.position)
        assertEquals(0, reader.remaining)

        assertFailsWith<LuaBytecodeFormatException> { reader.readByte() }
        assertFailsWith<LuaBytecodeFormatException> { reader.seek(4) }
        assertFailsWith<LuaBytecodeFormatException> { reader.readBytes(-1) }
        assertFailsWith<LuaBytecodeFormatException> { reader.readUnsigned(9) }
    }

    @Test
    fun `truncated reads report the offset`() {
        val error = assertFailsWith<LuaBytecodeFormatException> {
            reader(0x01).readBytes(4)
        }
        assertTrue(error.offset >= 0)
        assertTrue(error.message!!.contains("truncated"))
    }
}
