@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.aiagenttestapp.ui.audit

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagenttestapp.data.audit.AuditMode
import com.example.aiagenttestapp.data.audit.AuditStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AuditReportScreen(
    viewModel: AuditReportViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val document = state.document
    val modelName = state.modelName

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Renders the report and hands it to the system share sheet.
    //
    // The sheet rather than a save dialog because sharing is the general case and saving is one of
    // its options -- "Save to Files" and Drive both appear there -- where a save dialog can only
    // ever save. An audit report's usual destination is an email or a chat to whoever asked for it.
    //
    // Rendering happens off the main thread: the report is small, but PDF text layout is not free.
    val shareReport: () -> Unit = share@{
        val doc = document ?: return@share
        val result = doc.result ?: return@share
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val directory = File(context.cacheDir, "reports").apply { mkdirs() }
                    val file = File(directory, suggestedPdfName(doc.name, doc.mode))
                    file.outputStream().use {
                        it.write(AuditPdf.render(doc.name, result, modelName, doc.analysisMillis))
                    }
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }.getOrNull()
            }

            if (uri == null) {
                Toast.makeText(context, "Could not prepare the report.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                // Both, deliberately: EXTRA_SUBJECT is what mail apps use as the subject line, and
                // EXTRA_TITLE is what the sheet itself shows above the preview.
                putExtra(Intent.EXTRA_SUBJECT, doc.name)
                putExtra(Intent.EXTRA_TITLE, doc.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // The flag goes on the chooser too. The receiving app is granted access through whichever
            // intent actually starts it, and which of the two that is has varied across versions.
            val chooser = Intent.createChooser(send, "Share report")
                .apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.startActivity(chooser)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(document?.name ?: "Report", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Only a finished report can be exported; while analysing there is nothing to
                    // put on paper yet, so the action simply is not offered.
                    if (document?.result != null) {
                        IconButton(onClick = shareReport) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = "Share as PDF")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val doc = document
        val result = doc?.result
        when {
            doc == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            result == null -> Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (doc.status) {
                        AuditStatus.FAILED -> doc.error ?: "Analysis failed."
                        AuditStatus.QUEUED, AuditStatus.ANALYSING -> "Still analysing…"
                        else -> "No report available."
                    },
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
                AuditReportContent(
                    analysis = result,
                    modelName = modelName,
                    analysisMillis = doc.analysisMillis,
                )
            }
        }
    }
}

/**
 * "site-visit.txt" -> "site-visit-audit.pdf": safe for every documents provider, never blank.
 *
 * The suffix names the read, so a quick summary and a full audit of the same file do not land in
 * the user's downloads under the same name -- and neither is mislabelled as the other.
 */
private fun suggestedPdfName(documentName: String, mode: AuditMode): String {
    val base = documentName
        .substringBeforeLast('.')
        .replace(Regex("[^A-Za-z0-9 _-]"), "")
        .trim()
        .replace(Regex("\\s+"), "-")
        .ifBlank { if (mode == AuditMode.QUICK) "summary" else "audit-report" }
    return if (mode == AuditMode.QUICK) "$base-summary.pdf" else "$base-audit.pdf"
}
