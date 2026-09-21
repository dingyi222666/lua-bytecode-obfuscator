package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.platform.Platform
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTarget

/**
 * P4.1: translates a Lua prototype into the private ISA of [LuaVmProgram] (docs section 11).
 *
 * The translation is deliberately *not* a renumbering of Lua opcodes: a `compare + JMP` pair
 * becomes an explicit `CMP` into a scratch register plus a `JMPIF`/`JMPIFNOT`, and every
 * opcode number is drawn from a per chunk random bijection, so the resulting program contains
 * no recognisable Lua opcode stream (Rolles WOOT'09: opcode reordering alone leaves the
 * original instruction literals intact and is recoverable with regexes).
 *
 * Only a subset is supported today; an unsupported opcode makes [translate] return `null`
 * instead of emitting something wrong.
 */
object LuaVmTranslator {
    /** A translated program plus the constant pool its `LOADK`s index. */
    data class VmProgram(
        val words: IntArray,
        val constants: List<LuaConstant>,
        val maxStackSize: Int,
        val numParams: Int,
        val isVararg: Boolean,
        /** how many P4.3 super instructions the translation produced */
        val superInstructions: Int = 0,
        /** nested prototypes, translated as separate VM programs (index = the CLOSURE operand) */
        val children: List<VmProgram> = emptyList(),
        /** Captured-cell descriptors parallel to [children]. */
        val childCaptures: List<List<Capture>> = emptyList(),
        /**
         * Where the numeric `for` keeps its control variable: `3` for 5.3/5.4 (`R[A+3]`) and
         * `2` for 5.5, which moved it one register down and reuses `R[A+2]` for the step.
         */
        val controlOffset: Int = 3,
        /** Reachable static return counts; -1 denotes a dynamic result sequence. */
        val returns: List<Int> = emptyList(),
        val sourceStackSize: Int = maxStackSize,
        val varargTable: Boolean = false,
        /** Number of upvalue cells required by the translated prototype, including injected
         * decoder cells produced by the regular obfuscation pipeline. */
        val upvalueCount: Int = 0,
    ) {
        data class Capture(val inStack: Boolean, val index: Int)

        override fun equals(other: Any?): Boolean =
            other is VmProgram && words.contentEquals(other.words) && constants == other.constants

        override fun hashCode(): Int = words.contentHashCode() * 31 + constants.hashCode()
    }

    private val SIMPLE = mapOf(
        "MOVE" to LuaVmProgram.Op.MOVE,
        "GETTABLE" to LuaVmProgram.Op.GETTABLE,
        "SETTABLE" to LuaVmProgram.Op.SETTABLE,
        "ADD" to LuaVmProgram.Op.ADD,
        "SUB" to LuaVmProgram.Op.SUB,
        "MUL" to LuaVmProgram.Op.MUL,
        "DIV" to LuaVmProgram.Op.DIV,
        "IDIV" to LuaVmProgram.Op.IDIV,
        "MOD" to LuaVmProgram.Op.MOD,
        "POW" to LuaVmProgram.Op.POW,
        "BAND" to LuaVmProgram.Op.BAND,
        "BOR" to LuaVmProgram.Op.BOR,
        "BXOR" to LuaVmProgram.Op.BXOR,
        "SHL" to LuaVmProgram.Op.SHL,
        "SHR" to LuaVmProgram.Op.SHR,
    )

    /**
     * Compares `R[b] <op> R[c]` into register `a` (1 or 0). The Lua compare + following `JMP`
     * pair is folded into `CMP` + `JMPIF`/`JMPIFNOT` so the VM never has to model the skip
     * polarity of three different Lua versions.
     */
    private val IMMEDIATE_COMPARE = setOf("EQI", "LTI", "LEI", "GTI", "GEI")
    private val CONSTANT_COMPARE = setOf("EQK", "LTK", "LEK", "GTK", "GEK")
    private val IMMEDIATE_ARITH = setOf("ADDI", "SUBI", "MULI", "BANDI", "BORI", "BXORI")
    private val CONSTANT_ARITH = setOf("ADDK", "SUBK", "MULK", "DIVK", "IDIVK", "MODK", "POWK", "BANDK", "BORK", "BXORK")

    private val COMPARE = mapOf(
        "EQ" to LuaVmProgram.Op.EQ,
        "LT" to LuaVmProgram.Op.LT,
        "LE" to LuaVmProgram.Op.LE,
    )

    /** Writes a refusal reason to the developer trace when the file switch is on. */
    private fun reportRefusal(reason: String) {
        Platform.traceSink.append("refused: $reason")
    }

    private class EncodingLimit(message: String) : RuntimeException(message)

    /** The private ISA has byte-sized operands. Reject oversized programs without wrapping fields. */
    private fun instruction(op: LuaVmProgram.Op, a: Int, b: Int, c: Int, x: Int = 0): LuaVmProgram.Insn {
        if (a !in 0..255 || b !in 0..255 || c !in 0..255 || (op.hasX && x !in Short.MIN_VALUE..Short.MAX_VALUE))
            throw EncodingLimit("private ISA operand exceeds encoding range: $op ($a,$b,$c,$x)")
        return LuaVmProgram.Insn(op, a, b, c, x)
    }

    fun translate(
        prototype: LuaPrototype,
        map: LuaVmProgram.OpcodeMap,
        scratch: Int = prototype.maxStackSize,
        enableCountBasedLoops: Boolean = false,
    ): VmProgram? = try {
        translateProgram(prototype, map, scratch, enableCountBasedLoops)
    } catch (failure: EncodingLimit) {
        reportRefusal(failure.message.orEmpty())
        null
    }

    private fun translateProgram(
        prototype: LuaPrototype,
        map: LuaVmProgram.OpcodeMap,
        scratch: Int = prototype.maxStackSize,
        /** New semantic capabilities are opt-in until their full version matrix is green. */
        enableCountBasedLoops: Boolean = false,
    ): VmProgram? {
        // children are translated first so `CLOSURE` can reference them by index
        val children = ArrayList<VmProgram>()
        val childCaptures = ArrayList<List<VmProgram.Capture>>()
        for (child in prototype.nestedPrototypes) {
            children += translate(child, map, child.maxStackSize, enableCountBasedLoops) ?: return null
            childCaptures += child.upvalues.map { VmProgram.Capture(it.inStack, it.index) }
        }
        val code = prototype.code
        val constants = prototype.constants
        val controlOffset = if (prototype.version == LuaBytecodeVersion.LUA_5_5) 2 else 3
        val words = ArrayList<Int>(code.size + 8)
        val pending = ArrayList<Triple<LuaVmProgram.Op, Int, Int>>() // op, a, target pc
        val out = ArrayList<LuaVmProgram.Insn>()

        // every refusal explains itself (the `else ->` branch already reports the opcode):
        // a black box refusal costs a round trip, a named one costs a minute
        fun refuse(reason: String): VmProgram? {
            Platform.traceSink.append("refused: " + reason + " src=" + prototype.source + ":" + prototype.lineDefined)
            return null
        }

        fun emit(insn: LuaVmProgram.Insn) { out += insn }

        // A constant operand is encoded as `256 + k` (`RK` style; Lua 5.3's listing prints it
        // as `-1 - k`). Every distinct constant operand is materialised **once, in a prologue**
        // instead of next to its consumer: a per site load sits between two branch targets and
        // a jump into the following instruction skipped it (`R4` was nil when the EQ ran, so
        // `classify(0)` answered "pos").
        // immediates are interned as extra constants so the prologue can materialise them
        val extraConstants = ArrayList<LuaConstant>()
        fun internImmediate(value: Long): Int {
            val index = constants.size + extraConstants.size
            extraConstants += LuaConstant.Integer(value)
            return index
        }

        val constRegs = HashMap<Int, Int>()
        // immediates are keyed by value (the same immediate is materialised once)
        val immediateRegs = HashMap<Long, Int>()
        val floatImmediateRegs = HashMap<Long, Int>()
        // integer table keys (SETLIST element indices, GETI/SETI keys), materialised in the
        // prologue and looked up by value so no per-site load can be skipped by a jump
        val keyRegs = HashMap<Long, Int>()
        // VM-CONST-2 (R2 Round3): ONE shared scratch counter. The SIMPLE/COMPARE pre-pass used
        // to bump a second counter that also started at scratch+1, so two different constants
        // could both get `scratch+1` (e.g. k=2 and k=3 -> R4): the second prologue LOADK
        // overwrote the first, and `print(math.maxinteger, math.maxinteger+1)` crashed on a
        // nil operand ("arithmetic on a nil value").
        var scratchCursor = scratch + 1
        fun nextScratchIndex(): Int = scratchCursor++
        fun listBase(pc: Int): Long? {
            val i = code[pc]
            val extraNeeded = if (i.version == LuaBytecodeVersion.LUA_5_3) i.c == 0 else i.k != 0
            val extra = if (extraNeeded) code.getOrNull(pc + 1)?.takeIf { it.opcodeName == "EXTRAARG" }?.ax
                ?: return null else 0
            return when (i.version) {
                LuaBytecodeVersion.LUA_5_3 -> ((if (i.c == 0) extra else i.c).toLong() - 1) * 50
                LuaBytecodeVersion.LUA_5_4 -> i.c.toLong() + extra.toLong() * 256
                LuaBytecodeVersion.LUA_5_5 -> i.vC.toLong() + extra.toLong() * 1024
            }
        }
        fun materializeConstant(index: Int): Int = constRegs.getOrPut(index) {
            nextScratchIndex().also { emit(instruction(LuaVmProgram.Op.LOADK, it, index, 0)) }
        }
        fun materializeImmediate(value: Long): Int = immediateRegs.getOrPut(value) {
            nextScratchIndex().also { emit(instruction(LuaVmProgram.Op.LOADK, it, internImmediate(value), 0)) }
        }
        for ((sourcePc, instruction) in code.withIndex()) {
            val opcodeName = instruction.opcodeName
            // The 5.4/5.5 immediate and constant forms materialise into registers too, and the
            // register has to be filled in the *prologue*: allocating it inside the branch left
            // `EQ scratch, R4, R6` with R6 never written, so `i == 6` could never be true and
            // the break of `c07_break` never fired (the VM summed the whole range: 210 vs 15).
            if (opcodeName in IMMEDIATE_COMPARE) {
                val immediate = instruction.b - 127L
                if (instruction.c != 0 && immediate !in floatImmediateRegs) {
                    val index = constants.size + extraConstants.size
                    extraConstants += LuaConstant.Number(immediate.toDouble())
                    floatImmediateRegs[immediate] = materializeConstant(index)
                }
                if (immediate !in immediateRegs) {
                    val reg = nextScratchIndex()
                    immediateRegs[immediate] = reg
                    emit(instruction(LuaVmProgram.Op.LOADK, reg, internImmediate(immediate), 0))
                }
                continue
            }
            if (opcodeName in IMMEDIATE_ARITH) {
                val immediate = instruction.c - 127L
                if (immediate !in immediateRegs) {
                    val reg = nextScratchIndex()
                    immediateRegs[immediate] = reg
                    emit(instruction(LuaVmProgram.Op.LOADK, reg, internImmediate(immediate), 0))
                }
                continue
            }
            if (opcodeName == "SETLIST") {
                val base = listBase(sourcePc) ?: return refuse("SETLIST missing EXTRAARG")
                keyRegs[base] = materializeImmediate(base)
                continue
            }
            if (opcodeName == "MMBINI") {
                materializeImmediate(instruction.signedB.toLong())
                continue
            }
            if (opcodeName == "MMBINK") {
                materializeConstant(instruction.b)
                continue
            }
            if (opcodeName == "SETTABUP") {
                if (prototype.version == LuaBytecodeVersion.LUA_5_3) {
                    if (instruction.b >= 256) materializeConstant(instruction.b - 256)
                    if (instruction.c >= 256) materializeConstant(instruction.c - 256)
                } else {
                    materializeConstant(instruction.b)
                    if (instruction.k != 0) materializeConstant(instruction.c)
                }
                continue
            }
            if (opcodeName == "GETI" || opcodeName == "SETI") {
                // both are 5.4+ opcodes: `GETI A B C: R[A] := R[B][C]` (int key in C) and
                // `SETI A B C k: R[A][B] := RK(C)` (int key in **B**; k marks C as a constant
                // index). Reading the key from C for SETI swapped key/value: `t[5] = "hello"`
                // became `t[0] = 5` and the printed value was nil (B2 Round4).
                val key = if (opcodeName == "GETI") instruction.c.toLong() else instruction.b.toLong()
                if (key !in keyRegs) {
                    val reg = nextScratchIndex()
                    keyRegs[key] = reg
                    emit(instruction(LuaVmProgram.Op.LOADK, reg, internImmediate(key), 0))
                }
                if (opcodeName == "SETI" && instruction.k != 0) {
                    // the RK(C) value of a SETI can be a constant: materialise it like a *K operand
                    if (instruction.c !in constRegs) {
                        val reg = nextScratchIndex()
                        constRegs[instruction.c] = reg
                        emit(instruction(LuaVmProgram.Op.LOADK, reg, instruction.c, 0))
                    }
                }
                continue
            }
            if (opcodeName == "GETFIELD" || opcodeName == "SETFIELD") {
                // VM-META-1 (R2): the constant key moved between 5.3 and 5.4 --
                //   GETFIELD A B C: key in C on both versions
                //   SETFIELD A B C: key in C on 5.3 (`R[A][K[C]] := R[B]`) but in B on
                //   5.4/5.5 (`R[A][K[B]] := R[C]`). Materialising C for a 5.4 SETFIELD put
                //   `k[3]="print"` in the key slot, the `__add` metamethod never landed on
                //   the metatable and `t + t` crashed with "arithmetic on a table value".
                val keyIndex = if (opcodeName == "SETFIELD" &&
                    prototype.version != LuaBytecodeVersion.LUA_5_3
                ) instruction.b else instruction.c
                if (keyIndex >= constants.size) return refuse("field name index out of range")
                if (keyIndex !in constRegs) {
                    val reg = nextScratchIndex()
                    constRegs[keyIndex] = reg
                    emit(instruction(LuaVmProgram.Op.LOADK, reg, keyIndex, 0))
                }
                if (opcodeName == "SETFIELD" && instruction.k != 0) materializeConstant(instruction.c)
                continue
            }
            if (opcodeName == "SELF") {
                val keyIndex = when (prototype.version) {
                    LuaBytecodeVersion.LUA_5_3 -> if (instruction.c >= 256) instruction.c - 256 else null
                    LuaBytecodeVersion.LUA_5_4 -> if (instruction.k != 0) instruction.c else null
                    LuaBytecodeVersion.LUA_5_5 -> instruction.c
                }
                if (keyIndex != null) materializeConstant(keyIndex)
                continue
            }
            if (opcodeName == "GETTABLE" || opcodeName == "SETTABLE") {
                if (opcodeName == "SETTABLE" && instruction.k != 0) materializeConstant(instruction.c)
                for (value in intArrayOf(instruction.b, instruction.c)) {
                    if (value < 256) continue
                    val k = value - 256
                    if (k >= constants.size) return refuse("table operand out of range")
                    if (constRegs.containsKey(k)) continue
                    val reg = nextScratchIndex()
                    constRegs[k] = reg
                    emit(instruction(LuaVmProgram.Op.LOADK, reg, k, 0))
                }
                continue
            }
            if (opcodeName in CONSTANT_COMPARE || opcodeName in CONSTANT_ARITH) {
                materializeConstant(if (opcodeName in CONSTANT_COMPARE) instruction.b else instruction.c)
                continue
            }
            val needsConstant = opcodeName in SIMPLE || opcodeName in COMPARE
            if (!needsConstant) continue
            for (value in intArrayOf(instruction.b, instruction.c)) {
                if (value < 256) continue
                val k = value - 256
                if (k >= constants.size) return refuse("constant operand out of range")
                if (constRegs.containsKey(k)) continue
                val reg = nextScratchIndex()
                constRegs[k] = reg
                emit(instruction(LuaVmProgram.Op.LOADK, reg, k, 0))
            }
        }
        fun resolveOperand(value: Int): Int? {
            if (value < 256) return value
            return constRegs[value - 256]
        }

        // pc -> index of the word that *implements* that pc. It has to be recorded while
        // emitting, because materialising a constant operand inserts `LOADK` words that the
        // branch targets must not land on (`word[2]` used to jump one word short of the EQ
        // fold, so `classify(0)` fell through to "pos").
        val pcWord = IntArray(code.size) { -1 }

        // pcs that are branch targets: a merge must never swallow one of them
        val jumpTargets = HashSet<Int>()
        for (index in code.indices) code[index].jumpTarget(index)?.let { jumpTargets += it }

        var superInstructions = 0
        // Reachable pcs: a RETURN that cannot be reached does not contribute to the callee's
        // result count. `classify` of `c10_ifchain` returns on every path, so its implicit
        // `RETURN 0 1` is unreachable and `CALL C=0` can be resolved (docs 14.95).
        val reachable = BooleanArray(code.size)
        if (code.isNotEmpty()) {
            val pendingPcs = ArrayDeque<Int>()
            reachable[0] = true
            pendingPcs += 0
            while (pendingPcs.isNotEmpty()) {
                val at = pendingPcs.removeLast()
                val insn = code[at]
                val successors = when (insn.opcodeName) {
                    "RETURN", "RETURN0", "RETURN1" -> emptyList()
                    "JMP" -> listOfNotNull(insn.jumpTarget(at))
                    "FORLOOP", "TFORLOOP" -> listOfNotNull(insn.jumpTarget(at)) + listOf(at + 1)
                    // `FORPREP` (numeric `for`) jumps *forward onto the `FORLOOP`*: it never
                    // falls through into the body (lvm.c 5.3: `ci->u.l.savedpc += GETARG_sBx(i)`).
                    // Modelling it as a fall-through ran the body once with the un-incremented
                    // index, so `for i=1,3 do print(i) end` printed `0 1 2 3`.
                    "FORPREP" -> listOfNotNull(insn.jumpTarget(at))
                    // Generic-for preparation jumps to its iterator call.
                    "TFORPREP" -> listOfNotNull(insn.jumpTarget(at))
                    else -> listOf(at + 1) + if (insn.opcodeName in COMPARE) {
                        listOfNotNull(code.getOrNull(at + 1)?.jumpTarget(at + 1))
                    } else {
                        emptyList()
                    }
                }
                for (next in successors) {
                    if (next in code.indices && !reachable[next]) {
                        reachable[next] = true
                        pendingPcs += next
                    }
                }
            }
        }
        val returnCounts = ArrayList<Int>()
        var pc = 0
        while (pc < code.size) {
            val i = code[pc]
            // Arithmetic and its metamethod continuation form one semantic operation.
            // The continuation retains the original event and operand order even when
            // luac chose ADDI(-n) for subtraction or swapped a commutative immediate.
            val mm = code.getOrNull(pc + 1)
            if (mm != null && mm.opcodeName in setOf("MMBIN", "MMBINI", "MMBINK")) {
                val operation = listOf("ADD", "SUB", "MUL", "MOD", "POW", "DIV", "IDIV", "BAND", "BOR", "BXOR", "SHL", "SHR")
                    .getOrNull(mm.c - 6) ?: return refuse("unknown arithmetic metamethod event")
                val right = when (mm.opcodeName) {
                    "MMBIN" -> mm.b
                    "MMBINI" -> immediateRegs[mm.signedB.toLong()] ?: return refuse("missing immediate")
                    else -> constRegs[mm.b] ?: return refuse("missing constant")
                }
                val flipped = mm.opcodeName != "MMBIN" && mm.k != 0
                pcWord[pc] = out.size
                emit(instruction(LuaVmProgram.Op.valueOf(operation), i.a,
                    if (flipped) right else mm.a, if (flipped) mm.a else right))
                pcWord[pc + 1] = out.size - 1
                pc += 2
                continue
            }
            when (val op = i.opcodeName) {
                "MMBIN", "MMBINI", "MMBINK", "VARARGPREP", "EXTRAARG" -> {
                    // fast path glue of 5.4/5.5; the VM does the generic operation itself
                }

                "MOVE" -> {
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.MOVE, i.a, i.b, 0))
                }
                // 5.4/5.5 small integer load: `LOADI A sBx`
                "LOADI", "LOADF" -> {
                    val value = i.sbx
                    pcWord[pc] = out.size
                    if (op == "LOADI" && value in -128..127) {
                        emit(instruction(LuaVmProgram.Op.LOADI, i.a, value and 0xFF, 0))
                    } else {
                        val index = constants.size + extraConstants.size
                        extraConstants += if (op == "LOADF") LuaConstant.Number(value.toDouble()) else LuaConstant.Integer(value.toLong())
                        emit(instruction(LuaVmProgram.Op.LOADK, i.a, index, 0))
                    }
                }
                "LOADNIL" -> {
                    pcWord[pc] = out.size
                    for (reg in i.a..i.a + i.b) emit(instruction(LuaVmProgram.Op.LOADNIL, reg, 0, 0))
                }
                "LOADK", "LOADKX" -> {
                    val k = if (op == "LOADK") i.bx else code.getOrNull(pc + 1)?.takeIf { it.opcodeName == "EXTRAARG" }?.ax ?: -1
                    if (k < 0 || k >= constants.size) return null
                    // P4.3 super instruction: `LOADK Rk, Kx` + `RETURN Rk` -> `RETK Kx`
                    val next = code.getOrNull(pc + 1)
                    val merged = next != null &&
                        next.opcodeName == "RETURN" &&
                        next.a == i.a &&
                        next.b == 2 &&
                        (pc + 1) !in jumpTargets &&
                        k !in jumpTargets
                    if (merged) {
                        pcWord[pc] = out.size
                        // `RETK` returns exactly one value (only reachable ones count)
                        if (reachable[pc]) returnCounts += 1
                        emit(instruction(LuaVmProgram.Op.RETK, i.a, k, 1))
                        pcWord[pc + 1] = out.size - 1
                        superInstructions++
                        pc += 2
                        continue
                    }
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.LOADK, i.a, k, 0))
                }
                // `GETTABUP A B C`: C is always a constant key (`256 + k` after decoding)
                "NEWTABLE" -> {
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.NEWTABLE, i.a, 0, 0))
                }
                "SETLIST" -> {
                    val count = if (prototype.version == LuaBytecodeVersion.LUA_5_5) i.vB else i.b
                    val base = listBase(pc) ?: return refuse("SETLIST missing EXTRAARG")
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.SETLIST, i.a, count, keyRegs.getValue(base)))
                }
                "GETTABLE" -> {
                    val rb = resolveOperand(i.b) ?: return refuse("GETTABLE table operand not materialised")
                    val rc = resolveOperand(i.c) ?: return refuse("GETTABLE key operand not materialised")
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.GETTABLE, i.a, rb, rc))
                }
                "SETTABLE" -> {
                    val rb = resolveOperand(i.b) ?: return refuse("SETTABLE key operand not materialised")
                    val rc = (if (i.k != 0) constRegs[i.c] else resolveOperand(i.c)) ?: return refuse("SETTABLE value operand not materialised")
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.SETTABLE, i.a, rb, rc))
                }
                "GETI" -> {
                    val key = keyRegs[i.c.toLong()] ?: return refuse("GETI key not materialised")
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.GETTABLE, i.a, i.b, key))
                }
                "SETI" -> {
                    // `SETI A B C k: R[A][B] := RK(C)` (5.4+): int key in B, value in C
                    // (k=1 -> C is a constant index, materialised in the prologue)
                    val key = keyRegs[i.b.toLong()] ?: return refuse("SETI key not materialised")
                    val value = if (i.k != 0) {
                        constRegs[i.c] ?: return refuse("SETI constant value not materialised")
                    } else {
                        i.c
                    }
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.SETTABLE, i.a, key, value))
                }
                "GETFIELD" -> {
                    val key = constRegs[i.c] ?: return refuse("GETFIELD key not materialised")
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.GETTABLE, i.a, i.b, key))
                }
                "SETFIELD" -> {
                    // key/value split is version specific (see the pre-pass note above):
                    // 5.3 `R[A][K[C]] := R[B]`, 5.4/5.5 `R[A][K[B]] := R[C]`
                    val keyIndex = if (prototype.version == LuaBytecodeVersion.LUA_5_3) i.c else i.b
                    val key = constRegs[keyIndex] ?: return refuse("SETFIELD key not materialised")
                    val value = if (i.k != 0) constRegs.getValue(i.c) else i.c
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.SETTABLE, i.a, key, value))
                }
                "GETTABUP" -> {
                    pcWord[pc] = out.size
                    if (prototype.version == LuaBytecodeVersion.LUA_5_3 && i.c < 256) {
                        val table = nextScratchIndex()
                        emit(instruction(LuaVmProgram.Op.GETUPVAL, table, i.b, 0))
                        emit(instruction(LuaVmProgram.Op.GETTABLE, i.a, table, i.c))
                    } else {
                        val key = if (prototype.version == LuaBytecodeVersion.LUA_5_3) i.c - 256 else i.c
                        if (key !in constants.indices) return refuse("GETTABUP key out of range")
                        emit(instruction(LuaVmProgram.Op.GETTABUP, i.a, i.b, key))
                    }
                }
                "GETUPVAL" -> {
                    if (i.b !in prototype.upvalues.indices) return refuse("GETUPVAL index out of range")
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.GETUPVAL, i.a, i.b, 0))
                }
                "SETUPVAL" -> {
                    if (i.b !in prototype.upvalues.indices) return refuse("SETUPVAL index out of range")
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.SETUPVAL, i.a, i.b, 0))
                }
                "VARARG" -> {
                    if (!prototype.isVararg) return refuse("VARARG in a non-vararg prototype")
                    val count = if (prototype.version == LuaBytecodeVersion.LUA_5_3) i.b else i.c
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.VARARG, i.a,
                        if (count == 0) 0 else if (count == 1) 255 else count - 1,
                        if (prototype.version == LuaBytecodeVersion.LUA_5_5 && i.k != 0) i.b + 1 else 0))
                }
                "TEST", "TESTSET" -> {
                    val polarity = if (prototype.version == LuaBytecodeVersion.LUA_5_3) i.c else i.k
                    pcWord[pc] = out.size
                    val branch = if (polarity == 0) LuaVmProgram.Op.JMPIF else LuaVmProgram.Op.JMPIFNOT
                    emit(instruction(branch, if (op == "TEST") i.a else i.b, 0, 0))
                    pending += Triple(branch, out.size - 1, pc + 2)
                    if (op == "TESTSET") emit(instruction(LuaVmProgram.Op.MOVE, i.a, i.b, 0))
                }
                "SELF" -> {
                    val key = when (prototype.version) {
                        LuaBytecodeVersion.LUA_5_3 -> resolveOperand(i.c)
                        LuaBytecodeVersion.LUA_5_4 -> if (i.k != 0) constRegs[i.c] else i.c
                        LuaBytecodeVersion.LUA_5_5 -> constRegs[i.c]
                    } ?: return refuse("SELF key not materialised")
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.SELF, i.a, i.b, key))
                }
                "LOADBOOL" -> {
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.LOADBOOL, i.a, i.b, 0))
                    if (i.c != 0) {
                        emit(instruction(LuaVmProgram.Op.JMP, 0, 0, 0))
                        pending += Triple(LuaVmProgram.Op.JMP, out.size - 1, pc + 2)
                    }
                }
                "LFALSESKIP" -> {
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.LOADBOOL, i.a, 0, 0))
                    emit(instruction(LuaVmProgram.Op.JMP, 0, 0, 0))
                    pending += Triple(LuaVmProgram.Op.JMP, out.size - 1, pc + 2)
                }
                "LOADFALSE" -> {
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.LOADBOOL, i.a, 0, 0))
                }
                "LOADTRUE" -> {
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.LOADBOOL, i.a, 1, 0))
                }
                // Detach captured cells and unwind native close scopes at this register.
                "CLOSE" -> {
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.CLOSE, i.a, 0, 0))
                }
                "TBC" -> {
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.TBC, i.a, 0, 0))
                }
                "GETVARG" -> {
                    if (prototype.version != LuaBytecodeVersion.LUA_5_5) return refuse("GETVARG is Lua 5.5 only")
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.GETVARG, i.a, i.b, i.c))
                }
                "ERRNNIL" -> {
                    if (prototype.version != LuaBytecodeVersion.LUA_5_5) return refuse("ERRNNIL is Lua 5.5 only")
                    pcWord[pc] = out.size
                    val messageIndex = constants.size + extraConstants.size
                    extraConstants += LuaVmSourceErrors.alreadyDefined(prototype, pc, i.bx)
                    emit(instruction(LuaVmProgram.Op.ERRNNIL, i.a, messageIndex, 0))
                }
                "SETTABUP" -> {
                    val key = if (prototype.version == LuaBytecodeVersion.LUA_5_3) resolveOperand(i.b) else constRegs[i.b]
                    val value = if (prototype.version == LuaBytecodeVersion.LUA_5_3) resolveOperand(i.c)
                        else if (i.k != 0) constRegs[i.c] else i.c
                    if (key == null || value == null) return refuse("SETTABUP operands unavailable")
                    pcWord[pc] = out.size
                    val table = nextScratchIndex()
                    emit(instruction(LuaVmProgram.Op.GETUPVAL, table, i.a, 0))
                    emit(instruction(LuaVmProgram.Op.SETTABLE, table, key, value))
                }
                "CONCAT" -> {
                    val first = if (prototype.version == LuaBytecodeVersion.LUA_5_3) i.b else i.a
                    val last = if (prototype.version == LuaBytecodeVersion.LUA_5_3) i.c else i.a + i.b - 1
                    if (first > last) return refuse("CONCAT has inverted range")
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.CONCAT, i.a, first, last))
                }
                "UNM", "BNOT", "NOT", "LEN" -> {
                    val source = i.b
                    val unary = when (op) {
                        "UNM" -> LuaVmProgram.Op.UNM
                        "BNOT" -> LuaVmProgram.Op.BNOT
                        "NOT" -> LuaVmProgram.Op.NOT
                        else -> LuaVmProgram.Op.LEN
                    }
                    pcWord[pc] = out.size
                    emit(instruction(unary, i.a, source, 0))
                }
                "JMP" -> {
                    val target = i.jumpTarget(pc) ?: return refuse("JMP target missing")
                    pcWord[pc] = out.size
                    if (prototype.version == LuaBytecodeVersion.LUA_5_3 && i.a > 0)
                        emit(instruction(LuaVmProgram.Op.CLOSE, i.a - 1, 0, 0))
                    emit(instruction(LuaVmProgram.Op.JMP, 0, 0, 0))
                    pending += Triple(LuaVmProgram.Op.JMP, out.size - 1, target)
                }
                // numeric `for`, Lua 5.3 layout (lvm.c): `R[A]` is the internal index,
                // `R[A+1]` the limit, `R[A+2]` the step and `R[A+3]` the control variable the
                // body reads. `FORPREP` subtracts one step and **jumps onto the `FORLOOP`**;
                // `FORLOOP` adds the step, tests against the limit (sign aware) and jumps back.
                // 5.4 and 5.5 replaced this with a *count based* loop (`R[A+1]`/`R[A]` hold a
                // remaining-iteration count and `FORPREP` may skip the whole loop), and 5.5 also
                // moved the control variable to `R[A+2]`. Those two are refused until each
                // layout is modelled and differentially verified: a wrong result is far worse
                // than a refusal (the `1 2 3` -> `0 1 2 3` extra-iteration bug came from
                // modelling 5.3's `FORPREP` as a fall-through).
                "FORPREP" -> {
                    val target = i.jumpTarget(pc) ?: return refuse("FORPREP target missing")
                    val countBased = prototype.version != LuaBytecodeVersion.LUA_5_3
                    if (countBased && !enableCountBasedLoops) return refuse("${prototype.version} numeric for capability disabled")
                    pcWord[pc] = out.size
                    emit(instruction(if (countBased) LuaVmProgram.Op.FORPREP_COUNT else LuaVmProgram.Op.FORPREP, i.a, 0, if (countBased) controlOffset else 0))
                    pending += Triple(if (countBased) LuaVmProgram.Op.FORPREP_COUNT else LuaVmProgram.Op.FORPREP, out.size - 1, target)
                }
                "FORLOOP" -> {
                    val target = i.jumpTarget(pc) ?: return null
                    val countBased = prototype.version != LuaBytecodeVersion.LUA_5_3
                    if (countBased && !enableCountBasedLoops) return refuse("${prototype.version} numeric for capability disabled")
                    pcWord[pc] = out.size
                    emit(instruction(if (countBased) LuaVmProgram.Op.FORLOOP_COUNT else LuaVmProgram.Op.FORLOOP, i.a, 0, if (countBased) controlOffset else 0))
                    pending += Triple(if (countBased) LuaVmProgram.Op.FORLOOP_COUNT else LuaVmProgram.Op.FORLOOP, out.size - 1, target)
                }
                "CLOSURE" -> {
                    val index = i.bx
                    if (index < 0 || index >= children.size) return refuse("CLOSURE index out of range")
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.CLOSURE, i.a, index, 0))
                }
                // Generic-for has a version-specific control/closing-variable layout.
                "TFORPREP" -> {
                    val target = i.jumpTarget(pc) ?: return refuse("TFORPREP target missing")
                    pcWord[pc] = out.size
                    if (prototype.version == LuaBytecodeVersion.LUA_5_5) {
                        val temp = nextScratchIndex()
                        emit(instruction(LuaVmProgram.Op.MOVE, temp, i.a + 2, 0))
                        emit(instruction(LuaVmProgram.Op.MOVE, i.a + 2, i.a + 3, 0))
                        emit(instruction(LuaVmProgram.Op.MOVE, i.a + 3, temp, 0))
                    }
                    emit(instruction(LuaVmProgram.Op.TBC,
                        i.a + if (prototype.version == LuaBytecodeVersion.LUA_5_5) 2 else 3, 0, 0))
                    emit(instruction(LuaVmProgram.Op.JMP, 0, 0, 0))
                    pending += Triple(LuaVmProgram.Op.JMP, out.size - 1, target)
                }
                "TFORCALL" -> {
                    if (i.c <= 0) return refuse("TFORCALL without results")
                    // 5.3: call R[A](R[A+1],R[A+2]), results at A+3.
                    // 5.4: same inputs, results at A+4 (A+3 is to-be-closed).
                    // 5.5: TFORPREP swaps control/close; call inputs are copied to A+3..A+5,
                    // results start at A+3 and the loop body reads A+3.
                    val resultBase = if (prototype.version == LuaBytecodeVersion.LUA_5_5) i.a + 3 else if (prototype.version == LuaBytecodeVersion.LUA_5_4) i.a + 4 else i.a + 3
                    val callBase = nextScratchIndex()
                    repeat(maxOf(3, i.c) - 1) { nextScratchIndex() }
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.MOVE, callBase, i.a, 0))
                    emit(instruction(LuaVmProgram.Op.MOVE, callBase + 1, i.a + 1, 0))
                    emit(instruction(LuaVmProgram.Op.MOVE, callBase + 2, i.a + if (prototype.version == LuaBytecodeVersion.LUA_5_5) 3 else 2, 0))
                    emit(instruction(LuaVmProgram.Op.CALL, callBase, 2, i.c))
                    for (slot in 0 until i.c) {
                        emit(instruction(LuaVmProgram.Op.MOVE, resultBase + slot, callBase + slot, 0))
                    }
                }
                "TFORLOOP" -> {
                    val target = i.jumpTarget(pc) ?: return refuse("TFORLOOP target missing")
                    val nilReg = nextScratchIndex()
                    val cmpReg = nextScratchIndex()
                    pcWord[pc] = out.size
                    emit(instruction(LuaVmProgram.Op.LOADNIL, nilReg, 0, 0))
                    val resultReg = when (prototype.version) {
                        LuaBytecodeVersion.LUA_5_3 -> i.a + 1
                        LuaBytecodeVersion.LUA_5_4 -> i.a + 4
                        LuaBytecodeVersion.LUA_5_5 -> i.a + 3
                    }
                    emit(instruction(LuaVmProgram.Op.EQ, cmpReg, resultReg, nilReg))
                    emit(instruction(LuaVmProgram.Op.JMPIF, cmpReg, if (prototype.version == LuaBytecodeVersion.LUA_5_5) 1 else 2, 0))
                    if (prototype.version == LuaBytecodeVersion.LUA_5_3) {
                        emit(instruction(LuaVmProgram.Op.MOVE, i.a, i.a + 1, 0))
                    } else if (prototype.version == LuaBytecodeVersion.LUA_5_4) {
                        emit(instruction(LuaVmProgram.Op.MOVE, i.a + 2, i.a + 4, 0))
                    }
                    emit(instruction(LuaVmProgram.Op.JMP, 0, 0, 0))
                    pending += Triple(LuaVmProgram.Op.JMP, out.size - 1, target)
                }
                "CALL", "TAILCALL" -> {
                    val arguments = if (i.b == 0) 255 else i.b - 1
                    val results = if (i.c == 0) 0 else if (i.c == 1) 255 else i.c - 1
                    pcWord[pc] = out.size
                    emit(instruction(if (op == "CALL") LuaVmProgram.Op.CALL else LuaVmProgram.Op.TAILCALL,
                        i.a, arguments, if (op == "CALL") results else 0))
                }
                "RETURN", "RETURN0", "RETURN1" -> {
                    val a = if (op == "RETURN0") 0 else i.a
                    val count = when (op) {
                        "RETURN0" -> 255
                        "RETURN1" -> 1
                        else -> if (i.b == 0) 0 else if (i.b == 1) 255 else i.b - 1
                    }
                    pcWord[pc] = out.size
                    if (reachable[pc]) returnCounts += if (count == 255) 0 else if (count == 0) -1 else count
                    emit(instruction(LuaVmProgram.Op.RETURN, a, 0, count))
                }
                in SIMPLE -> {
                    val rb = resolveOperand(i.b) ?: return null
                    val rc = resolveOperand(i.c) ?: return null
                    pcWord[pc] = out.size
                    emit(instruction(SIMPLE.getValue(op), i.a, rb, rc))
                }
                // 5.4/5.5 immediate and constant forms. Calibrated against `luac -l`:
                //   EQI/LTI/LEI/GTI/GEI: a = register, b = 127 + immediate, k = polarity
                //   EQK/LTK/LEK/GTK/GEK: a = register, c = constant index, k = polarity
                //   ADDI/ADDK/...      : a = destination, b = left, c = 127 + immediate | index
                in IMMEDIATE_COMPARE, in CONSTANT_COMPARE -> {
                    val isImmediate = op.endsWith("I")
                    val base = when {
                        op.startsWith("EQ") -> LuaVmProgram.Op.EQ
                        op.startsWith("LT") || op.startsWith("GT") -> LuaVmProgram.Op.LT
                        else -> LuaVmProgram.Op.LE
                    }
                    val swapped = op.startsWith("GT") || op.startsWith("GE")
                    val value: Int = if (isImmediate) {
                        (if (i.c != 0) floatImmediateRegs else immediateRegs)[(i.b - 127).toLong()] ?: return refuse("immediate not materialised")
                    } else {
                        constRegs[i.b] ?: return refuse("constant operand not materialised")
                    }
                    val left = if (swapped) value else i.a
                    val right = if (swapped) i.a else value
                    val next = code.getOrNull(pc + 1)
                    if (next == null || next.opcodeName != "JMP") return refuse("compare without a following JMP")
                    val target = next.jumpTarget(pc + 1) ?: return refuse("compare JMP target missing")
                    pcWord[pc] = out.size
                    emit(instruction(base, scratch, left, right))
                    val branch = if (i.k != 0) LuaVmProgram.Op.JMPIF else LuaVmProgram.Op.JMPIFNOT
                    emit(instruction(branch, scratch, 0, 0))
                    pending += Triple(branch, out.size - 1, target)
                    pc++
                }
                in IMMEDIATE_ARITH, in CONSTANT_ARITH -> {
                    val isImmediate = op.endsWith("I")
                    val base = when {
                        op.startsWith("ADD") -> LuaVmProgram.Op.ADD
                        op.startsWith("SUB") -> LuaVmProgram.Op.SUB
                        op.startsWith("MUL") -> LuaVmProgram.Op.MUL
                        op.startsWith("BAND") -> LuaVmProgram.Op.BAND
                        op.startsWith("BOR") -> LuaVmProgram.Op.BOR
                        else -> LuaVmProgram.Op.BXOR
                    }
                    val value: Int = if (isImmediate) {
                        immediateRegs[(i.c - 127).toLong()] ?: return refuse("immediate not materialised")
                    } else {
                        constRegs[i.c] ?: return refuse("constant operand not materialised")
                    }
                    pcWord[pc] = out.size
                    emit(instruction(base, i.a, i.b, value))
                }
                in COMPARE -> {
                    // `CMP scratch, left, right` then fold the following JMP.
                    // VM-CMP-1 (R2 Round2): the register-register encoding is version
                    // specific -- 5.3 is `LT A B C` (operands in B/C, polarity in A), while
                    // 5.4/5.5 moved to `LT A B k` (operands in A/B, polarity in k). Reading
                    // a 5.4 chunk with 5.3 semantics compared the wrong registers and
                    // silently inverted every `while i < j` loop.
                    val left: Int
                    val right: Int
                    val polarity: Boolean
                    if (prototype.version == LuaBytecodeVersion.LUA_5_3) {
                        left = resolveOperand(i.b) ?: return null
                        right = resolveOperand(i.c) ?: return null
                        polarity = i.a != 0
                    } else {
                        if (i.c != 0) return refuse("unexpected C field in a 5.4+ register compare")
                        left = resolveOperand(i.a) ?: return null
                        right = resolveOperand(i.b) ?: return null
                        polarity = i.k != 0
                    }
                    pcWord[pc] = out.size
                    emit(instruction(COMPARE.getValue(op), scratch, left, right))
                    val next = code.getOrNull(pc + 1)
                    if (next == null || next.opcodeName != "JMP") return refuse("compare without a following JMP")
                    val target = next.jumpTarget(pc + 1) ?: return refuse("compare JMP target missing")
                    // Lua's compare skips the JMP when `(comparison) ~= polarity`, so the
                    // polarity decides the direction: with polarity=false the JMP runs when
                    // the comparison is *false*, with true when it is *true*. Ignoring it
                    // silently inverted every branch of `c07_break` (the loop broke
                    // immediately).
                    val branch = if (polarity) LuaVmProgram.Op.JMPIF else LuaVmProgram.Op.JMPIFNOT
                    if (prototype.version == LuaBytecodeVersion.LUA_5_3 && next.a > 0) {
                        val skip = if (polarity) LuaVmProgram.Op.JMPIFNOT else LuaVmProgram.Op.JMPIF
                        emit(instruction(skip, scratch, 0, 0))
                        pending += Triple(skip, out.size - 1, pc + 2)
                        pcWord[pc + 1] = out.size
                        emit(instruction(LuaVmProgram.Op.CLOSE, next.a - 1, 0, 0))
                        emit(instruction(LuaVmProgram.Op.JMP, 0, 0, 0))
                        pending += Triple(LuaVmProgram.Op.JMP, out.size - 1, target)
                    } else {
                        emit(instruction(branch, scratch, 0, 0))
                        pending += Triple(branch, out.size - 1, target)
                    }
                    pc++ // consume the JMP
                }
                else -> {
                    // file switch: Gradle's test JVM does not inherit our env vars
                    Platform.traceSink.append("unsupported op=" + op + " src=" + prototype.source + ":" + prototype.lineDefined)
                    return null
                }
            }
            pc++
        }

        // second pass: the recorded map already accounts for materialised constants
        val fixed = out.toMutableList()
        for ((op, wordIndex, targetPc) in pending) {
            val targetWord = if (targetPc == code.size) out.size else pcWord.getOrNull(targetPc) ?: -1
            if (targetWord < 0) return refuse("branch target outside the program")
            val offset = targetWord - (wordIndex + 1)
            if (offset < -128 || offset > 127) return refuse("branch offset out of range")
            fixed[wordIndex] = when (op) {
                // JMP keeps its offset in the `a` field ...
                LuaVmProgram.Op.JMP -> instruction(op, offset and 0xFF, 0, 0)
                // ... FORLOOP keeps its loop register in `a` and takes the offset in `b` ...
                LuaVmProgram.Op.FORLOOP, LuaVmProgram.Op.FORLOOP_COUNT -> instruction(op, fixed[wordIndex].a, offset and 0xFF, fixed[wordIndex].c)
                // ... FORPREP likewise: its offset is the forward jump onto the FORLOOP ...
                LuaVmProgram.Op.FORPREP, LuaVmProgram.Op.FORPREP_COUNT -> instruction(op, fixed[wordIndex].a, offset and 0xFF, fixed[wordIndex].c)
                // ... and the folded branches carry the comparison result register in `a`.
                else -> instruction(op, fixed[wordIndex].a, offset and 0xFF, 0)
            }
        }
        for (insn in fixed) words += LuaVmProgram.encode(map, insn)
        return VmProgram(
            words.toIntArray(),
            constants + extraConstants,
            maxOf(prototype.maxStackSize, scratchCursor + 1),
            prototype.numParams,
            prototype.isVararg,
            superInstructions,
            children,
            childCaptures,
            if (prototype.version == LuaBytecodeVersion.LUA_5_5) 2 else 3,
            returnCounts,
            prototype.maxStackSize,
            prototype.version == LuaBytecodeVersion.LUA_5_5 && (prototype.isVarargFlags and LuaBytecodeVersion.PF_VATAB) != 0,
            prototype.upvalues.size,
        )
    }
}
