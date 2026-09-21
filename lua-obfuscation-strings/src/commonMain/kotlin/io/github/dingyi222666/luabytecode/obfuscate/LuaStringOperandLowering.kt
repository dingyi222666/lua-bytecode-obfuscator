package io.github.dingyi222666.luabytecode.obfuscate
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaInstructionFactory

import io.github.dingyi222666.luabytecode.bytecode.*
import io.github.dingyi222666.luabytecode.bytecode.analysis.*
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaPrototypeScope

/** Lower constant string operands to LOADK + register operations before encryption.
 * All uses of a pool entry must be supported: partially encrypting a shared key is invalid.
 * Staging slots precede the decoder CALL window, so decoding a second operand cannot
 * destroy the first. Original table/upvalue binding and metamethod order are retained.
 */
internal object LuaStringOperandLowering {
    fun apply(chunk: LuaChunk, config: StringEncryptionConfig): Int {
        var changed = 0
        for (p in chunk.prototypes) {
            val v = p.version
            val v53 = v == LuaBytecodeVersion.LUA_5_3
            val eligible = p.constants.withIndex().filter { (_, c) ->
                c is LuaConstant.Str && c.value.size >= config.minLength
            }.map { it.index }.toMutableSet()
            val supported = if (v53) setOf("LOADK", "LOADKX", "GETTABUP", "GETTABLE", "SELF", "SETTABUP",
                "SETTABLE", "EQ", "LT", "LE", "ADD", "SUB", "MUL", "MOD", "POW", "DIV", "IDIV")
            else setOf("LOADK", "LOADKX", "GETTABUP", "GETFIELD", "SELF", "SETTABUP", "SETFIELD", "SETTABLE", "SETI", "EQK")
            for ((pc, i) in p.code.withIndex()) {
                if (i.opcodeName == "EXTRAARG") continue
                if (i.opcodeName !in supported || (i.skipsNextInstruction &&
                        p.code.getOrNull(pc + 1)?.opcodeName != "JMP")) eligible.removeAll(i.constantOperands().toSet())
            }
            val sites = p.code.indices.filter { pc -> p.code[pc].opcodeName in supported &&
                p.code[pc].opcodeName !in setOf("LOADK", "LOADKX") &&
                p.code[pc].constantOperands().any { it in eligible } }
            if (sites.isEmpty()) continue
            // Three staging slots, three decode slots, and a main prologue slot window.
            require(p.maxStackSize + 9 <= 255) { "key encryption exceeds Lua register limit" }
            require(p.code.last().opcodeName in setOf("RETURN", "RETURN0", "RETURN1", "JMP")) {
                "key encryption requires a terminated instruction stream"
            }
            val scratch = p.maxStackSize
            p.maxStackSize += 3
            val lines = p.resolvedLineInfo().toMutableList()
            fun abc(name: String, a: Int, b: Int = 0, c: Int = 0, k: Int = 0) =
                LuaInstructionFactory.abc(v, LuaOpcodes.opcodeOf(v, name)!!, a, b, c, k)
            for (pc in sites) {
                val i = p.code[pc]
                val start = p.code.size
                val block = ArrayList<LuaInstruction>()
                var slot = scratch
                fun load(index: Int): Int {
                    val r = slot++
                    block += LuaInstructionFactory.abx(v, "LOADK", r, index)
                    return r
                }
                fun rk(value: Int) = if (value >= 256 && value - 256 in eligible) load(value - 256) else value
                var lowered = i
                if (v53) {
                    lowered = when (i.opcodeName) {
                        "GETTABUP", "GETTABLE", "SELF" -> i.withC(rk(i.c))
                        else -> i.withB(rk(i.b)).withC(rk(i.c))
                    }
                } else {
                    var b = i.b
                    var c = i.c
                    var k = i.k
                    when (i.opcodeName) {
                        "GETTABUP", "GETFIELD", "SELF" -> {
                            c = load(i.c)
                            lowered = when (i.opcodeName) {
                                "GETTABUP" -> {
                                    block += abc("GETUPVAL", scratch + 2, b)
                                    abc("GETTABLE", i.a, scratch + 2, c)
                                }
                                "GETFIELD" -> abc("GETTABLE", i.a, b, c)
                                else -> if (v == LuaBytecodeVersion.LUA_5_4) i.withC(c).withK(0) else {
                                    block += abc("MOVE", i.a + 1, b)
                                    abc("GETTABLE", i.a, b, c)
                                }
                            }
                        }
                        "EQK" -> lowered = abc("EQ", i.a, load(i.b), 0, k)
                        else -> {
                            val key = i.opcodeName in setOf("SETTABUP", "SETFIELD")
                            // If only the value qualifies, a field key still needs staging
                            // to change the opcode into SETTABLE.
                            if (key) b = load(i.b)
                            if (k != 0 && c in eligible) { c = load(c); k = 0 }
                            lowered = if (i.opcodeName == "SETTABUP") {
                                block += abc("GETUPVAL", scratch + 2, i.a)
                                abc("SETTABLE", scratch + 2, b, c, k)
                            } else if (key) abc("SETTABLE", i.a, b, c, k)
                            else i.withC(c).withK(k)
                        }
                    }
                }
                block += lowered
                if (i.skipsNextInstruction) {
                    // VM comparisons fetch the following JMP. Move the pair together;
                    // preserve A on the original 5.3 jump to retain close side effects.
                    block += p.code[pc + 1].relocated(pc + 1, start + block.size)
                    block += jumpTo(v, start + block.size, pc + 2)
                } else block += jumpTo(v, start + block.size, pc + 1)
                LuaPrototypeScope(p).apply {
                    set(pc, jumpTo(v, pc, start))
                    appendAll(block)
                }
                if (lines.isNotEmpty()) repeat(block.size) { lines += lines[pc] }
                changed++
            }
            if (lines.isNotEmpty()) p.setAbsoluteLines(lines)
        }
        return changed
    }
}
