package io.github.dingyi222666.luabytecode.obfuscate
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
import io.github.dingyi222666.luabytecode.bytecode.analysis.constantOperands
import io.github.dingyi222666.luabytecode.bytecode.analysis.jumpTo
import io.github.dingyi222666.luabytecode.bytecode.analysis.loadKSites
import io.github.dingyi222666.luabytecode.bytecode.analysis.referencedConstantIndices
import io.github.dingyi222666.luabytecode.bytecode.analysis.relocated
import io.github.dingyi222666.luabytecode.bytecode.analysis.setAbsoluteLines
import io.github.dingyi222666.luabytecode.bytecode.edit.clearDebugInfo
import io.github.dingyi222666.luabytecode.bytecode.edit.currentAbsoluteLines
import io.github.dingyi222666.luabytecode.bytecode.edit.indexOfConstant
import io.github.dingyi222666.luabytecode.bytecode.edit.internConstant
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader

/**
 * Tunables of [LuaConstantEncryptor] (Pass K, `LEAK-2`).
 */
data class ConstantEncryptionConfig(
    /**
     * Upper bound of constants moved into the encrypted blob per chunk
     * (`0` = unlimited).
     */
    val maxConstants: Int = 0,

    /** `0` derives a deterministic seed from the chunk content. */
    val seed: Long = 0,
)

/** Statistics of one [LuaConstantEncryptor.encrypt] run. */
data class ConstantEncryptionResult(
    /** Number of constants that were replaced by their encrypted blob reference. */
    val encryptedConstants: Int,
    /** Number of `LOADK` sites that now call the kget stub. */
    val rewrittenSites: Int,
    /** Number of prototypes that received a kget block. */
    val patchedPrototypes: Int,
    /**
     * The prototypes that now contain a kget stub (same flattener contract as
     * [StringEncryptionResult.stubPrototypes]).
     */
    val stubPrototypes: Set<LuaPrototype> = emptySet(),
    /** Index of the injected kget prototype in the main prototype, `-1` when unused. */
    val decoderPrototypeIndex: Int = -1,
    /**
     * The injected decoder prototype(s). The flattener must NOT rebuild them:
     * its block rewriting silently corrupted decoded values (Round 1), so
     * [LuaObfuscator] forwards this set as the flattener's `protectedPrototypes`.
     */
    val decoderPrototypes: Set<LuaPrototype> = emptySet(),
    /** Candidate sites left unchanged because safe expansion was unavailable. */
    val skippedSites: Int = 0,
)

/**
 * Encrypts LOADK/LOADKX integer, boolean and floating constants as exact tagged
 * eight-byte payloads. RK and opcode immediates remain outside this pass.
 * Decoder calls use a separate register window; protected CFF capsules use the
 * call-free integer expansion. Public seed delivery is not a secrecy claim.
 */
object LuaConstantEncryptor {

    /** `kget` decoded value slots are 3 above the high water mark. */
    private const val SCRATCH_REGISTERS = 4

    /** One type tag plus eight exact little-endian bytes; no float/integer truncation. */
    private const val PAYLOAD_SIZE = 8
    private const val CIPHER_ENTRY_SIZE = PAYLOAD_SIZE + 1
    private const val TAG_INTEGER = 0
    private const val TAG_BOOLEAN = 1
    private const val TAG_NUMBER = 2

    /**
     * Placeholder literals inside `tools/decode_num.lua` / the bundled
     * `decode_num_<version>.luac` templates. They are patched in place with the
     * chunk's random full period parameters, so the well known triple never
     * ships in an artifact (and the template compiles with plain `luac`).
     *
     * `m` and `inc` always live in the constant pool. The `shift` literal is
     * small, so 5.4 / 5.5 `luac` folds it into a `LOADI` immediate: there it is
     * patched in the *code* (the `LOADI` carrying [SHIFT_PLACEHOLDER] becomes a
     * `LOADK` of the pooled shift constant); 5.3 keeps it in the pool.
     */
    private const val M_PLACEHOLDER = 1061988609L
    private const val INC_PLACEHOLDER = 718832137L
    private const val SHIFT_PLACEHOLDER = 11L

    /**
     * Encrypts the eligible Integer / Boolean / Number constants of [chunk].
     *
     * @return statistics about what was rewritten.
     */
    @JvmStatic
    @JvmOverloads
    fun encrypt(
        chunk: LuaChunk,
        config: ConstantEncryptionConfig = ConstantEncryptionConfig(),
        protectedPrototypes: Set<LuaPrototype> = emptySet(),
    ): ConstantEncryptionResult {
        val candidate = chunk.copy(mainPrototype = chunk.mainPrototype.detachedCopy())
        val candidatePrototypes = candidate.prototypes
        val protected = IdentitySet<LuaPrototype>()
        chunk.prototypes.forEachIndexed { i, p ->
            if (protectedPrototypes.any { it === p }) protected += candidatePrototypes[i]
        }
        val result = encryptCandidate(candidate, config, protected)
        val all = candidate.prototypes
        fun indices(set: Set<LuaPrototype>) = all.indices.filter { i -> set.any { it === all[i] } }
        val stubs = indices(result.stubPrototypes)
        val decoders = indices(result.decoderPrototypes)
        chunk.mainPrototype.commitFrom(candidate.mainPrototype)
        val committed = chunk.prototypes
        fun remap(indices: List<Int>): Set<LuaPrototype> =
            IdentitySet<LuaPrototype>().apply {
                indices.forEach { add(committed[it]) }
            }
        return result.copy(stubPrototypes = remap(stubs), decoderPrototypes = remap(decoders))
    }

    private fun encryptCandidate(chunk: LuaChunk, config: ConstantEncryptionConfig,
        protectedPrototypes: Set<LuaPrototype>): ConstantEncryptionResult {
        val main = chunk.mainPrototype
        val sites = collectSites(chunk, config, protectedPrototypes)
        if (sites.isEmpty()) {
            return ConstantEncryptionResult(0, 0, 0, emptySet(), -1)
        }
        // The upvalue budget is intentionally generous: when other passes ran
        // first (string decoding and other closure-based helpers) the main chunk
        // legitimately carries several anonymous upvalues. Pass K only needs
        // `ensureEnvUpvalue` (index 0) to resolve `string` inside the decoder.
        if (main.upvalues.size > 250) {
            throw LuaBytecodeFormatException(
                "constant encryption needs an upvalue slot, main already has ${main.upvalues.size}",
            )
        }

        val random = ObfRandom(if (config.seed != 0L) config.seed else contentSeed(chunk))
        val version = chunk.version
        val cipherSpec = ConstCipher.Spec.random(
            ObfRandom((if (config.seed != 0L) config.seed else contentSeed(chunk)) xor 0x5EED4E0L),
        )

        val envIndex = ensureEnvUpvalue(main)

        // Inject the kget prototype (appending keeps existing CLOSURE indices valid).
        val decoder = loadDecoder(version, envIndex, cipherSpec)
        val decoderIndex = main.nestedPrototypes.size
        main.addNested(decoder)
        val decoderPrototypes = setOf(decoder)

        // Upvalue slots: the main chunk hosts kget, every ancestor of a patched
        // prototype forwards it (same chain as the string decoder's).
        val needsSlot = IdentityMap<LuaPrototype, Boolean>()
        needsSlot[main] = true
        markSlots(main, sites.keys, needsSlot, emptyList())
        val slotOf = IdentityMap<LuaPrototype, Int>()
        assignSlots(main, null, needsSlot, slotOf)

        var encrypted = 0
        var rewritten = 0
        var skipped = 0
        for ((prototype, prototypeSites) in sites) {
            if (System.getenv("K_DEBUG") != null) {
                System.err.println("[K] rewrite proto=${prototype.source?.utf8}: sites=${prototypeSites.size} " +
                    "stack=${prototype.maxStackSize}")
            }
            val slot = slotOf[prototype] ?: continue
            val salt = random.nextInt(1, Int.MAX_VALUE)
            val outcome = rewrite(prototype, prototypeSites, slot, salt, cipherSpec, random)
            encrypted += outcome.encrypted
            rewritten += outcome.sites
            skipped += outcome.skipped
        }

        // Scrub the orphaned plaintext slots: the flattener's rematerialisation
        // re-derives values at run time and ORPHANS the original Integer in the
        // pool (an attacker reads it exactly like a live constant), and the junk
        // pass's random `LOADK scratch` references disappear when K rewrites
        // those sites. Compute orphans AFTER the rewrite loop, on the final code.
        var scrubbed = 0
        val promisedSlots = collectPromisedSlots(chunk)
        for ((prototype, constantIndices) in promisedSlots) {
            val referenced = referencedIndices(prototype)
            for (constantIndex in constantIndices) {
                if (constantIndex in referenced) continue
                val record = ByteArray(CIPHER_ENTRY_SIZE)
                random.nextBytes(record)
                io.github.dingyi222666.luabytecode.bytecode.edit.LuaPrototypeScope(prototype)
                    .setConstant(constantIndex, LuaConstant.Str(LuaString(record)))
                scrubbed++
            }
        }

        installDecoderPrologue(main, slotOf.getValue(main), decoderIndex)
        if (System.getenv("K_DEBUG") != null) {
            val mainRefs = referencedIndices(main)
            val sampleRefs = (165..172).map { it to (it in mainRefs) }
            System.err.println("[K] scrubbed=$scrubbed promisedProtos=${promisedSlots.size} " +
                "mainPromised=${promisedSlots[main]?.size ?: 0} refs165-172=$sampleRefs")
        }

        return ConstantEncryptionResult(
            encryptedConstants = encrypted,
            rewrittenSites = rewritten,
            patchedPrototypes = sites.size,
            stubPrototypes = sites.keys,
            decoderPrototypeIndex = decoderIndex,
            decoderPrototypes = decoderPrototypes,
            skippedSites = skipped,
        )
    }

    /**
     * Every Integer / Boolean constant of the chunk that NO instruction reads
     * any more (an orphan of a later pass's constant rematerialisation). Their
     * slots are scrubbed so the plaintext cannot be read from the pool.
     */
    private fun collectPromisedSlots(
        chunk: LuaChunk,
    ): Map<LuaPrototype, List<Int>> {
        val result = IdentityMap<LuaPrototype, MutableList<Int>>()
        for (prototype in chunk.prototypes) {
            val referenced = referencedIndices(prototype)
            for ((constantIndex, constant) in prototype.constants.withIndex()) {
                if (constantIndex in referenced) continue
                when (constant) {
                    is LuaConstant.Integer, is LuaConstant.Boolean, is LuaConstant.Number -> {}
                    else -> continue
                }
                result.getOrPut(prototype) { mutableListOf() } += constantIndex
            }
        }
        return result
    }

    /** All constant indices any instruction of [prototype] references. */
    private fun referencedIndices(prototype: LuaPrototype): Set<Int> {
        val referenced = HashSet<Int>()
        for ((pc, instruction) in prototype.code.withIndex()) {
            when (instruction.opcodeName) {
                "LOADK" -> referenced += instruction.bx
                "LOADKX" -> prototype.code.getOrNull(pc + 1)?.let { referenced += it.ax }
                "EXTRAARG" -> {} // table sizes are not constant references
                else -> referenced.addAll(instruction.constantOperands())
            }
        }
        return referenced
    }

    // ------------------------------------------------------------------ sites

    private class Site(
        val pc: Int,
        val destination: Int,
        val constantIndex: Int,
        /** For `LOADKX` sites: the pc of the paired `EXTRAARG` (its `Ax` must be neutralised). */
        val extraPc: Int = -1,
    )

    /**
     * Picks the constants to encrypt: Integer / Boolean / Number constants that
     * are only ever `LOADK`ed. `RK` / `*K` / `*I` sites are out of scope by
     * construction: the constant behind them is in [referencedConstantIndices]
     * and never selected (DESIGN §3.5 staging is a registered follow-up).
     */
    private fun collectSites(
        chunk: LuaChunk,
        config: ConstantEncryptionConfig,
        protectedPrototypes: Set<LuaPrototype>,
    ): Map<LuaPrototype, List<Site>> {
        val result = LinkedHashMap<LuaPrototype, List<Site>>()
        var budget = if (config.maxConstants > 0) config.maxConstants else Int.MAX_VALUE

        for (prototype in chunk.prototypes) {
            if (protectedPrototypes.any { it === prototype }) continue
            if (budget <= 0) break
            val shared = prototype.referencedConstantIndices()
            val sites = ArrayList<Site>()
            // `LOADKX` + `EXTRAARG` pairs load constants too (large pools); the
            // site is the LOADKX instruction, the constant index lives in the
            // EXTRAARG that follows it.
            val loadkxSites = HashMap<Int, MutableList<Site>>()
            if (System.getenv("K_TRACE_CONST")?.toIntOrNull() == 166 &&
                prototype === chunk.mainPrototype) {
                val extraPcs = prototype.code.withIndex().filter { it.value.opcodeName == "EXTRAARG" && it.value.ax == 166 }.map { it.index }
                val loadkxPcs = prototype.code.withIndex().filter { it.value.opcodeName == "LOADKX" }.map { it.index }
                val loadkPcs = prototype.code.withIndex().filter { it.value.opcodeName == "LOADK" && it.value.bx == 166 }.map { it.index }
                System.err.println("[KDBG166] extraPcs=$extraPcs loadkxPcs=$loadkxPcs loadkPcs=$loadkPcs")
            }
            for (pc in prototype.code.indices) {
                if (prototype.code[pc].opcodeName != "LOADKX") continue
                val extra = prototype.code.getOrNull(pc + 1) ?: continue
                if (extra.opcodeName != "EXTRAARG") continue
                val constantIndex = extra.ax
                if (constantIndex in shared) continue
                loadkxSites.getOrPut(constantIndex) { mutableListOf() } +=
                    Site(pc, prototype.code[pc].a, constantIndex, extraPc = pc + 1)
            }
            for ((constantIndex, pcs) in (prototype.loadKSites().map { (k, v) ->
                k to v
            } + loadkxSites.map { (k, v) -> k to v.map { it.pc } }).groupBy({ it.first }, { it.second })
                .map { (k, v) -> k to v.flatten() }) {
                if (budget <= 0) break
                if (constantIndex in shared) continue
                val traceThis = System.getenv("K_TRACE_CONST")?.toIntOrNull() == constantIndex &&
                    System.getenv("K_TRACE_PROTO")?.toIntOrNull() == chunk.prototypes.indexOf(prototype)
                if (traceThis) {
                    System.err.println("[KTRACE] const=$constantIndex pcs=$pcs shared=${constantIndex in shared}")
                }
                // Match the complete decoder call window, not any nearby GETUPVAL.
                // A normal closure can read an upvalue immediately before a numeric load.
                val inForeignStub = pcs.any { sitePc ->
                    (maxOf(0, sitePc - 3) until sitePc).any { start ->
                        val get = prototype.code[start]
                        if (get.opcodeName != "GETUPVAL") false else {
                            val call = prototype.code.getOrNull(start + 3)
                            call?.opcodeName == "CALL" && call.a == get.a && call.b == 3 && call.c == 2 &&
                                prototype.code.getOrNull(start + 1)?.let { it.opcodeName == "LOADK" && it.a == get.a + 1 } == true &&
                                prototype.code.getOrNull(start + 2)?.let { it.opcodeName == "LOADK" && it.a == get.a + 2 } == true
                        }
                    }
                }
                if (traceThis) {
                    System.err.println("[KTRACE] const=$constantIndex inForeignStub=$inForeignStub " +
                        "pcs=$pcs sitesAdded=${sites.size}")
                }
                if (inForeignStub) continue
                when (prototype.constants[constantIndex]) {
                    is LuaConstant.Integer, is LuaConstant.Boolean, is LuaConstant.Number -> {}
                    else -> continue
                }
                for (pc in pcs) {
                    sites += Site(pc, prototype.code[pc].a, constantIndex,
                        extraPc = if (prototype.code[pc].opcodeName == "LOADKX") pc + 1 else -1)
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

    private class RewriteOutcome(val encrypted: Int, val sites: Int, val skipped: Int)

    private fun rewrite(
        prototype: LuaPrototype,
        sites: List<Site>,
        decoderSlot: Int,
        salt: Int,
        cipherSpec: ConstCipher.Spec,
        random: ObfRandom,
    ): RewriteOutcome {
        val version = prototype.version
        val scratch = prototype.maxStackSize
        if (scratch + SCRATCH_REGISTERS > 250) {
            // fail safe (DESIGN §3.4): keep the prototype's constants in the clear
            // rather than pushing the frame against Lua's 255 register wall.
            return RewriteOutcome(0, 0, sites.size)
        }
        prototype.maxStackSize = scratch + SCRATCH_REGISTERS

        // Serialize the selected constants, encrypt them, store them as string
        // constants, and remember the blob slot per constant index.
        val plain = ArrayList<Byte>()
        val order = sites.map { it.constantIndex }.distinct()
                for (constantIndex in order) {
            when (val constant = prototype.constants[constantIndex]) {
                is LuaConstant.Integer -> plain.addAll(integerPayload(constant.value).toList())
                is LuaConstant.Boolean -> plain.addAll(booleanPayload(constant.value).toList())
                is LuaConstant.Number -> plain.addAll(numberPayload(constant.value).toList())
                else -> throw LuaBytecodeFormatException("unexpected constant kind at K$constantIndex")
            }
        }
        // Each entry is encrypted with its OWN stream starting at
        // `ConstCipher.ofIndex(salt, position)` — the decoder restarts the LCG
        // per entry there (it can only seek, not rewind a shared stream), so
        // the encryptor must do exactly the same. A single stream across the
        // concatenated payload decoded entry 0 correctly and everything after
        // it as garbage (Round 1 isolation run, 5.3).
        val plainEntries = plain.toByteArray()

        // One encrypted 9-byte record per constant becomes one binary string
        // constant (byte-safe: Lua strings are byte arrays, the reader/writer
        // never re-encode them). A per record *string* also keeps the pool
        // diff readable and every record self contained.
        val blobSlots = HashMap<Int, Int>()
        for ((position, constantIndex) in order.withIndex()) {
            val entry = plainEntries.copyOfRange(position * CIPHER_ENTRY_SIZE, (position + 1) * CIPHER_ENTRY_SIZE)
            val record = ConstCipher.apply(entry, ConstCipher.ofIndex(salt, position), cipherSpec)
            if (System.getenv("K_DEBUG") != null) {
                System.err.println("[K] blobSlots=$blobSlots order=$order")
            }
            if (System.getenv("K_DEBUG") != null) {
                System.err.println(
                    "[K] proto=${prototype.source?.utf8}:$prototype.lineDefined pos=$position const=$constantIndex " +
                        "plain=${entry.toList()} cipher=${record.toList()} seed=${ConstCipher.ofIndex(salt, position)} " +
                        "spec=(${'$'}{cipherSpec.multiplier},${'$'}{cipherSpec.increment},${'$'}{cipherSpec.shift}) salt=$salt",
                )
            }
            val slot = prototype.constants.size
            io.github.dingyi222666.luabytecode.bytecode.edit.LuaPrototypeScope(prototype)
                .addConstant(LuaConstant.Str(LuaString(record)))
            blobSlots[constantIndex] = slot
        }

        val lines = prototype.currentAbsoluteLines()
        val appendedLines = ArrayList<Int>()

        var encrypted = 0
        // The per-prototype salt is public seed material shipped with the decoder.
        // FRESH APPEND, never internConstant: interning would reuse an ORIGINAL
        // plaintext slot when values collide (e.g. salt == some sequence value),
        // keeping that slot referenced forever and blocking the orphan scrub
        // (the 82-slot residue on big_module_53, Round 2).
        val saltSlot = io.github.dingyi222666.luabytecode.bytecode.edit.LuaPrototypeScope(prototype)
            .addConstant(LuaConstant.Integer(salt.toLong()))

        for (site in sites) {
            val continuation = if (site.extraPc >= 0) site.pc + 2 else site.pc + 1
            if (continuation >= prototype.code.size) {
                throw LuaBytecodeFormatException(
                    "cannot encrypt the LOADK at ${site.pc}: no instruction follows it",
                )
            }

            val blobSlot = blobSlots.getValue(site.constantIndex)
            val indexSlot = io.github.dingyi222666.luabytecode.bytecode.edit.LuaPrototypeScope(prototype)
                .addConstant(LuaConstant.Integer(order.indexOf(site.constantIndex).toLong()))

            val blockStart = prototype.code.size
            val block = mutableListOf<LuaInstruction>()
            block += LuaInstructionFactory.abc(version, "GETUPVAL", scratch, decoderSlot, 0)
            block += LuaInstructionFactory.abx(version, "LOADK", scratch + 1, blobSlot)
            block += LuaInstructionFactory.abx(version, "LOADK", scratch + 2, indexSlot)
            // Pass the same public per-prototype salt used at encryption time.
            // Cipher parameters vary per chunk; this is reversible obfuscation.
            block += LuaInstructionFactory.abx(version, "LOADK", scratch + 3, saltSlot)
            block += LuaInstructionFactory.abc(version, "CALL", scratch, 4, 2)
            block += LuaInstructionFactory.abc(version, "MOVE", site.destination, scratch, 0)
            block += jumpTo(version, blockStart + block.size, continuation)
            prototype.edit {
                appendAll(block)
                set(site.pc, jumpTo(version, site.pc, blockStart))
                if (site.extraPc >= 0) {
                // neutralise the LOADKX pair-mate: with the LOADKX rewritten into
                // a kget jump, the stranded EXTRAARG would keep the ORIGINAL
                // constant index alive (referenced), blocking the orphan scrub
                // and keeping a plaintext reference in the stream.
                    set(site.extraPc, jumpTo(version, site.extraPc, continuation))
                }
            }

            if (lines != null) {
                val line = lines.getOrElse(site.pc) { prototype.lineDefined }
                repeat(block.size) { appendedLines += line }
            }
            encrypted++
        }
        if (lines != null) {
            lines.addAll(appendedLines)
            prototype.setAbsoluteLines(lines)
        }
        return RewriteOutcome(order.size, sites.size, 0)
    }

    // ------------------------------------------------------------- prologue

    /** Initialize after VARARGPREP while preserving atomic instruction pairs. */
    private fun installDecoderPrologue(
        main: LuaPrototype,
        decoderSlot: Int,
        decoderPrototypeIndex: Int,
    ) {
        val version = main.version
        val scratch = main.maxStackSize
        if (scratch + SCRATCH_REGISTERS > 255) {
            throw LuaBytecodeFormatException(
                "cannot install the constant decoder prologue: main needs $scratch registers",
            )
        }
        main.maxStackSize = scratch + SCRATCH_REGISTERS
        if (decoderSlot < main.upvalues.size) {
            main.upvalues[decoderSlot] = LuaUpvalue(
                inStack = true,
                index = scratch,
                kind = if (version == LuaBytecodeVersion.LUA_5_3) null else 0,
            )
        }

        installClosurePrologue(main, scratch, decoderSlot, decoderPrototypeIndex)
    }

    // ------------------------------------------------------------- payloads

    /** `tag | payload(8, little endian)` for an integer constant. */
    private fun integerPayload(value: Long): ByteArray {
        val result = ByteArray(CIPHER_ENTRY_SIZE)
        result[0] = TAG_INTEGER.toByte()
        for (i in 0 until PAYLOAD_SIZE) {
            result[1 + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
        return result
    }

    /** `tag | payload(8, little endian)` for a boolean constant. */
    private fun booleanPayload(value: Boolean): ByteArray {
        val result = ByteArray(CIPHER_ENTRY_SIZE)
        result[0] = TAG_BOOLEAN.toByte()
        result[1] = if (value) 1 else 0
        return result
    }

    /** Preserve all IEEE 754 bits, including negative zero and NaN payloads. */
    private fun numberPayload(value: Double): ByteArray {
        val result = ByteArray(CIPHER_ENTRY_SIZE)
        result[0] = TAG_NUMBER.toByte()
        val raw = value.toRawBits()
        for (i in 0 until PAYLOAD_SIZE) {
            result[1 + i] = ((raw ushr (8 * i)) and 0xFF).toByte()
        }
        return result
    }

    // ------------------------------------------------------------- upvalues

    /** The decoder uses the main environment upvalue; add a slot when absent. */
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
            prototype.upvalues += LuaUpvalue(inStack = false, index = parentSlot ?: 0, kind = upvalueKind(prototype.version))
            if (prototype.upvalueNames.isNotEmpty()) {
                prototype.upvalueNames += null
            }
            slotOf[prototype] = slot
        }
        for (child in prototype.nestedPrototypes) {
            assignSlots(child, slot, needsSlot, slotOf)
        }
    }

    /** Loads the bundled kget prototype and re-parents its `_ENV` upvalue. */
    private fun loadDecoder(
        version: LuaBytecodeVersion,
        envIndex: Int,
        cipherSpec: ConstCipher.Spec,
    ): LuaPrototype {
        val resource = "/obfuscator/decode_num_" + hex2(version.versionByte) + ".luac"
        val bytes = LuaConstantEncryptor::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: throw LuaBytecodeFormatException("missing bundled decoder resource $resource")
        val chunk = LuaChunkLoader.load(bytes, version)
        // The template is `return function(...) ... end`, so the decoder lives as
        // the single nested prototype of the wrapping main chunk.
        val decoder = chunk.mainPrototype.nestedPrototypes.singleOrNull()
            ?: throw LuaBytecodeFormatException("$resource must contain exactly one decoder prototype")
        if (decoder.upvalues.size != 1) {
            throw LuaBytecodeFormatException(
                "$resource is expected to capture exactly _ENV, found ${decoder.upvalues.size} upvalues",
            )
        }

        // Rewrite the decoder's LCG parameter constants (m / inc / shift) with this
        // chunk's full period parameters: same in-place patch trick as the string
        // decoder, but the parameters are genuinely random per chunk (Hull-Dobell
        // full period, no equivalence class).
        val mSlot = decoder.indexOfConstant(LuaConstant.Integer(M_PLACEHOLDER))
        val incSlot = decoder.indexOfConstant(LuaConstant.Integer(INC_PLACEHOLDER))
        check(mSlot >= 0 && incSlot >= 0) {
            "decode_num template must carry the m/inc placeholder literals"
        }
        decoder.constants[mSlot] = LuaConstant.Integer(cipherSpec.multiplier)
        decoder.constants[incSlot] = LuaConstant.Integer(cipherSpec.increment)
        val shiftSlot = decoder.indexOfConstant(LuaConstant.Integer(SHIFT_PLACEHOLDER))
        if (shiftSlot >= 0) {
            // 5.3 (and any build that did not fold the literal): patch the constant.
            decoder.edit { setConstant(shiftSlot, LuaConstant.Integer(cipherSpec.shift.toLong())) }
        } else {
            // 5.4 / 5.5 folded `shift = 11` into a `LOADI` immediate: convert that
            // one instruction into a `LOADK` of the (new) pooled shift constant.
            val loadIPc = decoder.code.indexOfFirst {
                it.opcodeName == "LOADI" && it.sbx == SHIFT_PLACEHOLDER.toInt()
            }
            check(loadIPc >= 0) { "decode_num template must carry the shift placeholder" }
            val slot = io.github.dingyi222666.luabytecode.bytecode.edit.LuaPrototypeScope(decoder).addConstant(LuaConstant.Integer(cipherSpec.shift.toLong()))
            decoder.edit {
                set(loadIPc, LuaInstructionFactory.abx(version, "LOADK", decoder.code[loadIPc].a, slot))
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
