package io.github.dingyi222666.luabytecode.vm

import kotlin.test.Test
import kotlin.test.assertEquals

class LuaVmVarargTest {
    @Test
    fun `vararg values retain nils and explicit result count`() {
        val map = LuaVmProgram.OpcodeMap.identity
        fun i(op: LuaVmProgram.Op, a: Int, b: Int = 0, c: Int = 0) =
            LuaVmProgram.encode(map, LuaVmProgram.Insn(op, a, b, c))
        val program = LuaVmTranslator.VmProgram(
            words = intArrayOf(i(LuaVmProgram.Op.VARARG, 0, 0), i(LuaVmProgram.Op.RETURN, 0, 0, 0)),
            constants = emptyList(), maxStackSize = 3, numParams = 0, isVararg = true,
        )
        val result = LuaVmInterpreter.run(program, map, args = listOf(1L, null, 3L))
        assertEquals(listOf(1L, null, 3L), result.returned)
    }
}
