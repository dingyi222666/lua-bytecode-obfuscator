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

