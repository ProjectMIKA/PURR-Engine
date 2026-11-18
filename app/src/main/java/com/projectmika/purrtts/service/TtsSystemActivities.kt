package com.projectmika.purrtts.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.projectmika.purrtts.PurrApplication
import com.projectmika.purrtts.R
import com.projectmika.purrtts.ui.forge.MainActivity

/**
 * Responds to Android OS `android.speech.tts.engine.CHECK_VOICE_DATA` with standard
 * ISO-3 formatted locale descriptors (`eng-USA`, `eng-GBR`, `jpn-JPN`, etc.) so AOSP
 * Settings never rejects malformed tags.
 */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as? PurrApplication
        val voices = ArrayList<String>().apply {
            val rawLangs = app?.repository?.allAvailableVoices()
                ?.map { toTtsCheckCode(it.languageCode) }
                ?.distinct()
                .orEmpty()
            if (rawLangs.isNotEmpty()) {
                addAll(rawLangs)
            } else {
                add("eng-USA")
                add("eng-GBR")
            }
        }
        val resultIntent = Intent().apply {
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, voices)
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf())
        }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, resultIntent)
        finish()
    }

    private fun toTtsCheckCode(langTag: String): String = when (langTag.lowercase()) {
        "en-us" -> "eng-USA"
        "en-gb" -> "eng-GBR"
        "ja-jp" -> "jpn-JPN"
        "zh-cn" -> "zho-CHN"
        "es-es" -> "spa-ESP"
        "fr-fr" -> "fra-FRA"
        "hi-in" -> "hin-IND"
        "it-it" -> "ita-ITA"
        "pt-br" -> "por-BRA"
        else -> "eng-USA"
    }
}

/**
 * Responds to Android OS `android.speech.tts.engine.GET_SAMPLE_TEXT`.
 */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val language = intent?.getStringExtra("language") ?: "eng"
        val sample = if (language.startsWith("jpn", ignoreCase = true) || language.startsWith("ja", ignoreCase = true)) {
            getString(R.string.sample_text_ja)
        } else {
            getString(R.string.sample_text_en)
        }
        val resultIntent = Intent().apply {
            putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, sample)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}

/**
 * Opens the PURR Cartridge Bay tab when Android Settings requests `INSTALL_TTS_DATA`.
 */
class DownloadVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forgeIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_INITIAL_TAB, 2)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(forgeIntent)
        finish()
    }
}
