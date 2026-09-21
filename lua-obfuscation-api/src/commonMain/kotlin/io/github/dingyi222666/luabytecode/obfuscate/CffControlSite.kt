package io.github.dingyi222666.luabytecode.obfuscate

/**
 * One verified control-packet entrance: the pc of the packet head and the
 * dispatcher target index it must reach.
 *
 * Promoted from the nested `CffControlPackets.Site` (roadmap step 4): the
 * jumps feature (`LuaJumpIndirection` runtime routes, `LuaMbaPredicates`) and
 * the CFF feature (`CffControlPackets`, `CffConstantExpansion`) both exchange
 * this record through [CffTranslationValidator.verify], and feature modules
 * must not depend on each other.
 */
data class CffControlSite(
    val pc: Int,
    val target: Int,
    val variableAtEntry: Boolean = false,
)
