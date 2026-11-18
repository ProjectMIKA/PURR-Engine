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
 * Exposes the GachaSwipe P.U.R.R. pipeline (slang/phonetic expansion, `*action*` vs `"dialogue"`
 * routing, deterministic MIKASTOP PCM silence injection, sentiment-driven prosody, and 24kHz DSP)
 * to every Android application via Android's standard TTS binder contract.
 *
 * Streams 24,000 Hz mono PCM16-LE (or Float32-LE when `useFloat32Callback` is enabled) buffers
 * respecting `callback.maxBufferSize` and instant `onStop()` cancellation.
 */
class PurrTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "PurrTtsService"
        const val SAMPLE_RATE_HZ = 24000
    }

    private lateinit var repository: PurrProfileRepository
    private lateinit var backendRouter: TtsBackendRouter

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isStopped: Boolean = false

    @Volatile
    private var activeSynthesisJob: Job? = null

    @Volatile
    private var currentLocale: Locale = Locale.US

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as PurrApplication
        repository = app.repository
        backendRouter = app.backendRouter
        Log.i(TAG, "[BOOT] PURR-TTS system service initialized.")
    }

    override fun onDestroy() {
        isStopped = true
        activeSynthesisJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val code = normalizeLangTag(lang, country)
        val hasExact = repository.allAvailableVoices().any { it.languageCode.equals(code, ignoreCase = true) }
        val hasLang = repository.allAvailableVoices().any {
            it.languageCode.substringBefore('-').equals(code.substringBefore('-'), ignoreCase = true)
        }
        return when {
            hasExact -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            hasLang -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf(
            currentLocale.isO3Language,
            currentLocale.isO3Country,
            currentLocale.variant
        )
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val status = onIsLanguageAvailable(lang, country, variant)
        if (status >= TextToSpeech.LANG_AVAILABLE) {
            currentLocale = Locale.forLanguageTag(normalizeLangTag(lang, country))
        }
        return status
    }

    override fun onGetVoices(): List<Voice> {
        val profile = repository.currentProfile
        val voices = repository.allAvailableVoices()
            .filterNot { it.id in profile.blacklist }
            .let { list ->
                if (profile.whitelist.isNotEmpty()) {
                    list.filter { it.id in profile.whitelist }.ifEmpty { list }
                } else {
                    list
                }
            }

        return voices.map { v ->
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
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (voiceName.isNullOrBlank()) return TextToSpeech.ERROR
        val exists = repository.allAvailableVoices().any { it.id == voiceName }
        return if (exists) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String {
        val profile = repository.currentProfile
        val targetLang = normalizeLangTag(lang, country).substringBefore('-')
        val matched = PurrVoiceMatcher.assignVoice(
            voices = repository.allAvailableVoices(),
            profile = profile,
            preferredLanguage = targetLang
        )
        return matched?.id ?: profile.primaryVoiceId
    }

    override fun onStop() {
        isStopped = true
        activeSynthesisJob?.cancel()
        backendRouter.markIdle()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) {
            callback?.error()
            return
        }

        val rawText = request.charSequenceText?.toString().orEmpty()
        if (rawText.isBlank()) {
            callback.start(SAMPLE_RATE_HZ, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        isStopped = false
        val profile = resolveEffectiveProfile(request)
        val osRateMultiplier = (request.speechRate.coerceIn(25, 400) / 100.0f)
        val osPitchMultiplier = (request.pitch.coerceIn(25, 400) / 100.0f)

        val chunks = PurrTextProcessor.process(
            rawText = rawText,
            profile = profile,
            customRules = repository.state.value.customPhoneticRules
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

        try {
            runBlocking(serviceScope.coroutineContext) {
                val job = coroutineContext[Job]
                activeSynthesisJob = job

                for (chunk in chunks) {
                    if (isStopped) break

                    when (chunk) {
                        is PurrChunk.Silence -> {
                            backendRouter.emitSilenceTelemetry(chunk.durationMs, chunk.reason)
                            val silenceBytes = if (useFloat32) {
                                PurrDspProcessor.createSilenceFloat32Le(SAMPLE_RATE_HZ, chunk.durationMs)
                            } else {
                                PurrDspProcessor.createSilencePcm16Le(SAMPLE_RATE_HZ, chunk.durationMs)
                            }
                            streamToCallback(silenceBytes, callback)
                        }

                        is PurrChunk.Speech -> {
                            val outcome = backendRouter.synthesizeSpeechChunk(
                                chunk = chunk,
                                profile = profile,
                                osRateMultiplier = osRateMultiplier,
                                osPitchMultiplier = osPitchMultiplier
                            )
                            if (isStopped) break

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

            if (!isStopped) {
                callback.done()
            } else {
                callback.done()
            }
        } catch (_: CancellationException) {
            callback.done()
        } catch (err: Exception) {
            Log.e(TAG, "[SYNTH_ERR] Failed synthesis: ${err.message}", err)
            callback.error()
        } finally {
            activeSynthesisJob = null
            backendRouter.markIdle()
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

    private fun resolveEffectiveProfile(request: SynthesisRequest): PurrProfile {
        val base = repository.currentProfile
        val requestedVoice = request.voiceName
        if (!requestedVoice.isNullOrBlank() && requestedVoice != base.primaryVoiceId) {
            val known = repository.allAvailableVoices().any { it.id == requestedVoice }
            if (known) {
                return base.copy(primaryVoiceId = requestedVoice)
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
            else -> lang?.take(2)?.lowercase() ?: "en"
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
