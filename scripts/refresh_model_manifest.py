#!/usr/bin/env python3
"""
✨ PURR-ENGINE MODEL MANIFEST REFRESHER (v1.8.1 "The Trust Renewal") ✨

Regenerates `model_manifest.json` — the self-healing SHA-256 truth for every network
asset in the catalog (PurrDefaultPresets.modelCatalog in PurrModels.kt).

WHY: upstream hosts are MUTABLE. k2-fsa re-uploads release assets under the same
`tts-models` tag (Pocket TTS INT8/FP32 re-uploaded 2026-11-21 broke every fresh
install with SHA-256 mismatches), and HuggingFace `resolve/main` URLs move with the
branch tip. Baked-in catalog pins rot; the app now resolves manifest → baked pin, so
refreshing this file + one commit fixes every user without shipping an APK.

Sources (metadata only — no model bytes are downloaded):
  - GitHub release assets: GET /repos/<owner>/<repo>/releases/tags/<tag>
      → per-asset `digest` ("sha256:...") + `size` (digests exist for uploads since 2024)
  - HuggingFace LFS files: GET /api/models/<repo>/tree/<rev>?recursive=true
      → per-entry `lfs.oid` (sha256) + `lfs.size`

Usage:
  python3 scripts/refresh_model_manifest.py            # regenerate model_manifest.json
  python3 scripts/refresh_model_manifest.py --check    # drift audit vs current manifest (exit 1 on drift)
"""

import argparse
import json
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS_KT = ROOT / "app/src/main/java/com/projectmika/purrtts/core/purr/PurrModels.kt"
MANIFEST_PATH = ROOT / "model_manifest.json"
USER_AGENT = "purr-engine-manifest-refresh/1.8.1 (ProjectMIKA)"


def extract_catalog_urls():
    """Every https(s) asset URL assigned in the catalog spec file (dedup, order kept)."""
    text = MODELS_KT.read_text(encoding="utf-8")
    urls = []
    for match in re.finditer(r'(?:downloadUrl|companionUrl|url)\s*=\s*"(https?://[^"]+)"', text):
        url = match.group(1)
        if url not in urls:
            urls.append(url)
    return urls


def fetch_json(url, timeout=45):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def resolve_github_release_asset(url, release_cache):
    """`github.com/<owner>/<repo>/releases/download/<tag>/<file>` → {sha256, sizeBytes} | None."""
    match = re.match(r"https://github\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/(.+)$", url)
    if not match:
        return None
    owner, repo, tag, filename = match.groups()
    key = (owner, repo, tag)
    if key not in release_cache:
        data = fetch_json(f"https://api.github.com/repos/{owner}/{repo}/releases/tags/{tag}")
        release_cache[key] = {a["name"]: a for a in data.get("assets", [])}
    asset = release_cache[key].get(filename)
    if asset is None:
        return None
    digest = asset.get("digest") or ""
    sha = digest.split(":", 1)[1] if digest.startswith("sha256:") else digest
    if not re.fullmatch(r"[0-9a-fA-F]{64}", sha or ""):
        return None
    return {"sha256": sha.lower(), "sizeBytes": asset.get("size")}


def resolve_huggingface_lfs(url, tree_cache):
    """`huggingface.co/<repo>/resolve/<rev>/<path>` → {sha256, sizeBytes} | None (LFS only)."""
    match = re.match(r"https://huggingface\.co/([^/]+/[^/]+)/resolve/([^/]+)/(.+)$", url)
    if not match:
        return None
    repo, rev, path = match.groups()
    key = (repo, rev)
    if key not in tree_cache:
        data = fetch_json(f"https://huggingface.co/api/models/{repo}/tree/{rev}?recursive=true")
        tree_cache[key] = {entry["path"]: entry for entry in data if isinstance(entry, dict) and "path" in entry}
    entry = tree_cache[key].get(path)
    if entry is None:
        return None
    lfs = entry.get("lfs") or {}
    sha = lfs.get("oid") or ""
    if not re.fullmatch(r"[0-9a-fA-F]{64}", sha):
        return None
    return {"sha256": sha.lower(), "sizeBytes": lfs.get("size") or entry.get("size")}


def build_asset_map(verbose=True):
    release_cache = {}
    tree_cache = {}
    assets = {}
    unresolved = []
    for url in extract_catalog_urls():
        resolved = resolve_github_release_asset(url, release_cache)
        if resolved is None:
            resolved = resolve_huggingface_lfs(url, tree_cache)
        if resolved is None:
            unresolved.append(url)
            continue
        assets[url] = resolved
        if verbose:
            size_mb = (resolved["sizeBytes"] or 0) / (1024 * 1024)
            print(f"  resolved  {resolved['sha256'][:12]}…  {size_mb:7.1f} MB  {url.split('/')[-1]}")
    return assets, unresolved


def load_existing_manifest():
    try:
        return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def main():
    parser = argparse.ArgumentParser(description="Refresh/audit model_manifest.json")
    parser.add_argument("--check", action="store_true", help="report drift vs the existing manifest; exit 1 on drift")
    args = parser.parse_args()

    print("Extracting catalog URLs…")
    assets, unresolved = build_asset_map()
    existing = load_existing_manifest()
    existing_assets = (existing or {}).get("assets", {})

    if args.check:
        drift = []
        for url, info in sorted(assets.items()):
            old = (existing_assets.get(url) or {}).get("sha256", "")
            if old and old.lower() != info["sha256"]:
                drift.append((url, old, info["sha256"]))
        if drift:
            print(f"DRIFT DETECTED — {len(drift)} asset(s) changed upstream since the last refresh:")
            for url, old, new in drift:
                print(f"  {url}\n    manifest: {old}\n    upstream: {new}")
            return 1
        print(f"NO DRIFT — {len(assets)} asset pins verified against upstream.")
        return 0

    document = {
        "version": 1,
        "generatedAtUtc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": "scripts/refresh_model_manifest.py (GitHub release digests + HuggingFace LFS oids)",
        "assets": assets,
    }
    MANIFEST_PATH.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    print(f"\nWrote {MANIFEST_PATH.name}: {len(assets)} verified asset pins.")
    if unresolved:
        print(f"\n⚠ {len(unresolved)} URL(s) had no upstream digest — the app keeps using their baked pins:")
        for url in unresolved:
            print(f"  {url}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
