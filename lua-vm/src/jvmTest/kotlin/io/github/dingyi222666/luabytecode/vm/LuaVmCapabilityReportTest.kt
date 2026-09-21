package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.obfuscate.ControlFlowFlatteningReport
import io.github.dingyi222666.luabytecode.obfuscate.ObfuscationReport
import io.github.dingyi222666.luabytecode.platform.ObfRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end check of the roadmap step 5 report contract on a real emission:
 * `source.cff` / `vm.lift` / `interpreter.source.cff` / target bytecode-level CFF
 * must be four distinguishable entries with honest values (a `not_implemented`
 * layer stays `not_implemented`, a stage that did not run is `refused`).
 */
class LuaVmCapabilityReportTest {

    @Test
    fun `a default emission reports staging honestly and keeps the target bytecode cff not implemented`() {
        val chunk = loadVmFixture("c01_numericfor_53.luac", LuaBytecodeVersion.LUA_5_3)
        val emission = LuaVmObfuscation.emitModuleDetailed(
            chunk,
            ObfRandom(11L),
            LuaVmObfuscation.Options(seed = 11L),
        )
        assertNotNull(emission, "c01_numericfor must be inside the VM subset")
        val report = emission.report

        assertEquals(
            listOf("source.cff", "vm.lift", "interpreter.source.cff", "interpreter.bytecode.cff"),
            report.categories.map { it.id },
        )
        assertTrue(report.categories.all { it.details.isNotBlank() }, "every category explains itself")

        // default options stage nothing on the input: the report must say so
        assertEquals(LuaVmObfuscation.CapabilityStatus.REFUSED, report.inputObfuscation.status)
        assertEquals(LuaVmObfuscation.CapabilityStatus.REFUSED, report.sourceCff.status)
        assertEquals(LuaVmObfuscation.CapabilityStatus.APPLIED, report.lift.status)
        assertEquals(LuaVmObfuscation.CapabilityStatus.APPLIED, report.interpreterSourceCff.status)

        // honesty invariant: the generated interpreter has not been compiled to target
        // bytecode, so that category is not_implemented and the product is not complete
        assertEquals(LuaVmObfuscation.CapabilityStatus.NOT_IMPLEMENTED, report.targetBytecodeCff.status)
        assertFalse(report.complete, "complete must stay false while target bytecode CFF is pending")
    }

    @Test
    fun `stage results drive the input and source cff entries`() {
        val chunk = loadVmFixture("c01_numericfor_53.luac", LuaBytecodeVersion.LUA_5_3)
        val cffReport = ControlFlowFlatteningReport(
            flattenedPrototypes = 3,
            skippedPrototypes = 0,
            blocks = 7,
            guards = 0,
            injectedInstructions = 21,
        )
        val emission = LuaVmObfuscation.emitModuleDetailed(
            chunk,
            ObfRandom(5L),
            LuaVmObfuscation.Options(
                seed = 5L,
                // :lua-vm depends on no feature module: a record-shaped stage stands in
                // for the host-wired pipeline and the entries follow its actual report
                inputObfuscation = LuaVmObfuscation.InputObfuscationStage { _, _ ->
                    ObfuscationReport(controlFlowFlattening = cffReport)
                },
                sourceCff = LuaVmObfuscation.SourceCffStage { cffReport },
                requireSourceCff = true,
            ),
        )
        assertNotNull(emission)
        val report = emission.report
        assertEquals(LuaVmObfuscation.CapabilityStatus.APPLIED, report.inputObfuscation.status)
        assertEquals(LuaVmObfuscation.CapabilityStatus.APPLIED, report.sourceCff.status)
        assertNotNull(report.cffReport)
        assertEquals(LuaVmObfuscation.CapabilityStatus.APPLIED, report.lift.status)
        assertEquals(LuaVmObfuscation.CapabilityStatus.APPLIED, report.interpreterSourceCff.status)
        assertTrue(report.staged)
        assertEquals(LuaVmObfuscation.CapabilityStatus.NOT_IMPLEMENTED, report.targetBytecodeCff.status)
        assertFalse(report.complete)
    }

    @Test
    fun `a source cff stage that flattens nothing is reported as refused`() {
        val chunk = loadVmFixture("c01_numericfor_53.luac", LuaBytecodeVersion.LUA_5_3)
        val emission = LuaVmObfuscation.emitModuleDetailed(
            chunk,
            ObfRandom(6L),
            LuaVmObfuscation.Options(
                seed = 6L,
                sourceCff = LuaVmObfuscation.SourceCffStage {
                    ControlFlowFlatteningReport(0, 0, 0, 0, 0)
                },
            ),
        )
        assertNotNull(emission)
        assertEquals(LuaVmObfuscation.CapabilityStatus.REFUSED, emission.report.sourceCff.status)
    }

    @Test
    fun `requireSourceCff refuses when neither a stage nor a pipeline cff is present`() {
        val chunk = loadVmFixture("c01_numericfor_53.luac", LuaBytecodeVersion.LUA_5_3)
        assertNull(
            LuaVmObfuscation.emitModuleDetailed(
                chunk,
                ObfRandom(7L),
                LuaVmObfuscation.Options(seed = 7L, requireSourceCff = true),
            ),
            "the strict contract must refuse an unstaged input instead of lifting it silently",
        )
    }
}
