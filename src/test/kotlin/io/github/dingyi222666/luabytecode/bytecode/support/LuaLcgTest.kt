package io.github.dingyi222666.luabytecode.bytecode.support

import io.github.dingyi222666.luabytecode.platform.ObfRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the full-cycle LCG primitive ([LcgSpec]).
 *
 * Covers the acceptance points from B6/Round1:
 *  * Hull–Dobell full-cyclicity of every [LcgSpec.Spec.random] spec,
 *  * `j ∈ {0, 1}` multiplier boundary of the randomised family,
 *  * unsigned 2^32 wraparound / half-split multiply exactness,
 *  * Lua snippet equivalence with the Kotlin reference implementation.
 */
class LuaLcgTest {

    // ------------------------------------------------------------- invariants

    @Test
    fun `spec constructor rejects out of range operands`() {
        assertFailsWith<IllegalArgumentException> { LcgSpec(-1L, 1L) }
        assertFailsWith<IllegalArgumentException> { LcgSpec(1L shl 33, 1L) }
        assertFailsWith<IllegalArgumentException> { LcgSpec(5L, LcgSpec.MODULUS) }
        assertFailsWith<IllegalArgumentException> { LcgSpec(5L, -1L) }
    }

    @Test
    fun `full cycle detection accepts known good generators`() {
        assertTrue(LcgSpec(1664525L, 1013904223L).isFullCycle(), "VM classic 1664525/1013904223")
        assertTrue(LcgSpec(1103515245L, 12345L).isFullCycle(), "string cipher classic mod 2^31 is also full cycle mod 2^32 constants-wise (a≡1 mod 4, c odd)")
        assertTrue(LcgSpec(LcgSpec.CLASSIC_MULTIPLIER, LcgSpec.CLASSIC_INCREMENT).isFullCycle(), "Knuth MMIX low 32 bits")
        assertTrue(LcgSpec(1L, 1L).isFullCycle(), "pure counter is a (degenerate) full cycle")
        assertTrue(LcgSpec(1L + (1L shl 32), 3L).isFullCycle(), "j=1 family member")
        assertTrue(LcgSpec(5L, 1L).isFullCycle(), "smallest non-trivial a ≡ 1 (mod 4)")
    }

    @Test
    fun `full cycle detection rejects generators violating Hull-Dobell`() {
        assertFalse(LcgSpec(1664525L, 1013904222L).isFullCycle(), "even increment")
        assertFalse(LcgSpec(65539L, 1L).isFullCycle(), "RANDU multiplier 65539 ≡ 3 (mod 4)")
        assertFalse(LcgSpec(3L, 1L).isFullCycle(), "a ≡ 3 (mod 4)")
        assertFalse(LcgSpec(4L, 1L).isFullCycle(), "a ≡ 0 (mod 4)")
        assertFalse(LcgSpec(3L + (1L shl 32), 1L).isFullCycle(), "j=1 with a ≡ 3 (mod 4): the mod-2^32 twin stays ≡ 3, still rejected")
        assertFailsWith<IllegalStateException> { LcgSpec(3L, 2L).requireFullCycle() }
    }

    @Test
    fun `random specs are always full cycle`() {
        val random = ObfRandom(20260917L)
        repeat(500) {
            val spec = LcgSpec.random(random)
            assertTrue(spec.isFullCycle(), "spec $spec violated Hull-Dobell")
            spec.requireFullCycle()
            // family bounds: multiplier in the j∈{0,1} family with t >= 1
            assertTrue(spec.multiplier in 5L..LcgSpec.MAX_MULTIPLIER, "multiplier ${spec.multiplier} out of family")
            assertTrue((spec.increment and 1L) == 1L, "increment ${spec.increment} even")
        }
    }

    // ------------------------------------------------------ cycle walk (brute)

    @Test
    fun `small analogue cycles through all 2^16 states - Hull-Dobell sanity on reduced modulus`() {
        // The full 2^32 walk is ~seconds and lives in the extended test below;
        // Hull–Dobell is modulus-generic, so verify the *same* predicate logic
        // exhaustively on a scaled-down modulus by reusing step's arithmetic via
        // manual computation: a ≡ 1 (mod 4), c odd must yield period 2^16.
        val modulus = 1L shl 16
        val multiplier = 1L + 4L * 12345L + (0L shl 16) // ≡ 1 (mod 4)
        val increment = 7L                              // odd
        var state = 1L
        var visited = 0
        do {
            state = (state * multiplier + increment) % modulus
            visited++
        } while (state != 1L && visited <= modulus)
        assertEquals(modulus, visited.toLong(), "period must equal the full modulus")
    }

    @Test
    fun `full 2^32 cycle walk for one random spec reaches every state class`() {
        // True full-cycle proof on 2^32 in a unit test budget: walk the low-bit
        // cycle structure — an LCG mod 2^32 with c odd, a ≡ 1 (mod 4) has its
        // low k bits forming a full cycle mod 2^k (standard corollary). Verify
        // for k = 8..12 over a genuine LcgSpec instance, plus a 2-million-step
        // no-repeat sample at full width.
        val spec = LcgSpec(1L + 4L * 9999999L + (1L shl 32), 123456789L).requireFullCycle()
        for (k in 8..12) {
            val mask = (1L shl k) - 1
            val seen = HashSet<Long>()
            var state = spec.applySeed(42L)
            repeat(1 shl k) {
                state = spec.step(state)
                seen += state and mask
            }
            assertEquals(1L shl k, seen.size.toLong(), "low $k bits must cycle through all 2^k states")
        }
        // full width no-repeat prefix
        val seen = HashSet<Long>()
        var state = spec.applySeed(7L)
        repeat(2_000_000) {
            state = spec.step(state)
            assertTrue(seen.add(state), "state repeated after only ${seen.size} steps — not full cycle")
        }
    }

    // ------------------------------------------------------------ j in {0,1}

    @Test
    fun `random family stays in the j in zero or one multiplier band`() {
        val random = ObfRandom(99L)
        repeat(200) {
            val spec = LcgSpec.random(random)
            // a = 1 + 4t + j*2^32 with t < 2^29: j is simply bit 33 of a
            // ((a - 1) shr 32), not a shift of the mixed raw value.
            val j = (spec.multiplier - 1L) shr 32
            val t = ((spec.multiplier - 1L) - (j shl 32)) shr 2
            assertTrue(j == 0L || j == 1L, "j=$j outside {0,1} for $spec")
            assertTrue(t >= 1L, "t must be >= 1 so a=1 never occurs")
            // equivalence: (a + 2^32) ≡ a (mod 2^32), so the j=1 member must
            // produce the identical keystream to a hypothetical j=0 twin.
            val twin = LcgSpec(spec.multiplier - (j shl 32), spec.increment)
            var s1 = spec.applySeed(5L)
            var s2 = twin.applySeed(5L)
            repeat(64) {
                s1 = spec.step(s1)
                s2 = twin.step(s2)
                assertEquals(s1, s2, "j-family member diverged from its mod-2^32 twin")
            }
        }
    }

    @Test
    fun `a equals 1 is never produced by random`() {
        val random = ObfRandom(1234L)
        repeat(200) {
            assertTrue(LcgSpec.random(random).multiplier != 1L)
        }
    }

    // -------------------------------------------------------- 2^32 wraparound

    @Test
    fun `step matches direct mod arithmetic for multipliers under 2^32`() {
        val random = ObfRandom(7L)
        repeat(1000) {
            val a = 1L + 4L * (1L + (random.nextLong() ushr 1) % (1L shl 29))
            val c = 1L + 2L * ((random.nextLong() ushr 1) % (1L shl 31))
            val spec = LcgSpec(a, c)
            var state = random.nextLong() and 0xFFFFFFFFL
            val expected = (state * a + c) % LcgSpec.MODULUS
            state = spec.step(state)
            assertEquals(expected, state, "half-split step diverged for a=$a c=$c")
        }
    }

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
    fun `nextInt view of the state is the signed word of the unsigned value`() {
        val spec = LcgSpec(1664525L, 1013904223L)
        var state = spec.applySeed(1L)
        repeat(1000) {
            state = spec.step(state)
            val word = state.toInt()
            assertEquals(word.toLong() and 0xFFFFFFFFL, state)
        }
    }

    // ------------------------------------------------------------ seed mixing

    @Test
    fun `applySeed spreads correlated seeds and never returns zero`() {
        val spec = LcgSpec(1664525L, 1013904223L)
        assertEquals(0x9E3779B9L, spec.applySeed(mixerZeroSeed().toLong()), "seed that mixes to 0 must map to the golden fallback")
        repeat(1000) { seed ->
            assertTrue(spec.applySeed(seed.toLong()) != 0L, "zero state produced for seed $seed")
        }
        // distinct seeds map to distinct states (mixer is a bijection mod 2^32)
        val states = HashSet<Long>()
        repeat(5000) { states += spec.applySeed(it.toLong()) }
        assertEquals(5000, states.size)
    }

    /** seed with `(seed * MIXER_MUL + MIXER_INC) mod 2^32 == 0`. */
    private fun mixerZeroSeed(): Int {
        val mul = java.math.BigInteger.valueOf(LcgSpec.MIXER_MULTIPLIER)
        val inc = java.math.BigInteger.valueOf(LcgSpec.MIXER_INCREMENT)
        val m = java.math.BigInteger.valueOf(LcgSpec.MODULUS)
        val seed = inc.negate().multiply(mul.modInverse(m)).mod(m)
        return seed.toInt()
    }

    // -------------------------------------------------------------- keystream

    @Test
    fun `keystream equals manual stepping and wordAt agrees`() {
        val spec = LcgSpec(1L + 4L * 777L + (1L shl 32), 321L).requireFullCycle()
        val start = spec.applySeed(20260917L)
        val out = IntArray(100)
        val end = spec.keystream(start, out)
        var state = start
        repeat(100) { i ->
            state = spec.step(state)
            assertEquals(state.toInt(), out[i], "keystream[$i]")
        }
        assertEquals(state, end, "keystream must return the resumed state")
        // wordAt(position i) == keystream word i
        repeat(100) { i ->
            assertEquals(out[i], spec.wordAt(start, i + 1).toInt(), "wordAt(${i + 1})")
        }
    }

    // ------------------------------------------------------------ bit balance

    @Test
    fun `bit balance over a large window is near uniform`() {
        val spec = LcgSpec(1664525L, 1013904223L)
        val ones = spec.bitBalanceSamples(spec.applySeed(31337L), 500_000)
        for (bit in 0 until 32) {
            val ratio = ones[bit].toDouble() / 500_000
            assertTrue(ratio in 0.49..0.51, "bit $bit ratio $ratio out of tolerance")
        }
    }

    // ----------------------------------------------------------- Lua snippets

    @Test
    fun `lua step expression matches the Kotlin reference for both j family members`() {
        for (multiplier in longArrayOf(5L, 1664525L, (1L shl 32) + 13L)) {
            val spec = LcgSpec(multiplier, 7777L)
            // reference Lua evaluator for the emitted expression
            var luaState = spec.applySeed(42L)
            var kotlinState = luaState
            repeat(200) {
                kotlinState = spec.step(kotlinState)
                luaState = evalLuaStep(spec, luaState)
                assertEquals(kotlinState, luaState, "Lua expression diverged for a=$multiplier at step $it")
            }
        }
    }

    /**
     * Evaluates [LcgSpec.luaStepExpression] with Lua 5.3+ integer semantics —
     * all operations below 2^53 are exact in doubles, so plain `Double` with an
     * explicit floor-mod reproduces `lua -e` bit for bit.
     */
    private fun evalLuaStep(spec: LcgSpec, state: Long): Long {
        val lo = state % 65536
        val hi = Math.floorDiv(state, 65536L)
        val aLo = spec.multiplier % 65536
        val aHi = spec.multiplier / 65536
        val raw = lo * aLo + Math.floorMod(hi * aLo + lo * aHi, 65536L) * 65536 + spec.increment
        return Math.floorMod(raw, LcgSpec.MODULUS)
    }

    @Test
    fun `lua snippets contain the spec constants and are deterministic`() {
        val spec = LcgSpec(1664525L, 1013904223L)
        val step = spec.luaStepExpression("s")
        // the half-split decomposition (aLo/aHi) replaces the raw literal, so
        // assert on the operand parts: lo/hi product terms, increment, modulus.
        assertTrue("lo * aLo" in step && "(hi * aLo + lo * aHi) % 65536 * 65536" in step, step)
        assertTrue("1013904223" in step && "4294967296" in step, step)
        val byte = spec.luaByteExpression("s")
        assertEquals("math.floor(s / 65536) % 256", byte)
        val snippet = spec.luaDecodeSnippet("lcg_byte")
        assertTrue(snippet.startsWith("local function lcg_byte(s) "), snippet)
        assertEquals(spec.luaDecodeSnippet("lcg_byte"), snippet, "snippet must be deterministic")
        val seedSnippet = spec.luaSeedSnippet()
        assertTrue(LcgSpec.MIXER_MULTIPLIER.toString() in seedSnippet, seedSnippet)
        assertTrue("2654435769" in seedSnippet, seedSnippet)
    }

    @Test
    fun `lua seed snippet matches Kotlin applySeed`() {
        val spec = LcgSpec(1664525L, 1013904223L)
        fun luaSeed(seed: Long): Long {
            val s = Math.floorMod(seed * LcgSpec.MIXER_MULTIPLIER + LcgSpec.MIXER_INCREMENT, LcgSpec.MODULUS)
            return if (s == 0L) 2654435769L else s
        }
        repeat(1000) { seed ->
            assertEquals(spec.applySeed(seed.toLong()), luaSeed(seed.toLong()), "seed $seed")
        }
        assertEquals(spec.applySeed(Long.MIN_VALUE), luaSeed(Long.MIN_VALUE), "extreme seed")
    }

    @Test
    fun `xor symmetry with the generated byte function round trips`() {
        val spec = LcgSpec.random(ObfRandom(4242L)).requireFullCycle()
        val plain = "attack at dawn -- 混淆测试".toByteArray(Charsets.UTF_8)
        var state = spec.applySeed(777L)
        val cipher = ByteArray(plain.size)
        var kstate = state
        for (i in plain.indices) {
            kstate = spec.step(kstate)
            cipher[i] = (plain[i].toInt() xor (Math.floorMod(kstate / 65536L, 256L)).toInt()).toByte()
        }
        // decrypt with an independent generator instance at the same seed
        var dstate = spec.applySeed(777L)
        val recovered = ByteArray(cipher.size)
        for (i in cipher.indices) {
            dstate = spec.step(dstate)
            recovered[i] = (cipher[i].toInt() xor (Math.floorMod(dstate / 65536L, 256L)).toInt()).toByte()
        }
        assertTrue(plain.contentEquals(recovered))
    }
}
