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
separate_arguments(_purr_c_flags NATIVE_COMMAND "${CMAKE_C_FLAGS}")
execute_process(
    COMMAND ${CMAKE_C_COMPILER} ${_purr_c_flags} -shared -fPIC -o "${OPENCL_STUB_DIR}/libOpenCL.so" "${OPENCL_STUB_DIR}/opencl_stub.c"
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
