#!/usr/bin/env python3
"""Compare original preview AE routing with the connected C++ plan builder.
No device capture, scene decision or AE estimation is emulated by this test.
"""
import argparse
import ctypes
import hashlib
from pathlib import Path
import random
import struct
import subprocess
import tempfile
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import *
from check_vivo_nice_motion import emulator

SHA='f5fdf379e0316fee77a0ff7760ef247881e4dfade98f3a7bc8b9b8f93c12e901'
DRIVER=r'''
#include "vivo-nice-preview-ae.h"
extern "C" int run(unsigned flags,const void* data,int past,int future,int alternate,
                    int contextType,void* routed,void* wire) {
 const auto* p=static_cast<const unsigned char*>(data);
 vivo_vcf::NicePreviewAe ae;
 std::memcpy(ae.primary.data(),p,192);std::memcpy(&ae.paired,p+192,196);
 std::memcpy(ae.evContent.data(),p+388,8);
 vivo_vcf::NiceHdrQuery q;q.pastCount=past;q.futureCount=future;
 q.alternateExposureMode=alternate;q.sceneType=31;q.imageEchoWithPast=true;
 q.alternateEv.fill(101.f);q.alternateShortEv.fill(-200.f);
 vivo_vcf::NiceHdrRequestContext c;c.dualRawType=contextType;c.captureDrcGain=2.5f;
 c.rawHdrParams={1,2,3};
 vivo_vcf::NiceHdrPlan initial;
 for(auto& f:initial.control.frames){f.gain=37;f.shutter=53;}
 try {
  auto b=vivo_vcf::bindNicePreviewAe(flags,ae,q,c);
  if(b.query.pastCount!=q.pastCount||b.query.futureCount!=q.futureCount||
     b.query.alternateExposureMode!=q.alternateExposureMode||b.query.sceneType!=31||
     !b.query.imageEchoWithPast||b.context.dualRawType!=c.dualRawType||
     b.context.captureDrcGain!=2.5f||b.context.rawHdrParams!=c.rawHdrParams||
     b.query.alternateEv!=q.alternateEv||b.query.alternateShortEv!=q.alternateShortEv)return -2;
  auto bundle=vivo_vcf::buildNiceHdrCaptureFromPreviewAe(flags,ae,q,c,initial);
  if(bundle.plan.control.frameCount!=unsigned(past+future))return -3;
  if(past && bundle.plan.control.frames[0].gain!=37)return -4;
  if(alternate && future && bundle.plan.control.frames[past].shutter!=53)return -5;
  auto o=static_cast<unsigned char*>(routed);
  for(const auto* a:{&b.query.ev,&b.query.gain,&b.query.shutter,&b.query.shortEv,
                     &b.context.shortGain,&b.context.shortShutter}){
   std::memcpy(o,a->data(),64);o+=64;
  }
  std::memcpy(o,b.context.evContent.data(),8);o+=8;
  std::memcpy(o,&b.pairedType,4);
  auto encoded=vivo_vcf::encodeNiceHdrRequest(bundle.metadata);
  std::memcpy(wire,encoded.data(),encoded.size());
  return 0;
 }catch(const std::invalid_argument&){return -1;}
}
'''

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('library',type=Path)
    args=ap.parse_args();assert hashlib.sha256(args.library.read_bytes()).hexdigest()==SHA
    u,_=emulator(args.library)
    cam,types,flags_ptr,sp=0x1100000,0x1180000,0x1190000,0x1fd0000
    end=u.hook_add(UC_HOOK_CODE,lambda u,a,s,d:u.emu_stop(),begin=0xad43c,end=0xad43c)
    rng=random.Random(245420)
    root=Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix='nice-preview-ae-') as directory:
        tmp=Path(directory);(tmp/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-shared','-fPIC','-Wall','-Wextra','-Werror',
                        '-I',str(root/'app/src/main/cpp'),str(tmp/'driver.cpp'),'-o',str(tmp/'driver.so')],check=True)
        run=ctypes.CDLL(str(tmp/'driver.so')).run
        run.argtypes=[ctypes.c_uint,ctypes.c_void_p]+[ctypes.c_int]*4+[ctypes.c_void_p]*2
        checked=0
        for flags in (0x10000,0x20000,0x30000,0x80010000):
          for alternate in (0,1):
            for trial in range(32):
                total=rng.randrange(1,17);past=rng.randrange(total+1);future=total-past
                values=[rng.uniform(.1,100) for _ in range(96)]
                values[0]=101.;values[48]=-200.
                primary=struct.pack('<48f',*values[:48]);paired=struct.pack('<48f',*values[48:])
                # Inactive cells are opaque: include unusual bit patterns.
                if future<16:
                    primary=bytearray(primary);struct.pack_into('<I',primary,4*future,0x7fc12345)
                    primary=bytes(primary)
                dual=rng.choice((0,1,2,6,0x7fc54321))
                ev=struct.pack('<2i',rng.randrange(65536),rng.randrange(65536))
                source=ctypes.create_string_buffer(primary+paired+struct.pack('<i',dual)+ev)
                routed=ctypes.create_string_buffer(396);wire=ctypes.create_string_buffer(448)
                assert run(flags,source,past,future,alternate,dual,routed,wire)==0
                u.mem_write(cam+0x38b0c-16,b'\xa5'*(0x190+32))
                u.mem_write(types,b'\xa5'*64)
                u.mem_write(flags_ptr+12,struct.pack('<I',flags))
                u.mem_write(sp+0x190,struct.pack('<Q',flags_ptr))
                u.mem_write(sp+0x140,struct.pack('<Q',cam+0x38c8c))
                u.mem_write(sp+0x12c0,primary)
                u.mem_write(sp+0x11f0,paired+struct.pack('<i',dual))
                u.mem_write(sp+0x11e8,ev)
                for reg,value in ((UC_ARM64_REG_SP,sp),(UC_ARM64_REG_X21,cam),(UC_ARM64_REG_X24,types)):
                    u.reg_write(reg,value)
                u.emu_start(0xad30c,0x7ff000,count=500)
                assert u.reg_read(UC_ARM64_REG_PC)==0xad43c
                expected=bytes(u.mem_read(cam+0x38b0c,384))+bytes(u.mem_read(cam+0x38c8c,8))+bytes(u.mem_read(types+28,4))
                assert routed.raw==expected
                assert bytes(u.mem_read(cam+0x38b0c-16,16))==b'\xa5'*16
                assert bytes(u.mem_read(cam+0x38c94,16))==b'\xa5'*16
                assert bytes(u.mem_read(types,28))+bytes(u.mem_read(types+32,32))==b'\xa5'*60
                main=(struct.pack('<16f',*([101.]*16))+bytes(128)) if alternate else primary
                short=(struct.pack('<16f',*([-200.]*16))+bytes(128)) if alternate else paired
                expected_wire=(main+short+struct.pack('<i',dual)+struct.pack('<9i',past,future,*([0]*7))
                               +struct.pack('<3i',1,2,3)+ev+struct.pack('<f',2.5))
                assert wire.raw==expected_wire
                checked+=1
        for flags,past,future,kind in ((0,4,3,dual),(0x40000,4,3,dual),(0x10000,17,0,dual),(0x10000,4,3,dual^1)):
            routed=ctypes.create_string_buffer(b'\xa5'*396,396);wire=ctypes.create_string_buffer(b'\xa5'*448,448)
            assert run(flags,source,past,future,0,kind,routed,wire)==-1
            assert routed.raw==b'\xa5'*396 and wire.raw==b'\xa5'*448
        print(f'PASS: {checked} native preview AE routes and connected request payloads; guards and rejection verified')
        print('Scene/count/DRC producers, Camera2 submission and TCE remain outside this test.')
    u.hook_del(end)

if __name__=='__main__':main()
