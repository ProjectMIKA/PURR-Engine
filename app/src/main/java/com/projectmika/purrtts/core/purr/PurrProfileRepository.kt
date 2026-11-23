package com.projectmika.purrtts.core.purr

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    // ✨ INIT-ORDER LAW: these legacy JSON keys are read by migrateLegacyEnhancerFlags(), which
    // runs DURING construction (`_state` below initializes via loadInitialState()). Kotlin
    // initializes properties in DECLARATION ORDER — declared after `_state`, this set was still
    // null when the migration read it: NPE inside runCatching → migration silently no-oped →
    // every flag fell back to its data-class default (caught by PurrProfileRepositoryMigrationTest
    // in the v1.8.3 CI run). It MUST stay declared before `_state`.
    private val legacyProfileKeys = setOf(
        "enableSlangAndHonorifics",
        "slangDuringNeuralPure",
        "qwen3GpuOffload"
    )

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

    // ✨ PERSISTED CARTRIDGE BAY & THEME CONTROLS (v1.8.6)
    fun setCatalogSortMode(mode: CartridgeSortMode) {
        updateState { s -> s.copy(catalogSortMode = mode) }
    }

    fun setCatalogFilterMode(mode: CartridgeFilterMode) {
        updateState { s -> s.copy(catalogFilterMode = mode) }
    }

    fun updateThemeConfig(transform: (PurrThemeConfig) -> PurrThemeConfig) {
        updateState { s -> s.copy(themeConfig = transform(s.themeConfig)) }
    }

    // ✨ SMART HARDWARE OFFLOAD (v1.8.5): experimental device-level toggle — auto-selects
    // Adreno OpenCL, Hexagon NPU / NNAPI FP16, or XNNPACK per SoC with safe CPU fallback.
    fun setSmartHardwareOffload(enabled: Boolean) {
        updateState { s -> s.copy(smartHardwareOffload = enabled) }
    }

    // ✨ HARDWARE OFFLOAD (v1.8.3): global device-level accelerator state (Settings ▸
    // HARDWARE_OFFLOAD). Deliberately NOT per-vibe-preset — hardware doesn't change when you
    // switch personas; the Qwen3 engine re-mounts on the next synthesis after a flip.
    fun setQwen3GpuOffload(enabled: Boolean) {
        updateState { s -> s.copy(qwen3GpuOffload = enabled) }
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
                .filter { !it.isSubCartridge && (it.category == CartridgeCategory.TTS || (it.category == CartridgeCategory.CLONING && !it.isBuiltInOsCartridge)) }
                .map { it.id }
                .toSet()
            val fallbackCart = nextSlotted.firstOrNull { it in ttsCatalogIds }
            val fallbackSpec = fallbackCart?.let { id ->
                PurrDefaultPresets.modelCatalog().firstOrNull { it.id == id }
            }
            val availableVoices = computeAvailableVoices(
                s.copy(slottedCartridges = nextSlotted)
            )
            val fallbackVoice = if (fallbackCart != null) {
                availableVoices.firstOrNull {
                    it.cartridgeId == fallbackCart
                } ?: PurrDefaultPresets.builtInVoices().firstOrNull { it.cartridgeId == fallbackCart }
            } else {
                null
            }
            val nextProfiles = s.profiles.map { p ->
                val needsSwitchOnEject = !slotted && p.selectedCartridgeId == cartridgeId && fallbackCart != null
                val needsSubHeal = slotted && p.selectedCartridgeId == cartridgeId &&
                    availableVoices.none { it.id == p.primaryVoiceId }
                if (needsSwitchOnEject && fallbackCart != null) {
                    p.copy(
                        selectedCartridgeId = fallbackCart,
                        preferredBackend = fallbackVoice?.engine ?: fallbackSpec?.backend ?: p.preferredBackend,
                        sherpaFamily = fallbackVoice?.sherpaFamily ?: fallbackSpec?.sherpaFamily ?: p.sherpaFamily,
                        primaryVoiceId = fallbackVoice?.id ?: p.primaryVoiceId
                    )
                } else if (needsSubHeal) {
                    val healed = availableVoices.firstOrNull { it.cartridgeId == cartridgeId }
                    if (healed != null) {
                        p.copy(
                            preferredBackend = healed.engine,
                            sherpaFamily = healed.sherpaFamily ?: p.sherpaFamily,
                            primaryVoiceId = healed.id
                        )
                    } else p
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

    fun isScriptStudioModuleActive(): Boolean = isCartridgeSlotted("tool_script_studio")

    fun isVoiceBenchmarkModuleActive(): Boolean = isCartridgeSlotted("tool_voice_benchmark")

    fun isSrtBatchModuleActive(): Boolean = isCartridgeSlotted("tool_srt_batch_studio")

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
            "sub_qwen3_base_06b" -> "sub_qwen3_base_06b"
            "sub_chatterbox_turbo" -> "sub_chatterbox_turbo"
            "rom_zipvoice", "sub_zipvoice_full" -> "rom_zipvoice"
            else -> "rom_pocket_tts"
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
            val nextEnabledSubs = if (engineId in setOf("sub_pocket_fp32", "sub_zipvoice_full")) {
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

    fun allAvailableVoices(): List<VoiceDescriptor> = computeAvailableVoices(_state.value)

    private fun computeAvailableVoices(state: PurrForgeState): List<VoiceDescriptor> {
        val catalog = PurrDefaultPresets.modelCatalog()
        val addOnChipIds = setOf("rom_kokoro_npu_g2p", "rom_kokoro_litert")
        val hubCartridgesWithStandaloneSubs = setOf(
            "rom_kokoro_sherpa", "rom_piper", "rom_kitten_tts", "rom_qwen3_custom_06b",
            "rom_pocket_tts", "rom_zipvoice", "rom_inflect_v2",
            "rom_icefall_vits", "rom_coqui_vits", "rom_meta_mms",
            "rom_mimic3_neural"
        )
        val base = PurrDefaultPresets.builtInVoices().filter { v ->
            val subOk = v.requiredSubCartridgeId == null ||
                (v.requiredSubCartridgeId in state.enabledSubCartridges &&
                    state.modelStates[v.requiredSubCartridgeId]?.state == DownloadState.INSTALLED) ||
                state.modelStates[v.requiredSubCartridgeId]?.state == DownloadState.INSTALLED
            val baseHubOk = if (v.requiredSubCartridgeId != null) {
                true
            } else if (v.cartridgeId in hubCartridgesWithStandaloneSubs) {
                val baseInstalled = state.modelStates[v.cartridgeId]?.state == DownloadState.INSTALLED
                val anyStandaloneSubInstalled = catalog.any { sub ->
                    sub.parentCartridgeId == v.cartridgeId &&
                        sub.id !in addOnChipIds &&
                        state.modelStates[sub.id]?.state == DownloadState.INSTALLED
                }
                baseInstalled || !anyStandaloneSubInstalled
            } else {
                true
            }
            subOk && baseHubOk
        }
        val sysVoices = _discoveredSystemVoices.value
        val cloned = state.clonedVoices.map { c ->
            val engineId = c.resolvedCloneEngineId
            val cartId = when (engineId) {
                "sub_qwen3_base_06b" -> "sub_qwen3_base_06b"
                "sub_chatterbox_turbo" -> "sub_chatterbox_turbo"
                "rom_zipvoice", "sub_zipvoice_full" -> "rom_zipvoice"
                else -> "rom_pocket_tts"
            }
            val requiredSub = if (engineId in setOf("sub_pocket_fp32", "sub_zipvoice_full")) engineId else null
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
                requiredSubCartridgeId = requiredSub,
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

    // ✨ v1.8.3 THREE-WAY BENDER + GLOBAL OFFLOAD MIGRATION ✨
    // Old state files carry the combined `enableSlangAndHonorifics` bender flag, the
    // `slangDuringNeuralPure` flagship carve-out, and a per-profile `qwen3GpuOffload`. The new
    // contracts split the bender into `enableSlang` / `enableHonorifics` / `enableStutters`
    // (each seeded from the old combined flag so no preset silently changes personality) and
    // hoist the GPU offload into GLOBAL `PurrForgeState.qwen3GpuOffload` (hardware outlives
    // vibe presets). This raw-JSON pass runs BEFORE decoding: with `ignoreUnknownKeys = true`
    // the old flags would otherwise be dropped and every new flag would default to ON —
    // flipping clean-prose presets (Audiobook / Light Novel, Clean & Fast) into slang mode.
    // `slangDuringNeuralPure` has no successor: the v1.8.3 law is SLANG-ONLY survival on
    // flagship voices, so the old per-context carve-out toggle is retired. Users who had it
    // OFF (explicit full-v1.8.0 Neural Pure) START WITH SLANG OFF everywhere — their expressed
    // preference was clean speech on heavy engines, and one tap re-enables it everywhere
    // (Master's call). Everyone else seeds the Slang toggle from the old combined flag.
    private fun migrateLegacyEnhancerFlags(raw: String): String {
        return runCatching {
            val root = Json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching raw
            val profilesElement = root["profiles"] as? JsonArray ?: return@runCatching raw
            var hoistedGpuOffload = false
            val migratedProfiles = profilesElement.map { element ->
                val obj = element as? JsonObject ?: return@map element
                if ((obj["qwen3GpuOffload"] as? JsonPrimitive)?.booleanOrNull == true) {
                    hoistedGpuOffload = true
                }
                val legacyCombined = (obj["enableSlangAndHonorifics"] as? JsonPrimitive)?.booleanOrNull
                val legacySlangOnFlagship = (obj["slangDuringNeuralPure"] as? JsonPrimitive)?.booleanOrNull
                // Seed order: a user who explicitly disabled the old flagship carve-out wanted
                // FULL v1.8.0 Neural Pure → they start with Slang OFF everywhere (Master's call);
                // otherwise the legacy combined flag wins; else an already-migrated value is
                // kept; else the data-class default (true) applies via explicit put.
                val legacySlang = if (legacyCombined != null && legacySlangOnFlagship == false) {
                    false
                } else {
                    legacyCombined
                        ?: (obj["enableSlang"] as? JsonPrimitive)?.booleanOrNull
                        ?: true
                }
                buildJsonObject {
                    obj.forEach { (key, value) ->
                        if (key !in legacyProfileKeys) put(key, value)
                    }
                    put("enableSlang", JsonPrimitive(legacySlang))
                    put("enableHonorifics", JsonPrimitive(legacyCombined
                        ?: (obj["enableHonorifics"] as? JsonPrimitive)?.booleanOrNull ?: true))
                    put("enableStutters", JsonPrimitive(legacyCombined
                        ?: (obj["enableStutters"] as? JsonPrimitive)?.booleanOrNull ?: true))
                }
            }
            val migrated = buildJsonObject {
                root.forEach { (key, value) ->
                    if (key != "profiles" && key != "qwen3GpuOffload") put(key, value)
                }
                put("profiles", JsonArray(migratedProfiles))
                if (hoistedGpuOffload) put("qwen3GpuOffload", JsonPrimitive(true))
            }
            migrated.toString()
        }.onFailure { throwable ->
            // ✨ LOUD-FAILURE LAW: a silently skipped migration re-defaults every new flag to
            // ON — clean-prose presets would flip into slang mode and nobody would ever know why.
            println("[PURR_REPO][MIGRATE_ERR] Legacy state migration failed; loading raw state: ${throwable.message}")
        }.getOrDefault(raw)
    }

    private fun loadInitialState(): PurrForgeState {
        if (!stateFile.exists()) return PurrForgeState()
        return runCatching {
            val decoded = json.decodeFromString<PurrForgeState>(migrateLegacyEnhancerFlags(stateFile.readText()))
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
            // ✨ v1.7.0 HUB MIGRATION + v1.8.5 CLONING CATEGORY PROMOTION:
            val legacyCartridgeToHub = mapOf(
                "rom_kokoro_fp32" to "rom_kokoro_sherpa",
                "rom_kokoro_v11" to "rom_kokoro_sherpa",
                "rom_supertonic3" to "rom_piper",
                "rom_matcha_zh_en" to "rom_piper"
            )
            val topLevelCloningIds = setOf(
                "rom_pocket_tts",
                "rom_zipvoice",
                "sub_chatterbox_turbo",
                "sub_qwen3_base_06b"
            )
            val catalogById = PurrDefaultPresets.modelCatalog().associateBy { it.id }
            val nextEnabledSubs = decoded.enabledSubCartridges.toMutableSet()
            val nextSlotted = decoded.slottedCartridges.toMutableSet()
            // Auto-slot newly introduced v1.8.0 & v1.8.6 OS modules once when upgrading.
            val isNewToolCatalogV2 = decoded.knownToolCatalogVersion < 2 ||
                ("tool_voice_design" !in decoded.modelStates && "tool_audiobook_constructor" !in decoded.modelStates)
            if (isNewToolCatalogV2) {
                nextSlotted += "tool_voice_design"
                nextSlotted += "tool_audiobook_constructor"
            }
            if (decoded.knownToolCatalogVersion < 3 || "tool_theme_engine" !in decoded.modelStates) {
                nextSlotted += "tool_theme_engine"
            }
            if (decoded.knownToolCatalogVersion < 4 || "tool_script_studio" !in decoded.modelStates) {
                nextSlotted += "tool_script_studio"
                nextSlotted += "tool_voice_benchmark"
                nextSlotted += "tool_srt_batch_studio"
            }
            for ((legacyId, hubId) in legacyCartridgeToHub) {
                if (normalizedModelStates[legacyId]?.state == DownloadState.INSTALLED) {
                    nextEnabledSubs += legacyId
                    if (nextSlotted.remove(legacyId) || catalogById[legacyId]?.parentCartridgeId == hubId) {
                        nextSlotted += hubId
                    }
                }
            }
            // Promote cloning engines out of retired rom_clone_hub & rom_qwen3_custom_06b into CLONING bay
            nextSlotted.remove("rom_clone_hub")
            for (cloningId in topLevelCloningIds) {
                if (normalizedModelStates[cloningId]?.state == DownloadState.INSTALLED) {
                    nextSlotted += cloningId
                    nextEnabledSubs.remove(cloningId)
                }
            }
            if (normalizedModelStates["sub_pocket_fp32"]?.state == DownloadState.INSTALLED) {
                nextSlotted += "rom_pocket_tts"
                nextEnabledSubs += "sub_pocket_fp32"
            }
            if (normalizedModelStates["sub_zipvoice_full"]?.state == DownloadState.INSTALLED) {
                nextSlotted += "rom_zipvoice"
                nextEnabledSubs += "sub_zipvoice_full"
            }
            val clonedById = decoded.clonedVoices.associateBy { it.id }
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
                val hubFixed = if (migratedHub != null) {
                    prefixFixed.copy(selectedCartridgeId = migratedHub)
                } else {
                    prefixFixed
                }
                val clonedMatch = clonedById[hubFixed.primaryVoiceId]
                when {
                    clonedMatch != null -> {
                        val targetCart = when (clonedMatch.resolvedCloneEngineId) {
                            "sub_qwen3_base_06b" -> "sub_qwen3_base_06b"
                            "sub_chatterbox_turbo" -> "sub_chatterbox_turbo"
                            "rom_zipvoice", "sub_zipvoice_full" -> "rom_zipvoice"
                            else -> "rom_pocket_tts"
                        }
                        if (hubFixed.selectedCartridgeId != targetCart) hubFixed.copy(selectedCartridgeId = targetCart) else hubFixed
                    }
                    hubFixed.selectedCartridgeId == "rom_clone_hub" -> {
                        val targetCart = when {
                            hubFixed.primaryVoiceId.startsWith("chatterbox") -> "sub_chatterbox_turbo"
                            hubFixed.primaryVoiceId.startsWith("zipvoice") -> "rom_zipvoice"
                            else -> "rom_pocket_tts"
                        }
                        hubFixed.copy(selectedCartridgeId = targetCart)
                    }
                    hubFixed.selectedCartridgeId == "rom_qwen3_custom_06b" &&
                        hubFixed.primaryVoiceId == "qwen3_base_clone_default" -> {
                        hubFixed.copy(selectedCartridgeId = "sub_qwen3_base_06b")
                    }
                    else -> hubFixed
                }
            }
            decoded.copy(
                modelStates = normalizedModelStates,
                profiles = remappedProfiles,
                slottedCartridges = nextSlotted,
                enabledSubCartridges = nextEnabledSubs,
                knownToolCatalogVersion = 4
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
