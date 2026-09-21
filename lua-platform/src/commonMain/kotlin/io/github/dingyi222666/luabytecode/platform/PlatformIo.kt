package io.github.dingyi222666.luabytecode.platform

/**
 * Platform service contracts for the bytecode / obfuscation / vm packages —
 * the seams that let library code stay free of `java.io`, `SecureRandom`,
 * classpath asset loading and environment lookups (roadmap step 2, segment D).
 *
 * The [Platform] holder carries process-wide defaults that are safe everywhere
 * (common-stdlib entropy, no assets, debug off); the entrypoint — CLI `main`
 * or a host application — installs the platform implementation
 * (`platform.jvm.JvmPlatform.install()` on the JVM).
 */

/** OS entropy for the unseeded run mode (PRED-2/LEAK-5). */
fun interface SecureEntropy {

    /** Fresh unpredictable 64 bits; must never repeat across runs. */
    fun nextLong(): Long
}

/**
 * Loads bundled decoder assets shipped inside the library
 * (`/obfuscator/decode_*.luac`). Returns `null` when the resource is absent.
 */
fun interface BundledChunkSource {

    fun load(path: String): ByteArray?
}

/**
 * Named debug switches plus a diagnostics sink. On the JVM the switches map to
 * environment variables (`CFF_DEBUG`, `K_DEBUG`, `K_TRACE_CONST`, ...) and the
 * sink is stderr.
 */
interface DebugLog {

    /** Whether the named switch is on. */
    fun enabled(flag: String): Boolean

    /** Raw value of a switch, when the caller needs more than on/off. */
    fun value(flag: String): String?

    /** Writes one diagnostics line. */
    fun log(message: String)
}

/**
 * Developer-only refusal trace of the VM translator (historically the
 * `/tmp/vm-subset-on` sentinel gating appends to `/tmp/vm-subset.txt`).
 */
fun interface TraceSink {

    /** Appends one line to the trace. */
    fun append(line: String)
}

/**
 * Process-wide platform services. Defaults are conservative and platform
 * independent; install `platform.jvm.JvmPlatform` (or a test double) to
 * upgrade them.
 */
object Platform {

    /**
     * Entropy for `--seed`-less runs. The default draws from the common
     * stdlib random (fresh per process), so library users get unique output
     * even without installing a platform; the CLI installs a `SecureRandom`
     * backed source for real OS entropy.
     */
    var entropy: SecureEntropy = SecureEntropy { kotlin.random.Random.Default.nextLong() }

    /** Bundled asset access; defaults to absent (passes skip optional assets). */
    var bundledChunks: BundledChunkSource = BundledChunkSource { null }

    /** Debug switches; default is all-off and silent. */
    var debug: DebugLog = object : DebugLog {
        override fun enabled(flag: String): Boolean = false
        override fun value(flag: String): String? = null
        override fun log(message: String) {}
    }

    /** Developer refusal trace; default discards. */
    var traceSink: TraceSink = TraceSink {}
}
