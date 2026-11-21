package com.projectmika.purrtts.engine.qwen3

import android.content.Context
import android.util.Log

/**
 * ✨ MIKA'S QWEN3-TTS NATIVE JNI BRIDGE (`libpurr_qwen3_jni.so`) ✨
 * Links ggml/llama.cpp b8470 + Lux-Luna/LunaVox C++ runtime + ONNX Runtime 1.31.1.
 * Loads native libraries cleanly inside `runCatching` so pure-JVM unit tests stay safe.
 */
object Qwen3NativeBridge {

    private const val TAG = "PurrQwen3Bridge"

    val isLoaded: Boolean

    init {
        var ok = false
        runCatching {
            val deps = listOf(
                "c++_shared",
                "onnxruntime",
                "ggml-base",
                "ggml-cpu",
                "ggml",
                "llama"
            )
            for (lib in deps) {
                runCatching { System.loadLibrary(lib) }
            }
            System.loadLibrary("purr_qwen3_jni")
            ok = true
            Log.i(TAG, "Loaded native Qwen3-TTS JNI stack (llama.cpp + LunaVox + ORT)")
        }.onFailure { err ->
            Log.w(TAG, "Qwen3 native libs unavailable in this process: ${err.message}")
        }
        isLoaded = ok
    }

    fun nativeLibDir(context: Context): String =
        context.applicationInfo?.nativeLibraryDir.orEmpty()

    external fun nativeCreate(modelDir: String, nativeLibDir: String, nThreads: Int, nGpuLayers: Int): Long

    external fun nativeSynthesizeCustom(
        handle: Long,
        text: String,
        speaker: String,
        instruct: String,
        languageId: Int,
        nThreads: Int
    ): FloatArray?

    external fun nativeSynthesizeClone(
        handle: Long,
        text: String,
        refWavPath: String,
        refText: String,
        languageId: Int,
        nThreads: Int
    ): FloatArray?

    external fun nativeSynthesizeDesign(
        handle: Long,
        text: String,
        instruct: String,
        languageId: Int,
        nThreads: Int
    ): FloatArray?

    external fun nativeGetLastError(handle: Long): String

    external fun nativeDestroy(handle: Long)
}
