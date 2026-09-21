package io.github.dingyi222666.luabytecode.vm

import io.github.dingyi222666.luabytecode.bytecode.LuaBytecodeVersion
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Disablement proof for the optional VM module (roadmap step 5):
 *
 *  * with the `:lua-vm` artifact removed from the classpath entirely, the CLI /
 *    orchestrator still parses and prints chunks — there is no VM class-loading
 *    path behind the `--vm-out` switch;
 *  * with the artifact present and `--vm-out` given, the CLI emits the module and
 *    prints the four-category protection report, in which the target bytecode-level
 *    CFF stays `not_implemented`.
 *
 * This is the VM-side equivalent of `SingleModuleIsolationTest` (the VM is not an
 * `ObfuscationConfig` field but a module-level, CLI-level switch).
 */
class VmDisablementTest {

    private val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath

    private fun classpath(): List<String> =
        File("build/cli-test-classpath.txt").readText().split(File.pathSeparator).filter { it.isNotBlank() }

    private fun runCli(classpath: String, vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(
            javaBin, "-cp", classpath, "io.github.dingyi222666.luabytecode.cli.MainKt", *args,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the CLI timed out: $output")
        return process.exitValue() to output
    }

    /** Materialise the fixture so the child CLI can read it by path. */
    private fun fixture(): File {
        val bytes = checkNotNull(VmDisablementTest::class.java.getResourceAsStream("/real/ctor/c01_numericfor_53.luac")) {
            "missing fixture /real/ctor/c01_numericfor_53.luac"
        }.use { it.readBytes() }
        return File.createTempFile("vm-disablement", ".luac").apply { writeBytes(bytes) }
    }

    @Test
    fun `the cli runs with the lua-vm artifact absent from the classpath`() {
        val withoutVm = classpath().filter { !it.contains("lua-vm") }
        assertTrue(withoutVm.isNotEmpty(), "the runtime classpath must be resolvable")
        val (code, output) = runCli(
            withoutVm.joinToString(File.pathSeparator),
            fixture().absolutePath,
            "--summary",
        )
        assertEquals(0, code, output)
        assertTrue("parsed" in output, output)
    }

    @Test
    fun `the cli vm path prints the four report categories with the target cff not implemented`() {
        val moduleOut = File.createTempFile("vm-module", ".lua")
        val (code, output) = runCli(
            classpath().joinToString(File.pathSeparator),
            fixture().absolutePath,
            "--vm-out", moduleOut.absolutePath,
        )
        assertEquals(0, code, output)
        assertTrue(moduleOut.length() > 0, "the VM module must be written")
        // the four contract categories, each with an explicit status label
        assertTrue("source.cff: refused" in output, output)
        assertTrue("vm.lift: applied" in output, output)
        assertTrue("interpreter.source.cff: applied" in output, output)
        assertTrue("interpreter.bytecode.cff: not_implemented" in output, output)
        // and the honest incompleteness line
        assertTrue("complete: false" in output, output)
    }
}
