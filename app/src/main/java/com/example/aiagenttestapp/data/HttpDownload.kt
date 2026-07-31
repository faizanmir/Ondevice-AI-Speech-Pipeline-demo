package com.example.aiagenttestapp.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Streams [url] to [target], reporting bytes written as it goes.
 *
 * Extracted from `SpeechModelRepository` when a second downloader
 * ([com.example.aiagenttestapp.data.audiomodels.AudioModelRepository]) needed the identical loop.
 * The two repositories model genuinely different things -- one selectable ASR model versus
 * present-or-absent bundles -- but "copy an HTTP body to a file without hanging, without lying about
 * progress, and while noticing cancellation" is the same problem in both, and getting it subtly
 * different in two places is how one of them ends up not honouring cancellation.
 *
 * [label] appears only in error messages, so a failure names the file a human recognises rather than
 * a URL.
 */
internal suspend fun downloadToFile(
    client: OkHttpClient,
    url: String,
    target: File,
    label: String,
    onProgress: suspend (bytesWritten: Long) -> Unit,
) {
    val request = Request.Builder().url(url).build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("Download of $label failed with HTTP ${response.code}")
        }
        val body = response.body ?: throw IOException("Empty response for $label")

        body.byteStream().use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(128 * 1024)
                var written = 0L
                var lastReport = 0L

                while (true) {
                    // A blocking read does not notice a cancelled worker; this check does, so a
                    // cancel stops the transfer at the next chunk instead of at the end of the file.
                    currentCoroutineContext().ensureActive()

                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    written += read

                    // Throttled: reporting every 128 KB would recompose the UI hundreds of times a
                    // second for a bar that moves a pixel.
                    if (written - lastReport > 1_000_000) {
                        lastReport = written
                        onProgress(written)
                    }
                }
                output.flush()
            }
        }
    }
}
