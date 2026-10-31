#!/usr/bin/env python3
"""Verify APK identity/assets. Device playback still requires separate testing."""
import hashlib
import os
import pathlib
import subprocess
import zipfile

root = pathlib.Path("app/build/outputs/apk")
apks = [p for p in root.rglob("*.apk")
        if "leanback" in p.name.lower() or "leanback" in str(p.parent).lower()]
if len(apks) != 1:
    raise SystemExit(f"Expected one TV APK, found {len(apks)}: {apks}")
apk = apks[0]
sdk = pathlib.Path(os.environ["ANDROID_HOME"]) / "build-tools" / "37.0.0"
badging = subprocess.run([str(sdk / "aapt"), "dump", "badging", str(apk)],
                         text=True, capture_output=True, check=True).stdout
if "package: name='com.caiguale.tv'" not in badging:
    raise SystemExit("Incorrect Android applicationId: " + badging[:600])
subprocess.run([str(sdk / "apksigner"), "verify", "--verbose", str(apk)], check=True)

with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())
    required = {"assets/caiguale/index.html", "assets/caiguale/app.js", "assets/caiguale/styles.css"}
    if not required.issubset(names):
        raise SystemExit("Missing UI assets: " + repr(sorted(required - names)))
    mpv = [n for n in names if n.endswith("/arm64-v8a/libmpv.so")]
    ijk = [n for n in names if n.endswith("/arm64-v8a/libijkffmpeg.so")]
    if not mpv or not ijk:
        raise SystemExit(f"Missing native player libraries: mpv={mpv} ijk={ijk}")
    py = [n for n in names if n.startswith("assets/chaquopy/")]
    if not py:
        raise SystemExit("No Chaquopy runtime assets found")

digest = hashlib.sha256(apk.read_bytes()).hexdigest()
pathlib.Path("ci-build-sha256.txt").write_text(f"{digest}  {apk.name}\n")
print("APK static checks passed:", apk, "SHA256:", digest)
print("NOTE: installation, real source search and playback are not yet validated.")
