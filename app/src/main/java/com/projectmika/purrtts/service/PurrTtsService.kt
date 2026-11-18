package com.projectmika.purrtts.service

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import com.projectmika.purrtts.PurrApplication
import com.projectmika.purrtts.core.purr.PurrChunk
import com.projectmika.purrtts.core.purr.PurrDspProcessor
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrProfileRepository
import com.projectmika.purrtts.core.purr.PurrTextProcessor
import com.projectmika.purrtts.core.purr.PurrVoiceMatcher
import com.projectmika.purrtts.engine.TtsBackendRouter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * ✨ SYSTEM-WIDE ANDROID PURR-TTS SERVICE (`android.speech.tts.TextToSpeechService`) ✨
 *
 * Crash-safe implementation:
 * - Initializes [repository] and [backendRouter] BEFORE calling `super.onCreate()` (AOSP
 *   invokes `onLoadLanguage` inside `super.onCreate()`!).
 * - Overrides `onLoadVoice(voiceName)` so Android OS voice switches succeed cleanly instead of
 *   rejecting custom voice IDs as malformed Locale tags.
 * - Guards all Locale ISO3 / language conversions and never leaks uncaught exceptions on Binder threads.
 */
class PurrTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "PurrTtsService"
        const val SAMPLE_RATE_HZ = 24000
    }

    @Volatile
    private var repository: PurrProfileRepository? = null

    @Volatile
    private var backendRouter: TtsBackendRouter? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isStopped: Boolean = false

    @Volatile
    private var activeSynthesisJob: Job? = null

    @Volatile
    private var currentLocale: Locale = Locale.US

    private fun ensureDependencies(): Boolean {
        if (repository != null && backendRouter != null) return true
        val app = applicationContext as? PurrApplication ?: return false
        repository = app.repository
        backendRouter = app.backendRouter
        return true
    }

    override fun onCreate() {
        // Must run BEFORE super.onCreate() because AOSP calls onLoadLanguage synchronously inside super.onCreate()
        ensureDependencies()
        super.onCreate()
        Log.i(TAG, "[BOOT] PURR-TTS system service initialized cleanly.")
    }

    override fun onDestroy() {
        isStopped = true
        activeSynthesisJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        ensureDependencies()
        val repo = repository ?: return TextToSpeech.LANG_AVAILABLE
        val code = normalizeLangTag(lang, country)
        val voices = repo.allAvailableVoices()
        val hasExact = voices.any { it.languageCode.equals(code, ignoreCase = true) }
        val hasLang = voices.any {
            it.languageCode.substringBefore('-').equals(code.substringBefore('-'), ignoreCase = true)
        }
        return when {
            hasExact && !variant.isNullOrBlank() -> TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            hasExact -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            hasLang -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE // Allow English fallback cleanly for OS callers
        }
    }

    override fun onGetLanguage(): Array<String> {
        val lang = runCatching { currentLocale.isO3Language }.getOrDefault("eng")
        val country = runCatching { currentLocale.isO3Country }.getOrDefault("USA")
        return arrayOf(lang, country, currentLocale.variant.orEmpty())
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        ensureDependencies()
        val status = onIsLanguageAvailable(lang, country, variant)
        if (status >= TextToSpeech.LANG_AVAILABLE) {
            currentLocale = runCatching {
                Locale.forLanguageTag(normalizeLangTag(lang, country))
            }.getOrDefault(Locale.US)
        }
        return status
    }

    override fun onGetVoices(): List<Voice> {
        if (!ensureDependencies()) return emptyList()
        val repo = repository ?: return emptyList()
        val profile = repo.currentProfile

        // Only expose voices belonging to CORE_OS or currently SLOTTED ROM cartridges
        val eligible = repo.allAvailableVoices().filter { v ->
            repo.isCartridgeSlotted(v.cartridgeId)
        }
        val voices = eligible.ifEmpty { repo.allAvailableVoices().take(1) }

        return voices.mapNotNull { v ->
            runCatching {
                val locale = Locale.forLanguageTag(v.languageCode)
                val customTags = profile.customVoiceTags[v.id].orEmpty()
                val features = (v.nativeTags + customTags + listOf("purr-engine", v.engine.name.lowercase())).toSet()
                Voice(
                    v.id,
                    locale,
                    Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_VERY_LOW,
                    false,
                    features
                )
            }.getOrNull()
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (!ensureDependencies() || voiceName.isNullOrBlank()) return TextToSpeech.ERROR
        val exists = repository?.allAvailableVoices()?.any { it.id == voiceName } == true
        return if (exists) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onLoadVoice(voiceName: String?): Int {
        if (!ensureDependencies() || voiceName.isNullOrBlank()) return TextToSpeech.ERROR
        val voice = repository?.allAvailableVoices()?.firstOrNull { it.id == voiceName }
            ?: return TextToSpeech.ERROR
        currentLocale = runCatching { Locale.forLanguageTag(voice.languageCode) }.getOrDefault(Locale.US)
        return TextToSpeech.SUCCESS
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        if (!ensureDependencies()) return "core_os_auto"
        val repo = repository ?: return "core_os_auto"
        val profile = repo.currentProfile
        if (profile.primaryVoiceId.isNotBlank()) return profile.primaryVoiceId
        val targetLang = normalizeLangTag(lang, country).substringBefore('-')
        val matched = PurrVoiceMatcher.assignVoice(
            voices = repo.allAvailableVoices().filter { repo.isCartridgeSlotted(it.cartridgeId) },
            profile = profile,
            preferredLanguage = targetLang
        )
        return matched?.id ?: "core_os_auto"
    }

    override fun onStop() {
        isStopped = true
        activeSynthesisJob?.cancel()
        backendRouter?.markIdle()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (callback == null) return
        if (request == null || !ensureDependencies()) {
            runCatching { callback.error() }
            return
        }

        val repo = repository ?: run { callback.error(); return }
        val router = backendRouter ?: run { callback.error(); return }

        val rawText = request.charSequenceText?.toString().orEmpty()
        if (rawText.isBlank()) {
            if (callback.start(SAMPLE_RATE_HZ, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) {
                callback.done()
            }
            return
        }

        isStopped = false
        val profile = resolveEffectiveProfile(repo, request)
        val osRateMultiplier = (request.speechRate.coerceIn(25, 400) / 100.0f)
        val osPitchMultiplier = (request.pitch.coerceIn(25, 400) / 100.0f)

        val chunks = PurrTextProcessor.process(
            rawText = rawText,
            profile = profile,
            customRules = repo.state.value.customPhoneticRules
        )

        val useFloat32 = profile.dspConfig.useFloat32Callback
        val audioEncoding = if (useFloat32) {
            AudioFormat.ENCODING_PCM_FLOAT
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }

        if (callback.start(SAMPLE_RATE_HZ, audioEncoding, 1) != TextToSpeech.SUCCESS) {
            callback.error()
            return
        }

        if (chunks.isEmpty()) {
            callback.done()
            return
        }

        try {
            runBlocking(serviceScope.coroutineContext) {
                activeSynthesisJob = coroutineContext[Job]

                for (chunk in chunks) {
                    if (isStopped) break

                    when (chunk) {
                        is PurrChunk.Silence -> {
                            router.emitSilenceTelemetry(chunk.durationMs, chunk.reason)
                            val silenceBytes = if (useFloat32) {
                                PurrDspProcessor.createSilenceFloat32Le(SAMPLE_RATE_HZ, chunk.durationMs)
                            } else {
                                PurrDspProcessor.createSilencePcm16Le(SAMPLE_RATE_HZ, chunk.durationMs)
                            }
                            streamToCallback(silenceBytes, callback)
                        }

                        is PurrChunk.Speech -> {
                            val outcome = router.synthesizeSpeechChunk(
                                chunk = chunk,
                                profile = profile,
                                osRateMultiplier = osRateMultiplier,
                                osPitchMultiplier = osPitchMultiplier
                            )
                            if (isStopped) break

                            if (outcome.pcmFloat24k.isNotEmpty()) {
                                val pcmBytes = if (useFloat32) {
                                    PurrDspProcessor.float32ToByteArrayLe(outcome.pcmFloat24k)
                                } else {
                                    PurrDspProcessor.float32ToPcm16Le(outcome.pcmFloat24k)
                                }
                                streamToCallback(pcmBytes, callback)
                            }
                        }
                    }
                }
            }
            callback.done()
        } catch (_: CancellationException) {
            runCatching { callback.done() }
        } catch (err: Exception) {
            Log.e(TAG, "[SYNTH_ERR] Synthesis failed cleanly: ${err.message}", err)
            runCatching { callback.error() }
        } finally {
            activeSynthesisJob = null
            router.markIdle()
        }
    }

    private fun streamToCallback(bytes: ByteArray, callback: SynthesisCallback) {
        if (bytes.isEmpty() || isStopped) return
        val maxPacket = callback.maxBufferSize.coerceAtLeast(1024)
        var offset = 0
        while (offset < bytes.size && !isStopped) {
            val count = minOf(maxPacket, bytes.size - offset)
            val status = callback.audioAvailable(bytes, offset, count)
            if (status != TextToSpeech.SUCCESS) {
                isStopped = true
                break
            }
            offset += count
        }
    }

    private fun resolveEffectiveProfile(repo: PurrProfileRepository, request: SynthesisRequest): PurrProfile {
        val base = repo.currentProfile
        val requestedVoice = request.voiceName
        if (!requestedVoice.isNullOrBlank() && requestedVoice != base.primaryVoiceId) {
            val match = repo.allAvailableVoices().firstOrNull { it.id == requestedVoice }
            if (match != null && repo.isCartridgeSlotted(match.cartridgeId)) {
                return base.copy(
                    primaryVoiceId = requestedVoice,
                    preferredBackend = match.engine,
                    selectedCartridgeId = match.cartridgeId
                )
            }
        }
        return base
    }

    private fun normalizeLangTag(lang: String?, country: String?): String {
        val iso2Lang = when (lang?.lowercase()) {
            "eng", "en" -> "en"
            "jpn", "ja" -> "ja"
            "zho", "chi", "zh" -> "zh"
            "spa", "es" -> "es"
            "fra", "fre", "fr" -> "fr"
            "hin", "hi" -> "hi"
            "ita", "it" -> "it"
            "por", "pt" -> "pt"
            else -> lang?.take(2)?.lowercase()?.takeIf { it.all(Char::isLetter) } ?: "en"
        }
        val iso2Country = when (country?.uppercase()) {
            "USA", "US" -> "US"
            "GBR", "UK", "GB" -> "GB"
            "JPN", "JP" -> "JP"
            "CHN", "CN" -> "CN"
            "ESP", "ES" -> "ES"
            "FRA", "FR" -> "FR"
            "IND", "IN" -> "IN"
            "ITA", "IT" -> "IT"
            "BRA", "BR" -> "BR"
            else -> if (iso2Lang == "en") "US" else ""
        }
        return if (iso2Country.isNotEmpty()) "$iso2Lang-$iso2Country" else iso2Lang
    }
}
