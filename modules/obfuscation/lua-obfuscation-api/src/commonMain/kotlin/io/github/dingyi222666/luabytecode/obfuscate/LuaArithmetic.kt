package io.github.dingyi222666.luabytecode.obfuscate
import io.github.dingyi222666.luabytecode.bytecode.*
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaInstructionFactory
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaInstructionStream

/** Central arithmetic/bitwise instruction and expression DSL.
 *
 * Promoted to :lua-obfuscation-api (public): shared by the CFF feature
 * (flattener, control packets, translation validator, constant expansion) and
 * the jumps feature (junk/cloning emission).
 */
object LuaArithmetic {
    fun eventOf(name: String): Int? = when (name) {
        "ADD" -> 6; "SUB" -> 7; "MUL" -> 8; "MOD" -> 9; "POW" -> 10; "DIV" -> 11
        "IDIV" -> 12; "BAND" -> 13; "BOR" -> 14; "BXOR" -> 15; "SHL" -> 16; "SHR" -> 17
        else -> null
    }
    fun opcode(version: LuaBytecodeVersion, name: String): Int =
        LuaInstructionFactory.opcode(version, name)
    fun instruction(p: LuaPrototype, name: String, a: Int, b: Int, c: Int): LuaInstruction =
        LuaInstructionFactory.abc(p.version, name, a, b, c, 0)
    fun metaInstruction(p: LuaPrototype, name: String, b: Int, c: Int): LuaInstruction? {
        if (p.version == LuaBytecodeVersion.LUA_5_3) return null
        val event = eventOf(name) ?: return null
        return LuaInstructionFactory.abc(p.version, "MMBIN", b, c, event, 0)
    }
    fun pair(p: LuaPrototype, name: String, a: Int, b: Int, c: Int): List<LuaInstruction> = buildList {
        add(instruction(p, name, a, b, c)); metaInstruction(p, name, b, c)?.let(::add)
    }
    fun emit(stream: LuaInstructionStream, p: LuaPrototype, name: String, a: Int, b: Int, c: Int) =
        pair(p, name, a, b, c).forEach(stream::emit)

}
