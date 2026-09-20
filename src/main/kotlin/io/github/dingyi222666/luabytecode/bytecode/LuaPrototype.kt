package io.github.dingyi222666.luabytecode.bytecode

import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter

/**
 * Upvalue descriptor stored in a prototype (`Proto.upvalues`).
 *
 * 5.3 writes `instack` + `idx` only: the parent prototype describes where the
 * upvalue lives for its children. 5.4 and 5.5 keep `instack` + `idx` + `kind`
 * in the prototype and resolve the upvalue when the closure is created.
 */
data class LuaUpvalue(
    /** `true` when the upvalue refers to a register of the enclosing function. */
    val inStack: Boolean,
    /** Register index (when [inStack]) or index in the parent upvalue list. */
    val index: Int,
    /** 5.4 / 5.5 upvalue kind, `null` for 5.3. */
    val kind: Int?,
) {
    /** Name of [kind], or `null` when absent / unknown. */
    val kindName: String?
        get() = kind?.let { LuaUpvalueKind.nameOf(it) }

    override fun toString(): String = buildString {
        append(if (inStack) "instack" else "inparent").append(" idx=").append(index)
        if (kind != null) {
            append(" kind=").append(kindName ?: kind.toString())
        }
    }
}

/** The 5.4 / 5.5 upvalue kinds (`VDKREG`, `RDKCONST`, `RDKTOCLOSE`, `RDKCTC`). */
enum class LuaUpvalueKind(val value: Int, val kindName: String) {
    REGISTER(0, "REGISTER"),
    CONSTANT(1, "CONSTANT"),
    TO_CLOSE(2, "TO_CLOSE"),
    CTC(3, "CTC"),
    ;

    companion object {
        @JvmStatic
        fun nameOf(value: Int): String? = entries.firstOrNull { it.value == value }?.kindName
    }
}

/** A local variable debug entry (`LocVar`), only present in non-stripped chunks. */
data class LuaLocalVariable(
    val name: LuaString?,
    val startPc: Int,
    val endPc: Int,
)

/** A 5.4 / 5.5 absolute line info entry (`AbsLineInfo`). */
data class LuaAbsoluteLineInfo(
    val pc: Int,
    val line: Int,
)

/**
 * One function prototype of a chunk (`Proto`), including all nested prototypes.
 *
 * A chunk always contains at least the main prototype; nested functions are
 * attached through [nestedPrototypes].
 *
 * The tables are [MutableList]s, so a loaded chunk can be edited in place and
 * written back with [LuaChunkWriter]:
 *
 * ```kotlin
 * val chunk = LuaChunkLoader.load(File("script.luac"))
 * val constants = chunk.mainPrototype.constants
 * val index = constants.indexOfFirst { it is LuaConstant.Str && it.value.utf8 == "secret" }
 * constants[index] = LuaConstant.Str(LuaString.of("obfuscated"))
 * LuaChunkWriter.dump(chunk, File("script.obf.luac"))
 * ```
 *
 * Keep the tables consistent: `lineInfo` must have one entry per instruction
 * whenever it is not empty, and `upvalueNames` must be either empty or as long
 * as [upvalues]. [LuaChunkWriter] rejects inconsistent trees instead of writing
 * a chunk Lua cannot load.
 */
data class LuaPrototype(
    /** Version of the chunk this prototype belongs to. */
    val version: LuaBytecodeVersion,
    /** Source name (`@file.lua`, `=stdin`, ...); `null` for a stripped function. */
    var source: LuaString?,
    /** First line of the function definition (0 for the main chunk). */
    var lineDefined: Int,
    /** Last line of the function definition (0 for the main chunk). */
    var lastLineDefined: Int,
    /** Number of fixed parameters. */
    var numParams: Int,
    /** `true` when the function is variadic (`...`). */
    var isVararg: Boolean,
    /** Raw flag byte: a boolean in 5.3/5.4, `PF_*` bits in 5.5. */
    var isVarargFlags: Int,
    /** Maximum number of stack slots. */
    var maxStackSize: Int,
    /** Raw 32 bit instructions. */
    val code: MutableList<LuaInstruction>,
    /** Constant table. */
    val constants: MutableList<LuaConstant>,
    /** Upvalue descriptors. */
    val upvalues: MutableList<LuaUpvalue>,
    /** Nested prototypes (functions defined inside this one). */
    val nestedPrototypes: MutableList<LuaPrototype>,
    /** Line info: absolute lines in 5.3, 1 byte relative deltas in 5.4 / 5.5. */
    val lineInfo: MutableList<Int>,
    /** 5.4 / 5.5 absolute line info anchors; always empty for 5.3. */
    val absoluteLineInfo: MutableList<LuaAbsoluteLineInfo>,
    /** Local variable debug entries. */
    val localVariables: MutableList<LuaLocalVariable>,
    /** Upvalue names from the debug section (parallel to [upvalues]). */
    val upvalueNames: MutableList<LuaString?>,
) {

    val instructionCount: Int
        get() = code.size

    val constantCount: Int
        get() = constants.size

    val upvalueCount: Int
        get() = upvalues.size

    val nestedPrototypeCount: Int
        get() = nestedPrototypes.size

    /** `true` when the chunk was compiled without `luac -s`. */
    val hasLineInfo: Boolean
        get() = lineInfo.isNotEmpty()

    /**
     * Resolves the source line of the instruction at [pc], following
     * `luaG_getfuncline` of the matching version.
     *
     * @return the line number, or `-1` when the chunk has no line info.
     */
    fun lineForPc(pc: Int): Int {
        if (lineInfo.isEmpty() || pc < 0) {
            return -1
        }
        return when (version) {
            LuaBytecodeVersion.LUA_5_3 ->
                if (pc < lineInfo.size) lineInfo[pc] else lineInfo.last()

            LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> {
                if (pc >= lineInfo.size) {
                    return -1
                }
                var basePc: Int
                var baseline: Int
                if (absoluteLineInfo.isEmpty() || pc < absoluteLineInfo[0].pc) {
                    basePc = -1
                    baseline = lineDefined
                } else {
                    // Same estimate as `getbaseline`: it must stay a lower bound.
                    // A chunk whose anchors are too sparse (non canonical writer)
                    // must not index past the list, so clamp and fall back.
                    var i = pc / MAX_INSTRUCTIONS_WITHOUT_ABS_LINE_INFO - 1
                    if (i >= absoluteLineInfo.size) {
                        i = absoluteLineInfo.size - 1
                    }
                    while (i + 1 < absoluteLineInfo.size && absoluteLineInfo[i + 1].pc <= pc) {
                        i++
                    }
                    if (i < 0 || absoluteLineInfo[i].pc > pc) {
                        basePc = -1
                        baseline = lineDefined
                    } else {
                        basePc = absoluteLineInfo[i].pc
                        baseline = absoluteLineInfo[i].line
                    }
                }
                // `luaG_getfuncline` walks `while (basepc++ < pc)`, so it adds
                // `lineInfo[basePc + 1] .. lineInfo[pc]` (inclusive).
                var index = basePc
                while (index < pc) {
                    index++
                    baseline += lineInfo[index]
                }
                baseline
            }
        }
    }

    /** Absolute line of every instruction (empty when the chunk is stripped). */
    fun resolvedLineInfo(): List<Int> {
        if (lineInfo.isEmpty()) {
            return emptyList()
        }
        return List(lineInfo.size) { lineForPc(it) }
    }

    /** This prototype followed by all nested prototypes, in pre-order. */
    fun allPrototypes(): List<LuaPrototype> {
        val result = ArrayList<LuaPrototype>()
        fun visit(prototype: LuaPrototype) {
            result += prototype
            for (child in prototype.nestedPrototypes) {
                visit(child)
            }
        }
        visit(this)
        return result
    }

    override fun toString(): String = buildString {
        append("LuaPrototype(source=").append(source?.utf8)
        append(", lines=").append(lineDefined).append('-').append(lastLineDefined)
        append(", params=").append(numParams)
        append(", vararg=").append(isVararg)
        append(", code=").append(code.size)
        append(", constants=").append(constants.size)
        append(", upvalues=").append(upvalues.size)
        append(", protos=").append(nestedPrototypes.size)
        append(')')
    }

    private companion object {
        /** `MAXIWTHABS` from 5.4 / 5.5 `ldebug.c`. */
        const val MAX_INSTRUCTIONS_WITHOUT_ABS_LINE_INFO = 128
    }
}
