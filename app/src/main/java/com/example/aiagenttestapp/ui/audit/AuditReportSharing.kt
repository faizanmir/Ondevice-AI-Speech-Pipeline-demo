package com.example.aiagenttestapp.ui.audit

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.aiagenttestapp.data.audit.AuditDocument
import com.example.aiagenttestapp.data.audit.AuditMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders [doc]'s report to a PDF and hands it to the system share sheet.
 *
 * Lives here rather than inside the report screen because the same action is now offered from the
 * list rows, and a second copy of it would be a second chance for the two to disagree about the
 * file name, the MIME type or which of the URI-permission flags actually gets set.
 *
 * The share sheet rather than a save dialog: sharing is the general case and saving is one of its
 * options -- "Save to Files" and Drive both appear there -- where a save dialog can only ever save.
 * An audit report's usual destination is an email or a chat to whoever asked for it.
 *
 * Suspends because PDF text layout is not free and must not run on the main thread; the chooser is
 * started back on the caller's dispatcher, so call this from a UI-scoped coroutine.
 */
suspend fun shareAuditReport(context: Context, doc: AuditDocument, modelName: String?) {
    val result = doc.result
    if (result == null) {
        Toast.makeText(context, "This report is not finished yet.", Toast.LENGTH_SHORT).show()
        return
    }

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
        return
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
