package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.platform.ObfRandom

/**
 * P4.1: the private instruction set of the forthcoming Lua bytecode VM.
 *
 * The goal (docs/obfuscation-algorithms.md section 11, papers: Rolles WOOT'09, Luo & Ming)
 * is that the chunk no longer contains the original opcodes at all: the original semantics
 * live in an encrypted VM program that only the interpreter decrypts at run time. This file
 * defines that program's ISA, its per chunk **private opcode numbering** (a random bijection
 * over the ISA, so no chunk shares another chunk's numbering) and a fixed width encoder.
 *
 * Layout of one VM instruction (32 bit, big endian when serialised):
 *
 * ```
 *  31..24   opcode (the chunk private number)
 *  23..16   a
 *  15..8    b
 *  7..0     c          (or, for the `x` forms, b and c carry a 16 bit immediate)
 * ```
 */
object LuaVmProgram {
    /** The semantic operations the VM understands (independent of any Lua version). */
    enum class Op(val hasX: Boolean = false) {
        MOVE,
        LOADK,
        LOADI,
        LOADNIL,
        GETTABLE,
        SETTABLE,

        /** creates an empty table in `a` (P4: real tables, not just upvalue lookups) */
        NEWTABLE,

        /** explicit upvalue-constant lookup forms, so `GETTABLE`/`SETTABLE` stay real tables */
        GETTABUP,
        SETTABUP,
        GETUPVAL,
        SETUPVAL,
        /** b=0: open results, b=255: no results; c=table register+1 or zero for hidden args. */
        VARARG,
        TAILCALL,
        TEST,
        TESTSET,
        SELF,
        LOADBOOL,
        SETLIST,
        TBC,
        CLOSE,
        GETVARG,
        ERRNNIL,
        ADD,
        SUB,
        MUL,
        DIV,
        IDIV,
        MOD,
        POW,
        BAND,
        BOR,
        BXOR,
        SHL,
        SHR,
        UNM,
        BNOT,
        NOT,
        LEN,
        CONCAT,
        JMP,

        /** conditional branch: jumps when the value is truthy */
        JMPIF,
        JMPIFNOT,

        /** comparisons, produced by folding Lua's `compare + JMP` pair */
        EQ,
        LT,
        LE,
        /** b=255: open arguments; c=0: open results, c=255: no results. */
        CALL,
        CLOSURE,
        /** c=0: open results, c=255: no results, otherwise c is the result count. */
        RETURN,
        FORPREP,
        FORLOOP,

        /** Lua 5.4/5.5 count based numeric loop preparation. c carries control offset. */
        FORPREP_COUNT,
        /** Lua 5.4/5.5 count based numeric loop step. c carries control offset. */
        FORLOOP_COUNT,

        /**
         * P4.3 super instructions (Falcon/`docs` P4.3): merges of frequent sequences, which
         * both shorten the program and remove recognisable instruction patterns.
         * `RETK` folds `LOADK Rk, Kx` + `RETURN Rk` into one word (the constant is returned
         * directly, so no register load and no plain `LOADK`+`RETURN` pair survives).
         */
        RETK,

        /** placeholder for further merges; carries a 16 bit immediate */
        SUPERTABLE(true),
    }

    const val OPCODE_BITS = 8

    /**
     * A per chunk random bijection over [Op.entries]. Two chunks never share the numbering,
     * so an attacker cannot map a number to a semantics across chunks.
     */
    class OpcodeMap(private val permutation: IntArray) {
        init {
            require(permutation.size == Op.entries.size) { "permutation size mismatch" }
            require(permutation.toSet().size == Op.entries.size) { "permutation is not a bijection" }
        }

        fun encode(op: Op): Int = permutation[op.ordinal]
        fun decode(number: Int): Op = Op.entries[permutation.indexOf(number)]

        companion object {
            /** Deterministic from the caller's seeded RNG so results are reproducible. */
            fun random(random: ObfRandom): OpcodeMap {
                val order = MutableList(Op.entries.size) { it }
                order.shuffle(random)
                return OpcodeMap(order.toIntArray())
            }

            val identity: OpcodeMap = OpcodeMap(IntArray(Op.entries.size) { it })
        }
    }

    /** One encoded VM instruction. */
    data class Insn(val op: Op, val a: Int, val b: Int, val c: Int, val x: Int = 0) {
        init {
            require(a in 0..0xFF) { "a out of range: $a" }
            require(b in 0..0xFF) { "b out of range: $b" }
            require(c in 0..0xFF) { "c out of range: $c" }
            if (op.hasX) require(x in Short.MIN_VALUE..Short.MAX_VALUE) { "x out of range: $x" }
        }
    }

    // ------------------------------------------------------------------ P4.2 stream cipher
    //
    // The VM program is stored encrypted: the words never contain a plain VM opcode, and the
    // stream is per chunk (Laszlo & Kiss / docs section 11, P4.2).
    //
    // ISSUE VM-1 fix (B2 round 1): the keystream generator is **no longer the textbook LCG**
    // (1664525/1013904223). Every chunk draws its own (mul, add) pair from its RNG and ships it
    // inside the *encrypted* program header -- never as a plain literal in the emitted source:
    //
    //   * mul ≡ 1 (mod 4)  and add odd  ⇒ Hull–Dobell: the LCG is full period over 2^32,
    //     so every 32 bit state (and every keystream byte) occurs before the cycle repeats.
    //   * mul % 8 != 1 keeps the low bits from being a trivial mod-8 counter.
    //
    // The mod 2^32 / state mixing constants stay as code-level `const val`s only; they are
    // emitted as arithmetic *expressions* (never as decimal literals) and, for the multiplier /
    // increment, not at all: those two live only in the encrypted header.

    const val LCG_MOD = 0x100000000L

    /** Header magic word, encrypted with the lower half seed: lets the module detect a wrong seed. */
    const val HEADER_MAGIC = 0x9E3779B9.toInt()

    /**
     * Fixed secondary LCG for the *header* phase: the header carries the real per chunk
     * (mul, add), so the header decryptor cannot use them yet (chicken and egg). These
     * constants are NOT the textbook pair and travel only inside the generated code.
     */
    const val HEADER_MUL = 0x6C078965L
    const val HEADER_ADD = 1L

    /** A per chunk keystream generator (LCG over 2^32 with chunk private parameters). */
    class Cipher(
        val mul: Long,
        val add: Long,
        /** Seed mixing constants: per chunk random, so no shared fingerprint survives. */
        val mixM: Long,
        val mixA: Long,
        /** Zero state fallback: any odd nonzero 32 bit value. */
        val fallback: Long,
    ) {
        init {
            require(mul in 0..0xFFFFFFFFL && add in 0..0xFFFFFFFFL) { "cipher params out of range" }
        }

        fun initialState(seed: Long): Long {
            val mixed = (seed * mixM + mixA) and 0xFFFFFFFFL
            return if (mixed == 0L) fallback else mixed
        }

        /** Encrypts/decrypts in place: XOR with the LCG keystream truncated to 32 bits. */
        fun crypt(words: IntArray, seed: Long): IntArray {
            var state = initialState(seed)
            val out = IntArray(words.size)
            for (i in words.indices) {
                state = (state * mul + add) and 0xFFFFFFFFL
                out[i] = words[i] xor state.toInt()
            }
            return out
        }

        /** One independently keyed stream for a tagged constant payload. */
        fun cryptBytes(seed: Long, salt: Long, bytes: ByteArray): IntArray {
            var state = (initialState(seed) + salt) and 0xFFFFFFFFL
            val out = IntArray(bytes.size)
            for (i in bytes.indices) {
                state = (state * mul + add) and 0xFFFFFFFFL
                out[i] = (bytes[i].toInt() and 0xFF) xor (state and 0xFFL).toInt()
            }
            return out
        }

        override fun equals(other: Any?): Boolean = other is Cipher && other.mul == mul && other.add == add
        override fun hashCode(): Int = (mul.toInt() * 31 + add.toInt())
        override fun toString(): String = "Cipher(mul=0x${mul.toString(16)}, add=0x${add.toString(16)})"

        companion object {
            /**
             * Draws a full period (Hull–Dobell) LCG pair: `mul ≡ 1 (mod 4)`, `add` odd, plus the
             * `mul % 8 != 1` quality guard. The seed mixing constants and the zero state
             * fallback are drawn per chunk too, so no fixed mixing fingerprint survives in any
             * emitted module. Deterministic in the caller's RNG.
             */
            fun random(random: ObfRandom): Cipher {
                while (true) {
                    val mul = ((random.nextLong() and 0xFFFFFFFFL) or 1L) // odd
                    val mulF = (mul and 0xFFFFFFFFL) - ((mul and 3L) - 1L) // ≡ 1 (mod 4)
                    val add = ((random.nextLong() and 0xFFFFFFFFL) or 1L) // odd
                    if (mulF % 8L == 1L) continue
                    val mixM = ((random.nextLong() and 0xFFFFFFFFL) or 1L) // odd mixer
                    val mixA = random.nextLong() and 0xFFFFFFFFL
                    val fallback = ((random.nextLong() and 0xFFFFFFFFL) or 1L) // odd, nonzero
                    return Cipher(mulF, add, mixM, mixA, fallback)
                }
            }

        }
    }

    // ------------------------------------------------------------------ constant pool tagging (VM-1 A3)

    /**
     * Typed tags for the fully encrypted constant pool: every constant -- integers, floats,
     * booleans, nil included -- is shipped as `{ tag, bytes... }` encrypted with its own
     * keystream, so no `k[i] = 12345` plain literal survives in the emitted module.
     * The Lua side rebuilds the values with `string.unpack("<i8")` / `"<d"`.
     */
    object ConstTag {
        const val NIL = 0
        const val BOOLEAN = 1
        const val INTEGER = 2
        const val FLOAT = 3
        const val STRING = 4
    }

    /**
     * Encodes one constant as tagged plain bytes (the caller encrypts with [Cipher.cryptBytes]).
     * Little endian, matching the Lua side `string.unpack("<i8")` / `"<d"`.
     */
    fun encodeConstantBytes(constant: io.github.dingyi222666.luabytecode.bytecode.LuaConstant): ByteArray = when (constant) {
        is io.github.dingyi222666.luabytecode.bytecode.LuaConstant.Nil -> byteArrayOf(ConstTag.NIL.toByte())
        is io.github.dingyi222666.luabytecode.bytecode.LuaConstant.Boolean ->
            byteArrayOf(ConstTag.BOOLEAN.toByte(), if (constant.value) 1 else 0)
        is io.github.dingyi222666.luabytecode.bytecode.LuaConstant.Integer ->
            byteArrayOf(ConstTag.INTEGER.toByte()) + longToBytes(constant.value)
        is io.github.dingyi222666.luabytecode.bytecode.LuaConstant.Number ->
            byteArrayOf(ConstTag.FLOAT.toByte()) + longToBytes(doubleToCanonicalBits(constant.value))
        is io.github.dingyi222666.luabytecode.bytecode.LuaConstant.Str ->
            byteArrayOf(ConstTag.STRING.toByte()) + constant.value.bytes
        else -> byteArrayOf(ConstTag.NIL.toByte())
    }

    private fun longToBytes(v: Long): ByteArray = ByteArray(8) { i -> (v ushr (i * 8)).toByte() }

    /**
     * `java.lang.Double.doubleToLongBits` equivalent (pure common): NaN is
     * canonicalized to `0x7ff8000000000000`, every other value keeps its exact
     * [Double.toRawBits] pattern. Deliberately canonicalizing (rather than
     * using `toRawBits()` directly) so encrypted float payloads for NaN
     * constants stay byte-compatible with the pre-KMP output.
     */
    private fun doubleToCanonicalBits(value: Double): Long =
        if (value.isNaN()) 0x7ff8000000000000L else value.toRawBits()

    fun encode(map: OpcodeMap, insn: Insn): Int {
        val head = (map.encode(insn.op) shl 24) or ((insn.a and 0xFF) shl 16)
        // the `x` form reuses the b/c bytes for its 16 bit immediate, so b and c are
        // meaningless there and are normalised to 0 on both sides of the codec
        return if (insn.op.hasX) {
            head or (insn.x and 0xFFFF)
        } else {
            head or ((insn.b and 0xFF) shl 8) or (insn.c and 0xFF)
        }
    }

    fun decode(map: OpcodeMap, word: Int): Insn {
        val op = map.decode((word ushr 24) and 0xFF)
        val a = (word ushr 16) and 0xFF
        return if (op.hasX) {
            Insn(op, a, 0, 0, (word and 0xFFFF).toShort().toInt())
        } else {
            Insn(op, a, (word ushr 8) and 0xFF, word and 0xFF)
        }
    }
}
