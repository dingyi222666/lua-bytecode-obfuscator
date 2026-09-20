package io.github.dingyi222666.luabytecode.bytecode

import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter

/**
 * A fully parsed Lua binary chunk: the header plus the main prototype tree.
 *
 * The tree is editable in place, see [LuaPrototype]; write it back with
 * [LuaChunkWriter].
 *
 * @see LuaChunkLoader
 * @see LuaChunkWriter
 */
data class LuaChunk(
    val header: LuaChunkHeader,
    /** `nupvalues` of the main closure, must equal `mainPrototype.upvalues.size`. */
    val mainUpvalueCount: Int,
    val mainPrototype: LuaPrototype,
) {

    val version: LuaBytecodeVersion
        get() = header.version

    /** The main prototype and every prototype nested in it, in pre-order. */
    val prototypes: List<LuaPrototype>
        get() = mainPrototype.allPrototypes()

    override fun toString(): String =
        "LuaChunk(${header.version.displayName}, upvalues=$mainUpvalueCount, prototypes=${prototypes.size})"
}
