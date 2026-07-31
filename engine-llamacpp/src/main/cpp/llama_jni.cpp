// JNI bridge to llama.cpp, written against the C API of the pinned tag in CMakeLists.txt (b9999).
//
// The Kotlin side sees one opaque session handle and a pull-based token loop:
//
//     handle = nativeCreateSession(...)
//     nativeIngestPrompt(handle, formattedPrompt)   // prefill
//     while ((piece = nativeNextToken(handle)) != null) { emit(piece) }   // decode, one step each
//
// Pulling one token per call (rather than pushing from C++ into a JNI callback) keeps
// backpressure, cancellation and coroutine cancellation in Kotlin, where they belong, and means
// no C++ code ever needs to attach a thread to the JVM.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

#define LOG_TAG "llama_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Error codes returned by nativeIngestPrompt. Mirrored in LlamaNative.kt.
constexpr jint kOk = 0;
constexpr jint kErrDecode = -1;
constexpr jint kErrContextFull = -2;
constexpr jint kErrNoSession = -3;

struct LlamaSession {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
    const llama_vocab *vocab = nullptr;

    int32_t n_ctx = 0;
    int32_t n_batch = 0;
    // Tokens currently in the KV cache. Tracked here so the UI can warn before context overflow.
    int32_t n_past = 0;

    // The exact tokens behind those n_past positions, in order. The engine re-renders the whole
    // transcript every turn, so ingest diffs the new prompt against this to find how much of the
    // cache is still valid -- decode only ever runs on the suffix that actually changed.
    std::vector<llama_token> cache_tokens;

    std::atomic<bool> cancelled{false};

    // llama.cpp emits pieces as raw bytes, and one multi-byte UTF-8 codepoint (an emoji, a CJK
    // glyph, an accented letter) routinely straddles two tokens. Handing a half-sequence to
    // NewStringUTF produces mojibake, so incomplete tail bytes are held here until the rest lands.
    std::string utf8_pending;
};

LlamaSession *as_session(jlong handle) {
    return reinterpret_cast<LlamaSession *>(handle);
}

/**
 * Consumes the leading well-formed UTF-8 from `pending`, decodes it to UTF-16, and returns it as a
 * Java string. Consumed bytes are erased from `pending`; a *truncated* multi-byte sequence at the
 * very end is left in place to be completed by the next token (this is the common case -- one emoji
 * or CJK glyph routinely straddles two tokens).
 *
 * Why decode to UTF-16 by hand rather than hand the bytes to NewStringUTF: NewStringUTF expects
 * *Modified* UTF-8, and under CheckJNI (on in debug builds) it aborts the whole process on anything
 * else. Two kinds of byte run trip that abort, both of which llama.cpp produces in normal use:
 *   - malformed sequences -- a byte-fallback token can leave a lead byte whose continuation never
 *     arrives (e.g. 0xE6 0x82 followed by a space), which the old length-only scan wrongly accepted;
 *   - ordinary emoji -- a valid *standard* 4-byte sequence, which Modified UTF-8 forbids (it wants a
 *     surrogate pair instead).
 * NewString takes UTF-16 directly and sidesteps both. Malformed bytes are replaced with U+FFFD and
 * the scan resyncs one byte on, so bad input degrades to a replacement glyph instead of a crash.
 */
jstring drain_utf16(JNIEnv *env, std::string &pending) {
    std::vector<jchar> utf16;
    utf16.reserve(pending.size());

    // Smallest code point each length may legally encode -- used to reject overlong forms.
    static const uint32_t kMin[5] = {0, 0, 0x80, 0x800, 0x10000};

    size_t i = 0;
    const size_t n = pending.size();
    while (i < n) {
        const auto lead = static_cast<unsigned char>(pending[i]);
        uint32_t cp;
        size_t len;
        if (lead < 0x80) {
            cp = lead; len = 1;
        } else if ((lead & 0xE0) == 0xC0) {
            cp = lead & 0x1Fu; len = 2;
        } else if ((lead & 0xF0) == 0xE0) {
            cp = lead & 0x0Fu; len = 3;
        } else if ((lead & 0xF8) == 0xF0) {
            cp = lead & 0x07u; len = 4;
        } else {
            // Stray continuation byte or invalid lead (0xF8..0xFF): replace and resync.
            utf16.push_back(0xFFFD);
            i += 1;
            continue;
        }

        if (i + len > n) break;  // truncated tail -- wait for the next token to complete it

        bool valid = true;
        for (size_t k = 1; k < len; ++k) {
            const auto cont = static_cast<unsigned char>(pending[i + k]);
            if ((cont & 0xC0) != 0x80) { valid = false; break; }  // not a continuation byte
            cp = (cp << 6) | (cont & 0x3Fu);
        }
        // Reject malformed sequences, overlong encodings, surrogates and out-of-range code points --
        // all of which would be invalid to forward to the JVM.
        if (!valid || cp < kMin[len] || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            utf16.push_back(0xFFFD);
            i += 1;
            continue;
        }

        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
        i += len;
    }

    pending.erase(0, i);
    // Nothing complete yet (only a truncated tail, or an empty piece): hand back the empty string,
    // which the Kotlin loop reads as "keep going". Avoids NewString on a possibly-null data().
    if (utf16.empty()) return env->NewStringUTF("");
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

std::string token_to_piece(const llama_vocab *vocab, llama_token token) {
    char buf[128];
    int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, /*special=*/false);
    if (n >= 0) {
        return {buf, static_cast<size_t>(n)};
    }
    // Negative return = buffer too small, and |n| is the size needed.
    std::vector<char> large(static_cast<size_t>(-n));
    n = llama_token_to_piece(vocab, token, large.data(), static_cast<int32_t>(large.size()), 0,
                             /*special=*/false);
    if (n < 0) return {};
    return {large.data(), static_cast<size_t>(n)};
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text,
                                  bool add_special, bool parse_special) {
    // Probe for the required size: with n_tokens_max = 0 the call returns -(tokens needed).
    const int32_t needed = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                           nullptr, 0, add_special, parse_special);
    if (needed <= 0) return {};

    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int32_t written = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                           tokens.data(), needed, add_special, parse_special);
    if (written < 0) return {};
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_aiagent_engine_llamacpp_LlamaNative_nativeBackendInit(JNIEnv *, jobject) {
    llama_backend_init();
    // ggml is chatty at info level; keep logcat usable.
    llama_log_set([](ggml_log_level level, const char *text, void *) {
        if (level == GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
    }, nullptr);
}

JNIEXPORT jlong JNICALL
Java_com_example_aiagent_engine_llamacpp_LlamaNative_nativeCreateSession(
        JNIEnv *env, jobject, jstring model_path, jint n_ctx, jint n_threads, jint n_gpu_layers,
        jfloat temperature, jint top_k, jfloat top_p, jint seed) {

    const std::string path = jstring_to_std(env, model_path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = n_gpu_layers;
    // mmap lets the kernel page weights in on demand and, crucially, evict them again under
    // pressure -- the difference between a slow model and a killed process on a mid-range phone.
    mparams.use_mmap = true;
    mparams.use_mlock = false;

    // Force a genuinely CPU-only context when no GPU offload was requested. With the Vulkan backend
    // compiled in, llama.cpp's scheduler otherwise offloads the *compute graph* to the GPU even
    // though n_gpu_layers = 0 keeps every weight on the CPU (the reserve logs a Vulkan0 compute
    // buffer and hundreds of graph splits). On devices whose Vulkan compute is unreliable -- several
    // Adreno drivers among them -- that turns correct CPU weights into uniform token-salad garbage.
    // An empty, NULL-terminated device list keeps the GPU out of the scheduler entirely, so CPU
    // really means CPU. A GPU request (n_gpu_layers != 0) leaves devices = NULL to auto-select.
    ggml_backend_dev_t cpu_only_devices[] = {nullptr};
    if (n_gpu_layers == 0) {
        mparams.devices = cpu_only_devices;
    }

    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("failed to load model: %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = 512;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.no_perf = true;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler *sampler = llama_sampler_chain_init(sparams);

    // Order matters: penalties and truncation first, then temperature, then exactly one selector
    // at the tail. llama_sampler_sample() runs the chain and accepts the winning token itself.
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
            /*penalty_last_n=*/64, /*penalty_repeat=*/1.1f,
            /*penalty_freq=*/0.0f, /*penalty_present=*/0.0f));

    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, /*min_keep=*/1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    auto *session = new LlamaSession();
    session->model = model;
    session->ctx = ctx;
    session->sampler = sampler;
    session->vocab = llama_model_get_vocab(model);
    session->n_ctx = static_cast<int32_t>(llama_n_ctx(ctx));
    session->n_batch = static_cast<int32_t>(cparams.n_batch);

    LOGI("session ready: n_ctx=%d threads=%d", session->n_ctx, n_threads);
    return reinterpret_cast<jlong>(session);
}

/**
 * Renders a conversation through the model's own chat template (the one baked into the GGUF).
 * Using the model's template rather than a hand-rolled format is what keeps Qwen, Gemma and Llama
 * all producing coherent output from the same code path -- each expects a different control-token
 * layout, and getting it wrong degrades quality in ways that look like a bad model rather than a
 * bug.
 */
JNIEXPORT jstring JNICALL
Java_com_example_aiagent_engine_llamacpp_LlamaNative_nativeFormatChat(
        JNIEnv *env, jobject, jlong handle, jobjectArray roles, jobjectArray contents,
        jboolean add_assistant) {

    LlamaSession *session = as_session(handle);
    if (session == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(roles);

    // llama_chat_message holds borrowed pointers, so the backing strings must outlive the call.
    std::vector<std::string> role_store;
    std::vector<std::string> content_store;
    role_store.reserve(n);
    content_store.reserve(n);

    for (jsize i = 0; i < n; ++i) {
        auto role = reinterpret_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = reinterpret_cast<jstring>(env->GetObjectArrayElement(contents, i));
        role_store.push_back(jstring_to_std(env, role));
        content_store.push_back(jstring_to_std(env, content));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }

    std::vector<llama_chat_message> messages;
    messages.reserve(n);
    size_t total_chars = 0;
    for (jsize i = 0; i < n; ++i) {
        messages.push_back({role_store[i].c_str(), content_store[i].c_str()});
        total_chars += role_store[i].size() + content_store[i].size();
    }

    // Null name = the template the model shipped with.
    const char *tmpl = llama_model_chat_template(session->model, /*name=*/nullptr);
    if (tmpl == nullptr) {
        LOGE("model has no chat template");
        return nullptr;
    }

    // The header recommends 2x the total message length as a starting buffer.
    std::vector<char> buf(total_chars * 2 + 512);
    int32_t written = llama_chat_apply_template(tmpl, messages.data(), messages.size(),
                                                add_assistant == JNI_TRUE, buf.data(),
                                                static_cast<int32_t>(buf.size()));
    if (written > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<size_t>(written));
        written = llama_chat_apply_template(tmpl, messages.data(), messages.size(),
                                            add_assistant == JNI_TRUE, buf.data(),
                                            static_cast<int32_t>(buf.size()));
    }
    if (written < 0) {
        LOGE("chat template failed");
        return nullptr;
    }

    return env->NewStringUTF(std::string(buf.data(), static_cast<size_t>(written)).c_str());
}

/**
 * Prefill. Tokenizes the already-templated prompt and runs it through the model so that the next
 * call to nativeNextToken has logits to sample from. Returns the prompt's token count, or a
 * negative error code.
 */
JNIEXPORT jint JNICALL
Java_com_example_aiagent_engine_llamacpp_LlamaNative_nativeIngestPrompt(
        JNIEnv *env, jobject, jlong handle, jstring prompt) {

    LlamaSession *session = as_session(handle);
    if (session == nullptr) return kErrNoSession;

    session->cancelled.store(false);
    session->utf8_pending.clear();

    const std::string text = jstring_to_std(env, prompt);

    // add_special = true so the tokenizer prepends the model's *configured* BOS, honouring the
    // GGUF's add_bos_token metadata: exactly one <|begin_of_text|> for Llama, one <bos> for Gemma,
    // and nothing for Qwen (which sets add_bos_token = false). The chat templates these models ship
    // do NOT embed a literal BOS -- they start straight at the first role header -- so this is the
    // only thing that adds it. Getting it wrong was subtle: Qwen needs no BOS, so the old
    // add_special = false looked fine on Qwen while silently feeding Llama a prompt with no BOS at
    // position 0, a state it was never trained on -- which came out as unbounded garbage.
    // parse_special = true keeps the header/<|eot_id|> control tokens as single tokens.
    std::vector<llama_token> tokens = tokenize(session->vocab, text,
                                               /*add_special=*/true, /*parse_special=*/true);
    if (tokens.empty()) return kErrDecode;

    // After this ingest the KV cache holds exactly this prompt, so the prompt alone must leave at
    // least one slot free to generate into. Checked before any state is touched.
    if (static_cast<int32_t>(tokens.size()) >= session->n_ctx) {
        return kErrContextFull;
    }

    llama_memory_t mem = llama_get_memory(session->ctx);

    // The engine re-renders the whole transcript every turn, so most of this prompt is already in
    // the KV cache. Diff against what was actually decoded last time and keep the longest common
    // prefix -- without this, every turn *appends* a full copy of the conversation to the cache,
    // which both duplicates the context the model sees and burns through n_ctx in a turn or two
    // (the stream then dies after a couple of tokens on the n_past >= n_ctx guard).
    size_t common = 0;
    const size_t reusable = std::min(session->cache_tokens.size(), tokens.size());
    while (common < reusable && session->cache_tokens[common] == tokens[common]) ++common;

    // Always leave at least the final prompt token to decode, so this call ends with fresh logits
    // for nativeNextToken to sample from.
    if (common == tokens.size()) --common;

    // Sliding-window models (Gemma et al.) prune positions that fall out of the window, so a
    // truncated-back cache may be missing positions the next tokens still attend to. Same guard as
    // llama-server: if the window behind the reuse point is no longer intact, re-prefill in full.
    if (common > 0) {
        const int32_t n_swa = llama_model_n_swa(session->model);
        if (n_swa > 0) {
            const llama_pos pos_min = llama_memory_seq_pos_min(mem, 0);
            const llama_pos thold = std::max<llama_pos>(0, static_cast<llama_pos>(common) - n_swa);
            if (pos_min < 0 || pos_min > thold) common = 0;
        }
    }

    // Drop everything past the reused prefix. Some memory types cannot remove a partial range
    // (recurrent models return false); for those, clear outright and re-prefill from scratch --
    // slower, but always correct.
    if (common == 0 || !llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(common), -1)) {
        llama_memory_clear(mem, /*data=*/true);
        common = 0;
    }
    session->cache_tokens.resize(common);
    session->n_past = static_cast<int32_t>(common);

    // Feed in n_batch-sized chunks. llama_batch_get_one leaves the logits flag unset, so llama.cpp
    // only materialises logits for the final token of the final chunk -- exactly what sampling
    // needs, and it avoids allocating a logits row per prompt token.
    for (size_t offset = common; offset < tokens.size(); offset += session->n_batch) {
        const auto chunk = std::min(static_cast<size_t>(session->n_batch), tokens.size() - offset);
        llama_batch batch = llama_batch_get_one(tokens.data() + offset,
                                                static_cast<int32_t>(chunk));
        if (llama_decode(session->ctx, batch) != 0) {
            LOGE("llama_decode failed during prefill");
            return kErrDecode;
        }
        session->n_past += static_cast<int32_t>(chunk);
        session->cache_tokens.insert(session->cache_tokens.end(),
                                     tokens.begin() + static_cast<ptrdiff_t>(offset),
                                     tokens.begin() + static_cast<ptrdiff_t>(offset + chunk));
    }

    return static_cast<jint>(tokens.size());
}

/**
 * One decode step. Returns the next piece of text, or null when generation is finished -- because
 * the model emitted an end-of-generation token, the context filled up, the caller cancelled, or
 * decoding failed.
 *
 * An empty (non-null) string means "this token was half a UTF-8 codepoint, nothing to show yet,
 * keep going" -- it is not a terminator.
 */
JNIEXPORT jstring JNICALL
Java_com_example_aiagent_engine_llamacpp_LlamaNative_nativeNextToken(
        JNIEnv *env, jobject, jlong handle) {

    LlamaSession *session = as_session(handle);
    if (session == nullptr) return nullptr;
    if (session->cancelled.load()) return nullptr;
    if (session->n_past >= session->n_ctx) return nullptr;

    const llama_token token = llama_sampler_sample(session->sampler, session->ctx, -1);

    if (llama_vocab_is_eog(session->vocab, token)) {
        return nullptr;
    }

    session->utf8_pending += token_to_piece(session->vocab, token);
    jstring emit = drain_utf16(env, session->utf8_pending);

    // Feed the sampled token back so the next call has fresh logits.
    llama_token next = token;
    llama_batch batch = llama_batch_get_one(&next, 1);
    if (llama_decode(session->ctx, batch) != 0) {
        LOGE("llama_decode failed during generation");
        return nullptr;
    }
    session->n_past += 1;
    // Mirror it into the token history so the next turn's ingest can reuse this reply as prefix.
    session->cache_tokens.push_back(token);

    return emit;
}

JNIEXPORT void JNICALL
Java_com_example_aiagent_engine_llamacpp_LlamaNative_nativeCancel(JNIEnv *, jobject, jlong handle) {
    LlamaSession *session = as_session(handle);
    if (session != nullptr) session->cancelled.store(true);
}

/**
 * Ends the turn but deliberately KEEPS the KV cache and cache_tokens.
 *
 * For a run of self-contained prompts that share a long fixed preamble, clearing the cache throws
 * away the one thing worth keeping. Leaving it lets the next nativeIngestPrompt diff against
 * cache_tokens, reuse the shared prefix, and llama_memory_seq_rm evicts whatever the new prompt does
 * not share -- so the previous prompt's text and its reply are dropped by that diff rather than by a
 * wholesale clear, and isolation is preserved either way.
 *
 * The sampler still has to be reset: the chain carries repetition penalties over a 64-token window,
 * and letting those leak across prompts would penalise the next reply for words the last one used --
 * badly wrong when every reply is JSON reusing the same keys.
 */
JNIEXPORT void JNICALL
Java_com_example_aiagent_engine_llamacpp_LlamaNative_nativeResetTurnKeepCache(JNIEnv *, jobject,
                                                                             jlong handle) {
    LlamaSession *session = as_session(handle);
    if (session == nullptr) return;

    llama_sampler_reset(session->sampler);
    session->utf8_pending.clear();
    session->cancelled.store(false);
    // n_past and cache_tokens are intentionally left intact -- they are the reusable prefix.
}

/** Drops conversation history but keeps the (expensively loaded) weights resident. */
JNIEXPORT void JNICALL
Java_com_example_aiagent_engine_llamacpp_LlamaNative_nativeResetContext(JNIEnv *, jobject,
                                                                       jlong handle) {
    LlamaSession *session = as_session(handle);
    if (session == nullptr) return;

    llama_memory_clear(llama_get_memory(session->ctx), /*data=*/true);
    llama_sampler_reset(session->sampler);
    session->n_past = 0;
    session->cache_tokens.clear();
    session->utf8_pending.clear();
    session->cancelled.store(false);
}

JNIEXPORT jint JNICALL
Java_com_example_aiagent_engine_llamacpp_LlamaNative_nativeContextUsed(JNIEnv *, jobject,
                                                                      jlong handle) {
    LlamaSession *session = as_session(handle);
    return session == nullptr ? 0 : session->n_past;
}

JNIEXPORT jint JNICALL
Java_com_example_aiagent_engine_llamacpp_LlamaNative_nativeContextSize(JNIEnv *, jobject,
                                                                      jlong handle) {
    LlamaSession *session = as_session(handle);
    return session == nullptr ? 0 : session->n_ctx;
}

JNIEXPORT void JNICALL
Java_com_example_aiagent_engine_llamacpp_LlamaNative_nativeFreeSession(JNIEnv *, jobject,
                                                                      jlong handle) {
    LlamaSession *session = as_session(handle);
    if (session == nullptr) return;

    // Reverse construction order. The sampler chain owns the samplers added to it, so freeing the
    // chain is enough -- freeing them individually would double-free.
    if (session->sampler != nullptr) llama_sampler_free(session->sampler);
    if (session->ctx != nullptr) llama_free(session->ctx);
    if (session->model != nullptr) llama_model_free(session->model);

    delete session;
}

JNIEXPORT jstring JNICALL
Java_com_example_aiagent_engine_llamacpp_LlamaNative_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

/**
 * Name of a GPU ggml can actually offload to, or null if there is none.
 *
 * Compiling the Vulkan backend in is not the same as having a GPU to use. The binary may have
 * Vulkan support while the device has no usable driver -- emulators typically do not, and some
 * phones ship a Vulkan loader with no working compute queue. Asking ggml which devices it actually
 * enumerated is the only honest answer, and it is what stops the UI offering a "GPU" option that
 * would silently fall back to the CPU and leave the user wondering why nothing got faster.
 *
 * Phone GPUs enumerate as IGPU (integrated), not GPU, so both count.
 */
JNIEXPORT jstring JNICALL
Java_com_example_aiagent_engine_llamacpp_LlamaNative_nativeGpuDeviceName(JNIEnv *env, jobject) {
#ifdef LLAMAJNI_VULKAN
    const size_t count = ggml_backend_dev_count();
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t device = ggml_backend_dev_get(i);
        if (device == nullptr) continue;

        const enum ggml_backend_dev_type type = ggml_backend_dev_type(device);
        if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) {
            continue;
        }

        const char *description = ggml_backend_dev_description(device);
        const char *name = ggml_backend_dev_name(device);
        const char *label = (description && *description) ? description : name;
        if (label == nullptr) continue;

        LOGI("GPU device available: %s", label);
        return env->NewStringUTF(label);
    }
    LOGI("no Vulkan GPU device found; CPU only");
#endif
    return nullptr;
}

}  // extern "C"
