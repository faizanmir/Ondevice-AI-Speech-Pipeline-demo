package com.example.aiagenttestapp.data

/**
 * The context window a model gets: the one it declares, with nothing sized against the device on
 * top of it.
 *
 * This replaces a `DeviceContextWindow.cap` that clamped every model's window to the largest KV
 * cache the phone's RAM budget was estimated to hold. The estimate was a guess built on a
 * bytes-per-token-per-billion constant, it ran on the most pessimistic accelerator regardless of
 * which one would actually load, and on a tight device it bottomed out at a 1024-token floor. That
 * is small enough to be useless: an audit's own preamble is ~2,000 tokens, so a capped model could
 * not hold the prompt it was about to be given, and audits were refused on models that run fine.
 *
 * A model that genuinely will not fit is still caught, by `ModelFitEvaluator` -- which reports it as
 * EXCEEDS_MEMORY before anything is loaded, rather than quietly handing back a window too small to
 * work in. That is the honest place for the check: a refusal the user can see, not a silent
 * shrinking they cannot.
 */
object ModelContextDefaults {

    /**
     * The window a model gets when it declares none.
     *
     * Reached by every `.litertlm` bundle: the advertised length is read out of GGUF metadata, and
     * a LiteRT-LM bundle carries no equivalent field.
     */
    const val DEFAULT_TOKENS = 4096

    /**
     * The floor the removed cap clamped to.
     *
     * A stored custom model at or below this did not *declare* that window -- it was clamped to it
     * on the device that added it, and the declared value was never persisted alongside. Those
     * entries are raised to [DEFAULT_TOKENS] when read; see [CustomModelStore].
     */
    const val LEGACY_CLAMP_FLOOR = 1024
}
