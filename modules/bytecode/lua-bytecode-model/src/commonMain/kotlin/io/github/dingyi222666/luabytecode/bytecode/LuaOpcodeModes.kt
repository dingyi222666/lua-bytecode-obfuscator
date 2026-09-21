package io.github.dingyi222666.luabytecode.bytecode


/**
 * Operand layout of an opcode (`OpMode` in `lopcodes.h`) together with the per version
 * `luaP_opmodes` table.
 *
 * Generated from `lopcodes.c` of Lua 5.3.6 / 5.4.6 / 5.5.1.
 */
enum class LuaOpcodeMode {
    /** `A B C` (5.4 and 5.5 add the trailing `k` flag). */
    ABC,

    /** `A Bx` */
    ABX,

    /** `A sBx` */
    ASBX,

    /** `Ax` (only `EXTRAARG`). */
    AX,

    /** `sJ` (`JMP` of 5.4 and 5.5). */
    SJ,

    /** `A vB vC` with a 6 bit `vB` and a 10 bit `vC` (5.5 `NEWTABLE` / `SETLIST`). */
    VABC,
}

object LuaOpcodeModes {

    private val MODES_LUA_5_3: Array<LuaOpcodeMode> = arrayOf(
        LuaOpcodeMode.ABC, // MOVE
        LuaOpcodeMode.ABX, // LOADK
        LuaOpcodeMode.ABX, // LOADKX
        LuaOpcodeMode.ABC, // LOADBOOL
        LuaOpcodeMode.ABC, // LOADNIL
        LuaOpcodeMode.ABC, // GETUPVAL
        LuaOpcodeMode.ABC, // GETTABUP
        LuaOpcodeMode.ABC, // GETTABLE
        LuaOpcodeMode.ABC, // SETTABUP
        LuaOpcodeMode.ABC, // SETUPVAL
        LuaOpcodeMode.ABC, // SETTABLE
        LuaOpcodeMode.ABC, // NEWTABLE
        LuaOpcodeMode.ABC, // SELF
        LuaOpcodeMode.ABC, // ADD
        LuaOpcodeMode.ABC, // SUB
        LuaOpcodeMode.ABC, // MUL
        LuaOpcodeMode.ABC, // MOD
        LuaOpcodeMode.ABC, // POW
        LuaOpcodeMode.ABC, // DIV
        LuaOpcodeMode.ABC, // IDIV
        LuaOpcodeMode.ABC, // BAND
        LuaOpcodeMode.ABC, // BOR
        LuaOpcodeMode.ABC, // BXOR
        LuaOpcodeMode.ABC, // SHL
        LuaOpcodeMode.ABC, // SHR
        LuaOpcodeMode.ABC, // UNM
        LuaOpcodeMode.ABC, // BNOT
        LuaOpcodeMode.ABC, // NOT
        LuaOpcodeMode.ABC, // LEN
        LuaOpcodeMode.ABC, // CONCAT
        LuaOpcodeMode.ASBX, // JMP
        LuaOpcodeMode.ABC, // EQ
        LuaOpcodeMode.ABC, // LT
        LuaOpcodeMode.ABC, // LE
        LuaOpcodeMode.ABC, // TEST
        LuaOpcodeMode.ABC, // TESTSET
        LuaOpcodeMode.ABC, // CALL
        LuaOpcodeMode.ABC, // TAILCALL
        LuaOpcodeMode.ABC, // RETURN
        LuaOpcodeMode.ASBX, // FORLOOP
        LuaOpcodeMode.ASBX, // FORPREP
        LuaOpcodeMode.ABC, // TFORCALL
        LuaOpcodeMode.ASBX, // TFORLOOP
        LuaOpcodeMode.ABC, // SETLIST
        LuaOpcodeMode.ABX, // CLOSURE
        LuaOpcodeMode.ABC, // VARARG
        LuaOpcodeMode.AX, // EXTRAARG
    )

    private val MODES_LUA_5_4: Array<LuaOpcodeMode> = arrayOf(
        LuaOpcodeMode.ABC, // MOVE
        LuaOpcodeMode.ASBX, // LOADI
        LuaOpcodeMode.ASBX, // LOADF
        LuaOpcodeMode.ABX, // LOADK
        LuaOpcodeMode.ABX, // LOADKX
        LuaOpcodeMode.ABC, // LOADFALSE
        LuaOpcodeMode.ABC, // LFALSESKIP
        LuaOpcodeMode.ABC, // LOADTRUE
        LuaOpcodeMode.ABC, // LOADNIL
        LuaOpcodeMode.ABC, // GETUPVAL
        LuaOpcodeMode.ABC, // SETUPVAL
        LuaOpcodeMode.ABC, // GETTABUP
        LuaOpcodeMode.ABC, // GETTABLE
        LuaOpcodeMode.ABC, // GETI
        LuaOpcodeMode.ABC, // GETFIELD
        LuaOpcodeMode.ABC, // SETTABUP
        LuaOpcodeMode.ABC, // SETTABLE
        LuaOpcodeMode.ABC, // SETI
        LuaOpcodeMode.ABC, // SETFIELD
        LuaOpcodeMode.ABC, // NEWTABLE
        LuaOpcodeMode.ABC, // SELF
        LuaOpcodeMode.ABC, // ADDI
        LuaOpcodeMode.ABC, // ADDK
        LuaOpcodeMode.ABC, // SUBK
        LuaOpcodeMode.ABC, // MULK
        LuaOpcodeMode.ABC, // MODK
        LuaOpcodeMode.ABC, // POWK
        LuaOpcodeMode.ABC, // DIVK
        LuaOpcodeMode.ABC, // IDIVK
        LuaOpcodeMode.ABC, // BANDK
        LuaOpcodeMode.ABC, // BORK
        LuaOpcodeMode.ABC, // BXORK
        LuaOpcodeMode.ABC, // SHRI
        LuaOpcodeMode.ABC, // SHLI
        LuaOpcodeMode.ABC, // ADD
        LuaOpcodeMode.ABC, // SUB
        LuaOpcodeMode.ABC, // MUL
        LuaOpcodeMode.ABC, // MOD
        LuaOpcodeMode.ABC, // POW
        LuaOpcodeMode.ABC, // DIV
        LuaOpcodeMode.ABC, // IDIV
        LuaOpcodeMode.ABC, // BAND
        LuaOpcodeMode.ABC, // BOR
        LuaOpcodeMode.ABC, // BXOR
        LuaOpcodeMode.ABC, // SHL
        LuaOpcodeMode.ABC, // SHR
        LuaOpcodeMode.ABC, // MMBIN
        LuaOpcodeMode.ABC, // MMBINI
        LuaOpcodeMode.ABC, // MMBINK
        LuaOpcodeMode.ABC, // UNM
        LuaOpcodeMode.ABC, // BNOT
        LuaOpcodeMode.ABC, // NOT
        LuaOpcodeMode.ABC, // LEN
        LuaOpcodeMode.ABC, // CONCAT
        LuaOpcodeMode.ABC, // CLOSE
        LuaOpcodeMode.ABC, // TBC
        LuaOpcodeMode.SJ, // JMP
        LuaOpcodeMode.ABC, // EQ
        LuaOpcodeMode.ABC, // LT
        LuaOpcodeMode.ABC, // LE
        LuaOpcodeMode.ABC, // EQK
        LuaOpcodeMode.ABC, // EQI
        LuaOpcodeMode.ABC, // LTI
        LuaOpcodeMode.ABC, // LEI
        LuaOpcodeMode.ABC, // GTI
        LuaOpcodeMode.ABC, // GEI
        LuaOpcodeMode.ABC, // TEST
        LuaOpcodeMode.ABC, // TESTSET
        LuaOpcodeMode.ABC, // CALL
        LuaOpcodeMode.ABC, // TAILCALL
        LuaOpcodeMode.ABC, // RETURN
        LuaOpcodeMode.ABC, // RETURN0
        LuaOpcodeMode.ABC, // RETURN1
        LuaOpcodeMode.ABX, // FORLOOP
        LuaOpcodeMode.ABX, // FORPREP
        LuaOpcodeMode.ABX, // TFORPREP
        LuaOpcodeMode.ABC, // TFORCALL
        LuaOpcodeMode.ABX, // TFORLOOP
        LuaOpcodeMode.ABC, // SETLIST
        LuaOpcodeMode.ABX, // CLOSURE
        LuaOpcodeMode.ABC, // VARARG
        LuaOpcodeMode.ABC, // VARARGPREP
        LuaOpcodeMode.AX, // EXTRAARG
    )

    private val MODES_LUA_5_5: Array<LuaOpcodeMode> = arrayOf(
        LuaOpcodeMode.ABC, // MOVE
        LuaOpcodeMode.ASBX, // LOADI
        LuaOpcodeMode.ASBX, // LOADF
        LuaOpcodeMode.ABX, // LOADK
        LuaOpcodeMode.ABX, // LOADKX
        LuaOpcodeMode.ABC, // LOADFALSE
        LuaOpcodeMode.ABC, // LFALSESKIP
        LuaOpcodeMode.ABC, // LOADTRUE
        LuaOpcodeMode.ABC, // LOADNIL
        LuaOpcodeMode.ABC, // GETUPVAL
        LuaOpcodeMode.ABC, // SETUPVAL
        LuaOpcodeMode.ABC, // GETTABUP
        LuaOpcodeMode.ABC, // GETTABLE
        LuaOpcodeMode.ABC, // GETI
        LuaOpcodeMode.ABC, // GETFIELD
        LuaOpcodeMode.ABC, // SETTABUP
        LuaOpcodeMode.ABC, // SETTABLE
        LuaOpcodeMode.ABC, // SETI
        LuaOpcodeMode.ABC, // SETFIELD
        LuaOpcodeMode.VABC, // NEWTABLE
        LuaOpcodeMode.ABC, // SELF
        LuaOpcodeMode.ABC, // ADDI
        LuaOpcodeMode.ABC, // ADDK
        LuaOpcodeMode.ABC, // SUBK
        LuaOpcodeMode.ABC, // MULK
        LuaOpcodeMode.ABC, // MODK
        LuaOpcodeMode.ABC, // POWK
        LuaOpcodeMode.ABC, // DIVK
        LuaOpcodeMode.ABC, // IDIVK
        LuaOpcodeMode.ABC, // BANDK
        LuaOpcodeMode.ABC, // BORK
        LuaOpcodeMode.ABC, // BXORK
        LuaOpcodeMode.ABC, // SHLI
        LuaOpcodeMode.ABC, // SHRI
        LuaOpcodeMode.ABC, // ADD
        LuaOpcodeMode.ABC, // SUB
        LuaOpcodeMode.ABC, // MUL
        LuaOpcodeMode.ABC, // MOD
        LuaOpcodeMode.ABC, // POW
        LuaOpcodeMode.ABC, // DIV
        LuaOpcodeMode.ABC, // IDIV
        LuaOpcodeMode.ABC, // BAND
        LuaOpcodeMode.ABC, // BOR
        LuaOpcodeMode.ABC, // BXOR
        LuaOpcodeMode.ABC, // SHL
        LuaOpcodeMode.ABC, // SHR
        LuaOpcodeMode.ABC, // MMBIN
        LuaOpcodeMode.ABC, // MMBINI
        LuaOpcodeMode.ABC, // MMBINK
        LuaOpcodeMode.ABC, // UNM
        LuaOpcodeMode.ABC, // BNOT
        LuaOpcodeMode.ABC, // NOT
        LuaOpcodeMode.ABC, // LEN
        LuaOpcodeMode.ABC, // CONCAT
        LuaOpcodeMode.ABC, // CLOSE
        LuaOpcodeMode.ABC, // TBC
        LuaOpcodeMode.SJ, // JMP
        LuaOpcodeMode.ABC, // EQ
        LuaOpcodeMode.ABC, // LT
        LuaOpcodeMode.ABC, // LE
        LuaOpcodeMode.ABC, // EQK
        LuaOpcodeMode.ABC, // EQI
        LuaOpcodeMode.ABC, // LTI
        LuaOpcodeMode.ABC, // LEI
        LuaOpcodeMode.ABC, // GTI
        LuaOpcodeMode.ABC, // GEI
        LuaOpcodeMode.ABC, // TEST
        LuaOpcodeMode.ABC, // TESTSET
        LuaOpcodeMode.ABC, // CALL
        LuaOpcodeMode.ABC, // TAILCALL
        LuaOpcodeMode.ABC, // RETURN
        LuaOpcodeMode.ABC, // RETURN0
        LuaOpcodeMode.ABC, // RETURN1
        LuaOpcodeMode.ABX, // FORLOOP
        LuaOpcodeMode.ABX, // FORPREP
        LuaOpcodeMode.ABX, // TFORPREP
        LuaOpcodeMode.ABC, // TFORCALL
        LuaOpcodeMode.ABX, // TFORLOOP
        LuaOpcodeMode.VABC, // SETLIST
        LuaOpcodeMode.ABX, // CLOSURE
        LuaOpcodeMode.ABC, // VARARG
        LuaOpcodeMode.ABC, // GETVARG
        LuaOpcodeMode.ABX, // ERRNNIL
        LuaOpcodeMode.ABC, // VARARGPREP
        LuaOpcodeMode.AX, // EXTRAARG
    )

    /** The operand layout of [opcode], or `null` when the opcode is out of range. */
    fun modeOf(version: LuaBytecodeVersion, opcode: Int): LuaOpcodeMode? = when (version) {
        LuaBytecodeVersion.LUA_5_3 -> MODES_LUA_5_3.getOrNull(opcode)
        LuaBytecodeVersion.LUA_5_4 -> MODES_LUA_5_4.getOrNull(opcode)
        LuaBytecodeVersion.LUA_5_5 -> MODES_LUA_5_5.getOrNull(opcode)
    }

    /** Number of opcodes of [version]. */
    fun count(version: LuaBytecodeVersion): Int = when (version) {
        LuaBytecodeVersion.LUA_5_3 -> MODES_LUA_5_3.size
        LuaBytecodeVersion.LUA_5_4 -> MODES_LUA_5_4.size
        LuaBytecodeVersion.LUA_5_5 -> MODES_LUA_5_5.size
    }
}
