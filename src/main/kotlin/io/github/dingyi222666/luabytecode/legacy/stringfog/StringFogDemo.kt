package io.github.dingyi222666.luabytecode.legacy.stringfog

/**
 * Legacy source level string obfuscation demo: lexes Lua source with the external
 * luaparser dependency and rewrites every string literal with stringFog7.
 */
fun stringFogDemo() {
    println(stringFog7(SAMPLE_CODE, listOf("print", "table")))
}

private val SAMPLE_CODE = """
        local a = "hello world, 你好 世界"
        local b = "hello world"
        local c = "hello world"
        local d = "safkljhdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdffsdfsdsfdfdsfdfdfdafadfdadfjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjbjjjjjjjjjjjjjjjbjbjbjbjbjbjbjbjjjjj"
        local e = "safkljhdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjdjbjjjjjjjjjjjjjjjbjbjbjbjbjbjbjbjjjjj"
        local f = {b = b,c=c,a = a,d = d,e =e}

        print(f.a)
        print(f.b)
        print(f.c)
        print(f.d)
        print(f.e)
        print("xxxxxxxxxxxxx")
        print("xx")

        function a(t)
            for i=1, 10 do
                t[i] = i
            end
           return t
        end
        function c(s) return s end
        print(table.concat(a({}),","))
        print(c "hello world")
    """.trimIndent()


