"""Data audit for the ML plan (2026-10-28): label distributions of the Haiku profiles, KDC/NLK coverage and agreement,
same-work leakage, and a no-training baseline (majority of the 20 neighbours) per field."""
import json, sys
from collections import Counter, defaultdict
from pathlib import Path
import os
_REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
_PROJECTS = os.path.dirname(_REPO)  # the folder that holds this repo and chaekcheck-data
DATA = Path(os.path.join(_PROJECTS, r'chaekcheck-data'))
sys.path.insert(0, os.path.join(_REPO, r'scripts/crawl'))
from foryou_neighbours import work_key, first_author
out = {}
prof = {}
for l in open(DATA / 'aisearch/100k/profiles-detailed.jsonl', encoding='utf-8'):
    d = json.loads(l); prof[int(d['id'])] = d
out['profiles'] = len(prof)
def dist(key, multi=False):
    c = Counter()
    for d in prof.values():
        v = d.get(key)
        if multi:
            if not v: c['(없음)'] += 1
            for x in v or []: c[x] += 1
        else:
            c[v if v not in ('', None) else '(빈칸)'] += 1
    return c
for k in ('field', 'audience', 'level', 'country'):
    c = dist(k); out[k] = c.most_common()
for k in ('genre', 'form', 'mood'):
    out[k] = dist(k, True).most_common()
sub = Counter((d['field'], d.get('subfield') or '(빈칸)') for d in prof.values())
out['subfield_classes'] = len(sub); out['subfield_smallest'] = sorted(sub.items(), key=lambda kv: kv[1])[:12]
out['subfield_under_50'] = sum(1 for v in sub.values() if v < 50)
out['fiction_true'] = sum(1 for d in prof.values() if d.get('fiction'))
out['topics_per_book'] = round(sum(len(d.get('topics') or []) for d in prof.values()) / len(prof), 2)
# KDC / NLK
kdc = {}; nlk_aud = {}; ksh = {}
for l in open(DATA / 'aisearch/100k/nlk-kdc.tsv', encoding='utf-8'):
    p = l.rstrip('\n').split('\t') + [''] * 8
    b = int(p[0])
    if p[2].strip(): kdc[b] = p[2].strip()
    if p[4].strip(): nlk_aud[b] = p[4].strip()
    if p[6].strip(): ksh[b] = p[6].strip()
out['kdc_books'] = len(kdc); out['kdc_and_profile'] = len(set(kdc) & set(prof))
out['nlk_audience_values'] = Counter(nlk_aud.values()).most_common(10)
cross = defaultdict(Counter)
for b, k in kdc.items():
    if b in prof and k[:1].isdigit(): cross[k[0]][prof[b]['field']] += 1
out['kdc_main_class_to_field_top'] = {k: v.most_common(4) for k, v in sorted(cross.items())}
out['kdc_main_class_purity'] = round(sum(v.most_common(1)[0][1] for v in cross.values()) / sum(sum(v.values()) for v in cross.values()), 3)
# same-work leakage
books = {}
for l in open(os.path.join(_REPO, 'data', 'scratch', r'eval100k/books.tsv'), encoding='utf-8'):
    p = l.rstrip('\n').split('\t'); books[int(p[0])] = (p[1], p[2], int(p[3]))
groups = defaultdict(list)
for b in prof:
    if b in books: groups[(work_key(books[b][0]), first_author(books[b][1]))].append(b)
multi = [g for g in groups.values() if len(g) > 1]
out['same_work_groups_with_2plus'] = len(multi); out['books_in_such_groups'] = sum(len(g) for g in multi)
agree = sum(1 for g in multi if len({prof[b]['field'] for b in g}) == 1)
out['same_work_groups_same_field'] = round(agree / len(multi), 3)
# baseline: majority field of the 20 neighbours (no training)
neigh = {}
for l in open(DATA / 'ml/foryou-100k/neighbours.tsv', encoding='utf-8'):
    if l.startswith('#'): continue
    a, b = l.rstrip('\n').split('\t'); neigh[int(a)] = [int(x) for x in b.split(',')]
def knn(key, k=20):
    ok = tot = 0; conf = []
    for b, d in prof.items():
        ns = [n for n in neigh.get(b, [])[:k] if n in prof]
        if not ns: continue
        c = Counter(prof[n][key] for n in ns); top, cnt = c.most_common(1)[0]
        tot += 1; ok += top == d[key]; conf.append((cnt / len(ns), top == d[key]))
    conf.sort(key=lambda x: -x[0]); half = conf[:len(conf) // 2]
    return {'n': tot, 'acc': round(ok / tot, 3), 'acc_top_half_confidence': round(sum(x[1] for x in half) / len(half), 3)}
for key in ('field', 'audience', 'level', 'fiction'):
    out['knn_baseline_' + key] = knn(key)
out['majority_class_share'] = {k: round(Counter(d[k] for d in prof.values()).most_common(1)[0][1] / len(prof), 3) for k in ('field', 'audience', 'level', 'fiction')}
json.dump(out, open(Path(__file__).with_name('audit.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print(json.dumps({k: v for k, v in out.items() if not isinstance(v, list) or len(v) < 25}, ensure_ascii=False, indent=1)[:6000])
