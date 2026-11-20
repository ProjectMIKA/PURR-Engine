#include <jni.h>
#include <android/log.h>

#include "llama_wrapper.h"
#include "logger.h"
#include "lunavox_c_api.h"

#include <cstring>
#include <mutex>
#include <string>

#define PURR_LOG_TAG "PurrQwen3JNI"

namespace {

std::once_flag g_log_init_once;
std::string g_last_global_error;
std::mutex g_err_mutex;

void set_global_error(const std::string & msg) {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    g_last_global_error = msg;
}

jfloatArray copy_audio_and_free(JNIEnv * env, LunavoxAudio * audio, LunavoxEngine * engine) {
    if (!audio || !audio->samples || audio->n_samples <= 0) {
        if (engine) {
            const char * err = lunavox_get_error(engine);
            if (err && err[0]) set_global_error(err);
        }
        if (audio) lunavox_free_audio(audio);
        return nullptr;
    }

    jfloatArray out = env->NewFloatArray(audio->n_samples);
    if (out) {
        env->SetFloatArrayRegion(out, 0, audio->n_samples, audio->samples);
    }
    lunavox_free_audio(audio);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_projectmika_purrtts_engine_qwen3_Qwen3NativeBridge_nativeCreate(
    JNIEnv * env,
    jobject /* thiz */,
    jstring modelDir,
    jstring nativeLibDir,
    jint nThreads) {
    std::call_once(g_log_init_once, []() {
        lunavox_set_log_callback([](LunavoxLogLevel level, const char * message, void * /* user_data */) {
            if (!message) return;
            const int prio = (level >= LUNAVOX_LOG_ERROR) ? ANDROID_LOG_ERROR
                : (level == LUNAVOX_LOG_WARN) ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
            __android_log_print(prio, PURR_LOG_TAG, "%s", message);
        }, nullptr);
    });

    const char * c_model_dir = env->GetStringUTFChars(modelDir, nullptr);
    const char * c_lib_dir = env->GetStringUTFChars(nativeLibDir, nullptr);
    std::string model_path = c_model_dir ? c_model_dir : "";
    std::string lib_path = c_lib_dir ? c_lib_dir : "";
    if (c_model_dir) env->ReleaseStringUTFChars(modelDir, c_model_dir);
    if (c_lib_dir) env->ReleaseStringUTFChars(nativeLibDir, c_lib_dir);

    std::string llama_err;
    if (!lunavox::LlamaLibrary::instance().ensure_loaded(lib_path, llama_err)) {
        set_global_error("Failed to load libllama/libggml from " + lib_path + ": " + llama_err);
        __android_log_print(ANDROID_LOG_ERROR, PURR_LOG_TAG, "%s", g_last_global_error.c_str());
        return 0L;
    }

    LunavoxEngine * engine = lunavox_create(model_path.c_str(), nThreads > 0 ? nThreads : 4);
    if (!engine) {
        set_global_error("lunavox_create failed for " + model_path);
        return 0L;
    }
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jfloatArray JNICALL
Java_com_projectmika_purrtts_engine_qwen3_Qwen3NativeBridge_nativeSynthesizeCustom(
    JNIEnv * env,
    jobject /* thiz */,
    jlong handle,
    jstring text,
    jstring speaker,
    jstring instruct,
    jint languageId,
    jint nThreads) {
    auto * engine = reinterpret_cast<LunavoxEngine *>(handle);
    if (!engine) return nullptr;

    const char * c_text = env->GetStringUTFChars(text, nullptr);
    const char * c_spk = speaker ? env->GetStringUTFChars(speaker, nullptr) : nullptr;
    const char * c_inst = instruct ? env->GetStringUTFChars(instruct, nullptr) : nullptr;

    LunavoxSynthesisParams params;
    lunavox_default_params(&params);
    params.n_threads = nThreads > 0 ? nThreads : 4;
    params.language_id = languageId;

    LunavoxAudio * audio = lunavox_synthesize_custom(
        engine,
        c_text ? c_text : "",
        c_spk ? c_spk : "vivian",
        c_inst,
        &params
    );

    if (c_text) env->ReleaseStringUTFChars(text, c_text);
    if (c_spk) env->ReleaseStringUTFChars(speaker, c_spk);
    if (c_inst) env->ReleaseStringUTFChars(instruct, c_inst);

    return copy_audio_and_free(env, audio, engine);
}

JNIEXPORT jfloatArray JNICALL
Java_com_projectmika_purrtts_engine_qwen3_Qwen3NativeBridge_nativeSynthesizeClone(
    JNIEnv * env,
    jobject /* thiz */,
    jlong handle,
    jstring text,
    jstring refWavPath,
    jstring refText,
    jint languageId,
    jint nThreads) {
    auto * engine = reinterpret_cast<LunavoxEngine *>(handle);
    if (!engine) return nullptr;

    const char * c_text = env->GetStringUTFChars(text, nullptr);
    const char * c_wav = env->GetStringUTFChars(refWavPath, nullptr);
    const char * c_ref_txt = refText ? env->GetStringUTFChars(refText, nullptr) : nullptr;

    LunavoxSynthesisParams params;
    lunavox_default_params(&params);
    params.n_threads = nThreads > 0 ? nThreads : 4;
    params.language_id = languageId;
    params.ref_text = c_ref_txt;

    LunavoxAudio * audio = lunavox_synthesize_with_voice_file(
        engine,
        c_text ? c_text : "",
        c_wav ? c_wav : "",
        &params
    );

    if (c_text) env->ReleaseStringUTFChars(text, c_text);
    if (c_wav) env->ReleaseStringUTFChars(refWavPath, c_wav);
    if (c_ref_txt) env->ReleaseStringUTFChars(refText, c_ref_txt);

    return copy_audio_and_free(env, audio, engine);
}

JNIEXPORT jfloatArray JNICALL
Java_com_projectmika_purrtts_engine_qwen3_Qwen3NativeBridge_nativeSynthesizeDesign(
    JNIEnv * env,
    jobject /* thiz */,
    jlong handle,
    jstring text,
    jstring instruct,
    jint languageId,
    jint nThreads) {
    auto * engine = reinterpret_cast<LunavoxEngine *>(handle);
    if (!engine) return nullptr;

    const char * c_text = env->GetStringUTFChars(text, nullptr);
    const char * c_inst = env->GetStringUTFChars(instruct, nullptr);

    LunavoxSynthesisParams params;
    lunavox_default_params(&params);
    params.n_threads = nThreads > 0 ? nThreads : 4;
    params.language_id = languageId;

    LunavoxAudio * audio = lunavox_synthesize_design(
        engine,
        c_text ? c_text : "",
        c_inst ? c_inst : "Warm expressive studio voice",
        &params
    );

    if (c_text) env->ReleaseStringUTFChars(text, c_text);
    if (c_inst) env->ReleaseStringUTFChars(instruct, c_inst);

    return copy_audio_and_free(env, audio, engine);
}

JNIEXPORT jstring JNICALL
Java_com_projectmika_purrtts_engine_qwen3_Qwen3NativeBridge_nativeGetLastError(
    JNIEnv * env,
    jobject /* thiz */,
    jlong handle) {
    auto * engine = reinterpret_cast<LunavoxEngine *>(handle);
    if (engine) {
        const char * err = lunavox_get_error(engine);
        if (err && err[0]) {
            return env->NewStringUTF(err);
        }
    }
    std::lock_guard<std::mutex> lock(g_err_mutex);
    return env->NewStringUTF(g_last_global_error.c_str());
}

JNIEXPORT void JNICALL
Java_com_projectmika_purrtts_engine_qwen3_Qwen3NativeBridge_nativeDestroy(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle) {
    auto * engine = reinterpret_cast<LunavoxEngine *>(handle);
    if (engine) {
        lunavox_destroy(engine);
    }
}

} // extern "C"
