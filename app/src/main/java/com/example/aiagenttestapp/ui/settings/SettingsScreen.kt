package com.example.aiagenttestapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.EngineAvailability
import com.example.aiagent.engine.core.EngineRegistry
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagent.engine.core.SamplingParams
import com.example.aiagenttestapp.data.HuggingFaceAuth
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.stt.SpeechModel
import com.example.aiagenttestapp.ui.components.formatBytes
import com.example.aiagenttestapp.ui.components.readableWidth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    engines: EngineRegistry,
    auth: HuggingFaceAuth,
    downloadedModels: List<ModelSpec>,
    speechModels: List<SpeechModel>,
    audioModels: AudioModelRepository,
    onOpenModels: () -> Unit,
    onOpenSpeakers: () -> Unit,
    onBack: () -> Unit,
) {
    val settings by settingsStore.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            // Sections are capped at a readable width; on a tablet this centres them.
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Section("Chat model") {
                    Text(
                        "The model new chats use. Pick from what you have downloaded, or add more.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (downloadedModels.isEmpty()) {
                        Text(
                            "No models downloaded yet -- add one to start chatting.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            downloadedModels.forEach { model ->
                                FilterChip(
                                    selected = settings.activeModelId == model.id,
                                    onClick = {
                                        settingsStore.update { it.copy(activeModelId = model.id) }
                                    },
                                    label = { Text("${model.name} · ${model.paramsLabel}") },
                                )
                            }
                        }
                    }
                    Button(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) {
                        Text("Manage models")
                    }
                }
            }

            item {
                Section("Speech recognition") {
                    Text(
                        "The model that turns your voice into text, for voice notes and " +
                            "dictation. Downloaded the first time you record.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val selectedSpeech = speechModels
                        .firstOrNull { it.id == settings.speechModelId }
                        ?: speechModels.first()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        speechModels.forEach { model ->
                            FilterChip(
                                selected = selectedSpeech.id == model.id,
                                onClick = {
                                    settingsStore.update { it.copy(speechModelId = model.id) }
                                },
                                label = {
                                    Text("${model.label} · ${formatBytes(model.totalBytes)}")
                                },
                            )
                        }
                    }
                    // The blurb is where the choice becomes explainable: it names the languages
                    // each model can and cannot hear, which is the entire reason to switch.
                    Text(
                        selectedSpeech.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Section("Voice notes") {
                    Text(
                        "Two optional additions to voice notes, each with its own one-time " +
                            "download. Both run on your phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AudioBundleRow(
                        repository = audioModels,
                        bundle = audioModels.speaker,
                        enabled = settings.speakerIdEnabled,
                        onEnabledChange = { on ->
                            settingsStore.update { it.copy(speakerIdEnabled = on) }
                        },
                    )
                    AudioBundleRow(
                        repository = audioModels,
                        bundle = audioModels.keywords,
                        enabled = settings.keywordMarkersEnabled,
                        onEnabledChange = { on ->
                            settingsStore.update { it.copy(keywordMarkersEnabled = on) }
                        },
                    )
                    if (settings.speakerIdEnabled) {
                        Button(onClick = onOpenSpeakers, modifier = Modifier.fillMaxWidth()) {
                            Text("Manage speakers")
                        }
                    }
                }
            }

            item {
                Section("HuggingFace account") {
                    HuggingFaceAccountSection(auth)
                }
            }

            item {
                Section("Inference engine") {
                    Text(
                        "Which runtime executes the model. Only engines that can load a given " +
                            "model's file format are offered for it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.preferredEngine == null,
                            onClick = { settingsStore.update { it.copy(preferredEngine = null) } },
                            label = { Text("Automatic") },
                        )
                        engines.all.forEach { engine ->
                            val available = engine.availability() is EngineAvailability.Available
                            FilterChip(
                                selected = settings.preferredEngine == engine.descriptor.id,
                                onClick = {
                                    settingsStore.update {
                                        it.copy(preferredEngine = engine.descriptor.id)
                                    }
                                },
                                enabled = available,
                                label = { Text(engine.descriptor.displayName) },
                            )
                        }
                    }
                    engines.all.forEach { engine ->
                        Text(
                            "${engine.descriptor.displayName} — ${engine.descriptor.blurb}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Section("Tools") {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Let the model use tools",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = settings.appFunctionsEnabled,
                            onCheckedChange = { enabled ->
                                settingsStore.update { it.copy(appFunctionsEnabled = enabled) }
                            },
                        )
                    }
                    Text(
                        "When on, the model can call tools to open screens, change settings, and " +
                            "search the web for you -- try asking it to \"open settings\" or \"how " +
                            "much RAM does my phone have?\". Every tool it runs is shown in the chat. " +
                            "Off keeps the model to plain chat, and frees the system-prompt tokens " +
                            "the tool list would use. Takes effect on the next conversation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (settings.appFunctionsEnabled) {
                        SliderRow(
                            label = "Max tool calls per turn",
                            value = settings.maxToolHops.toFloat(),
                            range = 1f..8f,
                            hint = "How many tools the model may chain -- search, read, search " +
                                "again -- before it must answer.",
                            format = { it.toInt().toString() },
                            onChange = { value ->
                                settingsStore.update { it.copy(maxToolHops = value.toInt()) }
                            },
                        )
                    }
                }
            }

            item {
                Section("Web search") {
                    Text(
                        "Give a tool-capable model internet access. With a Tavily key and app " +
                            "functions on, the model can call web_search to look things up online. " +
                            "The search query leaves the device; inference stays local. Leave this " +
                            "blank to keep the model fully offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = settings.tavilyApiKey.orEmpty(),
                        onValueChange = { value ->
                            settingsStore.update {
                                it.copy(tavilyApiKey = value.trim().ifBlank { null })
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Tavily API key") },
                        placeholder = { Text("tvly-...") },
                        singleLine = true,
                    )
                }
            }

            item {
                Section("Accelerator") {
                    Text(
                        "GPU is much faster and, on LiteRT-LM, uses far less RAM. It falls back " +
                            "to the CPU automatically when a model or engine has no GPU path.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Accelerator.entries.forEach { accelerator ->
                            FilterChip(
                                selected = settings.preferredAccelerator == accelerator,
                                onClick = {
                                    settingsStore.update {
                                        it.copy(preferredAccelerator = accelerator)
                                    }
                                },
                                label = { Text(accelerator.label) },
                            )
                        }
                    }
                }
            }

            item {
                Section("Sampling") {
                    // Reproducible output replaces all three of these with argmax decoding, so they
                    // are shown but inert while it is on -- an enabled slider that changes nothing
                    // is worse than a disabled one that explains itself.
                    val samplerEnabled = !settings.reproducibleOutput
                    if (settings.reproducibleOutput) {
                        Text(
                            "Reproducible output is on, so generation always takes the " +
                                "highest-probability token. These three have no effect until you " +
                                "turn it off.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    SliderRow(
                        label = "Temperature",
                        value = settings.sampling.temperature,
                        range = 0f..2f,
                        hint = "Lower is more focused and repeatable; higher is more varied.",
                        format = { "%.2f".format(it) },
                        enabled = samplerEnabled,
                        onChange = { value ->
                            settingsStore.update {
                                it.copy(sampling = it.sampling.copy(temperature = value))
                            }
                        },
                    )
                    SliderRow(
                        label = "Top-P",
                        value = settings.sampling.topP,
                        range = 0.1f..1f,
                        hint = "Considers only the most likely tokens, up to this cumulative " +
                            "probability.",
                        format = { "%.2f".format(it) },
                        enabled = samplerEnabled,
                        onChange = { value ->
                            settingsStore.update {
                                it.copy(sampling = it.sampling.copy(topP = value))
                            }
                        },
                    )
                    SliderRow(
                        label = "Top-K",
                        value = settings.sampling.topK.toFloat(),
                        range = 1f..100f,
                        hint = "Considers only this many candidate tokens at each step.",
                        format = { it.toInt().toString() },
                        enabled = samplerEnabled,
                        onChange = { value ->
                            settingsStore.update {
                                it.copy(sampling = it.sampling.copy(topK = value.toInt()))
                            }
                        },
                    )
                    SliderRow(
                        label = "Max output tokens",
                        value = settings.sampling.maxOutputTokens.toFloat(),
                        range = 0f..2048f,
                        hint = "Hard cap on reply length. \"No limit\" stops only at the model's " +
                            "end-of-text or when the context fills.",
                        format = { if (it < 1f) "No limit" else it.toInt().toString() },
                        onChange = { value ->
                            settingsStore.update {
                                it.copy(sampling = it.sampling.copy(maxOutputTokens = value.toInt()))
                            }
                        },
                    )
                    StopSequencesField(
                        value = settings.sampling.stopSequences,
                        onChange = { sequences ->
                            settingsStore.update {
                                it.copy(sampling = it.sampling.copy(stopSequences = sequences))
                            }
                        },
                    )
                    SwitchRow(
                        label = "Reproducible output",
                        checked = settings.reproducibleOutput,
                        hint = "Always takes the highest-probability token and fixes the seed, so " +
                            "the same prompt gives the same reply -- for evals and bug reports. " +
                            "Overrides temperature, top-P and top-K without discarding them.",
                        onCheckedChange = { on ->
                            settingsStore.update { it.copy(reproducibleOutput = on) }
                        },
                    )
                }
            }

            item {
                Section("Performance") {
                    SliderRow(
                        label = "CPU threads",
                        value = settings.threadCount.toFloat(),
                        range = 0f..8f,
                        hint = "Decode threads for the CPU engines (llama.cpp, MNN). " +
                            "\"Automatic\" leaves the little cores free. No effect on GPU or NPU.",
                        format = { if (it < 1f) "Automatic" else it.toInt().toString() },
                        onChange = { value ->
                            settingsStore.update { it.copy(threadCount = value.toInt()) }
                        },
                    )
                }
            }

            item {
                Section("Reasoning") {
                    SwitchRow(
                        label = "Let models think",
                        checked = settings.thinkingEnabled,
                        hint = "Reasoning models (Qwen3, DeepSeek-R1) work through a problem in a " +
                            "\"thinking\" step before answering. Turning this off asks them to " +
                            "answer directly -- faster, but weaker on hard questions. Takes effect " +
                            "on the next conversation.",
                        onCheckedChange = { on ->
                            settingsStore.update { it.copy(thinkingEnabled = on) }
                        },
                    )
                }
            }

            item {
                Section("Sampling defaults") {
                    Text(
                        "Reset temperature, top-P, top-K, the output cap and stop sequences to " +
                            "their defaults.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            settingsStore.update { it.copy(sampling = SamplingParams()) }
                        },
                        label = { Text("Reset sampling") },
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.readableWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

/**
 * Stop strings, edited one per line. Keeps the raw text in local state so a trailing newline (the
 * user starting a second line) survives -- deriving the field value from the parsed, blank-filtered
 * list would delete that newline on every keystroke.
 */
@Composable
private fun StopSequencesField(
    value: List<String>,
    onChange: (List<String>) -> Unit,
) {
    var text by remember { mutableStateOf(value.joinToString("\n")) }
    Column {
        Text("Stop sequences", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { edited ->
                text = edited
                onChange(edited.split("\n").filter { it.isNotEmpty() })
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            placeholder = { Text("One sequence per line") },
        )
        Text(
            "The reply ends the moment the model emits one of these. One per line; leave empty " +
                "for none.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    hint: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    hint: String,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    // Dimmed rather than hidden when disabled: the value is still the user's and comes back the
    // moment the override is switched off, so it should stay legible instead of vanishing.
    val contentAlpha = if (enabled) 1f else 0.38f
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = contentAlpha),
            )
            Text(
                format(value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = LocalContentColor.current.copy(alpha = contentAlpha),
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
        )
    }
}
