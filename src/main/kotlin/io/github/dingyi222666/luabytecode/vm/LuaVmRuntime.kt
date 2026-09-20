package io.github.dingyi222666.luabytecode.vm

/** Explicit result sequence. Nil values are retained; [size] is never inferred from contents. */
data class LuaVmSeq(val values: List<Any?>) {
    val size: Int get() = values.size
    operator fun get(index: Int): Any? = values.getOrNull(index)
    companion object { val EMPTY = LuaVmSeq(emptyList()) }
}

/** Shared mutable cell used by virtual closures and upvalues. */
class LuaVmCell(var value: Any?)

/** Callable boundary shared by VM closures and host adapters. */
fun interface LuaVmCallable {
    fun invoke(arguments: LuaVmSeq): LuaVmSeq
}

/** A virtual frame owns its registers, top and captured cells. */
class LuaVmFrame(
    val registers: Array<Any?>,
    var top: Int,
    val upvalues: List<LuaVmCell>,
    val varargs: LuaVmSeq = LuaVmSeq.EMPTY,
) {
    val toBeClosed = ArrayList<Int>()
    private val registerCells = arrayOfNulls<LuaVmCell>(registers.size)
    fun read(index: Int): Any? {
        val cell = registerCells.getOrNull(index)
        return if (cell != null) cell.value else registers.getOrNull(index)
    }
    fun closeCells(first: Int) {
        for (index in first until registerCells.size) {
            registerCells[index]?.let { registers[index] = it.value }
            registerCells[index] = null
        }
    }
    fun write(index: Int, value: Any?) {
        require(index >= 0) { "negative virtual register $index" }
        if (index >= registers.size) throw IllegalStateException("virtual register $index exceeds frame capacity ${registers.size}")
        registers[index] = value
        registerCells[index]?.value = value
        if (index + 1 > top) top = index + 1
    }
    fun captureRegister(index: Int): LuaVmCell {
        require(index in registers.indices) { "capture register $index out of range" }
        return registerCells[index] ?: LuaVmCell(registers[index]).also { registerCells[index] = it }
    }
    fun writeResults(base: Int, result: LuaVmSeq, count: Int) {
        if (count < 0) {
            for (i in result.values.indices) write(base + i, result.values[i])
            top = base + result.size
            return
        }
        repeat(count) { i -> write(base + i, result[i]) }
        top = maxOf(top, base + count)
    }
    fun sequence(base: Int, count: Int): LuaVmSeq =
        if (count < 0) LuaVmSeq((base until top).map(::read))
        else LuaVmSeq((0 until count).map { read(base + it) })
}

internal fun Any?.asVmCallable(): LuaVmCallable? = when (this) {
    is LuaVmCallable -> this
    is Function1<*, *> -> LuaVmCallable { seq ->
        @Suppress("UNCHECKED_CAST")
        val fn = this as (List<Any?>) -> List<Any?>
        LuaVmSeq(fn(seq.values))
    }
    else -> null
}

internal fun Any?.cellValue(): Any? = if (this is LuaVmCell) value else this
