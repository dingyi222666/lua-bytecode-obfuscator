package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.bytecode.*
import io.github.dingyi222666.luabytecode.bytecode.analysis.*
import io.github.dingyi222666.luabytecode.bytecode.io.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue

class LuaAdvancedProtectionTest {
    @Test fun `global field method and shared comparison strings are actually encrypted`() = native("""
        local trace = {}
        secretGlobal = {}
        secretGlobal.secretField = "secretValue"
        local alias = secretGlobal
        function alias:secretMethod(s) return self.secretField .. s end
        local function access(flag)
          local secretGlobal = {secretField = "localValue"}
          if flag then return secretGlobal.secretField end
          return alias:secretMethod("suffix")
        end
        local proxy = setmetatable({}, {
          __index = function(t,k) trace[#trace+1] = "get:" .. k; return alias[k] end,
          __newindex = function(t,k,v) trace[#trace+1] = "set:" .. k .. ":" .. v end
        })
        proxy.secretField = "secretValue"
        print(proxy.secretField, proxy:secretMethod("suffix"))
        print(access(false), access(true), alias.secretField == "secretValue")
        print(table.concat(trace, ","))
        do
          local _ENV = {secretGlobal = alias}
          secretGlobal.secretField = "otherValue"
        end
        print(alias.secretField)
    """.trimIndent(), keys = setOf("secretGlobal", "secretField", "secretMethod", "secretValue"))

    @Test fun `jump routes retain loop exits closing captures and open result counts`() = native("""
        local function f(flag, n)
          local out, fs = 0, {}
          for i=1,n do
            local x=i
            fs[i]=function() return x end
            if flag and i==3 then break end
            out=out+i
          end
          for _,v in ipairs({2,3,5}) do out=out+v end
          for i=0,1,0.25 do out=out+i end
          return out,fs[1](),fs[2](),fs[3]()
        end
        local function values(...) return ... end
        local function fmt(n) return string.format("%d.%02d", n//100,n%100) end
        print(f(false,4)); print(f(true,4))
        print(values(1,nil,3)); print(fmt(250))
    """.trimIndent())

    @Test fun `constant encryption preserves full integer width float type and negative zero`() = native("""
        local function f(flag)
          local hi = 0x7fffffffffffffff
          local lo = 0x8000000000000000
          local fraction = 1.2345678901234567
          local negzero = -0.0
          local small = 4.9406564584124654e-324
          if flag then print(hi, lo) else print(lo, hi) end
          print(math.type(hi), math.type(fraction), math.type(negzero), 1 / negzero)
          print(string.format("%a,%a", fraction, small))
        end
        f(false); f(true)
    """.trimIndent())

    @Test fun `extended constant loads preserve their two word instruction boundary`() = native("""
        local function f(flag)
          local n, s = 9999999999, "extended secret literal"
          if flag then print(s,n) else print(n,s) end
        end
        f(false); f(true)
    """.trimIndent(), extendedLoads = true)

    @Test fun `runtime route comparisons have distinct successor blocks`() {
        val chunk = io.github.dingyi222666.luabytecode.bytecode.loadFixture("flow_54.luac")
        val before = java.util.IdentityHashMap<LuaPrototype, Int>()
        chunk.prototypes.forEach { before[it] = it.code.size }
        val report = LuaJumpIndirection.apply(chunk, JumpIndirectionConfig(depth = 3, runtimeRoutes = true))
        assertTrue(report.runtimeRoutedJumps > 0)
        var tested = 0
        for (p in chunk.prototypes) for (pc in before.getValue(p) until p.code.size - 2) {
            if (p.code[pc].opcodeName != "TEST") continue
            val a = p.code[pc + 1].jumpTarget(pc + 1)
            val b = p.code[pc + 2].jumpTarget(pc + 2)
            assertNotEquals(a, b)
            tested++
        }
        assertTrue(tested > 0)
    }

    @Test fun `oversized jump candidate rolls back instead of wrapping its offset`() {
        val v = LuaBytecodeVersion.LUA_5_3
        val chunk = io.github.dingyi222666.luabytecode.bytecode.loadFixture("flow_53.luac")
        val p = chunk.mainPrototype
        p.nestedPrototypes.clear()
        p.code.clear()
        p.code += jumpTo(v, 0, 1)
        repeat(132000) { p.code += LuaInstruction.abc(v, LuaOpcodes.opcodeOf(v, "RETURN")!!, 0, 1) }
        p.lineInfo.clear(); p.absoluteLineInfo.clear()
        val before = LuaChunkWriter.dump(chunk)
        val report = LuaJumpIndirection.apply(chunk, JumpIndirectionConfig(depth = 3))
        assertTrue(report.skippedPrototypes.any { "bit field" in it })
        assertTrue(before.contentEquals(LuaChunkWriter.dump(chunk)))
    }

    @Test fun `string failure does not leave encrypted pools or decoder upvalues behind`() {
        val chunk = io.github.dingyi222666.luabytecode.bytecode.loadFixture("hello_53.luac")
        chunk.mainPrototype.maxStackSize = 254
        val before = LuaChunkWriter.dump(chunk)
        assertFailsWith<IllegalArgumentException> {
            LuaStringEncryptor.encrypt(chunk, StringEncryptionConfig(minLength = 1, encryptKeys = true))
        }
        assertTrue(before.contentEquals(LuaChunkWriter.dump(chunk)))
    }

    private fun native(source: String, keys: Set<String> = emptySet(), extendedLoads: Boolean = false) {
        val versions = listOf("5.3.6", "5.4.6", "5.5.1")
        assumeTrue(versions.all { File("/tmp/lua-$it/src/luac").canExecute() })
        val dir = Files.createTempDirectory("advanced-protection-").toFile()
        try {
            val input = File(dir, "input.lua").apply { writeText(source) }
            for (v in versions) {
                val original = File(dir, "original.luac")
                run(dir, "/tmp/lua-$v/src/luac", "-o", original.path, input.path)
                val expected = run(dir, "/tmp/lua-$v/src/lua", original.path)
                for (seed in listOf(7L, 29L, 8731L)) for (combined in listOf(false, true)) {
                    val chunk = LuaChunkLoader.load(original)
                    if (extendedLoads) for (p in chunk.prototypes) {
                        val old = p.code.toList()
                        val offsets = IntArray(old.size + 1)
                        val code = ArrayList<LuaInstruction>()
                        for ((pc, i) in old.withIndex()) {
                            offsets[pc] = code.size
                            if (i.opcodeName == "LOADK") {
                                code += LuaInstruction.abx(p.version, LuaOpcodes.opcodeOf(p.version, "LOADKX")!!, i.a, 0)
                                code += LuaInstruction.ax(p.version, LuaOpcodes.opcodeOf(p.version, "EXTRAARG")!!, i.bx)
                            } else code += i
                        }
                        offsets[old.size] = code.size
                        for ((pc, i) in old.withIndex()) i.jumpTarget(pc)?.let { target ->
                            code[offsets[pc]] = i.retargeted(pc, offsets[pc], offsets[target])
                        }
                        p.code.clear(); p.code.addAll(code)
                        p.lineInfo.clear(); p.absoluteLineInfo.clear(); p.localVariables.clear()
                    }
                    val config = if (combined) ObfuscationConfig.AGGRESSIVE.copy(masterSeed = seed)
                        else ObfuscationConfig(masterSeed = seed,
                            stringEncryption = StringEncryptionConfig(minLength = 1, encryptKeys = true),
                            constantEncryption = ConstantEncryptionConfig(),
                            jumpIndirection = JumpIndirectionConfig(depth = 5, runtimeRoutes = true, decoyBlocks = 17))
                    val report = LuaObfuscator.obfuscate(chunk, config)
                    assertTrue(report.stringEncryption!!.loweredOperandSites > 0)
                    val remaining = chunk.prototypes.flatMap { p -> p.constants.filterIsInstance<LuaConstant.Str>() }
                        .map { it.value.utf8 }.toSet()
                    assertTrue(keys.intersect(remaining).isEmpty(), "$v plaintext: ${keys.intersect(remaining)}")
                    val jumps = report.jumpIndirection!!
                    assertTrue(jumps.runtimeRoutedJumps > 0, "$v $jumps")
                    assertTrue(jumps.verifiedPaths > 0 && jumps.decoyBlocks > 0)
                    val output = File(dir, "protected.luac")
                    LuaChunkWriter.dump(chunk, output)
                    assertEquals(expected, run(dir, "/tmp/lua-$v/src/lua", output.path),
                        "$v seed=$seed combined=$combined")
                }
            }
        } finally { dir.deleteRecursively() }
    }

    private fun run(dir: File, vararg cmd: String): String {
        val log = File.createTempFile("native-", ".log", dir)
        val process = ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(log).start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(); error("timeout ${cmd.toList()}") }
        return log.readText().also { assertEquals(0, process.exitValue(), "${cmd.toList()}: $it") }
    }
}
