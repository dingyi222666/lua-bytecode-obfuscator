// The web public API of the obfuscation pipeline (roadmap step 6 acceptance:
// "the web client only calls the common API"). Everything here is jsMain of
// the web runtime and touches ONLY the common APIs of the library modules —
// bytes come in as base64, a report and bytes go out, mirroring the public
// subset of the JVM frontends' orchestration.
package io.github.dingyi222666.luabytecode.runtime.js

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.edit.clearDebugInfo
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import io.github.dingyi222666.luabytecode.obfuscate.CffConstantExpansion
import io.github.dingyi222666.luabytecode.obfuscate.CffPrototypeReport
import io.github.dingyi222666.luabytecode.obfuscate.ObfuscationConfig
import io.github.dingyi222666.luabytecode.obfuscate.ObfuscationPhase\nimport io.github.dingyi222666.luabytecode.obfuscate.ObfuscationReport
import io.github.dingyi222666.luabytecode.obfuscate.SeedPolicy
import io.github.dingyi222666.luabytecode.obfuscate.LuaBlockCloning
import io.github.dingyi222666.luabytecode.obfuscate.LuaConstantEncryptor
import io.github.dingyi222666.luabytecode.obfuscate.LuaControlFlowFlattening
import io.github.dingyi222666.luabytecode.obfuscate.LuaJunkCode
import io.github.dingyi222666.luabytecode.obfuscate.LuaJumpIndirection
import io.github.dingyi222666.luabytecode.obfuscate.LuaObfuscationModuleContext
import io.github.dingyi222666.luabytecode.obfuscate.LuaObfuscationModuleResult
import io.github.dingyi222666.luabytecode.obfuscate.LuaRegisterObfuscator
import io.github.dingyi222666.luabytecode.obfuscate.LuaStringEncryptor
import io.github.dingyi222666.luabytecode.obfuscate.LuaTailReturn
import io.github.dingyi222666.luabytecode.platform.IdentitySet
import kotlin.io.encoding.Base64

/**
 * Web request surface: a preset name plus the few knobs a web UI needs.
 * Everything else stays at the preset default — the full `ObfuscationConfig`
 * remains a pure common API for JVM-style hosts.
 *
 * @param preset `none` | `light` | `balanced` | `aggressive`
 * @param masterSeed pins the run (SeedPolicy); `null` = fresh entropy per run.
 * @param expectedVersion reject chunks not declaring this version
 *   (`"5.3"`, `"5.4"`, `"5.5"`, optionally prefixed `Lua `); `null` = any.
 * @param stripDebugInfo override the preset's debug stripping flag.
 */
@JsExport
data class WebObfuscationRequestJs(
    val preset: String = "balanced",
    val masterSeed: Long? = null,
    val expectedVersion: String? = null,
    val stripDebugInfo: Boolean? = null,
)

/**
 * Web result: the obfuscated chunk plus the human readable report of what the
 * pipeline did (`reportText` is the same rendering the JVM frontends print).
 */
@JsExport
data class WebObfuscationResultJs(
    val chunkBase64: String,
    val version: String,
    val reportText: String,
    val masterSeed: Long,
)

/**
 * Obfuscates a precompiled Lua chunk (`luac` output, base64 encoded) with the
 * common pipeline composed exactly like the JVM orchestrator's public subset:
 * seed stamping, before/after extension phases, the feature passes in the
 * pipeline's fixed order, tail-return repair and optional debug stripping.
 *
 * The root-only table-field key renaming pass is not part of the web surface.
 *
 * @throws Throwable-shaped errors on JS (`LuaBytecodeFormatException` for
 *   invalid input / CFF refusals, `IllegalArgumentException` for bad
 *   arguments) with descriptive messages.
 */
@JsExport
fun obfuscateLuaChunk(
    inputBase64: String,
    request: WebObfuscationRequestJs = WebObfuscationRequestJs(),
): WebObfuscationResultJs {
    val input = decodeChunkBase64(inputBase64)
    val expected = parseExpectedVersion(request.expectedVersion)
    val chunk = LuaChunkLoader.load(input, expected)
    val config = request.toConfig()
    val master = SeedPolicy.masterSeed(config.masterSeed, chunk)
    val report = obfuscateWebSubset(chunk, config, master)
    return WebObfuscationResultJs(
        chunkBase64 = Base64.encode(LuaChunkWriter.dump(chunk)),
        version = chunk.header.version.displayName,
        reportText = report.toString(),
        masterSeed = master,
    )
}

/** Convenience probe: which version the chunk declares (e.g. `"Lua 5.4"`). */
@JsExport
fun inspectLuaChunk(inputBase64: String): String =
    LuaChunkLoader.load(decodeChunkBase64(inputBase64), null).header.version.displayName

// ------------------------------------------------------------------ plumbing

private fun decodeChunkBase64(inputBase64: String): ByteArray {
    val trimmed = inputBase64.trim()
    require(trimmed.isNotEmpty()) { "empty input: expected a base64 encoded Lua chunk" }
    return try {
        Base64.decode(trimmed)
    } catch (cause: Throwable) {
        throw IllegalArgumentException("input is not valid base64: ${cause.message}")
    }
}

private fun parseExpectedVersion(text: String?): LuaBytecodeVersion? {
    if (text == null) return null
    val digits = text.trim().removePrefix("Lua ").removePrefix("lua ").replace(".", "")
    require(digits.matches(Regex("[0-9A-Fa-f]+"))) { "expectedVersion must look like \"5.4\", got \"$text\"" }
    return LuaBytecodeVersion.fromVersionByte(digits.toInt(16))
        ?: throw IllegalArgumentException(
            "unsupported expectedVersion \"$text\": supported are " +
                LuaBytecodeVersion.supportedVersionsText(),
        )
}

private fun WebObfuscationRequestJs.toConfig(): ObfuscationConfig {
    val preset = when (preset.trim().lowercase()) {
        "none" -> ObfuscationConfig.NONE
        "light" -> ObfuscationConfig.LIGHT
        "balanced", "" -> ObfuscationConfig.BALANCED
        "aggressive" -> ObfuscationConfig.AGGRESSIVE
        else -> throw IllegalArgumentException(
            "unknown preset \"$preset\": none | light | balanced | aggressive",
        )
    }
    return if (stripDebugInfo == null && masterSeed == null) preset else preset.copy(
        masterSeed = masterSeed,
        stripDebugInfo = stripDebugInfo ?: preset.stripDebugInfo,
    )
}

/**
 * The orchestrator: a line-for-line mirror of the JVM frontends'
 * `obfuscate` (root module, `obfuscate/LuaObfuscator.kt`) minus the root-only
 * key renaming pass, so the web pipeline and the JVM pipeline stay in lock
 * step — pass order, seed stamping precedence and the CFF/Pass-K coupling
 * included. Any behavioural change there must be mirrored here.
 */
private fun obfuscateWebSubset(
    chunk: LuaChunk,
    config: ObfuscationConfig,
    master: Long,
): ObfuscationReport {
    // PRED-2/LEAK-5: the master seed is resolved once per run by the caller
    // and stamped into every pass config whose own `seed` field is still at
    // its default.
    val cfg = config.copy(
        stringEncryption = config.stringEncryption?.let {
            if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "strings")) else it
        },
        constantEncryption = config.constantEncryption?.let {
            if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "constants")) else it
        },
        jumpIndirection = config.jumpIndirection?.let {
            if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "jumps")) else it
        },
        junkCode = config.junkCode?.let {
            if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "junk")) else it
        },
        blockCloning = config.blockCloning?.let {
            if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "clones")) else it
        },
        controlFlowFlattening = config.controlFlowFlattening?.let {
            if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "flatten")) else it
        },
        registerObfuscation = config.registerObfuscation?.let {
            if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "registers")) else it
        },
    )
    val moduleContext = LuaObfuscationModuleContext(chunk, cfg, master)
    val moduleResults = ArrayList<LuaObfuscationModuleResult>()
    cfg.modules.filter { it.phase == ObfuscationPhase.BEFORE_CORE }
        .forEach { moduleResults += it.apply(moduleContext) }
    // Order matters: the jump pass runs first so the string stubs it creates
    // afterwards are plain, untrampolined blocks, and junk guards every jump
    // including the stubs' (all three append blocks after a terminator).
    val jumps = cfg.jumpIndirection?.let { LuaJumpIndirection.apply(chunk, it) }
    val strings = cfg.stringEncryption?.let { LuaStringEncryptor.encrypt(chunk, it) }
    // Pass R runs after the string stubs exist and before junk / cloning /
    // flattening (those derive their own register budgets from the layout).
    val registers = cfg.registerObfuscation?.let { LuaRegisterObfuscator.apply(chunk, it) }
    val junk = cfg.junkCode?.let { LuaJunkCode.apply(chunk, it) }
    val clones = cfg.blockCloning?.let { LuaBlockCloning.apply(chunk, it) }
    // Flattening rebuilds the whole instruction stream, so it runs last.
    var flattening = cfg.controlFlowFlattening?.let {
        val decoders = strings?.decoderPrototypeIndex?.takeIf { index -> index >= 0 }
            ?.let { index -> chunk.mainPrototype.nestedPrototypes[index].allPrototypes().toSet() }
            ?: emptySet()
        LuaControlFlowFlattening.apply(chunk, it, strings?.stubPrototypes ?: emptySet(), decoders)
    }
    // Pass K runs AFTER the flattener (see the JVM orchestrator for the two
    // reasons: rematerialised constants must stay encryptable, and the kget
    // decoder must stay out of the flattener's view entirely).
    if (flattening?.prototypes?.any { it.status == "INPUT_REJECTED" } == true) {
        throw LuaBytecodeFormatException(
            "CFF rejected an invalid input instruction structure: " +
                flattening.prototypes.filter { it.status == "INPUT_REJECTED" }
                    .joinToString { "${it.prototypeIndex}: ${it.reasons}" },
        )
    }
    val constants = cfg.constantEncryption?.let { constantConfig ->
        val protected = IdentitySet<LuaPrototype>()
        val beforeConstants = chunk.prototypes
        var inlineConstants = 0
        var inlinePrototypes = 0
        var inlineSites = 0
        val updatedRecords = ArrayList<CffPrototypeReport>()
        for (record in flattening?.prototypes.orEmpty()) {
            val proof = record.proof
            if (proof == null) {
                updatedRecords += record
                continue
            }
            val prototype = beforeConstants[record.prototypeIndex]
            protected += prototype
            val expansion = CffConstantExpansion.apply(prototype, proof, constantConfig)
            if (expansion != null) {
                inlineConstants += expansion.constants
                inlineSites += expansion.sites
                inlinePrototypes++
                updatedRecords += record.copy(
                    proof = expansion.proof,
                    verifiedControlPaths = expansion.paths,
                    verificationSteps = expansion.steps,
                )
            } else {
                updatedRecords += record
            }
        }
        flattening = flattening?.copy(prototypes = updatedRecords)
        // The legacy decoder uses CALL and cannot enter a live control packet.
        // Flattened prototypes use only the verified local expansion above.
        LuaConstantEncryptor.encrypt(chunk, constantConfig, protected).let {
            if (it.decoderPrototypeIndex >= 0) {
                val at = if (chunk.mainPrototype.code.first().opcodeName == "VARARGPREP") 1 else 0
                fun moved(pc: Int) = if (pc < at) pc else pc + 2
                flattening = flattening?.copy(
                    prototypes = flattening!!.prototypes.map { record ->
                        val proof = record.proof
                        if (record.prototypeIndex != 0 || proof == null) {
                            record
                        } else {
                            record.copy(
                                proof = proof.copy(
                                    controlEntries = proof.controlEntries
                                        .map { (pc, target) -> moved(pc) to target },
                                    destinationPcs = proof.destinationPcs.map(::moved),
                                ),
                            )
                        }
                    },
                )
            }
            it.copy(
                encryptedConstants = it.encryptedConstants + inlineConstants,
                rewrittenSites = it.rewrittenSites + inlineSites,
                patchedPrototypes = it.patchedPrototypes + inlinePrototypes,
            )
        }
    }
    // Every pass has run; some block layouts end in a `JMP` dispatcher back
    // edge — the appended unreachable RETURN makes the chunk acceptable to
    // official tooling again (unluac's "ends with RETURN" invariant).
    val tailReturn = LuaTailReturn.apply(chunk)
    if (config.stripDebugInfo) {
        chunk.clearDebugInfo()
        // clearDebugInfo drops the synthetic upvalue names LuaTailReturn just
        // installed; re-run the naming half so stripped chunks stay parseable.
        LuaTailReturn.nameUpvalues(chunk)
    }
    cfg.modules.filter { it.phase == ObfuscationPhase.AFTER_CORE }
        .forEach { moduleResults += it.apply(moduleContext) }
    return ObfuscationReport(
        stringEncryption = strings,
        constantEncryption = constants,
        jumpIndirection = jumps,
        junkCode = junk,
        blockCloning = clones,
        controlFlowFlattening = flattening,
        strippedDebugInfo = config.stripDebugInfo,
        tailReturn = tailReturn,
        registerObfuscation = registers,
        modules = moduleResults,
    )
}
