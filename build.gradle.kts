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
    // Reader/writer/loader of Lua binary chunks, KMP commonMain + JVM adapters.
    implementation(project(":lua-bytecode-io"))
    // In-place bytecode editing DSL and prototype edit API, KMP commonMain.
    implementation(project(":lua-bytecode-edit"))
    // Data flow / constant reference / register safety analysis and the chunk
    // printer, KMP commonMain.
    implementation(project(":lua-bytecode-analysis"))
    // Obfuscation contracts: extension point, pipeline config/report types,
    // seed policy and the helpers shared across feature modules.
    implementation(project(":lua-obfuscation-api"))
    // Optional feature passes, each independently switchable via ObfuscationConfig:
    implementation(project(":lua-obfuscation-strings"))
    implementation(project(":lua-obfuscation-constants"))
    implementation(project(":lua-obfuscation-jumps"))
    implementation(project(":lua-obfuscation-cff"))
    implementation(project(":lua-obfuscation-register"))
    // Optional VM feature module: translator, private ISA, encrypted program,
    // generated Lua interpreter (roadmap step 5). Input staging is injected via
    // LuaVmPipeline below; no other feature depends on it.
    implementation(project(":lua-vm"))
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