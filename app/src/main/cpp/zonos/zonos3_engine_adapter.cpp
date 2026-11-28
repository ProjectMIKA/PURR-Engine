// ✨ MIKA'S ZONOS3 RUNTIME ADAPTER (v2.8.0) ✨
// JNI glue ↔ vendored zonos3.cpp (third_party/zonos3 → servers/ggml). Mirrors the
// zonos3-cli path: build_prompt → generate → prefill codes → zac_decode → gain.
#include "zonos3_engine.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fstream>
#include <iterator>
#include <algorithm>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#if defined(PURR_ZONOS3_VENDORED)
#include "zonos3.h"
#include "zonos3-generation.h"
#include "zac.h"
#include "spk-encoder.h"
#include "npy.h"
#endif

namespace {

thread_local std::string g_lastError;
std::mutex g_mtx;

void set_error(char* err, int err_len, const char* message) {
    g_lastError = message;
    if (err != nullptr && err_len > 0) {
        std::strncpy(err, message, static_cast<size_t>(err_len - 1));
        err[err_len - 1] = '\0';
    }
}

bool file_ok(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    return f.good();
}

std::string join_path(const std::string& dir, const char* name) {
    if (dir.empty()) return name;
    return dir.back() == '/' ? dir + name : dir + "/" + name;
}

#if defined(PURR_ZONOS3_VENDORED)

// 16-bit mono PCM WAV (PURR's clone-reference format) → float samples.
bool read_wav_pcm16_mono(const std::string& path, std::vector<float>& out, int& rate_hz) {
    std::ifstream f(path, std::ios::binary);
    if (!f.good()) return false;
    std::vector<unsigned char> bytes((std::istreambuf_iterator<char>(f)),
                                     std::istreambuf_iterator<char>());
    if (bytes.size() < 44 || std::memcmp(bytes.data(), "RIFF", 4) != 0 ||
        std::memcmp(bytes.data() + 8, "WAVE", 4) != 0) return false;
    size_t pos = 12;
    int channels = 0, bits = 0, src_rate = 0;
    size_t data_off = 0, data_len = 0;
    while (pos + 8 <= bytes.size()) {
        uint32_t chunk = 0;
        std::memcpy(&chunk, bytes.data() + pos + 4, 4);
        if (std::memcmp(bytes.data() + pos, "fmt ", 4) == 0 && pos + 24 <= bytes.size()) {
            uint16_t fmt = 0, ch = 0, bps = 0;
            uint32_t sr = 0;
            std::memcpy(&fmt, bytes.data() + pos + 8, 2);
            std::memcpy(&ch, bytes.data() + pos + 10, 2);
            std::memcpy(&sr, bytes.data() + pos + 12, 4);
            std::memcpy(&bps, bytes.data() + pos + 22, 2);
            if (fmt != 1) return false;
            channels = ch;
            src_rate = static_cast<int>(sr);
            bits = bps;
        } else if (std::memcmp(bytes.data() + pos, "data", 4) == 0) {
            data_off = pos + 8;
            data_len = chunk;
            break;
        }
        pos += 8 + chunk + (chunk & 1u);
    }
    if (channels != 1 || bits != 16 || src_rate <= 0 || data_off == 0 || data_len < 2) return false;
    if (data_off + data_len > bytes.size()) data_len = bytes.size() - data_off;
    const size_t count = data_len / 2;
    out.resize(count);
    for (size_t i = 0; i < count; ++i) {
        int16_t sample = 0;
        std::memcpy(&sample, bytes.data() + data_off + i * 2, 2);
        out[i] = static_cast<float>(sample) / 32768.0f;
    }
    rate_hz = src_rate;
    return true;
}

std::vector<float> resample_linear(const std::vector<float>& in, int src, int dst) {
    if (in.empty() || src <= 0 || dst <= 0 || src == dst) return in;
    const double ratio = static_cast<double>(src) / static_cast<double>(dst);
    const size_t out_n = static_cast<size_t>(in.size() / ratio);
    std::vector<float> out(out_n);
    for (size_t i = 0; i < out_n; ++i) {
        const double x = i * ratio;
        const size_t i0 = static_cast<size_t>(x);
        const size_t i1 = i0 + 1 < in.size() ? i0 + 1 : i0;
        const float t = static_cast<float>(x - i0);
        out[i] = in[i0] * (1.0f - t) + in[i1] * t;
    }
    return out;
}

struct Z3Session {
    zonos3_model model;
    bool has_model = false;
    zac_model zac;
    bool has_zac = false;
    spk_model spk;
    bool has_spk = false;
};

std::map<long long, std::unique_ptr<Z3Session>> g_sessions;
long long g_next_handle = 1;

long long create_session(const char* model_dir, const char* spk_encoder_path, char* err, int err_len) {
    auto session = std::make_unique<Z3Session>();
    const std::string dir = model_dir;

    const std::string backbone = join_path(dir, "backbone-ptq.gguf");
    if (!file_ok(backbone)) {
        set_error(err, err_len, "zonos3: backbone-ptq.gguf missing in model dir");
        return 0;
    }
    if (!zonos3_model_load(session->model, backbone.c_str(), /*use_gpu=*/false)) {
        set_error(err, err_len, "zonos3: backbone load failed");
        return 0;
    }
    session->has_model = true;

    const std::string zac_path = join_path(dir, "zac-keep2.gguf");
    if (file_ok(zac_path) && zac_load(session->zac, zac_path.c_str(), /*use_gpu=*/false)) {
        session->has_zac = true;
    } else {
        set_error(err, err_len, "zonos3: zac-keep2.gguf missing or failed to load");
    }

    const std::string spk_path = (spk_encoder_path != nullptr && *spk_encoder_path != '\0')
        ? std::string(spk_encoder_path)
        : join_path(dir, "spk-encoder.gguf");
    if (file_ok(spk_path) && spk_load(session->spk, spk_path.c_str(), /*use_gpu=*/false)) {
        session->has_spk = true;
    }

    std::lock_guard<std::mutex> lock(g_mtx);
    const long long handle = g_next_handle++;
    g_sessions[handle] = std::move(session);
    return handle;
}

void destroy_session(long long handle) {
    std::unique_ptr<Z3Session> session;
    {
        std::lock_guard<std::mutex> lock(g_mtx);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end()) return;
        session = std::move(it->second);
        g_sessions.erase(it);
    }
    if (session->has_spk) spk_free(session->spk);
    if (session->has_zac) zac_free(session->zac);
    if (session->has_model) zonos3_model_free(session->model);
}

#else

#endif  // PURR_ZONOS3_VENDORED (sessions)

}  // namespace

extern "C" {

#if defined(PURR_ZONOS3_VENDORED)

long long z3_create(const char* model_dir, const char* spk_encoder_path, const char* /*lib_dir*/,
                    int /*threads*/, char* err, int err_len) {
    if (model_dir == nullptr || *model_dir == '\0') {
        set_error(err, err_len, "zonos3: empty model dir");
        return 0;
    }
    return create_session(model_dir, spk_encoder_path, err, err_len);
}

float* z3_synthesize(long long handle, const char* text, const char* embedding_path,
                     const float* /*emotion_axes*/, float /*speaking_rate*/, const char* /*language*/,
                     int /*threads*/, int* out_len, char* err, int err_len) {
    if (out_len != nullptr) *out_len = 0;
    if (text == nullptr || *text == '\0') {
        set_error(err, err_len, "zonos3: empty text");
        return nullptr;
    }
    std::unique_ptr<Z3Session>* slot = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mtx);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end()) {
            set_error(err, err_len, "zonos3: invalid session");
            return nullptr;
        }
        slot = &it->second;
    }
    Z3Session& s = **slot;
    if (!s.has_zac) {
        set_error(err, err_len, "zonos3: zac-keep2.gguf missing — reinstall ROM-18");
        return nullptr;
    }

    zonos3_prompt_options opt;
    std::vector<float> embedding;
    if (embedding_path != nullptr && *embedding_path != '\0') {
        std::vector<int64_t> shape;
        if (!npy::load_f32(embedding_path, embedding, shape) ||
            embedding.size() != s.model.hp.speaker_dim) {
            set_error(err, err_len, "zonos3: speaker embedding npy unreadable");
            return nullptr;
        }
        opt.speaker_embedding = embedding.data();
    }
    opt.condition_buckets.assign(s.model.hp.cond_bucket_counts.begin(),
                                 s.model.hp.cond_bucket_counts.end()); // all unknown

    zonos3_prompt prompt;
    std::string prompt_error;
    if (!zonos3_build_prompt(s.model, text, opt, prompt, &prompt_error)) {
        set_error(err, err_len, prompt_error.empty() ? "zonos3: prompt build failed"
                                                     : prompt_error.c_str());
        return nullptr;
    }

    int budget = static_cast<int>(s.model.hp.n_ctx_train) - prompt.n_rows;
    if (budget < 16) {
        set_error(err, err_len, "zonos3: text leaves no room in the model context");
        return nullptr;
    }
    if (budget > 750) budget = 750;

    zonos3_sampling sampling;
    sampling.seed = static_cast<uint32_t>(std::time(nullptr)) ^ static_cast<uint32_t>(handle);
    std::vector<int32_t> codes;
    int eos_frame = -1;
    const int n_frames = zonos3_generate(s.model, prompt, budget, sampling, 1.0f, codes, eos_frame,
                                         /*use_kv=*/true);
    if (n_frames <= 0) {
        set_error(err, err_len, "zonos3: no frames generated");
        return nullptr;
    }

    std::vector<int32_t> stream = zonos3_prefill_codes(s.model, prompt);
    stream.insert(stream.end(), codes.begin(), codes.end());
    const int total = prompt.prefill_frames + n_frames;
    const int n_codebooks = static_cast<int>(s.model.hp.n_codebooks);

    std::vector<float> audio;
    if (!zac_decode(s.zac, stream.data(), total, n_codebooks, eos_frame, audio) || audio.empty()) {
        set_error(err, err_len, "zonos3: codec decode failed");
        return nullptr;
    }
    const size_t drop = prompt.prefill_frames > 0
        ? std::min(audio.size(),
                   static_cast<size_t>(prompt.prefill_frames) * zac_samples_per_frame(s.zac))
        : 0;
    if (drop > 0) audio.erase(audio.begin(), audio.begin() + drop);
    const float gain = zac_output_gain(s.zac, -1.0);
    for (float& sample : audio) sample *= gain;
    zonos3_soft_limit(audio.data(), audio.size());
    if (audio.empty()) {
        set_error(err, err_len, "zonos3: empty audio after decode");
        return nullptr;
    }

    float* out = static_cast<float*>(std::malloc(audio.size() * sizeof(float)));
    if (out == nullptr) {
        set_error(err, err_len, "zonos3: out of memory");
        return nullptr;
    }
    std::memcpy(out, audio.data(), audio.size() * sizeof(float));
    if (out_len != nullptr) *out_len = static_cast<int>(audio.size());
    return out;
}

int z3_encode_speaker(long long handle, const char* wav_path, const char* out_npy_path,
                      char* err, int err_len) {
    std::unique_ptr<Z3Session>* slot = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_mtx);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end()) {
            set_error(err, err_len, "zonos3: invalid session");
            return 0;
        }
        slot = &it->second;
    }
    Z3Session& s = **slot;
    if (!s.has_spk) {
        set_error(err, err_len, "zonos3: spk-encoder.gguf missing — mount SUB-18C");
        return 0;
    }
    std::vector<float> pcm;
    int rate = 0;
    if (!read_wav_pcm16_mono(wav_path != nullptr ? wav_path : "", pcm, rate) || pcm.empty()) {
        set_error(err, err_len, "zonos3: reference must be a 16-bit mono PCM WAV");
        return 0;
    }
    if (rate != 16000 && rate != 44100) {
        pcm = resample_linear(pcm, rate, 16000);
        rate = 16000;
    }
    std::vector<float> embedding;
    {
        std::lock_guard<std::mutex> lock(g_mtx); // spk scheduler is stateful: one at a time
        if (!spk_embed(s.spk, pcm.data(), static_cast<int64_t>(pcm.size()), rate, embedding)) {
            set_error(err, err_len, "zonos3: speaker encode failed");
            return 0;
        }
    }
    if (!npy::save_f32(out_npy_path != nullptr ? out_npy_path : "", embedding.data(),
                       {static_cast<int64_t>(embedding.size())})) {
        set_error(err, err_len, "zonos3: embedding save failed");
        return 0;
    }
    return 1;
}

#else

long long z3_create(const char* /*model_dir*/, const char* /*spk_encoder_path*/,
                    const char* /*lib_dir*/, int /*threads*/, char* err, int err_len) {
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

#endif  // PURR_ZONOS3_VENDORED

const char* z3_last_error(void) { return g_lastError.c_str(); }

#if defined(PURR_ZONOS3_VENDORED)
void z3_destroy(long long handle) { destroy_session(handle); }
#else
void z3_destroy(long long /*handle*/) {}
#endif

}  // extern "C"

