#!/usr/bin/env python3
"""
Static verification suite for the PURR-TTS Android project.
Checks required files, parses Android XML resources/manifest, verifies Kotlin
package headers & balanced delimiters, and checks model catalog checksum format.
"""

import os
import re
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

REQUIRED_FILES = [
    ".gitignore",
    "README.md",
    "RELEASE_NOTES.md",
    "LICENSE",
    "NOTICE",
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "gradle/libs.versions.toml",
    "gradle/wrapper/gradle-wrapper.properties",
    ".github/workflows/build-android-apk.yml",
    "app/build.gradle.kts",
    "app/proguard-rules.pro",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/res/xml/tts_engine.xml",
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values/themes.xml",
    "app/src/main/res/drawable/ic_launcher_purr.xml",
    "app/src/main/java/com/projectmika/purrtts/PurrApplication.kt",
    "app/src/main/java/com/projectmika/purrtts/core/purr/PurrModels.kt",
    "app/src/main/java/com/projectmika/purrtts/core/purr/PurrHardwareProbe.kt",
    "app/src/main/java/com/projectmika/purrtts/core/purr/PurrBpeTokenizer.kt",
    "app/src/main/java/com/projectmika/purrtts/core/purr/PurrSentimentScorer.kt",
    "app/src/main/java/com/projectmika/purrtts/core/purr/PurrTextProcessor.kt",
    "app/src/main/java/com/projectmika/purrtts/core/purr/PurrDspProcessor.kt",
    "app/src/main/java/com/projectmika/purrtts/core/purr/PurrVoiceMatcher.kt",
    "app/src/main/java/com/projectmika/purrtts/core/purr/PurrProfileRepository.kt",
    "app/src/main/java/com/projectmika/purrtts/core/purr/QwenVoiceDesignBuilder.kt",
    "app/src/main/java/com/projectmika/purrtts/core/audiobook/EbookParser.kt",
    "app/src/main/java/com/projectmika/purrtts/core/audiobook/Mp4ChapterAtomWriter.kt",
    "app/src/main/java/com/projectmika/purrtts/engine/PurrSynthesizer.kt",
    "app/src/main/java/com/projectmika/purrtts/engine/TtsBackendRouter.kt",
    "app/src/main/java/com/projectmika/purrtts/engine/ModelCatalogManager.kt",
    "app/src/main/java/com/projectmika/purrtts/engine/audiobook/EbookPdfExtractor.kt",
    "app/src/main/java/com/projectmika/purrtts/engine/audiobook/M4bAudiobookMuxer.kt",
    "app/src/main/java/com/projectmika/purrtts/engine/audiobook/AudiobookConstructorManager.kt",
    "app/src/main/java/com/projectmika/purrtts/engine/litert/LiteRtKokoroEngine.kt",
    "app/src/main/java/com/projectmika/purrtts/engine/sherpa/SherpaOnnxEngine.kt",
    "app/src/main/java/com/projectmika/purrtts/engine/onnx/OnnxRuntimeEngine.kt",
    "app/src/main/java/com/projectmika/purrtts/engine/qwen3/Qwen3NativeBridge.kt",
    "app/src/main/java/com/projectmika/purrtts/engine/qwen3/Qwen3TtsEngine.kt",
    "app/src/main/java/com/projectmika/purrtts/engine/proxy/ExternalProxyEngine.kt",
    "app/src/main/assets/neutts_2e_refs.json",
    "app/src/main/cpp/CMakeLists.txt",
    "app/src/main/cpp/provider_policy_android.cpp",
    "app/src/main/cpp/purr_qwen3_jni.cpp",
    "app/src/main/java/com/projectmika/purrtts/service/PurrTtsService.kt",
    "app/src/main/java/com/projectmika/purrtts/service/TtsSystemActivities.kt",
    "app/src/main/java/com/projectmika/purrtts/ui/theme/MikaTheme.kt",
    "app/src/main/java/com/projectmika/purrtts/ui/theme/MikaComponents.kt",
    "app/src/main/java/com/projectmika/purrtts/ui/forge/MainActivity.kt",
    "app/src/main/java/com/projectmika/purrtts/ui/forge/TtsForgeViewModel.kt",
    "app/src/main/java/com/projectmika/purrtts/ui/forge/TtsForgeScreen.kt",
    "app/src/main/java/com/projectmika/purrtts/ui/forge/PurrRulebookTab.kt",
    "app/src/main/java/com/projectmika/purrtts/ui/forge/ModelCatalogTab.kt",
    "app/src/main/java/com/projectmika/purrtts/ui/forge/VoiceCloneStudioSheet.kt",
    "app/src/main/java/com/projectmika/purrtts/ui/forge/VoiceDesignModuleWindow.kt",
    "app/src/main/java/com/projectmika/purrtts/ui/forge/AudiobookConstructorModuleWindow.kt",
    "app/src/test/java/com/projectmika/purrtts/core/purr/PurrTextProcessorTest.kt",
    "app/src/test/java/com/projectmika/purrtts/core/purr/PurrHeavyEnginesTest.kt",
    "app/src/test/java/com/projectmika/purrtts/core/purr/QwenVoiceDesignBuilderTest.kt",
    "app/src/test/java/com/projectmika/purrtts/core/audiobook/PurrEbookParserTest.kt",
    "app/src/test/java/com/projectmika/purrtts/core/audiobook/Mp4ChapterAtomWriterTest.kt",
]


def check_delimiters(text: str, path: Path) -> list[str]:
    """Ensure braces and parentheses balance across a Kotlin file outside strings/comments."""
    errors = []
    stack = []
    pairs = {"(": ")", "{": "}", "[": "]"}
    closing = {v: k for k, v in pairs.items()}
    i = 0
    n = len(text)
    in_line_comment = False
    in_block_comment = False
    in_string = False
    in_triple_string = False
    in_char = False

    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""

        if in_line_comment:
            if ch == "\n":
                in_line_comment = False
            i += 1
            continue

        if in_block_comment:
            if ch == "*" and nxt == "/":
                in_block_comment = False
                i += 2
            else:
                i += 1
            continue

        if in_triple_string:
            if text.startswith('"""', i):
                in_triple_string = False
                i += 3
            else:
                i += 1
            continue

        if in_string:
            if ch == "\\":
                i += 2
            elif ch == '"':
                in_string = False
                i += 1
            else:
                i += 1
            continue

        if in_char:
            if ch == "\\":
                i += 2
            elif ch == "'":
                in_char = False
                i += 1
            else:
                i += 1
            continue

        if ch == "/" and nxt == "/":
            in_line_comment = True
            i += 2
        elif ch == "/" and nxt == "*":
            in_block_comment = True
            i += 2
        elif text.startswith('"""', i):
            in_triple_string = True
            i += 3
        elif ch == '"':
            in_string = True
            i += 1
        elif ch == "'":
            in_char = True
            i += 1
        elif ch in pairs:
            stack.append((ch, i))
            i += 1
        elif ch in closing:
            if not stack or stack[-1][0] != closing[ch]:
                line = text.count("\n", 0, i) + 1
                errors.append(f"{path}:{line}: unmatched '{ch}'")
                return errors
            stack.pop()
            i += 1
        else:
            i += 1

    if stack:
        ch, pos = stack[-1]
        line = text.count("\n", 0, pos) + 1
        errors.append(f"{path}:{line}: unclosed '{ch}'")
    return errors


def main() -> int:
    failures: list[str] = []

    # 1. File existence & non-empty checks
    for rel in REQUIRED_FILES:
        p = ROOT / rel
        if not p.is_file():
            failures.append(f"Missing required file: {rel}")
        elif p.stat().st_size == 0:
            failures.append(f"Empty file: {rel}")

    # 2. XML validation
    xml_files = [
        "app/src/main/AndroidManifest.xml",
        "app/src/main/res/xml/tts_engine.xml",
        "app/src/main/res/values/strings.xml",
        "app/src/main/res/values/themes.xml",
        "app/src/main/res/drawable/ic_launcher_purr.xml",
    ]
    for rel in xml_files:
        p = ROOT / rel
        if p.is_file():
            try:
                ET.parse(p)
            except ET.ParseError as exc:
                failures.append(f"Invalid XML in {rel}: {exc}")

    # 3. Manifest contract checks
    manifest_text = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    for expected in [
        "android.intent.action.TTS_SERVICE",
        "android.permission.BIND_TEXT_TO_SPEECH_SERVICE",
        "android.intent.action.MANAGE_VOICES",
        "android.speech.tts.engine.CONFIGURE_ENGINE",
        "android.speech.tts.engine.CHECK_VOICE_DATA",
        "android.speech.tts.engine.GET_SAMPLE_TEXT",
        "android.speech.tts.engine.INSTALL_TTS_DATA",
        ".service.PurrTtsService",
    ]:
        if expected not in manifest_text:
            failures.append(f"AndroidManifest.xml missing required entry: {expected}")

    # 4. Kotlin file checks (package header + balanced delimiters)
    kt_files = sorted(p for p in ROOT.glob("app/src/**/*.kt") if "third_party" not in p.parts)
    if len(kt_files) < 20:
        failures.append(f"Expected >= 20 Kotlin files, found {len(kt_files)}")

    for kt in kt_files:
        content = kt.read_text(encoding="utf-8")
        if not re.search(r"^package\s+com\.projectmika\.purrtts", content, re.MULTILINE):
            failures.append(f"{kt.relative_to(ROOT)}: missing or invalid package declaration")
        failures.extend(check_delimiters(content, kt.relative_to(ROOT)))

    # 5. Kokoro 54-voice roster & SHA-256 verification checks
    models_text = (ROOT / "app/src/main/java/com/projectmika/purrtts/core/purr/PurrModels.kt").read_text(encoding="utf-8")
    kokoro_matches = re.findall(r'"([abjefhipz][fm]_[a-z]+)"', models_text)
    if len(set(kokoro_matches)) < 54:
        failures.append(f"Expected 54 Kokoro voices in PurrModels.kt, found {len(set(kokoro_matches))}")

    sha_matches = re.findall(r'expectedSha256\s*=\s*"([0-9a-fA-F]{64})"', models_text)
    if len(sha_matches) < 6:
        failures.append(f"Expected 6 SHA-256 verified catalog bundles, found {len(sha_matches)}")

    # 6. Self-healing model hash manifest (v1.8.1) — must exist, parse, and cover the catalog
    manifest_path = ROOT / "model_manifest.json"
    if not manifest_path.exists():
        failures.append("model_manifest.json missing — the self-healing hash manifest is required (v1.8.1)")
    else:
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            assets = manifest.get("assets") or {}
            if manifest.get("version") != 1:
                failures.append("model_manifest.json: unsupported schema version")
            if len(assets) < 20:
                failures.append(f"model_manifest.json: expected >=20 verified asset pins, found {len(assets)}")
            if not any("pocket-tts" in url for url in assets):
                failures.append("model_manifest.json: Pocket TTS pins missing from the manifest")
        except json.JSONDecodeError as exc:
            failures.append(f"model_manifest.json is not valid JSON: {exc}")

    if failures:
        print("VERIFICATION FAILED:")
        for f in failures:
            print("  -", f)
        return 1

    print(
        f"VERIFICATION PASSED: {len(REQUIRED_FILES)} required files, "
        f"{len(xml_files)} valid XML files, {len(kt_files)} balanced Kotlin files, "
        f"{len(set(kokoro_matches))} Kokoro voices, {len(sha_matches)} SHA-256 bundles."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
