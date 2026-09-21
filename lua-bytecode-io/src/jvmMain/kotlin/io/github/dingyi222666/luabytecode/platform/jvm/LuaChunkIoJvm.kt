package io.github.dingyi222666.luabytecode.platform.jvm

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * JVM file / stream adapters for the bytecode io layer — the jvmMain half of
 * the former root module `LuaChunkIoJvm` (roadmap step 3). The package and the
 * call syntax are unchanged, so JVM callers only keep their
 * `import io.github.dingyi222666.luabytecode.platform.jvm.*` line.
 *
 * The `editChunkFile` convenience stays with the edit module (it depends on
 * the `bytecode.edit` DSL, which this module must not depend on).
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
