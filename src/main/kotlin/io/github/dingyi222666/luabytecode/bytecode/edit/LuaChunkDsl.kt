package io.github.dingyi222666.luabytecode.bytecode.edit

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaInstructionBuilder
import io.github.dingyi222666.luabytecode.bytecode.LuaLocalVariable
import io.github.dingyi222666.luabytecode.bytecode.LuaOpcodes
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.LuaString
import io.github.dingyi222666.luabytecode.bytecode.LuaUpvalue
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import io.github.dingyi222666.luabytecode.bytecode.luaInstruction
import io.github.dingyi222666.luabytecode.bytecode.analysis.setAbsoluteLines
import io.github.dingyi222666.luabytecode.bytecode.analysis.retargeted

/**
 * A small DSL around the [LuaPrototype] editing API.
 *
 * ```kotlin
 * val chunk = LuaChunkLoader.load(File("script.luac"))
 *
 * chunk.edit {
 *     // every prototype, main chunk first
 *     eachPrototype {
 *         replaceStringConstants { value -> value.replacing("secret", obfuscate(value)) }
 *     }
 *
 *     main {
 *         patch(3) { it.withA(0) }
 *         insert(4, instruction { opcode = opcode("MOVE"); a = 1; b = 2 }, line = 3)
 *     }
 * }
 *
 * LuaChunkWriter.dump(chunk, File("script.patched.luac"))
 * ```
 *
 * Or as a one shot load → edit → dump:
 *
 * ```kotlin
 * editChunkFile(File("in.luac"), File("out.luac")) {
 *     eachPrototype { strip() }        // like luac -s
 * }
 * ```
 */
class LuaChunkScope(val chunk: LuaChunk) {

    val version: LuaBytecodeVersion
        get() = chunk.version

    /** The main prototype and every nested one, in pre-order. */
    val prototypes: List<LuaPrototype>
        get() = chunk.prototypes

    /** The scope of the main prototype. */
    val main: LuaPrototypeScope
        get() = LuaPrototypeScope(chunk.mainPrototype)

    /** Edits the main prototype. */
    fun main(block: LuaPrototypeScope.() -> Unit) {
        main.block()
    }

    /** The scope of the prototype at pre-order [index]. */
    fun prototype(index: Int): LuaPrototypeScope = LuaPrototypeScope(chunk.prototypes[index])

    /** Edits the prototype at pre-order [index]. */
    fun prototype(index: Int, block: LuaPrototypeScope.() -> Unit) {
        prototype(index).block()
    }

    /** Edits every prototype, main chunk first. */
    fun eachPrototype(block: LuaPrototypeScope.() -> Unit) {
        chunk.prototypes.forEach { LuaPrototypeScope(it).block() }
    }

    /** Edits every nested prototype (skipping the main chunk). */
    fun eachNestedPrototype(block: LuaPrototypeScope.() -> Unit) {
        chunk.prototypes.drop(1).forEach { LuaPrototypeScope(it).block() }
    }

    /** Finds the first prototype matching [predicate] and edits it. */
    fun prototypeWhere(predicate: (LuaPrototype) -> Boolean, block: LuaPrototypeScope.() -> Unit): Boolean {
        val found = chunk.prototypes.firstOrNull(predicate) ?: return false
        LuaPrototypeScope(found).block()
        return true
    }

    /** Rewrites every string constant of every prototype. */
    fun replaceStringConstants(transform: (LuaPrototype, LuaString) -> LuaString?): Int =
        chunk.replaceStringConstants(transform)

    /** Strips all debug information of the chunk, like `luac -s`. */
    fun strip() = chunk.clearDebugInfo()
}

/** The scope of a single [LuaPrototype]. */
class LuaPrototypeScope(val prototype: LuaPrototype) {

    val version: LuaBytecodeVersion
        get() = prototype.version

    val code: MutableList<LuaInstruction>
        get() = prototype.code

    val constants: MutableList<LuaConstant>
        get() = prototype.constants

    val upvalues: MutableList<LuaUpvalue>
        get() = prototype.upvalues

    val children: MutableList<LuaPrototype>
        get() = prototype.nestedPrototypes

    val locals: MutableList<LuaLocalVariable>
        get() = prototype.localVariables

    val lineInfo: MutableList<Int>
        get() = prototype.lineInfo

    /** Instruction count. */
    val instructionCount: Int
        get() = prototype.instructionCount

    /** Constant count. */
    val constantCount: Int
        get() = prototype.constantCount

    // ------------------------------------------------------------ constants

    /** Interns the UTF-8 bytes of [text] as a constant and returns its index. */
    fun internString(text: String): Int = prototype.internString(text)

    /** Interns [value] as a constant and returns its index. */
    fun internString(value: LuaString): Int = prototype.internString(value)

    /** Interns [constant] and returns its index. */
    fun internConstant(constant: LuaConstant): Int = prototype.internConstant(constant)

    /** Index of the first constant matching [predicate], or `-1`. */
    fun locate(predicate: (LuaConstant) -> Boolean): Int = prototype.indexOfConstant(predicate)

    /** Index of the string constant whose UTF-8 text is [text], or `-1`. */
    fun locateString(text: String): Int = prototype.indexOfStringConstant(text)

    /** All string constants as `index to value` pairs. */
    fun stringConstants(): List<Pair<Int, LuaString>> = prototype.stringConstants()

    /** Rewrites every string constant; `null` keeps the original. */
    fun replaceStringConstants(transform: (LuaString) -> LuaString?): Int =
        prototype.replaceStringConstants(transform)

    /** Replaces the UTF-8 text [from] with [to] inside every string constant. */
    fun replaceStringText(from: String, to: String): Int =
        prototype.replaceStringConstants { it.replacing(from, to) }

    /** Replaces the constant at [index]. */
    fun setConstant(index: Int, constant: LuaConstant) {
        constants[index] = constant
    }

    // --------------------------------------------------------- instructions

    /** The opcode number of [name] in this prototype's version. */
    fun opcode(name: String): Int = LuaOpcodes.opcodeOf(version, name)
        ?: throw IllegalArgumentException("no opcode named $name in ${version.displayName}")

    /** The instruction at [index]. */
    fun instruction(index: Int): LuaInstruction = code[index]

    /** Builds an instruction with the prototype's version. */
    fun instruction(block: LuaInstructionBuilder.() -> Unit): LuaInstruction =
        luaInstruction(version, block)

    /** Named instruction constructors for pass code that emits one instruction. */
    fun abc(name: String, a: Int = 0, b: Int = 0, c: Int = 0, k: Int = 0): LuaInstruction =
        LuaInstructionFactory.abc(version, name, a, b, c, k)

    fun abx(name: String, a: Int = 0, bx: Int = 0): LuaInstruction =
        LuaInstructionFactory.abx(version, name, a, bx)

    fun asbx(name: String, a: Int = 0, sbx: Int = 0): LuaInstruction =
        LuaInstructionFactory.asbx(version, name, a, sbx)

    fun ax(name: String, ax: Int = 0): LuaInstruction =
        LuaInstructionFactory.ax(version, name, ax)

    fun sj(name: String, sj: Int = 0): LuaInstruction =
        LuaInstructionFactory.sj(version, name, sj)

    /** Creates a version-aware instruction stream builder. */
    fun stream(): LuaInstructionStream = LuaInstructionStream(version)

    /** Replaces the instruction at [index]. */
    fun set(index: Int, instruction: LuaInstruction) = prototype.setInstruction(index, instruction)

    /** Replaces the whole instruction stream through the edit API. */
    fun replaceCode(instructions: List<LuaInstruction>, lines: List<Int>? = null) {
        if (code.isEmpty()) {
            code.addAll(instructions)
            if (lines != null) prototype.setAbsoluteLines(lines.toMutableList())
        } else {
            prototype.replaceInstructions(0 until code.lastIndex + 1, instructions, lines)
        }
    }

    /** Appends a block of instructions while keeping debug metadata aligned. */
    fun appendAll(instructions: List<LuaInstruction>, lines: List<Int>? = null) =
        prototype.insertInstructions(code.size, instructions, lines)

    /** Applies an indexed rewrite to every instruction in this prototype. */
    fun rewriteCode(transform: (index: Int, instruction: LuaInstruction) -> LuaInstruction) =
        prototype.transformInstructions(transform)

    /** Applies a safe rewrite to every constant in this prototype. */
    fun rewriteConstants(transform: (index: Int, constant: LuaConstant) -> LuaConstant) {
        for (index in constants.indices) constants[index] = transform(index, constants[index])
    }

    /** Adds a constant without exposing the backing list to a pass. */
    fun addConstant(constant: LuaConstant): Int {
        constants += constant
        return constants.lastIndex
    }

    /** Replaces the complete constant pool through the editor boundary. */
    fun replaceConstants(values: List<LuaConstant>) {
        constants.clear()
        constants.addAll(values)
    }

    /** Replaces the instruction at [index] with the result of [patch]. */
    fun patch(index: Int, patch: (LuaInstruction) -> LuaInstruction) =
        prototype.patchInstruction(index, patch)

    /** Retargets a control transfer while preserving version-specific operands. */
    fun retarget(index: Int, target: Int) =
        prototype.patchInstruction(index) { it.retargeted(index, index, target) }

    /** Patches every instruction through the editor, including version-aware register rewrites. */
    fun rewrite(transform: (LuaInstruction) -> LuaInstruction) =
        prototype.transformInstructions { _, instruction -> transform(instruction) }

    /** Patches one instruction with a version-aware transformation. */
    fun patchAt(index: Int, transform: (LuaInstruction) -> LuaInstruction) =
        prototype.patchInstruction(index, transform)

    /** Inserts [instruction] before [index]. */
    fun insert(index: Int, instruction: LuaInstruction, line: Int? = null) =
        prototype.insertInstruction(index, instruction, line)

    /** Appends [instruction] at the end of the code. */
    fun append(instruction: LuaInstruction, line: Int? = null) =
        prototype.appendInstruction(instruction, line)

    /** Removes the instruction at [index]. */
    fun remove(index: Int): LuaInstruction = prototype.removeInstruction(index)

    /** Removes the instructions of [range] (inclusive). */
    fun remove(range: IntRange): Int = prototype.removeInstructions(range)

    /** Replaces the instructions of [range] (inclusive). */
    fun replace(range: IntRange, instructions: List<LuaInstruction>, lines: List<Int>? = null) =
        prototype.replaceInstructions(range, instructions, lines)

    /** Rewrites every instruction. */
    fun transformInstructions(transform: (index: Int, instruction: LuaInstruction) -> LuaInstruction) =
        prototype.transformInstructions(transform)

    // ---------------------------------------------------------------- misc

    /** The scope of the nested prototype at [index]. */
    fun child(index: Int): LuaPrototypeScope = LuaPrototypeScope(children[index])

    /** Edits the nested prototype at [index]. */
    fun child(index: Int, block: LuaPrototypeScope.() -> Unit) {
        child(index).block()
    }

    /** Edits every directly nested prototype. */
    fun eachChild(block: LuaPrototypeScope.() -> Unit) {
        children.forEach { LuaPrototypeScope(it).block() }
    }

    /** Appends an upvalue (and its optional debug name) and returns its index. */
    fun addUpvalue(upvalue: LuaUpvalue, name: LuaString? = null): Int =
        prototype.addUpvalue(upvalue, name)

    /** Appends a nested prototype and returns its index. */
    fun addChild(prototype: LuaPrototype): Int = this.prototype.addNestedPrototype(prototype)

    /** Strips every debug section of this prototype. */
    fun strip() = prototype.clearDebugInfo()

    /** True when this prototype still carries debug information. */
    val hasDebugInfo: Boolean
        get() = prototype.hasLineInfo || prototype.localVariables.isNotEmpty()
}

/**
 * Fluent instruction assembler used by transformation passes. Jumps are
 * emitted as labels and resolved after the stream is complete, so callers do
 * not calculate relative offsets or version-specific fields themselves.
 */
class LuaInstructionStream internal constructor(private val version: LuaBytecodeVersion) {
    private val instructions = ArrayList<LuaInstruction>()
    private val labels = LinkedHashMap<String, Int>()
    private data class PendingJump(val at: Int, val label: String, val opcode: String)
    private val pending = ArrayList<PendingJump>()

    val size: Int get() = instructions.size

    fun mark(label: String): LuaInstructionStream = apply {
        require(labels.put(label, instructions.size) == null) { "duplicate instruction label $label" }
    }

    fun emit(instruction: LuaInstruction): LuaInstructionStream = apply { instructions += instruction }

    fun emit(block: LuaInstructionBuilder.() -> Unit): LuaInstructionStream =
        apply { instructions += luaInstruction(version, block) }

    fun abc(opcode: String, a: Int = 0, b: Int = 0, c: Int = 0, k: Int = 0): LuaInstructionStream =
        emit { this.opcode = opcode(opcode); this.a = a; this.b = b; this.c = c; this.k = k }

    fun abx(opcode: String, a: Int = 0, bx: Int = 0): LuaInstructionStream =
        emit { this.opcode = opcode(opcode); this.a = a; this.bx = bx }

    fun asbx(opcode: String, a: Int = 0, sbx: Int = 0): LuaInstructionStream =
        emit { this.opcode = opcode(opcode); this.a = a; this.sbx = sbx }

    fun ax(opcode: String, ax: Int = 0): LuaInstructionStream =
        emit { this.opcode = opcode(opcode); this.ax = ax }

    fun sj(opcode: String, sj: Int = 0): LuaInstructionStream =
        emit { this.opcode = opcode(opcode); this.sj = sj }

    fun move(destination: Int, source: Int): LuaInstructionStream = abc("MOVE", destination, source)
    fun loadK(register: Int, constant: Int): LuaInstructionStream = abx("LOADK", register, constant)
    fun loadKX(register: Int, constant: Int): LuaInstructionStream =
        abx("LOADKX", register, 0).ax("EXTRAARG", constant)
    fun call(base: Int, arguments: Int, results: Int): LuaInstructionStream = abc("CALL", base, arguments, results)
    fun getUpvalue(destination: Int, upvalue: Int): LuaInstructionStream = abc("GETUPVAL", destination, upvalue)
    fun setUpvalue(source: Int, upvalue: Int): LuaInstructionStream = abc("SETUPVAL", source, upvalue)
    fun test(register: Int, polarity: Int = 0): LuaInstructionStream =
        if (version == LuaBytecodeVersion.LUA_5_3) abc("TEST", register, 0, polarity)
        else abc("TEST", register, 0, 0, polarity)
    fun eq(left: Int, right: Int, polarity: Int = 1): LuaInstructionStream =
        if (version == LuaBytecodeVersion.LUA_5_3) abc("EQ", polarity, left, right)
        else abc("EQ", left, right, 0, polarity)
    fun arithmetic(name: String, destination: Int, left: Int, right: Int): LuaInstructionStream = apply {
        abc(name, destination, left, right)
        val event = mapOf("ADD" to 6, "SUB" to 7, "MUL" to 8, "MOD" to 9, "POW" to 10, "DIV" to 11,
            "IDIV" to 12, "BAND" to 13, "BOR" to 14, "BXOR" to 15, "SHL" to 16, "SHR" to 17)[name]
        if (event != null && version != LuaBytecodeVersion.LUA_5_3) abc("MMBIN", left, right, event)
    }

    fun getTable(destination: Int, table: Int, key: Int): LuaInstructionStream = abc("GETTABLE", destination, table, key)
    fun setTable(table: Int, key: Int, value: Int): LuaInstructionStream = abc("SETTABLE", table, key, value)
    fun len(destination: Int, source: Int): LuaInstructionStream = abc("LEN", destination, source)
    fun close(register: Int): LuaInstructionStream = abc("CLOSE", register)

    private fun opcode(name: String): Int = LuaOpcodes.opcodeOf(version, name)
        ?: throw IllegalArgumentException("no opcode named $name in ${version.displayName}")

    fun jump(label: String): LuaInstructionStream = apply {
        pending += PendingJump(instructions.size, label, "JMP")
        instructions += LuaInstructionFactory.of(version, "JMP")
    }

    /** Emits a relocatable control-transfer instruction.
     * Only opcodes whose target is encoded by the instruction itself are accepted.
     * Conditional TEST/EQ instructions deliberately stay separate: their target is
     * the following JMP and cannot be safely retargeted as a raw instruction.
     */
    fun branch(opcode: String, label: String, configure: LuaInstructionBuilder.() -> Unit = {}): LuaInstructionStream =
        apply {
            require(opcode in setOf("JMP", "FORLOOP", "FORPREP", "TFORLOOP", "TFORPREP")) {
                "opcode $opcode does not carry a relocatable label target"
            }
            val at = instructions.size
            instructions += luaInstruction(version) {
                this.opcode = LuaOpcodes.opcodeOf(version, opcode)
                    ?: throw IllegalArgumentException("no opcode named $opcode in ${version.displayName}")
                configure()
            }
            pending += PendingJump(at, label, opcode)
        }

    fun build(): List<LuaInstruction> {
        pending.forEach { jump ->
            val target = labels[jump.label] ?: error("unresolved instruction label ${jump.label}")
            instructions[jump.at] = instructions[jump.at].retargeted(jump.at, jump.at, target)
        }
        return instructions.toList()
    }
}

/** Edits [chunk] in place and returns it. */
fun LuaChunk.edit(block: LuaChunkScope.() -> Unit): LuaChunk {
    LuaChunkScope(this).block()
    return this
}

/**
 * Starts a fluent edit stream for [LuaChunk]. The stream is deliberately
 * small: every operation is still expressed in terms of [LuaChunkScope], so
 * debug tables, local ranges and version-specific instruction builders keep
 * one implementation. Call [LuaChunkEditor.commit] at the end of a chain.
 *
 * ```kotlin
 * chunk.editing()
 *     .eachPrototype { strip() }
 *     .main { patch(0) { it.withA(0) } }
 *     .then { replaceStringConstants { _, value -> value.replacing("x", "y") } }
 *     .commit()
 * ```
 */
class LuaChunkEditor internal constructor(private val chunk: LuaChunk) {
    private val scope = LuaChunkScope(chunk)

    fun main(block: LuaPrototypeScope.() -> Unit): LuaChunkEditor = apply { scope.main(block) }

    fun prototype(index: Int, block: LuaPrototypeScope.() -> Unit): LuaChunkEditor =
        apply { scope.prototype(index, block) }

    fun eachPrototype(block: LuaPrototypeScope.() -> Unit): LuaChunkEditor =
        apply { scope.eachPrototype(block) }

    fun eachNestedPrototype(block: LuaPrototypeScope.() -> Unit): LuaChunkEditor =
        apply { scope.eachNestedPrototype(block) }

    fun where(
        predicate: (LuaPrototype) -> Boolean,
        block: LuaPrototypeScope.() -> Unit,
    ): LuaChunkEditor = apply { scope.prototypeWhere(predicate, block) }

    fun replaceStringConstants(transform: (LuaPrototype, LuaString) -> LuaString?): LuaChunkEditor =
        apply { scope.replaceStringConstants(transform) }

    fun strip(): LuaChunkEditor = apply { scope.strip() }

    /** Adds an arbitrary chunk-level edit while keeping the fluent chain. */
    fun then(block: LuaChunkScope.() -> Unit): LuaChunkEditor = apply { scope.block() }

    fun commit(): LuaChunk = chunk
}

/** Starts a fluent, in-place bytecode edit stream. */
fun LuaChunk.editing(): LuaChunkEditor = LuaChunkEditor(this)

/**
 * `editChunkFile(input, output) { ... }` — the file based entry point — moved
 * to `io.github.dingyi222666.luabytecode.platform.jvm.editChunkFile` so this
 * module stays free of `java.io`; same signature and call syntax.
 */
