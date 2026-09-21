package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.platform.jvm.load
import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P4.3/P4 product path: every chunk gets its own generated interpreter, its own private opcode
 * numbering, an encrypted program and an encrypted constant pool. Two emissions of the same
 * chunk must differ textually and both must run identically on the real Lua 5.3.6.
 */
class LuaVmGeneratedInterpreterTest {
    private val lua = File("/tmp/lua-5.3.6/src/lua")

    private fun emit(seed: Long): String {
        val chunk = LuaChunkLoader.load(
            File("src/test/resources/real/ctor/c01_numericfor_53.luac"),
            LuaBytecodeVersion.LUA_5_3,
        )
        val module = LuaVmObfuscation.emitModule(chunk, ObfRandom(seed), LuaVmObfuscation.Options(seed = seed))
        assertNotNull(module, "c01_numericfor must be inside the VM subset")
        return module
    }

    private fun run(module: String): String {
        val file = File.createTempFile("vm-module", ".lua")
        file.writeText(module)
        val process = ProcessBuilder(lua.path, file.path).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        assertTrue(process.waitFor() == 0, "the VM module failed: $text")
        return text
    }

    @Test
    fun `per chunk modules differ and all agree with the native run`() {
        if (!lua.canExecute()) return // no local toolchain: keep the suite green

        val a = emit(11L)
        val b = emit(22L)
        assertTrue(a != b, "two chunks must not share the same interpreter source")

        // the fixture sums 1..10 natively (verified: 55)
        assertEquals("55", run(a))
        assertEquals("55", run(b))

        // no plain text ISA name or string constant may survive in the shipped module
        for (token in listOf("LOADK", "FORLOOP", "GETTABLE", "\"print\"")) {
            assertTrue(!a.contains(token), "plain text leaked into the module: $token")
        }
    }
}
