// Pure Lua bytecode model: chunk header, prototypes, instructions, opcodes and
// constants as plain Kotlin data structures (see docs/roadmap-kmp-modules.md).
plugins {
    kotlin("multiplatform") version "2.4.20"
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
    }
}
