#!/usr/bin/env python3
"""Verify connected NICE query/control/vendor-payload construction with ARM64.

The original adapter executes its metadata publication blocks. Only metadata
lookup/publication and logging imports are intercepted; no Camera2 unit
conversion, scene/AE inference or device capture is claimed.
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

SHA='f5fdf379e0316fee77a0ff7760ef247881e4dfade98f3a7bc8b9b8f93c12e901'
DRIVER=r'''
#include "vivo-vcf-nice-request.h"
extern "C" int build(int past,int future,int alternate,const float* a,const int* c,float drc,void* out) {
 vivo_vcf::NiceHdrQuery q;q.pastCount=past;q.futureCount=future;q.alternateExposureMode=alternate;
 std::array<float,16>* dest[]={&q.ev,&q.gain,&q.shutter,&q.shortEv,&q.alternateEv,&q.alternateShortEv};
 for(int i=0;i<6;++i)std::memcpy(dest[i]->data(),a+16*i,64);
 vivo_vcf::NiceHdrRequestContext ctx;
 std::memcpy(ctx.shortGain.data(),a+96,64);std::memcpy(ctx.shortShutter.data(),a+112,64);
 std::copy(c,c+3,ctx.rawHdrParams.begin());std::copy(c+3,c+5,ctx.evContent.begin());
 ctx.dualRawType=c[5];ctx.captureDrcGain=drc;
 vivo_vcf::NiceHdrPlan initial;
 for(auto& f:initial.control.frames){f.gain=37.f;f.shutter=53.f;}
 try {
  auto b=vivo_vcf::buildNiceHdrCapture(q,ctx,initial);
  if(b.plan.control.frameCount!=q.pastCount+q.futureCount)return -2;
  if(q.pastCount && b.plan.control.frames[0].gain!=37.f)return -3;
  if(alternate && future && b.plan.control.frames[past].gain!=37.f)return -4;
  auto wire=vivo_vcf::encodeNiceHdrRequest(b.metadata);
  std::memcpy(out,wire.data(),wire.size());
  return 0;
 }catch(const std::invalid_argument&){return -1;}
}
'''

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('library',type=Path)
    ap.add_argument('--payloads',type=Path);args=ap.parse_args()
    payloads=bytearray()
    assert hashlib.sha256(args.library.read_bytes()).hexdigest()==SHA
    u,_=emulator(args.library)
    preview,control,owner,adapter,meta=0x1100000,0x1110000,0x1120000,0x1130000,0x1140000
    sp,fp=0x1fe0000,0x1fe0500
    names={};published={}
    def cstr(ptr):
        out=bytearray()
        while (v:=u.mem_read(ptr+len(out),1)[0]):out.append(v)
        return out.decode()
    def hook(u,address,size,data):
        if address==0x12b390:
            ptr=u.reg_read(UC_ARM64_REG_X1);b=u.mem_read(ptr,24)
            name=cstr(struct.unpack('<Q',b[16:24])[0] if b[0]&1 else ptr+1)
            tag=next((k for k,v in names.items() if v==name),len(names)+1);names[tag]=name
            u.reg_write(UC_ARM64_REG_X0,tag)
        elif address==0x12be80:
            tag=u.reg_read(UC_ARM64_REG_X2);ptr=u.reg_read(UC_ARM64_REG_X3);count=u.reg_read(UC_ARM64_REG_X4)
            published[names[tag]]=bytes(u.mem_read(ptr,count*4))
        elif address!=0x12b030:raise AssertionError(hex(address))
        u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))
    for addr in (0x12b030,0x12b390,0x12be80):u.hook_add(UC_HOOK_CODE,hook,begin=addr,end=addr)
    repo=Path(__file__).resolve().parents[1];rng=random.Random(245412)
    with tempfile.TemporaryDirectory(prefix='nice-request-') as tmp:
        tmp=Path(tmp);(tmp/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-shared','-fPIC','-Wall','-Wextra','-Werror',
            '-I',str(repo/'app/src/main/cpp'),str(tmp/'driver.cpp'),'-o',str(tmp/'driver.so')],check=True)
        build=ctypes.CDLL(str(tmp/'driver.so')).build
        build.argtypes=[ctypes.c_int]*3+[ctypes.c_void_p,ctypes.c_void_p,ctypes.c_float,ctypes.c_void_p]
        checked=0
        for past,future in [(0,1),(4,3),(0,16),(16,0),(5,2),(8,2)]:
         for alternate in (0,1):
          for dual in (0,1,2,6):
           for _ in range(8):
            arrays=[rng.uniform(.1,500) for _ in range(128)]
            arrays[0]=arrays[48]=arrays[64]=arrays[80]=101.
            ints=[rng.randrange(65536) for _ in range(5)]+[dual]
            drc=rng.uniform(-1,16)
            ac=(ctypes.c_float*128)(*arrays);ic=(ctypes.c_int*6)(*ints)
            out=ctypes.create_string_buffer(448)
            assert build(past,future,alternate,ac,ic,drc,out)==0
            u.mem_write(preview,bytes(0x5000));u.mem_write(sp,bytes(0x1000))
            for i,off in enumerate((0x2d10,0x2d50,0x2d90,0x2dd0,0x2ec0,0x2f00,0x2e10,0x2e50)):
                u.mem_write(preview+off,bytes(ac)[i*64:(i+1)*64])
            for off,value in ((0x3d8c,alternate),(0x3e44,dual),(0x68,ints[0])):
                u.mem_write(preview+off,struct.pack('<i',value))
            u.mem_write(preview+0x2eb8,struct.pack('<2i',*ints[1:3]))
            u.mem_write(preview+0x2d08,struct.pack('<2i',past,future))
            u.mem_write(preview+0x3dd0,struct.pack('<f',drc))
            u.mem_write(control,struct.pack('<i',past+future))
            u.mem_write(adapter+0x40,struct.pack('<Q',meta))
            u.mem_write(adapter+0x3568,struct.pack('<2i',*ints[3:5]))
            u.mem_write(meta,struct.pack('<Q',meta+0x100))
            for off,value in ((0x48,adapter),(0x50,meta),(0x68,owner)):
                u.mem_write(sp+off,struct.pack('<Q',value))
            for reg,value in ((UC_ARM64_REG_SP,sp),(UC_ARM64_REG_X29,fp),(UC_ARM64_REG_X19,preview),(UC_ARM64_REG_X24,control)):
                u.reg_write(reg,value)
            published.clear()
            # Explicit end hook also covers cached branches in repeated runs.
            end=u.hook_add(UC_HOOK_CODE,lambda u,a,s,d:u.emu_stop(),begin=0x1029f0,end=0x1029f0)
            u.emu_start(0x102410,0x7ff000,count=10000);u.hook_del(end)
            assert u.reg_read(UC_ARM64_REG_PC)==0x1029f0
            keys=['VivoAlgoAECFrameControl','VivoAlgoAECShortFrameControl','VivoAlgoCaptureFrameControl',
                  'rawHDRParams','niceHdrExpEVMode','rawHDRCaptureDrcGain']
            expected=b''.join(published['vivo.parameter.'+key] for key in keys)
            assert out.raw==expected,(past,future,alternate,dual,len(expected))
            payloads.extend(out.raw)
            checked+=1
        for past,future,alternate,index,value in ((17,0,0,0,1.),(4,3,0,96,float('nan')),(4,3,0,112,float('inf'))):
            ac=(ctypes.c_float*128)(*([1.]*128));ac[index]=value
            out=ctypes.create_string_buffer(b'\xa5'*448,448)
            assert build(past,future,alternate,ac,ic,1.,out)==-1
            assert out.raw==b'\xa5'*448
        if args.payloads:args.payloads.write_bytes(payloads)
        print(f'PASS: {checked} original vendor publications match connected query/request builder; atomic rejection verified')
        print('Exposure units preserved. No device capture or TCE call is tested.')

if __name__=='__main__':main()
