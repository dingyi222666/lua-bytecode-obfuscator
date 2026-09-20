package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LuaVmCountLoopGeneratedTest {
    @Test
    fun `generated 5_4 count loop module runs on target lua`() = runGenerated(LuaBytecodeVersion.LUA_5_4, "/tmp/lua-5.4.6/src/lua", 54L)
    @Test
    fun `generated 5_5 count loop module runs on target lua`() = runGenerated(LuaBytecodeVersion.LUA_5_5, "/tmp/lua-5.5.1/src/lua", 55L)

    private fun runGenerated(version: LuaBytecodeVersion, luaPath: String, seed: Long) {
        val lua = File(luaPath)
        if (!lua.canExecute()) return
        val suffix = if (version == LuaBytecodeVersion.LUA_5_4) "54" else "55"
        val chunk = LuaChunkLoader.load(File("src/test/resources/real/ctor/c14_numericfor_print_$suffix.luac"), version)
        val module = LuaVmObfuscation.emitModule(chunk, java.util.Random(seed), LuaVmObfuscation.Options(seed = seed, enableCountBasedLoops = true))
        assertNotNull(module)
        val file = File.createTempFile("vm-count-$suffix", ".lua")
        file.writeText(module)
        val process = ProcessBuilder(lua.path, file.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertTrue(process.waitFor() == 0, output)
        assertEquals("1\n2\n3\n2\n3\n4\n10\n7\n4\n1", output)
    }
}
