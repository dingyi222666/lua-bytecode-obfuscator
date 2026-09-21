package io.github.dingyi222666.luabytecode.bytecode

/**
 * The parsed header of a Lua binary chunk, equivalent to what `checkHeader` in
 * `lundump.c` validates before the prototype tree is read.
 *
 * Layout (little endian, 64 bit build of the reference implementation):
 *
 * ```
 * 5.3: "\x1bLua" | version | format | LUAC_DATA | sizeof(int) | sizeof(size_t)
 *      | sizeof(Instruction) | sizeof(lua_Integer) | sizeof(lua_Number)
 *      | LUAC_INT (0x5678) | LUAC_NUM (370.5)
 * 5.4: "\x1bLua" | version | format | LUAC_DATA | sizeof(Instruction)
 *      | sizeof(lua_Integer) | sizeof(lua_Number) | LUAC_INT | LUAC_NUM
 * 5.5: "\x1bLua" | version | format | LUAC_DATA
 *      | sizeof(int)   + LUAC_INT   (-0x5678)
 *      | sizeof(Instruction) + LUAC_INST (0x12345678)
 *      | sizeof(lua_Integer) + LUAC_INT
 *      | sizeof(lua_Number)  + LUAC_NUM (-370.5)
 * ```
 */
data class LuaChunkHeader(
    val version: LuaBytecodeVersion,
    /** Always `0` for the official luac format. */
    val format: Int,
    val byteOrder: LuaByteOrder,
    /** `sizeof(int)`; `null` for 5.4 which does not store it (it is always 4). */
    val intSize: Int?,
    /** `sizeof(size_t)`; `null` for 5.4 / 5.5. Used by 5.3 for long string sizes. */
    val sizeTSize: Int?,
    /** `sizeof(Instruction)`, always 4 for the reference implementation. */
    val instructionSize: Int,
    /** `sizeof(lua_Integer)`, 8 for the reference implementation. */
    val integerSize: Int,
    /** `sizeof(lua_Number)`, 8 for the reference implementation (4 for float builds). */
    val numberSize: Int,
    /** The `LUAC_INT` (0x5678) value that was read, used for endianness detection. */
    val checkInteger: Long,
    /** The `LUAC_NUM` (370.5) value that was read, used for float format detection. */
    val checkNumber: Double,
) {
    override fun toString(): String =
        "${version.displayName} chunk (format $format, ${byteOrder.displayName}, " +
            "instruction=$instructionSize, integer=$integerSize, number=$numberSize)"
}
