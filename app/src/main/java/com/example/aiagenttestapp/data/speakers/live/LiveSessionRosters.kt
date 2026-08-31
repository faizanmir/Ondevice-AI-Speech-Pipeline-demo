package com.example.aiagenttestapp.data.speakers.live

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One voice a live session heard: what it was called, and what it sounded like in this recording. */
class RosterVoice(val label: String, val voiceprint: FloatArray)

/**
 * What each live session learned about its speakers, kept for the final pass to build on.
 *
 * The live session and the batch pass used to be strangers: the session carried every voice across
 * its chunks by voiceprint, showed a consistent "Speaker A" for twenty minutes, and then handed the
 * finished file to a batch pass that started from nothing -- and on a recording with unenrolled
 * voices the batch pass's chunks cannot recognise each other, so the same person came back as
 * "Unknown Speaker 1", "3", "5" and "7". The transcript the user had been watching was better than
 * the one that replaced it.
 *
 * This is the hand-over. The session stores its roster here when it ends; the batch pass reads it
 * and treats every entry as an enrolment that exists only for this recording -- a voiceprint taken on
 * this microphone in this room, labelled with the letter or name the session used. Clusters that
 * match it keep the live labels; clusters that match nobody are numbered as before.
 *
 * In memory only, by choice for now: the hand-over happens seconds after the session ends, within one
 * process, and a roster lost to a process death costs consistency of letters, not correctness --
 * the batch pass still runs, it just names from scratch.
 */
@Singleton
class LiveSessionRosters @Inject constructor() {

    private val byRecording = ConcurrentHashMap<Long, List<RosterVoice>>()

    fun put(recordingId: Long, voices: List<RosterVoice>) {
        if (voices.isEmpty()) byRecording.remove(recordingId) else byRecording[recordingId] = voices
    }

    fun get(recordingId: Long): List<RosterVoice> = byRecording[recordingId].orEmpty()

    fun clear(recordingId: Long) {
        byRecording.remove(recordingId)
    }

    /** The roster as [com.example.aiagenttestapp.data.speakers.SpeakerRepository.foldAndName] wants it: label to takes. */
    fun asVoices(recordingId: Long): Map<String, List<FloatArray>> =
        get(recordingId).groupBy({ it.label }, { it.voiceprint })
}
