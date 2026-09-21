// Per-pass report / statistics types of the obfuscation pipeline: the result
// half of the contract between the feature modules and the pipeline
// (ObfuscationReport composes them). Extracted from the pass files when the
// feature modules were split (roadmap step 4); package kept as-is so callers
// are unaffected.
package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype

/** Statistics of one [LuaStringEncryptor.encrypt] run. */
data class StringEncryptionResult(
    /** Number of constants that were replaced by their encrypted bytes. */
    val encryptedConstants: Int,
    val loweredOperandSites: Int = 0,
    val remainingPlaintextStrings: Int = 0,
    /** Number of `LOADK` sites that now call the decoder. */
    val rewrittenSites: Int,
    /** Number of prototypes that received decrypt blocks. */
    val patchedPrototypes: Int,
    /**
     * The prototypes that now contain a decryption stub. The flattener uses this to decide
     * whether its save slot has to be chosen with the strict "never read" rule: a stub can
     * use a register that is dead at the flattener's dynamic instructions but live on the
     * stub's own path (goal round 65).
     */
    val stubPrototypes: Set<io.github.dingyi222666.luabytecode.bytecode.LuaPrototype> = emptySet(),
    /** Index of the injected decoder prototype in the main prototype, `-1` when unused. */
    val decoderPrototypeIndex: Int,
)

/** Statistics of one [LuaConstantEncryptor.encrypt] run. */
data class ConstantEncryptionResult(
    /** Number of constants that were replaced by their encrypted blob reference. */
    val encryptedConstants: Int,
    /** Number of `LOADK` sites that now call the kget stub. */
    val rewrittenSites: Int,
    /** Number of prototypes that received a kget block. */
    val patchedPrototypes: Int,
    /**
     * The prototypes that now contain a kget stub (same flattener contract as
     * [StringEncryptionResult.stubPrototypes]).
     */
    val stubPrototypes: Set<LuaPrototype> = emptySet(),
    /** Index of the injected kget prototype in the main prototype, `-1` when unused. */
    val decoderPrototypeIndex: Int = -1,
    /**
     * The injected decoder prototype(s). The flattener must NOT rebuild them:
     * its block rewriting silently corrupted decoded values (Round 1), so
     * [LuaObfuscator] forwards this set as the flattener's `protectedPrototypes`.
     */
    val decoderPrototypes: Set<LuaPrototype> = emptySet(),
    /** Candidate sites left unchanged because safe expansion was unavailable. */
    val skippedSites: Int = 0,
)

/** Statistics of one [LuaKeyRenaming.apply] run. */
data class KeyRenamingResult(
    /** Number of key string constants that were renamed. */
    val renamedKeys: Int,
    /** Number of rewritten reference sites. */
    val rewrittenSites: Int,
    /** Number of candidate keys skipped by the safety judgments. */
    val skippedKeys: Int,
)

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

/** Statistics of one [LuaJunkCode.apply] run. */
data class JunkCodeReport(
    val guardedJumps: Int,
    val deadBlocks: Int,
    val injectedInstructions: Int,
    val skippedPrototypes: List<String> = emptyList(),
)

/** Statistics of one [LuaBlockCloning.apply] run. */
data class CloneBlocksReport(
    val clonedPrototypes: Int,
    val clones: Int,
    val injectedInstructions: Int,
)

/** Statistics of one [LuaControlFlowFlattening.apply] run. */
data class ControlFlowFlatteningReport(
    val flattenedPrototypes: Int,
    val skippedPrototypes: Int,
    val blocks: Int,
    val guards: Int,
    val injectedInstructions: Int,
    /** §8 root cause codes -> how many prototypes were refused for each. */
    val refusals: Map<String, Int> = emptyMap(),
    /** The features that were actually enabled for this run (§9.6.5). */
    val appliedFeatures: List<String> = emptyList(),
    val prototypes: List<CffPrototypeReport> = emptyList(),
) {
    override fun toString(): String = buildString {
        append("$flattenedPrototypes prototypes, $blocks blocks")
        if (guards > 0) append(", $guards converging guards")
    }
}

data class CffPrototypeReport(
    val prototypeIndex: Int,
    val status: String,
    val requestedFeatures: List<String>,
    val actualFeatures: List<String>,
    val reasons: List<String>,
    val verifiedControlPaths: Int = 0,
    val verificationSteps: Int = 0,
    val proof: CffProofManifest? = null,
)

/** Build-side certificate; never serialized into Lua constants or debug metadata. */
data class CffProofManifest(
    val controlEntries: List<Pair<Int, Int>>,
    val destinationPcs: List<Int>,
    val firstPrivateRegister: Int,
    val validationBudget: Int,
    /** Only entry parameters have a two-input variability witness; edge truth may be fixed by a branch. */
    val variableSeedSites: Int = 0,
)

/** Statistics of one [LuaRegisterObfuscator.apply] run. */
data class RegisterObfuscationReport(
    /** Prototypes whose local / upvalue debug names were replaced (R2). */
    val renamedLocalsPrototypes: Int,
    /** Number of local variable debug entries that got a synthetic name. */
    val renamedLocals: Int,
    /** Prototypes whose registers were permuted (R1). */
    val renamedRegisterPrototypes: Int,
    /** Number of registers absorbed into another slot (R3); pairs merged. */
    val mergedRegisters: Int,
    /** Number of live-range splits performed (R4); each adds one MOVE. */
    val splitRanges: Int,
    /** Prototypes skipped by R1 (too small, too full, self-modifying upvalue tree). */
    val skippedPrototypes: Int,
) {
    override fun toString(): String = buildString {
        append("registers: ")
        append(renamedLocals).append(" locals renamed in ").append(renamedLocalsPrototypes).append(" prototypes")
        if (renamedRegisterPrototypes > 0) {
            append(", registers permuted in ").append(renamedRegisterPrototypes).append(" prototypes")
        }
        if (mergedRegisters > 0) {
            append(", ").append(mergedRegisters).append(" registers merged")
        }
        if (splitRanges > 0) {
            append(", ").append(splitRanges).append(" live ranges split")
        }
        if (skippedPrototypes > 0) {
            append(", ").append(skippedPrototypes).append(" skipped")
        }
    }
}
/** Statistics of one [LuaTailReturn.apply] run. */
data class TailReturnReport(val patchedPrototypes: Int, val injectedInstructions: Int) {
    override fun toString(): String =
        "tail return: $patchedPrototypes prototypes patched ($injectedInstructions instructions)"
}
