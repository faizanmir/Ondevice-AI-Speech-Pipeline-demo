package com.example.aiagenttestapp.data.speakers

/**
 * Releases items in index order, however they arrive.
 *
 * The diarisation lanes finish their chunks out of order -- lane 1 can hand back chunk 1 while
 * lane 0 is still grinding through chunk 0 -- but the fold-and-name pass that consumes them must
 * run in chunk order, because it numbers unenrolled voices by first appearance and that is only
 * "across the recording" if the chunks are folded in the order they were spoken.
 *
 * The old answer was to `awaitAll()` every lane and sort, which is correct and expensive: no chunk
 * could be folded until the *last* chunk was diarised, so on the measured 20-minute run 9.8 seconds
 * of folding sat entirely after 45 seconds of diarisation. Buffering here instead lets the consumer
 * fold chunk 0 the moment it lands while later chunks are still being diarised -- the same folds,
 * in the same order, with most of their cost hidden behind work that was happening anyway.
 *
 * Not thread-safe on purpose: one consumer drains one channel and calls [offer] from one coroutine.
 */
class InOrderChunks<T> {

    private val pending = HashMap<Int, T>()
    private var next = 0

    /**
     * Accepts the item at [index] and returns every item that is now deliverable in order --
     * empty while earlier indices are still missing, several at once when [index] fills a gap.
     */
    fun offer(index: Int, item: T): List<T> {
        require(index >= next) { "chunk $index arrived twice; $next was already released" }
        require(pending.put(index, item) == null) { "chunk $index arrived twice" }

        val ready = mutableListOf<T>()
        var due = pending.remove(next)
        while (due != null) {
            ready += due
            next++
            due = pending.remove(next)
        }
        return ready
    }

    /** True when everything offered has been released -- the end-of-run sanity check. */
    val isDrained: Boolean get() = pending.isEmpty()
}
