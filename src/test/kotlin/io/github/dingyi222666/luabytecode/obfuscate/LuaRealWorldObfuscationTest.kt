package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.analysis.basicBlockStarts
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTargets
import io.github.dingyi222666.luabytecode.bytecode.analysis.loadKSites
import io.github.dingyi222666.luabytecode.bytecode.analysis.referencedConstantIndices
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real world Lua programs (`src/test/resources/real/<name>.lua`, compiled by the real
 * luac 5.3.6 / 5.4.6 / 5.5.1): metatables + string formatting (`bank`), a
 * recursive encoder with pattern escapes (`json`), nested loops / recursion
 * (`algo`) and the 506 line / 50 prototype `big_module`.
 *
 * These are the fixtures that found the interaction bug between jump
 * indirection and junk code on 5.4 / 5.5 (`algo_54`: `attempt to perform 'n%0'`
 * with `--jmp 3 --junk 4 --rings 4:3`), so they are the regression net for it.
 *
 * Note: the tests here are structural (dump -> reload, jump target validity,
 * plaintext leakage). Semantics are verified separately by executing the
 * obfuscated chunks with the real interpreters, because a Lua VM is not
 * available inside the JVM test.
 */
class LuaRealWorldObfuscationTest {

    @Test
    fun `real world fixtures round trip byte exactly`() {
        for ((suffix, version) in VERSIONS) {
            for (name in PROGRAMS) {
                val bytes = bytes("${name}_$suffix.luac")
                val chunk = LuaChunkLoader.load(bytes, version)
                assertTrue(chunk.prototypes.size >= 2, "${name}_$suffix should have nested functions")
                assertTrue(
                    LuaChunkWriter.dump(chunk).contentEquals(bytes),
                    "${name}_$suffix does not round trip",
                )
            }
        }
    }

    @Test
    fun `super configuration keeps every real world fixture loadable`() {
        var anyEncrypted = false
        for ((suffix, version) in VERSIONS) {
            for (name in PROGRAMS) {
                val chunk = LuaChunkLoader.load(bytes("${name}_$suffix.luac"), version)

                // The encryption contract: a constant is encrypted in place only
                // when it is referenced *exclusively* by LOADK. A constant that is
                // also used as an RK key operand (table field, GETTABUP name, ...)
                // has to stay readable or the chunk breaks.
                val encryptable = HashSet<String>()
                val sharedKeys = HashSet<String>()
                for (prototype in chunk.prototypes) {
                    val shared = prototype.referencedConstantIndices()
                    val loadKIndices = prototype.loadKSites().keys
                    for ((index, constant) in prototype.constants.withIndex()) {
                        val string = constant as? LuaConstant.Str ?: continue
                        val text = string.value.utf8
                        if (index in shared) {
                            if (text.length >= 8) {
                                sharedKeys += text
                            }
                            continue
                        }
                        if (index in loadKIndices && string.value.size >= SUPER.stringEncryption!!.minLength) {
                            encryptable += text
                        }
                    }
                }

                val report = LuaObfuscator.obfuscate(chunk, SUPER)

                val dumped = LuaChunkWriter.dump(chunk)
                val reloaded = LuaChunkLoader.load(dumped, version)
                assertEquals(chunk.prototypes.size, reloaded.prototypes.size, "${name}_$suffix prototype count")
                assertTrue(
                    reloaded.prototypes.all { prototype ->
                        prototype.lineInfo.isEmpty() || prototype.lineInfo.size == prototype.instructionCount
                    },
                    "${name}_$suffix line info must stay consistent",
                )
                for (prototype in reloaded.prototypes) {
                    val starts = prototype.basicBlockStarts()
                    for ((pc, target) in prototype.jumpTargets()) {
                        assertTrue(
                            target in 0..prototype.code.size,
                            "${name}_$suffix: jump at $pc targets $target out of range",
                        )
                        assertTrue(target in starts, "${name}_$suffix: jump at $pc targets $target, not a block start")
                    }
                }

                // every encryptable literal must be gone from the constant pool...
                val remaining = reloaded.prototypes
                    .flatMap { it.constants }
                    .filterIsInstance<LuaConstant.Str>()
                    .map { it.value.utf8 }
                    .toSet()
                val leaked = encryptable.filter { it in remaining }
                assertTrue(leaked.isEmpty(), "${name}_$suffix: ${leaked.size} encryptable literal(s) stayed in the clear")

                // ... and out of the raw dumped bytes, where the ciphertext lives.
                val raw = dumped.toString(Charsets.ISO_8859_1)
                for (text in encryptable) {
                    assertFalse(raw.contains(text), "${name}_$suffix: \"$text\" is still readable in the dump")
                }

                // A shared key literal must still be readable: this is the guard
                // against "never encrypt a constant that is used as a key".
                if (sharedKeys.isNotEmpty()) {
                    val lost = sharedKeys.filter { it !in remaining }
                    assertTrue(
                        lost.isEmpty(),
                        "${name}_$suffix: shared key literal(s) must stay readable, but $lost disappeared",
                    )
                }
                assertTrue(report.stringEncryption != null, "${name}_$suffix: string encryption did not run")
                if (encryptable.isNotEmpty()) {
                    assertTrue(
                        report.stringEncryption.encryptedConstants > 0,
                        "${name}_$suffix: ${encryptable.size} encryptable literal(s) but none was encrypted",
                    )
                }
                anyEncrypted = anyEncrypted || report.stringEncryption.encryptedConstants > 0
                assertFalse(dumped.isEmpty())
            }
        }
        // Some fixtures (algo) only contain shared key literals, so the "something
        // was encrypted" check is global rather than per fixture.
        assertTrue(anyEncrypted, "the super configuration encrypted nothing at all")
    }

    private fun bytes(name: String): ByteArray = checkNotNull(
        LuaRealWorldObfuscationTest::class.java.getResourceAsStream("/real/$name"),
    ) { "missing real world fixture /real/$name" }.use { it.readBytes() }

    /**
     * Regression for `big_module_54.luac --jmp 3`: the jump pass appends many
     * blocks and re-encodes the line table on every append, so an encoder that
     * spaced its absolute anchors more than `MAXIWTHABS` apart made
     * `luaG_getfuncline`'s `pc / 128 - 1` estimate overshoot, the reader fell
     * back to walking from `-1`, and the resulting negative absolute lines made
     * the 5.4 writer throw `cannot dump a negative chunk integer`.
     *
     * The passes only append blocks after a terminator (no instruction ever
     * shifts), so the lines of the original instruction range must survive
     * exactly; the wide matrix (`big_module_54.luac --jmp 3` and the two super
     * commands per version) is executed against the real interpreters by
     * `tools/verify-big-module.sh`.
     */
    @Test
    fun `big module stays structurally valid for the jump and super configurations`() {
        for ((suffix, version) in VERSIONS) {
            val jumpOnly = LuaChunkLoader.load(bytes("big_module_$suffix.luac"), version)
            val countsBefore = jumpOnly.prototypes.map { it.instructionCount }
            val linesBefore = jumpOnly.prototypes.map { it.resolvedLineInfo() }

            LuaJumpIndirection.apply(jumpOnly, JumpIndirectionConfig(depth = 3))

            jumpOnly.prototypes.forEachIndexed { index, prototype ->
                if (linesBefore[index].isEmpty()) {
                    return@forEachIndexed
                }
                assertEquals(
                    linesBefore[index],
                    prototype.resolvedLineInfo().take(countsBefore[index]),
                    "big_module_$suffix proto #$index: jump indirection changed the original lines",
                )
            }
            assertStructurallyValid("big_module_$suffix (--jmp 3)", jumpOnly, expectDebugInfo = true)

            val configurations = listOf(
                "super seed 7 --strip-debug" to cliSuperConfig(7L, strip = true),
                "super seed 29 --strip-debug" to cliSuperConfig(29L, strip = true),
                "super seed 29" to cliSuperConfig(29L, strip = false),
            )
            for ((label, config) in configurations) {
                val chunk = LuaChunkLoader.load(bytes("big_module_$suffix.luac"), version)
                LuaObfuscator.obfuscate(chunk, config)
                assertStructurallyValid(
                    "big_module_$suffix ($label)",
                    chunk,
                    expectDebugInfo = !config.stripDebugInfo,
                )
            }
        }
    }

    /** The exact CLI configuration `--strings 4 --jmp 3 --junk 4 --rings 4:3 --predicate MIXED`. */
    private fun cliSuperConfig(seed: Long, strip: Boolean) = ObfuscationConfig(
        stringEncryption = StringEncryptionConfig(minLength = 4),
        jumpIndirection = JumpIndirectionConfig(depth = 3),
        junkCode = JunkCodeConfig(
            guardedBranches = 4,
            unreachableBlocks = 4,
            deadCycleLength = 3,
            predicateFamily = OpaquePredicate.MIXED,
            seed = seed,
        ),
        stripDebugInfo = strip,
    )

    /**
     * Dump -> reload keeps the tree loadable and every debug structure coherent:
     * one line per instruction (or none), sorted non negative absolute anchors
     * within `MAXIWTHABS`, and jump targets that point at block starts.
     */
    private fun assertStructurallyValid(label: String, chunk: LuaChunk, expectDebugInfo: Boolean) {
        val dumped = LuaChunkWriter.dump(chunk)
        val reloaded = LuaChunkLoader.load(dumped, chunk.header.version)
        assertEquals(chunk.prototypes.size, reloaded.prototypes.size, "$label: prototype count")
        if (!expectDebugInfo) {
            assertTrue(reloaded.prototypes.none { it.hasLineInfo }, "$label: stripped chunk still carries line info")
        }

        for ((index, prototype) in reloaded.prototypes.withIndex()) {
            if (prototype.lineInfo.isEmpty()) {
                if (!expectDebugInfo) {
                    assertTrue(prototype.absoluteLineInfo.isEmpty(), "$label proto #$index: anchors without line info")
                }
            } else {
                assertEquals(
                    prototype.instructionCount,
                    prototype.lineInfo.size,
                    "$label proto #$index: lineInfo size",
                )
            }
            for ((pc, target) in prototype.jumpTargets()) {
                assertTrue(target in 0..prototype.code.size, "$label proto #$index: jump $pc -> $target out of range")
                assertTrue(
                    target in prototype.basicBlockStarts(),
                    "$label proto #$index: jump $pc -> $target is not a block start",
                )
            }
            val anchors = prototype.absoluteLineInfo
            for (anchorIndex in anchors.indices) {
                val anchor = anchors[anchorIndex]
                assertTrue(anchor.pc in 0..prototype.code.size, "$label proto #$index: anchor pc ${anchor.pc} out of range")
                assertTrue(anchor.line >= 0, "$label proto #$index: negative anchor line ${anchor.line}")
                if (anchorIndex > 0) {
                    val gap = anchor.pc - anchors[anchorIndex - 1].pc
                    assertTrue(
                        gap in 1..128,
                        "$label proto #$index: $gap instructions between anchors exceeds MAXIWTHABS",
                    )
                }
            }
            assertTrue(
                prototype.resolvedLineInfo().all { it >= 0 },
                "$label proto #$index: resolved line info contains a negative line",
            )
        }
    }

    private companion object {
        val VERSIONS = listOf(
            "53" to LuaBytecodeVersion.LUA_5_3,
            "54" to LuaBytecodeVersion.LUA_5_4,
            "55" to LuaBytecodeVersion.LUA_5_5,
        )
        val PROGRAMS = listOf("bank", "json", "algo")

        /** The "super" configuration used for the manual sample runs. */
        val SUPER = ObfuscationConfig(
            stringEncryption = StringEncryptionConfig(minLength = 4),
            jumpIndirection = JumpIndirectionConfig(depth = 3),
            junkCode = JunkCodeConfig(
                junkPerBlock = 4,
                guardedBranches = 4,
                deadBlockSize = 6,
                unreachableBlocks = 4,
                deadCycleLength = 3,
                predicateFamily = OpaquePredicate.MIXED,
            ),
            stripDebugInfo = true,
        )
    }
}
