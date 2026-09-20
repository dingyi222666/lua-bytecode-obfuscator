package io.github.dingyi222666.luabytecode.obfuscate
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaInstructionFactory

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaOpcodes
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.LuaString
import io.github.dingyi222666.luabytecode.bytecode.edit.internConstant
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaInstructionStream

/**
 * Shared runtime seed-derivation primitives (B6 infrastructure).
 *
 * Several passes need an integer that a *static bytecode evaluator* cannot
 * fold, to seed VM keystreams (B2), constant encryption (B3, Pass K) or opaque
 * predicates (`LuaControlFlowNoise`). The established mechanism in this
 * codebase is [LuaControlFlowNoise.appendHostVersionSeed]: read `_ENV["_VERSION"]`,
 * call `tostring`, take `#` (the length) — a tiny integer computed at run time.
 *
 * This object factors that mechanism out so `vm/` and `obfuscate/` share one
 * implementation. The existing `LuaControlFlowNoise.appendHostVersionSeed`
 * (owned by `LuaJunkCode`) keeps its exact behaviour (it delegates here), so
 * every current call site is preserved byte-for-byte — the pass keeps its
 * private method signature and only the emission moves.
 *
 * ## Honest strength boundary: `#_VERSION` is bounded-guessable
 *
 * `#tostring(_VERSION)` takes **at most 3 distinct values** across every
 * host this obfuscator supports (`"Lua 5.3"` / `"Lua 5.4"` / `"Lua 5.5"`,
 * all length 7 — see the unit test that pins this against the real
 * interpreters in /tmp). A static attacker who folds `#_VERSION` to its
 * 3-value (today: 1-value) enumeration reduces a runtime-derived seed to a
 * constant; that is exactly [red/R5 GATE-2] and [red/R1 CFF-1]. What runtime
 * derivation *does* buy:
 *
 *  * no literal seed constant survives in the chunk (breaks constant-grep
 *    lifts, e.g. the `~60 line lifter` of ISSUE-VM-1 which keyed on a
 *    plaintext seed word);
 *  * a symbolic executor must model `GETTABUP`+`CALL`+`LEN` instead of
 *    reading one `LOADK`.
 *
 * It does **not** stop a solver that already knows the enumeration. Layer
 * HostVersion seeds behind per-chunk keyed derivation (see [LcgSpec.applySeed])
 * and never claim more than "not a literal" for this primitive.
 *
 * ## Register contract
 *
 * Every emitter here uses **4 consecutive scratch registers** starting at
 * [scratchBase]: `scratchBase` (staging + result), `scratchBase + 1` (callable),
 * `scratchBase + 2` (argument / result copy), `scratchBase + 3` (key staging).
 * The convention matches `LuaControlFlowNoise` / `appendEnvLookup`, which the
 * fixtures have proven across 5.3 / 5.4 / 5.5.
 */
object LuaHostSeed {

    /** Environment variable holding the globals table (see `resolveEnvUpvalues`). */
    const val VERSION_KEY = "_VERSION"

    /** Name of the global `tostring`. */
    const val TOSTRING_KEY = "tostring"

    /** Fallback state applied when a derived seed mixes to 0 (see [LcgSpec.applySeed]). */
    const val GOLDEN_STATE = 0x9E3779B9L

    /**
     * The bounded value domain of `#tostring(_VERSION)` over all supported
     * hosts. Lengths today: `Lua 5.3` = 7, `Lua 5.4` = 7, `Lua 5.5` = 7.
     * Kept as a documented constant so tools (`tools/audit-predicates.py`,
     * GATE-2 accounting) and unit tests share one number with the runtime.
     */
    val KNOWN_LENGTHS: IntArray = intArrayOf(7)

    /**
     * Emits `#tostring(_VERSION)` into register [destination].
     *
     * This is the shared core of `LuaControlFlowNoise.appendHostVersionSeed`:
     * the instructions produced here are identical to what the pass emitted
     * before the refactor, so guards generated on the fixtures keep their
     * proven shape.
     *
     * @param destination   register receiving the integer (`scratchBase` in the
     *   pass convention).
     * @param scratchBase   first of 4 consecutive scratch registers
     *   (`destination` should equal `scratchBase`).
     * @param envUpvalue    a **proven** `_ENV` upvalue index (see
     *   `resolveEnvUpvalues`); emitting with an unproven index reads an
     *   arbitrary upvalue and is a miscompile — callers must fall back to a
     *   constant seed instead.
     * @param setup         instruction sink.
     */
    fun appendVersionLength(
        prototype: LuaPrototype,
        destination: Int,
        scratchBase: Int,
        envUpvalue: Int,
        setup: MutableList<LuaInstruction>,
    ) {
        val version = prototype.version
        val versionName = prototype.internConstant(LuaConstant.Str(LuaString.of(VERSION_KEY)))
        val toStringName = prototype.internConstant(LuaConstant.Str(LuaString.of(TOSTRING_KEY)))
        LuaJunkCode.appendEnvLookup(setup, version, destination, scratchBase, envUpvalue, versionName)
        LuaJunkCode.appendEnvLookup(setup, version, scratchBase + 1, scratchBase, envUpvalue, toStringName)
        setup += LuaInstructionFactory.abc(version, "MOVE", scratchBase + 2, destination, 0)
        setup += LuaInstructionFactory.abc(version, "CALL", scratchBase + 1, 2, 2)
        setup += LuaInstructionFactory.abc(version, "LEN", destination, scratchBase + 1, 0)
    }


    /** Stream based overload used by composable editing passes. */
    fun appendVersionLength(
        prototype: LuaPrototype, destination: Int, scratchBase: Int, envUpvalue: Int, stream: LuaInstructionStream,
    ) {
        val emitted = ArrayList<LuaInstruction>()
        appendVersionLength(prototype, destination, scratchBase, envUpvalue, emitted)
        emitted.forEach(stream::emit)
    }

    /**
     * Emits `math.random(1, 2^30)` into register [destination] — a fresh
     * runtime integer on every execution. Stronger than [appendVersionLength]
     * (unbounded domain) but it has a **side effect**: perturbs the host RNG
     * sequence, so programs that call `math.random` themselves observe
     * different numbers. Use only where the host program tolerates that
     * (the junk pass documents the same caveat).
     *
     * Register contract identical to [appendVersionLength].
     */
    fun appendHostRandom(
        prototype: LuaPrototype,
        destination: Int,
        scratchBase: Int,
        envUpvalue: Int,
        setup: MutableList<LuaInstruction>,
    ) {
        val version = prototype.version
        val mathName = prototype.internConstant(LuaConstant.Str(LuaString.of("math")))
        val randomName = prototype.internConstant(LuaConstant.Str(LuaString.of("random")))
        val low = prototype.internConstant(LuaConstant.Integer(1))
        val high = prototype.internConstant(LuaConstant.Integer(1 shl 30))
        LuaJunkCode.appendEnvLookup(setup, version, destination, scratchBase, envUpvalue, mathName)
        setup += LuaInstructionFactory.abx(version, "LOADK", scratchBase + 3, randomName)
        setup += LuaInstructionFactory.abc(version, "GETTABLE", destination, destination, scratchBase + 3)
        setup += LuaInstructionFactory.abx(version, "LOADK", scratchBase + 1, low)
        setup += LuaInstructionFactory.abx(version, "LOADK", scratchBase + 2, high)
        setup += LuaInstructionFactory.abc(version, "CALL", destination, 3, 2)
    }


    /** Stream based overload used by composable editing passes. */
    fun appendHostRandom(
        prototype: LuaPrototype, destination: Int, scratchBase: Int, envUpvalue: Int, stream: LuaInstructionStream,
    ) {
        val emitted = ArrayList<LuaInstruction>()
        appendHostRandom(prototype, destination, scratchBase, envUpvalue, emitted)
        emitted.forEach(stream::emit)
    }

    /**
     * Size of the instruction sequence [appendVersionLength] emits (plus the
     * interned `_VERSION` / `tostring` keys which are shared, so callers can
     * size pre-allocated blocks before planning layout).
     */
    const val VERSION_LENGTH_SIZE = 5

    /** Size of the sequence [appendHostRandom] emits. */
    const val HOST_RANDOM_SIZE = 5

    /**
     * Kotlin-side mirror of the derivation for tests / tooling: what a host
     * with [versionName] would compute. The length of the canonical version
     * string — never a secret, documented as bounded-guessable.
     */
    fun versionLength(versionName: String): Int = versionName.length

    private fun opcode(version: LuaBytecodeVersion, name: String): Int =
        LuaOpcodes.opcodeOf(version, name)
            ?: throw io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException(
                "no $name opcode in ${version.displayName}",
            )
}
