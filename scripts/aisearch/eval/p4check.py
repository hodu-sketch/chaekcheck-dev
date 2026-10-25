"""Check profile4 outputs: ids match input order, values in taxonomy2. Prints bad/missing batches."""
import glob, json, os, sys
T = json.load(open('taxonomy2.json', encoding='utf-8'))
bad, ok, miss, books, offdict = [], 0, [], 0, 0
for inp in sorted(glob.glob('profile4/in-*.tsv')):
    n = inp[-7:-4]; out = f'profile4/out-{n}.jsonl'
    ids = [l.split('\t')[0] for l in open(inp, encoding='utf-8') if l.strip()]
    if not os.path.exists(out): miss.append(n); continue
    got = []
    for l in open(out, encoding='utf-8'):
        if not l.strip(): continue
        try: o = json.loads(l)
        except ValueError: continue
        got.append(str(o.get('id')))
        if o.get('field') not in T['field'] or (o.get('subfield') and o['subfield'] not in T['field'][o['field']]) \
           or any(g not in T['genre'] for g in o.get('genre') or []) or (o.get('country') and o['country'] not in T['country']) \
           or o.get('audience') not in T['audience'] or o.get('level') not in T['level']:
            offdict += 1
    if got == ids: ok += 1; books += len(ids)
    else: bad.append(f'{n}({len(set(got)&set(ids))}/{len(ids)})')
print(f'ok {ok} books {books} off-dict values {offdict}; bad {bad}; missing {len(miss)}')
