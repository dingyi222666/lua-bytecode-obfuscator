// The obfuscation pipeline contract: ObfuscationConfig (with the LIGHT /
// BALANCED / AGGRESSIVE presets) and ObfuscationReport. Every built-in pass is
// switched by a nullable config field, so each feature module can be enabled,
// disabled, published and tested independently. Extracted from LuaObfuscator.kt
// when the feature modules were split (roadmap step 4): the extension-point
// context (LuaObfuscationModuleContext) references this type, so it must live
// below the features.
package io.github.dingyi222666.luabytecode.obfuscate

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
    val tailReturn: TailReturnReport? = null,
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