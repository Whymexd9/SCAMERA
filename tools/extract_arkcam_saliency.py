#!/usr/bin/env python3
"""Extract the embedded float32 RGB saliency model from the reviewed ArkCam 4.3 .so.
No executable code from the donor is loaded. Requires pyelftools for inspection.
"""
import argparse, hashlib, struct
from pathlib import Path
from elftools.elf.elffile import ELFFile
p = argparse.ArgumentParser()
p.add_argument('library', type=Path)
p.add_argument('output', type=Path)
a = p.parse_args()
b = a.library.read_bytes()
start = 0x2f5a20
assert b[start+4:start+8] == b'TFL3', 'Unexpected donor; inspect model offsets again'
u = lambda at: struct.unpack_from('<I', b, at)[0]
def field(t, n):
    v = t - struct.unpack_from('<i', b, t)[0]
    at = v + 4 + 2*n
    o = struct.unpack_from('<H', b, at)[0] if at < v + struct.unpack_from('<H', b, v)[0] else 0
    return t+o if o else 0
root = start+u(start)
f = field(root, 4); vector = f+u(f)
end = start
for i in range(u(vector)):
    p = vector+4+4*i; table = p+u(p); f = field(table, 0)
    if f:
        d = f+u(f); end = max(end,d+4+u(d))
# Subgraphs/tensor tables follow the weights. The complete 957744-byte
# boundary was verified with LiteRT, including metadata and all tensor names.
end = max(end, start + 957744)
model = b[start:end]
assert 100_000 < len(model) < 2_000_000
assert hashlib.sha256(model).hexdigest() == 'a0bf6e5c324ad3a24b345970beb861bbe53f0f99223ec61900183ff639bc864a', 'Unexpected model bytes'
a.output.parent.mkdir(parents=True, exist_ok=True)
a.output.write_bytes(model)
print(len(model), hashlib.sha256(model).hexdigest())
