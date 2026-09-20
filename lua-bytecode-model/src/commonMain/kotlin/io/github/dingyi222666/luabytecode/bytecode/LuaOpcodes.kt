package io.github.dingyi222666.luabytecode.bytecode


/**
 * The opcode names of every supported version, in `OpCode` enum order.
 *
 * Generated from `lopcodes.h` of Lua 5.3.6 / 5.4.6 / 5.5.1. The enum order is part of
 * the binary format: 5.5 for example swaps `SHLI`/`SHRI` and inserts `GETVARG` and
 * `ERRNNIL` before `VARARGPREP`, so names must never be shared across versions.
 */
object LuaOpcodes {

    private val NAMES_LUA_5_3: List<String> = listOf(
        "MOVE", "LOADK", "LOADKX", "LOADBOOL", "LOADNIL", "GETUPVAL",
        "GETTABUP", "GETTABLE", "SETTABUP", "SETUPVAL", "SETTABLE", "NEWTABLE",
        "SELF", "ADD", "SUB", "MUL", "MOD", "POW",
        "DIV", "IDIV", "BAND", "BOR", "BXOR", "SHL",
        "SHR", "UNM", "BNOT", "NOT", "LEN", "CONCAT",
        "JMP", "EQ", "LT", "LE", "TEST", "TESTSET",
        "CALL", "TAILCALL", "RETURN", "FORLOOP", "FORPREP", "TFORCALL",
        "TFORLOOP", "SETLIST", "CLOSURE", "VARARG", "EXTRAARG",
    )

    private val NAMES_LUA_5_4: List<String> = listOf(
        "MOVE", "LOADI", "LOADF", "LOADK", "LOADKX", "LOADFALSE",
        "LFALSESKIP", "LOADTRUE", "LOADNIL", "GETUPVAL", "SETUPVAL", "GETTABUP",
        "GETTABLE", "GETI", "GETFIELD", "SETTABUP", "SETTABLE", "SETI",
        "SETFIELD", "NEWTABLE", "SELF", "ADDI", "ADDK", "SUBK",
        "MULK", "MODK", "POWK", "DIVK", "IDIVK", "BANDK",
        "BORK", "BXORK", "SHRI", "SHLI", "ADD", "SUB",
        "MUL", "MOD", "POW", "DIV", "IDIV", "BAND",
        "BOR", "BXOR", "SHL", "SHR", "MMBIN", "MMBINI",
        "MMBINK", "UNM", "BNOT", "NOT", "LEN", "CONCAT",
        "CLOSE", "TBC", "JMP", "EQ", "LT", "LE",
        "EQK", "EQI", "LTI", "LEI", "GTI", "GEI",
        "TEST", "TESTSET", "CALL", "TAILCALL", "RETURN", "RETURN0",
        "RETURN1", "FORLOOP", "FORPREP", "TFORPREP", "TFORCALL", "TFORLOOP",
        "SETLIST", "CLOSURE", "VARARG", "VARARGPREP", "EXTRAARG",
    )

    private val NAMES_LUA_5_5: List<String> = listOf(
        "MOVE", "LOADI", "LOADF", "LOADK", "LOADKX", "LOADFALSE",
        "LFALSESKIP", "LOADTRUE", "LOADNIL", "GETUPVAL", "SETUPVAL", "GETTABUP",
        "GETTABLE", "GETI", "GETFIELD", "SETTABUP", "SETTABLE", "SETI",
        "SETFIELD", "NEWTABLE", "SELF", "ADDI", "ADDK", "SUBK",
        "MULK", "MODK", "POWK", "DIVK", "IDIVK", "BANDK",
        "BORK", "BXORK", "SHLI", "SHRI", "ADD", "SUB",
        "MUL", "MOD", "POW", "DIV", "IDIV", "BAND",
        "BOR", "BXOR", "SHL", "SHR", "MMBIN", "MMBINI",
        "MMBINK", "UNM", "BNOT", "NOT", "LEN", "CONCAT",
        "CLOSE", "TBC", "JMP", "EQ", "LT", "LE",
        "EQK", "EQI", "LTI", "LEI", "GTI", "GEI",
        "TEST", "TESTSET", "CALL", "TAILCALL", "RETURN", "RETURN0",
        "RETURN1", "FORLOOP", "FORPREP", "TFORPREP", "TFORCALL", "TFORLOOP",
        "SETLIST", "CLOSURE", "VARARG", "GETVARG", "ERRNNIL", "VARARGPREP",
        "EXTRAARG",
    )

    /** All opcode names of [version], indexed by the numeric opcode. */
    fun names(version: LuaBytecodeVersion): List<String> = when (version) {
        LuaBytecodeVersion.LUA_5_3 -> NAMES_LUA_5_3
        LuaBytecodeVersion.LUA_5_4 -> NAMES_LUA_5_4
        LuaBytecodeVersion.LUA_5_5 -> NAMES_LUA_5_5
    }

    /** The name of [opcode] for [version], or `UNKNOWN(n)` when out of range. */
    fun nameOf(version: LuaBytecodeVersion, opcode: Int): String {
        val names = names(version)
        return if (opcode in names.indices) names[opcode] else "UNKNOWN($opcode)"
    }

    /** The numeric opcode of [name] for [version], or `null` when unknown. */
    fun opcodeOf(version: LuaBytecodeVersion, name: String): Int? =
        names(version).indexOf(name).takeIf { it >= 0 }
}
