package io.github.dingyi222666.luabytecode.cli

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getpid
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * File level end-to-end runs of the native CLI over real bytes: files in
 * (posix), pipeline, files out. The fixture is `real/ctor/c01_numericfor_53`
 * embedded as base64 — the same byte-verified chunk the js tests and the
 * pipeline contract test use.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeCliEndToEndTest {

    /** `real/ctor/c01_numericfor_53.luac` (Lua 5.3, numeric for loop). */
    private val numericFor53Base64: String =
        "G0x1YVMAGZMNChoKBAgECAh4VgAAAAAAAAAAAAAAKHdAARRAYzAxX251bWVyaWNmb3IubHVhAAAAAAAAAAAAAQULAAAAAQAAAEFA" +
        "AACBgAAAwUAAAGgAAIANAAEAZ0D/f0bAQACAAAAAZEAAASYAgAAEAAAAEwAAAAAAAAAAEwEAAAAAAAAAEwoAAAAAAAAABAZwcmlu" +
        "dAEAAAABAAAAAAALAAAAAQAAAAIAAAACAAAAAgAAAAIAAAACAAAAAgAAAAMAAAADAAAAAwAAAAMAAAAFAAAABnRvdGFsAQAAAAsA" +
        "AAAMKGZvciBpbmRleCkEAAAABwAAAAwoZm9yIGxpbWl0KQQAAAAHAAAACyhmb3Igc3RlcCkEAAAABwAAAAJpBQAAAAYAAAABAAAA" +
        "BV9FTlY="

    private fun tempPath(label: String): String {
        val dir = NativePlatform.posixGetenv("TMPDIR")?.takeIf { it.isNotEmpty() } ?: "/tmp"
        return "$dir/lua-cli-native-$label-${getpid()}.tmp"
    }

    private fun bytesEqual(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        for (index in left.indices) {
            if (left[index] != right[index]) return false
        }
        return true
    }

    @Test
    fun `balanced preset round trips through files`() {
        NativePlatform.install() // balanced runs string encryption over the embedded decoder assets
        val input = tempPath("in")
        val output = tempPath("out")
        assertTrue(writeFileBytes(input, Base64.decode(numericFor53Base64)), "fixture write failed")
        val code = runNativeCli(
            arrayOf(input, "--obfuscate", "balanced", "--seed", "20260917", "--dump", output),
        )
        assertEquals(0, code, "balanced obfuscation must succeed")
        val original = assertNotNull(readFileBytes(input))
        val obfuscated = assertNotNull(readFileBytes(output), "the dumped output is missing")
        assertFalse(bytesEqual(original, obfuscated), "balanced must change the bytes")
        val reloaded = LuaChunkLoader.load(obfuscated, LuaBytecodeVersion.LUA_5_3)
        assertEquals(LuaBytecodeVersion.LUA_5_3, reloaded.header.version)
        assertEquals(
            LuaChunkLoader.load(original).mainPrototype.numParams,
            reloaded.mainPrototype.numParams,
            "the parameter arity must survive the pass stack",
        )
    }

    @Test
    fun `granular flags obfuscate without a preset`() {
        NativePlatform.install()
        val input = tempPath("flags")
        val output = tempPath("flags-out")
        assertTrue(writeFileBytes(input, Base64.decode(numericFor53Base64)), "fixture write failed")
        val code = runNativeCli(
            arrayOf(input, "--jmp=3", "--junk", "--clone=2", "--seed", "7", "--dump", output),
        )
        assertEquals(0, code, "granular flag run must succeed")
        val obfuscated = assertNotNull(readFileBytes(output))
        LuaChunkLoader.load(obfuscated, LuaBytecodeVersion.LUA_5_3)
    }

    @Test
    fun `invalid bytecode exits one`() {
        NativePlatform.install()
        val input = tempPath("garbage")
        assertTrue(writeFileBytes(input, ByteArray(64) { it.toByte() }), "fixture write failed")
        assertEquals(1, runNativeCli(arrayOf(input)), "not a chunk must exit 1")
    }

    @Test
    fun `a version expectation mismatch exits one`() {
        NativePlatform.install()
        val input = tempPath("version")
        assertTrue(writeFileBytes(input, Base64.decode(numericFor53Base64)), "fixture write failed")
        assertEquals(1, runNativeCli(arrayOf(input, "--expect", "5.4")))
    }

    @Test
    fun `vm-out emits the lifted interpreter module`() {
        NativePlatform.install()
        val input = tempPath("vm")
        val output = tempPath("vm-out")
        assertTrue(writeFileBytes(input, Base64.decode(numericFor53Base64)), "fixture write failed")
        val code = runNativeCli(arrayOf(input, "--seed", "3", "--vm-out", output))
        assertEquals(0, code, "c01 numericfor must stay inside the modelled VM subset")
        val module = assertNotNull(readFileBytes(output), "the emitted interpreter module is missing")
        assertTrue(module.isNotEmpty(), "the emitted interpreter module must not be empty")
    }
}
