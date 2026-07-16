package com.example.aiagenttestapp.ui.history

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagenttestapp.ui.components.readableWidth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onOpenChat: (modelId: String, conversationId: Long) -> Unit,
    onNewChat: (ModelSpec) -> Unit,
    onGetModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNotes: () -> Unit,
) {
    val items by viewModel.conversations.collectAsStateWithLifecycle()
    val chatModels by viewModel.chatModels.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chats", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenNotes) {
                        Icon(Icons.Default.Mic, contentDescription = "Voice notes")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            NewChatMenu(
                models = chatModels,
                onPick = onNewChat,
                onGetModels = onGetModels,
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No chats yet. Tap New chat and pick a model to begin.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                // Rows are capped at a readable width; on a tablet this centres them.
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(items, key = { it.id }) { item ->
                    HistoryRow(
                        item = item,
                        onClick = { onOpenChat(item.modelId, item.id) },
                        onDelete = { viewModel.delete(item.id) },
                    )
                }
            }
        }
    }
}

/**
 * The New-chat button, fanned out speed-dial style: tapping it opens the model choice -- the
 * built-in Gemini Nano versus the models the user has downloaded -- instead of silently starting
 * on whatever Settings last said. The choice IS the menu; there is no separate picker screen.
 *
 * Hand-rolled rather than material3's FloatingActionButtonMenu because that component has not
 * reached the 1.4 stable line this app is pinned to (only its design tokens shipped).
 */
@Composable
private fun NewChatMenu(
    models: List<ModelSpec>,
    onPick: (ModelSpec) -> Unit,
    onGetModels: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    // The system back gesture should close the fan, not leave the screen.
    BackHandler(enabled = expanded) { expanded = false }

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val (builtIn, custom) = models.partition { it.systemManaged }

                // Custom models first, built-in nearest the button: speed-dial convention puts
                // the headline action closest to the finger that just opened the fan.
                custom.forEach { model ->
                    NewChatOption(
                        label = model.name,
                        icon = Icons.Default.Memory,
                        onClick = {
                            expanded = false
                            onPick(model)
                        },
                    )
                }

                // Nothing downloaded is not a dead end -- the fan offers the way to fix it.
                if (custom.isEmpty()) {
                    NewChatOption(
                        label = "Download a model",
                        icon = Icons.Default.Download,
                        onClick = {
                            expanded = false
                            onGetModels()
                        },
                    )
                }

                builtIn.forEach { model ->
                    NewChatOption(
                        label = "${model.name} · Built-in",
                        icon = Icons.Default.AutoAwesome,
                        onClick = {
                            expanded = false
                            onPick(model)
                        },
                    )
                }
            }
        }

        // The + rotates into an x rather than swapping icons -- continuous motion reads as the
        // same control changing state, where a swap reads as a different button appearing.
        val rotation by animateFloatAsState(
            targetValue = if (expanded) 45f else 0f,
            label = "newChatFabRotation",
        )
        ExtendedFloatingActionButton(
            onClick = { expanded = !expanded },
            shape = CircleShape,
            icon = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = if (expanded) "Close" else null,
                    modifier = Modifier.rotate(rotation),
                )
            },
            text = { Text("New chat") },
        )
    }
}

/**
 * One option in the fan: a full pill, deliberately lighter than the anchor FAB -- smaller type,
 * secondary tone, less elevation -- so the fan reads as choices radiating from the button rather
 * than as a stack of rival buttons.
 */
@Composable
private fun NewChatOption(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun HistoryRow(
    item: ChatHistoryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .readableWidth()
            .clickable(onClick = onClick),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.modelName} · ${DateUtils.getRelativeTimeSpanString(item.updatedAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Delete chat",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
