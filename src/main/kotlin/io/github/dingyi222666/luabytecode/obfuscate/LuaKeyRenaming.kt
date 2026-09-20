package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaOpcodes
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.LuaString
import io.github.dingyi222666.luabytecode.bytecode.analysis.constantOperands
import java.util.IdentityHashMap
import java.util.Random

/**
 * Tunables of [LuaKeyRenaming] (LEAK-1 key half).
 */
data class KeyRenamingConfig(
    /** Minimum key length to consider (short names like `x` carry no semantics). */
    val minKeyLength: Int = 4,

    /** `0` derives a deterministic seed from the chunk content. */
    val seed: Long = 0,
)

/** Statistics of one [LuaKeyRenaming.apply] run. */
data class KeyRenamingResult(
    /** Number of key string constants that were renamed. */
    val renamedKeys: Int,
    /** Number of rewritten reference sites. */
    val rewrittenSites: Int,
    /** Number of candidate keys skipped by the safety judgments. */
    val skippedKeys: Int,
)

/**
 * Table-field key renaming (LEAK-1 key half, DESIGN §3.6 `LuaKeyRenaming`).
 *
 * A key is a string constant whose every reference sits in the **K side of the
 * field-access family** (`GETTABUP` / `GETFIELD` / `SETTABUP` / `SETFIELD` /
 * `SELF`, and 5.3's `GETTABLE` / `SETTABLE` RK keys). Renaming is an in-place
 * constant-pool value replacement — **constant indices never move**, so every
 * `LOADK` decoration that the junk pass loaded from the same slot follows the
 * synthetic name automatically (the R3 5× inflation surface collapses into a
 * stream of meaningless hex).
 *
 * Safety judgments — a key is renamed only when ALL hold:
 *
 *  1. **K-side purity**: no instruction outside the field-access family
 *     references the constant (no `LOADK` value read, no `EQ`/`concat`/pattern
 *     use). `LOADK` decorations deliberately exclude a key from renaming in this
 *     first version (KN-1 boundary): a `LOADK` may be a *value* read whose
 *     semantics renaming would break, and junk decorations cannot be told apart
 *     from value reads statically.
 *  2. **No global write**: the key never appears in a `SETTABUP` whose table is
 *     `_ENV` at index 0 of a prototype without other upvalue tables — the
 *     conservative check is: `SETTABUP` referencing the key is only allowed when
 *     the prototype also *reads* the same key through the same upvalue table
 *     (method-table pattern), otherwise the key is skipped (global variable
 *     semantics, externally visible).
 *  3. **Length**: `utf8 length ≥ minKeyLength` (single letters carry no
 *     semantics and inflate the diff).
 *
 * Synthetic names are `k` + 8 random hex chars, unique across the chunk, with
 * **no token overlap** with any original key (R3's lexical acceptance checks
 * prefix/suffix/substring overlap).
 *
 * The pass runs AFTER constant encryption / flattening (it must see the final
 * layout) and BEFORE `LuaTailReturn`.
 */
object LuaKeyRenaming {

    /** Opcodes whose K-side operand may reference a *field key*. */
    private val KEY_SIDE = setOf(
        "GETTABUP", "GETFIELD", "SETTABUP", "SETFIELD", "SELF",
        "GETTABLE", "SETTABLE", // 5.3 RK keys
        "GETI", "SETI", // 5.4/5.5 integer keys — included for completeness of the scan below
    )

    @JvmStatic
    @JvmOverloads
    fun apply(
        chunk: LuaChunk,
        config: KeyRenamingConfig = KeyRenamingConfig(),
    ): KeyRenamingResult {
        val dbg = System.getenv("KEY_DEBUG") != null
        if (dbg) System.err.println("[KEY] apply called on ${chunk.prototypes.size} prototypes")
        val random = Random(if (config.seed != 0L) config.seed else contentSeed(chunk))
        var renamed = 0
        var sites = 0
        var skipped = 0
        val usedNames = HashSet<String>()

        // Cross-prototype value grouping: the SAME method name usually lives in
        // several constant pools (the table constructor interns it, every method
        // body re-interns it for `obj:method()` SELF calls). Renaming must hit
        // ALL equal strings across ALL prototypes at once, otherwise the write
        // side and the read side split apart (Round 3: `attempt to call a nil
        // value (field 'k4dac0127')`).
        class Group(val slots: MutableMap<LuaPrototype, MutableList<Int>> = IdentityHashMap())
        val groups = LinkedHashMap<String, Group>()
        for (prototype in chunk.prototypes) {
            for ((constantIndex, constant) in prototype.constants.withIndex()) {
                val text = (constant as? LuaConstant.Str)?.value?.utf8 ?: continue
                if (text.length < config.minKeyLength) continue
                groups.getOrPut(text) { Group() }.slots.getOrPut(prototype) { mutableListOf() } += constantIndex
            }
        }

        for ((text, group) in groups) {
            val refsAll = ArrayList<Ref>()
            val slotsToRename = ArrayList<Pair<LuaPrototype, Int>>()
            for ((prototype, indices) in group.slots) {
                val keyReferences = collectKeyReferences(prototype)
                for (constantIndex in indices) {
                    val refs = keyReferences[constantIndex] ?: continue
                    refsAll.addAll(refs)
                    slotsToRename += prototype to constantIndex
                }
            }
            // Conservative v1: only rename when ALL equal-string slots live in
            // ONE prototype (table constructed and accessed in the same body).
            // Cross-prototype groups stayed broken in Round 3 even with all-K-side
            // references (`attempt to call nil value (field 'k…')`), so they are
            // registered as CONST-KEY-2 instead of shipped broken.
            val distinctProtos = group.slots.keys.toList()
            if (distinctProtos.size != 1) {
                skipped++
                continue
            }
            if (refsAll.isEmpty()) {
                skipped++
                continue
            }
            if (refsAll.any { !it.keySide }) {
                if (dbg) {
                    val nonKey = refsAll.filter { !it.keySide }.take(3)
                    System.err.println("[KEY] skip non-key-side: '$text' at " +
                        nonKey.joinToString { prototypeNameOf(it) })
                }
                skipped++
                continue
            }
            if (hasGlobalWriteShape(chunk.mainPrototype, refsAll)) {
                if (dbg) System.err.println("[KEY] skip global-write: '$text'")
                skipped++
                continue
            }
            var synthetic = ""
            do {
                synthetic = "k" + (1..8).map { "0123456789abcdef"[random.nextInt(16)] }.joinToString("")
            } while (!usedNames.add(synthetic))
            for ((prototype, constantIndex) in slotsToRename) {
                io.github.dingyi222666.luabytecode.bytecode.edit.LuaPrototypeScope(prototype)
                    .setConstant(constantIndex, LuaConstant.Str(LuaString.of(synthetic)))
                renamed++
            }
            sites += refsAll.size
        }

        return KeyRenamingResult(renamed, sites, skipped)
    }

    private class Ref(val pc: Int, val keySide: Boolean, val isGlobalWrite: Boolean)

    private fun prototypeNameOf(ref: Ref): String = "pc${ref.pc}"

    /**
     * All references to string constants classified as key-side or not.
     * Returns `constant index -> refs` for constants that have at least one
     * key-side reference and NO reference from a plain value `LOADK` (KN-1).
     */
    private fun collectKeyReferences(
        prototype: LuaPrototype,
    ): Map<Int, List<Ref>> {
        val all = IdentityHashMap<Int, MutableList<Ref>>()
        for (pc in prototype.code.indices) {
            val instruction = prototype.code[pc]
            val opcodeName = instruction.opcodeName
            when (opcodeName) {
                "LOADK", "LOADKX" -> {
                    val index = if (opcodeName == "LOADK") instruction.bx else {
                        val extra = prototype.code.getOrNull(pc + 1)
                        if (extra?.opcodeName == "EXTRAARG") extra.ax else continue
                    }
                    all.getOrPut(index) { mutableListOf() } += Ref(pc, keySide = false, isGlobalWrite = false)
                }

                "GETTABUP", "GETFIELD", "SELF" -> {
                    val index = keyOperand(instruction, listOf('C'))
                    if (index != null) {
                        all.getOrPut(index) { mutableListOf() } += Ref(pc, keySide = true, isGlobalWrite = false)
                    }
                }

                "SETTABUP", "SETFIELD" -> {
                    val index = keyOperand(instruction, listOf('B'))
                    if (index != null) {
                        // a global write is a SETTABUP into upvalue 0 without a
                        // matching read — refined by [hasGlobalWriteShape]
                        all.getOrPut(index) { mutableListOf() } +=
                            Ref(pc, keySide = true, isGlobalWrite = opcodeName == "SETTABUP")
                    }
                }

                "GETTABLE", "SETTABLE" -> {
                    if (prototype.version == LuaBytecodeVersion.LUA_5_3) {
                        val operands = listOf(
                            'B' to instruction.b,
                            'C' to instruction.c,
                        )
                        for ((field, operand) in operands) {
                            if (operand and 0x100 != 0) {
                                val index = operand and 0xFF
                                all.getOrPut(index) { mutableListOf() } +=
                                    Ref(pc, keySide = true, isGlobalWrite = false)
                            }
                        }
                    }
                }

                else -> {
                    for (index in instruction.constantOperands()) {
                        all.getOrPut(index) { mutableListOf() } += Ref(pc, keySide = false, isGlobalWrite = false)
                    }
                }
            }
        }
        return all
    }

    /** The K-side constant index behind [instruction], or `null`. */
    private fun keyOperand(instruction: LuaInstruction, fields: List<Char>): Int? {
        val version = instruction.version
        return when (version) {
            LuaBytecodeVersion.LUA_5_3 -> {
                for (field in fields) {
                    val operand = if (field == 'B') instruction.b else instruction.c
                    if (operand and 0x100 != 0) {
                        return operand and 0xFF
                    }
                }
                null
            }

            else -> {
                // 5.4 / 5.5: plain 8-bit constant index (k flag not used for keys)
                for (field in fields) {
                    val operand = if (field == 'B') instruction.b else instruction.c
                    if (operand != 0 || fields == listOf('B')) {
                        return operand
                    }
                }
                null
            }
        }
    }

    /**
     * `true` when a `SETTABUP` reference looks like a *global variable* write
     * (no other key-side read of the same constant anywhere in the prototype):
     * those are `_ENV` bindings whose name is externally visible.
     */
    private fun hasGlobalWriteShape(prototype: LuaPrototype, refs: List<Ref>): Boolean {
        val writes = refs.filter { it.isGlobalWrite }
        if (writes.isEmpty()) return false
        val reads = refs.count { it.keySide && !it.isGlobalWrite }
        return reads == 0
    }

    private fun contentSeed(chunk: LuaChunk): Long {
        var hash = 1125899906842597L
        for (prototype in chunk.prototypes) {
            hash = hash * 31 + prototype.constantCount
        }
        return hash
    }

    private fun opcode(version: LuaBytecodeVersion, name: String): Int =
        LuaOpcodes.opcodeOf(version, name)
            ?: throw LuaBytecodeFormatException("no $name opcode in ${version.displayName}")
}
