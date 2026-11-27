package com.projectmika.purrtts.ui.forge

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.projectmika.purrtts.core.manga.MangaCloneHandoffTarget
import com.projectmika.purrtts.core.purr.AcousticEffectPreset
import com.projectmika.purrtts.core.purr.ArchetypeTone
import com.projectmika.purrtts.core.purr.CommaHandlingMode
import com.projectmika.purrtts.core.purr.ExportedAudioFile
import com.projectmika.purrtts.core.purr.PurrChunk
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.core.purr.PurrNeuralBypass
import com.projectmika.purrtts.core.purr.PurrProfile
import com.projectmika.purrtts.core.purr.PurrTextProcessor
import com.projectmika.purrtts.core.purr.PurrThemeEngine
import com.projectmika.purrtts.core.purr.PurrThemeMode
import com.projectmika.purrtts.core.purr.RuleCategory
import com.projectmika.purrtts.core.purr.SymbolShieldConfig
import com.projectmika.purrtts.ui.theme.CyberDropdown
import com.projectmika.purrtts.ui.theme.CyberIconType
import com.projectmika.purrtts.ui.theme.ExpandableTerminalSection
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaCyberIcon
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaOsModuleIcon
import com.projectmika.purrtts.ui.theme.MikaTagPill
import com.projectmika.purrtts.ui.theme.OsModuleIconType
import com.projectmika.purrtts.ui.theme.bringIntoViewWithIme
import kotlin.math.roundToInt

private enum class ActiveOsModule(
    val toolCartridgeId: String,
    val title: String,
    val subtitle: String,
    val badge: String,
    val iconType: OsModuleIconType,
    private val accentResolver: () -> Color
) {
    PURR_ENGINE(
        toolCartridgeId = "tool_purr_engine",
        title = "P.U.R.R. ENGINE",
        subtitle = "Vibe Presets, Slang, MIKASTOP & Rulebook",
        badge = "TOOL-01",
        iconType = OsModuleIconType.PURR_CORE,
        accentResolver = { MikaColors.NeonPink }
    ),
    DSP_RACK(
        toolCartridgeId = "tool_dsp_rack",
        title = "ACOUSTIC DSP RACK",
        subtitle = "120ms Tail Taper, EQ & 10 Voice FX",
        badge = "TOOL-02",
        iconType = OsModuleIconType.DSP_RACK,
        accentResolver = { MikaColors.NeonCyan }
    ),
    SYMBOL_SHIELD(
        toolCartridgeId = "tool_symbol_shield",
        title = "SYMBOL & CHUNK SHIELD",
        subtitle = "Audiobook Sanitizer & Loose Chunks",
        badge = "TOOL-03",
        iconType = OsModuleIconType.SYMBOL_SHIELD,
        accentResolver = { MikaColors.TerminalGreen }
    ),
    CLONE_STUDIO(
        toolCartridgeId = "tool_clone_studio",
        title = "VOICE CLONE STUDIO",
        subtitle = "3-12s Zero-Shot Recorder & Clones",
        badge = "CLN-05",
        iconType = OsModuleIconType.CLONE_STUDIO,
        accentResolver = { MikaColors.NeonPurple }
    ),
    AUDIO_EXPORTER(
        toolCartridgeId = "tool_audio_exporter",
        title = "AUDIO MEDIA EXPORTER",
        subtitle = "Text/Audiobook -> Mastered WAV",
        badge = "TOOL-04",
        iconType = OsModuleIconType.AUDIO_EXPORTER,
        accentResolver = { MikaColors.SsrGold }
    ),
    VOICE_DESIGN(
        toolCartridgeId = "tool_voice_design",
        title = "QWEN3 VOICEDESIGN STUDIO",
        subtitle = "1.7B Structured Prompt Lab",
        badge = "TOOL-05",
        iconType = OsModuleIconType.VOICE_DESIGN,
        accentResolver = { MikaColors.NeonPink }
    ),
    AUDIOBOOK_CONSTRUCTOR(
        toolCartridgeId = "tool_audiobook_constructor",
        title = "AUDIOBOOK CONSTRUCTOR",
        subtitle = "EPUB/PDF/TXT -> Chaptered .M4B",
        badge = "TOOL-06",
        iconType = OsModuleIconType.AUDIOBOOK_CONSTRUCTOR,
        accentResolver = { MikaColors.NeonCyan }
    ),
    THEME_ENGINE(
        toolCartridgeId = "tool_theme_engine",
        title = "NEON THEME ENGINE",
        subtitle = "Retro / Neon Glow Skin & Contrast",
        badge = "TOOL-07",
        iconType = OsModuleIconType.THEME_ENGINE,
        accentResolver = { MikaColors.NeonCyan }
    ),
    SCRIPT_STUDIO(
        toolCartridgeId = "tool_script_studio",
        title = "SCRIPT & PODCAST STUDIO",
        subtitle = "Multi-Voice Cast & Scene WAV",
        badge = "TOOL-08",
        iconType = OsModuleIconType.SCRIPT_STUDIO,
        accentResolver = { MikaColors.NeonPink }
    ),
    VOICE_BENCHMARK(
        toolCartridgeId = "tool_voice_benchmark",
        title = "VOICE A/B BENCHMARK",
        subtitle = "RTF Meter & Phonetic Stress Lab",
        badge = "TOOL-09",
        iconType = OsModuleIconType.VOICE_BENCHMARK,
        accentResolver = { MikaColors.TerminalGreen }
    ),
    SRT_BATCH_STUDIO(
        toolCartridgeId = "tool_srt_batch_studio",
        title = "SRT SUBTITLE & BATCH",
        subtitle = "Timed .SRT/.VTT + Batch WAV",
        badge = "TOOL-10",
        iconType = OsModuleIconType.SRT_BATCH_STUDIO,
        accentResolver = { MikaColors.SsrGold }
    ),
    MANGA_DUB_STUDIO(
        toolCartridgeId = "tool_manga_dub_studio",
        title = "MANGA DUB STUDIO",
        subtitle = "CBZ Vision Cast & .MP4 Dubs",
        badge = "TOOL-11",
        iconType = OsModuleIconType.MANGA_DUB_STUDIO,
        accentResolver = { MikaColors.NeonPink }
    ),
    OPENAI_VISION_HUB(
        toolCartridgeId = "tool_openai_vision_api",
        title = "OPENAI VISION API",
        subtitle = "NanoGPT, OpenRouter, Clinepass & Custom",
        badge = "TOOL-12",
        iconType = OsModuleIconType.OPENAI_VISION_HUB,
        accentResolver = { MikaColors.NeonCyan }
    );

    fun accentColor(): Color = accentResolver()
}

/**
 * ✨ TAB 2: CYBERDECK OS MODULES DESKTOP & WINDOW LAUNCHER ✨
 * Displays slotted Tool & Cloning OS Cartridges (`TOOL-01`..`TOOL-06`, `CLN-05`) as custom OS vector icons.
 * Clicking an icon plays an animated window launch transition and opens that module's terminal window.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PurrRulebookTab(
    forgeState: PurrForgeState,
    activeProfile: PurrProfile,
    previewChunks: List<PurrChunk>,
    isPlayingPreview: Boolean,
    viewModel: TtsForgeViewModel,
    onOpenSaveDialog: () -> Unit,
    onOpenCloneStudio: () -> Unit,
    onLaunchCloneForMangaCharacter: (MangaCloneHandoffTarget) -> Unit = {},
    onOpenCartridgeBayTools: () -> Unit,
    onOpenCartridgeBayTts: () -> Unit,
    requestedToolId: String? = null,
    onConsumeRequestedTool: () -> Unit = {}
) {
    val activeModules = remember(forgeState.slottedCartridges) {
        ActiveOsModule.entries.filter { it.toolCartridgeId in forgeState.slottedCartridges }
    }
    var openedModule by remember { mutableStateOf<ActiveOsModule?>(null) }

    LaunchedEffect(requestedToolId, activeModules) {
        if (requestedToolId != null) {
            activeModules.firstOrNull { it.toolCartridgeId == requestedToolId }?.let {
                openedModule = it
            }
            onConsumeRequestedTool()
        }
    }

    // If the user ejects a Tool Cartridge from Cartridge Bay while its window is open, close the window.
    LaunchedEffect(activeModules) {
        if (openedModule != null && openedModule !in activeModules) {
            openedModule = null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (activeModules.isEmpty()) {
            MikaGlowPanel(accent = MikaColors.MikaAmber) {
                Text(
                    text = "[ALL_TOOL_MODULES_EJECTED]",
                    color = MikaColors.MikaAmber,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Raw speech mode is active (P.U.R.R. text rewriting, 24kHz DSP & Symbol Shield are bypassed). Slam Tool Cartridges in Cartridge Bay -> TOOLS to restore OS modules.",
                    color = MikaColors.CyanWhite.copy(alpha = 0.78f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MikaColors.TerminalGreen.copy(alpha = 0.16f))
                        .border(1.dp, MikaColors.TerminalGreen, RoundedCornerShape(6.dp))
                        .clickable(onClick = onOpenCartridgeBayTools)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "OPEN CARTRIDGE BAY -> TOOLS",
                        color = MikaColors.TerminalGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp
                    )
                }
            }
        } else {
            AnimatedContent(
                targetState = openedModule,
                transitionSpec = {
                    (scaleIn(initialScale = 0.88f, animationSpec = tween(220)) + fadeIn(tween(180)))
                        .togetherWith(fadeOut(tween(140)))
                },
                label = "osModuleWindowTransition"
            ) { targetModule ->
                if (targetModule == null) {
                    // Cyberdeck OS Desktop Icon Grid (2 columns)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        activeModules.chunked(2).forEach { rowModules ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                rowModules.forEach { mod ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        OsDesktopAppTile(
                                            module = mod,
                                            statusNote = when (mod) {
                                                ActiveOsModule.PURR_ENGINE -> activeProfile.name
                                                ActiveOsModule.DSP_RACK -> activeProfile.dspConfig.effectPreset.label
                                                ActiveOsModule.SYMBOL_SHIELD -> if (activeProfile.enableSymbolShield) "SHIELD ACTIVE" else "BYPASSED"
                                                ActiveOsModule.CLONE_STUDIO -> "${forgeState.clonedVoices.count { !it.isMangaOnly }} CLONED"
                                                ActiveOsModule.AUDIO_EXPORTER -> "${forgeState.exportedAudioFiles.size} EXPORTS"
                                                ActiveOsModule.VOICE_DESIGN -> "${forgeState.savedVoiceDesigns.size} DESIGNS"
                                                ActiveOsModule.AUDIOBOOK_CONSTRUCTOR -> "EPUB/PDF -> .M4B"
                                                ActiveOsModule.THEME_ENGINE -> if (forgeState.themeConfig.mode == PurrThemeMode.NEON_GLOW) {
                                                    "NEON ${PurrThemeEngine.formatHexRgb(forgeState.themeConfig.neonPrimaryHex)}"
                                                } else {
                                                    "RETRO"
                                                }
                                                ActiveOsModule.SCRIPT_STUDIO -> "MULTI-CAST WAV"
                                                ActiveOsModule.VOICE_BENCHMARK -> "A/B RTF LAB"
                                                ActiveOsModule.SRT_BATCH_STUDIO -> ".SRT/.VTT QUEUE"
                                                ActiveOsModule.MANGA_DUB_STUDIO -> "CBZ -> .MP4 DUB"
                                                ActiveOsModule.OPENAI_VISION_HUB -> "${forgeState.savedOpenAiVisionEngines.size} SAVED"
                                            },
                                            onClick = { openedModule = mod }
                                        )
                                    }
                                }
                                if (rowModules.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                } else {
                    val targetAccent = targetModule.accentColor()
                    // Opened Animated Cyberdeck OS Window
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MikaColors.PanelBg)
                            .border(1.5.dp, targetAccent, RoundedCornerShape(10.dp))
                    ) {
                        // Window Chrome Header Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            targetAccent.copy(alpha = 0.28f),
                                            MikaColors.RaisedCardBg
                                        )
                                    )
                                )
                                .border(0.5.dp, targetAccent.copy(alpha = 0.45f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                MikaCyberIcon(type = CyberIconType.WINDOW_FRAME, tint = targetAccent, size = 13.dp)
                                Text(
                                    text = targetModule.title,
                                    color = targetAccent,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                MikaTagPill(
                                    label = if (isPlayingPreview) "STOP" else "TEST",
                                    selected = true,
                                    activeColor = if (isPlayingPreview) MikaColors.DangerRed else MikaColors.TerminalGreen,
                                    leadingIcon = if (isPlayingPreview) CyberIconType.STOP else CyberIconType.PLAY,
                                    onClick = {
                                        if (isPlayingPreview) viewModel.stopAudition() else viewModel.auditionText()
                                    }
                                )
                                MikaTagPill(
                                    label = "DESKTOP",
                                    selected = true,
                                    activeColor = MikaColors.DangerRed,
                                    leadingIcon = CyberIconType.CLOSE,
                                    onClick = { openedModule = null }
                                )
                            }
                        }

                        // Single-Row Horizontally Scrollable Icon Dock + Pulsating Scroll Indicators
                        if (activeModules.size > 1) {
                            val dockScroll = rememberScrollState()
                            val dockScope = rememberCoroutineScope()
                            val arrowPulse = rememberInfiniteTransition(label = "dockArrowPulse")
                            val arrowAlpha by arrowPulse.animateFloat(
                                initialValue = 0.35f,
                                targetValue = 1.0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(720, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "dockArrowAlpha"
                            )
                            val arrowNudge by arrowPulse.animateFloat(
                                initialValue = 0f,
                                targetValue = 2.5f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(720, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "dockArrowNudge"
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MikaColors.DeepWellBg)
                                    .padding(vertical = 7.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(dockScroll)
                                        .padding(horizontal = 24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    activeModules.forEach { mod ->
                                        val isCurrent = mod == targetModule
                                        val modAccent = mod.accentColor()
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(3.dp),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { openedModule = mod }
                                                .padding(2.dp)
                                        ) {
                                            MikaOsModuleIcon(
                                                type = mod.iconType,
                                                accentColor = modAccent,
                                                isOnline = isCurrent,
                                                iconSize = 36.dp
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .width(22.dp)
                                                    .height(2.5.dp)
                                                    .clip(RoundedCornerShape(2.dp))
                                                    .background(
                                                        if (isCurrent) modAccent else Color.Transparent
                                                    )
                                            )
                                        }
                                    }
                                }

                                if (dockScroll.canScrollBackward) {
                                    Box(
                                        contentAlignment = Alignment.CenterStart,
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .background(
                                                Brush.horizontalGradient(
                                                    colors = listOf(MikaColors.DeepWellBg, MikaColors.DeepWellBg.copy(alpha = 0.8f), Color.Transparent)
                                                )
                                            )
                                            .clickable {
                                                dockScope.launch {
                                                    dockScroll.animateScrollTo((dockScroll.value - 180).coerceAtLeast(0))
                                                }
                                            }
                                            .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                                    ) {
                                        MikaCyberIcon(
                                            type = CyberIconType.CHEVRON_LEFT,
                                            tint = targetAccent.copy(alpha = arrowAlpha),
                                            size = 14.dp,
                                            modifier = Modifier.offset { IntOffset(-arrowNudge.dp.roundToPx(), 0) }
                                        )
                                    }
                                }

                                if (dockScroll.canScrollForward) {
                                    Box(
                                        contentAlignment = Alignment.CenterEnd,
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .background(
                                                Brush.horizontalGradient(
                                                    colors = listOf(Color.Transparent, MikaColors.DeepWellBg.copy(alpha = 0.8f), MikaColors.DeepWellBg)
                                                )
                                            )
                                            .clickable {
                                                dockScope.launch {
                                                    dockScroll.animateScrollTo((dockScroll.value + 180).coerceAtMost(dockScroll.maxValue))
                                                }
                                            }
                                            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                                    ) {
                                        MikaCyberIcon(
                                            type = CyberIconType.CHEVRON_RIGHT,
                                            tint = targetAccent.copy(alpha = arrowAlpha),
                                            size = 14.dp,
                                            modifier = Modifier.offset { IntOffset(arrowNudge.dp.roundToPx(), 0) }
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = targetAccent.copy(alpha = 0.30f))

                        // Window Body
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            when (targetModule) {
                                ActiveOsModule.PURR_ENGINE -> PurrEngineModuleWindowBody(
                                    forgeState = forgeState,
                                    activeProfile = activeProfile,
                                    previewChunks = previewChunks,
                                    viewModel = viewModel,
                                    onOpenSaveDialog = onOpenSaveDialog
                                )
                                ActiveOsModule.DSP_RACK -> DspRackModuleWindowBody(
                                    activeProfile = activeProfile,
                                    viewModel = viewModel
                                )
                                ActiveOsModule.SYMBOL_SHIELD -> SymbolShieldModuleWindowBody(
                                    activeProfile = activeProfile,
                                    previewChunks = previewChunks,
                                    viewModel = viewModel
                                )
                                ActiveOsModule.CLONE_STUDIO -> CloneStudioModuleWindowBody(
                                    forgeState = forgeState,
                                    viewModel = viewModel,
                                    onOpenCloneStudio = onOpenCloneStudio
                                )
                                ActiveOsModule.AUDIO_EXPORTER -> AudioExporterModuleWindowBody(
                                    forgeState = forgeState,
                                    activeProfile = activeProfile,
                                    isPlayingPreview = isPlayingPreview,
                                    viewModel = viewModel
                                )
                                ActiveOsModule.VOICE_DESIGN -> VoiceDesignModuleWindowBody(
                                    forgeState = forgeState,
                                    activeProfile = activeProfile,
                                    isPlayingPreview = isPlayingPreview,
                                    viewModel = viewModel,
                                    onOpenCartridgeBayTts = onOpenCartridgeBayTts
                                )
                                ActiveOsModule.AUDIOBOOK_CONSTRUCTOR -> AudiobookConstructorModuleWindowBody(
                                    activeProfile = activeProfile,
                                    isPlayingPreview = isPlayingPreview,
                                    viewModel = viewModel
                                )
                                ActiveOsModule.THEME_ENGINE -> ThemeEngineModuleWindowBody(
                                    forgeState = forgeState,
                                    viewModel = viewModel
                                )
                                ActiveOsModule.SCRIPT_STUDIO -> ScriptStudioModuleWindowBody(
                                    forgeState = forgeState,
                                    activeProfile = activeProfile,
                                    availableVoices = viewModel.allAvailableVoices(),
                                    viewModel = viewModel
                                )
                                ActiveOsModule.VOICE_BENCHMARK -> VoiceBenchmarkModuleWindowBody(
                                    forgeState = forgeState,
                                    activeProfile = activeProfile,
                                    availableVoices = viewModel.allAvailableVoices(),
                                    viewModel = viewModel
                                )
                                ActiveOsModule.SRT_BATCH_STUDIO -> SrtBatchStudioModuleWindowBody(
                                    activeProfile = activeProfile,
                                    isPlayingPreview = isPlayingPreview,
                                    viewModel = viewModel
                                )
                                ActiveOsModule.MANGA_DUB_STUDIO -> MangaDubStudioModuleWindowBody(
                                    forgeState = forgeState,
                                    activeProfile = activeProfile,
                                    availableVoices = viewModel.allAvailableVoices(includeMangaClones = true),
                                    viewModel = viewModel,
                                    onLaunchCloneForCharacter = onLaunchCloneForMangaCharacter
                                )
                                ActiveOsModule.OPENAI_VISION_HUB -> OpenAiVisionModuleWindowBody(
                                    forgeState = forgeState,
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OsDesktopAppTile(
    module: ActiveOsModule,
    statusNote: String,
    onClick: () -> Unit
) {
    val moduleAccent = module.accentColor()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MikaColors.RaisedCardBg,
                        MikaColors.PanelBg
                    )
                )
            )
            .border(1.2.dp, moduleAccent.copy(alpha = 0.42f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = module.badge,
                color = moduleAccent.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MikaColors.TerminalGreen)
                )
                Text(
                    text = "RUN",
                    color = MikaColors.TerminalGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 8.sp
                )
            }
        }

        MikaOsModuleIcon(
            type = module.iconType,
            accentColor = moduleAccent,
            isOnline = true
        )

        Text(
            text = module.title,
            color = MikaColors.PrimaryText,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = statusNote,
            color = moduleAccent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = module.subtitle,
            color = MikaColors.DimText,
            fontFamily = FontFamily.Monospace,
            fontSize = 8.5.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 12.sp
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PurrEngineModuleWindowBody(
    forgeState: PurrForgeState,
    activeProfile: PurrProfile,
    previewChunks: List<PurrChunk>,
    viewModel: TtsForgeViewModel,
    onOpenSaveDialog: () -> Unit
) {
    var expandPauses by remember { mutableStateOf(false) }
    var expandRulebook by remember { mutableStateOf(false) }

    var rulePattern by remember { mutableStateOf("") }
    var ruleReplacement by remember { mutableStateOf("") }
    var ruleIsRegex by remember { mutableStateOf(false) }
    var ruleCategory by remember { mutableStateOf(RuleCategory.CUSTOM) }

    // 1. Vibe Presets Picker
    MikaGlowPanel(accent = MikaColors.NeonPink) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "> PICK_YOUR_VIBE_PRESET",
                color = MikaColors.NeonPink,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp
            )
            MikaTagPill(
                label = "+ SAVE CUSTOM",
                selected = true,
                activeColor = MikaColors.NeonCyan,
                onClick = onOpenSaveDialog
            )
        }
        Text(
            text = "// One tap configures slang, pauses, acting & audio polish for your style.",
            color = MikaColors.DimText,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
        )

        // ✨ v1.8.3 PRESET DROPDOWN: the tappable card wall collapsed into one slick CyberDropdown —
        // the same terminal tray the rest of the Forge uses (one selection control, zero scroll cost).
        // The active preset's summary still renders underneath, so swapping vibes never hides
        // what the preset actually does.
        CyberDropdown(
            label = "> ACTIVE_PRESET",
            options = forgeState.profiles,
            selectedOption = activeProfile,
            optionLabel = { preset -> "${preset.name} [${preset.badgeTag}]" },
            onSelect = { preset -> viewModel.selectProfile(preset.id) },
            accent = MikaColors.NeonPink
        )
        Text(
            text = activeProfile.summary,
            color = MikaColors.CyanWhite.copy(alpha = 0.72f),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    // 2. Core PURR Text & Acting Toggles
    val neuralPureActive = PurrNeuralBypass.shouldBypassEnhancers(activeProfile, forgeState.clonedVoices)
    MikaGlowPanel(accent = MikaColors.TerminalGreen) {
        Text(
            text = "> CORE_PURR_TEXT_CONTROLS",
            color = MikaColors.TerminalGreen,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        SimplePurrToggle(
            title = "Neural Pure Mode (Flagship Auto-Bypass)",
            subtitle = "Auto-bypasses honorifics, stutters, comma-chopping & EQ coloring on Qwen3, NeuTTS-2E, Chatterbox, Soprano & Studio FP32 voices. Slang follows its own toggle.",
            checked = activeProfile.autoBypassEnhancersForNeuralModels,
            accent = MikaColors.NeonCyan,
            icon = CyberIconType.BOLT_ACCEL,
            onToggle = { v -> viewModel.updateActiveProfile { it.copy(autoBypassEnhancersForNeuralModels = v) } }
        )
        if (neuralPureActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.NeonCyan.copy(alpha = 0.12f))
                    .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MikaCyberIcon(type = CyberIconType.SHIELD_GUARD, tint = MikaColors.NeonCyan, size = 12.dp)
                Text(
                    text = "NEURAL PURE ACTIVE: Honorifics, stutters, comma-chopping & EQ bypassed on ${activeProfile.primaryVoiceId.substringAfter(':')} — slang still follows its toggle.",
                    color = MikaColors.NeonCyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 12.5.sp
                )
            }
        }
        // ✨ v1.8.3 THREE-WAY BENDER SPLIT: Slang / Honorifics / Stutters are now independent
        // toggles (each with its own per-preset default) instead of one combined flag plus a
        // flagship carve-out toggle. On Neural Pure flagship voices ONLY slang survives —
        // honorifics & stutters are auto-bypassed with the other intrusive enhancers.
        SimplePurrToggle(
            title = "Texting Slang Expansion",
            subtitle = "Expands 'omg', 'fr', 'wdym' & co into spoken words. Pure pronunciation aid — stays active on flagship voices too.",
            checked = activeProfile.enableSlang,
            accent = MikaColors.NeonCyan,
            icon = CyberIconType.CHAT_SLANG,
            onToggle = { v -> viewModel.updateActiveProfile { it.copy(enableSlang = v) } }
        )
        SimplePurrToggle(
            title = "Anime Honorifics & Vocalisms",
            subtitle = "'-senpai' → 'sen-pie', '-chan' → 'chahn', 'nya~' → 'nyaah'. Auto-bypassed on flagship voices for clean speech.",
            checked = activeProfile.enableHonorifics,
            accent = MikaColors.NeonPurple,
            icon = CyberIconType.STAR_BADGE,
            onToggle = { v -> viewModel.updateActiveProfile { it.copy(enableHonorifics = v) } }
        )
        SimplePurrToggle(
            title = "Organic Stutter Translator",
            subtitle = "'w-what' → 'wuh-what', 'th-that' → 'thuh-that' — strictly repeated-letter written stutters. Auto-bypassed on flagship voices.",
            checked = activeProfile.enableStutters,
            accent = MikaColors.NeonPink,
            icon = CyberIconType.WAVE_TAPER,
            onToggle = { v -> viewModel.updateActiveProfile { it.copy(enableStutters = v) } }
        )
        SimplePurrToggle(
            title = "MIKASTOP Natural Breath Pauses",
            subtitle = "Splits '...', em-dash, and commas into clean silence gaps.",
            checked = activeProfile.enableMikaStopPauses,
            accent = MikaColors.MikaAmber,
            icon = CyberIconType.PAUSE,
            onToggle = { v -> viewModel.updateActiveProfile { it.copy(enableMikaStopPauses = v) } }
        )
        SimplePurrToggle(
            title = "Emotion & Archetype Acting",
            subtitle = "Adapts speech speed & pitch to sentence emotion.",
            checked = activeProfile.sentimentConfig.enabled,
            accent = MikaColors.NeonPink,
            icon = CyberIconType.DUAL_MASKS,
            onToggle = { v ->
                viewModel.updateActiveProfile { it.copy(sentimentConfig = it.sentimentConfig.copy(enabled = v)) }
            }
        )

        Spacer(modifier = Modifier.height(6.dp))
        CyberDropdown(
            label = "> ARCHETYPE_TONE_OVERRIDE",
            options = ArchetypeTone.entries,
            selectedOption = activeProfile.sentimentConfig.toneOverride,
            optionLabel = { it.label },
            onSelect = { tone ->
                viewModel.updateActiveProfile { p -> p.copy(sentimentConfig = p.sentimentConfig.copy(toneOverride = tone)) }
            },
            accent = MikaColors.NeonPink
        )
    }

    // 3. MIKASTOP Timings + Acting Intensity + Live Chunk Preview
    ExpandableTerminalSection(
        title = "ADVANCED: MIKASTOP TIMINGS, DRAMA & LIVE CHUNKS",
        subtitle = "Pause ms sliders, stutter/vowel curves & real-time chunk preview",
        expanded = expandPauses,
        onToggle = { expandPauses = !expandPauses },
        accent = MikaColors.MikaAmber
    ) {
        TerminalSlider(
            label = "ELLIPSIS (...)",
            valueText = "${activeProfile.mikaStopConfig.ellipsisPauseMs} ms",
            value = activeProfile.mikaStopConfig.ellipsisPauseMs.toFloat(),
            valueRange = 100f..1200f,
            accent = MikaColors.MikaAmber,
            onValueChange = { v -> viewModel.updateMikaStopConfig { it.copy(ellipsisPauseMs = v.roundToInt()) } }
        )
        TerminalSlider(
            label = "EM_DASH (-- / ~)",
            valueText = "${activeProfile.mikaStopConfig.emDashPauseMs} ms",
            value = activeProfile.mikaStopConfig.emDashPauseMs.toFloat(),
            valueRange = 80f..900f,
            accent = MikaColors.MikaAmber,
            onValueChange = { v -> viewModel.updateMikaStopConfig { it.copy(emDashPauseMs = v.roundToInt()) } }
        )
        TerminalSlider(
            label = "SENTENCE (. ! ?)",
            valueText = "${activeProfile.mikaStopConfig.sentencePauseMs} ms",
            value = activeProfile.mikaStopConfig.sentencePauseMs.toFloat(),
            valueRange = 80f..900f,
            accent = MikaColors.MikaAmber,
            onValueChange = { v -> viewModel.updateMikaStopConfig { it.copy(sentencePauseMs = v.roundToInt()) } }
        )
        TerminalSlider(
            label = "COMMA_BREATH (, ; :)",
            valueText = "${activeProfile.mikaStopConfig.commaPauseMs} ms",
            value = activeProfile.mikaStopConfig.commaPauseMs.toFloat(),
            valueRange = 40f..450f,
            accent = MikaColors.MikaAmber,
            onValueChange = { v -> viewModel.updateMikaStopConfig { it.copy(commaPauseMs = v.roundToInt()) } }
        )
        TerminalSlider(
            label = "STUTTER_DRAMA_INTENSITY",
            valueText = "${(activeProfile.sentimentConfig.stutterIntensity * 100).roundToInt()}%",
            value = activeProfile.sentimentConfig.stutterIntensity,
            valueRange = 0f..0.45f,
            accent = MikaColors.NeonPink,
            onValueChange = { v -> viewModel.updateActiveProfile { it.copy(sentimentConfig = it.sentimentConfig.copy(stutterIntensity = v)) } }
        )
        TerminalSlider(
            label = "VOWEL_STRETCH_INTENSITY",
            valueText = "${(activeProfile.sentimentConfig.vowelStretchIntensity * 100).roundToInt()}%",
            value = activeProfile.sentimentConfig.vowelStretchIntensity,
            valueRange = 0f..0.45f,
            accent = MikaColors.NeonPink,
            onValueChange = { v -> viewModel.updateActiveProfile { it.copy(sentimentConfig = it.sentimentConfig.copy(vowelStretchIntensity = v)) } }
        )

        ChunkPreviewBox(previewChunks = previewChunks)
    }

    // 4. Phonetic & Slang Rulebook
    ExpandableTerminalSection(
        title = "ADVANCED: PHONETIC & SLANG RULEBOOK",
        subtitle = "${PurrTextProcessor.SLANG_DICTIONARY.size} built-in GachaSwipe rules + ${forgeState.customPhoneticRules.size} custom",
        expanded = expandRulebook,
        onToggle = { expandRulebook = !expandRulebook },
        accent = MikaColors.TerminalGreen
    ) {
        Text(
            text = "> BUILT-IN GACHA-SWIPE RULES (ALWAYS ACTIVE WHEN SLANG IS ON)",
            color = MikaColors.TerminalGreen,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MikaColors.DeepWellBg)
                .border(1.dp, MikaColors.TerminalGreen.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            PurrTextProcessor.HONORIFIC_RULES_PREVIEW.forEach { (k, v) ->
                Text("$k  ->  $v", color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 9.5.sp)
            }
            Text(
                text = "Slang sample: " + PurrTextProcessor.SLANG_DICTIONARY.entries.take(8).joinToString(", ") { "${it.key}->${it.value}" } + " (+${PurrTextProcessor.SLANG_DICTIONARY.size - 8} more)",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
        }

        Text(
            text = "> ADD CUSTOM PHONETIC OVERRIDE",
            color = MikaColors.NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = rulePattern,
                onValueChange = { rulePattern = it },
                label = { Text("WORD / REGEX", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                singleLine = true,
                textStyle = TextStyle(color = MikaColors.TerminalGreen, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MikaColors.TerminalGreen,
                    unfocusedBorderColor = MikaColors.TerminalGreen.copy(alpha = 0.35f),
                    focusedContainerColor = MikaColors.DeepWellBg,
                    unfocusedContainerColor = MikaColors.DeepWellBg
                ),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = ruleReplacement,
                onValueChange = { ruleReplacement = it },
                label = { Text("SPEAK AS", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
                singleLine = true,
                textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MikaColors.NeonCyan,
                    unfocusedBorderColor = MikaColors.NeonCyan.copy(alpha = 0.35f),
                    focusedContainerColor = MikaColors.DeepWellBg,
                    unfocusedContainerColor = MikaColors.DeepWellBg
                ),
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MikaTagPill(
                    label = if (ruleIsRegex) "REGEX:ON" else "REGEX:OFF",
                    selected = ruleIsRegex,
                    activeColor = MikaColors.NeonCyan,
                    onClick = { ruleIsRegex = !ruleIsRegex }
                )
                MikaTagPill(
                    label = ruleCategory.label,
                    selected = true,
                    activeColor = MikaColors.TerminalGreen,
                    onClick = {
                        val entries = RuleCategory.entries
                        ruleCategory = entries[(entries.indexOf(ruleCategory) + 1) % entries.size]
                    }
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MikaColors.TerminalGreen.copy(alpha = 0.16f))
                    .border(1.dp, MikaColors.TerminalGreen, RoundedCornerShape(4.dp))
                    .clickable {
                        viewModel.addPhoneticRule(rulePattern, ruleReplacement, ruleIsRegex, ruleCategory)
                        rulePattern = ""
                        ruleReplacement = ""
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "+ ADD_RULE",
                    color = MikaColors.TerminalGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp
                )
            }
        }

        forgeState.customPhoneticRules.forEach { rule ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MikaColors.DeepWellBg)
                    .border(1.dp, MikaColors.TerminalGreen.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${rule.pattern} -> ${rule.replacement}",
                    color = if (rule.enabled) MikaColors.CyanWhite else MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MikaTagPill(
                        label = if (rule.enabled) "ON" else "OFF",
                        selected = rule.enabled,
                        activeColor = MikaColors.TerminalGreen,
                        onClick = { viewModel.togglePhoneticRule(rule.id) }
                    )
                    MikaTagPill(
                        label = "DEL",
                        selected = true,
                        activeColor = MikaColors.DangerRed,
                        onClick = { viewModel.deletePhoneticRule(rule.id) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DspRackModuleWindowBody(
    activeProfile: PurrProfile,
    viewModel: TtsForgeViewModel
) {
    MikaGlowPanel(accent = MikaColors.NeonCyan) {
        Text(
            text = "> 24KHZ STUDIO AUDIO POLISH & TAPER",
            color = MikaColors.NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        SimplePurrToggle(
            title = "Studio Audio Polish (24kHz DSP)",
            subtitle = "GachaSwipe 80Hz sub-cut, chest warmth, 8.5kHz de-esser & glue compressor.",
            checked = activeProfile.enableDsp,
            accent = MikaColors.NeonCyan,
            icon = CyberIconType.HEADPHONES_DSP,
            onToggle = { v -> viewModel.updateActiveProfile { it.copy(enableDsp = v) } }
        )
        SimplePurrToggle(
            title = "Sentence Tail Taper (GachaSwipe 120ms Fade)",
            subtitle = "Smoothly tapers off the voice at the end of every sentence and gates tail clicks.",
            checked = activeProfile.dspConfig.enableSentenceTaper,
            accent = MikaColors.NeonPurple,
            icon = CyberIconType.WAVE_TAPER,
            onToggle = { v ->
                viewModel.updateActiveProfile { it.copy(dspConfig = it.dspConfig.copy(enableSentenceTaper = v)) }
            }
        )
    }

    MikaGlowPanel(accent = MikaColors.NeonPurple) {
        Text(
            text = "> GACHASWIPE_VOICE_EFFECTS (10 PRESETS)",
            color = MikaColors.NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AcousticEffectPreset.entries.forEach { preset ->
                MikaTagPill(
                    label = preset.label,
                    selected = activeProfile.dspConfig.effectPreset == preset,
                    activeColor = MikaColors.NeonCyan,
                    onClick = {
                        viewModel.updateActiveProfile { p -> p.copy(dspConfig = p.dspConfig.copy(effectPreset = preset)) }
                    }
                )
            }
        }
        if (activeProfile.dspConfig.effectPreset.summary.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "// ${activeProfile.dspConfig.effectPreset.summary}",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp
            )
        }
    }

    MikaGlowPanel(accent = MikaColors.NeonCyan) {
        Text(
            text = "> PARAMETRIC EQ & ENVELOPE RACK",
            color = MikaColors.NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        TerminalSlider(
            label = "SENTENCE_TAIL_TAPER_MS",
            valueText = "${activeProfile.dspConfig.sentenceTaperMs} ms",
            value = activeProfile.dspConfig.sentenceTaperMs.toFloat(),
            valueRange = 40f..260f,
            accent = MikaColors.NeonPurple,
            onValueChange = { v ->
                viewModel.updateActiveProfile { it.copy(dspConfig = it.dspConfig.copy(sentenceTaperMs = v.roundToInt())) }
            }
        )
        val warmthDb = activeProfile.dspConfig.warmthEqDb
        val warmthText = "${if (warmthDb >= 0f) "+" else ""}${"%.1f".format(warmthDb)} dB"
        TerminalSlider(
            label = "CHEST_WARMTH_250HZ_EQ",
            valueText = warmthText,
            value = warmthDb,
            valueRange = -4f..8f,
            accent = MikaColors.NeonCyan,
            onValueChange = { v -> viewModel.updateActiveProfile { it.copy(dspConfig = it.dspConfig.copy(warmthEqDb = v)) } }
        )
        val deEssDb = activeProfile.dspConfig.presenceEqDb
        val deEssText = "${if (deEssDb >= 0f) "+" else ""}${"%.1f".format(deEssDb)} dB"
        TerminalSlider(
            label = "DE_ESSER_8_5KHZ_SHELF",
            valueText = deEssText,
            value = deEssDb,
            valueRange = -12f..4f,
            accent = MikaColors.NeonCyan,
            onValueChange = { v -> viewModel.updateActiveProfile { it.copy(dspConfig = it.dspConfig.copy(presenceEqDb = v)) } }
        )
        TerminalSlider(
            label = "ROOM_REVERB_WET",
            valueText = "${(activeProfile.dspConfig.reverbWetMix * 100).roundToInt()}%",
            value = activeProfile.dspConfig.reverbWetMix,
            valueRange = 0f..0.45f,
            accent = MikaColors.NeonCyan,
            onValueChange = { v -> viewModel.updateActiveProfile { it.copy(dspConfig = it.dspConfig.copy(reverbWetMix = v)) } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymbolShieldModuleWindowBody(
    activeProfile: PurrProfile,
    previewChunks: List<PurrChunk>,
    viewModel: TtsForgeViewModel
) {
    MikaGlowPanel(accent = MikaColors.TerminalGreen) {
        SimplePurrToggle(
            title = "Symbol & Punctuation Shield",
            subtitle = "Intercept quotes, commas & audiobook symbols in MIKA so TTS models never hallucinate.",
            checked = activeProfile.enableSymbolShield,
            accent = MikaColors.TerminalGreen,
            icon = CyberIconType.SHIELD_GUARD,
            onToggle = { v -> viewModel.updateActiveProfile { it.copy(enableSymbolShield = v) } }
        )

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "> COMMA & CLAUSE INTERPRETATION",
            color = MikaColors.NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.5.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CommaHandlingMode.entries.forEach { mode ->
                MikaTagPill(
                    label = mode.label,
                    selected = activeProfile.symbolShieldConfig.commaHandling == mode,
                    activeColor = MikaColors.NeonCyan,
                    onClick = {
                        viewModel.updateActiveProfile { p ->
                            p.copy(symbolShieldConfig = p.symbolShieldConfig.copy(commaHandling = mode))
                        }
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "// ${activeProfile.symbolShieldConfig.commaHandling.description}",
            color = MikaColors.DimText,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp
        )

        Spacer(modifier = Modifier.height(8.dp))
        TerminalSlider(
            label = "MIN_WORDS_BEFORE_COMMA_PAUSE",
            valueText = "${activeProfile.mikaStopConfig.minWordsForCommaPause} words",
            value = activeProfile.mikaStopConfig.minWordsForCommaPause.toFloat(),
            valueRange = 1f..8f,
            accent = MikaColors.MikaAmber,
            onValueChange = { v -> viewModel.updateMikaStopConfig { it.copy(minWordsForCommaPause = v.roundToInt()) } }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SLICE_AT_COMMAS", color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Switch(
                checked = activeProfile.mikaStopConfig.sliceAtCommas,
                onCheckedChange = { c -> viewModel.updateMikaStopConfig { it.copy(sliceAtCommas = c) } },
                colors = SwitchDefaults.colors(checkedThumbColor = MikaColors.VoidBg, checkedTrackColor = MikaColors.MikaAmber)
            )
        }
    }

    MikaGlowPanel(accent = MikaColors.NeonCyan) {
        Text(
            text = "> LOOSE_SENTENCE_CHUNK_SIZE (AUDIOBOOK FLOW)",
            color = MikaColors.NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                0 to "PER-SENTENCE (0)",
                140 to "SHORT (~140c)",
                220 to "AUDIOBOOK (~220c)",
                350 to "LONG (~350c)"
            ).forEach { (chars, label) ->
                val isPicked = if (chars == 0) {
                    activeProfile.mikaStopConfig.targetChunkChars < 40
                } else {
                    activeProfile.mikaStopConfig.targetChunkChars == chars
                }
                MikaTagPill(
                    label = label,
                    selected = isPicked,
                    activeColor = MikaColors.NeonCyan,
                    onClick = { viewModel.updateMikaStopConfig { it.copy(targetChunkChars = chars) } }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TerminalSlider(
            label = "TARGET_CHUNK_LENGTH",
            valueText = if (activeProfile.mikaStopConfig.targetChunkChars < 40) {
                "PER-SENTENCE"
            } else {
                "~${activeProfile.mikaStopConfig.targetChunkChars} chars"
            },
            value = activeProfile.mikaStopConfig.targetChunkChars.toFloat(),
            valueRange = 0f..450f,
            accent = MikaColors.NeonCyan,
            onValueChange = { v ->
                val rounded = if (v < 40f) 0 else ((v.roundToInt() / 10) * 10)
                viewModel.updateMikaStopConfig { it.copy(targetChunkChars = rounded) }
            }
        )
        Text(
            text = "// Groups complete sentences together up to this target. Never cuts off mid-sentence!",
            color = MikaColors.DimText,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp
        )
    }

    MikaGlowPanel(accent = MikaColors.TerminalGreen) {
        SimplePurrToggle(
            title = "Strip Quotes (\" ')",
            subtitle = "Keep contractions like don't / I'm while removing dialogue quote marks.",
            checked = activeProfile.symbolShieldConfig.stripQuotationMarks,
            accent = MikaColors.TerminalGreen,
            onToggle = { v ->
                viewModel.updateActiveProfile { p -> p.copy(symbolShieldConfig = p.symbolShieldConfig.copy(stripQuotationMarks = v)) }
            }
        )
        SimplePurrToggle(
            title = "Strip Brackets & Markdown (* _ # [ ] ( ))",
            subtitle = "Prevent neural voices from stuttering or humming on formatting symbols.",
            checked = activeProfile.symbolShieldConfig.stripBracketsAndMarkdown,
            accent = MikaColors.TerminalGreen,
            onToggle = { v ->
                viewModel.updateActiveProfile { p -> p.copy(symbolShieldConfig = p.symbolShieldConfig.copy(stripBracketsAndMarkdown = v)) }
            }
        )
        SimplePurrToggle(
            title = "Speak Symbols (& % + = @)",
            subtitle = "Convert common symbols into spoken English words cleanly.",
            checked = activeProfile.symbolShieldConfig.expandCommonSymbols,
            accent = MikaColors.TerminalGreen,
            onToggle = { v ->
                viewModel.updateActiveProfile { p -> p.copy(symbolShieldConfig = p.symbolShieldConfig.copy(expandCommonSymbols = v)) }
            }
        )

        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = activeProfile.symbolShieldConfig.customSkippedChars,
            onValueChange = { updated ->
                viewModel.updateActiveProfile { p ->
                    p.copy(symbolShieldConfig = p.symbolShieldConfig.copy(customSkippedChars = updated))
                }
            },
            label = { Text("SKIPPED CHARACTERS & SYMBOLS", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
            singleLine = true,
            textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.TerminalGreen,
                unfocusedBorderColor = MikaColors.TerminalGreen.copy(alpha = 0.35f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        MikaTagPill(
            label = "RESET DEFAULT SYMBOLS",
            selected = true,
            activeColor = MikaColors.MikaAmber,
            leadingIcon = CyberIconType.REFRESH,
            onClick = {
                viewModel.updateActiveProfile { p ->
                    p.copy(symbolShieldConfig = p.symbolShieldConfig.copy(customSkippedChars = SymbolShieldConfig().customSkippedChars))
                }
            }
        )

        Spacer(modifier = Modifier.height(10.dp))
        ChunkPreviewBox(previewChunks = previewChunks)
    }
}

@Composable
private fun CloneStudioModuleWindowBody(
    forgeState: PurrForgeState,
    viewModel: TtsForgeViewModel,
    onOpenCloneStudio: () -> Unit
) {
    MikaGlowPanel(accent = MikaColors.NeonPurple) {
        Text(
            text = "> 5-SECOND ZERO-SHOT VOICE CLONER",
            color = MikaColors.NeonPurple,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Record a sample or import an audio clip to clone any voice timbre offline across ROM-03 (Pocket, ZipVoice, Chatterbox-Turbo) & ROM-04 (Qwen3 0.6B Base).",
            color = MikaColors.CyanWhite.copy(alpha = 0.78f),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 14.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MikaColors.NeonPurple.copy(alpha = 0.22f))
                .border(1.5.dp, MikaColors.NeonPurple, RoundedCornerShape(6.dp))
                .clickable(onClick = onOpenCloneStudio)
                .padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MikaCyberIcon(type = CyberIconType.DNA_CLONE, tint = MikaColors.NeonPurple, size = 14.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "LAUNCH 5S VOICE RECORDER & CLONE WORKSHOP",
                color = MikaColors.WarmCream,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp
            )
        }
    }

    val userClones = remember(forgeState.clonedVoices) {
        forgeState.clonedVoices.filterNot { it.isMangaOnly }
    }
    MikaGlowPanel(accent = MikaColors.NeonCyan) {
        Text(
            text = "> REGISTERED_CLONED_VOICES (${userClones.size})",
            color = MikaColors.NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (userClones.isEmpty()) {
            Text(
                text = "// No cloned voices yet. Launch the recorder above after installing a cloning engine in ROM-03 or ROM-04.",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                userClones.forEach { clone ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MikaColors.DeepWellBg)
                            .border(1.dp, MikaColors.NeonPurple.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = clone.displayName,
                                color = MikaColors.PrimaryText,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "${clone.family.label} / ${clone.tags.take(3).joinToString(", ")}",
                                color = MikaColors.NeonPurple,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp
                            )
                        }
                        MikaTagPill(
                            label = "DEL",
                            selected = true,
                            activeColor = MikaColors.DangerRed,
                            onClick = { viewModel.removeClonedVoice(clone.id) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioExporterModuleWindowBody(
    forgeState: PurrForgeState,
    activeProfile: PurrProfile,
    isPlayingPreview: Boolean,
    viewModel: TtsForgeViewModel
) {
    val isExporting by viewModel.isExportingAudio.collectAsState()
    val exportProgress by viewModel.exportProgressFraction.collectAsState()
    val exportStatus by viewModel.exportStatusLabel.collectAsState()

    var exportTitle by remember { mutableStateOf("PURR_Audiobook_Track") }
    var exportBody by remember {
        mutableStateOf(
            "Chapter One.\n\nThe midnight rain pattered gently against the brass windowpane as Elara adjusted her headphones. \"Did you verify the neural bridge, MIKA?\" she whispered, leaning closer to the glowing emerald console.\n\nAcross the room, the terminal hummed softly in response... Ready for long-form storytelling."
        )
    }
    var targetChunkChars by remember { mutableIntStateOf(240) }
    var paragraphPauseMs by remember { mutableIntStateOf(550) }
    var normalizeLoudness by remember { mutableStateOf(true) }
    var smoothTransitions by remember { mutableStateOf(true) }

    val purrActive = "tool_purr_engine" in forgeState.slottedCartridges
    val dspActive = "tool_dsp_rack" in forgeState.slottedCartridges
    val estWords = remember(exportBody) {
        exportBody.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
    }
    val estChunks = remember(exportBody, targetChunkChars) {
        if (exportBody.isBlank()) 0
        else ((exportBody.length / targetChunkChars.coerceAtLeast(80)) + 1).coerceAtLeast(1)
    }

    MikaGlowPanel(accent = MikaColors.SsrGold) {
        Text(
            text = "> AUDIO_MEDIA_EXPORTER // TEXT_TO_WAV",
            color = MikaColors.SsrGold,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Render short voice messages or multi-page audiobook chapters directly into mastered 24kHz .WAV media files saved to Downloads/PURR-TTS/.",
            color = MikaColors.CyanWhite.copy(alpha = 0.78f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            lineHeight = 13.5.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MikaTagPill(
                label = if (purrActive) "P.U.R.R.: ACTIVE" else "P.U.R.R.: OFF",
                selected = purrActive,
                activeColor = MikaColors.NeonPink,
                onClick = {}
            )
            MikaTagPill(
                label = if (dspActive && activeProfile.enableDsp) "DSP: ACTIVE" else "DSP: OFF",
                selected = dspActive && activeProfile.enableDsp,
                activeColor = MikaColors.NeonCyan,
                onClick = {}
            )
            MikaTagPill(
                label = "VOICE: ${activeProfile.primaryVoiceId.substringAfter(':').take(16)}",
                selected = true,
                activeColor = MikaColors.TerminalGreen,
                onClick = {}
            )
        }
    }

    MikaGlowPanel(accent = MikaColors.NeonCyan) {
        OutlinedTextField(
            value = exportTitle,
            onValueChange = { exportTitle = it },
            label = { Text("EXPORT_FILENAME_TITLE", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
            singleLine = true,
            textStyle = TextStyle(color = MikaColors.SsrGold, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.SsrGold,
                unfocusedBorderColor = MikaColors.SsrGold.copy(alpha = 0.35f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
        )

        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MikaTagPill(
                label = "LOAD MESSAGE",
                selected = false,
                activeColor = MikaColors.NeonCyan,
                onClick = {
                    exportTitle = "MIKA_Voice_Note"
                    exportBody = "Hey! Wait up... *catches breath* \"Are we seriously testing neural audio export right now?\" omg fr nya~"
                }
            )
            MikaTagPill(
                label = "LOAD BOOK PAGE",
                selected = false,
                activeColor = MikaColors.SsrGold,
                onClick = {
                    exportTitle = "Chapter_01_Harbor"
                    exportBody = "Chapter One.\n\nThe evening wind blew softly across the quiet harbor as lanterns flickered along the wet cobblestone pier. Captain Elara adjusted her brass compass and watched the horizon ahead.\n\n\"Tomorrow the real journey begins,\" she murmured under her breath. Below deck, the engines settled into a low, steady rhythm."
                }
            )
            MikaTagPill(
                label = "CLEAR TEXT",
                selected = false,
                activeColor = MikaColors.DangerRed,
                onClick = { exportBody = "" }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = exportBody,
            onValueChange = { exportBody = it },
            label = { Text("SHORT MESSAGE OR AUDIOBOOK PAGES", fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
            minLines = 5,
            maxLines = 10,
            textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.NeonCyan,
                unfocusedBorderColor = MikaColors.NeonCyan.copy(alpha = 0.35f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
        )

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "CHARS: ${exportBody.length} | WORDS: $estWords | EST_CHUNKS: ~$estChunks",
            color = MikaColors.DimText,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp
        )
    }

    MikaGlowPanel(accent = MikaColors.TerminalGreen) {
        Text(
            text = "> CHUNKING & SEAMLESS POST-PROCESSING BLEND",
            color = MikaColors.TerminalGreen,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                160 to "SHORT (~160c)",
                240 to "BOOK (~240c)",
                360 to "LONG (~360c)"
            ).forEach { (chars, label) ->
                MikaTagPill(
                    label = label,
                    selected = targetChunkChars == chars,
                    activeColor = MikaColors.NeonCyan,
                    onClick = { targetChunkChars = chars }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TerminalSlider(
            label = "PARAGRAPH_GAP_MS",
            valueText = "$paragraphPauseMs ms",
            value = paragraphPauseMs.toFloat(),
            valueRange = 200f..1200f,
            accent = MikaColors.MikaAmber,
            onValueChange = { v -> paragraphPauseMs = (v.roundToInt() / 50) * 50 }
        )
        SimplePurrToggle(
            title = "Cosine Crossfade & Boundary De-Click",
            subtitle = "18ms raised-cosine overlap blending & DC offset removal across chunks.",
            checked = smoothTransitions,
            accent = MikaColors.TerminalGreen,
            icon = CyberIconType.CROSSFADE_LINK,
            onToggle = { smoothTransitions = it }
        )
        SimplePurrToggle(
            title = "Auto-Level Sentence Loudness (-18 dBFS RMS)",
            subtitle = "Matches loudness evenly between sentences & paragraphs + soft master limiter.",
            checked = normalizeLoudness,
            accent = MikaColors.NeonCyan,
            icon = CyberIconType.SLIDER_FADER,
            onToggle = { normalizeLoudness = it }
        )

        Spacer(modifier = Modifier.height(8.dp))
        if (isExporting) {
            LinearProgressIndicator(
                progress = { exportProgress },
                color = MikaColors.SsrGold,
                trackColor = MikaColors.SsrGold.copy(alpha = 0.18f),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = exportStatus,
                    color = MikaColors.SsrGold,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp,
                    modifier = Modifier.weight(1f)
                )
                MikaTagPill(
                    label = "CANCEL",
                    selected = true,
                    activeColor = MikaColors.DangerRed,
                    leadingIcon = CyberIconType.CLOSE,
                    onClick = { viewModel.cancelAudioExport() }
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.SsrGold.copy(alpha = 0.22f))
                    .border(1.5.dp, MikaColors.SsrGold, RoundedCornerShape(6.dp))
                    .clickable {
                        viewModel.renderAndExportMediaFile(
                            title = exportTitle,
                            rawText = exportBody,
                            targetChunkChars = targetChunkChars,
                            paragraphPauseMs = paragraphPauseMs,
                            normalizeLoudness = normalizeLoudness,
                            smoothTransitions = smoothTransitions
                        )
                    }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(type = CyberIconType.DOWNLOAD, tint = MikaColors.SsrGold, size = 14.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "RENDER & DOWNLOAD .WAV MEDIA FILE",
                    color = MikaColors.SsrGold,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.5.sp,
                    letterSpacing = 0.6.sp
                )
            }
        }
    }

    MikaGlowPanel(accent = MikaColors.SsrGold) {
        Text(
            text = "> EXPORTED_MEDIA_FILES (${forgeState.exportedAudioFiles.size})",
            color = MikaColors.SsrGold,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (forgeState.exportedAudioFiles.isEmpty()) {
            Text(
                text = "// No rendered .WAV files yet. Enter text above and tap RENDER & DOWNLOAD.",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                forgeState.exportedAudioFiles.forEach { entry ->
                    val sizeKb = (entry.sizeBytes / 1024L).coerceAtLeast(1L)
                    val sizeText = if (sizeKb >= 1024L) "${"%.2f".format(sizeKb / 1024f)} MB" else "$sizeKb KB"
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MikaColors.DeepWellBg)
                            .border(1.dp, MikaColors.SsrGold.copy(alpha = 0.38f), RoundedCornerShape(6.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.fileName,
                                    color = MikaColors.PrimaryText,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${"%.1f".format(entry.durationSec)}s | $sizeText | ${entry.chunkCount} chunks | ${entry.voiceLabel}",
                                    color = MikaColors.SsrGold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 8.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (entry.publicDownloadPath.isNotBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        MikaCyberIcon(type = CyberIconType.CHECK, tint = MikaColors.TerminalGreen, size = 10.dp)
                                        Text(
                                            text = entry.publicDownloadPath,
                                            color = MikaColors.TerminalGreen,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 8.5.sp
                                        )
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MikaTagPill(
                                label = if (isPlayingPreview) "STOP" else "PLAY",
                                selected = true,
                                activeColor = MikaColors.TerminalGreen,
                                leadingIcon = if (isPlayingPreview) CyberIconType.STOP else CyberIconType.PLAY,
                                onClick = {
                                    if (isPlayingPreview) viewModel.stopAudition()
                                    else viewModel.playExportedAudioFile(entry)
                                }
                            )
                            MikaTagPill(
                                label = "SAVE TO DOWNLOADS",
                                selected = true,
                                activeColor = MikaColors.NeonCyan,
                                leadingIcon = CyberIconType.DOWNLOAD,
                                onClick = { viewModel.saveExportedFileToDownloads(entry) }
                            )
                            MikaTagPill(
                                label = "DEL",
                                selected = true,
                                activeColor = MikaColors.DangerRed,
                                onClick = { viewModel.deleteExportedAudioFile(entry) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChunkPreviewBox(previewChunks: List<PurrChunk>) {
    Text(
        text = "> LIVE_CHUNK_PREVIEW (${previewChunks.size} CHUNKS)",
        color = MikaColors.NeonCyan,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MikaColors.DeepWellBg)
            .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        previewChunks.take(8).forEachIndexed { idx, chunk ->
            when (chunk) {
                is PurrChunk.Silence -> Text(
                    text = "#$idx [MIKASTOP] +${chunk.durationMs}ms (${chunk.reason})",
                    color = MikaColors.MikaAmber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
                is PurrChunk.Speech -> Text(
                    text = "#$idx [${chunk.role.name}|r=${"%.2f".format(chunk.speakingRate)}] ${chunk.text}",
                    color = MikaColors.CyanWhite,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
internal fun SimplePurrToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    icon: CyberIconType? = null,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (icon != null) {
                    MikaCyberIcon(type = icon, tint = if (checked) accent else MikaColors.DimText, size = 13.dp)
                }
                Text(
                    text = title,
                    color = if (checked) MikaColors.PrimaryText else MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
            Text(
                text = subtitle,
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MikaColors.VoidBg,
                checkedTrackColor = accent
            )
        )
    }
}

@Composable
internal fun TerminalSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    accent: Color,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            Text(valueText, color = accent, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 10.sp)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = accent.copy(alpha = 0.22f)
            )
        )
    }
}
