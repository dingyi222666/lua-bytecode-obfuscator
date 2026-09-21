// Optional feature module: numeric / boolean constant pool encryption
// (LuaConstantEncryptor, Pass K, LEAK-2 + the ConstCipher keystream). Depends
// on the obfuscation contracts, the bytecode layers and the platform seams —
// and on NO other feature module (feature modules are mutually independent).
// The bundled kget decoder assets (resources/obfuscator/decode_num_NN.luac)
// ship with this module so the artifact is self-contained.
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
