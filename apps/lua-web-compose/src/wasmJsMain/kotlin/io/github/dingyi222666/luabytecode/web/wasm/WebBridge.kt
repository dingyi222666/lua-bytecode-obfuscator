package io.github.dingyi222666.luabytecode.web.wasm

/**
 * Browser glue of the Compose web host (Compose Web 改造 Round 3): file
 * picking and result download — the wasmJs counterpart of the handwritten
 * index.html wiring this module replaces.
 *
 * Everything that needs a browser primitive (input[type=file], FileReader,
 * btoa/atob, Blob, URL.createObjectURL, a[download]) lives in `js()`
 * fragments — package-level by Kotlin/Wasm interop rules — while the Compose
 * side only sees plain Kotlin callbacks. Base64 is the boundary format in
 * both directions, exactly like the js runtime (bytes-in / bytes-out API).
 *
 * Note: a Kotlin `Long` cannot cross the wasm/JS boundary and a `ByteArray`
 * cannot be exported, so no chunk bytes or seeds appear in these signatures.
 */

/**
 * Opens the browser file picker and reads the picked file as base64.
 * [onResult] receives `(fileName, base64)`; both are `null` when the dialog
 * is cancelled and `(fileName, null)` when reading fails. Runs fully on the
 * (single) main thread event loop — the read is async but the wasm thread
 * never blocks.
 */
internal fun pickLuaChunkFile(onResult: (fileName: String?, base64: String?) -> Unit) {
    pickFileImpl(onResult)
}

/**
 * Offers [base64] (a `luac` dump) as a browser download named [fileName]:
 * atob → Uint8Array → Blob → object URL → `<a download>` click → revoke.
 */
internal fun downloadLuac(fileName: String, base64: String) {
    downloadImpl(fileName, base64)
}

private fun pickFileImpl(onResult: (fileName: String?, base64: String?) -> Unit): Unit = js(
    """
    const input = document.createElement("input");
    input.type = "file";
    input.style.display = "none";
    document.body.appendChild(input);
    let finished = false;
    const finish = (name, data) => {
        if (finished) { return; }
        finished = true;
        input.remove();
        onResult(name, data);
    };
    input.addEventListener("change", () => {
        const file = input.files && input.files[0];
        if (!file) { finish(null, null); return; }
        const reader = new FileReader();
        reader.onload = () => {
            const bytes = new Uint8Array(reader.result);
            let binary = "";
            const window_ = 0x8000;
            for (let i = 0; i < bytes.length; i += window_) {
                binary += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + window_, bytes.length)));
            }
            finish(file.name, btoa(binary));
        };
        reader.onerror = () => finish(file.name, null);
        reader.readAsArrayBuffer(file);
    });
    input.addEventListener("cancel", () => finish(null, null));
    input.click();
    """,
)

private fun downloadImpl(fileName: String, base64: String): Unit = js(
    """
    const padded = base64 + "====".slice((4 - (base64.length % 4)) % 4);
    const binary = atob(padded);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) { bytes[i] = binary.charCodeAt(i); }
    const blob = new Blob([bytes], { type: "application/octet-stream" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = fileName;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 0);
    """,
)
