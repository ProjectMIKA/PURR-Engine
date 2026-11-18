package com.projectmika.purrtts.core.purr

/**
 * ✨ MIKA'S SMART VITS / NEURAL VOICE MATCHER ✨
 * Ported directly from GachaSwipe's `assignVoiceToWaifu` (App.jsx) & MIKAReader's
 * `scoreVoiceForNarration` (purrBender.js). Respects whitelist/blacklist toggles,
 * gender/language gates, and tag scoring with deterministic name-hash fallback.
 */
object PurrVoiceMatcher {

    private val GENERIC_TAGS = setOf(
        "american", "british", "female", "male", "vits", "kokoro", "cloud", "supertonic"
    )

    fun assignVoice(
        voices: List<VoiceDescriptor>,
        profile: PurrProfile,
        queryName: String = "MIKA",
        queryTags: List<String> = profile.characterTags,
        preferredGender: VoiceGender? = null,
        preferredLanguage: String? = "en"
    ): VoiceDescriptor? {
        if (voices.isEmpty()) return null

        // 1. Respect Blacklist strictly, and Whitelist when populated
        var pool = voices.filterNot { it.id in profile.blacklist }
        if (profile.whitelist.isNotEmpty()) {
            val whitelisted = pool.filter { it.id in profile.whitelist }
            if (whitelisted.isNotEmpty()) pool = whitelisted
        }
        if (pool.isEmpty()) pool = voices

        // 2. Language filter (keep voices matching preferredLanguage unless pool collapses)
        if (!preferredLanguage.isNullOrBlank()) {
            val langCodeLower = preferredLanguage.lowercase()
            val langFiltered = pool.filter {
                it.languageCode.lowercase().startsWith(langCodeLower)
            }
            if (langFiltered.isNotEmpty()) pool = langFiltered
        }

        // 3. Gender gate
        if (preferredGender != null && preferredGender != VoiceGender.ANDROGYNOUS) {
            val genderFiltered = pool.filter { it.gender == preferredGender || it.gender == VoiceGender.ANDROGYNOUS }
            if (genderFiltered.isNotEmpty()) pool = genderFiltered
        }

        // 4. Score voice tags + Forge custom tags against character/scene query tags
        val queryCorpus = (queryTags + profile.characterTags).joinToString(" ").lowercase()
        var bestVoice: VoiceDescriptor? = null
        var maxScore = -1

        for (voice in pool) {
            val customTags = profile.customVoiceTags[voice.id].orEmpty()
            val combinedTags = (voice.nativeTags + customTags).map { it.lowercase() }.distinct()
            val customBonus = if (customTags.isNotEmpty()) 2 else 0

            var score = 0
            for (tag in combinedTags) {
                if (tag !in GENERIC_TAGS && queryCorpus.contains(tag)) {
                    score += (2 + customBonus)
                }
            }
            if (voice.id == profile.primaryVoiceId) {
                score += 1
            }
            if (score > maxScore) {
                maxScore = score
                bestVoice = voice
            }
        }

        if (bestVoice != null && maxScore > 0) {
            return bestVoice
        }

        // 5. Deterministic name-hash fallback
        val hash = queryName.sumOf { it.code }
        return pool[hash % pool.size]
    }
}
