"""The relation network for production: rel/rel-mlp.pt (rel_train.py, the largest size, seed 0) as follow-up-model.bin
in the first call model format (dim 4,096 = this question, the earlier one, the two joined, this x earlier; one single
head "relation"). Checks the file against torch on the 700 test pairs. usage: export_follow_up.py OUT_DIR"""
import json, os, struct, sys
import numpy as np, torch, torch.nn as nn
HERE = os.path.dirname(os.path.abspath(__file__)); RD = os.path.join(HERE, '..', 'rel')
ck = torch.load(os.path.join(RD, 'rel-mlp.pt')); REL = ck['rels']
m = nn.Sequential(nn.Linear(4096, 512), nn.ReLU(), nn.Dropout(0.3), nn.Linear(512, len(REL))); m.load_state_dict(ck['state_dict']); m.eval()
W1, b1, W2, b2 = (m[0].weight.detach().numpy(), m[0].bias.detach().numpy(), m[3].weight.detach().numpy(), m[3].bias.detach().numpy())
header = json.dumps({'dim': 4096, 'hidden': 512, 'heads': [{'name': 'relation', 'kind': 'single', 'labels': REL, 'outputs': len(REL)}]}).encode()
out = os.path.join(sys.argv[1], 'follow-up-model.bin')
with open(out, 'wb') as f:
    f.write(b'CCFC1\n'); f.write(struct.pack('<i', len(header))); f.write(header)
    for a in (W1, b1, W2, b2): f.write(np.ascontiguousarray(a, dtype='<f4').tobytes())
print(out, os.path.getsize(out) // 1024, 'KB')
