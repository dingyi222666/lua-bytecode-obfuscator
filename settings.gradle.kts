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

// Monorepo layout: Gradle project paths are stable logical identities while the
// physical directories are grouped into modules/{platform,bytecode,obfuscation}
// and apps/ (see docs/roadmap-kmp-progress.md).
include(":lua-platform")
project(":lua-platform").projectDir = file("modules/platform/lua-platform")
include(":lua-bytecode-model")
project(":lua-bytecode-model").projectDir = file("modules/bytecode/lua-bytecode-model")
include(":lua-bytecode-io")
project(":lua-bytecode-io").projectDir = file("modules/bytecode/lua-bytecode-io")
include(":lua-bytecode-edit")
project(":lua-bytecode-edit").projectDir = file("modules/bytecode/lua-bytecode-edit")
include(":lua-bytecode-analysis")
project(":lua-bytecode-analysis").projectDir = file("modules/bytecode/lua-bytecode-analysis")
include(":lua-obfuscation-api")
project(":lua-obfuscation-api").projectDir = file("modules/obfuscation/lua-obfuscation-api")
include(":lua-obfuscation-strings")
project(":lua-obfuscation-strings").projectDir = file("modules/obfuscation/lua-obfuscation-strings")
include(":lua-obfuscation-constants")
project(":lua-obfuscation-constants").projectDir = file("modules/obfuscation/lua-obfuscation-constants")
include(":lua-obfuscation-jumps")
project(":lua-obfuscation-jumps").projectDir = file("modules/obfuscation/lua-obfuscation-jumps")
include(":lua-obfuscation-cff")
project(":lua-obfuscation-cff").projectDir = file("modules/obfuscation/lua-obfuscation-cff")
include(":lua-obfuscation-register")
project(":lua-obfuscation-register").projectDir = file("modules/obfuscation/lua-obfuscation-register")
include(":lua-vm")
project(":lua-vm").projectDir = file("modules/obfuscation/lua-vm")
// Pipeline assembly (roadmap step 6b): the pass orchestrator (LuaObfuscator),
// the key renaming pass, the host seed primitives and the LuaVmPipeline
// composition root, shared by the JVM library entry, the web runtime and the
// native CLI.
include(":lua-obfuscation-pipeline")
project(":lua-obfuscation-pipeline").projectDir = file("modules/obfuscation/lua-obfuscation-pipeline")
// Native CLI (roadmap step 6b): the default command line entry of the
// project — linuxX64 assembly of the pipeline with the native platform seam
// implementations and the embedded decoder assets.
include(":lua-cli-native")
project(":lua-cli-native").projectDir = file("apps/lua-cli-native")
// Web runtime (roadmap step 6): js-only assembly of the common pipeline with
// the platform seam implementations, the bundled decoder assets as committed
// base64 constants and the minimal static web UI.
include(":lua-runtime-js")
project(":lua-runtime-js").projectDir = file("apps/lua-runtime-js")

