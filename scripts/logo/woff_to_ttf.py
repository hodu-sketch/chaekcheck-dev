"""WOFF 1.0 -> TrueType (standard library only), so the JDK can read the site's own font (NanumBarunGothic, OFL-1.1,
from web/node_modules/@kfonts/nanum-barun-gothic) when drawing the logo (BuildLetterLogo.java).

Usage: python scripts/logo/woff_to_ttf.py <in.woff> <out.ttf>
"""
import struct
import sys
import zlib


def convert(src, dst):
    data = open(src, 'rb').read()
    signature, flavor, _length, num_tables = struct.unpack('>4sIIH', data[:14])
    if signature != b'wOFF':
        raise SystemExit('not a WOFF 1.0 file')
    tables = []
    for i in range(num_tables):
        tag, offset, comp_length, orig_length, checksum = struct.unpack('>4sIIII', data[44 + i * 20:64 + i * 20])
        raw = data[offset:offset + comp_length]
        tables.append((tag, checksum, raw if comp_length == orig_length else zlib.decompress(raw)))
    tables.sort(key=lambda t: t[0])
    search = 1
    selector = 0
    while search * 2 <= num_tables:
        search *= 2
        selector += 1
    out = bytearray(struct.pack('>IHHHH', flavor, num_tables, search * 16, selector, num_tables * 16 - search * 16))
    offset = 12 + num_tables * 16
    body = bytearray()
    for tag, checksum, table in tables:
        out += struct.pack('>4sIII', tag, checksum, offset + len(body), len(table))
        body += table + b'\0' * ((4 - len(table) % 4) % 4)
    open(dst, 'wb').write(bytes(out + body))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    convert(*sys.argv[1:3])
