package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.FIXTURE_VERSIONS
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaOpcodes
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.LuaString
import io.github.dingyi222666.luabytecode.bytecode.edit.internConstant
import io.github.dingyi222666.luabytecode.bytecode.loadFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the shared runtime seed-derivation primitive [LuaHostSeed].
 *
 * The critical property is **emission equivalence** with the original
 * `LuaControlFlowNoise.appendHostVersionSeed` body it replaces (B6/Round1):
 * same instructions, same order, same register contract — proven by comparing
 * against a literal transcription of the pre-refactor code.
 */
class LuaHostSeedTest {

    /** The pre-refactor `appendHostVersionSeed`, kept verbatim as the oracle. */
    private fun legacyVersionLength(
        prototype: LuaPrototype,
        envUpvalue: Int,
        scratch: Int,
        setup: MutableList<LuaInstruction>,
    ) {
        val version = prototype.version
        val versionName = prototype.internConstant(LuaConstant.Str(LuaString.of("_VERSION")))
        val toStringName = prototype.internConstant(LuaConstant.Str(LuaString.of("tostring")))
        appendEnvLookup(setup, version, scratch, scratch, envUpvalue, versionName)
        appendEnvLookup(setup, version, scratch + 1, scratch, envUpvalue, toStringName)
        setup += LuaInstruction.abc(version, LuaOpcodes.opcodeOf(version, "MOVE")!!, scratch + 2, scratch, 0)
        setup += LuaInstruction.abc(version, LuaOpcodes.opcodeOf(version, "CALL")!!, scratch + 1, 2, 2)
        setup += LuaInstruction.abc(version, LuaOpcodes.opcodeOf(version, "LEN")!!, scratch, scratch + 1, 0)
    }

    /** The pre-refactor `appendHostRandomSeed`, kept verbatim as the oracle. */
    private fun legacyHostRandom(
        prototype: LuaPrototype,
        envUpvalue: Int,
        scratch: Int,
        setup: MutableList<LuaInstruction>,
    ) {
        val version = prototype.version
        val mathName = prototype.internConstant(LuaConstant.Str(LuaString.of("math")))
        val randomName = prototype.internConstant(LuaConstant.Str(LuaString.of("random")))
        val low = prototype.internConstant(LuaConstant.Integer(1))
        val high = prototype.internConstant(LuaConstant.Integer(1 shl 30))
        appendEnvLookup(setup, version, scratch, scratch, envUpvalue, mathName)
        setup += LuaInstruction.abx(version, LuaOpcodes.opcodeOf(version, "LOADK")!!, scratch + 3, randomName)
        setup += LuaInstruction.abc(version, LuaOpcodes.opcodeOf(version, "GETTABLE")!!, scratch, scratch, scratch + 3)
        setup += LuaInstruction.abx(version, LuaOpcodes.opcodeOf(version, "LOADK")!!, scratch + 1, low)
        setup += LuaInstruction.abx(version, LuaOpcodes.opcodeOf(version, "LOADK")!!, scratch + 2, high)
        setup += LuaInstruction.abc(version, LuaOpcodes.opcodeOf(version, "CALL")!!, scratch, 3, 2)
    }

    @Test
    fun `version length emission is byte identical to the legacy pass body`() {
        for ((_, version) in FIXTURE_VERSIONS) {
            val fixture = when (version) {
                LuaBytecodeVersion.LUA_5_3 -> "hello_53.luac"
                LuaBytecodeVersion.LUA_5_4 -> "hello_54.luac"
                else -> "hello_55.luac"
            }
            val chunk = loadFixture(fixture)
            val prototype = chunk.mainPrototype
            val legacy = ArrayList<LuaInstruction>()
            legacyVersionLength(prototype, envUpvalue = 0, scratch = 10, setup = legacy)
            val modern = ArrayList<LuaInstruction>()
            LuaHostSeed.appendVersionLength(prototype, destination = 10, scratchBase = 10, envUpvalue = 0, setup = modern)
            assertEquals(legacy.size, modern.size, "$version: instruction count changed")
            legacy.zip(modern).forEachIndexed { index, (old, new) ->
                assertEquals(old, new, "$version: instruction $index diverged from the legacy body")
            }
        }
    }

    @Test
    fun `host random emission is byte identical to the legacy pass body`() {
        for ((_, version) in FIXTURE_VERSIONS) {
            val fixture = when (version) {
                LuaBytecodeVersion.LUA_5_3 -> "flow_53.luac"
                LuaBytecodeVersion.LUA_5_4 -> "flow_54.luac"
                else -> "flow_55.luac"
            }
            val chunk = loadFixture(fixture)
            val prototype = chunk.mainPrototype
            val legacy = ArrayList<LuaInstruction>()
            legacyHostRandom(prototype, envUpvalue = 0, scratch = 4, setup = legacy)
            val modern = ArrayList<LuaInstruction>()
            LuaHostSeed.appendHostRandom(prototype, destination = 4, scratchBase = 4, envUpvalue = 0, setup = modern)
            assertEquals(legacy.size, modern.size, "$version: instruction count changed")
            legacy.zip(modern).forEachIndexed { index, (old, new) ->
                assertEquals(old, new, "$version: instruction $index diverged from the legacy body")
            }
        }
    }

    @Test
    fun `version length uses exactly the four scratch registers it reserves`() {
        for ((_, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture(if (version == LuaBytecodeVersion.LUA_5_3) "big_53.luac" else "big_54.luac")
            val prototype = chunk.mainPrototype
            val setup = ArrayList<LuaInstruction>()
            LuaHostSeed.appendVersionLength(prototype, destination = 0, scratchBase = 0, envUpvalue = 0, setup = setup)
            assertEquals(LuaHostSeed.VERSION_LENGTH_SIZE, setup.size)
            for (instruction in setup) {
                for (register in instruction.registerOperands()) {
                    assertTrue(register in 0..3, "$version: register $register outside scratchBase..scratchBase+3")
                }
            }
        }
    }

    @Test
    fun `known lengths boundary is pinned to the real interpreter values`() {
        // `#tostring(_VERSION)` on 5.3.6 / 5.4.6 / 5.5.1 — verified against
        // /tmp/lua-5.*/src/lua (see the war report for the command).
        assertEquals(7, LuaHostSeed.versionLength("Lua 5.3"))
        assertEquals(7, LuaHostSeed.versionLength("Lua 5.4"))
        assertEquals(7, LuaHostSeed.versionLength("Lua 5.5"))
        assertTrue(LuaHostSeed.KNOWN_LENGTHS.contentEquals(intArrayOf(7)))
        // the domain is *bounded* — that is the honest weakness this file documents
        assertTrue(LuaHostSeed.KNOWN_LENGTHS.size <= 3, "value domain must stay bounded (CFF-1/GATE-2 boundary)")
    }

    @Test
    fun `both emitters intern their keys only once per prototype`() {
        val chunk = loadFixture("features_54.luac")
        val prototype = chunk.mainPrototype
        val before = prototype.constants.size
        val setup = ArrayList<LuaInstruction>()
        LuaHostSeed.appendVersionLength(prototype, 0, 0, 0, setup)
        LuaHostSeed.appendVersionLength(prototype, 0, 0, 0, setup)
        LuaHostSeed.appendHostRandom(prototype, 0, 0, 0, setup)
        // `_VERSION`, `tostring`, `math`, `random`, `1`, `2^30` — all interned once
        assertEquals(before + 6, prototype.constants.size, "interning must deduplicate")
    }
}

/** Every register index this instruction reads or writes (operand-level view). */
private fun LuaInstruction.registerOperands(): List<Int> = when (opcodeName) {
    "MOVE", "LEN", "NOT", "UNM", "BNOT" -> listOf(a, b)
    "CALL" -> listOf(a, a + 1)
    "GETTABLE" -> listOf(a, b, c)
    "GETTABUP" -> listOf(a, b)
    "GETUPVAL" -> listOf(a)
    "LOADK", "LOADI", "LOADNIL", "LOADTRUE", "LOADFALSE" -> listOf(a)
    else -> listOf(a, b, c).filter { it >= 0 }
}
