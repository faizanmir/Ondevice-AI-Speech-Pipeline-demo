package com.example.aiagenttestapp.data.speakers

/**
 * Whether running this recording again under the current model selection should produce a **new
 * row** rather than overwrite this one.
 *
 * A transcript is the output of three models, and the row now records which ([speechModelId],
 * [speakerBundleId]). Re-running under different models used to replace the transcript in place,
 * which made the one comparison this screen exists for -- the same recording under two model
 * sets, side by side -- impossible: the first result was gone the moment the second was asked for.
 * So a re-run that would change any model becomes a sibling row on the same audio, and the list
 * shows both, each labelled with its models.
 *
 * Same models, or a row that never recorded any (never run, or run before the columns existed):
 * run in place, as before. Re-running the same configuration is a retry, not a comparison.
 */
fun DiarizedRecording.needsNewRowFor(currentSpeechModelId: String, currentSpeakerBundleId: String): Boolean {
    val recordedSpeech = speechModelId ?: return false
    val recordedBundle = speakerBundleId ?: return false
    return recordedSpeech != currentSpeechModelId || recordedBundle != currentSpeakerBundleId
}
