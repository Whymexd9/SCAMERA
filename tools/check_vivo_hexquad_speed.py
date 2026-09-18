#!/usr/bin/env python3
"""Check packed tensors AND Bayer output against pre-optimization v14 hashes."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

root=Path(__file__).resolve().parent
golden=json.loads((root/'vivo_hexquad_v14_hashes.json').read_text())
assert golden['commit']=='00769cd858405905634b4946e89da5fbb4a736cd'
with tempfile.TemporaryDirectory(prefix='hex-speed-') as temp:
    subprocess.run([sys.argv[1],temp],check=True)
    actual={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in Path(temp).iterdir()}
    assert actual==golden['sha256'],[(k,actual.get(k),v) for k,v in golden['sha256'].items() if actual.get(k)!=v]
print('Packed tensors and Bayer16 are byte-identical to v14 in all 24 capture fixtures')
