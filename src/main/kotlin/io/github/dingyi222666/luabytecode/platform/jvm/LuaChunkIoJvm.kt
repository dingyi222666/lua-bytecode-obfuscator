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
 * entry point. It stays in the root module because it depends on the edit DSL;
 * the plain `load(File/InputStream)` / `dump(chunk, File/OutputStream)`
 * adapters moved to the `:lua-bytecode-io` module's jvmMain (same package and
 * call syntax).
 */
fun editChunkFile(input: File, output: File, block: LuaChunkScope.() -> Unit): LuaChunk {
    val chunk = LuaChunkLoader.load(input)
    chunk.edit(block)
    LuaChunkWriter.dump(chunk, output)
    return chunk
}
