package io.github.dingyi222666.luabytecode.bytecode.support

import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.platform.md5Hex

/**
 * Structurally distinct Lua decode-skeleton variants (B6/Round4, VM-2 附录 B).
 *
 * R2/R6 measured the VM-2 fingerprint problem precisely: across 16 emitted
 * modules the three skeleton blocks (`M.decrypt` / `decode()` /
 * `decryptConstants`) each have exactly **one distinct md5** — text-polymorphic
 * names over byte-identical structure, so "one rule eats the whole family".
 * This generator gives B2 the missing dimension: the *shape* of each skeleton
 * is drawn per chunk, not only the names.
 *
 * Three axes, combinable:
 *  * [SeedInit] — how the runtime seed becomes the initial state (6 shapes),
 *  * [StepForm] — how one LCG step is spelled (5 shapes, all equal to
 *    [LcgSpec.step]),
 *  * [ByteExtract] — how the keystream byte is taken from the state (4 shapes).
 *
 * All emitted text is **exact under Lua 5.3 / 5.4 / 5.5 integer semantics**
 * (64-bit two's-complement wrap for `*` / `+` / `-`, floor `%`, logical `>>`
 * / `<<`, `~`, `&`, `|`): the Kotlin mirrors in the unit tests reproduce the
 * same wrap and are compared against the reference LCG step, and the shell
 * round-trip recorded in `agents/blue/B6-shared.md` runs the emitted text on
 * the three real interpreters.
 *
 * ## Honest boundary (same as [LcgSpec])
 *
 * These variants defeat *structural* fingerprints (canonicalized-text md5
 * dictionaries, Jaccard skeleton matching — the VM-2 attack), not a determined
 * analyst: every variant still implements the same arithmetic, so a lifter
 * that keys on the *semantics* (one multiply, one add, one mask per step)
 * folds them all. Variant diversity raises the cost of the cross-sample
 * matcher from "one rule" to "one rule per shape class".
 *
 * ## Usage (B2 splice point)
 *
 * ```kotlin
 * val draw = LuaDecodeVariants.draw(random)          // per chunk
 * val sk = LuaDecodeVariants.generate(
 *     spec, draw, fnNames = Triple(seedFn, stateVar, guardName=...), guard = 2654435769L)
 * // sk.seedInitFunction("lcg_seed")  -> "local function lcg_seed(seed) ... end"
 * // sk.stepStatement("state")        -> "state = (state * A + C) % 4294967296"
 * // sk.byteExpression("state")       -> "math.floor(state / 65536) % 256"
 * ```
 *
 * [SeedInit.LEN_SALT] requires globals access (`_VERSION`) in the module
 * scope and re-introduces the bounded-guessable `#_VERSION` factor — use it
 * as *one* input among several, never the sole secret (same boundary as
 * `LuaHostSeed`).
 */
object LuaDecodeVariants {

    /** How the runtime seed is mixed into the initial LCG state. */
    enum class SeedInit(val needsEnv: Boolean = false) {
        /** `s = (seed * M + A) % 2^32; if s == 0 then s = G end` — the classic. */
        MIXED_GUARD,

        /** `s = (seed ~ K) % 2^32` first, then one mul-add round. */
        XOR_PRE,

        /** Two mul-add rounds with independent constant pairs. */
        DOUBLE_MIX,

        /**
         * Reassociated literal layout: subtract-multiply-add. Same algebraic
         * family, different operator sequence and constant placement.
         */
        ADD_SUB_MIRROR,

        /**
         * Adds the runtime factor `#tostring(_VERSION) * K` into the mix.
         * [needsEnv]: requires `_VERSION` to be readable in the module scope.
         * Bounded-guessable factor — never the sole secret.
         */
        LEN_SALT,

        /** xor-fold + mul-add + 32-bit rotate-right-by-1. */
        ROTATE_FOLD,
    }

    /** How one LCG step is spelled. Every form equals [LcgSpec.step]. */
    enum class StepForm {
        /** `s = (s * A + C) % MOD`. */
        DIRECT,

        /** `s = ((s * A) % MOD + C) % MOD` — two residue reductions. */
        SPLIT_MOD,

        /** `s = (s * A - CNEG) % MOD` with `CNEG = MOD - C` (subtraction form). */
        FLOOR_SUB,

        /** Two statements: multiply-reduce, then add-reduce. */
        TWO_STATEMENT,

        /** 16-bit half-split multiply (float-Lua safe; exact on integers too). */
        HALF_SPLIT,
    }

    /** How the keystream byte is extracted. Every form equals `(s >> 16) & 0xFF`. */
    enum class ByteExtract {
        /** `math.floor(s / 65536) % 256`. */
        FLOOR_DIV,

        /** `(s >> 16) & 255`. */
        SHIFT_AND,

        /** `(s % 16777216) // 65536`. */
        MOD_CHAIN,

        /** `s // 65536 - (s // 16777216) * 256` — pure arithmetic form. */
        ARITH_PLANE,
    }

    /** One drawn combination (what B2 stores per chunk to reproduce the text). */
    data class Draw(
        val seedInit: SeedInit,
        val stepForm: StepForm,
        val byteExtract: ByteExtract,
        val m1: Long,
        val a1: Long,
        val m2: Long,
        val a2: Long,
        val k1: Long,
        val guard: Long,
    )

    /** The generated skeleton fragments (templates carry `@SEEDFN@` / `@S@` placeholders). */
    data class Skeletons(
        val draw: Draw,
        /** Seed-init function template; `@SEEDFN@` is the function name placeholder. */
        val seedInitTemplate: String,
        /** One LCG step as a statement template; `@S@` is the state variable placeholder. */
        val stepTemplate: String,
        /** Keystream byte expression template; `@S@` is the state variable placeholder. */
        val byteTemplate: String,
    ) {
        /** Complete `local function NAME(seed) ... return s end` text. */
        fun seedInitFunction(fnName: String): String = seedInitTemplate.replace(PLACEHOLDER_SEED_FN, fnName)

        /** One LCG step as a statement over the given state variable. */
        fun stepStatement(stateVar: String): String = stepTemplate.replace(PLACEHOLDER_STATE, stateVar)

        /** Keystream byte expression over the given state variable. */
        fun byteExpression(stateVar: String): String = byteTemplate.replace(PLACEHOLDER_STATE, stateVar)
    }

    /** Draws a random combination with fresh per-chunk constants. */
    fun draw(random: ObfRandom, guard: Long = 2654435769L): Draw {
        fun odd(bits: Int): Long = (((random.nextLong() ushr 1) % (1L shl (bits - 1))) shl 1) or 1L
        fun any32(): Long = (random.nextLong() ushr 1) % (1L shl 32)
        return Draw(
            seedInit = SeedInit.entries[random.nextInt(SeedInit.entries.size)],
            stepForm = StepForm.entries[random.nextInt(StepForm.entries.size)],
            byteExtract = ByteExtract.entries[random.nextInt(ByteExtract.entries.size)],
            m1 = odd(32),
            a1 = any32(),
            m2 = odd(32),
            a2 = any32(),
            k1 = odd(32),
            guard = guard,
        )
    }

    /**
     * Generates the three skeleton fragments for [draw].
     *
     * Placeholders `@SEEDFN@` / `@S@` in the raw fields are substituted by the
     * named accessors, so callers can match B2's per-chunk name drawer.
     */
    fun generate(spec: LcgSpec, draw: Draw): Skeletons {
        require(draw.m1 % 2 == 1L && draw.m2 % 2 == 1L && draw.k1 % 2 == 1L) {
            "mixer multipliers and xor key must be odd (bijectivity mod 2^32)"
        }
        return Skeletons(
            draw = draw,
            seedInitTemplate = seedInitText(draw),
            stepTemplate = stepText(spec, draw.stepForm),
            byteTemplate = byteText(draw.byteExtract),
        )
    }

    // ------------------------------------------------------------ seed init

    private fun seedInitText(d: Draw): String {
        val body = StringBuilder("local function @SEEDFN@(seed)\n")
        when (d.seedInit) {
            SeedInit.MIXED_GUARD -> {
                body.append("    local s = (seed * ${d.m1} + ${d.a1}) % 4294967296\n")
            }

            SeedInit.XOR_PRE -> {
                body.append("    local s = (seed ~ ${d.k1}) % 4294967296\n")
                body.append("    s = (s * ${d.m1} + ${d.a1}) % 4294967296\n")
            }

            SeedInit.DOUBLE_MIX -> {
                body.append("    local s = (seed * ${d.m1} + ${d.a1}) % 4294967296\n")
                body.append("    s = (s * ${d.m2} + ${d.a2}) % 4294967296\n")
            }

            SeedInit.ADD_SUB_MIRROR -> {
                body.append("    local s = (seed - ${d.a1}) % 4294967296\n")
                body.append("    s = (s * ${d.m1}) % 4294967296\n")
                body.append("    s = (s + ${d.k1}) % 4294967296\n")
            }

            SeedInit.LEN_SALT -> {
                body.append("    local s = (seed * ${d.m1} + ${d.a1} + #tostring(_VERSION) * ${d.k1}) % 4294967296\n")
            }

            SeedInit.ROTATE_FOLD -> {
                body.append("    local s = (seed ~ ${d.k1}) % 4294967296\n")
                body.append("    s = (s * ${d.m1} + ${d.a1}) % 4294967296\n")
                body.append("    s = (s >> 1) | ((s << 31) & 4294967295)\n")
            }
        }
        body.append("    if s == 0 then s = ${d.guard} end\n")
        body.append("    return s\nend")
        return body.toString()
    }

    // ------------------------------------------------------------- step form

    private fun stepText(spec: LcgSpec, form: StepForm): String {
        val a = spec.multiplier
        val c = spec.increment
        return when (form) {
            StepForm.DIRECT -> "@S@ = (@S@ * $a + $c) % 4294967296"

            StepForm.SPLIT_MOD -> "@S@ = ((@S@ * $a) % 4294967296 + $c) % 4294967296"

            StepForm.FLOOR_SUB -> "@S@ = (@S@ * $a - ${LcgSpec.MODULUS - c}) % 4294967296"

            StepForm.TWO_STATEMENT ->
                "@S@ = (@S@ * $a) % 4294967296\n@S@ = (@S@ + $c) % 4294967296"

            StepForm.HALF_SPLIT -> {
                val aLo = a and 0xFFFFL
                val aHi = a ushr 16
                "@S@ = ((@S@ % 65536) * $aLo + ((math.floor(@S@ / 65536) * $aLo + (@S@ % 65536) * $aHi) % 65536) * 65536 + $c) % 4294967296"
            }
        }
    }

    // ----------------------------------------------------------- byte extract

    private fun byteText(form: ByteExtract): String = when (form) {
        ByteExtract.FLOOR_DIV -> "math.floor(@S@ / 65536) % 256"
        ByteExtract.SHIFT_AND -> "(@S@ >> 16) & 255"
        ByteExtract.MOD_CHAIN -> "(@S@ % 16777216) // 65536"
        ByteExtract.ARITH_PLANE -> "@S@ // 65536 - (@S@ // 16777216) * 256"
    }

    // ---------------------------------------------------- Kotlin-side mirrors
    //
    // Each mirror reproduces the emitted Lua text under Lua 5.3+ semantics:
    // Long multiply/add wraps mod 2^64 (Kotlin `*` already does), `%` is
    // floorMod, `>>` is ushr (Lua's right shift is logical), `~`/`&`/`|` are
    // bitwise. These are what a B2 emitter runs to stay in lockstep with the
    // generated Lua.

    /** Kotlin mirror of [seedInitFunction]. */
    fun seedInitMirror(d: Draw, seed: Long): Long {
        fun mod32(v: Long): Long = v.mod(LcgSpec.MODULUS)
        val s: Long = when (d.seedInit) {
            SeedInit.MIXED_GUARD -> mod32(seed * d.m1 + d.a1)
            SeedInit.XOR_PRE -> mod32(mod32(seed xor d.k1) * d.m1 + d.a1)
            SeedInit.DOUBLE_MIX -> mod32(mod32(seed * d.m1 + d.a1) * d.m2 + d.a2)
            SeedInit.ADD_SUB_MIRROR -> mod32(mod32(mod32(seed - d.a1) * d.m1) + d.k1)
            SeedInit.LEN_SALT -> mod32(seed * d.m1 + d.a1 + 7L * d.k1) // mirror assumes length-7 host
            SeedInit.ROTATE_FOLD -> {
                val mixed = mod32(mod32(seed xor d.k1) * d.m1 + d.a1)
                ((mixed ushr 1) or ((mixed shl 31) and 0xFFFFFFFFL)) and 0xFFFFFFFFL
            }
        }
        return if (s == 0L) d.guard else s
    }

    /** Kotlin mirror of [stepStatement]. */
    fun stepMirror(spec: LcgSpec, form: StepForm, s: Long): Long {
        val a = spec.multiplier
        val c = spec.increment
        fun mod32(v: Long): Long = v.mod(LcgSpec.MODULUS)
        return when (form) {
            StepForm.DIRECT -> mod32(s * a + c)
            StepForm.SPLIT_MOD -> mod32(mod32(s * a) + c)
            StepForm.FLOOR_SUB -> mod32(s * a - (LcgSpec.MODULUS - c))
            StepForm.TWO_STATEMENT -> mod32(mod32(s * a) + c)
            StepForm.HALF_SPLIT -> {
                val lo = s % 65536
                val hi = s.floorDiv(65536L)
                val aLo = a % 65536
                val aHi = a / 65536
                mod32(lo * aLo + (hi * aLo + lo * aHi).mod(65536L) * 65536 + c)
            }
        }
    }

    /** Kotlin mirror of [byteExpression]. */
    fun byteMirror(form: ByteExtract, s: Long): Int = when (form) {
        ByteExtract.FLOOR_DIV -> (s.floorDiv(65536L) % 256).toInt()
        ByteExtract.SHIFT_AND -> ((s ushr 16) and 255L).toInt()
        ByteExtract.MOD_CHAIN -> (s.mod(16777216L) / 65536L).toInt()
        ByteExtract.ARITH_PLANE -> (s / 65536 - s / 16777216 * 256).toInt()
    }

    /**
     * md5 of a text with all decimal literals replaced by `#` — the
     * "canonicalized structure" form R2's cross-sample matcher computes.
     * Structural uniqueness of the variants is asserted on this form.
     */
    fun structuralMd5(text: String): String =
        md5Hex(text.replace(Regex("\\d+"), "#"))

    /** md5 of the raw text (per-chunk literal identity). */
    fun rawMd5(text: String): String = md5Hex(text)
}

/** Placeholders used by the generated templates. */
private const val PLACEHOLDER_SEED_FN = "@SEEDFN@"
private const val PLACEHOLDER_STATE = "@S@"
