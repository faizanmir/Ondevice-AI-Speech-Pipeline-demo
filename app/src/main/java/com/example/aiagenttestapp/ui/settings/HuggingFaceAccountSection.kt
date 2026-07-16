package com.example.aiagenttestapp.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagenttestapp.data.HuggingFaceAuth
import kotlinx.coroutines.launch

/**
 * Sign in to HuggingFace with a personal access token.
 *
 * A pasted token rather than OAuth: it is what HuggingFace's own tooling uses, it needs no client
 * secret shipped inside the APK, and it lets the user issue a read-only token scoped to exactly
 * this purpose and revoke it independently.
 */
@Composable
fun HuggingFaceAccountSection(auth: HuggingFaceAuth) {
    val account by auth.account.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var tokenInput by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (account != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = account?.fullName ?: account!!.username,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "@${account!!.username}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = {
                auth.signOut()
                tokenInput = ""
                error = null
            }) {
                Text("Sign out")
            }
        }

        Text(
            text = "Gated models — Gemma 3, Llama, and Google's official FunctionGemma — can now " +
                "be downloaded. You still have to accept each model's licence once, on its " +
                "HuggingFace page.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        text = "Many of the best models — Gemma 3, Llama 3.2, Google's official FunctionGemma — " +
            "are gated on HuggingFace and need an account to download. Everything else in the app " +
            "works without signing in.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(
        text = "Create a read-only access token at huggingface.co/settings/tokens and paste it here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = tokenInput,
        onValueChange = {
            tokenInput = it
            error = null
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Access token") },
        placeholder = { Text("hf_...") },
        singleLine = true,
        // A bearer credential should not sit in plain sight, and it must never end up in the
        // keyboard's learned-words dictionary.
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        isError = error != null,
        supportingText = error?.let { message ->
            { Text(message, color = MaterialTheme.colorScheme.error) }
        },
    )

    Button(
        onClick = {
            isVerifying = true
            error = null
            scope.launch {
                val result = auth.signIn(tokenInput)
                isVerifying = false
                result.onSuccess { tokenInput = "" }
                result.onFailure { error = it.message ?: "Could not sign in" }
            }
        },
        enabled = tokenInput.isNotBlank() && !isVerifying,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isVerifying) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text("Checking with HuggingFace")
        } else {
            Text("Sign in")
        }
    }

    Text(
        text = "The token is encrypted with a key held in this phone's secure hardware, and is " +
            "only ever sent to huggingface.co.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Arrangement helper so the section's children space themselves like the rest of Settings. */
val HuggingFaceSectionArrangement = Arrangement.spacedBy(10.dp)
