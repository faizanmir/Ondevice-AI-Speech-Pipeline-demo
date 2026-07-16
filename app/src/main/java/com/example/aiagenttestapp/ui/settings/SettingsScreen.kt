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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    onOpenModels: () -> Unit,
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
                Section("App functions") {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Let the model control the app",
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
                        "When on, the model can open screens and change settings for you -- try " +
                            "asking it to \"open settings\" or \"how much RAM does my phone have?\". " +
                            "Every function it runs is shown in the chat. Takes effect on the next " +
                            "conversation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    SliderRow(
                        label = "Temperature",
                        value = settings.sampling.temperature,
                        range = 0f..2f,
                        hint = "Lower is more focused and repeatable; higher is more varied.",
                        format = { "%.2f".format(it) },
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
                        onChange = { value ->
                            settingsStore.update {
                                it.copy(sampling = it.sampling.copy(topK = value.toInt()))
                            }
                        },
                    )
                }
            }

            item {
                Section("System prompt") {
                    OutlinedTextField(
                        value = settings.systemPrompt,
                        onValueChange = { value ->
                            settingsStore.update { it.copy(systemPrompt = value) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    Text(
                        "Applies to new conversations. Existing chats keep the prompt they " +
                            "started with.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Section("Sampling defaults") {
                    Text(
                        "Reset to temperature 0.8, top-P 0.95, top-K 40.",
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

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    hint: String,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                format(value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
