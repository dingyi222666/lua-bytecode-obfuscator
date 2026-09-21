package io.github.dingyi222666.luabytecode.platform

/**
 * Pure common MD5 (RFC 1321) — replacement of `java.security.MessageDigest` for
 * the non-cryptographic fingerprints of the decode-variant generator
 * (`LuaDecodeVariants.structuralMd5` / `rawMd5`, skeleton uniqueness tracking).
 *
 * The round constants are embedded as literals on purpose: deriving them via
 * `sin()` at run time could drift across platform math libraries. The digest
 * is bit-identical to every other MD5 implementation; `Md5Test` pins the RFC
 * vectors and differential-compares against the JVM provider.
 *
 * Strength boundary: this is a fingerprint, never a secret — MD5 must not be
 * used for authentication anywhere in this codebase.
 */
object Md5 {

    private val K = intArrayOf(
        0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db, 0xc1bdceee.toInt(),
        0xf57c0faf.toInt(), 0x4787c62a, 0xa8304613.toInt(), 0xfd469501.toInt(),
        0x698098d8, 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
        0x6b901122, 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821,
        0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51, 0xe9b6c7aa.toInt(),
        0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
        0x21e1cde6, 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed,
        0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9, 0x8d2a4c8a.toInt(),
        0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122, 0xfde5380c.toInt(),
        0xa4beea44.toInt(), 0x4bdecfa9, 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
        0x289b7ec6, 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05,
        0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8, 0xc4ac5665.toInt(),
        0xf4292244.toInt(), 0x432aff97, 0xab9423a7.toInt(), 0xfc93a039.toInt(),
        0x655b59c3, 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
        0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1,
        0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb, 0xeb86d391.toInt(),
    )

    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    /** 16 byte little-endian MD5 digest of [message]. */
    fun digest(message: ByteArray): ByteArray {
        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476

        // Pad: message | 0x80 | zeros | 64 bit little-endian bit length.
        val bitLength = message.size.toLong() * 8L
        val paddedLength = ((message.size + 8) / 64 + 1) * 64
        val padded = ByteArray(paddedLength)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[paddedLength - 8 + i] = (bitLength ushr (8 * i)).toByte()
        }

        val m = IntArray(16)
        for (chunkStart in padded.indices step 64) {
            for (j in 0 until 16) {
                val i = chunkStart + j * 4
                m[j] = (padded[i].toInt() and 0xFF) or
                    ((padded[i + 1].toInt() and 0xFF) shl 8) or
                    ((padded[i + 2].toInt() and 0xFF) shl 16) or
                    ((padded[i + 3].toInt() and 0xFF) shl 24)
            }
            var a = a0
            var b = b0
            var c = c0
            var d = d0
            for (i in 0 until 64) {
                val round = i / 16
                val f: Int
                val g: Int
                when (round) {
                    0 -> {
                        f = (b and c) or (b.inv() and d)
                        g = i
                    }
                    1 -> {
                        f = (d and b) or (d.inv() and c)
                        g = (5 * i + 1) % 16
                    }
                    2 -> {
                        f = b xor c xor d
                        g = (3 * i + 5) % 16
                    }
                    else -> {
                        f = c xor (b or d.inv())
                        g = (7 * i) % 16
                    }
                }
                val tmp = d
                d = c
                c = b
                b = b + (a + f + K[i] + m[g]).rotateLeft(S[i])
                a = tmp
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
        }

        val out = ByteArray(16)
        putLe(out, 0, a0)
        putLe(out, 4, b0)
        putLe(out, 8, c0)
        putLe(out, 12, d0)
        return out
    }

    /** [Md5.digest] as 32 lowercase hex characters. */
    fun digestHex(message: ByteArray): String {
        val digest = digest(message)
        val chars = CharArray(32)
        for (i in 0 until 16) {
            val v = digest[i].toInt() and 0xFF
            chars[i * 2] = "0123456789abcdef"[v shr 4]
            chars[i * 2 + 1] = "0123456789abcdef"[v and 0xF]
        }
        return chars.concatToString()
    }

    private fun putLe(out: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) {
            out[offset + i] = (value ushr (8 * i)).toByte()
        }
    }
}

/** Hex MD5 fingerprint of [text]'s UTF-8 bytes. */
fun md5Hex(text: String): String = Md5.digestHex(text.encodeToByteArray())
