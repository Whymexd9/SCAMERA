#!/usr/bin/env python3
"""Compare ready-queue selection with the original VCF2 ARM64 arithmetic.

Executes 0x1417e8--0x1422a8 including the original compatibility helper. Metadata
collection is outside this range. Config helper 0x1426f0 is supplied as a boolean
input, camera-name lookup is empty, debug index override is zero, logs disabled.
This does not validate the omitted scene/config policy or Camera2 integration.
"""
import argparse
import ctypes
import hashlib
import random
import struct
import subprocess
import tempfile
from pathlib import Path
from check_vivo_nice_motion import emulator
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import *

SHA = '93a3149a70fea5f177bb0542278c47ec232fc477aa96cb7a63534b1f6b5f8dad'
DRIVER = r'''
#include "vivo-vcf-ready-selection.h"
struct Sample {uint64_t exp,ts;uint32_t iso,motion;float se,sg;uint32_t unused;uint8_t settled,pad[3];};
static_assert(sizeof(Sample)==40);
extern "C" int select_frames(const Sample* data, unsigned n, unsigned original, unsigned needed,
 uint64_t timestamp, int mode, int type, unsigned scene, int aux, float et, float gt,
 int prefer, int initialNext, int* out) {
 try {
    std::vector<vivo_vcf::TimedFrameCandidate> frames;
    for(unsigned i=0;i<n;++i){auto& f=data[i];frames.push_back({{f.exp,f.iso,f.se,f.sg,f.settled!=0},f.ts});}
    auto r=vivo_vcf::selectReadyFrames(frames,original,needed,timestamp,
        {0,0,0,0,et,gt,mode,type,scene,aux},prefer!=0,initialNext!=0);
    out[0]=r.offset;out[1]=r.referenceIndex;out[2]=r.exposureReferenceIndex;
    out[3]=r.firstCompatible;out[4]=r.lastCompatible;out[5]=r.onlyNeedNext;
    return 0;
 } catch(const std::invalid_argument&) {return 1;}
}
'''


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('library',type=Path)
    args=ap.parse_args()
    assert hashlib.sha256(args.library.read_bytes()).hexdigest()==SHA
    u,_=emulator(args.library)
    sp=0x1ffe000
    config_value=False
    native_reference=[]
    def hook(u,pc,size,data):
        if pc==0x141adc:
            native_reference[:] = [u.reg_read(r) for r in (UC_ARM64_REG_X19,UC_ARM64_REG_X22)]
            return
        if pc==0x700000:
            u.mem_write(u.reg_read(UC_ARM64_REG_X8),bytes(24))
        elif pc in (0x2f22e8,0x142670): u.reg_write(UC_ARM64_REG_X0,0x1060000)
        else:u.reg_write(UC_ARM64_REG_X0,int(config_value) if pc==0x1426f0 else 0)
        u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))
    for pc in (0x141adc,0x700000,0x2f22e8,0x142670,0x1426f0,0x2f1910):
        u.hook_add(UC_HOOK_CODE,hook,begin=pc,end=pc)
    u.mem_write(0x1060000,struct.pack('<Q',0x1061000))
    u.mem_write(0x1061068,struct.pack('<Q',0x700000))
    # Log-control indirection, no changes to original instructions.
    u.mem_write(0x2ffb48,struct.pack('<Q',0x1068000))
    rng=random.Random(20260920)
    with tempfile.TemporaryDirectory(prefix='vcf-selection-') as d:
        d=Path(d);(d/'driver.cpp').write_text(DRIVER)
        include=Path(__file__).resolve().parents[1]/'app/src/main/cpp'
        subprocess.run(['c++','-std=c++17','-O2','-shared','-fPIC','-Wall','-Wextra','-Werror',
            '-I',str(include),str(d/'driver.cpp'),'-o',str(d/'driver.so')],check=True)
        check=ctypes.CDLL(str(d/'driver.so')).select_frames
        check.argtypes=[ctypes.c_void_p,*([ctypes.c_uint]*3),ctypes.c_uint64,
            ctypes.c_int,ctypes.c_int,ctypes.c_uint,ctypes.c_int,ctypes.c_float,ctypes.c_float,
            ctypes.c_int,ctypes.c_int,ctypes.c_void_p]
        check.restype=ctypes.c_int
        cases=0
        for trial in range(1600):
            n=rng.randrange(1,25);needed=rng.randrange(1,33)
            mode=trial%9;typ=rng.choice([0,0xf00]);scene=rng.choice([0,0xc00000]);aux=rng.choice([0,6,0x502])
            et,gt=rng.choice([0.,.1,.2,.5]),rng.choice([0.,.125,.25,.5])
            config_value=bool(trial%2);initial_next=int(trial%13==0)
            original=n+rng.randrange(0,min(3,n))
            timestamps=[100000000+i*33333333 for i in range(n)]
            if n>1 and trial%7==0: timestamps[1]=timestamps[0]
            timestamp=rng.choice([0,timestamps[0],timestamps[-1],timestamps[-1]+10000000,
                rng.randrange(timestamps[0],timestamps[-1]+1)])
            samples=[]
            for i,t in enumerate(timestamps):
                ratio=rng.choice([.5,1.,1.,1.,2.]) if trial%3 else 1.
                samples.append((int(10000000*ratio),t,int(100*ratio),0,10*ratio,2*ratio,0,int(rng.randrange(5)!=0)))
            packed=b''.join(struct.pack('<QQIIffIB3x',*f) for f in samples)
            source=ctypes.create_string_buffer(packed);out=(ctypes.c_int*6)()
            assert check(source,n,original,needed,timestamp,mode,typ,scene,aux,et,gt,
                         int(config_value),initial_next,out)==0
            u.mem_write(sp,bytes(0x640))
            u.reg_write(UC_ARM64_REG_SP,sp)
            u.mem_write(0x1070000,struct.pack('<QQQ',0x106a000,0x106a000+original*48,0x106a000+original*48))
            u.mem_write(0x1071000,packed)
            u.mem_write(sp+0x200,struct.pack('<QQQ',0x1071000,0x1071000+n*40,0x1071000+n*40))
            u.mem_write(0x1079000,struct.pack('<ffII',gt,et,scene,aux)+bytes(64))
            u.mem_write(sp+0x70,struct.pack('<QIIQ',0x1079000,3,typ,timestamp))
            u.mem_write(sp+0x8c,struct.pack('<I',needed))
            u.mem_write(sp+0x58,struct.pack('<I',mode))
            u.mem_write(sp+0x40,struct.pack('<Q',0x107a000))
            u.mem_write(sp+0x50,struct.pack('<Q',0x107b000))
            u.mem_write(0x107a000,struct.pack('<I',initial_next))
            u.mem_write(0x107b000,bytes(16))
            u.reg_write(UC_ARM64_REG_X27,0x1070000)
            u.reg_write(UC_ARM64_REG_X28,0x1067000)
            u.emu_start(0x1417e8,0x1422a8,count=100000)
            assert u.reg_read(UC_ARM64_REG_PC)==0x1422a8,'Oracle did not finish'
            native=[u.reg_read(r) for r in (UC_ARM64_REG_W25,UC_ARM64_REG_W20,
                                          UC_ARM64_REG_W27,UC_ARM64_REG_W23)]
            native.append(struct.unpack('<I',u.mem_read(0x107a000,4))[0]!=0)
            port=[out[0],out[1],out[3],out[4],bool(out[5])]
            assert native==port,(trial,n,needed,mode,timestamp,native,port,samples)
            ref=samples[out[2]]
            assert native_reference==[ref[0],ref[2]],(trial,native_reference,ref)
            cases+=1
        # Caller-invariant checks deliberately differ from unsafe native input.
        assert check(source,0,0,1,0,0,0,0,0,.2,.2,0,0,out)==1
        assert check(source,n,n,0,0,0,0,0,0,.2,.2,0,0,out)==1
        assert check(source,n,n+3,1,0,0,0,0,0,.2,.2,0,0,out)==1
        print(f'PASS {cases} original ARM64/C++ ready-selection comparisons and caller-invariant checks')
        print('Metadata acquisition, scene/config policy, debug override and capture integration excluded.')


if __name__=='__main__':main()
