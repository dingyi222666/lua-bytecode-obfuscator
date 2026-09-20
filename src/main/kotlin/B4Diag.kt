import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader

fun main(args: Array<String>) {
    val version = when (args[1]) { "53" -> LuaBytecodeVersion.LUA_5_3; "54" -> LuaBytecodeVersion.LUA_5_4; else -> LuaBytecodeVersion.LUA_5_5 }
    val chunk = LuaChunkLoader.load(java.io.File(args[0]).readBytes(), version)
    val p = if (args.size > 3) chunk.prototypes.first { it.lineDefined == args[3].toInt() } else chunk.mainPrototype
    for ((pc, i) in p.code.withIndex()) println("[%3d] %s".format(pc, i))
}
