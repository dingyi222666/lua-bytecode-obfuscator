package io.github.dingyi222666.luabytecode.bytecode.analysis

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype

/**
 * Registers one instruction reads and writes.
 *
 * [constantRefs] carries the operand values that are **not** registers —
 * 5.3 RK-encoded constants (operand ≥ 256, bit 8 set) and immediate keys —
 * so consumers that need "register + constant reference" pairs get both
 * without re-decoding. Defaults to empty: every positional construction
 * keeps working unchanged.
 */
data class UseDef(
    val uses: Set<Int>,
    val defs: Set<Int>,
    val constantRefs: Set<Int> = emptySet(),
)

/**
 * Register data flow of one prototype: basic blocks, the control flow graph,
 * block reachability, backwards liveness and a def-before-use verifier.
 *
 * This is the analysis the obfuscator needs to stay correct and to be *data*
 * driven rather than only control flow driven: it tells which registers are dead
 * at a point (so injected code can use them), which values are live across a
 * rewrite (so encoding them is observable) and which blocks are unreachable (so
 * dead code can live there). The inverse of this analysis — dominators plus
 * def-use chains — is what D810/Deflat use to deobfuscate, so the same facts are
 * used below to *verify* the result instead of assuming it.
 */
class LuaDataFlow(val prototype: LuaPrototype) {

    val code = prototype.code
    val size = code.size

    /** Basic block starts, including jump targets and skip fall-throughs. */
    val blockStarts: List<Int>

    /** Index of the block containing [pc], or -1. */
    val blockOf: IntArray

    val ranges: List<IntRange>
    val successors: List<Set<Int>>
    val predecessors: List<Set<Int>>
    val reachable: BooleanArray

    /** Live registers on entry / exit of each block. */
    val liveIn: List<Set<Int>>
    val liveOut: List<Set<Int>>

    init {
        val starts = sortedSetOf(0)
        for (pc in 0 until size) {
            code[pc].jumpTarget(pc)?.let { if (it in 0 until size) starts += it }
            if (code[pc].skipsNextInstruction && pc + 2 <= size) starts += pc + 2
        }
        blockStarts = starts.toList()
        blockOf = IntArray(size) { -1 }
        val bounds = ArrayList<IntRange>(blockStarts.size)
        for ((index, from) in blockStarts.withIndex()) {
            val to = if (index + 1 < blockStarts.size) blockStarts[index + 1] - 1 else size - 1
            bounds += from..to
            for (pc in from..to) blockOf[pc] = index
        }
        ranges = bounds

        successors = ranges.map { successorsOf(it) }
        predecessors = List(ranges.size) { mutableSetOf<Int>() }
        for (index in ranges.indices) {
            for (next in successors[index]) predecessors[next] += index
        }

        reachable = BooleanArray(ranges.size)
        val worklist = ArrayDeque<Int>()
        if (ranges.isNotEmpty()) {
            reachable[0] = true
            worklist += 0
        }
        while (worklist.isNotEmpty()) {
            val index = worklist.removeFirst()
            for (next in successors[index]) {
                if (!reachable[next]) {
                    reachable[next] = true
                    worklist += next
                }
            }
        }

        // backwards liveness: a block is live-in when a use happens before any def
        val liveInSets = Array(ranges.size) { HashSet<Int>() }
        val liveOutSets = Array(ranges.size) { HashSet<Int>() }
        var changed = true
        var rounds = 0
        while (changed && rounds++ < 128) {
            changed = false
            for (index in ranges.indices.reversed()) {
                val out = HashSet<Int>()
                for (next in successors[index]) out += liveInSets[next]
                val incoming = HashSet(out)
                for (pc in ranges[index].reversed()) {
                    val useDef = useDef(pc)
                    incoming -= useDef.defs
                    incoming += useDef.uses
                }
                if (out != liveOutSets[index]) {
                    liveOutSets[index] = out
                    changed = true
                }
                if (incoming != liveInSets[index]) {
                    liveInSets[index] = incoming
                    changed = true
                }
            }
        }
        liveIn = liveInSets.toList()
        liveOut = liveOutSets.toList()
    }

    private fun successorsOf(range: IntRange): Set<Int> {
        if (range.first > range.last) return emptySet()
        val last = range.last
        val instruction = code[last]
        val fallThrough = blockOf.getOrNull(last + 1)?.takeIf { it >= 0 }
        return when (instruction.opcodeName) {
            "RETURN", "RETURN0", "RETURN1", "TAILCALL" -> emptySet()
            "JMP" -> {
                val target = instruction.jumpTarget(last)?.let { blockOf.getOrNull(it)?.takeIf { b -> b >= 0 } }
                if (last > range.first && code[last - 1].skipsNextInstruction) {
                    setOfNotNull(target, fallThrough)
                } else {
                    setOfNotNull(target)
                }
            }
            "FORLOOP", "TFORLOOP", "FORPREP" -> {
                val target = instruction.jumpTarget(last)?.let { blockOf.getOrNull(it)?.takeIf { b -> b >= 0 } }
                setOfNotNull(target, fallThrough)
            }
            // `TFORPREP` jumps unconditionally to the loop head (5.4/5.5, where
            // `jumpTarget` resolves): a fall-through into the loop body never
            // executes, and the false edge made the def-before-use must-analysis
            // intersect over an entering path where the loop variables are not
            // yet defined (flow.lua `for _, value in ipairs(...)`, R8).
            // 5.3 leaves `jumpTarget` null here and keeps the fall-through model.
            "TFORPREP" -> {
                val target = instruction.jumpTarget(last)?.let { blockOf.getOrNull(it)?.takeIf { b -> b >= 0 } }
                if (target != null) setOfNotNull(target) else setOfNotNull(fallThrough)
            }
            else -> setOfNotNull(fallThrough)
        }
    }

    /** Registers live immediately after [pc] (i.e. still needed later on some path). */
    fun liveAfter(pc: Int): Set<Int> {
        val index = blockOf.getOrNull(pc)?.takeIf { it >= 0 } ?: return emptySet()
        val live = HashSet(liveOut[index])
        var current = pc
        while (true) {
            val next = current + 1
            if (next > ranges[index].last) break
            val useDef = useDef(next)
            live -= useDef.defs
            live += useDef.uses
            current = next
        }
        return live
    }

    /** Registers that hold nothing that any later instruction reads. */
    fun deadAfter(pc: Int): Set<Int> = (0 until prototype.maxStackSize).toSet() - liveAfter(pc)

    // ------------------------------------------------------------------
    // B6/Round1 additions: liveness helpers for register renaming (B4) and
    // other passes that need "what does this instruction touch" as a whole.
    // Pure additions — no existing member changes semantics.
    // ------------------------------------------------------------------

    /**
     * Registers live *immediately before* [pc]: the set of registers whose
     * current value may still be read later, taking this instruction's own
     * uses into account. `liveBefore(pc) == liveAfter(pc - 1)` on fall-through
     * paths; at block starts it equals the block's `liveIn` (or `liveOut` of
     * the actual predecessors when the pc is reached by jumps).
     *
     * A renaming pass must never overwrite a register in `liveBefore(pc)`
     * other than the ones the instruction itself defines — that is the
     * safety condition for "park a value in register R at pc".
     */
    fun liveBefore(pc: Int): Set<Int> {
        val index = blockOf.getOrNull(pc)?.takeIf { it >= 0 } ?: return emptySet()
        // Walk BACKWARD from liveOut over the instructions after [pc], then apply
        // pc's own transfer. (A forward walk from liveIn would wrongly kill values
        // defined between the block start and pc — e.g. `CLOSURE R1` at pc2 killed
        // a later `MOVE 6, 1` operand.) This is the mirror of [liveAfter].
        val live = HashSet(liveOut[index])
        for (current in ranges[index].last downTo pc + 1) {
            val useDef = useDef(current)
            live -= useDef.defs
            live += useDef.uses
        }
        val own = useDef(pc)
        live -= own.defs
        live += own.uses
        return live
    }

    /**
     * Registers whose value this instruction reads (a conservative superset
     * for the frame-top `B == 0` / `C == 0` forms — same model as [useDef]).
     */
    fun readsAt(pc: Int): Set<Int> = useDef(pc).uses

    /**
     * Registers whose value this instruction overwrites. A caller may
     * repurpose any register in `deadAfter(pc) union writesAt(pc)` — writing
     * into a register the instruction already defines cannot break earlier
     * readers.
     */
    fun writesAt(pc: Int): Set<Int> = useDef(pc).defs

    /**
     * The full use/def of every instruction in one call, indexed by pc.
     * Passes that rename registers across a whole prototype (B4) build their
     * interference view from this instead of calling [useDef] per pc.
     */
    fun useDefAll(): List<UseDef> = List(size) { useDef(it) }

    /**
     * The last pc at which [register] is *read* on any reachable path, or
     * `-1` when it is never read. After that pc (and at every point where all
     * paths have passed their last read) the register is a rename candidate:
     * `lastRead(register) <= pc` plus "dominates all paths" reasoning tells a
     * renaming pass when the old value can no longer be observed.
     *
     * Reachability is taken from [reachable]; dead code never extends the
     * range of a register.
     */
    fun lastRead(register: Int): Int {
        var last = -1
        for (pc in 0 until size) {
            if (!reachable[blockOf[pc]]) continue
            if (register in useDef(pc).uses) last = pc
        }
        return last
    }

    /**
     * The last pc at which [register] is *written* on any reachable path, or
     * `-1` when it is never written (matches the `defBeforeUseViolations`
     * convention that never-written registers are not locals of this
     * prototype).
     */
    fun lastWrite(register: Int): Int {
        var last = -1
        for (pc in 0 until size) {
            if (!reachable[blockOf[pc]]) continue
            if (register in useDef(pc).defs) last = pc
        }
        return last
    }

    /**
     * A register is read on some path before any definition. Registers that are
     * never written anywhere are skipped (parameters read from upvalues/globals are
     * not locals), which keeps the check free of false positives.
     *
     * B6/Round4 fix: the "definitely defined" fixpoint is initialised with the
     * **greatest** element (`seeds ∪ written`, narrowing iteration) instead of
     * the least one. The previous bottom initialisation lost facts that travel
     * *around* a loop: a register defined before a loop and only read inside it
     * (e.g. `sumEven`'s `total`) was killed at the loop head by the back-edge
     * intersection, producing false "reads before defined" violations on clean
     * luac output (22 across the three `flow` versions).
     */
    fun defBeforeUseViolations(): List<String> {
        val written = HashSet<Int>()
        for (pc in 0 until size) written += useDef(pc).defs
        if (written.isEmpty()) return emptyList()
        val seeds = (0 until prototype.numParams).toSet()
        val universe = seeds + written

        // forward "definitely defined" data flow: intersect the predecessors, so a
        // register counts as defined only when every path defines it first.
        // Greatest-fixpoint form: start from the universe and narrow.
        val mustIn = Array(ranges.size) { HashSet<Int>() }
        val mustOut = Array(ranges.size) { HashSet(universe) }
        var changed = true
        var rounds = 0
        while (changed && rounds++ < 128) {
            changed = false
            for (index in ranges.indices) {
                if (!reachable[index]) continue
                val incoming = if (index == 0) {
                    HashSet(seeds)
                } else {
                    val live = predecessors[index].filter { reachable[it] }
                    if (live.isEmpty()) HashSet(seeds) else HashSet(mustOut[live.first()]).also { set ->
                        for (pred in live.drop(1)) set.retainAll(mustOut[pred])
                    }
                }
                incoming.retainAll(universe)
                val out = defsThrough(index, incoming)
                if (incoming != mustIn[index]) {
                    mustIn[index] = incoming
                    changed = true
                }
                if (out != mustOut[index]) {
                    mustOut[index] = out
                    changed = true
                }
            }
        }

        val result = ArrayList<String>()
        for (index in ranges.indices) {
            if (!reachable[index]) continue
            val defined = HashSet(mustIn[index])
            for (pc in ranges[index]) {
                for (register in useDef(pc).uses) {
                    if (register !in defined && register in written) {
                        result += "${prototype.source}:${prototype.lineDefined} pc=$pc " +
                            "reads R$register (${code[pc].opcodeName}) before it is defined"
                    }
                }
                defined += useDef(pc).defs
            }
        }
        return result
    }

    private fun defsThrough(index: Int, incoming: Set<Int>): HashSet<Int> {
        val defined = HashSet(incoming)
        for (pc in ranges[index]) defined += useDef(pc).defs
        return defined
    }

    /**
     * { uses, defs, constantRefs } of one instruction; conservative for
     * opcodes not listed.
     *
     * B6/Round4 RK model (regression-pinned: `defBeforeUseViolations()` is
     * empty for every clean luac fixture):
     *
     *  * **5.3 RK operands** (`GETTABLE`/`SETTABLE` keys, `SELF` key, the two
     *    arithmetic operands, `EQ`/`LT`/`LE` comparators) carry a register
     *    index when `< 256` and a **constant-pool reference** when `>= 256`
     *    (bit 8 set). Constant references are *not* registers: modelling them
     *    as registers produced phantom live registers 256+ (B4 conflict-graph
     *    pollution) and spurious def-before-use violations.
     *  * **5.3 `EQ`/`LT`/`LE` operand A is a boolean flag**, not a register
     *    (`if ((RK(B) < RK(C)) ~= A) then pc++`). 5.4 / 5.5 moved the
     *    registers to A/B (`if ((R[A] == R[B]) ~= k)`), which the model keeps.
     *  * **`GETTABUP` B / `SETTABUP` A are upvalue indices** on every version,
     *    never registers — upvalue reads do not constrain the register file.
     *  * **`SETI` C is an immediate integer key** (`R[A][C] := R[B]`), not a
     *    register; the value read is B.
     *
     * Non-negative filter: `-1` means "field unused" in several emitters.
     */
    fun useDef(pc: Int): UseDef {
        val i = code[pc]
        val is53 = prototype.version == LuaBytecodeVersion.LUA_5_3

        fun regs(vararg values: Int): Set<Int> = values.filter { it >= 0 }.toSet()

        /**
         * 5.3 RK operand: register iff `< 256`, constant reference otherwise.
         * 5.4 / 5.5 operands of the same name are plain registers.
         */
        fun rk(vararg values: Int): Pair<Set<Int>, Set<Int>> {
            val uses = HashSet<Int>()
            val constants = HashSet<Int>()
            for (value in values) {
                if (value < 0) continue
                if (is53 && value >= 256) constants += value else uses += value
            }
            return uses to constants
        }

        fun d(vararg values: Int) = values.filter { it >= 0 }.toSet()
        return when (i.opcodeName) {
            "MOVE" -> UseDef(regs(i.b), d(i.a))
            "LOADK", "LOADI", "LOADNIL", "LOADBOOL", "LOADTRUE", "LOADFALSE", "NEWTABLE", "CLOSURE" ->
                UseDef(emptySet(), d(i.a))
            "GETUPVAL" -> UseDef(emptySet(), d(i.a))
            "SETUPVAL" -> UseDef(regs(i.a), emptySet())
            "GETTABLE" -> {
                val (uses, constants) = if (is53) rk(i.b, i.c) else (regs(i.b, i.c) to emptySet())
                UseDef(uses, d(i.a), constants)
            }

            "SETTABLE" -> {
                // A is always the table register; B and C are RK on 5.3
                val (uses, constants) = if (is53) rk(i.b, i.c) else (regs(i.b, i.c) to emptySet())
                UseDef(uses + setOf(i.a), emptySet(), constants)
            }

            "GETTABUP" ->
                // B = upvalue index, C = constant key: neither touches registers
                UseDef(emptySet(), d(i.a), if (is53) setOf(i.c) else emptySet())
            "SETTABUP" -> {
                // A = upvalue index, B = constant key, C = value
                // (RK on 5.3; on 5.4/5.5 a register unless the k flag is set)
                if (is53) {
                    val (uses, constants) = rk(i.c)
                    UseDef(uses, emptySet(), constants)
                } else if (i.k != 0) {
                    UseDef(emptySet(), emptySet(), setOf(i.c))
                } else {
                    UseDef(regs(i.c), emptySet())
                }
            }
            "GETFIELD" -> UseDef(regs(i.b), d(i.a))
            "SETFIELD" ->
                // 5.4/5.5: A table, B = K key, C = value (register iff k == 0;
                // B4's renaming and the VM translator use the same gate)
                if (!is53 && i.k != 0) UseDef(regs(i.a), emptySet(), setOf(i.c)) else UseDef(regs(i.a, i.c), emptySet())
            "GETI" -> UseDef(regs(i.b), d(i.a))
            "SETI" ->
                // 5.4/5.5: A table, B = immediate integer key, C = value (register iff k == 0)
                if (!is53 && i.k != 0) UseDef(regs(i.a), emptySet(), setOf(i.c)) else UseDef(regs(i.a, i.c), emptySet())
            "SELF" -> {
                val (uses, constants) = if (is53) rk(i.b, i.c) else (regs(i.b) to emptySet())
                UseDef(uses, d(i.a, i.a + 1), constants)
            }
            "LEN", "NOT", "UNM", "BNOT" -> UseDef(regs(i.b), d(i.a))
            "CONCAT" -> UseDef((i.b..i.c).toSet(), d(i.a))
            "ADD", "SUB", "MUL", "MOD", "DIV", "IDIV", "POW", "BAND", "BOR", "BXOR", "SHL", "SHR" -> {
                val (uses, constants) = rk(i.b, i.c)
                UseDef(uses, d(i.a), constants)
            }
            "ADDI", "ADDK", "SUBI", "SUBK", "MULI", "MULK", "MODI", "MODK", "POWI", "POWK",
            "DIVI", "DIVK", "IDIVI", "IDIVK", "BANDI", "BANDK", "BORI", "BORK", "BXORI", "BXORK",
            "SHLI", "SHLK", "SHRI", "SHRK" -> UseDef(regs(i.b), d(i.a))
            "MMBIN" -> UseDef(regs(i.a, i.b), emptySet())
            "MMBINI", "MMBINK" -> UseDef(regs(i.a), emptySet())
            "EQ", "LT", "LE" ->
                if (is53) {
                    // A is the boolean skip flag; B and C are RK
                    val (uses, constants) = rk(i.b, i.c)
                    UseDef(uses, emptySet(), constants)
                } else {
                    UseDef(regs(i.a, i.b), emptySet())
                }
            "EQK", "EQI", "LTI", "LEI", "GTI", "GEI" -> UseDef(regs(i.a), emptySet())
            "TEST" -> UseDef(regs(i.a), emptySet())
            "TESTSET" -> UseDef(regs(i.b), d(i.a))
            // `B = 0` / `C = 0` mean "up to the frame top", which used to be modelled as an
            // empty set: that made registers that such an instruction really reads (or
            // writes) look dead, and the flattener then parked its dispatcher state in a live
            // register (`features_55` `sum`: register 0 was `total`). Conservative now.
            "CALL" -> UseDef(
                if (i.b == 0) {
                    (i.a until prototype.maxStackSize).toSet()
                } else {
                    (0 until maxOf(i.b - 1, 0)).map { i.a + it }.toSet()
                },
                if (i.c == 0) {
                    (i.a until prototype.maxStackSize).toSet()
                } else {
                    (0 until maxOf(i.c - 1, 0)).map { i.a + it }.toSet()
                },
            )
            "TAILCALL", "RETURN" -> UseDef(
                if (i.b == 0) {
                    (i.a until prototype.maxStackSize).toSet()
                } else {
                    (0 until maxOf(i.b - 1, 0)).map { i.a + it }.toSet()
                },
                emptySet(),
            )
            // Variable width writers: `VARARG A B` writes `A .. A+B-1` and `SETLIST A B C`
            // writes the table at `A` while reading its sources from `A+1` on.
            "VARARG" -> UseDef(
                emptySet(),
                if (i.b == 0) {
                    (i.a until prototype.maxStackSize).toSet()
                } else {
                    (i.a until i.a + maxOf(i.b - 1, 0)).toSet()
                },
            )
            // `SETLIST A B C` with `B != 0` reads exactly the table `A` plus its
            // `B - 1` elements (`lvm.c: for i = 1 .. b, ra[i]`) — `A .. A+B-1`.
            // The old "A until top" model over-read slots above the flushed
            // range, and the must-verifier then demanded (non-existent)
            // definitions for never-written top registers. `B == 0` stays
            // conservative (genuine frame-top range).
            "SETLIST" -> UseDef(
                if (i.b == 0) {
                    (i.a until prototype.maxStackSize).toSet()
                } else {
                    (i.a until minOf(i.a + i.b, prototype.maxStackSize)).toSet()
                },
                emptySet(),
            )
            "RETURN0" -> UseDef(emptySet(), emptySet())
            "RETURN1" -> UseDef(regs(i.a), emptySet())
            // numeric for: R[A..A+2] = idx/limit/step, R[A+3] = control.
            // 5.4 / 5.5 FORLOOP also re-writes the internal index R[A]
            // (`R[A+3] += R[A+2]` stored back into the internal slot).
            "FORPREP", "FORLOOP" ->
                if (is53) UseDef(regs(i.a, i.a + 1, i.a + 2), d(i.a + 3))
                else UseDef(regs(i.a, i.a + 1, i.a + 2), d(i.a + 3, i.a))
            // generic for. 5.3: R[A]=iter, R[A+1]=state, R[A+2]=control+first var
            // (`TFORCALL A C` → `R[A+2] .. R[A+1+C]`; `TFORLOOP` copies control).
            // 5.4 / 5.5 moved the layout one slot up: `TFORCALL A C` →
            // `R[A+4] .. R[A+3+C]` (the loop variables the body reads) and
            // `TFORLOOP A B` tests/writes R[A+3] / R[A+2]. The old merged model
            // def'd R[A+2] on 5.4, so loop variables (R[A+4..]) were never
            // "defined" and every read of them was a false def-before-use.
            // Uses stay conservative on every version (liveness over-approximates).
            "TFORPREP" -> UseDef(regs(i.a, i.a + 1, i.a + 2), d())
            "TFORCALL" ->
                if (is53) {
                    UseDef(regs(i.a, i.a + 1, i.a + 2), d(i.a + 2, i.a + 3))
                } else {
                    UseDef(
                        regs(i.a, i.a + 1, i.a + 2),
                        (i.a + 4 until minOf(i.a + 4 + i.c, prototype.maxStackSize)).toSet(),
                    )
                }
            "TFORLOOP" ->
                if (is53) UseDef(regs(i.a, i.a + 1, i.a + 2), d(i.a + 2))
                else UseDef(regs(i.a, i.a + 1, i.a + 2, i.a + 3), d(i.a + 2))
            "VARARGPREP" -> UseDef(emptySet(), emptySet())
            else -> UseDef(emptySet(), emptySet())
        }
    }
}
