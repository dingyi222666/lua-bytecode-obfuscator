package io.github.dingyi222666.luabytecode.bytecode.edit

import io.github.dingyi222666.luabytecode.bytecode.ALL_FIXTURES
import io.github.dingyi222666.luabytecode.bytecode.FIXTURE_VERSIONS
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaOpcodes
import io.github.dingyi222666.luabytecode.bytecode.LuaString
import io.github.dingyi222666.luabytecode.bytecode.LuaUpvalue
import io.github.dingyi222666.luabytecode.bytecode.TestFixtures
import io.github.dingyi222666.luabytecode.bytecode.analysis.LuaLineInfo
import io.github.dingyi222666.luabytecode.bytecode.analysis.setAbsoluteLines
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import io.github.dingyi222666.luabytecode.bytecode.loadFixture
import io.github.dingyi222666.luabytecode.bytecode.stringConstantTexts

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue


class LuaChunkEditTest {

    @Test
    fun `line info encoders round trip absolute lines`() {
        val lines = listOf(1, 2, 2, 3, 100, 300, 4200, 0, 5)
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val prototype = loadFixture("hello_$suffix.luac").mainPrototype
            prototype.setAbsoluteLines(lines)

            assertEquals(lines, prototype.resolvedLineInfo())
            if (version == LuaBytecodeVersion.LUA_5_3) {
                assertEquals(lines, prototype.lineInfo)
                assertTrue(prototype.absoluteLineInfo.isEmpty())
            } else {
                assertTrue(prototype.lineInfo.all { it in -128..127 })
                assertTrue(prototype.lineInfo.any { it == LuaLineInfo.ABSOLUTE_LINE_INFO })
                assertTrue(prototype.absoluteLineInfo.isNotEmpty())
            }
        }
    }

    @Test
    fun `replaceStringConstants rewrites and reports the count`() {
        val chunk = loadFixture("hello_55.luac")
        val replaced = chunk.replaceStringConstants { _, value -> value.replacing("hello world", "HELLO WORLD") }

        assertEquals(1, replaced)
        assertTrue(chunk.stringConstants().none { it.second.utf8 == "hello world, 你好 世界" })
        assertTrue(chunk.stringConstants().any { it.second.utf8 == "HELLO WORLD, 你好 世界" })

        val reloaded = LuaChunkLoader.load(LuaChunkWriter.dump(chunk))
        assertTrue(reloaded.stringConstants().any { it.second.utf8 == "HELLO WORLD, 你好 世界" })
    }

    @Test
    fun `replaceStringConstants keeps values when the transform returns null`() {
        val chunk = loadFixture("hello_53.luac")
        val before = chunk.stringConstantTexts()

        assertEquals(0, chunk.replaceStringConstants { _, _ -> null })
        assertEquals(before, chunk.stringConstantTexts())

        assertEquals(0, chunk.mainPrototype.replaceStringConstants { null })
        assertEquals(before, chunk.stringConstantTexts())
    }

    @Test
    fun `internString reuses equal constants`() {
        val main = loadFixture("hello_53.luac").mainPrototype
        val size = main.constantCount

        val first = main.internString("reused")
        assertEquals(first, main.internString("reused"))
        assertEquals(size + 1, main.constantCount)

        // An already present literal is found instead of duplicated.
        assertEquals(main.indexOfStringConstant("print"), main.internString("print"))
        assertEquals(LuaString.of("print"), main.stringConstantAt(main.indexOfStringConstant("print")))
        assertNull(main.stringConstantAt(main.indexOfConstant { it is LuaConstant.Integer }))
    }

    @Test
    fun `inserting an instruction keeps line info and locals consistent`() {
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("features_$suffix.luac")
            val prototype = chunk.prototypes.first { it.localVariables.isNotEmpty() }
            val prototypeIndex = chunk.prototypes.indexOf(prototype)
            val linesBefore = prototype.resolvedLineInfo()
            val localsBefore = prototype.localVariables.toList()
            val index = prototype.instructionCount / 2

            prototype.insertInstruction(
                index,
                LuaInstruction.abc(version, opcode = LuaOpcodes.opcodeOf(version, "MOVE")!!, a = 0, b = 0),
                line = linesBefore[index],
            )

            assertEquals(linesBefore.size + 1, prototype.lineInfo.size)
            val linesAfter = prototype.resolvedLineInfo()
            assertEquals(linesBefore, linesAfter.filterIndexed { i, _ -> i != index })
            assertEquals(linesBefore[index], linesAfter[index])

            localsBefore.zip(prototype.localVariables).forEach { (before, after) ->
                assertEquals(if (before.startPc >= index) before.startPc + 1 else before.startPc, after.startPc)
                assertEquals(if (before.endPc >= index) before.endPc + 1 else before.endPc, after.endPc)
            }

            // The edited chunk must still dump and reload.
            val reloaded = LuaChunkLoader.load(LuaChunkWriter.dump(chunk))
            assertEquals(
                prototype.instructionCount,
                reloaded.prototypes[prototypeIndex].instructionCount,
            )
        }
    }

    @Test
    fun `instruction edits default the new line to the surrounding instruction`() {
        for ((suffix, _) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("features_$suffix.luac")
            val prototype = chunk.mainPrototype
            val lines = prototype.resolvedLineInfo()
            val index = 3

            prototype.insertInstruction(
                index,
                LuaInstruction.abx(prototype.version, opcode = LuaOpcodes.opcodeOf(prototype.version, "LOADK")!!, a = 0, bx = 0),
            )
            assertEquals(lines[index], prototype.resolvedLineInfo()[index])

            prototype.removeInstruction(index)
            assertEquals(lines, prototype.resolvedLineInfo())
        }
    }

    @Test
    fun `removing then re-inserting restores code lines and locals`() {
        for ((suffix, _) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("features_$suffix.luac")
            val prototype = chunk.mainPrototype
            val codeBefore = prototype.code.toList()
            val linesBefore = prototype.resolvedLineInfo()
            val localsBefore = prototype.localVariables.toList()
            // Removing and re-inserting is only bit exact when the index is not a
            // local variable boundary, because the shifts anchor on the boundary.
            val index = (0 until prototype.instructionCount).first { candidate ->
                prototype.localVariables.none { it.startPc == candidate || it.endPc == candidate }
            }

            val removed = prototype.removeInstruction(index)
            assertEquals(codeBefore[index], removed)
            assertEquals(codeBefore.size - 1, prototype.instructionCount)
            assertEquals(linesBefore.filterIndexed { i, _ -> i != index }, prototype.resolvedLineInfo())

            prototype.insertInstruction(index, removed, line = linesBefore[index])
            assertEquals(codeBefore, prototype.code)
            assertEquals(linesBefore, prototype.resolvedLineInfo())
            assertEquals(localsBefore, prototype.localVariables)
        }
    }

    @Test
    fun `remove then insert round trips the lines of every fixture`() {
        // The exact T1 invariant, for every fixture version: re-encoding the
        // shifted table must reproduce the original absolute lines. Before the
        // MAXIWTHABS spacing fix this produced garbage (e.g. 5, -60, -59) on
        // long prototypes such as big_55's >200 constant one.
        for (name in ALL_FIXTURES) {
            val chunk = loadFixture(name)
            for ((prototypeIndex, prototype) in chunk.prototypes.withIndex()) {
                if (prototype.lineInfo.isEmpty() || prototype.instructionCount < 2) {
                    continue
                }
                val linesBefore = prototype.resolvedLineInfo()
                val index = prototype.instructionCount / 2

                val removed = prototype.removeInstruction(index)
                assertEquals(
                    linesBefore.filterIndexed { i, _ -> i != index },
                    prototype.resolvedLineInfo(),
                    "$name proto #$prototypeIndex: lines after removal",
                )

                prototype.insertInstruction(index, removed, line = linesBefore[index])
                assertEquals(
                    linesBefore,
                    prototype.resolvedLineInfo(),
                    "$name proto #$prototypeIndex: remove+insert did not restore the lines",
                )
            }
        }
    }

    @Test
    fun `removing a range shifts everything back`() {
        val chunk = loadFixture("big_55.luac")
        val prototype = chunk.prototypes.single { it.constantCount >= 200 }
        val before = prototype.code.toList()
        val linesBefore = prototype.resolvedLineInfo()

        val removed = prototype.removeInstructions(4..6)
        assertEquals(3, removed)
        assertEquals(before.size - 3, prototype.instructionCount)
        assertEquals(
            before.filterIndexed { i, _ -> i !in 4..6 },
            prototype.code,
        )
        assertEquals(
            linesBefore.filterIndexed { i, _ -> i !in 4..6 },
            prototype.resolvedLineInfo(),
        )

        // Still loadable after the edit.
        LuaChunkLoader.load(LuaChunkWriter.dump(chunk))
    }

    @Test
    fun `stripping removes only debug information`() {
        val chunk = loadFixture("features_55.luac")
        val codeBefore = chunk.prototypes.map { it.code.toList() }
        val constantsBefore = chunk.prototypes.map { it.constants.toList() }

        chunk.clearDebugInfo()

        for (prototype in chunk.prototypes) {
            assertNull(prototype.source)
            assertTrue(prototype.lineInfo.isEmpty())
            assertTrue(prototype.absoluteLineInfo.isEmpty())
            assertTrue(prototype.localVariables.isEmpty())
            assertTrue(prototype.upvalueNames.isEmpty())
        }
        assertEquals(codeBefore, chunk.prototypes.map { it.code })
        assertEquals(constantsBefore, chunk.prototypes.map { it.constants })

        val reloaded = LuaChunkLoader.load(LuaChunkWriter.dump(chunk))
        assertTrue(reloaded.prototypes.all { !it.hasLineInfo })
        assertEquals(codeBefore, reloaded.prototypes.map { it.code })
    }

    @Test
    fun `dsl edits a prototype tree`() {
        val chunk = loadFixture("features_55.luac")

        chunk.edit {
            eachPrototype {
                replaceStringText("lorem", "LOREM")
            }
            main {
                patch(0) { it.withA(0) }
                append(instruction { opcode = opcode("RETURN"); a = 0; b = 1; c = 1; k = 1 }, line = 1)
                child(0) {
                    // nested prototype scope
                    assertEquals(1, prototype.numParams)
                }
            }
        }

        assertTrue(chunk.stringConstants().none { it.second.utf8.startsWith("lorem") })
        assertTrue(chunk.stringConstants().any { it.second.utf8.startsWith("LOREM") })

        val reloaded = LuaChunkLoader.load(LuaChunkWriter.dump(chunk))
        assertEquals(chunk.mainPrototype.instructionCount, reloaded.mainPrototype.instructionCount)
        val last = reloaded.mainPrototype.code.last()
        assertEquals("RETURN", last.opcodeName)
        assertEquals(1, last.k)
    }

    @Test
    fun `dsl can walk every prototype and strip them`() {
        val chunk = loadFixture("dedup_55.luac")
        var visited = 0
        chunk.edit {
            prototypeWhere({ it.nestedPrototypes.isEmpty() }) {
                visited++
                assertTrue(instructionCount > 0)
            }
            eachPrototype { strip() }
        }
        assertEquals(1, visited)
        assertTrue(chunk.prototypes.all { !it.hasLineInfo })
    }

    @Test
    fun `editChunkFile loads edits and dumps in one call`() {
        val directory = Files.createTempDirectory("lua-bytecode-edit").toFile()
        try {
            val input = directory.resolve("in.luac")
            input.writeBytes(TestFixtures.bytes("hello_53.luac"))
            val output = directory.resolve("out.luac")

            val chunk = editChunkFile(input, output) {
                eachPrototype { strip() }
                replaceStringConstants { prototype, value ->
                    if (prototype.version == LuaBytecodeVersion.LUA_5_3) value.replacing("hello", "HELLO") else value
                }
            }

            assertTrue(output.isFile)
            assertTrue(chunk.prototypes.all { it.source == null })
            assertTrue(chunk.stringConstants().any { it.second.utf8.startsWith("HELLO") })

            val reloaded = LuaChunkLoader.load(output)
            assertTrue(reloaded.prototypes.all { !it.hasLineInfo })
            assertTrue(reloaded.stringConstants().any { it.second.utf8.startsWith("HELLO") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `addUpvalue keeps the debug names consistent`() {
        val chunk = loadFixture("hello_55.luac")
        val main = chunk.mainPrototype
        val namesBefore = main.upvalueNames.size

        val index = main.addUpvalue(LuaUpvalue(inStack = true, index = 0, kind = 0), LuaString.of("extra"))

        assertEquals(main.upvalues.size - 1, index)
        assertEquals(namesBefore + 1, main.upvalueNames.size)
        assertEquals(main.upvalues.size, main.upvalueNames.size)

        val reloaded = LuaChunkLoader.load(LuaChunkWriter.dump(chunk))
        assertEquals(main.upvalues.size, reloaded.mainPrototype.upvalues.size)
        assertEquals(main.upvalueNames, reloaded.mainPrototype.upvalueNames)
    }

    @Test
    fun `rejects out of range instruction edits`() {
        val prototype = loadFixture("hello_55.luac").mainPrototype
        assertFailsWith<IllegalArgumentException> { prototype.removeInstruction(prototype.instructionCount) }
        assertFailsWith<IllegalArgumentException> { prototype.insertInstruction(-1, prototype.code[0]) }
        assertFailsWith<IllegalArgumentException> { prototype.removeInstructions(0..prototype.instructionCount) }
    }

    @Test
    fun `addNestedPrototype extends the tree`() {
        val chunk = loadFixture("hello_55.luac")
        val main = chunk.mainPrototype
        val child = main.nestedPrototypes[0]
        val index = main.addNestedPrototype(child)

        assertEquals(main.nestedPrototypes.size - 1, index)
        assertEquals(3, chunk.prototypes.size)

        val reloaded = LuaChunkLoader.load(LuaChunkWriter.dump(chunk))
        assertEquals(3, reloaded.prototypes.size)
    }
}
