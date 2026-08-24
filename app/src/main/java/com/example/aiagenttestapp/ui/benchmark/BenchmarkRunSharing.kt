package com.example.aiagenttestapp.ui.benchmark

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.aiagenttestapp.data.benchmark.BenchmarkClip
import com.example.aiagenttestapp.data.benchmark.BenchmarkRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Hands a finished run to the share sheet as a report the other side can act on.
 *
 * Shaped by what a number is worth without its context, which is nothing. A WER quoted on its own
 * has already cost this project a day: 26% and 7.9% on the same audio and the same engine turned
 * out to be a language-pack difference, and neither figure carried the pack it ran with. So the
 * report leads with the environment -- device, Android version, speech-service build, pack, feed
 * rate -- and only then gives the score, in the order the shared protocol says to read it:
 * **coverage first**, because a truncated run produces a plausible-looking error rate that is
 * really a measure of missing audio.
 *
 * Two payloads on one intent, on purpose. [Intent.EXTRA_TEXT] carries the summary, which is what a
 * chat or a mail body will paste inline; [Intent.EXTRA_STREAM] carries the full file including the
 * transcript, which is what the other side needs to re-score independently. Apps take whichever
 * they understand, and the two say the same thing.
 */
suspend fun shareBenchmarkRun(context: Context, run: BenchmarkRun, clip: BenchmarkClip) {
    if (run.transcript.isBlank()) {
        Toast.makeText(context, "This run has no transcript to share.", Toast.LENGTH_SHORT).show()
        return
    }

    val summary = summarise(context, run, clip)
    val uri = withContext(Dispatchers.IO) {
        runCatching {
            // The same cache directory the audit reports use, already exposed by file_paths.xml.
            val directory = File(context.cacheDir, "reports").apply { mkdirs() }
            val file = File(directory, suggestedName(run, clip))
            file.writeText(summary + "\n\n## Transcript\n\n" + run.transcript.trim() + "\n")
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    val send = Intent(Intent.ACTION_SEND).apply {
        // text/plain rather than text/markdown: the file is markdown, but the reach of the share
        // sheet matters more than the label, and every target understands plain text.
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, summary)
        putExtra(Intent.EXTRA_SUBJECT, "STT benchmark — ${clip.name}")
        putExtra(Intent.EXTRA_TITLE, "STT benchmark — ${clip.name}")
        uri?.let {
            putExtra(Intent.EXTRA_STREAM, it)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    val chooser = Intent.createChooser(send, "Share result")
        .apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    context.startActivity(chooser)
}

/** The report itself: environment, then coverage, then the score, then what went wrong. */
private fun summarise(context: Context, run: BenchmarkRun, clip: BenchmarkClip): String {
    val coverage = run.coveragePercent
    val truncated = coverage != null && coverage < 90.0

    return buildString {
        appendLine("# STT benchmark — ${clip.name}")
        appendLine()
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| clip | ${formatDuration(clip.durationMillis)} · ${clip.language.uppercase()} · ${clip.referenceText.split(Regex("\\s+")).size} reference words |")
        appendLine("| settings | ${run.settingsSummary} |")
        appendLine("| device | ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) |")
        appendLine("| speech service | ${speechServiceBuild(context)} |")
        appendLine("| run at | ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(run.startedAtMillis))} |")
        appendLine()

        // Coverage first, and said plainly when it fails, because everything below it is then
        // describing absent audio rather than a recogniser's accuracy.
        appendLine("## Result")
        appendLine()
        if (coverage != null) {
            appendLine("**Coverage %.1f%%**%s".format(coverage, if (truncated) " — **TRUNCATED RUN**" else ""))
            appendLine()
        }
        if (truncated) {
            appendLine("> Coverage is under 90%: the transcript is incomplete, so the error rate below")
            appendLine("> measures how much audio never came back, not how well it was heard. This is a")
            appendLine("> failed run — diagnose the run before comparing it with anything.")
            appendLine()
        }
        appendLine("| metric | value |")
        appendLine("|---|---|")
        appendLine("| WER (number-normalised) | **%.1f%%** |".format(run.normalisedWerPercent ?: 0.0))
        appendLine("| WER (raw) | %.1f%% |".format(run.rawWerPercent ?: 0.0))
        run.cerPercent?.let { appendLine("| CER | %.1f%% |".format(it)) }
        appendLine("| substitutions / deletions / insertions | ${run.substitutions} / ${run.deletions} / ${run.insertions} |")
        appendLine("| reference words | ${run.referenceWords} |")
        run.wallMillis?.takeIf { it > 0 }?.let {
            appendLine("| speed | %.1f× real time (%s) |".format(clip.durationMillis.toDouble() / it, formatDuration(it)))
        }
        appendLine()

        if (run.topPairs.isNotBlank()) {
            appendLine("## Most frequent errors")
            appendLine()
            run.topPairs.lineSequence().take(15).forEach { appendLine("- $it") }
            appendLine()
        }

        appendLine("---")
        appendLine()
        appendLine("Scored on device: word-level minimum edit distance (the NIST/`sclite` definition),")
        appendLine("WER = (S + D + I) / reference words. The normalised pass rewrites number phrases on")
        appendLine("both sides identically; the raw pass does not. Quote the raw figure when comparing")
        appendLine("against a scorer whose normalisation you do not know.")
    }
}

/**
 * The recogniser's build, which the shared protocol asks for by name.
 *
 * Google updates this silently, so a result without it is not reproducible -- the same app on the
 * same audio can score differently a month later with nothing on this side having changed. Both
 * candidate packages are tried because which one serves `createOnDeviceSpeechRecognizer` is a
 * vendor decision.
 */
private fun speechServiceBuild(context: Context): String {
    for (pkg in listOf("com.google.android.as", "com.google.android.tts")) {
        val version = runCatching {
            context.packageManager.getPackageInfo(pkg, 0).versionName
        }.getOrNull()
        if (version != null) return "$pkg $version"
    }
    return "unknown"
}

private fun suggestedName(run: BenchmarkRun, clip: BenchmarkClip): String {
    val base = clip.name.substringBeforeLast('.')
        .replace(Regex("[^A-Za-z0-9_-]"), "-")
        .ifBlank { "clip" }
    return "$base-run${run.id}.md"
}

