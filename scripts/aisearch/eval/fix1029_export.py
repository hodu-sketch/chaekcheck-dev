"""Route fix 2026-10-29: writes a new model folder. first-call-model.bin = the source folder's file with only the route
head (weights and tau) replaced by ROUTE_PT (fix1029_route.py); the 11 shelf heads are copied byte for byte.
follow-up-model.bin = REL_PT (fix1029_rel.py) as export_follow_up.py writes it, or the source file when REL_PT is "-".
Every other file of the source folder is copied unchanged. Same CCFC1 format, same head names, labels and order.
usage: fix1029_export.py SRC_DIR OUT_DIR ROUTE_PT REL_PT   (env NOCOPY=1: only the two model files, for trying;
TAU = the route head's tau instead of the one picked on dev)"""
import json, os, shutil, struct, sys
import numpy as np, torch
SRC, OUT, ROUTE_PT, REL_PT = sys.argv[1:5]
assert os.path.abspath(SRC) != os.path.abspath(OUT)
os.makedirs(OUT, exist_ok=True)
for f in ([] if os.environ.get('NOCOPY') else os.listdir(SRC)):
    if f not in ('first-call-model.bin', 'follow-up-model.bin', 'README.md') and os.path.isfile(os.path.join(SRC, f)):
        shutil.copy2(os.path.join(SRC, f), os.path.join(OUT, f))

def weights(sd):
    return [sd['0.weight'].numpy(), sd['0.bias'].numpy(), sd['3.weight'].numpy(), sd['3.bias'].numpy()]
with open(os.path.join(SRC, 'first-call-model.bin'), 'rb') as f:
    magic = f.read(6); assert magic == b'CCFC1\n'
    n = struct.unpack('<i', f.read(4))[0]; header = json.loads(f.read(n).decode('utf-8')); body = f.read()
dim, hid = header['dim'], header['hidden']
ck = torch.load(ROUTE_PT)
first = header['heads'][0]
assert first['name'] == 'route' and first['labels'] == ck['routes'] and first['kind'] == 'route'
size = (hid * dim + hid + first['outputs'] * hid + first['outputs']) * 4
first['tau'] = float(os.environ['TAU']) if os.environ.get('TAU') else float(ck['tau'])
blob = b''.join(np.ascontiguousarray(a, dtype='<f4').tobytes() for a in weights(ck['state_dict']))
assert len(blob) == size
hb = json.dumps(header, ensure_ascii=False).encode('utf-8')
with open(os.path.join(OUT, 'first-call-model.bin'), 'wb') as f:
    f.write(magic); f.write(struct.pack('<i', len(hb))); f.write(hb); f.write(blob); f.write(body[size:])
print('first-call-model.bin', os.path.getsize(os.path.join(OUT, 'first-call-model.bin')), 'route tau', first['tau'])
if REL_PT == '-':
    shutil.copy2(os.path.join(SRC, 'follow-up-model.bin'), os.path.join(OUT, 'follow-up-model.bin'))
else:
    ck = torch.load(REL_PT); REL = ck['rels']
    h = json.dumps({'dim': 4096, 'hidden': 512, 'heads': [{'name': 'relation', 'kind': 'single', 'labels': REL, 'outputs': len(REL)}]}).encode()
    with open(os.path.join(OUT, 'follow-up-model.bin'), 'wb') as f:
        f.write(b'CCFC1\n'); f.write(struct.pack('<i', len(h))); f.write(h)
        for a in weights(ck['state_dict']): f.write(np.ascontiguousarray(a, dtype='<f4').tobytes())
print('follow-up-model.bin', os.path.getsize(os.path.join(OUT, 'follow-up-model.bin')))
