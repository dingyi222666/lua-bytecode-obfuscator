package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import kotlin.test.*

class LuaCffVerificationTest {
    private fun chunk(version: String) = LuaChunkLoader.load(checkNotNull(javaClass.getResourceAsStream(
        "/bytecode/flow_$version.luac") ?: javaClass.getResourceAsStream("/real/flow_$version.luac")).use { it.readBytes() })

    @Test
    fun `failed verification rolls back code constants debug and child interfaces`() {
        for (v in listOf("53", "54", "55")) {
            val chunk = chunk(v)
            val before = LuaChunkWriter.dump(chunk)
            val report = LuaControlFlowFlattening.apply(chunk,
                ControlFlowFlatteningConfig.FULL.copy(maxValidationSteps = 1))
            assertEquals(0, report.flattenedPrototypes)
            assertContentEquals(before, LuaChunkWriter.dump(chunk))
            assertEquals(chunk.prototypes.size, report.prototypes.size)
            assertTrue(report.prototypes.all { it.actualFeatures.isEmpty() })
        }
    }

    @Test
    fun `dependencies and allocation contracts are reported without enabling them`() {
        val report = LuaControlFlowFlattening.apply(chunk("54"), ControlFlowFlatteningConfig(
            multipleDispatch = true, nestedDispatch = true, stateTable = true,
            predicateFamilies = setOf(CffPredicateFamily.GRAPH),
        ))
        assertTrue(report.flattenedPrototypes > 0)
        assertFalse("state.runtime" in report.appliedFeatures)
        assertFalse("state.namespaces" in report.appliedFeatures)
        assertFalse("dispatch.multiple" in report.appliedFeatures)
        assertFalse("state.table" in report.appliedFeatures)
        assertTrue(report.prototypes.any { p -> p.reasons.any { it.startsWith("R09") } })
        assertTrue(report.prototypes.any { p -> p.reasons.any { it.startsWith("R11") } })
    }

    @Test
    fun `wrong dispatcher polarity is rejected from final instruction words`() {
        for (v in listOf("53", "54", "55")) {
            val chunk = chunk(v)
            val report = LuaControlFlowFlattening.apply(chunk, ControlFlowFlatteningConfig.FULL.copy(seed = 31))
            val record = report.prototypes.first { it.proof != null }
            val p = chunk.prototypes[record.prototypeIndex]
            val proof = checkNotNull(record.proof)
            val code = p.code.toMutableList()
            var mutations = 0
            for ((pc, i) in code.withIndex()) {
                if (i.opcodeName != "EQ") continue
                val v53 = i.version == LuaBytecodeVersion.LUA_5_3
                if ((if (v53) i.b else i.a) != proof.firstPrivateRegister) continue
                code[pc] = if (v53) i.withA(1 - i.a) else i.withK(1 - i.k)
                mutations++
            }
            assertTrue(mutations > 0)
            assertFails {
                CffTranslationValidator.verify(code, p.constants,
                    proof.controlEntries.map { (pc, target) -> CffControlSite(pc, target) },
                    proof.destinationPcs, proof.firstPrivateRegister, p.maxStackSize, proof.validationBudget)
            }
        }
    }

    @Test
    fun `same input seed and features produce identical chunks`() {
        for (v in listOf("53", "54", "55")) {
            val a = chunk(v)
            val b = chunk(v)
            val config = ControlFlowFlatteningConfig.FULL.copy(seed = 817)
            assertEquals(LuaControlFlowFlattening.apply(a, config), LuaControlFlowFlattening.apply(b, config))
            assertContentEquals(LuaChunkWriter.dump(a), LuaChunkWriter.dump(b))
        }
    }
}
