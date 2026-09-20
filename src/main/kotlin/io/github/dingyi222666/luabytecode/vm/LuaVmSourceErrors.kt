package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.LuaString

/** ERRNNIL has a specified diagnostic; retain it inside the encrypted constant pool.
 * Chunk IDs follow luaO_chunkid (LUA_IDSIZE=60), including byte-based truncation. */
internal object LuaVmSourceErrors {
    fun alreadyDefined(prototype: LuaPrototype, pc: Int, nameIndex: Int): LuaConstant.Str {
        val name = if (nameIndex == 0) byteArrayOf('?'.code.toByte()) else
            (prototype.constants[nameIndex - 1] as LuaConstant.Str).value.bytes.takeWhile { it != 0.toByte() }.toByteArray()
        val source = prototype.source?.bytes
        val prefix = if (source == null) "?:?: ".toByteArray() else
            chunkId(source) + ":${prototype.lineForPc(pc)}: ".toByteArray()
        return LuaConstant.Str(LuaString.of(prefix + "global '".toByteArray() + name + "' already defined".toByteArray()))
    }

    private fun chunkId(source: ByteArray): ByteArray = when (source.firstOrNull()?.toInt()) {
        '='.code -> source.drop(1).take(59).toByteArray()
        '@'.code -> if (source.size <= 60) source.drop(1).toByteArray()
            else "...".toByteArray() + source.takeLast(56).toByteArray()
        else -> {
            val line = source.takeWhile { it != '\n'.code.toByte() }.toByteArray()
            val body = if (source.size < 45 && line.size == source.size) line
                else line.take(45).toByteArray() + "...".toByteArray()
            "[string \"".toByteArray() + body + "\"]".toByteArray()
        }
    }
}
