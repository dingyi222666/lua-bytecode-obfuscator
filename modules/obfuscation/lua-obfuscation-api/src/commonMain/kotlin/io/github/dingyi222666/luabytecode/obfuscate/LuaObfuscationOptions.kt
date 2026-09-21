// Per-pass option types of the obfuscation pipeline. These are the contract
// half of every optional pass: the feature modules consume them, the pipeline
// config (LuaObfuscationConfig.kt) composes them. A `null` config field in
// ObfuscationConfig disables the pass, so every feature is individually
// switchable. Extracted from the pass files when the feature modules were
// split (roadmap step 4); package kept as-is so callers are unaffected.
package io.github.dingyi222666.luabytecode.obfuscate

/**
 * Tunables of [LuaStringEncryptor].
 */
data class StringEncryptionConfig(
    /**
     * Only string constants of at least this many bytes are encrypted.
     * Short literals are usually identifiers, keys or format fragments whose
     * decryption call would cost more than it protects.
     */
    val minLength: Int = 6,

    /** Encrypt global/field/method operands as well as literal loads. */
    val encryptKeys: Boolean = false,

    /** Upper bound of encrypted constants (`0` = unlimited). */
    val maxConstants: Int = 0,

    /**
     * Seed of the key generator. `0` derives a deterministic seed from the chunk
     * content, so protecting the same chunk twice yields the same output.
     */
    val seed: Long = 0,
) : SeededPassConfig<StringEncryptionConfig> {
    override fun withSeed(seed: Long): StringEncryptionConfig = copy(seed = seed)
}

/**
 * Tunables of [LuaConstantEncryptor] (Pass K, `LEAK-2`).
 */
data class ConstantEncryptionConfig(
    /**
     * Upper bound of constants moved into the encrypted blob per chunk
     * (`0` = unlimited).
     */
    val maxConstants: Int = 0,

    /** `0` derives a deterministic seed from the chunk content. */
    val seed: Long = 0,
) : SeededPassConfig<ConstantEncryptionConfig> {
    override fun withSeed(seed: Long): ConstantEncryptionConfig = copy(seed = seed)
}

/**
 * Tunables of [LuaKeyRenaming] (LEAK-1 key half).
 */
data class KeyRenamingConfig(
    /** Minimum key length to consider (short names like `x` carry no semantics). */
    val minKeyLength: Int = 4,

    /** `0` derives a deterministic seed from the chunk content. */
    val seed: Long = 0,
) : SeededPassConfig<KeyRenamingConfig> {
    override fun withSeed(seed: Long): KeyRenamingConfig = copy(seed = seed)
}

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
) : SeededPassConfig<JumpIndirectionConfig> {
    init {
        require(decoyBlocks in 0..1024) { "decoyBlocks must be in 0..1024" }
        require(depth in 1..64) { "jump indirection depth must be in 1..64" }
        require(junkPerHop in 0..64) { "junkPerHop must be in 0..64" }
    }

    override fun withSeed(seed: Long): JumpIndirectionConfig = copy(seed = seed)
}

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
) : SeededPassConfig<JunkCodeConfig> {
    init {
        require(junkPerBlock in 0..64)
        require(guardedBranches >= 0)
        require(hotSiteRatio in 0.0..1.0)
        require(deadBlockSize in 0..64)
        require(unreachableBlocks >= 0)
        require(deadCycleLength >= 1)
    }

    override fun withSeed(seed: Long): JunkCodeConfig = copy(seed = seed)
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
) : SeededPassConfig<CloneBlocksConfig> {
    init {
        require(blocksPerPrototype >= 1)
        require(junkPerGuard in 0..16)
    }

    override fun withSeed(seed: Long): CloneBlocksConfig = copy(seed = seed)
}

/** Single CFF emitter, with transactional candidates and explicit feature gates. */
data class ControlFlowFlatteningConfig(
    /** Master switch. */
    val enabled: Boolean = true,

    /** `0` derives a deterministic seed from the chunk content. */
    val seed: Long = 0,

    /** Project original initialized values with TEST; never read _ENV. */
    val stateRuntime: Boolean = false,
    val participatingJunk: Boolean = false,
    val segmentedDispatch: Boolean = false,
    val indegreeGuards: Boolean = false,
    val shuffleBlocks: Boolean = false,
    val splitStraightLine: Boolean = false,
    val stateNamespaces: Boolean = false,
    val multipleDispatch: Boolean = false,
    val nestedDispatch: Boolean = false,
    val dispatchEffects: Boolean = false,
    val stateTable: Boolean = false,
    val predicateFamilies: Set<CffPredicateFamily> = emptySet(),
    /** Explicit execution contract required by §5; false preserves allocation observations. */
    val allocationEffectsUnobservable: Boolean = false,
    val segmentSize: Int = 8,
    val maxExpansionFactor: Int = 200,
    val maxValidationSteps: Int = 2_000_000,
) : SeededPassConfig<ControlFlowFlatteningConfig> {
    init {
        require(segmentSize > 0)
        require(maxExpansionFactor > 0 && maxValidationSteps > 0)
    }

    override fun withSeed(seed: Long): ControlFlowFlatteningConfig = copy(seed = seed)

    companion object {
        val BASELINE = ControlFlowFlatteningConfig()
        /** Requests all scalar features; actual coverage is reported per prototype. */
        val FULL = ControlFlowFlatteningConfig(
            stateRuntime = true, stateNamespaces = true, participatingJunk = true,
            segmentedDispatch = true, multipleDispatch = true, nestedDispatch = true,
            dispatchEffects = true, indegreeGuards = true, shuffleBlocks = true,
            splitStraightLine = true,
            predicateFamilies = setOf(CffPredicateFamily.NON_SQUARE),
        )
    }
}

enum class CffPredicateFamily { NON_SQUARE, PRIVATE_STRUCTURE, GRAPH }

/**
 * Tunables of [LuaRegisterObfuscator] (Pass R: register / local obfuscation).
 *
 * @param localNameRewrite R2: replace every `localVariables` debug name with a
 *   random synthetic name (`l3_9f2a`). Pure debug metadata, safe on every
 *   version and every preset.
 * @param registerRenaming R1: permute the non-parameter registers of every
 *   prototype under [LuaDataFlow] liveness. Does not add or remove
 *   instructions, so it composes with every other pass.
 * @param seed RNG seed; `0` derives one from the chunk, so runs are
 *   reproducible while different chunks never share a name space.
 */
data class RegisterObfuscationConfig(
    val localNameRewrite: Boolean = true,
    val registerRenaming: Boolean = false,
    /**
     * R3: merge live-range-disjoint registers onto one slot. Breaks the
     * one-register-per-variable mapping unluac reconstructs (declaration count
     * and scoping change in the decompiled output). Conservative candidate
     * rules, see [LuaRegisterObfuscator].
     */
    val registerMerging: Boolean = false,
    /**
     * R4: split live ranges by inserting `MOVE newReg, r` after a definition
     * and redirecting the dominated uses. One variable decompiles as two; the
     * register high water mark grows by one per split (bounded).
     */
    val registerSplitting: Boolean = false,
    val seed: Long = 0,
) : SeededPassConfig<RegisterObfuscationConfig> {
    override fun withSeed(seed: Long): RegisterObfuscationConfig = copy(seed = seed)
}