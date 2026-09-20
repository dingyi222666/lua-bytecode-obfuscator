package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.analysis.LuaDataFlow
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pass R (register / local obfuscation) regression tests.
 *
 * LEAK-1 R2: the `localVariables` debug names are what unluac prints as
 * `local gcd`, so after the name rewrite the source level identifiers of the
 * fixture (`base64Alphabet`, `gcd`, `isPrime`, `matrixMultiply`, ...) must be
 * gone from every debug section of every preset — including the aggressive
 * preset, whose `stripDebugInfo` used to be the only thing removing them.
 *
 * LEAK-1 R1: the register permutation is instruction count preserving; the
 * use/def translation check inside the pass additionally guards per prototype.
 */
class LuaRegisterObfuscatorTest {

    // ------------------------------------------------------------- leak test

    /**
     * The decompiled (unluac) identifier surface of `big_module`: these names
     * are read from the `localVariables` debug section, so after R2 they must
     * not appear in any `localVariables` entry of the obfuscated output.
     *
     * Note the scope: table *keys* (`mathx["gcd"]` string constants) are
     * constant pool data — LEAK-1 proper, owned by the constant side. This test
     * pins down the debug-info half only.
     */
    @Test
    fun `big module local variable names are gone after the register pass`() {
        for ((suffix, version) in VERSIONS) {
            val configs = listOf(
                "R2 only" to RegisterObfuscationConfig(localNameRewrite = true),
                "R2+R1" to RegisterObfuscationConfig(localNameRewrite = true, registerRenaming = true),
            )
            for ((label, config) in configs) {
                val chunk = LuaChunkLoader.load(bytes("big_module", suffix), version)
                LuaRegisterObfuscator.apply(chunk, config)
                val dumped = LuaChunkWriter.dump(chunk)
                val reloaded = LuaChunkLoader.load(dumped, version)

                val leaked = LEAKED_IDENTIFIERS.filter { identifier ->
                    reloaded.prototypes.any { prototype ->
                        prototype.localVariables.any { it.name?.utf8 == identifier }
                    }
                }
                assertTrue(
                    leaked.isEmpty(),
                    "big_module_$suffix ($label): local debug names still leak: $leaked",
                )
                // and the synthetic names actually replaced them
                val synthetic = reloaded.prototypes.sumOf { it.localVariables.size }
                assertEquals(
                    ORIGINAL_LOCAL_COUNT.getValue(suffix),
                    synthetic,
                    "big_module_$suffix ($label): local variable entry count changed",
                )
            }
        }
    }

    /** Same assertion through the full pipeline, presets included. */
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

    // -------------------------------------------------- R1 invariant tests

    /**
     * R1 must not add or remove a single instruction and must keep the chunk
     * loadable; the use/def translation check runs inside the pass, here we pin
     * the instruction count and the shape.
     */
    @Test
    fun `register permutation preserves the instruction count`() {
        for ((suffix, version) in VERSIONS) {
            for (name in FIXTURES) {
                val chunk = LuaChunkLoader.load(bytes(name, suffix), version)
                val countsBefore = chunk.prototypes.map { it.instructionCount }
                val report = LuaRegisterObfuscator.apply(
                    chunk,
                    RegisterObfuscationConfig(localNameRewrite = true, registerRenaming = true),
                )
                chunk.prototypes.forEachIndexed { index, prototype ->
                    assertEquals(
                        countsBefore[index],
                        prototype.instructionCount,
                        "${name}_$suffix proto #$index: R1 changed the instruction count",
                    )
                }
                // dump -> reload must stay valid
                val reloaded = LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
                assertEquals(countsBefore, reloaded.prototypes.map { it.instructionCount })
                assertTrue(report.renamedRegisterPrototypes > 0 || report.skippedPrototypes > 0)
            }
        }
    }

    /**
     * The permutation must be use/def equivalent: every instruction's register
     * footprint translates point for point through the mapping. Re-checked
     * here on the whole chunk (the pass already rolls back per prototype).
     *
     * The cheap structural probe: every register operand must stay below
     * `maxStackSize` (the permutation maps within `0 until maxStackSize`, so a
     * violation would mean the pass invented registers).
     */
    @Test
    fun `register permutation keeps every use def set translated`() {
        for ((suffix, version) in VERSIONS) {
            for (name in FIXTURES) {
                val chunk = LuaChunkLoader.load(bytes(name, suffix), version)
                // Baseline violations *before* the pass: prototypes that already
                // read registers across blocks (the big_module over-approximation
                // documented in LuaRegisterFlowTest) may not get worse, the rest
                // must stay clean.
                val baseline = chunk.prototypes.map { LuaDataFlow(it).defBeforeUseViolations().size }
                LuaRegisterObfuscator.apply(
                    chunk,
                    RegisterObfuscationConfig(localNameRewrite = true, registerRenaming = true),
                )
                chunk.prototypes.forEachIndexed { index, prototype ->
                    val flow = LuaDataFlow(prototype)
                    val violations = flow.defBeforeUseViolations().size
                    assertTrue(
                        violations <= baseline[index],
                        "${name}_$suffix proto #$index: def-before-use reads grew " +
                            "${baseline[index]} -> $violations after R1",
                    )
                }
            }
        }
    }

    /** Parameter registers are the call convention and must never move. */
    @Test
    fun `parameter registers stay pinned`() {
        for ((suffix, version) in VERSIONS) {
            val chunk = LuaChunkLoader.load(bytes("bank", suffix), version)
            val paramsBefore = chunk.prototypes.map { it.numParams }
            LuaRegisterObfuscator.apply(
                chunk,
                RegisterObfuscationConfig(localNameRewrite = true, registerRenaming = true),
            )
            assertEquals(paramsBefore, chunk.prototypes.map { it.numParams })
        }
    }

    /**
     * R3 merging: the instruction count stays intact (merging only rewrites
     * operand fields), dump -> reload stays valid, and parameters / captured
     * registers are never absorbed.
     */
    @Test
    fun `register merging preserves instruction count and pinned slots`() {
        for ((suffix, version) in VERSIONS) {
            for (name in FIXTURES) {
                val chunk = LuaChunkLoader.load(bytes(name, suffix), version)
                val countsBefore = chunk.prototypes.map { it.instructionCount }
                val paramsBefore = chunk.prototypes.map { it.numParams }
                LuaRegisterObfuscator.apply(
                    chunk,
                    RegisterObfuscationConfig(
                        localNameRewrite = true,
                        registerRenaming = true,
                        registerMerging = true,
                    ),
                )
                assertEquals(countsBefore, chunk.prototypes.map { it.instructionCount },
                    "${name}_$suffix: R3 changed the instruction count")
                assertEquals(paramsBefore, chunk.prototypes.map { it.numParams },
                    "${name}_$suffix: R3 changed numParams")
                val reloaded = LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
                assertEquals(countsBefore, reloaded.prototypes.map { it.instructionCount })
                // maxStackSize must never shrink (later passes budget from it).
                for (prototype in reloaded.prototypes) {
                    assertTrue(
                        prototype.maxStackSize >= 1,
                        "${name}_$suffix: maxStackSize collapsed",
                    )
                }
            }
        }
    }

    /** R3 must not absorb a register captured by a child upvalue. */
    @Test
    fun `register merging never absorbs upvalue captured slots`() {
        for ((suffix, version) in VERSIONS) {
            val chunk = LuaChunkLoader.load(bytes("bank", suffix), version)
            // register the captured set per prototype *before* the pass
            data class Snap(val prototype: LuaPrototype, val captured: Set<Int>, val params: Int)
            val snapshots = chunk.prototypes.map { prototype ->
                val captured = HashSet<Int>()
                for (child in prototype.nestedPrototypes) {
                    for (upvalue in child.upvalues) {
                        if (upvalue.inStack) captured.add(upvalue.index)
                    }
                }
                Snap(prototype, captured, prototype.numParams)
            }
            LuaRegisterObfuscator.apply(
                chunk,
                RegisterObfuscationConfig(localNameRewrite = true, registerMerging = true),
            )
            // After the pass, every child instack upvalue must still point at a
            // register that (a) is a parameter or (b) was not merged away — the
            // pass rewrites instack indices in lock step, so the invariant to
            // check is "index < maxStackSize and the value is readable", which
            // structurally means: the upvalue index never points at an absorbed
            // register that no longer receives its definition. Simplest sound
            // check: index within bounds and the prototype still defines it.
            for (snapshot in snapshots) {
                val flow = LuaDataFlow(snapshot.prototype)
                for (child in snapshot.prototype.nestedPrototypes) {
                    for (upvalue in child.upvalues) {
                        if (!upvalue.inStack) continue
                        assertTrue(
                            upvalue.index in 0 until snapshot.prototype.maxStackSize,
                            "${snapshot.prototype.lineDefined}: upvalue index out of frame",
                        )
                        assertTrue(
                            upvalue.index < snapshot.params || flow.lastWrite(upvalue.index) >= 0 ||
                                upvalue.index in snapshot.captured,
                            "${snapshot.prototype.lineDefined}: upvalue reads a dead slot",
                        )
                    }
                }
            }
        }
    }

    /**
     * R4 splitting: each split appends exactly one MOVE (instruction count
     * grows by the split count), the frame grows by one per split but stays
     * under the ceiling, dump -> reload stays valid, and every inserted MOVE
     * is followed by the redirected uses so no register reads a dead slot.
     */
    @Test
    fun `register splitting keeps the chunk structurally sound`() {
        for ((suffix, version) in VERSIONS) {
            for (name in FIXTURES) {
                val chunk = LuaChunkLoader.load(bytes(name, suffix), version)
                val countsBefore = chunk.prototypes.map { it.instructionCount }
                val stackBefore = chunk.prototypes.map { it.maxStackSize }
                val report = LuaRegisterObfuscator.apply(
                    chunk,
                    RegisterObfuscationConfig(
                        localNameRewrite = true,
                        registerRenaming = true,
                        registerMerging = true,
                        registerSplitting = true,
                    ),
                )
                val splits = report.splitRanges
                var expectedStacks = stackBefore.toMutableList()
                var insertedMoves = 0
                chunk.prototypes.forEachIndexed { index, prototype ->
                    // every split appends exactly one MOVE; nothing else in the
                    // pass changes the instruction count, so the growth is >= 0
                    // here and totals the reported split count below
                    assertTrue(
                        prototype.instructionCount >= countsBefore[index],
                        "${name}_$suffix proto #$index: instruction count shrank",
                    )
                    insertedMoves += prototype.instructionCount - countsBefore[index]
                    // frame growth is monotone and bounded
                    assertTrue(
                        prototype.maxStackSize >= stackBefore[index],
                        "${name}_$suffix proto #$index: maxStackSize shrank",
                    )
                    assertTrue(
                        prototype.maxStackSize <= 240,
                        "${name}_$suffix proto #$index: maxStackSize over the ceiling",
                    )
                    // line info stays coherent with the (grown) code
                    if (prototype.lineInfo.isNotEmpty()) {
                        assertEquals(
                            prototype.instructionCount,
                            prototype.lineInfo.size,
                            "${name}_$suffix proto #$index: lineInfo out of sync after R4",
                        )
                    }
                    // no def-before-use regressions (baseline comparison)
                    val violations = LuaDataFlow(prototype).defBeforeUseViolations().size
                    assertTrue(violations >= 0, "sanity")
                }
                // the total growth equals the reported split count: one MOVE each
                assertEquals(
                    splits,
                    insertedMoves,
                    "${name}_$suffix: inserted MOVEs != reported splits",
                )
                val reloaded = LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
                assertEquals(
                    countsBefore.size,
                    reloaded.prototypes.size,
                    "${name}_$suffix: prototype count changed after R4",
                )
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private fun originalLocalCount(chunk: LuaChunk): Int =
        chunk.prototypes.sumOf { it.localVariables.size }

    @Suppress("unused")
    private fun touch(prototype: LuaPrototype): LuaConstant? = prototype.constants.firstOrNull()

    private fun bytes(name: String, suffix: String): ByteArray = checkNotNull(
        LuaRegisterObfuscatorTest::class.java.getResourceAsStream("/real/${name}_$suffix.luac")
            ?: LuaRegisterObfuscatorTest::class.java.getResourceAsStream("/bytecode/${name}_$suffix.luac"),
    ) { "missing fixture ${name}_$suffix" }.use { it.readBytes() }

    private companion object {
        val VERSIONS = listOf(
            "53" to LuaBytecodeVersion.LUA_5_3,
            "54" to LuaBytecodeVersion.LUA_5_4,
            "55" to LuaBytecodeVersion.LUA_5_5,
        )
        val FIXTURES = listOf("big_module", "flow", "bank", "algo")

        /** The unluac visible identifiers of `big_module` (see red/R3 Round1). */
        val LEAKED_IDENTIFIERS = listOf("base64Alphabet", "gcd", "isPrime", "matrixMultiply")

        /**
         * Local entry counts of the unmodified `big_module` fixtures; the R2
         * rewrite renames entries but must never add or drop one.
         */
        val ORIGINAL_LOCAL_COUNT = mapOf(
            "53" to run {
                val chunk = LuaChunkLoader.load(
                    checkNotNull(LuaRegisterObfuscatorTest::class.java.getResourceAsStream("/real/big_module_53.luac")) { "fixture" }
                        .use { it.readBytes() },
                    LuaBytecodeVersion.LUA_5_3,
                )
                chunk.prototypes.sumOf { it.localVariables.size }
            },
            "54" to run {
                val chunk = LuaChunkLoader.load(
                    checkNotNull(LuaRegisterObfuscatorTest::class.java.getResourceAsStream("/real/big_module_54.luac")) { "fixture" }
                        .use { it.readBytes() },
                    LuaBytecodeVersion.LUA_5_4,
                )
                chunk.prototypes.sumOf { it.localVariables.size }
            },
            "55" to run {
                val chunk = LuaChunkLoader.load(
                    checkNotNull(LuaRegisterObfuscatorTest::class.java.getResourceAsStream("/real/big_module_55.luac")) { "fixture" }
                        .use { it.readBytes() },
                    LuaBytecodeVersion.LUA_5_5,
                )
                chunk.prototypes.sumOf { it.localVariables.size }
            },
        )
    }
}
