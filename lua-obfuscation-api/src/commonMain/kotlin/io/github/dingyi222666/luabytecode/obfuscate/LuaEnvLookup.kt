// `_ENV` upvalue resolution and constant-index-safe environment lookups.
//
// Promoted from LuaControlFlowNoise.kt to :lua-obfuscation-api (roadmap step
// 4): the jumps feature (LuaJunkCode) and the cross-cutting LuaHostSeed (root
// module, consumed by the future VM layer) both emit environment reads, and
// feature modules must not be a dependency boundary for shared primitives.
package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaInstructionFactory
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.platform.IdentityMap
import io.github.dingyi222666.luabytecode.platform.Platform

/** `BITRK` masks the constant index of every `RK` operand to 8 bits. */
private const val MAX_RK_CONSTANT_INDEX = 0xFF

/** Name of the upvalue that holds the global table. */
private const val ENV_UPVALUE_NAME = "_ENV"

/**
 * Emits `destination = env[constantIndex]`.
 *
 * `GETTABUP` addresses its key with an `RK` operand, and that operand carries
 * only 8 bits of constant index in every supported version: 5.3 packs the
 * `BITRK` flag into the 9 bit `C` field, so an index above 255 silently
 * decodes as `index and 0xFF` and reads a *different* constant (this is the
 * long standing `big_53` miscompilation), while 5.4 / 5.5 use an 8 bit
 * constant operand that cannot represent it at all. Larger indices are
 * therefore staged in a register and read through `GETTABLE`, whose key
 * operand accepts a register.
 *
 * The caller has to reserve [scratch] .. [scratch] + 3.
 */
fun appendEnvLookup(
    setup: MutableList<LuaInstruction>,
    version: LuaBytecodeVersion,
    destination: Int,
    scratch: Int,
    envUpvalue: Int,
    constantIndex: Int,
) {
    if (constantIndex <= MAX_RK_CONSTANT_INDEX) {
        setup += getTabUp(version, destination, envUpvalue, constantIndex)
    } else {
        setup += LuaInstructionFactory.abc(version, "GETUPVAL", scratch + 2, envUpvalue, 0)
        setup += LuaInstructionFactory.abx(version, "LOADK", scratch + 3, constantIndex)
        setup += LuaInstructionFactory.abc(version, "GETTABLE", destination, scratch + 2, scratch + 3)
    }
}

/** `GETTABUP A, env, K[name]` (5.3 encodes the key operand with `BITRK`). */
private fun getTabUp(
    version: LuaBytecodeVersion,
    destination: Int,
    envUpvalue: Int,
    constantIndex: Int,
): LuaInstruction {
    require(constantIndex <= MAX_RK_CONSTANT_INDEX) {
        "constant index $constantIndex does not fit the key operand of GETTABUP in ${version.displayName}; " +
            "use appendEnvLookup, which stages it in a register"
    }
    return LuaInstructionFactory.abc(
        version, "GETTABUP",
        destination,
        envUpvalue,
        if (version == LuaBytecodeVersion.LUA_5_3) rk53(constantIndex) else constantIndex,
    )
}

/** 5.3 encodes constant operands with `BITRK` set. */
private fun rk53(constantIndex: Int): Int = constantIndex or (1 shl 8)

/**
 * Resolves the `_ENV` upvalue index of every prototype of [chunk].
 *
 * The opcodes alone are **not** enough to recognize `_ENV`: on 5.3 any access to
 * an upvalue table compiles to `GETTABUP` (`t[k]` as well as a global `g`) and
 * any assignment to one compiles to `SETTABUP` (`store[i] = v`), so the first
 * such instruction may well reference a local table. `_ENV` is resolved through
 * the closure chain instead:
 *
 * * `lua_load` installs the global table in the first upvalue of the main chunk;
 * * a nested function that reaches globals captures `_ENV` by forwarding the
 *   parent's `_ENV` upvalue, i.e. `inStack == false && index == parentEnv`.
 *
 * A debug name is authoritative when present, because it also covers a source
 * level `local _ENV = ...` shadow. Prototypes whose `_ENV` cannot be proven to
 * hold the globals are absent from the result; callers fall back to a constant
 * seed instead of emitting code that indexes an arbitrary table.
 */
internal fun resolveEnvUpvalues(chunk: LuaChunk): IdentityMap<LuaPrototype, Int> {
    val result = IdentityMap<LuaPrototype, Int>()

    fun visit(prototype: LuaPrototype, parentEnv: Int?) {
        val named = prototype.upvalueNames.indexOfFirst { it?.utf8 == ENV_UPVALUE_NAME }
        val env = when {
            named >= 0 -> named
            prototype === chunk.mainPrototype -> if (prototype.upvalues.isEmpty()) null else 0
            parentEnv == null -> null
            else -> prototype.upvalues
                .indexOfFirst { !it.inStack && it.index == parentEnv }
                .takeIf { it >= 0 }
        }
        if (Platform.debug.enabled("JUNK_DEBUG")) {
            Platform.debug.log(
                "[env] proto=${prototype.source}:${prototype.lineDefined} " +
                    "upvalues=${prototype.upvalues.map { "inStack=${it.inStack},index=${it.index}" }} " +
                    "names=${prototype.upvalueNames.map { it?.utf8 }} named=$named parentEnv=$parentEnv -> env=$env",
            )
        }
        if (env != null && env < prototype.upvalues.size) {
            result[prototype] = env
        }
        for (child in prototype.nestedPrototypes) {
            visit(child, env)
        }
    }

    visit(chunk.mainPrototype, null)
    return result
}
