#!/usr/bin/env python3
import argparse
import hashlib
import json
import struct
from pathlib import Path

SHA='b05e37e0fc04701cbb978254c613ef500c1a44337cdefce9372b1c2136a3a4a4'

class Model:
    def __init__(self,data):
        self.data=data
    def read(self,offset,size):
        if offset<0 or size<0 or offset+size>len(self.data):raise ValueError('Out-of-bounds model field')
        return self.data[offset:offset+size]
    def number(self,offset,fmt='I'):
        return struct.unpack('<'+fmt,self.read(offset,struct.calcsize('<'+fmt)))[0]
    def pointer(self,offset):
        target=offset+self.number(offset)
        self.read(target,4)
        return target
    def fields(self,table):
        vtable=table-self.number(table,'i')
        size=self.number(vtable,'H');object_size=self.number(vtable+2,'H')
        if size<4 or size%2:raise ValueError('Invalid vtable')
        self.read(vtable,size);self.read(table,object_size)
        fields={}
        for i in range((size-4)//2):
            offset=self.number(vtable+4+2*i,'H')
            if offset:
                if offset>=object_size:raise ValueError('Invalid object offset')
                fields[i]=table+offset
        return fields
    def vector(self,field):
        p=self.pointer(field);n=self.number(p)
        self.read(p+4,n*4)
        return [p+4+4*i for i in range(n)]
    def ints(self,field):return [self.number(p) for p in self.vector(field)]
    def string(self,field):
        p=self.pointer(field);n=self.number(p)
        if self.read(p+4+n,1)!=b'\0':raise ValueError('Unterminated string')
        return self.read(p+4,n).decode('utf-8')

def inspect(path):
    data=path.read_bytes();digest=hashlib.sha256(data).hexdigest()
    if digest!=SHA:raise ValueError('Unsupported AISC model SHA256')
    m=Model(data);root=m.fields(m.number(0))
    tensors=[]
    for p in m.vector(root[3]):
        f=m.fields(m.pointer(p));tensors.append(m.string(f[0]))
    inputs=m.ints(root[4]);outputs=m.ints(root[5]);available=set();nodes=[];input_shape=None
    for p in m.vector(root[2]):
        f=m.fields(m.pointer(p));name=m.string(f[0]);op=m.number(f[1]) if 1 in f else 0
        ins=m.ints(f[4]) if 4 in f else [];outs=m.ints(f[5]) if 5 in f else []
        if any(i>=len(tensors) for i in ins+outs):raise ValueError('Invalid tensor index')
        if any(i not in available for i in ins):raise ValueError('Graph is not topologically ordered')
        if any(i in available for i in outs):raise ValueError('Duplicate tensor producer')
        item=dict(name=name,type_code=op,input_ids=ins,output_ids=outs)
        if op==0 and outs==inputs:
            params=m.fields(m.pointer(f[3]));shape=m.fields(m.pointer(params[0]));input_shape=m.ints(shape[0])
        if op==18:
            params=m.fields(m.pointer(f[3]));weights=m.fields(m.pointer(params[1]))
            shape=m.fields(m.pointer(weights[1]));dims=m.ints(shape[0])
            payload=m.fields(m.pointer(weights[2]));values=m.vector(payload[9])
            count=1
            for dim in dims:count*=dim
            if count!=len(values):raise ValueError('Weight shape/element count mismatch')
            item.update(weight_shape=dims,weight_elements=count)
        nodes.append(item);available.update(outs)
    if not set(outputs)<=available:raise ValueError('Missing output producer')
    if [tensors[i] for i in outputs]!=['conv2d/116','conv2d/118']:raise ValueError('Unexpected outputs')
    if input_shape!=[1,160,160,3]:raise ValueError('Unexpected input dimensions')
    return dict(sha256=digest,size_bytes=len(data),input_shape=input_shape,
                input_names=[tensors[i] for i in inputs],output_names=[tensors[i] for i in outputs],
                tensors=tensors,nodes=nodes,
                limitation='Structural decoding only; not runtime inference or camera integration')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('model',type=Path);a=p.parse_args()
    print(json.dumps(inspect(a.model),indent=2))
