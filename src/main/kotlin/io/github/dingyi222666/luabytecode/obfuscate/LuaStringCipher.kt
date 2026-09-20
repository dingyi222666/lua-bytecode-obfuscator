package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.platform.ObfRandom


/**
 * The keystream cipher of the bundled decoder (`tools/decode.lua`):
 * `state = (state * 1103515245 + 12345) % 2^31` per byte and the payload byte is
 * XORed with `(state >> 16) & 0xFF`.
 *
 * XOR is symmetric, so [encrypt] and [decrypt] are the same operation. The
 * Kotlin implementation mirrors the Lua one bit for bit, which lets tests verify
 * a protected chunk without executing it.
 */
object LuaStringCipher {

    /** `1103515245`, the LCG multiplier used by the Lua decoder. */
    const val MULTIPLIER = 1103515245L

    /** `12345`, the LCG increment. */
    const val INCREMENT = 12345L

    /** `2^31`, the LCG modulus. */
    const val MODULUS = 2147483648L

    /** Different full-period parameters per chunk, not equivalent modular disguises. */
    data class Spec(val multiplier: Long = MULTIPLIER, val increment: Long = INCREMENT) {
        companion object {
            fun random(random: ObfRandom): Spec =
                Spec(4L * (1 + random.nextLong(0x1FFFFL)) + 1,
                    2L * random.nextLong(0x3FFFFFFFL) + 1)
        }
    }

    /** Applies the keystream: `out[i] = data[i] ^ keystream(seed, i)`. */
    @JvmStatic
    @JvmOverloads
    fun apply(data: ByteArray, seed: Int, spec: Spec = Spec()): ByteArray {
        val result = ByteArray(data.size)
        var state = seed.toLong() and 0x7FFFFFFFL
        for (index in data.indices) {
            state = (state * spec.multiplier + spec.increment) % MODULUS
            val keyByte = ((state shr 16) and 0xFF).toInt()
            result[index] = (data[index].toInt() xor keyByte).toByte()
        }
        return result
    }

    /** Encrypts [plain] with [seed]. */
    @JvmStatic
    @JvmOverloads
    fun encrypt(plain: ByteArray, seed: Int, spec: Spec = Spec()): ByteArray = apply(plain, seed, spec)

    /** Decrypts [cipher] with [seed] (identical to [encrypt]). */
    @JvmStatic
    @JvmOverloads
    fun decrypt(cipher: ByteArray, seed: Int, spec: Spec = Spec()): ByteArray = apply(cipher, seed, spec)
}
