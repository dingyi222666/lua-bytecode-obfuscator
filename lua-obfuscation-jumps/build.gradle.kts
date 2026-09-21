// Optional feature module: control-flow noise passes — jump indirection
// trampolines (LuaJumpIndirection), junk code / opaque guarded branches
// (LuaJunkCode), live block cloning with a planted trap (LuaBlockCloning),
// the tail-return syntax repair (LuaTailReturn) and the junk predicate
// builder (LuaMbaPredicates, whose sole consumer is LuaJunkCode — the CFF
// feature has its own CffPredicateFamily machinery). Depends on the
// obfuscation contracts and the bytecode layers, on NO other feature module.
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
