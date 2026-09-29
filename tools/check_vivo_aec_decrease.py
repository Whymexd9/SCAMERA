#!/usr/bin/env python3
"""Compare complete decreaseEVCalc against the pinned original ARM64 body."""
import argparse,ctypes,hashlib,random,struct,subprocess,tempfile
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE
from unicorn.arm64_const import *
from check_vivo_aec_adjust import SHA

DRIVER=r'''
#include "vivo-aec-decrease.h"
#include <cstring>
extern "C" float host_exp2(float x){return std::exp2(x);}
extern "C" double host_log10(double x){return std::log10(x);}
extern "C" int plan(const float* p,const float* c,const unsigned* f,unsigned char* out) {
 try {
  vivo_aec::DecreaseInput in{int(f[0]),{f[1],0,0,f[2],0,f[3],0},f[4],
   p[0],p[1],p[2],p[3],p[4],p[5],p[6],p[7],bool(f[5]),bool(f[6]),p[8],bool(f[7])};
  vivo_aec::DecreaseTuning tuning;std::memcpy(&tuning,c,sizeof(tuning));
  auto result=vivo_aec::decreaseExposure(in,tuning);std::memcpy(out,&result,sizeof(result));return 0;
 }catch(const std::exception&){return -1;}
}
'''

def main():
    ap=argparse.ArgumentParser();ap.add_argument('library',type=Path);args=ap.parse_args()
    assert hashlib.sha256(args.library.read_bytes()).hexdigest()==SHA
    u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,0x800000);u.mem_map(0x1000000,0x1000000)
    with args.library.open('rb') as f:
        for seg in ELFFile(f).iter_segments():
            if seg['p_type']=='PT_LOAD':u.mem_write(seg['p_vaddr'],seg.data())
    obj,params,output,context,vtable,state,tuning,motion,common,evctx,evflag=[0x1100000+i*0x10000 for i in range(11)]
    stop,logger=0x7ff000,0x7ff100
    def q(a,v):u.mem_write(a,struct.pack('<Q',v))
    def w(a,v):u.mem_write(a,struct.pack('<I',v))
    def f(a,v):u.mem_write(a,struct.pack('<f',v))
    q(0x1f9ec0,0x1200000);q(0x1200000,logger)
    q(obj+0x550,context);q(context,vtable)
    accessors={0x7ff200:tuning,0x7ff300:evctx,0x7ff400:evflag}
    for off,addr in zip((0x300,0x2c0,0x2a0),accessors):q(vtable+off,addr)
    q(params+0xa8,common);q(params+0xa0,motion)
    root=Path(__file__).resolve().parents[1];rng=random.Random(245423)
    with tempfile.TemporaryDirectory(prefix='ae-decrease-') as tmp:
        d=Path(tmp);(d/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-ffp-contract=off','-shared','-fPIC',
            '-Wall','-Wextra','-Werror','-I',str(root/'app/src/main/cpp'),str(d/'driver.cpp'),'-o',str(d/'driver.so')],check=True)
        lib=ctypes.CDLL(str(d/'driver.so'));fp=ctypes.POINTER(ctypes.c_float)
        lib.plan.argtypes=[fp,fp,ctypes.POINTER(ctypes.c_uint),ctypes.POINTER(ctypes.c_ubyte)]
        lib.host_exp2.argtypes=[ctypes.c_float];lib.host_exp2.restype=ctypes.c_float
        lib.host_log10.argtypes=[ctypes.c_double];lib.host_log10.restype=ctypes.c_double
        def hook(uc,address,size,data):
            if address in (0x1ef2f0,0x1ef2e0):
                exp=address==0x1ef2f0;reg=UC_ARM64_REG_S0 if exp else UC_ARM64_REG_D0
                fmt,bits=('<f','<I') if exp else ('<d','<Q')
                value=struct.unpack(fmt,struct.pack(bits,uc.reg_read(reg)))[0]
                value=lib.host_exp2(value) if exp else lib.host_log10(value)
                uc.reg_write(reg,struct.unpack(bits,struct.pack(fmt,value))[0])
            elif address in accessors or address in (logger,0x1e8a10):uc.reg_write(UC_ARM64_REG_X0,accessors.get(address,0))
            else:return
            uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
        u.hook_add(UC_HOOK_CODE,hook);count=0
        for mode in range(15):
            for trial in range(200):
                p=(ctypes.c_float*9)(rng.uniform(1e8,1e11),rng.uniform(1e8,1e11),rng.uniform(-3,3),1,
                    rng.choice([0.,1e-4,1e8,1e10]),rng.uniform(0,10),rng.uniform(-.1,.3),rng.uniform(0,.2),rng.choice([0.,1e-7,-2.,2.]))
                p[3]=lib.host_exp2(p[2])
                c=(ctypes.c_float*14)(5,-4,6,-3,7,-1,1e5,1e5,1e5,5e5,1e6,1e5,1,rng.choice([1,1.001,0]))
                flags=(ctypes.c_uint*8)(mode,rng.randrange(3),rng.randrange(3),rng.randrange(3),rng.choice([0,255,256,512]),rng.randrange(2),rng.randrange(2),rng.randrange(2))
                # Keep valid native metadata in int32 output range.
                if p[4]==1e8:p[4]=1e10
                result=(ctypes.c_ubyte*16)();assert lib.plan(p,c,flags,result)==0
                for offset,value in zip((0xc,0x5c,0x60,0xd8,0x78),p[:5]):f(params+offset,value)
                f(motion+0x658,p[5]);f(params+0x8c,p[6]);f(params+0x90,p[7]);f(evctx+0x40,p[8])
                for offset,value in zip((0x34,0x38,0x3c,0x40,0x44,0x48,0x54,0x58,0x5c,0x60,0x64,0x68,0x74,0xb0),c):f(common+offset,value)
                w(params+0xc4,mode);w(obj+0x644,flags[1]);w(obj+0x650,flags[2]);w(obj+0x658,flags[3]);w(tuning+0x108,flags[4])
                w(params+0xc8,flags[5]);w(params+0xcc,flags[6]);w(evflag+0xac,flags[7])
                u.mem_write(output,b'\xa5'*0x84)
                for reg,val in zip((UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X2),(obj,params,output)):u.reg_write(reg,val)
                u.reg_write(UC_ARM64_REG_SP,0x1ff0000);u.reg_write(UC_ARM64_REG_LR,stop)
                u.emu_start(0x178b3c,stop,count=100000);assert u.reg_read(UC_ARM64_REG_PC)==stop
                expected=struct.pack('<II',u.reg_read(UC_ARM64_REG_S0),u.reg_read(UC_ARM64_REG_S1))+bytes(u.mem_read(output+0x60,8))
                assert bytes(result)==expected,(mode,trial,list(flags),list(p),bytes(result).hex(),expected.hex())
                assert bytes(u.mem_read(output,0x60))==b'\xa5'*0x60 and bytes(u.mem_read(output+0x68,0x1c))==b'\xa5'*0x1c
                count+=1
    print(f'PASS: {count} original ARM64 decreaseEVCalc cases; shared host exp2f/log10')

if __name__=='__main__':main()
