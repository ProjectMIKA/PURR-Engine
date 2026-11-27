// ✨ MIKA'S ZONOS1 ADAPTER CONTRACT (v2.7.0) ✨
// C surface implemented by zonos1_engine_adapter.cpp. Wire these six functions
// to zonos1.cpp's engine entry points (espeak-ng phonemize → prefix conditioner
// → 1.6B CFG transformer → DAC-44kHz) when vendored in third_party/zonos1.
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

// Creates a session over modelDir (zonos1-*.gguf + dac.gguf + spk-encoder.gguf).
// altBackbonePath lets the SUB-19A Q4_K Lite chip live in its own directory;
// pass "" to use the backbone inside modelDir. Returns 0 on failure.
long long z1_create(const char* model_dir, const char* alt_backbone_path,
                    const char* lib_dir, int threads, char* err, int err_len);

// Synthesizes one utterance with the speaker identity at referencePath — a
// saved .npy speaker embedding or a raw reference clip. emotionAxes:
// {joy, love, anger, sadness, flustered} in [0,1] → Zonos conditioning vector.
// Returns malloc'd 44kHz mono float PCM (length in out_len; caller frees).
float* z1_synthesize(long long handle, const char* text, const char* reference_path,
                     const float* emotion_axes, float speaking_rate, const char* language,
                     int threads, int* out_len, char* err, int err_len);

// Encodes a reference WAV (10-30s recommended) into a 128-d speaker embedding.
int z1_encode_speaker(long long handle, const char* wav_path, const char* out_npy_path,
                      char* err, int err_len);

const char* z1_last_error(void);

void z1_destroy(long long handle);

#ifdef __cplusplus
}
#endif
