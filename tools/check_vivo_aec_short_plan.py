#!/usr/bin/env python3
"""Complete EVMinusCalc comparison with original ARM64; shared host libm."""
import argparse,ctypes,hashlib,random,struct,subprocess,tempfile
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE
from unicorn.arm64_const import *
from check_vivo_aec_adjust import SHA
DRIVER=r'''
#include "vivo-aec-short-plan.h"
#include <cstring>
#include <cstdio>
extern "C" float host_exp2(float x){return std::exp2(x);}
extern "C" double host_log10(double x){return std::log10(x);}
extern "C" int plan(const float* p,const unsigned* f,unsigned char* out) {
 try {
  vivo_aec::ShortPlanInput in{int(f[0]),{f[1],0,0,0,0,0,0},
   p[0],p[1],p[2],p[3],p[4],p[5],p[6],p[7],p[8],p[9],p[10],p[11],p[12],p[13],p[14],
   bool(f[2]),bool(f[3]),bool(f[4]),bool(f[5]),int(f[6]),bool(f[7]),bool(f[8]),{p[15],p[16],4}};
  auto result=vivo_aec::plannedShortExposures({p[17],p[18],0,1},in,{1.43f,41245,.8f,p[19],0});
  if(result.enabled){std::memcpy(out,&result.shortFrame,16);std::memcpy(out+16,&result.extraShortFrame,16);}
  return 0;
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
    obj,params,work,decrease,output,context,vtable,sensor,state,tuning,common,bank,table,rows,gaps,camera=[0x1100000+i*0x10000 for i in range(16)]
    stop,logger=0x7ff000,0x7ff100
    def q(a,v):u.mem_write(a,struct.pack('<Q',v))
    def w(a,v):u.mem_write(a,struct.pack('<I',v&0xffffffff))
    def f(a,v):u.mem_write(a,struct.pack('<f',v))
    q(0x1f9ec0,0x1210000);q(0x1210000,logger)
    q(obj+0x550,context);q(context,vtable)
    accessors={0x7ff200:tuning,0x7ff300:state,0x7ff400:sensor,0x7ff500:camera}
    for off,addr in zip((0x300,0x298,0xc0,0x10),accessors):q(vtable+off,addr)
    q(vtable+0x168,0x7ff600)
    q(params+0xa8,common);q(params+0xb0,bank);q(params+0xb8,bank);q(bank+0x30,table)
    f(table,1);w(table+4,2);q(table+0x10,rows);f(table+24,.97)
    u.mem_write(rows,struct.pack('<fIQII',1.43,0,41245,1,0)+struct.pack('<fIQII',1921,0,1000000000,0,0));f(sensor+8,.8)
    redirects={0x1ead40:0x17aae4,0x1eac30:0x177a9c}
    root=Path(__file__).resolve().parents[1];rng=random.Random(245425)
    with tempfile.TemporaryDirectory(prefix='ae-short-plan-') as tmp:
        d=Path(tmp);(d/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-ffp-contract=off','-shared','-fPIC','-Wall','-Wextra','-Werror',
            '-I',str(root/'app/src/main/cpp'),str(d/'driver.cpp'),'-o',str(d/'driver.so')],check=True)
        lib=ctypes.CDLL(str(d/'driver.so'));lib.plan.argtypes=[ctypes.POINTER(ctypes.c_float),ctypes.POINTER(ctypes.c_uint),ctypes.POINTER(ctypes.c_ubyte)]
        lib.host_log10.argtypes=[ctypes.c_double];lib.host_log10.restype=ctypes.c_double
        lib.host_exp2.argtypes=[ctypes.c_float];lib.host_exp2.restype=ctypes.c_float
        fs=None
        def hook(uc,address,size,data):
            if address in redirects:uc.reg_write(UC_ARM64_REG_PC,redirects[address]);return
            if address in (0x1ef2f0,0x1ef2e0):
                exp=address==0x1ef2f0;reg=UC_ARM64_REG_S0 if exp else UC_ARM64_REG_D0
                fmt,bits=('<f','<I') if exp else ('<d','<Q')
                value=struct.unpack(fmt,struct.pack(bits,uc.reg_read(reg)))[0]
                value=lib.host_exp2(value) if exp else lib.host_log10(value)
                uc.reg_write(reg,struct.unpack(bits,struct.pack(fmt,value))[0])
            elif address==0x7ff600:uc.reg_write(UC_ARM64_REG_X0,fs[6])
            elif address in accessors or address in (logger,0x1e8a10):uc.reg_write(UC_ARM64_REG_X0,accessors.get(address,0))
            else:return
            uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
        u.hook_add(UC_HOOK_CODE,hook);count=0
        for mode in range(15):
            for trial in range(240):
                p=(ctypes.c_float*20)(rng.uniform(1e8,1e10),rng.uniform(1e8,1e10),rng.uniform(-3,3),rng.uniform(-4,0),rng.uniform(-10,1),
                    rng.uniform(0,.2),.01,rng.choice([1,2,4,16,64]),rng.uniform(0,12),rng.uniform(0,10),rng.uniform(0,10),rng.uniform(2,8),
                    rng.choice([0,4,10]),rng.choice([0,.2,3]),rng.choice([0,3,5]),-4,-8,rng.uniform(41245,1e8),rng.uniform(1.43,32),rng.choice([0,8333333,1e7]))
                if trial<20:p[17]=41245;p[18]=1.43
                fs=(ctypes.c_uint*9)(mode,rng.randrange(3),rng.randrange(2),rng.randrange(2),rng.randrange(2),rng.randrange(2),rng.choice([0,6]),rng.randrange(2),rng.randrange(2))
                actual=(ctypes.c_ubyte*32)(*([0xa5]*32));assert lib.plan(p,fs,actual)==0
                for offset,value in zip((0x5c,12,0x60),p[:3]):f(params+offset,value)
                f(decrease,p[3]);f(params+0x88,p[4]);f(params+0x8c,p[5]);f(common+0x2c,p[6]);f(params+0xe0,p[7]);f(tuning+0x410,p[8])
                for off,value in zip((0x78,0x7c,0x98,0x9c,0xb4,0xa0),p[9:15]):f(common+off,value)
                f(gaps,p[15]);f(gaps+4,p[16]);f(obj+0x5f0,p[19]);w(params+0xc4,mode);w(obj+0x644,fs[1])
                u.mem_write(common+0x28,bytes([fs[2]]));w(obj+0x628,fs[3]);w(tuning+0x43c,fs[4]);w(state+0x870,fs[5]);w(state+0x16c,fs[7]);w(camera+4,fs[8])
                u.mem_write(work,bytes(16));u.mem_write(output,bytes(0x84));f(output,p[17]);f(output+4,p[18]);w(output+12,1);u.mem_write(output+16,b'\xa5'*32)
                for reg,val in zip((UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X2,UC_ARM64_REG_X3,UC_ARM64_REG_X4,UC_ARM64_REG_X5),(obj,params,work,gaps,decrease,output)):u.reg_write(reg,val)
                u.reg_write(UC_ARM64_REG_SP,0x1ff0000);u.reg_write(UC_ARM64_REG_LR,stop)
                u.emu_start(0x17969c,stop,count=100000);assert u.reg_read(UC_ARM64_REG_PC)==stop
                expected=bytes(u.mem_read(output+16,32))
                assert bytes(actual)==expected,(mode,trial,list(fs),list(p),bytes(actual).hex(),expected.hex())
                count+=1
    print(f'PASS: {count} original ARM64 EVMinusCalc cases; shared host exp2f/log10')
if __name__=='__main__':main()
