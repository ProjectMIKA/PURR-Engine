package com.projectmika.purrtts.engine.zonos1

import android.content.Context
import android.util.Log

/**
 * ✨ MIKA'S ZONOS1 NATIVE JNI BRIDGE (`libpurr_zonos1_jni.so`) ✨
 * Wraps the vendored Zyphra `zonos1.cpp` runtime — the ENTIRE Zonos-v0.1 pipeline in
 * native C++: eSpeak-ng phonemization → prefix conditioner → 1.6B dense transformer
 * (classifier-free guidance) → DAC-44kHz vocoder, plus the ResNet293 speaker encoder.
 * Loads inside `runCatching`; absent libs fail soft, same contract as Zonos3/Qwen3.
 */
object Zonos1NativeBridge {

    private const val TAG = "PurrZonos1Bridge"

    val isLoaded: Boolean

    init {
        var ok = false
        runCatching {
            val deps = listOf("c++_shared", "ggml-base", "ggml-cpu", "ggml")
            for (lib in deps) {
                runCatching { System.loadLibrary(lib) }
            }
            System.loadLibrary("purr_zonos1_jni")
            ok = true
            Log.i(TAG, "Loaded native ZONOS1 ggml JNI stack")
        }.onFailure { err ->
            Log.w(TAG, "Zonos1 native libs unavailable in this process: ${err.message}")
        }
        isLoaded = ok
    }

    fun nativeLibDir(context: Context): String =
        context.applicationInfo?.nativeLibraryDir.orEmpty()

    external fun nativeCreate(
        modelDir: String,
        altBackbonePath: String,
        nativeLibDir: String,
        nThreads: Int
    ): Long

    /**
     * Synthesizes [text] with a speaker identity from [referencePath] — either a
     * saved 128-d speaker embedding (`.npy`) or a raw reference clip (wav/mp3).
     * [emotionAxes] = PURR's five sentiment axes → Zonos conditioning vector.
     * Returns 44kHz mono float PCM, or null on failure.
     */
    external fun nativeSynthesize(
        handle: Long,
        text: String,
        referencePath: String,
        emotionAxes: FloatArray,
        speakingRate: Float,
        languageHint: String,
        nThreads: Int
    ): FloatArray?

    /** Encodes [wavPath] into a 128-d speaker embedding written to [outNpyPath]. */
    external fun nativeEncodeSpeaker(handle: Long, wavPath: String, outNpyPath: String): Boolean

    external fun nativeGetLastError(handle: Long): String

    external fun nativeDestroy(handle: Long)
}
