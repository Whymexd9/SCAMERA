#!/usr/bin/env python3
"""Emulate only the supplied stock HC preprocessing kernel, never its network.
Usage: python tools/check_vivo_preprocess.py /path/to/libremosaiclib_s5khp3.so
Requires unicorn, pyelftools, numpy. Pins the examined firmware hash.
Profiler/thread hooks are replaced with serial no-ops; arithmetic is ARM64 code.
This verifies a 16-channel legacy kernel, NOT the missing HP9 4x/18-channel bridge.
"""
import sys, hashlib
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE
from unicorn.arm64_const import *
from elftools.elf.elffile import ELFFile
from pathlib import Path
import struct,numpy as np
assert len(sys.argv) == 2
assert hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() == '7495113303fb01cff07434eb9896253774846a7b2c5ff1e13017654c884c3aa5'
f=open(sys.argv[1],'rb');e=ELFFile(f);u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,0x3800000)
for p in e.iter_segments():
 if p['p_type']=='PT_LOAD':u.mem_write(p['p_vaddr'],p.data())
sym=e.get_section_by_name('.dynsym');lookup={s.name:s['st_value'] for s in sym.iter_symbols()}
for r in e.get_section_by_name('.rela.dyn').iter_relocations():
 if r['r_info_type'] in (1027,1025,257):u.mem_write(r['r_offset'],struct.pack('<Q',sym.get_symbol(r['r_info_sym'])['st_value']+r['r_addend']))
plt=e.get_section_by_name('.plt')['sh_addr'];stubs={}
for i,r in enumerate(e.get_section_by_name('.rela.plt').iter_relocations()):
 s=sym.get_symbol(r['r_info_sym']);a=plt+32+i*16
 if s['st_value']:u.mem_write(a,struct.pack('<I',0x14000000|(((s['st_value']-a)//4)&0x3ffffff)))
 else:stubs[a]=s.name
u.mem_map(0x10000000,0x1000000);heap=0x10010000
u.reg_write(UC_ARM64_REG_SP,0x10fff000);u.reg_write(UC_ARM64_REG_TPIDR_EL0,0x10000000)
u.reg_write(UC_ARM64_REG_CPACR_EL1,3<<20)
def alloc(data):
 global heap
 a=heap;heap=(heap+len(data)+15)&~15;u.mem_write(a,data);return a
noops=['halide_profiler_pipeline_start','halide_profiler_pipeline_end','halide_profiler_memory_allocate','halide_profiler_memory_free','halide_profiler_stack_peak_update','halide_profiler_incr_active_threads','halide_profiler_decr_active_threads']
# Stub profiler routines even when defined: no threads in this offline arithmetic check.
for n in noops+['halide_profiler_get_state','halide_do_par_for']:
 if lookup.get(n):stubs[lookup[n]]=n
pending=[]
def hook(uc,a,size,data):
 if a==0x10000080:
  fun,y,end,closure,lr,sp=pending[-1];y+=1
  if y<end:
   pending[-1]=(fun,y,end,closure,lr,sp);uc.reg_write(UC_ARM64_REG_X0,0);uc.reg_write(UC_ARM64_REG_X1,y);uc.reg_write(UC_ARM64_REG_X2,closure);uc.reg_write(UC_ARM64_REG_LR,a);uc.reg_write(UC_ARM64_REG_PC,fun)
  else:
   pending.pop();uc.reg_write(UC_ARM64_REG_SP,sp);uc.reg_write(UC_ARM64_REG_X0,0);uc.reg_write(UC_ARM64_REG_PC,lr)
  return
 if a not in stubs:return
 n=stubs[a];args=[uc.reg_read(UC_ARM64_REG_X0+i) for i in range(6)];res=0;lr=uc.reg_read(UC_ARM64_REG_LR)
 if n=='halide_profiler_get_state':res=0x10000100
 elif n in noops or n=='free':pass
 elif n=='malloc':res=alloc(bytes(args[0]))
 elif n=='__android_log_print':
  print('LOG',bytes(uc.mem_read(args[3],300)).split(b'\0')[0])
 elif n=='halide_do_par_for':
  _,fun,y,count,closure=args[:5];pending.append((fun,y,y+count,closure,lr,uc.reg_read(UC_ARM64_REG_SP)));uc.reg_write(UC_ARM64_REG_X0,0);uc.reg_write(UC_ARM64_REG_X1,y);uc.reg_write(UC_ARM64_REG_X2,closure);uc.reg_write(UC_ARM64_REG_LR,0x10000080);uc.reg_write(UC_ARM64_REG_PC,fun);return
 elif n in ['memcpy','memmove']:uc.mem_write(args[0],bytes(uc.mem_read(args[1],args[2])));res=args[0]
 elif n=='memset':uc.mem_write(args[0],bytes([args[1]&255])*args[2]);res=args[0]
 else:raise RuntimeError((n,args,hex(lr)))
 uc.reg_write(UC_ARM64_REG_X0,res);uc.reg_write(UC_ARM64_REG_PC,lr)
u.hook_add(UC_HOOK_CODE,hook)
def buf(host,typ,dim):
 dp=alloc(b''.join(struct.pack('<iiii',*d,0) for d in dim));return alloc(struct.pack('<QQQQiiQQ',0,0,host,0,typ,len(dim),dp,0))
w=h=64
raw=(np.arange(w*h,dtype=np.uint16) % 1024).reshape(h,w);inp=alloc(raw.tobytes());out=alloc(bytes(w*h*4));ib=buf(inp,0x11001,[(0,w,1),(0,h,w)]);ob=buf(out,0x12002,[(0,16,1),(0,w//4,16),(0,h//4,w*4)])
for i,v in enumerate([ib,0,0,1,1,ob]):u.reg_write(UC_ARM64_REG_X0+i,v)
u.reg_write(UC_ARM64_REG_LR,0x10000040)
try:u.emu_start(lookup['bayer_preproc_hc_v3'],0x10000040,count=1000000)
except Exception as ex:print(ex,'PC',hex(u.reg_read(UC_ARM64_REG_PC)));raise
assert u.reg_read(UC_ARM64_REG_PC) == 0x10000040, "Instruction budget exhausted"
assert u.reg_read(UC_ARM64_REG_X0) == 0
out_array=np.frombuffer(u.mem_read(out,w*h*4),np.float32).reshape(h//4,w//4,16)
phase=[(0,0),(0,1),(1,0),(1,1),(0,2),(0,3),(1,2),(1,3),
       (2,0),(2,1),(3,0),(3,1),(2,2),(2,3),(3,2),(3,3)]
expected=np.stack([np.sqrt(raw[y::4,x::4].astype(np.float32)/1023.0) for y,x in phase],axis=-1)
np.testing.assert_allclose(out_array,expected,rtol=2e-6,atol=1e-7)
print("Stock ARM64 legacy HC packing matches sqrt(raw/1023), all 4096 samples")
