package io.github.dingyi222666.luabytecode.vm

import kotlin.test.Test
import kotlin.test.assertEquals

class LuaVmClosureTest {
    @Test
    fun `closure reads and writes a shared upvalue cell`() {
        val map = LuaVmProgram.OpcodeMap.identity
        val child = LuaVmTranslator.VmProgram(
            words = intArrayOf(
                LuaVmProgram.encode(map, LuaVmProgram.Insn(LuaVmProgram.Op.GETUPVAL, 0, 0, 0)),
                LuaVmProgram.encode(map, LuaVmProgram.Insn(LuaVmProgram.Op.LOADI, 1, 1, 0)),
                LuaVmProgram.encode(map, LuaVmProgram.Insn(LuaVmProgram.Op.ADD, 0, 0, 1)),
                LuaVmProgram.encode(map, LuaVmProgram.Insn(LuaVmProgram.Op.SETUPVAL, 0, 0, 0)),
                LuaVmProgram.encode(map, LuaVmProgram.Insn(LuaVmProgram.Op.RETURN, 0, 1, 0)),
            ),
            constants = emptyList(),
            maxStackSize = 2,
            numParams = 0,
            isVararg = false,
        )
        val parent = LuaVmTranslator.VmProgram(
            words = intArrayOf(
                LuaVmProgram.encode(map, LuaVmProgram.Insn(LuaVmProgram.Op.CLOSURE, 0, 0, 0)),
                LuaVmProgram.encode(map, LuaVmProgram.Insn(LuaVmProgram.Op.CALL, 0, 0, 1)),
                LuaVmProgram.encode(map, LuaVmProgram.Insn(LuaVmProgram.Op.RETURN, 0, 1, 0)),
            ),
            constants = emptyList(),
            maxStackSize = 1,
            numParams = 0,
            isVararg = false,
            children = listOf(child),
            childCaptures = listOf(listOf(LuaVmTranslator.VmProgram.Capture(inStack = false, index = 0))),
        )
        val cell = LuaVmCell(41L)
        val result = LuaVmInterpreter.run(parent, map, upvalues = listOf(cell))
        assertEquals(listOf(42L), result.returned)
        assertEquals(42L, cell.value)
    }
}
