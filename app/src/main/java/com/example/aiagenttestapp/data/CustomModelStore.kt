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

        val parsed = elements.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(ModelSpec.serializer(), element) }
                .onFailure { Log.w(TAG, "dropping a custom model this build cannot load", it) }
                .getOrNull()
        }
        val models = parsed.map(::unclampContext)
        if (models.size < elements.size || models != parsed) {
            // Rewrite now rather than on the next add, so dead entries stop being re-parsed (and
            // re-logged) on every launch, and a migrated window is not re-migrated.
            persistToDisk(models)
        }
        return models
    }

    /**
     * Undoes the device-derived context cap that used to be applied when a model was added.
     *
     * The cap ran once, at add time, and wrote its result into this file -- so an entry saved on a
     * memory-tight device carries a window that model never declared, and it stays wrong for the
     * life of the entry however much RAM the device later has. The declared value was not persisted
     * beside it, so there is nothing to restore it from; what can be recognised is the cap's own
     * floor, and an entry sitting on it was clamped rather than declared.
     *
     * Deliberately conservative: it only lifts entries at or below that floor. An entry the cap
     * trimmed to something *above* it -- 3072 from a declared 8192, say -- is indistinguishable
     * from one that genuinely declares 3072, and inventing a larger number for it would be a guess
     * that costs the user an over-sized KV allocation. Those need removing and re-adding, which
     * now stores the declared value.
     */
    private fun unclampContext(model: ModelSpec): ModelSpec =
        if (model.contextTokens in 1..ModelContextDefaults.LEGACY_CLAMP_FLOOR) {
            Log.i(
                TAG,
                "raising '${model.name}' from a clamped ${model.contextTokens}-token window to " +
                    "${ModelContextDefaults.DEFAULT_TOKENS}",
            )
            model.copy(contextTokens = ModelContextDefaults.DEFAULT_TOKENS)
        } else {
            model
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
