#!/usr/bin/env python3
"""Verify a locally built EXO-only base APK without Gradle.

Checks the artifact against the base build contract:
- package id com.caiguale.tv
- exactly one ABI (arm64-v8a)
- none of the excluded proprietary/native SDKs
- official Media3 ExoPlayer present, fork-only Media3 extensions absent
"""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

APK_GLOB = "app/build/outputs/apk/**/*.apk"
EXPECTED_APP_ID = "com.caiguale.tv"
EXPECTED_ABI = "arm64-v8a"

FORBIDDEN_ENTRY_PATTERNS = [
    r"^lib/[^/]+/libijk.*\.so$",
    r"^lib/[^/]+/libmpv.*\.so$",
    r"^lib/[^/]+/libtvbus.*\.so$",
    r"^lib/[^/]+/libforcetech.*\.so$",
    r"^lib/[^/]+/libp2p.*\.so$",
    r"^lib/[^/]+/libxl.*\.so$",
    r"^lib/[^/]+/libxunlei.*\.so$",
    r"^assets/mpv-libs/",
]

FORBIDDEN_DEX_STRINGS = [
    "Lcom/forcetech/",
    "Lcom/p2p/",
    "Lcom/xunlei/downloadlib/",
    "Lcom/tvbus/engine/",
    "Lcom/fongmi/hook/",
    "Ltv/danmaku/ijk/media/player/",
    "Lis/xyz/mpv/",
    "Landroidx/media3/mpvplayer/",
    "Landroidx/media3/ui/danmaku/",
    "Lio/github/anilbeesetti/nextlib/",
]

REQUIRED_DEX_STRINGS = ["Landroidx/media3/exoplayer/ExoPlayer;"]


def find_apks(root: Path) -> list[Path]:
    return sorted(root.glob(APK_GLOB))


def read_badging(apk: Path) -> str | None:
    for tool in ("aapt2", "aapt"):
        try:
            out = subprocess.run([tool, "dump", "badging", str(apk)], capture_output=True, text=True, timeout=120)
        except (FileNotFoundError, subprocess.TimeoutExpired):
            continue
        if out.returncode == 0:
            return out.stdout
    return None


def check(apk: Path) -> tuple[list[str], dict]:
    errors: list[str] = []
    details: dict = {"apk": str(apk), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}
    with zipfile.ZipFile(apk) as zf:
        names = zf.namelist()
        details["entry_count"] = len(names)
        abis = sorted({n.split("/")[1] for n in names if n.startswith("lib/") and n.count("/") >= 2})
        details["abis"] = abis
        if abis and abis != [EXPECTED_ABI]:
            errors.append(f"ABI set {abis} != ['{EXPECTED_ABI}']")
        for pattern in FORBIDDEN_ENTRY_PATTERNS:
            for name in names:
                if re.match(pattern, name):
                    errors.append(f"forbidden native/asset entry: {name}")
        dex = b"".join(zf.read(n) for n in names if re.match(r"^classes\d*\.dex$", n))
        details["dex_bytes"] = len(dex)
        for needle in FORBIDDEN_DEX_STRINGS:
            if needle.encode() in dex:
                errors.append(f"excluded SDK referenced in dex: {needle}")
        for needle in REQUIRED_DEX_STRINGS:
            if needle.encode() not in dex:
                errors.append(f"required ExoPlayer class missing from dex: {needle}")
        badging = read_badging(apk)
        if badging is None:
            details["badging"] = "unavailable (aapt2/aapt not on PATH)"
            errors.append("package id unverified: aapt2/aapt not available")
        else:
            m = re.search(r"package: name='([^']+)'", badging)
            details["package"] = m.group(1) if m else None
            if not m or m.group(1) != EXPECTED_APP_ID:
                errors.append(f"package id {details['package']} != {EXPECTED_APP_ID}")
            native = re.search(r"native-code: (.*)", badging)
            details["badging_native_code"] = native.group(1).strip() if native else ""
            if native:
                declared = native.group(1).strip()
                if EXPECTED_ABI not in declared or "armeabi" in declared:
                    errors.append(f"badging native-code '{declared}' not arm64-only")
    return errors, details


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    apks = find_apks(root)
    if len(apks) != 1:
        print(json.dumps({"ok": False, "errors": [f"expected exactly one APK under {APK_GLOB}, found {len(apks)}"]}, indent=2))
        return 1
    errors, details = check(apks[0])
    lines = [f"{details['sha256']}  {apks[0].name}"]
    (root / "ci-base-sha256.txt").write_text("\n".join(lines) + "\n")
    print(json.dumps({"ok": not errors, "errors": errors, "details": details, "gradle_executed": False}, ensure_ascii=False, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
