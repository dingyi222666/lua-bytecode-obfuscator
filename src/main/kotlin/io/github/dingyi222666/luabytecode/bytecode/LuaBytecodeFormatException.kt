package io.github.dingyi222666.luabytecode.bytecode


/**
 * Thrown when a byte array is not a well formed Lua binary chunk.
 *
 * [offset] is the position inside the chunk where the problem was detected,
 * or `-1` when the problem is not bound to a single position.
 */
class LuaBytecodeFormatException(
    message: String,
    val offset: Int = -1,
) : RuntimeException(
    if (offset >= 0) "$message (at byte offset $offset)" else message,
)
