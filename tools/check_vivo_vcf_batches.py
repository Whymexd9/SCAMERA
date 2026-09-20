#!/usr/bin/env python3
"""Verify batch field semantics and count functions against pinned VCF ARM64.

The processRequest load/store blocks are executed, not the full request builder.
Both DecisionRule count functions execute in full without import stubs.
"""
import argparse
import ctypes
import hashlib
import random
import struct
import subprocess
import tempfile
from pathlib import Path
from check_vivo_nice_motion import emulator, invoke
from unicorn.arm64_const import *

SHA='c3449dbb9867118173abc649de1fbab7edebebd826603cabe51fb9b58c0f07c5'
DRIVER=r'''
#include "vivo-vcf-batches.h"
extern "C" int slices(const void* p, size_t n, uint32_t* out) {
    vivo_vcf::CaptureControlFields c;
    if(vivo_vcf::readCaptureControlFields(p,n,c)!=vivo_vcf::CaptureControlRead::Present)return -1;
    try {
        auto b=vivo_vcf::captureBatchSlices(c);
        for(size_t i=0;i<b.size();++i){out[4*i]=b[i].firstFrame;out[4*i+1]=b[i].frameCount;
            out[4*i+2]=b[i].algoType;out[4*i+3]=b[i].direction;}
        return int(b.size());
    } catch(const std::invalid_argument&) {return -2;}
}
extern "C" void counts(const uint32_t* sizes, size_t n, const uint32_t* directions, uint32_t* out) {
    std::vector<std::vector<vivo_vcf::CaptureFrameControl>> batches(n);
    size_t offset=0;
    for(size_t i=0;i<n;++i){batches[i].resize(sizes[i]);for(auto& f:batches[i])f.direction=directions[offset++];}
    auto result=vivo_vcf::countBatchedFrames(batches);out[0]=result.all;out[1]=result.future;
}
'''

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('library',type=Path);args=ap.parse_args()
    assert hashlib.sha256(args.library.read_bytes()).hexdigest()==SHA
    u,_=emulator(args.library);rng=random.Random(20260921)
    with tempfile.TemporaryDirectory(prefix='vcf-batches-') as tmp:
        tmp=Path(tmp);(tmp/'test.cpp').write_text(DRIVER)
        include=Path(__file__).resolve().parents[1]/'app/src/main/cpp'
        subprocess.run(['c++','-std=c++17','-O2','-shared','-fPIC','-Wall','-Wextra','-Werror',
            '-I',str(include),str(tmp/'test.cpp'),'-o',str(tmp/'test.so')],check=True)
        lib=ctypes.CDLL(str(tmp/'test.so'))
        lib.slices.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_void_p];lib.slices.restype=ctypes.c_int
        lib.counts.argtypes=[ctypes.c_void_p,ctypes.c_size_t,ctypes.c_void_p,ctypes.c_void_p]
        checked=0
        for case in range(100):
            nf=rng.randrange(1,33);nb=rng.randrange(1,min(nf,16)+1)
            cuts=[0,*sorted(rng.sample(range(1,nf),nb-1)),nf]
            source=bytearray(0xb30);struct.pack_into('<II',source,0,nf,nb)
            for i in range(nb):
                direction=i%2;count=cuts[i+1]-cuts[i]
                struct.pack_into('<I',source,0x14+4*i,1000+i)  # Deliberately distinct from counts.
                struct.pack_into('<I',source,0x54+4*i,count)
                for j in range(cuts[i],cuts[i+1]):struct.pack_into('<IfffI',source,0x94+20*j,32,0.,1.,10.,direction)
            data=ctypes.create_string_buffer(bytes(source));out=(ctypes.c_uint32*64)()
            assert lib.slices(data,len(source),out)==nb
            wire,native,algo,storage,sp=0x1070000,0x1080000,0x1090000,0x10a0000,0x1ffe000
            u.mem_write(wire,bytes(source));u.mem_write(native,bytes(source[:0x94]))
            u.mem_write(native+0x98,struct.pack('<Q',wire+0x94))
            u.mem_write(algo+0x20,struct.pack('<QQ',storage,storage+64))
            u.reg_write(UC_ARM64_REG_SP,sp)
            u.mem_write(sp+0x7a0,struct.pack('<Q',native));u.mem_write(sp+0x960,struct.pack('<Q',algo))
            for i in range(nb):
                u.reg_write(UC_ARM64_REG_X20,i);u.reg_write(UC_ARM64_REG_W23,out[4*i])
                u.reg_write(UC_ARM64_REG_W17,20)
                u.emu_start(0x61038,0x6107c,count=40)
                actual_count=struct.unpack('<I',u.mem_read(sp+0xc0,4))[0]
                actual_direction=struct.unpack('<I',u.mem_read(sp+0xb8,4))[0]
                u.emu_start(0x61080,0x61088,count=4)
                actual_algo=struct.unpack('<I',u.mem_read(storage,4))[0]
                assert [actual_count,actual_algo,actual_direction]==list(out[4*i+1:4*i+4])
                checked+=1
        for case in range(300):
            sizes=[rng.randrange(0,33) for _ in range(rng.randrange(0,17))]
            directions=[rng.choice([0,1,1,2,0xffffffff]) for _ in range(sum(sizes))]
            ns=(ctypes.c_uint32*len(sizes))(*sizes);ds=(ctypes.c_uint32*len(directions))(*directions)
            out=(ctypes.c_uint32*2)();lib.counts(ns,len(sizes),ds,out)
            this,info,outer,pool=0x1100000,0x1101000,0x1102000,0x1110000
            u.mem_write(this+0xd8,struct.pack('<Q',info))
            u.mem_write(info+0x30,struct.pack('<QQQ',outer,outer+24*len(sizes),outer+24*len(sizes)))
            offset=0
            for i,size in enumerate(sizes):
                ptr=pool+i*0x1000
                u.mem_write(outer+i*24,struct.pack('<QQQ',ptr,ptr+20*size,ptr+20*size))
                if size:u.mem_write(ptr,b''.join(struct.pack('<IfffI',32,0.,1.,10.,d) for d in directions[offset:offset+size]))
                offset+=size
            assert invoke(u,0x1409b0,(this,))==out[0]
            assert invoke(u,0x140a10,(this,))==out[1]
        def rejected(blob):
            data=ctypes.create_string_buffer(bytes(blob));out=(ctypes.c_uint32*64)()
            assert lib.slices(data,len(blob),out)==-2
        base=bytearray(0xb30);struct.pack_into('<II',base,0,2,1);struct.pack_into('<I',base,0x54,2)
        for count in (0,1,3,0xffffffff):
            bad=bytearray(base);struct.pack_into('<I',bad,0x54,count);rejected(bad)
        bad=bytearray(base);struct.pack_into('<I',bad,0xb8,1);rejected(bad) # second frame direction
        bad=bytearray(base);struct.pack_into('<I',bad,0xa4,2);rejected(bad)
        print(f'PASS {checked} original batch-consumer blocks; 600 complete ARM64 count calls; 6 invalid schedules')
        print('Capture submission, policy and exposure units are not tested.')

if __name__=='__main__':main()
