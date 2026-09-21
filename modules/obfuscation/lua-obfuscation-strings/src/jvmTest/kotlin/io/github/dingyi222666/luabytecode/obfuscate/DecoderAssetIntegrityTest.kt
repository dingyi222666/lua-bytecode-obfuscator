package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.platform.Md5
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins the md5 digests of the bundled decoder assets on the JVM side
 * (`jvmMain/resources/obfuscator/decode_NN.luac`). The digests come from
 * `tools/generate-js-decoder-assets.py`, which generates the byte-identical
 * base64 constants for the js target (:lua-runtime-js LuaDecoderAssetsJs) and
 * the same digests into that generated file — the js-side suite
 * LuaDecoderAssetsJsTest asserts them there. Editing an asset without
 * regenerating the js constants (or vice versa) fails one of the two suites.
 */
class DecoderAssetIntegrityTest {

    @Test
    fun `decode 5_3 asset keeps its committed digest`() {
        val bytes = assertNotNull(
            javaClass.getResourceAsStream("/obfuscator/decode_53.luac")?.readBytes(),
        )
        assertEquals("fecea709184c7d4ee39d6cde8ad183c1", Md5.digestHex(bytes))
    }

    @Test
    fun `decode 5_4 asset keeps its committed digest`() {
        val bytes = assertNotNull(
            javaClass.getResourceAsStream("/obfuscator/decode_54.luac")?.readBytes(),
        )
        assertEquals("2e355169ce424a22136a33664eb1777b", Md5.digestHex(bytes))
    }

    @Test
    fun `decode 5_5 asset keeps its committed digest`() {
        val bytes = assertNotNull(
            javaClass.getResourceAsStream("/obfuscator/decode_55.luac")?.readBytes(),
        )
        assertEquals("5fd0405d160621760977352781b3bfba", Md5.digestHex(bytes))
    }
}
