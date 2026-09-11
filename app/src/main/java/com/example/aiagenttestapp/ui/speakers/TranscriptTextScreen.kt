@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.aiagenttestapp.ui.speakers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * The transcript file, on screen.
 *
 * Shows the very text [shareTranscript] writes, so what the reader sees here is what the recipient
 * gets -- the header with the models and the score, then one turn per line. Selectable, because the
 * second most common thing to do with a transcript after sending it is to copy a few lines out of
 * it. Share sits in the top bar so viewing and exporting are the same trip.
 */
@Composable
fun TranscriptTextScreen(
    viewModel: TranscriptTextViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recording = state.recording
    val text = state.text

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recording?.name ?: "Transcript", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (recording != null && text != null) {
                        IconButton(onClick = {
                            scope.launch {
                                shareTranscript(context, recording, state.blocks, state.models)
                            }
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Export")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            !state.loaded -> Centred(padding) { WorkingPulse("Opening…") }

            recording == null -> Centred(padding) {
                Text("This recording has been deleted.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            text == null -> Centred(padding) {
                Text(
                    "No transcript yet — run the recording first.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Centred(padding: PaddingValues, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
        content()
    }
}
