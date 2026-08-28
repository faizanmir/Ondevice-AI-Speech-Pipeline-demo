package com.example.aiagenttestapp.stt

/**
 * Keeps released model instances warm so the next run can skip their load.
 *
 * Loading a sherpa model costs seconds (a diarizer lane set ~2s, the second recogniser 1.6-4.3s
 * measured), and a diarisation run builds several of them only to release them minutes later --
 * so back-to-back runs, which is how this screen is actually used, paid the same loads every time.
 * Stashing the idle instance instead makes the reload free, at the price of native memory held
 * between runs; memory pressure (AIAgentApplication.onTrimMemory) is what takes it back.
 *
 * **The key is the whole configuration.** An instance is only reusable if everything baked into it
 * at construction matches -- model files, thread count, provider, expected speakers -- so callers
 * key on all of it. A stashed instance whose key does not match the one being acquired is *evicted*,
 * not kept: its configuration has been replaced, so it would never be asked for again and would
 * just sit on its memory. This makes the pool self-cleaning across Settings changes without
 * subscribing to any of them.
 *
 * Generic and given [onEvict] rather than owning a model type, so the eviction policy is pinned by
 * a plain JVM test instead of needing native models loaded.
 */
class WarmPool<T>(
    /** Most instances kept at once -- sized to the largest lane fleet a run builds. */
    private val capacity: Int,
    /** Frees an instance leaving the pool. Called with instances that are idle by contract. */
    private val onEvict: (T) -> Unit,
) {

    private val warm = ArrayDeque<Pair<Any, T>>()

    /**
     * Takes a warm instance for [key], or null when there is none and the caller must load fresh.
     * Instances stashed under any *other* key are evicted here -- see the class note.
     */
    @Synchronized
    fun acquire(key: Any): T? {
        warm.filter { it.first != key }.forEach { (_, stale) -> onEvict(stale) }
        warm.retainAll { it.first == key }
        if (warm.isEmpty()) return null
        return warm.removeFirst().second
    }

    /**
     * Returns an idle instance to the pool. Beyond [capacity] the newcomer is evicted instead --
     * the pool is for repeats of one configuration, not an unbounded cache.
     */
    @Synchronized
    fun stash(key: Any, item: T) {
        if (warm.size >= capacity) {
            onEvict(item)
            return
        }
        warm.addLast(key to item)
    }

    /** Evicts everything. The memory-pressure path. */
    @Synchronized
    fun clear() {
        warm.forEach { onEvict(it.second) }
        warm.clear()
    }

    /** How many instances are warm right now; for logs and tests. */
    @Synchronized
    fun size(): Int = warm.size
}
