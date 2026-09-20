import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import io.github.dingyi222666.luabytecode.obfuscate.LuaRegisterObfuscator
import io.github.dingyi222666.luabytecode.obfuscate.RegisterObfuscationConfig
import java.io.File

fun main(args: Array<String>) {
    val version = when (args[1]) { "53" -> LuaBytecodeVersion.LUA_5_3; "54" -> LuaBytecodeVersion.LUA_5_4; else -> LuaBytecodeVersion.LUA_5_5 }
    val chunk = LuaChunkLoader.load(File(args[0]).readBytes(), version)
    val report = LuaRegisterObfuscator.apply(
        chunk,
        RegisterObfuscationConfig(
            localNameRewrite = true,
            registerRenaming = true,
            registerMerging = true,
            registerSplitting = true,
            seed = 7L,
        ),
    )
    File(args[2]).writeBytes(LuaChunkWriter.dump(chunk))
    println("eff: perm=${report.renamedRegisterPrototypes} merged=${report.mergedRegisters} split=${report.splitRanges} skip=${report.skippedPrototypes}")
}
