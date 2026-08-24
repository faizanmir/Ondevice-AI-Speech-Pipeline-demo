package com.example.aiagenttestapp.prompts

/**
 * Assembles the system prompt a feature loads its model with.
 *
 * Every feature's prompt is the same shape -- what the model *is*, then whatever app-wide
 * directives are in force, then any sections only that feature has -- and the assembly was
 * duplicated at each of them. That duplication is the kind that rots quietly: a new global
 * directive gets added to chat, nobody remembers the note summariser, and the two behave
 * differently for no reason anyone wrote down.
 *
 * So the *rule* lives here and the *text* stays with the feature. Adding a directive that applies
 * everywhere means changing this one function; adding one that applies to a single feature means
 * passing another section, and no caller that does not care is touched.
 *
 * Sections are joined by a blank line, and nulls are dropped -- so a caller can pass an optional
 * section straight through without an `if` around it.
 */
object SystemPromptBuilder {

    /**
     * @param base what the model is, for this feature. Always first: it frames everything after it.
     * @param thinkingEnabled false appends [ReasoningPrompts.NO_THINK_DIRECTIVE]. A model with no
     *   thinking mode ignores it, so this is safe to apply without knowing which model is loaded.
     * @param sections feature-specific additions, in the order they should appear. Nulls are
     *   skipped, which is what lets a caller pass "the tool section, if there is one" inline.
     */
    fun build(
        base: String,
        thinkingEnabled: Boolean = true,
        vararg sections: String?,
    ): String = buildList {
        add(base)
        if (!thinkingEnabled) add(ReasoningPrompts.NO_THINK_DIRECTIVE)
        addAll(sections.filterNotNull())
    }.filter { it.isNotBlank() }.joinToString(separator = "\n\n")
}
