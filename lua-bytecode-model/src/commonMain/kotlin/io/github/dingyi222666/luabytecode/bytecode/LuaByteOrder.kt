package io.github.dingyi222666.luabytecode.bytecode

/**
 * Endianness of a Lua binary chunk — the model-side replacement of
 * `java.nio.ByteOrder` (which is JVM-only and blocked [LuaChunkHeader] from
 * the common `:lua-bytecode-model` module; see `docs/roadmap-kmp-progress.md`).
 *
 * Lua chunks are little endian for the reference x64 builds; the loader probes
 * `LUAC_INT` and flips the reader to [BIG] when the probe reads byte swapped,
 * so foreign-endianness chunks round trip too.
 */
enum class LuaByteOrder(val displayName: String) {

    /** Big endian (`network` order) — only seen in foreign-host dumps. */
    BIG("big-endian"),

    /** Little endian — what the reference `luac` emits on x86-64 / arm64. */
    LITTLE("little-endian"),
    ;

    /** The opposite byte order (used by the loader's probe retry). */
    val flipped: LuaByteOrder
        get() = if (this == BIG) LITTLE else BIG

    /** `true` when this is [LITTLE]. */
    val isLittleEndian: Boolean
        get() = this == LITTLE
}
