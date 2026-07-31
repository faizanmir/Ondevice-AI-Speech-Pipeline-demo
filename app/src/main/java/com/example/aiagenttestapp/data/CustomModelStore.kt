package com.example.aiagenttestapp.data

import android.content.Context
import android.util.Log
import com.example.aiagent.engine.core.ModelSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.io.File

/**
 * Models the user added from HuggingFace, persisted as JSON.
 *
 * [ModelSpec] is already `@Serializable`, so this is just a file. A database would buy nothing:
 * the list is small, read whole, and written whole.
 */
class CustomModelStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "custom_models.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(ModelSpec.serializer())

    private val _models = MutableStateFlow(load())
    val models: StateFlow<List<ModelSpec>> = _models.asStateFlow()

    /**
     * Reads the list one entry at a time, keeping whatever still parses.
     *
     * Decoding the array in one go would be shorter but throws away far too much: a single entry
     * the current build cannot represent fails the whole decode, and the user loses every model
     * they ever added. That is not hypothetical -- dropping an engine drops its [ModelFormat],
     * and any saved model in that format becomes an unknown enum value overnight. Those entries
     * are genuinely dead (nothing left can load them), but the ones beside them are not.
     */
    private fun load(): List<ModelSpec> {
        if (!file.exists()) return emptyList()

        val elements = try {
            json.parseToJsonElement(file.readText()).jsonArray
        } catch (e: Exception) {
            // A truncated write should not brick the catalogue. Losing the user's added-model list
            // is recoverable; refusing to start is not.
            Log.e(TAG, "custom model list is unreadable, discarding it", e)
            return emptyList()
        }

        val models = elements.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(ModelSpec.serializer(), element) }
                .onFailure { Log.w(TAG, "dropping a custom model this build cannot load", it) }
                .getOrNull()
        }
        if (models.size < elements.size) {
            // Rewrite now rather than on the next add, so the dead entries stop being re-parsed
            // (and re-logged) on every launch.
            persistToDisk(models)
        }
        return models
    }

    private fun persist(models: List<ModelSpec>) {
        _models.value = models
        persistToDisk(models)
    }

    private fun persistToDisk(models: List<ModelSpec>) {
        runCatching { file.writeText(json.encodeToString(serializer, models)) }
            .onFailure { Log.e(TAG, "could not save custom models", it) }
    }

    /** Adding a model already present is a no-op, so re-adding from search cannot duplicate it. */
    fun add(model: ModelSpec) {
        if (_models.value.any { it.id == model.id }) return
        // Stamp the add time so the catalogue can offer a "newest first" sort.
        persist(_models.value + model.copy(addedAtMillis = System.currentTimeMillis()))
    }

    fun remove(modelId: String) {
        persist(_models.value.filterNot { it.id == modelId })
    }

    fun contains(modelId: String): Boolean = _models.value.any { it.id == modelId }

    private companion object {
        const val TAG = "CustomModelStore"
    }
}
