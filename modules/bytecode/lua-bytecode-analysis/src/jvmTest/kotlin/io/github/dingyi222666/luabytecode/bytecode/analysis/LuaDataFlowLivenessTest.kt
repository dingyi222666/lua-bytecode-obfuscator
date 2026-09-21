package io.github.dingyi222666.luabytecode.bytecode.analysis

import io.github.dingyi222666.luabytecode.bytecode.ALL_FIXTURES
import io.github.dingyi222666.luabytecode.bytecode.loadFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the B6/Round1 liveness additions to [LuaDataFlow]
 * (`liveBefore` / `readsAt` / `writesAt` / `useDefAll` / `lastRead` /
 * `lastWrite`) — the interface B4 register renaming consumes.
 */
class LuaDataFlowLivenessTest {

    @Test
    fun `liveBefore contains the uses of its own instruction and matches liveIn at block entry`() {
        val chunk = loadFixture("features_54.luac")
        for (prototype in chunk.prototypes) {
            val flow = LuaDataFlow(prototype)
            for (pc in 0 until flow.size) {
                val uses = flow.useDef(pc).uses
                val before = flow.liveBefore(pc)
                // (a) renaming contract: everything the instruction reads must be
                // live before it — otherwise a renamer could clobber an operand.
                assertTrue(
                    before.containsAll(uses),
                    "${prototype.source}: uses $uses of pc=$pc not live before it",
                )
                // (b) at a block start liveBefore degenerates to the block's liveIn
                val index = flow.blockOf[pc]
                if (index >= 0 && flow.ranges[index].first == pc) {
                    assertTrue(
                        before.containsAll(flow.liveIn[index]),
                        "${prototype.source}: liveBefore($pc) misses liveIn of block $index",
                    )
                }
            }
        }
    }

    @Test
    fun `liveBefore at a block start only contains registers reachable predecessors or params provide`() {
        val chunk = loadFixture("flow_54.luac")
        for (prototype in chunk.prototypes) {
            val flow = LuaDataFlow(prototype)
            for ((index, range) in flow.ranges.withIndex()) {
                if (!flow.reachable[index]) continue
                val fromPreds = flow.predecessors[index]
                    .filter { flow.reachable[it] }
                    .flatMap { flow.liveOut[it] }
                    .toSet()
                // registers live-in at block entry must be parameters or provided
                // by a predecessor (or the block is the entry, index 0, where the
                // analysis seeds liveness from the whole-chunk convention).
                for (register in flow.liveIn[index]) {
                    val fromParams = register < prototype.numParams
                    val isEntry = index == 0
                    assertTrue(
                        fromParams || isEntry || register in fromPreds,
                        "${prototype.source}: R$register live-in at block $index but no predecessor provides it",
                    )
                }
            }
        }
    }

    @Test
    fun `readsAt and writesAt agree with useDef`() {
        val chunk = loadFixture("dedup_54.luac")
        for (prototype in chunk.prototypes) {
            val flow = LuaDataFlow(prototype)
            for (pc in 0 until flow.size) {
                val useDef = flow.useDef(pc)
                assertEquals(useDef.uses, flow.readsAt(pc), "readsAt($pc)")
                assertEquals(useDef.defs, flow.writesAt(pc), "writesAt($pc)")
            }
            // useDefAll is the vectorised form
            assertEquals((0 until flow.size).map { flow.useDef(it) }, flow.useDefAll())
        }
    }

    @Test
    fun `lastRead and lastWrite are the real extremes over reachable code`() {
        for (name in ALL_FIXTURES) {
            val chunk = loadFixture(name)
            for (prototype in chunk.prototypes) {
                val flow = LuaDataFlow(prototype)
                val maxStack = prototype.maxStackSize
                if (maxStack == 0) continue
                for (register in 0 until maxStack) {
                    // reference computed over *reachable* pcs only (dead code never
                    // extends the range of a register — that is the documented contract)
                    val reads = (0 until flow.size).filter {
                        flow.reachable[flow.blockOf[it]] && register in flow.useDef(it).uses
                    }
                    val writes = (0 until flow.size).filter {
                        flow.reachable[flow.blockOf[it]] && register in flow.useDef(it).defs
                    }
                    assertEquals(reads.lastOrNull() ?: -1, flow.lastRead(register), "$name R$register lastRead")
                    assertEquals(writes.lastOrNull() ?: -1, flow.lastWrite(register), "$name R$register lastWrite")
                }
            }
        }
    }

    @Test
    fun `a register in deadAfter is safe to clobber - sanity for the renaming contract`() {
        // property: clobbering registers in deadAfter(pc) cannot change liveness
        // of anything downstream (that is the definition); here we only check the
        // cheap invariant that deadAfter is the complement of liveAfter.
        val chunk = loadFixture("big_54.luac")
        for (prototype in chunk.prototypes) {
            val flow = LuaDataFlow(prototype)
            val universe = (0 until prototype.maxStackSize).toSet()
            for (pc in 0 until flow.size) {
                assertEquals(universe - flow.liveAfter(pc), flow.deadAfter(pc), "deadAfter($pc)")
            }
        }
    }

    @Test
    fun `liveAfter at the last pc of a reachable block equals liveOut`() {
        val chunk = loadFixture("hello_55.luac")
        for (prototype in chunk.prototypes) {
            val flow = LuaDataFlow(prototype)
            for ((index, range) in flow.ranges.withIndex()) {
                val last = range.last
                if (last < 0 || !flow.reachable[index]) continue
                // liveAfter(last) walks past the block end, so it must equal liveOut[index]
                assertEquals(flow.liveOut[index], flow.liveAfter(last), "block $index last pc")
            }
        }
    }
}
