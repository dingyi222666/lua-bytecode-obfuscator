package io.github.dingyi222666.luabytecode.bytecode.io

import io.github.dingyi222666.luabytecode.bytecode.LuaByteOrder
import io.github.dingyi222666.luabytecode.bytecode.FIXTURE_VERSIONS
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaOpcodes
import io.github.dingyi222666.luabytecode.bytecode.TestFixtures
import io.github.dingyi222666.luabytecode.bytecode.allConstants
import io.github.dingyi222666.luabytecode.bytecode.loadFixture
import io.github.dingyi222666.luabytecode.bytecode.stringConstant
import io.github.dingyi222666.luabytecode.bytecode.stringConstantTexts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue


class LuaChunkLoaderTest {

    @Test
    fun `parses the chunk header of every version`() {
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("hello_$suffix.luac")
            val header = chunk.header

            assertEquals(version, header.version)
            assertEquals(0, header.format)
            assertEquals(LuaByteOrder.LITTLE, header.byteOrder)
            assertEquals(4, header.instructionSize)
            assertEquals(8, header.integerSize)
            assertEquals(8, header.numberSize)

            when (version) {
                LuaBytecodeVersion.LUA_5_3 -> {
                    assertEquals(0x5678L, header.checkInteger)
                    assertEquals(370.5, header.checkNumber, 0.0)
                    assertEquals(4, header.intSize)
                    assertEquals(8, header.sizeTSize)
                }

                LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> {
                    assertNull(header.sizeTSize)
                }
            }
            when (version) {
                LuaBytecodeVersion.LUA_5_3, LuaBytecodeVersion.LUA_5_4 -> {
                    assertEquals(0x5678L, header.checkInteger)
                    assertEquals(370.5, header.checkNumber, 0.0)
                }

                LuaBytecodeVersion.LUA_5_5 -> {
                    // 5.5 flipped the sign of both probes and added LUAC_INST.
                    assertEquals(-0x5678L, header.checkInteger)
                    assertEquals(-370.5, header.checkNumber, 0.0)
                    assertEquals(4, header.intSize)
                }
            }

            // luaU_undump asserts exactly this.
            assertEquals(chunk.mainPrototype.upvalues.size, chunk.mainUpvalueCount)
            assertTrue(chunk.mainUpvalueCount > 0, "the main closure captures _ENV")
        }
    }

    @Test
    fun `keeps the source name and prototype tree shape`() {
        val expectedPrototypes = mapOf(
            "hello" to 2, // main + add
            "features" to 4, // main + makeCounter + anonymous closure + sum
            "big" to 2, // main + fill
            "dedup" to 3, // main + first + second
            "flow" to 3, // main + classify + sumEven
        )
        for ((name, count) in expectedPrototypes) {
            for ((suffix, _) in FIXTURE_VERSIONS) {
                val chunk = loadFixture("${name}_$suffix.luac")
                assertEquals(count, chunk.prototypes.size, "$name $suffix prototype count")
                assertEquals("@$name.lua", chunk.mainPrototype.source?.utf8)
                // luac marks the main chunk with the <0,0> line range.
                assertEquals(0, chunk.mainPrototype.lineDefined)
                assertEquals(0, chunk.mainPrototype.lastLineDefined)
                assertTrue(chunk.mainPrototype.isVararg)

                // Nested prototypes keep their real line range.
                for (prototype in chunk.prototypes.drop(1)) {
                    assertTrue(prototype.lineDefined > 0, "nested prototypes start after line 0")
                    assertTrue(prototype.lineDefined <= prototype.lastLineDefined)
                }
            }
        }
    }

    @Test
    fun `reads constant tables`() {
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("hello_$suffix.luac")
            val constants = chunk.allConstants()

            val greeting = constants.filterIsInstance<LuaConstant.Str>()
                .single { it.value.utf8 == "hello world, 你好 世界" }
            // UTF-8 bytes must survive the round trip untouched.
            assertEquals(
                "hello world, 你好 世界".toByteArray(Charsets.UTF_8).toList(),
                greeting.value.bytes.toList(),
            )
            assertTrue(constants.any { it is LuaConstant.Number && it.value == 0.5 })
            assertTrue(constants.any { it is LuaConstant.Str && it.value.utf8 == "print" })

            if (version == LuaBytecodeVersion.LUA_5_3) {
                // 5.3 compiles `local count = 42` into LOADK with an integer constant.
                assertTrue(constants.any { it is LuaConstant.Integer && it.value == 42L })
            } else {
                // 5.4 and 5.5 use the immediate LOADI instruction instead.
                assertTrue(chunk.mainPrototype.code.any { it.opcodeName == "LOADI" })
            }
        }
    }

    @Test
    fun `reads negative integer values`() {
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("hello_$suffix.luac")
            if (version == LuaBytecodeVersion.LUA_5_3) {
                // 5.3 has no immediate load: the literal lands in the constant table
                // (a raw lua_Integer, or a zig-zag varint in 5.5).
                assertTrue(
                    chunk.allConstants().any { it is LuaConstant.Integer && it.value == -12345L },
                    "hello_$suffix.luac must keep the -12345 constant",
                )
            } else {
                // 5.4 / 5.5 use LOADI with a signed Bx immediate.
                assertTrue(chunk.mainPrototype.code.any { it.opcodeName == "LOADI" && it.sbx == -12345 })
            }
        }
    }

    @Test
    fun `reads upvalue descriptors per version`() {
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("features_$suffix.luac")

            for (prototype in chunk.prototypes) {
                if (version == LuaBytecodeVersion.LUA_5_3) {
                    assertTrue(prototype.upvalues.all { it.kind == null }, "5.3 has no upvalue kind")
                } else {
                    assertEquals(
                        prototype.upvalues.size,
                        prototype.upvalues.count { it.kind != null },
                        "5.4 / 5.5 store a kind byte for every upvalue",
                    )
                    assertTrue(prototype.upvalues.all { it.kind in 0..3 })
                }
            }

            // The anonymous closure captures value, step and counter.
            val closure = chunk.prototypes.single { it.upvalueCount == 3 }
            assertEquals(3, closure.upvalues.size)
            assertTrue(closure.upvalueNames.mapNotNull { it?.utf8 }.containsAll(listOf("value", "step")))
            assertTrue(closure.upvalues.any { it.inStack })
        }
    }

    @Test
    fun `resolves line info for every line info encoding`() {
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("features_$suffix.luac")
            for (prototype in chunk.prototypes) {
                assertTrue(prototype.hasLineInfo)
                assertEquals(prototype.instructionCount, prototype.lineInfo.size)

                val resolved = prototype.resolvedLineInfo()
                assertEquals(prototype.instructionCount, resolved.size)
                assertTrue(resolved.any { it > 0 }, "instructions map to real source lines")

                // Nested prototypes know their real line range; the main chunk is <0,0>.
                if (prototype.lastLineDefined > 0) {
                    assertTrue(resolved.all { it >= prototype.lineDefined })
                    assertTrue(resolved.all { it <= prototype.lastLineDefined })
                }

                when (version) {
                    // 5.3 stores one absolute line per instruction.
                    LuaBytecodeVersion.LUA_5_3 -> {
                        assertTrue(prototype.absoluteLineInfo.isEmpty())
                        assertEquals(prototype.lineInfo, resolved)
                        if (prototype.lastLineDefined > 0) {
                            assertTrue(prototype.lineInfo.all { it in 0..prototype.lastLineDefined })
                        }
                    }
                    // 5.4 and 5.5 store 1 byte deltas plus absolute anchors.
                    LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5 -> {
                        assertTrue(prototype.lineInfo.all { it in -128..127 })
                        assertTrue(
                            prototype.absoluteLineInfo.zipWithNext().all { (a, b) -> a.pc <= b.pc },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `parses stripped chunks without debug info`() {
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("hello_${suffix}_stripped.luac")
            val main = chunk.mainPrototype

            assertNull(main.source)
            assertFalse(main.hasLineInfo)
            assertEquals(-1, main.lineForPc(0))
            assertTrue(main.resolvedLineInfo().isEmpty())
            assertTrue(main.localVariables.isEmpty())
            assertTrue(main.upvalueNames.isEmpty())

            // Stripping only removes debug info: code, constants and upvalues survive.
            assertEquals(2, chunk.prototypes.size)
            assertEquals(version, chunk.version)
            assertTrue(main.upvalues.isNotEmpty())
            assertTrue(chunk.allConstants().any { it is LuaConstant.Str })
            assertTrue(main.code.isNotEmpty())
        }
    }

    @Test
    fun `normal and stripped chunks expose the same code`() {
        for ((suffix, _) in FIXTURE_VERSIONS) {
            val normal = loadFixture("hello_$suffix.luac")
            val stripped = loadFixture("hello_${suffix}_stripped.luac")

            assertEquals(normal.prototypes.size, stripped.prototypes.size)
            normal.prototypes.zip(stripped.prototypes).forEach { (a, b) ->
                assertEquals(a.code, b.code, "instruction stream must not change when stripped")
                assertEquals(a.constants, b.constants)
                assertEquals(a.upvalues, b.upvalues)
                assertEquals(a.lineDefined, b.lineDefined)
                assertEquals(a.numParams, b.numParams)
                assertEquals(a.maxStackSize, b.maxStackSize)
            }
        }
    }

    @Test
    fun `string constants come from the source file`() {
        for ((suffix, _) in FIXTURE_VERSIONS) {
            val source = TestFixtures.text("hello.lua")
            val constants = loadFixture("hello_$suffix.luac")
                .allConstants()
                .filterIsInstance<LuaConstant.Str>()
            assertTrue(constants.isNotEmpty())
            for (constant in constants) {
                assertTrue(
                    source.contains(constant.value.utf8),
                    "constant \"${constant.value.utf8}\" does not appear in hello.lua",
                )
            }
        }
    }

    @Test
    fun `string constants are identical across versions for the same source`() {
        for (name in listOf("hello", "features", "big", "dedup", "flow")) {
            val reference = loadFixture("${name}_53.luac").stringConstantTexts()
            for (suffix in listOf("54", "55")) {
                assertEquals(reference, loadFixture("${name}_$suffix.luac").stringConstantTexts(), name)
            }
        }
    }

    @Test
    fun `5_5 reuses repeated strings through the index table`() {
        val chunk = loadFixture("dedup_55.luac")

        val literals = chunk.prototypes
            .flatMap { prototype -> prototype.constants.filterIsInstance<LuaConstant.Str>() }
            .map { it.value }
            .filter { it.utf8 == "shared_literal" }
        assertTrue(literals.size >= 2, "the fixture repeats the literal in several prototypes")
        literals.forEach { assertSame(literals[0], it, "5.5 must resolve the reuse index to one instance") }

        val localNames = chunk.prototypes
            .flatMap { it.localVariables }
            .mapNotNull { it.name }
            .filter { it.utf8 == "shared_name" }
        assertTrue(localNames.size >= 2)
        localNames.forEach { assertSame(localNames[0], it) }

        // 5.3 / 5.4 have no reuse table: equal strings are separate instances.
        val oldLiterals = loadFixture("dedup_53.luac").prototypes
            .flatMap { prototype -> prototype.constants.filterIsInstance<LuaConstant.Str>() }
            .map { it.value }
            .filter { it.utf8 == "shared_literal" }
        assertTrue(oldLiterals.size >= 2)
        assertTrue(oldLiterals.any { it !== oldLiterals[0] })
    }

    @Test
    fun `handles long strings and multi byte counts`() {
        val expectedLongPad = "lorem ipsum dolor sit amet ".repeat(12)

        for ((suffix, _) in FIXTURE_VERSIONS) {
            // >254 byte literal: 5.3 needs the 0xFF + size_t escape, 5.4 / 5.5 use varints.
            val longPad = loadFixture("features_$suffix.luac").stringConstant(expectedLongPad)
            assertTrue(longPad.size > 254)
            assertEquals(expectedLongPad.toByteArray(Charsets.UTF_8).size, longPad.size)
            assertTrue(longPad.bytes.contentEquals(expectedLongPad.toByteArray(Charsets.UTF_8)))

            val big = loadFixture("big_$suffix.luac")
            val fill = big.prototypes.single { it.constantCount >= 200 }
            assertTrue(fill.instructionCount > 127, "counts must exceed one varint byte")
            assertTrue(
                fill.code.any { it.opcodeName in setOf("SETTABLE", "SETFIELD", "SETTABUP", "SETI") },
                "the fixture must actually store into the table",
            )

            val huge = big.allConstants().filterIsInstance<LuaConstant.Str>().maxBy { it.value.size }
            assertEquals(5000, huge.value.size)
            assertTrue(huge.value.bytes.all { it in 'a'.code.toByte()..'j'.code.toByte() })

            if (big.version != LuaBytecodeVersion.LUA_5_3) {
                // 5.4 / 5.5 need absolute line anchors once a delta leaves one byte.
                assertTrue(fill.absoluteLineInfo.isNotEmpty())
                assertEquals(fill.instructionCount, fill.lineInfo.size)
                assertTrue((fill.resolvedLineInfo().maxOrNull() ?: 0) > 0)
            }
        }
    }

    @Test
    fun `exposes the opcode tables`() {
        assertEquals(47, LuaOpcodes.names(LuaBytecodeVersion.LUA_5_3).size)
        assertEquals(83, LuaOpcodes.names(LuaBytecodeVersion.LUA_5_4).size)
        assertEquals(85, LuaOpcodes.names(LuaBytecodeVersion.LUA_5_5).size)
        assertEquals("MOVE", LuaOpcodes.nameOf(LuaBytecodeVersion.LUA_5_3, 0))
        assertEquals("EXTRAARG", LuaOpcodes.nameOf(LuaBytecodeVersion.LUA_5_3, 46))
        assertEquals("VARARGPREP", LuaOpcodes.nameOf(LuaBytecodeVersion.LUA_5_4, 81))
        assertEquals("EXTRAARG", LuaOpcodes.nameOf(LuaBytecodeVersion.LUA_5_4, 82))
        assertEquals("MMBIN", LuaOpcodes.nameOf(LuaBytecodeVersion.LUA_5_4, 46))
        // 5.5 inserted GETVARG / ERRNNIL before VARARGPREP and swapped SHLI / SHRI.
        assertEquals("SHLI", LuaOpcodes.nameOf(LuaBytecodeVersion.LUA_5_5, 32))
        assertEquals("SHRI", LuaOpcodes.nameOf(LuaBytecodeVersion.LUA_5_5, 33))
        assertEquals("GETVARG", LuaOpcodes.nameOf(LuaBytecodeVersion.LUA_5_5, 81))
        assertEquals("ERRNNIL", LuaOpcodes.nameOf(LuaBytecodeVersion.LUA_5_5, 82))
        assertEquals("VARARGPREP", LuaOpcodes.nameOf(LuaBytecodeVersion.LUA_5_5, 83))
        assertEquals("EXTRAARG", LuaOpcodes.nameOf(LuaBytecodeVersion.LUA_5_5, 84))
        assertEquals(34, LuaOpcodes.opcodeOf(LuaBytecodeVersion.LUA_5_4, "ADD"))
        assertEquals("UNKNOWN(47)", LuaOpcodes.nameOf(LuaBytecodeVersion.LUA_5_3, 47))
    }
}
