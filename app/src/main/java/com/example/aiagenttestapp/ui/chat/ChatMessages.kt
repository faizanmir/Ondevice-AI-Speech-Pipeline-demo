package com.example.aiagenttestapp.ui.chat

/**
 * Edits to the list of bubbles on screen, as plain functions over an immutable list.
 *
 * Small, but worth naming: these were scattered through the view model as one-off `messages.map`
 * and `toMutableList().apply { add(at, ...) }` expressions, each re-deciding what to do when the
 * message it targets is no longer there -- a real case, since a user can delete a bubble while the
 * model is still streaming into it.
 *
 * Every function here answers that the same way: a target that has gone means the edit is dropped,
 * never an exception and never a resurrected message. And every one returns a new list, so nothing
 * can mutate what a composition is already reading.
 */
object ChatMessages {

    /** [message] with [transform] applied, or the list unchanged when it is gone. */
    fun List<ChatMessage>.replacing(
        id: Long,
        transform: (ChatMessage) -> ChatMessage,
    ): List<ChatMessage> = map { if (it.id == id) transform(it) else it }

    /** Without the message, if it is still there. */
    fun List<ChatMessage>.without(id: Long): List<ChatMessage> = filterNot { it.id == id }

    /**
     * [message] placed directly above [id], or appended when [id] is not in the list.
     *
     * Appending rather than dropping is deliberate: this puts a function chip above the reply being
     * streamed, and a chip belongs in the transcript even if the reply it was anchored to has been
     * deleted mid-turn. The order it reads in is worth more than the exact position.
     */
    fun List<ChatMessage>.insertingBefore(id: Long?, message: ChatMessage): List<ChatMessage> {
        val at = indexOfFirst { it.id == id }
        if (at < 0) return this + message
        return toMutableList().apply { add(at, message) }
    }
}

/**
 * Hands out bubble ids for one chat.
 *
 * Ids only have to be unique within a screen -- they identify a row in a list, not a stored
 * message -- so a counter is enough, and starting from zero on every chat keeps them readable in
 * a log.
 */
class ChatMessageIds {

    private var next = 0L

    fun next(): Long = next++

    /**
     * Moves the counter past [id].
     *
     * A resumed chat reuses the stored messages' database ids as bubble ids, which is free and
     * keeps the two in step -- but new bubbles must then start above the highest of them, or the
     * first reply collides with a restored message and the list quietly updates the wrong row.
     */
    fun startAfter(id: Long) {
        next = maxOf(next, id + 1)
    }
}
