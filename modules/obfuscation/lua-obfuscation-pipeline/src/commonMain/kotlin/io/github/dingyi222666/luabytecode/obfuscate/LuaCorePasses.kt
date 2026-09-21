// Pass plugins that live in the assembly layer rather than a feature module:
// key renaming sits beside its pass implementation here (LuaKeyRenaming.kt),
// and the debug strip is an epilogue composed from the edit DSL plus the jumps
// feature's upvalue naming half — above both, below no one.
package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.edit.clearDebugInfo

/**
 * Plugin wrapper of [LuaKeyRenaming] (registry id `"keys"`).
 *
 * LEAK-1 key half: runs AFTER Pass K (both see the final layout; keys are a
 * shared-reference surface and never enter K's encryption set — the two passes
 * are orthogonal by construction).
 *
 * Deliberately NO [ObfuscationPassPlugin.seedLabel]: the pass is not stamped
 * with the run master seed (it never was — `seed == 0` keeps the pass's own
 * legacy content derivation inside [LuaKeyRenaming.apply]), so the stamping
 * stage leaves its section untouched.
 */
object LuaKeyRenamingPass : ObfuscationPassPlugin {
    override val id: String = "keys"

    override val runsAfter: Set<String> = setOf("constants")

    override fun isEnabled(config: ObfuscationConfig): Boolean = config.keyRenaming != null

    override fun apply(context: ObfuscationContext) {
        val config = checkNotNull(context.config.keyRenaming)
        context.reportBuilder.keyRenaming = LuaKeyRenaming.apply(context.chunk, config)
    }
}

/**
 * Debug strip epilogue (registry id `"debug-strip"`): drops line info, locals,
 * upvalue names and sources after every pass has run. `clearDebugInfo` drops
 * the synthetic upvalue names [LuaTailReturn] just installed, so the naming
 * half is re-run after the strip — stripped chunks stay parseable by unluac
 * (names are debug metadata, bytecode semantics equal).
 */
object LuaDebugStripPass : ObfuscationPassPlugin {
    override val id: String = "debug-strip"

    /** The appended RETURN of the tail repair must survive the strip re-naming. */
    override val runsAfter: Set<String> = setOf("tail-return")

    override fun isEnabled(config: ObfuscationConfig): Boolean = config.stripDebugInfo

    override fun apply(context: ObfuscationContext) {
        val chunk = context.chunk
        chunk.clearDebugInfo()
        LuaTailReturn.nameUpvalues(chunk)
        context.reportBuilder.strippedDebugInfo = true
    }
}
