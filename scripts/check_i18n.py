#!/usr/bin/env python3
"""Compare string resources across locales and report gaps.

Android silently falls back to values/ for any key a locale is missing, so a
missing translation never fails the build — the screen just shows English. This
walks every source set (main / mobile / leanback) and reports four things:

1. keys a locale is missing (it will render English there)
2. keys a locale has that the default does not (dead weight, or a typo)
3. translations still byte-identical to the default (usually "not translated")
4. format placeholders / xliff:g counts that disagree with the default — the
   default is what the code was written against, so a translation that drops a
   %s crashes with IllegalFormatException at runtime

Both <string> and <string-array> are covered; the option-label arrays are as
user-visible as any single string.

Exit status is 1 when anything from group 1, 2 or 4 is found, so it can gate CI.
Group 3 is reported but does not fail: some values are legitimately the same.

    python3 scripts/check_i18n.py
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
RES = ROOT / 'app' / 'src'

SOURCE_SETS = ['main', 'mobile', 'leanback']
LOCALES = {'values-zh-rCN': '简体中文', 'values-zh-rTW': '繁体中文'}
DEFAULT = 'values'

PLACEHOLDER = re.compile(r'%(?:\d+\$)?[sdfx]')

failures = []


def note(kind, message):
    print(f'  [{kind}] {message}')
    if kind in ('缺失', '多余', '占位符'):
        failures.append(message)


def load(path):
    """name -> (full text, placeholder list). Covers <string> and <string-array>.

    Arrays matter as much as single strings: select_scale / select_reset and friends
    are user-visible option labels, and a locale that omits the whole array falls
    back to English for every entry at once.
    """
    if not path.exists():
        return None
    root = ET.parse(path).getroot()
    out = {}
    for node in list(root.findall('string')) + list(root.findall('string-array')):
        name = node.get('name')
        if name is None:
            continue
        # translatable="false" entries (embedded images, constants) are deliberately
        # identical everywhere; comparing them only produces noise.
        if node.get('translatable') == 'false':
            continue
        if node.tag == 'string-array':
            text = '\n'.join(''.join(item.itertext()) for item in node.findall('item'))
        else:
            text = ''.join(node.itertext())
        out[name] = (text, PLACEHOLDER.findall(text))
    return out


def compare(source_set, default, locale_key, locale_label, default_label):
    differing = []
    for name, (text, slots) in sorted(default.items()):
        if name not in locale_key:
            note('缺失', f'{source_set}: {name} 在 {locale_label} 中缺失（会回退显示 {default_label}）')
            continue
        other_text, other_slots = locale_key[name]
        if sorted(slots) != sorted(other_slots):
            note('占位符', f'{source_set}: {name} 占位符不一致 — {default_label}={slots} {locale_label}={other_slots}')
        if other_text == text:
            differing.append(name)
    for name in sorted(set(locale_key) - set(default)):
        note('多余', f'{source_set}: {name} 只存在于 {locale_label}，{default_label} 里没有')
    if differing:
        print(f'  [同值] {source_set}: {len(differing)} 条与 {default_label} 相同')
        print(f'         专名、符号、URL 示例与纯格式串本就该相同，其余需人工确认：')
        for name in differing[:15]:
            print(f'             {name} = {default[name][0][:60]}')
        if len(differing) > 15:
            print(f'             …另有 {len(differing) - 15} 条')


def main():
    for source_set in SOURCE_SETS:
        base = RES / source_set / 'res'
        default = load(base / DEFAULT / 'strings.xml')
        if default is None:
            print(f'== {source_set}: 没有默认 strings.xml，跳过 ==\n')
            continue
        print(f'== {source_set}（默认 {len(default)} 条）==')
        for locale, label in LOCALES.items():
            data = load(base / locale / 'strings.xml')
            if data is None:
                note('缺失', f'{source_set}: 整个 {locale} 目录缺失')
                continue
            print(f'  -- {label}（{len(data)} 条）--')
            compare(source_set, default, data, label, '英文')
        print()

    print('=' * 60)
    if failures:
        print(f'{len(failures)} 项需要处理')
        return 1
    print('三种语言完全一致')
    return 0


if __name__ == '__main__':
    sys.exit(main())
