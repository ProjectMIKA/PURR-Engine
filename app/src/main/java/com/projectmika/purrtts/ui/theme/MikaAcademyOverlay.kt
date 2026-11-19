/**
 * ✨ MIKA'S ACADEMY OVERLAY (v1.5.0) — the GachaSwipe tutorial, ported natively ✨
 * - Full-screen CRT stage (scanlines + scan band via [MikaTerminalStage], VoidBg).
 * - **Milk & Wire** (`res/raw/milk_and_wire.mp3`) loops as BGM with a 2.5s volume fade-in;
 *   released cleanly on dispose. Starts with the overlay (no autoplay gate natively).
 * - MIKA narrates each slide with a typewriter effect + blinking block cursor in the step accent.
 * - The `VERSION_PATCHNOTES` slide renders the gold version pill and the [PURR_WHATS_NEW]
 *   highlight cards; `[SKIP_ALL >>]` in the top-right exits at any time.
 *
 * Note: `step.iconGlyph` emojis are intentional CONTENT parity with GachaSwipe's tutorial
 * slides (the emoji ban in this skin applies to UI chrome, not narrated slide artwork).
 */
package com.projectmika.purrtts.ui.theme

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmika.purrtts.R
import com.projectmika.purrtts.core.purr.PURR_TUTORIAL_STEPS
import com.projectmika.purrtts.core.purr.PURR_WHATS_NEW
import com.projectmika.purrtts.core.purr.PurrTutorialStep
import kotlinx.coroutines.delay


@Composable
fun MikaAcademyOverlay(
    versionName: String,
    startAtPatchnotes: Boolean,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val steps = remember {
        if (startAtPatchnotes) {
            PURR_TUTORIAL_STEPS.filterNot { it.firstLaunchOnly }
        } else {
            PURR_TUTORIAL_STEPS
        }
    }
    var stepIndex by remember {
        mutableIntStateOf(
            if (startAtPatchnotes) {
                steps.indexOfFirst { it.id == "VERSION_PATCHNOTES" }.coerceAtLeast(0)
            } else 0
        )
    }
    val step: PurrTutorialStep = steps[stepIndex.coerceIn(0, steps.lastIndex)]
    val isLast = stepIndex == steps.lastIndex
    val progress = (stepIndex + 1f) / steps.size

    // Typewriter narration (resets per slide).
    var typedChars by remember(step.id) { mutableIntStateOf(0) }
    LaunchedEffect(step.id) {
        typedChars = 0
        for (i in 1..step.body.length) {
            typedChars = i
            delay(13)
        }
    }
    val typingDone = typedChars >= step.body.length

    // 🎵 Milk & Wire BGM: loop + fade-in, hard-release on dispose.
    val bgm = remember {
        runCatching {
            MediaPlayer.create(context, R.raw.milk_and_wire)?.apply {
                isLooping = true
                setVolume(0f, 0f)
            }
        }.getOrNull()
    }
    DisposableEffect(Unit) {
        runCatching { bgm?.start() }
        onDispose { runCatching { bgm?.stop(); bgm?.release() } }
    }
    LaunchedEffect(Unit) {
        val fadeSteps = 25
        for (i in 1..fadeSteps) {
            val v = i / fadeSteps.toFloat() * 0.32f
            bgm?.setVolume(v, v)
            delay(100)
        }
    }

    val stepColor = Color(step.colorHex)

    Box(modifier = Modifier.fillMaxSize()) {
        MikaTerminalStage {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                // Top bar: brand stamp + SKIP_ALL
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "> MIKA_ACADEMY // ROM-04",
                        color = MikaColors.NeonCyan.copy(alpha = 0.65f),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "[ SKIP_ALL >> ]",
                        color = MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onFinish)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(0.5f))

                // Slide body: glyph, title, typewriter narration, patchnotes panel.
                Column(
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = step.iconGlyph,
                        fontSize = 34.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Text(
                        text = "> ${step.title}",
                        color = stepColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 17.sp,
                        letterSpacing = 1.0.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = step.body.take(typedChars),
                        color = MikaColors.CyanWhite.copy(alpha = 0.88f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 20.sp
                    )
                    if (!typingDone) {
                        Text(
                            text = "▊",
                            color = stepColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp
                        )
                    }

                    // ✨ VERSION PATCHNOTES PANEL — gold pill + WHATS_NEW highlight cards.
                    if (step.id == "VERSION_PATCHNOTES" && typingDone) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFFFD700).copy(alpha = 0.10f))
                                .border(1.dp, Color(0xFFFFD700), RoundedCornerShape(6.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "PURR-TTS v$versionName",
                                color = Color(0xFFFFD700),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                letterSpacing = 1.2.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "> HIGHLIGHTS",
                            color = Color(0xFFFFD700).copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            letterSpacing = 1.0.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        PURR_WHATS_NEW.forEach { entry ->
                            val entryColor = Color(entry.colorHex)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MikaColors.VoidBg.copy(alpha = 0.5f))
                                    .border(1.dp, entryColor.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = "${entry.iconGlyph}  ${entry.title}",
                                    color = entryColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.8.sp
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = entry.body,
                                    color = MikaColors.CyanWhite.copy(alpha = 0.78f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.5.sp,
                                    lineHeight = 15.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Progress + counter + NEXT button.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${stepIndex + 1}/${steps.size}",
                        color = MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        color = stepColor,
                        trackColor = stepColor.copy(alpha = 0.15f),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(stepColor.copy(alpha = 0.18f))
                        .border(1.dp, stepColor, RoundedCornerShape(8.dp))
                        .clickable {
                            if (isLast) onFinish() else stepIndex++
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isLast) "[ LET'S PURR ~ 💜 ]" else "[ NEXT > ]",
                        color = stepColor,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 1.0.sp
                    )
                }
            }
        }
    }
}
