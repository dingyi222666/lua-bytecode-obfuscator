package io.github.dingyi222666.luabytecode.bytecode


/**
 * How a version serializes its variable length integers
 * (`dumpVarint` / `loadVarint`).
 *
 * Both flavours write 7 bits per byte, most significant group first; they only
 * differ in which byte carries the `0x80` marker.
 */
enum class LuaVarintStyle {
    /**
     * Lua 5.4: the **last** byte is marked with `0x80`
     * (`while ((b & 0x80) == 0)` in 5.4 `lundump.c`).
     */
    TERMINATOR_FLAG,

    /**
     * Lua 5.5: every byte **except the last** is marked with `0x80`
     * (`while ((b & 0x80) != 0)` in 5.5 `lundump.c`).
     */
    CONTINUATION_FLAG,
}

/**
 * A supported Lua binary chunk (luac) version.
 *
 * The version byte lives at offset 4 of the chunk header and equals
 * `major * 16 + minor` (see `LUAC_VERSION` in `lundump.h`).
 */
enum class LuaBytecodeVersion(
    /** The byte stored in the chunk header. */
    val versionByte: Int,
    val displayName: String,
) {
    /** Lua 5.3.x — `LUAC_VERSION == 0x53`. */
    LUA_5_3(0x53, "Lua 5.3"),

    /** Lua 5.4.x — `LUAC_VERSION == 0x54`. */
    LUA_5_4(0x54, "Lua 5.4"),

    /** Lua 5.5.x — `LUAC_VERSION == 0x55`. */
    LUA_5_5(0x55, "Lua 5.5"),
    ;

    /** 5.3 uses fixed size native integers, 5.4 and 5.5 use varints. */
    val usesVariableLengthInt: Boolean
        get() = this != LUA_5_3

    /** Varint flavour of this version, `null` for 5.3 which has no varints. */
    val varintStyle: LuaVarintStyle?
        get() = when (this) {
            LUA_5_3 -> null
            LUA_5_4 -> LuaVarintStyle.TERMINATOR_FLAG
            LUA_5_5 -> LuaVarintStyle.CONTINUATION_FLAG
        }

    /** 5.4 and 5.5 store relative line info plus absolute line info anchors. */
    val hasAbsoluteLineInfo: Boolean
        get() = this != LUA_5_3

    /** 5.4 and 5.5 upvalue descriptors carry an extra `kind` byte. */
    val upvalueDescriptorHasKind: Boolean
        get() = this != LUA_5_3

    /** 5.5 deduplicates every dumped string through an index table. */
    val deduplicatesStrings: Boolean
        get() = this == LUA_5_5

    /** 5.5 encodes integer constants and `lua_Integer` constants as zig-zag varints. */
    val integerConstantsAreZigZag: Boolean
        get() = this == LUA_5_5

    /** 5.5 writes a prototype source *after* its nested prototypes. */
    val sourceAfterPrototypes: Boolean
        get() = this == LUA_5_5

    /** 5.5 writes absolute line info as raw `int` pairs instead of two varints. */
    val absoluteLineInfoIsRawInts: Boolean
        get() = this == LUA_5_5

    /**
     * Interprets the raw prototype flag byte stored next to `numparams`:
     * 5.3 and 5.4 store a plain boolean, 5.5 stores the `PF_*` bits.
     */
    fun isVararg(flags: Int): Boolean = when (this) {
        LUA_5_3, LUA_5_4 -> flags != 0
        LUA_5_5 -> (flags and (PF_VAHID or PF_VATAB)) != 0
    }

    companion object {
        /** `PF_VAHID` — function has hidden vararg arguments (5.5 `lobject.h`). */
        const val PF_VAHID = 1

        /** `PF_VATAB` — function has a vararg table (5.5 `lobject.h`). */
        const val PF_VATAB = 2

        /** `PF_FIXED` — prototype has parts in fixed memory (5.5 `lobject.h`). */
        const val PF_FIXED = 4

        /** Returns the matching version, or `null` when the byte is not supported. */
        @JvmStatic
        fun fromVersionByte(versionByte: Int): LuaBytecodeVersion? =
            entries.firstOrNull { it.versionByte == versionByte }

        /** Comma separated list of the supported version bytes, for error messages. */
        @JvmStatic
        fun supportedVersionsText(): String =
            entries.joinToString(", ") { "0x%02X (%s)".format(it.versionByte, it.displayName) }
    }
}
