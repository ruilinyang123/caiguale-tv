#!/usr/bin/env python3
"""Generate the static APK download index page from dist/*.json manifests.

Used by the release workflow (dist/index.html) and locally to refresh
https://pan.imotao.com/file/apk/. Output is self-contained: inline CSS,
no external assets, so it renders on any network.
"""
import html
import json
import re
import sys
import time
from pathlib import Path

DESCRIPTIONS = {
    'mobile-universal': 'Android 手机 · 不确定架构',
    'mobile-arm64_v8a': 'Android 手机 · 新设备',
    'mobile-armeabi_v7a': 'Android 手机 · 老设备',
    'mobile-lite-universal': 'Android 手机 · 精简版 · 不确定架构',
    'mobile-lite-arm64_v8a': 'Android 手机 · 精简版 · 新设备',
    'leanback-universal': 'Android TV · 不确定架构',
    'leanback-arm64_v8a': 'Android TV · 新电视盒子',
    'leanback-armeabi_v7a': 'Android TV · 老盒子',
    'leanback-lite-universal': 'Android TV · 精简版 · 不确定架构',
    'leanback-lite-arm64_v8a': 'Android TV · 精简版 · 新电视盒子',
}
ORDER = ['mobile-arm64_v8a', 'mobile-universal', 'mobile-lite-arm64_v8a', 'mobile-lite-universal', 'leanback-arm64_v8a', 'leanback-universal', 'leanback-lite-arm64_v8a', 'leanback-lite-universal', 'mobile-armeabi_v7a', 'leanback-armeabi_v7a']
GITHUB = 'https://github.com/motao123/webtv'


VERSIONED = re.compile(r'-\d+(?:\.\d+)*$')


def flavor_of(manifest: dict) -> str:
    """Variant key, independent of whether the file name carries a version.

    Manifests from the release workflow name this explicitly; older ones only
    carry `apk`, so strip a trailing version as a fallback. Release assets are
    published as <flavor>-<version>.apk so every release gets a URL the CDN has
    never cached, which is why the bare name can no longer be assumed.
    """
    flavor = manifest.get('flavor')
    if flavor:
        return flavor
    return VERSIONED.sub('', Path(manifest['apk']).stem)


def size_mb(size: int) -> str:
    return f'{size / 1000 / 1000:.1f} MB'


def render(manifests: list) -> str:
    manifests = sorted(manifests, key=lambda m: ORDER.index(flavor_of(m)) if flavor_of(m) in ORDER else 99)
    version = html.escape(manifests[0]['versionName'])
    code = manifests[0]['code']
    rows = []
    for m in manifests:
        stem = flavor_of(m)
        rows.append(f'''      <a class="row" href="{html.escape(m['apk'])}">
        <span class="info"><strong>{html.escape(m['apk'])}</strong><small>{html.escape(DESCRIPTIONS.get(stem, ''))}</small></span>
        <span class="meta">{size_mb(m['size'])}<small>SHA-256 已随更新清单校验</small></span>
      </a>''')
    return f'''<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex">
<title>WebHomeTV APK 下载 · v{version}</title>
<style>
  :root {{ --blue:#1a6bff; --ink:#111418; --muted:#5c6470; --line:#e6e9ee; }}
  * {{ box-sizing:border-box; margin:0; padding:0; }}
  body {{ font-family:-apple-system,"Segoe UI","Microsoft YaHei",sans-serif; background:#f5f7fa; color:var(--ink); }}
  main {{ max-width:680px; margin:0 auto; padding:36px 20px 56px; }}
  .kicker {{ color:var(--blue); font-size:12px; font-weight:700; letter-spacing:.12em; }}
  h1 {{ font-size:26px; margin:8px 0 4px; }}
  .sub {{ color:var(--muted); font-size:14px; margin-bottom:26px; }}
  .sub b {{ color:var(--ink); }}
  .rows {{ display:flex; flex-direction:column; gap:10px; }}
  .row {{ display:flex; align-items:center; justify-content:space-between; gap:14px; background:#fff; border:1px solid var(--line); border-radius:12px; padding:14px 16px; text-decoration:none; color:inherit; transition:border-color .15s; }}
  .row:hover {{ border-color:var(--blue); }}
  .info strong {{ display:block; font-size:14px; word-break:break-all; }}
  .info small, .meta small {{ display:block; color:var(--muted); font-size:12px; margin-top:2px; }}
  .meta {{ text-align:right; font-size:13px; font-weight:600; white-space:nowrap; }}
  .note {{ margin-top:22px; font-size:13px; color:var(--muted); line-height:1.7; }}
  .note a {{ color:var(--blue); }}
  footer {{ margin-top:30px; padding-top:16px; border-top:1px solid var(--line); font-size:12px; color:var(--muted); }}
</style>
</head>
<body>
<main>
  <div class="kicker">WEBTV · APK MIRROR</div>
  <h1>WebHomeTV v{version}</h1>
  <p class="sub">最新版本 <b>v{version}</b>（versionCode {code}） · 不确定架构时优先选择 universal 通用包</p>
  <div class="rows">
{chr(10).join(rows)}
  </div>
  <p class="note">不确定 CPU 架构时，优先下载对应设备的 <b>universal</b> 通用包。安装前请核对来源；应用内更新会自动校验文件大小与 SHA-256。<br>更多版本与更新说明见 <a href="{GITHUB}/releases">GitHub Releases</a> · 项目主页 <a href="{GITHUB}">GitHub</a></p>
  <footer>生成时间 {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())} · 本页为静态镜像目录，不内置任何影视内容</footer>
</main>
</body>
</html>
'''


def main() -> None:
    dist = Path(sys.argv[1] if len(sys.argv) > 1 else 'dist')
    manifests = [json.loads(p.read_text(encoding='utf-8')) for p in sorted(dist.glob('*.json'))]
    # Check against the flavor matrix instead of a manifest count: the count used
    # to be hardcoded (it said 8 after the lite edition brought it to 8 and then
    # broke the release once TV lite made it 10). This way a new variant only has
    # to be added to DESCRIPTIONS/ORDER, and a missing or unexpected package fails
    # the release loudly.
    if set(ORDER) != set(DESCRIPTIONS):
        raise SystemExit('ORDER and DESCRIPTIONS must list the same packages')
    found = {flavor_of(m) for m in manifests}
    if found != set(DESCRIPTIONS):
        missing = sorted(set(DESCRIPTIONS) - found)
        unexpected = sorted(found - set(DESCRIPTIONS))
        raise SystemExit(f'download page package mismatch: missing={missing} unexpected={unexpected}')
    out = dist / 'index.html'
    out.write_text(render(manifests), encoding='utf-8')
    print(f'wrote {out} ({out.stat().st_size} bytes), {len(manifests)} packages')


if __name__ == '__main__':
    main()
