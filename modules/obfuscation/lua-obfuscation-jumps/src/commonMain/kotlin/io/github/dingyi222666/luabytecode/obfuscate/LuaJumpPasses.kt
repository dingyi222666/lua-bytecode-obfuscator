// The jump-family passes (jump indirection, junk code, block cloning) plus the
// unconditional tail-return repair as registry plugins. Ordering is declared
// by id: jumps first (string stubs created afterwards stay plain, untrampolined
// blocks), registers -> junk -> clones in front of the flattener (they all
// append blocks after a terminator; the flattener rebuilds the whole stream
// and has to see the final block layout).
package io.github.dingyi222666.luabytecode.obfuscate

/** Plugin wrapper of [LuaJumpIndirection] (registry id `"jumps"`). */
object LuaJumpIndirectionPass : ObfuscationPassPlugin {
    override val id: String = "jumps"

    override val seedLabel: String = "jumps"

    /** No constraint: the trampolines go in first, every later stub stays plain. */
    override val runsAfter: Set<String> = emptySet()

    override fun isEnabled(config: ObfuscationConfig): Boolean = config.jumpIndirection != null

    override fun stampSeed(config: ObfuscationConfig, masterSeed: Long): ObfuscationConfig = config.copy(
        jumpIndirection = config.jumpIndirection?.withDefaultSeed(masterSeed, seedLabel),
    )

    override fun apply(context: ObfuscationContext) {
        val config = checkNotNull(context.config.jumpIndirection)
        context.reportBuilder.jumpIndirection = LuaJumpIndirection.apply(context.chunk, config)
    }
}

/**
 * Plugin wrapper of [LuaJunkCode] (registry id `"junk"`).
 *
 * Runs after the string stubs exist so their jumps are guarded too (all three
 * jump-family passes append blocks after a terminator), and after Pass R so
 * the junk derives its register budget from the post-R layout.
 */
object LuaJunkCodePass : ObfuscationPassPlugin {
    override val id: String = "junk"

    override val seedLabel: String = "junk"

    override val runsAfter: Set<String> = setOf("registers")

    override fun isEnabled(config: ObfuscationConfig): Boolean = config.junkCode != null

    override fun stampSeed(config: ObfuscationConfig, masterSeed: Long): ObfuscationConfig = config.copy(
        junkCode = config.junkCode?.withDefaultSeed(masterSeed, seedLabel),
    )

    override fun apply(context: ObfuscationContext) {
        val config = checkNotNull(context.config.junkCode)
        context.reportBuilder.junkCode = LuaJunkCode.apply(context.chunk, config)
    }
}

/** Plugin wrapper of [LuaBlockCloning] (registry id `"clones"`). */
object LuaBlockCloningPass : ObfuscationPassPlugin {
    override val id: String = "clones"

    override val seedLabel: String = "clones"

    override val runsAfter: Set<String> = setOf("junk")

    override fun isEnabled(config: ObfuscationConfig): Boolean = config.blockCloning != null

    override fun stampSeed(config: ObfuscationConfig, masterSeed: Long): ObfuscationConfig = config.copy(
        blockCloning = config.blockCloning?.withDefaultSeed(masterSeed, seedLabel),
    )

    override fun apply(context: ObfuscationContext) {
        val config = checkNotNull(context.config.blockCloning)
        context.reportBuilder.blockCloning = LuaBlockCloning.apply(context.chunk, config)
    }
}

/**
 * Plugin wrapper of [LuaTailReturn] (registry id `"tail-return"`).
 *
 * Always enabled (it is an unconditional syntax repair, not a configurable
 * pass): some flattener / junk block layouts end in a `JMP` (the dispatcher
 * back edge), which is valid bytecode but breaks the "every prototype ends
 * with a RETURN" invariant unluac validates. The appended RETURN is
 * unreachable (the previous instruction is a redirect), so this cannot change
 * semantics — it only makes the chunk acceptable to official tooling again.
 */
object LuaTailReturnPass : ObfuscationPassPlugin {
    override val id: String = "tail-return"

    /** After the last layout-changing pass; the debug strip runs even later. */
    override val runsAfter: Set<String> = setOf("keys")

    override fun apply(context: ObfuscationContext) {
        context.reportBuilder.tailReturn = LuaTailReturn.apply(context.chunk)
    }
}
