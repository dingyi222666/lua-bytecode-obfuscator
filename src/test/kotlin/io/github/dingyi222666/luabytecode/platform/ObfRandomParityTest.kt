package io.github.dingyi222666.luabytecode.platform

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Differential parity pin between [ObfRandom] (pure common Kotlin, JVM-safe)
 * and the JVM reference `java.util.Random`.
 *
 * This is the lynchpin of cross-platform reproducibility: every obfuscation
 * pass draws from [ObfRandom], and `--seed N` must produce byte-identical
 * artifacts wherever the modules end up running. These tests fail the build
 * the moment the clone's draw sequence drifts from the JDK reference, for
 * every method the passes actually use (including the JDK 17
 * `RandomGenerator` bounded defaults `nextInt(origin, bound)` and
 * `nextLong(bound)`).
 */
class ObfRandomParityTest {

    private val seeds = longArrayOf(
        0L, 1L, 7L, 42L, -1L, 99L, 20260917L, 0x5DEECE66DL,
        Long.MAX_VALUE, Long.MIN_VALUE, 0x123456789ABCDEL,
    )

    @Test
    fun `nextInt parity with java-util-Random`() {
        for (seed in seeds) {
            val j = Random(seed)
            val k = ObfRandom(seed)
            repeat(50_000) {
                assertEquals(j.nextInt(), k.nextInt(), "nextInt parity broken for seed $seed at draw $it")
            }
        }
    }

    @Test
    fun `nextInt(bound) parity across bound shapes`() {
        // Powers of two, primes, the documented worst-case rejection bound
        // (2^30 + 1), small bounds, the max bound, and every bound the passes use.
        val bounds = intArrayOf(
            1, 2, 3, 4, 10, 16, 17, 26, 30, 63, 64, 89, 91, 997, 128, 8192,
            1 shl 16, 1 shl 20, 0x80000, 0x2000000, 12_000_000,
            (1 shl 30) + 1, Int.MAX_VALUE,
        )
        for (seed in seeds) {
            for (bound in bounds) {
                val j = Random(seed)
                val k = ObfRandom(seed)
                repeat(2_000) {
                    val expected = j.nextInt(bound)
                    val actual = k.nextInt(bound)
                    assertTrue(actual in 0 until bound, "bound $bound violated: $actual")
                    assertEquals(
                        expected, actual,
                        "nextInt($bound) parity broken for seed $seed at draw $it",
                    )
                }
            }
        }
    }

    @Test
    fun `nextInt(origin, bound) parity across range shapes`() {
        val ranges = listOf(
            0 to 1,
            1 to Int.MAX_VALUE,       // used by Pass K / string encryptor salts
            1 to (1 shl 20),          // used by CFF constants
            5 to 10,
            -100 to 101,
            Int.MIN_VALUE + 1000 to Int.MAX_VALUE - 1000,
            Int.MIN_VALUE to Int.MAX_VALUE, // width overflows Int -> full-range branch
            Int.MIN_VALUE to 0,
        )
        for (seed in seeds) {
            for ((origin, bound) in ranges) {
                val j = Random(seed)
                val k = ObfRandom(seed)
                repeat(2_000) {
                    val expected = j.nextInt(origin, bound)
                    val actual = k.nextInt(origin, bound)
                    assertTrue(actual in origin until bound, "range $origin..$bound violated: $actual")
                    assertEquals(
                        expected, actual,
                        "nextInt($origin, $bound) parity broken for seed $seed at draw $it",
                    )
                }
            }
        }
        // Both must reject inverted ranges.
        assertFailsWith<IllegalArgumentException> { Random(1).nextInt(5, 5) }
        assertFailsWith<IllegalArgumentException> { ObfRandom(1).nextInt(5, 5) }
        assertFailsWith<IllegalArgumentException> { Random(1).nextInt(9, 5) }
        assertFailsWith<IllegalArgumentException> { ObfRandom(1).nextInt(9, 5) }
    }

    @Test
    fun `nextInt(bound) rejects non-positive bounds`() {
        assertFailsWith<IllegalArgumentException> { Random(1).nextInt(0) }
        assertFailsWith<IllegalArgumentException> { ObfRandom(1).nextInt(0) }
        assertFailsWith<IllegalArgumentException> { Random(1).nextInt(-3) }
        assertFailsWith<IllegalArgumentException> { ObfRandom(1).nextInt(-3) }
    }

    @Test
    fun `nextLong parity with java-util-Random`() {
        for (seed in seeds) {
            val j = Random(seed)
            val k = ObfRandom(seed)
            repeat(50_000) {
                assertEquals(j.nextLong(), k.nextLong(), "nextLong parity broken for seed $seed at draw $it")
            }
        }
    }

    @Test
    fun `nextLong(bound) parity across bound shapes`() {
        val bounds = longArrayOf(
            1L, 2L, 3L, 0x1FFFFL, 0x3FFFFFFFL, // the bounds LuaStringCipher / ConstCipher draw
            (1L shl 29) - 1, 1L shl 31, 1L shl 32, 1L shl 62,
            (1L shl 62) + 1, Long.MAX_VALUE, // max odd bound: worst-case rejection
        )
        for (seed in seeds) {
            for (bound in bounds) {
                val j = Random(seed)
                val k = ObfRandom(seed)
                repeat(2_000) {
                    val expected = j.nextLong(bound)
                    val actual = k.nextLong(bound)
                    assertTrue(actual in 0 until bound, "bound $bound violated: $actual")
                    assertEquals(
                        expected, actual,
                        "nextLong($bound) parity broken for seed $seed at draw $it",
                    )
                }
            }
        }
        assertFailsWith<IllegalArgumentException> { Random(1).nextLong(0L) }
        assertFailsWith<IllegalArgumentException> { ObfRandom(1).nextLong(0L) }
        assertFailsWith<IllegalArgumentException> { Random(1).nextLong(-7L) }
        assertFailsWith<IllegalArgumentException> { ObfRandom(1).nextLong(-7L) }
    }

    @Test
    fun `nextBoolean nextDouble nextFloat parity`() {
        for (seed in seeds) {
            val j = Random(seed)
            val k = ObfRandom(seed)
            repeat(20_000) {
                assertEquals(j.nextBoolean(), k.nextBoolean(), "nextBoolean parity broken for seed $seed")
                assertEquals(j.nextDouble(), k.nextDouble(), "nextDouble parity broken for seed $seed")
                assertEquals(j.nextFloat(), k.nextFloat(), "nextFloat parity broken for seed $seed")
            }
        }
    }

    @Test
    fun `setSeed parity mid-stream`() {
        for (seed in seeds) {
            val j = Random(seed)
            val k = ObfRandom(seed)
            repeat(1_000) { j.nextInt(1 shl 20); k.nextInt(1 shl 20) }
            val reseed = 0xC0FFEEDEL
            j.setSeed(reseed)
            k.setSeed(reseed)
            repeat(10_000) {
                assertEquals(j.nextInt(997), k.nextInt(997), "setSeed parity broken for seed $seed")
            }
        }
    }

    @Test
    fun `shuffled parity - common stdlib shuffle vs Collections-shuffle`() {
        // `Iterable.shuffled(ObfRandom)` goes through the common
        // kotlin.collections shuffle (draws nextInt(i + 1)); the historical
        // JVM path went through java.util.Collections.shuffle (draws
        // nextInt(i)). The sequences are identical by construction; this test
        // pins it against the real JVM behaviour.
        for (seed in longArrayOf(0L, 1L, 42L, -99L, Long.MAX_VALUE)) {
            for (size in intArrayOf(0, 1, 2, 3, 5, 7, 30, 257, 4_000)) {
                val source = List(size) { it }
                val expected = source.shuffled(Random(seed))
                val actual = source.shuffled(ObfRandom(seed))
                assertEquals(expected, actual, "shuffled parity broken for seed $seed size $size")
            }
        }
    }

    @Test
    fun `nextBytes parity with java-util-Random`() {
        // nextBytes is the draw-order trap: the JDK consumes one nextInt() per
        // up-to-4 bytes, the common stdlib default consumes 8 bits per byte.
        val sizes = intArrayOf(0, 1, 2, 3, 4, 5, 7, 8, 13, 16, 64, 333)
        for (seed in seeds) {
            for (size in sizes) {
                val jb = ByteArray(size)
                val kb = ByteArray(size)
                Random(seed).nextBytes(jb)
                ObfRandom(seed).nextBytes(kb)
                assertTrue(
                    jb.contentEquals(kb),
                    "nextBytes($size) parity broken for seed $seed",
                )
            }
        }
    }

    @Test
    fun `same seed reproduces the same sequence (reproducibility contract)`() {
        for (seed in seeds) {
            val a = ObfRandom(seed)
            val b = ObfRandom(seed)
            val selector = ObfRandom(seed)
            repeat(10_000) {
                val ops = selector.nextInt(3)
                val expected = when (ops) {
                    0 -> b.nextInt()
                    1 -> b.nextInt(1 shl 16)
                    else -> b.nextLong()
                }
                val actual = when (ops) {
                    0 -> a.nextInt()
                    1 -> a.nextInt(1 shl 16)
                    else -> a.nextLong()
                }
                assertEquals(expected, actual)
            }
        }
    }

    @Test
    fun `unseeded instances are independent`() {
        val a = ObfRandom.unseeded()
        val b = ObfRandom.unseeded()
        repeat(16) {
            assertTrue(a.nextInt() != b.nextInt() || a.nextLong() != b.nextLong())
        }
    }
}
