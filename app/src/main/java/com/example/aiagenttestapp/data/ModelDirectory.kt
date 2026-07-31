package com.example.aiagenttestapp.data

import com.example.aiagent.engine.core.ModelSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The catalogue: the curated built-ins plus whatever the user has added from HuggingFace.
 *
 * Everything downstream reads models from here rather than from the hardcoded list, so an added
 * model behaves exactly like a built-in one -- same fit check, same download, same chat. Previously
 * three loose members on the container; a type instead, so a caller that only needs to look a model
 * up can be handed exactly that.
 */
@Singleton
class ModelDirectory @Inject constructor(
    private val customModelStore: CustomModelStore,
) {

    val all: Flow<List<ModelSpec>> =
        customModelStore.models.map { custom -> ModelCatalog.builtIn + custom }

    fun find(id: String): ModelSpec? =
        ModelCatalog.byId(id) ?: customModelStore.models.value.firstOrNull { it.id == id }

    /** The catalogue right now, without collecting a Flow -- for app functions, which are one-shot. */
    fun snapshot(): List<ModelSpec> = ModelCatalog.builtIn + customModelStore.models.value
}
