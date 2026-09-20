package io.github.dingyi222666.luabytecode.bytecode.analysis

import io.github.dingyi222666.luabytecode.bytecode.ALL_FIXTURES
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.loadFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression pins for the B6/Round4 RK operand model in [LuaDataFlow]
 * (coordinator mandate: "对干净 luac 产物的 def-before-use 违规数必须为 0").
 *
 * Background: on 5.3 the RK operands (bit 8 set = constant-pool reference)
 * were modelled as registers, which put phantom registers 256+ into every
 * live set and, together with upvalue-index operands and the bottom-initialised
 * must-analysis, produced false "reads before defined" violations on clean
 * luac fixtures (150 before the fix).
 */
class LuaDataFlowRkModelTest {

    /** THE pin: clean luac output must yield zero def-before-use violations. */
    @Test
    fun `clean luac fixtures produce zero def-before-use violations`() {
        for (name in ALL_FIXTURES) {
            val chunk = loadFixture(name)
            for (prototype in chunk.prototypes) {
                val violations = LuaDataFlow(prototype).defBeforeUseViolations()
                assertEquals(
                    emptyList(),
                    violations,
                    "$name proto@${prototype.lineDefined} must be violation-free " +
                        "(first: ${violations.firstOrNull()})",
                )
            }
        }
    }

    /** No live set may ever contain a register outside the prototype's frame. */
    @Test
    fun `live sets never contain phantom or out-of-frame registers`() {
        for (name in ALL_FIXTURES) {
            val chunk = loadFixture(name)
            for (prototype in chunk.prototypes) {
                val flow = LuaDataFlow(prototype)
                val universe = 0 until prototype.maxStackSize
                for ((index, range) in flow.ranges.withIndex()) {
                    assertTrue(
                        flow.liveIn[index].all { it in universe },
                        "$name proto@${prototype.lineDefined} liveIn block $index " +
                            "has out-of-frame registers: ${flow.liveIn[index].filter { it !in universe }}",
                    )
                    assertTrue(
                        flow.liveOut[index].all { it in universe },
                        "$name proto@${prototype.lineDefined} liveOut block $index " +
                            "has out-of-frame registers: ${flow.liveOut[index].filter { it !in universe }}",
                    )
                    for (pc in range) {
                        assertTrue(
                            flow.liveAfter(pc).all { it in universe },
                            "$name proto@${prototype.lineDefined} liveAfter($pc) out of frame",
                        )
                        assertTrue(
                            flow.liveBefore(pc).all { it in universe },
                            "$name proto@${prototype.lineDefined} liveBefore($pc) out of frame",
                        )
                    }
                }
            }
        }
    }

    /** 5.3 RK operands (>= 256) land in constantRefs and never in uses. */
    @Test
    fun `five point three RK constants are constant references not registers`() {
        val chunk = loadFixture("flow_53.luac")
        val prototype = chunk.prototypes.first { it.lineDefined == 12 }
        val flow = LuaDataFlow(prototype)
        // pc4 ADD a=2 b=2 c=257 (K[1] = 1)
        val add = flow.useDef(4)
        assertTrue(2 in add.uses, "register operand must stay a use")
        assertTrue(257 in add.constantRefs, "RK constant must be reported in constantRefs")
        assertTrue(257 !in add.uses, "RK constant must not be a use")
        // pc6 EQ a=0 b=3 c=256 — on 5.3 A is the boolean flag, not a register
        val eq = flow.useDef(6)
        assertTrue(0 !in eq.uses, "5.3 EQ operand A is a boolean flag, not a register")
        assertEquals(setOf(3), eq.uses, "5.3 EQ register comparator is operand B")
        assertTrue(256 in eq.constantRefs, "5.3 EQ constant comparator in constantRefs")
    }

    /** Upvalue indices (GETTABUP B / SETTABUP A) never count as register reads. */
    @Test
    fun `gettabup and settabup index operands are not registers`() {
        val chunk = loadFixture("big_53.luac")
        val prototype = chunk.prototypes.first { it.lineDefined == 3 }
        val flow = LuaDataFlow(prototype)
        // pc0 SETTABUP a=0 b=256 c=257: upvalue 0, key K[0], value K[1]
        val setTabUp = flow.useDef(0)
        assertTrue(0 !in setTabUp.uses, "SETTABUP operand A is an upvalue index")
        assertTrue(257 in setTabUp.constantRefs, "SETTABUP RK value constant reported")
    }

    @Test
    fun `gettabup operand b is an upvalue index not a register`() {
        val chunk = loadFixture("flow_53.luac")
        val prototype = chunk.mainPrototype
        val flow = LuaDataFlow(prototype)
        val getTabUpPc = flow.code.indices.first { flow.code[it].opcodeName == "GETTABUP" }
        val getTabUp = flow.useDef(getTabUpPc)
        val instr = flow.code[getTabUpPc]
        assertTrue(instr.b !in getTabUp.uses, "GETTABUP operand B is an upvalue index")
        assertTrue(getTabUp.constantRefs.contains(instr.c) || instr.c < 256,
            "GETTABUP key operand reported as constant reference (5.3 RK form)")
    }

    /** 5.4 SETI/SETFIELD/SETTABUP: value operand C is a register iff the k flag is clear. */
    @Test
    fun `five point four key store value operand follows the k flag`() {
        val chunk = loadFixture("big_54.luac")
        val prototype = chunk.prototypes.first { it.lineDefined == 3 }
        val flow = LuaDataFlow(prototype)
        // SETI 0 1 0k with k set: value is the constant K[0], no register read
        val setiPc = flow.code.indices.first { flow.code[it].opcodeName == "SETI" && flow.code[it].k != 0 }
        val setI = flow.useDef(setiPc)
        val setIInstr = flow.code[setiPc]
        assertEquals(setOf(setIInstr.a), setI.uses, "k-flagged SETI reads only the table register")
        assertTrue(setIInstr.c in setI.constantRefs, "SETI constant value reported in constantRefs")
        assertTrue(flow.code[setiPc].b !in setI.uses, "SETI immediate key is not a register")
    }

    /** 5.4 generic for: TFORCALL defines the loop variables (R[A+4 .. A+3+C]). */
    @Test
    fun `five point four tforcall defines the loop control variables`() {
        val chunk = loadFixture("flow_54.luac")
        val prototype = chunk.prototypes.first { it.lineDefined == 12 }
        val flow = LuaDataFlow(prototype)
        val tforCallPc = flow.code.indices.first { flow.code[it].opcodeName == "TFORCALL" }
        val tforCall = flow.useDef(tforCallPc)
        // a=3, c=2: loop variables are R7 and R8 (`for _, value in ipairs(...)`)
        assertEquals((7 until 9).toSet(), tforCall.defs)
    }
}
