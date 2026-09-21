// Internal hex helpers of the model module. The two `"%02X".format(..)` call
// sites they replace used to resolve to `java.lang.String.format` on the JVM —
// a JVM-only API that the js target (roadmap step 6) cannot resolve, so the
// patterns the model needs are implemented on the common stdlib here. The
// model must stay dependency free (platform.Hex lives ABOVE the model in the
// module graph), hence the small duplication with :lua-platform.
package io.github.dingyi222666.luabytecode.bytecode

private const val HEX_DIGITS_UPPER = "0123456789ABCDEF"

/** `"%02X".format(value)` for a chunk version byte: uppercase two digit hex of the low byte. */
internal fun versionByteHex(value: Int): String {
    val v = value and 0xFF
    val chars = CharArray(2)
    chars[0] = HEX_DIGITS_UPPER[v shr 4]
    chars[1] = HEX_DIGITS_UPPER[v and 0xF]
    return chars.concatToString()
}

/**
 * `"%08X".format(value)`: uppercase zero padded hex of the unsigned 32 bit
 * pattern of [value] (exactly 8 characters for every Int, like the JVM API).
 */
internal fun instructionHex(value: Int): String {
    val chars = CharArray(8)
    var v = value
    for (i in 7 downTo 0) {
        chars[i] = HEX_DIGITS_UPPER[(v and 0xF).toInt()]
        v = v ushr 4
    }
    return chars.concatToString()
}
