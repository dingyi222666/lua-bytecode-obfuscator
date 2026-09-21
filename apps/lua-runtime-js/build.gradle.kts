// Web runtime (roadmap step 6, jsMain acceptance: "the web client only calls
// the common API; file reading, worker and download are implemented by
// jsMain"). A js-only module that assembles the library pipeline for the
// browser / node:
//
//   * JsPlatform — the JS implementations of the platform seams (webcrypto
//     entropy, committed base64 decoder assets, console debug sink);
//   * LuaDecoderAssetsJs — GENERATED base64 constants byte-identical to the
//     JVM classpath decoder assets (tools/generate-js-decoder-assets.py);
//   * WebObfuscator — the @JsExport bytes-in / report+bytes-out API composed
//     ONLY of the common APIs of the library modules above;
//   * src/jsMain/resources/web — the minimal static UI (page + worker +
//     download), no bundler: the default UMD output of the linked production
//     executable is loaded with importScripts inside the worker.
//
// Not wired into build/check anywhere: no headless browser ever runs in CI.
// `webDist` (below) assembles the runnable page on demand.
plugins {
    // Version is declared in the root build.gradle.kts (apply false): the
    // plugin is already on the shared classpath via the kotlin jvm plugin.
    kotlin("multiplatform")
}

group = "io.github.dingyi222666"
version = "1.0-SNAPSHOT"

kotlin {
    js {
        // Pin the UMD module name: the worker resolves the runtime through
        // globalThis["luabytecodeWebRuntime"], so it must not depend on the
        // Gradle-derived default naming scheme.
        outputModuleName.set("luabytecodeWebRuntime")
        nodejs {
            testTask {
                // End-to-end obfuscation over real chunks (and the asset suite)
                // can take a few seconds per test on node.
                useMocha {
                    timeout = "120000"
                }
            }
        }
        // The production executable (default module kind: UMD) is what the
        // static web UI loads; `main` exists as its node entry point.
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(kotlin("stdlib"))
            // The full common library stack, js variants included. :lua-vm is
            // pulled in for parity with the JVM frontends; the web API does
            // not expose VM staging yet (see the Phase 6 notes in
            // docs/roadmap-kmp-progress.md).
            implementation(project(":lua-platform"))
            implementation(project(":lua-bytecode-model"))
            implementation(project(":lua-bytecode-io"))
            implementation(project(":lua-bytecode-edit"))
            implementation(project(":lua-bytecode-analysis"))
            implementation(project(":lua-obfuscation-api"))
            implementation(project(":lua-obfuscation-strings"))
            implementation(project(":lua-obfuscation-constants"))
            implementation(project(":lua-obfuscation-jumps"))
            implementation(project(":lua-obfuscation-cff"))
            implementation(project(":lua-obfuscation-register"))
            // The sunk orchestrator + plugin registry: WebObfuscator assembles
            // ObfuscationRegistry.builtIn() and calls LuaObfuscator directly
            // (the former orchestrator mirror is gone).
            implementation(project(":lua-obfuscation-pipeline"))
            implementation(project(":lua-vm"))
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Developer convenience: assemble the linked production executable (UMD
// bundle, renamed luabytecode-runtime.js) into build/web. Since Compose Web
// 改造 Round 3 the static page + worker live in :lua-web-compose (Compose UI,
// wasmJs) — this module ships the API/bundle only. NOT part of the build /
// check dependency chains: CI never runs a browser or a bundler. Run
// explicitly: ./gradlew :lua-runtime-js:webDist
val webDist by tasks.registering(Sync::class) {
    val jsTarget = kotlin.targets.getByName("js") as
        org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
    val production = jsTarget.binaries.executable().first {
        it.mode == org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsBinaryMode.PRODUCTION
    }
    from(production.mainFileSyncPath) {
        // Stable name the CI smoke and node hosts reference.
        rename { "luabytecode-runtime.js" }
    }
    into(layout.buildDirectory.dir("web"))
}
