package io.github.dingyi222666.luabytecode.bytecode.support

import io.github.dingyi222666.luabytecode.bytecode.FIXTURE_VERSIONS
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.analysis.LuaDataFlow
import io.github.dingyi222666.luabytecode.bytecode.loadFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [LuaRegisterAnalysis] — the safe-state-slot primitive offered to
 * B1's dynamic-range flattening fix (Round 2).
 */
class LuaRegisterAnalysisTest {

    @Test
    fun `dynamic floor matches the inline B1 computation on every fixture`() {
        for (name in ALL_FIXTURES) {
            val chunk = loadFixture(name)
            for (prototype in chunk.prototypes) {
                val safety = LuaRegisterAnalysis.of(prototype)
                // reference: exactly the LuaControlFlowFlattening inline filter
                val dynamicPcs = prototype.code.indices.filter { pc ->
                    val i = prototype.code[pc]
                    i.opcodeName == "VARARG" ||
                        (i.opcodeName == "SETLIST" && i.b == 0) ||
                        (i.opcodeName in setOf("CALL", "TAILCALL") && (i.b == 0 || i.c == 0)) ||
                        (i.opcodeName == "RETURN" && i.b == 0)
                }
                val expectedFloor = dynamicPcs.minOfOrNull { pc ->
                    val i = prototype.code[pc]
                    if (i.opcodeName == "SETLIST") i.a + 1 else i.a
                } ?: prototype.maxStackSize
                assertEquals(expectedFloor, safety.dynamicFloor, "$name floor")
                assertEquals(dynamicPcs, safety.dynamicPcs, "$name dynamic pcs")
            }
        }
    }

    @Test
    fun `safe slots are below the floor, never read, never parameters`() {
        for (name in ALL_FIXTURES) {
            val chunk = loadFixture(name)
            for (prototype in chunk.prototypes) {
                val safety = LuaRegisterAnalysis.of(prototype)
                val flow = LuaDataFlow(prototype)
                for (slot in safety.safeStateSlots()) {
                    assertTrue(slot < safety.dynamicFloor, "$name slot $slot >= floor ${safety.dynamicFloor}")
                    assertTrue(slot >= prototype.numParams, "$name slot $slot is a parameter")
                    assertTrue(slot !in safety.globallyRead, "$name slot $slot is read somewhere")
                    assertTrue(slot < prototype.maxStackSize, "$name slot $slot above stack")
                }
                // slots must also be unread *after any single pc* — a slot read
                // nowhere can still be clobbered by a dynamic write above the
                // floor, which the floor test already covers.
                if (safety.dynamicPcs.isNotEmpty()) {
                    assertTrue(
                        safety.dynamicFloor <= prototype.maxStackSize,
                        "$name floor exceeds maxStackSize",
                    )
                }
                // consistency: a register in safeStateSlots is never in the reads of any useDef
                for (pc in 0 until flow.size) {
                    val reads = flow.useDef(pc).uses
                    for (slot in safety.safeStateSlots()) {
                        assertTrue(slot !in reads, "$name slot $slot read at pc=$pc")
                    }
                }
            }
        }
    }

    @Test
    fun `a prototype with no dynamic range has floor equal to maxStackSize and no restricted candidates`() {
        // features hello main on 5.4 has only plain arithmetic; find any prototype
        // without dynamic-range ops and confirm the floor degenerates.
        var checked = 0
        for ((_, version) in FIXTURE_VERSIONS) {
            val fixture = when (version) {
                LuaBytecodeVersion.LUA_5_3 -> "hello_53.luac"
                LuaBytecodeVersion.LUA_5_4 -> "hello_54.luac"
                else -> "hello_55.luac"
            }
            val chunk = loadFixture(fixture)
            for (prototype in chunk.prototypes) {
                val safety = LuaRegisterAnalysis.of(prototype)
                if (safety.dynamicPcs.isEmpty()) {
                    checked++
                    assertEquals(prototype.maxStackSize, safety.dynamicFloor)
                }
            }
        }
        assertTrue(checked > 0, "expected at least one prototype without dynamic range ops")
    }

    @Test
    fun `direct constant loads are constant reachable and parameters never are`() {
        val chunk = loadFixture("features_54.luac")
        for (prototype in chunk.prototypes) {
            val safety = LuaRegisterAnalysis.of(prototype)
            for (register in 0 until prototype.numParams) {
                assertTrue(register !in safety.constantReachable, "parameter R$register flagged constant reachable")
            }
        }
        // features main does LOADI 0 0 (pc1): R0's last writer is a direct load -> reachable
        val main = chunk.mainPrototype
        val safety = LuaRegisterAnalysis.of(main)
        assertTrue(0 in safety.constantReachable, "R0 last written by LOADI should be constant reachable")
    }

    @Test
    fun `dynamic rewrite invalidates constant reachability`() {
        // a register last written by CALL/MOVE-from-call is not constant reachable
        val chunk = loadFixture("flow_54.luac")
        for (prototype in chunk.prototypes) {
            val safety = LuaRegisterAnalysis.of(prototype)
            for (register in safety.constantReachable) {
                val lastWrite = LuaDataFlow(prototype).lastWrite(register)
                assertTrue(lastWrite >= 0, "R$register flagged reachable but never written")
                val writer = prototype.code[lastWrite].opcodeName
                assertTrue(
                    writer in setOf(
                        "LOADK", "LOADKX", "LOADI", "LOADF", "LOADFALSE", "LOADTRUE", "LOADNIL", "LOADBOOL",
                        "ADDI", "ADDK", "SUBI", "SUBK", "MULK", "MULI", "MODI", "MODK",
                        "BANDI", "BANDK", "BORI", "BORK", "BXORI", "BXORK", "SHLI", "SHRI", "SHLK", "SHRK",
                        "GETI", "SELF",
                    ),
                    "R$register reachable via last writer $writer at pc=$lastWrite",
                )
            }
        }
    }
}

private val ALL_FIXTURES = listOf(
    "hello_53.luac", "hello_54.luac", "hello_55.luac",
    "features_53.luac", "features_54.luac", "features_55.luac",
    "flow_53.luac", "flow_54.luac", "flow_55.luac",
    "dedup_53.luac", "dedup_54.luac", "dedup_55.luac",
)
