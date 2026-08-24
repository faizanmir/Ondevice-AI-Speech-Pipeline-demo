@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.aiagenttestapp.ui.audit

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiagenttestapp.data.audit.AuditStatus
import kotlinx.coroutines.launch

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

    // Same rendering and same share sheet the list rows use -- see [shareAuditReport].
    val shareReport: () -> Unit = share@{
        val doc = document ?: return@share
        scope.launch { shareAuditReport(context, doc, modelName) }
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
