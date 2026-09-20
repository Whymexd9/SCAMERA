#!/usr/bin/env python3
"""Compare known TCE Create argument binding with the original CRE producer.

Executes 38c19c..38c414 and its real leaf getters. Does not invoke TCE Create,
GPU initialization or processing. Unassigned argument bytes must be preserved.
"""
import argparse
import ctypes
import hashlib
import random
import struct
import subprocess
import tempfile
from pathlib import Path
from check_vivo_nice_motion import emulator, CRE_SHA256
from unicorn.arm64_const import *

DRIVER=r'''
#include "vivo-nice-tce-create.h"
using namespace vivo_nice::tce_contract;
extern "C" void bind(const void* initial,const uint64_t* paths,const int32_t* v,
 const float* zoom,const void* camera,const void* scene,void* out) {
 CreateArgument a;std::memcpy(a.bytes.data(),initial,a.bytes.size());
 CreateBindings b{};
 uint64_t* fields[]={&b.modelDir,&b.configXml,&b.effectXml,&b.segmentConfig,&b.allInOneConfig,
 &b.skyConfig,&b.sunConfig,&b.dumpDir,&b.dumpPrefix,&b.speConfig,&b.faceConfig,&b.outlineConfig};
 for(int i=0;i<12;++i)*fields[i]=paths[i];
 b.debugLevel=v[0];b.mode=v[1];b.photoMode=v[2];b.args1c=v[3];b.args18=v[4];
 b.config108=v[5];b.referenceEntryCount=v[6];b.hasColorInfo=v[7];
 b.uiZoom=zoom[0];b.colorInfoZoom=zoom[1];b.gpuBinaryPath=paths[12];
 std::memcpy(b.cameraInfo.data(),camera,b.cameraInfo.size());
 std::memcpy(b.sceneInfo.data(),scene,b.sceneInfo.size());
 bindCreateArguments(a,b);std::memcpy(out,a.bytes.data(),a.bytes.size());
}
'''

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('cre',type=Path)
    args=parser.parse_args()
    if hashlib.sha256(args.cre.read_bytes()).hexdigest()!=CRE_SHA256:
        raise SystemExit('Unsupported CRE donor')
    u,_=emulator(args.cre);rng=random.Random(20260926)
    node,txe,config,input_arg,color,dump,reference=0x1100000,0x1110000,0x1120000,0x1130000,0x1140000,0x1150000,0x1160000
    with tempfile.TemporaryDirectory(prefix='nice-create-') as temp:
        temp=Path(temp);(temp/'test.cpp').write_text(DRIVER)
        include=Path(__file__).resolve().parents[1]/'app/src/main/cpp'
        subprocess.run(['c++','-std=c++17','-O2','-Wall','-Wextra','-Werror','-shared','-fPIC',
            '-I',str(include),str(temp/'test.cpp'),'-o',str(temp/'test.so')],check=True)
        bind=ctypes.CDLL(str(temp/'test.so')).bind
        bind.argtypes=[ctypes.c_void_p]*7
        for case in range(128):
            initial=rng.randbytes(0x4c8);camera=rng.randbytes(0x68);scene=rng.randbytes(0x20)
            u.mem_write(txe+0x520,initial)
            u.mem_write(node+0x9b0,struct.pack('<Q',txe))
            u.mem_write(node+0x70,struct.pack('<Q',dump))
            u.mem_write(txe,struct.pack('<QQ',config,input_arg))
            entries=rng.randrange(21)
            u.mem_write(txe+0x68,struct.pack('<Q',reference))
            u.mem_write(reference,struct.pack('<QQ',0x2000000,0x2000000+entries*44))
            locations=[config+o for o in [0,0x18,0x30,0x78,0x90,0x60,0x48]]
            locations += [dump+8,txe+0x38,config+0xc0,config+0xd8,config+0xf0,txe+0x50]
            pointers=[]
            for index,location in enumerate(locations):
                value=f'path-{case}-{index}'.encode()
                if (case+index)%2:
                    ptr=0x1800000+index*256
                    descriptor=struct.pack('<QQQ',33,len(value),ptr)
                    u.mem_write(ptr,value+b'\0')
                else:
                    ptr=location+1
                    descriptor=(bytes([len(value)*2])+value+b'\0').ljust(24,b'\0')
                u.mem_write(location,descriptor);pointers.append(ptr)
            vals=[rng.randrange(4),rng.randrange(7),rng.randrange(7),rng.randrange(100),
                rng.randrange(100),rng.randrange(100),entries,case%2]
            zoom=[rng.uniform(.5,15),rng.uniform(.5,15)]
            for off,index in [(0x10,1),(0x14,2),(0x1c,3),(0x18,4)]:u.mem_write(txe+off,struct.pack('<i',vals[index]))
            u.mem_write(config+0x164,struct.pack('<i',vals[5]))
            u.mem_write(input_arg+0x1818,struct.pack('<f',zoom[0]))
            u.mem_write(input_arg+0x1968,struct.pack('<Q',color if vals[7] else 0))
            u.mem_write(color+0x1484,struct.pack('<f',zoom[1]))
            u.mem_write(input_arg+0x18a0,camera);u.mem_write(input_arg+0x186c,scene)
            for reg,value in [(UC_ARM64_REG_X19,node),(UC_ARM64_REG_X20,txe),
                (UC_ARM64_REG_X21,txe+0x520),(UC_ARM64_REG_X22,config),
                (UC_ARM64_REG_W0,vals[0])]:u.reg_write(reg,value)
            u.emu_start(0x38c19c,0x38c414,count=1000)
            assert u.reg_read(UC_ARM64_REG_PC)==0x38c414
            out=ctypes.create_string_buffer(0x4c8)
            bind(ctypes.create_string_buffer(initial),(ctypes.c_uint64*13)(*pointers),
                (ctypes.c_int32*8)(*vals),(ctypes.c_float*2)(*zoom),
                ctypes.create_string_buffer(camera),ctypes.create_string_buffer(scene),out)
            native=bytes(u.mem_read(txe+0x520,0x4c8))
            assert out.raw==native,(case,[(hex(i),out.raw[i],native[i]) for i in range(0x4c8) if out.raw[i]!=native[i]][:10])
        print('PASS: 128 original CRE Create-binding comparisons, both string layouts and optional color info')
        print('Unknown bytes preserved; no TCE Create/Process or capture execution performed.')

if __name__=='__main__':main()
