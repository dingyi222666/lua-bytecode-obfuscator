package io.github.dingyi222666.luabytecode.decompile

import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bridge to an external Lua decompiler, mainly used to *measure* obfuscation:
 * decompiling a chunk before and after a pass shows what a reverse engineer
 * would actually see.
 *
 * `unluac` (`https://sourceforge.net/projects/unluac/`, fork:
 * `https://github.com/Jeong-Min-Cho/unluac`) supports Lua 5.0 - 5.4. Lua 5.5 is
 * not supported by it yet.
 *
 * The jar is located through, in order:
 *  1. the `luabytecode.unluac` system property,
 *  2. the `UNLUAC_JAR` environment variable,
 *  3. `tools/unluac.jar` relative to the working directory.
 *
 * The decompiler package exists so that a native Lua decompiler can be added
 * later without touching the format, edit or obfuscation layers.
 */
object ExternalDecompiler {

    /** Path of the located jar, or `null` when no decompiler is configured. */
    @JvmStatic
    fun locate(): File? {
        val candidates = listOfNotNull(
            System.getProperty("luabytecode.unluac")?.let(::File),
            System.getenv("UNLUAC_JAR")?.let(::File),
            File("tools/unluac.jar"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    /** `true` when a decompiler is available. */
    @JvmStatic
    fun isAvailable(): Boolean = locate() != null

    /**
     * Decompiles a chunk by dumping it to a temporary file and running the
     * decompiler.
     *
     * @throws IllegalStateException when no decompiler is configured.
     */
    @JvmStatic
    fun decompile(chunk: LuaChunk): String = decompile(LuaChunkWriter.dump(chunk))

    /** Decompiles raw chunk bytes. */
    @JvmStatic
    fun decompile(bytes: ByteArray): String {
        val jar = locate() ?: throw IllegalStateException(
            "no unluac jar configured; set -Dluabytecode.unluac=/path/to/unluac.jar " +
                "or put it at tools/unluac.jar",
        )
        val directory = createTempDir("lua-decompile")
        return try {
            val chunkFile = File(directory, "chunk.luac")
            chunkFile.writeBytes(bytes)
            val java = File(System.getProperty("java.home"), "bin/java")
            val process = ProcessBuilder(java.absolutePath, "-jar", jar.absolutePath, chunkFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("decompiler timed out")
            }
            output
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createTempDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()
}
