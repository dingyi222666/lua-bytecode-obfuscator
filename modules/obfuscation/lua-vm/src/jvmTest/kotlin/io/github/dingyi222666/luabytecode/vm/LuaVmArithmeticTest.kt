package io.github.dingyi222666.luabytecode.vm

import kotlin.test.Test
import kotlin.test.assertEquals

class LuaVmArithmeticTest {
    @Test
    fun `extended arithmetic and unary operations execute`() {
        val map = LuaVmProgram.OpcodeMap.identity
        fun i(op: LuaVmProgram.Op, a: Int, b: Int = 0, c: Int = 0) = LuaVmProgram.encode(map, LuaVmProgram.Insn(op, a, b, c))
        val words = intArrayOf(
            i(LuaVmProgram.Op.DIV, 2, 0, 1),
            i(LuaVmProgram.Op.IDIV, 3, 0, 1),
            i(LuaVmProgram.Op.MOD, 4, 0, 1),
            i(LuaVmProgram.Op.POW, 5, 1, 1),
            i(LuaVmProgram.Op.UNM, 6, 0),
            i(LuaVmProgram.Op.CONCAT, 7, 0, 1),
            i(LuaVmProgram.Op.RETURN, 2, 6, 0),
        )
        val program = LuaVmTranslator.VmProgram(words, emptyList(), 8, 2, false)
        val result = LuaVmInterpreter.run(program, map, listOf(7L, 2L))
        assertEquals(listOf(3.5, 3L, 1L, 4.0, -7L, "72"), result.returned)
    }
}
