package io.github.dingyi222666.luabytecode.bytecode.support

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM parity cross-check of [LcgSpec.step] against `java.math.BigInteger`.
 * Lives in jvmTest because the arbitrary precision reference is JVM only; the
 * js target runs the remaining pure-stdlib LCG suite in commonTest.
 */
class LuaLcgBigIntegerParityTest {

    @Test
    fun `step is exact for the j equal 1 multiplier near 2^33 where naive Long math would overflow`() {
        // a ≈ 2^32 + x, state ≈ 2^32: naive (state * a) overflows signed Long,
        // the half-split must not. Compare against java.math.BigInteger.
        val spec = LcgSpec((1L shl 32) + 5L, 9L).requireFullCycle()
        val bigA = java.math.BigInteger.valueOf(spec.multiplier)
        val bigC = java.math.BigInteger.valueOf(spec.increment)
        val bigM = java.math.BigInteger.valueOf(LcgSpec.MODULUS)
        var state = 0xDEADBEEFL
        repeat(256) {
            val expected = java.math.BigInteger.valueOf(state)
                .multiply(bigA).add(bigC).mod(bigM).toLong()
            state = spec.step(state)
            assertEquals(expected, state, "half-split wrong at multiplier ${spec.multiplier}")
        }
    }

    @Test
    fun `applySeed maps the zero mixing seed to the golden fallback`() {
        val spec = LcgSpec(1664525L, 1013904223L)
        assertEquals(
            0x9E3779B9L,
            spec.applySeed(mixerZeroSeed().toLong()),
            "seed that mixes to 0 must map to the golden fallback",
        )
    }

    /** seed with `(seed * MIXER_MUL + MIXER_INC) mod 2^32 == 0`. */
    private fun mixerZeroSeed(): Int {
        val mul = java.math.BigInteger.valueOf(LcgSpec.MIXER_MULTIPLIER)
        val inc = java.math.BigInteger.valueOf(LcgSpec.MIXER_INCREMENT)
        val m = java.math.BigInteger.valueOf(LcgSpec.MODULUS)
        val seed = inc.negate().multiply(mul.modInverse(m)).mod(m)
        return seed.toInt()
    }
}
