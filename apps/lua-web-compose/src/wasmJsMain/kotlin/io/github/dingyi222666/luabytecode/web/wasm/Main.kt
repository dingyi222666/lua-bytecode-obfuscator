package io.github.dingyi222666.luabytecode.web.wasm

import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

/**
 * Wasm entry point of the Compose web host (Compose Web 改造 Round 3):
 * install the platform seams first (the pipeline needs entropy and the
 * bundled decoder assets before the first obfuscation run), then mount the
 * Compose UI into the document body — `ComposeViewport` is the current
 * Compose-for-Web entry (CanvasBasedWindow is deprecated).
 */
fun main() {
    WasmPlatform.install()
    ComposeViewport(document.body!!) {
        App()
    }
}
