package io.github.dingyi222666.luabytecode.obfuscate

import io.github.dingyi222666.luabytecode.platform.IdentitySet
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.edit.clearDebugInfo

/** Runs the configured passes over a chunk, in place. */
object LuaObfuscator {

    fun obfuscate(
        chunk: LuaChunk,
        config: ObfuscationConfig = ObfuscationConfig.BALANCED,
    ): ObfuscationReport {
        // PRED-2/LEAK-5: resolve the master seed once per run and stamp it into
        // every pass config whose own `seed` field is still at its default. A
        // pass config with an explicit seed keeps it (granular override, same
        // precedence rule as the CLI flag merge). `null` master => per-run
        // SecureRandom; `0` => legacy content derivation (SeedPolicy docs).
        val master = SeedPolicy.masterSeed(config.masterSeed, chunk)
        val stamped = config.copy(
            stringEncryption = config.stringEncryption?.let {
                if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "strings")) else it
            },
            constantEncryption = config.constantEncryption?.let {
                if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "constants")) else it
            },
            jumpIndirection = config.jumpIndirection?.let {
                if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "jumps")) else it
            },
            junkCode = config.junkCode?.let {
                if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "junk")) else it
            },
            blockCloning = config.blockCloning?.let {
                if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "clones")) else it
            },
            controlFlowFlattening = config.controlFlowFlattening?.let {
                if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "flatten")) else it
            },
            registerObfuscation = config.registerObfuscation?.let {
                if (it.seed == 0L) it.copy(seed = SeedPolicy.passSeed(master, "registers")) else it
            },
        )
        val cfg = stamped
        val moduleContext = LuaObfuscationModuleContext(chunk, cfg, master)
        val moduleResults = ArrayList<LuaObfuscationModuleResult>()
        cfg.modules.filter { it.phase == ObfuscationPhase.BEFORE_CORE }
            .forEach { moduleResults += it.apply(moduleContext) }
        // Order matters: the jump pass runs first so the string stubs it creates
        // afterwards are plain, untrampolined blocks, and junk guards every jump
        // including the stubs' (all three append blocks after a terminator).
        val jumps = cfg.jumpIndirection?.let { LuaJumpIndirection.apply(chunk, it) }
        val strings = cfg.stringEncryption?.let { LuaStringEncryptor.encrypt(chunk, it) }
        // Pass R runs after the string stubs exist (they are plain instructions the
        // register permutation has to see) and before junk / cloning / flattening
        // (those derive their own register budgets from the post-R layout).
        val registers = cfg.registerObfuscation?.let { LuaRegisterObfuscator.apply(chunk, it) }
        val junk = cfg.junkCode?.let { LuaJunkCode.apply(chunk, it) }
        val clones = cfg.blockCloning?.let { LuaBlockCloning.apply(chunk, it) }
        // Flattening rebuilds the whole instruction stream, so it runs last. Running the
        // string layer *after* it made every counter fixture DUMPFAIL (goal round 64), so the
        // order stays; the remaining `features_55` case is a false-safe save slot (docs 14.45).
        var flattening = cfg.controlFlowFlattening?.let {
            val decoders = strings?.decoderPrototypeIndex?.takeIf { index -> index >= 0 }
                ?.let { index -> chunk.mainPrototype.nestedPrototypes[index].allPrototypes().toSet() }
                ?: emptySet()
            LuaControlFlowFlattening.apply(chunk, it, strings?.stubPrototypes ?: emptySet(), decoders)
        }
        // Pass K runs AFTER the flattener: (a) the flattener's rematerialisation
        // re-derives integer constants at run time and LEAVES the plaintext in
        // the pool — running K last lets it encrypt whatever still survives as a
        // live LOADK site; (b) the kget decoder prototype (and its stub blocks)
        // are appended to the final layout, so no earlier pass can rebuild them.
        // Round 1 lesson: when the decoder existed DURING flattening, its rebuilt
        // body decoded garbage — keeping the decoder out of the flattener's view
        // entirely is the robust fix.
        if (flattening?.prototypes?.any { it.status == "INPUT_REJECTED" } == true) {
            throw LuaBytecodeFormatException("CFF rejected an invalid input instruction structure: " +
                flattening.prototypes.filter { it.status == "INPUT_REJECTED" }.joinToString { "${it.prototypeIndex}: ${it.reasons}" })
        }
        val constants = cfg.constantEncryption?.let { constantConfig ->
            val protected = IdentitySet<LuaPrototype>()
            val beforeConstants = chunk.prototypes
            var inlineConstants = 0
            var inlinePrototypes = 0
            var inlineSites = 0
            val updatedRecords = ArrayList<CffPrototypeReport>()
            for (record in flattening?.prototypes.orEmpty()) {
                val proof = record.proof
                if (proof == null) { updatedRecords += record; continue }
                val prototype = beforeConstants[record.prototypeIndex]
                protected += prototype
                val expansion = CffConstantExpansion.apply(prototype, proof, constantConfig)
                if (expansion != null) {
                    inlineConstants += expansion.constants
                    inlineSites += expansion.sites
                    inlinePrototypes++
                    updatedRecords += record.copy(proof = expansion.proof,
                        verifiedControlPaths = expansion.paths, verificationSteps = expansion.steps)
                } else updatedRecords += record
            }
            flattening = flattening?.copy(prototypes = updatedRecords)
            // The legacy decoder uses CALL and cannot enter a live control packet.
            // Flattened prototypes use only the verified local expansion above.
            LuaConstantEncryptor.encrypt(chunk, constantConfig, protected).let {
                if (it.decoderPrototypeIndex >= 0) {
                    val at = if (chunk.mainPrototype.code.first().opcodeName == "VARARGPREP") 1 else 0
                    fun moved(pc: Int) = if (pc < at) pc else pc + 2
                    flattening = flattening?.copy(prototypes = flattening!!.prototypes.map { record ->
                        val proof = record.proof
                        if (record.prototypeIndex != 0 || proof == null) record else record.copy(proof = proof.copy(
                            controlEntries = proof.controlEntries.map { (pc, target) -> moved(pc) to target },
                            destinationPcs = proof.destinationPcs.map(::moved)))
                    })
                }
                it.copy(encryptedConstants = it.encryptedConstants + inlineConstants,
                    rewrittenSites = it.rewrittenSites + inlineSites,
                    patchedPrototypes = it.patchedPrototypes + inlinePrototypes)
            }
        }
        // LEAK-1 key half runs after K (both see the final layout; keys are a
        // shared-reference surface and never enter K's encryption set — the two
        // passes are orthogonal by construction).
        val keys = cfg.keyRenaming?.let { LuaKeyRenaming.apply(chunk, it) }

        // Every pass has run; some flattener / junk block layouts end in a `JMP` (the
        // dispatcher back edge), which is valid bytecode but breaks the "every prototype
        // ends with a RETURN" invariant unluac validates. The appended RETURN is
        // unreachable (the previous instruction is a redirect), so this cannot change
        // semantics -- it only makes the chunk acceptable to official-tooling again.
        val tailReturn = LuaTailReturn.apply(chunk)
        if (config.stripDebugInfo) {
            chunk.clearDebugInfo()
            // `clearDebugInfo` drops the synthetic upvalue names `LuaTailReturn` just
            // installed; re-run the naming half after the strip so stripped chunks stay
            // parseable by unluac (names are debug metadata, bytecode semantics equal).
            LuaTailReturn.nameUpvalues(chunk)
        }
        cfg.modules.filter { it.phase == ObfuscationPhase.AFTER_CORE }
            .forEach { moduleResults += it.apply(moduleContext) }
        return ObfuscationReport(
            stringEncryption = strings,
            keyRenaming = keys,
            constantEncryption = constants,
            jumpIndirection = jumps,
            junkCode = junk,
            blockCloning = clones,
            controlFlowFlattening = flattening,
            strippedDebugInfo = config.stripDebugInfo,
            tailReturn = tailReturn,
            registerObfuscation = registers,
            modules = moduleResults,
        )
    }
}
