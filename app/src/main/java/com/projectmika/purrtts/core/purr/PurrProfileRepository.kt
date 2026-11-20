package com.projectmika.purrtts.core.purr

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * ✨ MIKA'S PURR PROFILE, CARTRIDGE BUS & VOICE REPOSITORY ✨
 * Thread-safe JSON persistence backed by `filesDir/purr_forge_state.json`.
 * Tracks active vibe presets, slotted ROM cartridges, discovered device OS voices, and cloned voices.
 */
class PurrProfileRepository(filesDir: File) {

    constructor(context: Context) : this(context.filesDir)

    private val stateFile = File(filesDir, "purr_forge_state.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<PurrForgeState> = _state.asStateFlow()

    private val _discoveredSystemVoices = MutableStateFlow<List<VoiceDescriptor>>(emptyList())
    val discoveredSystemVoices: StateFlow<List<VoiceDescriptor>> = _discoveredSystemVoices.asStateFlow()

    val currentProfile: PurrProfile
        get() {
            val s = _state.value
            return s.profiles.firstOrNull { it.id == s.activeProfileId } ?: s.profiles.first()
        }

    fun selectProfile(profileId: String) {
        updateState { s ->
            if (s.profiles.any { it.id == profileId }) {
                // Preserve user's selected engine/voice choice when switching PURR vibe presets
                val prev = s.profiles.firstOrNull { it.id == s.activeProfileId }
                val updatedProfiles = if (prev != null) {
                    s.profiles.map { p ->
                        if (p.id == profileId) {
                            p.copy(
                                selectedCartridgeId = prev.selectedCartridgeId,
                                preferredBackend = prev.preferredBackend,
                                sherpaFamily = prev.sherpaFamily,
                                primaryVoiceId = prev.primaryVoiceId,
                                dialogueVoiceId = prev.dialogueVoiceId,
                                enableDualVoiceSplit = prev.enableDualVoiceSplit
                            )
                        } else {
                            p
                        }
                    }
                } else {
                    s.profiles
                }
                s.copy(activeProfileId = profileId, profiles = updatedProfiles)
            } else {
                s
            }
        }
    }

    fun updateActiveProfile(transform: (PurrProfile) -> PurrProfile) {
        updateState { s ->
            val updatedProfiles = s.profiles.map { profile ->
                if (profile.id == s.activeProfileId) transform(profile) else profile
            }
            s.copy(profiles = updatedProfiles)
        }
    }

    fun saveAsNewProfile(name: String) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        val newId = "custom_${cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "_")}_${System.currentTimeMillis() % 10000}"
        updateState { s ->
            val clone = currentProfile.copy(
                id = newId,
                name = cleanName,
                badgeTag = "CUSTOM",
                summary = "User-saved PURR profile"
            )
            s.copy(
                activeProfileId = newId,
                profiles = s.profiles + clone
            )
        }
    }

    fun setCartridgeSlotted(cartridgeId: String, slotted: Boolean) {
        updateState { s ->
            val nextSlotted = if (slotted) s.slottedCartridges + cartridgeId else s.slottedCartridges - cartridgeId
            val ttsCatalogIds = PurrDefaultPresets.modelCatalog()
                .filter { it.category == CartridgeCategory.TTS && !it.isSubCartridge }
                .map { it.id }
                .toSet()
            val fallbackCart = nextSlotted.firstOrNull { it in ttsCatalogIds }
            val fallbackSpec = fallbackCart?.let { id ->
                PurrDefaultPresets.modelCatalog().firstOrNull { it.id == id }
            }
            val availableVoices = PurrDefaultPresets.builtInVoices() + _discoveredSystemVoices.value
            val fallbackVoice = if (fallbackCart != null) {
                availableVoices.firstOrNull {
                    it.cartridgeId == fallbackCart &&
                        (it.requiredSubCartridgeId == null || isSubCartridgeEnabled(it.requiredSubCartridgeId))
                }
            } else {
                null
            }
            val nextProfiles = s.profiles.map { p ->
                if (!slotted && p.selectedCartridgeId == cartridgeId && fallbackCart != null) {
                    p.copy(
                        selectedCartridgeId = fallbackCart,
                        preferredBackend = fallbackVoice?.engine ?: fallbackSpec?.backend ?: p.preferredBackend,
                        sherpaFamily = fallbackSpec?.sherpaFamily ?: p.sherpaFamily,
                        primaryVoiceId = fallbackVoice?.id ?: p.primaryVoiceId
                    )
                } else {
                    p
                }
            }
            s.copy(slottedCartridges = nextSlotted, profiles = nextProfiles)
        }
    }

    fun isCartridgeSlotted(cartridgeId: String): Boolean =
        cartridgeId == "proxy_lan" || cartridgeId in _state.value.slottedCartridges

    fun isPurrEngineModuleActive(): Boolean = isCartridgeSlotted("tool_purr_engine")

    fun isDspModuleActive(): Boolean = isCartridgeSlotted("tool_dsp_rack")

    fun isSymbolShieldModuleActive(): Boolean = isCartridgeSlotted("tool_symbol_shield")

    fun isCloneStudioModuleActive(): Boolean = isCartridgeSlotted("tool_clone_studio")

    fun isAudioExporterModuleActive(): Boolean = isCartridgeSlotted("tool_audio_exporter")

    fun isVoiceDesignModuleActive(): Boolean = isCartridgeSlotted("tool_voice_design")

    fun isAudiobookConstructorModuleActive(): Boolean = isCartridgeSlotted("tool_audiobook_constructor")

    fun selectEngineCartridge(cartridgeId: String) {
        val spec = PurrDefaultPresets.modelCatalog().firstOrNull { it.id == cartridgeId }
        val voicesForCart = allAvailableVoices().filter { it.cartridgeId == cartridgeId }
        val defaultVoice = voicesForCart.firstOrNull()

        updateActiveProfile { p ->
            when (cartridgeId) {
                "rom_core_os", "core_os" -> p.copy(
                    selectedCartridgeId = "rom_core_os",
                    preferredBackend = BackendType.CORE_OS,
                    primaryVoiceId = voicesForCart.firstOrNull()?.id ?: "core_os_auto"
                )
                "proxy_lan" -> p.copy(
                    selectedCartridgeId = "proxy_lan",
                    preferredBackend = BackendType.EXTERNAL_PROXY,
                    primaryVoiceId = "proxy_openai_endpoint"
                )
                else -> p.copy(
                    selectedCartridgeId = cartridgeId,
                    preferredBackend = defaultVoice?.engine ?: spec?.backend ?: BackendType.SHERPA_ONNX,
                    sherpaFamily = defaultVoice?.sherpaFamily ?: spec?.sherpaFamily ?: p.sherpaFamily,
                    primaryVoiceId = defaultVoice?.id ?: p.primaryVoiceId
                )
            }
        }
    }

    /** ✨ ACADEMY (v1.5.0): persist the last-seen tutorial content version after finish/dismiss. */
    fun markTutorialSeen(version: Int) {
        updateState { s ->
            if (s.seenTutorialVersion >= version) s else s.copy(seenTutorialVersion = version)
        }
    }

    fun updateDiscoveredSystemVoices(voices: List<VoiceDescriptor>) {
        _discoveredSystemVoices.value = voices
    }

    fun toggleVoiceWhitelist(voiceId: String, whitelisted: Boolean) {
        updateActiveProfile { profile ->
            val newWhite = if (whitelisted) profile.whitelist + voiceId else profile.whitelist - voiceId
            val newBlack = if (whitelisted) profile.blacklist - voiceId else profile.blacklist
            profile.copy(whitelist = newWhite, blacklist = newBlack)
        }
    }

    fun toggleVoiceBlacklist(voiceId: String, blacklisted: Boolean) {
        updateActiveProfile { profile ->
            val newBlack = if (blacklisted) profile.blacklist + voiceId else profile.blacklist - voiceId
            val newWhite = if (blacklisted) profile.whitelist - voiceId else profile.whitelist
            profile.copy(whitelist = newWhite, blacklist = newBlack)
        }
    }

    fun bulkSetWhitelist(voiceIds: Collection<String>, whitelisted: Boolean) {
        updateActiveProfile { profile ->
            val idSet = voiceIds.toSet()
            if (whitelisted) {
                profile.copy(whitelist = profile.whitelist + idSet, blacklist = profile.blacklist - idSet)
            } else {
                profile.copy(whitelist = profile.whitelist - idSet, blacklist = profile.blacklist + idSet)
            }
        }
    }

    fun toggleCustomVoiceTag(voiceId: String, tag: String) {
        updateActiveProfile { profile ->
            val existing = profile.customVoiceTags[voiceId].orEmpty()
            val updated = if (tag in existing) existing - tag else existing + tag
            val nextMap = profile.customVoiceTags.toMutableMap()
            if (updated.isEmpty()) nextMap.remove(voiceId) else nextMap[voiceId] = updated
            profile.copy(customVoiceTags = nextMap)
        }
    }

    fun addOrUpdatePhoneticRule(rule: PhoneticRule) {
        updateState { s ->
            val filtered = s.customPhoneticRules.filterNot { it.id == rule.id }
            s.copy(customPhoneticRules = listOf(rule) + filtered)
        }
    }

    fun togglePhoneticRule(ruleId: String) {
        updateState { s ->
            s.copy(
                customPhoneticRules = s.customPhoneticRules.map {
                    if (it.id == ruleId) it.copy(enabled = !it.enabled) else it
                }
            )
        }
    }

    fun deletePhoneticRule(ruleId: String) {
        updateState { s ->
            s.copy(customPhoneticRules = s.customPhoneticRules.filterNot { it.id == ruleId })
        }
    }

    fun registerClonedVoice(voice: ClonedVoiceProfile) {
        val engineId = voice.resolvedCloneEngineId
        val targetCart = when (engineId) {
            "sub_qwen3_base_06b" -> "rom_qwen3_custom_06b"
            else -> "rom_clone_hub"
        }
        val targetBackend = when (engineId) {
            "sub_qwen3_base_06b" -> BackendType.QWEN3_NATIVE
            "sub_chatterbox_turbo" -> BackendType.ONNX_NATIVE
            else -> BackendType.SHERPA_ONNX
        }
        updateState { s ->
            val updatedClones = s.clonedVoices.filterNot { it.id == voice.id } + voice
            val updatedProfiles = s.profiles.map { p ->
                if (p.id == s.activeProfileId) {
                    p.copy(
                        selectedCartridgeId = targetCart,
                        primaryVoiceId = voice.id,
                        preferredBackend = targetBackend,
                        sherpaFamily = voice.family
                    )
                } else {
                    p
                }
            }
            val nextEnabledSubs = if (engineId.startsWith("sub_")) {
                s.enabledSubCartridges + engineId
            } else {
                s.enabledSubCartridges
            }
            s.copy(
                clonedVoices = updatedClones,
                profiles = updatedProfiles,
                slottedCartridges = s.slottedCartridges + targetCart,
                enabledSubCartridges = nextEnabledSubs
            )
        }
    }

    fun removeClonedVoice(voiceId: String) {
        updateState { s ->
            s.copy(clonedVoices = s.clonedVoices.filterNot { it.id == voiceId })
        }
    }

    fun registerExportedAudio(entry: ExportedAudioFile) {
        updateState { s ->
            val filtered = s.exportedAudioFiles.filterNot { it.id == entry.id }
            s.copy(exportedAudioFiles = (listOf(entry) + filtered).take(40))
        }
    }

    fun removeExportedAudio(entryId: String) {
        updateState { s ->
            s.copy(exportedAudioFiles = s.exportedAudioFiles.filterNot { it.id == entryId })
        }
    }

    fun saveVoiceDesign(design: SavedVoiceDesign) {
        updateState { s ->
            val filtered = s.savedVoiceDesigns.filterNot { it.id == design.id }
            s.copy(savedVoiceDesigns = (listOf(design) + filtered).take(40))
        }
    }

    fun deleteVoiceDesign(designId: String) {
        updateState { s ->
            s.copy(savedVoiceDesigns = s.savedVoiceDesigns.filterNot { it.id == designId })
        }
    }

    fun setSubCartridgeEnabled(subSpecId: String, enabled: Boolean) {
        updateState { s ->
            val nextEnabled = if (enabled) s.enabledSubCartridges + subSpecId else s.enabledSubCartridges - subSpecId
            s.copy(enabledSubCartridges = nextEnabled)
        }
    }

    fun isSubCartridgeEnabled(subSpecId: String): Boolean =
        subSpecId in _state.value.enabledSubCartridges &&
            _state.value.modelStates[subSpecId]?.state == DownloadState.INSTALLED

    fun updateModelState(specId: String, bundleState: ModelBundleState) {
        val spec = PurrDefaultPresets.modelCatalog().firstOrNull { it.id == specId }
        val isSub = spec?.isSubCartridge == true
        updateState { s ->
            val nextMap = s.modelStates.toMutableMap()
            nextMap[specId] = bundleState
            var nextSlotted = s.slottedCartridges
            var nextEnabledSubs = s.enabledSubCartridges

            when (bundleState.state) {
                DownloadState.INSTALLED -> {
                    if (isSub) {
                        nextEnabledSubs = nextEnabledSubs + specId
                        nextSlotted = nextSlotted - specId
                        val parentId = spec.parentCartridgeId
                        if (!parentId.isNullOrBlank()) {
                            nextSlotted = nextSlotted + parentId
                        }
                    } else {
                        nextSlotted = nextSlotted + specId
                    }
                }
                DownloadState.NOT_INSTALLED -> {
                    nextSlotted = nextSlotted - specId
                    nextEnabledSubs = nextEnabledSubs - specId
                }
                else -> Unit
            }

            s.copy(
                modelStates = nextMap,
                slottedCartridges = nextSlotted,
                enabledSubCartridges = nextEnabledSubs
            )
        }
    }

    fun allAvailableVoices(): List<VoiceDescriptor> {
        val state = _state.value
        val catalog = PurrDefaultPresets.modelCatalog()
        val base = PurrDefaultPresets.builtInVoices().filter { v ->
            val subOk = v.requiredSubCartridgeId == null ||
                isSubCartridgeEnabled(v.requiredSubCartridgeId) ||
                state.modelStates[v.requiredSubCartridgeId]?.state == DownloadState.INSTALLED
            val baseHubOk = if (v.requiredSubCartridgeId != null) {
                true
            } else {
                when (v.cartridgeId) {
                    "rom_piper", "rom_kitten_tts", "rom_qwen3_custom_06b" -> {
                        val baseInstalled = state.modelStates[v.cartridgeId]?.state == DownloadState.INSTALLED
                        val anyStandaloneSubInstalled = catalog.any { sub ->
                            sub.parentCartridgeId == v.cartridgeId &&
                                state.modelStates[sub.id]?.state == DownloadState.INSTALLED
                        }
                        baseInstalled || !anyStandaloneSubInstalled
                    }
                    else -> true
                }
            }
            subOk && baseHubOk
        }
        val sysVoices = _discoveredSystemVoices.value
        val cloned = state.clonedVoices.map { c ->
            val engineId = c.resolvedCloneEngineId
            val cartId = if (engineId == "sub_qwen3_base_06b") "rom_qwen3_custom_06b" else "rom_clone_hub"
            val backend = when (engineId) {
                "sub_qwen3_base_06b" -> BackendType.QWEN3_NATIVE
                "sub_chatterbox_turbo" -> BackendType.ONNX_NATIVE
                else -> BackendType.SHERPA_ONNX
            }
            val engineTag = when (engineId) {
                "sub_qwen3_base_06b" -> "Qwen3 0.6B Base"
                "sub_chatterbox_turbo" -> "Chatterbox-Turbo"
                "sub_pocket_fp32" -> "Pocket FP32"
                "sub_zipvoice_full" -> "ZipVoice Full"
                else -> c.family.label
            }
            VoiceDescriptor(
                id = c.id,
                displayName = "Clone ($engineTag) // ${c.displayName}",
                engine = backend,
                cartridgeId = cartId,
                requiredSubCartridgeId = engineId,
                sherpaFamily = c.family,
                gender = c.gender,
                languageCode = c.languageCode,
                nativeTags = c.tags + listOf(c.gender.tag, "cloned"),
                isCloned = true
            )
        }
        return sysVoices + cloned + base
    }

    private fun updateState(block: (PurrForgeState) -> PurrForgeState) {
        synchronized(this) {
            _state.update(block)
            persistState(_state.value)
        }
    }

    private fun loadInitialState(): PurrForgeState {
        if (!stateFile.exists()) return PurrForgeState()
        return runCatching {
            val decoded = json.decodeFromString<PurrForgeState>(stateFile.readText())
            if (decoded.profiles.isEmpty()) return@runCatching PurrForgeState()
            val normalizedModelStates = decoded.modelStates.toMutableMap().apply {
                for (spec in PurrDefaultPresets.modelCatalog().filter { it.isBuiltInOsCartridge }) {
                    put(
                        spec.id,
                        ModelBundleState(
                            specId = spec.id,
                            state = DownloadState.INSTALLED,
                            sha256Verified = true,
                            localPath = spec.downloadUrl
                        )
                    )
                }
            }
            val premiumPrefixByCartridge = mapOf(
                "rom_kokoro_fp32" to "fp32_",
                "rom_kokoro_v11" to "v11_"
            )
            // ✨ v1.7.0 HUB MIGRATION: legacy standalone cartridges that became sub-cartridges under the 8-Bay Hub Rack.
            val legacyCartridgeToHub = mapOf(
                "rom_kokoro_fp32" to "rom_kokoro_sherpa",
                "rom_kokoro_v11" to "rom_kokoro_sherpa",
                "rom_pocket_tts" to "rom_clone_hub",
                "rom_zipvoice" to "rom_clone_hub",
                "rom_supertonic3" to "rom_piper",
                "rom_matcha_zh_en" to "rom_piper"
            )
            val catalogById = PurrDefaultPresets.modelCatalog().associateBy { it.id }
            val nextEnabledSubs = decoded.enabledSubCartridges.toMutableSet()
            val nextSlotted = decoded.slottedCartridges.toMutableSet()
            // Auto-slot newly introduced v1.8.0 OS modules once when upgrading from <= v1.7.1.
            val isNewToolCatalogV2 = decoded.knownToolCatalogVersion < 2 ||
                ("tool_voice_design" !in decoded.modelStates && "tool_audiobook_constructor" !in decoded.modelStates)
            if (isNewToolCatalogV2) {
                nextSlotted += "tool_voice_design"
                nextSlotted += "tool_audiobook_constructor"
            }
            for ((legacyId, hubId) in legacyCartridgeToHub) {
                if (normalizedModelStates[legacyId]?.state == DownloadState.INSTALLED) {
                    nextEnabledSubs += legacyId
                    if (nextSlotted.remove(legacyId) || catalogById[legacyId]?.parentCartridgeId == hubId) {
                        nextSlotted += hubId
                    }
                }
            }
            val remappedProfiles = decoded.profiles.map { profile ->
                val prefix = premiumPrefixByCartridge[profile.selectedCartridgeId]
                val prefixFixed = if (prefix == null) {
                    profile
                } else {
                    val needsPrimary = !profile.primaryVoiceId.startsWith(prefix) &&
                        PurrDefaultPresets.builtInVoices().any { it.id == prefix + profile.primaryVoiceId }
                    val needsDialogue = profile.dialogueVoiceId.isNotBlank() &&
                        !profile.dialogueVoiceId.startsWith(prefix) &&
                        PurrDefaultPresets.builtInVoices().any { it.id == prefix + profile.dialogueVoiceId }
                    if (needsPrimary || needsDialogue) {
                        profile.copy(
                            primaryVoiceId = if (needsPrimary) prefix + profile.primaryVoiceId else profile.primaryVoiceId,
                            dialogueVoiceId = if (needsDialogue) prefix + profile.dialogueVoiceId else profile.dialogueVoiceId
                        )
                    } else {
                        profile
                    }
                }
                val migratedHub = legacyCartridgeToHub[prefixFixed.selectedCartridgeId]
                if (migratedHub != null) {
                    prefixFixed.copy(selectedCartridgeId = migratedHub)
                } else {
                    prefixFixed
                }
            }
            decoded.copy(
                modelStates = normalizedModelStates,
                profiles = remappedProfiles,
                slottedCartridges = nextSlotted,
                enabledSubCartridges = nextEnabledSubs,
                knownToolCatalogVersion = 2
            )
        }.getOrElse {
            PurrForgeState()
        }
    }

    private fun persistState(state: PurrForgeState) {
        runCatching {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(json.encodeToString(state))
        }
    }
}
