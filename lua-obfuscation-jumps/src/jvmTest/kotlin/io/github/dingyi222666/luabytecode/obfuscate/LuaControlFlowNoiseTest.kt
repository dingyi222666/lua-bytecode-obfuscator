package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.ALL_FIXTURES
import io.github.dingyi222666.luabytecode.bytecode.FIXTURE_VERSIONS
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaOpcodes
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.analysis.basicBlockStarts
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTargets
import io.github.dingyi222666.luabytecode.bytecode.edit.indexOfStringConstant
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import io.github.dingyi222666.luabytecode.bytecode.loadFixture
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LuaControlFlowNoiseTest {

    @Test
    fun `structural invariants hold for every fixture`() {
        for (name in ALL_FIXTURES) {
            val chunk = loadFixture(name)
            val before = chunk.prototypes.sumOf { it.instructionCount }

            val report = LuaJumpIndirection.apply(
                chunk,
                JumpIndirectionConfig(depth = 2, junkPerHop = 2),
            )

            assertEquals(
                before + report.injectedInstructions,
                chunk.prototypes.sumOf { it.instructionCount },
                "$name: unexpected instruction count",
            )
            if (report.redirectedJumps > 0) {
                assertTrue(report.injectedInstructions > 0)
            }
            assertTargetsValid(name, chunk)
            LuaChunkLoader.load(LuaChunkWriter.dump(chunk))
        }
    }

    @Test
    fun `the control flow fixture is really trampolined on every version`() {
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("flow_$suffix.luac")
            val report = LuaJumpIndirection.apply(
                chunk,
                JumpIndirectionConfig(depth = 3, junkPerHop = 1),
            )
            assertTrue(report.redirectedJumps >= 3, "5.$version: flow fixture must have conditional jumps")
            assertEquals(report.redirectedJumps * 3, report.trampolines)
            assertTargetsValid("flow_$suffix.luac", chunk)
            LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
        }
    }

    @Test
    fun `loop jumps are only redirected when the version allows it`() {
        // 5.4 / 5.5 FORLOOP can only jump backwards, so it must never be redirected
        // to a block appended after the return.
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("flow_$suffix.luac")
            val before = chunk.prototypes.map { prototype ->
                prototype.jumpTargets().count { (pc, _) ->
                    prototype.code[pc].opcodeName in setOf("FORLOOP", "TFORLOOP", "FORPREP")
                }
            }
            LuaJumpIndirection.apply(chunk, JumpIndirectionConfig(depth = 1, redirectLoopJumps = true))
            val after = chunk.prototypes.map { prototype ->
                prototype.jumpTargets().keys.count { prototype.code[it].opcodeName == "JMP" }
            }
            if (version == io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion.LUA_5_3) {
                assertTrue(after.sum() > 0)
            }
            assertTrue(before.sum() > 0, "5.$version: the fixture should contain loop jumps")
            assertTargetsValid("flow_$suffix.luac", chunk)
        }
    }

    @Test
    fun `junk code guards branches of the control flow fixture`() {
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("flow_$suffix.luac")
            val before = chunk.prototypes.sumOf { it.instructionCount }

            val report = LuaJunkCode.apply(
                chunk,
                JunkCodeConfig(
                    junkPerBlock = 3,
                    guardedBranches = 4,
                    deadBlockSize = 5,
                    unreachableBlocks = 2,
                ),
            )

            assertTrue(report.guardedJumps > 0, "5.$version: nothing was guarded")
            assertTrue(report.deadBlocks >= report.guardedJumps)
            // `>=` and not `==`: the chunk secret (goal round 17) injects its own
            // prologue, which the junk report does not count.
            assertTrue(
                chunk.prototypes.sumOf { it.instructionCount } >= before + report.injectedInstructions,
                "5.${suffix}: junk report under-counts its own injections",
            )
            assertTargetsValid("flow_$suffix.luac", chunk)
            LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
        }
    }

    @Test
    fun `every pass composes with string encryption`() {
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("flow_$suffix.luac")

            LuaStringEncryptor.encrypt(chunk, StringEncryptionConfig(minLength = 6))
            val indirection = LuaJumpIndirection.apply(chunk, JumpIndirectionConfig(depth = 2, junkPerHop = 1))
            val junk = LuaJunkCode.apply(chunk, JunkCodeConfig(junkPerBlock = 2, guardedBranches = 2))

            assertTrue(indirection.redirectedJumps > 0, "5.$version: no jump redirected")
            assertTrue(junk.guardedJumps > 0, "5.$version: no branch guarded")
            assertTargetsValid("flow_$suffix.luac", chunk)
            LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
        }
    }

    @Test
    fun `rejects a code stream that could fall into the appended blocks`() {
        val chunk = loadFixture("flow_53.luac")
        val prototype = chunk.prototypes.first { it.jumpTargets().isNotEmpty() }
        prototype.code[prototype.code.size - 1] = LuaInstruction.abc(
            chunk.version,
            LuaOpcodes.opcodeOf(chunk.version, "MOVE")!!,
            0,
            0,
            0,
        )
        assertFailsWith<LuaBytecodeFormatException> { LuaJumpIndirection.apply(chunk) }
    }

    @Test
    fun `Collberg predicates and goto rings are emitted`() {
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("flow_$suffix.luac")
            val report = LuaJunkCode.apply(
                chunk,
                JunkCodeConfig(
                    junkPerBlock = 1,
                    guardedBranches = 3,
                    deadBlockSize = 3,
                    unreachableBlocks = 4,
                    deadCycleLength = 2,
                    predicateFamily = OpaquePredicate.NON_SQUARE,
                ),
            )
            assertTrue(report.guardedJumps > 0, "5.$version: nothing guarded")
            val prototype = chunk.prototypes.first { it.code.any { instruction -> instruction.opcodeName == "MUL" } }
            assertTrue(
                prototype.code.any { it.opcodeName == "MUL" },
                "5.$version: the nonlinear comparison must be emitted",
            )
            // ring of 2: an appended block that jumps forward then back again
            val targets = prototype.jumpTargets()
            assertTrue(targets.isNotEmpty())
            assertTargetsValid("flow_$suffix.luac", chunk)
            LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
        }
    }

    @Test
    fun `every predicate family composes with runtime seeds`() {
        for ((suffix, version) in FIXTURE_VERSIONS) {
            for (family in OpaquePredicate.entries) {
                for (seed in listOf(7L, 29L)) {
                    val chunk = loadFixture("flow_$suffix.luac")
                    LuaJunkCode.apply(
                        chunk,
                        JunkCodeConfig(
                            junkPerBlock = 1,
                            guardedBranches = 2,
                            deadBlockSize = 2,
                            unreachableBlocks = 2,
                            predicateFamily = family,
                            seed = seed,
                        ),
                    )
                    assertTargetsValid("flow_$suffix.luac", chunk)
                    LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
                }
            }
        }
    }

    @Test
    fun `runtime predicates no longer observe host libraries or fall back to constants`() {
        val chunk = loadFixture("flow_55.luac")
        val originals = chunk.prototypes.associateBy { System.identityHashCode(it) }
            .mapValues { it.value.code.size }
        val report = LuaJunkCode.apply(chunk, JunkCodeConfig(guardedBranches = 4))
        assertTrue(report.guardedJumps > 0)
        for (p in chunk.prototypes) {
            val added = p.code.drop(originals.getValue(System.identityHashCode(p)))
            assertTrue(added.none { it.opcodeName in setOf("CALL", "GETTABUP", "GETUPVAL") })
            if (p.numParams == 0) assertTrue(added.isEmpty())
            else if (added.isNotEmpty()) assertTrue(added.any { it.opcodeName == "TEST" && it.a == 0 })
        }
    }

    @Test
    fun `every predicate family and seed is structurally valid on every fixture`() {
        for (name in ALL_FIXTURES) {
            for (family in OpaquePredicate.entries) {
                for (seed in listOf(7L, 29L)) {
                    val chunk = loadFixture(name)
                    LuaJunkCode.apply(
                        chunk,
                        JunkCodeConfig(
                            junkPerBlock = 1,
                            guardedBranches = 3,
                            deadBlockSize = 2,
                            unreachableBlocks = 2,
                            deadCycleLength = 2,
                            predicateFamily = family,
                            seed = seed,
                        ),
                    )
                    assertTargetsValid(name, chunk)
                    LuaChunkLoader.load(LuaChunkWriter.dump(chunk))
                }
            }
        }
    }

    @Test
    fun `runtime seed setup never writes a register below the high water mark`() {
        var runtimeSetups = 0
        for (name in ALL_FIXTURES) {
            for (seed in listOf(7L, 29L)) {
                for (family in OpaquePredicate.entries) {
                    val chunk = loadFixture(name)
                    val highWater = IdentityHashMap<LuaPrototype, Int>()
                    val originalSize = IdentityHashMap<LuaPrototype, Int>()
                    // the chunk secret relocates the *original* first instruction into
                    // its prologue, and that instruction may legitimately write a low
                    // register, so exact copies of the original code are exempt
                    val originalRaw = IdentityHashMap<LuaPrototype, Set<Int>>()
                    for (prototype in chunk.prototypes) {
                        highWater[prototype] = prototype.maxStackSize
                        originalSize[prototype] = prototype.code.size
                        originalRaw[prototype] = prototype.code.map { it.raw }.toSet()
                    }
                    LuaJunkCode.apply(
                        chunk,
                        JunkCodeConfig(
                            junkPerBlock = 2,
                            guardedBranches = 2,
                            deadBlockSize = 2,
                            predicateFamily = family,
                            seed = seed,
                        ),
                    )
                    for (prototype in chunk.prototypes) {
                        val scratchStart = highWater.getValue(prototype)
                        for (pc in originalSize.getValue(prototype) until prototype.code.size) {
                            val instruction = prototype.code[pc]
                            if (isRuntimeSeedInstruction(instruction)) {
                                runtimeSetups++
                            }
                            val destination = destinationRegister(instruction) ?: continue
                            if (instruction.raw in originalRaw.getValue(prototype)) continue
                            assertTrue(
                                destination >= scratchStart,
                                "$name: $instruction at pc $pc writes register $destination, " +
                                    "which is below the high water mark $scratchStart",
                            )
                        }
                    }
                }
            }
        }
        assertTrue(runtimeSetups > 0, "no runtime seed setup was exercised")
    }

    @Test
    fun `removed low grade predicate names cannot be selected`() {
        for (name in listOf("SIMPLE_NIL", "XOR_SELF", "PARITY", "SQUARE_PARITY", "MBA_ADD",
            "MBA_XOR", "MBA_DE_MORGAN", "SHIFT", "MOD3_QUADRATIC", "SUM_SQUARES_MOD4")) {
            assertFailsWith<IllegalArgumentException> { OpaquePredicate.valueOf(name) }
        }
    }

    /** `true` for the `CALL` the runtime seeds emit and the constant seed does not. */
    private fun isRuntimeSeedInstruction(instruction: LuaInstruction): Boolean =
        instruction.opcodeName == "TEST"

    /** Register [LuaInstruction.a] writes, or `null` when the instruction writes no register. */
    private fun destinationRegister(instruction: LuaInstruction): Int? =
        when (instruction.opcodeName) {
            // `EQ` only compares (5.3 packs the skip flag into `A`) and `JMP` transfers control.
            "EQ", "TEST", "JMP", "FORLOOP", "FORPREP", "TFORLOOP" -> null
            else -> instruction.a
        }

    private fun assertTargetsValid(name: String, chunk: LuaChunk) {
        for (prototype in chunk.prototypes) {
            val starts = prototype.basicBlockStarts()
            for ((pc, target) in prototype.jumpTargets()) {
                assertTrue(target in 0..prototype.code.size, "$name: jump $pc -> $target out of range")
                assertTrue(target in starts, "$name: jump $pc -> $target is not a block start")
            }
        }
    }
}
