// In-place editing of the Lua bytecode tree: LuaPrototypeEdit, the chunk edit
// DSL (LuaChunkDsl / LuaChunkScope / LuaInstructionStream), the expression DSL
// and the single instruction construction boundary (LuaInstructionFactory).
// Also hosts the edit-support analysis extensions (LuaCodeRelocation /
// LuaLineInfo) that the DSL itself is built on; see
// docs/roadmap-kmp-progress.md (Phase 3, ownership decision).
plugins {
    // Version is declared in the root build.gradle.kts (apply false): the
    // plugin is already on the shared classpath via the kotlin jvm plugin.
    kotlin("multiplatform")
    // Final acceptance (roadmap step 7): every library module is independently
    // publishable (`publishToMavenLocal` / `publish`), not just the features.
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
            implementation(project(":lua-bytecode-model"))
        }
        jvmMain.dependencies {
            // editChunkFile composes the DSL with the io file/stream adapters.
            implementation(project(":lua-bytecode-io"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
