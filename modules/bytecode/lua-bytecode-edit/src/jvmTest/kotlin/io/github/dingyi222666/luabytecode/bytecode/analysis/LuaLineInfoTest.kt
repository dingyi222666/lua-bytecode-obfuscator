package io.github.dingyi222666.luabytecode.bytecode.analysis

import io.github.dingyi222666.luabytecode.bytecode.ALL_FIXTURES
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.loadFixture
// setAbsoluteLines now ships with :lua-bytecode-edit (same package, other
// module) — the explicit import keeps the dependency visible.
import io.github.dingyi222666.luabytecode.bytecode.analysis.setAbsoluteLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `luaG_getfuncline` contract of 5.4 / 5.5.
 *
 * `getbaseline` estimates the absolute anchor of a pc as `pc / MAXIWTHABS - 1`
 * and then *only walks forward*, so the estimate has to be a lower bound: a
 * prototype must never have more than `MAXIWTHABS` (128) instructions between
 * two absolute anchors. Real luac maintains that with `lcode.c savelineinfo`
 * (`fs->iwthabs`), and [LuaLineInfo.encode] has to maintain it too, otherwise a
 * re-encoded long prototype decodes its own table into garbage (and, on 5.4,
 * into negative absolute lines that [io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter]
 * refuses to dump).
 *
 * These tests pin the invariant for luac-produced chunks *and* for chunks this
 * project re-encodes, which is what broke `big_module_54 --jmp 3`.
 */
class LuaLineInfoTest {

    /** `MAXIWTHABS` of 5.4 / 5.5 `ldebug.h`. */
    private val maxInstructionsWithoutAbsoluteLine = 128

    /** Every fixture of the repository: `/bytecode` plus the real world `/real` chunks. */
    private fun allFixtureNames(): List<String> = buildList {
        addAll(ALL_FIXTURES)
        for (suffix in listOf("53", "54", "55")) {
            for (program in listOf("bank", "json", "algo", "big_module")) {
                add("real/${program}_$suffix.luac")
            }
        }
    }

    private fun load(name: String) =
        if (name.startsWith("real/")) {
            LuaChunkLoader.load(
                checkNotNull(LuaLineInfoTest::class.java.getResourceAsStream("/$name")) {
                    "missing fixture /$name"
                }.use { it.readBytes() },
            )
        } else {
            loadFixture(name)
        }

    private fun LuaPrototype.assertAnchorDensity(context: String) {
        val anchors = absoluteLineInfo
        if (anchors.isEmpty()) {
            return
        }
        for (index in 1 until anchors.size) {
            val previous = anchors[index - 1]
            val current = anchors[index]
            assertTrue(
                current.pc > previous.pc,
                "$context: absolute anchors must be sorted, got ${previous.pc} then ${current.pc}",
            )
            assertTrue(
                current.pc - previous.pc <= maxInstructionsWithoutAbsoluteLine,
                "$context: ${current.pc - previous.pc} instructions between anchors at " +
                    "${previous.pc} and ${current.pc} exceeds MAXIWTHABS " +
                    "($maxInstructionsWithoutAbsoluteLine)",
            )
        }
    }

    @Test
    fun `luac produced chunks keep the absolute anchor density invariant`() {
        for (name in allFixtureNames()) {
            val chunk = load(name)
            if (chunk.header.version == LuaBytecodeVersion.LUA_5_3) {
                continue
            }
            for ((index, prototype) in chunk.prototypes.withIndex()) {
                assertTrue(
                    prototype.absoluteLineInfo.size <= prototype.instructionCount,
                    "$name proto #$index: more anchors than instructions",
                )
                prototype.assertAnchorDensity("$name proto #$index (luac)")
            }
        }
    }

    @Test
    fun `re-encoding keeps the anchor density and round trips the absolute lines`() {
        for (name in allFixtureNames()) {
            val chunk = load(name)
            for ((index, prototype) in chunk.prototypes.withIndex()) {
                if (prototype.lineInfo.isEmpty()) {
                    continue
                }
                val before = prototype.resolvedLineInfo()
                prototype.setAbsoluteLines(before)
                prototype.assertAnchorDensity("$name proto #$index (re-encoded)")
                assertEquals(
                    before,
                    prototype.resolvedLineInfo(),
                    "$name proto #$index: re-encoding did not round trip the resolved lines",
                )
            }
        }
    }

    @Test
    fun `re-encoding survives a long synthetic function with overflowing deltas`() {
        // 1000 instructions whose lines drift out of the one byte delta range
        // several times, so both anchor rules (overflow and the 128 window)
        // are exercised.
        val lines = List(1000) { pc ->
            when {
                pc < 300 -> pc % 7
                pc < 600 -> 5000 + pc
                else -> 100 - (pc % 13)
            }.coerceAtLeast(0)
        }
        for (suffix in listOf("53", "54", "55")) {
            val prototype = loadFixture("hello_$suffix.luac").mainPrototype
            while (prototype.code.size < lines.size) {
                prototype.code += prototype.code.first()
            }
            while (prototype.code.size > lines.size) {
                prototype.code.removeAt(prototype.code.size - 1)
            }
            prototype.setAbsoluteLines(lines)

            assertEquals(lines, prototype.resolvedLineInfo(), "5.$suffix")
            if (prototype.version != LuaBytecodeVersion.LUA_5_3) {
                prototype.assertAnchorDensity("synthetic 5.$suffix")
            }
        }
    }
}