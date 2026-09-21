#!/usr/bin/env python3
"""EVBaseCalc differential test; original ARM64, shared host libm."""
import argparse,ctypes,hashlib,random,struct,subprocess,tempfile
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE
from unicorn.arm64_const import *
from check_vivo_aec_adjust import SHA
DRIVER=r'''
#include "vivo-aec-base.h"
#include <cstring>
#include <cstdio>
extern "C" double host_log10(double x){return std::log10(x);}
extern "C" int plan(const float* p,const unsigned* f,unsigned char* out) {
 try {
  vivo_aec::BaseInput in{uint64_t(p[0]),p[1],p[2],p[3],p[4],p[5],int(f[0]),
   {f[1],f[2],0,f[3],0,f[4],0},f[5],p[6],p[7],p[8],bool(f[6]),bool(f[7]),bool(f[8]),bool(f[9]),p[9],int(f[10])};
  auto result=vivo_aec::plannedBaseExposure(in,{p[10],p[11],123,int(p[12])},1,
   {{1.43f,41245,true},{1921,1000000000,false}},
   {1.43f,1921,41245,1e9,p[13],.97f,.8f,true,false,0});
  std::memcpy(out,&result.normal,16);std::memcpy(out+16,&result.decrease,16);
  std::memcpy(out+32,&result.targetRatio,4);std::memcpy(out+36,&result.correctionMilli,4);
  std::memcpy(out+40,&result.retainedCorrectionMilli,4);return 0;
 }catch(const std::exception& e){std::fprintf(stderr,"%s\n",e.what());return -1;}
}
'''
def main():
    ap=argparse.ArgumentParser();ap.add_argument('library',type=Path);args=ap.parse_args()
    assert hashlib.sha256(args.library.read_bytes()).hexdigest()==SHA
    u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,0x800000);u.mem_map(0x1000000,0x1000000)
    with args.library.open('rb') as f:
        for seg in ELFFile(f).iter_segments():
            if seg['p_type']=='PT_LOAD':u.mem_write(seg['p_vaddr'],seg.data())
    obj,params,work,decrease,output,context,vtable,sensor,state,tuning,common,bank,table,rows,flags,motion=[0x1100000+i*0x10000 for i in range(16)]
    stop,logger=0x7ff000,0x7ff100
    def q(a,v):u.mem_write(a,struct.pack('<Q',v))
    def w(a,v):u.mem_write(a,struct.pack('<I',v&0xffffffff))
    def f(a,v):u.mem_write(a,struct.pack('<f',v))
    q(0x1f9ec0,0x1210000);q(0x1210000,logger)
    q(obj+0x550,context);q(context,vtable);q(obj+0x600,common);q(obj+0x5f8,motion)
    accessors={0x7ff200:tuning,0x7ff300:state,0x7ff400:sensor}
    for off,addr in zip((0x300,0x298,0xc0),accessors):q(vtable+off,addr)
    q(params+0xa8,common);q(params+0xb0,bank);q(params+0xb8,bank);q(bank+0x30,table)
    f(table,1);w(table+4,2);q(table+0x10,rows);f(table+24,.97)
    u.mem_write(rows,struct.pack('<fIQII',1.43,0,41245,1,0)+struct.pack('<fIQII',1921,0,1000000000,0,0))
    u.mem_write(common+0x28,b'\x01\x00');f(sensor+8,.8)
    redirects={0x1ead10:0x17a464,0x1eac30:0x177a9c,0x1eac10:0x1774d4,0x1ead20:0x17a5fc}
    root=Path(__file__).resolve().parents[1];rng=random.Random(245424)
    with tempfile.TemporaryDirectory(prefix='ae-base-') as tmp:
        d=Path(tmp);(d/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-ffp-contract=off','-shared','-fPIC','-Wall','-Wextra','-Werror',
            '-I',str(root/'app/src/main/cpp'),str(d/'driver.cpp'),'-o',str(d/'driver.so')],check=True)
        lib=ctypes.CDLL(str(d/'driver.so'));lib.plan.argtypes=[ctypes.POINTER(ctypes.c_float),ctypes.POINTER(ctypes.c_uint),ctypes.POINTER(ctypes.c_ubyte)]
        lib.host_log10.argtypes=[ctypes.c_double];lib.host_log10.restype=ctypes.c_double
        def hook(uc,address,size,data):
            if address in redirects:uc.reg_write(UC_ARM64_REG_PC,redirects[address]);return
            if address==0x1ef2e0:
                value=struct.unpack('<d',struct.pack('<Q',uc.reg_read(UC_ARM64_REG_D0)))[0]
                uc.reg_write(UC_ARM64_REG_D0,struct.unpack('<Q',struct.pack('<d',lib.host_log10(value)))[0])
            elif address in accessors or address in (logger,0x1e8a10):uc.reg_write(UC_ARM64_REG_X0,accessors.get(address,0))
            else:return
            uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
        u.hook_add(UC_HOOK_CODE,hook);count=0
        for mode in range(15):
            for trial in range(200):
                p=(ctypes.c_float*14)(rng.randrange(20000000,100000000),rng.uniform(8,32),rng.uniform(1e8,1e10),rng.uniform(1e8,1e10),rng.choice([-2,0,1,2]),1,
                    rng.choice([1,1.001,0]),rng.uniform(0,10),rng.uniform(0,10),rng.uniform(.5,4),rng.uniform(-4,0),rng.uniform(.125,1),rng.randrange(-2000,2000),rng.choice([0,8333333,1e7]))
                p[5]=2**p[4]
                if trial % 20 == 0: p[9]=0
                fs=(ctypes.c_uint*11)(mode,rng.randrange(3),rng.randrange(3),rng.randrange(3),rng.randrange(3),rng.choice([0,255,256,512]),rng.randrange(2),rng.randrange(2),rng.randrange(2),rng.randrange(2),rng.randrange(2000))
                actual=(ctypes.c_ubyte*44)();assert lib.plan(p,fs,actual)==0,(mode,trial,list(p),list(fs))
                q(params,int(p[0]));f(params+8,p[1]);f(params+12,p[2]);f(params+0x5c,p[3]);f(params+0x60,p[4]);f(params+0xd8,p[5]);w(params+0xc4,mode)
                f(common+0xb0,p[6]);f(common+0x78,p[7]);f(common+0x7c,p[7]);f(tuning+0x410,p[8]);f(obj+0x63c,p[9]);f(obj+0x5f0,p[13])
                for off,value in zip((0x644,0x648,0x650,0x658,0x628,0x65c),(fs[1],fs[2],fs[3],fs[4],fs[6],fs[10])):w(obj+off,value)
                w(tuning+0x108,fs[5]);w(state+0x8c8,fs[7]);w(state+0x8b4,fs[8]);w(state+0xf0,fs[9])
                u.mem_write(work,bytes(16));u.mem_write(output,bytes(0x84));f(decrease,p[10]);f(decrease+4,p[11]);w(output+0x60,123);w(output+0x64,int(p[12]))
                for reg,val in zip((UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X2,UC_ARM64_REG_X3,UC_ARM64_REG_X4),(obj,params,work,decrease,output)):u.reg_write(reg,val)
                u.reg_write(UC_ARM64_REG_SP,0x1ff0000);u.reg_write(UC_ARM64_REG_LR,stop)
                u.emu_start(0x1790c8,stop,count=100000);assert u.reg_read(UC_ARM64_REG_PC)==stop
                expected=bytes(u.mem_read(output,16))+bytes(u.mem_read(decrease,8))+bytes(u.mem_read(output+0x60,8))+bytes(u.mem_read(output+0x7c,8))+bytes(u.mem_read(obj+0x65c,4))
                assert bytes(actual)==expected,(mode,trial,list(fs),list(p),bytes(actual).hex(),expected.hex())
                count+=1
    print(f'PASS: {count} original ARM64 EVBaseCalc cases; shared host log10')
if __name__=='__main__':main()
