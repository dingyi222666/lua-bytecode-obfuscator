package io.github.dingyi222666.luabytecode.vm

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract test for the roadmap step 5 acceptance criterion: "VM 报告必须区分
 * `source.cff`、`vm.lift`、`interpreter.source.cff` 和未完成的目标字节码 CFF"
 * (docs/roadmap-kmp-modules.md). The report structure must always carry the four
 * categories with honest statuses — and the target bytecode-level CFF must stay
 * `not_implemented` while the generated interpreter still ships as Lua source.
 */
class LuaVmCapabilityReportContractTest {

    private fun report(
        sourceCff: LuaVmObfuscation.CapabilityStatus = LuaVmObfuscation.CapabilityStatus.REFUSED,
        lift: LuaVmObfuscation.CapabilityStatus = LuaVmObfuscation.CapabilityStatus.APPLIED,
        interpreterSourceCff: LuaVmObfuscation.CapabilityStatus = LuaVmObfuscation.CapabilityStatus.APPLIED,
    ) = LuaVmObfuscation.CapabilityReport(
        inputObfuscation = LuaVmObfuscation.Capability(
            "input.obfuscation",
            LuaVmObfuscation.CapabilityStatus.REFUSED,
            "input obfuscation disabled",
        ),
        sourceCff = LuaVmObfuscation.Capability("source.cff", sourceCff, "source CFF disabled"),
        lift = LuaVmObfuscation.Capability("vm.lift", lift, "private ISA + encrypted program"),
        interpreterSourceCff = LuaVmObfuscation.Capability(
            "interpreter.source.cff",
            interpreterSourceCff,
            "source-level dispatcher applied without a parser",
        ),
    )

    @Test
    fun `the report always distinguishes the four contract categories`() {
        val categories = report().categories
        assertEquals(
            listOf("source.cff", "vm.lift", "interpreter.source.cff", "interpreter.bytecode.cff"),
            categories.map { it.id },
            "the four VM protection categories must be distinguishable, in contract order",
        )
        assertTrue(
            categories.zipWithNext().all { (a, b) -> a.id != b.id },
            "categories must stay distinct entries",
        )
    }

    @Test
    fun `the target bytecode cff category is not implemented and says so`() {
        val target = report().targetBytecodeCff
        assertEquals("interpreter.bytecode.cff", target.id)
        assertEquals(LuaVmObfuscation.CapabilityStatus.NOT_IMPLEMENTED, target.status)
        assertEquals("not_implemented", target.status.label)
        assertTrue(target.details.isNotBlank(), "the pending entry must explain itself")
    }

    @Test
    fun `staged layers can be applied while the product is still not complete`() {
        val current = report(
            sourceCff = LuaVmObfuscation.CapabilityStatus.APPLIED,
        )
        assertTrue(current.staged, "the three implemented layers applied")
        assertFalse(
            current.complete,
            "no VM product is complete while the target bytecode-level CFF is not implemented",
        )
    }

    @Test
    fun `complete stays false for every status combination reachable today`() {
        for (source in LuaVmObfuscation.CapabilityStatus.entries) {
            for (interpreter in LuaVmObfuscation.CapabilityStatus.entries) {
                val candidate = report(
                    sourceCff = source,
                    interpreterSourceCff = interpreter,
                )
                assertFalse(
                    candidate.complete,
                    "$source/$interpreter: complete must stay false until the target " +
                        "bytecode-level CFF ships",
                )
            }
        }
    }
}
