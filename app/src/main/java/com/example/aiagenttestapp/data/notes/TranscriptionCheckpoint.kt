package com.example.aiagenttestapp.data.notes

import com.example.aiagenttestapp.functions.MarkerEdge
import com.example.aiagenttestapp.functions.MarkerKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Compact string encodings for the small structures that have to cross a process boundary.
 *
 * Spoken markers and command ranges are handed to a WorkManager job as input data, and WorkManager
 * persists that in its own database -- so they need a text form. Hand-rolled rather than reaching for a
 * serialisation library, because the whole payload is a handful of integers and adding
 * kotlinx-serialization to the app module for it would be a poor trade.
 */
object MarkerCodec {

    fun encodeMarkers(markers: List<SpokenMarker>): String = markers.joinToString(";") {
        "${it.kind.name}|${it.edge.name}|${it.startSample}|${it.endSample}"
    }

    fun decodeMarkers(encoded: String?): List<SpokenMarker> {
        if (encoded.isNullOrBlank()) return emptyList()

        return encoded.split(";").mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size != 4) return@mapNotNull null

            val kind = MarkerKind.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
            val edge = MarkerEdge.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            val start = parts[2].toIntOrNull() ?: return@mapNotNull null
            val end = parts[3].toIntOrNull() ?: return@mapNotNull null

            SpokenMarker(kind, edge, start, end)
        }
    }

    fun encodeRanges(ranges: List<IntRange>): String =
        ranges.joinToString(";") { "${it.first}-${it.last}" }

    fun decodeRanges(encoded: String?): List<IntRange> {
        if (encoded.isNullOrBlank()) return emptyList()

        return encoded.split(";").mapNotNull { entry ->
            val parts = entry.split("-")
            if (parts.size != 2) return@mapNotNull null
            val from = parts[0].toIntOrNull() ?: return@mapNotNull null
            val to = parts[1].toIntOrNull() ?: return@mapNotNull null
            if (to < from) null else from..to
        }
    }
}

/**
 * What the transcription worker has finished so far, kept beside the audio file.
 *
 * This is what makes a killed worker resume rather than restart. Transcribing a long recording on a
 * phone is minutes of work; without a checkpoint, a process death two minutes in throws all of it away
 * and starts again, which on a long meeting can mean never finishing at all.
 *
 * Deliberately a sidecar file rather than rows in Room: it is scratch state belonging to one attempt at
 * one recording, it dies with the audio it describes, and putting it in the database would mean a
 * schema for something whose whole lifetime is shorter than a single note's.
 *
 * Written with `org.json`, which is part of Android, for the same reason [MarkerCodec] is hand-rolled.
 */
class TranscriptionCheckpoint(private val file: File) {

    /** A slice that has already been transcribed. */
    data class Done(
        val from: Int,
        val until: Int,
        val text: String,
        val language: String?,
    )

    /**
     * Where speech was found, if voice-activity detection ran.
     *
     * Kept for correctness rather than for speed -- the VAD is only a couple of seconds even on a
     * long recording. Speech regions decide slice boundaries, and the checkpoint resumes by matching
     * a slice's exact sample range against what was already transcribed. A restart that recomputed
     * even slightly different regions would shift every boundary, match nothing, and re-transcribe a
     * recording that was nearly finished.
     *
     * [ran] distinguishes "the VAD ran and found these regions" from "it never ran", which an empty
     * list alone cannot: one means skip everything outside them, the other means transcribe
     * everything.
     */
    data class SpeechActivity(val ran: Boolean, val regions: List<IntRange>?)

    /**
     * Everything the worker needs to know that is not in the audio itself.
     *
     * Written here rather than only into the job's input data, because input data dies with the job. If
     * WorkManager loses the request -- a force-stop, a cleared app, a database pruned -- re-enqueueing
     * from the surviving audio file would otherwise produce an *untagged* transcript, silently throwing
     * away markers the user deliberately spoke. Beside the audio, they last exactly as long as they are
     * needed.
     */
    data class Request(
        val markers: List<SpokenMarker>,
        val excludedRanges: List<IntRange>,
        /**
         * Which recogniser this recording was made for.
         *
         * Here rather than only in the job's input data for the same reason the markers are, and the
         * consequence of leaving it out would be subtler than a lost tag: [reconcileOrphans]
         * re-enqueues with nothing but a note id, so an interrupted Gemma transcription would quietly
         * finish on the ONNX model -- possibly with a different slicing granularity, and certainly
         * with half the note transcribed by each.
         */
        val sttBackend: SttBackend = SttBackend.DEFAULT,
        /**
         * The model the Gemma path resolved to when the recording was made, so a resume prefers the
         * same one. Null for the ONNX path, which picks its model from Settings on every run.
         */
        val sttModelId: String? = null,
    )

    private var done = mutableListOf<Done>()
    private var speech: SpeechActivity? = null
    private var request: Request? = null

    /**
     * Whether what is on disk has been read into memory yet.
     *
     * Every mutator checks this and loads first, because [persist] rewrites the whole file from
     * memory and this class is constructed freshly at several sites that each own one section of
     * it. Without the guard, those writers destroyed each other's work in sequence: the record
     * screen's stop handler wrote its request through a fresh instance and wiped every slice the
     * pipeline had pre-decoded during the recording -- so the worker missed on all of them and the
     * user waited out the full transcription the pre-decoding existed to remove. Nothing reported
     * a problem; the transcript was simply slow.
     */
    private var loaded = false

    private fun ensureLoaded() {
        if (!loaded) load()
    }

    /** Reads whatever a previous attempt left. A corrupt or absent file simply means "start over". */
    fun load() {
        loaded = true
        done = mutableListOf()
        speech = null
        request = null

        if (!file.exists()) return

        runCatching {
            val root = JSONObject(file.readText())

            root.optJSONObject("request")?.let { r ->
                request = Request(
                    markers = MarkerCodec.decodeMarkers(r.optString("markers")),
                    excludedRanges = MarkerCodec.decodeRanges(r.optString("excluded")),
                    // Absent in checkpoints written before the backend was a choice, and absent is
                    // exactly right for them: those recordings were all made on the ONNX path.
                    sttBackend = SttBackend.fromSlug(r.optString("sttBackend").ifBlank { null }),
                    sttModelId = r.optString("sttModelId").ifBlank { null },
                )
            }

            val slices = root.optJSONArray("slices") ?: JSONArray()
            for (i in 0 until slices.length()) {
                val entry = slices.getJSONObject(i)
                done += Done(
                    from = entry.getInt("from"),
                    until = entry.getInt("until"),
                    text = entry.getString("text"),
                    language = entry.optString("language").ifBlank { null },
                )
            }

            root.optJSONObject("speech")?.let { s ->
                speech = SpeechActivity(
                    ran = s.optBoolean("ran", false),
                    // Absent means "ran, but found no useful restriction" -- transcribe everything.
                    regions = if (s.has("regions")) {
                        MarkerCodec.decodeRanges(s.optString("regions"))
                    } else {
                        null
                    },
                )
            }
        }.onFailure {
            // A half-written checkpoint is worth exactly nothing and must not be trusted.
            done = mutableListOf()
            speech = null
        }
    }

    fun requestOrNull(): Request? {
        ensureLoaded()
        return request
    }

    /** Written by the recorder before the job is enqueued, so the job never owns the only copy. */
    fun recordRequest(request: Request) {
        ensureLoaded()
        this.request = request
        persist()
    }

    fun speechActivity(): SpeechActivity? {
        ensureLoaded()
        return speech
    }

    fun recordSpeechActivity(regions: List<IntRange>?) {
        ensureLoaded()
        speech = SpeechActivity(ran = true, regions = regions)
        persist()
    }

    /**
     * Forgets the speech-activity result while keeping everything else.
     *
     * Exists for the retry path. The regions are checkpointed for resume-correctness, but that
     * makes a *wrong* VAD verdict permanent: a quiet recording judged all-silence fails with
     * "Nothing was recognised. Try again", and the retry loaded the very regions that produced the
     * failure -- so the button could never succeed. Clearing only this entry lets the retry hear
     * the audio afresh while the slices already transcribed still resume.
     */
    fun clearSpeechActivity() {
        ensureLoaded()
        speech = null
        persist()
    }

    /** The transcription already done for this exact slice, if any. */
    fun textFor(range: IntRange): Done? {
        ensureLoaded()
        return done.firstOrNull { it.from == range.first && it.until == range.last + 1 }
    }

    fun record(range: IntRange, text: String, language: String?) {
        ensureLoaded()
        done.removeAll { it.from == range.first && it.until == range.last + 1 }
        done += Done(range.first, range.last + 1, text, language)
        persist()
    }

    fun delete() {
        runCatching { file.delete() }
    }

    private fun persist() {
        runCatching {
            val root = JSONObject()

            request?.let { r ->
                root.put(
                    "request",
                    JSONObject()
                        .put("markers", MarkerCodec.encodeMarkers(r.markers))
                        .put("excluded", MarkerCodec.encodeRanges(r.excludedRanges))
                        .put("sttBackend", r.sttBackend.slug)
                        .put("sttModelId", r.sttModelId ?: ""),
                )
            }

            root.put(
                "slices",
                JSONArray().apply {
                    done.forEach { slice ->
                        put(
                            JSONObject()
                                .put("from", slice.from)
                                .put("until", slice.until)
                                .put("text", slice.text)
                                .put("language", slice.language ?: ""),
                        )
                    }
                },
            )

            speech?.let { s ->
                root.put(
                    "speech",
                    JSONObject()
                        .put("ran", s.ran)
                        .apply {
                            // Only written when there is a restriction. Absent is meaningful --
                            // see [SpeechActivity] -- so an empty string must not stand in for it.
                            s.regions?.let { put("regions", MarkerCodec.encodeRanges(it)) }
                        },
                )
            }

            // Written to a temp file and renamed: a process killed mid-write would otherwise leave
            // truncated JSON, and the next attempt would discard a checkpoint that was nearly complete.
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                file.writeText(root.toString())
                temp.delete()
            }
        }
    }

    companion object {
        /** The checkpoint that belongs beside a given audio file. */
        fun forAudio(audio: File): TranscriptionCheckpoint =
            TranscriptionCheckpoint(File(audio.parentFile, "${audio.name}.progress"))
    }
}
