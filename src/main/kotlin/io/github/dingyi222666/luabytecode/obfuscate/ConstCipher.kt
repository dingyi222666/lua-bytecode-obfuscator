package io.github.dingyi222666.luabytecode.obfuscate

/**
 * The keystream cipher of the numeric constant decoder
 * (`obfuscate/LuaConstantEncryptor.kt`, bundled as
 * `resources/obfuscator/decode_num_<version>.luac`).
 *
 * Unlike [LuaStringCipher.Spec], whose `random()` only shuffles the two members
 * of a 2^31 equivalence class (so `j = 0` and `j = 1` yield the *same* stream and
 * the parameters still carry the textbook signature), every [Spec] produced here
 * is a genuinely different full period LCG over `2^31`:
 *
 *  * `multiplier = 4*q + 1` (random 17 bit magnitude, always `≡ 1 (mod 4)`)
 *  * `increment` odd (random 30 bit magnitude)
 *
 * which is exactly the Hull–Dobell condition: the state visits all `2^31` values
 * before repeating, so no short cycle can leak plaintext structure and no
 * parameter pair is equivalent to a known one. The Kotlin side and the Lua
 * decoder mirror each other bit for bit:
 *
 * ```lua
 * state = (state * m + inc) % 2147483648   -- one step per byte
 * byte  = cipher_byte ~ ((state >> shift) & 0xFF)
 * ```
 *
 * The per constant start state is `seed ~ (index * 0x9E3779B1) & 0x7FFFFFFF`
 * (see [ofIndex]); it must stay in lock step with `kget` in `tools/decode_num.lua`.
 */
object ConstCipher {

    /** `2^31`, the LCG modulus (kept a power of two for cheap Lua `%`). */
    const val MODULUS: Long = 2147483648L

    /** Per index start state mixer (the golden ratio constant). */
    const val INDEX_MIX: Long = -1640531527L and 0xFFFFFFFFL // 0x9E3779B1

    /** The two LCG parameters of one chunk (or one fragment batch). */
    data class Spec(val multiplier: Long, val increment: Long, val shift: Int) {
        companion object {
            /**
             * A random full period spec. Every value satisfies Hull–Dobell, so the
             * caller can never draw a degenerate stream (M4: no equivalence classes).
             */
            fun random(random: java.util.Random): Spec {
                val multiplier = 4L * (1 + random.nextLong(0x1FFFFL)) + 1 // ≡ 1 (mod 4), 17..19 bit
                val increment = 2L * random.nextLong(0x3FFFFFFFL) + 1 // odd
                val shift = 8 + random.nextInt(17) // 8..24, which state bits feed the XOR
                return Spec(multiplier, increment, shift)
            }
        }
    }

    /** The start state for the constant at [index]: `seed ~ index * 0x9E3779B1`. */
    fun ofIndex(seed: Int, index: Int): Int {
        var state = (index.toLong() * INDEX_MIX) and 0xFFFFFFFFL
        state = (seed.toLong() and 0xFFFFFFFFL) xor state
        return (state and 0x7FFFFFFFL).toInt()
    }

    /**
     * XORs [data] with the keystream starting at state [seed] using [spec].
     * XOR is symmetric, so encrypt and decrypt are the same operation.
     * Every intermediate stays inside `2^31`, which is exactly what the Lua
     * decoder's double arithmetic reproduces.
     */
    fun apply(data: ByteArray, seed: Int, spec: Spec): ByteArray {
        val result = ByteArray(data.size)
        var state = seed.toLong() and 0x7FFFFFFFL
        for (index in data.indices) {
            state = (state * spec.multiplier + spec.increment) % MODULUS
            val keyByte = ((state shr spec.shift) and 0xFFL).toInt()
            result[index] = (data[index].toInt() xor keyByte).toByte()
        }
        return result
    }
}
