// ✨ MIKA'S ZONOS1 JNI GLUE (v2.7.0) ✨
// Java_com_projectmika_purrtts_engine_zonos1_Zonos1NativeBridge_* → C adapter
// (zonos1_engine.h). Pure marshaling; failures land in nativeGetLastError().
#include <jni.h>

#include <cstdlib>
#include <string>

#include "zonos1_engine.h"

namespace {

std::string jstr(JNIEnv* env, jstring value) {
    if (value == nullptr) return "";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_projectmika_purrtts_engine_zonos1_Zonos1NativeBridge_nativeCreate(
    JNIEnv* env, jobject /*thiz*/, jstring modelDir, jstring altBackbonePath,
    jstring nativeLibDir, jint nThreads) {
    char err[512] = {0};
    const std::string dir = jstr(env, modelDir);
    const std::string alt = jstr(env, altBackbonePath);
    const std::string libDir = jstr(env, nativeLibDir);
    return static_cast<jlong>(z1_create(dir.c_str(), alt.c_str(), libDir.c_str(),
                                        static_cast<int>(nThreads), err, sizeof(err)));
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_projectmika_purrtts_engine_zonos1_Zonos1NativeBridge_nativeSynthesize(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring text, jstring referencePath,
    jfloatArray emotionAxes, jfloat speakingRate, jstring languageHint, jint nThreads) {
    float axes[5] = {0.f, 0.f, 0.f, 0.f, 0.f};
    if (emotionAxes != nullptr) {
        const jsize len = env->GetArrayLength(emotionAxes);
        env->GetFloatArrayRegion(emotionAxes, 0, len < 5 ? len : 5, axes);
    }
    char err[512] = {0};
    const std::string body = jstr(env, text);
    const std::string reference = jstr(env, referencePath);
    const std::string language = jstr(env, languageHint);
    int outLen = 0;
    float* pcm = z1_synthesize(static_cast<long long>(handle), body.c_str(),
                               reference.c_str(), axes, static_cast<float>(speakingRate),
                               language.c_str(), static_cast<int>(nThreads), &outLen, err,
                               sizeof(err));
    if (pcm == nullptr || outLen <= 0) {
        if (pcm != nullptr) std::free(pcm);
        return nullptr;
    }
    jfloatArray out = env->NewFloatArray(outLen);
    if (out != nullptr) env->SetFloatArrayRegion(out, 0, outLen, pcm);
    std::free(pcm);
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_projectmika_purrtts_engine_zonos1_Zonos1NativeBridge_nativeEncodeSpeaker(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring wavPath, jstring outNpyPath) {
    char err[512] = {0};
    const std::string wav = jstr(env, wavPath);
    const std::string outNpy = jstr(env, outNpyPath);
    const int ok = z1_encode_speaker(static_cast<long long>(handle), wav.c_str(),
                                     outNpy.c_str(), err, sizeof(err));
    return ok != 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_projectmika_purrtts_engine_zonos1_Zonos1NativeBridge_nativeGetLastError(
    JNIEnv* env, jobject /*thiz*/, jlong /*handle*/) {
    return env->NewStringUTF(z1_last_error());
}

extern "C" JNIEXPORT void JNICALL
Java_com_projectmika_purrtts_engine_zonos1_Zonos1NativeBridge_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    z1_destroy(static_cast<long long>(handle));
}
