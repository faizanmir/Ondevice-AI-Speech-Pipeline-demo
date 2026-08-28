package com.example.aiagenttestapp.data

/**
 * Which ONNX Runtime execution provider the sherpa-onnx sessions run on.
 *
 * A user-visible setting rather than a constant because the answer is not knowable from here. All
 * three of these are compiled into the prebuilt `sherpa-onnx-static-link-onnxruntime` AAR, but which
 * one is fastest depends on the chipset, the model, and how much of the graph the provider can
 * actually take -- and the only honest way to find out is to run the same recording through each on
 * a real device.
 *
 * [XNNPACK] is the default. Every model this drives is int8, and XNNPACK is the one provider here
 * with ARM int8 kernels built for exactly that -- measured on device it is the faster of the two CPU
 * providers, which is why it now leads rather than sits behind an opt-in nobody would find. It is
 * still a CPU provider, statically linked into the same AAR, and ONNX Runtime falls back to plain
 * CPU per-operator if it ever cannot take a graph -- so defaulting to it cannot leave a device unable
 * to run, which is what let it move ahead of [CPU]. [CPU] stays as the conservative baseline the user
 * can pick to measure against.
 *
 * Deliberately not offering `qnn`. It is in the AAR, but it refuses ONNX files outright -- it wants
 * models pre-compiled into QNN context binaries with Qualcomm's SDK -- so a toggle for it would be
 * an option that could never work with any model this app downloads.
 */
enum class OnnxProvider(
    /** What sherpa-onnx expects in its `provider` field. */
    val slug: String,
    val label: String,
    val hint: String,
) {
    CPU(
        slug = "cpu",
        label = "CPU",
        hint = "Works everywhere. The baseline to measure the other two against.",
    ),

    /**
     * ARM-optimised CPU kernels. The most likely of the two to help: the speech models are int8
     * precisely because that is what makes them fast on a CPU, and XNNPACK has strong int8 kernels
     * for exactly that case.
     */
    XNNPACK(
        slug = "xnnpack",
        label = "XNNPACK",
        hint = "Optimised CPU kernels for ARM. Most likely of the two to be faster.",
    ),

    /**
     * Android's accelerator API. Present, but two things are working against it: it is deprecated as
     * of Android 15, and the speech models here are dynamically int8-quantised, which its ONNX
     * Runtime provider does not cover -- so the unsupported operators fall back to the CPU anyway and
     * every hand-off between the two costs a copy. A fragmented graph is routinely *slower* than
     * plain CPU, which is the reason this is offered as something to measure rather than a
     * recommendation.
     */
    NNAPI(
        slug = "nnapi",
        label = "NNAPI",
        hint = "Sends what it can to the GPU or NPU. Often slower on these models, and deprecated " +
            "by Android — worth measuring, not assuming.",
    ),

    ;

    companion object {

        val DEFAULT = XNNPACK

        /**
         * Resolves a stored slug, falling back to [DEFAULT].
         *
         * Unrecognised means a setting written by a newer build, or one whose option has since been
         * withdrawn -- and it is also the no-setting-yet case, since a fresh install has nothing
         * stored. Either way the answer is [DEFAULT], which is safe to hand any device for the reason
         * the class note gives: XNNPACK is a statically linked CPU provider that ONNX Runtime can
         * fall back out of per-operator, so it can never be the thing that stops a model loading.
         */
        fun fromSlug(slug: String?): OnnxProvider =
            entries.firstOrNull { it.slug == slug } ?: DEFAULT
    }
}
