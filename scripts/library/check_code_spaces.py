"""Checks which screen kinds share one book-code numbering (library-maintenance.md 4-11).

The catalog importer groups holdings with the same code into one book (BookGrouping step 1):
Kyobo, Kyobo subscription and YES24 codes across all libraries, portal and Gangnam codes only
inside one library. Sites can change how they number books, so this is re-checked whenever the
library list is checked. Nothing is decided here: the script prints the numbers, a person decides.

Reads the collected SQLite read-only (the crawler may be writing). Only libraries of the service
(dev seed, id < 100000) and rows not removed, audio and video books left out, as in the import.

For every pair of screen kinds: codes that both have, and how many of them have the same title
(normalized: NFKC, lower case, no spaces, first 8 characters). For every screen kind: codes held
by several libraries of that kind, and how many of them have different titles there.

Usage (repository root):
    python scripts/library/check_code_spaces.py ../chaekcheck-data/ebook-catalog.sqlite
"""
import sqlite3
import sys
import unicodedata
from collections import defaultdict


def norm(title):
    text = unicodedata.normalize("NFKC", title or "").lower()
    return "".join(text.split())[:8]


def main(path):
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    rows = db.execute(
        """SELECT l.platform_type, h.library_id, h.item_key, h.title
           FROM holding h JOIN library l ON l.id = h.library_id
           WHERE h.removed_at IS NULL AND l.id < 100000
             AND h.content_type NOT IN ('AUDIOBOOK', 'VIDEO_BOOK')"""
    )
    # code -> screen kind -> titles; code -> screen kind -> libraries
    titles = defaultdict(lambda: defaultdict(set))
    libraries = defaultdict(lambda: defaultdict(set))
    count = 0
    for platform, library_id, code, title in rows:
        count += 1
        titles[code][platform].add(norm(title))
        libraries[code][platform].add(library_id)

    pairs = defaultdict(lambda: [0, 0])
    for kinds in titles.values():
        names = sorted(kinds)
        for i, a in enumerate(names):
            for b in names[i + 1:]:
                pairs[(a, b)][0 if kinds[a] & kinds[b] else 1] += 1

    print(f"rows read: {count}")
    print()
    print("## screen kinds that share codes (same code, same title?)")
    print("kind A\tkind B\tsame title\tdifferent\tdifferent %")
    for (a, b), (same, different) in sorted(pairs.items()):
        total = same + different
        print(f"{a}\t{b}\t{same}\t{different}\t{100 * different / total:.1f}")
    print()
    print("## inside one screen kind (codes held by 2+ libraries)")
    print("kind\tcodes in 2+ libraries\twith different titles\tdifferent %")
    for kind in sorted({k for kinds in titles.values() for k in kinds}):
        shared = [code for code in titles if len(libraries[code].get(kind, ())) > 1]
        different = sum(1 for code in shared if len(titles[code][kind]) > 1)
        if shared:
            print(f"{kind}\t{len(shared)}\t{different}\t{100 * different / len(shared):.1f}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
