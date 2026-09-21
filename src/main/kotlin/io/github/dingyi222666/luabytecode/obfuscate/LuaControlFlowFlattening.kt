package io.github.dingyi222666.luabytecode.obfuscate
import io.github.dingyi222666.luabytecode.platform.Platform
import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaInstructionFactory

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTarget
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTo
import io.github.dingyi222666.luabytecode.bytecode.analysis.retargeted
import io.github.dingyi222666.luabytecode.bytecode.analysis.setAbsoluteLines
import io.github.dingyi222666.luabytecode.bytecode.analysis.skipsNextInstruction
import io.github.dingyi222666.luabytecode.bytecode.edit.internConstant

/** Single CFF emitter, with transactional candidates and explicit feature gates. */
data class ControlFlowFlatteningConfig(
    /** Master switch. */
    val enabled: Boolean = true,

    /** `0` derives a deterministic seed from the chunk content. */
    val seed: Long = 0,

    /** Project original initialized values with TEST; never read _ENV. */
    val stateRuntime: Boolean = false,
    val participatingJunk: Boolean = false,
    val segmentedDispatch: Boolean = false,
    val indegreeGuards: Boolean = false,
    val shuffleBlocks: Boolean = false,
    val splitStraightLine: Boolean = false,
    val stateNamespaces: Boolean = false,
    val multipleDispatch: Boolean = false,
    val nestedDispatch: Boolean = false,
    val dispatchEffects: Boolean = false,
    val stateTable: Boolean = false,
    val predicateFamilies: Set<CffPredicateFamily> = emptySet(),
    /** Explicit execution contract required by §5; false preserves allocation observations. */
    val allocationEffectsUnobservable: Boolean = false,
    val segmentSize: Int = 8,
    val maxExpansionFactor: Int = 200,
    val maxValidationSteps: Int = 2_000_000,
) {
    init {
        require(segmentSize > 0)
        require(maxExpansionFactor > 0 && maxValidationSteps > 0)
    }

    companion object {
        val BASELINE = ControlFlowFlatteningConfig()
        /** Requests all scalar features; actual coverage is reported per prototype. */
        val FULL = ControlFlowFlatteningConfig(
            stateRuntime = true, stateNamespaces = true, participatingJunk = true,
            segmentedDispatch = true, multipleDispatch = true, nestedDispatch = true,
            dispatchEffects = true, indegreeGuards = true, shuffleBlocks = true,
            splitStraightLine = true,
            predicateFamilies = setOf(CffPredicateFamily.NON_SQUARE),
        )
    }
}
/** Statistics of one [LuaControlFlowFlattening.apply] run. */
data class ControlFlowFlatteningReport(
    val flattenedPrototypes: Int,
    val skippedPrototypes: Int,
    val blocks: Int,
    val guards: Int,
    val injectedInstructions: Int,
    /** §8 root cause codes -> how many prototypes were refused for each. */
    val refusals: Map<String, Int> = emptyMap(),
    /** The features that were actually enabled for this run (§9.6.5). */
    val appliedFeatures: List<String> = emptyList(),
    val prototypes: List<CffPrototypeReport> = emptyList(),
) {
    override fun toString(): String = buildString {
        append("$flattenedPrototypes prototypes, $blocks blocks")
        if (guards > 0) append(", $guards converging guards")
    }
}

data class CffPrototypeReport(
    val prototypeIndex: Int,
    val status: String,
    val requestedFeatures: List<String>,
    val actualFeatures: List<String>,
    val reasons: List<String>,
    val verifiedControlPaths: Int = 0,
    val verificationSteps: Int = 0,
    val proof: CffProofManifest? = null,
)

/** Build-side certificate; never serialized into Lua constants or debug metadata. */
data class CffProofManifest(
    val controlEntries: List<Pair<Int, Int>>,
    val destinationPcs: List<Int>,
    val firstPrivateRegister: Int,
    val validationBudget: Int,
    /** Only entry parameters have a two-input variability witness; edge truth may be fixed by a branch. */
    val variableSeedSites: Int = 0,
)

private fun ControlFlowFlatteningConfig.requestedFeatures(): List<String> = buildList {
    add("baseline")
    if (stateRuntime) add("state.runtime")
    if (stateNamespaces) add("state.namespaces")
    if (segmentedDispatch) add("dispatch.segmented")
    if (multipleDispatch) add("dispatch.multiple")
    if (nestedDispatch) add("dispatch.nested")
    if (dispatchEffects) add("dispatch.effects")
    if (indegreeGuards) add("indegree")
    if (shuffleBlocks) add("layout.shuffle")
    if (splitStraightLine) add("split.straightline")
    if (stateTable) add("state.table")
    if (participatingJunk) add("junk.participating")
    predicateFamilies.forEach { add("predicate.${it.name.lowercase()}") }
}

enum class CffPredicateFamily { NON_SQUARE, PRIVATE_STRUCTURE, GRAPH }

object LuaControlFlowFlattening {

    /** §8 root cause codes this pass can produce. */
    private const val R03_CFG = "R03 control flow not closed"
    private const val R04_GROUPS = "R04 instruction group conflict"
    private const val R07_STORAGE = "R07 no provably safe storage"
    private const val R17_CAPACITY = "R17 encoding capacity exceeded"

    @JvmStatic
    @JvmOverloads
    fun apply(
        chunk: LuaChunk,
        config: ControlFlowFlatteningConfig = ControlFlowFlatteningConfig(),
        stubPrototypes: Set<LuaPrototype> = emptySet(),
        protectedPrototypes: Set<LuaPrototype> = emptySet(),
    ): ControlFlowFlatteningReport {
        if (!config.enabled) {
            return ControlFlowFlatteningReport(0, 0, 0, 0, 0)
        }
        val debug = Platform.debug.enabled("CFF_DEBUG")
        var flattened = 0
        var blocks = 0
        var guards = 0
        var injected = 0
        val refusals = LinkedHashMap<String, Int>()
        val features = linkedSetOf<String>()
        val reports = ArrayList<CffPrototypeReport>()
        for ((index, prototype) in chunk.prototypes.withIndex()) {
            val reasons = ArrayList<String>()
            if (chunk.header.integerSize != 8 || chunk.header.numberSize != 8 || chunk.header.instructionSize != 4) {
                val reason = "R02 unsupported numeric or instruction configuration"
                refusals[reason] = (refusals[reason] ?: 0) + 1
                reports += CffPrototypeReport(index, "UNCHANGED_CFF", config.requestedFeatures(), emptyList(), listOf(reason))
                continue
            }
            try {
                CffTranslationValidator.checkStructure(prototype)
            } catch (e: IllegalArgumentException) {
                val reason = "R01 invalid input: ${e.message}"
                refusals[reason] = (refusals[reason] ?: 0) + 1
                reports += CffPrototypeReport(index, "INPUT_REJECTED", config.requestedFeatures(), emptyList(), listOf(reason))
                continue
            }
            // Prototypes are mutable data classes. Hash membership becomes stale
            // when an upstream pass edits their code; capsule ownership is identity.
            if (protectedPrototypes.any { it === prototype } || stubPrototypes.any { it === prototype }) {
                val reason = "R16 preserved entry or upstream capsule"
                refusals[reason] = (refusals[reason] ?: 0) + 1
                reports += CffPrototypeReport(index, "UNCHANGED_CFF", config.requestedFeatures(), emptyList(), listOf(reason))
                continue
            }
            var effective = config
            if (effective.multipleDispatch && !effective.stateRuntime) {
                reasons += "R09 dispatch.multiple requires state.runtime"
                effective = effective.copy(multipleDispatch = false)
            }
            if (effective.nestedDispatch && !effective.stateNamespaces) {
                reasons += "R09 dispatch.nested requires state.namespaces"
                effective = effective.copy(nestedDispatch = false)
            }
            if (effective.stateTable && !effective.allocationEffectsUnobservable) {
                reasons += "R11 state.table requires an allocation observation contract"
                effective = effective.copy(stateTable = false)
            }
            if (effective.stateTable && prototype.code.withIndex().any { (pc, i) ->
                    i.jumpTarget(pc)?.let { it <= pc } == true }) {
                reasons += "R19 state.table allocation excluded from cyclic prototypes"
                effective = effective.copy(stateTable = false)
            }
            val privateFamilies = setOf(CffPredicateFamily.PRIVATE_STRUCTURE, CffPredicateFamily.GRAPH)
            if (effective.predicateFamilies.any { it in privateFamilies } &&
                (!effective.allocationEffectsUnobservable || prototype.code.withIndex().any { (pc, i) ->
                    i.jumpTarget(pc)?.let { it <= pc } == true })) {
                reasons += "R11/R19 private predicates require permitted allocations on acyclic paths"
                effective = effective.copy(predicateFamilies = effective.predicateFamilies - privateFamilies)
            }
            if (effective.stateRuntime && prototype.numParams == 0) reasons += "R08 no initialized parameter seed"
            if (effective.predicateFamilies.isNotEmpty() && (!effective.stateRuntime || prototype.numParams == 0)) {
                reasons += "R20 predicates require runtime input; constant fallback removed"
                effective = effective.copy(predicateFamilies = emptySet())
            }
            val attempts = listOf(effective, ControlFlowFlatteningConfig.BASELINE.copy(
                seed = config.seed, maxExpansionFactor = config.maxExpansionFactor,
                maxValidationSteps = config.maxValidationSteps,
            )).distinct()
            var accepted: Flattened? = null
            for (attempt in attempts) {
                val candidate = prototype.copy(
                    code = prototype.code.toMutableList(), constants = prototype.constants.toMutableList(),
                    lineInfo = prototype.lineInfo.toMutableList(), absoluteLineInfo = prototype.absoluteLineInfo.toMutableList(),
                    localVariables = prototype.localVariables.toMutableList(),
                    upvalues = prototype.upvalues.toMutableList(), upvalueNames = prototype.upvalueNames.toMutableList(),
                    nestedPrototypes = prototype.nestedPrototypes.toMutableList(),
                )
                val failed = LinkedHashMap<String, Int>()
                val result = try {
                    flatten(candidate, chunk, attempt, debug, failed)
                } catch (exception: IllegalArgumentException) {
                    failed["R18 validation failed: ${exception.message}"] = 1
                    null
                } catch (exception: IllegalStateException) {
                    failed["R18 validation failed: ${exception.message}"] = 1
                    null
                }
                if (result == null) { reasons += failed.keys; continue }
                prototype.code.apply { clear(); addAll(candidate.code) }
                prototype.replaceConstantPool(candidate.constants)
                prototype.lineInfo.apply { clear(); addAll(candidate.lineInfo) }
                prototype.absoluteLineInfo.apply { clear(); addAll(candidate.absoluteLineInfo) }
                prototype.localVariables.apply { clear(); addAll(candidate.localVariables) }
                prototype.maxStackSize = candidate.maxStackSize
                accepted = result
                break
            }
            if (accepted != null) {
                flattened++
                blocks += accepted.blocks
                guards += accepted.guards
                injected += accepted.injected
                features += accepted.features
                reports += CffPrototypeReport(index, "APPLIED", config.requestedFeatures(),
                    accepted.features.toList(), reasons, accepted.paths, accepted.steps, accepted.manifest)
            } else {
                val reason = reasons.lastOrNull() ?: "R18 unknown candidate failure"
                refusals[reason] = (refusals[reason] ?: 0) + 1
                reports += CffPrototypeReport(index, "UNCHANGED_CFF", config.requestedFeatures(), emptyList(), reasons)
            }
        }
        return ControlFlowFlatteningReport(flattened, chunk.prototypes.size - flattened,
            blocks, guards, injected, refusals, features.toList(), reports)
    }

    private class Flattened(val blocks: Int, val guards: Int, val injected: Int, val features: Set<String>,
        val paths: Int, val steps: Int, val manifest: CffProofManifest)

    /** §8 refusal taxonomy: every skip lands in exactly one stable root cause. */
    private fun refusal(
        code: String,
        reason: String,
        prototype: LuaPrototype,
        maxStackSize: Int,
        refusals: MutableMap<String, Int>,
        debug: Boolean,
    ): Nothing? {
        prototype.maxStackSize = maxStackSize
        refusals[code] = (refusals[code] ?: 0) + 1
        if (debug) {
            Platform.debug.log("[cff] skip ${prototype.source}:${prototype.lineDefined} $code: $reason")
        }
        return null
    }

    private fun flatten(
        prototype: LuaPrototype,
        chunk: LuaChunk,
        config: ControlFlowFlatteningConfig,
        debug: Boolean,
        refusals: MutableMap<String, Int>,
    ): Flattened? {
        val code = prototype.code
        val size = code.size
        val state = prototype.maxStackSize
        fun refuse(codeName: String, reason: String): Nothing? =
            refusal(codeName, reason, prototype, state, refusals, debug)

        if (size < 4) return refuse(R17_CAPACITY, "prototype too small")
        if (code.last().opcodeName !in RETURNS && code.last().opcodeName != "JMP") {
            // the emitter relies on the last block ending in a control transfer
            if (code.none { it.opcodeName == "JMP" }) return refuse(R03_CFG, "no terminal control transfer")
        }

        val originalUnitCount = unitsOf(prototype)?.size
        val units = unitsOf(prototype, config.splitStraightLine) ?: return refuse(R04_GROUPS, "unsafe unit boundaries")
        // Single-unit prototypes are flattened too (one-block machines still
        // route the body through the dispatcher); a prototype whose single unit
        // has no terminating control transfer is refused above instead.
        if (units.isEmpty()) return refuse(R03_CFG, "no units")
        var guards = 0

        val version = prototype.version
        val random = ObfRandom(seedFor(config.seed, chunk, prototype))
        val blockOf = IntArray(size) { -1 }
        for ((index, unit) in units.withIndex()) for (pc in unit) blockOf[pc] = index
        val labels = IntArray(units.size)
        val supply = LabelSupply()
        val builder = CodeBuilder(version)
        // keep the source line of every copied instruction (debuggability; the
        // flattened layout must not destroy the line table)
        val sourceLines = if (prototype.lineInfo.isEmpty()) null else prototype.resolvedLineInfo()
        val emittedLines = ArrayList<Int>()

        // Private registers are live only inside management capsules. Their values
        // are never expected to survive an original CALL or open-top instruction.
        val wideScratch = config.stateTable || config.predicateFamilies.isNotEmpty()
        val frameTop = state + if (config.indegreeGuards) 12 else if (wideScratch) 11 else 7
        if (frameTop > 255) {
            return refuse(R07_STORAGE, "no room for guard scratch registers")
        }
        val scratch = state + 2
        prototype.maxStackSize = frameTop

        // §2.1.5 honest degradation: without a provable run time source the state
        // stays a plain constant encoding and the prototype is reported as such.
        val useMask = config.stateRuntime && prototype.numParams > 0

        // Instructions whose register range depends on the frame top (`VARARG B=0`,
        // `SETLIST B=0`, `CALL/TAILCALL B=0 or C=0`, `RETURN B=0`) can clobber
        // registers above the high water mark. Only `B == 0` is a genuine dynamic
        // range (5.3 `RETURN A B C` has no `C`, so testing `C == 0` would match
        // every 5.3 return).
        // Control packages never survive original instructions. An open top
        // producer/consumer pair must remain in one unit, without injected code.
        for (pc in code.indices) {
            val i = code[pc]
            val open = (i.opcodeName == "CALL" && i.c == 0) ||
                (i.opcodeName == "VARARG" && (if (version == LuaBytecodeVersion.LUA_5_3) i.b else i.c) == 0)
            if (open) {
                val next = code.getOrNull(pc + 1)
                val consumes = next != null && when (next.opcodeName) {
                    "CALL", "TAILCALL", "RETURN" -> next.b == 0
                    "SETLIST" -> (if (version == LuaBytecodeVersion.LUA_5_5) next.vB else next.b) == 0
                    else -> false
                }
                if (!consumes || blockOf[pc] != blockOf[pc + 1]) {
                    return refuse("R06 open top interval", "cannot inject into an open top interval")
                }
            }
        }
        val stateReg = state

        val ids = units.indices.shuffled(random).toIntArray()
        fun constant(value: Int) = prototype.internConstant(LuaConstant.Integer(value.toLong()))
        fun label(index: Int): Int {
            if (labels[index] == 0) labels[index] = supply.next()
            return labels[index]
        }

        fun line(sourcePc: Int?) {
            emittedLines += sourcePc?.let { sourceLines?.getOrNull(it) } ?: prototype.lineDefined
        }

        fun emit(instruction: LuaInstruction, sourcePc: Int? = null) {
            builder.emit(instruction)
            line(sourcePc)
        }

        // Packet gateways already carry the state dependency. The historical
        // constant-seeded converging guards added foldable, duplicate branches.
        fun emitConvergingGuard(continuation: () -> Unit) = continuation()

        val packets = CffControlPackets(prototype, config, state, units.size, builder, supply,
            { emit(it) }, { line(null) }, random)
        fun emitState(id: Int, sourcePc: Int? = null) {
            packets.state(ids.indexOf(id), sourcePc?.let { blockOf[it] } ?: units.size)
        }
        // P2 participating junk (§6.1.8): a site-unique computation folded into
        // the dispatcher state twice (net zero), so it cannot be deleted as dead
        // code without deleting the state update it feeds. Four shapes, chosen
        // per site, all ending in a non-zero junk value.
        fun emitParticipatingJunk(sourcePc: Int?) {
            val junk = state + 3
            val temp = state + 4
            val a = random.nextInt(1, 1 shl 20)
            val b = random.nextInt(1, 1 shl 20)
            emit(LuaInstructionFactory.abx(version, opcodeOf(version, "LOADK"), junk, constant(a)), sourcePc)
            when (random.nextInt(4)) {
                0 -> {
                    emit(LuaInstructionFactory.abx(version, opcodeOf(version, "LOADK"), temp, constant(b)), sourcePc)
                    LuaArithmetic.pair(prototype, "MUL", junk, junk, temp).forEach { emit(it, sourcePc) }
                    emit(
                        LuaInstructionFactory.abx(version, opcodeOf(version, "LOADK"), temp, constant(2 + random.nextInt(1 shl 16))),
                        sourcePc,
                    )
                    LuaArithmetic.pair(prototype, "ADD", junk, junk, temp).forEach { emit(it, sourcePc) }
                }

                1 -> {
                    emit(LuaInstructionFactory.abx(version, opcodeOf(version, "LOADK"), temp, constant(b)), sourcePc)
                    LuaArithmetic.pair(prototype, "BOR", junk, junk, temp).forEach { emit(it, sourcePc) }
                    LuaArithmetic.pair(prototype, "BXOR", junk, junk, temp).forEach { emit(it, sourcePc) }
                    LuaArithmetic.pair(prototype, "BXOR", junk, junk, temp).forEach { emit(it, sourcePc) }
                    emit(
                        LuaInstructionFactory.abx(version, opcodeOf(version, "LOADK"), temp, constant(2 + random.nextInt(1 shl 16))),
                        sourcePc,
                    )
                    LuaArithmetic.pair(prototype, "ADD", junk, junk, temp).forEach { emit(it, sourcePc) }
                }

                2 -> {
                    emit(LuaInstructionFactory.abx(version, opcodeOf(version, "LOADK"), temp, constant(b)), sourcePc)
                    LuaArithmetic.pair(prototype, "MUL", junk, junk, temp).forEach { emit(it, sourcePc) }
                    LuaArithmetic.pair(prototype, "BXOR", junk, junk, temp).forEach { emit(it, sourcePc) }
                    LuaArithmetic.pair(prototype, "BXOR", junk, junk, temp).forEach { emit(it, sourcePc) }
                    emit(
                        LuaInstructionFactory.abx(version, opcodeOf(version, "LOADK"), temp, constant(2 + random.nextInt(1 shl 16))),
                        sourcePc,
                    )
                    LuaArithmetic.pair(prototype, "ADD", junk, junk, temp).forEach { emit(it, sourcePc) }
                }

                else -> {
                    emit(LuaInstructionFactory.abc(version, opcodeOf(version, "MOVE"), junk, junk, 0, 0), sourcePc)
                    emit(LuaInstructionFactory.abx(version, opcodeOf(version, "LOADK"), temp, constant(b)), sourcePc)
                    LuaArithmetic.pair(prototype, "MUL", junk, junk, temp).forEach { emit(it, sourcePc) }
                    emit(
                        LuaInstructionFactory.abx(version, opcodeOf(version, "LOADK"), temp, constant(2 + random.nextInt(1 shl 16))),
                        sourcePc,
                    )
                    LuaArithmetic.pair(prototype, "ADD", junk, junk, temp).forEach { emit(it, sourcePc) }
                }
            }
            LuaArithmetic.pair(prototype, "BXOR", stateReg, stateReg, junk).forEach { emit(it, sourcePc) }
            LuaArithmetic.pair(prototype, "BXOR", stateReg, stateReg, junk).forEach { emit(it, sourcePc) }
        }

        fun emitJumpToDispatch(sourcePc: Int? = null) {
            if (config.participatingJunk) emitParticipatingJunk(sourcePc)
            packets.dispatchJump()
        }

        // §1.4: on 5.4 / 5.5 `VARARGPREP` relocates the caller's varargs above the
        // fixed frame and MUST be the first instruction executed - the entry state
        // derivation emitted before it ran with an unadjusted frame and
        // desynchronised `select("#", ...)`.
        var entryVarargprepEmitted = false
        if (version != LuaBytecodeVersion.LUA_5_3 && code.firstOrNull()?.opcodeName == "VARARGPREP") {
            emit(code[0], 0)
            entryVarargprepEmitted = true
        }
        emitState(ids[0])
        emitJumpToDispatch()

        // §4.2: the physical layout is independent of the logical CFG - every
        // cross-block edge is either a dispatcher capsule or a label jump, except
        // the fall-through after a conditional terminator, which gets its own
        // explicit capsule when the layout is shuffled. The version's direction
        // restricted loop jumps (5.4/5.5: FORPREP / TFORPREP forward only,
        // FORLOOP / TFORLOOP backward only) impose order constraints between
        // their units: the layout is a randomized topological order of those
        // constraints. A constraint cycle degrades THIS prototype back to the
        // original order (refuse-to-shuffle, never refuse-to-flatten).
        val emissionOrder: List<Int> = run {
            val identity = units.indices.toList()
            if (!config.shuffleBlocks) return@run identity
            if (version == LuaBytecodeVersion.LUA_5_3) {
                // 5.3 encodes every loop jump as signed sBx: no direction constraints.
                return@run (units.indices).shuffled(random)
            }
            val forwardOnly = setOf("FORPREP", "TFORPREP")
            val backwardOnly = setOf("FORLOOP", "TFORLOOP")
            val successors = HashMap<Int, MutableList<Int>>()
            val inDegree = IntArray(units.size)
            for (pc in code.indices) {
                val instruction = code[pc]
                val target = instruction.jumpTarget(pc) ?: continue
                val from = blockOf[pc].takeIf { it >= 0 } ?: continue
                val to = blockOf.getOrNull(target)?.takeIf { it >= 0 } ?: continue
                if (from == to) continue
                val (before, after) = when {
                    instruction.opcodeName in forwardOnly -> from to to
                    instruction.opcodeName in backwardOnly -> to to from
                    else -> continue
                }
                successors.getOrPut(before) { ArrayList() } += after
                inDegree[after]++
            }
            // Kahn's algorithm; the candidate order stays the shuffled one, so the
            // layout keeps its randomness inside the constrained family.
            val candidates = (units.indices).shuffled(random).toMutableList()
            val result = ArrayList<Int>(units.size)
            var progress = true
            while (result.size < units.size && progress) {
                progress = false
                val iterator = candidates.iterator()
                for (unit in iterator) {
                    if (inDegree[unit] == 0) {
                        result += unit
                        iterator.remove()
                        progress = true
                        successors[unit]?.forEach { inDegree[it]-- }
                    }
                }
            }
            if (result.size == units.size) result else identity
        }

        for (index in emissionOrder) {
            val unit = units[index]
            builder.place(label(index))
            val startsWithTforcall = code.getOrNull(unit.first)?.opcodeName == "TFORCALL"
            // §4.1 in-degree obfuscation: a converging guard in front of the body,
            // so dispatcher entries and direct entries look structurally identical.
            // TFORCALL-starting units are exempt: `TFORPREP` must land on the
            // TFORCALL instruction itself (native fetch target, §1.4.7), so its
            // unit label has to stay glued to the instruction.
            if (config.indegreeGuards && !startsWithTforcall) {
                val body = supply.next()
                emitConvergingGuard { builder.emitJumpTo(body); line(unit.first) }
                builder.place(body)
            }
            var pc = unit.first
            while (pc <= unit.last) {
                val instruction = code[pc]
                if (entryVarargprepEmitted && pc == 0 && instruction.opcodeName == "VARARGPREP") {
                    pc++
                    continue
                }
                val target = instruction.jumpTarget(pc)
                when {
                    // A terminator is emitted exactly once by the edge code.
                    pc == unit.last && (instruction.opcodeName in RETURNS || target != null) -> Unit
                    target != null -> {
                        val destination = blockOf.getOrNull(target)?.takeIf { it >= 0 }
                            ?: return refuse(R03_CFG, "target outside the cfg")
                        builder.emitCopy(instruction, pc, label(destination))
                        line(pc)
                    }
                    else -> emit(instruction, pc)
                }

                pc++
            }

            // ---- terminator edges (§1.4)
            val last = unit.last
            val terminator = code[last]
            val target = terminator.jumpTarget(last)
            when {
                terminator.opcodeName in RETURNS -> {
                    emit(terminator, last)
                }
                // (a `RETURN` ends the prototype, so the mask never has to survive it)

                terminator.opcodeName in LOOP_FORWARD -> {
                    val to = target?.let { blockOf.getOrNull(it) }?.takeIf { it >= 0 }
                        ?: return refuse(R03_CFG, "loop prep target outside the cfg")
                    builder.emitCopy(terminator, last, label(to))
                    line(last)
                    if (version != LuaBytecodeVersion.LUA_5_3 && terminator.opcodeName == "FORPREP") {
                        val exit = blockOf.getOrNull(last + 1)?.takeIf { it >= 0 }
                            ?: return refuse(R03_CFG, "loop entry outside the cfg")
                        emitState(ids[exit], last)
                        emitJumpToDispatch(last)
                    }
                }

                terminator.opcodeName in LOOP_BACK -> {
                    val back = target?.let { blockOf.getOrNull(it) }?.takeIf { it >= 0 }
                        ?: return refuse(R03_CFG, "loop back target outside the cfg")
                    val exit = blockOf.getOrNull(last + 1)?.takeIf { it >= 0 }
                        ?: return refuse(R03_CFG, "loop exit outside the cfg")
                    builder.emitCopy(terminator, last, label(back))
                    line(last)
                    emitState(ids[exit], last)
                    emitJumpToDispatch(last)
                }

                terminator.opcodeName == "JMP" -> {
                    val conditional = last > unit.first && code[last - 1].skipsNextInstruction
                    val to = target?.let { blockOf.getOrNull(it) }?.takeIf { it >= 0 }
                        ?: return refuse(R03_CFG, "jump target outside the cfg")
                    if (conditional) {
                        // the comparison skips this jump; when taken it goes to the
                        // target block. Without shuffling the not-taken path falls
                        // through into the next unit in layout order (whose entry
                        // re-derives the state); with shuffling the fall-through is
                        // an explicit capsule so the layout stays free.
                        builder.emitCopy(terminator, last, label(to))
                        line(last)
                        if (config.shuffleBlocks) {
                            val exit = blockOf.getOrNull(last + 1)?.takeIf { it >= 0 }
                                ?: return refuse(R03_CFG, "conditional fall-through outside the cfg")
                            emitState(ids[exit], last)
                            emitJumpToDispatch(last)
                        }
                    } else {
                        if (version == LuaBytecodeVersion.LUA_5_3 && terminator.a != 0) {
                            val capsule = supply.next()
                            builder.emitCopy(terminator, last, capsule)
                            line(last)
                            builder.place(capsule)
                        }
                        emitState(ids[to], last)
                        emitJumpToDispatch(last)
                    }
                }

                else -> {
                    // A unit that *ends* on a skip instruction (the 5.3
                    // `LOADBOOL R b 1` boolean-return idiom) keeps its skip flag:
                    // the skip swallows whatever comes next, so a harmless
                    // absorber on a dead scratch register is parked between the
                    // terminator and the dispatcher edge, and control continues
                    // at `last + 2` (the skipped instruction is never executed).
                    if (terminator.skipsNextInstruction) {
                        val exit = blockOf.getOrNull(last + 2)?.takeIf { it >= 0 }
                            ?: return refuse(R03_CFG, "skip fall-through outside the cfg")
                        emit(
                            LuaInstructionFactory.abc(version, opcodeOf(version, "MOVE"), scratch, scratch, 0, 0),
                            last,
                        )
                        emitState(ids[exit], last)
                        emitJumpToDispatch(last)
                    } else {
                        val exit = blockOf.getOrNull(last + 1)?.takeIf { it >= 0 }
                            ?: return refuse(R03_CFG, "fall-through outside the cfg")
                        emitState(ids[exit], last)
                        emitJumpToDispatch(last)
                    }
                }
            }
        }

        packets.dispatch(::label) { emit(returnNothing(version)) }

        // ---- transaction commit (§1.5): the machine is only written back when
        // every label resolved and every jump re-encoded; any failure restores
        // the prototype's pre-CFF frame top and records the refusal.
        val built = try {
            builder.build()
        } catch (exception: RuntimeException) {
            return refusal(R17_CAPACITY, "re-encode failed: ${exception.message}", prototype, state, refusals, debug)
        }
        if (built.size > size * config.maxExpansionFactor) return refuse("R19 expansion budget", "candidate too large")
        val proof = CffTranslationValidator.verify(built.map { LuaInstruction(it.raw, version) },
            prototype.constants, packets.sites, units.indices.map { builder.positionOf(label(it)) },
            state, frameTop, config.maxValidationSteps)
        CffTranslationValidator.checkStructure(prototype.copy(code = built.toMutableList()))
        require(emittedLines.size == built.size) { "source map does not cover every emitted instruction" }
        prototype.edit { replaceCode(built, emittedLines) }
        prototype.localVariables.clear()
        if (debug) {
            Platform.debug.log("[cff] ${prototype.source}:${prototype.lineDefined} blocks=${units.size} mask=$useMask")
        }
        val actual = packets.features.toMutableSet()
        if (guards > 0) actual += "predicates.numeric"
        if (config.participatingJunk) actual += "junk.participating"
        if (emissionOrder != units.indices.toList()) actual += "layout.shuffle"
        if (config.splitStraightLine && originalUnitCount != units.size) {
            actual += "split.straightline"
        }
        return Flattened(units.size, guards, built.size - size, actual, proof.paths, proof.steps,
            CffProofManifest(packets.sites.map { it.pc to it.target },
                units.indices.map { builder.positionOf(label(it)) }, state, config.maxValidationSteps,
                packets.sites.count { it.variableAtEntry }))
    }

    // ------------------------------------------------------------------ units

    /**
     * §1.2 atomic units: jump targets and conditional skip fall-throughs open a
     * unit; instruction groups (arithmetic + `MMBIN`, `EXTRAARG` pairs,
     * `TFORCALL` + `TFORLOOP`, compare + `JMP`) must never be split by a
     * fall-through boundary. The 5.3 `LOADBOOL R b 1` boolean-return idiom is
     * the one tolerated exception: the emitter parks a skip absorber after it,
     * so the skip eats the absorber instead of a dispatcher instruction.
     */
    private fun unitsOf(prototype: LuaPrototype, split: Boolean = false): List<IntRange>? {
        val code = prototype.code
        val size = code.size
        val starts = sortedSetOf(0)
        for (pc in code.indices) {
            val instruction = code[pc]
            instruction.jumpTarget(pc)?.let { if (it in 0 until size) starts += it }
            if (instruction.skipsNextInstruction && pc + 2 <= size) starts += pc + 2
            // In 5.3 a tail call to C returns to the following RETURN B=0.
            // Preserve that top-sensitive pair even though Lua tail calls replace
            // the frame. Keeping the compiler pair is also safe on 5.4/5.5.
            if ((instruction.jumpTarget(pc) != null || instruction.opcodeName in RETURNS) &&
                instruction.opcodeName != "TAILCALL" && pc + 1 < size) {
                starts += pc + 1
            }
        }
        if (split && code.none { it.opcodeName in LOOP_ANY || it.opcodeName in DYNAMIC_HINT }) {
            val groups = instructionGroupsOf(prototype)
            for (group in groups.drop(1).dropLast(1).filterIndexed { index, _ -> index % 8 == 3 }) {
                if (!code[group.first - 1].skipsNextInstruction) starts += group.first
            }
        }
        val filtered = starts.filter { it in 0 until size }
        val boundaries = filtered.toSet()
        val groupEnd = HashMap<Int, Int>()
        for (group in instructionGroupsOf(prototype)) for (pc in group) groupEnd[pc] = group.last
        for (index in filtered.indices) {
            val end = if (index + 1 < filtered.size) filtered[index + 1] - 1 else size - 1
            val gEnd = groupEnd[end] ?: continue
            if (gEnd == end) continue
            val terminator = code[end]
            if (terminator.skipsNextInstruction && terminator.opcodeName !in setOf("LOADBOOL", "LFALSESKIP")) return null
            if (terminator.opcodeName !in setOf("LOADBOOL", "LFALSESKIP")) return null
        }
        for (pc in code.indices) {
            if (code[pc].skipsNextInstruction && (pc + 1) in boundaries) {
                if (code[pc].opcodeName in setOf("LOADBOOL", "LFALSESKIP")) {
                    continue
                }
                return null
            }
        }
        val units = ArrayList<IntRange>(filtered.size)
        for ((index, from) in filtered.withIndex()) {
            val to = if (index + 1 < filtered.size) filtered[index + 1] - 1 else size - 1
            units += from..to
        }
        return units
    }

    /** `op`, `MMBIN`, `head + EXTRAARG`, `TFORCALL + TFORLOOP`, compare + `JMP`. */
    private fun instructionGroupsOf(prototype: LuaPrototype): List<IntRange> {
        val code = prototype.code
        val groups = ArrayList<IntRange>()
        var pc = 0
        while (pc < code.size) {
            val name = code[pc].opcodeName
            val next = code.getOrNull(pc + 1)?.opcodeName
            val groupSize = when {
                name in ARITH && next != null && next.startsWith("MMBIN") -> 2
                (name == "LOADKX" || name == "SETLIST" || name == "NEWTABLE") && next == "EXTRAARG" -> 2
                name == "TFORCALL" && next == "TFORLOOP" -> 2
                name == "TAILCALL" && next == "RETURN" -> 2
                code[pc].skipsNextInstruction && next == "JMP" -> 2
                else -> 1
            }
            groups += pc until (pc + groupSize)
            pc += groupSize
        }
        return groups
    }

    private val ARITH = setOf(
        "ADD", "SUB", "MUL", "MOD", "POW", "DIV", "IDIV", "BAND", "BOR", "BXOR", "SHL", "SHR",
        "ADDI", "ADDK", "SUBI", "SUBK", "MULI", "MULK", "MODI", "MODK", "POWI", "POWK",
        "DIVI", "DIVK", "IDIVI", "IDIVK", "BANDI", "BANDK", "BORI", "BORK", "BXORI", "BXORK",
        "SHLI", "SHLK", "SHRI", "SHRK",
    )

    private val LOOP_BACK = setOf("FORLOOP", "TFORLOOP")
    private val LOOP_FORWARD = setOf("FORPREP", "TFORPREP")
    private val LOOP_ANY = LOOP_BACK + LOOP_FORWARD
    private val RETURNS = setOf("RETURN", "RETURN0", "RETURN1", "TAILCALL")

    /** Opcodes that hint at a frame-top dependent range (split refusals). */
    private val DYNAMIC_HINT = setOf("VARARG", "SETLIST", "CALL", "TAILCALL")

    private fun opcodeOf(version: LuaBytecodeVersion, name: String): Int =
        LuaArithmetic.opcode(version, name)

    private fun returnNothing(version: LuaBytecodeVersion): LuaInstruction =
        if (version == LuaBytecodeVersion.LUA_5_3) {
            LuaInstructionFactory.abc(version, opcodeOf(version, "RETURN"), 0, 1, 0)
        } else {
            LuaInstructionFactory.abc(version, opcodeOf(version, "RETURN0"), 0, 0, 0)
        }

    /** Label based assembler: labels are placed, jumps are patched at the end. */
    internal class LabelSupply {
        private var next = 1
        fun next(): Int = next++
    }

    internal class CodeBuilder(private val version: LuaBytecodeVersion) {
        private val code = ArrayList<LuaInstruction>()
        val size: Int get() = code.size
        private val labels = HashMap<Int, Int>()

        /**
         * A jump that still has to be re-encoded. `sourcePc` matters: retargeting
         * computes the new offset from the instruction's *original* position, and
         * losing it produced wrong offsets on every retargeted jump.
         */
        private data class Fixup(
            val pc: Int,
            val sourcePc: Int,
            val instruction: LuaInstruction,
            val label: Int,
        )

        private val fixups = ArrayList<Fixup>()

        fun place(label: Int) {
            labels[label] = code.size
        }

        fun emit(instruction: LuaInstruction) {
            code += instruction
        }

        fun emitCopy(instruction: LuaInstruction, sourcePc: Int, targetLabel: Int) {
            // Keep the original encoding as a placeholder: re-encoding it towards
            // its own position would already fail for direction restricted jumps
            // (`FORLOOP` backwards only / `FORPREP` forwards only on 5.4 / 5.5).
            val pc = code.size
            code += instruction
            fixups += Fixup(pc, sourcePc, instruction, targetLabel)
        }

        fun emitJumpTo(targetLabel: Int) {
            val pc = code.size
            val placeholder = jumpTo(version, pc, pc)
            code += placeholder
            fixups += Fixup(pc, pc, placeholder, targetLabel)
        }

        fun positionOf(label: Int): Int = labels[label] ?: -1

        fun build(): List<LuaInstruction> {
            for ((pc, sourcePc, instruction, label) in fixups) {
                val target = labels[label]
                    ?: throw LuaBytecodeFormatException("unresolved label $label in flattening")
                code[pc] = try {
                    instruction.retargeted(sourcePc, pc, target).also {
                        require(it.jumpTarget(pc) == target) { "jump offset overflow" }
                    }
                } catch (exception: IllegalArgumentException) {
                    throw LuaBytecodeFormatException(
                        "flattening cannot re-encode ${instruction.opcodeName} at new pc $pc " +
                            "(target block starts at $target, instruction count ${code.size}): " +
                            exception.message,
                    )
                }
            }
            return code
        }
    }
}
