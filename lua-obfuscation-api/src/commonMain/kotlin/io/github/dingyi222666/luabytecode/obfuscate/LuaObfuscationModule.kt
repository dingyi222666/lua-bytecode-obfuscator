package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaChunkEditor
import io.github.dingyi222666.luabytecode.bytecode.edit.editing

/** Stable insertion points for third-party obfuscation modules. */
enum class ObfuscationPhase {
    BEFORE_CORE,
    AFTER_CORE,
}

/** A small, composable extension point for chunk transformations. */
interface LuaObfuscationModule {
    val id: String
    val phase: ObfuscationPhase
        get() = ObfuscationPhase.BEFORE_CORE

    fun apply(context: LuaObfuscationModuleContext): LuaObfuscationModuleResult
}

/** Mutable state shared by modules in one obfuscation run. */
class LuaObfuscationModuleContext(
    val chunk: LuaChunk,
    val config: ObfuscationConfig,
    val masterSeed: Long,
) {
    /** Fluent bytecode editor backed by the live chunk. */
    fun edit(): LuaChunkEditor = chunk.editing()

    /** Module-local state; keys should be namespaced by the module id. */
    val attributes: MutableMap<String, Any?> = LinkedHashMap()
}

data class LuaObfuscationModuleResult(
    val id: String,
    val changed: Boolean = true,
    val details: String? = null,
)

/** Convenient adapter for Kotlin callers that only need a lambda. */
class LambdaObfuscationModule(
    override val id: String,
    override val phase: ObfuscationPhase = ObfuscationPhase.BEFORE_CORE,
    private val action: (LuaObfuscationModuleContext) -> LuaObfuscationModuleResult = {
        LuaObfuscationModuleResult(id)
    },
) : LuaObfuscationModule {
    override fun apply(context: LuaObfuscationModuleContext): LuaObfuscationModuleResult = action(context)
}
