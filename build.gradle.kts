plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    // Declared here so :lua-bytecode-model can apply it without a version:
    // the kotlin-gradle-plugin jar is already on the shared build classpath
    // (pulled in by the jvm plugin), and Gradle rejects versioned subproject
    // requests for plugins whose classpath version cannot be checked.
    id("org.jetbrains.kotlin.multiplatform") version "2.4.20" apply false
    // Compose Multiplatform web host (:lua-web-compose, Compose Web 改造
    // Round 3): declared here apply false so the subproject can apply both
    // plugins without a version — the CMP gradle plugin resolves from the
    // plugin repositories and the compose compiler plugin ships inside the
    // same kotlin-gradle-plugin 2.4.20 jar already on this classpath.
    id("org.jetbrains.compose") version "1.12.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
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
    // The sunk pipeline assembly (roadmap step 6b): the pass orchestrator
    // (LuaObfuscator), key renaming, host seed primitives and the LuaVmPipeline
    // composition root. The root module keeps only the JVM frontends (CLI,
    // decompiler) plus the platform assembler on top of it.
    implementation(project(":lua-obfuscation-pipeline"))
    // Legacy source level string obfuscation only.
    implementation("io.github.dingyi222666:luaparser:1.0.3")
    testImplementation(kotlin("test"))
}

// Roadmap step 6b acceptance: the native CLI (:lua-cli-native) is the only
// default CLI. The JVM entry (cli/Main.kt) stays in this module as a library
// entry / test harness — no `application` plugin and no mainClass here anymore,
// so no JVM application artifact is published or assembled by default.

// The CLI runtime classpath, handed to VmDisablementTest (it spawns a fresh JVM
// to prove the disablement contract; Gradle test workers do not expose a usable
// java.class.path for the application under test). Keep it lazy: resolving the
// path eagerly would realize the Kotlin compile tasks before jvmToolchain runs.
val cliRuntimeClasspath = sourceSets["main"].runtimeClasspath

// Differential matrix CI (tools/run-differential-matrix.sh): exports the JVM
// library entry's runtime classpath so the verification pillar can shim the
// historical `lua-bytecode-obfuscator` tool command with
// io.github.dingyi222666.luabytecode.cli.MainKt. This is a tooling export, not
// an application artifact — there is still no `application` plugin and no
// mainClass here (the native CLI is the only default CLI, roadmap step 6b).
tasks.register("exportCliRuntimeClasspath") {
    // Materialize the classpath entries too: the differential CI job runs this
    // task alone on a fresh runner workdir where nothing has been built yet.
    dependsOn(cliRuntimeClasspath.buildDependencies)
    doLast {
        val out = file("$buildDir/cli-runtime-classpath.txt")
        out.parentFile.mkdirs()
        out.writeText(cliRuntimeClasspath.asPath)
    }
}

tasks.test {
    useJUnitPlatform()
    doFirst {
        file("$buildDir/cli-test-classpath.txt").writeText(cliRuntimeClasspath.asPath)
    }
}

kotlin {
    jvmToolchain(17)
}