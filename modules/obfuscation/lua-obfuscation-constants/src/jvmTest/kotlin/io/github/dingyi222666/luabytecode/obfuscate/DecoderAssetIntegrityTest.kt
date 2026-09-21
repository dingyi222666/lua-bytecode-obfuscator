package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.platform.Md5
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins the md5 digests of the bundled kget decoder assets on the JVM side
 * (`jvmMain/resources/obfuscator/decode_num_NN.luac`). The digests come from
 * `tools/generate-js-decoder-assets.py`, which generates the byte-identical
 * base64 constants for the js target (:lua-runtime-js LuaDecoderAssetsJs) and
 * the same digests into that generated file — the js-side suite
 * LuaDecoderAssetsJsTest asserts them there. Editing an asset without
 * regenerating the js constants (or vice versa) fails one of the two suites.
 */
class DecoderAssetIntegrityTest {

    @Test
    fun `decode num 5_3 asset keeps its committed digest`() {
        val bytes = assertNotNull(
            javaClass.getResourceAsStream("/obfuscator/decode_num_53.luac")?.readBytes(),
        )
        assertEquals("27ac5383f75592a4e1b40027cf25dfef", Md5.digestHex(bytes))
    }

    @Test
    fun `decode num 5_4 asset keeps its committed digest`() {
        val bytes = assertNotNull(
            javaClass.getResourceAsStream("/obfuscator/decode_num_54.luac")?.readBytes(),
        )
        assertEquals("626531c30419c1923cef87f51ade25db", Md5.digestHex(bytes))
    }

    @Test
    fun `decode num 5_5 asset keeps its committed digest`() {
        val bytes = assertNotNull(
            javaClass.getResourceAsStream("/obfuscator/decode_num_55.luac")?.readBytes(),
        )
        assertEquals("2c4233abfbdc33ce8dff3c3e0d45b41d", Md5.digestHex(bytes))
    }
}
