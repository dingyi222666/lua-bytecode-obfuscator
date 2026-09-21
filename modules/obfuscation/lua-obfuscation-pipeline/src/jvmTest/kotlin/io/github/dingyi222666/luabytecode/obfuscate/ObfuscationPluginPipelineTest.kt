// End-to-end behaviour of the plugin-driven pipeline: registration order can
// never change the output bytes, an unregistered pass stays off even when its
// config section is set, a full stack reports every section, and the
// cross-pass artifact slots (strings -> cff stub bookkeeping, cff -> constants
// flattener records) keep the coupled passes working through the context.
package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ObfuscationPluginPipelineTest {

    private val version = LuaBytecodeVersion.LUA_5_4

    /** Pinned so every comparison in here is reproducible (PRED-2 precedence). */
    private val seed = 20260917L

    private fun chunk() = LuaChunkLoader.load(
        checkNotNull(ObfuscationPluginPipelineTest::class.java.getResourceAsStream("/bytecode/flow_54.luac")) {
            "missing fixture /bytecode/flow_54.luac"
        }.use { it.readBytes() },
        version,
    )

    private fun registry(order: List<ObfuscationPassPlugin>) = ObfuscationRegistry().registerAll(order)

    @Test
    fun `registration order never changes the obfuscated bytes`() {
        val builtIn = DefaultObfuscationPasses.all()
        val config = ObfuscationConfig(
            masterSeed = seed,
            stringEncryption = StringEncryptionConfig(minLength = 6),
            jumpIndirection = JumpIndirectionConfig(depth = 1, junkPerHop = 1),
            junkCode = JunkCodeConfig(
                junkPerBlock = 1, guardedBranches = 2, deadBlockSize = 3, hotSiteRatio = 0.0,
            ),
            registerObfuscation = RegisterObfuscationConfig(),
        )
        var reference: ByteArray? = null
        for (permutation in listOf(builtIn, builtIn.reversed(), builtIn.shuffled(Random(3)))) {
            val chunk = chunk()
            LuaObfuscator.obfuscate(chunk, config, registry(permutation))
            val dumped = LuaChunkWriter.dump(chunk)
            val previous = reference
            if (previous == null) {
                reference = dumped
            } else {
                assertTrue(previous.contentEquals(dumped), "permuted registration changed the output")
            }
        }
    }

    @Test
    fun `an unregistered pass is disabled even when its config section is set`() {
        // A host assembling only a prefix of the built-in chain (jumps ->
        // strings -> registers): the junk section is set, but the pass was
        // never registered, so it must not run and must not report.
        val all = DefaultObfuscationPasses.all()
        val prefix = registry(listOf("jumps", "strings", "registers").map { id ->
            checkNotNull(all.firstOrNull { it.id == id })
        })
        val chunk = chunk()
        val report = LuaObfuscator.obfuscate(chunk, ObfuscationConfig(
            masterSeed = seed,
            stringEncryption = StringEncryptionConfig(minLength = 6),
            jumpIndirection = JumpIndirectionConfig(depth = 1),
            junkCode = JunkCodeConfig(),
            registerObfuscation = RegisterObfuscationConfig(),
        ), prefix)
        assertNull(report.junkCode, "an unregistered pass must not run")
        assertNotNull(report.stringEncryption)
        assertNotNull(report.jumpIndirection)
        assertNotNull(report.registerObfuscation)
        assertTrue(report.modules.isEmpty())
        LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
    }

    @Test
    fun `excluding a leaf pass keeps its config switch inert`() {
        val withoutStrip = registry(DefaultObfuscationPasses.all().filter { it.id != "debug-strip" })
        val chunk = chunk()
        val report = LuaObfuscator.obfuscate(chunk, ObfuscationConfig(
            masterSeed = seed,
            stripDebugInfo = true,
        ), withoutStrip)
        assertFalse(report.strippedDebugInfo, "unregistered debug-strip must not strip")
        assertNotNull(report.tailReturn, "the always-on repair still runs")
        LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
    }

    @Test
    fun `removing a mid-chain pass surfaces its dangling dependents`() {
        // Sharp edge, on purpose: constraint references are validated against
        // the registered set (typo protection), so a partial assembly must
        // keep the graph consistent - dropping a mid-chain pass without its
        // dependents is an error, not a silent reorder.
        val broken = registry(DefaultObfuscationPasses.all().filter { it.id != "junk" })
        val exception = assertFailsWith<IllegalArgumentException> { broken.resolve() }
        assertTrue(exception.message!!.contains("unknown pass id 'junk'"), exception.message)
    }

    @Test
    fun `a full stack reports every pass section`() {
        val chunk = chunk()
        val report = LuaObfuscator.obfuscate(chunk, ObfuscationConfig(
            masterSeed = seed,
            stringEncryption = StringEncryptionConfig(),
            constantEncryption = ConstantEncryptionConfig(),
            jumpIndirection = JumpIndirectionConfig(depth = 1),
            junkCode = JunkCodeConfig(guardedBranches = 2, hotSiteRatio = 0.0),
            blockCloning = CloneBlocksConfig(),
            controlFlowFlattening = ControlFlowFlatteningConfig.FULL,
            keyRenaming = KeyRenamingConfig(),
            registerObfuscation = RegisterObfuscationConfig(),
            stripDebugInfo = true,
        ))
        // the nine pass report sections plus the strip flag, exactly like the
        // pre-plugin orchestrator produced them
        assertNotNull(report.stringEncryption)
        assertNotNull(report.constantEncryption)
        assertNotNull(report.jumpIndirection)
        assertNotNull(report.junkCode)
        assertNotNull(report.blockCloning)
        assertNotNull(report.controlFlowFlattening)
        assertNotNull(report.keyRenaming)
        assertNotNull(report.registerObfuscation)
        assertNotNull(report.tailReturn)
        assertTrue(report.strippedDebugInfo)
        assertTrue(report.modules.isEmpty())
        // the full-stack output must stay valid bytecode
        LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
    }

    @Test
    fun `cross-pass artifacts flow through the context slots`() {
        // strings -> cff: the flattener consumes the stub bookkeeping from the
        // report slots; constants reads the flattener records. None of the
        // involved plugins imports another, so the slots are the only path.
        val configs = listOf(
            ObfuscationConfig(
                masterSeed = seed,
                stringEncryption = StringEncryptionConfig(),
                controlFlowFlattening = ControlFlowFlatteningConfig.FULL,
            ),
            ObfuscationConfig(
                masterSeed = seed,
                stringEncryption = StringEncryptionConfig(),
                constantEncryption = ConstantEncryptionConfig(),
                controlFlowFlattening = ControlFlowFlatteningConfig.FULL,
            ),
        )
        for (config in configs) {
            val chunk = chunk()
            val report = LuaObfuscator.obfuscate(chunk, config)
            assertNotNull(report.stringEncryption, "strings must report")
            assertNotNull(report.controlFlowFlattening, "cff must report")
            if (config.constantEncryption != null) {
                assertNotNull(report.constantEncryption, "constants must report")
            }
            LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
        }
    }
}
