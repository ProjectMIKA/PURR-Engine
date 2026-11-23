package com.projectmika.purrtts.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.ModelBundleSpec
import com.projectmika.purrtts.core.purr.VoiceDescriptor
import com.projectmika.purrtts.core.purr.VoiceGender
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Scrolls the decorated input cleanly into view whenever it gains focus or the soft keyboard opens,
 * leaving [extraBottomDp] of clearance below the input so the IME never covers the text cursor.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bringIntoViewWithIme(extraBottomDp: Dp = 28.dp): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    var isFocused by remember { mutableStateOf(false) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(isFocused, imeBottom) {
        if (isFocused) {
            val padPx = with(density) { extraBottomDp.toPx() }
            val rect = Rect(
                left = 0f,
                top = 0f,
                right = boxSize.width.toFloat().coerceAtLeast(1f),
                bottom = (boxSize.height.toFloat() + padPx).coerceAtLeast(1f)
            )
            delay(90)
            requester.bringIntoView(rect)
            if (imeBottom > 0) {
                delay(220)
                requester.bringIntoView(rect)
            }
        }
    }

    this
        .bringIntoViewRequester(requester)
        .onSizeChanged { boxSize = it }
        .onFocusChanged { isFocused = it.isFocused }
}

/**
 * Full-screen GachaSwipe CRT Stage: `#050308` void background, cyan hairline monitor side
 * borders, subtle 3px horizontal scanlines, and slow vertical scan band.
 */
@Composable
fun MikaTerminalStage(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "scanlines")
    val bandFraction by infinite.animateFloat(
        initialValue = -0.15f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanlineScroll"
    )

    val stageGlow = MikaColors.GlowIntensity
    val borderAlpha = if (MikaColors.IsNeonGlowMode) (0.28f + 0.25f * stageGlow).coerceAtMost(0.60f) else 0.08f
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MikaColors.VoidBg)
            .border(width = if (MikaColors.IsNeonGlowMode) 1.5.dp else 1.dp, color = MikaColors.NeonCyan.copy(alpha = borderAlpha))
    ) {
        content()

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (MikaColors.IsNeonGlowMode) {
                // Sleek ambient neon top/bottom edge luminescence in Neon Glow skin
                val edgeAlpha = (0.12f * stageGlow).coerceIn(0.03f, 0.22f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MikaColors.NeonCyan.copy(alpha = edgeAlpha),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = 140.dp.toPx()
                    ),
                    topLeft = Offset.Zero,
                    size = Size(size.width, 140.dp.toPx())
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MikaColors.NeonCyan.copy(alpha = edgeAlpha * 0.8f)
                        ),
                        startY = size.height - 120.dp.toPx(),
                        endY = size.height
                    ),
                    topLeft = Offset(0f, size.height - 120.dp.toPx()),
                    size = Size(size.width, 120.dp.toPx())
                )
            } else {
                val stripeStep = 3.dp.toPx().coerceAtLeast(3f)
                var y = 0f
                val stripeColor = MikaColors.NeonCyan.copy(alpha = 0.016f)
                while (y < size.height) {
                    drawLine(
                        color = stripeColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                    y += stripeStep
                }

                val bandHeight = 60.dp.toPx()
                val bandTop = bandFraction * size.height
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MikaColors.NeonCyan.copy(alpha = 0.045f),
                            Color.Transparent
                        ),
                        startY = bandTop,
                        endY = bandTop + bandHeight
                    ),
                    topLeft = Offset(0f, bandTop),
                    size = Size(size.width, bandHeight)
                )
            }
        }
    }
}

/**
 * Single animated hardware accelerator status badge (`[CORE_OS]`, `[CPU NEON]`, `[GPU FP16]`, `[IDLE]`).
 *
 * ✨ FIXED-SIZE SLOT LAW (v1.3.3 scar): callers MUST pass a fixed `modifier` width (the header
 * uses `Modifier.width(112.dp)` — sized for the longest label `[NPU ACTIVE]`). The badge never
 * sizes itself to its text, because label swaps (`[IDLE]` → `[CPU NEON]`) and the VRAM countdown
 * appearing/disappearing would re-measure the parent row and shift the whole layout. Label and
 * countdown render `maxLines = 1` and clip inside the slot instead of growing it.
 */
/**
 * Single animated hardware accelerator status badge (`[CORE_OS]`, `[CPU NEON]`, `[GPU FP16]`, `[IDLE]`).
 *
 * ✨ FIXED-SIZE SLOT LAW (v1.3.3 scar) + MARQUEE UPGRADE (v1.5.0): callers MUST pass a fixed
 * `modifier` width — the header uses `Modifier.width(84.dp)`, sized for the resting `[IDLE]`
 * label so the pinned header never grows. Longer states (`[NPU ACTIVE]`, VRAM countdown) no
 * longer resize anything: the text is measured and, on overflow, scrolls as a smooth bounce
 * marquee inside the slot (clipToBounds), while the pulsing dot and border stay anchored.
 */
@Composable
fun MikaStatusBadge(
    badge: HardwareStatusBadge,
    vramCountdownSec: Int,
    modifier: Modifier = Modifier
) {
    val badgeColor = when (badge) {
        HardwareStatusBadge.NPU_ACTIVE -> MikaColors.TerminalGreen
        HardwareStatusBadge.GPU_FP16 -> MikaColors.MikaAmber
        HardwareStatusBadge.CPU_NEON -> MikaColors.TerminalGreen
        HardwareStatusBadge.CORE_OS -> MikaColors.NeonCyan
        HardwareStatusBadge.PROXY_LINK -> MikaColors.NeonPurple
        HardwareStatusBadge.IDLE -> MikaColors.DimText
    }

    val infinite = rememberInfiniteTransition(label = "statusPulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = if (badge == HardwareStatusBadge.IDLE) 0.65f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    // ✨ MARQUEE (v1.5.0): label + optional VRAM countdown compose the display string; overflow
    // scrolls as a hold -> scroll -> hold -> return bounce inside the fixed slot.
    val countdown = if (vramCountdownSec > 0 && badge != HardwareStatusBadge.IDLE) {
        " %d:%02d".format(vramCountdownSec / 60, vramCountdownSec % 60)
    } else ""
    val display = badge.terminalLabel + countdown

    val marqueeTextStyle = remember {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp
        )
    }

    val textMeasurer = rememberTextMeasurer()
    val textLayout = remember(display, badge) {
        textMeasurer.measure(text = AnnotatedString(display), style = marqueeTextStyle)
    }
    val cycleDisplay = "$display   •   "
    val cycleLayout = remember(cycleDisplay, badge) {
        textMeasurer.measure(text = AnnotatedString(cycleDisplay), style = marqueeTextStyle)
    }

    var laneWidthPx by remember { mutableStateOf(0f) }
    val needsMarquee = laneWidthPx > 0f && textLayout.size.width.toFloat() > laneWidthPx
    val cycleWidthPx = cycleLayout.size.width.toFloat().coerceAtLeast(1f)
    val marqueeDurationMs = ((cycleWidthPx / 38f) * 1000f).roundToInt().coerceIn(2200, 7500)

    val marquee = rememberInfiniteTransition(label = "badgeMarquee")
    val marqueeShift by marquee.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = marqueeDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "badgeShift"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(badgeColor.copy(alpha = 0.12f))
            .border(1.dp, badgeColor.copy(alpha = pulseAlpha), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(badgeColor.copy(alpha = pulseAlpha))
        )
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            contentAlignment = if (needsMarquee) Alignment.CenterStart else Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .clipToBounds()
                .onSizeChanged { laneWidthPx = it.width.toFloat() }
        ) {
            if (!needsMarquee) {
                Text(
                    text = display,
                    color = badgeColor,
                    style = marqueeTextStyle,
                    softWrap = false,
                    maxLines = 1
                )
            } else {
                val shiftPx = -(cycleWidthPx * marqueeShift)
                Text(
                    text = "$cycleDisplay$display",
                    color = badgeColor,
                    style = marqueeTextStyle,
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier.offset { IntOffset(shiftPx.roundToInt(), 0) }
                )
            }
        }
    }
}

/**
 * Recipe 1 & 3 Terminal Panel with hairline neon border.
 */
@Composable
fun MikaGlowPanel(
    modifier: Modifier = Modifier,
    accent: Color = MikaColors.TerminalGreen,
    cornerRadius: Dp = 6.dp,
    contentPadding: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val effectiveAccent = MikaColors.resolveAccent(accent)
    val glowMult = if (MikaColors.IsNeonGlowMode) (0.55f + 0.45f * MikaColors.GlowIntensity) else 1f
    val borderAlpha = if (MikaColors.IsNeonGlowMode) (0.52f * glowMult).coerceIn(0.32f, 0.85f) else 0.30f
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (MikaColors.IsNeonGlowMode) 12.dp else 8.dp,
                shape = RoundedCornerShape(cornerRadius),
                spotColor = effectiveAccent.copy(alpha = if (MikaColors.IsNeonGlowMode) 0.45f * MikaColors.GlowIntensity else 0.25f)
            )
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MikaColors.RaisedCardBg.copy(alpha = 0.96f),
                        MikaColors.PanelBg.copy(alpha = 0.98f)
                    )
                )
            )
            .border(if (MikaColors.IsNeonGlowMode) 1.25.dp else 1.dp, effectiveAccent.copy(alpha = borderAlpha), RoundedCornerShape(cornerRadius))
            .padding(contentPadding),
        content = content
    )
}

/**
 * Custom Cyberpunk Vector Icon Set rendered natively via Compose Canvas.
 * Replaces system emojis & plain Unicode glyphs across PURR-TTS.
 */
enum class CyberIconType {
    PLAY,
    STOP,
    PAUSE,
    RECORD_DOT,
    DOWNLOAD,
    SLAM_IN,
    EJECT,
    REFRESH,
    CHECK,
    CLOSE,
    CHEVRON_DOWN,
    CHEVRON_UP,
    CHEVRON_LEFT,
    CHEVRON_RIGHT,
    MIC_WAVE,
    WRENCH_TOOL,
    BUS_PLUG,
    DNA_CLONE,
    CHIP_EXPANSION,
    BOLT_ACCEL,
    ALERT_TRIANGLE,
    CHAT_SLANG,
    DUAL_MASKS,
    HEADPHONES_DSP,
    WAVE_TAPER,
    SHIELD_GUARD,
    CROSSFADE_LINK,
    SLIDER_FADER,
    SPEED_GAUGE,
    PITCH_TUNER,
    BOOK_NARRATOR,
    WINDOW_FRAME,
    SCREW_BOSS,
    STAR_BADGE,
    SETTINGS_COG
}

@Composable
fun MikaCyberIcon(
    type: CyberIconType,
    tint: Color,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = (minOf(w, h) * 0.13f).coerceAtLeast(1.4.dp.toPx())
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val thin = Stroke(width = (sw * 0.78f).coerceAtLeast(1.dp.toPx()), cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (type) {
            CyberIconType.PLAY -> {
                val p = Path().apply {
                    moveTo(w * 0.24f, h * 0.16f)
                    lineTo(w * 0.84f, h * 0.50f)
                    lineTo(w * 0.24f, h * 0.84f)
                    close()
                }
                drawPath(p, color = tint)
            }
            CyberIconType.STOP -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.20f, h * 0.20f),
                    size = Size(w * 0.60f, h * 0.60f),
                    cornerRadius = CornerRadius(w * 0.08f)
                )
            }
            CyberIconType.PAUSE -> {
                drawRoundRect(tint, Offset(w * 0.22f, h * 0.18f), Size(w * 0.20f, h * 0.64f), CornerRadius(w * 0.06f))
                drawRoundRect(tint, Offset(w * 0.58f, h * 0.18f), Size(w * 0.20f, h * 0.64f), CornerRadius(w * 0.06f))
            }
            CyberIconType.RECORD_DOT -> {
                drawCircle(tint.copy(alpha = 0.35f), radius = w * 0.42f, center = Offset(w * 0.5f, h * 0.5f), style = thin)
                drawCircle(tint, radius = w * 0.24f, center = Offset(w * 0.5f, h * 0.5f))
            }
            CyberIconType.DOWNLOAD -> {
                drawLine(tint, Offset(w * 0.50f, h * 0.14f), Offset(w * 0.50f, h * 0.64f), strokeWidth = sw, cap = StrokeCap.Round)
                val head = Path().apply {
                    moveTo(w * 0.28f, h * 0.46f)
                    lineTo(w * 0.50f, h * 0.68f)
                    lineTo(w * 0.72f, h * 0.46f)
                }
                drawPath(head, color = tint, style = stroke)
                val tray = Path().apply {
                    moveTo(w * 0.18f, h * 0.72f)
                    lineTo(w * 0.18f, h * 0.86f)
                    lineTo(w * 0.82f, h * 0.86f)
                    lineTo(w * 0.82f, h * 0.72f)
                }
                drawPath(tray, color = tint, style = stroke)
            }
            CyberIconType.SLAM_IN -> {
                drawLine(tint, Offset(w * 0.50f, h * 0.14f), Offset(w * 0.50f, h * 0.62f), strokeWidth = sw, cap = StrokeCap.Round)
                val head = Path().apply {
                    moveTo(w * 0.26f, h * 0.44f)
                    lineTo(w * 0.50f, h * 0.68f)
                    lineTo(w * 0.74f, h * 0.44f)
                }
                drawPath(head, color = tint, style = stroke)
                drawLine(tint, Offset(w * 0.16f, h * 0.84f), Offset(w * 0.84f, h * 0.84f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            CyberIconType.EJECT -> {
                val tri = Path().apply {
                    moveTo(w * 0.50f, h * 0.18f)
                    lineTo(w * 0.82f, h * 0.58f)
                    lineTo(w * 0.18f, h * 0.58f)
                    close()
                }
                drawPath(tri, color = tint)
                drawRoundRect(tint, Offset(w * 0.18f, h * 0.70f), Size(w * 0.64f, h * 0.14f), CornerRadius(w * 0.05f))
            }
            CyberIconType.REFRESH -> {
                drawArc(
                    color = tint,
                    startAngle = -55f,
                    sweepAngle = 275f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.16f),
                    size = Size(w * 0.68f, h * 0.68f),
                    style = stroke
                )
                val arrow = Path().apply {
                    moveTo(w * 0.66f, h * 0.14f)
                    lineTo(w * 0.86f, h * 0.26f)
                    lineTo(w * 0.68f, h * 0.36f)
                }
                drawPath(arrow, color = tint, style = thin)
            }
            CyberIconType.CHECK -> {
                val p = Path().apply {
                    moveTo(w * 0.18f, h * 0.52f)
                    lineTo(w * 0.42f, h * 0.76f)
                    lineTo(w * 0.84f, h * 0.24f)
                }
                drawPath(p, color = tint, style = stroke)
            }
            CyberIconType.CLOSE -> {
                drawLine(tint, Offset(w * 0.22f, h * 0.22f), Offset(w * 0.78f, h * 0.78f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.78f, h * 0.22f), Offset(w * 0.22f, h * 0.78f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            CyberIconType.CHEVRON_DOWN -> {
                val p = Path().apply {
                    moveTo(w * 0.20f, h * 0.34f)
                    lineTo(w * 0.50f, h * 0.66f)
                    lineTo(w * 0.80f, h * 0.34f)
                }
                drawPath(p, color = tint, style = stroke)
            }
            CyberIconType.CHEVRON_UP -> {
                val p = Path().apply {
                    moveTo(w * 0.20f, h * 0.66f)
                    lineTo(w * 0.50f, h * 0.34f)
                    lineTo(w * 0.80f, h * 0.66f)
                }
                drawPath(p, color = tint, style = stroke)
            }
            CyberIconType.CHEVRON_LEFT -> {
                val p = Path().apply {
                    moveTo(w * 0.66f, h * 0.20f)
                    lineTo(w * 0.34f, h * 0.50f)
                    lineTo(w * 0.66f, h * 0.80f)
                }
                drawPath(p, color = tint, style = stroke)
            }
            CyberIconType.CHEVRON_RIGHT -> {
                val p = Path().apply {
                    moveTo(w * 0.34f, h * 0.20f)
                    lineTo(w * 0.66f, h * 0.50f)
                    lineTo(w * 0.34f, h * 0.80f)
                }
                drawPath(p, color = tint, style = stroke)
            }
            CyberIconType.MIC_WAVE -> {
                drawRoundRect(tint, Offset(w * 0.36f, h * 0.12f), Size(w * 0.28f, h * 0.44f), CornerRadius(w * 0.14f), style = stroke)
                drawArc(tint, 0f, 180f, false, Offset(w * 0.24f, h * 0.28f), Size(w * 0.52f, h * 0.40f), style = thin)
                drawLine(tint, Offset(w * 0.50f, h * 0.68f), Offset(w * 0.50f, h * 0.86f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, h * 0.86f), Offset(w * 0.66f, h * 0.86f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            CyberIconType.WRENCH_TOOL -> {
                drawArc(tint, 35f, 270f, false, Offset(w * 0.14f, h * 0.14f), Size(w * 0.44f, h * 0.44f), style = thin)
                drawLine(tint, Offset(w * 0.44f, h * 0.44f), Offset(w * 0.84f, h * 0.84f), strokeWidth = sw * 1.2f, cap = StrokeCap.Round)
            }
            CyberIconType.BUS_PLUG -> {
                drawRoundRect(tint, Offset(w * 0.24f, h * 0.32f), Size(w * 0.52f, h * 0.36f), CornerRadius(w * 0.08f), style = stroke)
                drawLine(tint, Offset(w * 0.36f, h * 0.14f), Offset(w * 0.36f, h * 0.32f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.64f, h * 0.14f), Offset(w * 0.64f, h * 0.32f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.50f, h * 0.68f), Offset(w * 0.50f, h * 0.88f), strokeWidth = sw, cap = StrokeCap.Round)
            }
            CyberIconType.DNA_CLONE -> {
                val p1 = Path()
                val p2 = Path()
                for (i in 0..8) {
                    val t = i / 8f
                    val y = h * 0.14f + t * h * 0.72f
                    val x1 = w * 0.5f + sin(t * PI * 2.0).toFloat() * w * 0.26f
                    val x2 = w * 0.5f - sin(t * PI * 2.0).toFloat() * w * 0.26f
                    if (i == 0) {
                        p1.moveTo(x1, y); p2.moveTo(x2, y)
                    } else {
                        p1.lineTo(x1, y); p2.lineTo(x2, y)
                    }
                    if (i % 2 == 1) {
                        drawLine(tint.copy(alpha = 0.5f), Offset(x1, y), Offset(x2, y), strokeWidth = sw * 0.65f)
                    }
                }
                drawPath(p1, color = tint, style = thin)
                drawPath(p2, color = tint, style = thin)
            }
            CyberIconType.CHIP_EXPANSION -> {
                drawRoundRect(tint, Offset(w * 0.22f, h * 0.22f), Size(w * 0.56f, h * 0.56f), CornerRadius(w * 0.08f), style = stroke)
                drawLine(tint, Offset(w * 0.36f, h * 0.08f), Offset(w * 0.36f, h * 0.22f), strokeWidth = sw * 0.85f)
                drawLine(tint, Offset(w * 0.64f, h * 0.08f), Offset(w * 0.64f, h * 0.22f), strokeWidth = sw * 0.85f)
                drawLine(tint, Offset(w * 0.36f, h * 0.78f), Offset(w * 0.36f, h * 0.92f), strokeWidth = sw * 0.85f)
                drawLine(tint, Offset(w * 0.64f, h * 0.78f), Offset(w * 0.64f, h * 0.92f), strokeWidth = sw * 0.85f)
                drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.5f, h * 0.5f))
            }
            CyberIconType.BOLT_ACCEL, CyberIconType.SPEED_GAUGE -> {
                val bolt = Path().apply {
                    moveTo(w * 0.58f, h * 0.10f)
                    lineTo(w * 0.26f, h * 0.54f)
                    lineTo(w * 0.50f, h * 0.54f)
                    lineTo(w * 0.42f, h * 0.90f)
                    lineTo(w * 0.76f, h * 0.44f)
                    lineTo(w * 0.52f, h * 0.44f)
                    close()
                }
                drawPath(bolt, color = tint)
            }
            CyberIconType.ALERT_TRIANGLE -> {
                val tri = Path().apply {
                    moveTo(w * 0.50f, h * 0.14f)
                    lineTo(w * 0.86f, h * 0.82f)
                    lineTo(w * 0.14f, h * 0.82f)
                    close()
                }
                drawPath(tri, color = tint, style = stroke)
                drawLine(tint, Offset(w * 0.50f, h * 0.38f), Offset(w * 0.50f, h * 0.58f), strokeWidth = sw, cap = StrokeCap.Round)
                drawCircle(tint, radius = sw * 0.55f, center = Offset(w * 0.50f, h * 0.71f))
            }
            CyberIconType.CHAT_SLANG -> {
                val box = Path().apply {
                    moveTo(w * 0.14f, h * 0.18f)
                    lineTo(w * 0.86f, h * 0.18f)
                    lineTo(w * 0.86f, h * 0.68f)
                    lineTo(w * 0.46f, h * 0.68f)
                    lineTo(w * 0.24f, h * 0.84f)
                    lineTo(w * 0.24f, h * 0.68f)
                    lineTo(w * 0.14f, h * 0.68f)
                    close()
                }
                drawPath(box, color = tint, style = thin)
                drawCircle(tint, radius = sw * 0.55f, center = Offset(w * 0.34f, h * 0.43f))
                drawCircle(tint, radius = sw * 0.55f, center = Offset(w * 0.50f, h * 0.43f))
                drawCircle(tint, radius = sw * 0.55f, center = Offset(w * 0.66f, h * 0.43f))
            }
            CyberIconType.DUAL_MASKS -> {
                drawCircle(tint, radius = w * 0.24f, center = Offset(w * 0.36f, h * 0.42f), style = thin)
                drawCircle(tint, radius = w * 0.24f, center = Offset(w * 0.64f, h * 0.58f), style = thin)
                drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.30f, h * 0.38f))
                drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.70f, h * 0.54f))
            }
            CyberIconType.HEADPHONES_DSP -> {
                drawArc(tint, 180f, 180f, false, Offset(w * 0.16f, h * 0.18f), Size(w * 0.68f, h * 0.68f), style = stroke)
                drawRoundRect(tint, Offset(w * 0.14f, h * 0.50f), Size(w * 0.18f, h * 0.32f), CornerRadius(w * 0.06f))
                drawRoundRect(tint, Offset(w * 0.68f, h * 0.50f), Size(w * 0.18f, h * 0.32f), CornerRadius(w * 0.06f))
            }
            CyberIconType.WAVE_TAPER -> {
                val p = Path().apply {
                    moveTo(w * 0.10f, h * 0.52f)
                    lineTo(w * 0.26f, h * 0.20f)
                    lineTo(w * 0.42f, h * 0.80f)
                    lineTo(w * 0.58f, h * 0.34f)
                    lineTo(w * 0.74f, h * 0.64f)
                    lineTo(w * 0.90f, h * 0.52f)
                }
                drawPath(p, color = tint, style = stroke)
            }
            CyberIconType.SHIELD_GUARD -> {
                val shield = Path().apply {
                    moveTo(w * 0.50f, h * 0.12f)
                    lineTo(w * 0.84f, h * 0.26f)
                    lineTo(w * 0.80f, h * 0.62f)
                    lineTo(w * 0.50f, h * 0.88f)
                    lineTo(w * 0.20f, h * 0.62f)
                    lineTo(w * 0.16f, h * 0.26f)
                    close()
                }
                drawPath(shield, color = tint, style = stroke)
            }
            CyberIconType.CROSSFADE_LINK -> {
                val p1 = Path().apply {
                    moveTo(w * 0.14f, h * 0.74f)
                    lineTo(w * 0.42f, h * 0.74f)
                    lineTo(w * 0.60f, h * 0.26f)
                    lineTo(w * 0.86f, h * 0.26f)
                }
                val p2 = Path().apply {
                    moveTo(w * 0.14f, h * 0.26f)
                    lineTo(w * 0.42f, h * 0.26f)
                    lineTo(w * 0.60f, h * 0.74f)
                    lineTo(w * 0.86f, h * 0.74f)
                }
                drawPath(p1, color = tint, style = thin)
                drawPath(p2, color = tint, style = thin)
            }
            CyberIconType.SLIDER_FADER -> {
                val xs = listOf(w * 0.25f, w * 0.50f, w * 0.75f)
                val ks = listOf(h * 0.34f, h * 0.66f, h * 0.30f)
                xs.forEachIndexed { i, x ->
                    drawLine(tint.copy(alpha = 0.45f), Offset(x, h * 0.16f), Offset(x, h * 0.84f), strokeWidth = sw * 0.8f)
                    drawRoundRect(tint, Offset(x - w * 0.08f, ks[i] - h * 0.07f), Size(w * 0.16f, h * 0.14f), CornerRadius(w * 0.04f))
                }
            }
            CyberIconType.PITCH_TUNER -> {
                drawLine(tint, Offset(w * 0.42f, h * 0.20f), Offset(w * 0.42f, h * 0.72f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.42f, h * 0.20f), Offset(w * 0.78f, h * 0.28f), strokeWidth = sw, cap = StrokeCap.Round)
                drawCircle(tint, radius = w * 0.16f, center = Offset(w * 0.32f, h * 0.74f))
            }
            CyberIconType.BOOK_NARRATOR -> {
                drawRoundRect(tint, Offset(w * 0.16f, h * 0.18f), Size(w * 0.68f, h * 0.64f), CornerRadius(w * 0.07f), style = stroke)
                drawLine(tint, Offset(w * 0.50f, h * 0.18f), Offset(w * 0.50f, h * 0.82f), strokeWidth = sw * 0.85f)
            }
            CyberIconType.WINDOW_FRAME -> {
                drawRoundRect(tint, Offset(w * 0.14f, h * 0.18f), Size(w * 0.72f, h * 0.64f), CornerRadius(w * 0.08f), style = stroke)
                drawLine(tint, Offset(w * 0.14f, h * 0.38f), Offset(w * 0.86f, h * 0.38f), strokeWidth = sw * 0.85f)
            }
            CyberIconType.SCREW_BOSS -> {
                drawCircle(tint.copy(alpha = 0.7f), radius = w * 0.38f, center = Offset(w * 0.5f, h * 0.5f), style = thin)
                drawLine(tint, Offset(w * 0.28f, h * 0.50f), Offset(w * 0.72f, h * 0.50f), strokeWidth = sw * 0.75f)
                drawLine(tint, Offset(w * 0.50f, h * 0.28f), Offset(w * 0.50f, h * 0.72f), strokeWidth = sw * 0.75f)
            }
            CyberIconType.STAR_BADGE -> {
                drawCircle(tint, radius = w * 0.34f, center = Offset(w * 0.5f, h * 0.5f), style = thin)
                drawCircle(tint, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.5f))
            }
            CyberIconType.SETTINGS_COG -> {
                // Outer gear ring: 8 teeth drawn as short radial spokes + ring.
                drawCircle(tint, radius = w * 0.34f, center = Offset(w * 0.5f, h * 0.5f), style = thin)
                for (i in 0 until 8) {
                    val angle = Math.toRadians((i * 45.0))
                    val cx = w * 0.5f + (cos(angle)).toFloat() * w * 0.34f
                    val cy = h * 0.5f + (sin(angle)).toFloat() * h * 0.34f
                    val ex = w * 0.5f + (cos(angle)).toFloat() * w * 0.46f
                    val ey = h * 0.5f + (sin(angle)).toFloat() * h * 0.46f
                    drawLine(tint, Offset(cx, cy), Offset(ex, ey), strokeWidth = sw * 1.6f, cap = StrokeCap.Round)
                }
                // Inner ring + axle hole.
                drawCircle(tint, radius = w * 0.20f, center = Offset(w * 0.5f, h * 0.5f), style = thin)
                drawCircle(tint, radius = w * 0.09f, center = Offset(w * 0.5f, h * 0.5f))
            }
        }
    }
}

/**
 * Collapsible terminal disclosure section for keeping advanced settings tucked cleanly away.
 */
@Composable
fun ExpandableTerminalSection(
    title: String,
    subtitle: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    accent: Color = MikaColors.NeonCyan,
    content: @Composable ColumnScope.() -> Unit
) {
    MikaGlowPanel(accent = if (expanded) accent else accent.copy(alpha = 0.45f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MikaCyberIcon(
                    type = if (expanded) CyberIconType.CHEVRON_DOWN else CyberIconType.CHEVRON_RIGHT,
                    tint = accent,
                    size = 12.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = accent,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp,
                        letterSpacing = 0.8.sp
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            color = MikaColors.DimText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                    }
                }
            }
            Text(
                text = if (expanded) "[ HIDE ]" else "[ EXPAND ]",
                color = accent.copy(alpha = 0.8f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}

/**
 * Jetpack Compose port of GachaSwipe's `CyberDropdown.jsx`.
 */
@Composable
fun <T> CyberDropdown(
    label: String,
    options: List<T>,
    selectedOption: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MikaColors.NeonCyan
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = label,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.PanelBg)
                    .border(
                        width = 1.dp,
                        color = if (expanded) accent else accent.copy(alpha = 0.40f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = optionLabel(selectedOption),
                    color = MikaColors.VioletWhite,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                MikaCyberIcon(
                    type = if (expanded) CyberIconType.CHEVRON_UP else CyberIconType.CHEVRON_DOWN,
                    tint = accent,
                    size = 11.dp
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(MikaColors.PanelBg)
                    .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            ) {
                options.forEach { item ->
                    val isSelected = item == selectedOption
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = optionLabel(item),
                                    color = if (isSelected) accent else MikaColors.CyanWhite,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 11.sp
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    MikaCyberIcon(
                                        type = CyberIconType.CHECK,
                                        tint = MikaColors.NeonCyan,
                                        size = 12.dp
                                    )
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(item)
                        },
                        modifier = Modifier.background(
                            if (isSelected) accent.copy(alpha = 0.14f) else Color.Transparent
                        )
                    )
                }
            }
        }
    }
}

/**
 * Chunky, pressable GachaSwipe tag pill (4px radius, monospace bold uppercase) with optional custom vector icons.
 */
@Composable
fun MikaTagPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = MikaColors.TerminalGreen,
    inactiveColor: Color = MikaColors.DimText,
    leadingIcon: CyberIconType? = null,
    trailingIcon: CyberIconType? = null
) {
    val contentColor = if (selected) activeColor else inactiveColor
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) activeColor.copy(alpha = 0.16f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) activeColor else activeColor.copy(alpha = 0.22f),
                shape = RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (leadingIcon != null) {
            MikaCyberIcon(type = leadingIcon, tint = contentColor, size = 10.dp)
        }
        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = contentColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 0.6.sp
            )
        }
        if (trailingIcon != null) {
            MikaCyberIcon(type = trailingIcon, tint = contentColor, size = 10.dp)
        }
    }
}

/**
 * GachaSwipe `TerminalToast`: pulsing pink `>` prefix, 15ms/char typewriter reveal, blinking `_` cursor.
 */
@Composable
fun TerminalToastBanner(
    message: String?,
    modifier: Modifier = Modifier
) {
    var typed by remember(message) { mutableStateOf("") }
    LaunchedEffect(message) {
        typed = ""
        if (!message.isNullOrEmpty()) {
            for (i in 1..message.length) {
                typed = message.take(i)
                delay(15L)
            }
        }
    }

    val infinite = rememberInfiniteTransition(label = "toastCursor")
    val cursorAlpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 480, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blink"
    )

    AnimatedVisibility(
        visible = !message.isNullOrEmpty(),
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(350)),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MikaColors.VoidBg.copy(alpha = 0.94f))
                .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Text(
                text = "> ",
                color = MikaColors.NeonPink,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp
            )
            Text(
                text = typed,
                color = MikaColors.NeonCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
            Text(
                text = "_",
                color = MikaColors.NeonCyan.copy(alpha = cursorAlpha),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            )
        }
    }
}

/**
 * Dramatic Cyberdeck ROM Cartridge Slot / Eject overlay inspired by GachaSwipe v2.7.81.
 */
@Composable
fun CartridgeSlamOverlay(
    spec: ModelBundleSpec?,
    isInsert: Boolean,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = spec != null,
        enter = fadeIn(tween(150)) + scaleIn(tween(220), initialScale = 0.92f),
        exit = fadeOut(tween(250))
    ) {
        if (spec != null) {
            val accent = if (isInsert) MikaColors.resolveAccent(Color(spec.accentHex)) else MikaColors.DangerRed
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MikaColors.VoidBg.copy(alpha = 0.88f))
                    .clickable(onClick = onDismiss)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Molded Cartridge Graphic
                    Column(
                        modifier = Modifier
                            .width(240.dp)
                            .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(MikaColors.HousingTop, MikaColors.CardViolet, MikaColors.PanelBg)
                                )
                            )
                            .border(2.dp, accent, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                            .padding(start = 12.dp, end = 12.dp, top = 10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            repeat(3) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .background(MikaColors.PrimaryText.copy(alpha = 0.16f))
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(accent.copy(alpha = 0.16f))
                                .border(1.dp, accent, RoundedCornerShape(4.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = spec.romCode,
                                    color = MikaColors.VoidBg,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 9.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(accent)
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                                Text(
                                    text = "// ${spec.badge}",
                                    color = accent,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 8.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = spec.name,
                                color = MikaColors.PrimaryText,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = spec.hardwareAccelNote,
                                color = MikaColors.PrimaryText.copy(alpha = 0.7f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MikaCyberIcon(
                                type = if (isInsert) CyberIconType.SLAM_IN else CyberIconType.EJECT,
                                tint = accent,
                                size = 10.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isInsert) "LOCKING BUS PINS" else "RELEASING BUS PINS",
                                color = accent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 8.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            MikaCyberIcon(
                                type = if (isInsert) CyberIconType.SLAM_IN else CyberIconType.EJECT,
                                tint = accent,
                                size = 10.dp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        // 12 Gold PCB Contact Pins
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MikaColors.DeepWellBg)
                                .border(1.dp, MikaColors.SsrGold.copy(alpha = 0.35f))
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            repeat(12) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(10.dp)
                                        .background(MikaColors.SsrGold)
                                )
                            }
                        }
                    }

                    // Motherboard Socket Mouth
                    Row(
                        modifier = Modifier
                            .width(260.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MikaColors.PanelBg)
                            .border(2.dp, accent, RoundedCornerShape(4.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isInsert) MikaColors.TerminalGreen else MikaColors.DangerRed)
                            )
                            Text(
                                text = if (isInsert) "BUS_LOCKED" else "UNMOUNTED",
                                color = if (isInsert) MikaColors.TerminalGreen else MikaColors.DangerRed,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 8.sp
                            )
                        }
                        Text(
                            text = "SLOT // ${spec.romCode}",
                            color = MikaColors.DimText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp
                        )
                    }

                    Column(
                        modifier = Modifier
                            .width(300.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MikaColors.PanelBg.copy(alpha = 0.96f))
                            .border(1.dp, accent, RoundedCornerShape(6.dp))
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MikaCyberIcon(
                                type = if (isInsert) CyberIconType.BOLT_ACCEL else CyberIconType.EJECT,
                                tint = if (isInsert) MikaColors.TerminalGreen else MikaColors.DangerRed,
                                size = 12.dp
                            )
                            Text(
                                text = if (isInsert) "[ ${spec.romCode} SLOTTED // ONLINE ]"
                                else "[ ${spec.romCode} EJECTED // OFFLINE ]",
                                color = if (isInsert) MikaColors.TerminalGreen else MikaColors.DangerRed,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 10.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isInsert) "// Voices mounted into active Cyberdeck bus."
                            else "// Weights evicted & voices unmounted from active bus.",
                            color = MikaColors.DimText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/**
 * Cassette-bay `#030206` 32-band live VU waveform visualizer (Green -> Gold -> Pink)
 * with live MIKASTOP chunk / silence monitor line.
 */
@Composable
fun LiveWaveformMonitor(
    waveformBins: FloatArray,
    activeChunkLabel: String,
    engineSummary: String,
    sentimentLabel: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MikaColors.DeepWellBg)
            .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "24KHZ_PCM_OSCILLOSCOPE",
                color = MikaColors.NeonCyan.copy(alpha = 0.8f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 1.0.sp
            )
            Text(
                text = "MOOD:$sentimentLabel",
                color = when (sentimentLabel) {
                    "LOVE", "FLUSTERED" -> MikaColors.NeonPink
                    "ANGER" -> MikaColors.DangerRed
                    "JOY" -> MikaColors.SsrGold
                    "SADNESS" -> MikaColors.NeonCyan
                    else -> MikaColors.TerminalGreen
                },
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
        ) {
            val count = waveformBins.size.coerceAtLeast(1)
            val gap = 3.dp.toPx()
            val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(2f)
            val midY = size.height / 2f

            drawLine(
                color = MikaColors.NeonCyan.copy(alpha = 0.18f),
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1f
            )

            for (i in 0 until count) {
                val amp = waveformBins[i].coerceIn(0.04f, 1.0f)
                val barH = (size.height * amp).coerceAtLeast(3f)
                val barColor = when {
                    amp > 0.78f -> MikaColors.NeonPink
                    amp > 0.48f -> MikaColors.SsrGold
                    else -> MikaColors.TerminalGreen
                }
                val x = i * (barWidth + gap)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, midY - barH / 2f),
                    size = Size(barWidth, barH),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = activeChunkLabel,
            color = if (activeChunkLabel.contains("MIKASTOP")) MikaColors.MikaAmber else MikaColors.CyanWhite,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "// $engineSummary",
            color = MikaColors.DimText,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Stylized Cyberdeck Expandable Voice Dropdown Selector:
 * Displays the active voice card cleanly at the top, with an expandable searchable/filterable
 * scrollable drawer of voices + inline audition buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MikaVoiceDropdownSelector(
    label: String,
    voices: List<VoiceDescriptor>,
    selectedVoice: VoiceDescriptor?,
    onSelectVoice: (VoiceDescriptor) -> Unit,
    onSampleVoice: (VoiceDescriptor) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MikaColors.TerminalGreen,
    onRefreshAction: (() -> Unit)? = null,
    emptyHint: String = "No voices available. Slam a cartridge in Tab 3!"
) {
    var expanded by remember(label) { mutableStateOf(false) }
    var searchQuery by remember(label) { mutableStateOf("") }
    var genderFilter by remember(label) { mutableStateOf<VoiceGender?>(null) }

    val filteredVoices = remember(voices, searchQuery, genderFilter) {
        voices.filter { v ->
            if (genderFilter != null && v.gender != genderFilter) return@filter false
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.trim().lowercase()
                val matchName = v.displayName.lowercase().contains(q) || v.id.lowercase().contains(q)
                val matchLang = v.languageCode.lowercase().contains(q)
                val matchTag = v.nativeTags.any { it.lowercase().contains(q) }
                if (!matchName && !matchLang && !matchTag) return@filter false
            }
            true
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Header row with label and optional Refresh trigger
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 11.sp,
                letterSpacing = 0.6.sp,
                modifier = Modifier.weight(1f)
            )
            if (onRefreshAction != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onRefreshAction)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    MikaCyberIcon(type = CyberIconType.REFRESH, tint = MikaColors.NeonCyan, size = 10.dp)
                    Text(
                        text = "REFRESH",
                        color = MikaColors.NeonCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                }
            }
        }

        // Stylized Selected Voice Trigger Box
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MikaColors.DeepWellBg)
                .border(
                    width = if (expanded) 1.5.dp else 1.dp,
                    color = if (expanded) accent else accent.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable { if (voices.isNotEmpty()) expanded = !expanded }
                .padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (selectedVoice != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "[${selectedVoice.gender.tag.uppercase()} | ${selectedVoice.languageCode}]",
                                color = accent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 9.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(accent.copy(alpha = 0.14f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                            Text(
                                text = selectedVoice.nativeTags.take(2).joinToString(" / "),
                                color = MikaColors.DimText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = selectedVoice.displayName,
                            color = MikaColors.PrimaryText,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = emptyHint,
                            color = MikaColors.DimText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedVoice != null) {
                        MikaTagPill(
                            label = "TEST",
                            selected = true,
                            activeColor = MikaColors.NeonCyan,
                            leadingIcon = CyberIconType.PLAY,
                            onClick = { onSampleVoice(selectedVoice) }
                        )
                    }
                    MikaTagPill(
                        label = if (expanded) "CLOSE" else "CHANGE",
                        selected = expanded,
                        activeColor = accent,
                        inactiveColor = accent,
                        leadingIcon = if (expanded) CyberIconType.CHEVRON_UP else CyberIconType.CHEVRON_DOWN,
                        onClick = { if (voices.isNotEmpty()) expanded = !expanded }
                    )
                }
            }
        }

        // Expandable Cyberdeck Search + Filter + Scrollable Voice List Panel
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MikaColors.DeepWellBg)
                    .border(1.5.dp, accent.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            "Search voice name, language, or vibe tag...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = MikaColors.DimText
                        )
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = accent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        unfocusedBorderColor = accent.copy(alpha = 0.35f),
                        focusedContainerColor = MikaColors.PanelBg,
                        unfocusedContainerColor = MikaColors.PanelBg
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        MikaTagPill(
                            label = "ALL",
                            selected = genderFilter == null,
                            activeColor = accent,
                            onClick = { genderFilter = null }
                        )
                        VoiceGender.entries.forEach { g ->
                            MikaTagPill(
                                label = g.tag.uppercase(),
                                selected = genderFilter == g,
                                activeColor = MikaColors.NeonCyan,
                                onClick = { genderFilter = if (genderFilter == g) null else g }
                            )
                        }
                    }
                    Text(
                        text = "[ ${filteredVoices.size} MATCH ]",
                        color = MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.5.sp
                    )
                }

                if (filteredVoices.isEmpty()) {
                    Text(
                        text = emptyHint,
                        color = MikaColors.DimText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        items(filteredVoices, key = { it.id }) { voice ->
                            val isSelected = selectedVoice?.id == voice.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isSelected) accent.copy(alpha = 0.16f)
                                        else MikaColors.PrimaryText.copy(alpha = 0.04f)
                                    )
                                    .border(
                                        width = if (isSelected) 1.dp else 0.5.dp,
                                        color = if (isSelected) accent else MikaColors.SubtleBorder,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        onSelectVoice(voice)
                                        expanded = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) accent else Color.Transparent)
                                            .border(1.2.dp, if (isSelected) accent else MikaColors.DimText, CircleShape)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = voice.displayName,
                                            color = if (isSelected) MikaColors.PrimaryText else MikaColors.CyanWhite,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "[${voice.languageCode}] ${voice.nativeTags.take(3).joinToString(" / ")}",
                                            color = if (isSelected) accent else MikaColors.DimText,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MikaColors.NeonCyan.copy(alpha = 0.14f))
                                        .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                        .clickable { onSampleVoice(voice) }
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    MikaCyberIcon(type = CyberIconType.PLAY, tint = MikaColors.NeonCyan, size = 10.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

enum class OsModuleIconType {
    PURR_CORE,
    DSP_RACK,
    SYMBOL_SHIELD,
    CLONE_STUDIO,
    AUDIO_EXPORTER,
    VOICE_DESIGN,
    AUDIOBOOK_CONSTRUCTOR,
    THEME_ENGINE,
    SCRIPT_STUDIO,
    VOICE_BENCHMARK,
    SRT_BATCH_STUDIO
}

/**
 * Custom Cyberdeck OS Vector SVG-Style Module Icon drawn via Compose Canvas.
 */
@Composable
fun MikaOsModuleIcon(
    type: OsModuleIconType,
    accentColor: Color,
    isOnline: Boolean = true,
    iconSize: Dp = 58.dp,
    modifier: Modifier = Modifier
) {
    val activeAlpha = if (isOnline) 1f else 0.42f
    val s = (iconSize.value / 58f).coerceIn(0.45f, 1.5f)
    val cornerDp = (10f * s).dp
    Box(
        modifier = modifier
            .size(iconSize)
            .clip(RoundedCornerShape(cornerDp))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.22f * activeAlpha),
                        MikaColors.PanelBg
                    )
                )
            )
            .border((1.2f * s).dp, accentColor.copy(alpha = 0.72f * activeAlpha), RoundedCornerShape(cornerDp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding((9f * s).dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = (2.0f * s).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val thinStroke = Stroke(width = (1.2f * s).dp.toPx(), cap = StrokeCap.Round)
            val drawColor = accentColor.copy(alpha = activeAlpha)

            // HUD Corner framing ticks
            val tick = w * 0.18f
            drawLine(drawColor.copy(alpha = 0.45f), Offset(0f, 0f), Offset(tick, 0f), strokeWidth = (1.4f * s).dp.toPx())
            drawLine(drawColor.copy(alpha = 0.45f), Offset(0f, 0f), Offset(0f, tick), strokeWidth = (1.4f * s).dp.toPx())
            drawLine(drawColor.copy(alpha = 0.45f), Offset(w, h), Offset(w - tick, h), strokeWidth = (1.4f * s).dp.toPx())
            drawLine(drawColor.copy(alpha = 0.45f), Offset(w, h), Offset(w, h - tick), strokeWidth = (1.4f * s).dp.toPx())

            when (type) {
                OsModuleIconType.PURR_CORE -> {
                    // Neural Pulse Core + Harmonic Sine Wave + Circuit Nodes
                    drawCircle(
                        color = drawColor.copy(alpha = 0.35f),
                        radius = w * 0.42f,
                        center = Offset(w * 0.5f, h * 0.5f),
                        style = thinStroke
                    )
                    val wave = Path().apply {
                        moveTo(w * 0.12f, h * 0.55f)
                        lineTo(w * 0.28f, h * 0.55f)
                        lineTo(w * 0.38f, h * 0.25f)
                        lineTo(w * 0.50f, h * 0.78f)
                        lineTo(w * 0.62f, h * 0.32f)
                        lineTo(w * 0.72f, h * 0.55f)
                        lineTo(w * 0.88f, h * 0.55f)
                    }
                    drawPath(wave, color = drawColor, style = stroke)
                    drawCircle(color = MikaColors.NeonCyan.copy(alpha = activeAlpha), radius = (2.6f * s).dp.toPx(), center = Offset(w * 0.12f, h * 0.55f))
                    drawCircle(color = MikaColors.NeonCyan.copy(alpha = activeAlpha), radius = (2.6f * s).dp.toPx(), center = Offset(w * 0.88f, h * 0.55f))
                }

                OsModuleIconType.DSP_RACK -> {
                    // 3-Band Parametric Equalizer Faders + Neon Caps
                    val xs = listOf(w * 0.24f, w * 0.50f, w * 0.76f)
                    val knobYs = listOf(h * 0.34f, h * 0.68f, h * 0.28f)
                    xs.forEachIndexed { idx, x ->
                        drawLine(drawColor.copy(alpha = 0.35f), Offset(x, h * 0.14f), Offset(x, h * 0.86f), strokeWidth = (2f * s).dp.toPx(), cap = StrokeCap.Round)
                        drawLine(drawColor, Offset(x, knobYs[idx]), Offset(x, h * 0.86f), strokeWidth = (2.4f * s).dp.toPx(), cap = StrokeCap.Round)
                        drawRoundRect(
                            color = drawColor,
                            topLeft = Offset(x - (5f * s).dp.toPx(), knobYs[idx] - (3f * s).dp.toPx()),
                            size = Size((10f * s).dp.toPx(), (6f * s).dp.toPx()),
                            cornerRadius = CornerRadius((2f * s).dp.toPx())
                        )
                    }
                }

                OsModuleIconType.SYMBOL_SHIELD -> {
                    // Hexagonal Cyber Shield with Filter Brackets </>
                    val shield = Path().apply {
                        moveTo(w * 0.50f, h * 0.10f)
                        lineTo(w * 0.84f, h * 0.24f)
                        lineTo(w * 0.80f, h * 0.62f)
                        lineTo(w * 0.50f, h * 0.88f)
                        lineTo(w * 0.20f, h * 0.62f)
                        lineTo(w * 0.16f, h * 0.24f)
                        close()
                    }
                    drawPath(shield, color = drawColor, style = stroke)
                    // Inner filter slash / node dots
                    drawLine(MikaColors.NeonCyan.copy(alpha = activeAlpha), Offset(w * 0.36f, h * 0.60f), Offset(w * 0.64f, h * 0.34f), strokeWidth = (2f * s).dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(drawColor, radius = (2.4f * s).dp.toPx(), center = Offset(w * 0.38f, h * 0.38f))
                    drawCircle(drawColor, radius = (2.4f * s).dp.toPx(), center = Offset(w * 0.62f, h * 0.56f))
                }

                OsModuleIconType.CLONE_STUDIO -> {
                    // Studio Condenser Mic Capsule + Dual Helix Waves
                    drawRoundRect(
                        color = drawColor,
                        topLeft = Offset(w * 0.36f, h * 0.14f),
                        size = Size(w * 0.28f, h * 0.44f),
                        cornerRadius = CornerRadius((6f * s).dp.toPx()),
                        style = stroke
                    )
                    // Helix Orbit Arcs
                    val steps = 12
                    val strand1 = Path()
                    val strand2 = Path()
                    for (i in 0..steps) {
                        val t = i.toFloat() / steps.toFloat()
                        val yPos = h * 0.18f + t * h * 0.64f
                        val x1 = w * 0.50f + sin(t * PI * 2.0).toFloat() * w * 0.30f
                        val x2 = w * 0.50f - sin(t * PI * 2.0).toFloat() * w * 0.30f
                        if (i == 0) {
                            strand1.moveTo(x1, yPos)
                            strand2.moveTo(x2, yPos)
                        } else {
                            strand1.lineTo(x1, yPos)
                            strand2.lineTo(x2, yPos)
                        }
                    }
                    drawPath(strand1, color = MikaColors.NeonCyan.copy(alpha = 0.65f * activeAlpha), style = thinStroke)
                    drawPath(strand2, color = drawColor.copy(alpha = 0.65f), style = thinStroke)
                    drawLine(drawColor, Offset(w * 0.50f, h * 0.66f), Offset(w * 0.50f, h * 0.84f), strokeWidth = (2f * s).dp.toPx(), cap = StrokeCap.Round)
                    drawLine(drawColor, Offset(w * 0.34f, h * 0.84f), Offset(w * 0.66f, h * 0.84f), strokeWidth = (2f * s).dp.toPx(), cap = StrokeCap.Round)
                }

                OsModuleIconType.AUDIO_EXPORTER -> {
                    // Soundwave bars at top + download arrow into glowing media tray
                    drawLine(MikaColors.NeonCyan.copy(alpha = activeAlpha), Offset(w * 0.20f, h * 0.22f), Offset(w * 0.20f, h * 0.42f), strokeWidth = (2f * s).dp.toPx(), cap = StrokeCap.Round)
                    drawLine(MikaColors.NeonCyan.copy(alpha = activeAlpha), Offset(w * 0.32f, h * 0.14f), Offset(w * 0.32f, h * 0.46f), strokeWidth = (2f * s).dp.toPx(), cap = StrokeCap.Round)
                    drawLine(MikaColors.NeonCyan.copy(alpha = activeAlpha), Offset(w * 0.68f, h * 0.14f), Offset(w * 0.68f, h * 0.46f), strokeWidth = (2f * s).dp.toPx(), cap = StrokeCap.Round)
                    drawLine(MikaColors.NeonCyan.copy(alpha = activeAlpha), Offset(w * 0.80f, h * 0.22f), Offset(w * 0.80f, h * 0.42f), strokeWidth = (2f * s).dp.toPx(), cap = StrokeCap.Round)

                    // Center download arrow shaft + chevron
                    drawLine(drawColor, Offset(w * 0.50f, h * 0.14f), Offset(w * 0.50f, h * 0.62f), strokeWidth = (2.2f * s).dp.toPx(), cap = StrokeCap.Round)
                    val arrowHead = Path().apply {
                        moveTo(w * 0.34f, h * 0.48f)
                        lineTo(w * 0.50f, h * 0.65f)
                        lineTo(w * 0.66f, h * 0.48f)
                    }
                    drawPath(arrowHead, color = drawColor, style = stroke)

                    // Bottom media storage tray
                    val tray = Path().apply {
                        moveTo(w * 0.16f, h * 0.66f)
                        lineTo(w * 0.16f, h * 0.84f)
                        lineTo(w * 0.84f, h * 0.84f)
                        lineTo(w * 0.84f, h * 0.66f)
                    }
                    drawPath(tray, color = drawColor, style = stroke)
                }

                OsModuleIconType.VOICE_DESIGN -> {
                    // Sculpted orbital ring + 4-point neural spark + sculpted pitch bars
                    drawCircle(
                        color = drawColor.copy(alpha = 0.36f),
                        radius = w * 0.40f,
                        center = Offset(w * 0.50f, h * 0.52f),
                        style = thinStroke
                    )
                    // Sculpted neural spark star (top-right)
                    val cx = w * 0.68f
                    val cy = h * 0.28f
                    val spark = Path().apply {
                        moveTo(cx, cy - h * 0.14f)
                        lineTo(cx + w * 0.04f, cy - h * 0.04f)
                        lineTo(cx + w * 0.14f, cy)
                        lineTo(cx + w * 0.04f, cy + h * 0.04f)
                        lineTo(cx, cy + h * 0.14f)
                        lineTo(cx - w * 0.04f, cy + h * 0.04f)
                        lineTo(cx - w * 0.14f, cy)
                        lineTo(cx - w * 0.04f, cy - h * 0.04f)
                        close()
                    }
                    drawPath(spark, color = MikaColors.NeonCyan.copy(alpha = activeAlpha))

                    // Sculpted symmetric formant wave bars
                    val barXs = listOf(w * 0.22f, w * 0.36f, w * 0.50f, w * 0.64f, w * 0.78f)
                    val tops = listOf(h * 0.52f, h * 0.34f, h * 0.26f, h * 0.42f, h * 0.54f)
                    val bots = listOf(h * 0.70f, h * 0.80f, h * 0.84f, h * 0.78f, h * 0.68f)
                    barXs.forEachIndexed { idx, x ->
                        drawLine(
                            color = drawColor,
                            start = Offset(x, tops[idx]),
                            end = Offset(x, bots[idx]),
                            strokeWidth = (2.3f * s).dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }
                }

                OsModuleIconType.AUDIOBOOK_CONSTRUCTOR -> {
                    // Open cyber-book spreads + spine + glowing bookmark & audio track lines
                    val leftPage = Path().apply {
                        moveTo(w * 0.12f, h * 0.20f)
                        lineTo(w * 0.48f, h * 0.26f)
                        lineTo(w * 0.48f, h * 0.82f)
                        lineTo(w * 0.12f, h * 0.76f)
                        close()
                    }
                    val rightPage = Path().apply {
                        moveTo(w * 0.88f, h * 0.20f)
                        lineTo(w * 0.52f, h * 0.26f)
                        lineTo(w * 0.52f, h * 0.82f)
                        lineTo(w * 0.88f, h * 0.76f)
                        close()
                    }
                    drawPath(leftPage, color = drawColor, style = stroke)
                    drawPath(rightPage, color = drawColor, style = stroke)
                    // Bookmark ribbon + chapter audio bars inside pages
                    drawLine(MikaColors.SsrGold.copy(alpha = activeAlpha), Offset(w * 0.38f, h * 0.24f), Offset(w * 0.38f, h * 0.62f), strokeWidth = (2.1f * s).dp.toPx(), cap = StrokeCap.Round)
                    drawLine(MikaColors.NeonPink.copy(alpha = activeAlpha), Offset(w * 0.22f, h * 0.40f), Offset(w * 0.30f, h * 0.40f), strokeWidth = (1.6f * s).dp.toPx(), cap = StrokeCap.Round)
                    drawLine(MikaColors.NeonPink.copy(alpha = activeAlpha), Offset(w * 0.22f, h * 0.54f), Offset(w * 0.30f, h * 0.54f), strokeWidth = (1.6f * s).dp.toPx(), cap = StrokeCap.Round)
                    drawLine(MikaColors.NeonCyan.copy(alpha = activeAlpha), Offset(w * 0.62f, h * 0.42f), Offset(w * 0.62f, h * 0.60f), strokeWidth = (2.0f * s).dp.toPx(), cap = StrokeCap.Round)
                    drawLine(MikaColors.NeonCyan.copy(alpha = activeAlpha), Offset(w * 0.71f, h * 0.34f), Offset(w * 0.71f, h * 0.66f), strokeWidth = (2.0f * s).dp.toPx(), cap = StrokeCap.Round)
                    drawLine(MikaColors.NeonCyan.copy(alpha = activeAlpha), Offset(w * 0.80f, h * 0.44f), Offset(w * 0.80f, h * 0.58f), strokeWidth = (2.0f * s).dp.toPx(), cap = StrokeCap.Round)
                }

                OsModuleIconType.THEME_ENGINE -> {
                    // Neon Theme Prism: outer glowing aperture ring, spectral quadrant nodes & inner diamond prism
                    drawCircle(
                        color = drawColor.copy(alpha = 0.35f),
                        radius = w * 0.40f,
                        center = Offset(w * 0.50f, h * 0.50f),
                        style = thinStroke
                    )
                    val prism = Path().apply {
                        moveTo(w * 0.50f, h * 0.16f)
                        lineTo(w * 0.84f, h * 0.50f)
                        lineTo(w * 0.50f, h * 0.84f)
                        lineTo(w * 0.16f, h * 0.50f)
                        close()
                    }
                    drawPath(prism, color = drawColor, style = stroke)
                    val core = Path().apply {
                        moveTo(w * 0.50f, h * 0.32f)
                        lineTo(w * 0.68f, h * 0.50f)
                        lineTo(w * 0.50f, h * 0.68f)
                        lineTo(w * 0.32f, h * 0.50f)
                        close()
                    }
                    drawPath(core, color = MikaColors.NeonCyan.copy(alpha = 0.85f * activeAlpha), style = thinStroke)
                    val nodeR = (2.8f * s).dp.toPx()
                    drawCircle(MikaColors.NeonCyan.copy(alpha = activeAlpha), radius = nodeR, center = Offset(w * 0.50f, h * 0.16f))
                    drawCircle(MikaColors.NeonPink.copy(alpha = activeAlpha), radius = nodeR, center = Offset(w * 0.84f, h * 0.50f))
                    drawCircle(MikaColors.TerminalGreen.copy(alpha = activeAlpha), radius = nodeR, center = Offset(w * 0.50f, h * 0.84f))
                    drawCircle(MikaColors.SsrGold.copy(alpha = activeAlpha), radius = nodeR, center = Offset(w * 0.16f, h * 0.50f))
                }

                OsModuleIconType.SCRIPT_STUDIO -> {
                    // Multi-Speaker Dialogue Script Studio: dual interlocking speech balloons + waveform turns
                    val leftBubble = Path().apply {
                        moveTo(w * 0.10f, h * 0.16f)
                        lineTo(w * 0.64f, h * 0.16f)
                        lineTo(w * 0.64f, h * 0.50f)
                        lineTo(w * 0.32f, h * 0.50f)
                        lineTo(w * 0.18f, h * 0.62f)
                        lineTo(w * 0.18f, h * 0.50f)
                        lineTo(w * 0.10f, h * 0.50f)
                        close()
                    }
                    drawPath(leftBubble, color = drawColor, style = stroke)
                    val rightBubble = Path().apply {
                        moveTo(w * 0.36f, h * 0.44f)
                        lineTo(w * 0.90f, h * 0.44f)
                        lineTo(w * 0.90f, h * 0.78f)
                        lineTo(w * 0.82f, h * 0.78f)
                        lineTo(w * 0.82f, h * 0.90f)
                        lineTo(w * 0.68f, h * 0.78f)
                        lineTo(w * 0.36f, h * 0.78f)
                        close()
                    }
                    drawPath(rightBubble, color = MikaColors.NeonCyan.copy(alpha = activeAlpha), style = thinStroke)
                    drawLine(drawColor, Offset(w * 0.22f, h * 0.33f), Offset(w * 0.52f, h * 0.33f), strokeWidth = (2.0f * s).dp.toPx(), cap = StrokeCap.Round)
                    drawLine(MikaColors.SsrGold.copy(alpha = activeAlpha), Offset(w * 0.48f, h * 0.61f), Offset(w * 0.78f, h * 0.61f), strokeWidth = (2.0f * s).dp.toPx(), cap = StrokeCap.Round)
                }

                OsModuleIconType.VOICE_BENCHMARK -> {
                    // Voice A/B Benchmark Meter: dual A/B comparison bars + speed gauge needle
                    drawArc(
                        color = drawColor.copy(alpha = 0.40f),
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(w * 0.14f, h * 0.14f),
                        size = Size(w * 0.72f, h * 0.72f),
                        style = thinStroke
                    )
                    // Gauge needle
                    drawLine(
                        color = MikaColors.NeonPink.copy(alpha = activeAlpha),
                        start = Offset(w * 0.50f, h * 0.50f),
                        end = Offset(w * 0.74f, h * 0.28f),
                        strokeWidth = (2.1f * s).dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawCircle(color = MikaColors.NeonCyan.copy(alpha = activeAlpha), radius = (3.0f * s).dp.toPx(), center = Offset(w * 0.50f, h * 0.50f))
                    // Dual A / B latency comparison tracks at bottom
                    drawRoundRect(
                        color = drawColor,
                        topLeft = Offset(w * 0.16f, h * 0.64f),
                        size = Size(w * 0.68f, h * 0.10f),
                        cornerRadius = CornerRadius((3f * s).dp.toPx())
                    )
                    drawRoundRect(
                        color = MikaColors.NeonCyan.copy(alpha = activeAlpha),
                        topLeft = Offset(w * 0.16f, h * 0.80f),
                        size = Size(w * 0.46f, h * 0.10f),
                        cornerRadius = CornerRadius((3f * s).dp.toPx())
                    )
                }

                OsModuleIconType.SRT_BATCH_STUDIO -> {
                    // SRT Subtitle & Batch Queue: clapper/caption timeline bars + precision clock node
                    drawRoundRect(
                        color = drawColor,
                        topLeft = Offset(w * 0.12f, h * 0.16f),
                        size = Size(w * 0.76f, h * 0.68f),
                        cornerRadius = CornerRadius((5f * s).dp.toPx()),
                        style = stroke
                    )
                    drawLine(drawColor.copy(alpha = 0.45f), Offset(w * 0.12f, h * 0.36f), Offset(w * 0.88f, h * 0.36f), strokeWidth = (1.6f * s).dp.toPx())
                    // Timed subtitle bars inside frame
                    drawLine(MikaColors.NeonCyan.copy(alpha = activeAlpha), Offset(w * 0.24f, h * 0.50f), Offset(w * 0.76f, h * 0.50f), strokeWidth = (2.4f * s).dp.toPx(), cap = StrokeCap.Round)
                    drawLine(MikaColors.NeonPink.copy(alpha = activeAlpha), Offset(w * 0.24f, h * 0.66f), Offset(w * 0.60f, h * 0.66f), strokeWidth = (2.4f * s).dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(MikaColors.TerminalGreen.copy(alpha = activeAlpha), radius = (2.8f * s).dp.toPx(), center = Offset(w * 0.74f, h * 0.66f))
                }
            }
        }
    }
}

/**
 * Floating GachaSwipe-inspired Glassmorphic Bottom HUD Navigation Capsule:
 * Translucent see-through backdrop, compact input-bar profile above Android gesture pill,
 * and icon-only custom vector buttons (Voice | Modules | Cartridge Bay).
 */
@Composable
fun MikaBottomHudBar(
    activeTab: Int,
    activeToolCount: Int,
    slottedTtsCount: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabAccents = listOf(
        MikaColors.NeonCyan,
        if (activeToolCount > 0) MikaColors.NeonPink else MikaColors.DimText,
        if (slottedTtsCount > 0) MikaColors.TerminalGreen else MikaColors.MikaAmber
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(16.dp),
                    spotColor = MikaColors.NeonCyan.copy(alpha = 0.25f)
                )
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MikaColors.RaisedCardBg.copy(alpha = 0.82f),
                            MikaColors.PanelBg.copy(alpha = 0.90f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            MikaColors.NeonCyan.copy(alpha = 0.40f),
                            MikaColors.NeonPink.copy(alpha = 0.28f),
                            MikaColors.TerminalGreen.copy(alpha = 0.40f)
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabAccents.forEachIndexed { index, accent ->
                val selected = activeTab == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (selected) {
                                Brush.radialGradient(
                                    colors = listOf(
                                        accent.copy(alpha = 0.24f),
                                        MikaColors.DeepWellBg.copy(alpha = 0.86f)
                                    )
                                )
                            } else {
                                Brush.verticalGradient(
                                    colors = listOf(
                                        MikaColors.VoidBg.copy(alpha = 0.45f),
                                        MikaColors.VoidBg.copy(alpha = 0.62f)
                                    )
                                )
                            }
                        )
                        .border(
                            width = if (selected) 1.4.dp else 0.8.dp,
                            color = if (selected) accent else MikaColors.NeonCyan.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(11.dp)
                        )
                        .clickable { onSelectTab(index) },
                    contentAlignment = Alignment.Center
                ) {
                    // Icon-only custom SVG/Canvas vector artwork
                    Canvas(modifier = Modifier.size(22.dp)) {
                        val w = size.width
                        val h = size.height
                        val c = if (selected) accent else MikaColors.CyanWhite.copy(alpha = 0.54f)
                        val sw = 1.9.dp.toPx()
                        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val thin = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

                        when (index) {
                            0 -> {
                                // Voice Tab Icon: Harmonic Waveform inside Precision Outer Arc
                                drawArc(c.copy(alpha = if (selected) 0.5f else 0.28f), -50f, 100f, false, Offset(w * 0.06f, h * 0.08f), Size(w * 0.88f, h * 0.84f), style = thin)
                                drawArc(c.copy(alpha = if (selected) 0.5f else 0.28f), 130f, 100f, false, Offset(w * 0.06f, h * 0.08f), Size(w * 0.88f, h * 0.84f), style = thin)
                                drawLine(c, Offset(w * 0.24f, h * 0.42f), Offset(w * 0.24f, h * 0.62f), strokeWidth = sw, cap = StrokeCap.Round)
                                drawLine(c, Offset(w * 0.42f, h * 0.20f), Offset(w * 0.42f, h * 0.80f), strokeWidth = sw, cap = StrokeCap.Round)
                                drawLine(c, Offset(w * 0.60f, h * 0.28f), Offset(w * 0.60f, h * 0.72f), strokeWidth = sw, cap = StrokeCap.Round)
                                drawLine(c, Offset(w * 0.76f, h * 0.38f), Offset(w * 0.76f, h * 0.64f), strokeWidth = sw, cap = StrokeCap.Round)
                            }
                            1 -> {
                                // Modules Tab Icon: 2x2 Cyberdeck OS App Grid + Core Node
                                val box = w * 0.36f
                                drawRoundRect(c, Offset(w * 0.06f, h * 0.06f), Size(box, box), CornerRadius(3.dp.toPx()), style = stroke)
                                drawRoundRect(c, Offset(w * 0.58f, h * 0.06f), Size(box, box), CornerRadius(3.dp.toPx()), style = stroke)
                                drawRoundRect(c, Offset(w * 0.06f, h * 0.58f), Size(box, box), CornerRadius(3.dp.toPx()), style = stroke)
                                drawRoundRect(c, Offset(w * 0.58f, h * 0.58f), Size(box, box), CornerRadius(3.dp.toPx()), style = stroke)
                                drawCircle(
                                    color = if (selected) accent else c.copy(alpha = 0.6f),
                                    radius = 2.4.dp.toPx(),
                                    center = Offset(w * 0.50f, h * 0.50f)
                                )
                            }
                            else -> {
                                // Cartridge Bay Tab Icon: Molded ROM Shell + Grip Lines + 4 Gold PCB Edge Pins
                                drawRoundRect(c, Offset(w * 0.14f, h * 0.08f), Size(w * 0.72f, h * 0.68f), CornerRadius(3.dp.toPx()), style = stroke)
                                drawLine(c.copy(alpha = 0.65f), Offset(w * 0.28f, h * 0.26f), Offset(w * 0.72f, h * 0.26f), strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
                                drawLine(c.copy(alpha = 0.65f), Offset(w * 0.28f, h * 0.42f), Offset(w * 0.72f, h * 0.42f), strokeWidth = 1.4.dp.toPx(), cap = StrokeCap.Round)
                                val pinColor = if (selected) MikaColors.SsrGold else c
                                drawLine(pinColor, Offset(w * 0.24f, h * 0.78f), Offset(w * 0.24f, h * 0.94f), strokeWidth = sw, cap = StrokeCap.Round)
                                drawLine(pinColor, Offset(w * 0.41f, h * 0.78f), Offset(w * 0.41f, h * 0.94f), strokeWidth = sw, cap = StrokeCap.Round)
                                drawLine(pinColor, Offset(w * 0.59f, h * 0.78f), Offset(w * 0.59f, h * 0.94f), strokeWidth = sw, cap = StrokeCap.Round)
                                drawLine(pinColor, Offset(w * 0.76f, h * 0.78f), Offset(w * 0.76f, h * 0.94f), strokeWidth = sw, cap = StrokeCap.Round)
                            }
                        }
                    }

                    if (selected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 3.dp)
                                .width(20.dp)
                                .height(2.5.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(accent)
                        )
                    }
                }
            }
        }
    }
}
