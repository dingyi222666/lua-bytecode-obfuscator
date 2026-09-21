package io.github.dingyi222666.luabytecode.platform

import java.security.MessageDigest
import kotlin.random.Random as KotlinRandom
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the pure common [Md5] against the RFC 1321 test vectors and, on the
 * JVM, differentially against `java.security.MessageDigest` over random and
 * boundary-length inputs (the padding paths around 55..64 bytes are exactly
 * where naive implementations break).
 */
class Md5ParityTest {

    private fun jvmHex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `rfc 1321 test vectors`() {
        val vectors = listOf(
            "" to "d41d8cd98f00b204e9800998ecf8427e",
            "a" to "0cc175b9c0f1b6a831c399e269772661",
            "abc" to "900150983249c50d7064616b679aeacb",
            "message digest" to "f96b697d7cb7938d525a2f31aaf161d0",
            "abcdefghijklmnopqrstuvwxyz" to "c3fcd3d76192e4007dfb496cca67e13b",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" to
                "d174ab98d277d9f5a5611c2c9f419d9f",
            "12345678901234567890123456789012345678901234567890123456789012345678901234567890" to
                "57edf4a22be3c955ac49da2e2107b67a",
        )
        for ((input, expected) in vectors) {
            assertEquals(expected, Md5.digestHex(input.encodeToByteArray()), "md5($input) diverged")
        }
    }

    @Test
    fun `parity with java security MessageDigest across boundary lengths`() {
        val rng = KotlinRandom(20260921L)
        for (size in listOf(0, 1, 2, 15, 16, 31, 32, 54, 55, 56, 57, 63, 64, 65, 111, 112, 119, 120, 127, 128, 1000)) {
            val bytes = ByteArray(size) { rng.nextInt().toByte() }
            assertEquals(jvmHex(bytes), Md5.digestHex(bytes), "md5 parity broken at size $size")
        }
    }

    @Test
    fun `random blobs match the jvm reference`() {
        val rng = KotlinRandom(7L)
        repeat(200) {
            val bytes = ByteArray(rng.nextInt(300)) { rng.nextInt().toByte() }
            assertEquals(jvmHex(bytes), Md5.digestHex(bytes), "md5 parity broken at iteration $it")
        }
    }

    @Test
    fun `md5Hex of text matches the jvm reference`() {
        for (text in listOf("", "decode", "M.decrypt = function(s) end", "книга", "\u0000\t\n")) {
            assertEquals(jvmHex(text.encodeToByteArray()), md5Hex(text))
        }
    }
}
