package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.edit.edit
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Always exercise encrypted products on the matching official Lua, never just the JVM oracle. */
class LuaVmSemanticBridgeTest {
    private fun check(source: String, versions: List<LuaBytecodeVersion> = LuaBytecodeVersion.entries,
                      expectedOpcodes: Set<String> = emptySet(), transform: (LuaChunk) -> Unit = {}) {
        for (version in versions) {
            val release = when (version) {
                LuaBytecodeVersion.LUA_5_3 -> "5.3.6"
                LuaBytecodeVersion.LUA_5_4 -> "5.4.6"
                LuaBytecodeVersion.LUA_5_5 -> "5.5.1"
            }
            val lua = "/tmp/lua-$release/src/lua"
            val luac = "/tmp/lua-$release/src/luac"
            assumeTrue(File(lua).canExecute() && File(luac).canExecute(), "Requires official Lua $release")
            val dir = Files.createTempDirectory("vm-bridge-").toFile()
            try {
                fun run(vararg command: String): String {
                    val output = File(dir, "output.txt")
                    val process = ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(output).start()
                    if (!process.waitFor(20, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        error("Timed out: ${command.toList()}")
                    }
                    val result = output.readText()
                    assertEquals(0, process.exitValue(), "$version: $result")
                    return result
                }
                val input = File(dir, "input.lua").apply { writeText(source.trimIndent()) }
                val chunkFile = File(dir, "input.luac")
                run(luac, "-o", chunkFile.path, input.path)
                val chunk = LuaChunkLoader.load(chunkFile, version)
                transform(chunk)
                LuaChunkWriter.dump(chunk, chunkFile)
                val baseline = run(lua, chunkFile.path)
                val seen = chunk.prototypes.flatMap { p -> p.code.map { it.opcodeName } }.toSet()
                assertTrue(seen.containsAll(expectedOpcodes), "Missing ${expectedOpcodes - seen}: $seen")
                for (seed in listOf(0L, 11L, 42L, 55L)) {
                    val product = LuaVmObfuscation.emitModule(chunk, ObfRandom(seed), LuaVmObfuscation.Options(seed))
                    assertNotNull(product, "$version seed=$seed refused: $seen")
                    val module = File(dir, "module.lua").apply { writeText(product) }
                    assertEquals(baseline, run(lua, module.path), "$version seed=$seed")
                }
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun `generic for preserves false controls and closes on break`() = check("""
        local function iter(_, v)
          if v == nil then return false, 11 end
          if v == false then return 1, 22 end
        end
        for k,v in iter do print(k,v) end
        for k,v in ipairs({10,20,30}) do print(k,v) end
    """)

    @Test fun `open calls varargs returns and list constructors retain nil and false`() = check("""
        local function identity(...) return ... end
        local function receive(...) print(select('#', ...), ...) end
        receive(identity(nil, false, 3, nil))
        local t = {8, identity(false, nil, 9, nil)}
        print(t[1], t[2], t[3], t[4], t[5])
        local function tail(...) return identity(...) end
        receive(tail(false, nil))
        local function none() end
        receive(none())
        local function fixed(...) local a,b,c,d = ...; return a,b,c,d end
        receive(fixed(nil,false))
        local function many(...) local t = {...}; print(select('#', ...), t[1], t[160], t[200]); return ... end
        local big = {}; local i = 1
        while i <= 200 do big[i] = i; i = i + 1 end
        receive(select('#', many(table.unpack(big, 1, 200))))
    """)

    @Test fun `all arithmetic comparison indexing and call metamethods use native bridge`() = check("""
        local function label(v) if type(v) == 'table' then return v.name end return v end
        local function binary(name)
          return function(a,b) print(name,label(a),label(b)); return 7 end
        end
        local mt = {
          __add=binary('add'), __sub=binary('sub'), __mul=binary('mul'),
          __div=binary('div'), __idiv=binary('idiv'), __mod=binary('mod'), __pow=binary('pow'),
          __band=binary('and'), __bor=binary('or'), __bxor=binary('xor'),
          __shl=binary('shl'), __shr=binary('shr'), __unm=binary('unm'), __bnot=binary('bnot'),
          __len=function(t) return 12 end,
          __index=function(t,k) print('index',k); return false end,
          __newindex=function(t,k,v) print('newindex',k,v) end,
          __call=function(t,...) print('call',select('#',...),...); return false,nil end,
          __concat=function(a,b) print('concat',label(a),label(b)); return 'joined' end,
          __lt=function(a,b) print('lt',label(a),label(b)); return false end,
          __le=function(a,b) print('le',label(a),label(b)); return true end,
          __eq=function(a,b) print('eq',label(a),label(b)); return true end
        }
        local a,b = setmetatable({name='a'},mt),setmetatable({name='b'},mt)
        print(a+b,a-b,a*b,a/b,a//b,a%b,a^b,a&b,a|b,a~b,a<<b,a>>b,-a,~a,#a)
        print(a+2,2+a,a-2,a*2,2*a,a<<2,2<<a,a>>2,2>>a)
        print(a < b,a <= b,a == b,a < 2,a <= 2,a < 2.0)
        print(a.foo); a.bar=false
        print(a .. b .. 'z'); print(a(nil,false,nil))
        local env=setmetatable({}, {__index=function(_,k) return false end})
        local function access() local _ENV=env; return missing end
        print(access())
        local function dynamic(k) local _ENV=env; return _ENV[k] end
        print(dynamic('key'))
        local obj={value=false,method=function(self,x) return self.value,x end}
        print(obj:method(false))
    """)

    @Test fun `closed cells remain shared but register reuse creates new cells`() = check("""
        local get,set
        do
          local x=false
          get=function() return x end
          set=function(v) x=v end
        end
        set(nil); print(get()==nil)
        set(false); print(get())
        local fs={}; local i=1
        while i<=3 do local x=i; fs[i]=function() return x end; i=i+1 end
        print(fs[1](),fs[2](),fs[3]())
        local function parent()
          local function child() return function() return 10 end end
          local function sibling() return 20 end
          return child()(),sibling()
        end
        print(parent())
    """)

    private val modern = listOf(LuaBytecodeVersion.LUA_5_4, LuaBytecodeVersion.LUA_5_5)
    @Test fun `native close scopes preserve reverse order errors and generic for resources`() = check("""
        local failure={}
        local mt={__metatable='locked',__close=function(v,e) print('close',v.name,e==failure) end}
        local function resource(name) return setmetatable({name=name},mt) end
        local function normal()
          local x <close> = resource('outer')
          do local y <close> = resource('inner') end
          return false,nil,3
        end
        print(normal())
        local ok,e=pcall(function()
          local x <close> = resource('error outer')
          local y <close> = resource('error inner')
          error(failure)
        end)
        print(ok,e==failure)
        local function iterator()
          return function(_,v) if v==nil then return 1 end end,nil,nil,resource('iterator')
        end
        for v in iterator() do print(v); break end
        local function changing()
          local mt2={__close=function(v,e) print('old') end}
          local x <close> = setmetatable({},mt2)
          mt2.__close=function(v,e) print('new') end
        end
        changing()
        local ok=pcall(function() local bad <close> = 1 end); print(ok)
        do local ignored <close> = false end
    """, modern, setOf("TBC", "CLOSE", "TFORPREP"))

    @Test fun `coroutines resume nested VM and host calls without replaying effects`() = check("""
        local function yield(...) return coroutine.yield(...) end
        local function inner(...) local a,b,c=yield(...); print('resumed',a,b,c); return a,b,c end
        local co=coroutine.create(function() return inner(false,nil,7,nil) end)
        print(coroutine.resume(co)); print(coroutine.status(co))
        print(coroutine.resume(co,nil,false,9)); print(coroutine.status(co))
        local mt={__index=function(t,k) print('before',k); return yield('index',nil) end,
                  __add=function(a,b) print('add once'); return yield('add') end}
        local co2=coroutine.create(function()
          local t=setmetatable({},mt)
          print('value',t.key)
          print('result',t+t)
        end)
        print(coroutine.resume(co2)); print(coroutine.resume(co2,false)); print(coroutine.resume(co2,17))
        local co3=coroutine.create(function()
          local ok,a,b=pcall(function() return yield('protected') end)
          print(ok,a,b)
        end)
        print(coroutine.resume(co3)); print(coroutine.resume(co3,false,nil))
    """)

    @Test fun `yield in close and coroutine close use native unwinding`() = check("""
        local co=coroutine.create(function()
          local x <close> = setmetatable({}, {__close=function() print('closing'); coroutine.yield('in close'); print('closed') end})
          return false,nil
        end)
        print(coroutine.resume(co)); print(coroutine.resume(co))
        local co2=coroutine.create(function()
          local a <close> = setmetatable({}, {__close=function(v,e) print('outer',e==nil) end})
          local b <close> = setmetatable({}, {__close=function(v,e) print('inner',e==nil) end})
          coroutine.yield('suspend')
        end)
        print(coroutine.resume(co2)); print(coroutine.close(co2)); print(coroutine.status(co2))
    """, modern)

    @Test fun `5_5 hidden vararg indexing and mutable table expansion`() = check("""
        local function hidden(...t)
          print(t.n,t[1],t[2],t[3],t[4],t[1.0],t[0],t[-1],t['1'],t[false])
          return ...
        end
        print(hidden(false,nil,3,nil))
        local function mutable(a,...t)
          t[1]=false; t[3]=9; t.n=4
          setmetatable(t,{__index=function() return 'wrong' end})
          local function captured() return t end
          print(captured()==t)
          return ...
        end
        print(mutable(0,1,2))
        local function bad(...t) t.n=1.5; return ... end
        local ok,e=pcall(bad,1); print(ok,string.find(e,"vararg table has no proper 'n'",1,true)~=nil)
    """, listOf(LuaBytecodeVersion.LUA_5_5), setOf("GETVARG", "VARARG"))

    @Test fun `5_5 global initialization refuses every non nil value`() = check("""
        global seen, pcall, print, string
        local function init() global seen=false end
        init()
        local ok,e=pcall(init)
        print(seen,ok,e)
        seen=nil; init(); print(seen)
    """, listOf(LuaBytecodeVersion.LUA_5_5), setOf("ERRNNIL"))

    @Test fun `setlist extended bases consume extraarg`() {
        val values = List(1100) { "false" }.joinToString(",")
        check("local t={$values}; print(#t,t[1],t[255],t[256],t[1024],t[1100])", versions=modern, expectedOpcodes=setOf("SETLIST","EXTRAARG"))
    }
    @Test fun `close errors replace the active error in reverse scope order`() = check("""
        local first,second,third={},{},{}
        local ok,e=pcall(function()
          local a <close> = setmetatable({}, {__close=function(v,e) print('outer',e==second); error(third) end})
          local b <close> = setmetatable({}, {__close=function(v,e) print('inner',e==first); error(second) end})
          error(first)
        end)
        print(ok,e==third)
        local co=coroutine.create(function()
          local resource=setmetatable({}, {__close=function(v,e) print('iterator closed',e==nil) end})
          local function iter(_,v) if not v then return 1 end end
          for value in iter,nil,nil,resource do coroutine.yield(value) end
        end)
        print(coroutine.resume(co)); print(coroutine.close(co))
    """, modern)

    @Test fun `5_3 setlist extraarg supplies the full block index`() = check(
        "local t={false}; print(t[51101],t[1])", listOf(LuaBytecodeVersion.LUA_5_3), setOf("SETLIST","EXTRAARG"),
    ) { chunk ->
        chunk.edit { main {
            val pc = code.indexOfFirst { it.opcodeName == "SETLIST" }
            replace(pc..pc, stream().emit(code[pc].withC(0)).ax("EXTRAARG",1023).build())
        } }
    }

    @Test fun `host library mutations do not corrupt private VM helpers`() = check("""
        local saved,output=table,print
        table={}
        local function f(...) return ... end
        output(f(false,nil,3,nil))
        table=saved
        output(string.byte('\255\0\128',1,3))
    """)

    @Test fun `yielding metamethods commit once and preserve native errors`() = check("""
        local failure={}
        local object=setmetatable({}, {
          __newindex=function(t,k,v) print('write once',k,v); coroutine.yield('write') end,
          __len=function(t) print('length once'); return coroutine.yield('length') end,
          __concat=function(a,b) print('concat once'); return coroutine.yield('concat') end,
          __lt=function(a,b) print('compare once'); return coroutine.yield('compare') end,
          __call=function(t,...) print('call once',select('#',...)); return coroutine.yield('call') end
        })
        local co=coroutine.create(function()
          object.k=false
          print(#object)
          print(object .. 'x')
          print(object < {})
          print(object(nil,false,nil))
        end)
        print(coroutine.resume(co)); print(coroutine.resume(co)); print(coroutine.resume(co,12))
        print(coroutine.resume(co,'joined')); print(coroutine.resume(co,false)); print(coroutine.resume(co,false,nil))
        local bad=setmetatable({}, {__add=function() error(failure) end})
        local ok,e=pcall(function() return bad+bad end)
        print(ok,e==failure)
    """)

}
