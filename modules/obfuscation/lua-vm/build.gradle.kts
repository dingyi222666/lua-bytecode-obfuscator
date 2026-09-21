// Optional feature module: the private-ISA virtual machine (LuaVmTranslator,
// LuaVmProgram + encrypted program packaging, LuaVmInterpreter, the generated
// Lua interpreter emitter and the source-level staging obfuscator for the
// generated host). Depends on the obfuscation contracts (:lua-obfuscation-api)
// and the bytecode layers — on NO other feature module and NOT on the
// orchestrator: the input-staging executors (library pipeline, source CFF) are
// injected by the root module as stage functions (see LuaVmPipeline there).
// The VM report distinguishes source.cff, vm.lift, interpreter.source.cff and
// the not-yet-implemented target bytecode-level CFF (roadmap step 5 contract).
// See docs/roadmap-kmp-modules.md and docs/roadmap-kmp-progress.md (Phase 5).
plugins {
    // Version is declared in the root build.gradle.kts (apply false): the
    // plugin is already on the shared classpath via the kotlin jvm plugin.
    kotlin("multiplatform")
    `maven-publish`
}

group = "io.github.dingyi222666"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)

    jvm()

    // Web target (roadmap step 6): the common code compiles for the browser /
    // node unchanged; tests run on node only (no karma / headless browser).
    js {
        nodejs()
    }

    // Web (wasm) target: same pure-common code, tests run on Node.js only —
    // no browser in CI (mirrors the js target policy).
    wasmJs {
        nodejs()
    }

    // Native target (roadmap step 6b): the common code compiles for the
    // linux X64 toolchain unchanged; commonTest suites run on native too.
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation(kotlin("stdlib"))
            implementation(project(":lua-platform"))
            implementation(project(":lua-bytecode-model"))
            // jumpTarget and the read-only analysis helpers used by the translator.
            implementation(project(":lua-bytecode-analysis"))
            // Contracts: ObfuscationConfig / ObfuscationReport /
            // ControlFlowFlatteningReport types exchanged with the host wiring.
            implementation(project(":lua-obfuscation-api"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // Fixture loading via LuaChunkLoader, the dump/load JVM adapters and
            // the edit DSL used by the semantic bridge tests.
            implementation(project(":lua-bytecode-io"))
            implementation(project(":lua-bytecode-edit"))
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
