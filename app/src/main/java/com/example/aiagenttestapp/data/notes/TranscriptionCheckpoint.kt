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

    /** Diarisation result, if it ran. Kept so a restart never repeats it -- it is the slow half. */
    data class Diarisation(val turns: List<SpeakerTurn>, val labels: Map<Int, String>)

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
        val expectedSpeakers: Int,
    )

    private var done = mutableListOf<Done>()
    private var diarisation: Diarisation? = null
    private var request: Request? = null

    /** Reads whatever a previous attempt left. A corrupt or absent file simply means "start over". */
    fun load() {
        done = mutableListOf()
        diarisation = null
        request = null

        if (!file.exists()) return

        runCatching {
            val root = JSONObject(file.readText())

            root.optJSONObject("request")?.let { r ->
                request = Request(
                    markers = MarkerCodec.decodeMarkers(r.optString("markers")),
                    excludedRanges = MarkerCodec.decodeRanges(r.optString("excluded")),
                    expectedSpeakers = r.optInt("expectedSpeakers", 0),
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

            root.optJSONObject("diarisation")?.let { d ->
                val turns = mutableListOf<SpeakerTurn>()
                val turnsJson = d.optJSONArray("turns") ?: JSONArray()
                for (i in 0 until turnsJson.length()) {
                    val t = turnsJson.getJSONObject(i)
                    turns += SpeakerTurn(
                        range = t.getInt("from") until t.getInt("until"),
                        cluster = t.getInt("cluster"),
                    )
                }

                val labels = mutableMapOf<Int, String>()
                val labelsJson = d.optJSONObject("labels") ?: JSONObject()
                labelsJson.keys().forEach { key ->
                    key.toIntOrNull()?.let { labels[it] = labelsJson.getString(key) }
                }

                diarisation = Diarisation(turns, labels)
            }
        }.onFailure {
            // A half-written checkpoint is worth exactly nothing and must not be trusted.
            done = mutableListOf()
            diarisation = null
        }
    }

    fun requestOrNull(): Request? = request

    /** Written by the recorder before the job is enqueued, so the job never owns the only copy. */
    fun recordRequest(request: Request) {
        this.request = request
        persist()
    }

    fun diarisationResult(): Diarisation? = diarisation

    fun recordDiarisation(turns: List<SpeakerTurn>, labels: Map<Int, String>) {
        diarisation = Diarisation(turns, labels)
        persist()
    }

    /** The transcription already done for this exact slice, if any. */
    fun textFor(range: IntRange): Done? =
        done.firstOrNull { it.from == range.first && it.until == range.last + 1 }

    fun record(range: IntRange, text: String, language: String?) {
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
                        .put("expectedSpeakers", r.expectedSpeakers),
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

            diarisation?.let { d ->
                root.put(
                    "diarisation",
                    JSONObject()
                        .put(
                            "turns",
                            JSONArray().apply {
                                d.turns.forEach { turn ->
                                    put(
                                        JSONObject()
                                            .put("from", turn.range.first)
                                            .put("until", turn.range.last + 1)
                                            .put("cluster", turn.cluster),
                                    )
                                }
                            },
                        )
                        .put(
                            "labels",
                            JSONObject().apply {
                                d.labels.forEach { (cluster, name) -> put(cluster.toString(), name) }
                            },
                        ),
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
