package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.platform.Platform
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype

/**
 * PRED-2/LEAK-5 (plan A, master-approved): the obfuscated output must not be a
 * deterministic function of the input. One master seed drives every pass, so a
 * single run produces a single coherent artifact:
 *
 *  * `null`  : per-run randomness (SecureRandom, OS entropy). This is the
 *              DEFAULT — release builds emit a unique artifact every run, which
 *              is what kills input->output rainbow tables (LEAK-5).
 *  * `0L`    : legacy content derivation (the pre-PRED-2 behaviour, FNV over
 *              the chunk). Byte-sensitive tools and regression comparisons pass
 *              `--seed 0` to pin it.
 *  * other   : a pinned seed. `--seed 42` twice => byte-identical artifacts
 *              (the reproducibility half of the acceptance test).
 *
 * Per-pass seeds are DOMAIN SEPARATED (VM-3 lesson: two streams sharing a seed
 * collapse into one keystream). Every pass id mixes a distinct constant into the
 * master seed, so no two passes ever draw the same stream, and a pass's stream
 * does not shift when another pass's configuration changes.
 */
object SeedPolicy {
    /** Per-pass domain constants. Never reuse an id for a new pass. */
    val DOMAINS: Map<String, Long> = mapOf(
        "strings" to 0x5EED0001L,
        "constants" to 0x5EED0002L,
        "jumps" to 0x5EED0003L,
        "junk" to 0x5EED0004L,
        "clones" to 0x5EED0005L,
        "flatten" to 0x5EED0006L,
        "registers" to 0x5EED0007L,
        "vm" to 0x5EED0008L,
    )

    /**
     * The master seed for one obfuscation run.
     * `null` (unset) => fresh OS entropy; `0` => legacy content derivation;
     * anything else is used verbatim.
     */
    fun masterSeed(explicit: Long?, chunk: LuaChunk): Long = when (explicit) {
        null -> Platform.entropy.nextLong()
        0L -> legacyContentSeed(chunk)
        else -> explicit
    }

    /** Domain-separated pass seed: `masterSeed xor DOMAIN[passId]`. */
    fun passSeed(masterSeed: Long, passId: String): Long =
        masterSeed xor (DOMAINS[passId]
            ?: throw IllegalArgumentException("unknown seed domain $passId"))

    /**
     * The legacy derivation (the old `contentSeed` / `seedFor` shape): FNV-style
     * hash over the chunk's shape and constants. Kept byte-compatible as the
     * `--seed 0` mode so regression comparisons keep working.
     */
    fun legacyContentSeed(chunk: LuaChunk): Long {
        var hash = 1125899906842597L
        hash = hash * 1099511628211L + chunk.prototypes.size
        for (prototype in chunk.prototypes) {
            hash = hash * 31 + prototype.constantCount
            hash = hash * 31 + prototype.instructionCount
            for (constant in prototype.constants) {
                hash = hash * 31 + when (constant) {
                    is io.github.dingyi222666.luabytecode.bytecode.LuaConstant.Str ->
                        constant.value.bytes.contentHashCode().toLong()
                    is io.github.dingyi222666.luabytecode.bytecode.LuaConstant.Integer ->
                        constant.value
                    is io.github.dingyi222666.luabytecode.bytecode.LuaConstant.Number ->
                        constant.value.toRawBits()
                    is io.github.dingyi222666.luabytecode.bytecode.LuaConstant.Boolean ->
                        if (constant.value) 1L else 0L
                    io.github.dingyi222666.luabytecode.bytecode.LuaConstant.Nil -> 17L
                }
            }
        }
        return hash
    }
}

/**
 * Deterministic per-prototype seed derived from the chunk content when a pass
 * config's [seed] is `0` (the historical behaviour, kept byte-compatible):
 * FNV-style hash over the prototype shape and constants, mixed with the
 * explicit seed when one is given.
 *
 * Promoted to :lua-obfuscation-api (public): shared by the jumps feature
 * (`LuaJumpIndirection` / `LuaJunkCode`) and the CFF feature
 * (`LuaControlFlowFlattening`).
 */
fun seedFor(seed: Long, chunk: LuaChunk, prototype: LuaPrototype): Long {
    if (seed != 0L) {
        return seed + prototype.constantCount * 31L + prototype.instructionCount
    }
    var hash = 1469598103934665603L
    hash = hash * 1099511628211L + chunk.prototypes.size
    hash = hash * 1099511628211L + prototype.constantCount
    hash = hash * 1099511628211L + prototype.instructionCount
    for (constant in prototype.constants) {
        hash = hash * 1099511628211L + when (constant) {
            is LuaConstant.Str -> constant.value.bytes.contentHashCode().toLong()
            is LuaConstant.Integer -> constant.value
            is LuaConstant.Number -> constant.value.toRawBits()
            is LuaConstant.Boolean -> if (constant.value) 1L else 0L
            LuaConstant.Nil -> 17L
        }
    }
    return hash
}
