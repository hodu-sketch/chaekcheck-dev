"""Checks, per e-library portal library, whether the collected author and publisher fields are swapped.

Why (plan ADR-0115, cycle 19 research.md experiment (d)): on some e-library portal sites (platform
ELIB_PORTAL_V1) the new-book list shows publisher -> author -> date with no labels, and the crawler
reads the first span as the author. The import then swaps the two fields for the libraries listed in
the rules file author_publisher_swapped.tsv. When a copy collected by the fixed crawler arrives (the
import stops on a copy after swap_before), run this script on that copy: delete a library's rules
line only when this script says "바름" for it; for anything else ask the user.

Input: path to a collected catalog SQLite copy (tables library, holding). Opened read-only
(file:...?mode=ro URI); nothing is written anywhere. Standard library only.

Method (experiment (d)):
  1. Reference counts. Every non-removed holding row of every library that is NOT an e-library portal
     (platform_type != 'ELIB_PORTAL_V1') adds 1 to "used as author" for each person name in its
     author field, and 1 to "used as publisher" for its publisher. Portal sites are left out so that
     the swapped sites do not vote for themselves.
  2. Per row of a portal library (non-removed rows): take the first name of the author field, look up
     a = used as author, p = used as publisher.
       a + p < MIN_EVIDENCE                -> row "판단 못 함" (name too rare elsewhere)
       p / (a + p) >= ROW_SHARE            -> row "뒤바뀜" (the author field holds a publisher)
       a / (a + p) >= ROW_SHARE            -> row "바름"
       otherwise                           -> row "판단 못 함" (name used both ways)
  3. Per library, over the decided rows (뒤바뀜 + 바름):
       decided < MIN_DECIDED               -> library "판단 못 함"
       뒤바뀜 / decided >= LIBRARY_SHARE   -> library "뒤바뀜"
       바름 / decided >= LIBRARY_SHARE     -> library "바름"
       otherwise                           -> library "판단 못 함" (mixed: look at the site and ask the user)
  Many rows are "판단 못 함" (names seen nowhere else); that is normal and does not change the verdict.

Name keys: NFKC, angle brackets and other brackets removed, "저자명 :" prefixes and role words
("저", "지음", "(옮긴이)", "외 1명", ...) removed, spaces and punctuation removed, lower case. An
author field is split into names at , ; / · |. A publisher's "(주)" / "주식회사" is removed.

Default thresholds (chosen on the 2026-10-12 copy, cycle 19 step 9): MIN_EVIDENCE 3 rows,
ROW_SHARE 0.8, MIN_DECIDED 100 rows, LIBRARY_SHARE 0.9. Why: a row needs at least 3 uses
elsewhere so one stray row does not decide it, and 4 of 5 uses one way so names used both ways
(a person who also runs a one-person imprint) stay undecided. A library needs 100 decided rows (the
smallest portal, 14, has 576) and 90 % one way. On that copy the swapped libraries had 99.88-99.96 %
of decided rows swapped and the correct ones 0.00-0.33 %, so the verdict does not depend on the
exact values; a library between 10 % and 90 % is a real mix and must be looked at by a person.

Usage (from the repository root):
    python scripts/library/check_author_publisher_swap.py <copy.sqlite>
        [--min-evidence 3] [--row-share 0.8] [--min-decided 100] [--library-share 0.9]
e.g. python scripts/library/check_author_publisher_swap.py \\
         ../chaekcheck-data/snapshots/ebook-catalog-2026-10-12-0135.sqlite
Output: one tab-separated line per portal library: id, name, host, verdict, rows 뒤바뀜, 바름,
판단 못 함, swapped share of decided rows. Expected on the 2026-10-12 copy: 11, 12, 15 뒤바뀜, the
other portals (10, 13, 14, 280) 바름. Runtime on the 17 GB copy: about 3 minutes (191 s, one full
read of 26 M rows, under 1 GB memory). Progress goes to stderr; redirect stdout to a UTF-8 file on
Windows (the script writes UTF-8), e.g. "> data/c19/swap-check.tsv", and read that file.
Test: python -m unittest scripts/library/test_check_author_publisher_swap.py
"""
import argparse
import re
import sqlite3
import sys
import time
import unicodedata
from collections import Counter
from functools import lru_cache
from pathlib import Path

PORTAL = "ELIB_PORTAL_V1"
SWAPPED, CORRECT, UNDECIDED = "뒤바뀜", "바름", "판단 못 함"

MIN_EVIDENCE = 3
ROW_SHARE = 0.8
MIN_DECIDED = 100
LIBRARY_SHARE = 0.9

ROLE_WORDS = ("지은이", "옮긴이", "엮은이", "글쓴이", "그린이", "저자", "지음", "옮김", "번역", "역자", "엮음",
              "편저", "편역", "편집", "감수", "그림", "기획", "사진", "해설", "글", "저", "역", "편", "공저", "외")
_ROLE = "|".join(sorted(ROLE_WORDS, key=len, reverse=True))
_SPLIT = re.compile(r"[,;/·|]")
_LABEL = re.compile(r"^\s*(저자명|저자|지은이|출판사)\s*:\s*")
_PAREN_ROLE = re.compile(r"[(\[]\s*(?:" + _ROLE + r")\s*[)\]]")
_OTHERS = re.compile(r"\s*외\s*\d+\s*(명|인)?\s*$")
_TRAIL_ROLE = re.compile(r"(?:\s+|(?<=>))(?:" + _ROLE + r")\s*$")
_COMPANY = re.compile(r"\(주\)|㈜|주식회사")
_DROP = re.compile(r"[\s<>＜＞〈〉《》「」\[\]()'\".,:·_-]+")


def _key(text):
    return _DROP.sub("", text).lower()


@lru_cache(maxsize=500_000)
def author_keys(raw):
    """Name keys of an author field, in order (empty names dropped)."""
    text = unicodedata.normalize("NFKC", raw or "")
    keys = []
    for part in _SPLIT.split(text):
        part = _COMPANY.sub("", _LABEL.sub("", part))
        part = _PAREN_ROLE.sub(" ", part)
        part = _OTHERS.sub("", part)
        for _ in range(2):  # "홍길동 저 역" / "<홍길동> 저"
            part = _TRAIL_ROLE.sub("", part.strip())
        key = _key(part)
        if key:
            keys.append(key)
    return tuple(keys)


@lru_cache(maxsize=300_000)
def publisher_key(raw):
    text = unicodedata.normalize("NFKC", raw or "")
    return _key(_COMPANY.sub("", _LABEL.sub("", text)))


def row_verdict(a, p, min_evidence=MIN_EVIDENCE, row_share=ROW_SHARE):
    total = a + p
    if total < min_evidence:
        return UNDECIDED
    if p / total >= row_share:
        return SWAPPED
    if a / total >= row_share:
        return CORRECT
    return UNDECIDED


def library_verdict(swapped, correct, min_decided=MIN_DECIDED, library_share=LIBRARY_SHARE):
    decided = swapped + correct
    if decided < min_decided:
        return UNDECIDED
    if swapped / decided >= library_share:
        return SWAPPED
    if correct / decided >= library_share:
        return CORRECT
    return UNDECIDED


def check(path, min_evidence=MIN_EVIDENCE, row_share=ROW_SHARE, min_decided=MIN_DECIDED,
          library_share=LIBRARY_SHARE, log=None):
    """Returns [(id, name, host, verdict, swapped, correct, undecided)] for every portal library."""
    db = sqlite3.connect(Path(path).resolve().as_uri() + "?mode=ro", uri=True)
    try:
        portals = db.execute("SELECT id, name, host FROM library WHERE platform_type = ? ORDER BY id",
                             (PORTAL,)).fetchall()
        portal_ids = [p[0] for p in portals]
        marks = ",".join("?" * len(portal_ids)) or "NULL"

        # first author key of every portal row; only these keys are counted in step 1
        portal_rows = {lid: [] for lid in portal_ids}
        for lid, raw in db.execute(f"SELECT library_id, author_raw FROM holding "
                                   f"WHERE removed_at IS NULL AND library_id IN ({marks})", portal_ids):
            keys = author_keys(raw)
            portal_rows[lid].append(keys[0] if keys else None)
        wanted = {k for rows in portal_rows.values() for k in rows if k}

        as_author, as_publisher = Counter(), Counter()
        seen = 0
        for raw_author, raw_publisher in db.execute(
                f"SELECT h.author_raw, h.publisher FROM holding h "
                f"WHERE h.removed_at IS NULL AND h.library_id NOT IN ({marks})", portal_ids):
            seen += 1
            for k in set(author_keys(raw_author)):
                if k in wanted:
                    as_author[k] += 1
            k = publisher_key(raw_publisher)
            if k in wanted:
                as_publisher[k] += 1
            if log and seen % 5_000_000 == 0:
                log(f"reference rows read: {seen:,}")
        if log:
            log(f"reference rows read: {seen:,} (portal rows {sum(map(len, portal_rows.values())):,})")
    finally:
        db.close()

    result = []
    for lid, name, host in portals:
        counts = Counter(row_verdict(as_author[k], as_publisher[k], min_evidence, row_share) if k else UNDECIDED
                         for k in portal_rows[lid])
        s, c, u = counts[SWAPPED], counts[CORRECT], counts[UNDECIDED]
        result.append((lid, name, host, library_verdict(s, c, min_decided, library_share), s, c, u))
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="python scripts/library/check_author_publisher_swap.py",
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("copy", help="collected catalog SQLite copy (opened read-only)")
    parser.add_argument("--min-evidence", type=int, default=MIN_EVIDENCE,
                        help="row: fewest uses of the name elsewhere (as author + as publisher) to decide (default 3)")
    parser.add_argument("--row-share", type=float, default=ROW_SHARE,
                        help="row: share of one use needed to call the row 뒤바뀜 or 바름 (default 0.8)")
    parser.add_argument("--min-decided", type=int, default=MIN_DECIDED,
                        help="library: fewest decided rows to give a verdict (default 100)")
    parser.add_argument("--library-share", type=float, default=LIBRARY_SHARE,
                        help="library: share of decided rows needed for 뒤바뀜 or 바름 (default 0.9)")
    args = parser.parse_args(argv)

    started = time.monotonic()
    rows = check(args.copy, args.min_evidence, args.row_share, args.min_decided, args.library_share,
                 log=lambda m: print(m, file=sys.stderr, flush=True))
    print(f"# thresholds: min-evidence {args.min_evidence}, row-share {args.row_share}, "
          f"min-decided {args.min_decided}, library-share {args.library_share}")
    print("id\tname\thost\tverdict\t뒤바뀜\t바름\t판단 못 함\tswapped % of decided")
    for lid, name, host, verdict, s, c, u in rows:
        share = f"{100 * s / (s + c):.2f}" if s + c else "-"
        print(f"{lid}\t{name}\t{host}\t{verdict}\t{s}\t{c}\t{u}\t{share}")
    print(f"# {time.monotonic() - started:.0f} s", file=sys.stderr)


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    main()
