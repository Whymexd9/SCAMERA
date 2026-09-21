#!/usr/bin/env python3
"""Execute the complete donor adjustment, including active motion arbitration."""
import argparse,ctypes,hashlib,random,struct,subprocess,tempfile
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE
from unicorn.arm64_const import (UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X2,
    UC_ARM64_REG_X3,UC_ARM64_REG_X4,UC_ARM64_REG_X5,UC_ARM64_REG_S0,UC_ARM64_REG_S1,UC_ARM64_REG_LR,UC_ARM64_REG_SP,UC_ARM64_REG_PC)
SHA='b2e3e12469a05cf62c2a5892b2b619228fb29a3ccb8fa9fe5f934df0dd5842e9'
DRIVER=r'''
#include "vivo-aec-long.h"
#include <cstring>
extern "C" int adjust(unsigned char* packet,const float* p,int banding,int active,int mode,
                       unsigned count,const float* rows,float motion) {
 try {
  vivo_aec::Exposure value;float factor;
  std::memcpy(&value,packet,16);std::memcpy(&factor,packet+16,4);
  std::vector<vivo_aec::BlurRow> blur;
  for(unsigned i=0;i<count;++i)blur.push_back({rows[i*3],rows[i*3+1],rows[i*3+2]});
  auto out=vivo_aec::normalExposureAdjustment(value,factor,
     {p[0],p[1],p[2],p[3],p[4],p[5],p[6],banding!=0,active!=0,mode},blur,motion);
  std::memcpy(packet,&out.value,16);std::memcpy(packet+16,&out.correction,4);return 0;
 } catch(const std::invalid_argument&){return -1;}
}
extern "C" int longer(unsigned char* packet,const float* p,int banding,int active,int mode,
                       unsigned count,const float* rows,float motion,const float* control) {
 try {
  vivo_aec::Exposure value;std::memcpy(&value,packet,16);
  std::vector<vivo_aec::BlurRow> blur;
  for(unsigned i=0;i<count;++i)blur.push_back({rows[i*3],rows[i*3+1],rows[i*3+2]});
  const std::vector<vivo_aec::TableRow> table={{p[0],uint64_t(p[2]),true},{p[1],uint64_t(p[3]),false}};
  auto out=vivo_aec::longExposure(value,control[0],control[1],control[2],table,
     {p[0],p[1],p[2],p[3],p[4],p[5],p[6],banding!=0,active!=0,mode},blur,motion);
  std::memcpy(packet,&out,16);return 0;
 } catch(const std::invalid_argument&){return -1;}
}

'''
def main():
 ap=argparse.ArgumentParser();ap.add_argument('library',type=Path);args=ap.parse_args()
 assert hashlib.sha256(args.library.read_bytes()).hexdigest()==SHA
 u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,0x800000);u.mem_map(0x1000000,0x1000000)
 with args.library.open('rb') as f:
  for seg in ELFFile(f).iter_segments():
   if seg['p_type']=='PT_LOAD':u.mem_write(seg['p_vaddr'],seg.data())
 obj,exposure,factor,table,rows,blur,blurRows,flags,motion,context,vtable,sensor=[0x1100000+i*0x10000 for i in range(12)]
 stop,logger,sensorCall=0x7ff000,0x7ff100,0x7ff200
 def q(p,x):u.mem_write(p,struct.pack('<Q',x))
 def f(p,x):u.mem_write(p,struct.pack('<f',x))
 q(0x1f9ec0,0x11c0000);q(0x11c0000,logger)
 q(obj+0x550,context);q(context,vtable);q(vtable+0xc0,sensorCall)
 q(obj+0x600,flags);q(obj+0x5f8,motion);q(table+0x10,rows);q(blur+8,blurRows)
 redirects={0x1ead10:0x17a464,0x1eac30:0x177a9c,0x1eac10:0x1774d4,0x1ead20:0x17a5fc}
 def hook(uc,address,size,data):
  if address in redirects:
   uc.reg_write(UC_ARM64_REG_PC,redirects[address]);return
  if address in (0x1e8a10,logger,sensorCall):
   uc.reg_write(UC_ARM64_REG_X0,sensor if address==sensorCall else 0)
   uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
 u.hook_add(UC_HOOK_CODE,hook)
 root=Path(__file__).resolve().parents[1];rng=random.Random(2454);total=0
 with tempfile.TemporaryDirectory(prefix='ae-adjust-') as tmp:
  d=Path(tmp);(d/'driver.cpp').write_text(DRIVER)
  subprocess.run(['c++','-std=c++17','-O2','-ffp-contract=off','-shared','-fPIC','-Wall','-Wextra','-Werror',
                 '-I',str(root/'app/src/main/cpp'),str(d/'driver.cpp'),'-o',str(d/'driver.so')],check=True)
  lib=ctypes.CDLL(str(d/'driver.so'))
  lib.adjust.argtypes=[ctypes.POINTER(ctypes.c_ubyte),ctypes.POINTER(ctypes.c_float),
                       ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_uint,ctypes.POINTER(ctypes.c_float),ctypes.c_float]
  lib.longer.argtypes=lib.adjust.argtypes+[ctypes.POINTER(ctypes.c_float)]
  def run(value,params,banding,active,mode,curve,speed,valid=True,longControl=None):
   nonlocal total
   p=(ctypes.c_float*7)(*params);b=(ctypes.c_float*(len(curve)*3))(*[x for row in curve for x in row])
   initial=struct.pack('<3fIf',*value[:3],0x12345678,value[3]);out=(ctypes.c_ubyte*20).from_buffer_copy(initial)
   status=(lib.adjust(out,p,banding,active,mode,len(curve),b,speed) if longControl is None else
           lib.longer(out,p,banding,active,mode,len(curve),b,speed,(ctypes.c_float*3)(*longControl)))
   if not valid:
    assert status==-1 and bytes(out)==initial;return
   assert status==0,(value,params,curve,speed)
   u.mem_write(exposure-16,b'\xa5'*16+initial[:16]+b'\xa5'*16)
   u.mem_write(factor-16,b'\xa5'*16+initial[16:]+b'\xa5'*16)
   u.mem_write(table,struct.pack('<fI',1. if longControl is None else longControl[2],2));f(table+24,p[5])
   u.mem_write(rows,struct.pack('<fIQII',p[0],0,int(p[2]),1,0)+struct.pack('<fIQII',p[1],0,int(p[3]),0,0))
   u.mem_write(blur,struct.pack('<I',len(curve)));u.mem_write(blurRows,bytes(b))
   u.mem_write(flags+0x28,bytes([banding,active]));u.mem_write(obj+0x61c,struct.pack('<I',0))
   f(obj+0x5f0,p[4]);f(motion+4,speed);f(sensor+8,p[6])
   for reg,val in zip((UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X2,UC_ARM64_REG_X3,UC_ARM64_REG_X4,UC_ARM64_REG_X5),
                      (obj,exposure,factor,table,blur,mode)):u.reg_write(reg,val)
   u.reg_write(UC_ARM64_REG_SP,0x1ff0000);u.reg_write(UC_ARM64_REG_LR,stop)
   address=0x17a5fc
   if longControl is not None:
    address=0x17a238
    u.reg_write(UC_ARM64_REG_X2,table);u.reg_write(UC_ARM64_REG_X3,blur)
    u.reg_write(UC_ARM64_REG_S0,struct.unpack('<I',struct.pack('<f',longControl[0]))[0])
    u.reg_write(UC_ARM64_REG_S1,struct.unpack('<I',struct.pack('<f',longControl[1]))[0])
   u.emu_start(address,stop,count=200000)
   assert u.reg_read(UC_ARM64_REG_PC)==stop,'donor did not terminate'
   expected=bytes(u.mem_read(exposure,16))+bytes(u.mem_read(factor,4))
   assert bytes(out)==expected,(total,value,params,banding,active,mode,curve,speed,bytes(out).hex(),expected.hex())
   for a,n in ((exposure,16),(factor,4)):
    assert bytes(u.mem_read(a-16,16))+bytes(u.mem_read(a+n,16))==b'\xa5'*32
   total+=1
  curve=[(1.e6,.0001,1.43),(1.e8,.2,8.),(1.e9,1.,80.),(1.e12,2.,1921.)]
  for i in range(1200):
   period=rng.choice([0.,8333333.,10000000.]);speed=rng.choice([0.,1.e-7,1.e-6,.1,1.,20.])
   params=[1.43,1921.,41245.,1000000000.,period,rng.choice([0.,.5,.97,1.]),.8]
   value=[rng.uniform(100000.,100000000.),rng.uniform(1.43,128.),rng.choice([0.,-4.,4.]),rng.choice([.1,.5,1.,1.5])]
   run(value,params,i%2,(i//2)%2,i%5,curve,speed)
  for target in (5.e5,1.e6,5.e7,1.e8,5.e8,1.e9,1.e12):
   for speed in (0.,1.e-7,1.e-6,1.):
    run([1000000.,target/1000000.,2.,1.],[1.43,1921.,41245.,1000000000.,0.,.97,.8],0,1,0,curve,speed)
  run([1000000.,1.,0.,1.],[1.e-7,1921.,1.,1000000000.,10000000.,.97,.8],0,0,1,curve,0.)
  for i in range(800):
   params=[1.43,1921.,41245.,1000000000.,rng.choice([0.,8333333.,10000000.]),.97,.8]
   run([rng.uniform(1.e5,1.e8),rng.uniform(1.43,128.),4.,1.],params,i%2,(i//2)%2,0,curve,
       rng.choice([0.,.1,1.,20.]),longControl=[rng.choice([1.,2.,4.,16.]),rng.choice([1.e7,1.e8,1.e9]),rng.choice([.5,1.,2.])])
  for speed in (-1.,float('nan'),float('inf')):
   run([1000000.,2.,0.,1.],[1.43,1921.,41245.,1000000000.,0.,.97,.8],0,1,0,curve,speed,False)
  for bad in ([],[(1.,0.,2.)],[(2.,1.,2.),(1.,2.,3.)]):
   run([1000000.,2.,0.,1.],[1.43,1921.,41245.,1000000000.,0.,.97,.8],0,1,0,bad,0.,False)
 print(f'PASS: {total} complete ARM64 adjustment/long-exposure comparisons, including active blur and banding; malformed motion/table rejected')
 print('Native motion provenance and Camera2 scheduling are outside this arithmetic test.')
if __name__=='__main__':main()
