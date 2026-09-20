package io.github.dingyi222666.luabytecode.bytecode.support

import java.util.Random

/**
 * Full-cycle LCG (linear congruential generator) over Z/2^32 — the shared
 * keystream primitive for the VM program cipher (B2) and the constant
 * encryptor Pass K (B3).
 *
 * An LCG `state = (state * multiplier + increment) mod 2^32` visits **every**
 * one of its 2^32 states exactly once per period (Hull–Dobell theorem) iff:
 *
 * 1. `increment` is odd (`gcd(increment, 2^32) == 1`), and
 * 2. `multiplier ≡ 1 (mod 4)` (i.e. `multiplier - 1` is divisible by 4, which
 *    for `m = 2^32` means divisible by `c = m / 4 = 2^30 = gcd(a-1, m)`).
 *
 * Why full cycle matters here: a keystream generator with a short cycle
 * repeats within one payload, and a repeating XOR pad is trivially broken.
 * The classic values this obfuscator shipped before round 128
 * (`1664525 / 1013904223` in the VM, `1103515245 / 12345` mod 2^31 in the
 * string cipher) both satisfy Hull–Dobell for their modulus; this class makes
 * the property a *checked invariant* instead of folklore, and lets callers
 * randomise the constants per chunk without ever emitting a degenerate
 * generator.
 *
 * ## 2^32 overflow behaviour
 *
 * The state arithmetic must behave like **unsigned 32 bit** wraparound
 * (`state * a + c mod 2^32`). Kotlin `Long` multiply of two values `< 2^32`
 * stays below `2^64`, so `(state * multiplier + increment) and 0xFFFFFFFFL`
 * computes the exact residue without overflow — the same value Lua's float
 * path (`state % 4294967296`, exact up to 2^53) produces. Consumers that want
 * a signed 32 bit word use [nextInt], which returns the state as an `Int`
 * (bit-identical to the unsigned word).
 *
 * ## The `j ∈ {0, 1}` multiplier family
 *
 * Like the existing per-chunk equivalence trick in `LuaStringCipher.Spec`
 * (`1103515245 + 2^31 * j`), a multiplier is only defined modulo `2^32` for
 * keystream purposes. [random] keeps multipliers in the tight family
 * `1 + 4 * t + 2^32 * j` with `j ∈ {0, 1}` and `t < 2^29` so that the
 * product `state * multiplier` (with `state < 2^32`) never exceeds
 * `(2^32)^2 * 2` — comfortably inside the signed 64 bit range, so the
 * generated **Lua decode snippets stay exact in Lua 5.1..5.5 integers** and
 * the Kotlin side needs no big-number arithmetic either. The `+ 2^32 * j`
 * part is a pure signature change: `(a + 2^32) ≡ a (mod 2^32)` so the
 * keystream is bit-for-bit identical, while the literal constant embedded in
 * the chunk differs per chunk.
 *
 * ## Honest strength boundary
 *
 * An LCG is **linear and invertible**: given two consecutive 32 bit words an
 * attacker recovers `multiplier` and `increment` algebraically
 * (`s1 = a*s0 + c`, `s2 = a*s1 + c`), so this primitive is an *obfuscation*
 * layer (breaks literal-pattern signatures, forces symbolic execution) —
 * never a cryptographic one. The per-chunk randomisation raises the number of
 * constants an attacker must confirm per chunk from 0 (signature match) to
 * O(chunk), it does not stop a lifter that already knows the scheme.
 *
 * ## Usage
 *
 * Kotlin keystream (same as the VM interpreter's `M.crypt`):
 * ```kotlin
 * val spec = LuaLcg.Spec.random(Random(chunkSeed))
 * var state = spec.applySeed(mixedSeed)           // any non-zero start is fine
 * words.forEachIndexed { i, w -> out[i] = w xor spec.step(state).also { state = it } }
 * ```
 *
 * Matching Lua decoder fragment (5.3 / 5.4 / 5.5, integers stay exact):
 * ```kotlin
 * val lua = spec.luaDecodeSnippet("state", offset = 16)
 * // local function lcg_step(state) return (state * A + C) % 4294967296 end
 * // ... XOR byte = math.floor(state / 65536) % 256  (byte extraction is a
 * //     caller decision; see luaStepExpression / luaByteExpression)
 * ```
 */
data class LcgSpec(
    /**
     * The multiplier `a`. The constructor does **not** reject multipliers that
     * violate `a ≡ 1 (mod 4)` for `j ∈ {0, 1}` derived specs — use
     * [isFullCycle] / [random] / [requireFullCycle] to check the property for
     * an arbitrary spec (e.g. a classic constant set such as `1664525`, which
     * satisfies it). Callers embedding a *randomised* spec into a chunk should
     * use [random], which only produces full-cycle specs.
     */
    val multiplier: Long,

    /** The increment `c`; must be odd for a full cycle ([isFullCycle] checks). */
    val increment: Long,
) {

    init {
        require(multiplier in 0..MAX_MULTIPLIER) { "multiplier $multiplier out of 0..2^33 range" }
        require(increment in 0 until MODULUS) { "increment $increment out of 0..2^32 range" }
    }

    /** `2^32`, the modulus. Kept as a constant so Lua snippets can embed it. */
    companion object {
        const val MODULUS = 0x100000000L

        /** `4294967296` as a decimal literal for generated Lua code. */
        const val MODULUS_DEC = "4294967296"

        /**
         * Upper bound for [multiplier]: the `j ∈ {0, 1}` family keeps
         * `a ≤ 2^32 + (2^32 - 1) < 2^33`, which bounds `state * a + c < 2^65`
         * in *value* — but with the wrap applied via `and 0xFFFFFFFFL` after
         * every multiply the intermediate `state * a` itself stays
         * `< 2^32 * 2^33 = 2^65`… which overflows signed Longs. Hence
         * [step] wraps the product in two halves; see [step].
         */
        const val MAX_MULTIPLIER = (1L shl 33) - 1

        /** `s0 = (seed * 6364136223846793005 + 1442695040888963407) mod 2^32` — the VM's seed mixer. */
        const val MIXER_MULTIPLIER = 6364136223846793005L
        const val MIXER_INCREMENT = 1442695040888963407L

        /**
         * Knuth's MMIX multiplier truncated to 32 bits
         * (`6364136223846793005 mod 2^32 = 1284865837`), a well-analysed
         * full-cycle constant mod 2^32 (`≡ 1 (mod 4)`, the unit test asserts it).
         */
        const val CLASSIC_MULTIPLIER = 1284865837L

        /** A plain odd increment; `1` keeps the family `a^n(s) + ...` shape simple. */
        const val CLASSIC_INCREMENT = 1L

        /** The identity spec `x' = x + 1` (full cycle, useful as a neutral default / test peer). */
        val IDENTITY_COUNTER = LcgSpec(multiplier = 1, increment = 1)

        /**
         * A randomised **full-cycle** spec derived from [random].
         *
         * * `multiplier = 1 + 4t + 2^32 * j`, `t ∈ [1, 2^29)`, `j ∈ {0, 1}` —
         *   odd, `≡ 1 (mod 4)`, never the degenerate `a = 1` (because `t ≥ 1`),
         *   never equal to a previous chunk's value with overwhelming
         *   probability, and bounded so Lua/Kotlin arithmetic stays exact.
         * * `increment = 1 + 2k`, `k ∈ [0, 2^31)` — any odd value.
         */
        fun random(random: Random): LcgSpec {
            val t = 1L + (random.nextLong() ushr 1) % ((1L shl 29) - 1)
            val j = if (random.nextBoolean()) 1L else 0L
            val multiplier = 1L + 4L * t + (j shl 32)
            val k = (random.nextLong() ushr 1) % (1L shl 31)
            val increment = 1L + 2L * k
            return LcgSpec(multiplier, increment)
        }

        /** [random] but throws when the (Hull–Dobell-checked) result would not be full cycle. */
        fun requireRandom(random: Random): LcgSpec = random(random).also { it.requireFullCycle() }
    }

    /**
     * Advances the state once: `(state * a + c) mod 2^32`, unsigned wraparound.
     *
     * Implemented with a two-half multiply so even `a` values up to
     * [MAX_MULTIPLIER] (`< 2^33`) cannot overflow the signed 64 bit
     * intermediate. For `a < 2^32` a plain `(state * multiplier + increment)
     * and 0xFFFFFFFFL` is equivalent — the half-split only matters for the
     * `j = 1` family member `a ≈ 2^32 + x`.
     */
    fun step(state: Long): Long {
        val lo = state and 0xFFFFFFFFL
        // split a = aHigh * 2^16 + aLow; lo * a = lo * aLow + ((lo * aHigh) << 16)
        val aLow = multiplier and 0xFFFFL
        val aHigh = multiplier ushr 16
        val product = lo * aLow + (((lo * aHigh) and 0xFFFFFFFFL) shl 16)
        return (product + increment) and 0xFFFFFFFFL
    }

    /** Initial state from an arbitrary 64 bit seed, mixed so correlated seeds spread. */
    fun applySeed(seed: Long): Long {
        val mixed = (seed * MIXER_MULTIPLIER + MIXER_INCREMENT) and 0xFFFFFFFFL
        // never return the fixed point 0 — for a != 1 there is no zero fixed
        // point (c is odd), but 0 as an explicit start would confuse callers
        // that use "state == 0" as a sentinel.
        return if (mixed == 0L) 0x9E3779B9L else mixed
    }

    /** Keystream word at 1-based position [index] starting from [state] *before* any step. */
    fun wordAt(state: Long, index: Int): Long {
        var current = state
        repeat(index) { current = step(current) }
        return current
    }

    /**
     * Fills [out] with the keystream words `step^1(state) .. step^n(state)`.
     * Returns the state *after* the last consumed word, so callers can resume.
     */
    fun keystream(state: Long, out: IntArray): Long {
        var current = state
        for (i in out.indices) {
            current = step(current)
            out[i] = current.toInt()
        }
        return current
    }

    /**
     * Hull–Dobell full-cycle check for modulus `2^32`:
     * `increment` odd **and** `multiplier ≡ 1 (mod 4)`.
     *
     * Note the multiplier condition is on `a - 1` being divisible by
     * `gcd(a - 1, m)`'s required factor — for `m = 2^k` the theorem reduces to
     * `a ≡ 1 (mod 4)` for `k ≥ 2`, which is exactly what is tested here.
     */
    fun isFullCycle(): Boolean =
        (increment and 1L) == 1L && (multiplier and 3L) == 1L

    /** [isFullCycle] as a hard check, with a message naming the violated condition. */
    fun requireFullCycle(): LcgSpec {
        check(isFullCycle()) {
            "LCG $this is not full cycle mod 2^32 (Hull-Dobell): " +
                "increment must be odd and multiplier must be ≡ 1 (mod 4)"
        }
        return this
    }

    /**
     * Positional bit-balance audit: over one full period every bit position of
     * the state must be 1 exactly half the time (`2^31` of `2^32` states).
     *
     * A full-cycle LCG with odd `c` has an odd `xor` of all states… the exact
     * balance per bit is guaranteed **only for the low bit** (`c` odd ⇒ bit 0
     * alternates; over the full cycle each bit b ≥ 1 is 1 in exactly `2^31`
     * states iff `a ≡ 1 (mod 4)` and `c` odd — this is the standard corollary
     * used as a self-check). The test walks a window of the cycle (the full
     * cycle is 4 billion steps, ~seconds in Kotlin but too slow for every
     * build); [samples] caps the walked prefix at `min(samples, 2^32)` states.
     *
     * Used by the unit tests and available to blue teams as a cheap "is my
     * randomised spec sane" assertion. It is *not* a strength claim.
     *
     * @return per-bit count of set bits over the sampled window.
     */
    fun bitBalanceSamples(state: Long, samples: Int): LongArray {
        var current = state
        val ones = LongArray(32)
        repeat(samples) {
            current = step(current)
            var word = current
            for (bit in 0 until 32) {
                if (word and 1L == 1L) ones[bit]++
                word = word ushr 1
            }
        }
        return ones
    }

    // ------------------------------------------------------- Lua side snippets

    /**
     * Lua expression that advances [stateVar] once, exact on Lua 5.1-5.5
     * (floats are exact up to 2^53; the product bound is documented on
     * [MAX_MULTIPLIER]).
     *
     * With the default `j ∈ {0,1}` multiplier family the product
     * `state * multiplier` can reach `≈ 2^64` in *value*, which a double
     * cannot represent exactly. Lua decode code therefore splits the multiply
     * the same way [step] does. The emitted fragment is self-contained:
     *
     * ```lua
     * local lo = state % 65536
     * local hi = math.floor(state / 65536)
     * local aLo = 12345 % 65536
     * local aHi = math.floor(12345 / 65536)
     * state = (lo * aLo + (hi * aLo + lo * aHi) % 65536 * 65536 + C) % 4294967296
     * ```
     *
     * Callers typically wrap it in `local function lcg_step(s) ... return s end`.
     */
    fun luaStepExpression(stateVar: String): String = buildString {
        val aLo = multiplier and 0xFFFFL
        val aHi = multiplier ushr 16
        append("local lo = $stateVar % 65536; ")
        append("local hi = math.floor($stateVar / 65536); ")
        append("local aLo = $aLo; ")
        append("local aHi = $aHi; ")
        append("$stateVar = (lo * aLo + (hi * aLo + lo * aHi) % 65536 * 65536 + $increment) % $MODULUS_DEC")
    }

    /**
     * Lua expression for the keystream byte most obfuscation schemes use:
     * `(state / 65536) % 256` (the third byte, as in `LuaStringCipher`).
     */
    fun luaByteExpression(stateVar: String): String =
        "math.floor($stateVar / 65536) % 256"

    /**
     * A complete, self-contained Lua decode function: advances the LCG and
     * returns the XOR byte. `local function <fnName>(s) ... return b end`.
     *
     * The generated text is deterministic for a given spec — embed it once per
     * chunk and call it per byte/word.
     */
    fun luaDecodeSnippet(fnName: String = "lcg_byte"): String =
        "local function $fnName(s) " +
            luaStepExpression("s") + "; " +
            "return " + luaByteExpression("s") + " end"

    /**
     * Lua seed-mixing expression matching [applySeed]: turns a runtime seed
     * (e.g. derived from `#tostring(_VERSION)`, **bounded-guessable — at most
     * 3 distinct values across supported hosts**) into the initial state.
     */
    fun luaSeedSnippet(fnName: String = "lcg_seed"): String =
        "local function $fnName(seed) " +
            "local s = (seed * $MIXER_MULTIPLIER + $MIXER_INCREMENT) % $MODULUS_DEC; " +
            "if s == 0 then s = 2654435769 end; " +
            "return s end"
}
