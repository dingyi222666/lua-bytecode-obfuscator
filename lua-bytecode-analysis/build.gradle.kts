// Bytecode analysis: register data flow (LuaDataFlow), constant table
// references (LuaConstantReferences incl. jump targets / basic blocks),
// register-safety analysis (bytecode.support.LuaRegisterAnalysis) and the
// read-only chunk renderer used for inspection and test verification
// (bytecode.print.LuaChunkPrinter).
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
    js(IR) {
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(kotlin("stdlib"))
            implementation(project(":lua-bytecode-model"))
            // LuaConstantReferences.relocated() delegates to retargeted(),
            // which ships with the edit module (same package, other module).
            implementation(project(":lua-bytecode-edit"))
            // LuaChunkPrinter formats bytes via platform.hex2.
            implementation(project(":lua-platform"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // TestFixtures load luac fixtures through LuaChunkLoader. Test-only:
            // commonMain stays free of the io module.
            implementation(project(":lua-bytecode-io"))
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
