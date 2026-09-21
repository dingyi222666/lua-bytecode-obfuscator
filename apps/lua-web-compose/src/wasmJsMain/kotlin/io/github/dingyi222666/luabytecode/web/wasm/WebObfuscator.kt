// The wasmJs public API of the obfuscation pipeline (Compose Web 改造
// Round 3) — the counterpart of `runtime.js.WebObfuscator`, composed ONLY of
// the common APIs of the library modules. The single orchestrator
// (LuaObfuscator + ObfuscationRegistry.builtIn()) keeps the four hosts
// byte-compatible.
//
// The main path is plain Kotlin called from the Compose UI on the same
// (single) wasm thread, so it needs NO @JsExport and keeps natural Kotlin
// types (Long seed). A flat @JsExport top-level shape (String in / String
// out) is appended for a future worker host — see obfuscateLuaChunkJson.
package io.github.dingyi222666.luabytecode.web.wasm

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import io.github.dingyi222666.luabytecode.obfuscate.LuaObfuscator
import io.github.dingyi222666.luabytecode.obfuscate.ObfuscationConfig
import io.github.dingyi222666.luabytecode.obfuscate.ObfuscationRegistry
import io.github.dingyi222666.luabytecode.obfuscate.SeedPolicy
import kotlin.io.encoding.Base64

/**
 * Web request surface: a preset name plus the knobs the Compose UI exposes.
 * Everything else stays at the preset default — the full `ObfuscationConfig`
 * remains a pure common API for JVM-style hosts.
 *
 * @param preset `none` | `light` | `balanced` | `aggressive`
 * @param masterSeed pins the run (SeedPolicy); `null` = fresh entropy per run.
 * @param expectedVersion reject chunks not declaring this version
 *   (`"5.3"`, `"5.4"`, `"5.5"`, optionally prefixed `Lua `); `null` = any.
 * @param disableStrings / disableConstants / disableFlatten / disableJunk /
 *   disableDebugStrip force the named preset feature off (granular overrides
 *   matching the CLI flag families; merging over the preset, like the CLI).
 */
data class WebObfuscationRequest(
    val preset: String = "balanced",
    val masterSeed: Long? = null,
    val expectedVersion: String? = null,
    val disableStrings: Boolean = false,
    val disableConstants: Boolean = false,
    val disableFlatten: Boolean = false,
    val disableJunk: Boolean = false,
    val disableDebugStrip: Boolean = false,
)

/**
 * Web result: the obfuscated chunk plus the human readable report of what the
 * pipeline did (`reportText` is the same rendering the JVM frontends print).
 */
data class WebObfuscationResult(
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
 * arrive through the [WasmPlatform] seam.
 *
 * The presets never set key renaming, so the pass stays off here — same
 * behavior as the js / JVM hosts.
 *
 * @throws `LuaBytecodeFormatException` for invalid input / CFF refusals,
 *   `IllegalArgumentException` for bad arguments (seed, preset, version).
 */
fun obfuscateLuaChunk(
    inputBase64: String,
    request: WebObfuscationRequest = WebObfuscationRequest(),
): WebObfuscationResult {
    val input = decodeChunkBase64(inputBase64)
    val expected = parseExpectedVersion(request.expectedVersion)
    val chunk = LuaChunkLoader.load(input, expected)
    val config = request.toConfig()
    // The master seed is resolved ONCE here so the result can echo it; pinning
    // it into the config makes the pipeline use exactly this value (a non-null
    // config master seed is taken verbatim — no second entropy draw), which
    // keeps per-run-entropy and `--seed 0` semantics identical to the CLI.
    val master = SeedPolicy.masterSeed(config.masterSeed, chunk)
    // Composition root of the wasm host: the full built-in plugin set; the
    // pipeline skips every pass whose config section is null.
    val report = LuaObfuscator.obfuscate(chunk, config.copy(masterSeed = master), ObfuscationRegistry.builtIn())
    return WebObfuscationResult(
        chunkBase64 = Base64.encode(LuaChunkWriter.dump(chunk)),
        version = chunk.header.version.displayName,
        reportText = report.toString(),
        masterSeed = master,
    )
}

/** Convenience probe: which version the chunk declares (e.g. `"Lua 5.4"`). */
fun inspectLuaChunk(inputBase64: String): String =
    LuaChunkLoader.load(decodeChunkBase64(inputBase64), null).header.version.displayName

// ------------------------------------------------------ flat @JsExport shape
//
// Written for a FUTURE worker host (off the UI thread); the Compose UI does
// not use it. All parameters and the result are boundary types: the pinned
// seed travels as a decimal STRING because @JsExport exposes kotlin.Long to
// JS as BigInt, which a handwritten worker would have to unwrap; the result
// is one JSON object string so no custom class needs to cross the boundary.

/**
 * Flat worker shape of [obfuscateLuaChunk]: String in, JSON String out
 * (`{ chunkBase64, version, reportText, masterSeed }`). A blank [seed] means
 * fresh entropy; a non-blank seed must be a 64-bit decimal integer.
 */
@JsExport
fun obfuscateLuaChunkJson(
    inputBase64: String,
    preset: String,
    seed: String?,
    expectedVersion: String?,
    disableStrings: Boolean,
    disableConstants: Boolean,
    disableFlatten: Boolean,
    disableJunk: Boolean,
    disableDebugStrip: Boolean,
): String {
    val trimmedSeed = seed?.trim()
    val request = WebObfuscationRequest(
        preset = preset,
        masterSeed = when {
            trimmedSeed.isNullOrEmpty() -> null
            else -> trimmedSeed.toLongOrNull()
                ?: throw IllegalArgumentException("seed \"$trimmedSeed\" is not a 64-bit decimal integer")
        },
        expectedVersion = expectedVersion,
        disableStrings = disableStrings,
        disableConstants = disableConstants,
        disableFlatten = disableFlatten,
        disableJunk = disableJunk,
        disableDebugStrip = disableDebugStrip,
    )
    val result = obfuscateLuaChunk(inputBase64, request)
    return buildString {
        append('{')
        append("\"chunkBase64\":").append(jsonString(result.chunkBase64)).append(',')
        append("\"version\":").append(jsonString(result.version)).append(',')
        append("\"reportText\":").append(jsonString(result.reportText)).append(',')
        append("\"masterSeed\":").append(result.masterSeed)
        append('}')
    }
}

private fun jsonString(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else ->
                if (ch < ' ') {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
        }
    }
    append('"')
}

// ------------------------------------------------------------------ plumbing
// Line-by-line port of the js runtime's private plumbing.

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

private fun WebObfuscationRequest.toConfig(): ObfuscationConfig {
    val preset = when (preset.trim().lowercase()) {
        "none" -> ObfuscationConfig.NONE
        "light" -> ObfuscationConfig.LIGHT
        "balanced", "" -> ObfuscationConfig.BALANCED
        "aggressive" -> ObfuscationConfig.AGGRESSIVE
        else -> throw IllegalArgumentException(
            "unknown preset \"$preset\": none | light | balanced | aggressive",
        )
    }
    // Granular overrides merge over the preset exactly like the CLI flags do:
    // a forced-off feature is the null (disabled) section.
    var config = preset
    if (disableStrings) config = config.copy(stringEncryption = null)
    if (disableConstants) config = config.copy(constantEncryption = null)
    if (disableFlatten) config = config.copy(controlFlowFlattening = null)
    if (disableJunk) config = config.copy(junkCode = null)
    if (disableDebugStrip) config = config.copy(stripDebugInfo = false)
    return config.copy(masterSeed = masterSeed)
}
