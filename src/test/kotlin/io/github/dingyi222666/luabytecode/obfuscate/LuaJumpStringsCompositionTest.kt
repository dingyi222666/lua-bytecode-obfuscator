package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.analysis.basicBlockStarts
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTargets
import io.github.dingyi222666.luabytecode.bytecode.FIXTURE_VERSIONS
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import io.github.dingyi222666.luabytecode.bytecode.loadFixture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cross-pass integration acceptance (root): the jump / junk passes must compose
 * with real string-encryption stubs — the appended-block principle guarantees
 * stub blocks written by :lua-obfuscation-strings stay plain and valid when the
 * :lua-obfuscation-jumps passes append after them.
 */
class LuaJumpStringsCompositionTest {

    @Test
    fun `every pass composes with string encryption`() {
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("flow_$suffix.luac")

            LuaStringEncryptor.encrypt(chunk, StringEncryptionConfig(minLength = 6))
            val indirection = LuaJumpIndirection.apply(chunk, JumpIndirectionConfig(depth = 2, junkPerHop = 1))
            val junk = LuaJunkCode.apply(chunk, JunkCodeConfig(junkPerBlock = 2, guardedBranches = 2))

            assertTrue(indirection.redirectedJumps > 0, "5.$version: no jump redirected")
            assertTrue(junk.guardedJumps > 0, "5.$version: no branch guarded")
            assertTargetsValid("flow_$suffix.luac", chunk)
            LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
        }
    }

    private fun assertTargetsValid(name: String, chunk: LuaChunk) {
        for (prototype in chunk.prototypes) {
            val starts = prototype.basicBlockStarts()
            for ((pc, target) in prototype.jumpTargets()) {
                assertTrue(target in 0..prototype.code.size, "$name: jump $pc -> $target out of range")
                assertTrue(target in starts, "$name: jump $pc -> $target is not a block start")
            }
        }
    }
}
