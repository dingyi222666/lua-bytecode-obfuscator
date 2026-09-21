package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.platform.jvm.dump
import io.github.dingyi222666.luabytecode.platform.jvm.load
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Differential checks use the native VM, including its implicit fetch/close behavior. */
class LuaCffSemanticsTest {
    private val versions = listOf(
        LuaBytecodeVersion.LUA_5_3 to "5.3.6",
        LuaBytecodeVersion.LUA_5_4 to "5.4.6",
        LuaBytecodeVersion.LUA_5_5 to "5.5.1",
    )

    @Test
    fun `loop exits boolean skips and captured locals survive block layouts`() = differential(
        """
        local function loops(n)
          local s = 0
          for i = 1, n do s = s + i end
          for i = n, 1, -1 do s = s + i end
          for i = 2.5, 0.5, -0.5 do s = s + i end
          for _, v in ipairs({3, 5, 7}) do s = s + v end
          return s
        end
        local function capture(n)
          local fs = {}
          for i = 1, n do
            local x = i
            fs[i] = function() return x end
          end
          local a, b, c = fs[1](), fs[2](), fs[3]()
          return a, b, c
        end
        local function boolean(a, b)
          local c = a and b
          return a < b, a == b, not c, c
        end
        print(loops(0), loops(1), loops(4))
        print(capture(3))
        print(boolean(1, 2))
        print(boolean(2, 1))
        """.trimIndent(),
    )

    @Test
    fun `control state does not observe or modify the environment`() = differential(
        """
        local output = print
        local function f(n)
          _VERSION = nil
          local s = 0
          while n > 0 do s = s + n; n = n - 1 end
          return s
        end
        _VERSION = nil
        output(f(4))
        """.trimIndent(),
    )

    @Test
    fun `all control packet features execute with both runtime keys`() = differential(
        """
        local function f(flag, n)
          local a = n + 1
          local b = a * 2
          local c = b - 3
          local d = c * 4
          local e = d + 5
          local g = e * 6
          if flag then g = g + 7 else g = g - 8 end
          if n > 0 then g = g + n else g = g - n end
          return g
        end
        print(f(false, 3), f(true, 3), f(nil, -2), f({}, -2))
        """.trimIndent(),
        full = true,
    )

    @Test
    fun `open results nil holes errors and coroutine resumes are preserved`() = differential(
        """
        local function values(n) return n, nil, n+1 end
        local function forward(...) return ... end
        local function f(n)
          local t = {values(n)}
          local a,b,c = forward(values(n))
          return a,b,c,t[1],t[3]
        end
        local function worker(n)
          local a = coroutine.yield(n+1)
          if a then return n+2 else return n+3 end
        end
        local function format(n) return string.format("%d.%02d", n // 100, n % 100) end
        print(format(250))
        print(f(3))
        local co = coroutine.create(worker)
        print(coroutine.resume(co, 5))
        print(coroutine.resume(co, true))
        """.trimIndent(),
    )

    @Test
    fun `C callees may grow and overwrite hundreds of temporary stack slots`() = differential(
        """
        local clobber = assert(__cff_clobber)
        local function f(n)
          local a = clobber(n)
          if n > 0 then a = a + 1 else a = a - 2 end
          local b = clobber(a)
          return b + 2
        end
        print(f(3), f(-2))
        """.trimIndent(), nativeModule = true,
    )

    @Test
    fun `individual features and dependent pairs preserve native results`() {
        val base = ControlFlowFlatteningConfig.BASELINE.copy(seed = 19)
        val configs = listOf(
            base, base.copy(stateRuntime = true), base.copy(stateNamespaces = true),
            base.copy(segmentedDispatch = true, segmentSize = 1),
            base.copy(stateRuntime = true, multipleDispatch = true),
            base.copy(stateNamespaces = true, nestedDispatch = true),
            base.copy(dispatchEffects = true), base.copy(indegreeGuards = true, stateRuntime = true),
            base.copy(splitStraightLine = true), base.copy(participatingJunk = true),
            base.copy(stateTable = true, allocationEffectsUnobservable = true),
        ) + CffPredicateFamily.entries.map { base.copy(stateRuntime = true,
            predicateFamilies = setOf(it), allocationEffectsUnobservable = true) }
        for (config in configs) differential("""
            local function f(flag, n)
              local a = n + 1; local b = a * 2; local c = b - 3
              local d = c * 4; local e = d + 5; local g = e * 6
              if flag then g = g + 7 else g = g - 8 end
              if n > 0 then g = g + n else g = g - n end
              return g
            end
            print(f(false, 3), f(true, 3), f(nil, -2), f({}, -2))
        """.trimIndent(), requested = config)
    }

    @Test
    fun `to be closed values preserve break return and error order`() = differential("""
        local log, sentinel = {}, {}
        local mt = {__close = function(self, err)
          log[#log+1] = self.name .. (err == sentinel and ":error" or ":ok")
        end}
        local function f(fail)
          local a <close> = setmetatable({name="a"}, mt)
          local b <close> = setmetatable({name="b"}, mt)
          for i = 1, 3 do
            local c <close> = setmetatable({name="c" .. i}, mt)
            if i == 2 then break end
          end
          if fail then error(sentinel) end
          return 1, nil, 3
        end
        print(pcall(f, false))
        print(table.concat(log, ","))
        log = {}
        local ok, err = pcall(f, true)
        print(ok, err == sentinel, table.concat(log, ","))
    """.trimIndent(), supports53 = false)

    private fun differential(source: String, full: Boolean = false, nativeModule: Boolean = false,
        requested: ControlFlowFlatteningConfig? = null, supports53: Boolean = true) {
        assumeTrue(versions.all { (_, v) -> File("/tmp/lua-${v}/src/luac").canExecute() })
        val dir = Files.createTempDirectory("cff-semantics-").toFile()
        try {
            val input = File(dir, "input.lua").apply { writeText(source) }
            for ((version, nativeVersion) in versions) {
                if (!supports53 && version == LuaBytecodeVersion.LUA_5_3) continue
                var lua = "/tmp/lua-${nativeVersion}/src/lua"
                if (nativeModule) {
                    val c = File(dir, "scratch.c").apply { writeText("""
                        #include "lua.h"
                        #include "lauxlib.h"
                        #include "lualib.h"
                        #include <stdio.h>
                        static int scratch(lua_State *L) {
                          lua_Integer n = luaL_checkinteger(L, 1);
                          luaL_checkstack(L, 512, "scratch regression");
                          for (int i = 0; i < 300; ++i) lua_pushinteger(L, 1000 + i);
                          lua_pushinteger(L, n * 2);
                          return 1;
                        }
                        int main(int argc, char **argv) {
                          lua_State *L = luaL_newstate();
                          luaL_openlibs(L);
                          lua_pushcfunction(L, scratch);
                          lua_setglobal(L, "__cff_clobber");
                          int rc = luaL_loadfile(L, argv[1]);
                          if (!rc) rc = lua_pcall(L, 0, LUA_MULTRET, 0);
                          if (rc) fprintf(stderr, "%s\n", lua_tostring(L, -1));
                          lua_close(L);
                          return rc ? 1 : 0;
                        }
                    """.trimIndent()) }
                    val host = File(dir, "scratch-host")
                    val linking = if (System.getProperty("os.name").contains("Mac")) {
                        // Use the SDK belonging to the selected compiler. A newer
                        // CommandLineTools SDK can be incompatible with Xcode's ld.
                        val compiler = run(dir, "xcrun", "--find", "clang").trim()
                        val sdk = File(compiler.substringBefore("/Toolchains/") +
                            "/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk")
                        if (sdk.isDirectory) arrayOf("-isysroot", sdk.path) else emptyArray()
                    } else emptyArray()
                    run(dir, "cc", *linking, "-I/tmp/lua-${nativeVersion}/src", c.path,
                        "/tmp/lua-${nativeVersion}/src/liblua.a", "-lm", "-o", host.path)
                    lua = host.path
                }
                val original = File(dir, "original.luac")
                run(dir, "/tmp/lua-${nativeVersion}/src/luac", "-o", original.path, input.path)
                val expected = run(dir, lua, original.path)
                for (shuffle in listOf(false, true)) for (jumps in listOf(false, true)) {
                    val chunk = LuaChunkLoader.load(original, version)
                    if (jumps) LuaJumpIndirection.apply(chunk, JumpIndirectionConfig(depth = 3, seed = 7))
                    val config = requested?.copy(shuffleBlocks = shuffle) ?: if (full) ControlFlowFlatteningConfig.FULL.copy(
                        seed = 7, shuffleBlocks = shuffle, segmentSize = 2,
                        stateTable = true, allocationEffectsUnobservable = true,
                        predicateFamilies = CffPredicateFamily.entries.toSet(),
                    ) else ControlFlowFlatteningConfig(
                        seed = 7, shuffleBlocks = shuffle,
                        stateRuntime = true, participatingJunk = true,
                        segmentedDispatch = true, indegreeGuards = true,
                    )
                    val report = if (full) checkNotNull(LuaObfuscator.obfuscate(chunk, ObfuscationConfig(
                        masterSeed = 7, controlFlowFlattening = config,
                        constantEncryption = ConstantEncryptionConfig(seed = 7),
                    )).controlFlowFlattening) else LuaControlFlowFlattening.apply(chunk, config)
                    assertTrue(report.flattenedPrototypes > 0, "$nativeVersion $report")
                    if (requested != null && !jumps) {
                        val required = buildList {
                            if (config.stateRuntime) add("state.runtime")
                            if (config.stateNamespaces) add("state.namespaces")
                            if (config.segmentedDispatch) add("dispatch.segmented")
                            if (config.multipleDispatch) add("dispatch.multiple")
                            if (config.nestedDispatch) add("dispatch.nested")
                            if (config.dispatchEffects) add("dispatch.effects")
                            if (config.indegreeGuards) add("indegree")
                            if (config.splitStraightLine) add("split.straightline")
                            if (config.participatingJunk) add("junk.participating")
                            if (config.stateTable) add("state.table")
                            config.predicateFamilies.forEach { add("predicate.${it.name.lowercase()}") }
                        }
                        assertTrue(report.appliedFeatures.containsAll(required),
                            "$nativeVersion missing ${required - report.appliedFeatures.toSet()}: ${report.prototypes}")
                    }
                    if (full && !jumps) {
                        for (feature in listOf("state.runtime", "state.namespaces", "state.table",
                            "dispatch.multiple", "dispatch.nested", "dispatch.effects", "indegree") +
                            CffPredicateFamily.entries.map { "predicate.${it.name.lowercase()}" }) {
                            assertTrue(feature in report.appliedFeatures, "$nativeVersion missing $feature: ${report.prototypes}")
                        }
                    }
                    val output = File(dir, "flattened.luac")
                    LuaChunkWriter.dump(chunk, output)
                    if (full) {
                        assertTrue(report.prototypes.any { (it.proof?.validationBudget ?: 0) > config.maxValidationSteps },
                            "the final constant expansion must actually run")
                        val reloaded = LuaChunkLoader.load(output, version)
                        for (record in report.prototypes) {
                            val proof = record.proof ?: continue
                            val p = reloaded.prototypes[record.prototypeIndex]
                            CffTranslationValidator.verify(p.code, p.constants,
                                proof.controlEntries.map { (pc, target) -> CffControlPackets.Site(pc, target) },
                                proof.destinationPcs, proof.firstPrivateRegister, p.maxStackSize, proof.validationBudget)
                        }
                    }
                    assertEquals(expected, run(dir, lua, output.path),
                        "$nativeVersion shuffle=$shuffle jumps=$jumps")
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun run(dir: File, vararg command: String): String {
        val log = File.createTempFile("process-", ".log", dir)
        val process = ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            error("Timed out: ${command.joinToString(" ")}")
        }
        val output = log.readText()
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")}: $output")
        return output
    }
}
