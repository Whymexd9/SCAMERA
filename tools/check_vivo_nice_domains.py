#!/usr/bin/env python3
"""Compare forward exposure rebasing with original CRE instructions.

Executes the selected-S arithmetic block 0x2ddf14..0x2ddfcc, including the
original getters/setters, without replacing code or hooking imports. Selection
of that S frame, motion/alignment and photographic quality are outside this test.
"""
import argparse, ctypes, hashlib, random, struct, subprocess, tempfile
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import (UC_ARM64_REG_CPACR_EL1, UC_ARM64_REG_SP,
    UC_ARM64_REG_X19, UC_ARM64_REG_X20, UC_ARM64_REG_X21, UC_ARM64_REG_PC)
from decode_vivo_nice_kernels import DONOR_SHA256


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('library',type=Path)
    args=ap.parse_args()
    assert hashlib.sha256(args.library.read_bytes()).hexdigest()==DONOR_SHA256
    u=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
    u.mem_map(0,0x500000);u.mem_map(0x1000000,0x200000)
    with args.library.open('rb') as f:
        for segment in ELFFile(f).iter_segments():
            if segment['p_type']=='PT_LOAD':u.mem_write(segment['p_vaddr'],segment.data())
    u.reg_write(UC_ARM64_REG_CPACR_EL1,3<<20)
    node,param,domain,vec,ptrs=0x1000000,0x1001000,0x1002000,0x1003000,0x1004000
    frames=[0x1010000+i*0x5000 for i in range(7)]
    def put(addr,fmt,*values):u.mem_write(addr,struct.pack(fmt,*values))
    def scalar(addr):return struct.unpack('<f',u.mem_read(addr,4))[0]
    def f32(value):return struct.unpack('<f',struct.pack('<f',value))[0]
    put(node+0x90,'<Q',param);put(param+0x50,'<Q',domain)
    put(vec,'<QQQ',ptrs,ptrs+56,ptrs+56);put(ptrs,'<7Q',*frames)
    for frame,kind in zip(frames,[1,1,1,1,2,0,3]):put(frame+0x458c,'<I',kind)
    cases=[[1,1,1,1,4,.25,.25],[1,1,1,1,4,.25,.03125],
           [1,1,1,1,1,.3,.1]]
    rng=random.Random(20260920)
    cases.extend([[1,1,1,1,rng.uniform(1,8),rng.uniform(.1,.5),rng.uniform(.005,.09)] for _ in range(100)])
    header=Path(__file__).resolve().parents[1]/'app/src/main/cpp/vivo-nice-profile.h'
    with tempfile.TemporaryDirectory() as tmp:
        cpp=Path(tmp)/'domains.cpp';so=Path(tmp)/'domains.so'
        cpp.write_text('#include "'+str(header)+'"\nextern "C" void run(const float* input,float* output){'
            'std::array<float,7> e;std::copy(input,input+7,e.begin());auto d=vivo_nice::forwardExposureDomains(e);'
            'std::copy(d.frameEV.begin(),d.frameEV.end(),output);output[7]=d.normalEV;output[8]=d.normalizationEV;}\n')
        subprocess.run(['g++','-O2','-std=c++17','-shared','-fPIC',str(cpp),'-o',str(so)],check=True)
        lib=ctypes.CDLL(str(so));lib.run.argtypes=[ctypes.POINTER(ctypes.c_float)]*2
        for case in cases:
            e=[f32(v) for v in case];initial=[f32(v/e[6]) for v in e]
            for frame,value in zip(frames,initial):put(frame+0x45bc,'<f',value)
            put(domain+0xec,'<f',initial[0]);put(domain+0xf0,'<f',initial[0]);put(domain+0xf8,'<f',initial[4])
            for reg,value in [(UC_ARM64_REG_SP,0x11ff000),(UC_ARM64_REG_X19,node),
                              (UC_ARM64_REG_X20,vec),(UC_ARM64_REG_X21,5)]:u.reg_write(reg,value)
            u.emu_start(0x2ddf14,0x2ddfcc,count=10000)
            assert u.reg_read(UC_ARM64_REG_PC)==0x2ddfcc
            expected=[scalar(f+0x45bc) for f in frames]+[scalar(domain+0xf0),scalar(domain+0xf8)]
            result=(ctypes.c_float*9)();lib.run((ctypes.c_float*7)(*e),result)
            assert list(result)==expected,(case,list(result),expected)
        print(f'PASS: {len(cases)} S/ES exposure cases; all 9 adapter outputs match original ARM64 exactly')

if __name__=='__main__':main()
