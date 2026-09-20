package io.github.dingyi222666.luabytecode.bytecode.edit

import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype

/** Version independent integer expression tree for bytecode synthesis. */
sealed interface LuaExpr {
    data class Reg(val index: Int) : LuaExpr
    data class Num(val value: Long) : LuaExpr
    data class Bin(val op: String, val left: LuaExpr, val right: LuaExpr) : LuaExpr
}

fun reg(index: Int): LuaExpr = LuaExpr.Reg(index)
fun num(value: Long): LuaExpr = LuaExpr.Num(value)
fun add(a: LuaExpr, b: LuaExpr) = LuaExpr.Bin("ADD", a, b)
fun sub(a: LuaExpr, b: LuaExpr) = LuaExpr.Bin("SUB", a, b)
fun mul(a: LuaExpr, b: LuaExpr) = LuaExpr.Bin("MUL", a, b)
fun band(a: LuaExpr, b: LuaExpr) = LuaExpr.Bin("BAND", a, b)
fun bor(a: LuaExpr, b: LuaExpr) = LuaExpr.Bin("BOR", a, b)
fun bxor(a: LuaExpr, b: LuaExpr) = LuaExpr.Bin("BXOR", a, b)
fun bnot(a: LuaExpr) = bxor(a, num(-1))
fun shl(a: LuaExpr, n: Long) = LuaExpr.Bin("SHL", a, num(n))
fun shr(a: LuaExpr, n: Long) = LuaExpr.Bin("SHR", a, num(n))
fun carry(a: LuaExpr, b: LuaExpr) = add(bxor(a, b), shl(band(a, b), 1))
fun borrow(a: LuaExpr, b: LuaExpr) = sub(bxor(a, b), shl(band(bnot(a), b), 1))

/** Compiles [expr] into an editor instruction stream. */
fun LuaPrototypeScope.emitExpression(
    expr: LuaExpr,
    destination: Int,
    free: List<Int>,
    load: (register: Int, value: Long) -> LuaInstruction,
) {
    val stream = stream()
    stream.emitExpression(prototype, expr, destination, free, load)
    appendAll(stream.build())
}

/** Compiles an expression into an existing stream without mutating a prototype. */
fun LuaInstructionStream.emitExpression(
    prototype: LuaPrototype,
    expr: LuaExpr,
    destination: Int,
    free: List<Int>,
    load: (register: Int, value: Long) -> LuaInstruction,
) {
    fun pair(op: String, a: Int, b: Int, c: Int) {
        abc(op, a, b, c)
        val events = mapOf("ADD" to 6, "SUB" to 7, "MUL" to 8, "BAND" to 13, "BOR" to 14,
            "BXOR" to 15, "SHL" to 16, "SHR" to 17)
        events[op]?.let { if (versionOf(prototype) != io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion.LUA_5_3) abc("MMBIN", b, c, it) }
    }
    fun compile(value: LuaExpr, target: Int, slots: List<Int>) {
        when (value) {
            is LuaExpr.Reg -> abc("MOVE", target, value.index, 0)
            is LuaExpr.Num -> emit(load(target, value.value))
            is LuaExpr.Bin -> {
                require(slots.isNotEmpty()) { "expression exceeds private register window" }
                compile(value.left, target, slots)
                compile(value.right, slots.first(), slots.drop(1))
                pair(value.op, target, target, slots.first())
            }
        }
    }
    compile(expr, destination, free)
}

private fun versionOf(prototype: LuaPrototype) = prototype.version

/** Fluent expression builder shared by obfuscation passes. */
class LuaExprBuilder {
    fun reg(index: Int): LuaExpr = LuaExpr.Reg(index)
    fun num(value: Long): LuaExpr = LuaExpr.Num(value)
    fun add(a: LuaExpr, b: LuaExpr): LuaExpr = io.github.dingyi222666.luabytecode.bytecode.edit.add(a, b)
    fun sub(a: LuaExpr, b: LuaExpr): LuaExpr = io.github.dingyi222666.luabytecode.bytecode.edit.sub(a, b)
    fun mul(a: LuaExpr, b: LuaExpr): LuaExpr = io.github.dingyi222666.luabytecode.bytecode.edit.mul(a, b)
    fun band(a: LuaExpr, b: LuaExpr): LuaExpr = io.github.dingyi222666.luabytecode.bytecode.edit.band(a, b)
    fun bor(a: LuaExpr, b: LuaExpr): LuaExpr = io.github.dingyi222666.luabytecode.bytecode.edit.bor(a, b)
    fun bxor(a: LuaExpr, b: LuaExpr): LuaExpr = io.github.dingyi222666.luabytecode.bytecode.edit.bxor(a, b)
    fun bnot(a: LuaExpr): LuaExpr = io.github.dingyi222666.luabytecode.bytecode.edit.bnot(a)
    fun shl(a: LuaExpr, n: Long): LuaExpr = io.github.dingyi222666.luabytecode.bytecode.edit.shl(a, n)
    fun shr(a: LuaExpr, n: Long): LuaExpr = io.github.dingyi222666.luabytecode.bytecode.edit.shr(a, n)
    fun carry(a: LuaExpr, b: LuaExpr): LuaExpr = io.github.dingyi222666.luabytecode.bytecode.edit.carry(a, b)
    fun borrow(a: LuaExpr, b: LuaExpr): LuaExpr = io.github.dingyi222666.luabytecode.bytecode.edit.borrow(a, b)
}

fun expr(block: LuaExprBuilder.() -> LuaExpr): LuaExpr = LuaExprBuilder().block()
