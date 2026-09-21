package io.github.dingyi222666.luabytecode.bytecode.analysis

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
// retargeted ships with :lua-bytecode-edit (same package, other module) — the
// explicit import keeps the dependency visible.
import io.github.dingyi222666.luabytecode.bytecode.analysis.retargeted

/**
 * Constant table analysis: which operands actually reference a constant.
 *
 * `lopcodes.h` declares per opcode whether an argument is a register, an
 * immediate or an `RK`/`K` value, but only in comments — the `luaP_opmodes`
 * table does not encode it. The tables below are transcribed from the operand
 * descriptions of Lua 5.3.6 / 5.4.6 / 5.5.1 and are validated by
 * `LuaConstantReferencesTest`, which checks every operand against the kind of
 * constant it points to in real `luac` output.
 *
 * This matters for any transformation that rewrites the constant pool: a
 * constant that is still referenced as a *key* (a field or global name) must
 * not be encrypted in place, while a constant that is only ever `LOADK`ed can.
 */

/** Constant indices [this] instruction reads (`K` / `RK` operands). */
fun LuaInstruction.constantOperands(): List<Int> = when (version) {
    LuaBytecodeVersion.LUA_5_3 -> constantOperands53()
    LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> constantOperands54()
}

/** 5.3 `OpArgK` / `OpArgR` semantics. */
private fun LuaInstruction.constantOperands53(): List<Int> = when (opcodeName) {
    "LOADK" -> listOf(bx)

    // `EXTRAARG` carries the constant index of the preceding `LOADKX`.
    "EXTRAARG" -> listOf(ax)

    // R(A) := UpValue[B][RK(C)] / R(A) := R(B)[RK(C)] / R(A) := R(B)[RK(C)]
    "GETTABUP", "GETTABLE", "SELF" -> rk53(c)

    // UpValue[A][RK(B)] := RK(C) / R(A)[RK(B)] := RK(C)
    "SETTABUP", "SETTABLE" -> rk53(b) + rk53(c)

    // R(A) := RK(B) op RK(C)
    "ADD", "SUB", "MUL", "MOD", "POW", "DIV", "IDIV",
    "BAND", "BOR", "BXOR", "SHL", "SHR",
    // if ((RK(B) == RK(C)) ~= A) then pc++
    "EQ", "LT", "LE",
    -> rk53(b) + rk53(c)

    else -> emptyList()
}

/**
 * `BITRK`: the 9th bit of a 5.3 `RK` operand flags a constant, the low bits are
 * then its constant index (`INDEXK` in `lobject.h`).
 */
private const val RK_BIT = 1 shl 8

/** Constant indices behind a 5.3 `RK` operand (empty for a plain register). */
private fun rk53(operand: Int): List<Int> =
    if (operand and RK_BIT != 0) listOf(operand and RK_BIT.inv()) else emptyList()

/**
 * 5.4 / 5.5 `K` operand semantics.
 *
 * The plain opcodes take registers only: `GETTABLE`'s `C` and `SETTABLE`'s `C`
 * are *registers* (the constant key variants are `GETFIELD` / `GETI`), so they
 * must never be read as constant indices — doing so marked an arbitrary
 * constant (usually `K0`) as "shared" and silently disabled its encryption
 * (found in goal round 7: `bank`'s `"statement for %s"` stayed in the clear).
 * `SETTABUP` / `SETFIELD` / `SETI` / `SETTABLE` still carry a `k`-flagged `RK`
 * value operand, so `C` counts as a constant only when the `k` bit is set.
 */
private fun LuaInstruction.constantOperands54(): List<Int> = when (opcodeName) {
    "LOADK" -> listOf(bx)

    // Constant index of the preceding `LOADKX`.
    "EXTRAARG" -> listOf(ax)

    // R[A] := UpValue[B][K[C]:string] / R[A] := R[B][K[C]:string]
    "GETTABUP", "GETFIELD" -> listOf(c)
    "SELF" -> if (version == LuaBytecodeVersion.LUA_5_5) listOf(c) else rk54(c, k)

    // UpValue[A][K[B]:string] := RK(C) / R[A][K[B]:string] := RK(C)
    "SETTABUP", "SETFIELD" -> listOf(b) + rk54(c, k)

    // R[A][R[B]] := RK(C) / R[A][B] := RK(C)
    "SETTABLE", "SETI" -> rk54(c, k)

    // R[B] op K[C]
    "ADDK", "SUBK", "MULK", "MODK", "POWK", "DIVK", "IDIVK",
    "BANDK", "BORK", "BXORK",
    -> listOf(c)

    // R[A] == K[B] / call C metamethod over R[A] and K[B]
    "EQK", "MMBINK" -> listOf(b)

    // A Bx : Bx - 1 is the index of the global name constant (5.5 only)
    "ERRNNIL" -> if (bx > 0) listOf(bx - 1) else emptyList()

    else -> emptyList()
}

/**
 * Constant index behind a 5.4 / 5.5 `RK` operand: those carry the constant only
 * when the `k` flag is set, otherwise the operand is a plain register.
 */
private fun rk54(operand: Int, k: Int): List<Int> = if (k != 0) listOf(operand) else emptyList()

/**
 * Every constant index [this] prototype reads through an instruction other than
 * `LOADK` (and `LOADKX`).
 *
 * Those constants are *shared*: a string may be loaded as a value in one place
 * and used as a field key somewhere else, so they must stay in the clear.
 */
fun LuaPrototype.referencedConstantIndices(): Set<Int> {
    val referenced = HashSet<Int>()
    for (instruction in code) {
        // `LOADKX` and `EXTRAARG` belong to the LOADK family (their constant is
        // encrypted in place through the site scan), and an `EXTRAARG` after
        // `NEWTABLE` / `SETLIST` is a *size hint*, not a constant at all — reading
        // it as one marked an arbitrary constant (usually `K0`) as shared and
        // silently disabled its encryption (goal round 7).
        if (instruction.opcodeName == "LOADK" ||
            instruction.opcodeName == "LOADKX" ||
            instruction.opcodeName == "EXTRAARG"
        ) {
            continue
        }
        referenced += instruction.constantOperands()
    }
    return referenced
}

/** Constant indices loaded by a `LOADK` instruction, with every site. */
fun LuaPrototype.loadKSites(): Map<Int, List<Int>> {
    val sites = LinkedHashMap<Int, MutableList<Int>>()
    for (pc in code.indices) {
        val instruction = code[pc]
        if (instruction.opcodeName != "LOADK") {
            continue
        }
        if (instruction.bx >= constants.size) {
            continue
        }
        sites.getOrPut(instruction.bx) { mutableListOf() } += pc
    }
    return sites
}

/**
 * Absolute index this instruction transfers control to, or `null` when it does
 * not jump.
 *
 * The bias depends on the version and on the opcode: 5.3 uses signed `sBx` for
 * every jump, 5.4 / 5.5 use `sJ` for `JMP` and *unsigned* `Bx` distances for the
 * loop opcodes (`pc -= Bx`, `pc += Bx + 1`).
 */
fun LuaInstruction.jumpTarget(pc: Int): Int? = when (opcodeName) {
    "JMP" -> when (version) {
        LuaBytecodeVersion.LUA_5_3 -> pc + 1 + sbx
        LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> pc + 1 + sj
    }

    "FORLOOP", "TFORLOOP" -> when (version) {
        LuaBytecodeVersion.LUA_5_3 -> pc + 1 + sbx
        LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> pc + 1 - bx
    }

    "FORPREP" -> when (version) {
        LuaBytecodeVersion.LUA_5_3 -> pc + 1 + sbx

        // Fetch has already advanced pc. An empty loop then adds Bx + 1,
        // so the taken successor is the instruction AFTER FORLOOP.
        LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> pc + 2 + bx
    }

    // 5.4 / 5.5 `TFORPREP A Bx` transfers control onto the `TFORCALL` that
    // follows the loop header (`pc += Bx; i = *(pc++)`) instead of falling
    // through, so it must stay inside its basic block.
    "TFORPREP" -> when (version) {
        LuaBytecodeVersion.LUA_5_3 -> null
        LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> pc + 1 + bx
    }

    else -> null
}

/** `true` when this instruction conditionally skips the *next* instruction. */
val LuaInstruction.skipsNextInstruction: Boolean
    get() = when (opcodeName) {
        "EQ", "LT", "LE" -> true
        "TEST" -> true
        "TESTSET" -> true
        "EQK", "EQI", "LTI", "LEI", "GTI", "GEI" -> version != LuaBytecodeVersion.LUA_5_3
        "LOADBOOL" -> version == LuaBytecodeVersion.LUA_5_3 && c != 0
        "LFALSESKIP" -> version != LuaBytecodeVersion.LUA_5_3
        else -> false
    }

/** `true` when this instruction is an unconditional jump. */
val LuaInstruction.isUnconditionalJump: Boolean
    get() = opcodeName == "JMP"

/**
 * This instruction moved from [fromPc] to [toPc], keeping the same target.
 *
 * Lives next to [jumpTarget] (its dependency); [retargeted] itself ships with
 * `:lua-bytecode-edit`, which the root module depends on.
 */
fun LuaInstruction.relocated(fromPc: Int, toPc: Int): LuaInstruction {
    val target = jumpTarget(fromPc) ?: return this
    return retargeted(fromPc, toPc, target)
}

/** Every jump of this prototype as `pc to target` pairs. */
fun LuaPrototype.jumpTargets(): Map<Int, Int> = buildMap {
    for (pc in code.indices) {
        code[pc].jumpTarget(pc)?.let { put(pc, it) }
    }
}

/**
 * Instruction indices that start a basic block: the entry, every jump target and
 * the instruction after a conditional skip.
 */
fun LuaPrototype.basicBlockStarts(): Set<Int> {
    val starts = sortedSetOf(0)
    for (pc in code.indices) {
        code[pc].jumpTarget(pc)?.let { target ->
            if (target in 0..code.size) {
                starts += target
            }
        }
        if (code[pc].skipsNextInstruction && pc + 2 <= code.size) {
            starts += pc + 2
        }
    }
    return starts
}
