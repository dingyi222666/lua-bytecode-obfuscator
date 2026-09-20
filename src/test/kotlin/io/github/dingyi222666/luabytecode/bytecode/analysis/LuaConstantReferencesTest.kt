package io.github.dingyi222666.luabytecode.bytecode.analysis

import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.edit.indexOfStringConstant
import io.github.dingyi222666.luabytecode.bytecode.FIXTURE_VERSIONS
import io.github.dingyi222666.luabytecode.bytecode.ALL_FIXTURES
import io.github.dingyi222666.luabytecode.bytecode.loadFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the hand written operand tables of [constantOperands] and the jump
 * decoding of [jumpTarget] against real `luac` output: every operand they report
 * must really point at a constant (of the expected kind) and every jump must
 * land inside the prototype.
 */
class LuaConstantReferencesTest {

    @Test
    fun `reported constant indices always exist`() {
        for (name in ALL_FIXTURES) {
            val chunk = loadFixture(name)
            for (prototype in chunk.prototypes) {
                for (instruction in prototype.code) {
                    for (index in instruction.constantOperands()) {
                        assertTrue(
                            index in prototype.constants.indices,
                            "$name: ${instruction.opcodeName} references constant $index but there are " +
                                "${prototype.constants.size}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `key operands always point at string constants`() {
        for (name in ALL_FIXTURES) {
            val chunk = loadFixture(name)
            for (prototype in chunk.prototypes) {
                for (instruction in prototype.code) {
                    val version = instruction.version
                    val keyIndices = when (instruction.opcodeName) {
                        // R[A] := UpValue[B][K[C]:string]
                        "GETTABUP", "GETFIELD", "SELF" ->
                            if (version == LuaBytecodeVersion.LUA_5_3) emptyList()
                            else listOf(instruction.c)

                        // UpValue[A][K[B]:string] := RK(C)
                        "SETTABUP", "SETFIELD" ->
                            if (version == LuaBytecodeVersion.LUA_5_3) emptyList()
                            else listOf(instruction.b)

                        else -> emptyList()
                    }
                    for (index in keyIndices) {
                        val constant = prototype.constants.getOrNull(index)
                        assertTrue(
                            constant is LuaConstant.Str,
                            "$name: ${instruction.opcodeName} K$index is $constant instead of a string",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `arithmetic constant operands point at numbers`() {
        for (name in ALL_FIXTURES) {
            val chunk = loadFixture(name)
            for (prototype in chunk.prototypes) {
                for (instruction in prototype.code) {
                    if (instruction.version == LuaBytecodeVersion.LUA_5_3) {
                        continue
                    }
                    if (instruction.opcodeName !in K_ARITHMETIC) {
                        continue
                    }
                    val constant = prototype.constants.getOrNull(instruction.c)
                    assertTrue(
                        constant is LuaConstant.Number || constant is LuaConstant.Integer,
                        "$name: ${instruction.opcodeName} K${instruction.c} is $constant",
                    )
                }
            }
        }
    }

    @Test
    fun `global names are never mistaken for loadK only constants`() {
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("hello_$suffix.luac")
            val main = chunk.mainPrototype
            val printIndex = main.indexOfStringConstant("print")
            assertTrue(printIndex >= 0, "hello_$suffix.luac must use print")
            assertTrue(
                printIndex in main.referencedConstantIndices(),
                "5.$version: the global name must be reported as referenced outside LOADK " +
                    "(5.3 RK operands carry BITRK)",
            )
        }
    }

    @Test
    fun `loadK sites point at the constants they load`() {
        for (name in ALL_FIXTURES) {
            val chunk = loadFixture(name)
            for (prototype in chunk.prototypes) {
                for ((constantIndex, pcs) in prototype.loadKSites()) {
                    assertTrue(constantIndex in prototype.constants.indices)
                    for (pc in pcs) {
                        assertEquals("LOADK", prototype.code[pc].opcodeName)
                        assertEquals(constantIndex, prototype.code[pc].bx)
                    }
                }
            }
        }
    }

    @Test
    fun `jump targets stay inside the prototype and start basic blocks`() {
        for (name in ALL_FIXTURES) {
            val chunk = loadFixture(name)
            for (prototype in chunk.prototypes) {
                val starts = prototype.basicBlockStarts()
                for ((pc, target) in prototype.jumpTargets()) {
                    assertTrue(
                        target in 0..prototype.code.size,
                        "$name: jump at $pc targets $target outside 0..${prototype.code.size}",
                    )
                    assertTrue(target in starts, "$name: jump at $pc targets $target which is not a block start")
                }
                assertTrue(0 in starts)
                // every prototype ends with a return, which the noise passes rely on
                assertTrue(
                    prototype.code.last().opcodeName in setOf("RETURN", "RETURN0", "RETURN1", "TAILCALL"),
                    "$name ends with ${prototype.code.last().opcodeName}",
                )
            }
        }
    }

    private companion object {
        val K_ARITHMETIC = setOf(
            "ADDK", "SUBK", "MULK", "MODK", "POWK", "DIVK", "IDIVK", "BANDK", "BORK", "BXORK",
        )
    }
}