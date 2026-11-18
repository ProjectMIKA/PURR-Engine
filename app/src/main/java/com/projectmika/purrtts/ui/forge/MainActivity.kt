package com.projectmika.purrtts.ui.forge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.projectmika.purrtts.ui.theme.MikaPurrTheme

/**
 * ✨ PURR-TTS MAIN FORGE ACTIVITY ✨
 * Serves as:
 * 1) Standalone PURR-TTS Studio & Voice Forge Launcher (`MAIN`)
 * 2) Android System TTS Settings activity (`CONFIGURE_ENGINE`)
 * 3) Voice Manager activity (`MANAGE_VOICES`)
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INITIAL_TAB = "com.projectmika.purrtts.extra.INITIAL_TAB"
    }

    private val viewModel: TtsForgeViewModel by viewModels()

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.showToast(
            if (granted) "Mic permission granted for 5s Voice Cloning!"
            else "Mic permission denied — voice cloning limited to WAV import"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        val initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, 0)

        setContent {
            MikaPurrTheme {
                TtsForgeScreen(
                    viewModel = viewModel,
                    initialTabIndex = initialTab,
                    onOpenSystemTtsSettings = { openAndroidSystemTtsSettings() }
                )
            }
        }
    }

    private fun openAndroidSystemTtsSettings() {
        val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure {
            viewModel.showToast("Open Settings > Accessibility > Text-to-speech output")
        }
    }
}
