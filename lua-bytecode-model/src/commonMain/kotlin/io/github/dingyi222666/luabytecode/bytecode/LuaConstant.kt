package io.github.dingyi222666.luabytecode.bytecode


/**
 * One entry of a prototype constant table (`Proto.k`).
 *
 * Both 5.3 and 5.4 tag each constant with a one byte type tag; the tag set and
 * the float/integer tags are *not* identical between the two versions:
 *
 * | constant | 5.3 tag | 5.4 tag |
 * |----------|---------|---------|
 * | nil      | `0x00`  | `0x00`  |
 * | boolean  | `0x01` + 1 byte value | `0x01` / `0x11` |
 * | number   | float `0x03`, integer `0x13` | integer `0x03`, float `0x13` |
 * | string   | short `0x04`, long `0x14` | short `0x04`, long `0x14` |
 */
sealed class LuaConstant {

    /** `nil` constant. */
    data object Nil : LuaConstant()

    /** `true` / `false` constant. */
    data class Boolean(val value: kotlin.Boolean) : LuaConstant()

    /** A `lua_Integer` constant (integral numbers are not stored as doubles). */
    data class Integer(val value: Long) : LuaConstant()

    /** A `lua_Number` (floating point) constant. */
    data class Number(val value: Double) : LuaConstant()

    /** A string constant. */
    data class Str(val value: LuaString) : LuaConstant()
}
