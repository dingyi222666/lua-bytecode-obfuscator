package io.github.dingyi222666.luabytecode.cli

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import io.github.dingyi222666.luabytecode.obfuscate.CloneBlocksConfig
import io.github.dingyi222666.luabytecode.obfuscate.ConstantEncryptionConfig
import io.github.dingyi222666.luabytecode.obfuscate.ControlFlowFlatteningConfig
import io.github.dingyi222666.luabytecode.obfuscate.CffPredicateFamily
import io.github.dingyi222666.luabytecode.obfuscate.JunkCodeConfig
import io.github.dingyi222666.luabytecode.obfuscate.JumpIndirectionConfig
import io.github.dingyi222666.luabytecode.obfuscate.LuaObfuscator
import io.github.dingyi222666.luabytecode.obfuscate.ObfuscationConfig
import io.github.dingyi222666.luabytecode.obfuscate.OpaquePredicate
import io.github.dingyi222666.luabytecode.obfuscate.StringEncryptionConfig
import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.platform.Platform
import io.github.dingyi222666.luabytecode.vm.LuaVmObfuscation
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.fseek
import kotlin.system.exitProcess

/**
 * The native command line (roadmap step 6b) — the DEFAULT CLI of the project.
 * The flag surface is the minimal usable subset of the JVM `cli/Main.kt`
 * library entry, sharing the pipeline module with the JVM/web hosts:
 * files in/out, lua version expectation, master seed, every feature switch,
 * the presets and the `--vm-out` interpreter emission. Byte-level io goes
 * through posix (`fopen`/`fread`/`fwrite`); the exits codes match the JVM CLI
 * (0 ok, 1 invalid bytecode, 2 usage, 3 VM refusal).
 */
private const val USAGE = """
lua-bytecode-obfuscator (native) — Lua 5.3 / 5.4 / 5.5 bytecode obfuscator
Usage:
  lua-bytecode-obfuscator <chunk.luac> [options]
Options:
  --expect <5.3|5.4|5.5>  Fail unless the chunk declares this version
Obfuscate (all optional, combinable):
  --obfuscate <preset>          light | balanced | aggressive
  --encrypt-keys               Encrypt short global, field and method names
  --jmp-advanced               Runtime routes with unreachable control-flow islands
  --jmp-decoys <n>             Number of unreachable blocks per prototype
  --constants                 Encrypt integer, boolean and float pool loads
  --strings[=<minLength>]       Encrypt string constants (default min length 6)
  --jmp[=<depth>]               Route jumps through <depth> trampoline hops (default 2)
  --junk[=<n>]                  Inject <n> junk/dead branches per prototype (default 2)
  --predicate <family>          NON_SQUARE | MBA_CARRY_CHAIN | MBA_BORROW_CHAIN |
                                MBA_MASK_SELECT | MBA_PRODUCT_PARTITION | MIXED
  --rings <blocks>[:<length>]   Unreachable "infinite goto" rings (default 0:2)
  --flatten                     Control flow flattening (OLLVM style dispatcher)
  --cff <baseline|full|stress>   Select CFF feature requests (actual coverage is reported)
  --cff-allow-allocation-effects Permit private allocation effects under the host contract
  --clone[=n]                   Live block cloning with a trap copy (Collberg §4.2)
  --strip-debug                 Drop line info, locals, upvalue names and sources
  --seed <n>                    Pin the run's master seed (reproducible artifacts).
                                Omit: every run is unique (per-run OS entropy).
                                --seed 0: derive seeds from chunk content.
Output:
  --dump <out.luac>             Write the (obfuscated) chunk back out
  --vm-out <module.lua>         Emit the lifted private-ISA Lua interpreter module
  -h, --help              Show this help
"""

/** Program entry: install the native platform services, then run the CLI. */
fun main(args: Array<String>) {
    NativePlatform.install()
    val code = runNativeCli(args)
    if (code != 0) {
        exitProcess(code)
    }
}

/**
 * Testable CLI body: returns the process exit code instead of terminating
 * (0 ok, 1 invalid bytecode, 2 usage error, 3 VM refusal).
 */
internal fun runNativeCli(args: Array<String>): Int {
    if (args.isEmpty() || args.contains("-h") || args.contains("--help")) {
        println(USAGE.trimIndent())
        return 0
    }
    var path: String? = null
    var dumpPath: String? = null
    var vmOutPath: String? = null
    var expectedVersion: LuaBytecodeVersion? = null
    var preset: ObfuscationConfig? = null
    var encryptKeys = false
    var advancedJumps = false
    var jumpDecoys: Int? = null
    var constantsEnabled = false
    var stringsEnabled = false
    var jumpsEnabled = false
    var junkEnabled = false
    var stripDebug = false
    var flattenEnabled = false
    var cffConfig = ControlFlowFlatteningConfig.BASELINE
    var cffAllowAllocations = false
    var cloneBlocks = 0
    var jumpDepth = 2
    var junkBranches = 2
    var stringMinLength = 6
    var predicateFamily: OpaquePredicate? = null
    var rings: Pair<Int, Int>? = null
    // PRED-2/LEAK-5: null = per-run OS entropy (default), 0 = legacy content
    // derivation, anything else pins the run byte-for-byte.
    var masterSeed: Long? = null
    // accept both "--jmp" and the documented "--jmp=3" spellings
    val argv = ArrayList<String>(args.size + 4)
    for (argument in args) {
        val separator = argument.indexOf('=')
        if (argument.startsWith("--") && separator > 2) {
            argv += argument.substring(0, separator)
            argv += argument.substring(separator + 1)
        } else {
            argv += argument
        }
    }

    var index = 0
    while (index < argv.size) {
        when (val argument = argv[index]) {
            "--expect" -> {
                val value = argv.getOrNull(++index)
                    ?: return usageFail("--expect requires a value (5.3, 5.4 or 5.5)")
                expectedVersion = when (value) {
                    "5.3" -> LuaBytecodeVersion.LUA_5_3
                    "5.4" -> LuaBytecodeVersion.LUA_5_4
                    "5.5" -> LuaBytecodeVersion.LUA_5_5
                    else -> return usageFail("unsupported version \"$value\", expected 5.3, 5.4 or 5.5")
                }
            }
            "--dump" -> dumpPath = argv.getOrNull(++index)
                ?: return usageFail("--dump requires an output path")
            "--vm-out" -> vmOutPath = argv.getOrNull(++index)
                ?: return usageFail("--vm-out requires an output path")
            "--obfuscate" -> {
                val value = argv.getOrNull(++index)
                    ?: return usageFail("--obfuscate requires a preset (light, balanced, aggressive)")
                preset = when (value) {
                    "light" -> ObfuscationConfig.LIGHT
                    "balanced" -> ObfuscationConfig.BALANCED
                    "aggressive" -> ObfuscationConfig.AGGRESSIVE
                    else -> return usageFail("unknown obfuscation preset \"$value\"")
                }
            }

            "--constants" -> constantsEnabled = true
            "--jmp-decoys" -> {
                jumpDecoys = argv.getOrNull(++index)?.toIntOrNull()
                    ?: return usageFail("--jmp-decoys requires 0..1024")
                if (jumpDecoys !in 0..1024) return usageFail("--jmp-decoys requires 0..1024")
                jumpsEnabled = true
            }
            "--encrypt-keys" -> { encryptKeys = true; stringsEnabled = true; stringMinLength = 1 }
            "--jmp-advanced" -> { advancedJumps = true; jumpsEnabled = true }
            "--strings" -> {
                stringsEnabled = true
                argv.getOrNull(index + 1)?.toIntOrNull()?.let { stringMinLength = it; index++ }
            }

            "--jmp" -> {
                jumpsEnabled = true
                argv.getOrNull(index + 1)?.toIntOrNull()?.let { jumpDepth = it; index++ }
            }

            "--junk" -> {
                junkEnabled = true
                argv.getOrNull(index + 1)?.toIntOrNull()?.let { junkBranches = it; index++ }
            }
            "--flatten" -> flattenEnabled = true
            "--cff" -> {
                flattenEnabled = true
                cffConfig = when (val value = argv.getOrNull(++index)) {
                    "baseline" -> ControlFlowFlatteningConfig.BASELINE
                    "full" -> ControlFlowFlatteningConfig.FULL
                    "stress" -> ControlFlowFlatteningConfig.FULL.copy(stateTable = true,
                        predicateFamilies = CffPredicateFamily.entries.toSet())
                    else -> return usageFail("unknown CFF configuration: $value")
                }
            }
            "--cff-allow-allocation-effects" -> cffAllowAllocations = true
            "--clone" -> {
                val value = argv.getOrNull(index + 1)
                if (value != null && value.toIntOrNull() != null) {
                    cloneBlocks = value.toInt()
                    index++
                } else {
                    cloneBlocks = 1
                }
            }
            "--strip-debug" -> stripDebug = true
            "--predicate" -> {
                val value = argv.getOrNull(++index)
                    ?: return usageFail("--predicate requires a family")
                predicateFamily = runCatching { OpaquePredicate.valueOf(value.uppercase()) }
                    .getOrElse { return usageFail("unknown predicate family \"$value\"") }
            }

            "--rings" -> {
                val value = argv.getOrNull(++index)
                    ?: return usageFail("--rings requires blocks[:length]")
                val parts = value.split(':')
                val blocks = parts[0].toIntOrNull()
                    ?: return usageFail("--rings needs an integer block count")
                val length = parts.getOrNull(1)?.toIntOrNull() ?: 2
                rings = blocks to length
            }

            "--seed" -> {
                val value = argv.getOrNull(++index)
                    ?: return usageFail("--seed requires a Long value")
                masterSeed = value.toLongOrNull()
                    ?: return usageFail("--seed needs a Long (0 = legacy content seed)")
            }

            else -> if (argument.startsWith("-")) {
                return usageFail("unknown option $argument")
            } else {
                path = argument
            }
        }
        index++
    }
    if (path == null) {
        return usageFail("missing chunk file")
    }
    val inputBytes = readFileBytes(path) ?: return usageFail("no such file: $path")
    try {
        val chunk = LuaChunkLoader.load(inputBytes, expectedVersion)
        if (vmOutPath != null) {
            // PRED-2: default per-run OS entropy, `--seed` pins, `--seed 0` legacy.
            val vmRandom = when (masterSeed) {
                null -> ObfRandom(Platform.entropy.nextLong())
                else -> ObfRandom(masterSeed)
            }
            // Roadmap step 5 contract: the VM report distinguishes source.cff,
            // vm.lift, interpreter.source.cff and the not-implemented target
            // bytecode-level CFF (LuaVmObfuscation.CapabilityReport.categories).
            val emission = LuaVmObfuscation.emitModuleDetailed(chunk, vmRandom)
            if (emission == null) {
                // A refusal is a *correct* outcome (the chunk is outside the
                // modelled subset), never a wrong program. It exits 3 so a caller
                // can tell it apart from a crash (any other non zero status) and
                // from success — the same contract as the JVM CLI.
                NativePlatform.stderrPrintln("error: this chunk uses instructions outside the VM subset")
                return 3
            }
            val out = vmOutPath
            if (!writeFileBytes(out, emission.module.encodeToByteArray())) {
                return usageFail("cannot write $out")
            }
            println("wrote VM module to $out")
            println("VM protection report (${emission.report.translator}):")
            for (capability in emission.report.categories) {
                println("  ${capability.id}: ${capability.status.label} - ${capability.details}")
            }
            println(
                "  staged: ${emission.report.staged}, complete: ${emission.report.complete} " +
                    "(target bytecode-level CFF is not implemented)",
            )
            return 0
        }
        cffConfig = cffConfig.copy(allocationEffectsUnobservable = cffAllowAllocations)
        val presetConfig = preset
        val flagConfig = if (constantsEnabled || stringsEnabled || jumpsEnabled || junkEnabled || stripDebug || flattenEnabled ||
            cloneBlocks > 0 ||
            predicateFamily != null || rings != null
        ) {
            ObfuscationConfig(
                constantEncryption = if (constantsEnabled) ConstantEncryptionConfig() else null,
                stringEncryption = if (stringsEnabled) StringEncryptionConfig(
                    minLength = stringMinLength,
                    encryptKeys = encryptKeys || presetConfig?.stringEncryption?.encryptKeys == true,
                ) else null,
                jumpIndirection = if (jumpsEnabled) JumpIndirectionConfig(
                    depth = jumpDepth,
                    runtimeRoutes = advancedJumps || presetConfig?.jumpIndirection?.runtimeRoutes == true,
                    decoyBlocks = jumpDecoys
                        ?: if (advancedJumps) 12
                        else presetConfig?.jumpIndirection?.decoyBlocks ?: 0,
                ) else null,
                junkCode = if (junkEnabled || predicateFamily != null || rings != null) {
                    JunkCodeConfig(
                        guardedBranches = if (junkEnabled) junkBranches else 0,
                        predicateFamily = predicateFamily ?: OpaquePredicate.MIXED,
                        unreachableBlocks = rings?.first ?: 0,
                        deadCycleLength = rings?.second ?: 2,
                    )
                } else {
                    null
                },
                blockCloning = if (cloneBlocks > 0) {
                    CloneBlocksConfig(
                        blocksPerPrototype = cloneBlocks,
                        // same toggle as the JVM CLI: CLONE_NO_CORRUPT disables the trap copy
                        corruptTrapCopy = NativePlatform.posixGetenv("CLONE_NO_CORRUPT") == null,
                    )
                } else {
                    null
                },
                controlFlowFlattening = if (flattenEnabled) cffConfig else null,
                stripDebugInfo = stripDebug,
                masterSeed = masterSeed,
            )
        } else {
            null
        }
        val obfuscation = when {
            presetConfig != null && flagConfig != null -> ObfuscationConfig(
                stringEncryption = flagConfig.stringEncryption ?: presetConfig.stringEncryption,
                jumpIndirection = flagConfig.jumpIndirection ?: presetConfig.jumpIndirection,
                junkCode = when {
                    flagConfig.junkCode == null -> presetConfig.junkCode
                    else -> presetConfig.junkCode?.let { base ->
                        // granular flags only override the fields they set
                        val flags = checkNotNull(flagConfig.junkCode)
                        base.copy(
                            guardedBranches = if (junkEnabled) flags.guardedBranches else base.guardedBranches,
                            predicateFamily = predicateFamily ?: base.predicateFamily,
                            unreachableBlocks = rings?.first ?: base.unreachableBlocks,
                            deadCycleLength = rings?.second ?: base.deadCycleLength,
                        )
                    } ?: flagConfig.junkCode
                },
                blockCloning = flagConfig.blockCloning ?: presetConfig.blockCloning,
                controlFlowFlattening = flagConfig.controlFlowFlattening ?: presetConfig.controlFlowFlattening,
                // fields without a granular CLI flag exist only on the preset; dropping
                // them silently turned `aggressive + <any granular flag>` into a config
                // without constant encryption / register obfuscation (CLI-MERGE-1)
                constantEncryption = flagConfig.constantEncryption ?: presetConfig.constantEncryption,
                // keyRenaming / registerObfuscation have no granular flag: they exist
                // only on the preset and must survive the merge untouched
                keyRenaming = presetConfig.keyRenaming,
                registerObfuscation = presetConfig.registerObfuscation,
                stripDebugInfo = flagConfig.stripDebugInfo || presetConfig.stripDebugInfo,
                masterSeed = masterSeed,
            )
            presetConfig != null -> presetConfig.copy(masterSeed = masterSeed)
            else -> flagConfig
        }
        val obfuscationReport = obfuscation?.let { LuaObfuscator.obfuscate(chunk, it) }
        obfuscationReport?.let { report ->
            println("obfuscated: $report")
            report.controlFlowFlattening?.let { flattening ->
                println("CFF actual features: ${flattening.appliedFeatures.joinToString()}")
            }
        }
        val dumped = LuaChunkWriter.dump(chunk)
        dumpPath?.let { output ->
            if (!writeFileBytes(output, dumped)) {
                return usageFail("cannot write $output")
            }
            println("dumped $output (${dumped.size} bytes)")
            // Guarantee that Lua can load what we produced.
            val reloaded = LuaChunkLoader.load(dumped, expectedVersion)
            println("re-parsed the dumped chunk: ${reloaded.prototypes.size} prototype(s), ok")
        }
        return 0
    } catch (exception: LuaBytecodeFormatException) {
        NativePlatform.stderrPrintln("invalid Lua bytecode: ${exception.message}")
        return 1
    }
}

private fun usageFail(message: String): Int {
    NativePlatform.stderrPrintln("error: $message")
    NativePlatform.stderrPrintln("")
    NativePlatform.stderrPrintln(USAGE.trimIndent())
    return 2
}

/** Reads the whole file at [path], `null` when it cannot be opened. */
@OptIn(ExperimentalForeignApi::class)
internal fun readFileBytes(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    try {
        return memScoped {
            fseek(file, 0, SEEK_END)
            val length = ftell(file)
            fseek(file, 0, SEEK_SET)
            if (length <= 0L) {
                ByteArray(0)
            } else {
                val buffer = allocArray<ByteVar>(length)
                val read = fread(buffer, 1u, length.toULong(), file)
                ByteArray(read.toInt()) { position -> buffer[position] }
            }
        }
    } finally {
        fclose(file)
    }
}

/** Writes [bytes] to [path] (truncating), `false` when it cannot be opened. */
@OptIn(ExperimentalForeignApi::class)
internal fun writeFileBytes(path: String, bytes: ByteArray): Boolean {
    val file = fopen(path, "wb") ?: return false
    try {
        if (bytes.isEmpty()) {
            return true
        }
        memScoped {
            val buffer = allocArray<ByteVar>(bytes.size)
            for (position in bytes.indices) {
                buffer[position] = bytes[position]
            }
            fwrite(buffer, 1u, bytes.size.toULong(), file)
        }
    } finally {
        fclose(file)
    }
    return true
}
