package io.github.dingyi222666.luabytecode.obfuscate
import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaInstructionFactory

import io.github.dingyi222666.luabytecode.bytecode.*
import io.github.dingyi222666.luabytecode.bytecode.analysis.*

/** A restricted, call-free final constant pass with relocated CFF certificates.
 *
 * Originates from the CFF feature but lives in the contract layer (same
 * promotion rule as [CffTranslationValidator]): the constant-encryption pass
 * plugin (`:lua-obfuscation-constants`) drives it between flattening and
 * Pass K, and feature modules must not import each other — so the shared
 * helper is promoted to the one module both sides already depend on.
 */
object CffConstantExpansion {
    data class Result(val constants: Int, val sites: Int, val proof: CffProofManifest,
        val paths: Int, val steps: Int)

    fun apply(prototype: LuaPrototype, proof: CffProofManifest, config: ConstantEncryptionConfig): Result? {
        val scratch = prototype.maxStackSize
        if (scratch >= 255) return null
        val shared = prototype.referencedConstantIndices().toMutableSet()
        for ((pc, i) in prototype.code.withIndex()) {
            if (i.opcodeName == "LOADK" && pc > 0 && prototype.code[pc - 1].skipsNextInstruction) shared += i.bx
            if (i.opcodeName == "LOADKX") prototype.code.getOrNull(pc + 1)?.let { shared += it.ax }
        }
        val eligible = prototype.code.filter { it.opcodeName == "LOADK" }.map { it.bx }.distinct()
            .filter { it !in shared && prototype.constants[it] is LuaConstant.Integer }
            .let { if (config.maxConstants > 0) it.take(config.maxConstants) else it }
        if (eligible.isEmpty()) return null
        val constants = prototype.constants.toMutableList()
        val random = ObfRandom(config.seed xor 0x4346464bL)
        val keys = eligible.associateWith {
            var key = random.nextLong()
            if (key == 0L) key = 1L
            val value = (constants[it] as LuaConstant.Integer).value
            constants[it] = LuaConstant.Integer(value xor key)
            val index = constants.size
            constants += LuaConstant.Integer(key)
            index
        }
        val old = prototype.code.toList()
        val mapping = IntArray(old.size + 1)
        val output = ArrayList<LuaInstruction>()
        val lines = ArrayList<Int>()
        val originalLines = prototype.resolvedLineInfo()
        for ((pc, i) in old.withIndex()) {
            mapping[pc] = output.size
            val start = output.size
            output += i
            if (i.opcodeName == "LOADK" && i.bx in keys) {
                output += LuaInstructionFactory.abx(prototype.version, "LOADK", scratch, keys.getValue(i.bx))
                output += LuaArithmetic.pair(prototype, "BXOR", i.a, i.a, scratch)
            }
            repeat(output.size - start) { lines += originalLines.getOrElse(pc) { prototype.lineDefined } }
        }
        mapping[old.size] = output.size
        return try {
            for ((pc, i) in old.withIndex()) {
                i.jumpTarget(pc)?.let { target ->
                    output[mapping[pc]] = i.retargeted(pc, mapping[pc], mapping[target]).also {
                        require(it.jumpTarget(mapping[pc]) == mapping[target])
                    }
                }
            }
            CffTranslationValidator.checkStructure(prototype.copy(code = output, constants = constants))
            val verified = CffTranslationValidator.verify(output, constants,
                proof.controlEntries.map { (pc, target) -> CffControlSite(mapping[pc], target) },
                proof.destinationPcs.map { mapping[it] }, proof.firstPrivateRegister, scratch + 1,
                proof.validationBudget * 2)
            prototype.replaceCodeAndConstants(output, constants)
            prototype.maxStackSize = scratch + 1
            if (originalLines.isNotEmpty()) prototype.setAbsoluteLines(lines)
            Result(eligible.size, old.count { it.opcodeName == "LOADK" && it.bx in keys },
                proof.copy(controlEntries = proof.controlEntries.map { (pc, target) -> mapping[pc] to target },
                    destinationPcs = proof.destinationPcs.map { mapping[it] },
                    validationBudget = proof.validationBudget * 2), verified.paths, verified.steps)
        } catch (_: IllegalArgumentException) {
            null // Whole candidate is discarded; the verified CFF stays intact.
        } catch (_: IllegalStateException) {
            null
        }
    }
}
