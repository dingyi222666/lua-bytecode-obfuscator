package io.github.dingyi222666.luabytecode.bytecode.support

import io.github.dingyi222666.luabytecode.platform.ObfRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [LuaDecodeVariants] — the VM-2 skeleton-variant generator.
 *
 * Three property families:
 *  1. **Equivalence** — every StepForm computes [LcgSpec.step], every
 *     ByteExtract computes `(s >> 16) & 0xFF`, and each seed-init mirror is a
 *     bijective, zero-avoiding map mod 2^32.
 *  2. **Structural uniqueness** — after digit-canonicalization (R2's matcher
 *     form) the shapes stay distinct: 6 / 5 / 4 classes on the three axes.
 *  3. **Per-chunk literal uniqueness** — random draws yield distinct raw md5.
 */
class LuaDecodeVariantsTest {

    private fun allDraws(guard: Long = 2654435769L): List<LuaDecodeVariants.Draw> =
        LuaDecodeVariants.SeedInit.entries.flatMap { si ->
            LuaDecodeVariants.StepForm.entries.flatMap { sf ->
                LuaDecodeVariants.ByteExtract.entries.map { be ->
                    LuaDecodeVariants.Draw(si, sf, be, m1 = 3L, a1 = 5L, m2 = 7L, a2 = 11L, k1 = 9L, guard = guard)
                }
            }
        }

    // -------------------------------------------------------------- equivalence

    @Test
    fun `every step form equals the reference LCG step`() {
        val spec = LcgSpec.random(ObfRandom(7L)).requireFullCycle()
        for (form in LuaDecodeVariants.StepForm.entries) {
            var state = spec.applySeed(20260917L)
            repeat(500) { i ->
                val expected = spec.step(state)
                state = LuaDecodeVariants.stepMirror(spec, form, state)
                assertEquals(expected, state, "StepForm $form diverged at step $i")
            }
        }
    }

    @Test
    fun `step forms stay equivalent for both j family members and degenerate a`() {
        for (multiplier in longArrayOf(5L, 1664525L, (1L shl 32) + 13L)) {
            val spec = LcgSpec(multiplier, 7777L)
            val reference = LuaDecodeVariants.StepForm.DIRECT
            for (form in LuaDecodeVariants.StepForm.entries) {
                var s = 0xDEADBEEFL
                repeat(64) {
                    val expected = LuaDecodeVariants.stepMirror(spec, reference, s)
                    s = LuaDecodeVariants.stepMirror(spec, form, s)
                    assertEquals(expected, s, "StepForm $form (a=$multiplier) diverged")
                }
            }
        }
    }

    @Test
    fun `every byte extract equals the third byte`() {
        val random = ObfRandom(42L)
        for (form in LuaDecodeVariants.ByteExtract.entries) {
            repeat(2000) {
                val state = random.nextLong() and 0xFFFFFFFFL
                assertEquals(((state ushr 16) and 0xFFL).toInt(), LuaDecodeVariants.byteMirror(form, state))
            }
            // boundary states
            for (state in longArrayOf(0L, 0xFFFFL, 0x10000L, 0xFFFFFFFFL)) {
                assertEquals(((state ushr 16) and 0xFFL).toInt(), LuaDecodeVariants.byteMirror(form, state))
            }
        }
    }

    @Test
    fun `seed init mirrors are zero avoiding and map into the 32 bit domain`() {
        val random = ObfRandom(99L)
        for (init in LuaDecodeVariants.SeedInit.entries) {
            val draw = LuaDecodeVariants.Draw(init, LuaDecodeVariants.StepForm.DIRECT, LuaDecodeVariants.ByteExtract.FLOOR_DIV,
                m1 = 0x9DE39CADL, a1 = 0x12345679L, m2 = 5L, a2 = 7L, k1 = 3L, guard = 2654435769L)
            for (seed in longArrayOf(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 20260917L)) {
                val out = LuaDecodeVariants.seedInitMirror(draw, seed)
                assertTrue(out in 0 until LcgSpec.MODULUS, "SeedInit $init out of range for seed $seed: $out")
                assertTrue(out != 0L, "SeedInit $init returned 0 for seed $seed")
            }
            repeat(1000) {
                val out = LuaDecodeVariants.seedInitMirror(draw, random.nextLong())
                assertTrue(out in 1 until LcgSpec.MODULUS, "SeedInit $init bad output $out")
            }
        }
    }

    @Test
    fun `seed init mirror is injective on a 50k sample - bijectivity smoke`() {
        val draw = LuaDecodeVariants.Draw(
            LuaDecodeVariants.SeedInit.ROTATE_FOLD, LuaDecodeVariants.StepForm.DIRECT, LuaDecodeVariants.ByteExtract.FLOOR_DIV,
            m1 = 0x9DE39CADL, a1 = 0x12345679L, m2 = 1L, a2 = 0L, k1 = 0x5DEECE66DL, guard = 2654435769L,
        )
        val seen = HashSet<Long>()
        var base = 123456789L
        repeat(50_000) {
            val out = LuaDecodeVariants.seedInitMirror(draw, base + it)
            assertTrue(seen.add(out), "SeedInit ROTATE_FOLD collided at input ${base + it}")
        }
    }

    // ------------------------------------------------------ structural identity

    @Test
    fun `structural md5 classes - 6 seed inits, 5 step forms, 4 byte extracts`() {
        fun countShapes(texts: List<String>): Int = texts.map { LuaDecodeVariants.structuralMd5(it) }.toSet().size

        val seedTexts = LuaDecodeVariants.SeedInit.entries.map { init ->
            val draw = LuaDecodeVariants.Draw(init, LuaDecodeVariants.StepForm.DIRECT, LuaDecodeVariants.ByteExtract.FLOOR_DIV,
                m1 = 3L, a1 = 5L, m2 = 7L, a2 = 11L, k1 = 9L, guard = 2654435769L)
            LuaDecodeVariants.generate(LcgSpec(5L, 1L), draw).seedInitFunction("f")
        }
        assertTrue(countShapes(seedTexts) >= 6, "expected >= 6 seed-init shapes, got ${countShapes(seedTexts)}")

        val stepTexts = LuaDecodeVariants.StepForm.entries.map { form ->
            val draw = LuaDecodeVariants.Draw(LuaDecodeVariants.SeedInit.MIXED_GUARD, form, LuaDecodeVariants.ByteExtract.FLOOR_DIV,
                m1 = 3L, a1 = 5L, m2 = 7L, a2 = 11L, k1 = 9L, guard = 2654435769L)
            LuaDecodeVariants.generate(LcgSpec(1664525L, 1013904223L), draw).stepStatement("state")
        }
        assertTrue(countShapes(stepTexts) >= 5, "expected >= 5 step shapes, got ${countShapes(stepTexts)}")

        val byteTexts = LuaDecodeVariants.ByteExtract.entries.map { form ->
            val draw = LuaDecodeVariants.Draw(LuaDecodeVariants.SeedInit.MIXED_GUARD, LuaDecodeVariants.StepForm.DIRECT, form,
                m1 = 3L, a1 = 5L, m2 = 7L, a2 = 11L, k1 = 9L, guard = 2654435769L)
            LuaDecodeVariants.generate(LcgSpec(5L, 1L), draw).byteExpression("state")
        }
        assertTrue(countShapes(byteTexts) >= 4, "expected >= 4 byte-extract shapes, got ${countShapes(byteTexts)}")
    }

    @Test
    fun `random draws are raw-md5 unique across 100 chunks`() {
        val random = ObfRandom(2026L)
        val seen = HashSet<String>()
        val combined = HashSet<String>()
        repeat(100) {
            val draw = LuaDecodeVariants.draw(random)
            val sk = LuaDecodeVariants.generate(LcgSpec.random(random), draw)
            seen += LuaDecodeVariants.rawMd5(sk.seedInitFunction("lcg_seed"))
            combined += LuaDecodeVariants.rawMd5(
                sk.seedInitFunction("lcg_seed") + sk.stepStatement("s") + sk.byteExpression("s"),
            )
        }
        assertEquals(100, combined.size, "combined skeleton md5 collided across 100 draws")
        // seed-init alone can collide by chance far less often; structure classes are shared
        assertTrue(seen.size >= 90, "seed-init raw md5 uniqueness too low: ${seen.size}/100")
    }

    // ------------------------------------------------------------ composition

    @Test
    fun `generated skeleton composes into a working decode loop in kotlin mirror form`() {
        val random = ObfRandom(5L)
        repeat(20) {
            val draw = LuaDecodeVariants.draw(random)
            val spec = LcgSpec.random(random).requireFullCycle()
            val sk = LuaDecodeVariants.generate(spec, draw)
            // placeholders substituted
            val seedFn = sk.seedInitFunction("my_seed")
            val step = sk.stepStatement("state")
            val byteExpr = sk.byteExpression("state")
            assertTrue("my_seed" in seedFn && "@SEEDFN@" !in seedFn)
            assertTrue("state" in step && "@S@" !in step)
            assertTrue("state" in byteExpr && "@S@" !in byteExpr)
            // end-to-end mirror round trip
            var state = LuaDecodeVariants.seedInitMirror(draw, 777L)
            val plain = "round trip payload 混淆".toByteArray(Charsets.UTF_8)
            val cipher = ByteArray(plain.size)
            for (i in plain.indices) {
                state = LuaDecodeVariants.stepMirror(spec, draw.stepForm, state)
                cipher[i] = (plain[i].toInt() xor LuaDecodeVariants.byteMirror(draw.byteExtract, state)).toByte()
            }
            var dstate = LuaDecodeVariants.seedInitMirror(draw, 777L)
            val recovered = ByteArray(cipher.size)
            for (i in cipher.indices) {
                dstate = LuaDecodeVariants.stepMirror(spec, draw.stepForm, dstate)
                recovered[i] = (cipher[i].toInt() xor LuaDecodeVariants.byteMirror(draw.byteExtract, dstate)).toByte()
            }
            assertTrue(plain.contentEquals(recovered), "round trip failed for draw $draw")
        }
    }

    @Test
    fun `all draws constructor accepts the spec range without exceptions`() {
        val spec = LcgSpec((1L shl 32) + 13L, 4294967295L)
        for (draw in allDraws()) {
            LuaDecodeVariants.generate(spec, draw)
        }
    }
}
