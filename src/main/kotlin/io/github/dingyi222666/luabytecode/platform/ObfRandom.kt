package io.github.dingyi222666.luabytecode.platform

/**
 * Cross-platform, seed-reproducible PRNG — a bit-exact reimplementation of
 * `java.util.Random`'s 48-bit LCG written in pure Kotlin on top of the common
 * stdlib ([kotlin.random.Random]). This is the obfuscator's canonical random
 * source: every pass draws from an [ObfRandom] seeded by [io.github.dingyi222666.luabytecode.obfuscate.SeedPolicy]
 * domain-separated seeds, so a `--seed N` run produces byte-identical artifacts
 * on every platform (JVM today; js/native once the modules split).
 *
 * ## Why a clone instead of `java.util.Random`
 *
 * `java.util.Random` is JVM-only. The module roadmap
 * (`docs/roadmap-kmp-modules.md`) moves `bytecode/obfuscate/vm` into
 * `commonMain`, where only the common stdlib is available. Extending
 * [kotlin.random.Random] (rather than defining a freestanding class) keeps
 * the stdlib shuffling helpers working: `Iterable.shuffled(random)` draws
 * `nextInt(i + 1)` for `i = size down to 2`, which dispatches to the override
 * below — the exact draw sequence `java.util.Collections.shuffle(list,
 * java.util.Random)` produces today. A `ObfRandomParityTest` pins every
 * method against the JVM reference so a divergence can never slip in.
 *
 * ## Implemented surface (bit-exact vs the JDK 17 reference)
 *
 * * [nextBits] / [nextInt] / [nextInt(until)] / [nextInt(from, until)]
 * * [nextLong] / [nextLong(until)]
 * * [nextBoolean] / [nextDouble] / [nextFloat] / [nextBytes]
 * * [setSeed] and the `ObfRandom(seed)` constructor (same seed unmasking).
 *
 * `nextInt(from, until)` and `nextLong(until)` replicate the
 * `RandomGenerator` default methods (`RandomSupport.boundedNextInt/Long`)
 * that `java.util.Random` inherits on JDK 17+ — including their rejection
 * sampling loops — because the codebase calls them (e.g. `nextInt(1,
 * Int.MAX_VALUE)`, `nextLong(0x1FFFFL)`).
 *
 * Methods *not* overridden (`nextLong(from, until)`, `nextDouble(from,
 * until)`) fall back to the `kotlin.random.Random` default implementations.
 * They stay seed-deterministic but do **not** match `java.util.Random`'s
 * draw pattern; the obfuscator never calls them.
 *
 * ## Honest strength boundary
 *
 * Same as `java.util.Random`: a 48-bit LCG is trivially predictable from a
 * few outputs. Every use here is obfuscation reproducibility, never
 * confidentiality — the only security-relevant consumer is the *unseeded*
 * run mode, which obtains its entropy from the platform (`SecureRandom`) in
 * the entrypoint and never from this class.
 *
 * @param seed the initial seed; any 64-bit value is legal (it is unmasked
 *   with the LCG multiplier exactly like `new java.util.Random(seed)`).
 */
class ObfRandom(seed: Long) : kotlin.random.Random() {

    /** Internal 48-bit state (only the low 48 bits are ever non-zero). */
    private var seed: Long = (seed xor MULTIPLIER) and MASK

    /** Re-seeds this generator (the public `java.util.Random#setSeed` contract). */
    fun setSeed(seed: Long) {
        this.seed = (seed xor MULTIPLIER) and MASK
    }

    /** The core 48-bit LCG step; returns the top [bits] bits of the new state. */
    private fun next(bits: Int): Int {
        seed = (seed * MULTIPLIER + ADDEND) and MASK
        return (seed ushr (48 - bits)).toInt()
    }

    override fun nextBits(bitCount: Int): Int = next(bitCount)

    override fun nextInt(): Int = next(32)

    /** Bit-exact clone of `java.util.Random.nextInt(int bound)`. */
    override fun nextInt(until: Int): Int {
        if (until <= 0) throw IllegalArgumentException("bound must be positive")
        var r = next(31)
        val m = until - 1
        if ((until and m) == 0) {
            // Bound is a power of two: take the high bits (LCG low bits are weak).
            r = ((until * r.toLong()) shr 31).toInt()
        } else {
            // Reject over-represented candidates (java.util.Random's loop shape:
            // `for (int u = r; u - (r = u % bound) + m < 0; u = next(31));`).
            var u = r
            while (true) {
                r = u % until
                if (u - r + m >= 0) break
                u = next(31)
            }
        }
        return r
    }

    /**
     * Bit-exact clone of the JDK 17 `RandomGenerator.nextInt(origin, bound)`
     * default method (`RandomSupport.boundedNextInt`), which `java.util.Random`
     * instances serve today. Handles all four reference cases: power-of-two
     * width, rejection sampling, and the two's-complement overflow case where
     * `until - from` wraps negative (the full-range branch).
     */
    override fun nextInt(from: Int, until: Int): Int {
        if (from >= until) throw IllegalArgumentException("from ($from) must be less than until ($until)")
        var r = nextInt()
        if (from < until) {
            val n = until - from
            val m = n - 1
            if ((n and m) == 0) {
                // Case (2): range width is a power of two.
                r = (r and m) + from
            } else if (n > 0) {
                // Case (3): reject over-represented candidates.
                var u = r ushr 1
                while (true) {
                    r = u % n
                    if (u + m - r >= 0) break
                    u = nextInt() ushr 1
                }
                r += from
            } else {
                // Case (4): `until - from` overflowed (near full-range request).
                while (r < from || r >= until) {
                    r = nextInt()
                }
            }
        }
        return r
    }

    /** Bit-exact clone of `java.util.Random.nextLong()`. */
    override fun nextLong(): Long = (next(32).toLong() shl 32) + next(32)

    /**
     * Bit-exact clone of the JDK 17 `RandomGenerator.nextLong(bound)` default
     * method (`RandomSupport.boundedNextLong`).
     */
    override fun nextLong(until: Long): Long {
        if (until <= 0L) throw IllegalArgumentException("bound must be positive")
        val m = until - 1L
        var r = nextLong()
        if ((until and m) == 0L) {
            // Bound is a power of two.
            r = r and m
        } else {
            // Reject over-represented candidates
            // (`for (long u = r >>> 1; u + m - (r = u % bound) < 0L; u = nextLong() >>> 1);`).
            var u = r ushr 1
            while (true) {
                r = u % until
                if (u + m - r >= 0L) break
                u = nextLong() ushr 1
            }
        }
        return r
    }

    override fun nextBoolean(): Boolean = next(1) != 0

    /** Bit-exact clone of `java.util.Random.nextDouble()` (53 random significand bits). */
    override fun nextDouble(): Double = ((next(26).toLong() shl 27) + next(27)) * DOUBLE_UNIT

    /** Bit-exact clone of `java.util.Random.nextFloat()` (24 random significand bits). */
    override fun nextFloat(): Float = next(24) / (1 shl 24).toFloat()

    /**
     * Bit-exact clone of `java.util.Random.nextBytes(byte[])`.
     *
     * This override is load-bearing for draw-order compatibility: the common
     * `kotlin.random.Random.nextBytes` default draws `nextBits(8)` **once per
     * byte**, while the JDK fills up to four bytes from a single `nextInt()`.
     * Without it every draw after a `nextBytes` call desyncs from the JVM
     * reference (Pass K's cipher-blob filler hit exactly that).
     */
    override fun nextBytes(array: ByteArray, fromIndex: Int, toIndex: Int): ByteArray {
        require(fromIndex >= 0 && toIndex <= array.size && fromIndex <= toIndex) {
            "invalid range $fromIndex until $toIndex for array of size ${array.size}"
        }
        var i = fromIndex
        while (i < toIndex) {
            var rnd = nextInt()
            var n = minOf(toIndex - i, Int.SIZE_BITS / Byte.SIZE_BITS)
            while (n-- > 0) {
                array[i++] = rnd.toByte()
                rnd = rnd shr 8
            }
        }
        return array
    }

    companion object {
        private const val MULTIPLIER = 0x5DEECE66DL
        private const val ADDEND = 0xBL
        private const val MASK = 0xFFFF_FFFF_FFFFL // (1L shl 48) - 1
        private val DOUBLE_UNIT = 1.0 / (1L shl 53) // == 0x1.0p-53

        /**
         * Unseeded mode. Draws the initial seed from the platform-default
         * `kotlin.random.Random` (common stdlib), i.e. fresh entropy per run
         * on every backend. Pass code should prefer receiving a seeded
         * generator from the caller; this constructor exists for parity with
         * the historical `Random()` uses.
         */
        fun unseeded(): ObfRandom = ObfRandom(kotlin.random.Random.Default.nextLong())
    }
}
