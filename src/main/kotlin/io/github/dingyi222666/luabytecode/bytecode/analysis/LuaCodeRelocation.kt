package io.github.dingyi222666.luabytecode.bytecode.analysis

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaOpcodes
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaInstructionFactory

/**
 * This instruction re-encoded so that it transfers control to [target] from
 * [toPc].
 *
 * The `A` operand of a 5.3 / 5.4 `JMP` is preserved: it tells the VM to close
 * the upvalues of registers `>= A - 1`, which is how `break` leaves a loop.
 */
fun LuaInstruction.retargeted(fromPc: Int, toPc: Int, target: Int): LuaInstruction = when (opcodeName) {
    "JMP" -> when (version) {
        LuaBytecodeVersion.LUA_5_3 ->
            // 5.3 `JMP` is iAsBx: `A>0` closes the upvalues of registers
            // `>= A - 1` (how `break` leaves a loop), so it is preserved.
            LuaInstructionFactory.asbx(version, opcode, a, target - (toPc + 1))

        // 5.4 / 5.5 `JMP` is isJ: it has **no** `A` operand at all - the field
        // would overlap `sJ`. Upvalue closing there is a separate explicit
        // `CLOSE` instruction, so building a fresh `sj` (A = 0) is correct.
        LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 ->
            LuaInstructionFactory.sj(version, opcode, sj = target - (toPc + 1))
    }

    "FORLOOP", "TFORLOOP" -> when (version) {
        LuaBytecodeVersion.LUA_5_3 ->
            LuaInstructionFactory.asbx(version, opcode, a, target - (toPc + 1))

        LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> {
            val distance = toPc + 1 - target
            require(distance >= 0) {
                "5.4 / 5.5 loop jumps only go backwards: target $target from $toPc"
            }
            LuaInstructionFactory.abx(version, opcode, a, distance)
        }
    }

    "FORPREP" -> when (version) {
        LuaBytecodeVersion.LUA_5_3 ->
            LuaInstructionFactory.asbx(version, opcode, a, target - (toPc + 1))

        // Inverse of the VM's empty-loop successor: original PC + 2 + Bx.
        LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> {
            val distance = target - (toPc + 2)
            require(distance >= 0) {
                "5.4 / 5.5 FORPREP only jumps forward: target $target from $toPc"
            }
            LuaInstructionFactory.abx(version, opcode, a, distance)
        }
    }

    // `TFORPREP` jumps onto the `TFORCALL` that follows the loop header. It had **no**
    // branch here at all, so it fell through to `else -> this`: it kept its original
    // `Bx` while the layout moved, and every 5.4 / 5.5 generic `for` therefore jumped to
    // the wrong place (`c05_generic_ipairs_54`: "attempt to call a number value").
    // Same unsigned-field wrap hazard as FORPREP: forward-only, refuse otherwise.
    "TFORPREP" -> when (version) {
        LuaBytecodeVersion.LUA_5_3 -> this

        LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> {
            val distance = target - (toPc + 1)
            require(distance >= 0) {
                "5.4 / 5.5 TFORPREP only jumps forward: target $target from $toPc"
            }
            LuaInstructionFactory.abx(version, opcode, a, distance)
        }
    }

    else -> this
}

/** This instruction moved from [fromPc] to [toPc], keeping the same target. */
fun LuaInstruction.relocated(fromPc: Int, toPc: Int): LuaInstruction {
    val target = jumpTarget(fromPc) ?: return this
    return retargeted(fromPc, toPc, target)
}

/** A fresh `JMP` from [fromPc] to [target]. */
fun jumpTo(version: LuaBytecodeVersion, fromPc: Int, target: Int): LuaInstruction =
    LuaInstructionFactory.of(version, requireNotNull(LuaOpcodes.opcodeOf(version, "JMP"))).retargeted(fromPc, fromPc, target)
