"""Makes the small web font files: NanumBarunGothic with only the common characters, under another family name
(cycle 08 handoff step 7-5, R10-5, AC-13).

Usage (from the repository root; needs fonttools and brotli, user approval 2026-10-11):
  python scripts/web/subset_font.py

Reads the package files in web/node_modules/@kfonts/nanum-barun-gothic and writes
web/src/assets/fonts/ChaekcheckBarun-{Regular,Bold}.woff2. Characters kept:
- the 2,350 Hangul syllables of KS X 1001 (the ones EUC-KR writes in two bytes) and the Hangul compatibility jamo,
- ASCII, Latin-1, general punctuation, arrows, enclosed alphanumerics (ⓘ), geometric shapes, CJK symbols,
  fullwidth forms,
- every other character written in web/src (the screens' own text), so no screen text falls back.
Book titles may hold rare syllables; the page lists the full NanumBarunGothic after this family, so the browser
fetches the full file only for a page that has such a syllable.

The family is renamed (the license's Reserved Font Names include NanumBarunGothic in the copies of
Nanum_Font_License.txt found 2026-10-11; a renamed subset is fine either way). The copyright and trademark records
stay in the file.
"""
import os
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

PACKAGE = 'web/node_modules/@kfonts/nanum-barun-gothic/'
OUT = 'web/src/assets/fonts/'
FAMILY = 'Chaekcheck Barun'
FILES = [('NanumBarunGothic.woff2', 'Regular'), ('NanumBarunGothicBold.woff2', 'Bold')]
RANGES = [
    (0x0020, 0x007E),  # ASCII
    (0x00A0, 0x00FF),  # Latin-1
    (0x2000, 0x206F),  # general punctuation
    (0x2190, 0x21FF),  # arrows
    (0x2460, 0x24FF),  # enclosed alphanumerics
    (0x25A0, 0x25FF),  # geometric shapes
    (0x3000, 0x303F),  # CJK symbols and punctuation
    (0x3131, 0x318E),  # Hangul compatibility jamo
    (0xFF01, 0xFF5E),  # fullwidth forms
]


def common_hangul():
    found = []
    for code in range(0xAC00, 0xD7A4):
        # Python's EUC-KR writes the other syllables as 8-byte jamo sequences; the 2,350 take two bytes
        if len(chr(code).encode('euc_kr')) == 2:
            found.append(code)
    return found


def screen_characters():
    found = set()
    for root, _, files in os.walk('web/src'):
        for name in files:
            if name.endswith(('.ts', '.tsx', '.css', '.json')) and '.test.' not in name:
                found.update(ord(c) for c in open(os.path.join(root, name), encoding='utf-8').read() if ord(c) > 0x7F)
    return found


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    hangul = common_hangul()
    assert len(hangul) == 2350, len(hangul)
    codes = set(hangul)
    for start, end in RANGES:
        codes.update(range(start, end + 1))
    extra = sorted(screen_characters() - codes)
    codes.update(extra)
    print(f'characters: {len(codes)} (KS X 1001 Hangul {len(hangul)}, from the screens besides: '
          f'{"".join(chr(c) for c in extra) or "none"})')
    os.makedirs(OUT, exist_ok=True)
    for source, style in FILES:
        font = TTFont(PACKAGE + source)
        options = subset.Options()
        options.flavor = 'woff2'
        options.layout_features = ['*']
        options.name_IDs = ['*']
        options.name_languages = ['*']
        options.notdef_outline = True
        subsetter = subset.Subsetter(options)
        subsetter.populate(unicodes=codes)
        subsetter.subset(font)
        rename(font, style)
        target = f'{OUT}ChaekcheckBarun-{style}.woff2'
        font.flavor = 'woff2'
        font.save(target)
        print(f'{target}: {os.path.getsize(PACKAGE + source):,} -> {os.path.getsize(target):,} bytes')


def rename(font, style):
    """Family, full and PostScript names to the new family; every other record (copyright, trademark) stays."""
    full = f'{FAMILY} {style}' if style != 'Regular' else FAMILY
    names = {1: FAMILY, 4: full, 6: f'ChaekcheckBarun-{style}', 16: FAMILY, 17: style}
    table = font['name']
    for record in table.names:
        if record.nameID in names:
            record.string = names[record.nameID]
        if record.nameID == 3:
            record.string = f'Chaekcheck: {FAMILY} {style}: subset of NanumBarunGothic'


if __name__ == '__main__':
    os.chdir(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
    main()
