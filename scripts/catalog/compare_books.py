"""Compares two folders written by measure_books.py (plan handoff step 15, AC-6 / AC-15).

Usage (from the repository root):
  python scripts/catalog/compare_books.py <before dir> <after dir>

Book ids change on every import, so a book is compared by (title, author, publisher). For each saved request it
prints whether the list and its order are the same, and what differs: total count, books only before or only after,
and books in both but in another order. The stream targets are compared by library id and order.
"""
import json
import os
import sys


def key(item):
    return (item['title'], item.get('authorDisplay'), item.get('publisher'))


def load(path):
    return json.load(open(path, encoding='utf-8'))


def main(before_dir, after_dir):
    sys.stdout.reconfigure(encoding='utf-8')
    same = differ = 0
    for name in sorted(os.listdir(before_dir)):
        if not name.endswith('.json'):
            continue
        after_path = os.path.join(after_dir, name)
        if not os.path.exists(after_path):
            print(f'{name}: missing after')
            continue
        before, after = load(os.path.join(before_dir, name)), load(after_path)
        if name.startswith('targets-'):
            ids_before = [lib['id'] for lib in before['libraries']]
            ids_after = [lib['id'] for lib in after['libraries']]
            ok = ids_before == ids_after
            print(f'{name}: {"same" if ok else "DIFFERENT"} ({len(ids_before)} -> {len(ids_after)} libraries)')
            same, differ = same + ok, differ + (not ok)
            continue
        keys_before = [key(i) for i in before['items']]
        keys_after = [key(i) for i in after['items']]
        if keys_before == keys_after and before['totalCount'] == after['totalCount']:
            same += 1
            continue
        differ += 1
        only_before = [k for k in keys_before if k not in keys_after]
        only_after = [k for k in keys_after if k not in keys_before]
        common_before = [k for k in keys_before if k in keys_after]
        common_after = [k for k in keys_after if k in keys_before]
        print(f'{name}: DIFFERENT total {before["totalCount"]} -> {after["totalCount"]}, '
              f'only before {len(only_before)}, only after {len(only_after)}, '
              f'order of common {"same" if common_before == common_after else "changed"}')
        for k in only_before[:3]:
            print(f'    - {k}')
        for k in only_after[:3]:
            print(f'    + {k}')
    print(f'same {same}, different {differ}')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(*sys.argv[1:3])
