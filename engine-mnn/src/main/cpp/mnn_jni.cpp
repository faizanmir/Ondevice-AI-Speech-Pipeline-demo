// JNI bridge to MNN's LLM engine, written against the C++ API of the pinned tag in CMakeLists.txt
// (3.6.0): transformers/llm/engine/include/llm/llm.hpp.
//
// The Kotlin side sees the same opaque-handle, pull-based token loop as llama_jni.cpp:
//
//     handle = nativeCreateSession(configPath, ...)
//     nativeIngestPrompt(handle, userMessage)       // template + prefill, no decode
//     while ((piece = nativeNextToken(handle)) != null) { emit(piece) }   // one decode step each
//
// Pulling one token per call keeps backpressure, cancellation and coroutine cancellation in
// Kotlin, and means no C++ code ever attaches a thread to the JVM. MNN's API is push-based
// (response() streams into a std::ostream), but its own MnnLlmChat app already drives it in
// exactly this shape: response(messages, os, end, /*max_new_tokens=*/0) is documented prefill-only
// ("if (0 < max_tokens)" guards the decode), and generate(1) then decodes a single token into the
// ostream. This file just parks the ostream on a per-session capture buffer between calls.
//
// Unlike llama_jni, the conversation transcript lives *here* rather than in Kotlin: MNN renders
// ChatMessages through the model's chat template natively, and its prompt_cache needs to see the
// same message list every turn to reuse the KV cache incrementally.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdio>
#include <memory>
#include <ostream>
#include <streambuf>
#include <string>
#include <vector>

#include "llm/llm.hpp"

#define LOG_TAG "mnn_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using MNN::Transformer::ChatMessage;
using MNN::Transformer::ChatMessages;
using MNN::Transformer::Llm;
using MNN::Transformer::LlmStatus;

namespace {

// Error codes returned by nativeIngestPrompt. Mirrored in MnnNative.kt.
constexpr jint kErrDecode = -1;
constexpr jint kErrContextFull = -2;
constexpr jint kErrNoSession = -3;

// Refuse an ingest that would leave the reply fewer tokens of room than this. Arbitrary but small:
// the point is only to fail with "context full" rather than stream zero tokens and stop.
constexpr int kMinGenerateHeadroom = 16;

/// std::streambuf that appends everything written to it onto a std::string. MNN's generate loop
/// writes each decoded token's text to the session ostream; between nativeNextToken calls the
/// bytes just accumulate here.
class CaptureBuf final : public std::streambuf {
public:
    explicit CaptureBuf(std::string &target) : target_(target) {}

protected:
    std::streamsize xsputn(const char *s, std::streamsize n) override {
        target_.append(s, static_cast<size_t>(n));
        return n;
    }

    int overflow(int c) override {
        if (c != EOF) target_.push_back(static_cast<char>(c));
        return c;
    }

private:
    std::string &target_;
};

struct MnnSession {
    std::unique_ptr<Llm> llm;

    // The conversation as MNN's chat template will render it: optional ("system", ...) head, then
    // alternating user/assistant turns. MNN's prompt_cache diffs the rendered text of this list
    // against the previous turn's, so the KV cache is reused incrementally as long as the list
    // only ever grows at the tail.
    ChatMessages messages;

    // Raw bytes of the reply being decoded right now; becomes the "assistant" message on commit.
    std::string reply_raw;
    // Bytes captured from the most recent generate(1) call.
    std::string chunk;
    // Same role as in llama_jni: one multi-byte UTF-8 codepoint routinely straddles two tokens,
    // so incomplete tail bytes wait here until the rest lands.
    std::string utf8_pending;

    CaptureBuf capture{chunk};
    std::ostream os{&capture};

    // The context budget the *app* asked for. MNN's own max_all_tokens is set to match, but the
    // guard in nativeNextToken is ours, so the stream ends cleanly instead of erroring inside MNN.
    int max_ctx = 0;

    // A user turn has been ingested and its reply not yet committed to `messages`.
    bool turn_open = false;

    std::atomic<bool> cancelled{false};
};

MnnSession *as_session(jlong handle) {
    return reinterpret_cast<MnnSession *>(handle);
}

/**
 * Consumes the leading well-formed UTF-8 from `pending`, decodes it to UTF-16, and returns it as a
 * Java string; a truncated multi-byte sequence at the very end is left in place to be completed by
 * the next token. Identical rationale to llama_jni.cpp's drain_utf16: NewStringUTF wants
 * *Modified* UTF-8 and CheckJNI aborts the process on real-world model output (emoji, byte-
 * fallback tokens), so decode to UTF-16 by hand and let malformed bytes degrade to U+FFFD.
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
            utf16.push_back(0xFFFD);
            i += 1;
            continue;
        }

        if (i + len > n) break;  // truncated tail -- wait for the next token to complete it

        bool valid = true;
        for (size_t k = 1; k < len; ++k) {
            const auto cont = static_cast<unsigned char>(pending[i + k]);
            if ((cont & 0xC0) != 0x80) { valid = false; break; }
            cp = (cp << 6) | (cont & 0x3Fu);
        }
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
    if (utf16.empty()) return env->NewStringUTF("");
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

std::string json_escape(const std::string &raw) {
    std::string out;
    out.reserve(raw.size());
    for (char c : raw) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c;
        }
    }
    return out;
}

/**
 * Ends the current turn: the decoded reply becomes an "assistant" message, and MNN's prompt cache
 * is synced to the transcript *including* that reply. Without the sync the cache text would end at
 * the user turn (updateCachedPromptText runs inside the prefill-only response(), before any of our
 * generate(1) steps), every next-turn comparison would see a divergence, and the delta path --
 * the whole point of prompt_cache -- would never fire.
 */
void commit_reply(MnnSession *session) {
    if (!session->turn_open) return;
    session->turn_open = false;

    // A cancelled-before-first-token turn commits no assistant message: the transcript then ends
    // at the user turn, which every chat template renders happily.
    if (!session->reply_raw.empty()) {
        session->messages.emplace_back("assistant", session->reply_raw);
        session->llm->syncPromptCache(session->messages);
    }
    session->reply_raw.clear();
}

/**
 * Whether the session is in a state no further generate() call can recover from. Deliberately NOT
 * `status != RUNNING`: MNN's bounded decode sets MAX_TOKENS_FINISHED after *every* generate(1)
 * step (and stoped() sets NORMAL_FINISHED), and both of those states accept further calls --
 * generate_init resets them to RUNNING on the next turn. This mirrors the status set MNN's own
 * CHECK_LLM_RUNNING macro treats as fatal.
 */
bool in_error_state(const MNN::Transformer::LlmContext *ctx) {
    return ctx->status == LlmStatus::NOT_LOADED ||
           ctx->status == LlmStatus::INTERNAL_ERROR ||
           ctx->status == LlmStatus::TIMEOUT ||
           ctx->status == LlmStatus::USER_CANCEL;
}

}  // namespace

extern "C" {

/**
 * Loads the model behind [config_path] (an MNN export's config.json) and returns an opaque session
 * handle, or 0 on failure. Blocking and slow; called from Dispatchers.IO.
 *
 * There is no seed parameter, deliberately: MNN's sampler exposes no seed in its config schema,
 * so reproducible sampling is not available on this engine.
 */
JNIEXPORT jlong JNICALL
Java_com_example_aiagent_engine_mnn_MnnNative_nativeCreateSession(
        JNIEnv *env, jobject, jstring config_path, jint n_ctx, jint n_threads,
        jfloat temperature, jint top_k, jfloat top_p, jstring cache_dir) {

    const std::string path = jstring_to_std(env, config_path);
    const std::string tmp_dir = jstring_to_std(env, cache_dir);

    Llm *llm = Llm::createLLM(path);
    if (llm == nullptr) {
        LOGE("Llm::createLLM failed for %s", path.c_str());
        return 0;
    }

    // Overrides on top of the model's own config.json. backend_type stays "cpu" -- see
    // CMakeLists.txt for why no GPU backend is compiled in. prompt_cache is MNN's incremental
    // KV reuse across response() calls; without it every turn re-prefills the whole transcript.
    //
    // The sampler chain is stated in full rather than inherited, because the defaults bite:
    // MNN's default mixed_samplers has NO "penalty" stage, and several of Alibaba's own exports
    // (Qwen 2.5 1.5B among them) ship a config.json with no sampler section at all -- so without
    // this, generation runs with no repetition penalty, and small quantized models loop and
    // ramble exactly the way that implies. The chain and its numbers mirror llama_jni.cpp:
    // penalty 1.1 over the last 64 tokens, then topK -> topP -> temperature.
    char config[768];
    if (temperature <= 0.0f) {
        // Greedy decoding: MNN's temperature sampler cannot express temperature 0. Greedy is a
        // single-stage sampler, so (as with llama.cpp's greedy branch) no penalty applies.
        std::snprintf(config, sizeof(config),
                      R"({"backend_type":"cpu","thread_num":%d,"max_all_tokens":%d,)"
                      R"("prompt_cache":true,"sampler_type":"greedy","tmp_path":"%s"})",
                      static_cast<int>(n_threads), static_cast<int>(n_ctx),
                      json_escape(tmp_dir).c_str());
    } else {
        std::snprintf(config, sizeof(config),
                      R"({"backend_type":"cpu","thread_num":%d,"max_all_tokens":%d,)"
                      R"("prompt_cache":true,"sampler_type":"mixed",)"
                      R"("mixed_samplers":["penalty","topK","topP","temperature"],)"
                      R"("penalty":1.1,"penalty_window":64,)"
                      R"("temperature":%.4f,"topK":%d,"topP":%.4f,)"
                      R"("tmp_path":"%s"})",
                      static_cast<int>(n_threads), static_cast<int>(n_ctx),
                      static_cast<double>(temperature), static_cast<int>(top_k),
                      static_cast<double>(top_p), json_escape(tmp_dir).c_str());
    }
    llm->set_config(config);

    if (!llm->load()) {
        LOGE("Llm::load failed for %s", path.c_str());
        Llm::destroy(llm);
        return 0;
    }

    auto *session = new MnnSession();
    session->llm.reset(llm);
    session->max_ctx = static_cast<int>(n_ctx);

    LOGI("session ready: max_ctx=%d threads=%d", session->max_ctx, n_threads);
    return reinterpret_cast<jlong>(session);
}

/**
 * Seeds the transcript at load time: the system prompt (role "system") plus any restored history.
 * Called once, right after nativeCreateSession, before the first ingest.
 */
JNIEXPORT void JNICALL
Java_com_example_aiagent_engine_mnn_MnnNative_nativeSeedHistory(
        JNIEnv *env, jobject, jlong handle, jobjectArray roles, jobjectArray contents) {

    MnnSession *session = as_session(handle);
    if (session == nullptr) return;

    const jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; ++i) {
        auto role = reinterpret_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = reinterpret_cast<jstring>(env->GetObjectArrayElement(contents, i));
        session->messages.emplace_back(jstring_to_std(env, role), jstring_to_std(env, content));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }
}

/**
 * Appends the user message to the transcript, renders it through the model's own chat template,
 * and prefills -- no decode happens here. Returns the number of prompt tokens actually prefilled
 * this turn (with prompt_cache warm that is just the new suffix, which is also the honest number
 * for "prompt tokens" this turn), or a negative ERR_* code.
 */
JNIEXPORT jint JNICALL
Java_com_example_aiagent_engine_mnn_MnnNative_nativeIngestPrompt(
        JNIEnv *env, jobject, jlong handle, jstring prompt) {

    MnnSession *session = as_session(handle);
    if (session == nullptr) return kErrNoSession;

    // A previous turn abandoned mid-stream (collector cancelled without draining to the end)
    // still has its partial reply pending; fold it into the transcript before starting anew.
    commit_reply(session);

    session->cancelled.store(false);
    session->utf8_pending.clear();
    session->reply_raw.clear();
    session->chunk.clear();

    const auto *ctx = session->llm->getContext();

    // The KV cache must have room to prefill this turn and generate *something*. MNN clamps at
    // max_all_tokens internally, but its failure mode there is an ended stream with no
    // explanation; catching it here turns that into an actionable "start a new chat" error.
    if (ctx->all_seq_len >= session->max_ctx - kMinGenerateHeadroom) {
        return kErrContextFull;
    }

    session->messages.emplace_back("user", jstring_to_std(env, prompt));
    session->turn_open = true;

    // end_with = "" on purpose: nullptr would default to "\n", which MNN writes into the stream
    // when generation finishes and would arrive as a phantom trailing newline in the chat.
    session->llm->response(session->messages, &session->os, /*end_with=*/"",
                           /*max_new_tokens=*/0);

    if (in_error_state(ctx)) {
        LOGE("prefill failed, status=%d", static_cast<int>(ctx->status));
        session->messages.pop_back();
        session->turn_open = false;
        return kErrDecode;
    }
    if (ctx->all_seq_len >= session->max_ctx) {
        // The prompt alone filled the context; nothing can be generated from here. Roll the turn
        // back so a retry does not leave the message in the transcript twice. The KV cache keeps
        // the prefilled tokens, but the prompt cache detects that mismatch next turn and falls
        // back to a full re-prefill, so nothing is corrupted -- and the context is full anyway.
        session->messages.pop_back();
        session->turn_open = false;
        return kErrContextFull;
    }

    return static_cast<jint>(ctx->prompt_len);
}

/**
 * One decode step. Returns the next piece of text, or null when generation is finished -- the
 * model emitted its stop token, the context filled up, the caller cancelled, or MNN reported an
 * error. An empty (non-null) string means "half a UTF-8 codepoint, keep going".
 */
JNIEXPORT jstring JNICALL
Java_com_example_aiagent_engine_mnn_MnnNative_nativeNextToken(JNIEnv *env, jobject, jlong handle) {
    MnnSession *session = as_session(handle);
    if (session == nullptr) return nullptr;

    const auto *ctx = session->llm->getContext();

    if (session->cancelled.load() ||
        in_error_state(ctx) ||
        ctx->all_seq_len >= session->max_ctx ||
        (ctx->gen_seq_len > 0 && session->llm->stoped())) {
        commit_reply(session);
        return nullptr;
    }

    session->chunk.clear();
    session->llm->generate(1);

    // The step that samples the stop token writes no text; that is the natural end of the stream.
    if (session->chunk.empty() && (session->llm->stoped() || in_error_state(ctx))) {
        commit_reply(session);
        return nullptr;
    }

    session->reply_raw += session->chunk;
    session->utf8_pending += session->chunk;
    return drain_utf16(env, session->utf8_pending);
}

JNIEXPORT void JNICALL
Java_com_example_aiagent_engine_mnn_MnnNative_nativeCancel(JNIEnv *, jobject, jlong handle) {
    MnnSession *session = as_session(handle);
    if (session != nullptr) session->cancelled.store(true);
}

/** Drops conversation history -- everything but the seeded system prompt -- and clears the KV cache. */
JNIEXPORT void JNICALL
Java_com_example_aiagent_engine_mnn_MnnNative_nativeResetContext(JNIEnv *, jobject, jlong handle) {
    MnnSession *session = as_session(handle);
    if (session == nullptr) return;

    session->llm->reset();

    ChatMessages system_only;
    if (!session->messages.empty() && session->messages.front().first == "system") {
        system_only.push_back(session->messages.front());
    }
    session->messages = std::move(system_only);

    session->reply_raw.clear();
    session->chunk.clear();
    session->utf8_pending.clear();
    session->turn_open = false;
    session->cancelled.store(false);
}

/** Tokens currently held in the KV cache. */
JNIEXPORT jint JNICALL
Java_com_example_aiagent_engine_mnn_MnnNative_nativeContextUsed(JNIEnv *, jobject, jlong handle) {
    MnnSession *session = as_session(handle);
    if (session == nullptr) return 0;
    return static_cast<jint>(session->llm->getContext()->all_seq_len);
}

/**
 * Decode throughput as measured by MNN itself for the current/last turn, in tokens per second.
 * 0 when nothing has been decoded yet. Preferred over wall-clock because it excludes the JNI and
 * Flow-collection overhead the pull loop adds around each token.
 */
JNIEXPORT jdouble JNICALL
Java_com_example_aiagent_engine_mnn_MnnNative_nativeDecodeTokensPerSecond(JNIEnv *, jobject,
                                                                          jlong handle) {
    MnnSession *session = as_session(handle);
    if (session == nullptr) return 0.0;

    const auto *ctx = session->llm->getContext();
    if (ctx->decode_us <= 0 || ctx->gen_seq_len <= 0) return 0.0;
    return ctx->gen_seq_len * 1'000'000.0 / static_cast<double>(ctx->decode_us);
}

JNIEXPORT void JNICALL
Java_com_example_aiagent_engine_mnn_MnnNative_nativeFreeSession(JNIEnv *, jobject, jlong handle) {
    MnnSession *session = as_session(handle);
    delete session;
}

}  // extern "C"
