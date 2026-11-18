package com.projectmika.purrtts.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.projectmika.purrtts.PurrApplication
import com.projectmika.purrtts.R
import com.projectmika.purrtts.ui.forge.MainActivity

/**
 * Responds to Android OS `android.speech.tts.engine.CHECK_VOICE_DATA` by advertising all
 * PURR Engine voices in `CHECK_VOICE_DATA_PASS`.
 */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as PurrApplication
        val voices = ArrayList(
            app.repository.allAvailableVoices()
                .map { it.languageCode.replace('-', '_') }
                .distinct()
        )
        val resultIntent = Intent().apply {
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, voices)
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf())
        }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, resultIntent)
        finish()
    }
}

/**
 * Responds to Android OS `android.speech.tts.engine.GET_SAMPLE_TEXT` with an expressive
 * PURR-friendly sample string showcasing stutters, pauses, and character inflection.
 */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val language = intent.getStringExtra("language") ?: "eng"
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
 * Opens the PURR Forge Model & Voice Catalog tab when Android Settings requests
 * `android.speech.tts.engine.INSTALL_TTS_DATA`.
 */
class DownloadVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forgeIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_INITIAL_TAB, 2) // Open Model & Voice Catalog tab
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(forgeIntent)
        finish()
    }
}
