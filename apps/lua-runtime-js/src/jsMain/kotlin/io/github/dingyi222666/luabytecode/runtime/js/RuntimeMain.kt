package io.github.dingyi222666.luabytecode.runtime.js

/**
 * Node entry point of the runtime binary. Browsers only consume the UMD
 * module object of the linked production executable (the worker drives the
 * exported obfuscation API); a node host can run the binary directly for a
 * smoke check. Kept silent: installing the platform seams must not have a
 * side effect on stdout/stderr.
 */
fun main() {
    JsPlatform.install()
}
