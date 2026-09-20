package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.ALL_FIXTURES
import io.github.dingyi222666.luabytecode.bytecode.FIXTURE_VERSIONS
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.analysis.loadKSites
import io.github.dingyi222666.luabytecode.bytecode.analysis.referencedConstantIndices
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import io.github.dingyi222666.luabytecode.bytecode.loadFixture
import io.github.dingyi222666.luabytecode.bytecode.stringConstantTexts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LuaStringEncryptorTest {

    @Test
    fun `cipher round trips and matches the Lua decoder`() {
        val plain = "hello world, 你好 世界".toByteArray()
        for (seed in listOf(1, 12345, Int.MAX_VALUE)) {
            val encrypted = LuaStringCipher.encrypt(plain, seed)
            assertFalse(encrypted.contentEquals(plain), "seed $seed left the payload readable")
            assertTrue(LuaStringCipher.decrypt(encrypted, seed).contentEquals(plain))
        }
        assertFalse(
            LuaStringCipher.encrypt(plain, 1).contentEquals(LuaStringCipher.encrypt(plain, 2)),
            "different seeds must produce different keystreams",
        )
    }

    @Test
    fun `encryptable literals disappear from the constant pool`() {
        for ((suffix, version) in FIXTURE_VERSIONS) {
            val chunk = loadFixture("features_$suffix.luac")
            val encryptable = encryptableLiterals(chunk, minLength = 8)
            assertTrue(encryptable.isNotEmpty(), "5.$version fixture has nothing to encrypt")

            val result = LuaStringEncryptor.encrypt(chunk, StringEncryptionConfig(minLength = 8))
            assertTrue(result.encryptedConstants > 0, "5.$version encrypted nothing")
            assertTrue(result.rewrittenSites >= result.encryptedConstants)
            assertTrue(result.decoderPrototypeIndex >= 0)

            val dumped = LuaChunkWriter.dump(chunk)
            val reloaded = LuaChunkLoader.load(dumped, version)
            assertEquals(version, reloaded.version)

            val remaining = reloaded.stringConstantTexts()
            for (plaintext in encryptable) {
                assertFalse(
                    plaintext in remaining,
                    "5.$version: \"${plaintext.take(24)}\" is still in the constant pool",
                )
            }
        }
    }

    @Test
    fun `encrypting is deterministic for a given chunk`() {
        fun protect(): ByteArray {
            val chunk = loadFixture("hello_53.luac")
            LuaStringEncryptor.encrypt(chunk, StringEncryptionConfig(minLength = 6))
            return LuaChunkWriter.dump(chunk)
        }
        assertTrue(protect().contentEquals(protect()))
    }

    @Test
    fun `shared key constants are never touched`() {
        for (name in ALL_FIXTURES) {
            val chunk = loadFixture(name)
            val before = chunk.prototypes.map { prototype ->
                val shared = prototype.referencedConstantIndices()
                shared.filter { it in prototype.constants.indices }
                    .associateWith { prototype.constants[it] }
            }

            LuaStringEncryptor.encrypt(chunk, StringEncryptionConfig(minLength = 1))

            // the decoder prototype is appended at the end, ignore it
            chunk.prototypes.take(before.size).forEachIndexed { index, prototype ->
                before[index].forEach { (constantIndex, constant) ->
                    assertEquals(
                        constant,
                        prototype.constants[constantIndex],
                        "$name: shared constant K$constantIndex was modified",
                    )
                }
            }
        }
    }

    @Test
    fun `every fixture stays loadable including stripped ones`() {
        for (name in ALL_FIXTURES) {
            val chunk = loadFixture(name)
            val constantsBefore = chunk.prototypes.sumOf { it.constantCount }
            LuaStringEncryptor.encrypt(chunk, StringEncryptionConfig(minLength = 4))
            assertTrue(chunk.prototypes.sumOf { it.constantCount } >= constantsBefore)

            val dumped = LuaChunkWriter.dump(chunk)
            val reloaded = LuaChunkLoader.load(dumped)
            assertEquals(chunk.prototypes.size, reloaded.prototypes.size)
            reloaded.prototypes.zip(chunk.prototypes).forEach { (a, b) ->
                assertEquals(a.instructionCount, b.instructionCount)
                assertEquals(a.lineInfo.size, b.lineInfo.size)
                assertEquals(a.constantCount, b.constantCount)
                // the appended decoder stub ends with a JMP, the original return is still there
                assertTrue(a.code.any { it.opcodeName in setOf("RETURN", "RETURN0", "RETURN1") })
            }
        }
    }

    /** Literals the encryptor is expected to pick up: only `LOADK` and not shared. */
    private fun encryptableLiterals(
        chunk: io.github.dingyi222666.luabytecode.bytecode.LuaChunk,
        minLength: Int,
    ): List<String> {
        val result = ArrayList<String>()
        for (prototype in chunk.prototypes) {
            val shared = prototype.referencedConstantIndices()
            for ((constantIndex, _) in prototype.loadKSites()) {
                if (constantIndex in shared) continue
                val constant = prototype.constants[constantIndex] as? LuaConstant.Str ?: continue
                if (constant.value.size >= minLength) {
                    result += constant.value.utf8
                }
            }
        }
        return result
    }
}