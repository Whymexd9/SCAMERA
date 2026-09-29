#!/usr/bin/env python3
import argparse, ctypes, hashlib, random, struct, subprocess, tempfile
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import *

SHA='137ac72b364f280d993744cb06cf4e6f297bbe45e1aa3248a3560eafc1ac4020'
DRIVER=r'''
#include "vivo-camx-ae-plan.h"
#include <cstring>
extern "C" int resolve(float code,const void* data,uint64_t ns,float gain,float* output) {
 std::array<vivo_aec::Exposure,6> values;
 std::memcpy(values.data(),data,sizeof(values));
 try {
  const auto out=vivo_aec::resolveAlternateExposure(code,values,ns,gain);
  output[0]=out.shutterMs;output[1]=out.gain;output[2]=out.ev;
  return out.solverSlot;
 } catch(const std::invalid_argument&) { return -2; }
}
extern "C" int slot(float code){return vivo_aec::alternateExposureSlot(code);}
'''
def main():
 ap=argparse.ArgumentParser();ap.add_argument('library',type=Path);args=ap.parse_args()
 assert hashlib.sha256(args.library.read_bytes()).hexdigest()==SHA
 u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,0xc00000);u.mem_map(0x1000000,0x1000000)
 with args.library.open('rb') as file:
  elf=ELFFile(file)
  for segment in elf.iter_segments():
   if segment['p_type']=='PT_LOAD':u.mem_write(segment['p_vaddr'],segment.data())
 obj,codep,table,base,out,stack=0x1100000,0x1110000,0x1120000,0x1130000,0x1140000,0x1ff0000
 u.mem_write(obj,struct.pack('<Q',codep))
 u.mem_write(stack-0x68,struct.pack('<Q',table));u.mem_write(stack-0x4c,struct.pack('<f',1.e6))
 def execute(start,end):
  u.emu_start(start,end,count=300)
  assert u.reg_read(UC_ARM64_REG_PC)==end
 def bits(v):return struct.unpack('<I',struct.pack('<f',v))[0]
 rng=random.Random(245421);root=Path(__file__).resolve().parents[1]
 with tempfile.TemporaryDirectory() as tmp:
  tmp=Path(tmp);(tmp/'driver.cpp').write_text(DRIVER)
  subprocess.run(['c++','-std=c++17','-O2','-ffp-contract=off','-shared','-fPIC','-Wall','-Wextra','-Werror','-I',str(root/'app/src/main/cpp'),str(tmp/'driver.cpp'),'-o',str(tmp/'driver.so')],check=True)
  lib=ctypes.CDLL(str(tmp/'driver.so'));lib.slot.argtypes=[ctypes.c_float]
  lib.resolve.argtypes=[ctypes.c_float,ctypes.c_void_p,ctypes.c_uint64,ctypes.c_float,ctypes.POINTER(ctypes.c_float)]
  for code in [0.,-100.,-200.,100.,101.,102.,1.,-1.,1.e-8,2**-23,float('nan'),float('inf')]:
   u.mem_write(codep,struct.pack('<f',code))
   u.reg_write(UC_ARM64_REG_X19,obj);u.reg_write(UC_ARM64_REG_X26,0);u.reg_write(UC_ARM64_REG_S8,bits(2**-23))
   execute(0x8d25d4,0x8d264c)
   expected=ctypes.c_int32(u.reg_read(UC_ARM64_REG_W28)).value
   assert lib.slot(code)==expected,(code,expected)
  for trial in range(500):
   code=[0.,-100.,-200.,100.,101.][trial%5]
   payload=b''.join(struct.pack('<3fI',rng.uniform(1.e4,2.e8),rng.uniform(1.,64.),rng.uniform(-8.,4.),rng.randrange(2)) for _ in range(6))
   ns=rng.randrange(10000,200000000);gain=rng.uniform(1.,32.)
   source=ctypes.create_string_buffer(payload);result=(ctypes.c_float*3)()
   slot=lib.resolve(code,source,ns,gain,result)
   u.mem_write(table,payload);u.mem_write(base,struct.pack('<Qf',ns,gain));u.mem_write(out+0x3c,bytes(4))
   for reg,val in [(UC_ARM64_REG_X29,stack),(UC_ARM64_REG_X20,out),(UC_ARM64_REG_X27,base),(UC_ARM64_REG_W28,slot&0xffffffff),(UC_ARM64_REG_S8,bits(2**-23)),(UC_ARM64_REG_S14,bits(code))]:u.reg_write(reg,val)
   execute(0x8d2800 if slot>=0 else 0x8d28ac,0x8d2ab0)
   expected=b''.join(struct.pack('<I',u.reg_read(r)) for r in [UC_ARM64_REG_S9,UC_ARM64_REG_S10,UC_ARM64_REG_S15])
   assert bytes(result)==expected,(trial,list(result),struct.unpack('<3f',expected))
  for code in [102.,999.,float('nan')]:
   result=(ctypes.c_float*3)(7.,8.,9.);initial=bytes(result)
   assert lib.resolve(code,source,ns,gain,result)==-2 and bytes(result)==initial
 print('PASS: HAL code dispatch and 500 direct-table/echo resolutions match original ARM64 bytes')
 print('Pro RAW arbitration, full HAL initialization and measured sensor delivery are not emulated.')
if __name__=='__main__':main()
