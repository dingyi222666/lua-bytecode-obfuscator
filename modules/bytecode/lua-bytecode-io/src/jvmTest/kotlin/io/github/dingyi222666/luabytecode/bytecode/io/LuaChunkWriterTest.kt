package io.github.dingyi222666.luabytecode.bytecode.io

import io.github.dingyi222666.luabytecode.platform.jvm.dump
import io.github.dingyi222666.luabytecode.platform.jvm.load
import io.github.dingyi222666.luabytecode.bytecode.ALL_FIXTURES
import io.github.dingyi222666.luabytecode.bytecode.LuaAbsoluteLineInfo
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaString
import io.github.dingyi222666.luabytecode.bytecode.TestFixtures
import io.github.dingyi222666.luabytecode.bytecode.loadFixture

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue


class LuaChunkWriterTest {

    @Test
    fun `dumping a parsed chunk reproduces it byte for byte`() {
        for (name in ALL_FIXTURES) {
            val original = TestFixtures.bytes(name)
            val chunk = LuaChunkLoader.load(original)
            val dumped = LuaChunkWriter.dump(chunk)
            assertContentEquals(original, dumped, "$name does not round trip")
        }
    }

    @Test
    fun `dumping a chunk twice is stable`() {
        for (name in ALL_FIXTURES) {
            val first = LuaChunkWriter.dump(LuaChunkLoader.load(TestFixtures.bytes(name)))
            val second = LuaChunkWriter.dump(LuaChunkLoader.load(first))
            assertContentEquals(first, second, name)
        }
    }

    @Test
    fun `a dumped chunk loads back into an equal tree`() {
        for (name in ALL_FIXTURES) {
            val chunk = LuaChunkLoader.load(TestFixtures.bytes(name))
            val reparsed = LuaChunkLoader.load(LuaChunkWriter.dump(chunk))

            assertEquals(chunk.header, reparsed.header)
            assertEquals(chunk.mainUpvalueCount, reparsed.mainUpvalueCount)
            assertEquals(chunk.prototypes.size, reparsed.prototypes.size)

            chunk.prototypes.zip(reparsed.prototypes).forEach { (a, b) ->
                assertEquals(a.source, b.source)
                assertEquals(a.lineDefined, b.lineDefined)
                assertEquals(a.lastLineDefined, b.lastLineDefined)
                assertEquals(a.numParams, b.numParams)
                assertEquals(a.isVararg, b.isVararg)
                assertEquals(a.isVarargFlags, b.isVarargFlags)
                assertEquals(a.maxStackSize, b.maxStackSize)
                assertEquals(a.code, b.code)
                assertEquals(a.constants, b.constants)
                assertEquals(a.upvalues, b.upvalues)
                assertEquals(a.nestedPrototypes.size, b.nestedPrototypes.size)
                assertEquals(a.lineInfo, b.lineInfo)
                assertEquals(a.absoluteLineInfo, b.absoluteLineInfo)
                assertEquals(a.localVariables, b.localVariables)
                assertEquals(a.upvalueNames, b.upvalueNames)
            }
        }
    }

    @Test
    fun `dumps to a stream and to a file`() {
        val chunk = loadFixture("features_55.luac")
        val expected = TestFixtures.bytes("features_55.luac")

        val stream = ByteArrayOutputStream()
        LuaChunkWriter.dump(chunk, stream)
        assertContentEquals(expected, stream.toByteArray())

        val directory = Files.createTempDirectory("lua-bytecode-dump").toFile()
        try {
            val file = directory.resolve("out.luac")
            LuaChunkWriter.dump(chunk, file)
            assertContentEquals(expected, file.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `rejects line info that does not match the code`() {
        val chunk = loadFixture("features_55.luac")
        chunk.mainPrototype.lineInfo += 1
        val error = assertFailsWith<LuaBytecodeFormatException> { LuaChunkWriter.dump(chunk) }
        assertTrue(error.message!!.contains("lineInfo"))
    }

    @Test
    fun `rejects absolute line info on 5_3`() {
        val chunk = loadFixture("features_53.luac")
        chunk.mainPrototype.absoluteLineInfo += LuaAbsoluteLineInfo(0, 1)
        val error = assertFailsWith<LuaBytecodeFormatException> { LuaChunkWriter.dump(chunk) }
        assertTrue(error.message!!.contains("absolute line info"))
    }

    @Test
    fun `rejects mismatched upvalue names`() {
        val chunk = loadFixture("features_55.luac")
        chunk.mainPrototype.upvalueNames += LuaString.of("extra")
        val error = assertFailsWith<LuaBytecodeFormatException> { LuaChunkWriter.dump(chunk) }
        assertTrue(error.message!!.contains("upvalueNames"))
    }
}
