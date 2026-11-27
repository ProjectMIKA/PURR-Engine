// ✨ MIKA'S ZONOS3 RUNTIME ADAPTER (v2.7.0) ✨
// The single integration point between PURR's JNI glue and the vendored Zyphra
// zonos3.cpp ggml runtime (third_party/zonos3). Built in stub mode when the
// sources are absent so the APK always links; every call then returns a clean
// "not vendored" error and the Kotlin engine fails soft to legacy paths.
#include "zonos3_engine.h"

#include <cstring>
#include <string>
#include <vector>

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

#ifdef PURR_ZONOS3_VENDORED
// Real runtime: zonos3.cpp exposes its session/synthesis entry points here.
// See third_party/zonos3/servers/ggml (zonos3-cli / zonos3-server share these).
#error "Wire z3_* to the vendored zonos3.cpp engine API (scripts/fetch_zonos_sources.sh)"
#else
long long z3_create(const char* /*model_dir*/, const char* /*lib_dir*/, int /*threads*/,
                    char* err, int err_len) {
    set_error(err, err_len, "zonos3 runtime not vendored (run scripts/fetch_zonos_sources.sh)");
    return 0;
}

float* z3_synthesize(long long /*handle*/, const char* /*text*/, const char* /*embedding_path*/,
                     const float* /*emotion_axes*/, float /*speaking_rate*/, const char* /*language*/,
                     int /*threads*/, int* out_len, char* err, int err_len) {
    if (out_len != nullptr) *out_len = 0;
    set_error(err, err_len, "zonos3 runtime not vendored (run scripts/fetch_zonos_sources.sh)");
    return nullptr;
}

int z3_encode_speaker(long long /*handle*/, const char* /*wav_path*/, const char* /*out_npy_path*/,
                      char* err, int err_len) {
    set_error(err, err_len, "zonos3 speaker encoder not vendored");
    return 0;
}
#endif

const char* z3_last_error(void) { return g_lastError.c_str(); }

void z3_destroy(long long /*handle*/) {}

}  // extern "C"
