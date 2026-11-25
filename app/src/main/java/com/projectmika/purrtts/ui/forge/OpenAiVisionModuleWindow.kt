package com.projectmika.purrtts.ui.forge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectmika.purrtts.core.purr.OpenAiEndpointUtils
import com.projectmika.purrtts.core.purr.OpenAiVisionProviderPreset
import com.projectmika.purrtts.core.purr.PurrForgeState
import com.projectmika.purrtts.core.purr.SavedOpenAiVisionEngine
import com.projectmika.purrtts.ui.theme.CyberDropdown
import com.projectmika.purrtts.ui.theme.CyberIconType
import com.projectmika.purrtts.ui.theme.MikaColors
import com.projectmika.purrtts.ui.theme.MikaCyberIcon
import com.projectmika.purrtts.ui.theme.MikaGlowPanel
import com.projectmika.purrtts.ui.theme.MikaTagPill
import com.projectmika.purrtts.ui.theme.bringIntoViewWithIme

/**
 * ✨ TOOL-12: OPTIONAL OPENAI-COMPATIBLE VISION API ENGINE HUB ✨
 *
 * Disabled (ejected) by default in Cartridge Bay -> TOOLS.
 * Allows users to configure, test, save, edit, and delete named Vision-capable LLM endpoints
 * using pre-configured services (NanoGPT, OpenRouter, Clinepass, OpenAI, Together AI, Groq)
 * or a full Custom OpenAI Endpoint at the bottom of the service dropdown. Saved engines
 * automatically surface in Manga Dub Studio's Vision LLM selector while this module is active.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OpenAiVisionModuleWindowBody(
    forgeState: PurrForgeState,
    viewModel: TtsForgeViewModel
) {
    var editingEngineId by remember { mutableStateOf<String?>(null) }
    var selectedProvider by remember { mutableStateOf(OpenAiVisionProviderPreset.NANOGPT) }
    var nameInput by remember { mutableStateOf("") }
    var endpointInput by remember { mutableStateOf(OpenAiVisionProviderPreset.NANOGPT.defaultEndpointUrl) }
    var apiKeyInput by remember { mutableStateOf("") }
    var modelInput by remember { mutableStateOf(OpenAiVisionProviderPreset.NANOGPT.defaultModelHint) }
    var showApiKey by remember { mutableStateOf(false) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var testResultBanner by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun resetFormToProvider(preset: OpenAiVisionProviderPreset) {
        editingEngineId = null
        selectedProvider = preset
        nameInput = ""
        endpointInput = preset.defaultEndpointUrl
        apiKeyInput = ""
        modelInput = preset.defaultModelHint
        testResultBanner = null
    }

    MikaGlowPanel(accent = MikaColors.NeonCyan) {
        Text(
            text = if (editingEngineId == null) {
                "> CONFIGURE_OPENAI_VISION_ENGINE"
            } else {
                "> EDIT_SAVED_VISION_ENGINE"
            },
            color = MikaColors.NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.5.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Configure any OpenAI-compatible Vision LLM (API key + model). Saved engines appear in Manga Dub Studio's LLM dropdown while TOOL-12 is slotted.",
            color = MikaColors.CyanWhite.copy(alpha = 0.80f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            lineHeight = 13.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        CyberDropdown(
            label = "> API_SERVICE_PROVIDER",
            options = OpenAiVisionProviderPreset.entries,
            selectedOption = selectedProvider,
            optionLabel = { preset ->
                if (preset.isCustom) "Custom OpenAI Endpoint" else preset.label
            },
            onSelect = { nextPreset ->
                val prevHint = selectedProvider.defaultModelHint
                selectedProvider = nextPreset
                if (!nextPreset.isCustom) {
                    endpointInput = nextPreset.defaultEndpointUrl
                    if (modelInput.isBlank() || modelInput == prevHint) {
                        modelInput = nextPreset.defaultModelHint
                    }
                    if (nameInput.isBlank()) {
                        nameInput = "${nextPreset.label} Vision"
                    }
                } else {
                    if (endpointInput.isBlank() || endpointInput == OpenAiVisionProviderPreset.NANOGPT.defaultEndpointUrl) {
                        endpointInput = nextPreset.defaultEndpointUrl
                    }
                }
                testResultBanner = null
            },
            accent = MikaColors.NeonCyan
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = it },
            label = { Text("ENGINE_NAME (DISPLAYED IN STUDIO)", fontSize = 8.sp, fontFamily = FontFamily.Monospace) },
            placeholder = {
                Text(
                    text = if (selectedProvider.isCustom) "My Custom VLM" else "${selectedProvider.label} Vision",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MikaColors.DimText
                )
            },
            singleLine = true,
            textStyle = TextStyle(color = MikaColors.PrimaryText, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.NeonCyan,
                unfocusedBorderColor = MikaColors.NeonCyan.copy(alpha = 0.35f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (selectedProvider.isCustom) {
            OutlinedTextField(
                value = endpointInput,
                onValueChange = { endpointInput = it },
                label = { Text("CUSTOM_OPENAI_ENDPOINT_URL", fontSize = 8.sp, fontFamily = FontFamily.Monospace) },
                placeholder = {
                    Text(
                        text = "https://your-server.example/v1/chat/completions",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MikaColors.DimText
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MikaColors.NeonPurple,
                    unfocusedBorderColor = MikaColors.NeonPurple.copy(alpha = 0.45f),
                    focusedContainerColor = MikaColors.DeepWellBg,
                    unfocusedContainerColor = MikaColors.DeepWellBg
                ),
                modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
            )
            Spacer(modifier = Modifier.height(6.dp))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.DeepWellBg)
                    .border(1.dp, MikaColors.NeonCyan.copy(alpha = 0.25f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "PRESET_ENDPOINT_URL (${selectedProvider.label.uppercase()})",
                    color = MikaColors.DimText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 7.8.sp
                )
                Text(
                    text = selectedProvider.defaultEndpointUrl,
                    color = MikaColors.NeonCyan,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            label = { Text("API_KEY (BEARER TOKEN)", fontSize = 8.sp, fontFamily = FontFamily.Monospace) },
            placeholder = {
                Text(
                    text = if (selectedProvider.isCustom) "Optional for local servers" else "sk-...",
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MikaColors.DimText
                )
            },
            singleLine = true,
            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                MikaTagPill(
                    label = if (showApiKey) "HIDE" else "SHOW",
                    selected = showApiKey,
                    activeColor = MikaColors.NeonCyan,
                    onClick = { showApiKey = !showApiKey },
                    modifier = Modifier.padding(end = 6.dp)
                )
            },
            textStyle = TextStyle(color = MikaColors.PrimaryText, fontFamily = FontFamily.Monospace, fontSize = 10.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.NeonCyan,
                unfocusedBorderColor = MikaColors.NeonCyan.copy(alpha = 0.35f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
        )
        Spacer(modifier = Modifier.height(6.dp))

        OutlinedTextField(
            value = modelInput,
            onValueChange = { modelInput = it },
            label = { Text("VISION_MODEL_ID", fontSize = 8.sp, fontFamily = FontFamily.Monospace) },
            placeholder = {
                Text(
                    text = selectedProvider.defaultModelHint,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MikaColors.DimText
                )
            },
            singleLine = true,
            textStyle = TextStyle(color = MikaColors.CyanWhite, fontFamily = FontFamily.Monospace, fontSize = 10.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MikaColors.NeonPink,
                unfocusedBorderColor = MikaColors.NeonPink.copy(alpha = 0.35f),
                focusedContainerColor = MikaColors.DeepWellBg,
                unfocusedContainerColor = MikaColors.DeepWellBg
            ),
            modifier = Modifier.fillMaxWidth().bringIntoViewWithIme()
        )
        Spacer(modifier = Modifier.height(8.dp))

        val effectiveUrl = if (selectedProvider.isCustom) {
            OpenAiEndpointUtils.normalizeChatCompletionsUrl(endpointInput)
        } else {
            selectedProvider.defaultEndpointUrl
        }
        val effectiveModel = modelInput.trim().ifBlank { selectedProvider.defaultModelHint }
        val effectiveName = nameInput.trim().ifBlank {
            if (selectedProvider.isCustom) effectiveModel else "${selectedProvider.label}: ${effectiveModel.substringAfterLast('/')}"
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.NeonCyan.copy(alpha = 0.14f))
                    .border(1.2.dp, MikaColors.NeonCyan, RoundedCornerShape(6.dp))
                    .clickable(enabled = !isTestingConnection && effectiveUrl.isNotBlank()) {
                        isTestingConnection = true
                        testResultBanner = null
                        viewModel.testOpenAiVisionEngine(
                            endpointUrl = effectiveUrl,
                            apiKey = apiKeyInput,
                            modelId = effectiveModel
                        ) { res ->
                            isTestingConnection = false
                            testResultBanner = if (res.isSuccess) {
                                true to "CONNECTED: ${res.getOrNull().orEmpty().take(80)}"
                            } else {
                                false to "ERROR: ${res.exceptionOrNull()?.message ?: "Request failed"}"
                            }
                        }
                    }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(type = CyberIconType.BOLT_ACCEL, tint = MikaColors.NeonCyan, size = 12.dp)
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (isTestingConnection) "TESTING..." else "TEST API",
                    color = MikaColors.NeonCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.5.sp
                )
            }

            Row(
                modifier = Modifier
                    .weight(1.2f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MikaColors.TerminalGreen.copy(alpha = 0.20f))
                    .border(1.4.dp, MikaColors.TerminalGreen, RoundedCornerShape(6.dp))
                    .clickable(enabled = effectiveUrl.isNotBlank() && effectiveModel.isNotBlank()) {
                        viewModel.saveOpenAiVisionEngine(
                            existingId = editingEngineId,
                            name = effectiveName,
                            provider = selectedProvider,
                            endpointUrl = effectiveUrl,
                            apiKey = apiKeyInput,
                            modelId = effectiveModel
                        )
                        resetFormToProvider(selectedProvider)
                    }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MikaCyberIcon(type = CyberIconType.CHECK, tint = MikaColors.TerminalGreen, size = 12.dp)
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (editingEngineId == null) "SAVE ENGINE" else "UPDATE ENGINE",
                    color = MikaColors.TerminalGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 9.5.sp
                )
            }
        }

        if (editingEngineId != null) {
            Spacer(modifier = Modifier.height(6.dp))
            MikaTagPill(
                label = "CANCEL EDIT",
                selected = false,
                activeColor = MikaColors.MikaAmber,
                onClick = { resetFormToProvider(selectedProvider) }
            )
        }

        testResultBanner?.let { (ok, msg) ->
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (ok) MikaColors.TerminalGreen.copy(alpha = 0.14f) else MikaColors.DangerRed.copy(alpha = 0.14f))
                    .border(1.dp, if (ok) MikaColors.TerminalGreen else MikaColors.DangerRed, RoundedCornerShape(5.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MikaCyberIcon(
                    type = if (ok) CyberIconType.CHECK else CyberIconType.ALERT_TRIANGLE,
                    tint = if (ok) MikaColors.TerminalGreen else MikaColors.DangerRed,
                    size = 12.dp
                )
                Text(
                    text = msg,
                    color = if (ok) MikaColors.TerminalGreen else MikaColors.DangerRed,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.5.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    MikaGlowPanel(accent = MikaColors.NeonPurple) {
        Text(
            text = "> SAVED_OPENAI_VISION_ENGINES (${forgeState.savedOpenAiVisionEngines.size})",
            color = MikaColors.NeonPurple,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (forgeState.savedOpenAiVisionEngines.isEmpty()) {
            Text(
                text = "No OpenAI Vision engines saved yet. Pick a provider preset above (or Custom OpenAI Endpoint), enter your key and vision model ID, and hit SAVE ENGINE.",
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                lineHeight = 13.sp
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                forgeState.savedOpenAiVisionEngines.forEach { engine ->
                    SavedOpenAiVisionEngineCard(
                        engine = engine,
                        isEditing = editingEngineId == engine.id,
                        onEdit = {
                            editingEngineId = engine.id
                            selectedProvider = engine.provider
                            nameInput = engine.name
                            endpointInput = engine.endpointUrl
                            apiKeyInput = engine.apiKey
                            modelInput = engine.modelId
                            testResultBanner = null
                        },
                        onDelete = {
                            if (editingEngineId == engine.id) {
                                resetFormToProvider(selectedProvider)
                            }
                            viewModel.deleteOpenAiVisionEngine(engine.id)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedOpenAiVisionEngineCard(
    engine: SavedOpenAiVisionEngine,
    isEditing: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val cardAccent = if (isEditing) MikaColors.SsrGold else MikaColors.NeonCyan
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MikaColors.DeepWellBg)
            .border(1.dp, cardAccent.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = engine.name,
                    color = MikaColors.PrimaryText,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "[${engine.provider.label.uppercase()}] • MODEL: ${engine.modelId}",
                    color = cardAccent,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MikaTagPill(
                    label = if (isEditing) "EDITING" else "EDIT",
                    selected = isEditing,
                    activeColor = MikaColors.SsrGold,
                    onClick = onEdit
                )
                MikaTagPill(
                    label = "DELETE",
                    selected = false,
                    activeColor = MikaColors.DangerRed,
                    onClick = onDelete
                )
            }
        }

        val maskedKey = if (engine.apiKey.isBlank()) {
            "NO KEY (OPEN ENDPOINT)"
        } else {
            "KEY ****${engine.apiKey.trim().takeLast(4)}"
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = engine.endpointUrl,
                color = MikaColors.DimText,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "[$maskedKey]",
                color = if (engine.apiKey.isBlank()) MikaColors.MikaAmber else MikaColors.TerminalGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp
            )
        }
    }
}
