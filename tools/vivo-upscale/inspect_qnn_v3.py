"""Observed SoftPQE QNN V3 metadata only. Offline; no inference or SDK ABI promise."""
import sys, json
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from inspect_vivo_neural import Metadata

def inspect(path):
    m=Metadata(path.read_bytes())
    w=m.ref_field(m.reference(40),0)
    if m.number(m.field(w,0))!=3: raise ValueError('Expected binary V3')
    b=m.ref_field(w,2)
    graphs=[]
    for w in m.vector(m.ref_field(b,10),True):
        if m.number(m.field(w,0))!=3: raise ValueError('Expected graph V3')
        g=m.ref_field(m.ref_field(m.ref_field(w,2),0),0)
        graphs.append(dict(name=m.string(m.ref_field(g,0)),
            inputs=[m.tensor(t) for t in m.vector(m.ref_field(g,2),True)],
            outputs=[m.tensor(t) for t in m.vector(m.ref_field(g,4),True)]))
    return dict(file=path.name,graphs=graphs)
if __name__=='__main__':
    print(json.dumps([inspect(p) for p in sorted(Path(sys.argv[1]).glob('*.bin'))],indent=2))
