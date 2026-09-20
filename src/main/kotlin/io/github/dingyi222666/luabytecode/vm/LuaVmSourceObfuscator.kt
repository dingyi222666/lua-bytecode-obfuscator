package io.github.dingyi222666.luabytecode.vm

/**
 * Small source level protection pass for the generated Lua host.
 *
 * This intentionally does not parse Lua. It only replaces the single generated VM entry call
 * with a private, three-way source dispatcher whose branches have identical effects. The real
 * VM payload, decryptor and handler polymorphism remain unchanged; this pass is a safe staging
 * layer until a target-version bytecode editor is available for the generated host itself.
 */
internal object LuaVmSourceObfuscator {
    fun apply(source: String, entryCall: String, random: java.util.Random): String {
        if (entryCall.isEmpty() || !source.contains(entryCall)) return source
        fun ident(): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz"
            var result = "_" + alphabet[random.nextInt(alphabet.length)]
            repeat(4 + random.nextInt(4)) {
                result += if (random.nextBoolean()) alphabet[random.nextInt(alphabet.length)] else random.nextInt(10)
            }
            return result
        }
        val state = ident()
        val step = ident()
        val route = ident()
        val routes = ident()
        val multiplier = 5 + random.nextInt(91) * 2
        val increment = 1 + random.nextInt(89)
        val prelude = """
local $state = #tostring(_VERSION)
local function $step(v)
    local q = (v * $multiplier + $increment) % 97
    if q % 3 == 0 then return q + 1 elseif q % 3 == 1 then return q + 2 else return q + 3 end
end
local $route = $step($state)
""".trimIndent()
        val dispatch = """
do
    local $routes = {}
    $routes[1] = function() $entryCall end
    $routes[2] = function() $entryCall end
    $routes[3] = function() $entryCall end
    $routes[(($route % 3) + 1)]()
end
""".trimIndent()
        return source.replaceFirst(entryCall, "$dispatch\n")
            .replaceFirst("\n", "\n$prelude\n")
    }
}
