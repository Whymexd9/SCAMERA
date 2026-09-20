#!/usr/bin/env python3
"""Inspect supplied NICE VDNN/QNN metadata; never run or guess preprocessing.

Usage: inspect_vivo_nice.py MODELS_DIRECTORY --output inventory.json
Optional --extract-forward writes only the verified IMX06C forward context.
Keep model inputs and extracted weights outside git.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
from inspect_vivo_hexquad import FlatBuffer
from inspect_vivo_neural import Metadata

FORWARD_SHA='7c4663c3c03394841b92cbcc207ad6bd610369c004d45d913aa950cc61511a16'
CONTEXT_SHA='a551304d938af0cab76091557414f46a64cae05aaf68ef8030c1bcc42decac8c'

def inspect(path, allow_multiple_inputs=False):
    data=path.read_bytes()
    if not 1024<=len(data)<=64*1024*1024:raise ValueError('VDNN size outside inspected range')
    r=FlatBuffer(data);network=r.pointer(r.field(r.pointer(0),7))
    def tensor(base):
        return dict(name=r.string(r.one(r.field(network,base))),
                    shape=r.shape(r.field(network,base+1)),
                    vdnn_type_code=r.number('I',r.one(r.field(network,base+2))))
    start,length=r.vector(r.field(network,10),1);context=data[start:start+length]
    m=Metadata(context);w=m.ref_field(m.reference(40),0)
    if m.number(m.field(w,0))!=3:raise ValueError('Unverified QNN binary schema')
    b=m.ref_field(w,2);graphs=[]
    for w in m.vector(m.ref_field(b,10),True):
        if m.number(m.field(w,0))!=3:raise ValueError('Unverified QNN graph schema')
        g=m.ref_field(m.ref_field(m.ref_field(w,2),0),0)
        graphs.append(dict(name=m.string(m.ref_field(g,0)),
            inputs=[m.tensor(t) for t in m.vector(m.ref_field(g,2),True)],
            outputs=[m.tensor(t) for t in m.vector(m.ref_field(g,4),True)]))
    if len(graphs)!=1 or (not allow_multiple_inputs and len(graphs[0]['inputs'])!=1) or len(graphs[0]['outputs'])!=1:
        raise ValueError('Unexpected graph/tensor count')
    result=dict(source_sha256=hashlib.sha256(data).hexdigest(),wrapper_input=tensor(0) if len(graphs[0]["inputs"])==1 else None,wrapper_output=tensor(5),
                context=dict(offset=start,bytes=length,sha256=hashlib.sha256(context).hexdigest()),
                qnn_builds=sorted(set(x.decode() for x in re.findall(rb'v2\.[0-9.]+_[0-9]+',context))),
                graphs=graphs,preprocessing_verified=False,device_execution_verified=False)
    result['wrapper_output_shape_matches_graph']=result['wrapper_output']['shape']==graphs[0]['outputs'][0]['shape']
    return result,context

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('models',type=Path);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--extract-forward',type=Path)
    args=parser.parse_args();rows=[];chosen=None
    for path in sorted(args.models.rglob('*.vdnn')):
        row,context=inspect(path);row['file']=path.relative_to(args.models).as_posix();rows.append(row)
        if row['source_sha256']==FORWARD_SHA:
            if row['context']['sha256']!=CONTEXT_SHA:raise ValueError('Pinned context mismatch')
            chosen=context
    if not rows:raise ValueError('No NICE models found')
    if args.extract_forward:
        if chosen is None:raise ValueError('Verified forward model missing')
        with args.extract_forward.open('xb') as f:f.write(chosen)
    args.output.write_text(json.dumps(dict(models=rows,scope='Static metadata, not a verified photo pipeline'),indent=2)+'\n')
    print('NICE metadata:',len(rows),'models;',sum(not r['wrapper_output_shape_matches_graph'] for r in rows),'wrapper/graph output mismatches')

if __name__=='__main__':main()
