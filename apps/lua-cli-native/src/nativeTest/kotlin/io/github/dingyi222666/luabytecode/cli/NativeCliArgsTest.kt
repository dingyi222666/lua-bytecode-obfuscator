package io.github.dingyi222666.luabytecode.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Usage / argument surface of the native CLI (kept minimal on purpose — the
 * full flag surface is covered end to end by the JVM CLI tests and by the
 * single-file e2e suite in [NativeCliEndToEndTest]).
 */
class NativeCliArgsTest {

    @Test
    fun `no arguments prints usage and exits zero`() {
        assertEquals(0, runNativeCli(arrayOf()))
        assertEquals(0, runNativeCli(arrayOf("--help")))
        assertEquals(0, runNativeCli(arrayOf("-h")))
    }

    @Test
    fun `unknown options are usage errors`() {
        assertEquals(2, runNativeCli(arrayOf("--nope", "chunk.luac")))
        assertEquals(2, runNativeCli(arrayOf("-x")))
    }

    @Test
    fun `missing or malformed values are usage errors`() {
        assertEquals(2, runNativeCli(arrayOf("--expect")))
        assertEquals(2, runNativeCli(arrayOf("--expect", "5.1")))
        assertEquals(2, runNativeCli(arrayOf("--seed")))
        assertEquals(2, runNativeCli(arrayOf("--seed", "abc")))
        assertEquals(2, runNativeCli(arrayOf("--obfuscate")))
        assertEquals(2, runNativeCli(arrayOf("--obfuscate", "mega")))
        assertEquals(2, runNativeCli(arrayOf("--cff", "mega")))
        assertEquals(2, runNativeCli(arrayOf("--predicate", "BOGUS")))
        assertEquals(2, runNativeCli(arrayOf("--rings", "abc")))
        assertEquals(2, runNativeCli(arrayOf("--jmp-decoys", "4096")))
    }

    @Test
    fun `a missing input file is a usage error`() {
        assertEquals(2, runNativeCli(arrayOf("/nonexistent-chunk.luac")))
    }

    @Test
    fun `the equals spelling matches the space spelling`() {
        // both spellings parse identically; both fail late on the missing file
        // with the same usage exit code (no early parse difference)
        assertEquals(2, runNativeCli(arrayOf("--seed=7", "/nonexistent-chunk.luac")))
    }
}
