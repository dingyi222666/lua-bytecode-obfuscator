package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression net for the string coverage bug found in goal round 7.
 *
 * Two false "shared constant" references disabled encryption for whole classes
 * of literals:
 *  * 5.4 / 5.5 `GETTABLE`'s `C` was read as a constant index although it is a
 *    *register* key (the constant key opcodes are `GETFIELD` / `GETI`), and
 *  * `EXTRAARG` after `NEWTABLE` / `SETLIST` is a size hint, not a constant.
 *
 * Both made an arbitrary constant (usually `K0`) look shared, and literals used
 * as method receivers such as `("statement for %s"):format(...)` stayed in the
 * clear. This test pins both directions of the contract: encryptable literals
 * must disappear from the dumped bytes, shared keys must stay readable.
 */
class StringCoverageTest {

    @Test
    fun `method receiver literals are encrypted while shared keys stay readable`() {
        for ((suffix, version) in VERSIONS) {
            val chunk = LuaChunkLoader.load(bytes(suffix), version)
            LuaObfuscator.obfuscate(
                chunk,
                ObfuscationConfig(stringEncryption = StringEncryptionConfig(minLength = 4)),
            )
            val dumped = String(LuaChunkWriter.dump(chunk), Charsets.ISO_8859_1)

            for (literal in ENCRYPTABLE) {
                assertFalse(
                    dumped.contains(literal),
                    "bank_${suffix} leaked the encryptable literal \"$literal\"",
                )
            }
            for (key in SHARED_KEYS) {
                assertTrue(
                    dumped.contains(key),
                    "bank_${suffix} lost the shared key \"$key\" (it must stay readable)",
                )
            }
        }
    }

    private fun bytes(suffix: String): ByteArray = checkNotNull(
        StringCoverageTest::class.java.getResourceAsStream("/real/bank_${suffix}.luac"),
    ) { "missing bank_${suffix}" }.use { it.readBytes() }

    private companion object {
        val VERSIONS = listOf(
            "53" to LuaBytecodeVersion.LUA_5_3,
            "54" to LuaBytecodeVersion.LUA_5_4,
            "55" to LuaBytecodeVersion.LUA_5_5,
        )

        /** Only `LOADK`-loaded literals; all of these are >= minLength 4. */
        val ENCRYPTABLE = listOf(
            "statement for",
            "insufficient funds",
            "deposit must be positive",
            "withdraw -%s",
            "balance %s",
        )

        /** Field / global keys: read through `GETFIELD` / `SETFIELD` / `SELF`. */
        val SHARED_KEYS = listOf("__index", "setmetatable", "format", "deposit", "withdraw", "new")
    }
}
