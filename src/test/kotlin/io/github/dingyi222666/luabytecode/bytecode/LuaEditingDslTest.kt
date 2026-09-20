package io.github.dingyi222666.luabytecode.bytecode

import io.github.dingyi222666.luabytecode.bytecode.edit.*
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class LuaEditingDslTest {
    @Test fun `instruction stream resolves labeled jumps`() {
        val p = LuaPrototype(version = LuaBytecodeVersion.LUA_5_4, source = null, lineDefined = 0, lastLineDefined = 0, numParams = 0, isVararg = false, isVarargFlags = 0, maxStackSize = 2, code = mutableListOf(), constants = mutableListOf(), upvalues = mutableListOf(), nestedPrototypes = mutableListOf(), lineInfo = mutableListOf(), absoluteLineInfo = mutableListOf(), localVariables = mutableListOf(), upvalueNames = mutableListOf())
        val stream = LuaPrototypeScope(p).stream()
            .abc("LOADI", 0, 1)
            .jump("done")
            .abc("LOADI", 0, 2)
            .mark("done")
            .abc("RETURN0")
        val code = stream.build()
        assertEquals("JMP", code[1].opcodeName)
        assertEquals(3, code[1].jumpTarget(1))
    }

    @Test fun `expression DSL emits arithmetic pairs`() {
        val p = LuaPrototype(version = LuaBytecodeVersion.LUA_5_4, code = mutableListOf(), constants = mutableListOf(), source = null, lineDefined = 0, lastLineDefined = 0, numParams = 0, isVararg = false, isVarargFlags = 0, maxStackSize = 8, upvalues = mutableListOf(), nestedPrototypes = mutableListOf(), lineInfo = mutableListOf(), absoluteLineInfo = mutableListOf(), localVariables = mutableListOf(), upvalueNames = mutableListOf())
        LuaPrototypeScope(p).emitExpression(add(reg(0), num(1)), 2, listOf(3)) { register, value ->
            LuaInstruction.abx(p.version, LuaOpcodes.opcodeOf(p.version, "LOADI")!!, register, value.toInt())
        }
        assertEquals(listOf("MOVE", "LOADI", "ADD", "MMBIN"), p.code.map { it.opcodeName })
    }
}
