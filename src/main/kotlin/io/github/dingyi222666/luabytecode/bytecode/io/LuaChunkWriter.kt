package io.github.dingyi222666.luabytecode.bytecode.io

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.LuaString
import io.github.dingyi222666.luabytecode.bytecode.LuaVarintStyle
import io.github.dingyi222666.luabytecode.bytecode.edit.edit
import io.github.dingyi222666.luabytecode.bytecode.edit.replacing

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteOrder


/** `LUA_SIGNATURE` — `ESC "Lua"`. */
private val DUMP_SIGNATURE = byteArrayOf(0x1B, 'L'.code.toByte(), 'u'.code.toByte(), 'a'.code.toByte())

/** `LUAC_DATA`. */
private val DUMP_LUAC_DATA = byteArrayOf(0x19, 0x93.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

private const val DUMP_LUAC_FORMAT = 0
private const val DUMP_LUAC_INT_5_3 = 0x5678L
private const val DUMP_LUAC_NUM_5_3 = 370.5
private const val DUMP_LUAC_INT_5_5 = -0x5678L
private const val DUMP_LUAC_INST_5_5 = 0x12345678L
private const val DUMP_LUAC_NUM_5_5 = -370.5

/** `LUAI_MAXSHORTLEN`: strings of at most this many bytes are tagged `SHRSTR`. */
private const val MAX_SHORT_STRING_LENGTH = 40

/**
 * Serializes a [LuaChunk] back into the luac binary format — the counterpart of
 * [LuaChunkLoader] — so a chunk can be loaded, edited and dumped again:
 *
 * ```kotlin
 * val chunk = LuaChunkLoader.load(File("script.luac"))
 *
 * // edit: rename every occurrence of a string constant
 * for (prototype in chunk.prototypes) {
 *     prototype.constants.replaceAll { constant ->
 *         if (constant is LuaConstant.Str && constant.value.utf8 == "secret") {
 *             LuaConstant.Str(LuaString.of("obfuscated"))
 *         } else {
 *             constant
 *         }
 *     }
 * }
 *
 * LuaChunkWriter.dump(chunk, File("script.obfuscated.luac"))
 * ```
 *
 * The writer follows `ldump.c` of the chunk version, which makes the dump
 * canonical: dumping an unmodified chunk reproduces the original file byte for
 * byte (see the round trip tests) — same header probes, canonical varints, the
 * same 5.5 string reuse table and the same 5.5 alignment padding.
 *
 * Inconsistent trees are rejected with [LuaBytecodeFormatException] instead of
 * producing a chunk Lua cannot load:
 *  * `lineInfo` must be empty or have exactly one entry per instruction;
 *  * `absoluteLineInfo` must be empty for 5.3;
 *  * `upvalueNames` must be empty or as long as `upvalues`.
 */
object LuaChunkWriter {

    /** Dumps a chunk into a fresh byte array. */
    @JvmStatic
    fun dump(chunk: LuaChunk): ByteArray {
        val output = ByteArrayOutputStream()
        dump(chunk, output)
        return output.toByteArray()
    }

    /** Dumps a chunk to a file, replacing its content. */
    @JvmStatic
    fun dump(chunk: LuaChunk, file: File) {
        file.writeBytes(dump(chunk))
    }

    /** Dumps a chunk to a stream (the stream is flushed, not closed). */
    @JvmStatic
    fun dump(chunk: LuaChunk, output: OutputStream) {
        ChunkDumper(chunk, output).dump()
    }
}

private class ChunkDumper(
    private val chunk: LuaChunk,
    private val output: OutputStream,
) {

    private val version = chunk.header.version
    private val byteOrder: ByteOrder = chunk.header.byteOrder
    private val varintStyle = version.varintStyle

    /** 5.5 string reuse table: content -> 1 based index. */
    private val reusedStrings = HashMap<LuaString, Int>()
    private var reusedStringCount = 0

    /** Number of bytes written so far, needed for the 5.5 alignment padding. */
    private var offset = 0

    fun dump() {
        writeHeader()
        writeByte(chunk.mainPrototype.upvalues.size)
        writePrototype(chunk.mainPrototype, null)
        output.flush()
    }

    // ------------------------------------------------------------------ header

    private fun writeHeader() {
        writeBytes(DUMP_SIGNATURE)
        writeByte(version.versionByte)
        writeByte(DUMP_LUAC_FORMAT)
        writeBytes(DUMP_LUAC_DATA)
        when (version) {
            LuaBytecodeVersion.LUA_5_3 -> {
                writeByte(chunk.header.intSize ?: 4)
                writeByte(chunk.header.sizeTSize ?: 8)
                writeByte(chunk.header.instructionSize)
                writeByte(chunk.header.integerSize)
                writeByte(chunk.header.numberSize)
                writeSigned(DUMP_LUAC_INT_5_3, chunk.header.integerSize)
                writeNumber(DUMP_LUAC_NUM_5_3, chunk.header.numberSize)
            }

            LuaBytecodeVersion.LUA_5_4 -> {
                writeByte(chunk.header.instructionSize)
                writeByte(chunk.header.integerSize)
                writeByte(chunk.header.numberSize)
                writeSigned(DUMP_LUAC_INT_5_3, chunk.header.integerSize)
                writeNumber(DUMP_LUAC_NUM_5_3, chunk.header.numberSize)
            }

            LuaBytecodeVersion.LUA_5_5 -> {
                val intSize = chunk.header.intSize ?: 4
                writeByte(intSize)
                writeSigned(DUMP_LUAC_INT_5_5, intSize)
                writeByte(chunk.header.instructionSize)
                writeSigned(DUMP_LUAC_INST_5_5, chunk.header.instructionSize)
                writeByte(chunk.header.integerSize)
                writeSigned(DUMP_LUAC_INT_5_5, chunk.header.integerSize)
                writeByte(chunk.header.numberSize)
                writeNumber(DUMP_LUAC_NUM_5_5, chunk.header.numberSize)
            }
        }
    }

    // -------------------------------------------------------------- prototypes

    private fun writePrototype(prototype: LuaPrototype, parentSource: LuaString?) {
        val sourceFirst = !version.sourceAfterPrototypes
        if (sourceFirst) {
            // The C writer stores NULL when the child shares its parent's source.
            writeString(if (prototype.source == parentSource) null else prototype.source)
        }

        writeInt(prototype.lineDefined)
        writeInt(prototype.lastLineDefined)
        writeByte(prototype.numParams)
        writeByte(prototype.isVarargFlags)
        writeByte(prototype.maxStackSize)

        writeCode(prototype)
        writeConstants(prototype)
        writeUpvalues(prototype)
        writeNestedPrototypes(prototype)

        if (!sourceFirst) {
            writeString(prototype.source)
        }

        writeDebug(prototype)
    }

    private fun writeCode(prototype: LuaPrototype) {
        writeInt(prototype.code.size)
        if (version == LuaBytecodeVersion.LUA_5_5) {
            align(4)
        }
        for (instruction in prototype.code) {
            writeSigned(instruction.raw.toLong() and 0xFFFFFFFFL, chunk.header.instructionSize)
        }
    }

    private fun writeConstants(prototype: LuaPrototype) {
        writeInt(prototype.constants.size)
        for (constant in prototype.constants) {
            when (constant) {
                LuaConstant.Nil -> writeByte(0x00)

                is LuaConstant.Boolean -> when (version) {
                    LuaBytecodeVersion.LUA_5_3 -> {
                        writeByte(0x01)
                        writeByte(if (constant.value) 1 else 0)
                    }

                    LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 ->
                        writeByte(if (constant.value) 0x11 else 0x01)
                }

                is LuaConstant.Number -> {
                    writeByte(if (version == LuaBytecodeVersion.LUA_5_3) 0x03 else 0x13)
                    writeNumber(constant.value, chunk.header.numberSize)
                }

                is LuaConstant.Integer -> when (version) {
                    LuaBytecodeVersion.LUA_5_3 -> {
                        writeByte(0x13)
                        writeSigned(constant.value, chunk.header.integerSize)
                    }

                    LuaBytecodeVersion.LUA_5_4 -> {
                        writeByte(0x03)
                        writeSigned(constant.value, chunk.header.integerSize)
                    }

                    LuaBytecodeVersion.LUA_5_5 -> {
                        writeByte(0x03)
                        writeZigZagInteger(constant.value)
                    }
                }

                is LuaConstant.Str -> {
                    writeByte(if (constant.value.size <= MAX_SHORT_STRING_LENGTH) 0x04 else 0x14)
                    writeString(constant.value)
                }
            }
        }
    }

    private fun writeUpvalues(prototype: LuaPrototype) {
        writeInt(prototype.upvalues.size)
        for (upvalue in prototype.upvalues) {
            writeByte(if (upvalue.inStack) 1 else 0)
            writeByte(upvalue.index)
            if (version.upvalueDescriptorHasKind) {
                writeByte(upvalue.kind ?: 0)
            }
        }
    }

    private fun writeNestedPrototypes(prototype: LuaPrototype) {
        writeInt(prototype.nestedPrototypes.size)
        for (child in prototype.nestedPrototypes) {
            writePrototype(child, prototype.source)
        }
    }

    private fun writeDebug(prototype: LuaPrototype) {
        if (prototype.lineInfo.isNotEmpty() && prototype.lineInfo.size != prototype.code.size) {
            throw LuaBytecodeFormatException(
                "cannot dump: lineInfo has ${prototype.lineInfo.size} entries but the code has " +
                    "${prototype.code.size} instructions (a non stripped chunk needs one line per " +
                    "instruction, until Lua 5.5 one per instruction too)",
            )
        }
        if (version == LuaBytecodeVersion.LUA_5_3 && prototype.absoluteLineInfo.isNotEmpty()) {
            throw LuaBytecodeFormatException("cannot dump: Lua 5.3 has no absolute line info")
        }
        if (prototype.upvalueNames.isNotEmpty() && prototype.upvalueNames.size != prototype.upvalues.size) {
            throw LuaBytecodeFormatException(
                "cannot dump: upvalueNames has ${prototype.upvalueNames.size} entries but the " +
                    "prototype has ${prototype.upvalues.size} upvalues",
            )
        }

        writeInt(prototype.lineInfo.size)
        when (version) {
            LuaBytecodeVersion.LUA_5_3 -> for (line in prototype.lineInfo) writeSigned(line.toLong(), 4)
            LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 ->
                for (line in prototype.lineInfo) writeByte(line and 0xFF)
        }

        // 5.3 has no absolute line info section at all.
        if (version.hasAbsoluteLineInfo) {
            writeInt(prototype.absoluteLineInfo.size)
            if (prototype.absoluteLineInfo.isNotEmpty()) {
                when (version) {
                    LuaBytecodeVersion.LUA_5_3 -> Unit // unreachable, 5.3 is filtered above
                    LuaBytecodeVersion.LUA_5_4 -> for (entry in prototype.absoluteLineInfo) {
                        writeInt(entry.pc)
                        writeInt(entry.line)
                    }

                    LuaBytecodeVersion.LUA_5_5 -> {
                        align(4)
                        for (entry in prototype.absoluteLineInfo) {
                            writeSigned(entry.pc.toLong(), 4)
                            writeSigned(entry.line.toLong(), 4)
                        }
                    }
                }
            }
        }

        writeInt(prototype.localVariables.size)
        for (local in prototype.localVariables) {
            writeString(local.name)
            writeInt(local.startPc)
            writeInt(local.endPc)
        }

        writeInt(prototype.upvalueNames.size)
        for (name in prototype.upvalueNames) {
            writeString(name)
        }
    }

    // ----------------------------------------------------------------- strings

    private fun writeString(value: LuaString?) {
        when (version) {
            LuaBytecodeVersion.LUA_5_3 -> {
                if (value == null) {
                    writeByte(0)
                    return
                }
                val size = value.size + 1
                if (size < 0xFF) {
                    writeByte(size)
                } else {
                    writeByte(0xFF)
                    writeUnsigned(size.toLong(), chunk.header.sizeTSize ?: 8)
                }
                writeBytes(value.bytes)
            }

            LuaBytecodeVersion.LUA_5_4 -> {
                if (value == null) {
                    writeVarint(0)
                    return
                }
                writeVarint((value.size + 1).toLong())
                writeBytes(value.bytes)
            }

            LuaBytecodeVersion.LUA_5_5 -> {
                if (value == null) {
                    writeVarint(0)
                    writeVarint(0)
                    return
                }
                val reused = reusedStrings[value]
                if (reused != null) {
                    writeVarint(0)
                    writeVarint(reused.toLong())
                    return
                }
                writeVarint((value.size + 1).toLong())
                writeBytes(value.bytes)
                writeByte(0) // the dumped payload includes the terminating '\0'
                reusedStringCount++
                reusedStrings[value] = reusedStringCount
            }
        }
    }

    // ------------------------------------------------------------- primitives

    private fun writeByte(value: Int) {
        output.write(value and 0xFF)
        offset++
    }

    private fun writeBytes(bytes: ByteArray) {
        output.write(bytes)
        offset += bytes.size
    }

    private fun writeUnsigned(value: Long, width: Int) {
        val bytes = ByteArray(width)
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            for (i in 0 until width) {
                bytes[i] = (value ushr (8 * (width - 1 - i))).toByte()
            }
        } else {
            for (i in 0 until width) {
                bytes[i] = (value ushr (8 * i)).toByte()
            }
        }
        writeBytes(bytes)
    }

    private fun writeSigned(value: Long, width: Int) = writeUnsigned(value, width)

    private fun writeNumber(value: Double, width: Int) {
        val bits = if (width == 4) value.toFloat().toRawBits().toLong() else value.toRawBits()
        writeUnsigned(bits, width)
    }

    private fun writeInt(value: Int) {
        if (value < 0) {
            throw LuaBytecodeFormatException("cannot dump a negative chunk integer: $value")
        }
        if (version.usesVariableLengthInt) {
            writeVarint(value.toLong())
        } else {
            writeSigned(value.toLong(), 4)
        }
    }

    /** MSB first 7 bit varint, marked according to the version's [LuaVarintStyle]. */
    private fun writeVarint(value: Long) {
        val style = requireNotNull(varintStyle) { "Lua 5.3 does not use varints" }
        val groups = ArrayList<Int>()
        var rest = value
        do {
            groups.add((rest and 0x7F).toInt())
            rest = rest ushr 7
        } while (rest != 0L)

        for (i in groups.indices.reversed()) {
            val isLast = i == 0
            var group = groups[i]
            when (style) {
                LuaVarintStyle.TERMINATOR_FLAG -> if (isLast) group = group or 0x80
                LuaVarintStyle.CONTINUATION_FLAG -> if (!isLast) group = group or 0x80
            }
            writeByte(group)
        }
    }

    /**
     * 5.5 `dumpInteger`: a non negative `x` becomes `2x`, a negative one
     * `-2x - 1`, so small values stay small.
     */
    private fun writeZigZagInteger(value: Long) {
        val encoded = if (value >= 0) value shl 1 else ((value.inv()) shl 1) or 1
        writeVarint(encoded)
    }

    /** 5.5 `dumpAlign`: pads with zero bytes up to the next multiple of [alignment]. */
    private fun align(alignment: Int) {
        val padding = alignment - (offset % alignment)
        if (padding < alignment) {
            repeat(padding) { writeByte(0) }
        }
    }
}
