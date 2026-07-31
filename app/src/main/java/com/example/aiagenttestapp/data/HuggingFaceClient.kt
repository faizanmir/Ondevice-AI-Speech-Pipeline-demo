package com.example.aiagenttestapp.data

import com.example.aiagent.engine.core.Accelerator
import com.example.aiagent.engine.core.DeviceMemoryProfile
import com.example.aiagent.engine.core.EngineId
import com.example.aiagent.engine.core.ModelFile
import com.example.aiagent.engine.core.ModelFormat
import com.example.aiagent.engine.core.ModelSpec
import com.example.aiagent.engine.core.ParamBudget
import com.example.aiagent.engine.core.Quantization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** A repository in HuggingFace search results. */
data class HfRepo(
    val id: String,
    val author: String,
    val downloads: Long,
    val likes: Int,
    /** True when the repo needs a signed-in HuggingFace account to download from. */
    val gated: Boolean,
)

/**
 * One downloadable model inside a repo. Usually one literal file; a model that ships as several
 * carries every one of them in [components], with [path] naming the entry point the engine loads
 * and [sizeBytes] the total.
 */
data class HfModelFile(
    val path: String,
    val sizeBytes: Long,
    val format: ModelFormat,
    val quantization: Quantization,
    val components: List<ModelFile> = emptyList(),
)

data class HfRepoDetail(
    val id: String,
    val gated: Boolean,
    val license: String,
    /** Null when HuggingFace does not publish a parameter count for the repo. */
    val paramsBillions: Double?,
    val contextTokens: Int?,
    val architecture: String?,
    val files: List<HfModelFile>,
)

/** One page of search results, plus the URL of the next page ([nextUrl]) or null when it is the last. */
data class HfSearchPage(
    val repos: List<HfRepo>,
    val nextUrl: String?,
)

/**
 * Reads the HuggingFace Hub API.
 *
 * Works signed out, and works better signed in. Gated repos -- every Gemma-branded and Llama repo,
 * among others -- are always *shown*, because a search for "gemma" that silently returns nothing is
 * far more confusing than one that says "needs sign-in". [HuggingFaceAuth] supplies a bearer token
 * when the user has one, which both unlocks those downloads and lifts the Hub's rate limits.
 */
class HuggingFaceClient(
    private val auth: HuggingFaceAuth,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Searches the Hub for repos containing models this app can actually run.
     *
     * The filter is by file format, because that is what decides which engine can load the result:
     * GGUF goes to llama.cpp, `.litertlm` to LiteRT-LM. There is no Hub-wide tag for LiteRT-LM, so
     * that search is scoped to the `litert-community` org, which is where Google publishes them.
     */
    suspend fun search(query: String, format: ModelFormat): List<HfRepo> = withContext(Dispatchers.IO) {
        parseRepoArray(get(searchFirstUrl(query, format, SEARCH_LIMIT)))
    }

    /**
     * The URL of the first page of results for a query. The paging source fetches this, then follows
     * the `Link: ...; rel="next"` header for each subsequent page (see [fetchRepoPage]).
     *
     * With a query, the Hub ranks by relevance. Forcing sort=downloads buries a specific smaller
     * model under the more-downloaded members of its family -- a search for "qwen3" would return the
     * popular 8B/14B GGUFs and never the 1.7B, even though it matches. Only browse mode (a blank
     * query) sorts by popularity, where "most downloaded" is what you want.
     */
    fun searchFirstUrl(query: String, format: ModelFormat, limit: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val order = if (query.isBlank()) "&sort=downloads&direction=-1" else ""
        return when (format) {
            ModelFormat.GGUF ->
                "$API/models?search=$encoded&filter=gguf&limit=$limit$order"

            ModelFormat.LITERTLM ->
                "$API/models?search=$encoded&author=litert-community&limit=$limit$order"

        }
    }

    /** Fetches one page of results at [url] and the URL of the next page from the `Link` header. */
    suspend fun fetchRepoPage(url: String): HfSearchPage = withContext(Dispatchers.IO) {
        val (body, next) = getWithNext(url)
        HfSearchPage(repos = parseRepoArray(body), nextUrl = next)
    }

    private fun parseRepoArray(body: String): List<HfRepo> =
        json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj.string("id") ?: obj.string("modelId") ?: return@mapNotNull null
            HfRepo(
                id = id,
                author = obj.string("author") ?: id.substringBefore('/'),
                downloads = obj.long("downloads") ?: 0L,
                likes = obj.int("likes") ?: 0,
                gated = obj.isGated(),
            )
        }

    /**
     * The search-result summary for a single repo by id, for the "paste a HuggingFace link" path --
     * a repo the user names directly, which search may never have surfaced (see the sort note above).
     */
    suspend fun repo(repoId: String): HfRepo = withContext(Dispatchers.IO) {
        val obj = json.parseToJsonElement(get("$API/models/$repoId")).jsonObject
        val id = obj.string("id") ?: obj.string("modelId") ?: repoId
        HfRepo(
            id = id,
            author = obj.string("author") ?: id.substringBefore('/'),
            downloads = obj.long("downloads") ?: 0L,
            likes = obj.int("likes") ?: 0,
            gated = obj.isGated(),
        )
    }

    /** Everything needed to turn a repo into runnable [ModelSpec]s: metadata plus the file list. */
    suspend fun repoDetail(repoId: String): HfRepoDetail = withContext(Dispatchers.IO) {
        val info = json.parseToJsonElement(get("$API/models/$repoId")).jsonObject
        val tree = json.parseToJsonElement(get("$API/models/$repoId/tree/main?recursive=true"))
            .jsonArray

        // GGUF repos carry parsed metadata under `gguf`, which is the good case: a real parameter
        // count and a real context length rather than something guessed from the repo name.
        val gguf = info["gguf"]?.jsonObject
        val paramCount = gguf?.long("total")
            ?: info["safetensors"]?.jsonObject?.long("total")

        HfRepoDetail(
            id = repoId,
            gated = info.isGated(),
            license = info.licenseFromTags(),
            paramsBillions = paramCount?.let { it / 1_000_000_000.0 }
                // No published count: fall back to the parameter size in the repo name, which
                // authors reliably encode ("Qwen2.5-1.5B-Instruct", "SmolLM2-360M").
                ?: parseParamsFromName(repoId),
            contextTokens = gguf?.int("context_length"),
            architecture = gguf?.string("architecture"),
            files = tree.toModelFiles(),
        )
    }

    private fun JsonArray.toModelFiles(): List<HfModelFile> = mapNotNull { element ->
        val obj = element.jsonObject
        val path = obj.string("path") ?: return@mapNotNull null

        val format = when {
            path.endsWith(".gguf", ignoreCase = true) -> ModelFormat.GGUF
            path.endsWith(".litertlm", ignoreCase = true) -> ModelFormat.LITERTLM
            else -> return@mapNotNull null
        }

        // Multi-part GGUF ("-00001-of-00003.gguf") needs all shards downloaded and stitched, which
        // this app does not do. Skipping them beats offering a download that cannot ever load.
        if (SPLIT_FILE.containsMatchIn(path)) return@mapNotNull null

        // LFS size is the real one; `size` on an LFS pointer is the size of the pointer file.
        val size = obj["lfs"]?.jsonObject?.long("size") ?: obj.long("size") ?: return@mapNotNull null

        HfModelFile(
            path = path,
            sizeBytes = size,
            format = format,
            quantization = Quantization.fromFileName(path) ?: Quantization.Q4,
        )
    }.sortedBy { it.sizeBytes }

    private fun get(url: String): String = getWithNext(url).first

    /** GET [url], returning the body and the next-page URL from the `Link` header (null if last). */
    private fun getWithNext(url: String): Pair<String, String?> {
        val request = Request.Builder()
            .url(url)
            .apply {
                // Signing in also makes gated repos visible to search, so results stop being
                // silently truncated for anyone who has an account.
                auth.authHeader()?.let { (name, value) -> header(name, value) }
            }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        401, 403 -> "This repository requires a HuggingFace sign-in"
                        404 -> "Not found on HuggingFace"
                        429 -> "HuggingFace is rate-limiting; wait a moment and try again"
                        else -> "HuggingFace returned HTTP ${response.code}"
                    },
                )
            }
            val body = response.body?.string() ?: throw IOException("Empty response from HuggingFace")
            val next = response.header("Link")?.let { NEXT_LINK.find(it)?.groupValues?.get(1) }
            return body to next
        }
    }

    private companion object {
        const val API = "https://huggingface.co/api"
        const val SEARCH_LIMIT = 30

        val SPLIT_FILE = Regex("""-\d{5}-of-\d{5}\.""")

        /** The `<url>; rel="next"` entry of a HuggingFace `Link` header -- the cursor to the next page. */
        val NEXT_LINK = Regex("""<([^>]+)>;\s*rel="next"""")

        /** `gated` is `false`, `"auto"`, or `"manual"` -- a boolean *or* a string, so test both. */
        fun JsonObject.isGated(): Boolean {
            val element = this["gated"]?.jsonPrimitive ?: return false
            element.booleanOrNull?.let { return it }
            return element.contentOrNull != null && element.contentOrNull != "false"
        }

        fun JsonObject.licenseFromTags(): String =
            this["tags"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.firstOrNull { it.startsWith("license:") }
                ?.removePrefix("license:")
                ?: "See model card"

        fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
        fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
        fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    }
}

/** A HuggingFace reference a user pasted: the repo, plus the specific file if the link named one. */
data class HfRef(val repoId: String, val filePath: String?)

/**
 * Parses a pasted HuggingFace reference into a repo id, and the file path when the link points at a
 * file. Accepts a full URL (`.../owner/repo`, `.../owner/repo/resolve/main/model.gguf`, `/blob/`,
 * `/tree/`, with or without scheme, `www.` or `hf.co`) or a bare `owner/repo` id. Returns null for
 * anything that is not a two-segment repo reference -- an ordinary search query, in other words, so
 * the Hub screen can tell a link to open from text to search for.
 */
internal fun parseHuggingFaceRef(input: String): HfRef? {
    var s = input.trim()
    if (s.isEmpty()) return null
    s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
    s = s.removePrefix("huggingface.co/").removePrefix("hf.co/").trim('/')

    val segments = s.split('/').filter { it.isNotEmpty() }
    if (segments.size < 2 || segments[0].isBlank() || segments[1].isBlank()) return null

    val repoId = "${segments[0]}/${segments[1]}"
    // .../resolve/<ref>/<path...> or .../blob/<ref>/<path...> names a specific file.
    val filePath = if (segments.size >= 5 && (segments[2] == "resolve" || segments[2] == "blob")) {
        segments.drop(4).joinToString("/").substringBefore('?')
    } else {
        null
    }
    return HfRef(repoId, filePath)
}

/** Pulls "1.5B" / "360M" out of a repo name, for repos with no published parameter count. */
private val PARAMS_IN_NAME = Regex("""(\d+(?:\.\d+)?)\s*([BbMm])(?![a-zA-Z])""")

/** Parses "1.5B"/"360M" out of a repo name, which is where authors reliably encode it. */
internal fun parseParamsFromName(name: String): Double? =
    PARAMS_IN_NAME.findAll(name)
        // Take the largest match: "Qwen2.5-1.5B" would otherwise match the "2.5" in the
        // family name before reaching the parameter count.
        .mapNotNull { result ->
            val value = result.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            when (result.groupValues[2].lowercase()) {
                "b" -> value
                "m" -> value / 1000.0
                else -> null
            }
        }
        .maxOrNull()

/**
 * Builds a runnable [ModelSpec] from a file the user picked out of a HuggingFace repo.
 *
 * `minDeviceMemoryGb = 0` is the important part: nobody has hand-vetted this model on real
 * hardware the way Google's allowlist tiers were, so it carries no curated tier and the fit check
 * falls through to the computed RAM formula, which is the only honest authority available here.
 */
fun HfRepoDetail.toModelSpec(file: HfModelFile, device: DeviceMemoryProfile): ModelSpec {
    val name = id.substringAfter('/')

    // A missing parameter count only affects the KV-cache term. Infer from file size rather than
    // give up: at this quantization, bytes/param is at least a usable approximation.
    val resolvedParams = paramsBillions
        ?: (file.sizeBytes / 1_000_000_000.0 / file.quantization.bytesPerWeight)

    return ModelSpec(
        id = "hf:$id:${file.path}",
        name = name,
        vendor = id.substringBefore('/'),
        paramsBillions = resolvedParams,
        quantization = file.quantization,
        format = file.format,
        downloadUrl = "https://huggingface.co/$id/resolve/main/${file.path}?download=true",
        // Namespaced by repo: two repos both shipping "model.gguf" must not collide on disk, and
        // engines that cache compiled graphs key on filename alone. A multi-file model gets a
        // repo-named *directory* instead, with the entry-point file inside.
        fileName = if (file.components.isEmpty()) {
            "${id.replace('/', '_')}_${file.path.substringAfterLast('/')}"
        } else {
            "${id.replace('/', '_')}/${file.path}"
        },
        sizeBytes = file.sizeBytes,
        // Context window sized to THIS device, not a flat cap. Honour the length the GGUF
        // advertises (its real training context) when the KV cache fits the device's RAM budget,
        // and trim it to what fits when it does not -- so a 128K model runs at 128K on a phone that
        // can hold the cache and at a smaller window on one that cannot, instead of everything
        // being pinned to 4096. See [deviceContext].
        contextTokens = deviceContext(
            advertised = contextTokens ?: DEFAULT_CONTEXT,
            file = file,
            paramsBillions = resolvedParams,
            device = device,
        ),
        minDeviceMemoryGb = 0,
        accelerators = when (file.format) {
            ModelFormat.GGUF -> setOf(Accelerator.CPU)
            ModelFormat.LITERTLM -> setOf(Accelerator.GPU, Accelerator.CPU)
        },
        license = license,
        description = buildString {
            append("Added from HuggingFace")
            architecture?.let { append(" · $it") }
            append(" · ${file.path.substringAfterLast('/')}")
        },
        isCustom = true,
        repoId = id,
        requiresAuth = gated,
        files = file.components,
    )
}

/**
 * The context window to give a HuggingFace model on THIS device: the length it [advertised], capped
 * by the largest KV cache the device's RAM budget can actually hold for a model this size.
 *
 * The cap is computed against CPU -- the most pessimistic accelerator for every engine here, since
 * CPU keeps the weights fully resident and so leaves the least room for KV -- so we never size a
 * cache the device cannot afford. The result is rounded down to a whole [CONTEXT_GRANULARITY] block
 * (the estimate runs high, so under-fill) and floored at [DeviceContextWindow.MIN_CONTEXT] so a tight device still gets
 * a usable window rather than a few hundred tokens; a model that genuinely will not fit is caught
 * separately by ModelFitEvaluator, which reports it as EXCEEDS_MEMORY.
 */
private fun deviceContext(
    advertised: Int,
    file: HfModelFile,
    paramsBillions: Double,
    device: DeviceMemoryProfile,
): Int {
    val engine = when (file.format) {
        ModelFormat.GGUF -> EngineId.LLAMA_CPP
        ModelFormat.LITERTLM -> EngineId.LITE_RT_LM
    }
    return DeviceContextWindow.cap(
        advertised = advertised,
        weightsBytes = file.sizeBytes,
        paramsBillions = paramsBillions,
        engine = engine,
        device = device,
    )
}

/** Fallback context when a GGUF advertises none, and the window a tight device is still given. */
private const val DEFAULT_CONTEXT = 4096
