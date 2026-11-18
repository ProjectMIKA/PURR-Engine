package com.projectmika.purrtts.engine.proxy

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.engine.PurrSynthesizer
import com.projectmika.purrtts.engine.SynthesisOutcome
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * ✨ MIKA'S FALLBACK / PASSTHROUGH PROXY ENGINE ✨
 * Routes PURR-processed chunks (already stripped of emojis, expanded for slang/honorifics,
 * and split at MIKASTOP silence boundaries) to either:
 * 1) A LAN / local OpenAI-compatible `/v1/audio/speech` TTS endpoint (`response_format=pcm`), OR
 * 2) A secondary installed Android OS TextToSpeech engine via `synthesizeToFile`, decoded back to
 *    24kHz float32 PCM so PurrDspProcessor and exact MIKASTOP silence buffers remain intact.
 */
class ExternalProxyEngine(
    private val context: Context
) : PurrSynthesizer {

    companion object {
        private const val TAG = "PurrProxyTTS"
        private const val SAMPLE_RATE_HZ = 24000
    }

    @Serializable
    private data class OpenAiSpeechRequest(
        val model: String,
        val input: String,
        val voice: String,
        val speed: Float = 1.0f,
        val response_format: String = "pcm"
    )

    override val backendType: BackendType = BackendType.EXTERNAL_PROXY

    private val _statusFlow = MutableStateFlow(HardwareStatusBadge.IDLE)
    override val statusFlow: StateFlow<HardwareStatusBadge> = _statusFlow.asStateFlow()

    override val isModelReady: Boolean = true

    private val json = Json { encodeDefaults = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private var externalSystemTts: TextToSpeech? = null
    private var boundPackage: String? = null

    override suspend fun warmup(profile: PurrProfile) {
        _statusFlow.value = HardwareStatusBadge.PROXY_LINK
    }

    override suspend fun synthesizeChunk(
        text: String,
        voiceId: String,
        speakingRate: Float,
        pitch: Float,
        profile: PurrProfile
    ): SynthesisOutcome = withContext(Dispatchers.IO) {
        _statusFlow.value = HardwareStatusBadge.PROXY_LINK
        val proxy = profile.proxyConfig

        if (proxy.useOpenAiEndpoint && proxy.openAiBaseUrl.isNotBlank()) {
            val remoteSamples = runCatching {
                fetchOpenAiPcm(
                    endpointUrl = proxy.openAiBaseUrl,
                    apiKey = proxy.openAiApiKey,
                    model = proxy.openAiModel,
                    voice = if (voiceId.startsWith("proxy_")) proxy.openAiVoice else voiceId,
                    input = text,
                    speed = speakingRate
                )
            }.getOrNull()

            if (remoteSamples != null && remoteSamples.isNotEmpty()) {
                return@withContext SynthesisOutcome(
                    pcmFloat24k = remoteSamples,
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.PROXY_LINK,
                    engineSummary = "OpenAI TTS Proxy (${proxy.openAiBaseUrl})"
                )
            }
        }

        // Secondary fallback: delegate synthesis to an external Android TTS package
        val systemSamples = synthesizeViaExternalAndroidEngine(
            enginePackage = proxy.fallbackSystemEnginePackage,
            text = text,
            speed = speakingRate,
            pitch = pitch
        )

        if (systemSamples != null && systemSamples.isNotEmpty()) {
            return@withContext SynthesisOutcome(
                pcmFloat24k = systemSamples,
                sampleRateHz = SAMPLE_RATE_HZ,
                hardwareBadge = HardwareStatusBadge.PROXY_LINK,
                engineSummary = "OS TTS Passthrough (${proxy.fallbackSystemEnginePackage})"
            )
        }

        SynthesisOutcome(
            pcmFloat24k = FloatArray(0),
            sampleRateHz = SAMPLE_RATE_HZ,
            hardwareBadge = HardwareStatusBadge.IDLE,
            engineSummary = "Proxy unreachable — check LAN endpoint or external engine",
            isFallbackNeuralPreview = true
        )
    }

    override suspend fun evictResources() {
        runCatching { externalSystemTts?.shutdown() }
        externalSystemTts = null
        boundPackage = null
        _statusFlow.value = HardwareStatusBadge.IDLE
    }

    private fun fetchOpenAiPcm(
        endpointUrl: String,
        apiKey: String,
        model: String,
        voice: String,
        input: String,
        speed: Float
    ): FloatArray? {
        val bodyJson = json.encodeToString(
            OpenAiSpeechRequest(
                model = model,
                input = input,
                voice = voice,
                speed = speed.coerceIn(0.25f, 4.0f),
                response_format = "pcm"
            )
        )
        val reqBuilder = Request.Builder()
            .url(endpointUrl)
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            if (bytes.size < 4) return null

            // Handle WAV header automatically if server ignores `pcm` and returns `RIFF...`
            return if (bytes.size > 44 &&
                bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()
            ) {
                val hdr = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
                val sr = hdr.getInt(24).coerceAtLeast(8000)
                val pcm = PurrDspProcessor.pcm16LeToFloat32(bytes.copyOfRange(44, bytes.size))
                PurrDspProcessor.resampleMono(pcm, sr, SAMPLE_RATE_HZ)
            } else {
                // OpenAI raw PCM is 24kHz 16-bit signed little-endian mono
                PurrDspProcessor.pcm16LeToFloat32(bytes)
            }
        }
    }

    private suspend fun synthesizeViaExternalAndroidEngine(
        enginePackage: String,
        text: String,
        speed: Float,
        pitch: Float
    ): FloatArray? {
        if (enginePackage.isBlank() || enginePackage == context.packageName) return null

        val tts = ensureExternalTtsReady(enginePackage) ?: return null
        val tempWav = File(context.cacheDir, "proxy_${UUID.randomUUID()}.wav")

        return try {
            val ok = suspendCancellableCoroutine<Boolean> { cont ->
                val utteranceId = UUID.randomUUID().toString()
                tts.setSpeechRate(speed)
                tts.setPitch(pitch)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) {
                        if (id == utteranceId && cont.isActive) cont.resume(true)
                    }
                    @Deprecated("Deprecated in Android API")
                    override fun onError(id: String?) {
                        if (id == utteranceId && cont.isActive) cont.resume(false)
                    }
                    override fun onError(id: String?, errorCode: Int) {
                        if (id == utteranceId && cont.isActive) cont.resume(false)
                    }
                })
                val res = tts.synthesizeToFile(text, null, tempWav, utteranceId)
                if (res != TextToSpeech.SUCCESS && cont.isActive) {
                    cont.resume(false)
                }
            }
            if (!ok || !tempWav.exists() || tempWav.length() <= 44L) return null
            val bytes = tempWav.readBytes()
            val hdr = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
            val sr = hdr.getInt(24).coerceAtLeast(8000)
            val pcm = PurrDspProcessor.pcm16LeToFloat32(bytes.copyOfRange(44, bytes.size))
            PurrDspProcessor.resampleMono(pcm, sr, SAMPLE_RATE_HZ)
        } catch (err: Exception) {
            Log.w(TAG, "External TTS passthrough failed: ${err.message}")
            null
        } finally {
            runCatching { tempWav.delete() }
        }
    }

    private suspend fun ensureExternalTtsReady(pkg: String): TextToSpeech? {
        if (externalSystemTts != null && boundPackage == pkg) return externalSystemTts
        runCatching { externalSystemTts?.shutdown() }
        externalSystemTts = null
        boundPackage = null

        return suspendCancellableCoroutine { cont ->
            var engine: TextToSpeech? = null
            engine = TextToSpeech(context, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    externalSystemTts = engine
                    boundPackage = pkg
                    if (cont.isActive) cont.resume(engine)
                } else {
                    runCatching { engine?.shutdown() }
                    if (cont.isActive) cont.resume(null)
                }
            }, pkg)
        }
    }
}
