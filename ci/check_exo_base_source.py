#!/usr/bin/env python3
"""Fail-closed EXO-only source policy. Does not invoke Gradle or network."""
from pathlib import Path
import argparse, json, re, sys
ROOT=Path(__file__).resolve().parents[1]
BAD_IMPORTS=('com.forcetech.', 'com.p2p.', 'com.xunlei.downloadlib.', 'com.tvbus.engine.', 'com.fongmi.hook.', 'tv.danmaku.ijk.', 'is.xyz.mpv.', 'androidx.media3.mpvplayer.', 'io.github.anilbeesetti.nextlib.')
def check(root):
    errors=[]
    for p in sorted(root.rglob('*')):
        if not p.is_file() or any(x in p.relative_to(root).parts for x in ('.git','build','.gradle','__pycache__')):continue
        rel=p.relative_to(root).as_posix()
        if p.suffix.lower() in ('.aar','.so','.dex','.apk','.jks','.keystore','.tflite') or (p.suffix=='.jar' and rel!='gradle/wrapper/gradle-wrapper.jar'):
            errors.append(f'unapproved binary: {rel}')
        if rel.startswith('app/src/main/assets/lut_presets/'):
            errors.append(f'LUT preset with unverified rights: {rel}')
        if p.suffix=='.java' and '/src/' in rel:
            text=p.read_text()
            if any(b in text for b in BAD_IMPORTS):errors.append(f'excluded SDK reference: {rel}')
        if rel.startswith('webhome-devkit/') and p.suffix in ('.html','.js','.json'):
            if re.search(r'''(?i)(?:^|[{,])\s*["']?(?:apiKey|password|sharePassword|sharePwd|pwd)["']?\s*:\s*["'][^"'\r\n]+["']''',p.read_text(),re.M):
                errors.append(f'nonempty demo credential: {rel}')
    g=(root/'app/build.gradle').read_text()
    if 'implementation fileTree' in g:errors.append('unbounded app/libs dependency')
    if 'implementation libs.nextlib' in g:errors.append('nextlib dependency')
    if not all(variant in g for variant in ('leanbackLiteArm64_v8aDebug', 'leanbackLiteArmeabi_v7aDebug')):errors.append('base target variants not restricted to ARM64 and ARMv7')
    if 'applicationId "com.caiguale.tv"' not in g:errors.append('wrong applicationId')
    catalog=(root/'gradle/libs.versions.toml').read_text()
    if '-fongmi' in catalog or 'media3-ui-danmaku' in catalog:errors.append('custom media dependency')
    return errors
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--root',type=Path,default=ROOT);args=parser.parse_args()
    errors=check(args.root)
    print(json.dumps({'ok':not errors,'errors':errors,'gradle_executed':False},ensure_ascii=False,indent=2))
    sys.exit(bool(errors))
