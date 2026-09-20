package io.github.dingyi222666.luabytecode.bytecode.support

import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.analysis.LuaDataFlow

/**
 * Register-safety analysis shared by every pass that needs to *park a long
 * lived value in a register of an existing prototype* — currently the CFF
 * dispatcher state slot (B1, `LuaControlFlowFlattening`)
 * and any future pass that wants a scratch slot surviving a `CALL` with a
 * dynamic frame top.
 *
 * ## The three hazards a "safe" register must avoid
 *
 * 1. **Dynamic frame range clobber** (`VARARG B=0`, `SETLIST B=0`,
 *    `CALL/TAILCALL B=0 or C=0`, `RETURN B=0`): the instruction touches
 *    `[A, top)` where `top` is the frame top at run time, so any register at
 *    or above the *dynamic floor* (the minimum `A` of such a site, or its
 *    `A+1` for `SETLIST`) can be destroyed. B1's current inline logic stops
 *    looking *below* that floor; this class keeps that rule and exposes it.
 * 2. **Data flow read** (`LuaDataFlow.useDef().uses` anywhere): a register
 *    the program reads may hold a live value at the parking point.
 * 3. **Constant reachability** (R5 reverse evidence, GATE-2 / CFF-1): a
 *    register whose defining value can be recomputed from the constant pool
 *    by a short straight-line chain (`LOADK/LOADI/LOADBOOL` directly, or one
 *    `MOVE` / `ADDI`-style immediate op away) is the *cheapest folding path*
 *    for a deobfuscator — parking a "runtime secret" dispatcher state there
 *    puts the secret one constant-fold away. Such registers are flagged
 *    [RegisterSafety.constantReachable] so callers can exclude them from
 *    secret-bearing slots (they remain fine for ordinary scratch).
 *
 * ## Usage (B1 dynamic-range-no-safe-slot)
 *
 * ```kotlin
 * val safety = LuaRegisterAnalysis.of(prototype)
 * val slot = safety.safeStateSlots(dynamicFloor).firstOrNull()
 *     ?: return skip("dynamic range instruction without a safe state slot")
 * ```
 *
 * `safeStateSlots` returns candidates *ordered by preference* (lowest index
 * first, matching B1's current `firstOrNull` behaviour) with all three
 * hazards applied; [RegisterSafety.constantReachable] is available for
 * finer-grained decisions (e.g. keep a constant-reachable slot for the
 * public `probe` value while moving the secret `state` elsewhere).
 *
 * This is a pure addition file: nothing in `obfuscate/` changes behaviour
 * until B1 opts in.
 */
object LuaRegisterAnalysis {

    /**
     * Opcodes whose `B == 0` (or `SETLIST`'s `B == 0`) means "up to the frame
     * top" — the dynamic-range family of `lvm.c` / `ldo.c`. `SETLIST` reads
     * its sources from `A + 1` to the top, so its floor is `A + 1`.
     */
    val DYNAMIC_RANGE_OPCODES = setOf(
        "CALL", "TAILCALL", "RETURN", "VARARG", "SETLIST",
    )

    /**
     * Opcodes that write a register with a value **directly derivable from
     * the constant pool in one instruction** (no register operand feeding it).
     */
    private val DIRECT_CONSTANT_WRITERS = setOf(
        "LOADK", "LOADKX", "LOADI", "LOADF", "LOADFALSE", "LOADTRUE", "LOADNIL", "LOADBOOL",
    )

    /**
     * Opcodes that write a register from *one register operand plus one
     * immediate/constant operand* — one fold step beyond direct loads.
     */
    private val IMMEDIATE_TRANSFORM_WRITERS = setOf(
        "ADDI", "ADDK", "SUBI", "SUBK", "MULK", "MULI", "MODI", "MODK",
        "BANDI", "BANDK", "BORI", "BORK", "BXORI", "BXORK", "SHLI", "SHRI", "SHLK", "SHRK",
        "GETI", "SELF",
    )

    /** Safety facts of one prototype's registers. */
    class RegisterSafety internal constructor(
        val prototype: LuaPrototype,
        /** Registers any instruction reads anywhere in the prototype. */
        val globallyRead: Set<Int>,
        /** Minimum `A` (or `A+1` for `SETLIST`) of the dynamic-range sites; `maxStackSize` when there are none. */
        val dynamicFloor: Int,
        /**
         * Registers whose *current writer* is a short constant-derived chain
         * (direct load, or immediate transform of another constant-derived
         * register). A deobfuscator can recompute these values from `k[]`
         * without executing anything data dependent — the cheapest fold path.
         */
        val constantReachable: Set<Int>,
        /** pcs of the dynamic-range instructions (for diagnostics / targeted emission). */
        val dynamicPcs: List<Int>,
    ) {
        /**
         * Registers usable as a long-lived state slot: below [dynamicFloor],
         * never read anywhere, not parameter pinned. Ordered ascending — the
         * lowest index is the "most internal" slot and matches B1's current
         * preference.
         */
        fun safeStateSlots(): List<Int> =
            (0 until minOf(dynamicFloor, prototype.maxStackSize))
                .filter { it !in globallyRead && it >= prototype.numParams }
    }

    /** Analyses [prototype]; cheap (single data-flow pass + two linear scans). */
    fun of(prototype: LuaPrototype): RegisterSafety {
        val flow = LuaDataFlow(prototype)

        val dynamicPcs = mutableListOf<Int>()
        var floor = prototype.maxStackSize
        val globallyRead = HashSet<Int>()
        for (pc in 0 until flow.size) {
            val instruction = prototype.code[pc]
            val useDef = flow.useDef(pc)
            globallyRead += useDef.uses
            if (instruction.opcodeName in DYNAMIC_RANGE_OPCODES) {
                val isDynamic = when (instruction.opcodeName) {
                    "VARARG" -> true
                    "SETLIST" -> instruction.b == 0
                    // `C == 0` on RETURN is a don't-care field in 5.3 (no C at
                    // all) — only `B == 0` is a real dynamic range there, and
                    // `CALL/TAILCALL` treat either `B == 0` (args to top) or
                    // `C == 0` (multi-result to top) as dynamic.
                    "RETURN" -> instruction.b == 0
                    else -> instruction.b == 0 || instruction.c == 0
                }
                if (isDynamic) {
                    dynamicPcs += pc
                    val siteFloor = if (instruction.opcodeName == "SETLIST") instruction.a + 1 else instruction.a
                    if (siteFloor < floor) floor = siteFloor
                }
            }
        }

        return RegisterSafety(
            prototype = prototype,
            globallyRead = globallyRead,
            dynamicFloor = floor,
            constantReachable = constantReachableSet(prototype, flow),
            dynamicPcs = dynamicPcs,
        )
    }

    /**
     * Computes the constant-reachable register set: registers written by a
     * direct constant load, or by an immediate transform of another
     * constant-reachable register, fixed-pointed over the prototype (a later
     * dynamic write invalidates — the last writer wins, so the analysis walks
     * *backwards* per register using [LuaDataFlow.lastWrite]).
     *
     * Registers with no writer at all (pure parameters / upvalue-forwarded)
     * are **not** constant reachable: their values come from the caller.
     */
    private fun constantReachableSet(prototype: LuaPrototype, flow: LuaDataFlow): Set<Int> {
        // last defining pc per register (reachable code only)
        val lastDef = IntArray(prototype.maxStackSize) { -1 }
        for (pc in 0 until flow.size) {
            if (!flow.reachable[flow.blockOf[pc]]) continue
            for (register in flow.useDef(pc).defs) {
                if (register in lastDef.indices) lastDef[register] = pc
            }
        }

        // A register is constant reachable when its last writer is:
        //   - a direct constant load, or
        //   - an immediate transform whose source register is itself constant
        //     reachable at that point (approximated globally: source's last
        //     writer is also constant derived and defined no later).
        // Iterate to fixpoint (at most maxStackSize rounds).
        val reachable = HashSet<Int>()
        var changed = true
        while (changed) {
            changed = false
            for (register in 0 until prototype.maxStackSize) {
                if (register in reachable) continue
                val pc = lastDef[register]
                if (pc < 0) continue
                val instruction = prototype.code[pc]
                val name = instruction.opcodeName
                val isDirect = name in DIRECT_CONSTANT_WRITERS
                val isImmediate = name in IMMEDIATE_TRANSFORM_WRITERS
                if (isDirect) {
                    reachable += register
                    changed = true
                } else if (isImmediate) {
                    // single register source feeding an immediate fold
                    val source = instruction.b
                    if (source in reachable) {
                        reachable += register
                        changed = true
                    }
                }
            }
        }
        return reachable
    }
}
