package io.github.dingyi222666.luabytecode.bytecode.print

import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaString
import io.github.dingyi222666.luabytecode.bytecode.loadFixture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


class LuaChunkPrinterTest {

    @Test
    fun `prints the header and the whole prototype tree`() {
        val chunk = loadFixture("features_54.luac")
        val text = LuaChunkPrinter.print(chunk)

        assertTrue(text.contains("Lua 5.4 chunk"))
        assertTrue(text.contains("little-endian"))
        assertTrue(text.contains("main closure upvalues: 1"))
        assertTrue(text.contains("prototype #0"))
        assertTrue(text.contains("prototype #1"))
        assertTrue(text.contains("@features.lua"))
        assertTrue(text.contains("VARARGPREP"))
        assertTrue(text.contains("constants ("))
        assertTrue(text.contains("upvalues ("))
        assertTrue(text.contains("中文常量"))
        assertTrue(text.contains("; line "))
        assertTrue(text.contains("nested prototypes ("))
    }

    @Test
    fun `prints 5_3 chunks with absolute lines`() {
        val text = LuaChunkPrinter.print(loadFixture("hello_53.luac"))
        assertTrue(text.contains("Lua 5.3 chunk"))
        assertTrue(text.contains("@hello.lua"))
        assertTrue(text.contains("; line "))
    }

    @Test
    fun `can hide instructions and debug sections`() {
        val text = LuaChunkPrinter.print(
            loadFixture("features_53.luac"),
            includeCode = false,
            includeDebug = false,
        )
        assertFalse(text.contains("instructions:"))
        assertFalse(text.contains("locals ("))
        assertTrue(text.contains("prototype #0"))
        assertTrue(text.contains("constants ("))
    }

    @Test
    fun `escapes special characters`() {
        assertEquals("\\n\\t\\\\\\\"", LuaChunkPrinter.escape("\n\t\\\""))
        assertEquals("\\x01\\x7f", LuaChunkPrinter.escape("\u0001\u007f"))
        assertTrue(LuaChunkPrinter.escape("abcdef", maxLength = 3).endsWith("..."))
    }

    @Test
    fun `formats every constant kind`() {
        assertEquals("nil", LuaChunkPrinter.formatConstant(LuaConstant.Nil))
        assertEquals("true", LuaChunkPrinter.formatConstant(LuaConstant.Boolean(true)))
        assertEquals("false", LuaChunkPrinter.formatConstant(LuaConstant.Boolean(false)))
        assertEquals("42", LuaChunkPrinter.formatConstant(LuaConstant.Integer(42)))
        assertEquals("0.5", LuaChunkPrinter.formatConstant(LuaConstant.Number(0.5)))
        val string = LuaChunkPrinter.formatConstant(LuaConstant.Str(LuaString.of("abc")))
        assertTrue(string.startsWith("\"abc\""))
        assertTrue(string.contains("len=3"))
    }
}
