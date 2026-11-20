#include "provider_policy.h"

#include <cpu_provider_factory.h>
#include <onnxruntime_cxx_api.h>

#include <exception>
#include <string>
#include <vector>

namespace lunavox {

std::vector<ProviderCandidate> build_provider_chain(ort_session_role role) {
    const bool is_decoder = (role == ort_session_role::decoder);
    ProviderCandidate cpu;
    cpu.name = "CPUExecutionProvider";
    // Use ORT_ENABLE_BASIC on Android ARM64 CPU EP so Level 2/3 layout transformers
    // (specifically NhwcTransformer -> NhwcFusedConv) do not rewrite FP16 Conv nodes in
    // qwen3_tts_speaker_encoder.fp16.onnx / codec_encoder.fp16.onnx / decoder.fp16.onnx
    // into unsupported NhwcFused kernels.
    cpu.opt_level = GraphOptimizationLevel::ORT_ENABLE_BASIC;
    // Decoder graph is sizable; disable memory arena/pattern on decoder to contain mobile RSS.
    cpu.disable_mem_arena = is_decoder;
    cpu.disable_mem_pattern = is_decoder;
    cpu.disable_spinning = is_decoder;
    cpu.apply = [is_decoder](Ort::SessionOptions & opts, std::string & err) -> bool {
        try {
            opts.AppendExecutionProvider_CPU(/*use_arena=*/is_decoder ? 0 : 1);
            return true;
        } catch (const std::exception & e) {
            err = std::string("CPU EP error: ") + e.what();
            return false;
        }
    };
    return {cpu};
}

} // namespace lunavox
