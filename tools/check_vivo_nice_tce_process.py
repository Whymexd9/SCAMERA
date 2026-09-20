#!/usr/bin/env python3
"""Compare complete CRE scene/color Process binding, not TCE image execution."""
import argparse
import ctypes
import hashlib
import random
import struct
import subprocess
import tempfile
from pathlib import Path
from check_vivo_nice_motion import emulator, invoke, CRE_SHA256

DRIVER = r'''
#include "vivo-nice-tce-process.h"
using namespace vivo_nice::tce_contract;
extern "C" int bind(const void* initial,const void* scene,const void* color,
                    float reference,void* output) {
 ProcessArgument a; CreSceneInput s; CreColorInput c;
 std::memcpy(a.bytes.data(),initial,a.bytes.size());
 std::memcpy(s.bytes.data(),scene,s.bytes.size());
 if(color)std::memcpy(c.bytes.data(),color,c.bytes.size());
 try { bindProcessScene(a,s,color?&c:nullptr,reference); }
 catch(const std::invalid_argument&) { return 0; }
 std::memcpy(output,a.bytes.data(),a.bytes.size());return 1;
}
'''

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('cre',type=Path)
    args=p.parse_args()
    assert hashlib.sha256(args.cre.read_bytes()).hexdigest()==CRE_SHA256
    u,_=emulator(args.cre);rng=random.Random(20260929)
    node,scene,color,txe=0x1100000,0x1110000,0x1120000,0x1130000
    with tempfile.TemporaryDirectory(prefix='nice-process-') as temp:
        temp=Path(temp);(temp/'test.cpp').write_text(DRIVER)
        include=Path(__file__).resolve().parents[1]/'app/src/main/cpp'
        subprocess.run(['c++','-std=c++17','-O2','-Wall','-Wextra','-Werror','-shared','-fPIC',
            '-I',str(include),str(temp/'test.cpp'),'-o',str(temp/'test.so')],check=True)
        bind=ctypes.CDLL(str(temp/'test.so')).bind
        bind.argtypes=[ctypes.c_void_p]*3+[ctypes.c_float,ctypes.c_void_p]
        for case in range(384):
            initial=rng.randbytes(0x6d0);s=bytearray(rng.randbytes(0x1970))
            c=bytearray(rng.randbytes(0x14c8))
            has_color=case%3!=0;lut=case%3==2
            reference=struct.unpack('<f',struct.pack('<f',rng.uniform(.01,64)))[0]
            struct.pack_into('<f',s,0x1814,rng.uniform(-6000,6000))
            struct.pack_into('<Q',s,0x1968,color if has_color else 0)
            struct.pack_into('<I',c,0x1480,2 if lut else 0)
            # Include uint32 overflow in native size arithmetic, not just usual LUT sizes.
            struct.pack_into('<I',c,0x1468,[0,17,33,65,0xffffffff,100000][case%6])
            for i in range(6):struct.pack_into('<f',c,0x1450+4*i,rng.uniform(-16,16))
            u.mem_write(node+0x9c0,b'G'*16+initial+b'H'*16)
            u.mem_write(node+0x14,struct.pack('<i',case%6))
            u.mem_write(node+0x9b0,struct.pack('<Q',txe))
            u.mem_write(txe+0x28,struct.pack('<f',reference))
            u.mem_write(scene,bytes(s));u.mem_write(color,bytes(c))
            invoke(u,0x38d140,[node,scene])
            native=bytes(u.mem_read(node+0x9d0,0x6d0))
            out=ctypes.create_string_buffer(0x6d0)
            assert bind(ctypes.create_string_buffer(initial),ctypes.create_string_buffer(bytes(s)),
                ctypes.create_string_buffer(bytes(c)) if has_color else None,reference,out)==1
            assert out.raw==native,(case,[(hex(i),out.raw[i],native[i])
                for i in range(len(native)) if out.raw[i]!=native[i]][:12])
            assert bytes(u.mem_read(node+0x9c0,16))==b'G'*16
            assert bytes(u.mem_read(node+0x10a0,16))==b'H'*16
        print('PASS: 384 complete native scene/color bindings, absent color, LUT branches, guards and preserved fields')

if __name__=='__main__':main()
