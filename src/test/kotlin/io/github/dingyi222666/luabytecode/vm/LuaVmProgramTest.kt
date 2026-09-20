package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.vm.LuaVmProgram.Insn
import io.github.dingyi222666.luabytecode.vm.LuaVmProgram.Op
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** P4.1: the private ISA must round trip exactly and never share a numbering across chunks. */
class LuaVmProgramTest {
    @Test
    fun `every opcode round trips through a random private numbering`() {
        val random = java.util.Random(20250815L)
        repeat(20) {
            val map = LuaVmProgram.OpcodeMap.random(random)
            for (op in Op.entries) {
                // the x form normalises b/c to 0, so build the canonical shape
                val insn = Insn(op, 1, if (op.hasX) 0 else 2, if (op.hasX) 0 else 3)
                assertEquals(insn, LuaVmProgram.decode(map, LuaVmProgram.encode(map, insn)), "op=$op")
            }
        }
    }

    @Test
    fun `the x form keeps its 16 bit immediate`() {
        val map = LuaVmProgram.OpcodeMap.random(java.util.Random(7L))
        for (x in listOf(-32768, -1, 0, 1, 32767)) {
            val insn = Insn(Op.SUPERTABLE, 4, 0, 0, x)
            val back = LuaVmProgram.decode(map, LuaVmProgram.encode(map, insn))
            assertEquals(x, back.x, "x=$x")
            assertEquals(Op.SUPERTABLE, back.op)
        }
    }

    @Test
    fun `two chunks get different numberings`() {
        val a = LuaVmProgram.OpcodeMap.random(java.util.Random(1L))
        val b = LuaVmProgram.OpcodeMap.random(java.util.Random(2L))
        val differs = Op.entries.any { a.encode(it) != b.encode(it) }
        assertTrue(differs, "two chunks must not share the same opcode numbering")
        assertNotEquals(a.encode(Op.ADD), a.encode(Op.SUB))
    }

    @Test
    fun `the stream cipher round trips and hides the plain words`() {
        val words = IntArray(64) { it * 2654435761L.toInt() }
        val cipher = LuaVmProgram.Cipher.random(java.util.Random(20250815L))
        val encrypted = cipher.crypt(words, 20250815L)
        assertTrue(!encrypted.contentEquals(words), "the ciphertext must differ from the plain words")
        assertTrue(
            cipher.crypt(encrypted, 20250815L).contentEquals(words),
            "the same keystream must decrypt",
        )
        // a different chunk seed must not decrypt it
        assertTrue(!cipher.crypt(encrypted, 1L).contentEquals(words))
    }

    @Test
    fun `an identity map keeps the natural order`() {
        for (op in Op.entries) {
            assertEquals(op.ordinal, LuaVmProgram.OpcodeMap.identity.encode(op))
        }
    }
}
