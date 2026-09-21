package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * P4.1 coverage: numeric `for` must run on the VM with the **exact** Lua 5.3 iterations.
 *
 * This test exists because the previous version only asserted the *sum* of `c01_numericfor`
 * (`1..10` -> `55`), and the extra `i = 0` iteration the broken `FORPREP` produced added `0`
 * and stayed invisible (adversarial audit C1). `c14_numericfor_print` prints every iteration,
 * so an extra or a missing iteration cannot hide, and the negative step case pins the sign
 * aware `FORLOOP`.
 *
 * Reference semantics (`lvm.c` 5.3): `R[A]` internal index, `R[A+1]` limit, `R[A+2]` step,
 * `R[A+3]` control variable; `FORPREP` subtracts one step and jumps onto `FORLOOP`.
 */
class LuaVmNumericForTest {
    private fun print(): Pair<ArrayList<String>, Map<String, Any?>> {
        val printed = ArrayList<String>()
        val env = mapOf<String, Any?>(
            "print" to { argv: List<Any?> ->
                printed += argv.joinToString("\t") { it.toString() }
                emptyList<Any?>()
            },
        )
        return printed to env
    }

    @Test
    fun `the c01 numeric for main prototype runs on the vm`() {
        val chunk = loadVmFixture("c01_numericfor_53.luac", LuaBytecodeVersion.LUA_5_3)
        val map = LuaVmProgram.OpcodeMap.random(ObfRandom(9L))
        val program = LuaVmTranslator.translate(chunk.mainPrototype, map, scratch = chunk.mainPrototype.maxStackSize)
        assertNotNull(program, "the main chunk of c01_numericfor must now be translatable")

        val (printed, env) = print()
        LuaVmInterpreter.run(program, map, upvalues = listOf(env))
        // `c01_numericfor.lua` sums 1..10 and prints the total (verified natively: 55)
        assertEquals(listOf("55"), printed, "the VM must sum 1..10 like the native chunk")
    }

    @Test
    fun `every iteration is printed exactly once, in order, for positive and negative steps`() {
        val chunk = loadVmFixture("c14_numericfor_print_53.luac", LuaBytecodeVersion.LUA_5_3)
        val map = LuaVmProgram.OpcodeMap.random(ObfRandom(11L))
        val program = LuaVmTranslator.translate(chunk.mainPrototype, map, scratch = chunk.mainPrototype.maxStackSize)
        assertNotNull(program, "the c14 numeric for fixture must be inside the subset")

        val (printed, env) = print()
        LuaVmInterpreter.run(program, map, upvalues = listOf(env))
        // native `lua c14_numericfor_print.lua` prints exactly this (1 2 3 / 2 3 4 / 10 7 4 1):
        // the old broken loop produced `0 1 2 3 1 2 3 4 9 6 3 0`.
        assertEquals(
            listOf("1", "2", "3", "2", "3", "4", "10", "7", "4", "1"),
            printed,
            "FORPREP must not fall through into the body and FORLOOP must respect the step sign",
        )
    }

    @Test
    fun `5_4 and 5_5 count based numeric for are refused, never mistranslated`() {
        for (version in listOf(LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5)) {
            val suffix = if (version == LuaBytecodeVersion.LUA_5_4) "54" else "55"
            val chunk = loadVmFixture("c14_numericfor_print_$suffix.luac", version)
            val map = LuaVmProgram.OpcodeMap.random(ObfRandom(13L))
            assertNull(
                LuaVmTranslator.translate(chunk.mainPrototype, map, scratch = chunk.mainPrototype.maxStackSize),
                "a $version numeric for must be refused: its layout is count based and not modelled yet",
            )
        }
    }
}
