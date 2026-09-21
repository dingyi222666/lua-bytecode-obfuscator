plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    // Declared here so :lua-bytecode-model can apply it without a version:
    // the kotlin-gradle-plugin jar is already on the shared build classpath
    // (pulled in by the jvm plugin), and Gradle rejects versioned subproject
    // requests for plugins whose classpath version cannot be checked.
    id("org.jetbrains.kotlin.multiplatform") version "2.4.20" apply false
    application
}

group = "io.github.dingyi222666"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Pure platform seams and helpers (hex/md5/random/identity collections and
    // the entropy/asset/debug service contracts), KMP commonMain.
    implementation(project(":lua-platform"))
    // Pure bytecode model types (opcode/instruction/version tables), KMP commonMain.
    implementation(project(":lua-bytecode-model"))
    // Legacy source level string obfuscation only.
    implementation("io.github.dingyi222666:luaparser:1.0.3")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.dingyi222666.luabytecode.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}