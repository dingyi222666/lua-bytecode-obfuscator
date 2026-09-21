package io.github.dingyi222666.luabytecode.platform

/**
 * Mutable set with **reference (identity) equality** — the pure common
 * replacement of `java.util.Collections.newSetFromMap(java.util.IdentityHashMap())`.
 *
 * Semantics that matter to the passes:
 *  * `add` / `contains` / `remove` compare keys with `===`, never `equals`;
 *  * iteration visits elements in insertion order (the JDK identity map
 *    iterated in bucket order — every current consumer only does membership
 *    tests or order-insensitive walks, which is asserted by the tests).
 *
 * Backed by a linear scan: the sets hold prototypes of a single chunk, so the
 * O(n) membership test is negligible next to the pass work around it.
 */
class IdentitySet<T : Any> : AbstractMutableSet<T>() {

    private val elements = ArrayList<T>()

    override val size: Int
        get() = elements.size

    override fun add(element: T): Boolean {
        if (contains(element)) return false
        elements.add(element)
        return true
    }

    override fun remove(element: T): Boolean {
        val index = elements.indexOfFirst { it === element }
        if (index < 0) return false
        elements.removeAt(index)
        return true
    }

    override fun contains(element: T): Boolean = elements.any { it === element }

    override fun clear() {
        elements.clear()
    }

    override fun iterator(): MutableIterator<T> =
        object : MutableIterator<T> {
            private var index = 0
            private var canRemove = false

            override fun hasNext(): Boolean = index < elements.size

            override fun next(): T {
                if (!hasNext()) throw NoSuchElementException()
                canRemove = true
                return elements[index++]
            }

            override fun remove() {
                check(canRemove) { "next() has not been called" }
                elements.removeAt(--index)
                canRemove = false
            }
        }
}

/**
 * Mutable map with **reference (identity) equality** on keys — the pure common
 * replacement of `java.util.IdentityHashMap` (there is no identity hashing in
 * the common stdlib, so lookups are a linear `===` scan; the maps hold one
 * entry per prototype at most).
 *
 * Key semantics preserved from the JDK map: two keys are the same entry iff
 * they are the same object. Values compare with `equals` for
 * [containsValue], exactly like the JDK implementation. Iteration order is
 * insertion order.
 */
class IdentityMap<K : Any, V> : MutableMap<K, V> {

    private val keyList = ArrayList<K>()
    private val valueList = ArrayList<V?>()

    private fun indexOfKey(key: K): Int = keyList.indexOfFirst { it === key }

    override val size: Int
        get() = keyList.size

    override fun isEmpty(): Boolean = keyList.isEmpty()

    override fun containsKey(key: K): Boolean = indexOfKey(key) >= 0

    override fun containsValue(value: V): Boolean = valueList.contains(value)

    override fun get(key: K): V? {
        val index = indexOfKey(key)
        @Suppress("UNCHECKED_CAST")
        val result = if (index >= 0) valueList[index] as V else null
        return result
    }

    override fun put(key: K, value: V): V? {
        val index = indexOfKey(key)
        if (index >= 0) {
            @Suppress("UNCHECKED_CAST")
            val previous = valueList[index] as V
            valueList[index] = value
            return previous
        }
        keyList.add(key)
        valueList.add(value)
        return null
    }

    override fun remove(key: K): V? {
        val index = indexOfKey(key)
        if (index < 0) return null
        keyList.removeAt(index)
        @Suppress("UNCHECKED_CAST")
        val previous = valueList.removeAt(index) as V
        return previous
    }

    override fun putAll(from: Map<out K, V>) {
        for ((key, value) in from) put(key, value)
    }

    override fun clear() {
        keyList.clear()
        valueList.clear()
    }

    override val keys: MutableSet<K>
        get() = object : AbstractMutableSet<K>() {
            override val size: Int
                get() = this@IdentityMap.size
            override fun add(element: K): Boolean = this@IdentityMap.put(element, null as V?) == null
            override fun remove(element: K): Boolean = this@IdentityMap.remove(element) != null
            override fun contains(element: K): Boolean = containsKey(element)
            override fun clear() = this@IdentityMap.clear()
            override fun iterator(): MutableIterator<K> =
                object : MutableIterator<K> {
                    private var index = 0
                    override fun hasNext(): Boolean = index < keyList.size
                    override fun next(): K = keyList[index++]
                    override fun remove() {
                        this@IdentityMap.remove(keyList[--index])
                    }
                }
        }

    override val values: MutableCollection<V>
        get() = object : AbstractMutableCollection<V>() {
            override val size: Int
                get() = this@IdentityMap.size
            override fun add(element: V): Boolean = throw UnsupportedOperationException()
            override fun iterator(): MutableIterator<V> =
                object : MutableIterator<V> {
                    private var index = 0
                    override fun hasNext(): Boolean = index < keyList.size
                    @Suppress("UNCHECKED_CAST")
                    override fun next(): V {
                        if (index >= keyList.size) throw NoSuchElementException()
                        val result = valueList[index++] as V
                        return result
                    }
                    override fun remove() {
                        this@IdentityMap.remove(keyList[--index])
                    }
                }
        }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = object : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
            override val size: Int
                get() = this@IdentityMap.size
            override fun add(element: MutableMap.MutableEntry<K, V>): Boolean =
                throw UnsupportedOperationException()
            override fun contains(element: MutableMap.MutableEntry<K, V>): Boolean {
                val own = this@IdentityMap.get(element.key)
                return own != null && own == element.value
            }
            override fun remove(element: MutableMap.MutableEntry<K, V>): Boolean =
                this@IdentityMap.remove(element.key) != null
            override fun clear() = this@IdentityMap.clear()
            override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> =
                object : MutableIterator<MutableMap.MutableEntry<K, V>> {
                    private var index = 0
                    override fun hasNext(): Boolean = index < keyList.size
                    override fun next(): MutableMap.MutableEntry<K, V> {
                        if (index >= keyList.size) throw NoSuchElementException()
                        return IdentityEntry(index++)
                    }
                    override fun remove() {
                        this@IdentityMap.remove(keyList[--index])
                    }
                }
        }

    private inner class IdentityEntry(
        private var index: Int,
    ) : MutableMap.MutableEntry<K, V> {
        override val key: K
            get() = keyList[index]
        @Suppress("UNCHECKED_CAST")
        override var value: V
            get() = valueList[index] as V
            set(value) {
                valueList[index] = value
            }
        override fun hashCode(): Int = key.hashCode()
        override fun equals(other: Any?): Boolean =
            other is MutableMap.MutableEntry<*, *> && other.key == key && other.value == value
    }
}
