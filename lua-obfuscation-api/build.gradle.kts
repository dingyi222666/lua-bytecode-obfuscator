// Obfuscation contract layer: the LuaObfuscationModule extension point, the
// ObfuscationConfig presets and per-pass option types, the per-pass report /
// statistics types, SeedPolicy and the helpers shared by more than one feature
// module (arithmetic DSL, editing extensions, CFF control-site record +
// translation validator, env lookup, LCG keystream primitives and the decode
// skeleton variants).
//
// Feature modules (:lua-obfuscation-*) depend on this module plus
// model/io/edit/analysis/platform and on NOTHING from each other; whenever two
// features need the same helper it is promoted here (see the promotion list in
// docs/roadmap-kmp-progress.md, Phase 4).
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
            implementation(project(":lua-bytecode-edit"))
            // CffTranslationValidator checks jump targets; LuaObfuscationEditing
            // re-anchors absolute line tables on relocation.
            implementation(project(":lua-bytecode-analysis"))
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
