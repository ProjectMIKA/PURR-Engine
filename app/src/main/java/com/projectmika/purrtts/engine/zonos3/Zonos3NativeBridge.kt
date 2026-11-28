package com.projectmika.purrtts.engine.zonos3

import android.content.Context
import android.util.Log

/**
 * ✨ MIKA'S ZONOS3 NATIVE JNI BRIDGE (`libpurr_zonos3_jni.so`) ✨
 * Wraps the vendored Zyphra `zonos3.cpp` ggml/GGUF runtime (backbone-ptq.gguf +
 * zac-keep2.gguf ZAC-1 decoder + optional spk-encoder.gguf / steer.gguf).
 * Loads inside `runCatching` so pure-JVM unit tests stay safe; on an ABI without
 * the .so every call fails soft to the legacy engines — same contract as Qwen3.
 */
object Zonos3NativeBridge {

    private const val TAG = "PurrZonos3Bridge"

    val isLoaded: Boolean

    init {
        var ok = false
        runCatching {
            val deps = listOf("c++_shared", "ggml-base", "ggml-cpu", "ggml")
            for (lib in deps) {
                runCatching { System.loadLibrary(lib) }
            }
            System.loadLibrary("purr_zonos3_jni")
            ok = true
            Log.i(TAG, "Loaded native ZONOS3 ggml JNI stack")
        }.onFailure { err ->
            Log.w(TAG, "Zonos3 native libs unavailable in this process: ${err.message}")
        }
        isLoaded = ok
    }

    fun nativeLibDir(context: Context): String =
        context.applicationInfo?.nativeLibraryDir.orEmpty()

    external fun nativeCreate(modelDir: String, spkEncoderPath: String, nativeLibDir: String, nThreads: Int): Long

    /**
     * Synthesizes [text] with the 192-d speaker embedding at [voiceEmbeddingPath]
     * (empty = neutral speaker). [emotionAxes] are PURR's five sentiment axes
     * (joy, love, anger, sadness, flustered) mapped onto the steer.gguf directions.
     * Returns 44.1kHz mono float PCM, or null on failure.
     */
    external fun nativeSynthesize(
        handle: Long,
        text: String,
        voiceEmbeddingPath: String,
        emotionAxes: FloatArray,
        speakingRate: Float,
        languageHint: String,
        nThreads: Int
    ): FloatArray?

    /**
     * Encodes [wavPath] into a 192-d speaker embedding written to [outNpyPath]
     * (`.embedding.npy` compatible). Requires spk-encoder.gguf mounted.
     */
    external fun nativeEncodeSpeaker(handle: Long, wavPath: String, outNpyPath: String): Boolean

    external fun nativeGetLastError(handle: Long): String

    external fun nativeDestroy(handle: Long)
}
