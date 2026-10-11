"""p50 and p95 of the first-page times of several measure_books.py runs (cycle 08 handoff steps 0, 8, 9).

Usage: python scripts/catalog/summarize_times.py <run dir> <run dir> ...
Prints one line per query and condition (p50 / p95 over the runs) and one line over every first page of every run.
p95 uses the nearest-rank method, so with three runs it is the slowest run.
"""
import collections
import math
import sys


def percentile(values, p):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(p / 100 * len(ordered)) - 1)]


def main(dirs):
    times = collections.defaultdict(list)
    for d in dirs:
        for line in open(f'{d}/times.tsv', encoding='utf-8').read().splitlines()[1:]:
            query, condition, page, ms, _ = line.split('\t')
            if page == '1':
                times[(query, condition)].append(int(ms))
    print('query\tcondition\tp50\tp95')
    for (query, condition), values in times.items():
        print(f'{query}\t{condition}\t{percentile(values, 50)}\t{percentile(values, 95)}')
    every = [v for values in times.values() for v in values]
    print(f'ALL\tfirst pages ({len(every)})\t{percentile(every, 50)}\t{percentile(every, 95)}')


if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    main(sys.argv[1:])
