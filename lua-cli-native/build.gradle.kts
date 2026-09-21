// Native CLI (roadmap step 6b — acceptance: "Native CLI is the only default
// CLI; the JVM entry is no longer the default application artifact").
//
// A linuxX64-only module that assembles the library pipeline for the command
// line:
//
//   * NativePlatform — the native implementations of the platform seams
//     (/dev/urandom entropy, getenv/stderr debug switches, the committed
//     embedded decoder assets, the /tmp vm-subset sentinel trace);
//   * LuaDecoderAssetsNative — GENERATED base64 constants byte-identical to
//     the JVM classpath and js deliveries (tools/generate-js-decoder-assets.py);
//   * NativeCli — the entry point with a flag surface aligned to the JVM
//     cli/Main.kt (files in / files out, lua version, seed, feature switches,
//     --vm-out), byte-level io through posix.
//
// Depends on the pipeline and below — on NO root frontend module.
plugins {
    // Version is declared in the root build.gradle.kts (apply false): the
    // plugin is already on the shared classpath via the kotlin jvm plugin.
    kotlin("multiplatform")
}

group = "io.github.dingyi222666"
version = "1.0-SNAPSHOT"

kotlin {
    // Pin the target name "native" so the source sets and tasks read
    // nativeMain / linkReleaseExecutableNative / nativeTest.
    linuxX64("native") {
        binaries {
            executable {
                // The entry point lives in the cli package (next to the JVM
                // cli/Main.kt it mirrors); the K/N linker only searches the
                // root package without this hint.
                entryPoint = "io.github.dingyi222666.luabytecode.cli.main"
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(kotlin("stdlib"))
            // The sunk pipeline assembly (orchestrator + every feature + vm).
            implementation(project(":lua-obfuscation-pipeline"))
            // Byte-level chunk load/dump around the posix file io of the CLI.
            implementation(project(":lua-bytecode-io"))
        }
        nativeTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
