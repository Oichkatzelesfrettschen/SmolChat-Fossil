// Inference entry points for a process that holds no filesystem access.
//
// The model arrives as an already-open file descriptor. An isolated_app
// process may read and mmap a descriptor passed over Binder but may not
// open() any app_data_file (system/sepolicy private/isolated_app_all.te:
// "neverallow isolated_app_all app_data_file_type:file open"), so reopening
// /proc/self/fd/N fails and the GGUF is read through
// llama_model_load_from_file_ptr() on an fdopen()ed duplicate.
//
// Every generate() call starts from an empty KV cache and a caller-formatted
// prompt, and builds a fresh sampler chain, so a GBNF grammar applies to one
// request only.

#include "common.h"
#include "llama.h"

#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <atomic>
#include <cstdio>
#include <string>
#include <vector>

#define TAG "SandboxedInference"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct SandboxedModel {
    FILE*             file  = nullptr;
    llama_model*      model = nullptr;
    llama_context*    ctx   = nullptr;
    std::atomic<bool> cancel{false};

    ~SandboxedModel() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
        // llama_file(FILE*) does not own the stream (owns_fp = false); the
        // mmap, when used, stays valid after fclose.
        if (file) fclose(file);
    }
};

void throwIllegalState(JNIEnv* env, const char* msg) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), msg);
}

// Length of the longest prefix of s that ends on a UTF-8 code point boundary.
size_t completeUtf8Prefix(const std::string& s) {
    size_t i = 0, last = 0;
    while (i < s.size()) {
        const auto c = static_cast<unsigned char>(s[i]);
        size_t     n = (c & 0x80) == 0x00 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        if (i + n > s.size()) break;
        i += n;
        last = i;
    }
    return last;
}

// NewStringUTF expects modified UTF-8; build the jstring from UTF-16 instead
// so 4-byte sequences (emoji, CJK extension planes) survive.
jstring toJString(JNIEnv* env, const std::string& utf8) {
    std::u16string out;
    out.reserve(utf8.size());
    for (size_t i = 0; i < utf8.size();) {
        const auto c = static_cast<unsigned char>(utf8[i]);
        uint32_t   cp;
        size_t     n;
        if (c < 0x80) { cp = c; n = 1; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; n = 2; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; n = 3; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; n = 4; }
        else { cp = 0xFFFD; n = 1; }
        if (i + n > utf8.size()) { cp = 0xFFFD; n = utf8.size() - i; }
        else for (size_t k = 1; k < n; ++k) cp = (cp << 6) | (static_cast<unsigned char>(utf8[i + k]) & 0x3F);
        i += n;
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
    }
    return env->NewString(reinterpret_cast<const jchar*>(out.data()), static_cast<jsize>(out.size()));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_shubham0204_smollm_SandboxedLM_nativeLoad(JNIEnv* env, jobject /*thiz*/, jint fd, jint nCtx, jint nThreads,
                                                  jboolean useMmap) {
    // dup: the caller's ParcelFileDescriptor keeps ownership of fd
    const int own = dup(fd);
    if (own < 0) {
        throwIllegalState(env, "dup() on the model descriptor failed");
        return 0;
    }
    auto* m = new SandboxedModel();
    m->file = fdopen(own, "rb");
    if (!m->file) {
        close(own);
        delete m;
        throwIllegalState(env, "fdopen() on the model descriptor failed");
        return 0;
    }

    ggml_backend_load_all();
    llama_model_params mp = llama_model_default_params();
    mp.use_mmap           = useMmap;
    mp.use_mlock          = false;
    m->model              = llama_model_load_from_file_ptr(m->file, mp);
    if (!m->model) {
        delete m;
        throwIllegalState(env, "llama_model_load_from_file_ptr() failed");
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx                = static_cast<uint32_t>(nCtx);
    cp.n_batch              = static_cast<uint32_t>(nCtx);
    cp.n_ubatch             = 128;
    cp.n_threads            = nThreads;
    cp.n_threads_batch      = nThreads;
    cp.no_perf              = true;
    m->ctx                  = llama_init_from_model(m->model, cp);
    if (!m->ctx) {
        delete m;
        throwIllegalState(env, "llama_init_from_model() failed");
        return 0;
    }
    LOGi("model loaded: n_params=%llu n_ctx=%u threads=%d mmap=%d",
         static_cast<unsigned long long>(llama_model_n_params(m->model)), llama_n_ctx(m->ctx), nThreads, useMmap);
    return reinterpret_cast<jlong>(m);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_shubham0204_smollm_SandboxedLM_nativeDescribe(JNIEnv* env, jobject /*thiz*/, jlong ptr) {
    auto* m = reinterpret_cast<SandboxedModel*>(ptr);
    char  desc[128];
    llama_model_desc(m->model, desc, sizeof(desc));
    const std::string out = std::string(desc) + "\n" + std::to_string(llama_model_n_params(m->model)) + "\n" +
                            std::to_string(llama_model_size(m->model)) + "\n" + std::to_string(llama_n_ctx(m->ctx));
    return toJString(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_io_shubham0204_smollm_SandboxedLM_nativeCancel(JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    reinterpret_cast<SandboxedModel*>(ptr)->cancel.store(true);
}

// Streams pieces to callback.onPiece(String): Boolean; returns tokens generated,
// or -1 when the grammar failed to parse.
extern "C" JNIEXPORT jint JNICALL
Java_io_shubham0204_smollm_SandboxedLM_nativeGenerate(JNIEnv* env, jobject /*thiz*/, jlong ptr, jstring jprompt,
                                                      jstring jgrammar, jfloat temperature, jfloat minP,
                                                      jint maxTokens, jobject callback) {
    auto* m = reinterpret_cast<SandboxedModel*>(ptr);
    m->cancel.store(false);

    const char*       promptC  = env->GetStringUTFChars(jprompt, nullptr);
    const std::string prompt(promptC);
    env->ReleaseStringUTFChars(jprompt, promptC);
    std::string grammar;
    if (jgrammar) {
        const char* g = env->GetStringUTFChars(jgrammar, nullptr);
        grammar       = g;
        env->ReleaseStringUTFChars(jgrammar, g);
    }

    jclass    cbClass = env->GetObjectClass(callback);
    jmethodID onPiece = env->GetMethodID(cbClass, "onPiece", "(Ljava/lang/String;)Z");

    const llama_vocab* vocab = llama_model_get_vocab(m->model);

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        llama_sampler* g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!g) {
            llama_sampler_free(smpl);
            return -1;
        }
        llama_sampler_chain_add(smpl, g);
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(minP, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    llama_memory_clear(llama_get_memory(m->ctx), true);
    // the caller formats special tokens (<|im_start|> etc.) into the prompt
    std::vector<llama_token> tokens = common_tokenize(vocab, prompt, true, true);
    const int                n_ctx  = static_cast<int>(llama_n_ctx(m->ctx));
    if (static_cast<int>(tokens.size()) + 1 >= n_ctx) {
        llama_sampler_free(smpl);
        throwIllegalState(env, "prompt does not fit the context window");
        return 0;
    }

    const int n_batch = static_cast<int>(llama_n_batch(m->ctx));
    for (size_t i = 0; i < tokens.size(); i += n_batch) {
        const int     n     = std::min<int>(n_batch, static_cast<int>(tokens.size() - i));
        llama_batch   batch = llama_batch_get_one(tokens.data() + i, n);
        if (llama_decode(m->ctx, batch) != 0) {
            llama_sampler_free(smpl);
            throwIllegalState(env, "llama_decode() failed on the prompt");
            return 0;
        }
        if (m->cancel.load()) break;
    }

    int         generated = 0;
    int         n_past    = static_cast<int>(tokens.size());
    std::string pending;
    while (!m->cancel.load() && generated < maxTokens && n_past < n_ctx) {
        llama_token tok = llama_sampler_sample(smpl, m->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        pending += common_token_to_piece(m->ctx, tok, false);
        ++generated;
        const size_t ready = completeUtf8Prefix(pending);
        if (ready > 0) {
            jstring  js   = toJString(env, pending.substr(0, ready));
            jboolean more = env->CallBooleanMethod(callback, onPiece, js);
            env->DeleteLocalRef(js);
            pending.erase(0, ready);
            if (env->ExceptionCheck() || !more) break;
        }
        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(m->ctx, batch) != 0) {
            llama_sampler_free(smpl);
            throwIllegalState(env, "llama_decode() failed during generation");
            return generated;
        }
        ++n_past;
    }
    llama_sampler_free(smpl);
    return generated;
}

extern "C" JNIEXPORT void JNICALL
Java_io_shubham0204_smollm_SandboxedLM_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    delete reinterpret_cast<SandboxedModel*>(ptr);
}
