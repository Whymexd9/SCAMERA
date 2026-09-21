#!/usr/bin/env python3
"""Compare EVPlusCalc orchestration, supplying identical host libm to ARM64."""
import argparse, ctypes, hashlib, math, random, struct, subprocess, tempfile
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import *
from check_vivo_aec_adjust import SHA

DRIVER=r'''
#include "vivo-aec-long.h"
#include <cstring>
extern "C" float host_exp2(float v) { return std::exp2(v); }
extern "C" double host_log10(double v) { return std::log10(v); }
extern "C" int plan(float* normal,const float* p,const float* history,int mode,unsigned base,unsigned family) {
 try {
  vivo_aec::Exposure n;std::memcpy(&n,normal,16);
  vivo_aec::LongPlanInput in{uint64_t(history[0]),history[1],history[2],history[3],
      history[4],history[5],history[6],history[7],mode,{base,0,0,0,0,family,0}};
  std::vector<vivo_aec::TableRow> table{{p[0],uint64_t(p[2]),true},{p[1],uint64_t(p[3]),false}};
  auto r=vivo_aec::plannedLongExposure(n,in,p[7],p[8],table,
      {p[0],p[1],p[2],p[3],p[4],p[5],p[6],true,false,0});
  std::memcpy(normal,&r,16);return 0;
 } catch(const std::invalid_argument&) {return -1;}
}
'''

def main():
    ap=argparse.ArgumentParser();ap.add_argument('library',type=Path);args=ap.parse_args()
    assert hashlib.sha256(args.library.read_bytes()).hexdigest()==SHA
    u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,0x800000);u.mem_map(0x1000000,0x1000000)
    with args.library.open('rb') as f:
        for seg in ELFFile(f).iter_segments():
            if seg['p_type']=='PT_LOAD':u.mem_write(seg['p_vaddr'],seg.data())
    obj,params,work,gaps,output,context,vtable,sensor,flags,motion,common,bank1,bank2,table1,table2,rows=[0x1100000+i*0x10000 for i in range(16)]
    stop,logger,sensorCall=0x7ff000,0x7ff100,0x7ff200
    def q(a,v):u.mem_write(a,struct.pack('<Q',v))
    def f(a,v):u.mem_write(a,struct.pack('<f',v))
    def w(a,v):u.mem_write(a,struct.pack('<I',v&0xffffffff))
    q(0x1f9ec0,0x1210000);q(0x1210000,logger)
    q(obj+0x550,context);q(context,vtable);q(vtable+0xc0,sensorCall)
    q(obj+0x600,flags);q(obj+0x5f8,motion)
    q(params+0xa8,common);q(params+0xb0,bank1);q(params+0xb8,bank2)
    q(bank1+0x30,table1);q(bank2+0x30,table2)
    # In EVPlusCalc the blur table always comes from bank1+0x38.
    u.mem_write(bank1+0x38,bytes(16))
    redirects={0x1ead60:0x17a238,0x1ead10:0x17a464,0x1eac30:0x177a9c,
        0x1eac10:0x1774d4,0x1ead20:0x17a5fc}
    root=Path(__file__).resolve().parents[1];rng=random.Random(245422)
    with tempfile.TemporaryDirectory(prefix='ae-long-plan-') as tmp:
        d=Path(tmp);(d/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-ffp-contract=off','-shared','-fPIC',
            '-Wall','-Wextra','-Werror','-I',str(root/'app/src/main/cpp'),
            str(d/'driver.cpp'),'-o',str(d/'driver.so')],check=True)
        lib=ctypes.CDLL(str(d/'driver.so'));fp=ctypes.POINTER(ctypes.c_float)
        lib.plan.argtypes=[fp,fp,fp,ctypes.c_int,ctypes.c_uint,ctypes.c_uint]
        lib.host_exp2.argtypes=[ctypes.c_float];lib.host_exp2.restype=ctypes.c_float
        lib.host_log10.argtypes=[ctypes.c_double];lib.host_log10.restype=ctypes.c_double
        def hook(uc,address,size,data):
            if address in redirects:uc.reg_write(UC_ARM64_REG_PC,redirects[address]);return
            if address in (0x1ef2f0,0x1ef2e0):
                exp=address==0x1ef2f0;reg=UC_ARM64_REG_S0 if exp else UC_ARM64_REG_D0
                fmt,bits=('<f','<I') if exp else ('<d','<Q')
                value=struct.unpack(fmt,struct.pack(bits,uc.reg_read(reg)))[0]
                value=lib.host_exp2(value) if exp else lib.host_log10(value)
                uc.reg_write(reg,struct.unpack(bits,struct.pack(fmt,value))[0])
                uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR));return
            if address in (logger,0x1e8a10,sensorCall):
                uc.reg_write(UC_ARM64_REG_X0,sensor if address==sensorCall else 0)
                uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
        u.hook_add(UC_HOOK_CODE,hook)
        count=0
        for mode in range(15):
            for trial in range(80):
                base,family=rng.randrange(4),rng.randrange(4)
                n=(ctypes.c_float*4)(rng.uniform(1e5,1e8),rng.uniform(1.43,32),0,0)
                before=bytes(n)
                p=(ctypes.c_float*9)(1.43,1921,41245,1e9,rng.choice([0,8333333,1e7]),.97,.8,rng.choice([1e7,1e8,1e9]),1)
                h=(ctypes.c_float*8)(rng.randrange(100000,100000000),rng.uniform(1.43,32),
                    rng.uniform(1,16),rng.uniform(-2,6),rng.uniform(1,16),rng.choice([-2,0,1,2]),1,rng.uniform(2,6))
                h[6]=lib.host_exp2(h[5])
                assert lib.plan(n,p,h,mode,base,family)==0
                q(params,int(h[0]));f(params+8,h[1]);f(params+0xc,h[2]);f(params+0x38,h[3])
                f(params+0x5c,h[4]);f(params+0x60,h[5]);f(params+0xd8,h[6]);f(gaps+8,h[7])
                w(params+0xc4,mode);w(obj+0x644,base);w(obj+0x658,family)
                w(obj+0x61c,0);f(obj+0x5f0,p[4]);f(sensor+8,p[6]);f(common+0x50,p[7])
                u.mem_write(flags+0x28,b'\x01\x00');w(output+0x78,0)
                for table in (table1,table2):
                    u.mem_write(table,struct.pack('<fI',p[8],2));q(table+0x10,rows);f(table+24,p[5])
                u.mem_write(rows,struct.pack('<fIQII',p[0],0,int(p[2]),1,0)+struct.pack('<fIQII',p[1],0,int(p[3]),0,0))
                u.mem_write(output,before);u.mem_write(output+0x40-4,b'\xa5'*24)
                for reg,value in zip((UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X2,UC_ARM64_REG_X3,UC_ARM64_REG_X4,UC_ARM64_REG_X5),
                    (obj,params,work,gaps,0x1220000,output)):u.reg_write(reg,value)
                u.reg_write(UC_ARM64_REG_SP,0x1ff0000);u.reg_write(UC_ARM64_REG_LR,stop)
                u.emu_start(0x179fec,stop,count=100000)
                assert u.reg_read(UC_ARM64_REG_PC)==stop
                expected=bytes(u.mem_read(output+0x40,16))
                assert bytes(n)==expected,(mode,base,family,list(h),bytes(n).hex(),expected.hex())
                assert bytes(u.mem_read(output+0x3c,4))==b'\xa5'*4
                assert bytes(u.mem_read(output+0x50,4))==b'\xa5'*4
                count+=1
    print(f'PASS: {count} original ARM64 EVPlusCalc cases, shared host exp2f/log10; Camera2 integration not tested')

if __name__=='__main__':main()
