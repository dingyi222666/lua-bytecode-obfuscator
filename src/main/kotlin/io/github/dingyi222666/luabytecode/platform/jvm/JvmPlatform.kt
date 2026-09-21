package io.github.dingyi222666.luabytecode.platform.jvm

import io.github.dingyi222666.luabytecode.platform.BundledChunkSource
import io.github.dingyi222666.luabytecode.platform.DebugLog
import io.github.dingyi222666.luabytecode.platform.Platform
import io.github.dingyi222666.luabytecode.platform.SecureEntropy
import io.github.dingyi222666.luabytecode.platform.TraceSink
import java.io.File
import java.security.SecureRandom

/**
 * JVM implementations of the [io.github.dingyi222666.luabytecode.platform]
 * service contracts. Entry points (CLI `main`, host applications, test
 * fixtures) call [install] once before running the pipeline:
 *
 * * [SecureEntropy] — `SecureRandom` (OS entropy).
 * * [BundledChunkSource] — classpath resources (the `/obfuscator/decode_NN.luac` assets).
 * * [DebugLog] — environment variable switches, stderr sink.
 * * [TraceSink] — the `/tmp/vm-subset-on` sentinel gating appends to
 *   `/tmp/vm-subset.txt` (kept byte-compatible with the historical behaviour).
 */
object JvmPlatform {

    fun install() {
        Platform.entropy = SecureEntropy { SecureRandom().nextLong() }
        Platform.bundledChunks = BundledChunkSource { path ->
            JvmPlatform::class.java.getResourceAsStream(path)?.use { it.readBytes() }
        }
        Platform.debug = object : DebugLog {
            override fun enabled(flag: String): Boolean = System.getenv(flag) != null
            override fun value(flag: String): String? = System.getenv(flag)
            override fun log(message: String) {
                System.err.println(message)
            }
        }
        Platform.traceSink = TraceSink { line ->
            val switch = File("/tmp/vm-subset-on")
            if (switch.exists()) {
                File("/tmp/vm-subset.txt").appendText(line + "\n")
            }
        }
    }
}
