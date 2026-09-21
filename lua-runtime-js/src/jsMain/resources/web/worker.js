/**
 * Web worker of the obfuscator UI (roadmap step 6: "file reading, the worker
 * and the download are implemented by jsMain").
 *
 * The worker loads the Kotlin runtime binary (the UMD production executable
 * of :lua-runtime-js, module name `luabytecodeWebRuntime`) and drives its
 * exported common API — obfuscation itself never runs on the UI thread.
 *
 * Protocol:
 *   in:  { id, chunkBase64, request: { preset, masterSeed, expectedVersion, stripDebugInfo } }
 *   out: { id, ok: true,  chunkBase64, version, reportText, masterSeed }
 *   out: { id, ok: false, message }
 */

// UMD works inside importScripts: the factory attaches to the worker global.
// The require fallback is only meaningful when this file is reused under node.
var runtime = (typeof self !== "undefined" && self["luabytecodeWebRuntime"]) ||
  (typeof require !== "undefined" ? require("../luabytecode-runtime.js") : null);

self.onmessage = function (event) {
  var message = event.data || {};
  var id = message.id;
  if (!runtime) {
    post(id, false, "runtime bundle luabytecode-runtime.js not loaded");
    return;
  }
  try {
    var result = runtime.obfuscateLuaChunk(
      message.chunkBase64,
      message.request || { preset: "balanced" },
    );
    self.postMessage({
      id: id,
      ok: true,
      chunkBase64: result.chunkBase64,
      version: result.version,
      reportText: result.reportText,
      masterSeed: result.masterSeed,
    });
  } catch (cause) {
    // Kotlin exceptions surface as Error with the diagnostic message.
    post(id, false, (cause && cause.message) ? cause.message : String(cause));
  }
};

function post(id, ok, message) {
  self.postMessage({ id: id, ok: ok, message: message });
}
