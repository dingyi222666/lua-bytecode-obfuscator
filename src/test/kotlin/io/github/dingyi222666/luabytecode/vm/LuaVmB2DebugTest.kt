package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.platform.jvm.load
import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import java.io.File
import kotlin.test.Test

/** Temporary B2 debug harness (deleted before handoff). */
class LuaVmB2DebugTest {
    @Test
    fun dumpHeaderAlignment() {
        val chunk = LuaChunkLoader.load(
            File("src/test/resources/real/ctor/c01_numericfor_53.luac"),
            LuaBytecodeVersion.LUA_5_3,
        )
        val random = ObfRandom(7L)
        val map = LuaVmProgram.OpcodeMap.random(random)
        val program = LuaVmTranslator.translate(chunk.mainPrototype, map, scratch = chunk.mainPrototype.maxStackSize)!!
        println("RETURN number: " + map.encode(LuaVmProgram.Op.RETURN))
        println("RETK number: " + map.encode(LuaVmProgram.Op.RETK))
        println("GETTABUP number: " + map.encode(LuaVmProgram.Op.GETTABUP))
        println("FORLOOP number: " + map.encode(LuaVmProgram.Op.FORLOOP))
        program.words.forEachIndexed { i, w ->
            val insn = LuaVmProgram.decode(map, w)
            println("word $i: op=" + map.encode(insn.op) + " " + insn.op + " a=" + insn.a + " b=" + insn.b + " c=" + insn.c)
        }
    }
}
