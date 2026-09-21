package io.github.dingyi222666.luabytecode.cli

import io.github.dingyi222666.luabytecode.platform.BundledChunkSource
import io.github.dingyi222666.luabytecode.platform.DebugLog
import io.github.dingyi222666.luabytecode.platform.Platform
import io.github.dingyi222666.luabytecode.platform.SecureEntropy
import io.github.dingyi222666.luabytecode.platform.TraceSink
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fprintf
import platform.posix.fread
import platform.posix.getenv
import platform.posix.stderr

/**
 * The native counterpart of `platform.jvm.JvmPlatform` and `runtime.js.JsPlatform`
 * (roadmap step 6b). It lives in the native host module — not in :lua-platform —
 * because the bundled decoder assets belong to the feature modules and
 * :lua-platform sits BELOW them in the dependency graph; the native CLI is the
 * only native host assembly point, mirroring the JsPlatform precedent recorded
 * in docs/roadmap-kmp-progress.md (Phase 6, first half).
 *
 * Call [install] once before running the pipeline in a native host — the CLI
 * entry point does it first thing, and the nativeTest suites repeat it:
 *
 * * [SecureEntropy] — `/dev/urandom` (8 bytes, big-endian Long).
 * * [BundledChunkSource] — the committed embedded constants of
 *   [LuaDecoderAssetsNative], byte-identical to the JVM classpath and js
 *   deliveries.
 * * [DebugLog] — `getenv` switches (`CFF_DEBUG`, `K_DEBUG`, ...), stderr sink.
 * * [TraceSink] — the `/tmp/vm-subset-on` sentinel gating appends to
 *   `/tmp/vm-subset.txt`, byte-compatible with the historical JVM behaviour.
 */
@OptIn(ExperimentalForeignApi::class)
object NativePlatform {

    private const val VM_SUBSET_SENTINEL = "/tmp/vm-subset-on"
    private const val VM_SUBSET_TRACE = "/tmp/vm-subset.txt"

    fun install() {
        Platform.entropy = SecureEntropy { urandomRandomLong() }
        Platform.bundledChunks = BundledChunkSource { path -> LuaDecoderAssetsNative.load(path) }
        Platform.debug = object : DebugLog {
            override fun enabled(flag: String): Boolean = posixGetenv(flag) != null
            override fun value(flag: String): String? = posixGetenv(flag)
            override fun log(message: String) {
                stderrPrintln(message)
            }
        }
        Platform.traceSink = TraceSink { line ->
            // Sentinel check first (fopen succeeds only when the developer
            // switch exists), then append the line as one UTF-8 write.
            val switch = fopen(VM_SUBSET_SENTINEL, "rb") ?: return@TraceSink
            fclose(switch)
            val trace = fopen(VM_SUBSET_TRACE, "a") ?: return@TraceSink
            try {
                fprintf(trace, "%s\n", line)
            } finally {
                fclose(trace)
            }
        }
    }

    /** `getenv(3)` as a Kotlin string, `null` when the variable is unset. */
    internal fun posixGetenv(name: String): String? = getenv(name)?.toKString()

    /** One diagnostics line on stderr (the [DebugLog] sink). */
    internal fun stderrPrintln(message: String) {
        fprintf(stderr, "%s\n", message)
    }

    /**
     * Fresh OS entropy: eight bytes from `/dev/urandom`, assembled big-endian
     * into a Long. Falls back to the common stdlib random only if the device
     * cannot be opened (never expected on the supported linux hosts).
     */
    private fun urandomRandomLong(): Long {
        val device = fopen("/dev/urandom", "rb")
            ?: return kotlin.random.Random.Default.nextLong()
        try {
            return memScoped {
                val buffer = allocArray<ByteVar>(8)
                fread(buffer, 1u, 8u, device)
                var value = 0L
                for (index in 0 until 8) {
                    value = (value shl 8) or (buffer[index].toLong() and 0xFF)
                }
                value
            }
        } finally {
            fclose(device)
        }
    }
}
