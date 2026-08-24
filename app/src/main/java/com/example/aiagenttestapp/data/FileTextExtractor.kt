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
            // Sanitised before it is measured, so the cap counts characters that will actually be
            // sent rather than ones about to be removed.
            val text = sanitise(raw)
            Result.Success(name, text.take(limit).trim(), truncated = text.length > limit)
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

    internal companion object {
        /**
         * Removes the characters that are wrong in every downstream use, and only those.
         *
         * Extraction hands back whatever the file held: a PDF stripper emits a form feed at every page
         * break, exported transcripts carry stray NULs and vertical tabs, and copy-pasted text arrives
         * full of zero-width joiners and byte-order marks. Each of those then travels the whole pipeline
         * -- into the prompt, back out inside a quoted value, and into a report.
         *
         * What each one costs:
         *  - A control character is illegal raw inside a JSON string, so a model copying a quote across
         *    a page break produces JSON no parser will take.
         *  - It is equally fatal to the line-oriented records format: a form feed mid-line is not a line
         *    break to the parser but is one to the model, so a field silently loses its value.
         *  - A zero-width character is invisible in both the document and the reply, and makes
         *    [AuditEvidence]'s substring check fail on a quote that looks identical to the source. The
         *    finding then loses evidence it genuinely had.
         *
         * Deliberately NOT normalised: quotation marks, dashes, spacing and case. Straight quotes break
         * JSON strings and nothing else, and the fix for that is asking for a format that has nothing to
         * escape, not rewriting the document. An evidence quote is supposed to be word-for-word from the
         * source, and the more this rewrites the source the less that claim is worth.
         */
        internal fun sanitise(raw: String): String = buildString(raw.length) {
            for (ch in raw) {
                when {
                    // The two control characters that carry meaning. \r is dropped rather than kept:
                    // CRLF becomes LF, and a lone CR would otherwise read as a line break to the model
                    // and as nothing to the parser.
                    ch == '\n' || ch == '\t' -> append(ch)
                    ch == '\r' -> Unit
                    // A space, not nothing: a form feed at a page break separates two words, and
                    // deleting it would run the last word of one page into the first of the next.
                    ch.isISOControl() -> append(' ')
                    ch in ZERO_WIDTH -> Unit
                    else -> append(ch)
                }
            }
        }

        /** Default cap (~3k tokens) for callers that do not size one to a specific model's context. */
        const val MAX_CHARS = 10_000

        /**
         * Invisible characters that survive a copy-paste and defeat an exact-match check.
         *
         * The BOM (U+FEFF) leads many exported files; the joiners and the soft hyphen come from
         * word processors. None of them render, so a quote carrying one looks character-for-character
         * identical to the source and still fails to match it.
         */
        val ZERO_WIDTH = setOf(
            '\uFEFF', // byte-order mark
            '\u200B', // zero-width space
            '\u200C', // zero-width non-joiner
            '\u200D', // zero-width joiner
            '\u2060', // word joiner
            '\u00AD', // soft hyphen
        )

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
