package com.example.aiagenttestapp.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pulls plain text out of a file the user attaches, so the model can act on its contents.
 *
 * Text-based files (txt, md, csv, json, source code, ...) are read directly; PDFs go through PDFBox.
 * Bounded on purpose: the extracted text is capped at the caller-supplied [extract] `maxChars` and
 * the caller is told when it was truncated -- feeding more than a model's context can hold just
 * overflows it and fails the turn. The caller sizes that cap to the loaded model's context window,
 * so a large-context model accepts a big file while a small one takes only what it can chew.
 */
class FileTextExtractor(context: Context) {

    private val appContext = context.applicationContext

    sealed interface Result {
        data class Success(val name: String, val text: String, val truncated: Boolean) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun extract(uri: Uri, maxChars: Int = MAX_CHARS): Result = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "file"
        val mime = appContext.contentResolver.getType(uri).orEmpty()
        try {
            val raw = when {
                isPdf(mime, name) -> extractPdf(uri)
                isTextLike(mime, name) -> readText(uri)
                else -> return@withContext Result.Failure(
                    "$name can't be read. Attach a PDF or a text file (txt, md, csv, json, code, ...).",
                )
            }
            if (raw.isBlank()) {
                return@withContext Result.Failure("No text could be extracted from $name.")
            }
            val limit = maxChars.coerceAtLeast(1)
            Result.Success(name, raw.take(limit).trim(), truncated = raw.length > limit)
        } catch (e: Exception) {
            Result.Failure("Could not read $name: ${e.message ?: "unknown error"}")
        }
    }

    private fun readText(uri: Uri): String =
        appContext.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            .orEmpty()

    private fun extractPdf(uri: Uri): String {
        // Ships fonts/resources as assets; init once (idempotent) before the first document loads.
        PDFBoxResourceLoader.init(appContext)
        return appContext.contentResolver.openInputStream(uri)?.use { stream ->
            PDDocument.load(stream).use { document -> PDFTextStripper().getText(document) }
        }.orEmpty()
    }

    private fun displayName(uri: Uri): String? =
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun isPdf(mime: String, name: String) =
        mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)

    private fun isTextLike(mime: String, name: String): Boolean =
        mime.startsWith("text/") ||
            mime in TEXT_MIMES ||
            TEXT_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    private companion object {
        /** Default cap (~3k tokens) for callers that do not size one to a specific model's context. */
        const val MAX_CHARS = 10_000

        val TEXT_MIMES = setOf(
            "application/json", "application/xml", "application/csv", "application/x-yaml",
            "application/javascript", "application/x-sh", "application/x-httpd-php",
        )

        val TEXT_EXTENSIONS = listOf(
            ".txt", ".md", ".markdown", ".csv", ".tsv", ".json", ".xml", ".yaml", ".yml", ".toml",
            ".ini", ".cfg", ".conf", ".log", ".html", ".htm", ".kt", ".kts", ".java", ".py", ".js",
            ".ts", ".tsx", ".jsx", ".c", ".h", ".cpp", ".cc", ".cs", ".go", ".rs", ".rb", ".php",
            ".swift", ".sh", ".sql", ".gradle", ".properties",
        )
    }
}
