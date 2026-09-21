// The control flow flattening pass as a registry plugin. It consumes the
// string pass's decoder-stub bookkeeping through the typed report slots of the
// ObfuscationContext — the two feature modules never import each other.
package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException

/**
 * Plugin wrapper of [LuaControlFlowFlattening] (registry id `"cff"`).
 *
 * Flattening rebuilds the whole instruction stream, so it runs after every
 * block-appending pass (jumps -> strings -> registers -> junk -> clones is the
 * resolved order). Running the string layer *after* it made every counter
 * fixture DUMPFAIL (goal round 64), so the constraint set pins it behind them;
 * the remaining `features_55` case is a false-safe save slot (docs 14.45).
 */
object LuaControlFlowFlatteningPass : ObfuscationPassPlugin {
    override val id: String = "cff"

    override val seedLabel: String = "flatten"

    /**
     * `strings`: the decoder stub bookkeeping (typed report slot) decides the
     * strict "never read" save-slot rule and which prototypes stay protected.
     * `clones`: transitively junk / registers / jumps — the flattener must see
     * the final block layout.
     */
    override val runsAfter: Set<String> = setOf("strings", "clones")

    override fun isEnabled(config: ObfuscationConfig): Boolean = config.controlFlowFlattening != null

    override fun stampSeed(config: ObfuscationConfig, masterSeed: Long): ObfuscationConfig = config.copy(
        controlFlowFlattening = config.controlFlowFlattening?.withDefaultSeed(masterSeed, seedLabel),
    )

    override fun apply(context: ObfuscationContext) {
        val config = checkNotNull(context.config.controlFlowFlattening)
        val chunk = context.chunk
        // Cross-pass slot written by the string pass: its stub prototypes use a
        // register that is dead at the flattener's dynamic instructions but live
        // on the stub's own path (goal round 65), and the decoder prototype must
        // never be rebuilt (Round 1).
        val strings = context.reportBuilder.stringEncryption
        val stubs = strings?.stubPrototypes ?: emptySet()
        val decoders = strings?.decoderPrototypeIndex?.takeIf { index -> index >= 0 }
            ?.let { index -> chunk.mainPrototype.nestedPrototypes[index].allPrototypes().toSet() }
            ?: emptySet()
        val report = LuaControlFlowFlattening.apply(chunk, config, stubs, decoders)
        val rejected = report.prototypes.filter { it.status == "INPUT_REJECTED" }
        if (rejected.isNotEmpty()) {
            throw LuaBytecodeFormatException("CFF rejected an invalid input instruction structure: " +
                rejected.joinToString { "${it.prototypeIndex}: ${it.reasons}" })
        }
        context.reportBuilder.controlFlowFlattening = report
    }
}
