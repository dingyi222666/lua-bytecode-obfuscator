package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.obfuscate.ObfuscationConfig
import io.github.dingyi222666.luabytecode.platform.ObfRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Orchestrator-side wiring proof for roadmap step 5: the root module binds the
 * real pipeline executors to the :lua-vm stage interfaces (LuaVmPipeline), and
 * the resulting report still distinguishes the four contract categories with
 * honest values.
 */
class LuaVmPipelineTest {

    private fun chunk() = LuaChunkLoader.load(
        checkNotNull(LuaVmPipelineTest::class.java.getResourceAsStream("/real/ctor/c01_numericfor_53.luac")) {
            "missing fixture /real/ctor/c01_numericfor_53.luac"
        }.use { it.readBytes() },
        LuaBytecodeVersion.LUA_5_3,
    )

    @Test
    fun `the real input obfuscation stage reports applied and the four categories stay distinct`() {
        val emission = LuaVmObfuscation.emitModuleDetailed(
            chunk(),
            ObfRandom(3L),
            LuaVmObfuscation.Options(
                seed = 3L,
                // all passes off: the stage exercises the real LuaObfuscator wiring
                // (SeedPolicy stamping included) without changing the input
                inputObfuscation = LuaVmPipeline.inputObfuscation(ObfuscationConfig(masterSeed = 3L)),
            ),
        )
        assertNotNull(emission, "c01_numericfor must survive the pipeline and stay in the VM subset")
        val report = emission.report
        assertEquals(
            listOf("source.cff", "vm.lift", "interpreter.source.cff", "interpreter.bytecode.cff"),
            report.categories.map { it.id },
        )
        assertEquals(LuaVmObfuscation.CapabilityStatus.APPLIED, report.inputObfuscation.status)
        // the run had no CFF, so source.cff must not pretend otherwise
        assertEquals(LuaVmObfuscation.CapabilityStatus.REFUSED, report.sourceCff.status)
        assertEquals(LuaVmObfuscation.CapabilityStatus.APPLIED, report.lift.status)
        assertEquals(LuaVmObfuscation.CapabilityStatus.APPLIED, report.interpreterSourceCff.status)
        assertEquals(LuaVmObfuscation.CapabilityStatus.NOT_IMPLEMENTED, report.targetBytecodeCff.status)
        assertFalse(report.complete, "target bytecode-level CFF is not implemented")
    }

    @Test
    fun `the strict entry refuses an unstaged input when requireSourceCff is set`() {
        assertNull(
            LuaVmPipeline.obfuscated(
                chunk(),
                ObfRandom(1L),
                LuaVmObfuscation.Options(requireSourceCff = true),
            ),
            "LuaVmPipeline.obfuscated must enforce the strict staging contract",
        )
    }
}
