package io.github.dingyi222666.luabytecode.bytecode.analysis

import io.github.dingyi222666.luabytecode.bytecode.LuaAbsoluteLineInfo
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype

/**
 * Encoding and decoding of the prototype line info, so instruction edits can
 * keep the debug sections valid.
 *
 * 5.3 stores one absolute line per instruction. 5.4 and 5.5 store a signed one
 * byte *delta* per instruction plus a list of absolute anchors; the sentinel
 * `-128` marks a instruction whose line lives in the anchor list.
 */
object LuaLineInfo {

    /** `ABSLINEINFO` — `lineInfo[pc]` refers to an [LuaAbsoluteLineInfo] entry. */
    const val ABSOLUTE_LINE_INFO: Int = -128

    /**
     * Largest delta the one byte field can hold without colliding with
     * [ABSOLUTE_LINE_INFO] (`ls_byte` is signed).
     */
    private const val MAX_DELTA = 127
    private const val MIN_DELTA = -127

    /**
     * `MAXIWTHABS` from 5.4 / 5.5 `ldebug.c`: `luaG_getfuncline` derives its
     * starting anchor as `pc / MAXIWTHABS - 1` and then only walks forward, so a
     * prototype must never contain more than this many instructions between two
     * absolute anchors. Real luac guarantees it; our encoder has to as well,
     * otherwise a rewritten long prototype crashes its own reader.
     *
     * `lcode.c savelineinfo` counts with `iwthabs`: it increments once per
     * instruction and places an anchor when the counter *before* the increment
     * reaches this value, then restarts the counter at `1` (not `0`). That reset
     * is what makes the distance exactly [MAX_INSTRUCTIONS_WITHOUT_ABSOLUTE_LINE]
     * instead of one more; keeping the estimator a lower bound depends on it.
     */
    private const val MAX_INSTRUCTIONS_WITHOUT_ABSOLUTE_LINE = 128

    /** A freshly encoded `lineInfo` / `absoluteLineInfo` pair. */
    data class Encoding(
        val lineInfo: MutableList<Int>,
        val absoluteLineInfo: MutableList<LuaAbsoluteLineInfo>,
    )

    /**
     * Encodes [absoluteLines] (one absolute source line per instruction) into
     * the representation of [version].
     *
     * The produced pair is decoded back to [absoluteLines] by
     * [LuaPrototype.resolvedLineInfo] and by Lua's own `luaG_getfuncline`.
     */
    fun encode(
        version: LuaBytecodeVersion,
        lineDefined: Int,
        absoluteLines: List<Int>,
    ): Encoding {
        if (version == LuaBytecodeVersion.LUA_5_3) {
            return Encoding(absoluteLines.toMutableList(), mutableListOf())
        }

        val lineInfo = ArrayList<Int>(absoluteLines.size)
        val absoluteLineInfo = ArrayList<LuaAbsoluteLineInfo>()
        var baseline = lineDefined
        // `fs->iwthabs` of `lcode.c savelineinfo`: it starts at 0, is post
        // incremented for every instruction and is restarted at 1 after an
        // absolute anchor, so two consecutive anchors are at most
        // MAX_INSTRUCTIONS_WITHOUT_ABSOLUTE_LINE instructions apart.
        var sinceAnchor = 0
        for (pc in absoluteLines.indices) {
            val line = absoluteLines[pc]
            val delta = line - baseline
            val needsAnchor = sinceAnchor >= MAX_INSTRUCTIONS_WITHOUT_ABSOLUTE_LINE ||
                delta < MIN_DELTA || delta > MAX_DELTA
            if (needsAnchor) {
                absoluteLineInfo += LuaAbsoluteLineInfo(pc, line)
                lineInfo += ABSOLUTE_LINE_INFO
                sinceAnchor = 1
            } else {
                lineInfo += delta
                sinceAnchor++
            }
            baseline = line
        }
        return Encoding(lineInfo, absoluteLineInfo)
    }
}

/**
 * Recomputes the debug line tables of this prototype from [absoluteLines]
 * (one absolute source line per instruction).
 */
fun LuaPrototype.setAbsoluteLines(absoluteLines: List<Int>) {
    val encoding = LuaLineInfo.encode(version, lineDefined, absoluteLines)
    lineInfo.clear()
    lineInfo.addAll(encoding.lineInfo)
    absoluteLineInfo.clear()
    absoluteLineInfo.addAll(encoding.absoluteLineInfo)
}
