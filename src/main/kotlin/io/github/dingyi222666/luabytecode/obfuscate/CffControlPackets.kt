package io.github.dingyi222666.luabytecode.obfuscate
import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaInstructionFactory

import io.github.dingyi222666.luabytecode.bytecode.*
import io.github.dingyi222666.luabytecode.bytecode.edit.internConstant

/** All control values are short lived: no original CALL is crossed. */
internal class CffControlPackets(
    private val prototype: LuaPrototype,
    private val config: ControlFlowFlatteningConfig,
    private val base: Int,
    private val blockCount: Int,
    private val builder: LuaControlFlowFlattening.CodeBuilder,
    private val labels: LuaControlFlowFlattening.LabelSupply,
    private val emit: (LuaInstruction) -> Unit,
    private val line: () -> Unit,
    private val random: ObfRandom,
) {
    data class Packet(val namespace: Int, val local: Int, val target: Int)
    data class Site(val pc: Int, val target: Int, val variableAtEntry: Boolean = false)
    val packets = ArrayList<Packet>()
    val sites = ArrayList<Site>()
    private var pendingSite: Site? = null
    val features = linkedSetOf("baseline")
    private val version = prototype.version
    private val state = base
    private val namespace = base + 1
    private val key = base + 2
    private val probe = base + 3
    private val temp = base + 4
    private val multiplier = base + 5
    private val cursor = base + 6
    private val table = base + 7
    private val alias = base + 8
    private val index = base + 9
    private val other = base + 10
    private val runtime = config.stateRuntime && prototype.numParams > 0
    private val multiple = config.multipleDispatch && runtime
    private val routes = List(if (multiple) 2 else 1) { labels.next() }
    private val tableMode = config.stateTable && config.allocationEffectsUnobservable
    private fun powerOfTwo(n: Int) = Integer.highestOneBit((n - 1).coerceAtLeast(1)) shl 1
    private val namespaceDomain = powerOfTwo(blockCount + 2)
    private val localDomain = if (config.stateNamespaces) 4 else powerOfTwo(blockCount)

    private fun op(name: String) = LuaArithmetic.opcode(version, name)
    private fun abc(name: String, a: Int, b: Int = 0, c: Int = 0, k: Int = 0) =
        emit(LuaInstructionFactory.abc(version, op(name), a, b, c, k))
    private fun jump(label: Int) { builder.emitJumpTo(label); line() }
    private fun place(label: Int) = builder.place(label)
    private fun arithmetic(name: String, out: Int, a: Int, b: Int) =
        LuaArithmetic.pair(prototype, name, out, a, b).forEach(emit)

    /** No per-case constant pool map: only public radix digits and arithmetic parameters. */
    private fun number(register: Int, value: Int) {
        require(value >= 0)
        val digits = value.toString(16).map { it.digitToInt(16) }
        fun load(r: Int, n: Int) = emit(LuaInstructionFactory.abx(version, op("LOADK"), r,
            prototype.internConstant(LuaConstant.Integer(n.toLong()))))
        load(register, digits.first())
        for (digit in digits.drop(1)) {
            load(temp, 16)
            arithmetic("MUL", register, register, temp)
            if (digit != 0) { load(temp, digit); arithmetic("ADD", register, register, temp) }
        }
    }

    private fun compare(a: Int, b: Int, yes: Int) {
        if (version == LuaBytecodeVersion.LUA_5_3) abc("EQ", 1, a, b)
        else abc("EQ", a, b, 0, 1)
        jump(yes)
    }

    private fun truth(register: Int, yes: Int) {
        if (version == LuaBytecodeVersion.LUA_5_3) abc("TEST", register, 0, 1)
        else abc("TEST", register, 0, 0, 1)
        jump(yes)
    }

    private fun projectKey() {
        if (!runtime) { number(key, 0); return }
        val yes = labels.next()
        val done = labels.next()
        truth(0, yes)
        number(key, 0)
        jump(done)
        place(yes)
        number(key, 1)
        place(done)
        features += "state.runtime"
    }

    /** Odd affine multipliers; namespace and local token have different domains. */
    private fun encode(register: Int, value: Int, ns: Boolean, salt: Int) {
        val domain = if (ns) namespaceDomain else localDomain
        if (!runtime) {
            number(register, (value * (if (ns) 3 else 1) + salt) and (domain - 1))
            return
        }
        number(multiplier, 2)
        arithmetic("MUL", multiplier, multiplier, key)
        number(temp, if (ns) 3 else 1)
        arithmetic("ADD", multiplier, multiplier, temp)
        number(register, value)
        arithmetic("MUL", register, register, multiplier)
        number(multiplier, if (ns) 7 else 5)
        arithmetic("MUL", multiplier, multiplier, key)
        arithmetic("ADD", register, register, multiplier)
        number(multiplier, salt)
        arithmetic("ADD", register, register, multiplier)
        number(multiplier, domain - 1)
        arithmetic("BAND", register, register, multiplier)
    }

    fun state(target: Int, source: Int) {
        val ns = if (config.stateNamespaces) source + 1 else 0
        val existing = packets.firstOrNull { it.namespace == ns && it.target == target }
        val packet = existing ?: Packet(ns, packets.count { it.namespace == ns }, target).also { packets += it }
        require(packet.namespace < namespaceDomain && packet.local < localDomain)
        pendingSite = Site(builder.size, target, runtime && source == blockCount)
        projectKey()
        encode(state, packet.local, false, ns)
        encode(namespace, ns, true, 11)
        number(cursor, 0)
        if (config.stateNamespaces) features += "state.namespaces"
        if (tableMode) {
            // The table and its aliases exist only in this control capsule, with
            // no calls/escape. Allocation observation requires an explicit contract.
            abc("NEWTABLE", table)
            if (version != LuaBytecodeVersion.LUA_5_3) emit(LuaInstructionFactory.ax(version, op("EXTRAARG"), 0))
            abc("MOVE", alias, table)
            number(index, 1)
            arithmetic("ADD", index, index, key)
            abc("SETTABLE", table, index, state)
            number(other, 3)
            arithmetic("ADD", other, other, key)
            abc("SETTABLE", alias, other, namespace)
            abc("LOADNIL", state, 1)
            // Both aliases are reachable and reads are required by the dispatch.
            val second = labels.next()
            val loaded = labels.next()
            number(probe, 1)
            compare(key, probe, second)
            abc("GETTABLE", state, alias, index)
            abc("GETTABLE", namespace, table, other)
            jump(loaded)
            place(second)
            abc("GETTABLE", state, table, index)
            abc("GETTABLE", namespace, alias, other)
            place(loaded)
            // Release roots before executing the original program.
            abc("LOADNIL", table, 1)
            features += "state.table"
        }
    }

    fun dispatchJump() {
        pendingSite?.let { sites += it }
        pendingSite = null
        if (multiple) {
            number(probe, 1)
            compare(key, probe, routes[1])
        }
        jump(routes[0])
    }

    private fun update(amount: Int) {
        number(probe, amount)
        arithmetic("ADD", state, state, probe)
        arithmetic("ADD", cursor, cursor, probe)
        features += "dispatch.effects"
    }

    /**
     * Typed templates. x is the truth projection, so x is exactly 0 or 1.
     * The validator executes each instantiated instruction sequence for both x.
     * These families are cost/experimental features, not resilience claims.
     */
    private fun predicate(family: CffPredicateFamily) {
        when (family) {
            CffPredicateFamily.NON_SQUARE -> {
                // 7*x*x - 1 is 7 or 6 mod 8; no square has either residue.
                arithmetic("MUL", probe, key, key)
                number(other, 7)
                arithmetic("MUL", probe, probe, other)
                number(other, 1)
                arithmetic("SUB", probe, probe, other)
                number(other, 7)
                arithmetic("BAND", probe, probe, other)
                arithmetic("MUL", other, key, key)
                val unequal = labels.next()
                if (version == LuaBytecodeVersion.LUA_5_3) abc("EQ", 0, probe, other)
                else abc("EQ", probe, other, 0, 0)
                jump(unequal)
                number(probe, 1)
                arithmetic("ADD", state, state, probe)
                place(unequal)
                features += "predicate.non_square"
                return
            }
            CffPredicateFamily.PRIVATE_STRUCTURE -> {
                if (!config.allocationEffectsUnobservable) return
                abc("NEWTABLE", table)
                if (version != LuaBytecodeVersion.LUA_5_3) emit(LuaInstructionFactory.ax(version, op("EXTRAARG"), 0))
                abc("MOVE", alias, table)
                number(index, 1)
                arithmetic("ADD", index, key, index)
                number(probe, 3)
                arithmetic("ADD", probe, probe, key)
                abc("SETTABLE", table, index, probe)
                abc("GETTABLE", other, alias, index)
                abc("LOADNIL", table, 1)
            }
            CffPredicateFamily.GRAPH -> {
                if (!config.allocationEffectsUnobservable) return
                // A triangle witness, represented by unordered pair keys. Swapping
                // vertices 0 and 1 at runtime permutes the two incident edge keys.
                // This is a finite candidate, with no NP-hardness/security claim.
                abc("NEWTABLE", table)
                if (version != LuaBytecodeVersion.LUA_5_3) emit(LuaInstructionFactory.ax(version, op("EXTRAARG"), 0))
                number(probe, 1)
                for (edge in listOf(1, 2, 6)) {
                    number(index, edge)
                    abc("SETTABLE", table, index, probe)
                }
                number(index, 4)
                arithmetic("MUL", index, key, index)
                number(probe, 2)
                arithmetic("ADD", index, index, probe)
                abc("GETTABLE", probe, table, index)
                number(index, 1)
                abc("GETTABLE", other, table, index)
                arithmetic("MUL", probe, probe, other)
                number(index, 1)
                arithmetic("SUB", index, index, key)
                number(other, 4)
                arithmetic("MUL", index, index, other)
                number(other, 2)
                arithmetic("ADD", index, index, other)
                abc("GETTABLE", other, table, index)
                arithmetic("MUL", probe, probe, other)
                number(other, 1)
                abc("LOADNIL", table, 1)
            }
        }
        val valid = labels.next()
        compare(probe, other, valid)
        // A broken template changes the control package and is rejected by
        // translation validation, rather than being hidden by two identical arms.
        number(probe, 1)
        arithmetic("ADD", state, state, probe)
        place(valid)
        features += "predicate.${family.name.lowercase()}"
    }

    private fun localProbe(packet: Packet) {
        encode(probe, packet.local, false, packet.namespace)
        if (config.dispatchEffects) arithmetic("ADD", probe, probe, cursor)
    }

    private fun gateway(target: Int, destination: Int) {
        if (!config.indegreeGuards || !runtime) { jump(destination); return }
        val second = labels.next()
        val normalized = labels.next()
        val expected = base + 11
        abc("MOVE", expected, state)
        arithmetic("SUB", expected, expected, cursor)
        number(probe, 1)
        compare(key, probe, second)
        // Feasible alternatives normalize two different private state updates.
        number(probe, target + 1)
        arithmetic("ADD", state, state, probe)
        number(other, target + 1)
        arithmetic("ADD", other, other, cursor)
        arithmetic("SUB", state, state, other)
        jump(normalized)
        place(second)
        number(probe, target + 2)
        arithmetic("BXOR", state, state, probe)
        arithmetic("BXOR", state, state, probe)
        arithmetic("SUB", state, state, cursor)
        place(normalized)
        compare(state, expected, destination)
        if (version == LuaBytecodeVersion.LUA_5_3) abc("RETURN", 0, 1) else abc("RETURN0", 0)
        features += "indegree"
    }

    fun dispatch(targetLabel: (Int) -> Int, terminal: () -> Unit) {
        for ((route, entry) in routes.withIndex()) {
            place(entry)
            val gateways = packets.map { it.target }.distinct().associateWith { labels.next() }
            var tested = 0
            fun test(packet: Packet, namespaceChecked: Boolean) {
                if (config.segmentedDispatch && tested > 0 && tested % config.segmentSize == 0) {
                    val segment = labels.next()
                    jump(segment)
                    place(segment)
                    if (config.dispatchEffects) update(route + tested + 1)
                    features += "dispatch.segmented"
                }
                val miss = labels.next()
                if (!namespaceChecked && config.stateNamespaces) {
                    val local = labels.next()
                    encode(probe, packet.namespace, true, 11)
                    compare(namespace, probe, local)
                    jump(miss)
                    place(local)
                }
                localProbe(packet)
                compare(state, probe, gateways.getValue(packet.target))
                place(miss)
                tested++
            }
            if (config.dispatchEffects) update(route + 1)
            config.predicateFamilies.forEach(::predicate)
            if (config.nestedDispatch && config.stateNamespaces) {
                for ((ns, group) in packets.groupBy { it.namespace }.entries.shuffled(random)) {
                    val local = labels.next()
                    val next = labels.next()
                    encode(probe, ns, true, 11)
                    compare(namespace, probe, local)
                    jump(next)
                    place(local)
                    group.shuffled(random).forEach { test(it, true) }
                    terminal() // rejected by validation if reachable from a valid packet
                    place(next)
                }
                features += "dispatch.nested"
            } else packets.shuffled(random).forEach { test(it, false) }
            terminal()
            for ((target, label) in gateways) {
                place(label)
                gateway(target, targetLabel(target))
            }
        }
        if (multiple) features += "dispatch.multiple"
    }
}
