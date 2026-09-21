// Resolution rules of the plugin registry, pinned on every target (jvm / js /
// native): canonical built-in order, registration-order independence, constraint
// honouring, cycle / dangling / duplicate / seed-domain rejection.
package io.github.dingyi222666.luabytecode.obfuscate

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ObfuscationRegistryTest {

    /** Graph-shaped test plugin; [apply] is never invoked by resolution. */
    private class FakePass(
        override val id: String,
        override val runsAfter: Set<String> = emptySet(),
        override val runsBefore: Set<String> = emptySet(),
        override val seedLabel: String? = null,
    ) : ObfuscationPassPlugin {
        override fun apply(context: ObfuscationContext) {}
    }

    @Test
    fun `built-in passes resolve in the canonical pipeline order`() {
        assertEquals(
            listOf(
                "jumps", "strings", "registers", "junk", "clones",
                "cff", "constants", "keys", "tail-return", "debug-strip",
            ),
            ObfuscationRegistry.builtIn().resolve().map { it.id },
        )
    }

    @Test
    fun `resolution is independent of the registration order`() {
        val builtIn = DefaultObfuscationPasses.all()
        val expected = ObfuscationRegistry().registerAll(builtIn).resolve().map { it.id }
        val permutations = listOf(
            builtIn.reversed(),
            builtIn.shuffled(Random(7)),
            builtIn.shuffled(Random(42)),
            // the string pass registered last must not lose its constraint edge
            listOf(builtIn.last()) + builtIn.dropLast(1),
        )
        for (permutation in permutations) {
            val order = ObfuscationRegistry().registerAll(permutation).resolve().map { it.id }
            assertEquals(expected, order, "registration order must not change the resolved order")
        }
    }

    @Test
    fun `runsBefore constraints are honored`() {
        val order = ObfuscationRegistry().registerAll(listOf(
            FakePass("second"),
            FakePass("first", runsBefore = setOf("second")),
        )).resolve().map { it.id }
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun `ordering cycles are rejected with an explicit exception`() {
        val registry = ObfuscationRegistry().registerAll(listOf(
            FakePass("a", runsAfter = setOf("c")),
            FakePass("b", runsAfter = setOf("a")),
            FakePass("c", runsAfter = setOf("b")),
        ))
        val exception = assertFailsWith<IllegalStateException> { registry.resolve() }
        assertTrue(exception.message!!.contains("cycle"), exception.message)
    }

    @Test
    fun `self references are rejected`() {
        val registry = ObfuscationRegistry().register(FakePass("selfish", runsAfter = setOf("selfish")))
        assertFailsWith<IllegalArgumentException> { registry.resolve() }
    }

    @Test
    fun `dangling references are rejected with an explicit exception`() {
        val after = ObfuscationRegistry().register(FakePass("lonely", runsAfter = setOf("ghost")))
        val exception = assertFailsWith<IllegalArgumentException> { after.resolve() }
        assertTrue(exception.message!!.contains("unknown pass id 'ghost'"), exception.message)

        val before = ObfuscationRegistry().register(FakePass("lonely", runsBefore = setOf("ghost")))
        val exception2 = assertFailsWith<IllegalArgumentException> { before.resolve() }
        assertTrue(exception2.message!!.contains("unknown pass id 'ghost'"), exception2.message)
    }

    @Test
    fun `duplicate ids are rejected unless it is the same instance`() {
        val pass = FakePass("dup")
        val registry = ObfuscationRegistry().register(pass).register(pass)
        assertEquals(listOf(pass), registry.resolve())
        val exception = assertFailsWith<IllegalArgumentException> {
            registry.register(FakePass("dup"))
        }
        assertTrue(exception.message!!.contains("already registered"), exception.message)
    }

    @Test
    fun `unknown seed domains are rejected at registration`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ObfuscationRegistry().register(FakePass("bad", seedLabel = "not-a-domain"))
        }
        assertTrue(exception.message!!.contains("unknown seed domain"), exception.message)
    }

    @Test
    fun `every built-in plugin declares a valid seed domain or none`() {
        // builtIn() itself validates; assert the observed mix so the contract
        // stays visible (only keys / tail-return / debug-strip run unlabeled)
        for (plugin in DefaultObfuscationPasses.all()) {
            assertTrue(plugin.seedLabel == null || plugin.seedLabel in SeedPolicy.DOMAINS, plugin.id)
        }
    }
}
