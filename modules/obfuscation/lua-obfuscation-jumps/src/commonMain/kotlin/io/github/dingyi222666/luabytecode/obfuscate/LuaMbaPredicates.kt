package io.github.dingyi222666.luabytecode.obfuscate
import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaInstructionFactory

import io.github.dingyi222666.luabytecode.bytecode.*
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTo
import io.github.dingyi222666.luabytecode.bytecode.edit.internConstant
import io.github.dingyi222666.luabytecode.bytecode.edit.*



/** Random compositions over Z/(2^64), using only private, initialized integer slots.
 * Each layer's result feeds the next layer and the final branch. This raises expression
 * cost; it is not a claim of resistance to symbolic execution or MBA solvers.
 */
internal object LuaMbaPredicates {
    fun build(p: LuaPrototype, base: Int, random: ObfRandom, requested: OpaquePredicate): LuaJunkCode.PredicatePlan {
        require(p.numParams > 0 && base + 8 <= p.maxStackSize)
        val v = p.version
        val code = ArrayList<LuaInstruction>()
        fun abc(name: String, a: Int, b: Int = 0, c: Int = 0, k: Int = 0) =
            LuaInstructionFactory.abc(v, LuaOpcodes.opcodeOf(v, name)!!, a, b, c, k)
        fun load(reg: Int, value: Long) = LuaInstructionFactory.abx(v, "LOADK",
            reg, p.internConstant(LuaConstant.Integer(value)))
        fun emit(expr: LuaExpr, dest: Int, free: List<Int>) {
            val stream = io.github.dingyi222666.luabytecode.bytecode.edit.LuaPrototypeScope(p).stream()
            stream.emitExpression(p, expr, dest, free, ::load)
            code += stream.build()
        }
        fun calculate(expr: LuaExpr, dest: Int) {
            emit(expr, base + 4, listOf(base + 5, base + 6, base + 7))
            code += abc("MOVE", dest, base + 4)
        }
        val x = random.nextLong()
        code += load(base, x)
        code += if (v == LuaBytecodeVersion.LUA_5_3) abc("TEST", 0, 0, 0) else abc("TEST", 0)
        code += jumpTo(v, 2, 4)
        code += load(base, x xor (random.nextLong() or 1L))
        calculate(add(mul(LuaExpr.Reg(base), LuaExpr.Num(random.nextLong() or 1L)), LuaExpr.Num(random.nextLong())), base + 1)
        val a = LuaExpr.Reg(base)
        val b = LuaExpr.Reg(base + 1)
        val family = if (requested == OpaquePredicate.MIXED)
            OpaquePredicate.entries.filter { it != OpaquePredicate.MIXED }.let { it[random.nextInt(it.size)] }
        else requested
        val pair = when (family) {
            OpaquePredicate.NON_SQUARE -> mul(a, a) to sub(mul(LuaExpr.Num(7), mul(b, b)), LuaExpr.Num(1))
            OpaquePredicate.MBA_CARRY_CHAIN -> add(a, b) to carry(a, b)
            OpaquePredicate.MBA_BORROW_CHAIN -> sub(a, b) to borrow(a, b)
            OpaquePredicate.MBA_MASK_SELECT -> {
                val mask = LuaExpr.Num(random.nextLong())
                bor(band(a, mask), band(b, bnot(mask))) to bxor(b, band(bxor(a, b), mask))
            }
            OpaquePredicate.MBA_PRODUCT_PARTITION -> mul(a, b) to
                add(mul(bor(a, b), band(a, b)), mul(band(a, bnot(b)), band(bnot(a), b)))
            OpaquePredicate.MIXED -> error("resolved above")
        }
        calculate(pair.first, base + 2)
        calculate(pair.second, base + 3)
        // Independently encoded, bijective outer layers preserve equality AND inequality.
        // The final predicate is never merely one of the base identities.
        repeat(2 + random.nextInt(4)) {
            val value = random.nextLong() or 1L
            val rotation = 1L + random.nextInt(63)
            val shape = random.nextInt(4)
            for (side in 0..1) {
                val reg = base + 2 + side
                val e = LuaExpr.Reg(reg)
                val n = LuaExpr.Num(value)
                val expanded = random.nextBoolean()
                val expr = when (shape) {
                    0 -> if (expanded) carry(e, n) else add(bor(e, n), band(e, n))
                    1 -> if (expanded) sub(bor(e, n), band(e, n)) else bor(band(e, bnot(n)), band(bnot(e), n))
                    2 -> if (expanded) add(shl(mul(e, LuaExpr.Num(value ushr 1)), 1), e) else mul(e, n)
                    else -> if (expanded) bor(shl(e, rotation), shr(e, 64 - rotation))
                        else add(shr(e, 64 - rotation), shl(e, rotation))
                }
                calculate(expr, reg)
            }
        }
        val plan = LuaJunkCode.PredicatePlan(code, base + 2, base + 3, family != OpaquePredicate.NON_SQUARE)
        // Independently execute final encoded words, including every generated arithmetic/MM pair.
        val checked = code.toMutableList()
        checked += LuaJunkCode.compareBranch(p, plan.left, plan.right, plan.alwaysTrue)
        val trap = checked.size + 2
        val good = trap + 1
        checked += jumpTo(v, checked.size, trap)
        checked += jumpTo(v, checked.size, good)
        repeat(2) { checked += abc("RETURN", 0, 1) }
        CffTranslationValidator.verify(checked, p.constants, listOf(CffControlSite(0, 0)),
            listOf(good, trap), base, p.maxStackSize, 100_000)
        return plan
    }
}
