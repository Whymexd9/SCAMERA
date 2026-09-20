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
extern "C" int reference(const void* initial,const void* image,const void* faces,
 const int32_t* geometry,const uint64_t* pointers,void* output,void* storageOutput) {
 ProcessArgument a;CreReferenceInput i;ToneFaces f;ToneFaceStorage storage;
 storage.roiRects.fill(0x5a5a5a5a);storage.jsonRects.fill(0x5a5a5a5a);
 storage.maskRects.fill(0x5a5a5a5a);storage.roiIds.fill(0x5a5a5a5a);
 storage.jsonIds.fill(0x5a5a5a5a);storage.maskValid.fill(0x5a);
 std::memcpy(a.bytes.data(),initial,a.bytes.size());
 std::memcpy(i.bytes.data(),image,i.bytes.size());std::memcpy(&f,faces,sizeof(f));
 try { bindProcessReference(a,storage,i,f,geometry[0],geometry[1],
     {geometry[2],geometry[3]},{geometry[4],geometry[5],geometry[6],geometry[7]}); }
 catch(const std::invalid_argument&) { return 0; }
 const size_t offsets[]={0xaa0,0xab0,0xc50,0xc60,0xac0,0xac8};
 const uint64_t actual[]={reinterpret_cast<uint64_t>(storage.roiRects.data()),
   reinterpret_cast<uint64_t>(storage.roiIds.data()),reinterpret_cast<uint64_t>(storage.jsonRects.data()),
   reinterpret_cast<uint64_t>(storage.jsonIds.data()),reinterpret_cast<uint64_t>(storage.maskRects.data()),
   reinterpret_cast<uint64_t>(storage.maskValid.data())};
 for(int n=0;n<6;++n) {
   if(toneRead<uint64_t>(a,offsets[n]-0x9d0)!=actual[n])return -1;
   processPut(a,offsets[n]-0x9d0,pointers[n]);
 }
 std::memcpy(storageOutput,&storage,sizeof(storage));
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
        reference_bind=ctypes.CDLL(str(temp/'test.so')).reference
        reference_bind.argtypes=[ctypes.c_void_p]*7
        image=0x1140000
        for case in range(128):
            count=[0,1,2,39,40][case%5]
            initial=rng.randbytes(0x6d0);descriptor=bytearray(rng.randbytes(0x198))
            struct.pack_into('<f',descriptor,0xb4,rng.uniform(.125,128))
            geometry=[4096,3072,case,case+1,4,8,3999,2999]
            faces=rng.randbytes(40*16*3+40*4*2+40)+struct.pack('<i',count)
            u.mem_write(image,bytes(0x5230))
            u.mem_write(image+0x18,bytes(descriptor))
            u.mem_write(image,struct.pack('<QQ',0x1600000,0x1600000+44*count))
            u.mem_write(image+0x4580,struct.pack('<ii',*geometry[:2]))
            u.mem_write(image+0x4e0,struct.pack('<ii',*geometry[2:4]))
            # 2a35c8 returns object+0x4f0: the inclusive crop rectangle.
            u.mem_write(image+0x4f0,struct.pack('<iiii',*geometry[4:]))
            for offset,start,size in [(0x4a90,0,640),(0x4f90,640,640),
                (0x4d10,1280,640),(0x4630,1920,160),(0x46d0,2240,40)]:
                u.mem_write(image+offset,faces[start:start+size])
            # Both native face views use the same ID array.
            faces=faces[:2080]+faces[1920:2080]+faces[2240:]
            u.mem_write(node+0x90,b'\x5a'*0x8e8)
            u.mem_write(node+0x984,bytes(4));u.mem_write(node+0x99c,bytes(4))
            u.mem_write(node+0x9c0,b'G'*16+initial+b'H'*16)
            u.mem_write(node+0x14,struct.pack('<i',case%6))
            u.mem_write(node+0x9b0,struct.pack('<Q',txe))
            u.mem_write(txe+0x68,struct.pack('<Q',image))
            invoke(u,0x38ccb8,[node])
            pointers=[node+o for o in [0x130,0x90,0x450,0x3b0,0x6d0,0x950]]
            output=ctypes.create_string_buffer(0x6d0);storage=ctypes.create_string_buffer(2280)
            assert reference_bind(ctypes.create_string_buffer(initial),ctypes.create_string_buffer(bytes(descriptor)),
                ctypes.create_string_buffer(faces),(ctypes.c_int32*8)(*geometry),
                (ctypes.c_uint64*6)(*pointers),output,storage)==1
            native=bytes(u.mem_read(node+0x9d0,0x6d0))
            assert output.raw==native,(case,[(hex(i),output.raw[i],native[i]) for i in range(0x6d0)
                if output.raw[i]!=native[i]][:12])
            expected=b''.join(bytes(u.mem_read(node+off,size)) for off,size in
                [(0x130,640),(0x450,640),(0x6d0,640),(0x90,160),(0x3b0,160),(0x950,40)])
            assert storage.raw==expected,(case,'owned face storage')
            assert bytes(u.mem_read(node+0x9c0,16))==b'G'*16
            assert bytes(u.mem_read(node+0x10a0,16))==b'H'*16
        print('PASS: 128 complete native reference/face/AE bindings and owned face storage')

if __name__=='__main__':main()
