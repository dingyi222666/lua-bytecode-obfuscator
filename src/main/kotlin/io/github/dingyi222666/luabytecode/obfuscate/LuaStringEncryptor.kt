package io.github.dingyi222666.luabytecode.obfuscate
import io.github.dingyi222666.luabytecode.platform.Platform
import io.github.dingyi222666.luabytecode.platform.IdentityMap
import io.github.dingyi222666.luabytecode.platform.IdentitySet
import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.platform.hex2
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaInstructionFactory

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaInstruction
import io.github.dingyi222666.luabytecode.bytecode.LuaOpcodes
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.bytecode.LuaString
import io.github.dingyi222666.luabytecode.bytecode.LuaUpvalue
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTo
import io.github.dingyi222666.luabytecode.bytecode.analysis.loadKSites
import io.github.dingyi222666.luabytecode.bytecode.analysis.referencedConstantIndices
import io.github.dingyi222666.luabytecode.bytecode.analysis.relocated
import io.github.dingyi222666.luabytecode.bytecode.analysis.setAbsoluteLines
import io.github.dingyi222666.luabytecode.bytecode.edit.clearDebugInfo
import io.github.dingyi222666.luabytecode.bytecode.edit.currentAbsoluteLines
import io.github.dingyi222666.luabytecode.bytecode.edit.LuaPrototypeScope
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader

/**
 * Tunables of [LuaStringEncryptor].
 */
data class StringEncryptionConfig(
    /**
     * Only string constants of at least this many bytes are encrypted.
     * Short literals are usually identifiers, keys or format fragments whose
     * decryption call would cost more than it protects.
     */
    val minLength: Int = 6,

    /** Encrypt global/field/method operands as well as literal loads. */
    val encryptKeys: Boolean = false,

    /** Upper bound of encrypted constants (`0` = unlimited). */
    val maxConstants: Int = 0,

    /**
     * Seed of the key generator. `0` derives a deterministic seed from the chunk
     * content, so protecting the same chunk twice yields the same output.
     */
    val seed: Long = 0,
)

/** Statistics of one [LuaStringEncryptor.encrypt] run. */
data class StringEncryptionResult(
    /** Number of constants that were replaced by their encrypted bytes. */
    val encryptedConstants: Int,
    val loweredOperandSites: Int = 0,
    val remainingPlaintextStrings: Int = 0,
    /** Number of `LOADK` sites that now call the decoder. */
    val rewrittenSites: Int,
    /** Number of prototypes that received decrypt blocks. */
    val patchedPrototypes: Int,
    /**
     * The prototypes that now contain a decryption stub. The flattener uses this to decide
     * whether its save slot has to be chosen with the strict "never read" rule: a stub can
     * use a register that is dead at the flattener's dynamic instructions but live on the
     * stub's own path (goal round 65).
     */
    val stubPrototypes: Set<io.github.dingyi222666.luabytecode.bytecode.LuaPrototype> = emptySet(),
    /** Index of the injected decoder prototype in the main prototype, `-1` when unused. */
    val decoderPrototypeIndex: Int,
)

/**
 * Constant pool / string pool encryption.
 *
 * Every eligible string constant is replaced **in place** by its encrypted bytes
 * (so constant indices never move and no other instruction has to be fixed up),
 * and each `LOADK` site is rewritten into a jump to an appended stub that calls
 * the bundled decoder closure:
 *
 * ```text
 * [site]      JMP  -> stub            ; the original LOADK is gone
 * ...
 * [stub]      GETUPVAL scratch, decoderUpvalue
 *             LOADK    scratch+1, K[encrypted]
 *             LOADK    scratch+2, K[seed]
 *             CALL     scratch, 3, 2
 *             MOVE     destination, scratch
 *             JMP      -> site + 1
 * ```
 *
 * Properties of this design:
 *  * **no instruction ever shifts**, so the original jump offsets stay valid and
 *    a constant that is *shared* (used as a field/global key somewhere else) is
 *    never touched — see [referencedConstantIndices];
 *  * the stub only uses registers above the original `maxStackSize`
 *    (the register allocator high water mark), so no live value is clobbered;
 *  * `JMP` and the conditional skip of the previous comparison compose exactly
 *    as before: skipping the site also skips the decryption;
 *  * the decoder is a **precompiled prototype compiled by real luac**
 *    (`tools/decode.lua` -> `resources/obfuscator/decode_<version>.luac`),
 *    injected as an extra nested prototype and reached through a fresh upvalue
 *    of the surrounding closures, so no global is polluted.
 *
 * The decoder algorithm itself stays visible inside the chunk: hiding it is the
 * job of the VM based protection layer described in
 * `docs/obfuscation-algorithms.md`.
 */
object LuaStringEncryptor {

    /** `LOADK` decoded value slots are 3 above the high water mark. */
    private const val SCRATCH_REGISTERS = 3

    /**
     * Encrypts the eligible string constants of [chunk] in place.
     *
     * @return statistics about what was rewritten.
     */
    fun encrypt(
        chunk: LuaChunk,
        config: StringEncryptionConfig = StringEncryptionConfig(),
    ): StringEncryptionResult {
        val candidate = chunk.copy(mainPrototype = chunk.mainPrototype.detachedCopy())
        val result = encryptCandidate(candidate, config)
        val patchedIndices = candidate.prototypes.withIndex()
            .filter { (_, p) -> result.stubPrototypes.any { it === p } }.map { it.index }
        chunk.mainPrototype.commitFrom(candidate.mainPrototype)
        val committed = chunk.prototypes
        return result.copy(stubPrototypes = IdentitySet<LuaPrototype>().apply { patchedIndices.forEach { add(committed[it]) } })
    }

    private fun encryptCandidate(chunk: LuaChunk, config: StringEncryptionConfig): StringEncryptionResult {
        val main = chunk.mainPrototype
        val lowered = if (config.encryptKeys) LuaStringOperandLowering.apply(chunk, config) else 0
        val sites = collectSites(chunk, config)
        if (sites.isEmpty()) {
            return StringEncryptionResult(
                encryptedConstants = 0,
                loweredOperandSites = lowered,
                remainingPlaintextStrings = chunk.prototypes.sumOf { p -> p.constants.count { it is LuaConstant.Str } },
                rewrittenSites = 0,
                patchedPrototypes = 0,
                decoderPrototypeIndex = -1,
            )
        }
        if (main.upvalues.size > 1) {
            throw LuaBytecodeFormatException(
                "string encryption expects a main chunk with at most one upvalue (_ENV), " +
                    "found ${main.upvalues.size}; the decoder plumbing cannot be forwarded",
            )
        }

        val random = ObfRandom(if (config.seed != 0L) config.seed else contentSeed(chunk))
        val version = chunk.version
        // Independent full-period LCG parameters per chunk.
        val cipherSpec = LuaStringCipher.Spec.random(
            ObfRandom((if (config.seed != 0L) config.seed else contentSeed(chunk)) xor 0x5EED1234L),
        )

        // The decoder needs `string` / `table`, so the main closure must capture _ENV.
        val envIndex = ensureEnvUpvalue(main)

        // Inject the decoder prototype: appending keeps existing CLOSURE indices valid.
        val decoder = loadDecoder(version, envIndex, cipherSpec)
        val decoderIndex = main.nestedPrototypes.size
        main.addNested(decoder)

        // Upvalue slots: the main chunk hosts the decoder, every ancestor of a patched
        // prototype forwards it, so a nested stub can reach it through `GETUPVAL`.
        val needsSlot = IdentityMap<LuaPrototype, Boolean>()
        needsSlot[main] = true // the main chunk always hosts the decoder
        markSlots(main, sites.keys, needsSlot, emptyList())
        val slotOf = IdentityMap<LuaPrototype, Int>()
        assignSlots(main, null, needsSlot, slotOf)

        var encrypted = 0
        var rewritten = 0
        for ((prototype, prototypeSites) in sites) {
            val slot = slotOf[prototype] ?: continue
            val outcome = rewrite(prototype, prototypeSites, slot, random, cipherSpec)
            encrypted += outcome.encrypted
            rewritten += outcome.sites
        }

        installDecoderPrologue(main, slotOf.getValue(main), decoderIndex)

        return StringEncryptionResult(
            encryptedConstants = encrypted,
            rewrittenSites = rewritten,
            patchedPrototypes = sites.size,
            decoderPrototypeIndex = decoderIndex,
            stubPrototypes = IdentitySet<LuaPrototype>()
                .apply { addAll(sites.keys) },
            loweredOperandSites = lowered,
            remainingPlaintextStrings = chunk.prototypes.filter { it !== decoder }.sumOf { p ->
                val selected = sites.entries.firstOrNull { it.key === p }?.value.orEmpty().map { it.constantIndex }.toSet()
                p.constants.withIndex().count { (i, c) -> c is LuaConstant.Str && i !in selected }
            },
        )
    }

    // ------------------------------------------------------------------ sites

    private class Site(val pc: Int, val destination: Int, val constantIndex: Int, val width: Int = 1)

    /**
     * Picks the constants to encrypt: string constants of at least
     * [StringEncryptionConfig.minLength] bytes that are only ever `LOADK`ed.
     */
    private fun collectSites(
        chunk: LuaChunk,
        config: StringEncryptionConfig,
    ): Map<LuaPrototype, List<Site>> {
        val result = LinkedHashMap<LuaPrototype, List<Site>>()
        var budget = if (config.maxConstants > 0) config.maxConstants else Int.MAX_VALUE

        for (prototype in chunk.prototypes) {
            if (budget <= 0) {
                break
            }
            val shared = prototype.referencedConstantIndices()
            val sites = ArrayList<Site>()
            val loads = prototype.loadKSites().mapValues { it.value.toMutableList() }.toMutableMap()
            prototype.code.forEachIndexed { pc, i ->
                if (i.opcodeName == "LOADKX") {
                    val extra = prototype.code.getOrNull(pc + 1)
                    require(extra?.opcodeName == "EXTRAARG") { "LOADKX without EXTRAARG" }
                    loads.getOrPut(extra!!.ax) { mutableListOf() } += pc
                }
            }
            for ((constantIndex, pcs) in loads) {
                if (budget <= 0) {
                    break
                }
                if (constantIndex in shared) {
                    continue
                }
                val constant = prototype.constants[constantIndex] as? LuaConstant.Str ?: continue
                if (constant.value.size < config.minLength) {
                    continue
                }
                for (pc in pcs) {
                    sites += Site(pc, prototype.code[pc].a, constantIndex,
                        if (prototype.code[pc].opcodeName == "LOADKX") 2 else 1)
                }
                budget--
            }
            if (sites.isNotEmpty()) {
                result[prototype] = sites
            }
        }
        return result
    }

    // -------------------------------------------------------------- rewriting

    private class RewriteOutcome(val encrypted: Int, val sites: Int)

    private fun rewrite(
        prototype: LuaPrototype,
        sites: List<Site>,
        decoderSlot: Int,
        random: ObfRandom,
        cipherSpec: LuaStringCipher.Spec,
    ): RewriteOutcome {
        val version = prototype.version
        val scratch = prototype.maxStackSize
        require(scratch + SCRATCH_REGISTERS <= 255) { "string decoder exceeds Lua register limit" }
        prototype.maxStackSize = scratch + SCRATCH_REGISTERS

        val lines = prototype.currentAbsoluteLines()

        /** constant index -> (seed, index of the seed constant). */
        val keys = HashMap<Int, Pair<Int, Int>>()
        var encrypted = 0

        for (site in sites) {
            val (seed, seedIndex) = keys.getOrPut(site.constantIndex) {
                val seed = random.nextInt(1, Int.MAX_VALUE)
                val constant = prototype.constants[site.constantIndex] as LuaConstant.Str
                LuaPrototypeScope(prototype).setConstant(site.constantIndex, LuaConstant.Str(
                    LuaString(LuaStringCipher.encrypt(constant.value.bytes, seed, cipherSpec)),
                ))
                val seedIndex = prototype.constants.size
                LuaPrototypeScope(prototype).addConstant(LuaConstant.Integer(seed.toLong()))
                encrypted++
                seed to seedIndex
            }

            val continuation = site.pc + site.width
            if (continuation >= prototype.code.size) {
                throw LuaBytecodeFormatException(
                    "cannot encrypt the LOADK at $site.pc: no instruction follows it",
                )
            }

            val blockStart = prototype.code.size
            val block = mutableListOf(
                LuaInstructionFactory.abc(version, "GETUPVAL", scratch, decoderSlot, 0))
            fun load(register: Int, index: Int) {
                val limit = if (version == LuaBytecodeVersion.LUA_5_3) 0x3ffff else 0x1ffff
                if (index <= limit) block += LuaInstructionFactory.abx(version, "LOADK", register, index)
                else {
                    block += LuaInstructionFactory.abx(version, "LOADKX", register, 0)
                    block += LuaInstructionFactory.ax(version, "EXTRAARG", index)
                }
            }
            load(scratch + 1, site.constantIndex)
            load(scratch + 2, seedIndex)
            block += LuaInstructionFactory.abc(version, "CALL", scratch, 3, 2)
            block += LuaInstructionFactory.abc(version, "MOVE", site.destination, scratch, 0)
            block += jumpTo(version, blockStart + block.size, continuation)
            LuaPrototypeScope(prototype).apply {
                val line = lines?.getOrElse(site.pc) { prototype.lineDefined }
                appendAll(block, line?.let { List(block.size) { it } })
                set(site.pc, jumpTo(version, site.pc, blockStart))
                if (site.width == 2) set(site.pc + 1, jumpTo(version, site.pc + 1, continuation))
            }
        }
        return RewriteOutcome(encrypted, sites.size)
    }

    /** Insert initialization after VARARGPREP, relocating all original control edges. */
    private fun installDecoderPrologue(
        main: LuaPrototype,
        decoderSlot: Int,
        decoderPrototypeIndex: Int,
    ) {
        val version = main.version
        val scratch = main.maxStackSize
        require(scratch + SCRATCH_REGISTERS <= 255) { "string prologue exceeds Lua register limit" }
        main.maxStackSize = scratch + SCRATCH_REGISTERS
        // The main chunk may only declare upvalues that point into its *own* stack
        // (`instack = 1`), like `_ENV` does. Declaring `instack = 0` here produced a
        // structurally invalid chunk (unluac: `func.parent is null` NPE) and was
        // mistaken for decompiler resistance -- adversarial audit, goal round 26.
        if (decoderSlot < main.upvalues.size) {
            main.upvalues[decoderSlot] = io.github.dingyi222666.luabytecode.bytecode.LuaUpvalue(
                inStack = true,
                index = scratch,
                kind = if (version == io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion.LUA_5_3) {
                    null
                } else {
                    0
                },
            )
        }

        installClosurePrologue(main, scratch, decoderSlot, decoderPrototypeIndex)
    }

    // ------------------------------------------------------------- upvalues

    /** The main chunk captures nothing but `_ENV`; add it when the script has no global. */
    private fun ensureEnvUpvalue(main: LuaPrototype): Int {
        if (main.upvalues.isEmpty()) {
            main.upvalues += LuaUpvalue(inStack = false, index = 0, kind = upvalueKind(main.version))
        }
        return 0
    }

    private fun upvalueKind(version: LuaBytecodeVersion): Int? =
        if (version == LuaBytecodeVersion.LUA_5_3) null else 0

    private fun markSlots(
        prototype: LuaPrototype,
        patched: Set<LuaPrototype>,
        needsSlot: IdentityMap<LuaPrototype, Boolean>,
        ancestors: List<LuaPrototype>,
    ) {
        val chain = ancestors + prototype
        if (patched.any { it === prototype }) {
            chain.forEach { needsSlot[it] = true }
        }
        for (child in prototype.nestedPrototypes) {
            markSlots(child, patched, needsSlot, chain)
        }
    }

    private fun assignSlots(
        prototype: LuaPrototype,
        parentSlot: Int?,
        needsSlot: IdentityMap<LuaPrototype, Boolean>,
        slotOf: IdentityMap<LuaPrototype, Int>,
    ) {
        var slot = parentSlot
        if (needsSlot[prototype] == true) {
            slot = prototype.upvalues.size
            prototype.upvalues += LuaUpvalue(
                inStack = false,
                index = parentSlot ?: 0,
                kind = upvalueKind(prototype.version),
            )
            // debug names must stay parallel to the upvalue list
            if (prototype.upvalueNames.isNotEmpty()) {
                prototype.upvalueNames += null
            }
            slotOf[prototype] = slot
        }
        for (child in prototype.nestedPrototypes) {
            assignSlots(child, slot, needsSlot, slotOf)
        }
    }

    /** Loads the bundled decoder prototype and re-parents its `_ENV` upvalue. */
    private fun loadDecoder(
        version: LuaBytecodeVersion,
        envIndex: Int,
        cipherSpec: LuaStringCipher.Spec,
    ): LuaPrototype {
        val resource = "/obfuscator/decode_" + hex2(version.versionByte) + ".luac"
        val bytes = Platform.bundledChunks.load(resource)
            ?: throw LuaBytecodeFormatException("missing bundled decoder resource $resource")
        val chunk = LuaChunkLoader.load(bytes, version)
        val decoder = chunk.mainPrototype.nestedPrototypes.singleOrNull()
            ?: throw LuaBytecodeFormatException("$resource must contain exactly one decoder prototype")
        if (decoder.upvalues.size != 1) {
            throw LuaBytecodeFormatException(
                "$resource is expected to capture exactly _ENV, found ${decoder.upvalues.size} upvalues",
            )
        }

        // Patch decoder and encryptor with the same per-chunk stream parameters.
        for (index in decoder.constants.indices) {
            val constant = decoder.constants[index]
            if (constant is LuaConstant.Integer) {
                decoder.constants[index] = when (constant.value) {
                    LuaStringCipher.MULTIPLIER -> LuaConstant.Integer(cipherSpec.multiplier)
                    LuaStringCipher.INCREMENT -> LuaConstant.Integer(cipherSpec.increment)
                    else -> constant
                }
            }
        }

        decoder.clearDebugInfo()
        decoder.upvalues.clear()
        decoder.upvalues += LuaUpvalue(inStack = false, index = envIndex, kind = upvalueKind(version))
        return decoder
    }

    // ------------------------------------------------------------- utilities

    private fun opcode(version: LuaBytecodeVersion, name: String): Int =
        LuaOpcodes.opcodeOf(version, name)
            ?: throw LuaBytecodeFormatException("no $name opcode in ${version.displayName}")

    /** Deterministic seed derived from the chunk content. */
    private fun contentSeed(chunk: LuaChunk): Long {
        var hash = 1125899906842597L
        for (prototype in chunk.prototypes) {
            hash = hash * 31 + prototype.constantCount
            hash = hash * 31 + prototype.instructionCount
            for (constant in prototype.constants) {
                hash = hash * 31 + when (constant) {
                    is LuaConstant.Str -> constant.value.bytes.contentHashCode().toLong()
                    is LuaConstant.Integer -> constant.value
                    is LuaConstant.Number -> constant.value.toRawBits()
                    is LuaConstant.Boolean -> if (constant.value) 1L else 0L
                    LuaConstant.Nil -> 17L
                }
            }
        }
        return hash
    }
}
