package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.platform.jvm.load
import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Opt-in coverage for Lua 5.4/5.5 count based numeric-for semantics. */
class LuaVmCountLoopTest {
    @Test
    fun `5_4 count based numeric for runs exact iterations`() {
        val chunk = LuaChunkLoader.load(File("src/test/resources/real/ctor/c14_numericfor_print_54.luac"), LuaBytecodeVersion.LUA_5_4)
        val map = LuaVmProgram.OpcodeMap.random(ObfRandom(54L))
        val program = LuaVmTranslator.translate(chunk.mainPrototype, map, enableCountBasedLoops = true)
        assertNotNull(program)
        val printed = ArrayList<String>()
        val env = mapOf<String, Any?>("print" to { argv: List<Any?> -> printed += argv.joinToString("\t") { it.toString() }; emptyList<Any?>() })
        LuaVmInterpreter.run(program, map, upvalues = listOf(env))
        assertEquals(listOf("1", "2", "3", "2", "3", "4", "10", "7", "4", "1"), printed)
    }

    @Test
    fun `5_5 count based numeric for runs exact iterations`() {
        val chunk = LuaChunkLoader.load(File("src/test/resources/real/ctor/c14_numericfor_print_55.luac"), LuaBytecodeVersion.LUA_5_5)
        val map = LuaVmProgram.OpcodeMap.random(ObfRandom(55L))
        val program = LuaVmTranslator.translate(chunk.mainPrototype, map, enableCountBasedLoops = true)
        assertNotNull(program)
        val printed = ArrayList<String>()
        val env = mapOf<String, Any?>("print" to { argv: List<Any?> -> printed += argv.joinToString("\t") { it.toString() }; emptyList<Any?>() })
        LuaVmInterpreter.run(program, map, upvalues = listOf(env))
        assertEquals(listOf("1", "2", "3", "2", "3", "4", "10", "7", "4", "1"), printed)
    }
}
