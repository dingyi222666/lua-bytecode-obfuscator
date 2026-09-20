plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    application
}

group = "io.github.dingyi222666"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Legacy source level string obfuscation only.
    implementation("io.github.dingyi222666:luaparser:1.0.1")
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