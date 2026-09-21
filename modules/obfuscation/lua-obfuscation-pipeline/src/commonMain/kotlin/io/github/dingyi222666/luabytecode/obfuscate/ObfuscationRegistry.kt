// The DI registry of obfuscation pass plugins plus the default built-in
// assembly. The registry is the ONLY place that knows the pass graph: plugins
// declare ordering constraints by id, resolution derives the execution order
// from them deterministically (independent of the registration order), and
// neither the orchestrator nor any pass hardcodes the sequence anymore.
package io.github.dingyi222666.luabytecode.obfuscate

/**
 * Registry of [ObfuscationPassPlugin]s. Composition roots (the hosts) decide
 * which plugins take effect by registering them; a pass that is not
 * registered never runs, even when its config section is set.
 *
 * Registration rules:
 *  - ids are unique; registering the same instance again is a no-op, while a
 *    second, different plugin with an already registered id is rejected;
 *  - a non-null [ObfuscationPassPlugin.seedLabel] must be a known
 *    [SeedPolicy.DOMAINS] key (domain separation is what keeps pass streams
 *    independent — VM-3).
 *
 * [resolve] validates the declared constraint graph and returns the execution
 * order: a stable topological order over `runsAfter` / `runsBefore` edges with
 * ties broken by id, so the same constraint set always resolves to the same
 * sequence. Dangling references and cycles are rejected with explicit
 * exceptions (typo protection: a silently dropped edge could reorder passes).
 * Consequence for partial assemblies: a host that registers only a SUBSET of
 * the chain must keep the graph consistent — removing a pass also removes
 * everything that declared it in `runsAfter`/`runsBefore`, otherwise the
 * dangling check fires with the exact reference in its message.
 */
class ObfuscationRegistry private constructor(
    private val passesById: LinkedHashMap<String, ObfuscationPassPlugin>,
) {
    constructor() : this(LinkedHashMap())

    /** Registers [plugin]; see the class documentation for the rules. */
    fun register(plugin: ObfuscationPassPlugin): ObfuscationRegistry {
        val existing = passesById[plugin.id]
        if (existing === plugin) return this
        require(existing == null) {
            "a different obfuscation pass with id '${plugin.id}' is already registered"
        }
        val label = plugin.seedLabel
        if (label != null) {
            require(label in SeedPolicy.DOMAINS) {
                "obfuscation pass '${plugin.id}' declares unknown seed domain '$label' " +
                    "(known: ${SeedPolicy.DOMAINS.keys.sorted()})"
            }
        }
        passesById[plugin.id] = plugin
        return this
    }

    /** Registers every plugin of [plugins] (see [register]). */
    fun registerAll(plugins: Collection<ObfuscationPassPlugin>): ObfuscationRegistry {
        plugins.forEach(::register)
        return this
    }

    /** Ids currently registered, in registration order (diagnostics only). */
    val registeredPassIds: Set<String> get() = passesById.keys

    /**
     * The deterministic execution order: a topological sort of the declared
     * constraints, ties broken lexicographically by pass id. The result
     * depends ONLY on the constraint graph — permuting the registration order
     * cannot change it (pinned by tests). A pass whose section is disabled at
     * run time is skipped later; removing it from a total order keeps the
     * relative order of the rest, so enabled passes never observe the skip.
     */
    fun resolve(): List<ObfuscationPassPlugin> {
        for (plugin in passesById.values) {
            for (reference in plugin.runsAfter + plugin.runsBefore) {
                require(reference != plugin.id) {
                    "obfuscation pass '${plugin.id}' references itself"
                }
                require(passesById.containsKey(reference)) {
                    "obfuscation pass '${plugin.id}' references unknown pass id '$reference' " +
                        "(registered: ${passesById.keys.sorted()})"
                }
            }
        }
        // Predecessor set per pass: everything it declares via runsAfter plus
        // everything that declares it via runsBefore.
        val predecessors = HashMap<String, Set<String>>()
        for (target in passesById.values) {
            val direct = target.runsAfter.toMutableSet()
            for (other in passesById.values) {
                if (target.id in other.runsBefore) direct += other.id
            }
            predecessors[target.id] = direct
        }
        val pending = passesById.values.sortedBy { it.id }
        val emitted = LinkedHashMap<String, ObfuscationPassPlugin>()
        while (emitted.size < pending.size) {
            val ready = pending.firstOrNull { candidate ->
                candidate.id !in emitted &&
                    predecessors.getValue(candidate.id).all { it in emitted }
            }
            if (ready == null) {
                val stuck = pending.filter { it.id !in emitted }.map { it.id }.sorted()
                throw IllegalStateException(
                    "obfuscation pass ordering cannot be resolved (cycle among: $stuck)",
                )
            }
            emitted[ready.id] = ready
        }
        return emitted.values.toList()
    }

    companion object {
        /**
         * A registry preloaded with every built-in pass plugin. Hosts may use
         * it as-is (the pipeline skips passes whose config section is null) or
         * assemble a custom one; the built-in list's order is irrelevant —
         * [resolve] canonicalizes it.
         */
        fun builtIn(): ObfuscationRegistry = ObfuscationRegistry().registerAll(DefaultObfuscationPasses.all())
    }
}

/** The default built-in pass set every host assembles. */
object DefaultObfuscationPasses {
    fun all(): List<ObfuscationPassPlugin> = listOf(
        LuaJumpIndirectionPass,
        LuaStringEncryptionPass,
        LuaRegisterObfuscationPass,
        LuaJunkCodePass,
        LuaBlockCloningPass,
        LuaControlFlowFlatteningPass,
        LuaConstantEncryptionPass,
        LuaKeyRenamingPass,
        LuaTailReturnPass,
        LuaDebugStripPass,
    )
}
