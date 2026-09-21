package io.github.dingyi222666.luabytecode.bytecode.edit

import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.LuaString
import io.github.dingyi222666.luabytecode.bytecode.LuaUpvalue
import io.github.dingyi222666.luabytecode.bytecode.analysis.setAbsoluteLines
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter

/**
 * Editing API for the [LuaChunk] model.
 *
 * Everything here mutates the tree in place, so a load → edit → dump workflow
 * stays allocation free and fast:
 *
 * ```kotlin
 * val chunk = LuaChunkLoader.load(File("script.luac"))
 *
 * // 1. rewrite every string constant of every prototype
 * chunk.prototypes.forEach { prototype ->
 *     prototype.replaceStringConstants { value -> value.replacing("secret", "?") }
 * }
 *
 * // 2. patch one instruction and add a new one, keeping the line tables valid
 * val main = chunk.mainPrototype
 * main.setInstruction(3, main.code[3].withA(0))
 * main.insertInstruction(4, LuaInstruction.abc(chunk.version, opcode = 0, a = 1, b = 2), line = 3)
 *
 * LuaChunkWriter.dump(chunk, File("script.patched.luac"))
 * ```
 *
 * The chunk level helpers live in [LuaChunkDsl] (`chunk.edit { ... }`).
 */

// ------------------------------------------------------------------ constants

/** Index of the first constant matching [predicate], or `-1`. */
fun LuaPrototype.indexOfConstant(predicate: (LuaConstant) -> Boolean): Int =
    constants.indexOfFirst(predicate)

/** Index of the constant equal to [constant], or `-1`. */
fun LuaPrototype.indexOfConstant(constant: LuaConstant): Int =
    constants.indexOfFirst { it == constant }

/** Index of the first string constant whose UTF-8 text equals [text], or `-1`. */
fun LuaPrototype.indexOfStringConstant(text: String): Int =
    constants.indexOfFirst { it is LuaConstant.Str && it.value.utf8 == text }

/** The string constant at [index], or `null` when it is not a string constant. */
fun LuaPrototype.stringConstantAt(index: Int): LuaString? =
    (constants.getOrNull(index) as? LuaConstant.Str)?.value

/**
 * Appends [constant] unless an equal one already exists, and returns its index.
 *
 * Interning keeps the constant table small, which matters because obfuscation
 * tends to add the same helper string (a key, a chunk of code) many times.
 */
fun LuaPrototype.internConstant(constant: LuaConstant): Int {
    val existing = indexOfConstant(constant)
    if (existing >= 0) {
        return existing
    }
    constants += constant
    return constants.size - 1
}

/** Interns [value] as a string constant and returns its index. */
fun LuaPrototype.internString(value: LuaString): Int = internConstant(LuaConstant.Str(value))

/** Interns the UTF-8 bytes of [text] as a string constant and returns its index. */
fun LuaPrototype.internString(text: String): Int = internString(LuaString.of(text))

/**
 * Replaces every constant matching [predicate] with `replacement(old)`.
 *
 * @return the number of replaced constants.
 */
fun LuaPrototype.replaceConstants(
    predicate: (LuaConstant) -> Boolean,
    replacement: (LuaConstant) -> LuaConstant,
): Int {
    var replaced = 0
    for (index in constants.indices) {
        val current = constants[index]
        if (predicate(current)) {
            constants[index] = replacement(current)
            replaced++
        }
    }
    return replaced
}

/**
 * Rewrites every string constant through [transform]; returning `null` (or the
 * same value) keeps the original.
 *
 * @return the number of rewritten constants.
 */
fun LuaPrototype.replaceStringConstants(transform: (LuaString) -> LuaString?): Int {
    var replaced = 0
    for (index in constants.indices) {
        val constant = constants[index]
        if (constant is LuaConstant.Str) {
            val rewritten = transform(constant.value) ?: continue
            if (rewritten != constant.value) {
                constants[index] = LuaConstant.Str(rewritten)
                replaced++
            }
        }
    }
    return replaced
}

/** Runs [action] for every string constant, with its index in the table. */
fun LuaPrototype.forEachStringConstant(action: (index: Int, value: LuaString) -> Unit) {
    constants.forEachIndexed { index, constant ->
        if (constant is LuaConstant.Str) {
            action(index, constant.value)
        }
    }
}

/** All string constants of this prototype, as `index to value` pairs. */
fun LuaPrototype.stringConstants(): List<Pair<Int, LuaString>> = buildList {
    forEachStringConstant { index, value -> add(index to value) }
}

/** The UTF-8 text of a string constant, or `null` for every other constant. */
val LuaConstant.stringValue: String?
    get() = (this as? LuaConstant.Str)?.value?.utf8

/** The bytes of a string constant, or `null` for every other constant. */
val LuaConstant.stringBytes: ByteArray?
    get() = (this as? LuaConstant.Str)?.value?.bytes

/**
 * A new [LuaString] whose UTF-8 text has [from] replaced with [to].
 *
 * ```kotlin
 * prototype.replaceStringConstants { it.replacing("print", "_G[\\\"p\\\"]") }
 * ```
 */
fun LuaString.replacing(from: String, to: String): LuaString {
    val text = utf8
    return if (text.contains(from)) LuaString.of(text.replace(from, to)) else this
}

// --------------------------------------------------------------- instructions

/** The instruction at [index] (`code[index]`). */
fun LuaPrototype.instructionAt(index: Int): LuaInstruction = code[index]

/** Replaces the instruction at [index]; the instruction count does not change. */
fun LuaPrototype.setInstruction(index: Int, instruction: LuaInstruction) {
    code[index] = instruction
}

/** Replaces the instruction at [index] with the result of [patch]. */
fun LuaPrototype.patchInstruction(index: Int, patch: (LuaInstruction) -> LuaInstruction) {
    code[index] = patch(code[index])
}

/** Appends [instruction], keeping the line tables and local pc ranges valid. */
fun LuaPrototype.appendInstruction(instruction: LuaInstruction, line: Int? = null) {
    insertInstruction(code.size, instruction, line)
}

/**
 * Inserts [instruction] before [index].
 *
 * Debug information stays consistent:
 *  * `lineInfo` / `absoluteLineInfo` get a new entry (using [line], or the line
 *    of the surrounding instruction when `null`), and are re-encoded;
 *  * local variable pc ranges are shifted;
 *  * a stripped prototype stays stripped.
 */
fun LuaPrototype.insertInstruction(index: Int, instruction: LuaInstruction, line: Int? = null) {
    require(index in 0..code.size) { "instruction index $index out of 0..${code.size}" }
    val lines = currentAbsoluteLines()
    code.add(index, instruction)
    if (lines != null) {
        val resolvedLine = line ?: lines.getOrElse(index) { lines.lastOrNull() ?: lineDefined }
        lines.add(index, resolvedLine)
        setAbsoluteLines(lines)
    }
    shiftLocalVariablesForInsert(index, 1)
}

/** Inserts [instructions] before [index]; [lines] must match their count. */
fun LuaPrototype.insertInstructions(
    index: Int,
    instructions: List<LuaInstruction>,
    lines: List<Int>? = null,
) {
    if (instructions.isEmpty()) {
        return
    }
    require(index in 0..code.size) { "instruction index $index out of 0..${code.size}" }
    if (lines != null) {
        require(lines.size == instructions.size) {
            "got ${lines.size} lines for ${instructions.size} instructions"
        }
    }
    val absoluteLines = currentAbsoluteLines()
    code.addAll(index, instructions)
    if (absoluteLines != null) {
        val inserted = lines
            ?: List(instructions.size) { absoluteLines.getOrElse(index) { absoluteLines.lastOrNull() ?: lineDefined } }
        absoluteLines.addAll(index, inserted)
        setAbsoluteLines(absoluteLines)
    }
    shiftLocalVariablesForInsert(index, instructions.size)
}

/** Removes the instruction at [index] and returns it. */
fun LuaPrototype.removeInstruction(index: Int): LuaInstruction {
    require(index in code.indices) { "instruction index $index out of 0..${code.size - 1}" }
    val absoluteLines = currentAbsoluteLines()
    val removed = code.removeAt(index)
    if (absoluteLines != null && index < absoluteLines.size) {
        absoluteLines.removeAt(index)
        setAbsoluteLines(absoluteLines)
    }
    shiftLocalVariablesForRemove(index)
    return removed
}

/**
 * Removes every instruction of [range] and returns how many were removed.
 * Local pc ranges and the line tables are adjusted.
 */
fun LuaPrototype.removeInstructions(range: IntRange): Int {
    require(!range.isEmpty() && range.first >= 0 && range.last < code.size) {
        "instruction range $range out of 0..${code.size - 1}"
    }
    val absoluteLines = currentAbsoluteLines()
    val count = range.last - range.first + 1
    repeat(count) {
        code.removeAt(range.first)
        if (absoluteLines != null && range.first < absoluteLines.size) {
            absoluteLines.removeAt(range.first)
        }
        shiftLocalVariablesForRemove(range.first)
    }
    if (absoluteLines != null) {
        setAbsoluteLines(absoluteLines)
    }
    return count
}

/**
 * Replaces [range] with [instructions] (`range` is inclusive); [lines] must
 * match the count of [instructions] when given.
 */
fun LuaPrototype.replaceInstructions(
    range: IntRange,
    instructions: List<LuaInstruction>,
    lines: List<Int>? = null,
) {
    val start = range.first
    removeInstructions(range)
    insertInstructions(start, instructions, lines)
}

/** Rewrites every instruction through [transform]. */
fun LuaPrototype.transformInstructions(transform: (index: Int, instruction: LuaInstruction) -> LuaInstruction) {
    for (index in code.indices) {
        code[index] = transform(index, code[index])
    }
}

/** The absolute source line of every instruction, or `null` when stripped. */
fun LuaPrototype.currentAbsoluteLines(): MutableList<Int>? =
    if (lineInfo.isEmpty()) null else resolvedLineInfo().toMutableList()

/** Shift the local variable pc ranges for an insertion of [count] instructions at [index]. */
private fun LuaPrototype.shiftLocalVariablesForInsert(index: Int, count: Int) {
    for (i in localVariables.indices) {
        val variable = localVariables[i]
        val startPc = if (variable.startPc >= index) variable.startPc + count else variable.startPc
        val endPc = if (variable.endPc >= index) variable.endPc + count else variable.endPc
        if (startPc != variable.startPc || endPc != variable.endPc) {
            localVariables[i] = variable.copy(startPc = startPc, endPc = endPc)
        }
    }
}

/** Shift the local variable pc ranges for the removal of the instruction at [index]. */
private fun LuaPrototype.shiftLocalVariablesForRemove(index: Int) {
    for (i in localVariables.indices) {
        val variable = localVariables[i]
        val startPc = (if (variable.startPc > index) variable.startPc - 1 else variable.startPc)
            .coerceIn(0, code.size)
        val endPc = (if (variable.endPc > index) variable.endPc - 1 else variable.endPc)
            .coerceIn(startPc, code.size)
        if (startPc != variable.startPc || endPc != variable.endPc) {
            localVariables[i] = variable.copy(startPc = startPc, endPc = endPc)
        }
    }
}

// -------------------------------------------------------------- upvalues etc.

/** Appends an upvalue (and its optional debug [name]) and returns its index. */
fun LuaPrototype.addUpvalue(upvalue: LuaUpvalue, name: LuaString? = null): Int {
    upvalues += upvalue
    when {
        name != null -> {
            while (upvalueNames.size < upvalues.size - 1) {
                upvalueNames += null
            }
            upvalueNames += name
        }

        upvalueNames.isNotEmpty() -> upvalueNames += null
    }
    return upvalues.size - 1
}

/** Appends a nested prototype and returns its index. */
fun LuaPrototype.addNestedPrototype(prototype: LuaPrototype): Int {
    nestedPrototypes += prototype
    return nestedPrototypes.size - 1
}

/** Strips every debug section of this prototype, like `luac -s`. */
fun LuaPrototype.clearDebugInfo() {
    source = null
    lineInfo.clear()
    absoluteLineInfo.clear()
    localVariables.clear()
    upvalueNames.clear()
}

/**
 * Replaces the local variable debug names with [rename] and the upvalue debug
 * names with [renameUpvalue], keeping every other debug section intact.
 *
 * Unlike [clearDebugInfo] this keeps `source`, `lineInfo` and the local pc
 * ranges: only the *names* — pure debug metadata the VM never reads — change,
 * so an unluac round trip still succeeds while the original identifiers
 * (`local gcd`, `local factor`, ...) disappear from the decompiled output.
 *
 * `upvalueNames` stays parallel to [LuaPrototype.upvalues]: entries renamed to
 * `null` are written as `""` by [io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter]
 * (the same thing the official `luac` writes for a nameless upvalue), so the
 * writer's size invariant holds.
 */
fun LuaPrototype.renameLocalAndUpvalueNames(
    rename: (String) -> String,
    renameUpvalue: (String) -> String,
) {
    for (index in localVariables.indices) {
        val variable = localVariables[index]
        val name = variable.name?.utf8 ?: continue
        localVariables[index] = variable.copy(name = LuaString.of(rename(name)))
    }
    for (index in upvalueNames.indices) {
        val name = upvalueNames[index]?.utf8 ?: continue
        upvalueNames[index] = LuaString.of(renameUpvalue(name))
    }
}

/** Strips every debug section of the whole chunk, like `luac -s`. */
fun LuaChunk.clearDebugInfo() {
    prototypes.forEach { it.clearDebugInfo() }
}

/**
 * Renames the local variable / upvalue debug names of the whole chunk.
 *
 * @see renameLocalAndUpvalueNames
 */
fun LuaChunk.renameLocalAndUpvalueNames(
    rename: (String) -> String,
    renameUpvalue: (String) -> String,
) {
    prototypes.forEach { it.renameLocalAndUpvalueNames(rename, renameUpvalue) }
}

/** Every string constant of the whole chunk, as `prototype to value` pairs. */
fun LuaChunk.stringConstants(): List<Pair<LuaPrototype, LuaString>> = buildList {
    prototypes.forEach { prototype ->
        prototype.forEachStringConstant { _, value -> add(prototype to value) }
    }
}

/**
 * Rewrites every string constant of every prototype through [transform].
 *
 * @return the number of rewritten constants.
 */
fun LuaChunk.replaceStringConstants(transform: (LuaPrototype, LuaString) -> LuaString?): Int {
    var replaced = 0
    prototypes.forEach { prototype ->
        replaced += prototype.replaceStringConstants { value -> transform(prototype, value) }
    }
    return replaced
}
