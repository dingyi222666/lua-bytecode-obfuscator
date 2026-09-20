package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.bytecode.LuaChunk
import io.github.dingyi222666.luabytecode.bytecode.LuaConstant
import io.github.dingyi222666.luabytecode.bytecode.LuaPrototype
import io.github.dingyi222666.luabytecode.obfuscate.ControlFlowFlatteningConfig
import io.github.dingyi222666.luabytecode.obfuscate.ControlFlowFlatteningReport
import io.github.dingyi222666.luabytecode.obfuscate.LuaControlFlowFlattening
import io.github.dingyi222666.luabytecode.obfuscate.LuaObfuscator
import io.github.dingyi222666.luabytecode.obfuscate.ObfuscationConfig
import io.github.dingyi222666.luabytecode.obfuscate.ObfuscationReport

/**
 * P4 entry point: turns a chunk's main prototype into a **self contained VM module**
 * (docs section 11).
 *
 * The emitted Lua source contains the per chunk generated interpreter (P4.3 handler
 * heterogeneity + private handler numbers), the encrypted VM program (P4.2), the chunk private
 * opcode numbering baked into the dispatch and the encrypted constant pool (P4.4). Nothing in
 * it carries a plain Lua opcode, a plain VM opcode name or a plain string constant.
 *
 * Only prototypes whose whole instruction stream is inside the subset are translated; anything
 * else makes [emitModule] return `null` instead of producing a wrong program. Nested prototypes
 * are emitted as indexed child programs with descriptor-level shared-cell captures.
 *
 * B2 round 1 (ISSUE VM-1) hardening, per the P4 audit /tmp/adv3-p4/REPORT.md §5:
 *
 *  * **per chunk LCG parameters**: `mul`/`add` are drawn from the caller's RNG (Hull–Dobell
 *    full period mod 2^32) and shipped only inside the *encrypted* program header -- never as
 *    a Lua literal, so the textbook 1664525/1013904223 pair is gone from every product;
 *  * **runtime seed derivation**: the encryption seed never appears as a literal. It is
 *    computed at run time from `#tostring(_VERSION)` mixed with three chunk private constants
 *    (K1..K3) that themselves live encrypted in the *upper half* of the pool, and a per chunk
 *    random rotation;
 *  * **no plain numeric/boolean constants**: every pool entry ships as a tagged byte list
 *    encrypted with its own keystream ({tag, bytes...});
 *  * **wrong seed self check**: the encrypted header carries a magic word; a wrong runtime
 *    seed fails loudly (`vm: integrity check failed`) instead of executing garbage;
 *  * **decoder eats pc**: every decrypted body word is whitened again with a mask derived
 *    from its slot index, so the decoder cannot be sliced out and replayed standalone.
 */
object LuaVmObfuscation {
    enum class CapabilityStatus { APPLIED, REFUSED, NOT_IMPLEMENTED }

    data class Capability(
        val id: String,
        val status: CapabilityStatus,
        val details: String,
    )

    /** Build-time manifest for the three distinct VM protection layers. */
    data class CapabilityReport(
        val inputObfuscation: Capability,
        val sourceCff: Capability,
        val lift: Capability,
        val interpreterCff: Capability,
        val translator: String,
        val cffReport: ControlFlowFlatteningReport? = null,
        val obfuscationReport: ObfuscationReport? = null,
    ) {
        val complete: Boolean
            get() = sourceCff.status == CapabilityStatus.APPLIED &&
                lift.status == CapabilityStatus.APPLIED &&
                interpreterCff.status == CapabilityStatus.APPLIED
    }

    data class Options(
        val seed: Long = 0L,
        val debug: Boolean = false,
        /** Opt-in Lua 5.4/5.5 count-based numeric-for support. */
        val enableCountBasedLoops: Boolean = false,
        /** Existing library pipeline applied to a private copy before VM lifting. */
        val inputObfuscation: ObfuscationConfig? = null,
        /**
         * CFF applied to a private copy of the input before VM lifting. This is enabled by
         * default: VM input is required to be an already-obfuscated representation. Set it to
         * null only for compatibility experiments, and set [requireSourceCff] to false as well.
         */
        // Kept opt-in for the compatibility entry point. New callers that require the
        // documented VM contract should use [obfuscated] or set both fields explicitly.
        val sourceCff: ControlFlowFlatteningConfig? = null,
        val requireSourceCff: Boolean = false,
        /** Apply the lightweight source-level CFF pass to the generated Lua host. */
        val interpreterCff: Boolean = true,
    )

    fun emitModule(chunk: LuaChunk, random: java.util.Random, options: Options = Options()): String? {
        return emitModuleDetailed(chunk, random, options)?.module
    }

    /** Strict VM entry point: the input representation must pass source CFF before lifting. */
    fun obfuscated(chunk: LuaChunk, random: java.util.Random, options: Options = Options(
        inputObfuscation = ObfuscationConfig.AGGRESSIVE,
        sourceCff = null,
        requireSourceCff = false,
    )): Emission? = emitModuleDetailed(chunk, random, options)

    /**
     * Emits a module and its explicit protection manifest. The detailed entry point is useful
     * to packaging frontends: a successful Lua module is not silently presented as fully
     * CFF-protected when the generated host interpreter has not gone through the second CFF
     * compilation/lift/validation stage yet.
     */
    fun emitModuleDetailed(chunk: LuaChunk, random: java.util.Random, options: Options = Options()): Emission? {
        val working = cloneChunk(chunk)
        val obfuscationReport = options.inputObfuscation?.let { config ->
            LuaObfuscator.obfuscate(working, config.copy(masterSeed = options.seed))
        }
        val cffReport = options.sourceCff?.let { LuaControlFlowFlattening.apply(working, it) }
            ?: obfuscationReport?.controlFlowFlattening
        if (options.requireSourceCff && options.sourceCff == null && obfuscationReport?.controlFlowFlattening == null) return null
        if (options.requireSourceCff && (cffReport == null || cffReport.flattenedPrototypes == 0)) {
            if (options.debug) System.err.println("[vm] source CFF did not apply to any prototype")
            return null
        }
        val prototype = working.mainPrototype
        val map = LuaVmProgram.OpcodeMap.random(random)
        val program = LuaVmTranslator.translate(prototype, map, scratch = prototype.maxStackSize, enableCountBasedLoops = options.enableCountBasedLoops) ?: return null

        // B2 round 1: the per chunk cipher parameters are random (full period Hull-Dobell);
        // they travel encrypted in the header and never appear as literals.
        val cipher = LuaVmProgram.Cipher.random(random)

        // Seed split (issue VM-1 A2): the *lower half* (a random constant chosen now) encrypts
        // the header, which carries the upper half (K1..K3 + rotation) plus the magic word.
        // The Lua driver recomputes the upper half at run time from `#tostring(_VERSION)`.
        val seedLower = random.nextLong() and 0xFFFFFFFFL
        val kUpper = LongArray(3) { (random.nextLong() and 0xFFFFFFFFL) }
        if (kUpper[0] == 0L) kUpper[0] = 1L // the seed multiplier must not collapse _VERSION's length
        val rotation = 1 + random.nextInt(30)
        val seedMul2 = (random.nextLong() and 0xFFFFFFFFL) or 3L
        val upperSeed = upperSeed(kUpper, seedMul2)

        val parts = LuaVmInterpreterGenerator.generateModuleParts(random, map, cipher, prototype.version)
        val names = parts.names

        // ---- assemble the whole encrypted word array: header (6 words) + body.
        // Phase 1 of the Lua decryptor XORs the header words with the plain LCG stream
        // (initial state only); phase 2 decrypts the body with the *re-mixed* stream plus
        // position whitening. Everything below mirrors that byte for byte.
        // the whitening mask addend is the same per chunk magic word the Lua side checks
        val magicAnd = names.magicValue.toLong() and 0xFFFFFFFFL
        val initial = cipher.initialState(seedLower)
        val bodySeedMix = lowerAndUpperMix(upperSeed, kUpper)
        val mixWord = (bodySeedMix and 0xFFFFFFFFL).toInt() xor (rotation shl 26)
        // the whitening rotation travels in the header's top bits; the magic word marks the slot

        /**
         * Packs a program (main or child) into the shipped layout: 6 word header
         * `[pad, magic, mulLo, mulHi, add, mix]` on the fixed secondary LCG, then the body
         * on the per chunk LCG with position whitening. `bodySeed` selects the domain: the
         * lower seed for the main program, the upper (runtime derived) seed for children.
         * Every call restarts both streams from `bodySeed`, mirroring the Lua decryptor,
         * whose phase 1 runs off `seedState(seed)` and phase 2 off `seedState(seed) + mix`.
         */
        fun packProgram(plain: IntArray, bodySeed: Long): IntArray {
            val packed = IntArray(6 + plain.size)
            packed[0] = 0 // padding word consumes no keystream
            var headerState = cipher.initialState(bodySeed)
            fun headerCrypt(w: Int): Int {
                headerState = (headerState * names.headerMul + 1L) and 0xFFFFFFFFL
                return w xor headerState.toInt()
            }
            packed[1] = headerCrypt(names.magicValue)
            packed[2] = headerCrypt((cipher.mul and 0xFFFFFFFFL).toInt())
            packed[3] = headerCrypt((cipher.mul shr 32).toInt())
            packed[4] = headerCrypt((cipher.add and 0xFFFFFFFFL).toInt())
            packed[5] = headerCrypt(mixWord)
            var state = (cipher.initialState(bodySeed) + (mixWord.toLong() and 0xFFFFFFFFL)) and 0xFFFFFFFFL
            for ((i, w) in plain.withIndex()) {
                state = (state * cipher.mul + cipher.add) and 0xFFFFFFFFL
                val wi = i + 1
                val wmask = (wi * names.whiteRot + magicAnd) and 0xFFFFFFFFL
                val wmaskSigned = if (wmask >= 0x80000000L) wmask - 0x100000000L else wmask
                packed[6 + i] = (w xor wmaskSigned.toInt()) xor state.toInt()
            }
            return packed
        }
        val encryptedProgram = packProgram(program.words, seedLower)

        // the upper-half constants go to the *end* of the pool so the program's indices stay
        // untouched; decryptConstants decodes ALL of them, the driver only reads the last four.
        // Every pool entry uses ONE domain separator (salt 3000, emitted as a disguised integer
        // expression by the generator) so Kotlin and Lua agree without a per-class literal.
        val upperEncrypted = kUpper.map { cipher.cryptBytes(seedLower, 3000L, longBytes(it)) }
        val upperMixedEncrypted = cipher.cryptBytes(seedLower, 3000L, longBytes(kUpper[0] * 5L + kUpper[1]))

        // the translator's pool is a list; the module's k[i] keeps the same indices
        val poolSize = maxOf(program.constants.size, 4)
        // the upper-half constants go to the *end* of the pool so the program's indices stay
        // untouched; decryptConstants decodes ALL of them, the driver only reads the last four
        val encryptedConstants = arrayOfNulls<IntArray?>(poolSize + 4)
        encryptedConstants[poolSize] = upperEncrypted[0]
        encryptedConstants[poolSize + 1] = upperEncrypted[1]
        encryptedConstants[poolSize + 2] = upperEncrypted[2]
        encryptedConstants[poolSize + 3] = upperMixedEncrypted
        val idxU1 = poolSize
        val idxU2 = poolSize + 1
        val idxU3 = poolSize + 2
        val idxUM = poolSize + 3

        fun encryptedFor(constant: LuaConstant): IntArray =
            cipher.cryptBytes(upperSeed, 3000L, LuaVmProgram.encodeConstantBytes(constant))

        // every program constant ships encrypted (tagged bytes); no plain literal survives
        program.constants.forEachIndexed { index, constant ->
            encryptedConstants[index] = encryptedFor(constant)
        }

        // the generated interpreter ends with `return $moduleTable`, so the driver goes before it
        val head = parts.head
        val tail = parts.tail

        val out = StringBuilder()
        out.append(head)
        out.append("\nlocal programs = {}\n")
        out.append("local constants = {}\n")
        out.append("local captures = {}\n")
        out.append("local paramCounts = {}\n")
        out.append("local childIds = {}\nlocal frameInfo = {}\n")
        out.append("frameInfo[1] = {${program.sourceStackSize}, ${program.varargTable}}\n")
        out.append("programs[1] = {")
        out.append(encryptedProgram.joinToString(", ") { it.toString() })
        out.append("}\n")
        out.append("paramCounts[1] = ").append(program.numParams).append("\n")
        out.append("local k = {}\n")
        encryptedConstants.forEachIndexed { index, bytes ->
            if (bytes != null) {
                out.append("k[").append(index).append("] = {").append(bytes.joinToString(", ")).append("}\n")
            }
        }
        // B2: driver order matters. The program decryptor installs the real cipher mul/add as
        // a side effect of its first call, so the main program decrypts FIRST (with the literal
        // lower seed), then the seed tail, then seed2, then the pool and any child programs.
        // The seed never appears as a plain literal that reveals the upper half: the runtime
        // `#tostring(_VERSION)` mixing plus the encrypted K1..K3 reconstruct it.
        out.append("programs[1] = ").append(names.decryptName)
            .append("(programs[1], ").append(seedLower).append(")\n")
        out.append("local seedTail = {}\n")
        for (off in 0 until 4) {
            out.append("seedTail[").append(off).append("] = k[").append(idxU1 + off).append("]\n")
            out.append("k[").append(idxU1 + off).append("] = nil\n")
        }
        out.append("seedTail = ").append(names.decryptConstantsName).append("(seedTail, 4, ")
            .append(seedLower).append(")\n")
        out.append("local seed2 = (").append(seedMul2)
            .append(" * seedTail[0] + seedTail[1]) % 0x100000000 ~ seedTail[2]\n")
        out.append("seed2 = seed2 % 0x100000000\n")
        out.append("k = ").append(names.decryptConstantsName)
            .append("(k, ").append(poolSize).append(", seed2)\n")
        for (off in 0 until 4) {
            out.append("k[").append(idxU1 + off).append("] = seedTail[").append(off).append("]\n")
        }
        out.append("constants[1] = k\n")
        var childIndex = 1
        fun emitChildren(parentIndex: Int, list: List<LuaVmTranslator.VmProgram>, parentCaptures: List<List<LuaVmTranslator.VmProgram.Capture>>) {
            out.append("childIds[$parentIndex] = {}\n")
            for ((childOffset, child) in list.withIndex()) {
                childIndex++
                val currentIndex = childIndex
                out.append("childIds[$parentIndex][${childOffset + 1}] = $currentIndex\n")
                out.append("frameInfo[$currentIndex] = {${child.sourceStackSize}, ${child.varargTable}}\n")
                out.append("programs[").append(childIndex).append("] = {")
                out.append(packProgram(child.words, upperSeed).joinToString(", ") { it.toString() })
                out.append("}\n")
                out.append("paramCounts[").append(childIndex).append("] = ").append(child.numParams).append("\n")
                out.append("constants[").append(childIndex).append("] = {}")
                child.constants.forEachIndexed { index, constant ->
                    out.append("constants[").append(childIndex).append("][").append(index).append("] = ")
                    out.append("{").append(encryptedFor(constant).joinToString(", ")).append("}")
                    out.append("\n")
                }
                out.append("constants[").append(childIndex).append("] = ").append(names.decryptConstantsName)
                    .append("(constants[").append(childIndex).append("], ").append(child.constants.size)
                    .append(", seed2)\n")
                out.append("captures[").append(childIndex).append("] = {")
                out.append(parentCaptures.getOrNull(childOffset)?.joinToString(",") { d ->
                    "{" + (if (d.inStack) "true" else "false") + "," + d.index + "}"
                } ?: "")
                out.append("}\n")
                emitChildren(currentIndex, child.children, child.childCaptures)
            }
        }
        emitChildren(1, program.children, program.childCaptures)
        // child programs decrypt with the runtime derived seed2 as well (their words were
        // encrypted with the upper seed, mirroring the main program's pool domain)
        out.append("for i = 2, #programs do programs[i] = ")
            .append(names.decryptName).append("(programs[i], seed2) end\n")
        out.append("local args = table.pack(...)\n")
        out.append("args.k = k; args.varargs = table.pack(...); args.up = { { value = _ENV } }; ")
        out.append("for i = 2, ").append(program.upvalueCount).append(" do args.up[i] = { value = nil } end\n")
        val entryCall = buildString {
            append(names.runName).append("(programs[1], nil, 1, args, ")
            .append(names.stepsLimit).append(", programs, constants, ")
            .append(program.controlOffset).append(", captures, 1, paramCounts, childIds, frameInfo)\n")
        }
        out.append(entryCall)
        out.append(tail)
        if (options.debug) {
            System.err.println(
                "[vm] module: ${program.words.size} words, ${program.superInstructions} super instructions, " +
                    "cipher=$cipher, seedLower=$seedLower, upperSeed=$upperSeed, rotation=$rotation",
            )
        }
        val sourceStatus = when {
            options.sourceCff == null && obfuscationReport?.controlFlowFlattening == null -> CapabilityStatus.REFUSED
            cffReport != null && cffReport.flattenedPrototypes > 0 -> CapabilityStatus.APPLIED
            else -> CapabilityStatus.REFUSED
        }
        val rawModule = out.toString()
        val finalModule = if (options.interpreterCff) {
            LuaVmSourceObfuscator.apply(rawModule, entryCall.trimEnd('\n'), random)
        } else rawModule
        return Emission(
            finalModule,
            CapabilityReport(
                Capability("input.obfuscation",
                    if (options.inputObfuscation != null) CapabilityStatus.APPLIED else CapabilityStatus.REFUSED,
                    obfuscationReport?.toString() ?: "input obfuscation disabled"),
                Capability("source.cff", sourceStatus,
                    cffReport?.toString() ?: "source CFF disabled"),
                Capability("vm.lift", CapabilityStatus.APPLIED, "private ISA + encrypted program"),
                Capability("interpreter.source.cff",
                    if (options.interpreterCff) CapabilityStatus.APPLIED else CapabilityStatus.REFUSED,
                    if (options.interpreterCff) "source-level dispatcher applied without a parser"
                    else "generated host source CFF disabled"),
                "LuaVmTranslator",
                cffReport,
                obfuscationReport,
            ),
        )
    }

    data class Emission(val module: String, val report: CapabilityReport)

    private fun cloneChunk(chunk: LuaChunk): LuaChunk = LuaChunk(
        chunk.header,
        chunk.mainUpvalueCount,
        clonePrototype(chunk.mainPrototype),
    )

    private fun clonePrototype(source: LuaPrototype): LuaPrototype = LuaPrototype(
        version = source.version,
        source = source.source,
        lineDefined = source.lineDefined,
        lastLineDefined = source.lastLineDefined,
        numParams = source.numParams,
        isVararg = source.isVararg,
        isVarargFlags = source.isVarargFlags,
        maxStackSize = source.maxStackSize,
        code = source.code.toMutableList(),
        constants = source.constants.toMutableList(),
        upvalues = source.upvalues.toMutableList(),
        nestedPrototypes = source.nestedPrototypes.map(::clonePrototype).toMutableList(),
        lineInfo = source.lineInfo.toMutableList(),
        absoluteLineInfo = source.absoluteLineInfo.toMutableList(),
        localVariables = source.localVariables.toMutableList(),
        upvalueNames = source.upvalueNames.toMutableList(),
    )

    /**
     * The run-time recomputed upper half seed. Kept to 32 bit integer ops so the Lua driver
     * can mirror it exactly: `seed2 = (M * k1 + k2) % 2^32 ~ k3`, with the multiplier drawn
     * per chunk (nothing about the derivation is shared across modules beyond the shape).
     */
    private fun upperSeed(k: LongArray, seedMul2: Long): Long =
        ((seedMul2 * k[0] + k[1]) and 0xFFFFFFFFL) xor k[2]

    /** The header's body-seed mix word: the Lua side recomputes `k1*5 + k2` and folds hh[4]/hh[5]. */
    private fun lowerAndUpperMix(upperSeed: Long, k: LongArray): Long =
        (upperSeed xor ((k[0] * 5L + k[1]) and 0xFFFFFFFFL)) and 0xFFFFFFFFL

    private fun longBytes(v: Long): ByteArray =
        ByteArray(9) { i -> if (i == 0) LuaVmProgram.ConstTag.INTEGER.toByte() else (v ushr ((i - 1) * 8)).toByte() }

}
