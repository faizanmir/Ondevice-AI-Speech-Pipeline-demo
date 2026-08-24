package com.example.aiagenttestapp.ui.splash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagenttestapp.R
import com.example.aiagenttestapp.ui.components.readableWidth

/** A runtime permission the splash walks the user through, with the reason the app wants it. */
private data class PermissionStep(
    val permission: String,
    val icon: ImageVector,
    val title: String,
    val rationale: String,
)

/**
 * The launch screen. It does two jobs before the app proper appears:
 *
 *  1. Walks through the runtime permissions the manifest declares but nothing had ever requested --
 *     the rationale is shown *before* each system dialog, so the user knows why they are being
 *     asked. Denying anything is fine: the app works without, and point-of-use flows (the mic
 *     buttons) ask again in context.
 *  2. Initializes the model pathway -- verifies which models are on disk and starts the warm-load
 *     of the active model -- via [SplashViewModel].
 *
 * It leaves as soon as both are done; with everything already granted that is a brief flash of
 * branding while the disk scan runs.
 */
@Composable
fun SplashScreen(
    viewModel: SplashViewModel,
    onReady: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The permissions still to ask for, fixed when the splash opens. Already-granted ones never
    // produce a step, so a user who granted everything sees no cards at all.
    val steps = remember { pendingPermissionSteps(context) }
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val currentStep = steps.getOrNull(stepIndex)

    // Granted or denied, move to the next step: a denial only silences the download notification
    // (or defers the mic to its point-of-use prompt); it must never trap the user on the splash.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { stepIndex++ }

    val done = currentStep == null && state.modelPathwayReady
    LaunchedEffect(done) {
        if (done) onReady()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .readableWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Language models that run entirely on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(12.dp))

            if (currentStep != null) {
                PermissionRationaleCard(
                    step = currentStep,
                    onAllow = { permissionLauncher.launch(currentStep.permission) },
                    onSkip = { stepIndex++ },
                )
            } else {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Text(
                    text = if (state.modelPathwayReady) {
                        "Ready"
                    } else {
                        "Preparing on-device models…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The rationale, shown before the system dialog ever appears -- the dialog itself can only say
 * "Allow X?", so the sentence explaining *why* has to come from us, first.
 */
@Composable
private fun PermissionRationaleCard(
    step: PermissionStep,
    onAllow: () -> Unit,
    onSkip: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = step.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = step.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = step.rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                    Text("Not now")
                }
                Button(onClick = onAllow, modifier = Modifier.weight(1f)) {
                    Text("Continue")
                }
            }
        }
    }
}

/**
 * The runtime permissions the manifest declares that have not been granted yet, in the order the
 * splash should ask. Notifications first: it is the one nothing else in the app ever asks for.
 */
private fun pendingPermissionSteps(context: Context): List<PermissionStep> = buildList {
    // POST_NOTIFICATIONS exists only from Android 13; on older versions notifications just show.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
    ) {
        add(
            PermissionStep(
                permission = Manifest.permission.POST_NOTIFICATIONS,
                icon = Icons.Default.Notifications,
                title = "Download progress notifications",
                rationale = "Models are multi-gigabyte downloads that continue in the background. " +
                    "A notification shows the progress and lets Android keep the transfer alive. " +
                    "Without it downloads still work, but progress is only visible inside the app.",
            ),
        )
    }

    if (!context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
        add(
            PermissionStep(
                permission = Manifest.permission.RECORD_AUDIO,
                icon = Icons.Default.Mic,
                title = "Microphone for voice notes and dictation",
                rationale = "Voice notes and chat dictation listen through the microphone. Speech " +
                    "recognition runs entirely on this phone — audio never leaves the device. You " +
                    "can also grant this later, the first time you tap a mic button.",
            ),
        )
    }
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
