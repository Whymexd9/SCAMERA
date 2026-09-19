"""Execute the pinned APK ARM64 JNI binary on synthetic RAW with Unicorn."""
import struct
from pathlib import Path
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import *
from elftools.elf.elffile import ELFFile

LIB=Path(__file__).resolve().parents[2]/'app/src/main/jniLibs/arm64-v8a/libmobileRemosaic.so'
class Engine:
 def __init__(self):
  self.u=Uc(UC_ARCH_ARM64,UC_MODE_ARM); u=self.u
  u.mem_map(0x10000,0x80000);u.mem_map(0x100000,0x4000000);self.heap=0x200000
  with LIB.open('rb') as f:
   e=ELFFile(f)
   # ELF's first segment begins at zero, map it separately.
   u.mem_map(0,0x10000)
   for s in e.iter_segments():
    if s['p_type']=='PT_LOAD':u.mem_write(s['p_vaddr'],s.data())
   sym=e.get_section_by_name('.dynsym');self.plt={}
   for i,r in enumerate(e.get_section_by_name('.rela.plt').iter_relocations()):
    symbol=sym.get_symbol(r['r_info_sym']);self.plt[0x5cc60+16*i]=symbol.name
    if symbol['st_value']:u.mem_write(r['r_offset'],struct.pack('<Q',symbol['st_value']))
   for r in e.get_section_by_name('.rela.dyn').iter_relocations():
    if r['r_info_type']==1027:u.mem_write(r['r_offset'],struct.pack('<Q',r['r_addend']))
  self.env=0x100000;self.table=0x101000;self.stop=0x102000
  u.mem_write(self.env,struct.pack('<Q',self.table))
  self.funcs={}
  for n,off in enumerate([0x558,0x568,0x730,0xb8,0x548,0x550]):
   addr=0x103000+n*4;self.funcs[addr]=off;u.mem_write(self.table+off,struct.pack('<Q',addr))
  u.reg_write(UC_ARM64_REG_TPIDR_EL0,0x104000)
  u.reg_write(UC_ARM64_REG_CPACR_EL1,3<<20)
  u.hook_add(UC_HOOK_CODE,self.hook,begin=0x103000,end=0x103100)
  u.hook_add(UC_HOOK_CODE,self.hook,begin=0x5cc60,end=0x5cd20)
  self.arr=[]
 def alloc(self,b):
  if isinstance(b,int):b=b'\0'*b
  p=self.heap;self.heap+=(len(b)+15)&~15;self.u.mem_write(p,b);return p
 def hook(self,u,addr,size,_):
  x=[u.reg_read(UC_ARM64_REG_X0+i) for i in range(3)]
  if addr in self.funcs:
   off=self.funcs[addr]
   ret={0x558:lambda:len(self.arr),0x568:lambda:self.arr[x[2]],0x730:lambda:x[1],0xb8:lambda:0,0x548:lambda:x[1],0x550:lambda:0}[off]()
  else:
   if addr not in self.plt:return
   name=self.plt[addr]
   if name.startswith('_ZN'):return
   if name=='_Znwm':ret=self.alloc(x[0])
   elif name=='memset':u.mem_write(x[0],bytes([x[1]&255])*x[2]);ret=x[0]
   elif name in ['memmove','memcpy']:u.mem_write(x[0],bytes(u.mem_read(x[1],x[2])));ret=x[0]
   elif name=='strlen':
    ret=0
    while bytes(u.mem_read(x[0]+ret,1))!=b'\0':ret+=1
   elif name in ['_ZdlPv','__android_log_print']:ret=0
   else:raise RuntimeError(name)
  u.reg_write(UC_ARM64_REG_X0,ret);u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))
 def process(self,frames,w,h,block,cfa):
  self.arr=[self.alloc(struct.pack('<'+'H'*len(a),*a)) for a in frames]
  out=self.alloc(w*h*2);cfaPtr=self.alloc(cfa.encode()+b'\0');sp=0x4000000
  for i,v in enumerate([self.env,0,0,w,h,block,cfaPtr,len(frames)]):self.u.reg_write(UC_ARM64_REG_X0+i,v)
  self.u.reg_write(UC_ARM64_REG_S0,0)
  for r in [UC_ARM64_REG_S1,UC_ARM64_REG_S2]:self.u.reg_write(r,0x3f800000)
  self.u.mem_write(sp,struct.pack('<QQ',0,out));self.u.reg_write(UC_ARM64_REG_SP,sp);self.u.reg_write(UC_ARM64_REG_LR,self.stop)
  try:self.u.emu_start(0x1fd30,self.stop,timeout=30_000_000)
  except Exception:
   print('PC',hex(self.u.reg_read(UC_ARM64_REG_PC)),'LR',hex(self.u.reg_read(UC_ARM64_REG_LR)));raise
  assert self.u.reg_read(UC_ARM64_REG_PC)==self.stop,'timeout'
  assert self.u.reg_read(UC_ARM64_REG_X0)==1,'native failed'
  return struct.unpack('<'+'H'*(w*h),self.u.mem_read(out,w*h*2))
if __name__=='__main__':
 import random, hashlib
 assert hashlib.sha256(LIB.read_bytes()).hexdigest()=='efeb6894c95bd5b515ecf8894ffeed3729282346621be46f9b470d9e334d27b7'
 w=h=176
 for block in [1,2,4]:
  for cfa in ['RGGB','GRBG','GBRG','BGGR']:
   e=Engine();values={'R':1200,'G':2200,'B':3200}
   frame=[values[cfa[((y//block)%2)*2+(x//block)%2]] for y in range(h) for x in range(w)]
   out=e.process([frame]*3,w,h,block,cfa)
   expected=[values[cfa[(y%2)*2+x%2]] for y in range(h) for x in range(w)]
   assert max(abs(a-b) for a,b in zip(out,expected))<=1,(block,cfa)
  print('CFA preservation: block',block,'all 4 layouts passed',flush=True)
 # Single donors are real one-frame reconstructions, not duplicated fake bursts.
 for block in [1,2,4]:
  for signal in [400,1600,3200]:
   frame=[signal]*(w*h)
   out=Engine().process([frame],w,h,block,'BGGR')
   assert max(abs(v-signal) for v in out)<=1,(block,signal)
 print('Single-frame bracket donors: Bayer/Quad/Tetra and 3 exposure levels passed',flush=True)
 rng=random.Random(193)
 frames=[[2000+rng.randrange(-15,16) for _ in range(w*h)] for f in range(7)]
 out=Engine().process(frames,w,h,1,'RGGB')
 before=sum((v-2000)**2 for v in frames[3])/len(out)
 after=sum((v-2000)**2 for v in out)/len(out)
 assert after<before*.75,(before,after)
 print('Bayer temporal noise variance',before,'->',after,flush=True)
 e=Engine();e.arr=[e.alloc(struct.pack('<'+'H'*(w*h),*[64+(8 if i%2 else -8)+delta for i in range(w*h)])) for delta in [0,1,2,100]]
 out=e.alloc(w*h*2);sp=0x4000000
 for i,v in enumerate([e.env,0,0,w,h,out]):e.u.reg_write(UC_ARM64_REG_X0+i,v)
 e.u.reg_write(UC_ARM64_REG_SP,sp);e.u.reg_write(UC_ARM64_REG_LR,e.stop)
 e.u.emu_start(0x22a44,e.stop,timeout=30_000_000)
 assert e.u.reg_read(UC_ARM64_REG_PC)==e.stop and e.u.reg_read(UC_ARM64_REG_X0)==1
 residuals=struct.unpack('<'+'h'*(w*h),e.u.mem_read(out,w*h*2))
 assert set(residuals)=={-16,0},set(residuals)
 print('Signed FPN median/outlier test passed',flush=True)
