"""Cycle 26 step 1 (ADR-0163): the librarian list pictures kept in picks.cover as files named like the other list
pictures, sha1(source_url).jpg (UTF-8, lower-case hex: CuratedLists.sha1), so that S3 holds every list picture under
covers/ and the server can name it with CoverUrls.of(sha1(cover_source)).

Reads a CSV made on the production database (read-only), one row per cover:

    COPY (SELECT source_url, content_type, encode(image, 'base64') FROM picks.cover ORDER BY id)
      TO STDOUT WITH (FORMAT csv)

and writes the pictures into the output folder, plus content-types.tsv (file name, content type) for the upload.
Usage: python -I scripts/covers/extract_picks_covers.py <picks-covers.csv> <output folder>
"""
import base64
import csv
import hashlib
import sys
from pathlib import Path


def main(csv_path: str, out_dir: str) -> None:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    csv.field_size_limit(1 << 30)
    types = []
    with open(csv_path, newline="", encoding="utf-8") as f:
        for source_url, content_type, image in csv.reader(f):
            name = hashlib.sha1(source_url.encode("utf-8")).hexdigest() + ".jpg"
            (out / name).write_bytes(base64.b64decode(image))
            types.append((name, content_type))
    with open(out / "content-types.tsv", "w", encoding="utf-8", newline="\n") as f:
        for name, content_type in types:
            f.write(f"{name}\t{content_type}\n")
    print(f"{len(types)} pictures written to {out}")
    for content_type in sorted({t for _, t in types}):
        print(f"  {content_type}: {sum(1 for _, t in types if t == content_type)}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
