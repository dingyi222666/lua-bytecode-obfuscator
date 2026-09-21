package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.*
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTarget

/**
 * Independent, deliberately small interpreter for emitted management instructions.
 * It consumes encoded words, not the emitter's expressions. Original Lua operations
 * remain opaque and are checked separately by their port/operand mapping.
 * The complete control input domain is the two truth projections (including nil).
 */
object CffTranslationValidator {
    data class Result(val paths: Int, val steps: Int)

    fun checkStructure(prototype: LuaPrototype) {
        val code = prototype.code
        val tails = HashSet<Int>()
        for ((pc, i) in code.withIndex()) {
            require(i.mode != null) { "unknown opcode at $pc" }
            val next = code.getOrNull(pc + 1)
            if (i.opcodeName == "LOADK") require(i.bx in prototype.constants.indices) { "invalid LOADK index" }
            val mm = LuaArithmetic.eventOf(i.opcodeName.removeSuffix("K").removeSuffix("I"))
            if (prototype.version != LuaBytecodeVersion.LUA_5_3 && mm != null) {
                require(next?.opcodeName in setOf("MMBIN", "MMBINI", "MMBINK")) { "missing MM tail at $pc" }
                tails += pc + 1
            }
            val extra = i.opcodeName == "LOADKX" ||
                (i.opcodeName == "NEWTABLE" && prototype.version != LuaBytecodeVersion.LUA_5_3) ||
                (i.opcodeName == "SETLIST" && if (prototype.version == LuaBytecodeVersion.LUA_5_3) i.c == 0 else i.k != 0)
            if (extra) {
                require(next?.opcodeName == "EXTRAARG") { "missing EXTRAARG at $pc" }
                tails += pc + 1
            }
            if (i.opcodeName == "TFORCALL") {
                require(next?.opcodeName == "TFORLOOP") { "TFORCALL must consume TFORLOOP" }
                require(next.a == i.a + if (prototype.version == LuaBytecodeVersion.LUA_5_3) 2 else 0) {
                    "invalid generic loop register window"
                }
            }
            if (i.opcodeName == "TFORPREP") {
                val call = code.getOrNull(i.jumpTarget(pc) ?: -1)
                require(call?.opcodeName == "TFORCALL" && call.a == i.a) { "invalid native generic-for target" }
            }
        }
        for ((pc, i) in code.withIndex()) {
            i.jumpTarget(pc)?.let { target ->
                require(target in code.indices && target !in tails) { "invalid jump target $target at $pc" }
            }
            if (i.opcodeName == "EXTRAARG" || i.opcodeName.startsWith("MMBIN")) {
                require(pc in tails) { "orphan instruction tail at $pc" }
            }
        }
    }

    fun verify(
        code: List<LuaInstruction>, constants: List<LuaConstant>,
        sites: List<CffControlSite>, destinations: List<Int>,
        firstPrivateRegister: Int, frameSize: Int, budget: Int,
    ): Result {
        val stops = destinations.withIndex().associate { it.value to it.index }
        var total = 0
        var paths = 0
        val unset = Any()
        for (site in sites) for (input in listOf<Any?>(null, false, true)) {
            val registers = Array<Any?>(frameSize) { unset }
            registers[0] = input
            var pc = site.pc
            var count = 0
            fun read(r: Int): Any? {
                require(r in registers.indices) { "register outside frame: $r" }
                require(registers[r] !== unset) { "uninitialized control register R$r at $pc" }
                return registers[r]
            }
            fun write(r: Int, value: Any?) {
                require(r in firstPrivateRegister until frameSize) { "control writes original/outside register R$r" }
                registers[r] = value
            }
            fun integer(r: Int) = read(r) as? Long ?: error("noninteger R$r at $pc")
            fun truth(value: Any?) = value != null && value != false
            fun constant(index: Int): Any? = when (val c = constants[index]) {
                is LuaConstant.Integer -> c.value
                is LuaConstant.Number -> c.value
                is LuaConstant.Boolean -> c.value
                is LuaConstant.Str -> c.value
                LuaConstant.Nil -> null
            }
            fun operand(i: LuaInstruction, r: Int): Any? =
                if (i.version == LuaBytecodeVersion.LUA_5_3 && r >= 256) constant(r - 256) else read(r)
            fun shift(value: Long, by: Long): Long = when {
                by >= 64 || by <= -64 -> 0L
                by >= 0 -> value shl by.toInt()
                else -> value ushr (-by).toInt()
            }
            while (true) {
                if (count > 0 && pc in stops) {
                    require(stops[pc] == site.target) { "packet for ${site.target} reached ${stops[pc]}" }
                    break
                }
                require(++total <= budget) { "control verification budget exceeded" }
                require(++count <= code.size * 3) { "nonterminating management path" }
                val i = code.getOrNull(pc) ?: error("control escaped code at $pc")
                val v53 = i.version == LuaBytecodeVersion.LUA_5_3
                var next = pc + 1
                when (i.opcodeName) {
                    "LOADK" -> write(i.a, constant(i.bx))
                    "LOADI" -> write(i.a, i.sbx.toLong())
                    "MOVE" -> write(i.a, read(i.b))
                    "LOADNIL" -> for (r in i.a..i.a + i.b) write(r, null)
                    "LOADBOOL" -> { write(i.a, i.b != 0); if (i.c != 0) next++ }
                    "LOADTRUE" -> write(i.a, true)
                    "LOADFALSE", "LFALSESKIP" -> {
                        write(i.a, false); if (i.opcodeName == "LFALSESKIP") next++
                    }
                    "NOT" -> write(i.a, !truth(read(i.b)))
                    "BNOT" -> write(i.a, integer(i.b).inv())
                    "JMP" -> {
                        require(!v53 || i.a == 0) { "injected jump closes original upvalues" }
                        next += if (v53) i.sbx else i.sj
                    }
                    "TEST" -> if (truth(read(i.a)) != (if (v53) i.c != 0 else i.k != 0)) next++
                    "EQ" -> {
                        val equal = if (v53) operand(i, i.b) == operand(i, i.c) else read(i.a) == read(i.b)
                        if (equal != (if (v53) i.a != 0 else i.k != 0)) next++
                    }
                    "ADD", "SUB", "MUL", "MOD", "IDIV", "BAND", "BOR", "BXOR", "SHL", "SHR" -> {
                        val a = operand(i, i.b) as? Long ?: error("noninteger arithmetic")
                        val b = operand(i, i.c) as? Long ?: error("noninteger arithmetic")
                        val value = when (i.opcodeName) {
                            "ADD" -> a + b
                            "SUB" -> a - b
                            "MUL" -> a * b
                            "MOD" -> a.mod(b)
                            "IDIV" -> a.floorDiv(b)
                            "BAND" -> a and b
                            "BOR" -> a or b
                            "BXOR" -> a xor b
                            "SHL" -> shift(a, b)
                            else -> shift(a, -b)
                        }
                        write(i.a, value)
                        if (!v53) {
                            val mm = code.getOrNull(next) ?: error("missing MM instruction")
                            require(mm.opcodeName == "MMBIN" && mm.a == i.b && mm.b == i.c &&
                                mm.c == LuaArithmetic.eventOf(i.opcodeName)) { "invalid arithmetic pair" }
                            next++
                        }
                    }
                    "NEWTABLE" -> {
                        write(i.a, HashMap<Any, Any?>())
                        if (!v53) {
                            require(code.getOrNull(next)?.opcodeName == "EXTRAARG")
                            next++
                        }
                    }
                    "SETTABLE" -> {
                        @Suppress("UNCHECKED_CAST") val table = read(i.a) as MutableMap<Any, Any?>
                        table[requireNotNull(operand(i, i.b))] = operand(i, i.c)
                    }
                    "GETTABLE" -> {
                        val table = read(i.b) as Map<*, *>
                        write(i.a, table[operand(i, i.c)])
                    }
                    else -> error("unexpected management opcode ${i.opcodeName} at $pc")
                }
                pc = next
            }
            paths++
        }
        return Result(paths, total)
    }
}
