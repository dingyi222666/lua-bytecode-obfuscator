pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "lua-bytecode-obfuscator"

include(":lua-platform")
include(":lua-bytecode-model")
include(":lua-bytecode-io")
include(":lua-bytecode-edit")
include(":lua-bytecode-analysis")
include(":lua-obfuscation-api")
include(":lua-obfuscation-strings")
include(":lua-obfuscation-constants")
include(":lua-obfuscation-jumps")
include(":lua-obfuscation-cff")
include(":lua-obfuscation-register")
include(":lua-vm")
// Pipeline assembly (roadmap step 6b): the pass orchestrator (LuaObfuscator),
// the key renaming pass, the host seed primitives and the LuaVmPipeline
// composition root, shared by the JVM library entry, the web runtime and the
// native CLI.
include(":lua-obfuscation-pipeline")
// Native CLI (roadmap step 6b): the default command line entry of the
// project — linuxX64 assembly of the pipeline with the native platform seam
// implementations and the embedded decoder assets.
include(":lua-cli-native")
// Web runtime (roadmap step 6): js-only assembly of the common pipeline with
// the platform seam implementations, the bundled decoder assets as committed
// base64 constants and the minimal static web UI.
include(":lua-runtime-js")

