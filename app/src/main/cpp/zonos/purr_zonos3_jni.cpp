// ✨ MIKA'S ZONOS3 JNI GLUE (v2.7.0) ✨
// Java_com_projectmika_purrtts_engine_zonos3_Zonos3NativeBridge_* → C adapter
// (zonos3_engine.h). Pure marshaling: strings in, float PCM out. No throws —
// every failure lands in nativeGetLastError() and the Kotlin engine fails soft.
#include <jni.h>

#include <cstdlib>
#include <string>
#include <vector>

#include "zonos3_engine.h"

namespace {

std::string jstr(JNIEnv* env, jstring value) {
    if (value == nullptr) return "";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

jfloatArray float_array(JNIEnv* env, const float* pcm, int len) {
    if (pcm == nullptr || len <= 0) return nullptr;
    jfloatArray out = env->NewFloatArray(len);
    if (out != nullptr) {
        env->SetFloatArrayRegion(out, 0, len, pcm);
    }
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_projectmika_purrtts_engine_zonos3_Zonos3NativeBridge_nativeCreate(
    JNIEnv* env, jobject /*thiz*/, jstring modelDir, jstring nativeLibDir, jint nThreads) {
    char err[512] = {0};
    const std::string dir = jstr(env, modelDir);
    const std::string libDir = jstr(env, nativeLibDir);
    return static_cast<jlong>(
        z3_create(dir.c_str(), libDir.c_str(), static_cast<int>(nThreads), err, sizeof(err)));
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_projectmika_purrtts_engine_zonos3_Zonos3NativeBridge_nativeSynthesize(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring text, jstring voiceEmbeddingPath,
    jfloatArray emotionAxes, jfloat speakingRate, jstring languageHint, jint nThreads) {
    float axes[5] = {0.f, 0.f, 0.f, 0.f, 0.f};
    if (emotionAxes != nullptr) {
        const jsize len = env->GetArrayLength(emotionAxes);
        env->GetFloatArrayRegion(emotionAxes, 0, len < 5 ? len : 5, axes);
    }
    char err[512] = {0};
    const std::string body = jstr(env, text);
    const std::string embedding = jstr(env, voiceEmbeddingPath);
    const std::string language = jstr(env, languageHint);
    int outLen = 0;
    float* pcm = z3_synthesize(static_cast<long long>(handle), body.c_str(),
                               embedding.c_str(), axes, static_cast<float>(speakingRate),
                               language.c_str(), static_cast<int>(nThreads), &outLen, err,
                               sizeof(err));
    if (pcm == nullptr || outLen <= 0) {
        if (pcm != nullptr) std::free(pcm);
        return nullptr;
    }
    jfloatArray out = float_array(env, pcm, outLen);
    std::free(pcm);
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_projectmika_purrtts_engine_zonos3_Zonos3NativeBridge_nativeEncodeSpeaker(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring wavPath, jstring outNpyPath) {
    char err[512] = {0};
    const std::string wav = jstr(env, wavPath);
    const std::string outNpy = jstr(env, outNpyPath);
    const int ok = z3_encode_speaker(static_cast<long long>(handle), wav.c_str(),
                                     outNpy.c_str(), err, sizeof(err));
    return ok != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_projectmika_purrtts_engine_zonos3_Zonos3NativeBridge_nativeGetLastError(
    JNIEnv* env, jobject /*thiz*/, jlong /*handle*/) {
    return env->NewStringUTF(z3_last_error());
}

extern "C" JNIEXPORT void JNICALL
Java_com_projectmika_purrtts_engine_zonos3_Zonos3NativeBridge_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    z3_destroy(static_cast<long long>(handle));
}
