package com.example.aiagent.llm

import com.example.aiagent.engine.core.ModelSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The catalogue: the curated built-ins plus whatever the user has added from HuggingFace.
 *
 * Everything downstream reads models from here rather than from the hardcoded list, so an added
 * model behaves exactly like a built-in one -- same fit check, same download, same chat. Previously
 * three loose members on the container; a type instead, so a caller that only needs to look a model
 * up can be handed exactly that.
 *
 * [builtIn] is passed rather than read from `ModelCatalog`, which is the one change that lets this
 * ship as a library. The curated list is editorial -- six models somebody chose, with hand-vetted
 * RAM tiers and licence notes -- and it is the app's opinion, not a fact about hosting a model. A
 * caller with its own models should not have to delete ours to be rid of them, and an empty list is
 * a perfectly good answer.
 */
class ModelDirectory(
    private val builtIn: List<ModelSpec>,
    private val customModelStore: CustomModelStore,
) {

    val all: Flow<List<ModelSpec>> =
        customModelStore.models.map { custom -> builtIn + custom }

    fun find(id: String): ModelSpec? =
        builtIn.firstOrNull { it.id == id }
            ?: customModelStore.models.value.firstOrNull { it.id == id }

    /** The catalogue right now, without collecting a Flow -- for app functions, which are one-shot. */
    fun snapshot(): List<ModelSpec> = builtIn + customModelStore.models.value
}
