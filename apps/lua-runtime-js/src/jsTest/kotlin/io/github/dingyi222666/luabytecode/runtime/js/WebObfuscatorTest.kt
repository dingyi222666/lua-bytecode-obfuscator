package io.github.dingyi222666.luabytecode.runtime.js

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * API level end-to-end tests in the node environment (roadmap step 6): bytes
 * in, report + bytes out, and the obfuscated output must reload through the
 * common loader again. The chunk fixture is embedded as base64 — file reading
 * is a browser concern implemented by the static web UI, not by the API.
 */
class WebObfuscatorTest {


    @Test
    fun `balanced preset output reloads through the common loader`() {
        JsPlatform.install()
        val original = LuaChunkLoader.load(Base64.decode(numericFor53Base64), null)
        val result = obfuscateLuaChunk(
            numericFor53Base64,
            WebObfuscationRequestJs(preset = "balanced", masterSeed = 20260917L),
        )
        assertEquals("Lua 5.3", result.version)
        // the balanced preset runs string encryption, whose decoder stub is
        // one of the bundled assets delivered through the JS seam
        assertTrue("strings:" in result.reportText, result.reportText)
        val reloaded = LuaChunkLoader.load(Base64.decode(result.chunkBase64), LuaBytecodeVersion.LUA_5_3)
        assertEquals(original.header.version, reloaded.header.version)
        assertEquals(original.mainPrototype.numParams, reloaded.mainPrototype.numParams)
    }

    @Test
    fun `aggressive preset on the bank fixture reloads through the common loader`() {
        // bank_53 is the fixture the JVM frontends run AGGRESSIVE against, so
        // the full pass stack (strings + jumps + junk + clones + CFF + Pass K
        // + registers) must behave identically behind the web API.
        JsPlatform.install()
        val result = obfuscateLuaChunk(
            bank53Base64,
            WebObfuscationRequestJs(preset = "aggressive", masterSeed = 20260917L),
        )
        assertEquals("Lua 5.3", result.version)
        val reloaded = LuaChunkLoader.load(Base64.decode(result.chunkBase64), null)
        assertEquals(LuaBytecodeVersion.LUA_5_3, reloaded.header.version)
        // the aggressive stack includes CFF and Pass K: both must report work
        assertTrue("flattened:" in result.reportText, result.reportText)
        assertTrue("constants:" in result.reportText, result.reportText)
    }

    @Test
    fun `none preset keeps the chunk semantics intact`() {
        JsPlatform.install()
        val result = obfuscateLuaChunk(
            numericFor53Base64,
            WebObfuscationRequestJs(preset = "none", masterSeed = 20260917L),
        )
        val reloaded = LuaChunkLoader.load(Base64.decode(result.chunkBase64), null)
        val original = LuaChunkLoader.load(Base64.decode(numericFor53Base64), null)
        assertEquals(original.mainPrototype.code.size, reloaded.mainPrototype.code.size)
        assertEquals(original.mainPrototype.constants.size, reloaded.mainPrototype.constants.size)
        assertTrue("nothing" in result.reportText, result.reportText)
    }

    @Test
    fun `pinned master seed reproduces the same artifact`() {
        JsPlatform.install()
        val request = WebObfuscationRequestJs(preset = "light", masterSeed = 4242L)
        val first = obfuscateLuaChunk(numericFor53Base64, request)
        val second = obfuscateLuaChunk(numericFor53Base64, request)
        assertEquals(first.chunkBase64, second.chunkBase64, "same seed must pin the artifact")
        assertEquals(4242L, first.masterSeed)
    }

    @Test
    fun `inspect reports the chunk version`() {
        assertEquals("Lua 5.3", inspectLuaChunk(numericFor53Base64))
    }

    @Test
    fun `invalid chunks and bad arguments are rejected with messages`() {
        JsPlatform.install()
        val notAChunk = assertFailsWith<LuaBytecodeFormatException> {
            obfuscateLuaChunk(Base64.encode(ByteArray(64) { it.toByte() }), WebObfuscationRequestJs())
        }
        assertTrue(notAChunk.message!!.contains("not a precompiled Lua chunk"))
        val badBase64 = assertFailsWith<IllegalArgumentException> {
            obfuscateLuaChunk("!!! not base64 !!!", WebObfuscationRequestJs())
        }
        assertTrue(badBase64.message!!.contains("base64"))
        val badPreset = assertFailsWith<IllegalArgumentException> {
            obfuscateLuaChunk(numericFor53Base64, WebObfuscationRequestJs(preset = "mega"))
        }
        assertTrue(badPreset.message!!.contains("preset"))
        val badVersion = assertFailsWith<IllegalArgumentException> {
            obfuscateLuaChunk(
                numericFor53Base64,
                WebObfuscationRequestJs(preset = "none", expectedVersion = "5.1"),
            )
        }
        assertTrue(badVersion.message!!.contains("supported"))
        val wrongVersion = assertFailsWith<LuaBytecodeFormatException> {
            obfuscateLuaChunk(
                numericFor53Base64,
                WebObfuscationRequestJs(preset = "none", expectedVersion = "5.4"),
            )
        }
        assertTrue(wrongVersion.message!!.contains("was expected"))
    }
}
