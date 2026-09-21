package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.obfuscate.ControlFlowFlatteningConfig
import io.github.dingyi222666.luabytecode.obfuscate.LuaControlFlowFlattening
import io.github.dingyi222666.luabytecode.obfuscate.LuaObfuscator
import io.github.dingyi222666.luabytecode.obfuscate.ObfuscationConfig
import io.github.dingyi222666.luabytecode.platform.ObfRandom

/**
 * Orchestrator-side wiring for the `:lua-vm` feature module (roadmap step 5).
 *
 * The VM module depends on the obfuscation contracts plus the bytecode layers —
 * on NO other feature module and NOT on this module. The input-staging executors
 * (the full library pipeline, the CFF pass) therefore travel into the VM options
 * as stage functions instead of direct calls: this object is the only place that
 * binds the real passes to the VM entry points.
 *
 * Keep this file in the root/orchestrator module: it is the composition root,
 * exactly like `LuaObfuscator` itself.
 */
object LuaVmPipeline {

    /**
     * Stage that runs the library obfuscation pipeline over the VM's private working
     * copy. The VM seed is stamped as the pipeline's master seed, mirroring the
     * previous in-module behaviour byte for byte.
     */
    fun inputObfuscation(config: ObfuscationConfig): LuaVmObfuscation.InputObfuscationStage =
        LuaVmObfuscation.InputObfuscationStage { working, seed ->
            LuaObfuscator.obfuscate(working, config.copy(masterSeed = seed))
        }

    /** Stage that applies source CFF over the VM's private working copy. */
    fun sourceCff(config: ControlFlowFlatteningConfig): LuaVmObfuscation.SourceCffStage =
        LuaVmObfuscation.SourceCffStage { working -> LuaControlFlowFlattening.apply(working, config) }

    /**
     * Strict VM entry point: the input representation must pass source CFF before
     * lifting (the former `LuaVmObfuscation.obfuscated` default: full AGGRESSIVE
     * input staging, no separate source CFF stage).
     */
    fun obfuscated(
        chunk: LuaChunk,
        random: ObfRandom,
        options: LuaVmObfuscation.Options = LuaVmObfuscation.Options(
            inputObfuscation = inputObfuscation(ObfuscationConfig.AGGRESSIVE),
            sourceCff = null,
            requireSourceCff = false,
        ),
    ): LuaVmObfuscation.Emission? = LuaVmObfuscation.emitModuleDetailed(chunk, random, options)
}
