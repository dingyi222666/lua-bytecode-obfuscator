package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.vm.LuaVmObfuscation
import io.github.dingyi222666.luabytecode.vm.LuaVmPipeline
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pipeline contract test (Phase 6b): the sunk orchestrator must behave
 * identically on every target. It is deliberately file-system free (embedded
 * base64 fixture) and asset free (the string / constant passes are off — they
 * are the only passes that need the bundled decoder chunks, and those are
 * covered per host through the platform assemblers: classpath on the JVM,
 * base64 constants on js, embedded constants in the native CLI).
 */
class PipelineContractTest {

    private val version = LuaBytecodeVersion.LUA_5_3

    /** Pinned so the proof is reproducible on every platform (PRED-2). */
    private val seed = 20260917L

    private fun chunk() = LuaChunkLoader.load(Base64.decode(numericFor53Base64), version)

    @Test
    fun `the feature stack reports exactly the enabled passes and reloads`() {
        val chunk = chunk()
        val config = ObfuscationConfig(
            masterSeed = seed,
            jumpIndirection = JumpIndirectionConfig(depth = 1),
            junkCode = JunkCodeConfig(),
            blockCloning = CloneBlocksConfig(),
            registerObfuscation = RegisterObfuscationConfig(),
            keyRenaming = KeyRenamingConfig(),
        )
        val report = LuaObfuscator.obfuscate(chunk, config)

        assertNotNull(report.jumpIndirection)
        assertNotNull(report.junkCode)
        assertNotNull(report.blockCloning)
        assertNotNull(report.registerObfuscation)
        assertNotNull(report.keyRenaming)
        // the asset dependent passes stay off: the default platform seam has
        // no bundled chunks, and the test must not require an installed host
        assertNull(report.stringEncryption)
        assertNull(report.constantEncryption)
        assertNull(report.controlFlowFlattening)
        assertTrue(report.modules.isEmpty())

        val reloaded = LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
        assertEquals(version, reloaded.header.version)
    }

    @Test
    fun `nothing enabled runs no pass and the chunk stays loadable`() {
        val chunk = chunk()
        val report = LuaObfuscator.obfuscate(chunk, ObfuscationConfig(masterSeed = seed))
        assertNull(report.stringEncryption)
        assertNull(report.constantEncryption)
        assertNull(report.jumpIndirection)
        assertNull(report.junkCode)
        assertNull(report.blockCloning)
        assertNull(report.controlFlowFlattening)
        assertNull(report.registerObfuscation)
        assertNull(report.keyRenaming)
        assertTrue(report.modules.isEmpty())
        LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
    }

    @Test
    fun `the strict VM entry refuses an unstaged input on every platform`() {
        assertNull(
            LuaVmPipeline.obfuscated(
                chunk(),
                ObfRandom(1L),
                LuaVmObfuscation.Options(
                    inputObfuscation = null,
                    sourceCff = null,
                    requireSourceCff = true,
                ),
            ),
            "LuaVmPipeline.obfuscated must enforce the strict staging contract",
        )
    }
}
