package io.github.dingyi222666.luabytecode.cli

import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.platform.Md5
import io.github.dingyi222666.luabytecode.platform.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Asset delivery consistency (roadmap step 6b): the native embedded decoder
 * constants must be byte-identical to the JVM classpath assets and the js
 * base64 constants. The committed md5 digests were computed from the very
 * same resource files by `tools/generate-js-decoder-assets.py`; the feature
 * modules' JVM DecoderAssetIntegrityTests pin the classpath side, the js
 * LuaDecoderAssetsJsTest pins the js side and this suite pins the native side
 * — the three deliveries cannot drift apart without failing a build.
 */
class LuaDecoderAssetsNativeTest {

    @Test
    fun `all six bundled decoders match their committed md5 digests`() {
        for (path in LuaDecoderAssetsNative.paths) {
            val bytes = assertNotNull(LuaDecoderAssetsNative.load(path), path)
            assertEquals(
                LuaDecoderAssetsNative.md5HexByPath[path],
                Md5.digestHex(bytes),
                "$path must stay byte-identical to the JVM classpath asset",
            )
        }
    }

    @Test
    fun `every bundled decoder parses as a chunk of the version its name carries`() {
        for (path in LuaDecoderAssetsNative.paths) {
            val bytes = assertNotNull(LuaDecoderAssetsNative.load(path), path)
            assertTrue(LuaChunkLoader.isLuaBytecode(bytes), "$path missing the Lua signature")
            val chunk = LuaChunkLoader.load(bytes)
            val expectedVersionByte = path.substringAfterLast('_').removeSuffix(".luac").toInt(16)
            assertEquals(
                expectedVersionByte,
                chunk.header.version.versionByte,
                "$path carries the wrong bytecode version",
            )
        }
    }

    @Test
    fun `unknown paths load null like the jvm classpath source`() {
        assertNull(LuaDecoderAssetsNative.load("/obfuscator/decode_99.luac"))
        assertNull(LuaDecoderAssetsNative.load("/anything/else.bin"))
    }

    @Test
    fun `NativePlatform serves entropy and bundled chunks through the seams`() {
        NativePlatform.install()
        // /dev/urandom entropy: 1024 draws never repeat (a repeat would mean
        // either a broken seed source or a broken seam).
        val seen = HashSet<Long>()
        repeat(1024) { assertTrue(seen.add(Platform.entropy.nextLong())) }
        // The installed bundled chunk source resolves the decoder assets and
        // stays null-safe for unknown paths (strings/constants pass contract).
        assertNotNull(Platform.bundledChunks.load("/obfuscator/decode_54.luac"))
        assertNull(Platform.bundledChunks.load("/obfuscator/decode_99.luac"))
        // The debug switches read the process environment (unset on CI), the
        // sink must not throw.
        assertTrue(!Platform.debug.enabled("CFF_DEBUG"))
        assertEquals(null, Platform.debug.value("K_DEBUG"))
        Platform.debug.log("NativePlatform smoke")
        // The refusal trace sink must stay silent and not throw without the
        // /tmp sentinel installed.
        Platform.traceSink.append("NativePlatform trace smoke")
    }
}
