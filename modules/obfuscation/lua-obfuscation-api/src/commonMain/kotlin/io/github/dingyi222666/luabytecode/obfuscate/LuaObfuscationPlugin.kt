// The obfuscation pass plugin contract: every built-in pass is a plugin that
// registers itself with an ObfuscationRegistry (see :lua-obfuscation-pipeline,
// the assembly layer) and the pipeline runs `registry.resolve()` instead of a
// hardcoded pass sequence. Plugins know NOTHING about each other: ordering is
// declared per plugin (`runsAfter` / `runsBefore` ids) and resolved by the
// registry, and cross-pass artifacts travel through the typed named slots of
// the [ObfuscationContext.reportBuilder] (e.g. the string pass installs its
// stub bookkeeping, the flattener reads it — neither imports the other).
package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaChunk

/**
 * A per-pass config section that carries a domain-separated RNG [seed].
 *
 * Implemented by every seedable pass config in this package so the seeding
 * stage can stamp default seeds uniformly (see [withDefaultSeed]) without
 * knowing the concrete pass. [KeyRenamingConfig] implements it too, but its
 * plugin deliberately declares no seed label: the pass keeps its legacy
 * content-derived seed when `seed == 0` (it is not stamped with the master
 * seed, exactly like the pre-plugin orchestrator).
 */
interface SeededPassConfig<Self> {
    /** RNG seed; `0` means "derive from the run master seed" (see [withDefaultSeed]). */
    val seed: Long

    /** A copy of this section with [seed] replaced. */
    fun withSeed(seed: Long): Self
}

/**
 * The single source of the default-seed stamping rule (PRED-2/LEAK-5): a pass
 * section whose `seed` is still at its default `0` receives the domain
 * separated [SeedPolicy.passSeed] of the run's master seed; an explicit seed
 * keeps its granular-override precedence. The seeding stage of the pipeline
 * applies this through every registered plugin, so no orchestrator lists the
 * passes anymore.
 */
fun <C : SeededPassConfig<C>> C.withDefaultSeed(masterSeed: Long, seedLabel: String): C =
    if (seed == 0L) withSeed(SeedPolicy.passSeed(masterSeed, seedLabel)) else this

/**
 * Mutable report accumulator of one obfuscation run. Each enabled pass plugin
 * installs its own section after it ran; [build] assembles the final
 * [ObfuscationReport]. The fields double as the typed, named cross-pass
 * artifact slots (the flattener consumes the string pass's stub bookkeeping,
 * Pass K rewrites the flattener's prototype records), so passes exchange data
 * through this api-owned surface instead of importing each other.
 */
class ObfuscationReportBuilder {
    var stringEncryption: StringEncryptionResult? = null
    var keyRenaming: KeyRenamingResult? = null
    var constantEncryption: ConstantEncryptionResult? = null
    var jumpIndirection: JumpIndirectionReport? = null
    var junkCode: JunkCodeReport? = null
    var blockCloning: CloneBlocksReport? = null
    var controlFlowFlattening: ControlFlowFlatteningReport? = null
    var tailReturn: TailReturnReport? = null
    var registerObfuscation: RegisterObfuscationReport? = null
    var strippedDebugInfo: Boolean = false

    /** Assembles the immutable report; [modules] are the extension module results. */
    fun build(modules: List<LuaObfuscationModuleResult> = emptyList()): ObfuscationReport =
        ObfuscationReport(
            stringEncryption = stringEncryption,
            keyRenaming = keyRenaming,
            constantEncryption = constantEncryption,
            jumpIndirection = jumpIndirection,
            junkCode = junkCode,
            blockCloning = blockCloning,
            controlFlowFlattening = controlFlowFlattening,
            strippedDebugInfo = strippedDebugInfo,
            tailReturn = tailReturn,
            registerObfuscation = registerObfuscation,
            modules = modules,
        )
}

/**
 * Shared state of one obfuscation run, flowing through the pass plugins (and
 * through the [LuaObfuscationModule] extension hooks, which receive it as
 * their supertype [LuaObfuscationModuleContext]).
 */
class ObfuscationContext(
    chunk: LuaChunk,
    config: ObfuscationConfig,
    masterSeed: Long,
) : LuaObfuscationModuleContext(chunk, config, masterSeed) {

    /** Report sections installed by the passes that have run so far. */
    val reportBuilder: ObfuscationReportBuilder = ObfuscationReportBuilder()
}

/**
 * One obfuscation pass as a plugin. Plugins are independent singletons: they
 * read their own config section from [ObfuscationContext.config], publish
 * their report section into [ObfuscationContext.reportBuilder] and declare
 * ordering constraints by id — never by importing another pass.
 */
interface ObfuscationPassPlugin {
    /** Stable registry key and constraint reference target (e.g. `"strings"`). */
    val id: String

    /**
     * [SeedPolicy.DOMAINS] key used by the seeding stage, or `null` when the
     * pass draws no master-derived seed. Validated at registration time.
     */
    val seedLabel: String? get() = null

    /** Ids of passes that must run before this one. */
    val runsAfter: Set<String> get() = emptySet()

    /** Ids of passes that must run after this one. */
    val runsBefore: Set<String> get() = emptySet()

    /** Whether the pass participates in this run; `false` skips it silently. */
    fun isEnabled(config: ObfuscationConfig): Boolean = true

    /**
     * Called once per run by the seeding stage: installs this pass's
     * domain-separated default seed into its config section (see
     * [withDefaultSeed]). A disabled pass returns [config] unchanged.
     */
    fun stampSeed(config: ObfuscationConfig, masterSeed: Long): ObfuscationConfig = config

    /** Runs the pass over [ObfuscationContext.chunk], in place. */
    fun apply(context: ObfuscationContext)
}
