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
 * ✨ MIKA'S PURR PROFILE & CONFIG REPOSITORY ✨
 * Thread-safe JSON persistence backed by `filesDir/purr_forge_state.json`.
 * Shared synchronously between the background TextToSpeechService and Compose Forge UI.
 */
class PurrProfileRepository(context: Context) {

    private val stateFile = File(context.filesDir, "purr_forge_state.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<PurrForgeState> = _state.asStateFlow()

    val currentProfile: PurrProfile
        get() {
            val s = _state.value
            return s.profiles.firstOrNull { it.id == s.activeProfileId } ?: s.profiles.first()
        }

    fun selectProfile(profileId: String) {
        updateState { s ->
            if (s.profiles.any { it.id == profileId }) s.copy(activeProfileId = profileId) else s
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
            val clone = currentProfile.copy(id = newId, name = cleanName)
            s.copy(
                activeProfileId = newId,
                profiles = s.profiles + clone
            )
        }
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
                profile.copy(
                    whitelist = profile.whitelist + idSet,
                    blacklist = profile.blacklist - idSet
                )
            } else {
                profile.copy(
                    whitelist = profile.whitelist - idSet,
                    blacklist = profile.blacklist + idSet
                )
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
        updateState { s ->
            val updatedClones = s.clonedVoices.filterNot { it.id == voice.id } + voice
            val updatedProfiles = s.profiles.map { p ->
                if (p.id == s.activeProfileId) {
                    p.copy(
                        whitelist = p.whitelist + voice.id,
                        primaryVoiceId = voice.id,
                        preferredBackend = BackendType.SHERPA_ONNX,
                        sherpaFamily = voice.family
                    )
                } else {
                    p
                }
            }
            s.copy(clonedVoices = updatedClones, profiles = updatedProfiles)
        }
    }

    fun removeClonedVoice(voiceId: String) {
        updateState { s ->
            s.copy(clonedVoices = s.clonedVoices.filterNot { it.id == voiceId })
        }
    }

    fun updateModelState(specId: String, bundleState: ModelBundleState) {
        updateState { s ->
            val nextMap = s.modelStates.toMutableMap()
            nextMap[specId] = bundleState
            s.copy(modelStates = nextMap)
        }
    }

    fun allAvailableVoices(): List<VoiceDescriptor> {
        val base = PurrDefaultPresets.builtInVoices()
        val cloned = _state.value.clonedVoices.map { c ->
            VoiceDescriptor(
                id = c.id,
                displayName = "Clone (${c.family.label}) // ${c.displayName}",
                engine = BackendType.SHERPA_ONNX,
                sherpaFamily = c.family,
                gender = c.gender,
                languageCode = c.languageCode,
                nativeTags = c.tags + listOf(c.gender.tag, "cloned"),
                isCloned = true
            )
        }
        return cloned + base
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
            if (decoded.profiles.isEmpty()) PurrForgeState() else decoded
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
