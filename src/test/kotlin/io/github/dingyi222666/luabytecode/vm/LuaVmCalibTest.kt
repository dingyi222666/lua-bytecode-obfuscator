package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.platform.jvm.load
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import java.io.File
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Temporary calibration dump (goal round 107). */
class LuaVmCalibTest {
    @Test
    fun dump() {
        val input = File("/tmp/super/calib_54.luac")
        assumeTrue(input.isFile) { "calibration input not generated: $input" }
        val chunk = LuaChunkLoader.load(input, LuaBytecodeVersion.LUA_5_4)
        val out = StringBuilder()
        chunk.mainPrototype.code.forEachIndexed { i, insn ->
            out.append(
                "src[$i] ${insn.opcodeName} a=${insn.a} b=${insn.b} c=${insn.c} bx=${insn.bx} sbx=${insn.sbx} k=${insn.k}\n",
            )
        }
        File("/tmp/vm-calib.txt").writeText(out.toString())
    }
}
