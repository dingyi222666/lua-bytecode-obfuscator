package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.*
import io.github.dingyi222666.luabytecode.bytecode.analysis.*
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaPrototypeScope

/** Transactions retain identities of existing prototypes, including callers' references. */
fun LuaPrototype.detachedCopy(): LuaPrototype = copy(
    code = code.toMutableList(), constants = constants.toMutableList(), upvalues = upvalues.toMutableList(),
    nestedPrototypes = nestedPrototypes.map { it.detachedCopy() }.toMutableList(),
    lineInfo = lineInfo.toMutableList(), absoluteLineInfo = absoluteLineInfo.toMutableList(),
    localVariables = localVariables.toMutableList(), upvalueNames = upvalueNames.toMutableList(),
)

fun LuaPrototype.commitFrom(other: LuaPrototype) {
    maxStackSize = other.maxStackSize
    fun <T> MutableList<T>.replace(values: List<T>) { clear(); addAll(values) }
    code.replace(other.code); constants.replace(other.constants); upvalues.replace(other.upvalues)
    lineInfo.replace(other.lineInfo); absoluteLineInfo.replace(other.absoluteLineInfo)
    localVariables.replace(other.localVariables); upvalueNames.replace(other.upvalueNames)
    for (i in nestedPrototypes.indices) nestedPrototypes[i].commitFrom(other.nestedPrototypes[i])
    nestedPrototypes.addAll(other.nestedPrototypes.drop(nestedPrototypes.size))
}

/** Runs a structural mutation through the public editing DSL. */
public inline fun LuaPrototype.edit(block: LuaPrototypeScope.() -> Unit) {
    LuaPrototypeScope(this).block()
}

/** Replaces code and constants atomically through the editing API. */
fun LuaPrototype.replaceCodeAndConstants(
    code: List<LuaInstruction>,
    constants: List<LuaConstant>,
) {
    edit {
        replaceCode(code)
        replaceConstants(constants)
    }
}

/** Appends emitted instructions through the DSL, preserving line tables. */
fun LuaPrototype.appendInstructions(instructions: List<LuaInstruction>) {
    edit { appendAll(instructions) }
}

/** Replaces one instruction through the DSL facade. */
fun LuaPrototype.patchInstructionAt(index: Int, instruction: LuaInstruction) {
    edit { set(index, instruction) }
}

/** Rewrites the constant pool through the DSL facade. */
fun LuaPrototype.replaceConstantPool(constants: List<LuaConstant>) {
    edit {
        replaceConstants(constants)
    }
}

fun LuaPrototype.addNested(prototype: LuaPrototype): Int =
    LuaPrototypeScope(this).addChild(prototype)

/** Preserve VARARGPREP at entry and all fetch pairs; initialize exactly once. */
fun installClosurePrologue(main: LuaPrototype, scratch: Int, slot: Int, child: Int) {
    val at = if (main.code.firstOrNull()?.opcodeName == "VARARGPREP") 1 else 0
    fun moved(pc: Int) = if (pc < at) pc else pc + 2
    val lines = main.resolvedLineInfo().toMutableList()
    val code = main.code.mapIndexed { pc, i ->
        i.jumpTarget(pc)?.let { i.retargeted(pc, moved(pc), moved(it)) } ?: i
    }.toMutableList()
    val prefix = LuaPrototypeScope(main).stream()
        .abx("CLOSURE", scratch, child)
        .setUpvalue(scratch, slot)
        .build()
    code.addAll(at, prefix)
    main.edit { replaceCode(code) }
    if (lines.isNotEmpty()) {
        lines.addAll(at, listOf(main.lineDefined, main.lineDefined)); main.setAbsoluteLines(lines)
    }
    main.localVariables.replaceAll { it.copy(startPc = moved(it.startPc), endPc = moved(it.endPc)) }
}
