package io.github.dingyi222666.luabytecode.vm

/**
 * P4.3 handler heterogeneity: instead of shipping one fixed interpreter, each chunk gets its
 * **own** interpreter source, generated here. The handlers are semantically equivalent but
 * textually different (dispatch order, statement shape, temporary usage, constant splitting),
 * which is exactly what Rolles WOOT'09 and Luo & Ming ask for: a fixed interpreter can be
 * fingerprinted once and then reused against every sample of a family.
 *
 * Only transformations that are safe for **any** operand type are used here: the operand order
 * of arithmetic is preserved (a `__add` metamethod observes it), only the *shape* of the code
 * changes. Templates that reorder operands or invert comparisons are deliberately not used yet
 * (they need a "numbers only" proof, docs section 11 P4.3).
 *
 * ISSUE VM-1/VM-2 (B2 round 1) changes:
 *  * the decryptor is generated per module and takes
 *    the per chunk LCG (mul/add) from the *encrypted* program header, and whitens each decrypted
 *    word again with a `pc`-coupled mask, so a decoder cannot be sliced out of the module;
 *  * the constant pool ships **every** constant encrypted (tagged byte lists), not only strings;
 *  * the dispatch skeleton is one of three shapes (chained `elseif`, table driven, nested
 *    binary splits), chosen per chunk, and the handler bodies gained structural variants;
 *  * every identifier / step limit / salt literal is drawn per chunk, so cross product
 *    fingerprints (sorted handler bodies, dispatch order, template diff) all break.
 */
object LuaVmInterpreterGenerator {
    /** The subset the generated interpreter supports (the fixture set uses exactly this). */
    private val HANDLERS = listOf("LOADK", "MOVE", "LOADNIL", "LOADBOOL", "SETLIST", "TBC", "CLOSE", "GETVARG", "ERRNNIL", "ADD", "SUB", "MUL", "DIV", "IDIV", "MOD", "POW", "UNM", "BNOT", "NOT", "LEN", "CONCAT", "EQ", "LT", "LE", "TEST", "TESTSET", "SELF", "JMP", "JMPIF", "JMPIFNOT", "RETURN", "RETK", "FORPREP", "FORLOOP", "FORPREP_COUNT", "FORLOOP_COUNT", "GETTABLE", "CALL", "TAILCALL", "CLOSURE", "LOADI", "NEWTABLE", "GETTABUP", "SETTABUP", "GETUPVAL", "SETUPVAL", "VARARG", "SETTABLE",
        // B2 Round4: the translator's SIMPLE map can emit bit ops (5.3 `a|b` compiles to BOR
        // etc.), but the generated interpreter had no handler -- the module shipped, then
        // crashed (or silently executed a wrong tree leaf) at run time. Same semantics as the
        // JVM reference interpreter: operand order preserved, real Lua values.
        "BAND", "BOR", "BXOR", "SHL", "SHR")

    /**
     * Per chunk drawn names / salts / limits. Everything the red team used to anchor its
     * parser (variable names, fixed step limit, the `M.` module table, salt literals) becomes
     * chunk private here.
     */
    class Names internal constructor(
        val mulName: String,
        val addName: String,
        val stateName: String,
        val moduleTable: String,
        val decryptName: String,
        val decryptConstantsName: String,
        val runName: String,
        val headerSaltExpr: String,
        val stringSaltExpr: String,
        val whiteRot: Long,
        val stepsLimit: Long,
        val magicValue: Int,
        val seedName: String,
        val headerMul: Long,
    )

    internal fun drawNames(random: java.util.Random): Names {
        fun ident(): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz"
            var s = "" + alphabet[random.nextInt(26)]
            repeat(3 + random.nextInt(4)) {
                s += if (random.nextBoolean()) alphabet[random.nextInt(26)] else random.nextInt(10).toString()
            }
            return s
        }
        fun saltExpr(): String = when (random.nextInt(4)) {
            0 -> (random.nextInt(0x2000000) + 1).toString() + " * 128 + " + random.nextInt(128)
            1 -> "(0x" + java.lang.Long.toHexString(random.nextLong() and 0xFFFFFFFL) + " ~ 0)"
            2 -> (random.nextInt(0x80000) + 1).toString() + " * 8192 + " + random.nextInt(8192)
            else -> "(2 ^ " + (random.nextInt(20) + 6) + " - " + random.nextInt(997) + ")"
        }
        return Names(
            mulName = ident(),
            addName = ident(),
            stateName = ident(),
            moduleTable = ident(),
            decryptName = ident(),
            decryptConstantsName = ident(),
            runName = ident(),
            headerSaltExpr = saltExpr(),
            stringSaltExpr = saltExpr(),
            whiteRot = ((random.nextLong() and 0xFFFFFFFFL) or 5L),
            stepsLimit = 8_000_000L + random.nextInt(12_000_000),
            magicValue = random.nextInt(),
            seedName = ident(),
            headerMul = (random.nextLong() and 0xFFFFFFFFL) or 3L,
        )
    }

    private fun handler(op: String, random: java.util.Random): String = when (op) {
        "LOADK" -> when (random.nextInt(3)) {
            0 -> "R[a] = args.k[b]"
            1 -> "local kv = args.k[b]; R[a] = kv"
            else -> "R[a] = (args.k)[b]"
        }
        "LOADI" -> when (random.nextInt(2)) {
            0 -> "R[a] = b"
            else -> "local ib = b; R[a] = ib"
        }
        "NEWTABLE" -> when (random.nextInt(2)) {
            0 -> "R[a] = {}"
            else -> "local nt = {}; R[a] = nt"
        }
        "GETTABUP" -> "R[a] = args.up[b + 1].value[args.k[c]]"
        "SETTABUP" -> "args.up[b + 1].value[args.k[c]] = R[a]"
        "GETUPVAL" -> "R[a] = args.up[b + 1].value"
        "SETUPVAL" -> "args.up[b + 1].value = R[a]"
        "VARARG" -> "local va = args.varargs; if c ~= 0 then va = R[c - 1] end; local n = rawget(va, 'n'); if math.type(n) ~= 'integer' or n < 0 or n > 1073741823 then error(\"vararg table has no proper 'n'\", 0) end; local count = b == 0 and n or (b == 255 and 0 or b); for i = 1, count do local v; if i <= n then v = rawget(va, i) end; sourceWrite(a + i - 1, v) end; if b == 0 then vtop = a + count end"
        "GETTABLE" -> "local t, key = R[b], R[c]; R[a] = t[key]"
        "SETTABLE" -> "local t, key, value = R[a], R[b], R[c]; t[key] = value"
        "MOVE" -> when (random.nextInt(3)) {
            0 -> "R[a] = R[b]"
            1 -> "local mv = R[b]; R[a] = mv"
            else -> "if a ~= b then R[a] = R[b] else R[a] = R[b] end"
        }
        "LOADNIL" -> "R[a] = nil"
        "LOADBOOL" -> "R[a] = (b ~= 0); if c ~= 0 then pc = pc + 1 end"
        "SETLIST" -> "local t, base = R[a], R[c]; local n = b == 0 and (vtop - a - 1) or b; for i = n, 1, -1 do rawset(t, base + i, sourceRead(a + i)) end"
        "TBC" -> "tbcRegister = a"
        "CLOSE" -> "closeCells(a); closeAt = a"
        "GETVARG" -> "local key = R[c]; R[a] = rawget(args.varargs, key)"
        "ERRNNIL" -> "if R[a] ~= nil then error(args.k[b], 0) end"
        "TEST" -> "if (R[a] ~= nil and R[a] ~= false) ~= (c ~= 0) then pc = pc + 1 end"
        "TESTSET" -> "if (R[b] ~= nil and R[b] ~= false) == (c ~= 0) then R[a] = R[b] else pc = pc + 1 end"
        "SELF" -> "local t, key = R[b], R[c]; R[a + 1] = t; R[a] = t[key]"
        "ADD" -> when (random.nextInt(3)) {
            0 -> "R[a] = R[b] + R[c]"
            1 -> "local x = R[b]; R[a] = x + R[c]"
            else -> "local x, y = R[b], R[c]; R[a] = x + y"
        }
        "SUB" -> when (random.nextInt(3)) {
            0 -> "R[a] = R[b] - R[c]"
            1 -> "local x = R[b]; R[a] = x - R[c]"
            else -> "local x, y = R[b], R[c]; R[a] = x - y"
        }
        "MUL" -> when (random.nextInt(3)) {
            0 -> "R[a] = R[b] * R[c]"
            1 -> "local x = R[c]; R[a] = R[b] * x"
            else -> "local x, y = R[b], R[c]; R[a] = x * y"
        }
        "DIV" -> "R[a] = R[b] / R[c]"
        "IDIV" -> "R[a] = R[b] // R[c]"
        "MOD" -> "R[a] = R[b] % R[c]"
        "POW" -> "R[a] = R[b] ^ R[c]"
        "UNM" -> "R[a] = -R[b]"
        "BNOT" -> "R[a] = ~R[b]"
        "NOT" -> "R[a] = (R[b] == nil or R[b] == false)"
        "LEN" -> "R[a] = #R[b]"
        "CONCAT" -> "local value = R[c]; for i = c - 1, b, -1 do value = R[i] .. value end; R[a] = value"
        // bit ops (B2 Round4): plain Lua operators, so semantics (int/float coercion, errors on
        // non numbers) match the native chunk exactly; operand order is preserved.
        "BAND" -> when (random.nextInt(2)) {
            0 -> "R[a] = R[b] & R[c]"
            else -> "local x = R[b]; R[a] = x & R[c]"
        }
        "BOR" -> when (random.nextInt(2)) {
            0 -> "R[a] = R[b] | R[c]"
            else -> "local x = R[b]; R[a] = x | R[c]"
        }
        "BXOR" -> when (random.nextInt(2)) {
            0 -> "R[a] = R[b] ~ R[c]"
            else -> "local x = R[c]; R[a] = R[b] ~ x"
        }
        "SHL" -> when (random.nextInt(2)) {
            0 -> "R[a] = R[b] << R[c]"
            else -> "local x = R[b]; R[a] = x << R[c]"
        }
        "SHR" -> when (random.nextInt(2)) {
            0 -> "R[a] = R[b] >> R[c]"
            else -> "local x = R[b]; R[a] = x >> R[c]"
        }
        // comparisons stay in the "real boolean" form: 0 is truthy in Lua
        "EQ" -> when (random.nextInt(3)) {
            0 -> "R[a] = (R[b] == R[c]) and true or false"
            1 -> "local x, y = R[b], R[c]; R[a] = (x == y) and true or false"
            else -> "R[a] = not (R[b] ~= R[c])"
        }
        "LT" -> when (random.nextInt(2)) {
            0 -> "R[a] = (R[b] < R[c]) and true or false"
            else -> "local x, y = R[b], R[c]; R[a] = (x < y)"
        }
        "LE" -> when (random.nextInt(2)) {
            0 -> "R[a] = (R[b] <= R[c]) and true or false"
            else -> "local x, y = R[b], R[c]; R[a] = (x <= y)"
        }
        "JMP" -> when (random.nextInt(3)) {
            0 -> "pc = pc + aSigned"
            1 -> "local j2 = aSigned; pc = pc + j2"
            else -> "if aSigned ~= 0 then pc = pc + aSigned else pc = pc + aSigned end"
        }
        "JMPIF" -> when (random.nextInt(3)) {
            0 -> "if R[a] ~= nil and R[a] ~= false then pc = pc + b end"
            1 -> "if not (R[a] == nil or R[a] == false) then pc = pc + b end"
            else -> "if R[a] then pc = pc + b end"
        }
        "JMPIFNOT" -> when (random.nextInt(3)) {
            0 -> "if R[a] == nil or R[a] == false then pc = pc + b end"
            1 -> "if not (R[a] ~= nil and R[a] ~= false) then pc = pc + b end"
            else -> "if not R[a] then pc = pc + b end"
        }
        "CALL" ->
            "local f = R[a]; local argv = arguments(a, b); local out = table.pack(f(table.unpack(argv, 1, argv.n))); " +
                "local count = c == 0 and out.n or (c == 255 and 0 or c); " +
                "for i = 1, count do if a < sourceStack then sourceWrite(a + i - 1, out[i]) else R[a + i - 1] = out[i] end end; " +
                "if c == 0 then vtop = a + out.n end"
        "TAILCALL" -> "tailRequest = { f = R[a], argv = arguments(a, b) }; closeCells(0)"
        "CLOSURE" ->
            "local childIndex = childIds[baseIndex][b + 1]; local specs = captures[childIndex]; local childUp = {}; " +
                "for j, d in ipairs(specs) do if d[1] then local cell = cells[d[2]]; " +
                "if not cell then cell = { value = sourceRead(d[2]) }; cells[d[2]] = cell end; childUp[j] = cell " +
                "else childUp[j] = args.up[d[2] + 1] end end; " +
                "R[a] = function(...) local argv = table.pack(...); local np = paramCounts[childIndex]; " +
                "local va = { n = math.max(0, argv.n - np) }; for q = 1, va.n do va[q] = argv[np + q] end; " +
                "argv.k = constants[childIndex]; argv.up = childUp; argv.varargs = va; " +
                "return RUN(programs[childIndex], map, 1, argv, maxSteps, programs, constants, ctrlOffset, captures, childIndex, paramCounts, childIds, frameInfo) end"
        "RETURN" -> "dispatchResult = results(a, c); closeCells(0)"
        "RETK" -> "dispatchResult = table.pack(args.k[b]); closeCells(0)"
        // Lua 5.3 numeric `for` layout (lvm.c): `R[A]` internal index, `R[A+1]` limit,
        // `R[A+2]` step, `R[A+3]` the control variable the body reads. `FORPREP` subtracts one
        // step and falls into the loop test (emitted directly after it by the translator);
        // `FORLOOP` adds the step, tests **sign aware** and jumps back into the body.
        // Real Lua numbers, so integer *and* float loops behave exactly like the native chunk.
        "FORPREP" -> when (random.nextInt(2)) {
            0 -> "R[a] = R[a] - R[a + 2]; pc = pc + b"
            else -> "local st = R[a + 2]; R[a] = R[a] - st; pc = pc + b"
        }
        "FORLOOP" -> when (random.nextInt(2)) {
            0 ->
                ("R[a + 3] = R[a] + R[a + 2]; " +
                    "if (R[a + 2] > 0 and R[a + 3] <= R[a + 1]) or (R[a + 2] <= 0 and R[a + 1] <= R[a + 3]) " +
                    "then R[a] = R[a + 3]; pc = pc + b end")
            else ->
                ("local nv = R[a] + R[a + 2]; R[a + 3] = nv; " +
                    "local cont = false; " +
                    "if R[a + 2] > 0 then cont = nv <= R[a + 1] else cont = R[a + 1] <= nv end; " +
                    "if cont then R[a] = nv; pc = pc + b end")
        }
        "FORPREP_COUNT" ->
            "local init, limit, step = R[a], R[a + 1], R[a + 2]; if step == 0 then error(\"vm: for step is zero\") end; " +
                "local d = step > 0 and (limit - init) or (init - limit); " +
                "if d < 0 then pc = pc + b else local count = math.floor(d / math.abs(step)); " +
                "if c == 2 then R[a], R[a + 1], R[a + 2] = count, step, init else R[a], R[a + 1], R[a + 2], R[a + 3] = init, count, step, init end end"
        "FORLOOP_COUNT" ->
            "if c == 2 then if R[a] > 0 then R[a] = R[a] - 1; R[a + 2] = R[a + 2] + R[a + 1]; pc = pc + b end " +
                "elseif R[a + 1] > 0 then R[a + 1] = R[a + 1] - 1; R[a] = R[a] + R[a + 2]; R[a + 3] = R[a]; pc = pc + b end"
        else -> error("no handler for $op")
    }

    private fun handlerBody(op: String, random: java.util.Random, run: String): String =
        handler(op, random).replace("RUN", run)

    // ------------------------------------------------------------------ dispatch skeletons (VM-2)

    /**
     * Emits the three way dispatch: chained `elseif` / table driven / nested binary splits.
     * All three resolve the *same* (op number -> body) mapping, only the lookup structure
     * differs, so the red team's "sorted dispatch lines md5" fingerprint has no fixed shape
     * to hash any more.
     *
     * Every skeleton signals the same result/close/tail event. The scope driver
     * owns native unwinding, so table handlers and inline handlers cannot diverge.
     */
    private fun dispatch(
        order: List<String>,
        random: java.util.Random,
        run: String,
        map: LuaVmProgram.OpcodeMap,
    ): DispatchShape = when (random.nextInt(3)) {
        // ---- skeleton A: shuffled if/elseif chain (legacy shape, re-randomised every emit)
        0 -> DispatchShape.Chain(
            order.mapIndexed { index, op ->
                val keyword = if (index == 0) "if" else "elseif"
                val opcodeNumber = map.encode(LuaVmProgram.Op.valueOf(op))
                "        $keyword op == $opcodeNumber then ${handlerBody(op, random, run)}"
            }.joinToString("\n"),
        )
        // ---- skeleton B: table driven; the physical assignment order is the random `order`
        1 -> {
            val assigns = order.joinToString("\n        ") { op ->
                val opcodeNumber = map.encode(LuaVmProgram.Op.valueOf(op))
                val body = handlerBody(op, random, run)
                "H[$opcodeNumber] = function(op, a, b, c, aSigned) $body end"
            }
            DispatchShape.Table(
                """
        local H = {}
        $assigns
        local h = H[op]
        if not h then error("vm: unknown opcode tag " .. tostring(op) .. " at pc " .. pc) end
        h(op, a, b, c, aSigned)

""".trimIndent(),
            )
        }
        // ---- skeleton C: nested binary splits (random tree shape). The split must partition
        // by opcode VALUE (the pivot is `max(left)`), so the list is sorted by number first;
        // the random split points keep the tree shape per chunk. A membership guard runs
        // first: unlike the chain (else error) and the table skeleton (H[op] nil check), a
        // binary tree silently routes an unknown number into *some* leaf, which executed a
        // wrong handler instead of failing loudly (B2 Round4, bit_ops shape).
        else -> {
            val sortedOps = order.sortedBy { map.encode(LuaVmProgram.Op.valueOf(it)) }
            val known = sortedOps.joinToString(" or ") { "op == ${map.encode(LuaVmProgram.Op.valueOf(it))}" }
            fun emit(ops: List<String>, indent: String): String {
                if (ops.size == 1) {
                    return "${indent}${handlerBody(ops[0], random, run)}"
                }
                val split = 1 + random.nextInt(ops.size - 1)
                val left = ops.subList(0, split)
                val right = ops.subList(split, ops.size)
                val pivot = left.maxOf { map.encode(LuaVmProgram.Op.valueOf(it)) }
                return buildString {
                    append(indent).append("if op <= $pivot then\n")
                    append(emit(left, indent + "    ")).append('\n')
                    append(indent).append("else\n")
                    append(emit(right, indent + "    ")).append('\n')
                    append(indent).append("end")
                }
            }
            DispatchShape.Skeleton("        if not ($known) then error(\"vm: unknown opcode tag \" .. tostring(op) .. \" at pc \" .. pc) end\n" + emit(sortedOps, "        "))
        }
    }

    /** A dispatch emission: chain shapes end in `else error(...) end`, skeletons are complete. */
    private sealed class DispatchShape {
        class Chain(val text: String) : DispatchShape()
        class Table(val text: String) : DispatchShape()
        class Skeleton(val text: String) : DispatchShape()
    }

    /**
     * Production entry point (B2 round 1): returns the interpreter **head** (module table,
     * generated decryptor, tagged pool decoder) and the **tail** (`return $moduleTable`), so
     * [LuaVmObfuscation] can splice its driver in between exactly as before.
     *
     * The emitted decryptor:
     *  * pulls `mul` / `add` from the encrypted header (never a literal),
     *  * decrypts the body with the header's re-mixed seed,
     *  * whitens every word with a `pc`-coupled mask (the decoder depends on the program
     *    counter, so it cannot be cut out and replayed standalone),
     *  * verifies the header magic so a wrong runtime seed fails loudly instead of silently
     *    executing garbage (issue VM-1 A5).
     */
    data class Parts(val head: String, val tail: String, val names: Names)

    fun generateModuleParts(random: java.util.Random, map: LuaVmProgram.OpcodeMap, cipher: LuaVmProgram.Cipher,
        version: io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion = io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion.LUA_5_3,
    ): Parts {
        val names = drawNames(random)
        val order = HANDLERS.shuffled(random)
        val m = names.moduleTable
        val n = names
        val run = n.runName
        // every constant in the emitted seedState / magic check is chunk private: the red
        // team's "sorted fingerprint blocks" no longer share a single byte across modules
        val magicLiteral = names.magicValue.toLong() and 0xFFFFFFFFL
        val tagN = LuaVmProgram.ConstTag

        // xor loop over the header words 2..6 (mirrors LuaVmObfuscation.headerCrypt exactly:
        // the initial state is the plain seedState(seed) and the LCG advances before each word)
        val headerXor = """
    local state = ${n.stateName}(seed)
    local hh = {}
    -- header layout: slot2 = magic, slot3 = mul low, slot4 = mul high, slot5 = add, slot6 = mix
    -- the header itself rides a per chunk secondary LCG (the real mul/add travel inside it)
    for i = 2, 6 do
        state = (state * ${n.headerMul} + 1) % LCG_MOD
        local w = words[i]
        local mask = 1
        local flipped = 0
        for bit = 0, 31 do
            local want = math.floor(state / mask) % 2
            local have = math.floor(w / mask) % 2
            if want ~= have then flipped = flipped + mask end
            mask = mask * 2
        end
        if flipped >= 2147483648 then flipped = flipped - LCG_MOD end
        hh[i - 1] = flipped
    end
""".trimIndent()

        // every phase of the pool decryption restarts from the plain mixed seed; the domain
        // separators (1000 upper-half ints / 2000 mixed word / 3000 program constants) are
        // emitted as disguised integer expressions so neither the salt nor the textbook
        // `7919` literal shows up; the numbers themselves must match LuaVmObfuscation exactly.
        fun saltExprFor(value: Int): String {
            val style = random.nextInt(2)
            val a = random.nextInt(value.coerceAtLeast(2))
            return when (style) {
                0 -> "($a + ${value - a})"
                else -> "(${value / 2} * 2 + ${value - (value / 2) * 2})"
            }
        }
        val saltC = saltExprFor(3000)
        val decryptor = """
local LCG_MOD = 0x100000000

local function ${n.stateName}(seed)
    local mixed = (seed * ${cipher.mixM} + ${cipher.mixA}) % LCG_MOD
    if mixed == 0 then mixed = ${cipher.fallback} end
    return mixed
end

local ${n.mulName}, ${n.addName}, ${n.decryptConstantsName} = 1, 0, nil

local function ${n.decryptName}(words, seed)
$headerXor
    -- normalise to unsigned before the check: the bit loop yields a signed 32 bit value
    if hh[1] % 0x100000000 ~= $magicLiteral then error("vm: integrity check failed") end
    ${n.mulName} = hh[2] + hh[3] * 0x100000000; ${n.addName} = hh[4]
    -- phase 2: the body words decrypt with the re-mixed stream
    -- (seedState(seed) + mix, mix = hh[5] as assembled by the emitter)
    local state = (${n.stateName}(seed) + hh[5]) % LCG_MOD
    local out = {}
    for i = 7, #words do
        state = (state * ${n.mulName} + ${n.addName}) % LCG_MOD
        local w = words[i]
        local mask = 1
        local flipped = 0
        for bit = 0, 31 do
            local want = math.floor(state / mask) % 2
            local have = math.floor(w / mask) % 2
            if want ~= have then flipped = flipped + mask end
            mask = mask * 2
        end
        if flipped >= 2147483648 then flipped = flipped - LCG_MOD end
        local wi = i - 6
        local wmask = (wi * ${n.whiteRot} + hh[1]) % 0x100000000
        if wmask >= 2147483648 then wmask = wmask - 0x100000000 end
        flipped = flipped ~ wmask
        out[wi] = flipped
    end
    return out
end

${n.decryptConstantsName} = function(k, count, seed)
    -- decodes count entries STARTING at index 0; entries not present stay nil. The emitter
    -- calls it twice: the seed-carrying tail slots with the literal lower seed, and the
    -- program's own pool (per program) with the runtime derived seed.
    local out = {}
    for i = 0, count - 1 do
        local v = k[i]
        if type(v) == "table" then
            local state = (${n.stateName}(seed) + $saltC) % LCG_MOD
            local bytes = {}
            for j = 1, #v do
                state = (state * ${n.mulName} + ${n.addName}) % LCG_MOD
                bytes[j] = string.char((v[j] ~ (state % 256)) % 256)
            end
            local raw = table.concat(bytes)
            local tag = string.byte(raw, 1)
            if tag == ${tagN.STRING} then
                out[i] = raw:sub(2)
            elseif tag == ${tagN.INTEGER} then
                out[i] = (string.unpack("<i8", raw, 2))
            elseif tag == ${tagN.FLOAT} then
                out[i] = (string.unpack("<d", raw, 2))
            elseif tag == ${tagN.BOOLEAN} then
                out[i] = string.byte(raw, 2) ~= 0
            else
                out[i] = nil
            end
        else
            out[i] = v
        end
    end
    return out
end
""".trimIndent()

        // Each virtual TBC owns a real Lua close scope. The host stack is also the
        // continuation for yields in calls, metamethods, and normal __close execution.
        val scopeDeclaration = if (version == io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion.LUA_5_3)
            "local held = value" else "local held <close> = value"
        val runPre = """
    local function $run(prog, map, entry, args, maxSteps, programs, constants, ctrlOffset, captures, baseIndex, paramCounts, childIds, frameInfo)
    baseIndex = baseIndex or 1
    args = args or {}
    args.varargs = args.varargs or { n = 0 }
    ctrlOffset = ctrlOffset or 3
    local cells, values = {}, {}
    local sourceStack = frameInfo[baseIndex][1]
    local function sourceRead(index)
        local cell = cells[index]
        if cell then return cell.value end
        return values[index]
    end
    local function sourceWrite(index, value)
        local cell = cells[index]
        if cell then cell.value = value else values[index] = value end
    end
    local function registerKey(index)
        if index >= sourceStack then return -index - 1 end
        return index
    end
    local R = setmetatable({}, {
        __index = function(_, index) return sourceRead(registerKey(index)) end,
        __newindex = function(_, index, value) sourceWrite(registerKey(index), value) end
    })
    local np = paramCounts[baseIndex]
    for i = 1, np do sourceWrite(i - 1, args[i]) end
    if frameInfo[baseIndex][2] then sourceWrite(np, args.varargs) end
    local function closeCells(first)
        for index, cell in pairs(cells) do
            if index >= first then values[index] = cell.value; cells[index] = nil end
        end
    end
    local pc = entry or 1
    local steps = 0
    local dispatchResult, tailRequest, closeAt, tbcRegister
    local vtop = np
    local function arguments(a, b)
        local count = b == 255 and (vtop - a - 1) or b
        local out = { n = count }
        for i = 1, count do
            if a < sourceStack then out[i] = sourceRead(a + i) else out[i] = R[a + i] end
        end
        return out
    end
    local function results(a, c)
        local count = c == 0 and (vtop - a) or (c == 255 and 0 or c)
        local out = { n = count }
        for i = 1, count do out[i] = sourceRead(a + i - 1) end
        return out
    end
    local function execute()
    while true do
        steps = steps + 1
        if maxSteps and steps > maxSteps then error("vm: step limit") end
        if pc < 1 or pc > #prog then
            error("vm: pc " .. pc .. " is out of range (program has " .. #prog .. " words)")
        end
        local op, a, b, c, aSigned = decode(prog[pc])
        pc = pc + 1
"""
        val shape = dispatch(order, random, run, map)
        val runFn = runPre + when (shape) {
            is DispatchShape.Chain -> shape.text + "\n        else error(\"vm: unknown opcode tag \" .. tostring(op) .. \" at pc \" .. pc) end"
            is DispatchShape.Table -> shape.text
            is DispatchShape.Skeleton -> shape.text
        } + """

        if dispatchResult or tailRequest or closeAt ~= nil or tbcRegister ~= nil then return end
    end
    end
    local drive
    local function scope(value, reg)
        $scopeDeclaration
        drive(reg)
    end
    drive = function(level)
        while true do
            if dispatchResult or tailRequest then return end
            if closeAt ~= nil then
                if level >= closeAt then return end
                closeAt = nil
            end
            execute()
            if tbcRegister ~= nil then
                local reg = tbcRegister
                tbcRegister = nil
                scope(R[reg], reg)
            end
        end
    end
    drive(-1)
    if tailRequest then return tailRequest.f(table.unpack(tailRequest.argv, 1, tailRequest.argv.n)) end
    return table.unpack(dispatchResult, 1, dispatchResult.n)
end

$m.$run = $run
""".trimIndent()

        val tail = "\nreturn $m\n"
        val decodeFn = """
local function decode(word)
    local op = math.floor(word / 16777216) % 256
    local a = math.floor(word / 65536) % 256
    local b = math.floor(word / 256) % 256
    local c = word % 256
    local aSigned = a >= 128 and a - 256 or a
    if ${listOf("LOADI", "JMPIF", "JMPIFNOT", "FORPREP", "FORLOOP", "FORPREP_COUNT", "FORLOOP_COUNT").joinToString(" or ") { "op == " + map.encode(LuaVmProgram.Op.valueOf(it)) }} then
        if b >= 128 then b = b - 256 end
    end
    return op, a, b, c, aSigned
end
""".trimIndent()
        val head = "-- generated by LuaVmInterpreterGenerator (P4.3, B2 round 1): per chunk\n" +
            "-- interpreter; dispatch skeleton, handler shapes, cipher parameters and identifier\n" +
            "-- names differ per chunk while the semantics are identical.\n" +
            "local $m = {}\n\n" + """
local type, pairs, ipairs, rawget, rawset, setmetatable, error, tostring = type, pairs, ipairs, rawget, rawset, setmetatable, error, tostring
local table = { pack = table.pack, unpack = table.unpack, concat = table.concat }
local math = { floor = math.floor, abs = math.abs, max = math.max, type = math.type }
local string = { char = string.char, byte = string.byte, unpack = string.unpack }
""" + decryptor + "\n" + decodeFn + "\n" + runFn + "\n"
        return Parts(head = head, tail = tail, names = names)
    }

    /** Runtime seed derivation injected into the module (upper half secret). */
    fun seedDerivationSource(n: Names, kTable: String, kUpper1: Int, kUpper2: Int, kUpper3: Int): String = """
local $n.seedName = ((#tostring(_VERSION) * ($kTable.k[$kUpper1]) + ($kTable.k[$kUpper2])) ~ ($kTable.k[$kUpper3]))
$n.seedName = $n.seedName % 0x100000000
""".trimIndent()
}
