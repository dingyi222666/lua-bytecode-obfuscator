package io.github.dingyi222666.luabytecode.runtime.js

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Minimal smoke over the static web UI (roadmap step 6: file reading, worker
 * and download belong to jsMain). The real Worker / download behaviour runs
 * only in a browser and is deliberately not exercised in CI; this test makes
 * sure the shipped assets stay present and keep referencing the runtime
 * bundle and its exported API by their pinned names, and that the API the
 * worker calls really produces the documented result shape.
 *
 * Runs in the node test environment: the static files are located relative to
 * the repository layout and read with node's fs (a node-only concern of the
 * test, not of the shipped runtime).
 */
class WebUiAssetsSmokeTest {

    private val moduleName = "luabytecodeWebRuntime"
    private val bundleName = "luabytecode-runtime.js"

    @Test
    fun `the static page is present and wires file input, worker and download`() {
        val index = readWebAsset("index.html")
        assertTrue("worker.js" in index, "page must start the worker")
        assertTrue("new Worker(" in index, "page must construct a Worker")
        assertTrue("type=\"file\"" in index, "page must have a file input")
        assertTrue("FileReader" in index, "file reading belongs to the UI (jsMain)")
        assertTrue("URL.createObjectURL" in index, "download wiring belongs to the UI (jsMain)")
        assertTrue("download" in index, "page must offer the result download")
        assertTrue("btoa" in index && "atob" in index, "page must translate bytes <-> base64")
    }

    @Test
    fun `the worker loads the runtime bundle and drives the exported api`() {
        val worker = readWebAsset("worker.js")
        assertTrue("importScripts" in worker || moduleName in worker, "worker must load the UMD bundle")
        assertTrue(moduleName in worker, "worker must reference the pinned module name $moduleName")
        assertTrue(bundleName in worker, "worker must reference $bundleName")
        assertTrue("obfuscateLuaChunk" in worker, "worker must drive the exported common API")
        assertTrue("catch" in worker, "worker must surface failures to the page")
    }

    @Test
    fun `the exported api contract matches what the worker consumes`() {
        // The worker consumes { chunkBase64, version, reportText, masterSeed }
        // and passes a { preset, masterSeed } shaped request: assert the
        // exported surface here (a mocked worker round trip in Kotlin).
        JsPlatform.install()
        val result = obfuscateLuaChunk(
            numericFor53Base64,
            WebObfuscationRequestJs(preset = "balanced", masterSeed = 20260917L),
        )
        assertTrue(result.chunkBase64.isNotEmpty())
        assertTrue(result.version.isNotEmpty())
        assertTrue(result.reportText.isNotEmpty())
        assertEquals(20260917L, result.masterSeed)
    }

    // ---- node file access (test environment only) -------------------------

    private fun readWebAsset(name: String): String {
        val fs: dynamic = js("require('fs')")
        val path: dynamic = js("require('path')")
        var dir: String = js("process.cwd()")
        repeat(8) {
            for (candidate in listOf(
                path.join(dir, "src", "jsMain", "resources", "web", name),
                path.join(dir, "apps", "lua-runtime-js", "src", "jsMain", "resources", "web", name),
                path.join(dir, "lua-runtime-js", "src", "jsMain", "resources", "web", name),
            )) {
                if (fs.existsSync(candidate) == true) {
                    return fs.readFileSync(candidate, "utf8") as String
                }
            }
            dir = path.dirname(dir)
        }
        fail("web asset $name not found above ${js("process.cwd()")}")
    }
}
