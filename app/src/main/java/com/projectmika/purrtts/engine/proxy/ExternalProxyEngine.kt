package com.projectmika.purrtts.engine.proxy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.projectmika.purrtts.core.purr.BackendType
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.VoiceDescriptor
import com.projectmika.purrtts.core.purr.VoiceGender
import com.projectmika.purrtts.engine.PurrSynthesizer
import com.projectmika.purrtts.engine.SynthesisOutcome
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * ✨ MIKA'S BUILT-IN DEVICE SPEECH BRIDGE (`CORE_OS`) & LAN PROXY ENGINE ✨
 *
 * Delivers 100% coherent spoken audio out of the box (zero model download required) by routing
 * PURR-processed chunks to either:
 * 1) A LAN / local OpenAI `/v1/audio/speech` endpoint (when configured), OR
 * 2) The device's native Android TTS engine (e.g. Google Speech Services / Samsung TTS, never
 *    self-binding to `com.projectmika.purrtts`) via `synthesizeToFile` + robust RIFF chunk parsing,
 *    or direct `speak()` fallback during in-app auditioning.
 */
class ExternalProxyEngine(
    private val context: Context
) : PurrSynthesizer {

    companion object {
        private const val TAG = "PurrCoreOsSpeech"
        private const val SAMPLE_RATE_HZ = 24000
        const val SYS_VOICE_PREFIX = "core_os_sys:"
    }

    @Serializable
    private data class OpenAiSpeechRequest(
        val model: String,
        val input: String,
        val voice: String,
        val speed: Float = 1.0f,
        val response_format: String = "pcm"
    )

    override val backendType: BackendType = BackendType.CORE_OS

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
        val pkg = discoverFallbackEnginePackage(profile.proxyConfig.fallbackSystemEnginePackage)
        if (!pkg.isNullOrBlank()) {
            ensureExternalTtsReady(pkg)
        }
    }

    /**
     * Discovers all real voices exposed by the device's built-in TTS engine (e.g. Google TTS)
     * so users can choose their preferred system voice in Tab 1 (`> VOICE & ENGINE`).
     */
    suspend fun discoverSystemVoices(preferredPkg: String = ""): List<VoiceDescriptor> = withContext(Dispatchers.IO) {
        val targetPkg = discoverFallbackEnginePackage(preferredPkg) ?: return@withContext emptyList()
        val tts = ensureExternalTtsReady(targetPkg) ?: return@withContext emptyList()
        val rawVoices = runCatching { tts.voices?.toList().orEmpty() }.getOrDefault(emptyList())

        rawVoices
            .filter { !it.isNetworkConnectionRequired }
            .sortedWith(compareBy({ !it.locale.language.equals("en", ignoreCase = true) }, { it.name }))
            .take(40)
            .map { v ->
                val tag = v.locale.toLanguageTag().ifBlank { "en-US" }
                val lowerName = v.name.lowercase()
                val gender = when {
                    lowerName.contains("male") && !lowerName.contains("female") -> VoiceGender.MALE
                    lowerName.contains("-iom") || lowerName.contains("-tpd") -> VoiceGender.MALE
                    else -> VoiceGender.FEMALE
                }
                val shortLabel = v.name
                    .removePrefix("en-us-x-")
                    .removePrefix("en-gb-x-")
                    .uppercase()
                VoiceDescriptor(
                    id = "$SYS_VOICE_PREFIX${v.name}",
                    displayName = "System (${v.locale.displayLanguage}) // $shortLabel",
                    engine = BackendType.CORE_OS,
                    cartridgeId = "core_os",
                    gender = gender,
                    languageCode = tag,
                    nativeTags = listOf("built-in", "coherent", v.locale.language.lowercase())
                )
            }
    }

    override suspend fun synthesizeChunk(
        text: String,
        voiceId: String,
        speakingRate: Float,
        pitch: Float,
        profile: PurrProfile
    ): SynthesisOutcome = withContext(Dispatchers.IO) {
        val proxy = profile.proxyConfig

        if (profile.preferredBackend == BackendType.EXTERNAL_PROXY &&
            proxy.useOpenAiEndpoint &&
            proxy.openAiBaseUrl.isNotBlank()
        ) {
            _statusFlow.value = HardwareStatusBadge.PROXY_LINK
            val remoteSamples = runCatching {
                fetchOpenAiPcm(
                    endpointUrl = proxy.openAiBaseUrl,
                    apiKey = proxy.openAiApiKey,
                    model = proxy.openAiModel,
                    voice = if (voiceId.startsWith("proxy_") || voiceId.startsWith("core_os")) proxy.openAiVoice else voiceId,
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

        val enginePackage = discoverFallbackEnginePackage(proxy.fallbackSystemEnginePackage)
        if (!enginePackage.isNullOrBlank()) {
            _statusFlow.value = HardwareStatusBadge.CORE_OS
            val sysVoiceName = voiceId.removePrefix(SYS_VOICE_PREFIX).takeIf { voiceId.startsWith(SYS_VOICE_PREFIX) }
            val systemSamples = synthesizeViaExternalAndroidEngine(
                enginePackage = enginePackage,
                voiceName = sysVoiceName,
                text = text,
                speed = speakingRate,
                pitch = pitch
            )
            if (systemSamples != null && systemSamples.isNotEmpty()) {
                return@withContext SynthesisOutcome(
                    pcmFloat24k = systemSamples,
                    sampleRateHz = SAMPLE_RATE_HZ,
                    hardwareBadge = HardwareStatusBadge.CORE_OS,
                    engineSummary = "CORE_OS Speech ($enginePackage)"
                )
            }
        }

        SynthesisOutcome(
            pcmFloat24k = FloatArray(0),
            sampleRateHz = SAMPLE_RATE_HZ,
            hardwareBadge = HardwareStatusBadge.IDLE,
            engineSummary = "No secondary OS TTS engine available — install a ROM Cartridge in Tab 3",
            isFallbackNeuralPreview = true
        )
    }

    /**
     * Direct speaker playback used by the Forge Test Bench when `synthesizeToFile` is blocked
     * by an OEM TTS provider or no offline ROM cartridge is slotted yet.
     */
    suspend fun speakDirectAudition(
        text: String,
        voiceId: String,
        speed: Float,
        pitch: Float,
        preferredPkg: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val pkg = discoverFallbackEnginePackage(preferredPkg) ?: return@withContext false
        val tts = ensureExternalTtsReady(pkg) ?: return@withContext false
        if (voiceId.startsWith(SYS_VOICE_PREFIX)) {
            val targetName = voiceId.removePrefix(SYS_VOICE_PREFIX)
            tts.voices?.firstOrNull { it.name == targetName }?.let { v ->
                runCatching { tts.voice = v }
            }
        }
        withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine { cont ->
                val utteranceId = UUID.randomUUID().toString()
                tts.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
                tts.setPitch(pitch.coerceIn(0.6f, 1.6f))
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
                val res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
                if (res != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    fun stopDirectAudition() {
        runCatching { externalSystemTts?.stop() }
    }

    override suspend fun evictResources() {
        runCatching { externalSystemTts?.shutdown() }
        externalSystemTts = null
        boundPackage = null
        _statusFlow.value = HardwareStatusBadge.IDLE
    }

    private fun discoverFallbackEnginePackage(configuredPkg: String): String? {
        val selfPkg = context.packageName
        if (configuredPkg.isNotBlank() && configuredPkg != selfPkg) {
            return configuredPkg
        }

        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val services = runCatching {
            context.packageManager.queryIntentServices(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrDefault(emptyList())

        val candidates = services
            .mapNotNull { it.serviceInfo?.packageName }
            .filter { it.isNotBlank() && it != selfPkg }
            .distinct()

        return candidates.firstOrNull { it == "com.google.android.tts" }
            ?: candidates.firstOrNull { it == "com.samsung.SMT" }
            ?: candidates.firstOrNull()
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
            return decodeWavOrRawPcm24k(bytes)
        }
    }

    private suspend fun synthesizeViaExternalAndroidEngine(
        enginePackage: String,
        voiceName: String?,
        text: String,
        speed: Float,
        pitch: Float
    ): FloatArray? {
        if (enginePackage.isBlank() || enginePackage == context.packageName) return null
        val tts = ensureExternalTtsReady(enginePackage) ?: return null
        val tempWav = File(context.cacheDir, "coreos_${UUID.randomUUID()}.wav")

        return try {
            if (!voiceName.isNullOrBlank()) {
                tts.voices?.firstOrNull { it.name == voiceName }?.let { v ->
                    runCatching { tts.voice = v }
                }
            } else {
                runCatching { tts.language = Locale.US }
            }

            val ok = withTimeoutOrNull(12_000L) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val utteranceId = UUID.randomUUID().toString()
                    tts.setSpeechRate(speed.coerceIn(0.5f, 2.0f))
                    tts.setPitch(pitch.coerceIn(0.6f, 1.6f))
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
                    val res = tts.synthesizeToFile(text, Bundle(), tempWav, utteranceId)
                    if (res != TextToSpeech.SUCCESS && cont.isActive) {
                        cont.resume(false)
                    }
                }
            } ?: false

            if (!ok || !tempWav.exists() || tempWav.length() <= 44L) return null
            decodeWavOrRawPcm24k(tempWav.readBytes())
        } catch (err: Exception) {
            Log.w(TAG, "System TTS synthesizeToFile failed: ${err.message}")
            null
        } finally {
            runCatching { tempWav.delete() }
        }
    }

    /**
     * Parses RIFF/WAVE `"fmt "` and `"data"` chunks accurately regardless of extra metadata chunks,
     * downmixes to mono, and resamples to 24,000 Hz float32.
     */
    private fun decodeWavOrRawPcm24k(bytes: ByteArray): FloatArray? {
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
        ) {
            var offset = 12
            var channels = 1
            var sampleRate = SAMPLE_RATE_HZ
            var bitsPerSample = 16
            var dataStart = -1
            var dataLen = 0

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            while (offset + 8 <= bytes.size) {
                val id = String(bytes, offset, 4, Charsets.US_ASCII)
                val size = buf.getInt(offset + 4).coerceAtLeast(0)
                val bodyStart = offset + 8
                if (id == "fmt " && bodyStart + 16 <= bytes.size) {
                    channels = buf.getShort(bodyStart + 2).toInt().coerceAtLeast(1)
                    sampleRate = buf.getInt(bodyStart + 4).coerceAtLeast(8000)
                    bitsPerSample = buf.getShort(bodyStart + 14).toInt().coerceAtLeast(8)
                } else if (id == "data" && bodyStart <= bytes.size) {
                    dataStart = bodyStart
                    dataLen = minOf(size, bytes.size - bodyStart)
                    break
                }
                offset = bodyStart + size + (size and 1)
            }

            if (dataStart < 0 || dataLen <= 0) return null
            val payload = bytes.copyOfRange(dataStart, dataStart + dataLen)
            val rawFloat = when (bitsPerSample) {
                32 -> {
                    val fBuf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
                    FloatArray(payload.size / 4) { fBuf.float.coerceIn(-1f, 1f) }
                }
                else -> PurrDspProcessor.pcm16LeToFloat32(payload)
            }
            val mono = if (channels > 1) {
                FloatArray(rawFloat.size / channels) { i ->
                    var acc = 0f
                    for (c in 0 until channels) acc += rawFloat[i * channels + c]
                    acc / channels
                }
            } else {
                rawFloat
            }
            return PurrDspProcessor.resampleMono(mono, sampleRate, SAMPLE_RATE_HZ)
        }
        return PurrDspProcessor.pcm16LeToFloat32(bytes)
    }

    private suspend fun ensureExternalTtsReady(pkg: String): TextToSpeech? {
        if (externalSystemTts != null && boundPackage == pkg) return externalSystemTts
        runCatching { externalSystemTts?.shutdown() }
        externalSystemTts = null
        boundPackage = null

        return withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { cont ->
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
}
