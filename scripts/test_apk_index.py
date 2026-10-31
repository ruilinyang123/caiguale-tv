#!/usr/bin/env python3
"""Regression tests for apk_index.py.

The download page is generated inside the release job, so a mistake here only
surfaces after a ~25 minute build — and it exits hard, blocking the whole
release. Two things are worth pinning: the package set must be validated against
the flavor matrix (a hardcoded count once broke a release when the lite edition
changed the total), and variant keys must be recognised both with and without a
version suffix in the file name (release assets are published as
<flavor>-<version>.apk so each release gets a URL the CDN has never cached).

    python3 scripts/test_apk_index.py
"""
import contextlib
import importlib.util
import io
import json
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent

FLAVORS = [
    'mobile-arm64_v8a', 'mobile-universal', 'mobile-lite-arm64_v8a', 'mobile-lite-universal',
    'leanback-arm64_v8a', 'leanback-universal', 'leanback-lite-arm64_v8a', 'leanback-lite-universal',
    'mobile-armeabi_v7a', 'leanback-armeabi_v7a',
]

failures = []


def check(name, ok):
    print(f"  {'PASS' if ok else 'FAIL'}  {name}")
    if not ok:
        failures.append(name)


def load():
    spec = importlib.util.spec_from_file_location('apk_index', HERE / 'apk_index.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_dist(root, style):
    """style 'versioned' mirrors the release workflow; 'legacy' has bare names."""
    root.mkdir(parents=True, exist_ok=True)
    for flavor in FLAVORS:
        if style == 'versioned':
            manifest = dict(name='v9.9.9', versionName='9.9.9', code=999,
                            apk=f'{flavor}-9.9.9.apk', flavor=flavor,
                            size=1000, sha256='a' * 64, cnb=True)
        else:
            manifest = dict(name='v1.0.0', versionName='1.0.0', code=100,
                            apk=f'{flavor}.apk', size=1000, sha256='b' * 64, cnb=False)
        (root / f'{flavor}.json').write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
    return root


def run_index(module, dist):
    sys.argv = ['apk_index.py', str(dist)]
    with contextlib.redirect_stdout(io.StringIO()):
        module.main()


def main():
    module = load()

    print('== flavor_of：新旧两种清单格式都要认得出变体 ==')
    check('显式 flavor 字段优先', module.flavor_of({'apk': 'mobile-arm64_v8a-9.9.9.apk', 'flavor': 'mobile-arm64_v8a'}) == 'mobile-arm64_v8a')
    check('剥掉版本后缀', module.flavor_of({'apk': 'mobile-arm64_v8a-9.9.9.apk'}) == 'mobile-arm64_v8a')
    check('无版本名的旧格式不变', module.flavor_of({'apk': 'mobile-arm64_v8a.apk'}) == 'mobile-arm64_v8a')
    check('精简版不被误伤', module.flavor_of({'apk': 'leanback-lite-arm64_v8a-9.9.9.apk'}) == 'leanback-lite-arm64_v8a')
    check('v7a 不被误伤', module.flavor_of({'apk': 'mobile-armeabi_v7a-9.9.9.apk'}) == 'mobile-armeabi_v7a')

    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)

        print('== 新格式（发版产出）生成下载页 ==')
        d = write_dist(base / 'versioned', 'versioned')
        run_index(module, d)
        page = (d / 'index.html').read_text(encoding='utf-8')
        check('写出 index.html', (d / 'index.html').exists())
        check('每个包两处都指向带版本的文件名', page.count('-9.9.9.apk') == len(FLAVORS) * 2)
        check('描述匹配成功（说明 flavor 识别正确）', 'Android 手机 · 新设备' in page and 'Android TV · 老盒子' in page)
        check('按 ORDER 排序', page.index('mobile-arm64_v8a-9.9.9.apk') < page.index('mobile-universal-9.9.9.apk'))
        check('版本号写进标题', 'v9.9.9' in page)

        print('== 旧格式（无版本名）仍能生成 ==')
        d2 = write_dist(base / 'legacy', 'legacy')
        run_index(module, d2)
        page2 = (d2 / 'index.html').read_text(encoding='utf-8')
        check('旧格式生成成功', 'Android 手机 · 新设备' in page2)
        check('旧格式链接保持原样', 'mobile-arm64_v8a.apk' in page2)

        print('== 缺包必须报错（防住静默发版）==')
        d3 = write_dist(base / 'broken', 'versioned')
        (d3 / 'leanback-universal.json').unlink()
        try:
            run_index(module, d3)
            check('缺包时报错退出', False)
        except SystemExit as exc:
            check('缺包时报错退出并说明原因', 'mismatch' in str(exc))

    print()
    if failures:
        print(f'{len(failures)} 项未通过')
        return 1
    print('全部通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())
