package com.projectmika.purrtts.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmika.purrtts.core.purr.HardwareStatusBadge
import com.projectmika.purrtts.core.purr.ModelBundleSpec
import com.projectmika.purrtts.core.purr.VoiceDescriptor
import com.projectmika.purrtts.core.purr.VoiceGender
import kotlinx.coroutines.delay

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MikaColors.VoidBg)
            .border(width = 1.dp, color = MikaColors.NeonCyan.copy(alpha = 0.08f))
    ) {
        content()

        Canvas(modifier = Modifier.fillMaxSize()) {
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

/**
 * Single animated hardware accelerator status badge (`[CORE_OS]`, `[CPU NEON]`, `[GPU FP16]`, `[IDLE]`).
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

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(badgeColor.copy(alpha = 0.12f))
            .border(1.dp, badgeColor.copy(alpha = pulseAlpha), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(badgeColor.copy(alpha = pulseAlpha))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = badge.terminalLabel,
            color = badgeColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp,
            letterSpacing = 0.8.sp
        )
        if (vramCountdownSec > 0 && badge != HardwareStatusBadge.IDLE) {
            val mins = vramCountdownSec / 60
            val secs = vramCountdownSec % 60
            Text(
                text = " ${mins}:%02d".format(secs),
                color = badgeColor.copy(alpha = 0.72f),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(cornerRadius), spotColor = accent.copy(alpha = 0.25f))
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MikaColors.RaisedCardBg.copy(alpha = 0.96f),
                        MikaColors.PanelBg.copy(alpha = 0.98f)
                    )
                )
            )
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(cornerRadius))
            .padding(contentPadding),
        content = content
    )
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${if (expanded) "▼" else "▸"} $title",
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
                Text(
                    text = if (expanded) "▲" else "▼",
                    color = accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
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
                                    Text(
                                        text = "✓",
                                        color = MikaColors.NeonCyan,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
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
 * Chunky, pressable GachaSwipe tag pill (4px radius, monospace bold uppercase).
 */
@Composable
fun MikaTagPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = MikaColors.TerminalGreen,
    inactiveColor: Color = Color.White.copy(alpha = 0.45f)
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) activeColor.copy(alpha = 0.16f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) activeColor else activeColor.copy(alpha = 0.22f),
                shape = RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = if (selected) activeColor else inactiveColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 0.6.sp
        )
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
            val accent = if (isInsert) Color(spec.accentHex) else MikaColors.DangerRed
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xD805030A))
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
                                    listOf(Color(0xFF252038), Color(0xFF141122), Color(0xFF0D0A18))
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
                                        .background(Color.White.copy(alpha = 0.16f))
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
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = spec.hardwareAccelNote,
                                color = Color.White.copy(alpha = 0.7f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isInsert) "▼ LOCKING BUS PINS ▼" else "▲ RELEASING BUS PINS ▲",
                            color = accent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        // 12 Gold PCB Contact Pins
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF08060E))
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
                            .background(Color(0xFF0A0812))
                            .border(2.dp, accent, RoundedCornerShape(4.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isInsert) "● BUS_LOCKED" else "○ UNMOUNTED",
                            color = if (isInsert) MikaColors.TerminalGreen else MikaColors.DangerRed,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 8.sp
                        )
                        Text(
                            text = "SLOT // ${spec.romCode}",
                            color = Color.White.copy(alpha = 0.55f),
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
                        Text(
                            text = if (isInsert) "⚡ [ ${spec.romCode} SLOTTED // ONLINE ] ⚡"
                            else "⏏️ [ ${spec.romCode} EJECTED // OFFLINE ]",
                            color = if (isInsert) MikaColors.TerminalGreen else MikaColors.DangerRed,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp
                        )
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
                Text(
                    text = "↻ REFRESH",
                    color = MikaColors.NeonCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onRefreshAction)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
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
                                text = "[${selectedVoice.gender.tag.uppercase()} • ${selectedVoice.languageCode}]",
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
                                text = selectedVoice.nativeTags.take(2).joinToString(" • "),
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
                            color = Color.White,
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
                            label = "▶ TEST",
                            selected = true,
                            activeColor = MikaColors.NeonCyan,
                            onClick = { onSampleVoice(selectedVoice) }
                        )
                    }
                    MikaTagPill(
                        label = if (expanded) "▲ CLOSE" else "▼ CHANGE",
                        selected = expanded,
                        activeColor = accent,
                        inactiveColor = accent,
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
                    .background(Color(0xFF06040C))
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
                                        else Color.White.copy(alpha = 0.03f)
                                    )
                                    .border(
                                        width = if (isSelected) 1.dp else 0.5.dp,
                                        color = if (isSelected) accent else Color.White.copy(alpha = 0.12f),
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
                                    Text(
                                        text = if (isSelected) "●" else "○",
                                        color = if (isSelected) accent else MikaColors.DimText,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = voice.displayName,
                                            color = if (isSelected) Color.White else MikaColors.CyanWhite,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "[${voice.languageCode}] ${voice.nativeTags.take(3).joinToString(" • ")}",
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
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "▶",
                                        color = MikaColors.NeonCyan,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
