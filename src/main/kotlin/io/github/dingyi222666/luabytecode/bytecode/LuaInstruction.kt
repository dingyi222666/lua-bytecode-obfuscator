package io.github.dingyi222666.luabytecode.bytecode


/**
 * One 32 bit Lua VM instruction, kept in its raw (undecoded) form.
 *
 * Field layouts from `lopcodes.h`:
 *
 * ```
 * 5.3: POS_OP=0 SIZE_OP=6 | POS_A=6 SIZE_A=8 | POS_C=14 SIZE_C=9 | POS_B=23 SIZE_B=9
 *      POS_Bx=14 SIZE_Bx=18, POS_Ax=6 SIZE_Ax=26
 * 5.4: POS_OP=0 SIZE_OP=7 | POS_A=7 SIZE_A=8 | POS_k=15 SIZE_k=1
 *      POS_B=16 SIZE_B=8 | POS_C=24 SIZE_C=8
 *      POS_Bx=15 SIZE_Bx=17, POS_Ax=7 SIZE_Ax=25, POS_sJ=7 SIZE_sJ=25
 * 5.5: like 5.4, plus the `ivABC` layout used by NEWTABLE / SETLIST:
 *      POS_vB=16 SIZE_vB=6, POS_vC=22 SIZE_vC=10
 * ```
 *
 * Instances are immutable values; use the `with...` methods to derive a patched
 * instruction or the companion factories to build a new one:
 *
 * ```kotlin
 * val loadK = LuaInstruction.abx(version, opcode = LuaOpcodes.opcodeOf(version, "LOADK")!!, a = 0, bx = 3)
 * code[pc] = code[pc].withA(2)
 * ```
 */
data class LuaInstruction(val raw: Int, val version: LuaBytecodeVersion) {

    /** The operation code: 6 bits in 5.3, 7 bits in 5.4 and 5.5. */
    val opcode: Int
        get() = when (version) {
            LuaBytecodeVersion.LUA_5_3 -> raw and 0x3F
            LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> raw and 0x7F
        }

    /** Human readable opcode name for [version]. */
    val opcodeName: String
        get() = LuaOpcodes.nameOf(version, opcode)

    /** `A` operand (register A). */
    val a: Int
        get() = when (version) {
            LuaBytecodeVersion.LUA_5_3 -> (raw ushr 6) and 0xFF
            LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> (raw ushr 7) and 0xFF
        }

    /** `B` operand. */
    val b: Int
        get() = when (version) {
            LuaBytecodeVersion.LUA_5_3 -> (raw ushr 23) and 0x1FF
            LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> (raw ushr 16) and 0xFF
        }

    /** `C` operand. */
    val c: Int
        get() = when (version) {
            LuaBytecodeVersion.LUA_5_3 -> (raw ushr 14) and 0x1FF
            LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> (raw ushr 24) and 0xFF
        }

    /** `k` flag (5.4 / 5.5 only, always 0 for 5.3). */
    val k: Int
        get() = when (version) {
            LuaBytecodeVersion.LUA_5_3 -> 0
            LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> (raw ushr 15) and 0x1
        }

    /** `vB` operand of the 5.5 `ivABC` layout (6 bits). */
    val vB: Int
        get() = when (version) {
            LuaBytecodeVersion.LUA_5_5 -> (raw ushr 16) and 0x3F
            else -> b
        }

    /** `vC` operand of the 5.5 `ivABC` layout (10 bits). */
    val vC: Int
        get() = when (version) {
            LuaBytecodeVersion.LUA_5_5 -> (raw ushr 22) and 0x3FF
            else -> c
        }

    /** `Bx` operand (`B` and `C` combined). */
    val bx: Int
        get() = when (version) {
            LuaBytecodeVersion.LUA_5_3 -> (raw ushr 14) and 0x3FFFF
            LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> (raw ushr 15) and 0x1FFFF
        }

    /** `Ax` operand (used by `EXTRAARG`). */
    val ax: Int
        get() = when (version) {
            LuaBytecodeVersion.LUA_5_3 -> (raw ushr 6) and 0x3FFFFFF
            LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> (raw ushr 7) and 0x1FFFFFF
        }

    /** Signed `Bx` (jump offset of `FORLOOP` / `FORPREP` / `TFORLOOP`). */
    val sbx: Int
        get() = when (version) {
            LuaBytecodeVersion.LUA_5_3 -> bx - 131071
            LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> bx - 65535
        }

    /** Signed `sJ` (5.4 / 5.5 `JMP` jump offset). */
    val sj: Int
        get() = when (version) {
            LuaBytecodeVersion.LUA_5_3 -> throw UnsupportedOperationException("sJ does not exist in Lua 5.3")
            LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> ((raw ushr 7) and 0x1FFFFFF) - 16777215
        }

    /** Signed `B` (5.4 / 5.5, used by `ADDI` / `MMBINI`). */
    val signedB: Int
        get() = b - 127

    /** Signed `C` (5.4 / 5.5, used by `SHRI` / `SHLI`). */
    val signedC: Int
        get() = c - 127

    /** Operand layout of this instruction (`luaP_opmodes`). */
    val mode: LuaOpcodeMode?
        get() = LuaOpcodeModes.modeOf(version, opcode)

    // ------------------------------------------------------------- patching

    /** This instruction with another [opcode]. */
    fun withOpcode(opcode: Int): LuaInstruction =
        copy(raw = setField(raw, opcode, OPCODE_POSITION, opcodeSize))

    /** This instruction with another `A` operand. */
    fun withA(a: Int): LuaInstruction =
        copy(raw = setField(raw, a, aPosition, A_SIZE))

    /** This instruction with another `B` operand. */
    fun withB(b: Int): LuaInstruction = when (version) {
        LuaBytecodeVersion.LUA_5_3 -> copy(raw = setField(raw, b, 23, 9))
        LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> copy(raw = setField(raw, b, 16, 8))
    }

    /** This instruction with another `C` operand. */
    fun withC(c: Int): LuaInstruction = when (version) {
        LuaBytecodeVersion.LUA_5_3 -> copy(raw = setField(raw, c, 14, 9))
        LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> copy(raw = setField(raw, c, 24, 8))
    }

    /** This instruction with another `k` flag (5.4 / 5.5 only). */
    fun withK(k: Int): LuaInstruction {
        require(version != LuaBytecodeVersion.LUA_5_3) { "Lua 5.3 has no k flag" }
        return copy(raw = setField(raw, k, 15, 1))
    }

    /** This instruction with another `vB` operand (5.5 `ivABC`, otherwise `B`). */
    fun withVB(vB: Int): LuaInstruction = if (version == LuaBytecodeVersion.LUA_5_5) {
        copy(raw = setField(raw, vB, 16, 6))
    } else {
        withB(vB)
    }

    /** This instruction with another `vC` operand (5.5 `ivABC`, otherwise `C`). */
    fun withVC(vC: Int): LuaInstruction = if (version == LuaBytecodeVersion.LUA_5_5) {
        copy(raw = setField(raw, vC, 22, 10))
    } else {
        withC(vC)
    }

    /** This instruction with another `Bx` operand. */
    fun withBx(bx: Int): LuaInstruction = when (version) {
        LuaBytecodeVersion.LUA_5_3 -> copy(raw = setField(raw, bx, 14, 18))
        LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> copy(raw = setField(raw, bx, 15, 17))
    }

    /** This instruction with another signed `sBx` operand. */
    fun withSbx(sbx: Int): LuaInstruction = withBx(sbx + sBxOffset)

    /** This instruction with another `Ax` operand. */
    fun withAx(ax: Int): LuaInstruction = when (version) {
        LuaBytecodeVersion.LUA_5_3 -> copy(raw = setField(raw, ax, 6, 26))
        LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> copy(raw = setField(raw, ax, 7, 25))
    }

    /** This instruction with another signed `sJ` operand (5.4 / 5.5 only). */
    fun withSj(sj: Int): LuaInstruction {
        require(version != LuaBytecodeVersion.LUA_5_3) { "Lua 5.3 has no sJ operand" }
        return copy(raw = setField(raw, sj + sJOffset, 7, 25))
    }

    // ------------------------------------------------------------ formatting

    /**
     * Formats the operands according to [mode], so `LOADK` shows `Bx`, `JMP`
     * shows its jump offset and so on.
     */
    fun formatOperands(): String = when (mode) {
        LuaOpcodeMode.ABC -> when (version) {
            LuaBytecodeVersion.LUA_5_3 -> "A=$a B=$b C=$c"
            LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> "A=$a B=$b C=$c k=$k"
        }

        LuaOpcodeMode.VABC -> "A=$a vB=$vB vC=$vC k=$k"
        LuaOpcodeMode.ABX -> "A=$a Bx=$bx"
        LuaOpcodeMode.ASBX -> "A=$a sBx=$sbx"
        LuaOpcodeMode.AX -> "Ax=$ax"
        LuaOpcodeMode.SJ -> "sJ=$sj"
        null -> "raw=0x%08X".format(raw)
    }

    /** Raw bit fields, useful when debugging the decoder itself. */
    fun describeRawOperands(): String = buildString {
        append("A=").append(a)
        append(" B=").append(b)
        append(" C=").append(c)
        if (version != LuaBytecodeVersion.LUA_5_3) {
            append(" k=").append(k)
        }
    }

    override fun toString(): String = "$opcodeName ${formatOperands()}"

    // ------------------------------------------------------------ internals

    private val opcodeSize: Int
        get() = if (version == LuaBytecodeVersion.LUA_5_3) 6 else 7

    private val aPosition: Int
        get() = if (version == LuaBytecodeVersion.LUA_5_3) 6 else 7

    /** `OFFSET_sBx` — `MAXARG_sBx`, the bias of the signed `Bx` field. */
    private val sBxOffset: Int
        get() = if (version == LuaBytecodeVersion.LUA_5_3) 131071 else 65535

    /** `OFFSET_sJ` — the bias of the 25 bit signed `sJ` field. */
    private val sJOffset: Int
        get() = 16777215

    /** Replaces a bit field, rejecting values that do not fit. */
    private fun setField(raw: Int, value: Int, position: Int, size: Int): Int {
        val max = (1 shl size) - 1
        require(value in 0..max) { "value $value does not fit in a $size bit field" }
        val mask = max shl position
        return (raw and mask.inv()) or ((value shl position) and mask)
    }

    companion object {
        private const val OPCODE_POSITION = 0
        private const val A_SIZE = 8

        /** An instruction carrying only [opcode]. */
        @JvmStatic
        fun of(version: LuaBytecodeVersion, opcode: Int): LuaInstruction =
            LuaInstruction(opcode, version).withOpcode(opcode)

        /** Builds an `iABC` instruction. `k` is ignored (and must be 0) for 5.3. */
        @JvmStatic
        @JvmOverloads
        fun abc(
            version: LuaBytecodeVersion,
            opcode: Int,
            a: Int = 0,
            b: Int = 0,
            c: Int = 0,
            k: Int = 0,
        ): LuaInstruction {
            val instruction = of(version, opcode).withA(a).withB(b).withC(c)
            return if (version == LuaBytecodeVersion.LUA_5_3) {
                require(k == 0) { "Lua 5.3 has no k flag" }
                instruction
            } else {
                instruction.withK(k)
            }
        }

        /** Builds an `iABx` instruction. */
        @JvmStatic
        fun abx(version: LuaBytecodeVersion, opcode: Int, a: Int, bx: Int): LuaInstruction =
            of(version, opcode).withA(a).withBx(bx)

        /** Builds an `iAsBx` instruction. */
        @JvmStatic
        fun asbx(version: LuaBytecodeVersion, opcode: Int, a: Int, sbx: Int): LuaInstruction =
            of(version, opcode).withA(a).withSbx(sbx)

        /** Builds an `iAx` instruction. */
        @JvmStatic
        fun ax(version: LuaBytecodeVersion, opcode: Int, ax: Int): LuaInstruction =
            of(version, opcode).withAx(ax)

        /** Builds an `isJ` instruction (5.4 / 5.5 only). */
        @JvmStatic
        fun sj(version: LuaBytecodeVersion, opcode: Int, sj: Int): LuaInstruction =
            of(version, opcode).withSj(sj)

        /** Builds a 5.5 `ivABC` instruction. */
        @JvmStatic
        @JvmOverloads
        fun vabc(
            version: LuaBytecodeVersion,
            opcode: Int,
            a: Int = 0,
            vB: Int = 0,
            vC: Int = 0,
            k: Int = 0,
        ): LuaInstruction {
            require(version == LuaBytecodeVersion.LUA_5_5) { "ivABC only exists in Lua 5.5" }
            return of(version, opcode).withA(a).withVB(vB).withVC(vC).withK(k)
        }
    }
}

/**
 * Builds a [LuaInstruction] with a small DSL, picking the operands that match
 * the layout of [LuaInstructionBuilder.opcode]:
 *
 * ```kotlin
 * val jump = luaInstruction(version) {
 *     opcode = LuaOpcodes.opcodeOf(version, "JMP")!!
 *     sj = -3
 * }
 * ```
 */
class LuaInstructionBuilder(val version: LuaBytecodeVersion) {

    /** The opcode; determines which operands [build] uses. */
    var opcode: Int = 0

    /** Operand `A`. */
    var a: Int = 0

    /** Operand `B` of `iABC`. */
    var b: Int = 0

    /** Operand `C` of `iABC`. */
    var c: Int = 0

    /** The 5.4 / 5.5 `k` flag. */
    var k: Int = 0

    /** Operand `vB` of the 5.5 `ivABC` layout. */
    var vB: Int = 0

    /** Operand `vC` of the 5.5 `ivABC` layout. */
    var vC: Int = 0

    /** Operand `Bx`. */
    var bx: Int = 0

    /** Signed operand `sBx`. */
    var sbx: Int = 0

    /** Operand `Ax`. */
    var ax: Int = 0

    /** Signed operand `sJ`. */
    var sj: Int = 0

    /** The opcode name, for readable assertions and error messages. */
    val opcodeName: String
        get() = LuaOpcodes.nameOf(version, opcode)

    /** Builds the instruction using the operands [mode] requires. */
    fun build(): LuaInstruction = when (LuaOpcodeModes.modeOf(version, opcode)) {
        LuaOpcodeMode.ABC -> LuaInstruction.abc(version, opcode, a, b, c, k)
        LuaOpcodeMode.VABC -> LuaInstruction.vabc(version, opcode, a, vB, vC, k)
        LuaOpcodeMode.ABX -> LuaInstruction.abx(version, opcode, a, bx)
        LuaOpcodeMode.ASBX -> LuaInstruction.asbx(version, opcode, a, sbx)
        LuaOpcodeMode.AX -> LuaInstruction.ax(version, opcode, ax)
        LuaOpcodeMode.SJ -> LuaInstruction.sj(version, opcode, sj)
        null -> throw IllegalArgumentException("unknown opcode $opcode for ${version.displayName}")
    }
}

/** Builds an instruction with the [LuaInstructionBuilder] DSL. */
fun luaInstruction(
    version: LuaBytecodeVersion,
    block: LuaInstructionBuilder.() -> Unit,
): LuaInstruction = LuaInstructionBuilder(version).apply(block).build()
