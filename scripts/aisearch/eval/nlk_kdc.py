"""ISBN -> KDC (and the record's keywords) from the national bibliography TTL (data/nlk/ttl/book_ttl_20260807, 20 files),
for the AI search books. Reads each record (blocks end with " ." at line end), keeps those with a bibo:isbn matching a
search book's ISBN-13 (or its ISBN-10 form), writes nlk-kdc.tsv: book_id, isbn, kdc, keywords, audience note, genre, subject heading codes (KSH).
usage: nlk_kdc.py TTL_DIR BOOKS.tsv(book_id<TAB>isbn13) OUT.tsv"""
import os
import re
import sys

ttl_dir, books_path, out_path = sys.argv[1:4]
want = {}
for line in open(books_path, encoding="utf-8"):
    bid, isbn = line.rstrip("\n").split("\t")
    if isbn:
        want.setdefault(isbn, []).append(bid)


def isbn10(isbn13):
    if len(isbn13) != 13 or not isbn13.startswith("978"):
        return None
    core = isbn13[3:12]
    s = sum((10 - i) * int(c) for i, c in enumerate(core))
    check = (11 - s % 11) % 11
    return core + ("X" if check == 10 else str(check))


by10 = {}
for isbn in want:
    ten = isbn10(isbn)
    if ten:
        by10[ten] = isbn

ISBN = re.compile(r'bibo:isbn\s+"([^"]*)"')
KDC = re.compile(r'nlon:kdc\s+"([^"]*)"')
KEY = re.compile(r'nlon:keyword\s+((?:"[^"]*"\s*,?\s*)+)')
AUD = re.compile(r'nlon:audienceNote\s+"([^"]*)"')
GENRE = re.compile(r'nlon:genre\s+([^;.]*)')
SUBJ = re.compile(r'dcterms:subject\s+([^;]*)')
found = {}
for name in sorted(os.listdir(ttl_dir)):
    block = []
    with open(os.path.join(ttl_dir, name), encoding="utf-8", errors="replace") as f:
        for line in f:
            block.append(line)
            if not line.rstrip().endswith(" ."):
                continue
            text = "".join(block)
            block = []
            m = KDC.search(text)
            a = AUD.search(text)
            if not m and not a:
                continue
            for raw in ISBN.findall(text):
                digits = re.sub(r"[^0-9Xx]", "", raw.split("(")[0]).upper()
                isbn = digits if digits in want else by10.get(digits)
                if isbn and isbn not in found:
                    k = KEY.search(text)
                    words = re.findall(r'"([^"]*)"', k.group(1)) if k else []
                    g = GENRE.search(text)
                    sj = SUBJ.search(text)
                    found[isbn] = (m.group(1) if m else "", "|".join(words[:8]), a.group(1) if a else "",
                                   re.sub(r"[\s\"]", "", g.group(1)) if g else "",
                                   "|".join(re.findall(r"KSH\d+", sj.group(1))) if sj else "")
    print(name, len(found), flush=True)
with open(out_path, "w", encoding="utf-8") as out:
    for isbn, (kdc, words, aud, genre, subj) in found.items():
        for bid in want[isbn]:
            out.write(f"{bid}\t{isbn}\t{kdc}\t{words}\t{aud}\t{genre}\t{subj}\n")
print("books found", sum(len(want[i]) for i in found),
      "kdc", sum(len(want[i]) for i, v in found.items() if v[0]),
      "audience", sum(len(want[i]) for i, v in found.items() if v[2]),
      "subject", sum(len(want[i]) for i, v in found.items() if v[4]),
      "of", sum(len(v) for v in want.values()))
