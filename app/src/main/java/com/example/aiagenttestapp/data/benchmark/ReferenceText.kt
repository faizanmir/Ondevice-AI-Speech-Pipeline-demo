package com.example.aiagenttestapp.data.benchmark

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a reference transcript out of a picked file.
 *
 * One reader for the two features that score against a reference. The benchmark screen and the
 * speaker-transcript screen had a byte-for-byte copy of this each -- same open, same size guard,
 * same UTF-8 decode, the same 2 MB constant declared twice, and the same sentence shown to the
 * user. Two copies of a cap and its error message is one raised cap away from a screen that refuses
 * a file while telling the user the wrong limit.
 */
object ReferenceText {

    /** Far above any real reference script; a guard against picking the wrong file entirely. */
    const val MAX_BYTES = 2 * 1024 * 1024

    /** What a picker should ask for: text, plus the wildcard untyped corpus files need. */
    val MIME_TYPES = arrayOf("text/*", "*/*")

    /** The one sentence both screens show when a pick cannot be read. */
    const val UNREADABLE = "Could not read the reference transcript (is it a text file under 2 MB?)."

    /** The file's text, or null if it could not be opened, decoded, or is over [MAX_BYTES]. */
    suspend fun read(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size > MAX_BYTES) null else String(bytes, Charsets.UTF_8)
            }
        }.getOrNull()
    }
}
