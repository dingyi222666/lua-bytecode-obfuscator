package io.github.dingyi222666.luabytecode.bytecode

import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader

/** Fixture names are `<source>_<version>.luac`, compiled by real luac 5.3.6 / 5.4.6 / 5.5.1. */
internal val FIXTURE_VERSIONS = listOf(
    "53" to LuaBytecodeVersion.LUA_5_3,
    "54" to LuaBytecodeVersion.LUA_5_4,
    "55" to LuaBytecodeVersion.LUA_5_5,
)

/** Every generated fixture, including the stripped variants. */
internal val ALL_FIXTURES = listOf(
    "hello_53.luac", "hello_54.luac", "hello_55.luac",
    "hello_53_stripped.luac", "hello_54_stripped.luac", "hello_55_stripped.luac",
    "features_53.luac", "features_54.luac", "features_55.luac",
    "big_53.luac", "big_54.luac", "big_55.luac",
    "dedup_53.luac", "dedup_54.luac", "dedup_55.luac",
    "flow_53.luac", "flow_54.luac", "flow_55.luac",
    "dedup_53_stripped.luac", "dedup_54_stripped.luac", "dedup_55_stripped.luac",
)

internal object TestFixtures {
    fun bytes(name: String): ByteArray = checkNotNull(
        TestFixtures::class.java.getResourceAsStream("/bytecode/$name"),
    ) { "missing test fixture /bytecode/$name" }.use { it.readBytes() }

    fun text(name: String): String = checkNotNull(
        TestFixtures::class.java.getResourceAsStream("/lua/$name"),
    ) { "missing test resource /lua/$name" }.use { it.readBytes().toString(Charsets.UTF_8) }
}

internal fun loadFixture(name: String): LuaChunk = LuaChunkLoader.load(TestFixtures.bytes(name))

internal fun LuaChunk.allConstants(): List<LuaConstant> = prototypes.flatMap { it.constants }

internal fun LuaChunk.stringConstantTexts(): Set<String> =
    allConstants().filterIsInstance<LuaConstant.Str>().map { it.value.utf8 }.toSet()

internal fun LuaChunk.stringConstant(name: String): LuaString =
    allConstants().filterIsInstance<LuaConstant.Str>().first { it.value.utf8 == name }.value
