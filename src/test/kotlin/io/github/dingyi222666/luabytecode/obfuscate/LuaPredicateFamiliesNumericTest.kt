package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.bytecode.*
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTo
import kotlin.test.*

/** Check the emitted runtime template and its branch polarity for the complete truth domain. */
class LuaPredicateFamiliesNumericTest {
    @Test fun `runtime nonlinear predicates select the valid path for nil false and truthy values`() {
        for (suffix in listOf("53", "54", "55")) for (seed in 1L..30L) for (family in OpaquePredicate.entries) {
            val p = loadFixture("flow_$suffix.luac").prototypes.first { it.numParams > 0 }.detachedCopy()
            val floor = p.maxStackSize
            p.maxStackSize += 8
            val plan = LuaJunkCode.buildPredicate(p, floor, JunkCodeConfig(predicateFamily = family), ObfRandom(seed))
            assertTrue(plan.setup.any { it.opcodeName == "TEST" && it.a == 0 })
            val code = plan.setup.toMutableList()
            code += LuaJunkCode.compareBranch(p, plan.left, plan.right, plan.alwaysTrue)
            val trap = code.size + 2
            val good = trap + 1
            code += jumpTo(p.version, code.size, trap)
            code += jumpTo(p.version, code.size, good)
            repeat(2) { code += LuaInstruction.abc(p.version, LuaOpcodes.opcodeOf(p.version, "RETURN")!!, 0, 1) }
            val proof = CffTranslationValidator.verify(code, p.constants,
                listOf(CffControlSite(0, 0)), listOf(good, trap), floor, p.maxStackSize, 10000)
            assertEquals(3, proof.paths)
            val compare = plan.setup.size
            code[compare] = if (p.version == LuaBytecodeVersion.LUA_5_3) code[compare].withA(1 - code[compare].a)
                else code[compare].withK(1 - code[compare].k)
            assertFailsWith<IllegalArgumentException> {
                CffTranslationValidator.verify(code, p.constants, listOf(CffControlSite(0, 0)),
                    listOf(good, trap), floor, p.maxStackSize, 10000)
            }
        }
    }

    @Test fun `seeds change operation trees rather than only constant indices`() {
        val shapes = HashSet<String>()
        for (seed in 1L..64L) {
            val p = loadFixture("flow_54.luac").prototypes.first { it.numParams > 0 }.detachedCopy()
            val floor = p.maxStackSize
            p.maxStackSize += 8
            val plan = LuaJunkCode.buildPredicate(p, floor,
                JunkCodeConfig(predicateFamily = OpaquePredicate.MBA_PRODUCT_PARTITION), ObfRandom(seed))
            shapes += plan.setup.joinToString(",") { it.opcodeName }
        }
        assertTrue(shapes.size > 16, "only ${shapes.size} operation trees")
    }

    @Test fun `no parameter means refusal instead of a constant seeded predicate`() {
        val p = loadFixture("flow_53.luac").mainPrototype
        assertFailsWith<IllegalArgumentException> {
            LuaJunkCode.buildPredicate(p, p.maxStackSize, JunkCodeConfig(), ObfRandom(7))
        }
    }
}
