cmake_minimum_required(VERSION 3.22.1)
project(purr_qwen3_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(THIRD_PARTY_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party")
set(LLAMA_DIR "${THIRD_PARTY_DIR}/llama.cpp")
set(LUNAVOX_SRC_DIR "${THIRD_PARTY_DIR}/lunavox/src")
set(ORT_DIR "${THIRD_PARTY_DIR}/onnxruntime")

if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp sources missing at ${LLAMA_DIR}")
endif()
if(NOT EXISTS "${LUNAVOX_SRC_DIR}/lunavox_c_api.cpp")
    message(FATAL_ERROR "LunaVox sources missing at ${LUNAVOX_SRC_DIR}")
endif()
if(NOT EXISTS "${ORT_DIR}/include/onnxruntime_cxx_api.h")
    message(FATAL_ERROR "ONNX Runtime headers missing at ${ORT_DIR}/include")
endif()

# ✨ MIKA'S ADRENO OPENCL GPU OFFLOAD (v1.8.2) ✨
# Builds the OpenCL backend next to ggml-cpu so the Qwen3 talker/predictor can offload to the
# Adreno GPU when the user's "Qwen3 GPU Offload" toggle is on (profile qwen3GpuOffload →
# n_gpu_layers -1; OFF stays n_gpu_layers 0 = byte-identical CPU behavior). Fail-soft by law:
# unsupported tensor types and devices without a vendor libOpenCL.so simply fall back to CPU.
# Android NDK has no OpenCL SDK: pre-seed CMake's FindOpenCL with vendored Khronos headers plus
# a link-only stub libOpenCL.so generated at configure time (NEVER packaged — at runtime the
# device's vendor libOpenCL.so resolves, and is skipped gracefully when absent).
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/opencl_headers/CL/cl.h")
    message(FATAL_ERROR "OpenCL headers missing at ${CMAKE_CURRENT_SOURCE_DIR}/opencl_headers/CL (vendored KhronosGroup/OpenCL-Headers)")
endif()
set(OPENCL_STUB_DIR "${CMAKE_CURRENT_BINARY_DIR}/opencl_stub")
file(MAKE_DIRECTORY "${OPENCL_STUB_DIR}")
file(WRITE "${OPENCL_STUB_DIR}/opencl_stub.c" "static int purr_opencl_stub_guard;\n")
# Compile the stub with the SAME target triple + sysroot the toolchain uses (the NDK keeps
# --target/--sysroot in CMAKE_C_COMPILER_TARGET / CMAKE_SYSROOT, NOT in CMAKE_C_FLAGS —
# a plain compiler invocation would emit a host-arch ELF that the link step rejects).
set(_purr_stub_cmd ${CMAKE_C_COMPILER})
if(CMAKE_C_COMPILER_TARGET)
    list(APPEND _purr_stub_cmd "--target=${CMAKE_C_COMPILER_TARGET}")
endif()
if(CMAKE_SYSROOT)
    list(APPEND _purr_stub_cmd "--sysroot=${CMAKE_SYSROOT}")
endif()
list(APPEND _purr_stub_cmd -shared -fPIC -o "${OPENCL_STUB_DIR}/libOpenCL.so" "${OPENCL_STUB_DIR}/opencl_stub.c")
execute_process(
    COMMAND ${_purr_stub_cmd}
    RESULT_VARIABLE _opencl_stub_result
)
if(NOT _opencl_stub_result EQUAL 0)
    message(FATAL_ERROR "Failed to build the OpenCL link stub (see compiler output above)")
endif()
set(OpenCL_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/opencl_headers" CACHE PATH "" FORCE)
set(OpenCL_LIBRARY "${OPENCL_STUB_DIR}/libOpenCL.so" CACHE FILEPATH "" FORCE)
set(GGML_OPENCL ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)

# Configure llama.cpp cleanly for Android NDK (no OpenMP, OpenSSL, or tools)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_TESTS_INSTALL OFF CACHE BOOL "" FORCE)
set(LLAMA_TOOLS_INSTALL OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_DIR}" llama_build EXCLUDE_FROM_ALL)

# Strip .so.<version> suffixes so Android APK packaging & dlopen load unversioned .so files cleanly
foreach(_tgt IN ITEMS llama ggml ggml-base ggml-cpu ggml-opencl)
    if(TARGET ${_tgt})
        set_target_properties(${_tgt} PROPERTIES VERSION "" SOVERSION "")
    endif()
endforeach()

# ✨ OpenCL symbols resolve at RUNTIME from the device's vendor libOpenCL.so (see the stub note
# above). The NDK's default `-Wl,--no-undefined` would reject them at link time — allow undefined
# for this backend only; a device without a usable libOpenCL.so makes the backend dlopen fail and
# ggml simply skips it (fail-soft to CPU, doctrine law #1).
if(TARGET ggml-opencl)
    target_link_options(ggml-opencl PRIVATE -Wl,-z,undefs)
endif()

# Import unified libonnxruntime.so (v1.31.1 from onnxruntime-android AAR)
add_library(onnxruntime_android SHARED IMPORTED)
set_target_properties(onnxruntime_android PROPERTIES
    IMPORTED_LOCATION "${ORT_DIR}/jni/${ANDROID_ABI}/libonnxruntime.so"
    INTERFACE_INCLUDE_DIRECTORIES "${ORT_DIR}/include")

find_package(Threads REQUIRED)

add_library(purr_qwen3_jni SHARED
    "${CMAKE_CURRENT_SOURCE_DIR}/purr_qwen3_jni.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/provider_policy_android.cpp"
    "${LUNAVOX_SRC_DIR}/assets_manager.cpp"
    "${LUNAVOX_SRC_DIR}/audio_decoder.cpp"
    "${LUNAVOX_SRC_DIR}/audio_io.cpp"
    "${LUNAVOX_SRC_DIR}/llama_wrapper.cpp"
    "${LUNAVOX_SRC_DIR}/logger.cpp"
    "${LUNAVOX_SRC_DIR}/lunavox_c_api.cpp"
    "${LUNAVOX_SRC_DIR}/lunavox_engine.cpp"
    "${LUNAVOX_SRC_DIR}/nvml_monitor.cpp"
    "${LUNAVOX_SRC_DIR}/platform_utils.cpp"
    "${LUNAVOX_SRC_DIR}/talker_predictor_llama.cpp"
    "${LUNAVOX_SRC_DIR}/text_tokenizer.cpp"
)

target_include_directories(purr_qwen3_jni PRIVATE
    "${LUNAVOX_SRC_DIR}"
    "${ORT_DIR}/include"
)

target_link_libraries(purr_qwen3_jni PRIVATE
    llama
    onnxruntime_android
    Threads::Threads
    dl
    log
    m
)

# =============================================================================
# ✨ MIKA'S ZONOS3 NATIVE ENGINE (v2.8.0) — real build when sources are vendored ✨
# third_party/zonos3 ← sparse checkout of github.com/Zyphra/ZONOS3-Mini
# (servers/ggml + model/src/zonos3/registries; ggml submodule inited by CI).
# Synthesis path (mirrors zonos3-cli): zonos3_build_prompt → zonos3_generate →
# zac_decode → gain. NO Rust textnorm / espeak / server-core needed for this path.
# ggml is REUSED from the llama.cpp build above (b8470) — one ggml in the APK, no
# symbol clash. If the pinned SHAs ever diverge on a ggml API, fall back to a
# renamed static copy of the zonos3 ggml submodule.
# Absent sources → stub adapter, same fail-soft contract as before.
# =============================================================================
set(ZONOS3_ROOT "${THIRD_PARTY_DIR}/zonos3")
set(Z3 "${ZONOS3_ROOT}/servers/ggml")

add_library(purr_zonos3_jni SHARED
    "${CMAKE_CURRENT_SOURCE_DIR}/zonos/purr_zonos3_jni.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/zonos/zonos3_engine_adapter.cpp"
)
target_include_directories(purr_zonos3_jni PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/zonos")
target_link_libraries(purr_zonos3_jni PRIVATE Threads::Threads dl log m)
set_target_properties(purr_zonos3_jni PROPERTIES VERSION "" SOVERSION "")

if(EXISTS "${Z3}/src/zonos3.h")
    message(STATUS "Zonos3 sources found at ${Z3} — building real engine")
    target_compile_definitions(purr_zonos3_jni PRIVATE PURR_ZONOS3_VENDORED=1)

    # Embeds a repository JSON registry as generated/<header> (upstream helper).
    function(purr_z3_embed_json source symbol header)
        set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${source})
        file(READ ${source} _hex HEX)
        string(LENGTH "${_hex}" _hex_len)
        math(EXPR _len "${_hex_len} / 2")
        string(REGEX REPLACE "([0-9a-f][0-9a-f])" "0x\\1," _bytes "${_hex}")
        set(ZONOS3_EMBED_SOURCE ${source})
        set(ZONOS3_EMBED_SYMBOL ${symbol})
        set(ZONOS3_EMBED_LEN ${_len})
        set(ZONOS3_EMBED_BYTES ${_bytes})
        configure_file("${Z3}/src/embed-json.h.in"
            "${CMAKE_CURRENT_BINARY_DIR}/zonos3_gen/${header}" @ONLY)
    endfunction()
    file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/zonos3_gen")
    purr_z3_embed_json("${ZONOS3_ROOT}/model/src/zonos3/registries/languages.json"
        zonos3_languages_json zonos3-languages-json.h)
    purr_z3_embed_json("${ZONOS3_ROOT}/model/src/zonos3/registries/generation.json"
        zonos3_generation_json zonos3-generation-json.h)
    purr_z3_embed_json("${ZONOS3_ROOT}/model/src/zonos3/registries/short_text.json"
        zonos3_short_text_json zonos3-short-text-json.h)

    add_library(purr_z3_text STATIC "${Z3}/src/zonos3-text.cpp")
    target_include_directories(purr_z3_text PUBLIC "${Z3}/src")

    # zonos3's PINNED ggml is newer than llama's b8470 — prebuilt per-ABI by the workflow
    # (scripts/fetch_zonos_sources.sh --build-ggml), hidden visibility so the static ggml
    # never clashes with the packaged libggml.so at runtime.
    set(Z3_GGML_PREBUILD "${ZONOS3_ROOT}/ggml-ndk-build/${ANDROID_ABI}")
    set(Z3_GGML_BASE "${Z3_GGML_PREBUILD}/src/libggml-base.a")
    set(Z3_GGML_CPU "${Z3_GGML_PREBUILD}/src/libggml-cpu.a")
    set(Z3_GGML_TOP "${Z3_GGML_PREBUILD}/src/libggml.a")
    if(NOT EXISTS "${Z3_GGML_BASE}" OR NOT EXISTS "${Z3_GGML_CPU}" OR NOT EXISTS "${Z3_GGML_TOP}")
        message(FATAL_ERROR "zonos3 pinned ggml prebuild missing for ${ANDROID_ABI} at ${Z3_GGML_PREBUILD}/src — run scripts/fetch_zonos_sources.sh --build-ggml")
    endif()
    add_library(purr_z3_ggml_base STATIC IMPORTED GLOBAL)
    set_target_properties(purr_z3_ggml_base PROPERTIES IMPORTED_LOCATION "${Z3_GGML_BASE}"
        INTERFACE_INCLUDE_DIRECTORIES "${Z3}/ggml/include")
    add_library(purr_z3_ggml_cpu STATIC IMPORTED GLOBAL)
    set_target_properties(purr_z3_ggml_cpu PROPERTIES IMPORTED_LOCATION "${Z3_GGML_CPU}"
        INTERFACE_INCLUDE_DIRECTORIES "${Z3}/ggml/include")
    add_library(purr_z3_ggml_top STATIC IMPORTED GLOBAL)
    set_target_properties(purr_z3_ggml_top PROPERTIES IMPORTED_LOCATION "${Z3_GGML_TOP}"
        INTERFACE_INCLUDE_DIRECTORIES "${Z3}/ggml/include")
    add_library(purr_z3_ggml INTERFACE)
    target_link_libraries(purr_z3_ggml INTERFACE purr_z3_ggml_cpu purr_z3_ggml_base purr_z3_ggml_top)

    add_library(purr_z3_lmdb STATIC "${Z3}/vendor/lmdb/mdb.c" "${Z3}/vendor/lmdb/midl.c")
    target_include_directories(purr_z3_lmdb PUBLIC "${Z3}/vendor/lmdb")
    target_link_libraries(purr_z3_lmdb PUBLIC Threads::Threads)
    target_compile_options(purr_z3_lmdb PRIVATE -w)

    add_library(purr_z3_core STATIC
        "${Z3}/src/zonos3.cpp" "${Z3}/src/zonos3-graph.cpp" "${Z3}/src/zonos3-sampler.cpp"
        "${Z3}/src/zonos3-prompt.cpp" "${Z3}/src/zonos3-tokenizer.cpp" "${Z3}/src/zonos3-steer.cpp"
        "${Z3}/src/zonos3-languages.cpp" "${Z3}/src/zonos3-generation.cpp"
        "${Z3}/src/zonos3-phonemes.cpp" "${Z3}/src/zonos3-laci.cpp"
        "${Z3}/src/zonos3-span-follow.cpp" "${Z3}/src/zonos3-short-text.cpp"
        "${Z3}/src/zonos3-accent.cpp" "${Z3}/src/zonos3-edit.cpp")
    target_include_directories(purr_z3_core PUBLIC "${Z3}/src" "${Z3}/ggml/include"
        PRIVATE "${Z3}/vendor" "${CMAKE_CURRENT_BINARY_DIR}/zonos3_gen")
    target_link_libraries(purr_z3_core PUBLIC purr_z3_ggml purr_z3_text purr_z3_lmdb ${CMAKE_DL_LIBS})

    add_library(purr_z3_audio STATIC
        "${Z3}/src/zac.cpp" "${Z3}/src/zac-encoder.cpp" "${Z3}/src/ssl-encoder.cpp"
        "${Z3}/src/spk-encoder.cpp" "${Z3}/src/audio-decode.cpp" "${Z3}/src/wav.cpp")
    target_include_directories(purr_z3_audio PUBLIC "${Z3}/src" "${Z3}/ggml/include" PRIVATE "${Z3}/vendor")
    target_link_libraries(purr_z3_audio PUBLIC purr_z3_ggml purr_z3_text)

    target_link_libraries(purr_zonos3_jni PRIVATE purr_z3_core purr_z3_audio c++_shared)
else()
    message(STATUS "Zonos3 sources not vendored — building stub adapter (fail-soft)")
endif()

add_library(purr_zonos1_jni SHARED
    "${CMAKE_CURRENT_SOURCE_DIR}/zonos/purr_zonos1_jni.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/zonos/zonos1_engine_adapter.cpp"
)
target_include_directories(purr_zonos1_jni PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/zonos")
target_link_libraries(purr_zonos1_jni PRIVATE Threads::Threads dl log m)
set_target_properties(purr_zonos1_jni PROPERTIES VERSION "" SOVERSION "")
if(EXISTS "${ZONOS1_ROOT}")
    message(STATUS "Zonos1 sources found at ${ZONOS1_ROOT} — enabling real runtime adapter")
    target_compile_definitions(purr_zonos1_jni PRIVATE PURR_ZONOS1_VENDORED=1)
    target_include_directories(purr_zonos1_jni PRIVATE "${ZONOS1_ROOT}")
    target_link_libraries(purr_zonos1_jni PRIVATE zonos1 ggml ggml-base ggml-cpu)
else()
    message(STATUS "Zonos1 sources not vendored — building stub adapter (fail-soft)")
endif()
