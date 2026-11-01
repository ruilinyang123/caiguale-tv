#!/usr/bin/env python3
"""Unit tests for ci/verify_caiguale_base_apk.py and ci/check_exo_base_source.py.

These tests build synthetic ZIP files and fake source trees; they never invoke
Gradle and never produce a real APK. Run with: python3 ci/test_base_policy.py
"""
from __future__ import annotations

import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
import sys

ROOT = Path(__file__).resolve().parents[1]


def load(name: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / "ci" / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


verifier = load("verify_caiguale_base_apk")
source_check = load("check_exo_base_source")

GOOD_DEX = b"....Landroidx/media3/exoplayer/ExoPlayer;...."


class VerifyApkTest(unittest.TestCase):
    def make_apk(self, tmp: Path, entries: dict[str, bytes], name: str = "app-arm64-v8a-debug.apk") -> Path:
        target = tmp / "app/build/outputs/apk/leanbackLiteArm64_v8a/debug" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(target, "w") as zf:
            for path, data in entries.items():
                zf.writestr(path, data)
        return target

    def test_accepts_exo_only_arm64(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            self.make_apk(tmp, {"classes.dex": GOOD_DEX, "lib/arm64-v8a/libfoo.so": b"\x7fELF"})
            apks = verifier.find_apks(tmp)
            self.assertEqual(len(apks), 1)
            errors, details = verifier.check(apks[0])
            self.assertEqual(details["abis"], ["arm64-v8a"])
            # aapt2 may be absent on this host; that single check is environment-specific.
            self.assertTrue(all("aapt" in e for e in errors), errors)

    def test_accepts_32bit_arm_exo_only(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            self.make_apk(tmp, {"classes.dex": GOOD_DEX, "lib/armeabi-v7a/libquickjs.so": b"\\x7fELF"}, name="app-armeabi-v7a-debug.apk")
            with patch.object(verifier, "EXPECTED_ABI", "armeabi-v7a"):
                errors, details = verifier.check(verifier.find_apks(tmp)[0])
            self.assertEqual(details["abis"], ["armeabi-v7a"])
            self.assertTrue(all("aapt" in e for e in errors), errors)

    def test_32bit_target_rejects_arm64_library(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            self.make_apk(tmp, {"classes.dex": GOOD_DEX, "lib/arm64-v8a/libquickjs.so": b"\\x7fELF"})
            with patch.object(verifier, "EXPECTED_ABI", "armeabi-v7a"):
                errors, _ = verifier.check(verifier.find_apks(tmp)[0])
            self.assertTrue(any("ABI set" in e for e in errors), errors)

    def test_rejects_missing_native_abi(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            self.make_apk(tmp, {"classes.dex": GOOD_DEX})
            with patch.object(verifier, "EXPECTED_ABI", "armeabi-v7a"):
                errors, _ = verifier.check(verifier.find_apks(tmp)[0])
            self.assertTrue(any("ABI set" in e for e in errors), errors)

    def test_rejects_mpv_and_ijk_native(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            self.make_apk(tmp, {
                "classes.dex": GOOD_DEX,
                "lib/arm64-v8a/libmpv.so": b"\x7fELF",
                "lib/arm64-v8a/libijkffmpeg.so": b"\x7fELF",
            })
            errors, _ = verifier.check(verifier.find_apks(tmp)[0])
            self.assertTrue(any("libmpv" in e for e in errors), errors)
            self.assertTrue(any("libijk" in e for e in errors), errors)

    def test_rejects_second_abi(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            self.make_apk(tmp, {"classes.dex": GOOD_DEX, "lib/arm64-v8a/a.so": b"", "lib/armeabi-v7a/a.so": b""})
            errors, _ = verifier.check(verifier.find_apks(tmp)[0])
            self.assertTrue(any("ABI set" in e for e in errors), errors)

    def test_rejects_excluded_sdk_strings(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            self.make_apk(tmp, {"classes.dex": GOOD_DEX + b"Lcom/xunlei/downloadlib/XLTaskHelper;"})
            errors, _ = verifier.check(verifier.find_apks(tmp)[0])
            self.assertTrue(any("xunlei" in e for e in errors), errors)

    def test_requires_exoplayer(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            self.make_apk(tmp, {"classes.dex": b"nothing"})
            errors, _ = verifier.check(verifier.find_apks(tmp)[0])
            self.assertTrue(any("ExoPlayer" in e for e in errors), errors)

    def test_rejects_wrong_apk_count(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(verifier.find_apks(Path(d)), [])


class SourcePolicyTest(unittest.TestCase):
    def test_repository_tree_passes(self):
        errors = source_check.check(ROOT)
        self.assertEqual(errors, [], errors)

    def test_detects_bundled_aar(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            (tmp / "app/src/main").mkdir(parents=True)
            (tmp / "app/src/main/junk.aar").write_bytes(b"PK")
            (tmp / "app/build.gradle").write_text("implementation fileTree(dir: 'libs')")
            (tmp / "gradle").mkdir()
            (tmp / "gradle/libs.versions.toml").write_text('media3 = "1.11.0-alpha01-fongmi"')
            errors = source_check.check(tmp)
            self.assertTrue(any("unapproved binary" in e for e in errors), errors)
            self.assertTrue(any("custom media dependency" in e for e in errors), errors)


if __name__ == "__main__":
    unittest.main(verbosity=2)
