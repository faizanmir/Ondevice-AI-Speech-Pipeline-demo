package com.example.aiagenttestapp.functions

import com.example.aiagent.engine.core.VoiceCommandSpec

/** What a detected voice command does. */
sealed interface VoiceCommandAction {
    /** Leave the recorder and go somewhere. This discards the in-progress recording. */
    data class Navigate(val destination: AppNavigation) : VoiceCommandAction

    /** Stop recording and move to transcript review, exactly as the Stop button does. */
    data object StopRecording : VoiceCommandAction

    /** Throw the recording away and reset. */
    data object Discard : VoiceCommandAction
}

/**
 * The commands the user can speak while recording a voice note.
 *
 * Kept small and unambiguous on purpose. Every phrase here has to be something a person would only
 * say when they mean the command -- "open settings" is safe, a bare "open" or "stop" would fire
 * during ordinary dictation. Each command carries a couple of natural variants because people do
 * not say the exact same words twice.
 *
 * The navigation commands reuse the very same [AppNavigation] destinations that the in-chat model's
 * app-functions use, so "open settings" lands in the same place whether it was typed to the model
 * or spoken into a recording.
 */
object VoiceCommands {

    private val actions: Map<String, VoiceCommandAction> = mapOf(
        "open_settings" to VoiceCommandAction.Navigate(AppNavigation.Settings),
        "open_models" to VoiceCommandAction.Navigate(AppNavigation.Catalog),
        "stop_recording" to VoiceCommandAction.StopRecording,
        "discard_recording" to VoiceCommandAction.Discard,
    )

    val specs: List<VoiceCommandSpec> = listOf(
        VoiceCommandSpec(
            id = "open_settings",
            phrases = listOf("open settings", "open the settings", "go to settings"),
        ),
        VoiceCommandSpec(
            id = "open_models",
            phrases = listOf("open models", "open model catalog", "show models"),
        ),
        VoiceCommandSpec(
            id = "stop_recording",
            phrases = listOf("stop recording", "stop the recording", "finish recording"),
        ),
        VoiceCommandSpec(
            id = "discard_recording",
            phrases = listOf("discard recording", "delete this recording", "start over"),
        ),
    )

    /** Human-readable label for the chip shown when a command is heard. */
    fun labelFor(id: String): String = specs.firstOrNull { it.id == id }
        ?.phrases?.firstOrNull()
        ?: id

    fun actionFor(id: String): VoiceCommandAction? = actions[id]
}
