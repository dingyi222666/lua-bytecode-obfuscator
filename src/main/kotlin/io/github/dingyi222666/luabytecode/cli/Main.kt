package io.github.dingyi222666.luabytecode.cli

import io.github.dingyi222666.luabytecode.platform.jvm.dump
import io.github.dingyi222666.luabytecode.platform.jvm.load
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeFormatException
import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import io.github.dingyi222666.luabytecode.bytecode.edit.edit
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkLoader
import io.github.dingyi222666.luabytecode.bytecode.io.LuaChunkWriter
import io.github.dingyi222666.luabytecode.bytecode.print.LuaChunkPrinter
import io.github.dingyi222666.luabytecode.obfuscate.ConstantEncryptionConfig
import io.github.dingyi222666.luabytecode.obfuscate.CloneBlocksConfig
import io.github.dingyi222666.luabytecode.obfuscate.ControlFlowFlatteningConfig
import io.github.dingyi222666.luabytecode.obfuscate.CffPredicateFamily
import io.github.dingyi222666.luabytecode.obfuscate.JumpIndirectionConfig
import io.github.dingyi222666.luabytecode.obfuscate.JunkCodeConfig
import io.github.dingyi222666.luabytecode.obfuscate.LuaObfuscator
import io.github.dingyi222666.luabytecode.obfuscate.OpaquePredicate
import io.github.dingyi222666.luabytecode.obfuscate.ObfuscationConfig
import io.github.dingyi222666.luabytecode.obfuscate.StringEncryptionConfig
import io.github.dingyi222666.luabytecode.platform.ObfRandom
import io.github.dingyi222666.luabytecode.platform.Platform
import java.io.File

private const val USAGE = """
lua-bytecode-obfuscator — Lua 5.3 / 5.4 / 5.5 bytecode toolkit
Usage:
  lua-bytecode-obfuscator <chunk.luac> [options]
Inspect:
  --expect <5.3|5.4|5.5>  Fail unless the chunk declares this version
  --summary               Only print chunk level information, no prototype tree
  --no-code               Do not list instructions
  --no-debug              Hide line info, locals and upvalue names
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
  --cff-features <list>          Comma-separated feature names; prefix '-' to disable
  --cff-allow-allocation-effects Permit private allocation effects under the host contract
  --cff-report <path>            Save per-prototype feature and validation results
  --clone[=n]                   Live block cloning with a trap copy (Collberg §4.2)
  --strip-debug                 Drop line info, locals, upvalue names and sources
  --seed <n>                    Pin the run's master seed (reproducible artifacts).
                                Omit: every run is unique (per-run SecureRandom).
                                --seed 0: derive seeds from chunk content.

Edit:
  --replace-string <from>=<to>  Rewrite the UTF-8 text of every string constant
  --strip                       Strip all debug information (like luac -s)
  --dump <out.luac>             Write the (edited) chunk back out
  --verify                      Re-dump in memory and compare with the input
  -h, --help              Show this help
"""
fun main(args: Array<String>) {
    // Entry-point wiring: SecureRandom entropy, classpath assets, env/stderr debug.
    io.github.dingyi222666.luabytecode.platform.jvm.JvmPlatform.install()
    if (args.isEmpty() || args.contains("-h") || args.contains("--help")) {
        println(USAGE.trimIndent())
        return
    }
    var path: String? = null
    var dumpPath: String? = null
    var vmOutPath: String? = null
    var expectedVersion: LuaBytecodeVersion? = null
    var summaryOnly = false
    var includeCode = true
    var includeDebug = true
    var verify = false
    var strip = false
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
    val cffFeatures = ArrayList<String>()
    var cffAllowAllocations = false
    var cffReportPath: String? = null
    var cloneBlocks = 0
    var jumpDepth = 2
    var junkBranches = 2
    var stringMinLength = 6
    var predicateFamily: OpaquePredicate? = null
    var rings: Pair<Int, Int>? = null
    // PRED-2/LEAK-5: null = per-run SecureRandom (default), 0 = legacy content
    // derivation, anything else pins the run byte-for-byte.
    var masterSeed: Long? = null
    val replacements = mutableListOf<Pair<String, String>>()
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
                    ?: fail("--expect requires a value (5.3, 5.4 or 5.5)")
                expectedVersion = when (value) {
                    "5.3" -> LuaBytecodeVersion.LUA_5_3
                    "5.4" -> LuaBytecodeVersion.LUA_5_4
                    "5.5" -> LuaBytecodeVersion.LUA_5_5
                    else -> fail("unsupported version \"$value\", expected 5.3, 5.4 or 5.5")
                }
            }
            "--replace-string" -> {
                val value = argv.getOrNull(++index)
                    ?: fail("--replace-string requires from=to")
                val separator = value.indexOf('=')
                if (separator <= 0) {
                    fail("--replace-string expects from=to, got \"$value\"")
                }
                replacements += value.substring(0, separator) to value.substring(separator + 1)
            }
            "--dump" -> dumpPath = argv.getOrNull(++index)
            "--vm-out" -> vmOutPath = argv.getOrNull(++index)
                ?: fail("--dump requires an output path")
            "--obfuscate" -> {
                val value = argv.getOrNull(++index)
                    ?: fail("--obfuscate requires a preset (light, balanced, aggressive)")
                preset = when (value) {
                    "light" -> ObfuscationConfig.LIGHT
                    "balanced" -> ObfuscationConfig.BALANCED
                    "aggressive" -> ObfuscationConfig.AGGRESSIVE
                    else -> fail("unknown obfuscation preset \"$value\"")
                }
            }

            "--constants" -> constantsEnabled = true
            "--jmp-decoys" -> {
                jumpDecoys = argv.getOrNull(++index)?.toIntOrNull() ?: fail("--jmp-decoys requires 0..1024")
                if (jumpDecoys !in 0..1024) fail("--jmp-decoys requires 0..1024")
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
                    else -> fail("unknown CFF configuration: $value")
                }
            }
            "--cff-features" -> {
                flattenEnabled = true
                cffFeatures += (argv.getOrNull(++index) ?: fail("--cff-features requires a list")).split(',')
            }
            "--cff-allow-allocation-effects" -> cffAllowAllocations = true
            "--cff-report" -> cffReportPath = argv.getOrNull(++index) ?: fail("--cff-report requires a path")
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
                val value = argv.getOrNull(++index) ?: fail("--predicate requires a family")
                predicateFamily = runCatching { OpaquePredicate.valueOf(value.uppercase()) }
                    .getOrElse { fail("unknown predicate family \"$value\"") }
            }

            "--rings" -> {
                val value = argv.getOrNull(++index) ?: fail("--rings requires blocks[:length]")
                val parts = value.split(':')
                val blocks = parts[0].toIntOrNull() ?: fail("--rings needs an integer block count")
                val length = parts.getOrNull(1)?.toIntOrNull() ?: 2
                rings = blocks to length
            }

            "--seed" -> {
                val value = argv.getOrNull(++index) ?: fail("--seed requires a Long value")
                masterSeed = value.toLongOrNull() ?: fail("--seed needs a Long (0 = legacy content seed)")
            }

            "--summary" -> summaryOnly = true
            "--no-code" -> includeCode = false
            "--no-debug" -> includeDebug = false
            "--verify" -> verify = true
            "--strip" -> strip = true
            else -> if (argument.startsWith("-")) {
                fail("unknown option $argument")
            } else {
                path = argument
            }
        }
        index++
    }
    val file = File(path ?: fail("missing chunk file"))
    if (!file.isFile) {
        fail("no such file: ${file.absolutePath}")
    }
    try {
        val chunk = LuaChunkLoader.load(file, expectedVersion)
        if (vmOutPath != null) {
            // PRED-2: the VM path used `java.util.Random()` (the one truly
            // unseeded source). Same policy as the obfuscation pipeline:
            // default per-run SecureRandom, `--seed` pins, `--seed 0` legacy.
            val vmRandom = when (masterSeed) {
                null -> ObfRandom(Platform.entropy.nextLong())
                else -> ObfRandom(masterSeed)
            }
            val module = io.github.dingyi222666.luabytecode.vm.LuaVmObfuscation.emitModule(
                chunk,
                vmRandom,
            )
            if (module == null) {
                // A refusal is a *correct* outcome (the chunk is outside the modelled subset),
                // never a wrong program. It exits 3 so a caller can tell it apart from a crash
                // (any other non zero status) and from success: `tools/verify-vm.sh` scores
                // 0-with-a-module as `identical`/`failed`, 3 as `refused`, anything else as
                // `failed`. Exiting 0 here made a crashed tool indistinguishable from a refusal
                // (adversarial audit M6).
                System.err.println("error: this chunk uses instructions outside the VM subset")
                kotlin.system.exitProcess(3)
            }
            java.io.File(vmOutPath!!).writeText(module)
            println("wrote VM module to $vmOutPath")
            return
        }
        if (summaryOnly) {
            println(chunk.header)
            println("main closure upvalues: ${chunk.mainUpvalueCount}")
            println("prototypes: ${chunk.prototypes.size}")
            chunk.prototypes.forEachIndexed { prototypeIndex, prototype ->
                println(
                    "  #$prototypeIndex ${prototype.source?.utf8 ?: "?"} " +
                        "lines=${prototype.lineDefined}-${prototype.lastLineDefined} " +
                        "code=${prototype.instructionCount} constants=${prototype.constantCount} " +
                        "upvalues=${prototype.upvalueCount} protos=${prototype.nestedPrototypeCount}",
                )
            }
        } else {
            println(LuaChunkPrinter.print(chunk, includeCode = includeCode, includeDebug = includeDebug))
        }
        println("parsed ${file.name}: ${chunk.prototypes.size} prototype(s), ok")
        // Granular flags override only the preset fields explicitly supplied.
        for (feature in cffFeatures) {
            val enabled = !feature.startsWith('-')
            cffConfig = when (val name = feature.removePrefix("-").removePrefix("+")) {
                "state.runtime" -> cffConfig.copy(stateRuntime = enabled)
                "state.namespaces" -> cffConfig.copy(stateNamespaces = enabled)
                "dispatch.segmented" -> cffConfig.copy(segmentedDispatch = enabled)
                "dispatch.multiple" -> cffConfig.copy(multipleDispatch = enabled)
                "dispatch.nested" -> cffConfig.copy(nestedDispatch = enabled)
                "dispatch.effects" -> cffConfig.copy(dispatchEffects = enabled)
                "indegree" -> cffConfig.copy(indegreeGuards = enabled)
                "layout" -> cffConfig.copy(shuffleBlocks = enabled)
                "split" -> cffConfig.copy(splitStraightLine = enabled)
                "state.table" -> cffConfig.copy(stateTable = enabled)
                "junk.participating" -> cffConfig.copy(participatingJunk = enabled)
                else -> {
                    val family = name.removePrefix("predicate.").uppercase().let {
                        runCatching { CffPredicateFamily.valueOf(it) }.getOrElse { fail("unknown CFF feature: $name") }
                    }
                    cffConfig.copy(predicateFamilies = if (enabled) cffConfig.predicateFamilies + family else cffConfig.predicateFamilies - family)
                }
            }
        }
        cffConfig = cffConfig.copy(allocationEffectsUnobservable = cffAllowAllocations)
        val presetConfig = preset
        val flagConfig = if (constantsEnabled || stringsEnabled || jumpsEnabled || junkEnabled || stripDebug || flattenEnabled ||
            cloneBlocks > 0 ||
            predicateFamily != null || rings != null
        ) {
                    ObfuscationConfig(
                        constantEncryption = if (constantsEnabled) ConstantEncryptionConfig() else null,
                        stringEncryption = if (stringsEnabled) StringEncryptionConfig(minLength = stringMinLength, encryptKeys = encryptKeys || presetConfig?.stringEncryption?.encryptKeys == true) else null,
                        jumpIndirection = if (jumpsEnabled) JumpIndirectionConfig(depth = jumpDepth, runtimeRoutes = advancedJumps || presetConfig?.jumpIndirection?.runtimeRoutes == true, decoyBlocks = jumpDecoys ?: if (advancedJumps) 12 else presetConfig?.jumpIndirection?.decoyBlocks ?: 0) else null,
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
                                corruptTrapCopy = System.getenv("CLONE_NO_CORRUPT") == null,
                            )
                        } else {
                            null
                        },
                        // the minimal flattener is verified (byte identical on the smallest
                        // failing case, and the official matrix exercises it), so `--flatten`
                        // is live again (goal round 27)
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
                        val flags = flagConfig.junkCode
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
                // B3 (CLI-MERGE-1 追认): keyRenaming 无 granular flag, 只存在于 preset; 不带上会在
                // preset×flag 路径静默丢失(与 CLI-MERGE-1 同性质)。
                keyRenaming = presetConfig.keyRenaming,
                registerObfuscation = presetConfig.registerObfuscation,
                stripDebugInfo = flagConfig.stripDebugInfo || presetConfig.stripDebugInfo,
                masterSeed = masterSeed,
            )
            presetConfig != null -> presetConfig.copy(masterSeed = masterSeed)
            else -> flagConfig
        }
        val obfuscationReport = obfuscation?.let { LuaObfuscator.obfuscate(chunk, it) }
        obfuscationReport?.let { println("obfuscated: $it") }
        obfuscationReport?.controlFlowFlattening?.let { report ->
            println("CFF actual features: ${report.appliedFeatures.joinToString()}")
            cffReportPath?.let { path ->
                fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
                fun array(values: List<String>) = values.joinToString(prefix = "[", postfix = "]", transform = ::quoted)
                File(path).writeText(report.prototypes.joinToString(prefix = "[\n", postfix = "\n]\n") { p ->
                    "{\"prototype\":${p.prototypeIndex},\"status\":${quoted(p.status)}," +
                        "\"requested\":${array(p.requestedFeatures)},\"actual\":${array(p.actualFeatures)}," +
                        "\"reasons\":${array(p.reasons)},\"verifiedControlPaths\":${p.verifiedControlPaths}," +
                        "\"verificationSteps\":${p.verificationSteps}," +
                        "\"variableSeedSites\":${p.proof?.variableSeedSites ?: 0}}"
                })
            }
        }

        val edited = replacements.isNotEmpty() || strip || obfuscationReport != null
        if (edited) {
            val replaced = chunk.edit {
                eachPrototype {
                    for ((from, to) in replacements) {
                        replaceStringText(from, to)
                    }
                }
                if (strip) {
                    strip()
                }
            }.let { replacements.size }
            println(
                "edited: $replaced replacement rule(s)" +
                    (if (strip) ", debug information stripped" else ""),
            )
        }
        val dumped = LuaChunkWriter.dump(chunk)
        if (verify) {
            val original = file.readBytes()
            when {
                dumped.contentEquals(original) ->
                    println("round trip: byte identical (${dumped.size} bytes)")
                edited ->
                    println("round trip: output differs from the input because edits were applied")
                else -> {
                    System.err.println(
                        "round trip: MISMATCH (input ${original.size} bytes, output ${dumped.size} bytes)",
                    )
                    kotlin.system.exitProcess(1)
                }
            }
        }
        dumpPath?.let { output ->
            val outputFile = File(output)
            outputFile.writeBytes(dumped)
            println("dumped ${outputFile.absolutePath} (${outputFile.length()} bytes)")
            // Guarantee that Lua can load what we produced.
            val reloaded = LuaChunkLoader.load(outputFile, expectedVersion)
            println("re-parsed the dumped chunk: ${reloaded.prototypes.size} prototype(s), ok")
        }
    } catch (exception: LuaBytecodeFormatException) {
        System.err.println("invalid Lua bytecode: ${exception.message}")
        kotlin.system.exitProcess(1)
    }
}
private fun fail(message: String): Nothing {
    System.err.println("error: $message")
    System.err.println()
    System.err.println(USAGE.trimIndent())
    kotlin.system.exitProcess(2)
}
