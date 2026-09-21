package io.github.dingyi222666.luabytecode.web.wasm

import io.github.dingyi222666.luabytecode.platform.BundledChunkSource
import io.github.dingyi222666.luabytecode.platform.DebugLog
import io.github.dingyi222666.luabytecode.platform.Platform
import io.github.dingyi222666.luabytecode.platform.SecureEntropy
import io.github.dingyi222666.luabytecode.platform.TraceSink

/**
 * The wasmJs counterpart of `runtime.js.JsPlatform` (Compose Web 改造
 * Round 3). Same placement rule as the js / native seams: it lives in the
 * host module — not in :lua-platform — because the bundled decoder assets
 * belong to the feature modules and :lua-platform sits BELOW them in the
 * dependency graph.
 *
 * Call [install] once before running the pipeline (done in `Main.main`):
 *
 * * [SecureEntropy] — `crypto.getRandomValues` over eight bytes; the bytes
 *   travel back as a hex string because kotlin.Long cannot cross the
 *   wasm/JS boundary directly.
 * * [BundledChunkSource] — the committed base64 constants of
 *   [LuaDecoderAssetsWasm], byte-identical to the JVM classpath assets.
 * * [DebugLog] — switches off (a browser has no environment; the flag value
 *   is pinned to `null`), the sink goes to the devtools via `console.error`.
 * * [TraceSink] — discards (no `/tmp` sentinel in a browser).
 */
object WasmPlatform {

    fun install() {
        Platform.entropy = SecureEntropy { webCryptoRandomLong() }
        Platform.bundledChunks = BundledChunkSource { path -> LuaDecoderAssetsWasm.load(path) }
        Platform.debug = object : DebugLog {
            override fun enabled(flag: String): Boolean = false
            override fun value(flag: String): String? = null
            override fun log(message: String) {
                consoleError(message)
            }
        }
        Platform.traceSink = TraceSink { }
    }
}

/**
 * Fresh browser entropy: eight `crypto.getRandomValues` bytes as a 16-hex-digit
 * string, split into two 32-bit halves so the signed [String.toLong] parse
 * never overflows (`ffffffffffffffff` would; `ffffffff` twice cannot).
 */
private fun webCryptoRandomLong(): Long {
    val hex = randomHex16()
    val high = hex.substring(0, 8).toLong(16)
    val low = hex.substring(8, 16).toLong(16)
    return (high shl 32) or low
}

private fun randomHex16(): String = js(
    """
    const bytes = new Uint8Array(8);
    globalThis.crypto.getRandomValues(bytes);
    let hex = "";
    for (let i = 0; i < bytes.length; i++) {
        hex += bytes[i].toString(16).padStart(2, "0");
    }
    return hex;
    """,
)

private fun consoleError(message: String): Unit = js("console.error(message);")
