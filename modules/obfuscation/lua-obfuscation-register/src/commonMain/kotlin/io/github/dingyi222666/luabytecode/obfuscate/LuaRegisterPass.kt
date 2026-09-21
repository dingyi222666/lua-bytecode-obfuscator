// The register / local obfuscation pass (Pass R) as a registry plugin.
package io.github.dingyi222666.luabytecode.obfuscate

/**
 * Plugin wrapper of [LuaRegisterObfuscator] (registry id `"registers"`).
 *
 * Pass R runs after the string stubs exist (they are plain instructions the
 * register permutation has to see) and before junk / cloning / flattening
 * (those derive their own register budgets from the post-R layout).
 */
object LuaRegisterObfuscationPass : ObfuscationPassPlugin {
    override val id: String = "registers"

    override val seedLabel: String = "registers"

    override val runsAfter: Set<String> = setOf("strings")

    override fun isEnabled(config: ObfuscationConfig): Boolean = config.registerObfuscation != null

    override fun stampSeed(config: ObfuscationConfig, masterSeed: Long): ObfuscationConfig = config.copy(
        registerObfuscation = config.registerObfuscation?.withDefaultSeed(masterSeed, seedLabel),
    )

    override fun apply(context: ObfuscationContext) {
        val config = checkNotNull(context.config.registerObfuscation)
        context.reportBuilder.registerObfuscation = LuaRegisterObfuscator.apply(context.chunk, config)
    }
}
