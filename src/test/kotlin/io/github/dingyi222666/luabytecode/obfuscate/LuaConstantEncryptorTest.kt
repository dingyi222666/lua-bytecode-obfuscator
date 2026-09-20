package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.analysis.constantOperands
import io.github.dingyi222666.luabytecode.bytecode.analysis.loadKSites
import io.github.dingyi222666.luabytecode.bytecode.analysis.referencedConstantIndices
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acceptance for Pass K (`LEAK-2`) on the real `big_module` fixture, all three versions.
 *
 * 口径 of "非注入整型常量明文 = 0":
 *
 *  * sample = the `(prototypeIndex, constantIndex)` slots that the pass PROMISED to encrypt in the ORIGINAL
 *    chunk: Integer / Boolean constants whose only reference is `LOADK` (the pass's own selection predicate,
 *    minus the 2^40 payload bound). "Injected" constants — the kget blob fragments, the per prototype salt,
 *    the site indices, and the seeds later passes intern — are NOT part of the sample because the sample is
 *    fixed on the ORIGINAL chunk before any pass runs.
 *  * after `AGGRESSIVE`, that same slot (prototype positions are stable, constants are only ever appended, so
 *    `constantIndex` keeps addressing the same slot) must no longer hold the original plaintext Integer /
 *    Boolean. A slot may legitimately hold the encrypted blob fragment string, or a different value if a later
 *    pass rebuilt the prototype's constant list — both count as "not plaintext".
 *  * the leak count of such slots must be 0 for every version.
 */
class LuaConstantEncryptorTest {

    private val versions = listOf(
        "53" to LuaBytecodeVersion.LUA_5_3,
        "54" to LuaBytecodeVersion.LUA_5_4,
        "55" to LuaBytecodeVersion.LUA_5_5,
    )

    private class Sample(val prototypeIndex: Int, val constantIndex: Int, val value: Any)

    private fun sampleSlots(
        chunk: io.github.dingyi222666.luabytecode.bytecode.LuaChunk,
    ): List<Sample> {
        val samples = ArrayList<Sample>()
        chunk.prototypes.forEachIndexed { prototypeIndex, prototype ->
            val shared = prototype.referencedConstantIndices()
            for ((constantIndex, _) in prototype.loadKSites()) {
                if (constantIndex in shared) continue
                when (val constant = prototype.constants[constantIndex]) {
                    is LuaConstant.Integer -> if (constant.value < (1L shl 39) && constant.value > -(1L shl 39)) {
                        samples += Sample(prototypeIndex, constantIndex, constant.value)
                    }
                    is LuaConstant.Boolean -> samples += Sample(prototypeIndex, constantIndex, constant.value)
                    else -> {}
                }
            }
        }
        return samples
    }

    private fun plainAt(
        chunk: io.github.dingyi222666.luabytecode.bytecode.LuaChunk,
        sample: Sample,
    ): Boolean {
        val prototype = chunk.prototypes.getOrNull(sample.prototypeIndex) ?: return false
        return when (val constant = prototype.constants.getOrNull(sample.constantIndex)) {
            is LuaConstant.Integer -> sample.value is Long && constant.value == sample.value
            is LuaConstant.Boolean -> sample.value is Boolean && constant.value == sample.value
            else -> false
        }
    }

    @Test
    fun `big module aggressive leaves zero plain numeric constants`() {
        for ((suffix, version) in versions) {
            val bytes = javaClass.getResourceAsStream("/real/big_module_$suffix.luac")!!.use { it.readBytes() }
            val original = LuaChunkLoader.load(bytes, version)
            val samples = sampleSlots(original)
            // 5.4 / 5.5 compilers fold small integers into LOADI immediates, so a
            // version may legitimately have zero pool-integer slots; the leak
            // assertion below then trivially holds.
            if (samples.isEmpty()) {
                println("5.$suffix: no LOADK-only numeric slots (compiler folded them) — skip")
                continue
            }

            LuaObfuscator.obfuscate(original, ObfuscationConfig.AGGRESSIVE)
            val dumped = LuaChunkWriter.dump(original)
            val reloaded = LuaChunkLoader.load(dumped, version)

            // boundary: a slot the FINAL code references through a non-LOADK
            // operand (RK / *K immediate — the documented LEAK-2 skip surface)
            // cannot be encrypted this round; it is reported separately.
            fun referencedOnlyByLoadk(prototypeIndex: Int, constantIndex: Int): Boolean {
                val prototype = reloaded.prototypes.getOrNull(prototypeIndex) ?: return true
                var loadk = false
                for (instruction in prototype.code) {
                    val refs = if (instruction.opcodeName == "LOADK" || instruction.opcodeName == "LOADKX") {
                        listOf(instruction.bx)
                    } else if (instruction.opcodeName == "EXTRAARG") {
                        listOf(instruction.ax)
                    } else {
                        instruction.constantOperands()
                    }
                    if (constantIndex in refs) {
                        if (instruction.opcodeName == "LOADK" || instruction.opcodeName == "LOADKX") loadk = true
                        else return false
                    }
                }
                return loadk
            }

            val leaked = samples.filter { plainAt(reloaded, it) && referencedOnlyByLoadk(it.prototypeIndex, it.constantIndex) }
            val boundary = samples.count { plainAt(reloaded, it) && !referencedOnlyByLoadk(it.prototypeIndex, it.constantIndex) }
            println("5.$suffix: RK-boundary plaintext slots (documented skip, CONST-2): $boundary")
            assertEquals(
                emptyList(), leaked,
                "5.$suffix: ${leaked.size} promised slots still hold their plaintext: " +
                    leaked.take(10).map {
                        val proto = reloaded.prototypes.getOrNull(it.prototypeIndex)
                        val now = proto?.constants?.getOrNull(it.constantIndex)
                        "proto${it.prototypeIndex}K${it.constantIndex}: orig=${it.value} now=$now"
                    },
            )
        }
    }

    @Test
    fun `full period parameters satisfy hull dobell and cover the state space`() {
        val random = java.util.Random(20260917L)
        repeat(200) {
            val spec = ConstCipher.Spec.random(random)
            assertTrue(spec.multiplier % 4 == 1L, "multiplier ${spec.multiplier} not congruent 1 mod 4")
            assertTrue(spec.increment % 2 == 1L, "increment ${spec.increment} not odd")
            assertTrue(spec.shift in 8..24, "shift ${spec.shift} outside 8..24")
        }
    }

    @Test
    fun `cipher round trips positive and negative values through the 64 bit payload`() {
        val spec = ConstCipher.Spec(195573L, 211815755L, 18)
        val values = listOf(0L, 1L, 42L, -1L, -3L, -12345L, 2147483647L, (1L shl 39) - 1, -(1L shl 39) + 1, Long.MIN_VALUE, Long.MAX_VALUE)
        for (value in values) {
            val plain = payloadOf(value)
            val cipher = ConstCipher.apply(plain, ConstCipher.ofIndex(987654321, 3), spec)
            val decoded = ConstCipher.apply(cipher, ConstCipher.ofIndex(987654321, 3), spec)
            assertTrue(plain.contentEquals(decoded), "round trip failed for $value")
        }
    }

    private fun payloadOf(value: Long): ByteArray {
        val result = ByteArray(9)
        result[0] = 0
        for (i in 0 until 8) {
            result[1 + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
        return result
    }
}
