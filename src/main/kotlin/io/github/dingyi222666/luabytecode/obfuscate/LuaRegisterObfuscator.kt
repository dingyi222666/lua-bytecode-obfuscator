package io.github.dingyi222666.luabytecode.obfuscate
import io.github.dingyi222666.luabytecode.platform.Platform
import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.platform.hexPadded
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaInstructionFactory

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaOpcodes
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.LuaString
import io.github.dingyi222666.luabytecode.bytecode.LuaUpvalue
import io.github.dingyi222666.luabytecode.bytecode.analysis.LuaDataFlow
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTarget
import io.github.dingyi222666.luabytecode.bytecode.analysis.relocated
import io.github.dingyi222666.luabytecode.bytecode.analysis.retargeted
import io.github.dingyi222666.luabytecode.bytecode.analysis.skipsNextInstruction
import io.github.dingyi222666.luabytecode.bytecode.edit.currentAbsoluteLines
import io.github.dingyi222666.luabytecode.bytecode.edit.insertInstructions
import io.github.dingyi222666.luabytecode.bytecode.edit.renameLocalAndUpvalueNames
import io.github.dingyi222666.luabytecode.bytecode.support.LuaRegisterAnalysis

/**
 * Tunables of [LuaRegisterObfuscator] (Pass R: register / local obfuscation).
 *
 * @param localNameRewrite R2: replace every `localVariables` debug name with a
 *   random synthetic name (`l3_9f2a`). Pure debug metadata, safe on every
 *   version and every preset.
 * @param registerRenaming R1: permute the non-parameter registers of every
 *   prototype under [LuaDataFlow] liveness. Does not add or remove
 *   instructions, so it composes with every other pass.
 * @param seed RNG seed; `0` derives one from the chunk, so runs are
 *   reproducible while different chunks never share a name space.
 */
data class RegisterObfuscationConfig(
    val localNameRewrite: Boolean = true,
    val registerRenaming: Boolean = false,
    /**
     * R3: merge live-range-disjoint registers onto one slot. Breaks the
     * one-register-per-variable mapping unluac reconstructs (declaration count
     * and scoping change in the decompiled output). Conservative candidate
     * rules, see [LuaRegisterObfuscator].
     */
    val registerMerging: Boolean = false,
    /**
     * R4: split live ranges by inserting `MOVE newReg, r` after a definition
     * and redirecting the dominated uses. One variable decompiles as two; the
     * register high water mark grows by one per split (bounded).
     */
    val registerSplitting: Boolean = false,
    val seed: Long = 0,
)

/** Statistics of one [LuaRegisterObfuscator.apply] run. */
data class RegisterObfuscationReport(
    /** Prototypes whose local / upvalue debug names were replaced (R2). */
    val renamedLocalsPrototypes: Int,
    /** Number of local variable debug entries that got a synthetic name. */
    val renamedLocals: Int,
    /** Prototypes whose registers were permuted (R1). */
    val renamedRegisterPrototypes: Int,
    /** Number of registers absorbed into another slot (R3); pairs merged. */
    val mergedRegisters: Int,
    /** Number of live-range splits performed (R4); each adds one MOVE. */
    val splitRanges: Int,
    /** Prototypes skipped by R1 (too small, too full, self-modifying upvalue tree). */
    val skippedPrototypes: Int,
) {
    override fun toString(): String = buildString {
        append("registers: ")
        append(renamedLocals).append(" locals renamed in ").append(renamedLocalsPrototypes).append(" prototypes")
        if (renamedRegisterPrototypes > 0) {
            append(", registers permuted in ").append(renamedRegisterPrototypes).append(" prototypes")
        }
        if (mergedRegisters > 0) {
            append(", ").append(mergedRegisters).append(" registers merged")
        }
        if (splitRanges > 0) {
            append(", ").append(splitRanges).append(" live ranges split")
        }
        if (skippedPrototypes > 0) {
            append(", ").append(skippedPrototypes).append(" skipped")
        }
    }
}

/**
 * Pass R: register / local obfuscation.
 *
 * **R2 — local name rewrite.** unluac reconstructs identifier names straight
 * from the `localVariables` debug section, so `local factor = 2n / i` in the
 * decompiled output is a *gift* to the analyst. Every debug name is replaced
 * with a per prototype synthetic one; the pc ranges and the rest of the debug
 * info stay intact (name strings are never read by the VM — `lua_Si` locals are
 * lookup-only metadata).
 *
 * **R1 — register permutation.** A liveness driven graph coloring remaps every
 * register that is not pinned onto a random free register number. unluac names
 * locals after their registers, so the permutation also reshuffles which
 * synthetic name ends up on which declaration. The rewrite is per instruction
 * *group* (`op` + `MMBIN`, `TFORPREP` + `TFORCALL`, compare + skip-`JMP`), so
 * paired opcodes stay consistent, and it never adds or removes an instruction.
 *
 * Hard invariants (docs/obfuscation-algorithms.md §14, DESIGN §2.4):
 *  * `R0..R[numParams-1]` are the call convention — pinned.
 *  * A child's `instack` upvalue *is* a parent register number — remapped in
 *    lock step, `kind` preserved (5.4 / 5.5).
 *  * The frame top is public: `CALL/RETURN/VARARG B=0`, `SETLIST`, `SELF` and
 *    every callee reachable through a `CALL` may touch any register. A
 *    permutation is a bijection on `0 until maxStackSize`, so implicit
 *    `[A, top)` ranges translate consistently; the liveness conflict check
 *    keeps two live values from ever colliding on one register.
 *  * `maxStackSize` is left untouched (no new registers, no 255 budget use),
 *    so later passes' high water mark derivations (`safeSlot`, scratch
 *    registers) keep working unchanged.
 *
 * Order in the pipeline: **after** string encryption (the stub blocks are plain
 * instructions that must be seen — and rewritten — by R1) and **before**
 * junk / cloning / flattening (those derive their own register budgets from the
 * post-R layout).
 */
object LuaRegisterObfuscator {

    /** Length of the random hex suffix of a synthetic local name. */
    private const val NAME_SUFFIX_CHARS = 4

    /**
     * Prototypes above this stack size are skipped by R1: the permutation needs
     * no extra registers, but the later passes (junk scratch, string stub,
     * flattener state slot) each budget a few registers above the high water
     * mark and the 255 hard wall must stay reachable (C6).
     */
    private const val REGISTER_CEILING = 240

    /** Guest candidates examined per host (cost control for R3). */
    private const val MERGE_SCAN_BUDGET = 8

    /** Absorbed registers per prototype at most (cost control for R3). */
    private const val MERGE_BUDGET_PER_PROTOTYPE = 4

    /** R4: prototypes smaller than this are not worth splitting. */
    private const val SPLIT_MIN_INSTRUCTIONS = 8

    /** R4: `maxStackSize + splits` must stay at or below this (C6 headroom). */
    private const val SPLIT_CEILING = 240

    /** R4: live-range splits per prototype at most. */
    private const val SPLIT_BUDGET_PER_PROTOTYPE = 4

    fun apply(
        chunk: LuaChunk,
        config: RegisterObfuscationConfig = RegisterObfuscationConfig(),
    ): RegisterObfuscationReport {
        val random = ObfRandom(config.seed)
        var renamedLocalsPrototypes = 0
        var renamedLocals = 0
        var renamedRegisterPrototypes = 0
        var mergedRegisters = 0
        var splitRanges = 0
        var skipped = 0

        for (prototype in chunk.prototypes) {
            if (config.localNameRewrite) {
                val renamed = rewriteLocalNames(prototype, random)
                if (renamed > 0) {
                    renamedLocalsPrototypes++
                    renamedLocals += renamed
                }
            }
            if (config.registerRenaming) {
                val outcome = permuteRegisters(prototype, random)
                if (outcome) renamedRegisterPrototypes++ else skipped++
            }
            if (config.registerMerging) {
                mergedRegisters += mergeRegisters(prototype, random)
            }
            // Splitting runs last within the pass: it appends registers, and the
            // permutation / merging above must see the original layout.
            if (config.registerSplitting) {
                splitRanges += splitRegisters(prototype, random)
            }
        }
        return RegisterObfuscationReport(
            renamedLocalsPrototypes = renamedLocalsPrototypes,
            renamedLocals = renamedLocals,
            renamedRegisterPrototypes = renamedRegisterPrototypes,
            mergedRegisters = mergedRegisters,
            splitRanges = splitRanges,
            skippedPrototypes = skipped,
        )
    }

    // ------------------------------------------------------------------- R2

    /**
     * Replaces every local / upvalue debug name of [prototype] with a synthetic
     * one. Returns the number of renamed local entries.
     *
     * `(...)` vararg entries and nameless entries have nothing to leak and are
     * renamed anyway to keep the name space uniform.
     */
    private fun rewriteLocalNames(prototype: LuaPrototype, random: ObfRandom): Int {
        if (prototype.localVariables.isEmpty() && prototype.upvalueNames.isEmpty()) {
            return 0
        }
        val prefix = syntheticPrefix(random)
        var renamed = 0
        prototype.renameLocalAndUpvalueNames(
            rename = { _ ->
                renamed++
                "${prefix}_${hex4(random)}"
            },
            renameUpvalue = { _ -> "${prefix}up_${hex4(random)}" },
        )
        return renamed
    }

    /** `l0`, `l7`, ... — a short per prototype prefix. */
    private fun syntheticPrefix(random: ObfRandom): String = "l${random.nextInt(10)}"

    private fun hex4(random: ObfRandom): String {
        val value = random.nextInt(1 shl (4 * NAME_SUFFIX_CHARS))
        return hexPadded(value.toLong(), NAME_SUFFIX_CHARS)
    }

    // ------------------------------------------------------------------- R1

    /**
     * Permutes the registers of [prototype] in place. Returns `false` when the
     * prototype is skipped (no headroom, no unpinned register worth moving, or
     * a structural shape the permutation cannot serve).
     */
    private fun permuteRegisters(prototype: LuaPrototype, random: ObfRandom): Boolean {
        val debug = Platform.debug.enabled("B4_DEBUG")
        val code = prototype.code
        if (code.isEmpty()) {
            return false
        }
        // Headroom guard: the permutation itself needs no extra registers, but the
        // passes that run *after* this one (junk scratch, string stub, flattener
        // state slot) each need a few registers above the high water mark. Leave
        // prototypes that are already close to the 255 hard wall alone (C6).
        if (prototype.maxStackSize > REGISTER_CEILING) {
            return false
        }
        val pinned = 0 until prototype.numParams
        val movable = (0 until prototype.maxStackSize).filter { it !in pinned }.toSet()
        if (movable.isEmpty()) {
            return false
        }

        val flow = LuaDataFlow(prototype)
        val conflicts = conflictGraph(prototype, flow)

        // Random greedy coloring over a shuffled order: every prototype gets its
        // own permutation, so prototypes never share a register<->variable map.
        val target = HashMap<Int, Int>()
        val used = BooleanArray(prototype.maxStackSize)
        for (register in pinned) {
            target[register] = register
            used[register] = true
        }
        var placed = 0
        var fallbackOnly = 0
        for (register in movable.shuffled(random)) {
            // Prefer a random looking, *different* register: identity is the one
            // mapping we are trying to break.
            val forbidden = HashSet(conflicts[register] ?: emptySet())
            forbidden.add(register)
            val candidates = (0 until prototype.maxStackSize).filter { !used[it] && it !in forbidden }
            val choice = when {
                candidates.isNotEmpty() -> candidates[random.nextInt(candidates.size)]
                // No free register that is disjoint from every live neighbour: fall
                // back to a plain free slot so the permutation stays a bijection.
                else -> (0 until prototype.maxStackSize).firstOrNull { !used[it] }
            } ?: break
            if (choice == register) fallbackOnly++
            target[register] = choice
            used[choice] = true
            placed++
        }
        // Nothing moved: the prototype's shape pins everything to identity.
        if (placed == 0 || target.keys.all { target[it] == it }) {
            if (debug) {
                Platform.debug.log(
                    "[b4-perm] proto@${prototype.lineDefined} SKIP identity: placed=$placed fallbackOnly=$fallbackOnly " +
                        "maxRegs=${prototype.maxStackSize} numParams=${prototype.numParams} movable=${movable.size} " +
                        "conflictPairs=${conflicts.values.sumOf { it.size } / 2}",
                )
            }
            return false
        }

        val before = code.toList()
        applyPermutation(prototype, target)
        // Self check: the use/def sets must translate point for point. Any mismatch
        // (a bug in the operand tables below, e.g. a missed paired opcode) rolls the
        // prototype back instead of producing broken bytecode.
        if (!translationEquivalent(before, prototype, flow, target)) {
            restore(before, prototype)
            if (debug) {
                Platform.debug.log(
                    "[b4-perm] proto@${prototype.lineDefined} ROLLBACK translation mismatch: " +
                        "maxRegs=${prototype.maxStackSize} numParams=${prototype.numParams}",
                )
            }
            return false
        }
        fixChildUpvalues(prototype, target)
        return true
    }

    /**
     * Conflict graph: registers that are simultaneously live anywhere may never
     * share (here: may never *swap onto*) each other. Groups are atomic
     * (`op`+`MMBIN`, `TFORPREP`+`TFORCALL`, compare+skip `JMP`).
     */
    private fun conflictGraph(prototype: LuaPrototype, flow: LuaDataFlow): Map<Int, Set<Int>> {
        val conflicts = HashMap<Int, MutableSet<Int>>()
        fun touch(a: Int, b: Int) {
            if (a == b) return
            conflicts.getOrPut(a) { HashSet() }.add(b)
            conflicts.getOrPut(b) { HashSet() }.add(a)
        }
        for (pc in 0 until flow.size) {
            val simultaneous = HashSet(flow.liveAfter(pc))
            simultaneous += flow.useDef(pc).uses
            for (a in simultaneous) {
                for (b in simultaneous) {
                    touch(a, b)
                }
            }
        }
        // Registers live across a block boundary are pairwise conflicting through
        // the block level sets as well (the walk above already covers it via
        // liveAfter, but the block in/out sets catch the loop back edges).
        for (index in flow.ranges.indices) {
            val boundary = flow.liveIn[index] + flow.liveOut[index]
            for (a in boundary) for (b in boundary) touch(a, b)
        }
        return conflicts
    }

    /** Rewrites every register operand of every instruction through [map]. */
    private fun applyPermutation(prototype: LuaPrototype, map: Map<Int, Int>) {
        val version = prototype.version
        prototype.edit { rewriteCode { _, instruction -> permuteInstruction(instruction, version, map) } }
    }

    /**
     * Maps the register operands of one instruction. Only operand fields that
     * the opmodes tables classify as *registers* are touched: constants
     * (`RK` / `K` / immediate `sB` / `sC`), jump offsets (`sBx` / `Bx` / `sJ`)
     * and sizes (`CALL` B/C, `SETLIST` vB/vC, `VARARG` B, `NEWTABLE` vB/vC)
     * pass through untouched.
     */
    private fun permuteInstruction(
        instruction: LuaInstruction,
        version: LuaBytecodeVersion,
        map: Map<Int, Int>,
    ): LuaInstruction {
        var result = instruction
        val a = map[instruction.a]
        if (a != null && a != instruction.a) {
            result = result.withA(a)
        }
        fun reg(field: Int): Int? {
            val mapped = map[field] ?: return null
            return if (mapped != field) mapped else null
        }
        when (instruction.opcodeName) {
            "MOVE", "GETTABLE", "SETTABLE", "ADD", "SUB", "MUL", "MOD", "POW", "DIV", "IDIV",
            "BAND", "BOR", "BXOR", "SHL", "SHR",
            -> {
                // 5.3: B and C may be RK; the tables below only look at the
                // register reading. RK operands keep their constant bit.
                reg(instruction.b)?.let { result = result.withB(it) }
                reg(instruction.c)?.let { result = result.withC(it) }
            }

            "GETTABUP", "GETI", "GETFIELD", "SETTABUP", "SETI", "SETFIELD",
            "ADDI", "ADDK", "SUBI", "SUBK", "MULI", "MULK", "MODI", "MODK", "POWI", "POWK",
            "DIVI", "DIVK", "IDIVI", "IDIVK", "BANDI", "BANDK", "BORI", "BORK", "BXORI", "BXORK",
            "SHLI", "SHLK", "SHRI", "SHRK",
            -> {
                // B and/or C are constants here (see constantOperands): only the
                // register operands are mapped, per version.
                when (version) {
                    LuaBytecodeVersion.LUA_5_3 -> {
                        // GETTABUP / GETFIELD do not exist in 5.3; the *K family
                        // does not either. Nothing beyond A is a plain register.
                    }

                    LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> {
                        when (instruction.opcodeName) {
                            "GETTABUP", "GETI", "GETFIELD" -> reg(instruction.b)
                                ?.let { result = result.withB(it) }

                            "SETTABUP", "SETI", "SETFIELD" -> {
                                // B is a key constant; C is a register unless the k flag is set.
                                if (instruction.k == 0) {
                                    reg(instruction.c)?.let { result = result.withC(it) }
                                }
                            }

                            else -> reg(instruction.b)?.let { result = result.withB(it) }
                        }
                    }
                }
            }

            "UNM", "BNOT", "NOT", "LEN" ->
                reg(instruction.b)?.let { result = result.withB(it) }

            "SELF" -> {
                // R(A+1) := R(B); R(A) := R(B)[RK(C)] — B is the only plain
                // register beyond A, A+1 moves with A (it is never named
                // separately anywhere else, the useDef table guarantees that).
                reg(instruction.b)?.let { result = result.withB(it) }
            }

            "CONCAT" -> {
                // B..C is a register *range*: every element must be mapped. A
                // permutation keeps contiguity only if the whole range maps onto
                // a contiguous range — the liveness conflict graph makes any
                // range member conflict with every other, so a mapped range is
                // always mapped *pointwise consistently*; verify with the
                // translation check below.
                reg(instruction.b)?.let { result = result.withB(it) }
                reg(instruction.c)?.let { result = result.withC(it) }
            }

            "MMBIN" -> {
                reg(instruction.a)?.let { result = result.withA(it) }
                reg(instruction.b)?.let { result = result.withB(it) }
            }

            "MMBINI" -> {
                // A is the register, sB is an immediate: only A is mapped.
            }

            "MMBINK" -> {
                // A is the register, B is the constant: only A is mapped.
            }

            "EQ", "LT", "LE" -> {
                when (version) {
                    LuaBytecodeVersion.LUA_5_3 -> {
                        // RK operands: map the register form only (bit 8 clear).
                        val bOperand = instruction.b
                        val cOperand = instruction.c
                        if (bOperand and RK_BIT_53 == 0) {
                            reg(bOperand)?.let { result = result.withB(it) }
                        }
                        if (cOperand and RK_BIT_53 == 0) {
                            reg(cOperand)?.let { result = result.withC(it) }
                        }
                    }

                    else -> {
                        reg(instruction.b)?.let { result = result.withB(it) }
                        reg(instruction.c)?.let { result = result.withC(it) }
                    }
                }
            }

            "EQK", "EQI", "LTI", "LEI", "GTI", "GEI" -> {
                // A is the register; B / sB / sC are constants or immediates.
            }

            "TESTSET" -> reg(instruction.b)?.let { result = result.withB(it) }

            "TFORCALL" -> {
                // R(A), R(A+1) hold the iterator; R(A+2), R(A+3) the state and
                // control. All four move with A.
            }

            else -> Unit
        }
        return result
    }

    private const val RK_BIT_53 = 1 shl 8

    /**
     * Self check: the use/def set of every instruction must equal the use/def
     * set of the *original* instruction translated through [map]. Catches a
     * missed operand kind (e.g. a paired opcode whose partner was not remapped)
     * before the prototype leaves the pass.
     */
    private fun translationEquivalent(
        before: List<LuaInstruction>,
        prototype: LuaPrototype,
        flowBefore: LuaDataFlow,
        map: Map<Int, Int>,
    ): Boolean {
        if (before.size != prototype.code.size) {
            return false
        }
        val flowAfter = LuaDataFlow(prototype)
        for (pc in before.indices) {
            val useDefBefore = flowBefore.useDef(pc)
            val useDefAfter = flowAfter.useDef(pc)
            val translatedUses = useDefBefore.uses.mapNotNull { map[it] }.toSet()
            val translatedDefs = useDefBefore.defs.mapNotNull { map[it] }.toSet()
            // Dynamic-range instructions (B=0 / C=0) use `[A, maxStackSize)`: the
            // whole tail must translate as a set, which a bijection over the same
            // range guarantees — checked here explicitly.
            if (translatedUses != useDefAfter.uses || translatedDefs != useDefAfter.defs) {
                if (Platform.debug.enabled("B4_DEBUG")) {
                    val beforeInst = before[pc]
                    val afterInst = prototype.code[pc]
                    Platform.debug.log(
                        "[b4-check] pc=$pc ${beforeInst.opcodeName} a=${beforeInst.a} b=${beforeInst.b} c=${beforeInst.c} " +
                            "k=${beforeInst.k} -> a=${afterInst.a} b=${afterInst.b} c=${afterInst.c} " +
                            "| uses ${useDefBefore.uses}->${useDefAfter.uses} (translated=$translatedUses) " +
                            "defs ${useDefBefore.defs}->${useDefAfter.defs} (translated=$translatedDefs)",
                    )
                }
                return false
            }
        }
        // And the shape invariants: same instruction count, jumps still land in
        // range (they were never touched — only register fields were rewritten).
        val violationsAfter = flowAfter.defBeforeUseViolations()
        if (violationsAfter.isEmpty()) {
            return true
        }
        // Pre-existing violations (big_module main reads R0 across blocks) are not
        // this pass's problem; only assert we did not add new ones.
        val violationsBefore = countViolations(before, prototype)
        return violationsAfter.size <= violationsBefore
    }

    private fun countViolations(code: List<LuaInstruction>, prototype: LuaPrototype): Int {
        val snapshot = prototype.code.toList()
        try {
            prototype.edit { replaceCode(code) }
            return LuaDataFlow(prototype).defBeforeUseViolations().size
        } finally {
            prototype.edit { replaceCode(snapshot) }
        }
    }

    private fun restore(before: List<LuaInstruction>, prototype: LuaPrototype) {
        prototype.edit { replaceCode(before) }
    }

    // ------------------------------------------------------------------- R3

    /**
     * R3 register merging: live-range-disjoint registers share one slot, so the
     * "one register == one variable" shape unluac rebuilds collapses (fewer and
     * wider local declarations in the decompiled output).
     *
     * Candidate rules (all conservative, DESIGN §2.3 + C1):
     *  * parameters and already-merged targets are never absorbed;
     *  * a register captured by any child's `instack` upvalue is excluded (the
     *    upvalue slot *is* this frame's register at CLOSURE time);
     *  * a register read or written by a frame-top dynamic instruction
     *    (`CALL/RETURN/VARARG B=0`, `SETLIST` — the implicit `[A, top)` range)
     *    is excluded: its range touches everything above it, liveness alone
     *    cannot see that;
     *  * a register used inside a multi-register group (`CONCAT` range,
     *    `TFORPREP/TFORCALL` control block, `SELF`'s implicit `A+1`) is
     *    excluded from *absorption* — the merge map stays pointwise except for
     *    an absorbed register collapsing onto its host, which would silently
     *    shift such implicit neighbours;
     *  * the pair must never be simultaneously live anywhere (conflict graph),
     *    and one of the two must not appear in the same instruction as the
     *    other (pointwise use/def translation would break otherwise).
     *
     * `maxStackSize` is deliberately not shrunk: it is a conservative high
     * water mark that later passes derive their scratch budgets from, and
     * shrinking it safely would need a full frame-top re-derivation per
     * version. The merge therefore costs nothing and only collapses uses.
     *
     * @return the number of registers absorbed into a host.
     */
    private fun mergeRegisters(prototype: LuaPrototype, random: ObfRandom): Int {
        val code = prototype.code
        val debug = Platform.debug.enabled("B4_DEBUG")
        if (code.size < 4) {
            return 0
        }
        if (prototype.maxStackSize > REGISTER_CEILING) {
            return 0
        }
        val pinned = 0 until prototype.numParams
        val flow = LuaDataFlow(prototype)
        val conflicts = conflictGraph(prototype, flow)

        // Registers with frame-top dynamics or group membership: exclude from
        // both host and guest roles entirely (C1).
        val frameTop = HashSet<Int>()
        val inImplicitGroup = HashSet<Int>()
        for (pc in 0 until flow.size) {
            val instruction = code[pc]
            when (instruction.opcodeName) {
                "CALL", "TAILCALL" -> {
                    if (instruction.b == 0) addRange(frameTop, instruction.a, prototype.maxStackSize)
                    if (instruction.c == 0) addRange(frameTop, instruction.a, prototype.maxStackSize)
                }

                "RETURN" -> if (instruction.b == 0) addRange(frameTop, instruction.a, prototype.maxStackSize)
                "VARARG" -> addRange(frameTop, instruction.a, prototype.maxStackSize)
                "SETLIST" -> addRange(frameTop, instruction.a, prototype.maxStackSize)

                "CONCAT" -> {
                    addRange(inImplicitGroup, instruction.b, instruction.c + 1)
                    inImplicitGroup.add(instruction.a)
                }

                "TFORPREP", "TFORCALL", "TFORLOOP" ->
                    addRange(inImplicitGroup, instruction.a, instruction.a + 4)

                "FORPREP", "FORLOOP" ->
                    addRange(inImplicitGroup, instruction.a, instruction.a + 4)

                "SELF" -> {
                    inImplicitGroup.add(instruction.a)
                    inImplicitGroup.add(instruction.a + 1)
                }

                "SETLIST" -> Unit
                else -> Unit
            }
        }

        // Registers captured by a child upvalue: their *value must survive in
        // this exact slot* until every CLOSURE ran — excluded from absorption
        // (and from hosting an absorbed register, the value would be readable
        // through the child at the wrong time).
        val captured = HashSet<Int>()
        collectCaptured(prototype, captured)

        val hosts = (0 until prototype.maxStackSize).filter {
            it !in pinned && it !in frameTop && it !in inImplicitGroup && it !in captured
        }
        if (debug) {
            Platform.debug.log(
                "[b4-merge] proto@${prototype.lineDefined} hosts=${hosts.size}/${prototype.maxStackSize} " +
                    "(pinned=${pinned.count()}, frameTop=${frameTop.size}, group=${inImplicitGroup.size}, captured=${captured.size})",
            )
        }
        if (hosts.isEmpty()) {
            return 0
        }
        val guests = hosts.filter { it !in pinned }.toSet()

        val mergeInto = HashMap<Int, Int>()
        var merged = 0
        var rejectedConflict = 0
        var rejectedCoOccur = 0
        var noConflictGuests = 0
        // Random host order: each prototype merges differently. A host can host
        // several guests (they are pairwise disjoint by construction check).
        for (host in hosts.shuffled(random)) {
            for (guest in guests.shuffled(random).take(MERGE_SCAN_BUDGET)) {
                if (guest == host || guest in mergeInto) continue
                if (host in captured || host in frameTop || host in inImplicitGroup) continue
                // REG-INERT-1 root cause B fix: a *missing* conflict entry means the
                // guest is never simultaneously live with ANY register — it is the
                // best possible candidate (disjoint from every host), not a skip.
                // The old `?: continue` inverted this and rejected exactly those.
                val guestConflicts: Set<Int> = conflicts[guest] ?: emptySet()
                if (guestConflicts.isEmpty()) noConflictGuests++
                if (host in guestConflicts) {
                    rejectedConflict++
                    continue
                }
                // Same-instruction check: the use/def translation is pointwise, a
                // host+guest co-occurrence would double-map one instruction.
                if (coOccurs(code, host, guest)) {
                    rejectedCoOccur++
                    continue
                }
                mergeInto[guest] = host
                merged++
                break
            }
            if (merged >= MERGE_BUDGET_PER_PROTOTYPE) break
        }
        if (debug) {
            Platform.debug.log(
                "[b4-merge] proto@${prototype.lineDefined} merged=$merged rejected(conflict=$rejectedConflict, coOccurs=$rejectedCoOccur) " +
                    "noConflictGuests=$noConflictGuests maxRegs=${prototype.maxStackSize} numParams=${prototype.numParams}",
            )
        }
        if (mergeInto.isEmpty()) {
            return 0
        }

        val before = code.toList()
        applyPermutation(prototype, mergeInto)
        if (!translationEquivalent(before, prototype, flow, mergeInto)) {
            restore(before, prototype)
            return 0
        }
        fixChildUpvalues(prototype, mergeInto)
        // The debug table of a merged prototype can no longer describe scopes
        // truthfully (two LocVars now share one register) — R2 semantics: drop
        // the local entries whose register (== list index) got absorbed. Entries
        // are ordered by register, so entry i describes register i.
        for (index in prototype.localVariables.indices.reversed()) {
            if (index in mergeInto) {
                prototype.localVariables.removeAt(index)
            }
        }
        return merged
    }

    /** `addRange(set, from, until)` helper for the frame-top ranges. */
    private fun addRange(target: MutableSet<Int>, from: Int, untilExclusive: Int) {
        for (register in from until untilExclusive) {
            target.add(register)
        }
    }

    /** Collects every register of [prototype] captured by any child upvalue. */
    private fun collectCaptured(prototype: LuaPrototype, into: MutableSet<Int>) {
        for (child in prototype.nestedPrototypes) {
            for (upvalue in child.upvalues) {
                if (upvalue.inStack) {
                    into.add(upvalue.index)
                }
            }
        }
    }

    /** `true` when both registers appear in the use/def sets of one instruction. */
    private fun coOccurs(code: List<LuaInstruction>, a: Int, b: Int): Boolean {
        // Cheap pointwise check over the raw operands: any instruction whose
        // register-ish operand fields contain both values. The use/def model in
        // LuaDataFlow is the authority for liveness; this only guards the
        // pointwise translation, so raw fields are the right (wider) net.
        for (instruction in code) {
            if (a == b) return true
            val fields = listOf(instruction.a, instruction.b, instruction.c)
            if (a in fields && b in fields) {
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------- R4

    /**
     * R4 live-range splitting (DESIGN §2.5): insert `MOVE newReg, r` right
     * after a definition of `r` and redirect the uses that are reachable from
     * that definition without an intervening redefinition to `newReg`.
     *
     * The decompiled shape changes two ways: the single variable now decompiles
     * as **two** locals (def-use chain cut in half) and one extra `MOVE`
     * materialises, so "instruction count == variable count" style structural
     * reconstruction loses its anchor. A phantom use chain (a `MOVE` in the
     * split register that no one reads) is deliberately *not* added — dead code
     * is DCE bait (PRED-1), real data flow is not.
     *
     * Safety rules:
     *  * only prototypes with `maxStackSize + 1 <= SPLIT_CEILING` (one new
     *    register per split, budget counted);
     *  * `r` is never a parameter and never captured by a child `instack`
     *    upvalue (a captured register must keep its slot until every CLOSURE);
     *  * `r` is never touched by a frame-top dynamic instruction (the implicit
     *    `[A, top)` range would cover the new register too);
     *  * the new register is allocated **below the dynamic floor** when the
     *    prototype has one (B6 `LuaRegisterAnalysis` semantics: a slot above
     *    the floor can be destroyed by a frame-top call), otherwise anywhere
     *    free at or above `numParams`;
     *  * the redirect is a **dominator-precise** walk over the CFG: from the
     *    definition pc, every forward path is followed, stopping at any
     *    redefinition of `r` — only uses reached that way switch to `newReg`.
     *    Uses on paths that enter without passing the definition keep reading
     *    `r`, which is exactly what makes this a *split* instead of a rename;
     *  * `lineInfo` / `localVariables` stay consistent: insertion goes through
     *    [io.github.dingyi222666.luabytecode.bytecode.edit.insertInstructions].
     *
     * @return the number of splits performed.
     */
    private fun splitRegisters(prototype: LuaPrototype, random: ObfRandom): Int {
        val code = prototype.code
        if (code.size < SPLIT_MIN_INSTRUCTIONS) {
            return 0
        }
        if (prototype.maxStackSize + 1 > SPLIT_CEILING) {
            return 0
        }
        // REG-INERT-1 follow-up (the 5.4/5.5 bank rc=138 kill): this split grows
        // the frame by one at the top. In a variadic prototype the vararg
        // storage abuts the frame top on every version (5.3 keeps the varargs
        // inside the frame, 5.4/5.5 relocate them above it via VARARGPREP), so
        // a slot at the old top can alias `vararg[i]`. Vararg prototypes are
        // refused entirely — the same conclusion B1 reached for its dispatcher
        // slots, now applied to the split register too.
        if (prototype.isVararg) {
            return 0
        }

        val flow = LuaDataFlow(prototype)
        val captured = HashSet<Int>()
        collectCaptured(prototype, captured)
        val frameTop = HashSet<Int>()
        // REG-INERT-1 follow-up (semantic kill caught by the split>0 validation):
        // FORPREP/TFOR*/SELF define implicit block members (`FORPREP def R[A+3]`,
        // `SELF def R[A+1]`) that the single-def candidate filter would accept.
        // Splitting such a member corrupts the loop protocol: the FORLOOP's use
        // of the control variable is *implicit* and never gets redirected (the
        // dominator walk only sees explicit uses), which deadlocked the 5.3
        // numeric-for fixture. Implicit block members are excluded, same rule
        // as R3 merging.
        val inImplicitGroup = HashSet<Int>()
        for (pc in 0 until flow.size) {
            val instruction = code[pc]
            when (instruction.opcodeName) {
                "CALL", "TAILCALL" -> {
                    if (instruction.b == 0) addRange(frameTop, instruction.a, prototype.maxStackSize)
                    if (instruction.c == 0) addRange(frameTop, instruction.a, prototype.maxStackSize)
                }

                "RETURN" -> if (instruction.b == 0) addRange(frameTop, instruction.a, prototype.maxStackSize)
                "VARARG", "SETLIST" -> addRange(frameTop, instruction.a, prototype.maxStackSize)

                "CONCAT" -> {
                    addRange(inImplicitGroup, instruction.b, instruction.c + 1)
                    inImplicitGroup.add(instruction.a)
                }

                "FORPREP", "FORLOOP", "TFORPREP", "TFORCALL", "TFORLOOP" ->
                    addRange(inImplicitGroup, instruction.a, instruction.a + 4)

                "SELF" -> {
                    inImplicitGroup.add(instruction.a)
                    inImplicitGroup.add(instruction.a + 1)
                }

                else -> Unit
            }
        }

        // Candidate definition sites: instructions that *define* exactly one
        // register (unambiguous def), not parameters, not captured, not inside
        // the frame-top blast radius, not an implicit block member.
        val candidates = ArrayList<Pair<Int, Int>>() // pc to defined register
        for (pc in 0 until flow.size) {
            if (!flow.reachable[flow.blockOf[pc]]) continue
            val defs = flow.useDef(pc).defs
            if (defs.size != 1) continue
            val register = defs.single()
            if (register < prototype.numParams || register in captured) continue
            if (register in frameTop || register in inImplicitGroup) continue
            candidates.add(pc to register)
        }
        if (candidates.isEmpty()) {
            return 0
        }

        var performed = 0
        val budget = SPLIT_BUDGET_PER_PROTOTYPE
        val debug = Platform.debug.enabled("B4_DEBUG")
        // REG-INERT-1 follow-up (fourth semantic kill, found by bank54 "silent
        // empty output"): multiple splits inside one call share the `flow` and
        // the candidate list captured before the first insert. After a split
        // the code layout *and* the register roles change (the inserted MOVE
        // itself becomes a use of the source register that a later split's
        // redirect then wrongly rewrites onto its own split register), so
        // batch-splitting from a stale snapshot is unsound. One split per
        // invocation: the pipeline effect is restored by re-running the pass
        // (cheap — it is a single linear analysis), and correctness no longer
        // depends on cross-split bookkeeping.
        if (performed >= budget) return performed
        for ((defPc, register) in candidates.shuffled(random)) {
            if (performed >= budget) break
            // The MOVE goes in at defPc+1, which shifts every later pc — any
            // jump targeting defPc+1 or beyond must be re-encoded (offsets are
            // relative). Refuse the site when defPc+1 is a jump target: the
            // insert would put the MOVE in the middle of an existing edge.
            val insertAt = defPc + 1
            var targetAtInsert = false
            for (pc in 0 until flow.size) {
                val target = flow.code[pc].jumpTarget(pc)
                if (target == insertAt) {
                    targetAtInsert = true
                    break
                }
            }
            if (targetAtInsert) {
                if (debug) Platform.debug.log("[b4-split] proto@${prototype.lineDefined} pc=$defPc r=$register: insert point is a jump target")
                continue
            }
            // REG-INERT-1 root cause C fix: the old global-floor check rejected
            // every prototype that contains *any* dynamic-range site anywhere
            // (newRegister == maxStackSize is always >= a floor below the top).
            // The correct question is local: does a dynamic-range site exist
            // that the *split value's live window* cannot avoid? The split
            // window runs from `insertAt` to the last redirected use. If a
            // dynamic site sits inside that window, a frame-top clobber could
            // hit the new register between the MOVE and its uses — refuse that
            // site. Sites outside the window (before the def, after the last
            // use) never observe the new register. The redirected uses are
            // exactly the ones dominating down from the def, so a site on a
            // *sibling* path is outside every window by construction.
            val redirect = dominatorUsesAfter(flow, defPc, register)
            if (redirect.isEmpty()) {
                if (debug) Platform.debug.log("[b4-split] proto@${prototype.lineDefined} pc=$defPc r=$register: no dominated uses (def feeds a terminator or every path redefines)")
                continue
            }
            // REG-INERT-1 follow-up (conservative window after three semantic
            // kills in this series): only split when the whole redirected
            // window lives inside **one basic block** with no internal branch
            // and no loop involvement — `insertAt` starts the block, the last
            // use ends it, and no jump instruction sits in between. Anything
            // richer (multi-block windows, loops over the window) stays
            // refused until the remaining 5.4 signal-level failure
            // (bank54 rc=138 with a single otherwise-clean split) is root
            // caused with a debugger.
            val window = insertAt until (redirect.maxOrNull()!! + 1)
            val windowClean = window.all { pc ->
                val instruction = prototype.code.getOrNull(pc) ?: return@all false
                instruction.jumpTarget(pc) == null && !instruction.skipsNextInstruction &&
                    instruction.opcodeName !in LuaRegisterAnalysis.DYNAMIC_RANGE_OPCODES
            }
            if (!windowClean) {
                if (debug) Platform.debug.log("[b4-split] proto@${prototype.lineDefined} pc=$defPc r=$register: window [$insertAt,${redirect.maxOrNull()}] not a straight-line block")
                continue
            }
            val newRegister = prototype.maxStackSize + performed

            val lines = prototype.currentAbsoluteLines()
            val line = lines?.getOrNull(defPc) ?: prototype.lineDefined
            // 1. append the split register to the frame (grows by one per split;
            //    the ceiling check at the top bounded the total).
            prototype.maxStackSize += 1
            // 2. insert `MOVE newRegister, register` right after the definition.
            val move = LuaInstructionFactory.abc(
                prototype.version,
                LuaOpcodes.opcodeOf(prototype.version, "MOVE") ?: return 0,
                newRegister,
                register,
            )
            prototype.insertInstructions(insertAt, listOf(move), listOf(line))
            // 3. shift every jump across the insertion point. Third semantic
            //    kill in this series: jumps *after* the insert whose target is
            //    *before* it (loop back edges) need their relative offset
            //    reduced by one — the jump instruction itself moved but its
            //    target did not. Jumps after the insert with targets at/after
            //    it need nothing (both ends moved). Jumps before the insert
            //    with targets at/after it need +1 (target moved, jump did not).
            //    FOR* loop opcodes use unsigned distances with special biases
            //    and their targets can never legally be the inserted MOVE
            //    (targetAtInsert refused that) — they pass through untouched
            //    in both directions.
            for (pc in 0 until insertAt) {
                val instruction = prototype.code[pc]
                val oldTarget = instruction.jumpTarget(pc) ?: continue
                if (oldTarget >= insertAt) {
                    val newTarget = oldTarget + 1
                    val rewritten = when (instruction.opcodeName) {
                        "FORLOOP", "TFORLOOP", "FORPREP", "TFORPREP" -> instruction
                        else -> instruction.retargeted(pc, pc, newTarget)
                    }
                    prototype.patchInstructionAt(pc, rewritten)
                }
            }
            for (pc in insertAt until prototype.code.size) {
                val instruction = prototype.code[pc]
                val oldTarget = instruction.jumpTarget(pc) ?: continue
                if (oldTarget in 0 until insertAt && instruction.opcodeName !in
                    setOf("FORLOOP", "TFORLOOP", "FORPREP", "TFORPREP")
                ) {
                    prototype.patchInstructionAt(pc, instruction.retargeted(pc, pc, oldTarget))
                }
            }
            // 4. redirect the reached uses (pcs shifted by +1 after the insert).
            for (usePc in redirect) {
                val shifted = if (usePc > defPc) usePc + 1 else usePc
                prototype.patchInstructionAt(shifted, retargetRegisterUse(prototype.code[shifted], register, newRegister))
            }
            performed++
            if (debug) {
                Platform.debug.log(
                    "[b4-split] proto@${prototype.lineDefined} SPLIT def pc=$defPc r=$register -> " +
                        "r$newRegister, redirected uses at $redirect",
                )
            }
            // One split per invocation (see the stale-snapshot note above):
            // further splits belong to the next pass run over the fresh layout.
            return performed
        }
        return performed
    }

    /** Current dynamic floor of [prototype] (minimum dynamic `A`), `maxStackSize` when none. */
    private fun dynamicFloorOf(prototype: LuaPrototype): Int {
        var floor = prototype.maxStackSize
        for (instruction in prototype.code) {
            if (instruction.opcodeName !in LuaRegisterAnalysis.DYNAMIC_RANGE_OPCODES) continue
            val dynamic = when (instruction.opcodeName) {
                "VARARG" -> true
                "SETLIST" -> instruction.b == 0
                "RETURN" -> instruction.b == 0
                else -> instruction.b == 0 || instruction.c == 0
            }
            if (dynamic) {
                val site = if (instruction.opcodeName == "SETLIST") instruction.a + 1 else instruction.a
                if (site < floor) floor = site
            }
        }
        return floor
    }

    /**
     * REG-INERT-1 root cause C fix: `true` when a dynamic-range instruction
     * (frame-top blast radius) sits within `[from, until]` — the split value's
     * live window. A clobber there would destroy the new register between the
     * MOVE and its redirected uses. Window-local, not prototype-global: a site
     * before the definition or after the last redirected use never observes
     * the new register.
     */
    private fun hasDynamicRangeWithin(prototype: LuaPrototype, from: Int, until: Int): Boolean {
        for (pc in from..until) {
            val instruction = prototype.code.getOrNull(pc) ?: continue
            if (instruction.opcodeName !in LuaRegisterAnalysis.DYNAMIC_RANGE_OPCODES) continue
            val dynamic = when (instruction.opcodeName) {
                "VARARG" -> true
                "SETLIST" -> instruction.b == 0
                "RETURN" -> instruction.b == 0
                else -> instruction.b == 0 || instruction.c == 0
            }
            if (dynamic) return true
        }
        return false
    }

    /**
     * Uses of [register] reachable from [defPc] (exclusive) along forward CFG
     * edges before any redefinition, **dominated**: only pcs whose *every*
     * incoming edge is reached from [defPc] without a redefinition are
     * redirected. A pc with an edge fed by a path that did not pass the
     * definition (a join of split and non-split paths, e.g. a `return` value
     * meeting a fall-through that skipped the definition) keeps reading
     * [register], which is exactly what makes this a *split* instead of a
     * partial rename.
     *
     * Implementation: local edge counting seeded at `defPc + 1` (edges reached
     * from the definition only — the global `edgeCount` would wrongly count
     * back-edges from outside the walked region and stall the walk), then a
     * worklist that processes a pc only once **all** of its region-visible
     * incoming edges arrived. A pc whose global edge count exceeds its
     * region-visible count has an unsplit feeder: stop there.
     */
    private fun dominatorUsesAfter(flow: LuaDataFlow, defPc: Int, register: Int): List<Int> {
        val uses = ArrayList<Int>()
        val globalEdges = IntArray(flow.size)
        for (pc in 0 until flow.size) {
            for (next in successorsOf(flow, pc)) {
                globalEdges[next]++
            }
        }
        val seenEdges = IntArray(flow.size)
        val processed = BooleanArray(flow.size)
        val queue = ArrayDeque<Int>()
        fun offer(pc: Int) {
            if (pc in 0 until flow.size && !processed[pc]) {
                queue.add(pc)
            }
        }
        // seed: the instruction after the definition
        if (defPc + 1 in 0 until flow.size) {
            seenEdges[defPc + 1] = globalEdges[defPc + 1] // the walk owns this pc
            offer(defPc + 1)
        }
        while (queue.isNotEmpty()) {
            val pc = queue.removeFirst()
            if (pc !in 0 until flow.size || processed[pc]) continue
            if (seenEdges[pc] < globalEdges[pc]) {
                // some incoming edge comes from outside the walked region: a
                // join of split and unsplit paths — the old value is still
                // observable here. Stop without redirecting or expanding.
                processed[pc] = true // never reconsidered
                continue
            }
            processed[pc] = true
            val useDef = flow.useDef(pc)
            if (register in useDef.defs) {
                continue // redefined: the split value is dead from here on
            }
            if (register in useDef.uses) {
                uses.add(pc)
            }
            for (next in successorsOf(flow, pc)) {
                seenEdges[next]++
                if (seenEdges[next] == 1 || seenEdges[next] < globalEdges[next]) {
                    // first arrival, or a pc we already know is a partial join:
                    // (re-)offer; the seen check at the top sorts it out.
                    offer(next)
                } else if (seenEdges[next] == globalEdges[next]) {
                    offer(next)
                }
            }
        }
        return uses
    }

    /** CFG successors of [pc] (fall-through and both branch arms), in range. */
    /**
     * `true` when [target] is reachable from [from] along forward CFG edges
     * (loop detection for R4: a definition inside a cycle over its own uses
     * cannot be split — the back edge re-executes the uses without passing
     * the definition).
     */
    private fun reachesPc(flow: LuaDataFlow, from: Int, target: Int): Boolean {
        if (from == target) return true
        val visited = BooleanArray(flow.size)
        val queue = ArrayDeque<Int>()
        queue.add(from)
        visited[from] = true
        while (queue.isNotEmpty()) {
            val pc = queue.removeFirst()
            for (next in successorsOf(flow, pc)) {
                if (next == target) return true
                if (next in 0 until flow.size && !visited[next]) {
                    visited[next] = true
                    queue.add(next)
                }
            }
        }
        return false
    }

    private fun successorsOf(flow: LuaDataFlow, pc: Int): List<Int> {
        val result = ArrayList<Int>(3)
        val instruction = flow.code[pc]
        val fallThrough = pc + 1
        if (fallThrough in 0 until flow.size) {
            result.add(fallThrough)
        }
        instruction.jumpTarget(pc)?.let { if (it in 0 until flow.size) result.add(it) }
        // a conditional skip also continues after the skipped instruction
        if (instruction.skipsNextInstruction && pc + 2 in 0 until flow.size) {
            result.add(pc + 2)
        }
        return result
    }

    /** Rewrites the [from] register operand of one use instruction to [to]. */
    private fun retargetRegisterUse(instruction: LuaInstruction, from: Int, to: Int): LuaInstruction {
        var result = instruction
        val fields = LinkedHashMap<String, Int>()
        fields["a"] = instruction.a
        fields["b"] = instruction.b
        fields["c"] = instruction.c
        val patch = HashMap<Char, Int>()
        for ((name, value) in fields) {
            if (value == from) {
                patch[name[0]] = to
            }
        }
        patch['a']?.let { result = result.withA(it) }
        patch['b']?.let { result = result.withB(it) }
        patch['c']?.let { result = result.withC(it) }
        return result
    }

    /**
     * A child's `instack` upvalue is a register number of *this* frame at
     * `CLOSURE` time — remap it in lock step, preserving `kind` (5.4 / 5.5).
     * `inStack = false` upvalues index the parent's upvalue list and are not
     * touched.
     */
    private fun fixChildUpvalues(prototype: LuaPrototype, map: Map<Int, Int>) {
        fun fix(children: List<LuaPrototype>) {
            for (child in children) {
                for (index in child.upvalues.indices) {
                    val upvalue = child.upvalues[index]
                    if (upvalue.inStack) {
                        val mapped = map[upvalue.index] ?: continue
                        if (mapped != upvalue.index) {
                            child.upvalues[index] = LuaUpvalue(true, mapped, upvalue.kind)
                        }
                    }
                }
                fix(child.nestedPrototypes)
            }
        }
        fix(prototype.nestedPrototypes)
    }
}
