package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cross-pass integration acceptance (stays with the root orchestrator): Pass R
 * name rewrite must survive every preset pipeline, i.e. the other passes must
 * not re-introduce readable local debug names and R must run after the passes
 * that append stub blocks. Pass-level tests live in :lua-obfuscation-register.
 */
class LuaRegisterPipelineTest {

    @Test
    fun `preset pipelines leave no readable local names behind`() {
        val presets = listOf(
            "LIGHT" to ObfuscationConfig.LIGHT.copy(registerObfuscation = RegisterObfuscationConfig()),
            "BALANCED" to ObfuscationConfig.BALANCED.copy(registerObfuscation = RegisterObfuscationConfig()),
            // AGGRESSIVE currently depends on B3's decoder resources landing; run it
            // without the constant layer here so this test stays scoped to Pass R.
            "AGGRESSIVE(no-K)" to ObfuscationConfig.AGGRESSIVE.copy(constantEncryption = null),
        )
        for ((suffix, version) in VERSIONS) {
            for ((label, config) in presets) {
                val chunk = LuaChunkLoader.load(bytes("big_module", suffix), version)
                LuaObfuscator.obfuscate(chunk, config)
                val reloaded = LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
                val leaked = LEAKED_IDENTIFIERS.filter { identifier ->
                    reloaded.prototypes.any { prototype ->
                        prototype.localVariables.any { it.name?.utf8 == identifier }
                    }
                }
                assertTrue(
                    leaked.isEmpty(),
                    "big_module_$suffix ($label): local debug names survived the pipeline: $leaked",
                )
            }
        }
    }

    private companion object {
        val VERSIONS = listOf(
            "53" to LuaBytecodeVersion.LUA_5_3,
            "54" to LuaBytecodeVersion.LUA_5_4,
            "55" to LuaBytecodeVersion.LUA_5_5,
        )

        /** The unluac visible identifiers of `big_module` (see red/R3 Round1). */
        val LEAKED_IDENTIFIERS = listOf("base64Alphabet", "gcd", "isPrime", "matrixMultiply")

        fun bytes(name: String, suffix: String): ByteArray = checkNotNull(
            LuaRegisterPipelineTest::class.java.getResourceAsStream("/real/${name}_$suffix.luac"),
        ) { "missing /real/${name}_$suffix.luac" }.use { it.readBytes() }
    }
}
