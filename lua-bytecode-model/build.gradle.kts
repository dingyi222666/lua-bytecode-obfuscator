// Pure Lua bytecode model: chunk header, prototypes, instructions, opcodes and
// constants as plain Kotlin data structures (see docs/roadmap-kmp-modules.md).
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
            // The model must stay dependency free: kotlin-stdlib is the only
            // allowed dependency, no java.*, no file system, no processes.
            implementation(kotlin("stdlib"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
