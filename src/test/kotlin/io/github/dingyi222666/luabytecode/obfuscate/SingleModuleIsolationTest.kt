package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the Phase 4 acceptance criterion "every optional obfuscation module
 * can be disabled individually": each built-in pass is switched by a nullable
 * [ObfuscationConfig] field, so enabling exactly one feature must produce a
 * report in which ONLY that feature's section is present (plus the always-on
 * tail-return syntax repair), the extension module list stays untouched and
 * the chunk still dump/reload round trips.
 *
 * This is the switch proof for the feature artifacts
 * (:lua-obfuscation-strings / -constants / -jumps / -cff / -register and the
 * root-side key renaming pass); compilation-wise every feature already builds
 * and publishes as its own Gradle module.
 */
class SingleModuleIsolationTest {

    private val version = LuaBytecodeVersion.LUA_5_4

    /** Pinned so the isolation proof is reproducible (PRED-2 precedence). */
    private val seed = 20260917L

    private fun chunk() = LuaChunkLoader.load(
        checkNotNull(SingleModuleIsolationTest::class.java.getResourceAsStream("/bytecode/flow_54.luac")) {
            "missing fixture /bytecode/flow_54.luac"
        }.use { it.readBytes() },
        version,
    )

    private fun cases(): List<Triple<String, ObfuscationConfig, (ObfuscationReport) -> Any?>> = listOf(
        Triple("strings", ObfuscationConfig(masterSeed = seed, stringEncryption = StringEncryptionConfig())) { it.stringEncryption },
        Triple("constants", ObfuscationConfig(masterSeed = seed, constantEncryption = ConstantEncryptionConfig())) { it.constantEncryption },
        Triple("jumps", ObfuscationConfig(masterSeed = seed, jumpIndirection = JumpIndirectionConfig(depth = 1))) { it.jumpIndirection },
        Triple("junk", ObfuscationConfig(masterSeed = seed, junkCode = JunkCodeConfig())) { it.junkCode },
        Triple("clones", ObfuscationConfig(masterSeed = seed, blockCloning = CloneBlocksConfig())) { it.blockCloning },
        Triple("cff", ObfuscationConfig(masterSeed = seed, controlFlowFlattening = ControlFlowFlatteningConfig.FULL)) { it.controlFlowFlattening },
        Triple("registers", ObfuscationConfig(masterSeed = seed, registerObfuscation = RegisterObfuscationConfig())) { it.registerObfuscation },
        Triple("keys", ObfuscationConfig(masterSeed = seed, keyRenaming = KeyRenamingConfig())) { it.keyRenaming },
    )

    @Test
    fun `nothing enabled runs no pass at all`() {
        val chunk = chunk()
        val report = LuaObfuscator.obfuscate(chunk, ObfuscationConfig(masterSeed = seed))
        assertNull(report.stringEncryption)
        assertNull(report.constantEncryption)
        assertNull(report.keyRenaming)
        assertNull(report.jumpIndirection)
        assertNull(report.junkCode)
        assertNull(report.blockCloning)
        assertNull(report.controlFlowFlattening)
        assertNull(report.registerObfuscation)
        assertTrue(report.modules.isEmpty())
        // a no-op run still produces a loadable chunk
        LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
    }

    @Test
    fun `each feature can be enabled alone and every other pass stays off`() {
        for ((label, config, section) in cases()) {
            val chunk = chunk()
            val report = LuaObfuscator.obfuscate(chunk, config)

            assertNotNull(section(report), "$label: the enabled feature did not report")
            assertNull(report.stringEncryption, "$label: string encryption leaked in")
            assertNull(report.constantEncryption, "$label: constant encryption leaked in")
            assertNull(report.keyRenaming, "$label: key renaming leaked in")
            assertNull(report.jumpIndirection, "$label: jump indirection leaked in")
            assertNull(report.junkCode, "$label: junk code leaked in")
            assertNull(report.blockCloning, "$label: block cloning leaked in")
            if (label != "cff") assertNull(report.controlFlowFlattening, "$label: flattening leaked in")
            assertNull(report.registerObfuscation, "$label: register obfuscation leaked in")
            assertTrue(report.modules.isEmpty(), "$label: extension modules leaked in")

            // the single-feature output must stay valid bytecode
            LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
        }
    }
}
