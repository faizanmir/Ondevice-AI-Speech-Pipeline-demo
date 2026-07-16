package com.example.aiagenttestapp.data

import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.ModelFile
import com.example.aiagent.engine.core.ModelFormat
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagent.engine.core.Quantization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One model in Alibaba's MNN model market. */
data class MnnMarketModel(
    /** The market's model name, e.g. "Qwen3-0.6B-MNN". */
    val name: String,
    val vendor: String,
    /** Market tags: "Think", "Tool", ... Vision/Audio entries are filtered out upstream. */
    val tags: List<String>,
    /** Total download size as the market advertises it -- display only; the authoritative
     *  per-file sizes come from ModelScope when the model is opened. */
    val sizeBytes: Long,
    /** Repo path on ModelScope, e.g. "MNN/Qwen3-0.6B-MNN". */
    val modelScopePath: String,
) {
    /** Catalogue id for this market entry -- stable, and distinct from HF custom adds ("hf:..."). */
    val specId: String get() = "mnn:$modelScopePath"
}

/**
 * Reads Alibaba's MNN model market -- the same catalogue MNN's own MnnLlmChat app shows.
 *
 * Two Alibaba services are involved, and it is worth keeping them straight: the *market* JSON
 * (meta.alicdn.com) is the curated list of models with names, sizes and tags; the *files* behind
 * each model live on ModelScope (modelscope.cn, Alibaba's model hub), which is also where the
 * downloads come from. Neither needs an account.
 */
class MnnMarketClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The market's LLM catalogue, in the market's own order (Alibaba puts its recommendations
     * first). Entries without a ModelScope source are dropped, as are Vision/Audio models --
     * this build compiles MNN without its image and audio processors, so a multimodal model
     * would download whole and then fail to load.
     */
    suspend fun fetchMarket(): List<MnnMarketModel> = withContext(Dispatchers.IO) {
        val body = get(MARKET_URL)
        val models = json.parseToJsonElement(body).jsonObject["models"]?.jsonArray
            ?: throw IOException("The MNN market returned no model list")

        models.mapNotNull { element ->
            val obj = element.jsonObject
            val name = obj["modelName"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val scopePath = obj["sources"]?.jsonObject?.get("ModelScope")
                ?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

            val tags = obj["tags"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
            if (tags.any { it.equals("Vision", true) || it.equals("Audio", true) }) {
                return@mapNotNull null
            }

            MnnMarketModel(
                name = name,
                vendor = obj["vendor"]?.jsonPrimitive?.contentOrNull ?: "Unknown",
                tags = tags,
                sizeBytes = obj["file_size"]?.jsonPrimitive?.longOrNull
                    ?: obj["size_gb"]?.jsonPrimitive?.doubleOrNull
                        ?.let { (it * ModelSpec.BYTES_PER_GB).toLong() }
                    ?: 0L,
                modelScopePath = scopePath,
            )
        }
    }

    /**
     * Resolves a market entry into a runnable [ModelSpec] by listing its files on ModelScope.
     * Like an HF custom add, `minDeviceMemoryGb = 0`: nothing here is hand-vetted, so the fit
     * check falls through to the computed RAM formula.
     */
    suspend fun modelSpec(model: MnnMarketModel): ModelSpec = withContext(Dispatchers.IO) {
        val body = get("$MODELSCOPE_API/models/${model.modelScopePath}/repo/files?Recursive=true")
        val files = json.parseToJsonElement(body).jsonObject["Data"]?.jsonObject
            ?.get("Files")?.jsonArray
            ?.mapNotNull { element ->
                val obj = element.jsonObject
                if (obj["Type"]?.jsonPrimitive?.contentOrNull != "blob") return@mapNotNull null
                val path = obj["Path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                // Repo housekeeping, not model data: git config, model cards, and ModelScope's
                // own metadata file.
                if (path.startsWith(".") || path.substringAfterLast('/').startsWith(".")) {
                    return@mapNotNull null
                }
                if (path.endsWith(".md", true) || path == "configuration.json") {
                    return@mapNotNull null
                }

                val size = obj["Size"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                path to size
            }
            .orEmpty()

        val entryPoint = files.firstOrNull { (path, _) -> path == "config.json" }
            ?: throw IOException("${model.name} has no config.json on ModelScope, so MNN cannot load it")

        // Namespaced by repo on disk, like every other model: each MNN repo ships a file
        // literally called config.json.
        val dir = model.modelScopePath.replace('/', '_')

        ModelSpec(
            id = model.specId,
            name = model.name.removeSuffix("-MNN"),
            vendor = model.vendor,
            paramsBillions = parseParamsFromName(model.name)
                ?: (files.sumOf { it.second } / 1_000_000_000.0 / Quantization.Q4.bytesPerWeight),
            quantization = Quantization.fromFileName(model.name) ?: Quantization.Q4,
            format = ModelFormat.MNN,
            downloadUrl = downloadUrl(model.modelScopePath, entryPoint.first),
            fileName = "$dir/config.json",
            sizeBytes = files.sumOf { it.second },
            contextTokens = DEFAULT_CONTEXT,
            minDeviceMemoryGb = 0,
            accelerators = setOf(Accelerator.CPU),
            license = "See model card on ModelScope",
            description = "From the MNN model market · ${model.vendor}" +
                (model.tags.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · ") ?: ""),
            isCustom = true,
            files = files.map { (path, size) ->
                ModelFile(
                    url = downloadUrl(model.modelScopePath, path),
                    relativePath = "$dir/$path",
                    sizeBytes = size,
                )
            },
        )
    }

    private fun downloadUrl(repoPath: String, filePath: String): String =
        "https://modelscope.cn/models/$repoPath/resolve/master/$filePath"

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        404 -> "Not found on ModelScope"
                        429 -> "ModelScope is rate-limiting; wait a moment and try again"
                        else -> "The MNN market returned HTTP ${response.code}"
                    },
                )
            }
            return response.body?.string() ?: throw IOException("Empty response from the MNN market")
        }
    }

    private companion object {
        /** The catalogue MNN's own Android app loads (see MnnLlmChat's ModelRepository). */
        const val MARKET_URL = "https://meta.alicdn.com/data/mnn/apis/model_market.json"
        const val MODELSCOPE_API = "https://modelscope.cn/api/v1"
        const val DEFAULT_CONTEXT = 4096
    }
}
