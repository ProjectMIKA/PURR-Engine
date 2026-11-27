#!/usr/bin/env python3
"""
Aligns prebuilt sherpa-onnx Android JNI `.so` libraries with the unified
`libonnxruntime.so` packaged from `onnxruntime-android` (e.g. v1.31.1).

Why this exists:
  CI strips `jni/*/libonnxruntime.so` (1.28.2) from `sherpa-onnx-1.13.8.aar`
  so the APK carries one `libonnxruntime.so` (1.31.1). However, `libsherpa-onnx-jni.so`
  and `libsherpa-onnx-c-api.so` retain `.gnu.version_r` (`SHT_GNU_verneed`) entries
  requiring `OrtGetApiBase@VERS_1.28.2`, while `libonnxruntime.so` 1.31.1 exports
  `OrtGetApiBase@@VERS_1.31.1`. Android Bionic's linker rejects the mismatch at
  dlopen time with `UnsatisfiedLinkError`.
"""

from __future__ import annotations

import argparse
import io
import os
import shutil
import struct
import sys
import tempfile
import zipfile
from pathlib import Path

SHT_DYNAMIC = 6
SHT_DYNSYM = 11
SHT_GNU_VERSYM = 0x6FFFFFFF
SHT_GNU_VERDEF = 0x6FFFFFFD
SHT_GNU_VERNEED = 0x6FFFFFFE

DT_NULL = 0
DT_VERNEED = 0x6FFFFFFE
DT_VERNEEDNUM = 0x6FFFFFFF

VER_NDX_GLOBAL = 1
SHN_UNDEF = 0


def elf_hash(name: bytes) -> int:
    """Compute standard SysV ELF hash used in .gnu.version_r / .gnu.version_d."""
    h = 0
    for b in name:
        h = ((h << 4) + b) & 0xFFFFFFFF
        g = h & 0xF0000000
        if g:
            h ^= g >> 24
        h &= ~g & 0xFFFFFFFF
    return h


class ElfContext:
    """Minimal little-endian ELF32/ELF64 section & symbol parser."""

    def __init__(self, data: bytes | bytearray):
        self.data = bytearray(data)
        if len(self.data) < 52 or self.data[:4] != b"\x7fELF":
            raise ValueError("Not an ELF binary")
        self.ei_class = self.data[4]
        self.ei_data = self.data[5]
        if self.ei_data != 1:
            raise ValueError("Only little-endian ELF binaries are supported")
        if self.ei_class == 2:
            self.is_64 = True
            self.e_shoff = struct.unpack_from("<Q", self.data, 40)[0]
            self.e_shentsize, self.e_shnum, self.e_shstrndx = struct.unpack_from("<HHH", self.data, 58)
            self.shdr_fmt = "<IIQQQQIIQQ"
            self.sym_fmt = "<IBBHQQ"
            self.dyn_fmt = "<qQ"
        elif self.ei_class == 1:
            self.is_64 = False
            self.e_shoff = struct.unpack_from("<I", self.data, 32)[0]
            self.e_shentsize, self.e_shnum, self.e_shstrndx = struct.unpack_from("<HHH", self.data, 46)
            self.shdr_fmt = "<IIIIIIIIII"
            self.sym_fmt = "<IIIBBH"
            self.dyn_fmt = "<iI"
        else:
            raise ValueError(f"Unsupported ELF class: {self.ei_class}")

        self.sections: dict[str, dict] = {}
        self._parse_sections()

    def _raw_shdr(self, idx: int) -> tuple[int, ...]:
        off = self.e_shoff + idx * self.e_shentsize
        return struct.unpack_from(self.shdr_fmt, self.data, off)

    def _parse_sections(self) -> None:
        if self.e_shoff == 0 or self.e_shnum == 0:
            return
        shstr = self._raw_shdr(self.e_shstrndx)
        shstr_off, shstr_size = shstr[4], shstr[5]
        shstrtab = bytes(self.data[shstr_off : shstr_off + shstr_size])
        for idx in range(self.e_shnum):
            fields = self._raw_shdr(idx)
            name_off = fields[0]
            end = shstrtab.find(b"\x00", name_off)
            name = shstrtab[name_off : end if end != -1 else len(shstrtab)].decode("utf-8", errors="replace")
            self.sections[name] = {
                "idx": idx,
                "shdr_off": self.e_shoff + idx * self.e_shentsize,
                "name": name,
                "sh_name": fields[0],
                "sh_type": fields[1],
                "sh_flags": fields[2],
                "sh_addr": fields[3],
                "sh_offset": fields[4],
                "sh_size": fields[5],
                "sh_link": fields[6],
                "sh_info": fields[7],
                "sh_addralign": fields[8],
                "sh_entsize": fields[9],
            }

    def dynstr(self, offset: int) -> bytes:
        dynstr_sec = self.sections.get(".dynstr")
        if not dynstr_sec:
            raise ValueError("Missing .dynstr section")
        base = dynstr_sec["sh_offset"]
        end = self.data.find(b"\x00", base + offset)
        if end == -1:
            return bytes(self.data[base + offset :])
        return bytes(self.data[base + offset : end])

    def iter_dynsyms(self):
        dynsym = self.sections.get(".dynsym")
        if not dynsym:
            return
        entsize = dynsym["sh_entsize"] or struct.calcsize(self.sym_fmt)
        count = dynsym["sh_size"] // entsize
        base = dynsym["sh_offset"]
        for i in range(count):
            fields = struct.unpack_from(self.sym_fmt, self.data, base + i * entsize)
            if self.is_64:
                st_name, st_info, st_other, st_shndx, st_value, st_size = fields
            else:
                st_name, st_value, st_size, st_info, st_other, st_shndx = fields
            yield i, self.dynstr(st_name), st_shndx

    def get_versym(self, sym_idx: int) -> int | None:
        versym = self.sections.get(".gnu.version")
        if not versym:
            return None
        off = versym["sh_offset"] + sym_idx * 2
        return struct.unpack_from("<H", self.data, off)[0]

    def set_versym(self, sym_idx: int, value: int) -> None:
        versym = self.sections[".gnu.version"]
        off = versym["sh_offset"] + sym_idx * 2
        struct.pack_into("<H", self.data, off, value)


def read_ort_exported_version(elf_bytes: bytes) -> bytes:
    """Extract the exported version string (e.g. b'VERS_1.31.1') for OrtGetApiBase."""
    ctx = ElfContext(elf_bytes)
    target_sym_idx = None
    for idx, name, shndx in ctx.iter_dynsyms():
        if name == b"OrtGetApiBase" and shndx != SHN_UNDEF:
            target_sym_idx = idx
            break
    if target_sym_idx is None:
        raise ValueError("Defined OrtGetApiBase symbol not found in libonnxruntime.so")

    versym_val = ctx.get_versym(target_sym_idx)
    if versym_val is None:
        raise ValueError("Missing .gnu.version in libonnxruntime.so")
    ver_ndx = versym_val & 0x7FFF

    verdef = ctx.sections.get(".gnu.version_d")
    if not verdef:
        raise ValueError("Missing .gnu.version_d in libonnxruntime.so")

    pos = verdef["sh_offset"]
    end = pos + verdef["sh_size"]
    while pos < end:
        vd_version, vd_flags, vd_ndx, vd_cnt, vd_hash, vd_aux, vd_next = struct.unpack_from("<HHHHIII", ctx.data, pos)
        if vd_ndx == ver_ndx and vd_cnt >= 1:
            vda_name, _ = struct.unpack_from("<II", ctx.data, pos + vd_aux)
            return ctx.dynstr(vda_name)
        if vd_next == 0:
            break
        pos += vd_next

    raise ValueError(f"Version definition index {ver_ndx} not found in .gnu.version_d")


def _unlink_ort_verneed_fallback(ctx: ElfContext, target_ver: bytes) -> bool:
    """
    Fallback if target_ver is longer than the existing .dynstr version string:
    reset Ort/ONNXRuntime symbols referencing libonnxruntime.so verneed indices to
    VER_NDX_GLOBAL (1) and unlink the libonnxruntime.so Verneed entry cleanly.
    """
    verneed = ctx.sections[".gnu.version_r"]
    vr_off = verneed["sh_offset"]
    vr_size = verneed["sh_size"]

    entries: list[dict] = []
    pos = vr_off
    while pos < vr_off + vr_size:
        vn_version, vn_cnt, vn_file, vn_aux, vn_next = struct.unpack_from("<HHIII", ctx.data, pos)
        fname = ctx.dynstr(vn_file)
        aux_pos = pos + vn_aux
        aux_items = []
        removed_indices = set()
        for _ in range(vn_cnt):
            vna_hash, vna_flags, vna_other, vna_name, vna_next = struct.unpack_from("<IHHII", ctx.data, aux_pos)
            aux_items.append((vna_hash, vna_flags, vna_other, vna_name))
            removed_indices.add(vna_other)
            if vna_next == 0:
                break
            aux_pos += vna_next
        entry_end = pos + vn_next if vn_next else vr_off + vr_size
        entries.append(
            {
                "pos": pos,
                "size": entry_end - pos,
                "fname": fname,
                "raw": bytes(ctx.data[pos:entry_end]),
                "ver_indices": removed_indices,
            }
        )
        if vn_next == 0:
            break
        pos += vn_next

    ort_entries = [e for e in entries if e["fname"] == b"libonnxruntime.so"]
    if not ort_entries:
        return False

    ort_indices = set()
    for e in ort_entries:
        ort_indices |= e["ver_indices"]

    # Point affected undefined symbols to VER_NDX_GLOBAL (1)
    dynsym = ctx.sections.get(".dynsym")
    if dynsym and ".gnu.version" in ctx.sections:
        for sym_idx, _name, shndx in ctx.iter_dynsyms():
            if shndx == SHN_UNDEF:
                vs = ctx.get_versym(sym_idx)
                if vs is not None and (vs & 0x7FFF) in ort_indices:
                    ctx.set_versym(sym_idx, VER_NDX_GLOBAL)

    kept = [e for e in entries if e["fname"] != b"libonnxruntime.so"]
    new_blob = bytearray()
    for idx, entry in enumerate(kept):
        chunk = bytearray(entry["raw"])
        is_last = idx == len(kept) - 1
        struct.pack_into("<I", chunk, 12, 0 if is_last else len(chunk))
        new_blob.extend(chunk)

    # Zero-pad remainder inside the original .gnu.version_r span
    if len(new_blob) > vr_size:
        raise ValueError("Rebuilt .gnu.version_r exceeded original size")
    new_blob.extend(b"\x00" * (vr_size - len(new_blob)))
    ctx.data[vr_off : vr_off + vr_size] = new_blob

    # Update section header sh_info (number of verneed entries)
    new_count = len(kept)
    shdr_off = verneed["shdr_off"]
    if ctx.is_64:
        # <IIQQQQIIQQ -> sh_info is index 7 (offset 44)
        struct.pack_into("<I", ctx.data, shdr_off + 44, new_count)
    else:
        struct.pack_into("<I", ctx.data, shdr_off + 32, new_count)

    # Update DT_VERNEEDNUM in .dynamic
    dyn_sec = ctx.sections.get(".dynamic")
    if dyn_sec:
        entsize = dyn_sec["sh_entsize"] or struct.calcsize(ctx.dyn_fmt)
        d_off = dyn_sec["sh_offset"]
        d_count = dyn_sec["sh_size"] // entsize
        for i in range(d_count):
            tag, val = struct.unpack_from(ctx.dyn_fmt, ctx.data, d_off + i * entsize)
            if tag == DT_NULL:
                break
            if tag == DT_VERNEEDNUM:
                struct.pack_into(ctx.dyn_fmt, ctx.data, d_off + i * entsize, tag, new_count)
                break
    return True


def patch_elf_ort_verneed(elf_bytes: bytes, target_ver: bytes) -> tuple[bytes, bool]:
    """
    Rewrite `.gnu.version_r` entries referencing `libonnxruntime.so` to `target_ver`
    (with matching SysV `elf_hash`). Falls back to unlinking the verneed entry if
    `target_ver` does not fit in the existing `.dynstr` slot.
    """
    ctx = ElfContext(elf_bytes)
    verneed = ctx.sections.get(".gnu.version_r")
    dynstr = ctx.sections.get(".dynstr")
    if not verneed or not dynstr:
        return bytes(elf_bytes), False

    vr_off = verneed["sh_offset"]
    vr_end = vr_off + verneed["sh_size"]
    dynstr_off = dynstr["sh_offset"]
    target_hash = elf_hash(target_ver)

    modified = False
    need_unlink_fallback = False

    pos = vr_off
    while pos < vr_end:
        _vn_version, vn_cnt, vn_file, vn_aux, vn_next = struct.unpack_from("<HHIII", ctx.data, pos)
        fname = ctx.dynstr(vn_file)
        if fname == b"libonnxruntime.so":
            aux_pos = pos + vn_aux
            for _ in range(vn_cnt):
                vna_hash, _vna_flags, _vna_other, vna_name, vna_next = struct.unpack_from("<IHHII", ctx.data, aux_pos)
                old_ver = ctx.dynstr(vna_name)
                if old_ver != target_ver or vna_hash != target_hash:
                    if len(target_ver) <= len(old_ver):
                        slot_start = dynstr_off + vna_name
                        slot_len = len(old_ver) + 1
                        padded = target_ver + b"\x00" * (slot_len - len(target_ver))
                        ctx.data[slot_start : slot_start + slot_len] = padded
                        struct.pack_into("<I", ctx.data, aux_pos, target_hash)
                        modified = True
                    else:
                        need_unlink_fallback = True
                        break
                if vna_next == 0:
                    break
                aux_pos += vna_next
        if need_unlink_fallback or vn_next == 0:
            break
        pos += vn_next

    if need_unlink_fallback:
        unlinked = _unlink_ort_verneed_fallback(ctx, target_ver)
        modified = modified or unlinked

    return bytes(ctx.data), modified


def inspect_elf_ort_verneeds(elf_bytes: bytes) -> list[tuple[bytes, int]]:
    """Return [(ver_name, vna_hash)] for all Verneed entries referencing libonnxruntime.so."""
    ctx = ElfContext(elf_bytes)
    verneed = ctx.sections.get(".gnu.version_r")
    if not verneed:
        return []
    vr_off = verneed["sh_offset"]
    vr_end = vr_off + verneed["sh_size"]
    found: list[tuple[bytes, int]] = []
    pos = vr_off
    while pos < vr_end:
        _vn_version, vn_cnt, vn_file, vn_aux, vn_next = struct.unpack_from("<HHIII", ctx.data, pos)
        if vn_cnt == 0 and vn_next == 0:
            break
        fname = ctx.dynstr(vn_file)
        if fname == b"libonnxruntime.so":
            aux_pos = pos + vn_aux
            for _ in range(vn_cnt):
                vna_hash, _vna_flags, _vna_other, vna_name, vna_next = struct.unpack_from("<IHHII", ctx.data, aux_pos)
                found.append((ctx.dynstr(vna_name), vna_hash))
                if vna_next == 0:
                    break
                aux_pos += vna_next
        if vn_next == 0:
            break
        pos += vn_next
    return found


def align_sherpa_aar(sherpa_aar: Path, ort_aar: Path | None, ort_so: Path | None) -> None:
    """Strip duplicate libonnxruntime.so from sherpa AAR and align all jni/*/*.so symbol versions."""
    target_by_abi: dict[str, bytes] = {}
    fallback_target: bytes | None = None

    if ort_so is not None:
        fallback_target = read_ort_exported_version(ort_so.read_bytes())

    if ort_aar is not None:
        with zipfile.ZipFile(ort_aar, "r") as zf:
            for name in zf.namelist():
                if name.startswith("jni/") and name.endswith("/libonnxruntime.so"):
                    abi = name.split("/")[1]
                    ver = read_ort_exported_version(zf.read(name))
                    target_by_abi[abi] = ver
                    if fallback_target is None:
                        fallback_target = ver

    if not target_by_abi and fallback_target is None:
        raise RuntimeError("Could not determine target OrtGetApiBase version from --ort-aar or --ort-so")

    patched_files = 0
    stripped_ort = 0
    tmp_out = sherpa_aar.with_suffix(".aar.aligned.tmp")
    with zipfile.ZipFile(sherpa_aar, "r") as zin, zipfile.ZipFile(tmp_out, "w") as zout:
        for info in zin.infolist():
            payload = zin.read(info.filename)
            if info.filename.startswith("jni/") and info.filename.endswith("/libonnxruntime.so"):
                stripped_ort += 1
                continue
            if info.filename.startswith("jni/") and info.filename.endswith(".so"):
                parts = info.filename.split("/")
                abi = parts[1] if len(parts) >= 3 else ""
                target_ver = target_by_abi.get(abi) or fallback_target
                assert target_ver is not None
                payload, did_patch = patch_elf_ort_verneed(payload, target_ver)
                if did_patch:
                    patched_files += 1
                    print(f"[ALIGN] {info.filename} -> {target_ver.decode('ascii')}")

            new_info = zipfile.ZipInfo(filename=info.filename, date_time=info.date_time)
            new_info.compress_type = info.compress_type
            new_info.external_attr = info.external_attr
            zout.writestr(new_info, payload)

    shutil.move(str(tmp_out), str(sherpa_aar))
    print(
        f"[ALIGN] Repacked {sherpa_aar.name}: stripped {stripped_ort} duplicate libonnxruntime.so, "
        f"aligned {patched_files} JNI shared libraries."
    )

    # Fail-closed self-verification on the repacked AAR
    verify_archive_ort_alignment(sherpa_aar, target_by_abi, fallback_target)


def verify_archive_ort_alignment(
    archive_path: Path,
    override_target_by_abi: dict[str, bytes] | None = None,
    override_fallback: bytes | None = None,
) -> None:
    """Verify every .so inside an APK or AAR matches the packaged/expected libonnxruntime.so version."""
    with zipfile.ZipFile(archive_path, "r") as zf:
        names = zf.namelist()
        target_by_abi: dict[str, bytes] = dict(override_target_by_abi or {})
        for name in names:
            if (name.startswith("lib/") or name.startswith("jni/")) and name.endswith("/libonnxruntime.so"):
                abi = name.split("/")[1]
                target_by_abi[abi] = read_ort_exported_version(zf.read(name))

        if not target_by_abi and override_fallback is None:
            raise RuntimeError(f"No libonnxruntime.so found inside {archive_path}")

        checked = 0
        mismatches: list[str] = []
        for name in names:
            if not ((name.startswith("lib/") or name.startswith("jni/")) and name.endswith(".so")):
                continue
            if name.endswith("/libonnxruntime.so"):
                continue
            abi = name.split("/")[1]
            expected_ver = target_by_abi.get(abi) or override_fallback
            if expected_ver is None:
                continue
            expected_hash = elf_hash(expected_ver)
            verneeds = inspect_elf_ort_verneeds(zf.read(name))
            checked += 1
            for ver_name, vna_hash in verneeds:
                if ver_name != expected_ver or vna_hash != expected_hash:
                    mismatches.append(
                        f"{name}: requires {ver_name.decode('ascii', 'replace')} (hash=0x{vna_hash:x}), "
                        f"expected {expected_ver.decode('ascii')} (hash=0x{expected_hash:x})"
                    )

        if mismatches:
            raise RuntimeError("ELF symbol-version mismatch detected:\n  - " + "\n  - ".join(mismatches))

        summary = ", ".join(f"{abi}={ver.decode('ascii')}" for abi, ver in sorted(target_by_abi.items()))
        print(f"[VERIFY] {archive_path.name}: {checked} native .so libraries verified ({summary}).")


def run_self_test() -> None:
    """Unit-test SysV ELF hash and real/synthetic verneed alignment."""
    assert elf_hash(b"VERS_1.28.2") == 0x027BFE82, hex(elf_hash(b"VERS_1.28.2"))
    assert elf_hash(b"VERS_1.31.1") == 0x027BC581, hex(elf_hash(b"VERS_1.31.1"))

    # If the local v2.5.2 APK is present, test against its real arm64-v8a / x86_64 binaries non-destructively
    repo_root = Path(__file__).resolve().parent.parent
    sample_apk = repo_root / "builds" / "PURR-Engine_V2.5.2_Android.apk"
    if sample_apk.is_file():
        with zipfile.ZipFile(sample_apk, "r") as zf:
            for abi in ("arm64-v8a", "x86_64"):
                ort_bytes = zf.read(f"lib/{abi}/libonnxruntime.so")
                target_ver = read_ort_exported_version(ort_bytes)
                assert target_ver == b"VERS_1.31.1", (abi, target_ver)
                for lib_name in ("libsherpa-onnx-jni.so", "libsherpa-onnx-c-api.so"):
                    raw_so = zf.read(f"lib/{abi}/{lib_name}")
                    before = inspect_elf_ort_verneeds(raw_so)
                    assert before == [(b"VERS_1.28.2", 0x027BFE82)], (abi, lib_name, before)
                    patched_so, changed = patch_elf_ort_verneed(raw_so, target_ver)
                    assert changed
                    after = inspect_elf_ort_verneeds(patched_so)
                    assert after == [(b"VERS_1.31.1", 0x027BC581)], (abi, lib_name, after)
        print("[SELF_TEST] Verified ELF hash + real arm64-v8a/x86_64 sherpa/ORT alignment against V2.5.2 APK.")
    else:
        print("[SELF_TEST] Verified SysV ELF hashes cleanly.")


def main() -> int:
    parser = argparse.ArgumentParser(description="Align sherpa-onnx JNI .so ONNX Runtime symbol versions.")
    parser.add_argument("--sherpa-aar", type=Path, help="Path to sherpa-onnx AAR to strip & align in place")
    parser.add_argument("--ort-aar", type=Path, help="Path to onnxruntime-android AAR")
    parser.add_argument("--ort-so", type=Path, help="Path to reference libonnxruntime.so")
    parser.add_argument("--verify-apk", type=Path, help="Verify every .so in an APK or AAR matches libonnxruntime.so")
    parser.add_argument("--self-test", action="store_true", help="Run built-in ELF alignment self-tests")
    args = parser.parse_args()

    if args.self_test:
        run_self_test()
        return 0
    if args.sherpa_aar:
        align_sherpa_aar(args.sherpa_aar, args.ort_aar, args.ort_so)
        return 0
    if args.verify_apk:
        verify_archive_ort_alignment(args.verify_apk)
        return 0

    parser.print_help()
    return 1


if __name__ == "__main__":
    sys.exit(main())
