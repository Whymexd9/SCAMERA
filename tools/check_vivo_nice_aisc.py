#!/usr/bin/env python3
import argparse
import ctypes
import hashlib
import random
import struct
import subprocess
import tempfile
from pathlib import Path
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_S0, UC_ARM64_REG_PC, UC_ARM64_REG_LR
from check_vivo_nice_motion import emulator,invoke

ap=argparse.ArgumentParser();ap.add_argument('library',type=Path);args=ap.parse_args()
assert hashlib.sha256(args.library.read_bytes()).hexdigest()=='b49923bb8419dfc870d5a060ca5912807ad0342ae442f532fd64977be6a5f573'
u,_=emulator(args.library)
with args.library.open('rb') as f:
 e=ELFFile(f);syms=e.get_section_by_name('.dynsym')
 rels={r['r_offset']:syms.get_symbol(r['r_info_sym']).name for sec in e.iter_sections() if sec['sh_type']=='SHT_RELA' for r in sec.iter_relocations() if r['r_info_sym']}
 md=Cs(CS_ARCH_ARM64,CS_MODE_ARM);md.detail=True
 for address,expected in [(0x5560,'vdnnTensorGetHostBuffer'),(0x54a0,'expf'),(0x5630,'strcpy')]:
  ins=list(md.disasm(bytes(u.mem_read(address,16)),address))
  assert rels[ins[0].operands[1].imm+ins[1].operands[1].mem.disp]==expected
libm=ctypes.CDLL('libm.so.6');libm.expf.argtypes=[ctypes.c_float];libm.expf.restype=ctypes.c_float

def external(uc,addr,size,data):
 if addr==0x5560:pass  # Test tensor handles are already their host buffers.
 elif addr==0x54a0:
  x=struct.unpack('<f',struct.pack('<I',uc.reg_read(UC_ARM64_REG_S0)))[0]
  uc.reg_write(UC_ARM64_REG_S0,struct.unpack('<I',struct.pack('<f',libm.expf(x)))[0])
 else:return
 uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
for a in [0x5560,0x54a0]:u.hook_add(UC_HOOK_CODE,external,begin=a,end=a)
# strcpy has no scene logic and is not implemented by the shared harness.
from unicorn.arm64_const import UC_ARM64_REG_X1
def copy(uc,addr,size,data):
 dst=uc.reg_read(UC_ARM64_REG_X0);src=uc.reg_read(UC_ARM64_REG_X1);i=0
 while True:
  b=bytes(uc.mem_read(src+i,1));uc.mem_write(dst+i,b);i+=1
  if b==b'\0':break
 uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
u.hook_add(UC_HOOK_CODE,copy,begin=0x5630,end=0x5630)
obj,tensors,scene,light,out=0x1100000,0x1200000,0x1300000,0x1400000,0x1500000
u.mem_write(obj+0x250,struct.pack('<Q',tensors));u.mem_write(tensors,struct.pack('<QQQ',scene,0,light))
root=Path(__file__).resolve().parents[1]
driver='''#include "vivo-nice-aisc.h"
extern "C" int decode(const float* a,const float* b,int soft,void* out) {
 try {std::array<float,7> s;std::array<float,2> l;
 std::memcpy(s.data(),a,28);std::memcpy(l.data(),b,8);
 auto r=vivo_nice::decodeAisc(s,l,soft);std::memcpy(out,&r,sizeof(r));return 0;
 } catch(const std::invalid_argument&) {return -1;}
}
extern "C" bool backlight(float score) {vivo_nice::AiscResult r{};r.count=7;r.backlight=score;return vivo_nice::aiscBacklight(r);}
'''
rng=random.Random(2454);cases=[]
for threshold in (.75,0,.65,.75,.5,.78,.85):
 for index in range(7):
  for delta in (-.00001,0.,.00001):
   v=[-1.]*7;v[index]=threshold+delta;cases.append((v,[.3,.7],False))
cases += [([v]*7,[.3,.7],False) for v in (-1.,0.,.5,1.)]
for soft in (False,True):
 for _ in range(500):cases.append(([rng.uniform(-8,8) for _ in range(7)],[rng.uniform(-8,8),rng.uniform(-8,8)],soft))
with tempfile.TemporaryDirectory() as tmp:
 d=Path(tmp);(d/'driver.cpp').write_text(driver)
 subprocess.run(['c++','-std=c++17','-Wall','-Wextra','-Werror','-O2','-shared','-fPIC','-I',str(root/'app/src/main/cpp'),str(d/'driver.cpp'),'-o',str(d/'driver.so')],check=True)
 lib=ctypes.CDLL(str(d/'driver.so'));lib.decode.argtypes=[ctypes.c_void_p,ctypes.c_void_p,ctypes.c_int,ctypes.c_void_p]
 for s,l,soft in cases:
  u.mem_write(scene,struct.pack('<7f',*s));u.mem_write(light,struct.pack('<2f',*l));u.mem_write(obj+8,bytes([soft]));u.mem_write(out,bytes(324))
  invoke(u,0x7924,(obj,out))
  actual=ctypes.create_string_buffer(324)
  assert lib.decode((ctypes.c_float*7)(*s),(ctypes.c_float*2)(*l),soft,actual)==0
  expected=bytes(u.mem_read(out,324));assert actual.raw==expected,(s,l,soft)
 for value in (float('nan'),float('inf'),1000.,-1000.):
  result=ctypes.create_string_buffer(b'X'*324,324)
  assert lib.decode((ctypes.c_float*7)(*[value]*7),(ctypes.c_float*2)(0,0),1,result)==-1
  assert result.raw==b'X'*324
 lib.backlight.argtypes=[ctypes.c_float];lib.backlight.restype=ctypes.c_bool
 assert not lib.backlight(.7) and lib.backlight(.70001)
print('PASS:',len(cases),'original AISC postprocess comparisons, all 324 bytes; invalid outputs rejected')
print('Network inference and camera integration are not exercised.')
