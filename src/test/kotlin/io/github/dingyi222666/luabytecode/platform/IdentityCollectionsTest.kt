package io.github.dingyi222666.luabytecode.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reference (identity) semantics are load-bearing for the passes: two distinct
 * `LuaPrototype` instances can be `equals`-equal (data classes) yet must stay
 * separate keys, which is exactly what the replaced `IdentityHashMap` /
 * `Collections.newSetFromMap` provided. These tests pin the semantics with
 * deliberately equal-but-distinct keys.
 */
class IdentityCollectionsTest {

    private class Key(val name: String) // reference equality: no equals/hashCode override

    private data class EqualValue(val n: Int)

    @Test
    fun `set keeps equal but distinct instances distinct`() {
        val a = EqualValue(1)
        val b = EqualValue(1)
        assertTrue(a == b && a !== b)
        val set = IdentitySet<EqualValue>()
        set.add(a)
        set.add(b)
        assertEquals(2, set.size)
        assertTrue(set.contains(a))
        assertTrue(set.contains(b))
    }

    @Test
    fun `set add returns false for the same instance`() {
        val a = Key("a")
        val set = IdentitySet<Key>()
        assertTrue(set.add(a))
        assertFalse(set.add(a))
        assertEquals(1, set.size)
    }

    @Test
    fun `set remove and iterate`() {
        val a = Key("a")
        val b = Key("b")
        val set = IdentitySet<Key>()
        set.add(a)
        set.add(b)
        set.remove(a)
        assertEquals(listOf(b), set.toList())
    }

    @Test
    fun `map keeps equal but distinct instances distinct`() {
        val a = EqualValue(1)
        val b = EqualValue(1)
        val map = IdentityMap<EqualValue, String>()
        map[a] = "A"
        map[b] = "B"
        assertEquals("A", map[a])
        assertEquals("B", map[b])
        assertEquals(2, map.size)
    }

    @Test
    fun `map put replaces and returns previous`() {
        val a = Key("a")
        val map = IdentityMap<Key, Int>()
        assertNull(map.put(a, 1))
        assertEquals(1, map.put(a, 2))
        assertEquals(2, map[a])
        assertEquals(1, map.size)
    }

    @Test
    fun `map getOrPut behaves like the jdk usage`() {
        val a = Key("a")
        val map = IdentityMap<Key, MutableList<Int>>()
        map.getOrPut(a) { mutableListOf() }.add(7)
        map.getOrPut(a) { mutableListOf() }.add(9)
        assertEquals(listOf(7, 9), map[a])
        assertEquals(1, map.size)
    }

    @Test
    fun `map iteration is insertion ordered and remove works`() {
        val k1 = Key("1")
        val k2 = Key("2")
        val k3 = Key("3")
        val map = IdentityMap<Key, Int>()
        map[k3] = 3
        map[k1] = 1
        map[k2] = 2
        assertEquals(listOf(k3, k1, k2), map.keys.toList())
        assertEquals(listOf(3, 1, 2), map.values.toList())
        map.remove(k1)
        assertEquals(listOf(k3, k2), map.keys.toList())
        assertFalse(map.containsKey(k1))
    }

    @Test
    fun `map entries view supports iteration and removal`() {
        val k1 = Key("1")
        val k2 = Key("2")
        val map = IdentityMap<Key, Int>()
        map[k1] = 1
        map[k2] = 2
        val iterator = map.entries.iterator()
        iterator.next()
        iterator.remove()
        assertEquals(1, map.size)
        assertEquals(k2, map.keys.single())
    }

    @Test
    fun `map containsValue uses value equality`() {
        val map = IdentityMap<Key, EqualValue>()
        map[Key("k")] = EqualValue(5)
        assertTrue(map.containsValue(EqualValue(5)))
    }
}
