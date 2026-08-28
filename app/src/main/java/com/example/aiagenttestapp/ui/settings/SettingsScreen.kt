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
import com.example.aiagenttestapp.data.OnnxProvider
import com.example.aiagenttestapp.data.PlatformFeedChunk
import com.example.aiagenttestapp.data.PlatformFeedPace
import com.example.aiagenttestapp.data.SettingsStore
import com.example.aiagenttestapp.data.audiomodels.AudioModelCatalog
import com.example.aiagenttestapp.data.audiomodels.AudioModelRepository
import com.example.aiagenttestapp.stt.SpeechModel
import com.example.aiagenttestapp.ui.components.formatBytes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.aiagenttestapp.data.AppSettings
import com.example.aiagenttestapp.ui.components.ListDetailPanes
import com.example.aiagenttestapp.ui.components.readableWidth
import com.example.aiagenttestapp.ui.components.rememberListDetailState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    engines: EngineRegistry,
    auth: HuggingFaceAuth,
    downloadedModels: List<ModelSpec>,
    speechModels: List<SpeechModel>,
    audioModels: AudioModelRepository,
    onOpenModels: () -> Unit,
    onBack: () -> Unit,
) {
    val settings by settingsStore.settings.collectAsStateWithLifecycle()
    val firstCategory = SettingsCategory.entries.first()
    val panes = rememberListDetailState(firstCategory)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { panes.onBackPressed(orElse = onBack) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        ListDetailPanes(
            state = panes,
            modifier = Modifier.padding(padding),
            listPane = {
                SettingsCategoryList(
                    selected = panes.highlighted,
                    onSelect = panes::select,
                )
            },
            detailPane = {
                SettingsDetail(
                    category = panes.shown ?: firstCategory,
                    settings = settings,
                    settingsStore = settingsStore,
                    engines = engines,
                    auth = auth,
                    downloadedModels = downloadedModels,
                    speechModels = speechModels,
                    audioModels = audioModels,
                    onOpenModels = onOpenModels,
                )
            },
        )
    }
}

/**
 * The categories, in the order they are listed.
 *
 * Twelve sections in one scroll was the problem: everything was findable only by reading past
 * everything else, and the two people most likely to be in here -- someone changing a model and
 * someone chasing a speech setting -- had nothing in common. Grouped by *what you came to change*
 * rather than by which subsystem owns the value, which is why sampling and reasoning sit together
 * and the accelerator sits with the engine rather than with speech.
 */
enum class SettingsCategory(val label: String, val summary: String, val icon: ImageVector) {
    Models("Models", "Chat model and HuggingFace account", Icons.Default.Layers),
    Engine("Engine", "Runtime, accelerator and threads", Icons.Default.Memory),
    Speech("Speech", "Recognition and voice notes", Icons.Default.Mic),
    Generation("Generation", "Sampling, reasoning and defaults", Icons.Default.Tune),
    Tools("Tools", "App functions and web search", Icons.Default.Build),
}

/** The list pane: one row per category, with the selection shown when both panes are visible. */
@Composable
private fun SettingsCategoryList(
    selected: SettingsCategory?,
    onSelect: (SettingsCategory) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(SettingsCategory.entries) { entry ->
            val isSelected = entry == selected
            Card(
                onClick = { onSelect(entry) },
                modifier = Modifier.readableWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                ),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(entry.icon, contentDescription = null)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            entry.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            entry.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The detail pane: whichever category's sections, unchanged from when they were one long scroll. */
@Composable
private fun SettingsDetail(
    category: SettingsCategory,
    settings: AppSettings,
    settingsStore: SettingsStore,
    engines: EngineRegistry,
    auth: HuggingFaceAuth,
    downloadedModels: List<ModelSpec>,
    speechModels: List<SpeechModel>,
    audioModels: AudioModelRepository,
    onOpenModels: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        // Sections are capped at a readable width; on a tablet this centres them.
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (category == SettingsCategory.Models) item {
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

        if (category == SettingsCategory.Speech) item {
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

        if (category == SettingsCategory.Speech) item {
            Section("Voice notes") {
                // On by default and needing no download, so it sits above the two optional
                // bundles rather than among them.
                SwitchRow(
                    label = "Skip silence",
                    checked = settings.vadEnabled,
                    hint = "Finds the parts of a recording with speech in them and transcribes " +
                        "only those. Faster, and it stops the model inventing words to fill a " +
                        "silence. Turn it off if quiet speech is going missing.",
                    onCheckedChange = { on ->
                        settingsStore.update { it.copy(vadEnabled = on) }
                    },
                )
                Text(
                    "An optional addition to voice notes, with its own one-time download. It " +
                        "runs on your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AudioBundleRow(
                    repository = audioModels,
                    bundle = audioModels.keywords,
                    enabled = settings.keywordMarkersEnabled,
                    onEnabledChange = { on ->
                        settingsStore.update { it.copy(keywordMarkersEnabled = on) }
                    },
                )

                // No switch: the speaker screen needs these models and refuses to run without
                // them, so having them is the only "on" there is. Listed here because Settings
                // is where every other model download lives, and the screen that needs it sends
                // people here by name.
                SpeakerModelSection(
                    repository = audioModels,
                    bundles = AudioModelCatalog.speakerBundles,
                    selectedId = settings.speakerBundleId,
                    onSelect = { bundle ->
                        settingsStore.update { it.copy(speakerBundleId = bundle.id) }
                    },
                )

                OnnxProviderRow(
                    selected = settings.onnxProvider,
                    onSelect = { provider ->
                        settingsStore.update { it.copy(onnxProvider = provider) }
                    },
                )

                FeedPaceRow(
                    selected = settings.platformFeedPace,
                    onSelect = { pace ->
                        settingsStore.update { it.copy(platformFeedPace = pace) }
                    },
                    chunk = settings.platformFeedChunk,
                )

                FeedChunkRow(
                    selected = settings.platformFeedChunk,
                    onSelect = { chunk ->
                        settingsStore.update { it.copy(platformFeedChunk = chunk) }
                    },
                )
            }
        }

        if (category == SettingsCategory.Models) item {
            Section("HuggingFace account") {
                HuggingFaceAccountSection(auth)
            }
        }

        if (category == SettingsCategory.Engine) item {
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

        if (category == SettingsCategory.Tools) item {
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

        if (category == SettingsCategory.Tools) item {
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

        if (category == SettingsCategory.Engine) item {
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

        if (category == SettingsCategory.Generation) item {
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

        if (category == SettingsCategory.Engine) item {
            Section("Performance") {
                SliderRow(
                    label = "CPU threads",
                    value = settings.threadCount.toFloat(),
                    range = 0f..8f,
                    hint = "Decode threads for the CPU engine (llama.cpp). " +
                        "\"Automatic\" leaves the little cores free. No effect on GPU or NPU.",
                    format = { if (it < 1f) "Automatic" else it.toInt().toString() },
                    onChange = { value ->
                        settingsStore.update { it.copy(threadCount = value.toInt()) }
                    },
                )
            }
        }

        if (category == SettingsCategory.Generation) item {
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

        if (category == SettingsCategory.Generation) item {
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

/**
 * Which execution provider the speech models run on.
 *
 * Offered because the answer is device-specific and unmeasured. The copy says "measure it" rather
 * than naming a winner on purpose: the plausible-sounding choice here is frequently the slower one,
 * since a provider that cannot take the whole graph hands the rest back to the CPU and every
 * hand-off costs a copy.
 *
 * Takes effect on the next transcription rather than immediately -- the recognisers read this when
 * they build a session, and a run already under way keeps the provider it started on.
 */
@Composable
private fun OnnxProviderRow(
    selected: OnnxProvider,
    onSelect: (OnnxProvider) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Speech model processor",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "Which processor runs the speech models. Applies to the next recording you transcribe. " +
                "If one of these fails to load, transcription falls back to the CPU on its own.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OnnxProvider.entries.forEach { provider ->
                FilterChip(
                    selected = provider == selected,
                    onClick = { onSelect(provider) },
                    label = { Text(provider.label) },
                )
            }
        }
        Text(
            selected.hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    // Outlined rather than raised. `surfaceContainerLow` on this background is nearly the background,
    // so two stacked sections read as one continuous area and a setting appears to belong to the
    // heading above it when it does not -- which matters most in Generation, where three sections of
    // sliders sit together and only the headings say which is which. A border draws the boundary the
    // fill was failing to.
    OutlinedCard(
        modifier = Modifier.readableWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
            // Separates the heading from its controls. Cheap, and it stops the first row of a
            // section reading as a subtitle of the title above it.
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
