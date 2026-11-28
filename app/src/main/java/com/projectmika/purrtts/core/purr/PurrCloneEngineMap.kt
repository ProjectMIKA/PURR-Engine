package com.projectmika.purrtts.core.purr

/**
 * ✨ MIKA'S CLONE ENGINE MAP (v2.7.2) ✨
 * Single source of truth for clone-engine-id → cartridge / backend / required-sub / label.
 * WHY: three hand-copied `when` blocks in PurrProfileRepository drifted behind new engines
 * (Chatterbox, Qwen3-Base, then ZONOS) and silently bucketed new clones under Pocket TTS —
 * the v2.7.0 Zonos clone showed up as "POCKET_TTS_CLONER". Every clone flow MUST resolve
 * through here. PURE — no Android imports — unit-tested in PurrZonosCatalogTest.
 */
object PurrCloneEngineMap {

    /** Auto-slotted parent cartridge for a clone engine (the deck the voice lives under). */
    fun targetCartridge(engineId: String): String = when (engineId) {
        "sub_qwen3_base_06b" -> "sub_qwen3_base_06b"
        "sub_chatterbox_turbo" -> "sub_chatterbox_turbo"
        "rom_zipvoice", "sub_zipvoice_full" -> "rom_zipvoice"
        // ✨ ZONOS (v2.7.2): the clone chip lives under ROM-18; ZONOS1 IS its own deck cartridge.
        "sub_zonos3_clone" -> "rom_zonos3_mini"
        "rom_zonos1_studio" -> "rom_zonos1_studio"
        else -> "rom_pocket_tts"
    }

    /** Backend that actually synthesizes this clone engine. */
    fun targetBackend(engineId: String): BackendType = when (engineId) {
        "sub_qwen3_base_06b" -> BackendType.QWEN3_NATIVE
        "sub_chatterbox_turbo" -> BackendType.ONNX_NATIVE
        "sub_zonos3_clone" -> BackendType.ZONOS3_NATIVE
        "rom_zonos1_studio" -> BackendType.ZONOS1_NATIVE
        else -> BackendType.SHERPA_ONNX
    }

    /** Sub-chip that must be installed & enabled alongside the clone engine, if any. */
    fun requiredSubCartridge(engineId: String): String? = when (engineId) {
        "sub_pocket_fp32", "sub_zipvoice_full", "sub_zonos3_clone" -> engineId
        else -> null
    }

    /** Short engine label for the voice card ("Clone (X) // Name"). */
    fun engineLabel(engineId: String, family: SherpaModelFamily): String = when (engineId) {
        "sub_qwen3_base_06b" -> "Qwen3 0.6B Base"
        "sub_chatterbox_turbo" -> "Chatterbox-Turbo"
        "sub_pocket_fp32" -> "Pocket FP32"
        "sub_zipvoice_full" -> "ZipVoice Full"
        "sub_zonos3_clone" -> "ZONOS3-Mini 60s"
        "rom_zonos1_studio" -> "ZONOS1 Studio"
        else -> family.label
    }
}
