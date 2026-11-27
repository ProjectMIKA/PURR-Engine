// ✨ MIKA'S ZONOS1 RUNTIME ADAPTER (v2.7.0) ✨
// Integration point between PURR's JNI glue and the vendored Zyphra zonos1.cpp
// runtime (third_party/zonos1). Stub mode keeps the APK linkable and honest.
#include "zonos1_engine.h"

#include <cstring>
#include <string>

namespace {

thread_local std::string g_lastError;

void set_error(char* err, int err_len, const char* message) {
    g_lastError = message;
    if (err != nullptr && err_len > 0) {
        std::strncpy(err, message, static_cast<size_t>(err_len - 1));
        err[err_len - 1] = '\0';
    }
}

}  // namespace

extern "C" {

#ifdef PURR_ZONOS1_VENDORED
#error "Wire z1_* to the vendored zonos1.cpp engine API (scripts/fetch_zonos_sources.sh)"
#else
long long z1_create(const char* /*model_dir*/, const char* /*alt_backbone_path*/,
                    const char* /*lib_dir*/, int /*threads*/, char* err, int err_len) {
    set_error(err, err_len, "zonos1 runtime not vendored (run scripts/fetch_zonos_sources.sh)");
    return 0;
}

float* z1_synthesize(long long /*handle*/, const char* /*text*/, const char* /*reference_path*/,
                     const float* /*emotion_axes*/, float /*speaking_rate*/, const char* /*language*/,
                     int /*threads*/, int* out_len, char* err, int err_len) {
    if (out_len != nullptr) *out_len = 0;
    set_error(err, err_len, "zonos1 runtime not vendored (run scripts/fetch_zonos_sources.sh)");
    return nullptr;
}

int z1_encode_speaker(long long /*handle*/, const char* /*wav_path*/, const char* /*out_npy_path*/,
                      char* err, int err_len) {
    set_error(err, err_len, "zonos1 speaker encoder not vendored");
    return 0;
}
#endif

const char* z1_last_error(void) { return g_lastError.c_str(); }

void z1_destroy(long long /*handle*/) {}

}  // extern "C"
