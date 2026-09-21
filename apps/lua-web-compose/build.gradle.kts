// Compose Multiplatform web host (Compose Web 改造 Round 3). A wasmJs browser
// executable that assembles the same common library pipeline as
// :lua-runtime-js / :lua-cli-native behind a Compose material3 UI:
//
//   * WasmPlatform         — the wasmJs implementations of the platform seams
//                            (crypto.getRandomValues entropy, committed base64
//                            decoder assets, silent debug sink);
//   * LuaDecoderAssetsWasm — GENERATED base64 constants byte-identical to the
//                            JVM classpath assets (tools/generate-js-decoder-assets.py);
//   * WebObfuscator        — the plain-Kotlin bytes-in / report+bytes-out API
//                            (main thread, no @JsExport on the hot path) plus a
//                            flat @JsExport shape for a future worker;
//   * WebBridge            — file picking and download (js() fragments +
//                            kotlinx-browser DOM);
//   * App / Main           — ComposeViewport(document.body!!) bootstrap.
//
// CI never runs a browser: wasmJsBrowserTest is disabled below and the
// verification of this host is the production-distribution smoke in verify.yml.
plugins {
    // Versions are declared in the root build.gradle.kts (apply false).
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "io.github.dingyi222666"
version = "1.0-SNAPSHOT"

kotlin {
    wasmJs {
        // Pin the module identity: the shipped page and the CI dist smoke
        // reference the composeApp entry (the webpack output file name itself
        // is pinned via commonWebpackConfig below — it defaults to the Gradle
        // project name, not to outputModuleName).
        outputModuleName.set("composeApp")
        browser {
            commonWebpackConfig {
                // Without this the dist entry is named after the Gradle
                // project (lua-web-compose.js); pin it so index.html and the
                // CI smoke can rely on a stable name.
                outputFileName.set("composeApp.js")
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(kotlin("stdlib"))
            // The full common library stack, wasmJs variants included
            // (13 modules — mirrors apps/lua-runtime-js). :lua-vm is pulled in
            // for parity with the other hosts; the web UI does not expose VM
            // staging yet.
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
            implementation(project(":lua-obfuscation-pipeline"))
            implementation(project(":lua-vm"))
            // Compose UI through explicit maven coordinates: since CMP 1.10
            // the plugin's dependency aliases are deprecated, coordinates are
            // the supported spelling. material3 keeps its own release line —
            // the CMP 1.12.0 plugin catalog pins it to 1.9.0 (the 1.10+
            // material3 line is alpha-only), so 1.9.0 is the matching stable.
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
            implementation("org.jetbrains.compose.ui:ui:1.12.0")
            implementation("org.jetbrains.compose.runtime:runtime:1.12.0")
            // org.w3c.dom / kotlinx.browser for the wasmJs host.
            implementation("org.jetbrains.kotlinx:kotlinx-browser:0.5.0")
        }
    }
}

// The self-hosted CI runner has no browser and the project never runs one
// (see the :lua-runtime-js header for the same rule on the js side). With
// browser{} as the only wasmJs environment the default test task would try to
// drive a browser — disable it so `build`/`check` stay browser-free.
tasks.matching { it.name == "wasmJsBrowserTest" }.configureEach {
    enabled = false
}
