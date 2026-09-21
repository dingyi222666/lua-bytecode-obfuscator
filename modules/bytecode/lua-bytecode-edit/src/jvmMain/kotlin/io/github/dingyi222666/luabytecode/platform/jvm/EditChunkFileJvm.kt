package io.github.dingyi222666.luabytecode.platform.jvm

import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaChunkScope
import io.github.dingyi222666.luabytecode.bytecode.edit.edit
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import io.github.dingyi222666.luabytecode.platform.jvm.dump
import io.github.dingyi222666.luabytecode.platform.jvm.load
import java.io.File

/**
 * `editChunkFile(input, output) { .. }` — the file based load → edit → dump
 * entry point. It lives in this module's jvmMain because it composes the edit
 * DSL with the io adapters (the plain `load(File/InputStream)` /
 * `dump(chunk, File/OutputStream)` extensions come from `:lua-bytecode-io`
 * jvmMain, same package and call syntax).
 *
 * The file keeps the name `EditChunkFileJvm.kt` on purpose: two facade classes
 * with the same JVM name in one package shadow each other on a compile
 * classpath (see the `LuaChunkIoJvmKt` lesson in
 * `docs/roadmap-kmp-progress.md`).
 */
fun editChunkFile(input: File, output: File, block: LuaChunkScope.() -> Unit): LuaChunk {
    val chunk = LuaChunkLoader.load(input)
    chunk.edit(block)
    LuaChunkWriter.dump(chunk, output)
    return chunk
}
