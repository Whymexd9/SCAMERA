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
import struct
from inspect_vivo_neural import Metadata


class FlatBuffer:
    """Bounds-checked reads of the observed VDNN FlatBuffer fields (was in the HexQuad inspector, removed in P175)."""
    def __init__(self, data):
        self.data = data

    def check(self, offset, length):
        if offset < 0 or length < 0 or offset > len(self.data) - length:
            raise ValueError('FlatBuffer range outside file')

    def number(self, fmt, offset):
        self.check(offset, struct.calcsize('<' + fmt))
        return struct.unpack_from('<' + fmt, self.data, offset)[0]

    def pointer(self, offset):
        distance = self.number('I', offset)
        if distance < 4:
            raise ValueError('Invalid FlatBuffer relative pointer')
        result = offset + distance
        self.check(result, 4)
        return result

    def field(self, table, index):
        vtable = table - self.number('i', table)
        length = self.number('H', vtable)
        object_size = self.number('H', vtable + 2)
        if length < 4 or length % 2 or object_size < 4:
            raise ValueError('Invalid FlatBuffer table')
        self.check(vtable, length)
        self.check(table, object_size)
        if index < 0 or 4 + index * 2 >= length:
            raise ValueError('Missing required VDNN field')
        offset = self.number('H', vtable + 4 + index * 2)
        if offset < 4 or offset + 4 > object_size:
            raise ValueError('Invalid required VDNN field')
        return table + offset

    def vector(self, field, item_size=4):
        offset = self.pointer(field)
        count = self.number('I', offset)
        start = offset + 4
        self.check(start, count * item_size)
        return start, count

    def string(self, field):
        start, count = self.vector(field, 1)
        self.check(start + count, 1)
        if self.data[start + count] != 0:
            raise ValueError('Unterminated VDNN string')
        return self.data[start:start + count].decode('utf-8')

    def one(self, field):
        offset, count = self.vector(field)
        if count != 1:
            raise ValueError('Expected exactly one tensor')
        return offset

    def shape(self, field):
        table = self.pointer(self.one(field))
        start, count = self.vector(self.field(table, 0))
        if count != 4:
            raise ValueError('Expected rank-4 VDNN shape')
        return list(struct.unpack_from('<4I', self.data, start))


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
