package io.github.dingyi222666.luabytecode.bytecode.io

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.TestFixtures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith


class LuaChunkErrorTest {

    @Test
    fun `rejects empty input`() {
        val error = assertFailsWith<LuaBytecodeFormatException> { LuaChunkLoader.load(ByteArray(0)) }
        assertEquals(0, error.offset)
    }

    @Test
    fun `rejects a foreign signature`() {
        val error = assertFailsWith<LuaBytecodeFormatException> {
            LuaChunkLoader.load("not a lua chunk".toByteArray())
        }
        assertTrue(error.message!!.contains("signature"))
        assertEquals(0, error.offset)
    }

    @Test
    fun `detects the signature`() {
        assertTrue(LuaChunkLoader.isLuaBytecode(TestFixtures.bytes("hello_54.luac")))
        assertTrue(LuaChunkLoader.isLuaBytecode(TestFixtures.bytes("big_53.luac")))
        assertFalse(LuaChunkLoader.isLuaBytecode("PK\u0003\u0004".toByteArray()))
        assertFalse(LuaChunkLoader.isLuaBytecode(byteArrayOf(0x1B)))
    }

    @Test
    fun `rejects unsupported bytecode versions`() {
        // 0x51 = 5.1, 0x52 = 5.2, 0x56 = 5.6 and 0x00 are all rejected.
        for (unsupported in intArrayOf(0x51, 0x52, 0x56, 0x00)) {
            val bytes = TestFixtures.bytes("hello_54.luac")
            bytes[4] = unsupported.toByte()
            val error = assertFailsWith<LuaBytecodeFormatException> {
                LuaChunkLoader.load(bytes)
            }
            assertTrue(error.message!!.contains("unsupported bytecode version"), "version $unsupported")
            assertEquals(4, error.offset)
        }
    }

    @Test
    fun `rejects a chunk with a mismatched expected version`() {
        val error = assertFailsWith<LuaBytecodeFormatException> {
            LuaChunkLoader.load(TestFixtures.bytes("hello_54.luac"), LuaBytecodeVersion.LUA_5_3)
        }
        assertTrue(error.message!!.contains("Lua 5.3"))
        assertTrue(error.message!!.contains("expected"))
    }

    @Test
    fun `rejects a corrupted LUAC_DATA marker`() {
        val bytes = TestFixtures.bytes("hello_53.luac")
        bytes[6] = 0x00
        val error = assertFailsWith<LuaBytecodeFormatException> { LuaChunkLoader.load(bytes) }
        assertTrue(error.message!!.contains("corrupted chunk"))
        assertEquals(6, error.offset)
    }

    @Test
    fun `rejects a wrong chunk format byte`() {
        val bytes = TestFixtures.bytes("hello_53.luac")
        bytes[5] = 0x01
        val error = assertFailsWith<LuaBytecodeFormatException> { LuaChunkLoader.load(bytes) }
        assertTrue(error.message!!.contains("format"))
    }

    @Test
    fun `rejects chunks truncated at every interesting offset`() {
        for (name in listOf("hello_53.luac", "hello_54.luac", "features_53.luac", "features_54.luac")) {
            val bytes = TestFixtures.bytes(name)
            val cuts = intArrayOf(1, 4, 5, 8, 12, 20, 23, bytes.size / 3, bytes.size / 2, bytes.size - 1)
            for (cut in cuts) {
                assertFailsWith<LuaBytecodeFormatException>("$name cut at $cut") {
                    LuaChunkLoader.load(bytes.copyOf(cut))
                }
            }
        }
    }

    @Test
    fun `rejects a corrupted type size byte`() {
        val bytes = TestFixtures.bytes("hello_53.luac")
        // 5.3 layout: 4 signature + 1 version + 1 format + 6 LUAC_DATA, then sizeof(int).
        bytes[12] = 0x02
        val error = assertFailsWith<LuaBytecodeFormatException> { LuaChunkLoader.load(bytes) }
        assertTrue(error.message!!.contains("sizeof(int)"))
    }
}
