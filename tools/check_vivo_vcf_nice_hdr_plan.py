#!/usr/bin/env python3
"""Compare the recovered NICE HDR plan builder with original VAS ARM64 blocks.

Does not execute scene/AE inference, seamless/sensor branches, vendor publishing
or Camera2. Both EV-only and explicit-exposure branches execute unmodified;
only logging is skipped. Missing-input validation is our adapter contract.
"""
import argparse
import ctypes
import hashlib
import random
import struct
import subprocess
import tempfile
from pathlib import Path
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import *
from check_vivo_nice_motion import emulator

SHA = 'f5fdf379e0316fee77a0ff7760ef247881e4dfade98f3a7bc8b9b8f93c12e901'
DRIVER = r'''
#include "vivo-vcf-nice-hdr-plan.h"
#include "vivo-vcf-batches.h"
using namespace vivo_vcf;
extern "C" int build(uint32_t past,uint32_t future,int alternate,int echo,int scene,
 const float* arrays,const unsigned char* initial,const unsigned char* raw,unsigned char* out) {
 NiceHdrQuery q;q.pastCount=past;q.futureCount=future;q.alternateExposureMode=alternate;
 q.imageEchoWithPast=echo;q.sceneType=scene;
 std::array<float,16>* fields[]={&q.ev,&q.gain,&q.shutter,&q.shortEv,&q.alternateEv,&q.alternateShortEv};
 for(int i=0;i<6;++i)std::copy(arrays+16*i,arrays+16*(i+1),fields[i]->begin());
 NiceHdrPlan p;
 if(readCaptureControlFields(initial,0xb30,p.control)!=CaptureControlRead::Present)return -2;
 // Populate inactive initialized fields too: the transport reader deliberately
 // reads only active entries, but the builder must preserve all other storage.
 std::memcpy(p.control.frames.data(),initial+0x94,640);
 std::memcpy(p.control.batchAlgoTypes.data(),initial+0x14,64);
 std::memcpy(p.control.batchFrameCounts.data(),initial+0x54,64);
 std::memcpy(p.rawFrames.data(),raw,320);
 std::memcpy(&p.imageEchoFrameIndex,initial+0xb20,4);
 try {applyNiceHdrQuery(q,p);captureBatchSlices(p.control);}
 catch(const std::invalid_argument&){return -1;}
 std::memcpy(out,initial,0xb30);
 auto word=[&](size_t offset,uint32_t v){std::memcpy(out+offset,&v,4);};
 word(0,p.control.frameCount);word(4,p.control.batchCount);
 for(int i=0;i<16;++i){word(0x14+4*i,p.control.batchAlgoTypes[i]);word(0x54+4*i,p.control.batchFrameCounts[i]);}
 std::memcpy(out+0x94,p.control.frames.data(),640);
 word(0xb18,p.control.frameCatchMode);word(0xb20,p.imageEchoFrameIndex);
 std::memcpy(out+0xb30,p.rawFrames.data(),320);
 word(0xc70,p.accumulatedShutter);
 return 0;
}
'''

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('library',type=Path)
    args=ap.parse_args()
    if hashlib.sha256(args.library.read_bytes()).hexdigest()!=SHA:
        raise SystemExit('Unsupported VAS adapter donor')
    u,_=emulator(args.library)
    def skip_log(u,address,size,data):
        u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))
    u.hook_add(UC_HOOK_CODE,skip_log,begin=0x12b030,end=0x12b030)
    preview,control,shot,query,owner,sp=0x1100000,0x1110000,0x1120000,0x1130000,0x1140000,0x1ff0000
    rng=random.Random(20260925)
    with tempfile.TemporaryDirectory(prefix='nice-hdr-plan-') as root:
        root=Path(root);(root/'test.cpp').write_text(DRIVER)
        include=Path(__file__).resolve().parents[1]/'app/src/main/cpp'
        subprocess.run(['c++','-std=c++17','-O2','-shared','-fPIC','-Wall','-Wextra','-Werror',
            '-I',str(include),str(root/'test.cpp'),'-o',str(root/'test.so')],check=True)
        build=ctypes.CDLL(str(root/'test.so')).build
        build.argtypes=[ctypes.c_uint32,ctypes.c_uint32,ctypes.c_int,ctypes.c_int,ctypes.c_int,
            ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p]
        checked=0
        for total in range(1,17):
          for past in range(total+1):
           for alternate in range(2):
            for echo in range(2):
                future=total-past;scene=[8,31,0,17][checked%4]
                arrays=[rng.choice([0.,101.,-2.,2.,rng.uniform(-6,6)]) for _ in range(96)]
                arrays[16:48]=[rng.uniform(.01,100.5) for _ in range(32)]
                initial=bytearray(0xb30);struct.pack_into('<II',initial,0,1,1)
                struct.pack_into('<II',initial,0xb18,7,0)
                struct.pack_into('<i',initial,0xb20,-9)
                for i in range(16):
                    struct.pack_into('<I',initial,0x14+4*i,900+i)
                    struct.pack_into('<I',initial,0x54+4*i,700+i)
                # Distinct initialized AE fields detect unintended writes, even
                # to past frames, alternate-mode gain/shutter and inactive tail.
                for i in range(32):struct.pack_into('<IfffI',initial,0x94+20*i,77,10+i,30+i,50+i,9)
                raw=b''.join(struct.pack('<IfffI',88,80+i,100+i,120+i,7) for i in range(16))
                arr=(ctypes.c_float*96)(*arrays);out=ctypes.create_string_buffer(0xc74)
                initial_c=ctypes.create_string_buffer(bytes(initial));raw_c=ctypes.create_string_buffer(raw)
                assert build(past,future,alternate,echo,scene,arr,initial_c,raw_c,out)==0
                p=bytearray(0x5000)
                struct.pack_into('<II',p,0x2d08,past,future)
                for i,offset in enumerate([0x2d10,0x2d50,0x2d90,0x2dd0,0x2ec0,0x2f00]):
                    struct.pack_into('<16f',p,offset,*arrays[16*i:16*(i+1)])
                struct.pack_into('<I',p,0x3d8c,alternate)
                u.mem_write(preview,bytes(p));u.mem_write(control,bytes(initial))
                u.mem_write(shot+0x3890,raw);u.mem_write(owner+0x14f5,bytes([echo]))
                u.mem_write(query+0x20,struct.pack('<i',scene))
                u.mem_write(sp+0x70,struct.pack('<Q',owner+0x1000))
                for reg,val in [(UC_ARM64_REG_SP,sp),(UC_ARM64_REG_X1,control),
                    (UC_ARM64_REG_X5,preview),(UC_ARM64_REG_X19,preview),(UC_ARM64_REG_X26,control),
                    (UC_ARM64_REG_X22,control),(UC_ARM64_REG_X23,query),(UC_ARM64_REG_X24,shot)]:u.reg_write(reg,val)
                u.emu_start(0x101fa4,0x101fb8,count=10)
                u.emu_start(0x102048,0x1022c8,count=10000)
                assert u.reg_read(UC_ARM64_REG_PC)==0x1022c8
                u.reg_write(UC_ARM64_REG_X24,control);u.reg_write(UC_ARM64_REG_X23,query)
                u.emu_start(0x1023ec,0x102410,count=20)
                actual=bytes(u.mem_read(control,0xb30))
                for start,end in [(0,0x314),(0xb18,0xb1c),(0xb20,0xb24)]:
                    assert out.raw[start:end]==actual[start:end],(total,past,alternate,echo,[(hex(j),out.raw[j:j+4].hex(),actual[j:j+4].hex()) for j in range(start,end,4) if out.raw[j:j+4]!=actual[j:j+4]])
                assert out.raw[0xb30:0xc70]==bytes(u.mem_read(shot+0x3890,320))
                assert out.raw[0xc70:]==bytes(u.mem_read(query+0x44,4))
                checked+=1
        # No output publication on rejection; inactive arrays are not read.
        invalid=0
        for past,future,alternate,index,value in [(17,0,0,0,0),(0,17,0,0,0),(16,1,0,0,0),
             (0,0,0,0,0),(0,1,0,0,float('nan')),(0,1,0,32,float('inf')),
             (0,1,1,64,float('nan')),(0,1,1,80,float('inf')),(0,1,0,32,3e9)]:
            arr=(ctypes.c_float*96)(*([1.]*96));arr[index]=value
            out=ctypes.create_string_buffer(b'\xa5'*0xc74,0xc74)
            assert build(past,future,alternate,0,0,arr,initial_c,raw_c,out)==-1
            assert out.raw==b'\xa5'*0xc74
            invalid+=1
        print(f'PASS: {checked} original NICE HDR plan comparisons; {invalid} invalid queries rejected')
        print('Scene/AE inputs, Camera2 submission and TCE image integration remain outside this test.')

if __name__=='__main__':main()
