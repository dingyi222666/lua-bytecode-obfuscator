package io.github.dingyi222666.luabytecode.bytecode


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LuaInstructionTest {

    @Test
    fun `decodes 5_3 operands`() {
        // LOADK A=3 Bx=300
        val raw = 1 or (3 shl 6) or (300 shl 14)
        val instruction = LuaInstruction(raw, LuaBytecodeVersion.LUA_5_3)

        assertEquals(1, instruction.opcode)
        assertEquals("LOADK", instruction.opcodeName)
        assertEquals(3, instruction.a)
        assertEquals(300, instruction.bx)
        assertEquals(0, instruction.b)
        assertEquals(300 and 0x1FF, instruction.c)
        assertEquals(300 - 131071, instruction.sbx)
        assertEquals(0, instruction.k)
        assertEquals(3, instruction.ax and 0xFF)
    }

    @Test
    fun `decodes 5_4 operands`() {
        // ADD A=5 B=7 C=9 k=1
        val raw = 34 or (5 shl 7) or (1 shl 15) or (7 shl 16) or (9 shl 24)
        val instruction = LuaInstruction(raw, LuaBytecodeVersion.LUA_5_4)

        assertEquals(34, instruction.opcode)
        assertEquals("ADD", instruction.opcodeName)
        assertEquals(5, instruction.a)
        assertEquals(7, instruction.b)
        assertEquals(9, instruction.c)
        assertEquals(1, instruction.k)
        // Bx is the 17 bit field at bit 15: k is its lowest bit, then B, then C.
        assertEquals(1 or (7 shl 1) or (9 shl 9), instruction.bx)
        assertEquals((1 or (7 shl 1) or (9 shl 9)) - 65535, instruction.sbx)
    }

    @Test
    fun `decodes signed 5_4 operands`() {
        val instruction = LuaInstruction(
            21 or (2 shl 7) or (126 shl 24), // ADDI A=2, C=126 -> sC = -1
            LuaBytecodeVersion.LUA_5_4,
        )
        assertEquals("ADDI", instruction.opcodeName)
        assertEquals(-1, instruction.signedC)

        val shift = LuaInstruction(
            32 or (2 shl 7) or (0x7B shl 16), // SHRI A=2, B=0x7B -> sB = -4
            LuaBytecodeVersion.LUA_5_4,
        )
        assertEquals(-4, shift.signedB)
    }

    @Test
    fun `decodes 5_4 jump offsets`() {
        // OFFSET_sJ == MAXARG_sJ >> 1 == 16777215, the field is 25 bits wide at bit 7.
        fun jumpRaw(sj: Int): Int = ((16777215 + sj).toLong() shl 7).toInt()
        assertEquals(0, LuaInstruction(jumpRaw(0), LuaBytecodeVersion.LUA_5_4).sj)
        assertEquals(10, LuaInstruction(jumpRaw(10), LuaBytecodeVersion.LUA_5_4).sj)
        assertEquals(-10, LuaInstruction(jumpRaw(-10), LuaBytecodeVersion.LUA_5_4).sj)
    }

    @Test
    fun `rejects sJ on 5_3`() {
        assertFailsWith<UnsupportedOperationException> {
            LuaInstruction(0, LuaBytecodeVersion.LUA_5_3).sj
        }
    }

    @Test
    fun `knows the operand layout of every opcode`() {
        assertEquals(LuaOpcodeMode.ABC, LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_3, 0)) // MOVE
        assertEquals(LuaOpcodeMode.ABX, LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_3, 1)) // LOADK
        assertEquals(LuaOpcodeMode.AX, LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_3, 46)) // EXTRAARG
        assertEquals(LuaOpcodeMode.ASBX, LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_3, 30)) // JMP
        assertEquals(LuaOpcodeMode.ASBX, LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_3, 39)) // FORLOOP

        assertEquals(LuaOpcodeMode.ASBX, LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_4, 1)) // LOADI
        assertEquals(LuaOpcodeMode.ABX, LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_4, 3)) // LOADK
        assertEquals(LuaOpcodeMode.SJ, LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_4, 56)) // JMP
        assertEquals(LuaOpcodeMode.AX, LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_4, 82)) // EXTRAARG
        assertNull(LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_4, 83))
        assertNull(LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_3, -1))
    }

    @Test
    fun `formats operands by layout`() {
        fun field(value: Int, shift: Int): Int = (value.toLong() shl shift).toInt()

        // 5.3 FORLOOP A=1 sBx=-2
        val forLoop = LuaInstruction(39 or (1 shl 6) or field(131071 - 2, 14), LuaBytecodeVersion.LUA_5_3)
        assertEquals("FORLOOP", forLoop.opcodeName)
        assertEquals("A=1 sBx=-2", forLoop.formatOperands())

        // 5.4 LOADI A=3 sBx=0 (Bx field 65535)
        val loadI = LuaInstruction(1 or (3 shl 7) or field(65535, 15), LuaBytecodeVersion.LUA_5_4)
        assertEquals("LOADI", loadI.opcodeName)
        assertEquals("A=3 sBx=0", loadI.formatOperands())

        // 5.4 JMP sJ=-3
        assertEquals(
            "sJ=-3",
            LuaInstruction(56 or field(16777215 - 3, 7), LuaBytecodeVersion.LUA_5_4).formatOperands(),
        )

        // 5.4 ADD (ABC) keeps the k flag
        assertEquals(
            "A=5 B=7 C=9 k=1",
            LuaInstruction(34 or (5 shl 7) or (1 shl 15) or (7 shl 16) or (9 shl 24), LuaBytecodeVersion.LUA_5_4)
                .formatOperands(),
        )
    }

    @Test
    fun `handles all bits set`() {
        val instruction = LuaInstruction(0xFFFFFFFF.toInt(), LuaBytecodeVersion.LUA_5_4)
        assertEquals(0x7F, instruction.opcode)
        assertEquals(0xFF, instruction.a)
        assertEquals(0xFF, instruction.b)
        assertEquals(0xFF, instruction.c)
        assertEquals(1, instruction.k)
        assertTrue(instruction.opcodeName.startsWith("UNKNOWN"))
    }

    @Test
    fun `knows the 5_5 ivABC layout`() {
        assertEquals(LuaOpcodeMode.VABC, LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_5, 19)) // NEWTABLE
        assertEquals(LuaOpcodeMode.VABC, LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_5, 78)) // SETLIST
        assertEquals(LuaOpcodeMode.SJ, LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_5, 56)) // JMP
        assertEquals(LuaOpcodeMode.ABC, LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_5, 19 - 1))
        assertNull(LuaOpcodeModes.modeOf(LuaBytecodeVersion.LUA_5_5, 85))

        val newTable = LuaInstruction.vabc(LuaBytecodeVersion.LUA_5_5, opcode = 19, a = 1, vB = 5, vC = 300, k = 1)
        assertEquals(1, newTable.a)
        assertEquals(5, newTable.vB)
        assertEquals(300, newTable.vC)
        assertEquals(1, newTable.k)
        assertEquals("A=1 vB=5 vC=300 k=1", newTable.formatOperands())
    }

    @Test
    fun `builds instructions with the factories and the DSL`() {
        val version = LuaBytecodeVersion.LUA_5_5
        fun opcode(name: String): Int = LuaOpcodes.opcodeOf(version, name)!!

        val move = LuaInstruction.abc(version, opcode = opcode("MOVE"), a = 1, b = 2)
        assertEquals("MOVE", move.opcodeName)
        assertEquals(1, move.a)
        assertEquals(2, move.b)
        assertEquals(0, move.c)

        assertEquals(7, LuaInstruction.abx(version, opcode = opcode("LOADK"), a = 0, bx = 7).bx)
        assertEquals("A=2 sBx=-12", LuaInstruction.asbx(version, opcode = opcode("LOADI"), a = 2, sbx = -12).formatOperands())
        // 5.5 switched the loop opcodes from iAsBx to the unsigned iABx layout.
        assertEquals("A=2 Bx=3", LuaInstruction.abx(version, opcode = opcode("FORLOOP"), a = 2, bx = 3).formatOperands())
        assertEquals(9, LuaInstruction.ax(version, opcode = opcode("EXTRAARG"), ax = 9).ax)
        assertEquals("sJ=-3", LuaInstruction.sj(version, opcode = opcode("JMP"), sj = -3).formatOperands())

        val jump = luaInstruction(version) {
            opcode = opcode("JMP")
            sj = -3
        }
        assertEquals("JMP sJ=-3", jump.toString())

        val loaded = luaInstruction(LuaBytecodeVersion.LUA_5_3) {
            opcode = LuaOpcodes.opcodeOf(LuaBytecodeVersion.LUA_5_3, "LOADK")!!
            a = 3
            bx = 12
        }
        assertEquals("LOADK A=3 Bx=12", loaded.toString())

        assertFailsWith<IllegalArgumentException> { LuaInstruction.abc(LuaBytecodeVersion.LUA_5_3, opcode = 0, a = 260) }
        assertFailsWith<IllegalArgumentException> { LuaInstruction.abc(LuaBytecodeVersion.LUA_5_3, opcode = 0, k = 1) }
        assertFailsWith<IllegalArgumentException> { LuaInstruction.vabc(LuaBytecodeVersion.LUA_5_4, opcode = 19) }
        assertFailsWith<IllegalArgumentException> { luaInstruction(LuaBytecodeVersion.LUA_5_5) { opcode = 999 } }
    }

    @Test
    fun `patches operands without touching the others`() {
        val instruction = LuaInstruction.abc(LuaBytecodeVersion.LUA_5_5, opcode = 34, a = 1, b = 2, c = 3, k = 0)

        assertEquals("ADD A=5 B=2 C=3 k=0", instruction.withA(5).toString())
        assertEquals("ADD A=1 B=2 C=9 k=0", instruction.withC(9).toString())
        assertEquals("ADD A=1 B=2 C=3 k=1", instruction.withK(1).toString())
        assertEquals(300, LuaInstruction.abc(LuaBytecodeVersion.LUA_5_3, opcode = 0).withC(300).c)
        assertFailsWith<IllegalArgumentException> {
            LuaInstruction.abc(LuaBytecodeVersion.LUA_5_4, opcode = 0).withC(300)
        }

        val forLoop = LuaInstruction.asbx(LuaBytecodeVersion.LUA_5_3, opcode = 39, a = 1, sbx = -2)
        assertEquals(-2, forLoop.sbx)
        assertEquals(7, forLoop.withSbx(7).sbx)
        assertEquals(0, forLoop.withOpcode(0).opcode)
        assertEquals("MOVE A=1 B=0 C=0", LuaInstruction(0, LuaBytecodeVersion.LUA_5_3).withA(1).toString())
    }
}
