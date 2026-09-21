package io.github.dingyi222666.luabytecode.vm

import kotlin.math.pow

/** Reference interpreter for the private ISA. Runtime state is explicit: frames, top,
 * result sequences and captured cells are separate from source/CFF data. */
object LuaVmInterpreter {
    class Result(val returned: List<Any?>, val steps: Int) {
        val sequence: LuaVmSeq get() = LuaVmSeq(returned)
    }

    fun run(
        program: LuaVmTranslator.VmProgram,
        map: LuaVmProgram.OpcodeMap,
        args: List<Any?> = emptyList(),
        upvalues: List<Any?> = emptyList(),
        maxSteps: Int = 1_000_000,
    ): Result = runSequence(program, map, LuaVmSeq(args), upvalues.map { it as? LuaVmCell ?: LuaVmCell(it) }, maxSteps)

    internal fun runSequence(
        program: LuaVmTranslator.VmProgram,
        map: LuaVmProgram.OpcodeMap,
        args: LuaVmSeq,
        upvalues: List<LuaVmCell>,
        maxSteps: Int,
    ): Result {
        val capacity = maxOf(program.maxStackSize, args.size + 8, 8)
        val fixedArgs = args.values.take(program.numParams)
        val varargs = LuaVmSeq(args.values.drop(program.numParams))
        val frame = LuaVmFrame(arrayOfNulls(capacity), fixedArgs.size, upvalues, varargs)
        fixedArgs.forEachIndexed(frame::write)
        if (program.varargTable) {
            val table = LinkedHashMap<Any?, Any?>()
            table["n"] = varargs.size.toLong()
            varargs.values.forEachIndexed { index, value -> table[index + 1L] = value }
            frame.write(program.numParams, table)
        }
        var pc = 0
        var steps = 0
        fun truthy(v: Any?): Boolean = v != null && v != false
        fun offset(value: Int): Int = if (value >= 128) value - 256 else value
        fun invoke(value: Any?, input: LuaVmSeq): LuaVmSeq =
            value.cellValue().asVmCallable()?.invoke(input)
                ?: error("vm: value is not callable: ${value.cellValue()}")
        fun numeric(v: Any?): Long = when (val x = v.cellValue()) {
            null -> error("vm: arithmetic on nil")
            is Long -> x
            is Int -> x.toLong()
            is Double -> x.toLong()
            is Float -> x.toLong()
            else -> error("vm: not a number: $x")
        }
        fun constant(c: io.github.dingyi222666.luabytecode.bytecode.LuaConstant): Any? = when (c) {
            is io.github.dingyi222666.luabytecode.bytecode.LuaConstant.Integer -> c.value
            is io.github.dingyi222666.luabytecode.bytecode.LuaConstant.Number -> c.value
            is io.github.dingyi222666.luabytecode.bytecode.LuaConstant.Str -> c.value.toString()
            is io.github.dingyi222666.luabytecode.bytecode.LuaConstant.Boolean -> c.value
            io.github.dingyi222666.luabytecode.bytecode.LuaConstant.Nil -> null
        }
        while (true) {
            if (steps++ >= maxSteps) error("vm: step limit")
            require(pc in program.words.indices) { "vm: pc $pc is out of range" }
            val insn = LuaVmProgram.decode(map, program.words[pc])
            pc++
            when (insn.op) {
                LuaVmProgram.Op.LOADI -> frame.write(insn.a, offset(insn.b).toLong())
                LuaVmProgram.Op.MOVE -> frame.write(insn.a, frame.read(insn.b))
                LuaVmProgram.Op.LOADNIL -> frame.write(insn.a, null)
                LuaVmProgram.Op.LOADK -> frame.write(insn.a, constant(program.constants[insn.b]))
                LuaVmProgram.Op.ADD -> frame.write(insn.a, numeric(frame.read(insn.b)) + numeric(frame.read(insn.c)))
                LuaVmProgram.Op.SUB -> frame.write(insn.a, numeric(frame.read(insn.b)) - numeric(frame.read(insn.c)))
                LuaVmProgram.Op.MUL -> frame.write(insn.a, numeric(frame.read(insn.b)) * numeric(frame.read(insn.c)))
                LuaVmProgram.Op.DIV -> frame.write(insn.a, numeric(frame.read(insn.b)).toDouble() / numeric(frame.read(insn.c)).toDouble())
                LuaVmProgram.Op.IDIV -> frame.write(insn.a, numeric(frame.read(insn.b)).floorDiv(numeric(frame.read(insn.c))))
                LuaVmProgram.Op.MOD -> frame.write(insn.a, numeric(frame.read(insn.b)) % numeric(frame.read(insn.c)))
                LuaVmProgram.Op.POW -> frame.write(insn.a, numeric(frame.read(insn.b)).toDouble().pow(numeric(frame.read(insn.c)).toDouble()))
                LuaVmProgram.Op.BAND -> frame.write(insn.a, numeric(frame.read(insn.b)) and numeric(frame.read(insn.c)))
                LuaVmProgram.Op.BOR -> frame.write(insn.a, numeric(frame.read(insn.b)) or numeric(frame.read(insn.c)))
                LuaVmProgram.Op.BXOR -> frame.write(insn.a, numeric(frame.read(insn.b)) xor numeric(frame.read(insn.c)))
                LuaVmProgram.Op.SHL -> frame.write(insn.a, numeric(frame.read(insn.b)) shl numeric(frame.read(insn.c)).toInt())
                LuaVmProgram.Op.SHR -> frame.write(insn.a, numeric(frame.read(insn.b)) shr numeric(frame.read(insn.c)).toInt())
                LuaVmProgram.Op.BNOT -> frame.write(insn.a, numeric(frame.read(insn.b)).inv())
                LuaVmProgram.Op.UNM -> frame.write(insn.a, -numeric(frame.read(insn.b)))
                LuaVmProgram.Op.NOT -> frame.write(insn.a, !truthy(frame.read(insn.b)))
                LuaVmProgram.Op.LEN -> frame.write(insn.a, when (val v = frame.read(insn.b)) {
                    is String -> v.length.toLong()
                    is Map<*, *> -> v.keys.filterIsInstance<Long>().maxOrNull() ?: 0L
                    is List<*> -> v.size.toLong()
                    else -> error("vm: length of unsupported value: $v")
                })
                LuaVmProgram.Op.CONCAT -> {
                    val text = (insn.b..insn.c).joinToString("") { index ->
                        when (val v = frame.read(index)) {
                            null -> "nil"
                            is String -> v
                            else -> v.toString()
                        }
                    }
                    frame.write(insn.a, text)
                }
                LuaVmProgram.Op.LT -> frame.write(insn.a, numeric(frame.read(insn.b)) < numeric(frame.read(insn.c)))
                LuaVmProgram.Op.LE -> frame.write(insn.a, numeric(frame.read(insn.b)) <= numeric(frame.read(insn.c)))
                LuaVmProgram.Op.EQ -> frame.write(insn.a, frame.read(insn.b).cellValue() == frame.read(insn.c).cellValue())
                LuaVmProgram.Op.FORPREP -> {
                    frame.write(insn.a, numeric(frame.read(insn.a)) - numeric(frame.read(insn.a + 2)))
                    pc += offset(insn.b)
                }
                LuaVmProgram.Op.FORLOOP -> {
                    val step = numeric(frame.read(insn.a + 2)); val next = numeric(frame.read(insn.a)) + step
                    val limit = numeric(frame.read(insn.a + 1))
                    if (if (step > 0) next <= limit else limit <= next) {
                        frame.write(insn.a, next); frame.write(insn.a + 3, next); pc += offset(insn.b)
                    }
                }
                LuaVmProgram.Op.FORPREP_COUNT -> {
                    val init = numeric(frame.read(insn.a))
                    val limit = numeric(frame.read(insn.a + 1))
                    val step = numeric(frame.read(insn.a + 2))
                    require(step != 0L) { "vm: for step is zero" }
                    val distance = if (step > 0) limit - init else init - limit
                    if (distance < 0) {
                        pc += offset(insn.b)
                    } else {
                        val count = distance / kotlin.math.abs(step)
                        // 5.4 keeps index in A, remaining count in A+1, step in A+2,
                        // and the user-visible control value in A+3. 5.5 keeps count in A,
                        // step in A+1 and control in A+2; [insn.c] carries that offset.
                        if (insn.c == 2) {
                            frame.write(insn.a, count)
                            frame.write(insn.a + 1, step)
                            frame.write(insn.a + 2, init)
                        } else {
                            frame.write(insn.a, init)
                            frame.write(insn.a + 1, count)
                            frame.write(insn.a + 2, step)
                            frame.write(insn.a + 3, init)
                        }
                    }
                }
                LuaVmProgram.Op.FORLOOP_COUNT -> {
                    if (insn.c == 2) {
                        val count = numeric(frame.read(insn.a))
                        if (count > 0) {
                            val step = numeric(frame.read(insn.a + 1))
                            val next = numeric(frame.read(insn.a + 2)) + step
                            frame.write(insn.a, count - 1)
                            frame.write(insn.a + 2, next)
                            pc += offset(insn.b)
                        }
                    } else {
                        val count = numeric(frame.read(insn.a + 1))
                        if (count > 0) {
                            val step = numeric(frame.read(insn.a + 2))
                            val next = numeric(frame.read(insn.a)) + step
                            frame.write(insn.a + 1, count - 1)
                            frame.write(insn.a, next)
                            frame.write(insn.a + 3, next)
                            pc += offset(insn.b)
                        }
                    }
                }
                LuaVmProgram.Op.JMP -> pc += offset(insn.a)
                LuaVmProgram.Op.JMPIF -> if (truthy(frame.read(insn.a))) pc += offset(insn.b)
                LuaVmProgram.Op.JMPIFNOT -> if (!truthy(frame.read(insn.a))) pc += offset(insn.b)
                LuaVmProgram.Op.TEST -> if (truthy(frame.read(insn.a)) != (insn.c != 0)) pc++
                LuaVmProgram.Op.TESTSET -> {
                    val value = frame.read(insn.b)
                    if (truthy(value) == (insn.c != 0)) frame.write(insn.a, value) else pc++
                }
                LuaVmProgram.Op.SELF -> {
                    val table = frame.read(insn.b)
                    frame.write(insn.a + 1, table)
                    @Suppress("UNCHECKED_CAST")
                    frame.write(insn.a, (table as? Map<Any?, Any?>)?.get(frame.read(insn.c)))
                }
                LuaVmProgram.Op.LOADBOOL -> { frame.write(insn.a, insn.b != 0); if (insn.c != 0) pc++ }
                LuaVmProgram.Op.SETLIST -> {
                    @Suppress("UNCHECKED_CAST") val table = frame.read(insn.a) as? MutableMap<Any?, Any?>
                    val count = if (insn.b == 0) maxOf(0, frame.top - insn.a - 1) else insn.b
                    repeat(count) { slot -> table?.set(numeric(frame.read(insn.c)) + slot + 1L, frame.read(insn.a + slot + 1)) }
                }
                LuaVmProgram.Op.TBC -> if (insn.a !in frame.toBeClosed) frame.toBeClosed += insn.a
                LuaVmProgram.Op.CLOSE -> { frame.closeCells(insn.a); frame.toBeClosed.removeAll { it >= insn.a } }
                LuaVmProgram.Op.GETVARG -> {
                    val key = frame.read(insn.c)
                    val index = when (key) {
                        is Long -> key
                        is Double -> if (key.isFinite() && key == key.toLong().toDouble()) key.toLong() else -1L
                        else -> -1L
                    }
                    frame.write(insn.a, if (key == "n") frame.varargs.size.toLong()
                        else if (index in 1L..frame.varargs.size.toLong()) frame.varargs[index.toInt() - 1] else null)
                }
                LuaVmProgram.Op.ERRNNIL -> if (frame.read(insn.a) != null) error(constant(program.constants[insn.b]).toString())
                LuaVmProgram.Op.NEWTABLE -> frame.write(insn.a, LinkedHashMap<Any?, Any?>())
                LuaVmProgram.Op.GETTABUP -> {
                    val table = frame.upvalues.getOrNull(insn.b)?.value as? Map<*, *>
                    frame.write(insn.a, table?.get(constant(program.constants[insn.c])))
                }
                LuaVmProgram.Op.SETTABUP -> {
                    val table = frame.upvalues.getOrNull(insn.b)?.value as? MutableMap<Any?, Any?>
                    table?.set(constant(program.constants[insn.c]), frame.read(insn.a))
                }
                LuaVmProgram.Op.GETUPVAL -> frame.write(insn.a, frame.upvalues.getOrNull(insn.b)?.value)
                LuaVmProgram.Op.SETUPVAL -> frame.upvalues.getOrNull(insn.b)?.let { it.value = frame.read(insn.a) }
                LuaVmProgram.Op.VARARG -> {
                    val values = if (insn.c == 0) frame.varargs else {
                        val table = frame.read(insn.c - 1) as Map<*, *>
                        val count = table["n"] as? Long ?: error("vararg table has no proper 'n'")
                        require(count in 0..1073741823L) { "vararg table has no proper 'n'" }
                        LuaVmSeq(List(count.toInt()) { table[it + 1L] })
                    }
                    frame.writeResults(insn.a, values, if (insn.b == 0) -1 else if (insn.b == 255) 0 else insn.b)
                }
                LuaVmProgram.Op.GETTABLE -> {
                    @Suppress("UNCHECKED_CAST") val table = frame.read(insn.b) as? Map<Any?, Any?>
                    frame.write(insn.a, table?.get(frame.read(insn.c)))
                }
                LuaVmProgram.Op.SETTABLE -> {
                    @Suppress("UNCHECKED_CAST") val table = frame.read(insn.a) as? MutableMap<Any?, Any?>
                    table?.set(frame.read(insn.b), frame.read(insn.c))
                }
                LuaVmProgram.Op.CALL -> {
                    val result = invoke(frame.read(insn.a), frame.sequence(insn.a + 1, if (insn.b == 255) -1 else insn.b))
                    frame.writeResults(insn.a, result, if (insn.c == 0) -1 else if (insn.c == 255) 0 else insn.c)
                }
                LuaVmProgram.Op.TAILCALL -> {
                    val result = invoke(frame.read(insn.a), frame.sequence(insn.a + 1, if (insn.b == 255) -1 else insn.b))
                    return Result(result.values, steps)
                }
                LuaVmProgram.Op.CLOSURE -> {
                    val child = program.children.getOrNull(insn.b) ?: error("vm: no child program ${insn.b}")
                    val descriptors = program.childCaptures.getOrNull(insn.b).orEmpty()
                    val captured = descriptors.map { descriptor ->
                        if (descriptor.inStack) frame.captureRegister(descriptor.index)
                        else frame.upvalues.getOrNull(descriptor.index)
                            ?: error("vm: captured upvalue ${descriptor.index} is unavailable")
                    }
                    frame.write(insn.a, LuaVmCallable { input ->
                        runSequence(child, map, input, captured, maxSteps).sequence
                    })
                }
                LuaVmProgram.Op.RETURN -> return Result(frame.sequence(insn.a, if (insn.c == 0) -1 else if (insn.c == 255) 0 else insn.c).values, steps)
                LuaVmProgram.Op.RETK -> return Result(listOf(constant(program.constants[insn.b])), steps)
                else -> error("vm: unsupported op ${insn.op}")
            }
        }
    }
}
