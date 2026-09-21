package io.github.dingyi222666.luabytecode.web.wasm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PRESETS = listOf("none", "light", "balanced", "aggressive")

/** Granular pass overrides (force-off), matching the CLI flag families. */
private val PASS_OVERRIDES = listOf(
    "strings" to "strings",
    "constants" to "constants",
    "flatten" to "cff flatten",
    "junk" to "junk code",
    "debug strip" to "debug strip",
)

/**
 * The Compose material3 UI of the wasm host (Compose Web 改造 Round 3) —
 * the counterpart of the handwritten static page this module replaces.
 *
 * v1 runs the obfuscation synchronously on the (single) wasm main thread:
 * the "Obfuscating..." state is painted first (a short suspension before the
 * blocking call), but the UI freezes for the duration of the run. Worker
 * offloading is the v2 plan (see docs/roadmap-kmp-progress.md).
 */
@Composable
fun App() {
    var fileName by remember { mutableStateOf<String?>(null) }
    var chunkBase64 by remember { mutableStateOf<String?>(null) }
    var versionText by remember { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var preset by remember { mutableStateOf("balanced") }
    var presetExpanded by remember { mutableStateOf(false) }
    var seedText by remember { mutableStateOf("") }
    var disabledPasses by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<WebObfuscationResult?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("lua-bytecode-obfuscator", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Web front-end over the common obfuscation pipeline (Kotlin/Wasm, Compose Multiplatform).",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()

                // 1. chunk input --------------------------------------------------
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            pickLuaChunkFile { name, base64 ->
                                fileName = name
                                chunkBase64 = null
                                versionText = null
                                fileError = null
                                if (name != null && base64 != null) {
                                    chunkBase64 = base64
                                    val inspected = runCatching { inspectLuaChunk(base64) }
                                    inspected.fold(
                                        onSuccess = { versionText = it },
                                        onFailure = {
                                            fileError = "cannot parse $name: ${it.message}"
                                        },
                                    )
                                }
                            }
                        },
                    ) {
                        Text(if (fileName == null) "Choose .luac file..." else "Choose another file...")
                    }
                    when {
                        fileError != null -> Text(
                            fileError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        versionText != null -> Text(
                            "$fileName ($versionText)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        fileName != null -> Text(
                            "$fileName (inspecting...)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        else -> Text("pick a file to begin", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // 2. options ------------------------------------------------------
                Text("Pipeline options", style = MaterialTheme.typography.titleSmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box {
                        OutlinedButton(onClick = { presetExpanded = true }) {
                            Text("Preset: $preset")
                        }
                        DropdownMenu(
                            expanded = presetExpanded,
                            onDismissRequest = { presetExpanded = false },
                        ) {
                            PRESETS.forEach { candidate ->
                                DropdownMenuItem(
                                    text = {
                                        Text(if (candidate == preset) "$candidate (selected)" else candidate)
                                    },
                                    onClick = {
                                        preset = candidate
                                        presetExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = seedText,
                        onValueChange = { seedText = it },
                        label = { Text("Seed (blank = random)") },
                        singleLine = true,
                        modifier = Modifier.width(220.dp),
                    )
                }
                Text(
                    "Pass overrides (force off, merged over the preset):",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PASS_OVERRIDES.forEach { (key, label) ->
                        FilterChip(
                            selected = key in disabledPasses,
                            onClick = {
                                disabledPasses =
                                    if (key in disabledPasses) disabledPasses - key
                                    else disabledPasses + key
                            },
                            label = { Text(label) },
                        )
                    }
                }

                // 3. run + download ------------------------------------------------
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            val input = chunkBase64 ?: return@Button
                            busy = true
                            errorText = null
                            result = null
                            // Paint the busy state, then block the (single)
                            // wasm main thread with the synchronous run.
                            scope.launch {
                                delay(80)
                                val outcome = runCatching {
                                    obfuscateLuaChunk(
                                        input,
                                        WebObfuscationRequest(
                                            preset = preset,
                                            masterSeed = seedText.trim().takeIf { it.isNotEmpty() }
                                                ?.let { parsed ->
                                                    parsed.toLongOrNull()
                                                        ?: throw IllegalArgumentException(
                                                            "seed \"$parsed\" is not a 64-bit decimal integer",
                                                        )
                                                },
                                            disableStrings = "strings" in disabledPasses,
                                            disableConstants = "constants" in disabledPasses,
                                            disableFlatten = "flatten" in disabledPasses,
                                            disableJunk = "junk" in disabledPasses,
                                            disableDebugStrip = "debug strip" in disabledPasses,
                                        ),
                                    )
                                }
                                busy = false
                                outcome.fold(
                                    onSuccess = { result = it },
                                    onFailure = { errorText = it.message ?: "obfuscation failed" },
                                )
                            }
                        },
                        enabled = chunkBase64 != null && !busy,
                    ) {
                        Text("Obfuscate")
                    }
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
                        Text("Obfuscating...", style = MaterialTheme.typography.bodyMedium)
                    }
                    result?.let { done ->
                        OutlinedButton(
                            onClick = {
                                val versionSlug = done.version.replace(" ", "").lowercase()
                                downloadLuac("obfuscated_${versionSlug}_seed${done.masterSeed}.luac", done.chunkBase64)
                            },
                        ) {
                            Text(
                                "Download obfuscated_${done.version.replace(" ", "").lowercase()}" +
                                    "_seed${done.masterSeed}.luac",
                            )
                        }
                    }
                }
                errorText?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                // 4. report ---------------------------------------------------------
                Text("Report", style = MaterialTheme.typography.titleSmall)
                val reportScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(reportScroll),
                ) {
                    SelectionContainer {
                        Text(
                            result?.reportText ?: "run the pipeline to see the report",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}
