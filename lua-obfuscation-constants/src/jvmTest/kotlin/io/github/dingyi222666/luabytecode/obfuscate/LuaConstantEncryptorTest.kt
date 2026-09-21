package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pass-level acceptance for Pass K (`LEAK-2`) primitives: keystream parameter
 * families and the exact tagged 64-bit payload round trip.
 *
 * The end-to-end "zero plaintext after AGGRESSIVE" acceptance depends on the
 * full pipeline (flattener + Pass K ordering) and stays with the root
 * orchestrator tests (`LuaPipelineConstantLeakTest`).
 */
class LuaConstantEncryptorTest {

    @Test
    fun `full period parameters satisfy hull dobell and cover the state space`() {
        val random = ObfRandom(20260917L)
        repeat(200) {
            val spec = ConstCipher.Spec.random(random)
            assertTrue(spec.multiplier % 4 == 1L, "multiplier ${spec.multiplier} not congruent 1 mod 4")
            assertTrue(spec.increment % 2 == 1L, "increment ${spec.increment} not odd")
            assertTrue(spec.shift in 8..24, "shift ${spec.shift} outside 8..24")
        }
    }

    @Test
    fun `cipher round trips positive and negative values through the 64 bit payload`() {
        val spec = ConstCipher.Spec(195573L, 211815755L, 18)
        val values = listOf(0L, 1L, 42L, -1L, -3L, -12345L, 2147483647L, (1L shl 39) - 1, -(1L shl 39) + 1, Long.MIN_VALUE, Long.MAX_VALUE)
        for (value in values) {
            val plain = payloadOf(value)
            val cipher = ConstCipher.apply(plain, ConstCipher.ofIndex(987654321, 3), spec)
            val decoded = ConstCipher.apply(cipher, ConstCipher.ofIndex(987654321, 3), spec)
            assertTrue(plain.contentEquals(decoded), "round trip failed for $value")
        }
    }

    private fun payloadOf(value: Long): ByteArray {
        val result = ByteArray(9)
        result[0] = 0
        for (i in 0 until 8) {
            result[1 + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
        return result
    }
}
