package io.github.dingyi222666.luabytecode.obfuscate
import io.github.dingyi222666.luabytecode.platform.Platform
import io.github.dingyi222666.luabytecode.platform.IdentityMap
import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaInstructionFactory

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaOpcodes
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.LuaString
import io.github.dingyi222666.luabytecode.bytecode.LuaUpvalue
import io.github.dingyi222666.luabytecode.bytecode.analysis.isUnconditionalJump
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTarget
import io.github.dingyi222666.luabytecode.bytecode.analysis.relocated
import io.github.dingyi222666.luabytecode.bytecode.analysis.retargeted
import io.github.dingyi222666.luabytecode.bytecode.analysis.skipsNextInstruction
import io.github.dingyi222666.luabytecode.bytecode.analysis.setAbsoluteLines
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTo
import io.github.dingyi222666.luabytecode.bytecode.edit.internConstant
import kotlin.math.roundToInt

/**
 * Control flow noise passes: jump indirection and junk (花) code.
 *
 * Both passes work on the *appended block* principle used by
 * [LuaStringEncryptor]: nothing is inserted in the middle of the original code,
 * a jump is only re-pointed at a block appended after the final `RETURN`. That
 * keeps every original distance valid, keeps conditional-skip pairing intact and
 * makes each pass individually testable and composable.
 */

// ---------------------------------------------------------------- jump hops

/** Configuration of [LuaJumpIndirection]. */
data class JumpIndirectionConfig(
    /** Number of trampoline hops every jump is routed through. */
    val depth: Int = 1,

    /** Two executable routes per hop, selected from an initialized parameter. */
    val runtimeRoutes: Boolean = false,

    /** Unreachable, cross-linked control-flow islands (valid Lua instructions). */
    val decoyBlocks: Int = 0,

    /** Harmless instructions emitted before each hop (junk on the live path). */
    val junkPerHop: Int = 0,

    /** Also route `FORLOOP` / `FORPREP` / `TFORLOOP` through hops. */
    val redirectLoopJumps: Boolean = true,

    /** `0` derives a deterministic seed from the chunk. */
    val seed: Long = 0,
) {
    init {
        require(decoyBlocks in 0..1024) { "decoyBlocks must be in 0..1024" }
        require(depth in 1..64) { "jump indirection depth must be in 1..64" }
        require(junkPerHop in 0..64) { "junkPerHop must be in 0..64" }
    }
}

/** Statistics of one [LuaJumpIndirection.apply] run. */
data class JumpIndirectionReport(
    val redirectedJumps: Int,
    val trampolines: Int,
    val injectedInstructions: Int,
    val decoyBlocks: Int = 0,
    val runtimeRoutedJumps: Int = 0,
    val verifiedPaths: Int = 0,
    val skippedPrototypes: List<String> = emptyList(),
)

/**
 * Redirects every jump through [JumpIndirectionConfig.depth] trampoline blocks
 * whose order is shuffled, so the linear layout no longer reflects the control
 * flow of the original function.
 *
 * ```text
 * [jmp]        JMP -> hop3            ; original distance is replaced
 * ...
 * [hop3]       MOVE scratch, scratch  ; junk, executed
 *              JMP -> hop7
 * [hop7]       JMP -> hop1
 * [hop1]       JMP -> original target
 * ```
 */
object LuaJumpIndirection {

    fun apply(
        chunk: LuaChunk,
        config: JumpIndirectionConfig = JumpIndirectionConfig(),
    ): JumpIndirectionReport {
        var jumps = 0
        var trampolines = 0
        var injected = 0
        var decoys = 0
        var runtimeJumps = 0
        var verified = 0
        val skipped = ArrayList<String>()
        for ((index, prototype) in chunk.prototypes.withIndex()) {
            val sites = jumpSites(prototype, config.redirectLoopJumps)
            if (sites.isEmpty()) continue
            requireNoFallThrough(prototype)
            // Build and verify without mutating the input. Range/frame failures keep it intact.
            val candidate = prototype.detachedCopy()
            val runtime = config.runtimeRoutes && candidate.numParams > 0 && config.depth > 1
            val scratchCount = if (config.decoyBlocks > 0) 4 else if (runtime || config.junkPerHop > 0) 1 else 0
            if (candidate.maxStackSize + scratchCount > 255) {
                skipped += "prototype $index: register limit"; continue
            }
            try {
                val random = ObfRandom(seedFor(config.seed, chunk, prototype))
                val scratch = reserveScratch(candidate, scratchCount)
                val lanes = if (runtime) 2 else 1
                // Math.multiplyExact equivalent: Long intermediate + explicit overflow guard.
                val total = run {
                    val product = sites.size.toLong() * (config.depth * lanes).toLong()
                    require(product <= Int.MAX_VALUE) { "junk site count overflow: $product" }
                    product.toInt()
                }
                val regionStart = candidate.code.size
                val order = shuffledIndices(total, random)
                val sizes = IntArray(total)
                for (logical in 0 until total) {
                    val hop = (logical / lanes) % config.depth
                    sizes[order[logical]] = config.junkPerHop + if (runtime && hop + 1 < config.depth) 4 else 1
                }
                val offsets = IntArray(total + 1)
                for (i in 0 until total) offsets[i + 1] = offsets[i] + sizes[i]
                fun block(site: Int, hop: Int, lane: Int) =
                    regionStart + offsets[order[(site * config.depth + hop) * lanes + lane]]
                val blocks = arrayOfNulls<List<LuaInstruction>>(total)
                val targets = sites.map { requireNotNull(candidate.code[it].jumpTarget(it)) }.distinct()
                val entries = ArrayList<CffControlPackets.Site>()
                sites.forEachIndexed { siteIndex, site ->
                    val target = requireNotNull(candidate.code[site].jumpTarget(site))
                    require(target in prototype.code.indices) { "invalid original target $target" }
                    for (hop in 0 until config.depth) for (lane in 0 until lanes) {
                        val start = block(siteIndex, hop, lane)
                        val instructions = ArrayList<LuaInstruction>()
                        repeat(config.junkPerHop) { n ->
                            // Never read an uninitialized scratch slot at a capsule entry.
                            instructions += if (n == 0) LuaInstructionFactory.abc(candidate.version, "LOADNIL", scratch)
                            else junkInstruction(candidate, scratch, random)
                        }
                        fun next(nextLane: Int) = if (hop + 1 == config.depth) target
                            else block(siteIndex, hop + 1, nextLane)
                        if (runtime && hop + 1 < config.depth) {
                            val v = candidate.version
                            instructions += LuaInstructionFactory.abc(v, "NOT", scratch, 0)
                            val polarity = random.nextInt(2)
                            instructions += if (v == LuaBytecodeVersion.LUA_5_3)
                                LuaInstructionFactory.abc(v, "TEST", scratch, 0, polarity)
                            else LuaInstructionFactory.abc(v, "TEST", scratch, 0, 0, polarity)
                            instructions += jumpTo(v, start + instructions.size, next(lane))
                            instructions += jumpTo(v, start + instructions.size, next(1 - lane))
                        } else instructions += jumpTo(candidate.version, start + instructions.size, next(0))
                        blocks[order[(siteIndex * config.depth + hop) * lanes + lane]] = instructions
                    }
                    val first = block(siteIndex, 0, 0)
                    candidate.edit { set(site, candidate.code[site].retargeted(site, site, first)) }
                    entries += CffControlPackets.Site(first, targets.indexOf(target))
                    // The alternate entrance is checked too, including unreachable first lanes.
                    if (runtime) entries += CffControlPackets.Site(block(siteIndex, 0, 1), targets.indexOf(target))
                }
                val appended = blocks.flatMap { requireNotNull(it) }
                appendObfuscationCode(candidate, appended)
                val decoyStart = candidate.code.size
                val decoyCode = ArrayList<LuaInstruction>()
                val v = candidate.version
                repeat(config.decoyBlocks) { n ->
                    val pc = decoyStart + n * 5
                    // Overlapping loop intervals and cross-entry edges are legal VM
                    // control flow but do not have the lexical nesting expected by
                    // source-oriented decompilers. All edges remain in this island.
                    val prep = LuaInstructionFactory.of(v, opcode(v, "FORPREP")).withA(scratch)
                    decoyCode += prep.retargeted(pc, pc, pc + if (v == LuaBytecodeVersion.LUA_5_3) 3 else 4)
                    decoyCode += if (v == LuaBytecodeVersion.LUA_5_3)
                        LuaInstructionFactory.abc(v, "TEST", 0, 0, random.nextInt(2))
                    else LuaInstructionFactory.abc(v, "TEST", 0, 0, 0, random.nextInt(2))
                    decoyCode += jumpTo(v, pc + 2, decoyStart + random.nextInt(config.decoyBlocks) * 5 + 1)
                    val loop = LuaInstructionFactory.of(v, opcode(v, "FORLOOP")).withA(scratch)
                    decoyCode += loop.retargeted(pc + 3, pc + 3, decoyStart + random.nextInt(n + 1) * 5 + 1)
                    decoyCode += jumpTo(v, pc + 4, decoyStart + ((n + 1) % config.decoyBlocks) * 5)
                }
                appendObfuscationCode(candidate, decoyCode)
                require(candidate.code.take(decoyStart).withIndex().none { (pc, i) ->
                    (i.jumpTarget(pc) ?: -1) >= decoyStart
                }) { "live edge enters a decoy" }
                require(candidate.code[decoyStart - 1].opcodeName == "JMP" &&
                    !candidate.code[decoyStart - 2].skipsNextInstruction) { "fallthrough into decoys" }
                CffTranslationValidator.checkStructure(candidate)
                val proof = CffTranslationValidator.verify(candidate.code, candidate.constants, entries,
                    targets, prototype.maxStackSize, candidate.maxStackSize, 10_000_000)
                prototype.commitFrom(candidate)
                jumps += sites.size; trampolines += total; injected += appended.size + decoyCode.size
                decoys += config.decoyBlocks
                verified += proof.paths
                if (runtime) runtimeJumps += sites.size
                else if (config.runtimeRoutes) skipped += "prototype $index: runtime routes require a parameter and depth >= 2; verified static routes"
            } catch (e: IllegalArgumentException) {
                skipped += "prototype $index: ${e.message}"
            } catch (e: IllegalStateException) {
                skipped += "prototype $index: ${e.message}"
            }
        }
        return JumpIndirectionReport(jumps, trampolines, injected, decoys, runtimeJumps, verified, skipped)
    }

}

// ------------------------------------------------------------------ junk code

/** Configuration of [LuaJunkCode] (花指令 / dead branch injection). */
data class JunkCodeConfig(
    /** Harmless instructions on the live path of every guarded block. */
    val junkPerBlock: Int = 2,

    /** How many jumps receive an opaque guarded branch. */
    val guardedBranches: Int = 2,

    /**
     * Guarantee a convergent guard on **every loop back edge** (Collberg §4.3:
     * extend the loop condition with an always true predicate, so the predicate
     * is re-evaluated on every iteration). Cheap sites are unaffected.
     */
    val alwaysGuardLoopBackEdges: Boolean = false,

    /**
     * Fraction of guarded branches placed at "hot" sites: loop back edges and
     * join points (blocks with in-degree >= 2), which is where junk costs the
     * most to remove (NOP-RL reports 33%-92% fewer instructions needed at
     * optimal positions).
     */
    val hotSiteRatio: Double = 0.7,

    /** Size of the junk inside a dead block. */
    val deadBlockSize: Int = 4,

    /** Additional blocks that nothing references at all (they form cycles). */
    val unreachableBlocks: Int = 0,

    /**
     * Blocks per unreachable cycle: `1` is a self loop, `> 1` builds a longer
     * "infinite goto" ring that a decompiler has to follow.
     */
    val deadCycleLength: Int = 2,

    /** Predicate family; `MIXED` randomises per branch (fingerprint resistance). */
    val predicateFamily: OpaquePredicate = OpaquePredicate.MIXED,

    /** Also guard `FORLOOP` / `FORPREP` / `TFORLOOP`. */
    val guardLoopJumps: Boolean = false,

    /** `0` derives a deterministic seed from the chunk. */
    val seed: Long = 0,
) {
    init {
        require(junkPerBlock in 0..64)
        require(guardedBranches >= 0)
        require(hotSiteRatio in 0.0..1.0)
        require(deadBlockSize in 0..64)
        require(unreachableBlocks >= 0)
        require(deadCycleLength >= 1)
    }
}

/** Runtime input plus randomly composed integer/bitwise expression families. */
enum class OpaquePredicate {
    /** Runtime-seeded comparison of x² and 7(x+1)²-1; never equal modulo 8. */
    NON_SQUARE,
    MBA_CARRY_CHAIN,
    MBA_BORROW_CHAIN,
    MBA_MASK_SELECT,
    MBA_PRODUCT_PARTITION,
    /** Randomized coefficients/encoding of the supported runtime family. */
    MIXED,
}

/** Statistics of one [LuaJunkCode.apply] run. */
data class JunkCodeReport(
    val guardedJumps: Int,
    val deadBlocks: Int,
    val injectedInstructions: Int,
    val skippedPrototypes: List<String> = emptyList(),
)

/**
 * Injects junk instructions (花指令) and opaque guarded branches.
 *
 * A guarded jump is rewritten as
 *
 * ```text
 * [jmp]      JMP -> guard
 * [guard]    <junk on scratch registers>
 *            <runtime MBA expression on initialized parameter>
 *            EQ ...                   ; opaque predicate, skips the next instruction
 *            JMP -> dead              ; statically referenced, never executed
 *            JMP -> original target   ; the real transfer
 * [dead]     <junk>
 *            JMP -> original target   ; converges with the live path
 * ```
 *
 * Both paths converge on the original target, so the pass is **semantics
 * preserving even if the predicate were evaluated differently**: the dead block
 * is real code that simply is not taken.
 */
object LuaJunkCode {

    fun apply(
        chunk: LuaChunk,
        config: JunkCodeConfig = JunkCodeConfig(),
    ): JunkCodeReport {
        var guarded = 0
        var deadBlocks = 0
        var injected = 0

        // Legacy host/constant seed options no longer emit foldable predicates or calls.
        val skipped = ArrayList<String>()
        for (prototype in chunk.prototypes) {
            val allSites = jumpSites(prototype, config.guardLoopJumps)
            if (allSites.isEmpty() || config.guardedBranches == 0) {
                continue
            }
            if (prototype.numParams == 0 || prototype.maxStackSize + 8 > 255) {
                skipped += "${prototype.lineDefined}: no initialized parameter or insufficient registers"
                continue
            }
            requireNoFallThrough(prototype)

            val random = ObfRandom(seedFor(config.seed, chunk, prototype))
            val sites = selectSites(prototype, allSites, config, random)
            val scratch = reserveScratch(prototype, 8)

            // Predicates are position independent, so they can be planned before the layout.
            val predicates = sites.map {
                buildPredicate(prototype, scratch, config, random)
            }
            val guardSizes = predicates.map { config.junkPerBlock + it.setup.size + 3 }
            val deadSize = config.deadBlockSize + 1
            val regionStart = prototype.code.size
            // layout: one guard block per site, then one dead block per site,
            // then the unreachable blocks.
            val guardStarts = ArrayList<Int>(sites.size)
            var cursor = regionStart
            for (size in guardSizes) {
                guardStarts += cursor
                cursor += size
            }
            val deadRegionStart = cursor
            val unreachableStart = deadRegionStart + sites.size * deadSize

            // The blocks are appended in layout order: every guard block first, then
            // every dead block, then the unreachable ones, so the assumed positions
            // (computed above) match the real ones.
            val guards = ArrayList<List<LuaInstruction>>(sites.size)
            val deadBlocksAppended = ArrayList<List<LuaInstruction>>(sites.size + config.unreachableBlocks)
            sites.forEachIndexed { index, site ->
                val originalTarget = prototype.code[site].jumpTarget(site)
                    ?: throw LuaBytecodeFormatException("expected a jump at $site")
                val guardStart = guardStarts[index]
                val deadStart = deadRegionStart + index * deadSize
                val guard = ArrayList<LuaInstruction>(guardSizes[index])
                repeat(config.junkPerBlock) { guard += junkInstruction(prototype, scratch, random) }
                guard += predicates[index].setup
                val plan = predicates[index]
                val (first, second) = if (random.nextBoolean()) {
                    plan.left to plan.right
                } else {
                    plan.right to plan.left
                }
                guard += compareBranch(prototype, first, second, plan.alwaysTrue)
                val deadJumpPc = guardStart + guard.size
                guard += jumpTo(prototype.version, deadJumpPc, deadStart)
                guard += jumpTo(prototype.version, deadJumpPc + 1, originalTarget)
                guards += guard

                val dead = ArrayList<LuaInstruction>(deadSize)
                repeat(config.deadBlockSize) { dead += junkInstruction(prototype, scratch, random) }
                dead += jumpTo(prototype.version, deadStart + dead.size, originalTarget)
                deadBlocksAppended += dead

                if (Platform.debug.enabled("XPASS_DEBUG")) {
                    Platform.debug.log(
                        "[xpass] junk ${prototype.source}:${prototype.lineDefined} site=$site " +
                            "${prototype.code[site].opcodeName} -> guard@$guardStart (dead@$deadStart)",
                    )
                }
                prototype.patchInstructionAt(site, prototype.code[site].retargeted(site, site, guardStart))
            }

            val cycleLength = config.deadCycleLength.coerceAtLeast(1)
            repeat(config.unreachableBlocks) { index ->
                val blockStart = unreachableStart + index * deadSize
                val dead = ArrayList<LuaInstruction>(deadSize)
                repeat(config.deadBlockSize) { dead += junkInstruction(prototype, scratch, random) }
                // Blocks form rings of `deadCycleLength`: the last one jumps back to
                // the first, an "infinite goto" that is never entered but has to be
                // followed by a decompiler.
                val cycleEnd = minOf(index - index % cycleLength + cycleLength, config.unreachableBlocks)
                val next = if (index + 1 < cycleEnd) {
                    unreachableStart + (index + 1) * deadSize
                } else {
                    unreachableStart + (index - index % cycleLength) * deadSize
                }
                dead += jumpTo(prototype.version, blockStart + dead.size, next)
                deadBlocksAppended += dead
            }

            for (block in guards + deadBlocksAppended) {
                appendObfuscationCode(prototype, block)
                injected += block.size
            }
            guarded += sites.size
            deadBlocks += sites.size + config.unreachableBlocks
        }

        return JunkCodeReport(guarded, deadBlocks, injected, skipped)
    }

    /** An always-decidable predicate: setup instructions plus the two compared registers. */
    internal class PredicatePlan(
        val setup: List<LuaInstruction>,
        val left: Int,
        val right: Int,
        val alwaysTrue: Boolean,
    )

    /** Runtime-seeded, randomly composed and independently validated expressions. */
    internal fun buildPredicate(
        prototype: LuaPrototype,
        scratch: Int,
        config: JunkCodeConfig,
        random: ObfRandom,
    ): PredicatePlan {
        return LuaMbaPredicates.build(prototype, scratch, random, config.predicateFamily)
    }

    /**
     * `EQ` between two registers. [alwaysTrue] picks the encoding that makes the
     * branch skip the dead jump either way.
     */
    internal fun compareBranch(
        prototype: LuaPrototype,
        left: Int,
        right: Int,
        alwaysTrue: Boolean,
    ): LuaInstruction {
        val version = prototype.version
        return when (version) {
            // skip iff (comparison ~= A)
            LuaBytecodeVersion.LUA_5_3 ->
                LuaInstructionFactory.abc(version, "EQ", a = if (alwaysTrue) 0 else 1, b = left, c = right)

            // skip iff (cond != k)
            LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 ->
                LuaInstructionFactory.abc(version, "EQ", a = left, b = right, c = 0, k = if (alwaysTrue) 0 else 1)
        }
    }

    /**
     * Emits `destination = env[constantIndex]`.
     *
     * `GETTABUP` addresses its key with an `RK` operand, and that operand carries
     * only 8 bits of constant index in every supported version: 5.3 packs the
     * `BITRK` flag into the 9 bit `C` field, so an index above 255 silently
     * decodes as `index and 0xFF` and reads a *different* constant (this is the
     * long standing `big_53` miscompilation), while 5.4 / 5.5 use an 8 bit
     * constant operand that cannot represent it at all. Larger indices are
     * therefore staged in a register and read through `GETTABLE`, whose key
     * operand accepts a register.
     *
     * The caller has to reserve [scratch] .. [scratch] + 3.
     */
    internal fun appendEnvLookup(
        setup: MutableList<LuaInstruction>,
        version: LuaBytecodeVersion,
        destination: Int,
        scratch: Int,
        envUpvalue: Int,
        constantIndex: Int,
    ) {
        if (constantIndex <= MAX_RK_CONSTANT_INDEX) {
            setup += getTabUp(version, destination, envUpvalue, constantIndex)
        } else {
            setup += LuaInstructionFactory.abc(version, "GETUPVAL", scratch + 2, envUpvalue, 0)
            setup += LuaInstructionFactory.abx(version, "LOADK", scratch + 3, constantIndex)
            setup += LuaInstructionFactory.abc(version, "GETTABLE", destination, scratch + 2, scratch + 3)
        }
    }

    /** `GETTABUP A, env, K[name]` (5.3 encodes the key operand with `BITRK`). */
    private fun getTabUp(
        version: LuaBytecodeVersion,
        destination: Int,
        envUpvalue: Int,
        constantIndex: Int,
    ): LuaInstruction {
        require(constantIndex <= MAX_RK_CONSTANT_INDEX) {
            "constant index $constantIndex does not fit the key operand of GETTABUP in ${version.displayName}; " +
                "use appendEnvLookup, which stages it in a register"
        }
        return LuaInstructionFactory.abc(
            version, "GETTABUP",
            destination,
            envUpvalue,
            if (version == LuaBytecodeVersion.LUA_5_3) rk53(constantIndex) else constantIndex,
        )
    }

    /** 5.3 encodes constant operands with `BITRK` set. */
    private fun rk53(constantIndex: Int): Int = constantIndex or (1 shl 8)
}

/** `BITRK` masks the constant index of every `RK` operand to 8 bits. */
private const val MAX_RK_CONSTANT_INDEX = 0xFF

/** Name of the upvalue that holds the global table. */
private const val ENV_UPVALUE_NAME = "_ENV"

/**
 * Resolves the `_ENV` upvalue index of every prototype of [chunk].
 *
 * The opcodes alone are **not** enough to recognize `_ENV`: on 5.3 any access to
 * an upvalue table compiles to `GETTABUP` (`t[k]` as well as a global `g`) and
 * any assignment to one compiles to `SETTABUP` (`store[i] = v`), so the first
 * such instruction may well reference a local table. `_ENV` is resolved through
 * the closure chain instead:
 *
 * * `lua_load` installs the global table in the first upvalue of the main chunk;
 * * a nested function that reaches globals captures `_ENV` by forwarding the
 *   parent's `_ENV` upvalue, i.e. `inStack == false && index == parentEnv`.
 *
 * A debug name is authoritative when present, because it also covers a source
 * level `local _ENV = ...` shadow. Prototypes whose `_ENV` cannot be proven to
 * hold the globals are absent from the result; callers fall back to a constant
 * seed instead of emitting code that indexes an arbitrary table.
 */
internal fun resolveEnvUpvalues(chunk: LuaChunk): IdentityMap<LuaPrototype, Int> {
    val result = IdentityMap<LuaPrototype, Int>()

    fun visit(prototype: LuaPrototype, parentEnv: Int?) {
        val named = prototype.upvalueNames.indexOfFirst { it?.utf8 == ENV_UPVALUE_NAME }
        val env = when {
            named >= 0 -> named
            prototype === chunk.mainPrototype -> if (prototype.upvalues.isEmpty()) null else 0
            parentEnv == null -> null
            else -> prototype.upvalues
                .indexOfFirst { !it.inStack && it.index == parentEnv }
                .takeIf { it >= 0 }
        }
        if (Platform.debug.enabled("JUNK_DEBUG")) {
            Platform.debug.log(
                "[env] proto=${prototype.source}:${prototype.lineDefined} " +
                    "upvalues=${prototype.upvalues.map { "inStack=${it.inStack},index=${it.index}" }} " +
                    "names=${prototype.upvalueNames.map { it?.utf8 }} named=$named parentEnv=$parentEnv -> env=$env",
            )
        }
        if (env != null && env < prototype.upvalues.size) {
            result[prototype] = env
        }
        for (child in prototype.nestedPrototypes) {
            visit(child, env)
        }
    }

    visit(chunk.mainPrototype, null)
    return result
}

/**
 * Live block cloning with a planted trap (Collberg POPL '98 §4.2).
 *
 * A chosen block `B` is duplicated into **two live copies** appended after the
 * code, and every jump that used to enter `B` is routed through an opaque
 * predicate that provably selects the *good* copy:
 *
 * ```text
 * [compare]           ; proven value, so the branch always skips its successor
 * JMP -> bad          ; the trap: only reached if the predicate is removed
 * JMP -> good         ; the real copy, exactly equivalent to B
 * [good] <copy of B>  ; same instructions, jumps kept on their original targets
 * [bad]  <copy of B with one operand corrupted>
 * ```
 *
 * Both copies continue exactly where `B` continued, so semantics are preserved
 * by construction, while a deobfuscator that deletes the predicate (the standard
 * "non reducible graph" heuristic Collberg calls out) starts executing the
 * corrupted copy. Operands are integers on every path, so the proven predicate
 * cannot be mis-evaluated.
 */
data class CloneBlocksConfig(
    /** Cloned blocks per prototype (only blocks with an incoming jump are eligible). */
    val blocksPerPrototype: Int = 1,

    /** Junk instructions in front of the selecting predicate. */
    val junkPerGuard: Int = 2,

    /** Corrupt one operand in the trap copy (the good copy is always exact). */
    val corruptTrapCopy: Boolean = true,

    /**
     * Only clone blocks that existed before this pass ran. Blocks injected by the
     * string / junk passes are already noise, and cloning those wastes size for no
     * extra resilience (found in round 11), so they are excluded by default.
     */
    val originalOnly: Boolean = true,

    /** Predicate family / seed policy of the selecting predicate. */
    val predicateFamily: OpaquePredicate = OpaquePredicate.MIXED,

    /** `0` derives a deterministic seed from the chunk content. */
    val seed: Long = 0,
) {
    init {
        require(blocksPerPrototype >= 1)
        require(junkPerGuard in 0..16)
    }
}

/** Statistics of one [LuaBlockCloning.apply] run. */
data class CloneBlocksReport(
    val clonedPrototypes: Int,
    val clones: Int,
    val injectedInstructions: Int,
)

object LuaBlockCloning {

    fun apply(
        chunk: LuaChunk,
        config: CloneBlocksConfig = CloneBlocksConfig(),
    ): CloneBlocksReport {
        var prototypes = 0
        var clones = 0
        var injected = 0

        for (prototype in chunk.prototypes) {
            if (prototype.numParams == 0 || prototype.maxStackSize + 8 > 255) continue
            val blocks = blockRanges(prototype) ?: continue
            if (blocks.size < 2) continue
            val version = prototype.version
            val entrySize = prototype.code.size

            val eligible = blocks.filter { block ->
                (!config.originalOnly || block.last < entrySize) &&
                    block.first > 0 &&
                    prototype.code[block.first - 1].opcodeName in TERMINATORS &&
                    block.none { prototype.code[it].opcodeName in NON_CLONEABLE } &&
                    block.none { pc ->
                        val target = prototype.code[pc].jumpTarget(pc)
                        target != null && target in block
                    } &&
                    corruptibleIndex(prototype, block) != null &&
                    incomingSites(prototype, block.first).isNotEmpty()
            }
            if (eligible.isEmpty()) continue
            requireNoFallThrough(prototype)

            val random = ObfRandom(seedFor(config.seed, chunk, prototype))
            val chosen = eligible.shuffled(random).take(config.blocksPerPrototype)
            val scratch = reserveScratch(prototype, 8)
            var cloned = 0

            for (block in chosen) {
                val entrySites = incomingSites(prototype, block.first)
                if (Platform.debug.enabled("XPASS_DEBUG")) {
                    val siteInfo = entrySites.joinToString(",") { site ->
                        val t = prototype.code[site].jumpTarget(site)
                        "$site->${prototype.code[site].opcodeName}@${t}"
                    }
                    Platform.debug.log(
                        "[xpass] clone-pick ${prototype.source}:${prototype.lineDefined} " +
                            "block=$block entrySites=[$siteInfo] entrySize=$entrySize codeSize=${prototype.code.size}",
                    )
                }
                val blockCode = block.map { prototype.code[it] }
                // A block whose last instruction skips its successor keeps falling
                // through when the skip happens, so the copy has to continue to the
                // block's original successor explicitly (`compare; JMP`, a skipping
                // `LOADBOOL` / `LFALSESKIP` / `TESTSET`).
                val terminatorSkips = blockCode.last().skipsNextInstruction
                val endsWithTerminator = blockCode.last().opcodeName in TERMINATORS && !terminatorSkips
                // CLONE-ASM/CLONE-TERM fix (B1, Round 2): a block whose last
                // instruction *skips* its successor (5.4/5.5 `LFALSESKIP`,
                // `TESTSET`, 5.3 `LOADBOOL .. 1`) keeps that skip in the copies:
                // the continuation jump appended right after it would be
                // SWALLOWED by the skip and execution would slide into the
                // adjacent copy (observed on e01_float_precision 5.4: the good
                // copy's `LFALSESKIP` ate the continuation jump and fell into
                // the corrupted trap copy -> `format: number expected, got
                // string`). A dead-register absorber between the block copy and
                // the continuation jump eats the skip, exactly like the
                // flattener's unit-edge handling (docs 14.108).
                val copySize = blockCode.size +
                    (if (endsWithTerminator) 0 else 1) +
                    (if (terminatorSkips) 1 else 0)
                val goodStart = prototype.code.size
                val trapStart = goodStart + copySize
                val guardStart = trapStart + copySize

                fun copy(base: Int, corruptAt: Int?): List<LuaInstruction> {
                    val result = ArrayList<LuaInstruction>(copySize)
                    block.forEachIndexed { index, pc ->
                        val instruction = prototype.code[pc]
                        val target = instruction.jumpTarget(pc)
                        result += when {
                            index == corruptAt -> corrupt(prototype, instruction, random)
                            target != null -> instruction.retargeted(pc, base + index, target)
                            else -> instruction
                        }
                    }
                    if (!endsWithTerminator) {
                        // CLONE-ASM fix (B1, Round 2), second half: when the
                        // copied block ends on a skip instruction, the original
                        // flow does NOT resume at `block.last + 1` (that is the
                        // skipped instruction — e.g. the `LOADBOOL R 1 0` true
                        // load of the boolean-return idiom) but at
                        // `block.last + 2`, exactly like the flattener's
                        // unit-edge targeting (docs 14.108).
                        val continuation = block.last + if (terminatorSkips) 2 else 1
                        if (terminatorSkips) {
                            result += LuaInstructionFactory.abc(
                                version, "MOVE",
                                scratch,
                                scratch,
                                0,
                            )
                        }
                        result += jumpTo(version, base + result.size, continuation)
                    }
                    return result
                }

                val good = copy(goodStart, null)
                val corruptIndex = corruptibleIndex(prototype, block)
                val trap = if (config.corruptTrapCopy && corruptIndex != null) {
                    copy(trapStart, corruptIndex)
                } else {
                    copy(trapStart, null)
                }

                appendObfuscationCode(prototype, good)
                appendObfuscationCode(prototype, trap)

                val plan = LuaJunkCode.buildPredicate(
                    prototype,
                    scratch,
                    JunkCodeConfig(
                        predicateFamily = config.predicateFamily,
                    ),
                    random,
                )
                val guard = ArrayList<LuaInstruction>(plan.setup.size + config.junkPerGuard + 3)
                repeat(config.junkPerGuard) { guard += junkInstruction(prototype, scratch, random) }
                guard += plan.setup
                guard += LuaJunkCode.compareBranch(prototype, plan.left, plan.right, plan.alwaysTrue)
                val comparePc = guardStart + guard.size - 1
                guard += jumpTo(version, comparePc + 1, trapStart)
                guard += jumpTo(version, comparePc + 2, goodStart)
                appendObfuscationCode(prototype, guard)

                for (pc in prototype.code.indices) {
                    if (pc in goodStart until guardStart) continue
                    if (prototype.code[pc].jumpTarget(pc) == block.first) {
                        if (Platform.debug.enabled("XPASS_DEBUG")) {
                            Platform.debug.log(
                                "[xpass] clone ${prototype.source}:${prototype.lineDefined} site=$pc " +
                                    "${prototype.code[pc].opcodeName} -> guard@$guardStart " +
                                    "(block=$block good@$goodStart trap@$trapStart entrySize=$entrySize)",
                            )
                        }
                        prototype.patchInstructionAt(pc, prototype.code[pc].retargeted(pc, pc, guardStart))
                    }
                }
                cloned++
                injected += good.size + trap.size + guard.size
            }
            if (cloned > 0) {
                prototypes++
                clones += cloned
            }
        }
        return CloneBlocksReport(prototypes, clones, injected)
    }

    /** Block ranges of a prototype (`null` when it has no analysable shape). */
    private fun blockRanges(prototype: LuaPrototype): List<IntRange>? {
        val starts = sortedSetOf(0)
        for (pc in prototype.code.indices) {
            prototype.code[pc].jumpTarget(pc)?.let { target ->
                if (target in prototype.code.indices) starts += target
            }
        }
        val list = starts.toList()
        return list.mapIndexed { index, from ->
            from..(if (index + 1 < list.size) list[index + 1] - 1 else prototype.code.size - 1)
        }
    }

    private fun incomingSites(prototype: LuaPrototype, start: Int): List<Int> =
        prototype.code.indices.filter { prototype.code[it].jumpTarget(it) == start }

    /** Index inside [block] of an instruction whose operand can be corrupted. */
    private fun corruptibleIndex(prototype: LuaPrototype, block: IntRange): Int? =
        block.firstOrNull { pc ->
            when (prototype.code[pc].opcodeName) {
                "LOADK", "LOADI", "MOVE", "ADDK", "SUBK", "MULK" -> true
                else -> false
            }
        }?.let { it - block.first }

    /** A plausible looking but wrong variant of [instruction]. */
    private fun corrupt(
        prototype: LuaPrototype,
        instruction: LuaInstruction,
        random: ObfRandom,
    ): LuaInstruction = when (instruction.opcodeName) {
        "LOADK" -> instruction.withBx((instruction.bx + 1) % maxOf(1, prototype.constants.size))
        "LOADI" -> instruction.withSbx(instruction.sbx + 1 + random.nextInt(3))
        "MOVE" -> instruction.withB(instruction.b + 1)
        else -> instruction
    }

    private val NON_CLONEABLE = setOf(
        "CLOSURE", "VARARGPREP", "TFORPREP", "TFORCALL", "TFORLOOP", "FORPREP", "FORLOOP",
        "SETLIST", "LOADKX", "EXTRAARG", "SETUPVAL", "RETURN", "RETURN0", "RETURN1", "TAILCALL",
    )
}

// ------------------------------------------------------------------- helpers

/** Jump instructions of [prototype], optionally including the redirectable loop opcodes. */
/** Picks guarded branch sites, preferring loop back edges and join points. */
private fun selectSites(
    prototype: LuaPrototype,
    allSites: List<Int>,
    config: JunkCodeConfig,
    random: ObfRandom,
): List<Int> {
    val backward = allSites.filter { pc ->
        val target = prototype.code[pc].jumpTarget(pc)
        target != null && target <= pc
    }
    if (config.alwaysGuardLoopBackEdges && backward.isNotEmpty()) {
        val rest = allSites.filter { it !in backward.toSet() }
        val extra = rest.shuffled(random).take(config.guardedBranches)
        return (backward + extra).sorted()
    }
    val budget = minOf(config.guardedBranches, allSites.size)
    if (budget == 0) return emptyList()
    if (budget >= allSites.size || config.hotSiteRatio <= 0.0) {
        return allSites.shuffled(random).take(budget).sorted()
    }
    val inDegree = HashMap<Int, Int>()
    for (pc in allSites) {
        val target = prototype.code[pc].jumpTarget(pc) ?: continue
        inDegree[target] = (inDegree[target] ?: 0) + 1
    }
    val hot = allSites.filter { pc ->
        val target = prototype.code[pc].jumpTarget(pc)
        target != null && (target <= pc || (inDegree[target] ?: 0) >= 2)
    }
    val cold = allSites.filter { it !in hot.toSet() }
    val hotBudget = minOf(hot.size, maxOf(1, (budget * config.hotSiteRatio).roundToInt()))
    return (hot.shuffled(random).take(hotBudget) + cold.shuffled(random).take(budget - hotBudget)).sorted()
}

private fun jumpSites(prototype: LuaPrototype, includeLoopJumps: Boolean): List<Int> {
    val sites = ArrayList<Int>()
    for (pc in prototype.code.indices) {
        val instruction = prototype.code[pc]
        val isJump = instruction.isUnconditionalJump ||
            (includeLoopJumps && redirectableLoopJump(prototype.version, instruction.opcodeName))
        if (isJump && instruction.jumpTarget(pc) != null) {
            sites += pc
        }
    }
    return sites
}

private val LOOP_JUMPS = setOf("FORLOOP", "FORPREP", "TFORLOOP")

/**
 * 5.3 encodes every loop jump as a signed `sBx`, so any direction is fine.
 * 5.4 / 5.5 use an *unsigned* distance: `FORLOOP` / `TFORLOOP` can only jump
 * backwards and therefore cannot reach a block appended after the final return,
 * while `FORPREP` only jumps forwards and can.
 */
private fun redirectableLoopJump(version: LuaBytecodeVersion, opcodeName: String): Boolean =
    when (opcodeName) {
        "FORLOOP", "TFORLOOP" -> version == LuaBytecodeVersion.LUA_5_3
        "FORPREP" -> true
        else -> false
    }

/** Reserves [count] registers above the register allocator high water mark. */
private fun reserveScratch(prototype: LuaPrototype, count: Int): Int {
    val scratch = prototype.maxStackSize
    require(scratch + count <= 255) { "obfuscation exceeds Lua register limit" }
    if (count > 0) {
        prototype.maxStackSize = scratch + count
    }
    return scratch
}

/**
 * Appended blocks must not be reachable by fall through: the current last
 * instruction has to be a return or an unconditional jump.
 */
private fun requireNoFallThrough(prototype: LuaPrototype) {
    val last = prototype.code.lastOrNull() ?: throw LuaBytecodeFormatException("empty prototype")
    if (last.opcodeName !in TERMINATORS ||
        (prototype.version == LuaBytecodeVersion.LUA_5_3 && last.opcodeName == "TAILCALL")) {
        throw LuaBytecodeFormatException(
            "cannot append blocks: the code ends with ${last.opcodeName}, which can fall through",
        )
    }
}

private val TERMINATORS = setOf("RETURN", "RETURN0", "RETURN1", "TAILCALL", "JMP")

/** Appends [instructions] and keeps the line tables consistent. */
internal fun appendObfuscationCode(prototype: LuaPrototype, instructions: List<LuaInstruction>) {
    if (instructions.isEmpty()) {
        return
    }
    val lines = if (prototype.lineInfo.isEmpty()) null else prototype.resolvedLineInfo().toMutableList()
    val line = prototype.lineDefined
    prototype.edit { appendAll(instructions, lines?.let { List(instructions.size) { line } }) }
}

/**
 * A harmless instruction on a scratch register: it can never raise and never
 * affects a register the original code uses.
 */
internal fun junkInstruction(
    prototype: LuaPrototype,
    scratch: Int,
    random: ObfRandom,
): LuaInstruction {
    val version = prototype.version
    val choices = ArrayList<Int>(6)
    choices += 0 // LOADNIL
    choices += 1 // MOVE
    choices += 2 // LOADBOOL / LOADFALSE
    choices += 3 // NOT / LOADTRUE
    // NEW-55-RESIDUAL / REG55-INT hardening (B1, Round 4): the LOADK junk case
    // used to pick a *random pool constant* — which on a float-bearing chunk
    // parks a non-integer float in `scratch`, putting exactly the "float feeds
    // a bitwise op" shape (§25.20: MBA chains may only touch self-constructed
    // integers) into the junk stream that the 5.5 crash reports describe. Junk
    // LOADK now selects among *integer* constants only; a chunk without any
    // integer constant simply loses this junk shape.
    val integerConstantIndices = prototype.constants.withIndex()
        .filter { it.value is LuaConstant.Integer }
        .map { it.index }
    if (integerConstantIndices.isNotEmpty()) {
        choices += 4 // LOADK (integer constants only)
    }
    if (version != LuaBytecodeVersion.LUA_5_3) {
        choices += 5 // NOT
    }
    return when (choices[random.nextInt(choices.size)]) {
        0 -> LuaInstructionFactory.abc(version, "LOADNIL", scratch, 0, 0)
        1 -> LuaInstructionFactory.abc(version, "LOADNIL", scratch, 0, 0)
        2 -> when (version) {
            LuaBytecodeVersion.LUA_5_3 -> LuaInstructionFactory.abc(version, "LOADBOOL", scratch, 0, 0)
            LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 ->
                LuaInstructionFactory.of(version, opcode(version, "LOADFALSE")).withA(scratch)
        }

        3 -> when (version) {
            LuaBytecodeVersion.LUA_5_3 -> LuaInstructionFactory.abc(version, "LOADNIL", scratch, 0, 0)
            LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 ->
                LuaInstructionFactory.of(version, opcode(version, "LOADTRUE")).withA(scratch)
        }

        4 -> LuaInstructionFactory.abx(
            version, "LOADK",
            scratch,
            integerConstantIndices[random.nextInt(integerConstantIndices.size)],
        )
        else -> LuaInstructionFactory.abc(version, "LOADNIL", scratch, 0, 0)
    }
}

private fun opcode(version: LuaBytecodeVersion, name: String): Int =
    LuaOpcodes.opcodeOf(version, name)
        ?: throw LuaBytecodeFormatException("no $name opcode in ${version.displayName}")

/** Deterministic seed derived from the chunk content when [seed] is `0`. */
internal fun seedFor(seed: Long, chunk: LuaChunk, prototype: LuaPrototype): Long {
    if (seed != 0L) {
        return seed + prototype.constantCount * 31L + prototype.instructionCount
    }
    var hash = 1469598103934665603L
    hash = hash * 1099511628211L + chunk.prototypes.size
    hash = hash * 1099511628211L + prototype.constantCount
    hash = hash * 1099511628211L + prototype.instructionCount
    for (constant in prototype.constants) {
        hash = hash * 1099511628211L + when (constant) {
            is LuaConstant.Str -> constant.value.bytes.contentHashCode().toLong()
            is LuaConstant.Integer -> constant.value
            is LuaConstant.Number -> constant.value.toRawBits()
            is LuaConstant.Boolean -> if (constant.value) 1L else 0L
            LuaConstant.Nil -> 17L
        }
    }
    return hash
}

private fun shuffledIndices(size: Int, random: ObfRandom): IntArray {
    val indices = IntArray(size) { it }
    for (i in size - 1 downTo 1) {
        val j = random.nextInt(i + 1)
        val tmp = indices[i]
        indices[i] = indices[j]
        indices[j] = tmp
    }
    return indices
}
