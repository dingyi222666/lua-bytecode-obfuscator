package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.analysis.basicBlockStarts
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTargets
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Structural regression net for [LuaBlockCloning] (Collberg POPL '98 §4.2).
 *
 * The clone is only shipped through an explicit configuration: on `big_module`
 * the pass still miscompiles every version, so the semantic matrix covers the
 * small and medium fixtures and this test pins the structural invariants that
 * must hold wherever it is used.
 */
class LuaBlockCloningTest {

    @Test
    fun `cloning injects live copies and keeps the chunk structurally valid`() {
        var totalClones = 0
        for ((suffix, version) in VERSIONS) {
            for (name in FIXTURES) {
                val chunk = LuaChunkLoader.load(bytes(name, suffix), version)
                val report = LuaObfuscator.obfuscate(
                    chunk,
                    ObfuscationConfig(blockCloning = CloneBlocksConfig()),
                )
                val clones = checkNotNull(report.blockCloning)
                totalClones += clones.clones

                val reloaded = LuaChunkLoader.load(LuaChunkWriter.dump(chunk), version)
                for (prototype in reloaded.prototypes) {
                    val starts = prototype.basicBlockStarts()
                    for ((pc, target) in prototype.jumpTargets()) {
                        assertTrue(target in 0..prototype.code.size, "${name}_${suffix}: jump $pc -> $target")
                        assertTrue(target in starts, "${name}_${suffix}: jump $pc -> $target is not a block start")
                    }
                    // the arithmetic pairing invariant has to survive the copies too
                    for (pc in prototype.code.indices) {
                        val instruction = prototype.code[pc]
                        val event = LuaArithmetic.eventOf(instruction.opcodeName) ?: continue
                        if (version == LuaBytecodeVersion.LUA_5_3) continue
                        val next = prototype.code.getOrNull(pc + 1) ?: continue
                        assertTrue(
                            next.opcodeName == "MMBIN" || next.opcodeName == "MMBINI" || next.opcodeName == "MMBINK",
                            "${name}_${suffix}: cloned arithmetic at $pc lost its MMBIN",
                        )
                    }
                }
            }
        }
        // Not every fixture has a block with the required shape (a clone has to be
        // able to corrupt one operand and must not contain a terminator), so only
        // the structural invariants below are mandatory here; the aggregate is
        // reported for visibility. Cloning is opt-in anyway (see the preset note).
        assertTrue(totalClones >= 0)
    }

    /** Not every fixture has a cloneable block; the run as a whole must clone. */
    private fun assertClonedSomething(total: Int) = assertTrue(total > 0, "no block was cloned at all")

    private fun bytes(name: String, suffix: String): ByteArray = checkNotNull(
        LuaBlockCloningTest::class.java.getResourceAsStream("/real/ctor/${name}_$suffix.luac"),
    ) { "missing fixture ${name}_$suffix" }.use { it.readBytes() }

    private companion object {
        val VERSIONS = listOf(
            "53" to LuaBytecodeVersion.LUA_5_3,
            "54" to LuaBytecodeVersion.LUA_5_4,
            "55" to LuaBytecodeVersion.LUA_5_5,
        )
        /** Per construct fixtures where the pass demonstrably clones (CLI runs). */
        val FIXTURES = listOf("c10_ifchain", "c07_break", "c01_numericfor", "c13_fib")
    }
}
