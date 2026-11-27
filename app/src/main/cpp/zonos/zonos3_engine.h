// ✨ MIKA'S ZONOS3 ADAPTER CONTRACT (v2.7.0) ✨
// C surface implemented by zonos3_engine_adapter.cpp. When the vendored
// zonos3.cpp runtime lands in third_party/zonos3, wire these six functions to
// its engine entry points (backbone prompt build → 15Hz codebooks → ZAC-1
// decode → 44.1kHz iSTFT) and drop the PURR_ZONOS3_VENDORED branch in CMake.
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

// Creates a session over modelDir (backbone-ptq.gguf + zac-keep2.gguf
// [+ spk-encoder.gguf + steer.gguf]). Returns 0 on failure.
long long z3_create(const char* model_dir, const char* lib_dir, int threads,
                    char* err, int err_len);

// Synthesizes one utterance with the .npy speaker embedding at embeddingPath
// (empty string = neutral speaker). emotionAxes: {joy, love, anger, sadness,
// flustered} in [0,1] → steer.gguf directions. Returns malloc'd 44.1kHz mono
// float PCM (length in out_len; caller frees) or nullptr on failure.
float* z3_synthesize(long long handle, const char* text, const char* embedding_path,
                     const float* emotion_axes, float speaking_rate, const char* language,
                     int threads, int* out_len, char* err, int err_len);

// Encodes a reference WAV (up to 60s) into a 192-d speaker embedding .npy.
// Requires spk-encoder.gguf. Returns 1 on success.
int z3_encode_speaker(long long handle, const char* wav_path, const char* out_npy_path,
                      char* err, int err_len);

// Last error message for the calling thread (never nullptr).
const char* z3_last_error(void);

void z3_destroy(long long handle);

#ifdef __cplusplus
}
#endif
