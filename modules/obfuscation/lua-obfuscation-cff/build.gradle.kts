// Optional feature module: control flow flattening (LuaControlFlowFlattening,
// with transactional candidates, proof manifests and the CFF control-packet
// emitter) plus the call-free constant expansion over CFF proofs
// (CffConstantExpansion, driven by the root orchestrator between flattening
// and Pass K). Depends on the obfuscation contracts and the bytecode layers,
// on NO other feature module. The translation validator and the control-site
// record it shares with the jumps feature live in :lua-obfuscation-api.
// See docs/roadmap-kmp-modules.md and docs/roadmap-kmp-progress.md (Phase 4).
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

    // Native target (roadmap step 6b): the common code compiles for the
    // linux X64 toolchain unchanged; commonTest suites run on native too.
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation(kotlin("stdlib"))
            implementation(project(":lua-platform"))
            implementation(project(":lua-bytecode-model"))
            implementation(project(":lua-bytecode-io"))
            implementation(project(":lua-bytecode-edit"))
            implementation(project(":lua-bytecode-analysis"))
            implementation(project(":lua-obfuscation-api"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
