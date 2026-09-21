package io.github.dingyi222666.luabytecode.platform

/**
 * Pure common hex formatting helpers — replacements for the JVM-flavoured
 * `"%02x".format(..)` / `"%08X".format(..)` / `Long.toHexString(..)` call
 * sites in the bytecode / obfuscate / vm packages. Values are always treated
 * as **unsigned bit patterns**, matching `java.util.Formatter`'s `%x` /
 * `Long.toHexString` semantics (the value is never rendered with a sign).
 */
private const val HEX_DIGITS_LOWER = "0123456789abcdef"
private const val HEX_DIGITS_UPPER = "0123456789ABCDEF"

/** Lowercase two digit hex of [value]'s low byte — `"%02x".format(value)`. */
fun hex2(value: Int): String {
    val v = value and 0xFF
    val chars = CharArray(2)
    chars[0] = HEX_DIGITS_LOWER[v shr 4]
    chars[1] = HEX_DIGITS_LOWER[v and 0xF]
    return chars.concatToString()
}

/** Uppercase two digit hex of [value]'s low byte — `"%02X".format(value)`. */
fun hex2Upper(value: Int): String {
    val v = value and 0xFF
    val chars = CharArray(2)
    chars[0] = HEX_DIGITS_UPPER[v shr 4]
    chars[1] = HEX_DIGITS_UPPER[v and 0xF]
    return chars.concatToString()
}

/**
 * Uppercase natural width hex of the unsigned 64 bit pattern of [value] —
 * `"%X".format(value)` / `Long.toHexString(value).uppercase()`. `0L` renders
 * as `"0"`; no zero padding is applied.
 */
fun hexUpper(value: Long): String {
    var v = value
    if (v == 0L) return "0"
    val chars = CharArray(16)
    var i = chars.size
    while (v != 0L) {
        chars[--i] = HEX_DIGITS_UPPER[(v and 0xFL).toInt()]
        v = v ushr 4
    }
    return chars.concatToString(i, chars.size)
}

/**
 * Lowercase hex of the unsigned 64 bit pattern of [value], zero padded to
 * exactly [digits] characters (truncated from the left when the pattern needs
 * more, like `"%0${digits}x".format`). `[digits]` is clamped to `1..16`.
 */
fun hexPadded(value: Long, digits: Int): String {
    val width = digits.coerceIn(1, 16)
    var v = value
    val chars = CharArray(width)
    for (i in width - 1 downTo 0) {
        chars[i] = HEX_DIGITS_LOWER[(v and 0xFL).toInt()]
        v = v ushr 4
    }
    return chars.concatToString()
}
