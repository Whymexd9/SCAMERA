#!/usr/bin/env python3
"""Compare the isolated VCF2 candidate predicate with its original ARM64 code.

Requires pyelftools, Unicorn and a C++17 compiler. ConfigProvider's camera-name
lookup is stubbed to an empty short string; the predicate never reads that name.
No predicate instructions are replaced. This does not test complete ZSL policy.
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
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import *

SHA = '93a3149a70fea5f177bb0542278c47ec232fc477aa96cb7a63534b1f6b5f8dad'
DRIVER = '''#include "vivo-vcf-frame-compatibility.h"
extern "C" int check(unsigned long long e, unsigned i, float se, float sg, int settled,
 unsigned long long re, unsigned long long ri, float rse, float rsg, float et, float gt,
 int mode, int type, unsigned scene, int aux) {
 return vivo_vcf::frameCompatible({e,i,se,sg,settled!=0}, {re,ri,rse,rsg,et,gt,mode,type,scene,aux});
}
'''

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('library',type=Path)
    args=ap.parse_args()
    assert hashlib.sha256(args.library.read_bytes()).hexdigest()==SHA, 'Unsupported library'
    u,_=emulator(args.library)
    def config_hook(u, pc, size, data):
        if pc==0x2f22e8:
            u.reg_write(UC_ARM64_REG_X0,0x1060000)
        else:
            u.mem_write(u.reg_read(UC_ARM64_REG_X8),bytes(24))
        u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))
    u.hook_add(UC_HOOK_CODE,config_hook,begin=0x2f22e8,end=0x2f22e8)
    u.hook_add(UC_HOOK_CODE,config_hook,begin=0x700000,end=0x700000)
    u.mem_write(0x1060000,struct.pack('<Q',0x1061000))
    u.mem_write(0x1061068,struct.pack('<Q',0x700000))
    u.mem_write(0x1070000,struct.pack('<Q',0x1071000))
    u.mem_write(0x1071000,struct.pack('<QQQ',0x1072000,0x1072028,0x1072028))
    with tempfile.TemporaryDirectory(prefix='vcf-predicate-') as d:
        d=Path(d);(d/'check.cpp').write_text(DRIVER)
        repo=Path(__file__).resolve().parents[1]
        subprocess.run(['c++','-std=c++17','-O2','-shared','-fPIC','-I',str(repo/'app/src/main/cpp'),str(d/'check.cpp'),'-o',str(d/'check.so')],check=True)
        check=ctypes.CDLL(str(d/'check.so')).check
        check.argtypes=[ctypes.c_uint64,ctypes.c_uint32,ctypes.c_float,ctypes.c_float,ctypes.c_int,
            ctypes.c_uint64,ctypes.c_uint64,*([ctypes.c_float]*4),ctypes.c_int,ctypes.c_int,ctypes.c_uint32,ctypes.c_int]
        check.restype=ctypes.c_int
        rng=random.Random(20260920);cases=[]
        for mode in [0,3,4,5,6,7]:
            for typ in [0,0xf00]:
                for scene,aux in [(0,0),(0xc00000,6),(0xc00000,0x502),(0xc00000,7)]:
                    for settled in [0,1]:
                        for ratio in [0,0.79,0.8,0.800001,1,1.199999,1.2,1.200001,1.21,2]:
                            cases.append((int(10000000*ratio),int(100*ratio),float(10*ratio),float(2*ratio),settled,
                                10000000,100,10.,2.,.2,.2,mode,typ,scene,aux))
        for _ in range(2000):
            re=rng.randrange(1,1000000000);ri=rng.randrange(1,12801)
            cases.append((rng.randrange(1,2*re),rng.randrange(1,2*ri),rng.uniform(0,30),rng.uniform(0,8),rng.randrange(2),
                re,ri,rng.uniform(1,20),rng.uniform(1,4),rng.choice([0.,.1,.2,.5,1.]),rng.choice([0.,.1,.2,.5,1.]),
                rng.choice([0,4,5,6]),rng.choice([0,0xf00]),rng.choice([0,0xc00000]),rng.choice([0,6,0x502])))
        accepted=0
        for c in cases:
            e,i,se,sg,settled,re,ri,rse,rsg,et,gt,mode,typ,scene,aux=c
            u.mem_write(0x1072000,struct.pack('<QQIIffIB3x',e,0,i,0,se,sg,0,settled))
            for reg,value in zip([UC_ARM64_REG_S0,UC_ARM64_REG_S1,UC_ARM64_REG_S2,UC_ARM64_REG_S3],[rse,rsg,et,gt]):
                u.reg_write(reg,struct.unpack('<I',struct.pack('<f',value))[0])
            actual=invoke(u,0x1429ec,(0x1070000,0,re,ri,mode,typ,3,scene),(aux,))
            port=check(*c)
            assert actual==port,(c,actual,port)
            accepted+=actual
        print(f'PASS {len(cases)} original ARM64/C++ comparisons; {accepted} accepted; full capture policy untested')
if __name__=='__main__':main()
