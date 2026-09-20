package io.github.dingyi222666.luabytecode.bytecode.support

import java.io.File
import kotlin.test.Test

class LuaVariantDumpTest {
    @Test
    fun dumpHarness() {
        val outDir = File("/tmp/b6variants")
        outDir.mkdirs()
        var idx = 0
        for (si in LuaDecodeVariants.SeedInit.entries) {
            for (sf in LuaDecodeVariants.StepForm.entries) {
                for (be in LuaDecodeVariants.ByteExtract.entries) {
                    idx++
                    val draw = LuaDecodeVariants.Draw(si, sf, be,
                        m1 = 0x9DE39CADL, a1 = 0x12345679L, m2 = 0x85EBCA6BL, a2 = 0xC2B2AE35L, k1 = 0x5DEECE66DL, guard = 2654435769L)
                    val spec = LcgSpec(1664525L, 1013904223L)
                    val sk = LuaDecodeVariants.generate(spec, draw)
                    // expected states from the Kotlin reference + mirrors
                    val mirror = StringBuilder()
                    var state = LuaDecodeVariants.seedInitMirror(draw, 20260917L)
                    mirror.append(state).append('\n')
                    repeat(8) {
                        state = LuaDecodeVariants.stepMirror(spec, sf, state)
                        mirror.append(state).append('\n')
                    }
                    val bytes = StringBuilder()
                    repeat(8) {
                        bytes.append(LuaDecodeVariants.byteMirror(be, state)).append('\n')
                    }
                    val lua = buildString {
                        append(sk.seedInitFunction("v_seed")).append('\n')
                        append("local state = v_seed(20260917)\n")
                        append("print(state)\n")
                        repeat(8) {
                            append(sk.stepStatement("state")).append('\n')
                            append("print(state)\n")
                        }
                        append("-- byte extraction\n")
                        append("local function v_byte(s) return ").append(sk.byteExpression("s")).append(" end\n")
                        repeat(8) { append("print(v_byte(state))\n") }
                    }
                    File(outDir, "case$idx.lua").writeText(lua)
                    File(outDir, "case$idx.expected").writeText(mirror.toString() + bytes.toString())
                }
            }
        }
        File(outDir, "count.txt").writeText(idx.toString())
    }
}
