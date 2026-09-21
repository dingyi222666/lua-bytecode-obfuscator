package io.github.dingyi222666.luabytecode.platform.jvm

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaChunkScope
import io.github.dingyi222666.luabytecode.bytecode.edit.edit
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import java.io.File
import java.io.OutputStream

/**
 * JVM file / stream adapters for the bytecode io layer.
 *
 * The core `LuaChunkLoader` / `LuaChunkWriter` stay free of `java.io` so the
 * `bytecode.io` package can move into a common module (`docs/roadmap-kmp-modules.md`
 * step 3); the file based conveniences live here as extensions with the exact
 * same names and call syntax (`LuaChunkLoader.load(file)`,
 * `LuaChunkWriter.dump(chunk, file)`, `editChunkFile(..) { .. }`), so JVM
 * callers only add an `import io.github.dingyi222666.luabytecode.platform.jvm.*`.
 */

/** Loads a chunk from a file. */
@JvmOverloads
fun LuaChunkLoader.load(file: File, expectedVersion: LuaBytecodeVersion? = null): LuaChunk =
    load(file.readBytes(), expectedVersion)

/** Loads a chunk from a stream (fully read into memory; the stream is not closed). */
@JvmOverloads
fun LuaChunkLoader.load(input: InputStream, expectedVersion: LuaBytecodeVersion? = null): LuaChunk =
    load(input.readBytes(), expectedVersion)

/** Dumps a chunk to a file, replacing its content. */
fun LuaChunkWriter.dump(chunk: LuaChunk, file: File) {
    file.writeBytes(dump(chunk))
}

/** Dumps a chunk to a stream (the stream is flushed, not closed). */
fun LuaChunkWriter.dump(chunk: LuaChunk, output: OutputStream) {
    val bytes = dump(chunk)
    output.write(bytes)
    output.flush()
}

/**
 * Loads [input], applies [block] and writes the result to [output].
 *
 * @return the edited chunk, already dumped to [output].
 */
fun editChunkFile(input: File, output: File, block: LuaChunkScope.() -> Unit): LuaChunk {
    val chunk = LuaChunkLoader.load(input)
    chunk.edit(block)
    LuaChunkWriter.dump(chunk, output)
    return chunk
}
