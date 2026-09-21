package io.github.dingyi222666.luabytecode.bytecode.io

import io.github.dingyi222666.luabytecode.bytecode.LuaAbsoluteLineInfo
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaByteOrder
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaChunkHeader
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaLocalVariable
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.LuaString
import io.github.dingyi222666.luabytecode.bytecode.LuaUpvalue
import io.github.dingyi222666.luabytecode.bytecode.LuaVarintStyle
import io.github.dingyi222666.luabytecode.platform.hex2Upper
import io.github.dingyi222666.luabytecode.platform.hexUpper


/** `LUA_SIGNATURE` — `ESC "Lua"`, the first four bytes of every chunk. */
private val LUA_SIGNATURE = byteArrayOf(0x1B, 'L'.code.toByte(), 'u'.code.toByte(), 'a'.code.toByte())

/** `LUAC_DATA` — the corruption marker shared by every supported version. */
private val LUAC_DATA_MARKER = byteArrayOf(0x19, 0x93.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

/** `LUAC_FORMAT` of the official luac writer. */
private const val LUAC_FORMAT = 0

/** 5.3 / 5.4 `LUAC_INT` — integer probe written into the header. */
private const val LUAC_INT_5_3 = 0x5678L

/** 5.3 / 5.4 `LUAC_NUM` — float probe written into the header. */
private const val LUAC_NUM_5_3 = 370.5

/** 5.5 `LUAC_INT` — integer probe, note the sign change. */
private const val LUAC_INT_5_5 = -0x5678L

/** 5.5 `LUAC_INST` — instruction probe. */
private const val LUAC_INST_5_5 = 0x12345678L

/** 5.5 `LUAC_NUM` — float probe, note the sign change. */
private const val LUAC_NUM_5_5 = -370.5

/** `sizeof(Instruction)` of the reference implementation. */
private const val INSTRUCTION_SIZE = 4

/**
 * Guards against stack exhaustion on hostile input (the C parser cannot nest
 * functions deeper than `LUAI_MAXCCALLS == 200` anyway).
 */
private const val MAX_PROTOTYPE_DEPTH = 1000

/**
 * Parses standard Lua 5.3 / 5.4 / 5.5 precompiled chunks (`luac` output,
 * `luaL_loadfile` binary chunks) into the editable [LuaChunk] model.
 *
 * The parser mirrors `lundump.c` of every supported version, including the
 * header probes, the endianness detection through `LUAC_INT`, the 5.4 / 5.5
 * variable length integers, the per version constant tags, the 5.5 string reuse
 * table and alignment padding, the upvalue descriptor difference and the
 * absolute line info of 5.4 / 5.5.
 *
 * ```kotlin
 * val chunk = LuaChunkLoader.load(File("script.luac"))
 * println(chunk.version)                 // LUA_5_5
 * println(chunk.mainPrototype.constants) // [Str("hello"), Integer(42)]
 * ```
 *
 * @see LuaChunkWriter
 */
object LuaChunkLoader {

    /**
     * Loads a chunk from raw bytes.
     *
     * @param expectedVersion when not `null`, the chunk must declare this version.
     * @throws LuaBytecodeFormatException when the byte array is not a valid chunk.
     */
    @JvmStatic
    @JvmOverloads
    fun load(bytes: ByteArray, expectedVersion: LuaBytecodeVersion? = null): LuaChunk =
        ChunkLoader(bytes, expectedVersion).load()

    /** Quick check whether [bytes] starts with the Lua chunk signature. */
    @JvmStatic
    fun isLuaBytecode(bytes: ByteArray): Boolean {
        if (bytes.size < LUA_SIGNATURE.size) {
            return false
        }
        return LUA_SIGNATURE.indices.all { bytes[it] == LUA_SIGNATURE[it] }
    }
}

/** Version independent numbers read by `checkHeader`. */
private class HeaderNumbers(
    val intSize: Int?,
    val sizeTSize: Int?,
    val instructionSize: Int,
    val integerSize: Int,
    val numberSize: Int,
    val checkInteger: Long,
    val checkNumber: Double,
)

/** The four debug sections of a prototype. */
private class DebugInfo(
    val lineInfo: MutableList<Int>,
    val absoluteLineInfo: MutableList<LuaAbsoluteLineInfo>,
    val localVariables: MutableList<LuaLocalVariable>,
    val upvalueNames: MutableList<LuaString?>,
)

private class ChunkLoader(
    bytes: ByteArray,
    private val expectedVersion: LuaBytecodeVersion?,
) {

    private val reader = LuaChunkReader(bytes)
    private lateinit var header: LuaChunkHeader

    /** 5.5 string reuse table, 1 based exactly like `LoadState.nstr`. */
    private val reusedStrings = ArrayList<LuaString>()

    fun load(): LuaChunk {
        header = readHeader()
        val mainUpvalueCount = reader.readByte()
        val mainPrototype = readPrototype(null, 0)
        if (mainUpvalueCount != mainPrototype.upvalues.size) {
            throw LuaBytecodeFormatException(
                "main closure declares $mainUpvalueCount upvalue(s) but its prototype has " +
                    "${mainPrototype.upvalues.size}",
                reader.position,
            )
        }
        return LuaChunk(header, mainUpvalueCount, mainPrototype)
    }

    // ------------------------------------------------------------------ header

    private fun readHeader(): LuaChunkHeader {
        if (!LuaChunkLoader.isLuaBytecode(reader.readBytes(LUA_SIGNATURE.size))) {
            throw LuaBytecodeFormatException(
                "not a precompiled Lua chunk (bad signature, expected ESC \"Lua\")",
                0,
            )
        }

        val versionOffset = reader.position
        val versionByte = reader.readByte()
        val version = LuaBytecodeVersion.fromVersionByte(versionByte)
            ?: throw LuaBytecodeFormatException(
                "unsupported bytecode version 0x" +
                    hex2Upper(versionByte) + "; supported versions are " +
                    LuaBytecodeVersion.supportedVersionsText(),
                versionOffset,
            )
        if (expectedVersion != null && expectedVersion != version) {
            throw LuaBytecodeFormatException(
                "chunk is ${version.displayName} but ${expectedVersion.displayName} was expected",
                versionOffset,
            )
        }

        val formatOffset = reader.position
        val format = reader.readByte()
        if (format != LUAC_FORMAT) {
            throw LuaBytecodeFormatException("unsupported chunk format $format", formatOffset)
        }

        val dataOffset = reader.position
        if (!LUAC_DATA_MARKER.contentEquals(reader.readBytes(LUAC_DATA_MARKER.size))) {
            throw LuaBytecodeFormatException("corrupted chunk (LUAC_DATA mismatch)", dataOffset)
        }

        val numbers = when (version) {
            LuaBytecodeVersion.LUA_5_3 -> readHeaderNumbers53()
            LuaBytecodeVersion.LUA_5_4 -> readHeaderNumbers54()
            LuaBytecodeVersion.LUA_5_5 -> readHeaderNumbers55()
        }

        return LuaChunkHeader(
            version = version,
            format = format,
            byteOrder = reader.byteOrder,
            intSize = numbers.intSize,
            sizeTSize = numbers.sizeTSize,
            instructionSize = numbers.instructionSize,
            integerSize = numbers.integerSize,
            numberSize = numbers.numberSize,
            checkInteger = numbers.checkInteger,
            checkNumber = numbers.checkNumber,
        )
    }

    /** 5.3: `int`, `size_t`, `Instruction`, `lua_Integer`, `lua_Number` sizes + probes. */
    private fun readHeaderNumbers53(): HeaderNumbers {
        val intSize = expectSizeByte("sizeof(int)", 4)
        val sizeTSize = expectSizeByte("sizeof(size_t)", 4, 8)
        val instructionSize = expectSizeByte("sizeof(Instruction)", INSTRUCTION_SIZE)
        val integerSize = expectSizeByte("sizeof(lua_Integer)", 1, 8)
        val numberSize = expectSizeByte("sizeof(lua_Number)", 4, 8)
        val checkInteger = probeInteger(LUAC_INT_5_3, integerSize)
        val checkNumber = probeNumber(LUAC_NUM_5_3, numberSize)
        return HeaderNumbers(
            intSize,
            sizeTSize,
            instructionSize,
            integerSize,
            numberSize,
            checkInteger,
            checkNumber,
        )
    }

    /** 5.4: `Instruction`, `lua_Integer`, `lua_Number` sizes + probes. */
    private fun readHeaderNumbers54(): HeaderNumbers {
        val instructionSize = expectSizeByte("sizeof(Instruction)", INSTRUCTION_SIZE)
        val integerSize = expectSizeByte("sizeof(lua_Integer)", 1, 8)
        val numberSize = expectSizeByte("sizeof(lua_Number)", 4, 8)
        val checkInteger = probeInteger(LUAC_INT_5_3, integerSize)
        val checkNumber = probeNumber(LUAC_NUM_5_3, numberSize)
        return HeaderNumbers(null, null, instructionSize, integerSize, numberSize, checkInteger, checkNumber)
    }

    /** 5.5: four `size + raw value` probes: `int`, `Instruction`, `lua_Integer`, `lua_Number`. */
    private fun readHeaderNumbers55(): HeaderNumbers {
        val intSize = expectSizeByte("sizeof(int)", 4)
        val checkInteger = probeInteger(LUAC_INT_5_5, intSize)

        val instructionSize = expectSizeByte("sizeof(Instruction)", INSTRUCTION_SIZE)
        val instructionProbeOffset = reader.position
        val checkInstruction = reader.readUnsigned(instructionSize)
        if (checkInstruction != LUAC_INST_5_5) {
            throw LuaBytecodeFormatException(
                "instruction format mismatch (expected 0x" + hexUpper(LUAC_INST_5_5) +
                    ", read 0x" + hexUpper(checkInstruction),
                instructionProbeOffset,
            )
        }

        val integerSize = expectSizeByte("sizeof(lua_Integer)", 1, 8)
        val integerProbeOffset = reader.position
        val checkLuaInteger = reader.readSigned(integerSize)
        if (checkLuaInteger != LUAC_INT_5_5) {
            throw LuaBytecodeFormatException(
                "Lua integer format mismatch (expected $LUAC_INT_5_5, read $checkLuaInteger)",
                integerProbeOffset,
            )
        }

        val numberSize = expectSizeByte("sizeof(lua_Number)", 4, 8)
        val checkNumber = probeNumber(LUAC_NUM_5_5, numberSize)
        return HeaderNumbers(intSize, null, instructionSize, integerSize, numberSize, checkInteger, checkNumber)
    }

    /** Reads a size byte that must be inside `min..max`. */
    private fun expectSizeByte(what: String, min: Int, max: Int): Int {
        val offset = reader.position
        val value = reader.readByte()
        if (value !in min..max) {
            throw LuaBytecodeFormatException("$what mismatch: $value", offset)
        }
        return value
    }

    /** Reads a size byte that must equal [exactly]. */
    private fun expectSizeByte(what: String, exactly: Int): Int = expectSizeByte(what, exactly, exactly)

    /**
     * Reads an integer probe and, when it does not match under the current byte
     * order, retries byte swapped so foreign endianness chunks load too.
     */
    private fun probeInteger(expected: Long, width: Int): Long {
        val offset = reader.position
        var value = reader.readSigned(width)
        if (value != expected && reader.byteOrder == LuaByteOrder.LITTLE) {
            reader.seek(offset)
            reader.byteOrder = LuaByteOrder.BIG
            value = reader.readSigned(width)
        }
        if (value != expected) {
            throw LuaBytecodeFormatException(
                "integer format mismatch (expected $expected, read $value)",
                offset,
            )
        }
        return value
    }

    private fun probeNumber(expected: Double, width: Int): Double {
        val offset = reader.position
        val value = reader.readLuaNumber(width)
        if (value != expected) {
            throw LuaBytecodeFormatException(
                "float format mismatch (expected $expected, read $value)",
                offset,
            )
        }
        return value
    }

    // -------------------------------------------------------------- prototypes

    /** Reads one prototype and everything nested in it (`loadFunction`). */
    private fun readPrototype(parentSource: LuaString?, depth: Int): LuaPrototype {
        if (depth > MAX_PROTOTYPE_DEPTH) {
            throw LuaBytecodeFormatException(
                "prototype nesting is deeper than $MAX_PROTOTYPE_DEPTH",
                reader.position,
            )
        }
        val version = header.version

        // 5.5 moved the source after the nested prototypes, 5.3 / 5.4 keep it first.
        var source: LuaString? = if (version.sourceAfterPrototypes) {
            null
        } else {
            readStringOrNull() ?: parentSource
        }

        val lineDefined = readInt()
        val lastLineDefined = readInt()
        val numParams = reader.readByte()
        val isVarargFlags = reader.readByte()
        val maxStackSize = reader.readByte()

        val code = readCode()
        val constants = readConstants()
        val upvalues = readUpvalues()
        val nested = readPrototypes(source, depth)

        if (version.sourceAfterPrototypes) {
            source = readStringOrNull()
        }

        val debug = readDebugInfo(upvalues.size)

        return LuaPrototype(
            version = version,
            source = source,
            lineDefined = lineDefined,
            lastLineDefined = lastLineDefined,
            numParams = numParams,
            isVararg = version.isVararg(isVarargFlags),
            isVarargFlags = isVarargFlags,
            maxStackSize = maxStackSize,
            code = code,
            constants = constants,
            upvalues = upvalues,
            nestedPrototypes = nested,
            lineInfo = debug.lineInfo,
            absoluteLineInfo = debug.absoluteLineInfo,
            localVariables = debug.localVariables,
            upvalueNames = debug.upvalueNames,
        )
    }

    private fun readPrototypes(
        parentSource: LuaString?,
        depth: Int,
    ): MutableList<LuaPrototype> {
        val count = readInt()
        checkCount(count, "nested prototype", 1)
        return MutableList(count) { readPrototype(parentSource, depth + 1) }
    }

    private fun readCode(): MutableList<LuaInstruction> {
        val count = readInt()
        checkCount(count, "instruction", header.instructionSize)
        if (header.version == LuaBytecodeVersion.LUA_5_5) {
            align(header.instructionSize)
        }
        return MutableList(count) { LuaInstruction(reader.readInt32(), header.version) }
    }

    private fun readConstants(): MutableList<LuaConstant> {
        val count = readInt()
        checkCount(count, "constant", 1)
        return MutableList(count) { readConstant() }
    }

    private fun readConstant(): LuaConstant {
        val offset = reader.position
        val tag = reader.readByte()
        return when (header.version) {
            LuaBytecodeVersion.LUA_5_3 -> when (tag) {
                0x00 -> LuaConstant.Nil
                0x01 -> LuaConstant.Boolean(reader.readByte() != 0)
                0x03 -> LuaConstant.Number(reader.readLuaNumber(header.numberSize))
                0x13 -> LuaConstant.Integer(reader.readSigned(header.integerSize))
                0x04, 0x14 -> LuaConstant.Str(requiredString(offset))
                else -> throw LuaBytecodeFormatException(
                    "unknown 5.3 constant tag 0x" + hex2Upper(tag),
                    offset,
                )
            }

            LuaBytecodeVersion.LUA_5_4 -> when (tag) {
                0x00 -> LuaConstant.Nil
                0x01 -> LuaConstant.Boolean(false)
                0x11 -> LuaConstant.Boolean(true)
                0x03 -> LuaConstant.Integer(reader.readSigned(header.integerSize))
                0x13 -> LuaConstant.Number(reader.readLuaNumber(header.numberSize))
                0x04, 0x14 -> LuaConstant.Str(requiredString(offset))
                else -> throw LuaBytecodeFormatException(
                    "unknown 5.4 constant tag 0x" + hex2Upper(tag),
                    offset,
                )
            }

            LuaBytecodeVersion.LUA_5_5 -> when (tag) {
                0x00 -> LuaConstant.Nil
                0x01 -> LuaConstant.Boolean(false)
                0x11 -> LuaConstant.Boolean(true)
                0x03 -> LuaConstant.Integer(readZigZagInteger())
                0x13 -> LuaConstant.Number(reader.readLuaNumber(header.numberSize))
                0x04, 0x14 -> LuaConstant.Str(requiredString(offset))
                else -> throw LuaBytecodeFormatException(
                    "unknown 5.5 constant tag 0x" + hex2Upper(tag),
                    offset,
                )
            }
        }
    }

    /**
     * 5.5 `loadInteger`: `lua_Integer` values use a zig-zag varint, where a non
     * negative `x` is stored as `2x` and a negative one as `-2x - 1`.
     *
     * `MAX_INT64` dumps as `cx = 2x = 0xFFFFFFFFFFFFFFFE`, which as a signed Long
     * is `-2` — the `cx >> 1` of `loadInteger` is an *unsigned* shift
     * (`l_castU2S(cx >> 1)`), so the decode must use `ushr`, not `shr`.
     */
    private fun readZigZagInteger(): Long {
        val encoded = reader.readVarint(limit = -1L, style = LuaVarintStyle.CONTINUATION_FLAG)
        return if (encoded and 1L != 0L) (encoded ushr 1).inv() else encoded ushr 1
    }

    private fun readUpvalues(): MutableList<LuaUpvalue> {
        val count = readInt()
        val bytesPerDescriptor = if (header.version.upvalueDescriptorHasKind) 3 else 2
        checkCount(count, "upvalue", bytesPerDescriptor)
        return MutableList(count) {
            val inStack = reader.readByte() != 0
            val index = reader.readByte()
            val kind = if (header.version.upvalueDescriptorHasKind) reader.readByte() else null
            LuaUpvalue(inStack, index, kind)
        }
    }

    // ------------------------------------------------------------------- debug

    private fun readDebugInfo(upvalueCount: Int): DebugInfo {
        val version = header.version

        val lineCount = readInt()
        val lineInfo: MutableList<Int> = if (version.usesVariableLengthInt) {
            // 5.4 / 5.5: one signed byte per instruction (relative deltas).
            checkCount(lineCount, "line info entry", 1)
            MutableList(lineCount) { reader.readSigned(1).toInt() }
        } else {
            // 5.3: one `int` per instruction (absolute lines).
            checkCount(lineCount, "line info entry", 4)
            MutableList(lineCount) { reader.readInt32() }
        }

        val absoluteLineInfo = mutableListOf<LuaAbsoluteLineInfo>()
        if (version.hasAbsoluteLineInfo) {
            val count = readInt()
            if (version.absoluteLineInfoIsRawInts) {
                // 5.5: aligned array of `{ int pc; int line; }` structures.
                if (count > 0) {
                    align(4)
                }
                checkCount(count, "absolute line info entry", 8)
                repeat(count) {
                    absoluteLineInfo += LuaAbsoluteLineInfo(reader.readInt32(), reader.readInt32())
                }
            } else {
                checkCount(count, "absolute line info entry", 2)
                repeat(count) {
                    absoluteLineInfo += LuaAbsoluteLineInfo(readInt(), readInt())
                }
            }
        }

        val localCount = readInt()
        checkCount(localCount, "local variable", 3)
        val localVariables = MutableList(localCount) {
            val name = readStringOrNull()
            val startPc = readInt()
            val endPc = readInt()
            LuaLocalVariable(name, startPc, endPc)
        }

        val upvalueNameCount = readInt()
        if (upvalueNameCount != 0 && upvalueNameCount != upvalueCount) {
            throw LuaBytecodeFormatException(
                "prototype has $upvalueNameCount upvalue name(s) but $upvalueCount upvalue(s)",
                reader.position,
            )
        }
        checkCount(upvalueNameCount, "upvalue name", 1)
        val upvalueNames = MutableList(upvalueNameCount) { readStringOrNull() }

        return DebugInfo(lineInfo, absoluteLineInfo, localVariables, upvalueNames)
    }

    // ----------------------------------------------------------------- strings

    private fun readStringOrNull(): LuaString? = when (header.version) {
        LuaBytecodeVersion.LUA_5_3 -> readString53()
        LuaBytecodeVersion.LUA_5_4 -> readString54()
        LuaBytecodeVersion.LUA_5_5 -> readString55()
    }

    /**
     * 5.3 `LoadString`: one size byte, switching to a fixed size `size_t` after
     * a `0xFF` escape.
     */
    private fun readString53(): LuaString? {
        val first = reader.readByte()
        val size = if (first == 0xFF) reader.readUnsigned(header.sizeTSize ?: 8) else first.toLong()
        return if (size == 0L) null else LuaString(readPayload(size - 1))
    }

    /** 5.4 `loadStringN`: variable length size. */
    private fun readString54(): LuaString? {
        val size = reader.readVarint()
        return if (size == 0L) null else LuaString(readPayload(size - 1))
    }

    /**
     * 5.5 `loadString`: `size == 0` introduces an index into the reuse table
     * (`0` means `null`), otherwise the size includes the dumped terminating
     * `'\0'`.
     */
    private fun readString55(): LuaString? {
        val style = LuaVarintStyle.CONTINUATION_FLAG
        val size = reader.readVarint(style = style)
        if (size == 0L) {
            val index = reader.readVarint(style = style)
            if (index == 0L) {
                return null
            }
            if (index > reusedStrings.size) {
                throw LuaBytecodeFormatException(
                    "invalid reused string index $index (only ${reusedStrings.size} saved)",
                    reader.position,
                )
            }
            return reusedStrings[(index - 1).toInt()]
        }
        // The dumped payload is exactly `size` bytes: the content plus one '\0'.
        val payload = readPayload(size)
        val string = LuaString(payload.copyOf(payload.size - 1))
        reusedStrings += string
        return string
    }

    private fun readPayload(length: Long): ByteArray {
        if (length < 0 || length > Int.MAX_VALUE) {
            throw LuaBytecodeFormatException("invalid string size $length", reader.position)
        }
        return reader.readBytes(length.toInt())
    }

    private fun requiredString(offset: Int): LuaString = readStringOrNull()
        ?: throw LuaBytecodeFormatException("null string where a string constant was expected", offset)

    // ------------------------------------------------------------------ basics

    /** Reads a chunk `int`: variable length in 5.4 / 5.5, fixed 4 bytes in 5.3. */
    private fun readInt(): Int {
        val version = header.version
        return if (version.usesVariableLengthInt) {
            reader.readVarint(Int.MAX_VALUE.toLong(), version.varintStyle!!).toInt()
        } else {
            reader.readInt32()
        }
    }

    /**
     * 5.5 `loadAlign`: skips the padding bytes that align the current dump
     * position to [alignment].
     */
    private fun align(alignment: Int) {
        val padding = alignment - (reader.position % alignment)
        if (padding < alignment) {
            reader.readBytes(padding)
        }
    }

    /**
     * Rejects counts that cannot possibly fit in the remaining bytes, so a
     * corrupted chunk cannot make the parser allocate gigabytes.
     */
    private fun checkCount(count: Int, what: String, minBytesPerItem: Int) {
        if (count < 0) {
            throw LuaBytecodeFormatException("negative $what count $count", reader.position)
        }
        if (count.toLong() * minBytesPerItem > reader.remaining) {
            throw LuaBytecodeFormatException(
                "$what count $count cannot fit in the ${reader.remaining} remaining byte(s)",
                reader.position,
            )
        }
    }
}
