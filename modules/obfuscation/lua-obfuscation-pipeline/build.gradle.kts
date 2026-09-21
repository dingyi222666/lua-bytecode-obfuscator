// Pipeline assembly module (roadmap step 6b): LuaObfuscator (the pass
// orchestrator), LuaKeyRenaming, LuaHostSeed and the LuaVmPipeline composition
// root, sunk from the root JVM frontends so the JVM library entry, the web
// runtime (:lua-runtime-js) and the native CLI (:lua-cli-native) share ONE
// orchestration. Depends on every feature module plus :lua-vm — on NO
// host/frontend module (cli/, decompile/ and the platform assemblers stay in
// their host modules). See docs/roadmap-kmp-modules.md and
// docs/roadmap-kmp-progress.md (Phase 6b).
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

    // Web target (roadmap step 6): the orchestrator compiles for the browser /
    // node unchanged (:lua-runtime-js drives it through the common API); tests
    // run on node only — no karma and no headless browser inside CI.
    js {
        nodejs {
            testTask {
                // The pipeline contract test runs the real pass stack; on node
                // a full obfuscation can take a few seconds (same budget as
                // :lua-runtime-js and the api module e2e tests).
                useMocha {
                    timeout = "120000"
                }
            }
        }
    }

    // Native target (roadmap step 6b): the orchestrator compiles for the
    // linux X64 toolchain unchanged — :lua-cli-native assembles the native
    // CLI on top of it; the asset free commonTest contract suite runs on
    // native too.
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation(kotlin("stdlib"))
            implementation(project(":lua-platform"))
            implementation(project(":lua-bytecode-model"))
            implementation(project(":lua-bytecode-edit"))
            implementation(project(":lua-bytecode-analysis"))
            implementation(project(":lua-obfuscation-api"))
            // Every optional feature pass, then the optional VM feature: the
            // orchestrator sits above all of them, below the hosts.
            implementation(project(":lua-obfuscation-strings"))
            implementation(project(":lua-obfuscation-constants"))
            implementation(project(":lua-obfuscation-jumps"))
            implementation(project(":lua-obfuscation-cff"))
            implementation(project(":lua-obfuscation-register"))
            implementation(project(":lua-vm"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // The pipeline contract test loads its embedded fixture through
            // the common loader and re-loads the dumped output.
            implementation(project(":lua-bytecode-io"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":lua-bytecode-io"))
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
