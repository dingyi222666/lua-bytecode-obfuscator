// The web public API of the obfuscation pipeline (roadmap step 6 acceptance:
// "the web client only calls the common API"). Everything here is jsMain of
// the web runtime and touches ONLY the common APIs of the library modules —
// bytes come in as base64, a report and bytes go out.
//
// Since the plugin-based orchestration (ObfuscationRegistry) the web runtime
// no longer mirrors the pass sequence: it assembles the same built-in plugin
// set as the JVM and native hosts and lets :lua-obfuscation-pipeline run it.
// The single orchestrator keeps the three hosts byte-compatible.
package io.github.dingyi222666.luabytecode.runtime.js

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import io.github.dingyi222666.luabytecode.obfuscate.LuaObfuscator
import io.github.dingyi222666.luabytecode.obfuscate.ObfuscationConfig
import io.github.dingyi222666.luabytecode.obfuscate.ObfuscationRegistry
import io.github.dingyi222666.luabytecode.obfuscate.SeedPolicy
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
 * common pipeline: the built-in pass plugins run in the registry's resolved
 * order (jumps → strings → registers → junk → clones → cff → constants →
 * keys → tail-return → debug-strip), the string / constant decoder assets
 * arrive through the [JsPlatform] seam.
 *
 * The presets never set key renaming, so the pass stays off here — same
 * output as before the plugin refactor (which needed a hand-maintained
 * orchestrator mirror; the mirror is gone).
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
    // The master seed is resolved ONCE here so the result can echo it; pinning
    // it into the config makes the pipeline use exactly this value (a non-null
    // config master seed is taken verbatim — no second entropy draw), which
    // keeps per-run-entropy and `--seed 0` semantics identical to the JVM CLI.
    val master = SeedPolicy.masterSeed(config.masterSeed, chunk)
    // Composition root of the web runtime: the full built-in plugin set; the
    // pipeline skips every pass whose config section is null.
    val report = LuaObfuscator.obfuscate(chunk, config.copy(masterSeed = master), ObfuscationRegistry.builtIn())
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
