// Reading and writing Lua binary chunks: LuaChunkReader / LuaChunkWriter /
// LuaChunkLoader as pure commonMain code (ByteArray in, ByteArray out), with
// the JVM file/stream adapters in jvmMain (platform.jvm package).
// See docs/roadmap-kmp-modules.md and docs/roadmap-kmp-progress.md (Phase 3).
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

    // Native target (roadmap step 6b): the common code compiles for the
    // linux X64 toolchain unchanged; commonTest suites run on native too.
    linuxX64()


    sourceSets {
        commonMain.dependencies {
            implementation(kotlin("stdlib"))
            implementation(project(":lua-bytecode-model"))
            implementation(project(":lua-platform"))
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
