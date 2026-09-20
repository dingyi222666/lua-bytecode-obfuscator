package io.github.dingyi222666.luabytecode.bytecode.edit

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaOpcodes

/**
 * Single low-level instruction construction boundary for bytecode edits.
 *
 * Passes should prefer [LuaInstructionStream] for sequences, but when an
 * instruction must be materialised as a value this factory keeps opcode
 * lookup, encoding and version differences in one place.
 */
object LuaInstructionFactory {
    fun opcode(version: LuaBytecodeVersion, name: String): Int =
        LuaOpcodes.opcodeOf(version, name)
            ?: throw IllegalArgumentException("no opcode named $name in ${version.displayName}")

    fun of(version: LuaBytecodeVersion, opcode: Int): LuaInstruction =
        LuaInstruction.of(version, opcode)

    fun of(version: LuaBytecodeVersion, name: String): LuaInstruction =
        of(version, opcode(version, name))

    fun abc(version: LuaBytecodeVersion, opcode: Int, a: Int, b: Int = 0, c: Int = 0, k: Int = 0): LuaInstruction =
        LuaInstruction.abc(version, opcode, a, b, c, k)

    fun abc(version: LuaBytecodeVersion, name: String, a: Int = 0, b: Int = 0, c: Int = 0, k: Int = 0): LuaInstruction =
        abc(version, opcode(version, name), a, b, c, k)

    fun abx(version: LuaBytecodeVersion, opcode: Int, a: Int, bx: Int): LuaInstruction =
        LuaInstruction.abx(version, opcode, a, bx)

    fun abx(version: LuaBytecodeVersion, name: String, a: Int = 0, bx: Int = 0): LuaInstruction =
        abx(version, opcode(version, name), a, bx)

    fun asbx(version: LuaBytecodeVersion, opcode: Int, a: Int, sbx: Int): LuaInstruction =
        LuaInstruction.asbx(version, opcode, a, sbx)

    fun asbx(version: LuaBytecodeVersion, name: String, a: Int = 0, sbx: Int = 0): LuaInstruction =
        asbx(version, opcode(version, name), a, sbx)

    fun ax(version: LuaBytecodeVersion, opcode: Int, ax: Int): LuaInstruction =
        LuaInstruction.ax(version, opcode, ax)

    fun ax(version: LuaBytecodeVersion, name: String, ax: Int = 0): LuaInstruction =
        ax(version, opcode(version, name), ax)

    fun sj(version: LuaBytecodeVersion, opcode: Int, sj: Int): LuaInstruction =
        LuaInstruction.sj(version, opcode, sj)

    fun sj(version: LuaBytecodeVersion, name: String, sj: Int = 0): LuaInstruction =
        sj(version, opcode(version, name), sj)
}
