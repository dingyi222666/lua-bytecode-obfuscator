package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaChunk

/**
 * Runs the configured passes over a chunk, in place.
 *
 * The orchestrator owns NO hardcoded pass sequence: it asks the
 * [ObfuscationRegistry] for the resolved constraint order and executes it.
 * The built-in passes and their ordering declarations live in their feature
 * modules (`LuaStringPass` / `LuaConstantPass` / `LuaJumpPasses` /
 * `LuaCffPass` / `LuaRegisterPass` and the pipeline-level `LuaCorePasses`);
 * the resolved order for the default set is
 * jumps → strings → registers → junk → clones → cff → constants → keys →
 * tail-return → debug-strip (each plugin documents WHY it sits where it
 * sits). A pass whose config section is null is skipped by its own
 * [ObfuscationPassPlugin.isEnabled], which keeps the historical
 * "null disables the pass" semantics byte-compatible.
 */
object LuaObfuscator {

    fun obfuscate(
        chunk: LuaChunk,
        config: ObfuscationConfig = ObfuscationConfig.BALANCED,
        registry: ObfuscationRegistry = ObfuscationRegistry.builtIn(),
    ): ObfuscationReport {
        // PRED-2/LEAK-5: resolve the master seed once per run. `null` master =>
        // per-run SecureRandom; `0` => legacy content derivation (SeedPolicy
        // docs); anything else pins the run.
        val master = SeedPolicy.masterSeed(config.masterSeed, chunk)
        val passes = registry.resolve()
        // Unified seeding stage: every registered plugin stamps its own config
        // section with its domain-separated default seed (SeedPolicy.passSeed);
        // an explicit per-pass seed keeps its granular-override precedence.
        var stamped = config
        for (plugin in passes) stamped = plugin.stampSeed(stamped, master)
        val cfg = stamped
        val context = ObfuscationContext(chunk, cfg, master)
        val moduleResults = ArrayList<LuaObfuscationModuleResult>()
        cfg.modules.filter { it.phase == ObfuscationPhase.BEFORE_CORE }
            .forEach { moduleResults += it.apply(context) }
        for (plugin in passes) {
            if (plugin.isEnabled(cfg)) plugin.apply(context)
        }
        cfg.modules.filter { it.phase == ObfuscationPhase.AFTER_CORE }
            .forEach { moduleResults += it.apply(context) }
        return context.reportBuilder.build(moduleResults)
    }
}
