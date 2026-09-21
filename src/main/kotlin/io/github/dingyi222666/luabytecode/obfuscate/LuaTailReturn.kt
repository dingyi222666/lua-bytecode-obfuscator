package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaOpcodes
import io.github.dingyi222666.luabytecode.bytecode.LuaString
import io.github.dingyi222666.luabytecode.bytecode.edit.appendInstruction
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaPrototypeScope

/**
 * Appends a syntactic `RETURN` to every prototype whose final instruction is not a
 * terminator, so the chunk satisfies the official invariant `luac` relies on ("all
 * prototypes end with a RETURN") and decompilers such as unluac stop rejecting the
 * whole chunk with `Function doesn't end with implicit return`.
 *
 * The appended instruction is **unreachable**: control only reaches it by falling off
 * the previous instruction, and the previous instruction is by definition not a fall
 * through terminator (`JMP` / `FORLOOP` / `TFORLOOP` / `FORPREP` / `TFORPREP` all
 * redirect control, every `RETURN*` / `TAILCALL` ends the prototype). Preserves the
 * semantics, repairs the syntax.
 */
object LuaTailReturn {

    /** Acceptable final instructions; everything else gets a synthetic `RETURN`. */
    private val RETURNS = setOf("RETURN", "RETURN0", "RETURN1", "TAILCALL")

    fun apply(chunk: LuaChunk): TailReturnReport {
        var patched = 0
        var injected = 0
        for (prototype in chunk.prototypes) {
            val last = prototype.code.lastOrNull() ?: continue
            if (last.opcodeName in RETURNS) continue
            // appendInstruction keeps every debug structure consistent: lineInfo gains
            // the entry, 5.4/5.5 absolute anchors are re-encoded (keeping the
            // MAXIWTHABS anchor density the dump invariant requires), local pc ranges
            // stay valid. The appended RETURN inherits the prototype's own line.
            LuaPrototypeScope(prototype).append(syntheticReturn(prototype))
            patched++
            injected++
        }
        // unluac de-references every upvalue *name* while decompiling table writes
        // (`TableReference.isUpvalueOf`, NPE on `upvalue.name == null`). When debug
        // info is stripped, emit a synthetic name per upvalue: bytecode semantics are
        // untouched (names are debug metadata), and the chunk becomes parseable again
        // instead of "protected" by a crash.
        nameUpvalues(chunk)
        return TailReturnReport(patched, injected)
    }

    /**
     * Fills empty `upvalueNames` lists with synthetic names. Public because the
     * obfuscation pipeline must re-run this AFTER `clearDebugInfo` (the strip clears
     * the names again).
     */
    fun nameUpvalues(chunk: LuaChunk) {
        for (prototype in chunk.prototypes) {
            if (prototype.upvalueNames.isNotEmpty() || prototype.upvalues.isEmpty()) continue
            repeat(prototype.upvalues.size) { index ->
                prototype.upvalueNames += LuaString.of("(upvalue_$index)")
            }
        }
    }

    internal fun syntheticReturn(prototype: LuaPrototype): LuaInstruction {
        val version = prototype.version
        // 5.3: `RETURN A=0 B=1 C=0` returns no values; 5.4 / 5.5: `RETURN0`.
        // A=0 B=1 is exactly what `luac` emits for `return` at the end of a chunk.
        return if (version == LuaBytecodeVersion.LUA_5_3) {
            LuaPrototypeScope(prototype).abc("RETURN", 0, 1)
        } else {
            LuaPrototypeScope(prototype).abc("RETURN0")
        }
    }
}
