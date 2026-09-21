package io.github.dingyi222666.luabytecode.runtime.js

import io.github.dingyi222666.luabytecode.platform.BundledChunkSource
import io.github.dingyi222666.luabytecode.platform.DebugLog
import io.github.dingyi222666.luabytecode.platform.Platform
import io.github.dingyi222666.luabytecode.platform.SecureEntropy
import io.github.dingyi222666.luabytecode.platform.TraceSink

/**
 * The JS counterpart of `platform.jvm.JvmPlatform` (roadmap step 6). It lives
 * in the web runtime module — not in :lua-platform — because the bundled
 * decoder assets belong to the feature modules and :lua-platform sits BELOW
 * them in the dependency graph (the same reason the JVM assembler was a root
 * module component until Phase 4 moved it next to its test consumers).
 *
 * Call [install] once before running the pipeline in a JS host (the web
 * worker, or a node host) — and again in tests:
 *
 * * [SecureEntropy] — `crypto.getRandomValues` (webcrypto: browser global or
 *   `require('crypto').webcrypto` on node < 19).
 * * [BundledChunkSource] — the committed base64 constants of
 *   [LuaDecoderAssetsJs], byte-identical to the JVM classpath assets.
 * * [DebugLog] — switches off (a browser has no environment); the sink goes
 *   to the devtools / node stderr via `console.error`.
 * * [TraceSink] — discards (no `/tmp` sentinel in a browser).
 */
object JsPlatform {

    fun install() {
        Platform.entropy = SecureEntropy { webCryptoRandomLong() }
        Platform.bundledChunks = BundledChunkSource { path -> LuaDecoderAssetsJs.load(path) }
        Platform.debug = object : DebugLog {
            override fun enabled(flag: String): Boolean = false
            override fun value(flag: String): String? = null
            override fun log(message: String) {
                console.error(message)
            }
        }
        Platform.traceSink = TraceSink { }
    }

    /**
     * Fresh OS entropy: `crypto.getRandomValues` over eight bytes, assembled
     * big-endian into a Long. Falls back from the browser/node(>=19) global
     * webcrypto to node's `require('crypto').webcrypto` for older node.
     */
    private fun webCryptoRandomLong(): Long {
        if (hasGlobalCrypto()) {
            return randomLongFrom(js("globalThis.crypto"))
        }
        return randomLongFrom(js("require('crypto').webcrypto"))
    }

    private fun randomLongFrom(crypto: dynamic): Long {
        val buffer: dynamic = js("new Int8Array(8)")
        crypto.getRandomValues(buffer)
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or ((buffer[index] as Int).toLong() and 0xFFL)
        }
        return value
    }

    private fun hasGlobalCrypto(): Boolean =
        js("typeof globalThis.crypto !== 'undefined' && typeof globalThis.crypto.getRandomValues === 'function'")
}
