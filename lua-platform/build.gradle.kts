// Pure common platform seams and helpers (hex, md5, bit-identical Random,
// identity collections, entropy/asset/debug service contracts). See
// docs/roadmap-kmp-modules.md and docs/roadmap-kmp-progress.md (Phase 3).
//
// Platform implementations live in the root module under platform/jvm
// (JvmPlatform.install()); they are JVM adapters, not part of this module.
plugins {
    // Version is declared in the root build.gradle.kts (apply false): the
    // plugin is already on the shared classpath via the kotlin jvm plugin.
    kotlin("multiplatform")
}

group = "io.github.dingyi222666"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)

    jvm()

    sourceSets {
        commonMain.dependencies {
            // kotlin-stdlib is the only allowed dependency: no java.*, no
            // file system, no processes.
            implementation(kotlin("stdlib"))
        }
    }
}
