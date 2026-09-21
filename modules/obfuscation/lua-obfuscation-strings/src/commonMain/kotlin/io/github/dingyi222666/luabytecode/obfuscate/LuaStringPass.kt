// The string encryption pass as a registry plugin. Plugins know nothing about
// each other: ordering is declared by id (runsAfter) and the artifact other
// passes consume (the decoder stub bookkeeping) travels through the typed
// report slots of the ObfuscationContext, not through a direct reference.
package io.github.dingyi222666.luabytecode.obfuscate

/**
 * Plugin wrapper of [LuaStringEncryptor] (registry id `"strings"`).
 *
 * The report section it installs doubles as a cross-pass artifact slot: the
 * flattening pass plugin (`:lua-obfuscation-cff`) reads
 * [StringEncryptionResult.stubPrototypes] / [StringEncryptionResult.decoderPrototypeIndex]
 * from the run's report builder to keep the decoder stubs out of its block
 * rewriting — without this module and that module importing each other.
 */
object LuaStringEncryptionPass : ObfuscationPassPlugin {
    override val id: String = "strings"

    override val seedLabel: String = "strings"

    /** The jump pass runs first so the stubs created here are plain, untrampolined blocks. */
    override val runsAfter: Set<String> = setOf("jumps")

    override fun isEnabled(config: ObfuscationConfig): Boolean = config.stringEncryption != null

    override fun stampSeed(config: ObfuscationConfig, masterSeed: Long): ObfuscationConfig = config.copy(
        stringEncryption = config.stringEncryption?.withDefaultSeed(masterSeed, seedLabel),
    )

    override fun apply(context: ObfuscationContext) {
        val config = checkNotNull(context.config.stringEncryption)
        context.reportBuilder.stringEncryption = LuaStringEncryptor.encrypt(context.chunk, config)
    }
}
