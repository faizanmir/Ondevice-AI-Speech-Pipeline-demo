package com.example.aiagenttestapp.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagent.engine.core.GenerationStats
import com.example.aiagenttestapp.stt.SpeechModelState
import com.example.aiagenttestapp.ui.components.formatBytes
import com.example.aiagenttestapp.ui.components.readableWidth
import com.mikepenz.markdown.m3.Markdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Dictation needs RECORD_AUDIO. Launching when already granted returns immediately with no
    // dialog, so the mic button can always route through here.
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startVoiceInput()
    }

    // Shown when the user taps the mic but the shared speech model has not been downloaded yet.
    var showSpeechSetup by remember { mutableStateOf(false) }

    if (showSpeechSetup) {
        SpeechSetupDialog(
            state = state.speechModelState,
            sizeBytes = state.speechModelSizeBytes,
            onDownload = viewModel::downloadSpeechModel,
            onDismiss = { showSpeechSetup = false },
        )
    }

    // Whether the viewport is already at the end of the list. Streaming only pulls the view down
    // when the user is actually reading the latest message -- if they have scrolled up into history,
    // leave them there rather than yanking them back to the bottom on every token.
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }

    // Pixels of the screen the keyboard currently covers. Read here so this recomposes as the IME
    // animates in or out, which is what lets the keyboard effect below run as it slides.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    // A message was added (a send) or a token streamed in: go to the end when the user just sent --
    // their bubble is the newest -- or is already following at the bottom. A reader scrolled up into
    // history is left where they are, so the stream never yanks them back mid-read.
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        val last = state.messages.lastOrNull() ?: return@LaunchedEffect
        if (last.isUser || isAtBottom) listState.scrollToEnd(waitFrame = false)
    }

    // Keyboard slides in or out: keep the end pinned while the user is at the bottom, so tapping to
    // type -- or dismissing the keyboard -- never leaves the latest message buried behind the input.
    LaunchedEffect(imeBottom) {
        if (isAtBottom) listState.scrollToEnd(waitFrame = false)
    }

    // Chat opened: land on the newest message as soon as the transcript is ready to show.
    LaunchedEffect(state.loadState) {
        if (state.loadState is ModelLoadState.Ready) listState.scrollToEnd()
    }

    // Reply finished: the bubble re-renders from plain streamed text to Markdown and its height jumps
    // (tables, code, the stats footer). None of the keys above change on that flip, so land on the
    // true end here -- unconditionally, since a just-finished reply is what the user is here to read.
    LaunchedEffect(state.isGenerating) {
        if (!state.isGenerating) listState.scrollToEnd()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.model?.name ?: "Chat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            // Which engine is running, always visible. The whole point of the app
                            // is that this is swappable, so it should never be a mystery.
                            text = "${state.engineName} · ${state.accelerator.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::resetConversation,
                        enabled = state.messages.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "New conversation")
                    }
                },
            )
        },
        bottomBar = {
            ChatInputBar(
                draft = state.draft,
                onDraftChange = viewModel::onDraftChange,
                replyingTo = state.replyingTo,
                onCancelReply = viewModel::cancelReply,
                enabled = state.canSend,
                isGenerating = state.isGenerating,
                isDictating = state.isDictating,
                isTranscribing = state.isTranscribing,
                micLevel = state.micLevel,
                onSend = viewModel::send,
                onStop = viewModel::stopGenerating,
                onMic = {
                    when {
                        state.isDictating -> viewModel.stopVoiceInput()
                        state.isSpeechReady ->
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        else -> showSpeechSetup = true
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val load = state.loadState) {
                is ModelLoadState.Loading -> LoadingState(load.message)
                is ModelLoadState.Failed -> ErrorState(load.message)
                else -> {
                    if (state.messages.isEmpty()) {
                        EmptyState(state)
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            // With the bubbles capped below, this centres the transcript column
                            // on a tablet. On a phone it aligns nothing -- items fill the lane.
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            items(state.messages, key = { it.id }) { message ->
                                Box(Modifier.readableWidth()) {
                                    MessageBubble(
                                        message = message,
                                        isStreaming = state.isGenerating &&
                                            message.id == state.messages.last().id,
                                        onReply = { viewModel.startReply(message) },
                                        onDelete = { viewModel.deleteMessage(message.id) },
                                    )
                                }
                            }
                        }
                    }

                    // Context pressure. Surfaced only once it matters, because a progress bar that
                    // is always there stops being read.
                    if (state.contextFraction > CONTEXT_WARN_THRESHOLD) {
                        ContextWarning(
                            used = state.contextUsed,
                            total = state.contextTotal,
                            fraction = state.contextFraction,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .readableWidth(),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    isStreaming: Boolean,
    onReply: () -> Unit,
    onDelete: () -> Unit,
) {
    // The model did something to the app rather than saying something. Render it as an action, not
    // as speech, and give it no message menu -- there is nothing to copy or reply to.
    message.functionCall?.let { call ->
        FunctionCallChip(call)
        return
    }

    val isUser = message.isUser
    // Actions only make sense once there is text: never on the empty "thinking" bubble.
    val actionable = message.text.isNotBlank()

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    // Swipe the bubble aside to quote-reply. confirmValueChange returns false so it never actually
    // settles as dismissed -- it fires the reply and springs back.
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd && actionable) onReply()
            false
        },
    )

    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = actionable,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    ) {
        // Opaque so the swipe-reveal icon behind only shows while dragging. Matches the Scaffold's
        // default container colour, so the band itself is invisible at rest.
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Box {
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 18.dp,
                    ),
                    color = when {
                        message.isError -> MaterialTheme.colorScheme.errorContainer
                        isUser -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier
                        // Assistant replies get the full width so Markdown tables, code blocks and
                        // long text have room, ChatGPT-style; user messages stay a compact bubble.
                        .then(if (isUser) Modifier.widthIn(max = 300.dp) else Modifier.fillMaxWidth())
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (actionable) menuOpen = true },
                        ),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            message.text.isEmpty() && isStreaming -> {
                                // Nothing back yet: this is prefill. On a 3B model on a CPU that can
                                // be several seconds, an empty bubble with no life reads as a hang.
                                ThinkingIndicator()
                            }

                            // Rendered Markdown -- links, tables, code, lists -- but only once the
                            // reply is complete. While it streams it stays plain text (cheap, and no
                            // re-parsing of half-finished Markdown); it flips to the rendered version
                            // the moment the turn finishes.
                            !isUser && !message.isError && !isStreaming && message.text.isNotEmpty() -> {
                                Markdown(
                                    content = message.text,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            else -> {
                                Text(
                                    text = message.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = when {
                                        message.isError -> MaterialTheme.colorScheme.onErrorContainer
                                        isUser -> MaterialTheme.colorScheme.onPrimary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }

                MessageActionsMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    onCopy = {
                        clipboard.setText(AnnotatedString(message.text))
                        menuOpen = false
                    },
                    onReply = {
                        onReply()
                        menuOpen = false
                    },
                    onShare = {
                        context.shareText(message.text)
                        menuOpen = false
                    },
                    onDelete = {
                        onDelete()
                        menuOpen = false
                    },
                )
            }

            message.stats?.let { stats ->
                Spacer(Modifier.height(4.dp))
                StatsFooter(stats)
            }
        }
    }
}

/** Long-press menu on a message: the common text actions plus delete. */
@Composable
private fun MessageActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Copy") },
            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
            onClick = onCopy,
        )
        DropdownMenuItem(
            text = { Text("Reply") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
            onClick = onReply,
        )
        DropdownMenuItem(
            text = { Text("Share") },
            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
            onClick = onShare,
        )
        DropdownMenuItem(
            text = { Text("Delete") },
            leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
            onClick = onDelete,
        )
    }
}

private fun Context.shareText(text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startActivity(Intent.createChooser(intent, null))
}

/** Speed and latency, per response. Users comparing engines and models need this to be real. */
@Composable
private fun StatsFooter(stats: GenerationStats) {
    Text(
        text = buildString {
            append("%.1f tok/s".format(stats.tokensPerSecond))
            if (stats.timeToFirstTokenMs > 0) {
                append(" · %.1fs to first token".format(stats.timeToFirstTokenMs / 1000.0))
            }
            if (stats.generatedTokens > 0) append(" · ${stats.generatedTokens} tokens")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp),
    )
}

/**
 * A function the model invoked. Deliberately conspicuous: an on-device model quietly changing the
 * user's settings would be alarming, so every invocation leaves a visible, permanent trace in the
 * transcript naming exactly which function ran.
 */
@Composable
private fun FunctionCallChip(call: FunctionCallDisplay) {
    val accent = if (call.succeeded) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.error
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (call.succeeded) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (call.succeeded) Icons.Default.Bolt else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Column {
                Text(
                    text = call.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (call.succeeded) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
                Text(
                    text = call.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Thinking",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    replyingTo: ChatMessage?,
    onCancelReply: () -> Unit,
    enabled: Boolean,
    isGenerating: Boolean,
    isDictating: Boolean,
    isTranscribing: Boolean,
    micLevel: Float,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onMic: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        // The bar's background spans the screen, but its controls stay aligned with the (capped)
        // transcript column above -- a full-width text field under a centred chat reads broken.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.readableWidth()) {
            if (replyingTo != null) {
                ReplyPreview(message = replyingTo, onCancel = onCancelReply)
            }

            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (isDictating) "Listening…" else "Message") },
                // Locked only while the just-recorded audio is being transcribed, so the inserted
                // text does not race the user's own edits. Typeable at every other time, including
                // while a reply streams in or the model is still loading.
                enabled = !isTranscribing,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )

            // Dictation. Hidden while generating -- you cannot dictate a new message mid-reply.
            if (!isGenerating) {
                MicButton(
                    isDictating = isDictating,
                    isTranscribing = isTranscribing,
                    level = micLevel,
                    onClick = onMic,
                )
            }

            // Send and stop are the same affordance in two states, so the button never moves and
            // stop is always exactly where the user's thumb already is.
            FilledIconButton(
                onClick = {
                    if (isGenerating) {
                        onStop()
                    } else {
                        // Start the keyboard dismissal first, then hand off the message. The heavy
                        // work the send kicks off is deferred a beat in the ViewModel so it does not
                        // stutter this animation.
                        keyboard?.hide()
                        focusManager.clearFocus()
                        onSend()
                    }
                },
                enabled = isGenerating || (enabled && draft.isNotBlank()),
                modifier = Modifier.size(52.dp),
                colors = if (isGenerating) {
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    IconButtonDefaults.filledIconButtonColors()
                },
            ) {
                Icon(
                    imageVector = if (isGenerating) Icons.Default.Stop
                    else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isGenerating) "Stop generating" else "Send",
                )
            }
            }
            }
        }
    }
}

/** The message being answered, shown above the input with a way to back out of the reply. */
@Composable
private fun ReplyPreview(message: ChatMessage, onCancel: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (message.isUser) "Replying to yourself" else "Replying to the assistant",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Cancel reply",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The dictation affordance: an idle mic, a red stop while listening, a spinner while decoding. */
@Composable
private fun MicButton(
    isDictating: Boolean,
    isTranscribing: Boolean,
    level: Float,
    onClick: () -> Unit,
) {
    if (isTranscribing) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        return
    }

    // A gentle pulse sized by loudness, so the user can see the mic is hearing them.
    val scale by animateFloatAsState(
        targetValue = if (isDictating) 1f + level * 0.3f else 1f,
        label = "mic-level",
    )

    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Icon(
            imageVector = if (isDictating) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = if (isDictating) "Stop dictation" else "Dictate a message",
            tint = if (isDictating) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.scale(scale),
        )
    }
}

/**
 * Offered when the user taps the mic before the speech model exists. The model is the same ~240 MB
 * SenseVoice download the Voice Notes screen uses, so getting it here also gets it there.
 */
@Composable
private fun SpeechSetupDialog(
    state: SpeechModelState,
    sizeBytes: Long,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice input") },
        text = {
            when (state) {
                is SpeechModelState.Downloading -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Downloading the speech model, ${(state.progress * 100).toInt()}%.")
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is SpeechModelState.Failed -> Text("Download failed: ${state.message}")

                SpeechModelState.Ready ->
                    Text("Ready. Tap the microphone to dictate your message.")

                SpeechModelState.NotDownloaded -> Text(
                    "Dictation runs entirely on your phone. It needs a one-time " +
                        "${formatBytes(sizeBytes)} speech-model download, shared with Voice Notes.",
                )
            }
        },
        confirmButton = {
            when (state) {
                SpeechModelState.Ready -> TextButton(onClick = onDismiss) { Text("Done") }
                is SpeechModelState.Downloading -> {}
                else -> TextButton(onClick = onDownload) { Text("Download") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ContextWarning(used: Int, total: Int, fraction: Float, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Context almost full — $used of $total tokens. " +
                    "Start a new conversation soon, or the model will start forgetting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun LoadingState(message: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "The first load takes a few seconds while the model is read into memory.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorState(message: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun EmptyState(state: ChatUiState) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.model?.name.orEmpty(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Running entirely on your phone. Nothing you type here leaves the device, and " +
                "it works with no network at all.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        if (state.toolsActive) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Row(
                    Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "This model can control the app. Try \"open settings\" or " +
                            "\"how much RAM does my phone have?\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }

        // The model cannot do app functions even though the user has them switched on. Say so --
        // otherwise they ask it to open settings, nothing happens, and the app looks broken.
        state.toolsUnavailableReason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Scrolls to the very end of the transcript -- reliably, even when the last bubble is taller than the
 * screen, which a plain `scrollToItem(last)` leaves showing only its top. It jumps to the last item,
 * then scrolls past whatever of it remains below the fold; the second scroll clamps at the true end,
 * content padding included, so no manual offset arithmetic is needed.
 *
 * [waitFrame] gives a just-changed bubble (a new message, freshly rendered Markdown) one frame to lay
 * out at its final height before we scroll, so we land on where its bottom actually ends up.
 */
private suspend fun LazyListState.scrollToEnd(waitFrame: Boolean = true) {
    if (waitFrame) withFrameNanos { }
    val count = layoutInfo.totalItemsCount
    if (count == 0) return
    scrollToItem(count - 1)
    scrollBy(SCROLL_TO_END_OVERSHOOT)
}

/** Far more than any single bubble is tall; [scrollBy] clamps it to the real end of the list. */
private const val SCROLL_TO_END_OVERSHOOT = 1_000_000f

/** Warn only in the last 20% of the window, where the next turn could realistically overflow. */
private const val CONTEXT_WARN_THRESHOLD = 0.8f
