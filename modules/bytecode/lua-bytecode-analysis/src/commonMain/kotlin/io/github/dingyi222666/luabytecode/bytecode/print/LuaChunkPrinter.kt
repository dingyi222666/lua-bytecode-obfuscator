package io.github.dingyi222666.luabytecode.bytecode.print

import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.platform.hex2

/**
 * Renders a parsed [LuaChunk] as readable text, comparable to `luac -l` output
 * but version aware and including the constant / upvalue tables.
 */
object LuaChunkPrinter {

    /**
     * @param includeCode when `false`, instructions are not listed.
     * @param includeDebug when `false`, line info / locals / upvalue names are hidden.
     */
    fun print(
        chunk: LuaChunk,
        includeCode: Boolean = true,
        includeDebug: Boolean = true,
    ): String {
        val builder = StringBuilder()
        builder.append(chunk.header).append('\n')
        builder.append("main closure upvalues: ").append(chunk.mainUpvalueCount).append('\n')
        builder.append("prototypes: ").append(chunk.prototypes.size).append('\n')

        val numberOf = HashMap<LuaPrototype, Int>()
        chunk.prototypes.forEachIndexed { index, prototype -> numberOf[prototype] = index }
        appendPrototype(builder, chunk.mainPrototype, numberOf, 0, includeCode, includeDebug)
        return builder.toString()
    }

    private fun appendPrototype(
        builder: StringBuilder,
        prototype: LuaPrototype,
        numberOf: Map<LuaPrototype, Int>,
        depth: Int,
        includeCode: Boolean,
        includeDebug: Boolean,
    ) {
        val indent = "  ".repeat(depth)
        builder.append('\n')
        builder.append(indent)
            .append("prototype #").append(numberOf[prototype] ?: -1)
            .append(' ').append(prototype.source?.utf8 ?: "?")
            .append(" <").append(prototype.lineDefined).append(',').append(prototype.lastLineDefined)
            .append("> params=").append(prototype.numParams)
            .append(" vararg=").append(prototype.isVararg)
            .append(" stack=").append(prototype.maxStackSize)
            .append(" upvalues=").append(prototype.upvalueCount)
            .append(" protos=").append(prototype.nestedPrototypeCount)
            .append('\n')

        if (includeCode) {
            builder.append(indent).append("  code (").append(prototype.instructionCount)
                .append(" instructions):\n")
            prototype.code.forEachIndexed { pc, instruction ->
                builder.append(indent).append("    [").append(pc.toString().padStart(4)).append("] ")
                    .append(instruction.opcodeName.padEnd(12))
                    .append(instruction.formatOperands())
                annotationFor(prototype, pc, instruction)?.let {
                    builder.append("  ; ").append(it)
                }
                if (includeDebug && prototype.hasLineInfo) {
                    val line = prototype.lineForPc(pc)
                    if (line >= 0) {
                        builder.append("  ; line ").append(line)
                    }
                }
                builder.append('\n')
            }
        }

        builder.append(indent).append("  constants (").append(prototype.constantCount).append("):\n")
        prototype.constants.forEachIndexed { index, constant ->
            builder.append(indent).append("    K").append(index).append("  ")
                .append(formatConstant(constant)).append('\n')
        }

        builder.append(indent).append("  upvalues (").append(prototype.upvalueCount).append("):\n")
        prototype.upvalues.forEachIndexed { index, upvalue ->
            builder.append(indent).append("    U").append(index).append("  ").append(upvalue)
            if (includeDebug) {
                val name = prototype.upvalueNames.getOrNull(index)
                if (name != null) {
                    builder.append("  name=").append(name.utf8)
                }
            }
            builder.append('\n')
        }

        if (includeDebug) {
            builder.append(indent).append("  locals (").append(prototype.localVariables.size).append("):\n")
            prototype.localVariables.forEachIndexed { index, local ->
                builder.append(indent).append("    L").append(index).append("  ")
                    .append(local.name?.utf8 ?: "?").append("  pc ")
                    .append(local.startPc).append("..").append(local.endPc).append('\n')
            }
        }

        builder.append(indent).append("  line info: ").append(prototype.lineInfo.size)
            .append(" entries, abs line info: ").append(prototype.absoluteLineInfo.size)
            .append(" anchors").append('\n')

        if (prototype.nestedPrototypes.isNotEmpty()) {
            builder.append(indent).append("  nested prototypes (")
                .append(prototype.nestedPrototypeCount).append("):\n")
            for (child in prototype.nestedPrototypes) {
                appendPrototype(builder, child, numberOf, depth + 2, includeCode, includeDebug)
            }
        }
    }

    /**
     * Adds the constant / upvalue / prototype a few operand-carrying opcodes
     * refer to, so the printed listing can be verified by eye.
     */
    private fun annotationFor(
        prototype: LuaPrototype,
        pc: Int,
        instruction: LuaInstruction,
    ): String? = when (instruction.opcodeName) {
        "LOADK" -> prototype.constants.getOrNull(instruction.bx)
            ?.let { "K${instruction.bx} = ${formatConstant(it)}" }

        "LOADKX" -> prototype.code.getOrNull(pc + 1)?.let { extra ->
            prototype.constants.getOrNull(extra.ax)
                ?.let { "K${extra.ax} = ${formatConstant(it)}" }
        }

        "GETUPVAL", "SETUPVAL", "GETTABUP" -> prototype.upvalues.getOrNull(instruction.b)
            ?.let { "U${instruction.b}: $it" }

        "SETTABUP" -> prototype.upvalues.getOrNull(instruction.a)
            ?.let { "U${instruction.a}: $it" }

        "CLOSURE" -> prototype.nestedPrototypes.getOrNull(instruction.bx)
            ?.let { "proto #${it.lineDefined}" }

        else -> null
    }

    /** Formats a constant, truncating long strings. */
    fun formatConstant(constant: LuaConstant): String = when (constant) {
        LuaConstant.Nil -> "nil"
        is LuaConstant.Boolean -> constant.value.toString()
        is LuaConstant.Integer -> constant.value.toString()
        is LuaConstant.Number -> constant.value.toString()
        is LuaConstant.Str -> "\"${escape(constant.value.utf8)}\" (len=${constant.value.size})"
    }

    /** Escapes control characters, keeping printable UTF-8 text readable. */
    fun escape(text: String, maxLength: Int = 60): String {
        val builder = StringBuilder()
        var truncated = false
        for (char in text) {
            if (builder.length >= maxLength) {
                truncated = true
                break
            }
            when {
                char == '\\' -> builder.append("\\\\")
                char == '"' -> builder.append("\\\"")
                char == '\n' -> builder.append("\\n")
                char == '\r' -> builder.append("\\r")
                char == '\t' -> builder.append("\\t")
                char.code < 0x20 || char.code == 0x7F -> builder.append("\\x" + hex2(char.code))
                else -> builder.append(char)
            }
        }
        if (truncated) {
            builder.append("...")
        }
        return builder.toString()
    }
}
