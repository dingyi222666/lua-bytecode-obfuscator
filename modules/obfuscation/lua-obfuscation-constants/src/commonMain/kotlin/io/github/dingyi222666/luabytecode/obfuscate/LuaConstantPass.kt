// The constant pool encryption pass (Pass K) as a registry plugin. It reads
// the flattener's report records (a typed api slot) for the verified local
// constant expansion, then encrypts whatever still survives as a live LOADK.
package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.platform.IdentitySet
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype

/**
 * Plugin wrapper of [LuaConstantEncryptor] (registry id `"constants"`).
 *
 * Pass K runs AFTER the flattener: (a) the flattener's rematerialisation
 * re-derives integer constants at run time and LEAVES the plaintext in the
 * pool — running K last lets it encrypt whatever still survives as a live
 * `LOADK` site; (b) the kget decoder prototype (and its stub blocks) are
 * appended to the final layout, so no earlier pass can rebuild them. Round 1
 * lesson: when the decoder existed DURING flattening, its rebuilt body decoded
 * garbage — keeping the decoder out of the flattener's view entirely is the
 * robust fix.
 */
object LuaConstantEncryptionPass : ObfuscationPassPlugin {
    override val id: String = "constants"

    override val seedLabel: String = "constants"

    override val runsAfter: Set<String> = setOf("cff")

    override fun isEnabled(config: ObfuscationConfig): Boolean = config.constantEncryption != null

    override fun stampSeed(config: ObfuscationConfig, masterSeed: Long): ObfuscationConfig = config.copy(
        constantEncryption = config.constantEncryption?.withDefaultSeed(masterSeed, seedLabel),
    )

    override fun apply(context: ObfuscationContext) {
        val chunk = context.chunk
        val constantConfig = checkNotNull(context.config.constantEncryption)
        // Cross-pass slot: the flattening report records (empty when CFF is off).
        // Prototypes with a proof get the verified local expansion instead of the
        // global encryption, and their (possibly re-anchored) proofs are written
        // back into the report for the pipeline to return.
        val flattening = context.reportBuilder.controlFlowFlattening
        val protected = IdentitySet<LuaPrototype>()
        val beforeConstants = chunk.prototypes
        var inlineConstants = 0
        var inlinePrototypes = 0
        var inlineSites = 0
        val updatedRecords = ArrayList<CffPrototypeReport>()
        for (record in flattening?.prototypes.orEmpty()) {
            val proof = record.proof
            if (proof == null) { updatedRecords += record; continue }
            val prototype = beforeConstants[record.prototypeIndex]
            protected += prototype
            val expansion = CffConstantExpansion.apply(prototype, proof, constantConfig)
            if (expansion != null) {
                inlineConstants += expansion.constants
                inlineSites += expansion.sites
                inlinePrototypes++
                updatedRecords += record.copy(proof = expansion.proof,
                    verifiedControlPaths = expansion.paths, verificationSteps = expansion.steps)
            } else updatedRecords += record
        }
        var updatedFlattening = flattening?.copy(prototypes = updatedRecords)
        // The legacy decoder uses CALL and cannot enter a live control packet.
        // Flattened prototypes use only the verified local expansion above.
        val result = LuaConstantEncryptor.encrypt(chunk, constantConfig, protected)
        if (result.decoderPrototypeIndex >= 0) {
            val flattened = updatedFlattening
            if (flattened != null) {
                val at = if (chunk.mainPrototype.code.first().opcodeName == "VARARGPREP") 1 else 0
                fun moved(pc: Int) = if (pc < at) pc else pc + 2
                updatedFlattening = flattened.copy(prototypes = flattened.prototypes.map { record ->
                    val proof = record.proof
                    if (record.prototypeIndex != 0 || proof == null) record else record.copy(proof = proof.copy(
                        controlEntries = proof.controlEntries.map { (pc, target) -> moved(pc) to target },
                        destinationPcs = proof.destinationPcs.map(::moved)))
                })
            }
        }
        context.reportBuilder.controlFlowFlattening = updatedFlattening
        context.reportBuilder.constantEncryption = result.copy(
            encryptedConstants = result.encryptedConstants + inlineConstants,
            rewrittenSites = result.rewrittenSites + inlineSites,
            patchedPrototypes = result.patchedPrototypes + inlinePrototypes)
    }
}
