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
foreach(_tgt IN ITEMS llama ggml ggml-base ggml-cpu)
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
