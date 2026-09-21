package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader

/**
 * Classpath fixture loader for the :lua-vm jvmTest suite. The vm tests originally
 * loaded `src/test/resources/...` relative to the root module; since the migration
 * (Phase 5) the fixture subset lives in this module's jvmTest resources and is read
 * through the classpath like every other feature module's tests.
 *
 * Fixture names are `real/ctor/<source>_<version>.luac`, compiled by real
 * luac 5.3.6 / 5.4.6 / 5.5.1.
 */
internal fun loadVmFixture(name: String, version: LuaBytecodeVersion): LuaChunk =
    LuaChunkLoader.load(
        checkNotNull(VmTestChunks.RESOURCES.getResourceAsStream("/real/ctor/$name")) {
            "missing fixture /real/ctor/$name in :lua-vm jvmTest resources"
        }.use { it.readBytes() },
        version,
    )

private object VmTestChunks {
    val RESOURCES: ClassLoader = VmTestChunks::class.java.classLoader
}
