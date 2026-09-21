package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.platform.IdentitySet
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.edit.clearDebugInfo

/**
 * Obfuscation pipeline: every pass is optional and individually tunable, so a
 * caller can pick exactly what to pay for.
 *
 * ```kotlin
 * // only strings
 * LuaObfuscator.obfuscate(chunk, ObfuscationConfig(stringEncryption = StringEncryptionConfig()))
 *
 * // the balanced preset
 * LuaObfuscator.obfuscate(chunk, ObfuscationConfig.BALANCED)
 * ```
 *
 * Passes run in a fixed order because each one appends its blocks after the
 * previous one (see the individual pass docs):
 * string encryption -> jump indirection -> junk code -> debug strip.
 */
data class ObfuscationConfig(
    /** `null` disables constant pool / string encryption. */
    val stringEncryption: StringEncryptionConfig? = null,

    /** `null` disables numeric / boolean constant pool encryption (Pass K, LEAK-2). */
    val constantEncryption: ConstantEncryptionConfig? = null,

    /** `null` disables jump indirection (trampolines). */
    val jumpIndirection: JumpIndirectionConfig? = null,

    /** `null` disables junk code / dead branch injection. */
    val junkCode: JunkCodeConfig? = null,

    /** `null` disables live block cloning with a planted trap copy. */
    val blockCloning: CloneBlocksConfig? = null,

    /** `null` disables control flow flattening (it rebuilds the code, so it runs last). */
    val controlFlowFlattening: ControlFlowFlatteningConfig? = null,

    /** `null` disables table-field key renaming (LEAK-1 key half). */
    val keyRenaming: KeyRenamingConfig? = null,

    /** Strip `lineInfo`, locals, upvalue names and sources at the end. */
    val stripDebugInfo: Boolean = false,

    /** `null` disables register / local obfuscation (Pass R). */
    val registerObfuscation: RegisterObfuscationConfig? = null,

    /**
     * PRED-2/LEAK-5 master seed (SeedPolicy): `null` = per-run SecureRandom
     * (the default: every run emits a unique artifact), `0` = legacy content
     * derivation (byte-sensitive tooling passes this), anything else pins the
     * run. Individual pass configs' `seed` fields still win when set.
     */
    val masterSeed: Long? = null,

    /** Optional extension modules executed around the built-in pipeline. */
    val modules: List<LuaObfuscationModule> = emptyList(),
) {    companion object {
        /** Nothing at all. */
        val NONE = ObfuscationConfig()

        /**
         * Cheap tier: only protect the longer literals and use a single hop.
         * No junk code and no flattening, so the runtime and size cost stay in
         * the noise (see tools/benchmark-presets.sh).
         */
        val LIGHT = ObfuscationConfig(
            stringEncryption = StringEncryptionConfig(minLength = 8),
            jumpIndirection = JumpIndirectionConfig(depth = 1),
        )

        /**
         * Middle tier: shorter literals, one hop, a little junk placed on cold
         * sites only (`hotSiteRatio = 0.0`) so hot loops stay fast; no
         * flattening. Speed/strength trade-off measured by the benchmark script.
         */
        val BALANCED = ObfuscationConfig(
            stringEncryption = StringEncryptionConfig(minLength = 6),
            jumpIndirection = JumpIndirectionConfig(depth = 1, junkPerHop = 1),
            junkCode = JunkCodeConfig(
                junkPerBlock = 1,
                guardedBranches = 2,
                deadBlockSize = 3,
                unreachableBlocks = 1,
                hotSiteRatio = 0.0,

            ),
        )

        /** Most work: every literal, three hops with junk, four guarded branches. */
        val AGGRESSIVE = ObfuscationConfig(
            stringEncryption = StringEncryptionConfig(minLength = 1, encryptKeys = true),
            // Pass K (LEAK-2): numeric / boolean pool constants encrypted with a
            // full period LCG and exact tagged eight-byte payloads.
            constantEncryption = ConstantEncryptionConfig(),
            jumpIndirection = JumpIndirectionConfig(depth = 3, junkPerHop = 2, runtimeRoutes = true, decoyBlocks = 12),
            junkCode = JunkCodeConfig(
                junkPerBlock = 4,
                guardedBranches = 4,
                deadBlockSize = 6,
                unreachableBlocks = 4,
                deadCycleLength = 3,
                // P3 review: guards on every loop back edge (Collberg §4.3) and
                // hot site placement (NOP-RL) are the strength side of the
                // trade-off; the low tiers keep them off.
                alwaysGuardLoopBackEdges = true,
                hotSiteRatio = 0.8,
                predicateFamily = OpaquePredicate.MIXED,
                // Runtime parameters feed every generated predicate.

            ),
            // Flattening runs last (it rebuilds the instruction stream) and is now
            // verified on every version: semantic matrix, dump -> reload, the
            // arithmetic `MMBIN` pairing invariant, and the decompiler check in
            // docs/obfuscation-algorithms.md §8 P5.
            // Live block cloning with a trap copy (Collberg §4.2). Verified on
            // 5.4 / 5.5 for every fixture including `big_module`; 5.3 is gated off
            // inside the pass (see the TODO there).
            blockCloning = CloneBlocksConfig(),
            controlFlowFlattening = ControlFlowFlatteningConfig.FULL,
            // Pass R (LEAK-1 R2): synthetic local debug names; the R1 register
            // permutation stays opt-in until the full interaction matrix re-runs.
            registerObfuscation = RegisterObfuscationConfig(localNameRewrite = true),
            stripDebugInfo = true,
        )
    }
}

/** What [LuaObfuscator.obfuscate] did. */
data class ObfuscationReport(
    val stringEncryption: StringEncryptionResult? = null,
    val keyRenaming: KeyRenamingResult? = null,
    val constantEncryption: ConstantEncryptionResult? = null,
    val jumpIndirection: JumpIndirectionReport? = null,
    val junkCode: JunkCodeReport? = null,
    val blockCloning: CloneBlocksReport? = null,
    val controlFlowFlattening: ControlFlowFlatteningReport? = null,
    val strippedDebugInfo: Boolean = false,
    val tailReturn: LuaTailReturn.Report? = null,
    val registerObfuscation: RegisterObfuscationReport? = null,
    val modules: List<LuaObfuscationModuleResult> = emptyList(),
) {
    override fun toString(): String = buildString {
        stringEncryption?.let {
            append("strings: ${it.encryptedConstants} encrypted, ${it.rewrittenSites} sites, ${it.loweredOperandSites} key/operand sites, ${it.remainingPlaintextStrings} plaintext entries retained")
        }
        constantEncryption?.let {
            if (isNotEmpty()) append(", ")
            append("constants: ${it.encryptedConstants} encrypted, ${it.rewrittenSites} sites")
        }
        keyRenaming?.takeIf { it.renamedKeys > 0 }?.let {
            if (isNotEmpty()) append(", ")
            append("keys: ${it.renamedKeys} renamed (${it.rewrittenSites} sites)")
        }
        jumpIndirection?.let {
            if (isNotEmpty()) append(", ")
            append("jumps: ${it.redirectedJumps} redirected through ${it.trampolines} hops, ${it.runtimeRoutedJumps} runtime routes, ${it.decoyBlocks} unreachable blocks, ${it.verifiedPaths} verified paths")
            if (it.skippedPrototypes.isNotEmpty()) append(" (${it.skippedPrototypes.joinToString("; ")})")
        }
        junkCode?.let {
            if (isNotEmpty()) append(", ")
            append("junk: ${it.guardedJumps} guarded branches, ${it.deadBlocks} dead blocks")
        }
        blockCloning?.let {
            if (isNotEmpty()) append(", ")
            append("cloned: ${it.clones} blocks (${it.injectedInstructions} instructions)")
        }
        controlFlowFlattening?.let {
            if (isNotEmpty()) append(", ")
            append("flattened: ${it.flattenedPrototypes} prototypes, ${it.blocks} blocks")
        }
        if (strippedDebugInfo) {
            if (isNotEmpty()) append(", ")
            append("debug info stripped")
        }
        tailReturn?.takeIf { it.patchedPrototypes > 0 }?.let {
            if (isNotEmpty()) append(", ")
            append(it.toString())
        }
        registerObfuscation?.let {
            if (isNotEmpty()) append(", ")
            append(it.toString())
        }
        if (isEmpty()) append("nothing")
    }
}

/** Runs the configured passes over a chunk, in place. */
object LuaObfuscator {

    fun obfuscate(
        chunk: LuaChunk,
        config: ObfuscationConfig = ObfuscationConfig.BALANCED,
    ): ObfuscationReport {
        // PRED-2/LEAK-5: resolve the master seed once per run and stamp it into
        // every pass config whose own `seed` field is still at its default. A
        // pass config with an explicit seed keeps it (granular override, same
        // precedence rule as the CLI flag merge). `null` master => per-run
        // SecureRandom; `0` => legacy content derivation (SeedPolicy docs).
        val master = SeedPolicy.masterSeed(config.masterSeed, chunk)
        val stamped = config.copy(
            stringEncryption = config.stringEncryption?.let {
                if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "strings")) else it
            },
            constantEncryption = config.constantEncryption?.let {
                if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "constants")) else it
            },
            jumpIndirection = config.jumpIndirection?.let {
                if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "jumps")) else it
            },
            junkCode = config.junkCode?.let {
                if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "junk")) else it
            },
            blockCloning = config.blockCloning?.let {
                if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "clones")) else it
            },
            controlFlowFlattening = config.controlFlowFlattening?.let {
                if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "flatten")) else it
            },
            registerObfuscation = config.registerObfuscation?.let {
                if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "registers")) else it
            },
        )
        val cfg = stamped
        val moduleContext = LuaObfuscationModuleContext(chunk, cfg, master)
        val moduleResults = ArrayList<LuaObfuscationModuleResult>()
        cfg.modules.filter { it.phase == ObfuscationPhase.BEFORE_CORE }
            .forEach { moduleResults += it.apply(moduleContext) }
        // Order matters: the jump pass runs first so the string stubs it creates
        // afterwards are plain, untrampolined blocks, and junk guards every jump
        // including the stubs' (all three append blocks after a terminator).
        val jumps = cfg.jumpIndirection?.let { LuaJumpIndirection.apply(chunk, it) }
        val strings = cfg.stringEncryption?.let { LuaStringEncryptor.encrypt(chunk, it) }
        // Pass R runs after the string stubs exist (they are plain instructions the
        // register permutation has to see) and before junk / cloning / flattening
        // (those derive their own register budgets from the post-R layout).
        val registers = cfg.registerObfuscation?.let { LuaRegisterObfuscator.apply(chunk, it) }
        val junk = cfg.junkCode?.let { LuaJunkCode.apply(chunk, it) }
        val clones = cfg.blockCloning?.let { LuaBlockCloning.apply(chunk, it) }
        // Flattening rebuilds the whole instruction stream, so it runs last. Running the
        // string layer *after* it made every counter fixture DUMPFAIL (goal round 64), so the
        // order stays; the remaining `features_55` case is a false-safe save slot (docs 14.45).
        var flattening = cfg.controlFlowFlattening?.let {
            val decoders = strings?.decoderPrototypeIndex?.takeIf { index -> index >= 0 }
                ?.let { index -> chunk.mainPrototype.nestedPrototypes[index].allPrototypes().toSet() }
                ?: emptySet()
            LuaControlFlowFlattening.apply(chunk, it, strings?.stubPrototypes ?: emptySet(), decoders)
        }
        // Pass K runs AFTER the flattener: (a) the flattener's rematerialisation
        // re-derives integer constants at run time and LEAVES the plaintext in
        // the pool — running K last lets it encrypt whatever still survives as a
        // live LOADK site; (b) the kget decoder prototype (and its stub blocks)
        // are appended to the final layout, so no earlier pass can rebuild them.
        // Round 1 lesson: when the decoder existed DURING flattening, its rebuilt
        // body decoded garbage — keeping the decoder out of the flattener's view
        // entirely is the robust fix.
        if (flattening?.prototypes?.any { it.status == "INPUT_REJECTED" } == true) {
            throw LuaBytecodeFormatException("CFF rejected an invalid input instruction structure: " +
                flattening.prototypes.filter { it.status == "INPUT_REJECTED" }.joinToString { "${it.prototypeIndex}: ${it.reasons}" })
        }
        val constants = cfg.constantEncryption?.let { constantConfig ->
            val protected = IdentitySet<LuaPrototype>()
            val beforeConstants = chunk.prototypes
            var inlineConstants = 0
            var inlinePrototypes = 0
            var inlineSites = 0
            val updatedRecords = ArrayList<CffPrototypeReport>()
            for (record in flattening?.prototypes.orEmpty()) {
                val proof = record.proof
                if (proof == null) { updatedRecords += record; continue }
                val prototype = beforeConstants[record.prototypeIndex]
                protected += prototype
                val expansion = CffConstantExpansion.apply(prototype, proof, constantConfig)
                if (expansion != null) {
                    inlineConstants += expansion.constants
                    inlineSites += expansion.sites
                    inlinePrototypes++
                    updatedRecords += record.copy(proof = expansion.proof,
                        verifiedControlPaths = expansion.paths, verificationSteps = expansion.steps)
                } else updatedRecords += record
            }
            flattening = flattening?.copy(prototypes = updatedRecords)
            // The legacy decoder uses CALL and cannot enter a live control packet.
            // Flattened prototypes use only the verified local expansion above.
            LuaConstantEncryptor.encrypt(chunk, constantConfig, protected).let {
                if (it.decoderPrototypeIndex >= 0) {
                    val at = if (chunk.mainPrototype.code.first().opcodeName == "VARARGPREP") 1 else 0
                    fun moved(pc: Int) = if (pc < at) pc else pc + 2
                    flattening = flattening?.copy(prototypes = flattening!!.prototypes.map { record ->
                        val proof = record.proof
                        if (record.prototypeIndex != 0 || proof == null) record else record.copy(proof = proof.copy(
                            controlEntries = proof.controlEntries.map { (pc, target) -> moved(pc) to target },
                            destinationPcs = proof.destinationPcs.map(::moved)))
                    })
                }
                it.copy(encryptedConstants = it.encryptedConstants + inlineConstants,
                    rewrittenSites = it.rewrittenSites + inlineSites,
                    patchedPrototypes = it.patchedPrototypes + inlinePrototypes)
            }
        }
        // LEAK-1 key half runs after K (both see the final layout; keys are a
        // shared-reference surface and never enter K's encryption set — the two
        // passes are orthogonal by construction).
        val keys = cfg.keyRenaming?.let { LuaKeyRenaming.apply(chunk, it) }

        // Every pass has run; some flattener / junk block layouts end in a `JMP` (the
        // dispatcher back edge), which is valid bytecode but breaks the "every prototype
        // ends with a RETURN" invariant unluac validates. The appended RETURN is
        // unreachable (the previous instruction is a redirect), so this cannot change
        // semantics -- it only makes the chunk acceptable to official-tooling again.
        val tailReturn = LuaTailReturn.apply(chunk)
        if (config.stripDebugInfo) {
            chunk.clearDebugInfo()
            // `clearDebugInfo` drops the synthetic upvalue names `LuaTailReturn` just
            // installed; re-run the naming half after the strip so stripped chunks stay
            // parseable by unluac (names are debug metadata, bytecode semantics equal).
            LuaTailReturn.nameUpvalues(chunk)
        }
        cfg.modules.filter { it.phase == ObfuscationPhase.AFTER_CORE }
            .forEach { moduleResults += it.apply(moduleContext) }
        return ObfuscationReport(
            stringEncryption = strings,
            keyRenaming = keys,
            constantEncryption = constants,
            jumpIndirection = jumps,
            junkCode = junk,
            blockCloning = clones,
            controlFlowFlattening = flattening,
            strippedDebugInfo = config.stripDebugInfo,
            tailReturn = tailReturn,
            registerObfuscation = registers,
            modules = moduleResults,
        )
    }
}
