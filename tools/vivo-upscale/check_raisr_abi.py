#!/usr/bin/env python3
"""Execute the pinned vendor parameter validator only. No image inference.
Requires pyelftools and unicorn. Usage: check_raisr_abi.py /path/libvivo_raisr.so
"""
import hashlib,io,json,struct,sys
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM
from unicorn.arm64_const import UC_ARM64_REG_X0,UC_ARM64_REG_SP,UC_ARM64_REG_LR
manifest=json.loads(Path(__file__).with_name('manifest.json').read_text())
expected=next(x['sha256'] for x in manifest['files'] if x['path']=='vendor/lib64/libvivo_raisr.so')
b=Path(sys.argv[1]).read_bytes()
if hashlib.sha256(b).hexdigest()!=expected:raise ValueError('Unverified binary; offsets are version-specific')
e=ELFFile(io.BytesIO(b));segments=[s for s in e.iter_segments() if s['p_type']=='PT_LOAD']
end=max(s['p_vaddr']+s['p_memsz'] for s in segments)
cases=[('valid',{},0),('invalid width',{4:0},3),('invalid crop type',{0x10:3},3),('invalid disable',{0x134:2},3),('invalid output width',{0xc:0},3)]
for label,overrides,expected_rc in cases:
 u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,(end+4095)//4096*4096)
 for s in segments:u.mem_write(s['p_vaddr'],s.data())
 u.mem_map(0x10000000,0x10000)
 params=bytearray(0x160)
 values={0:480,4:640,8:960,12:1280,0x10:2,0x130:0,0x134:0,**overrides}
 for offset,value in values.items():struct.pack_into('<I',params,offset,value)
 u.mem_write(0x10001000,bytes(params));u.reg_write(UC_ARM64_REG_X0,0x10001000)
 u.reg_write(UC_ARM64_REG_SP,0x1000f000);u.reg_write(UC_ARM64_REG_LR,0x10000000)
 u.emu_start(0x9b54,0x10000000,count=10000)
 rc=u.reg_read(UC_ARM64_REG_X0)
 if rc!=expected_rc:raise AssertionError((label,rc,expected_rc))
 print('PASS',label,rc)
print('Only parameter validation tested; init/process not executed.')

# Execute native SAT-role/path construction up to the XML-parser boundary.
# libc string calls are emulated; XML parsing and filter loading are not executed.
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import UC_ARM64_REG_X1,UC_ARM64_REG_PC
for role,profile in [(2,'raisr_rear_master'),(8,'raisr_rear_tele_3x')]:
 u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,(end+4095)//4096*4096)
 for s in segments:u.mem_write(s['p_vaddr'],s.data())
 u.mem_map(0x10000000,0x10000);params=bytearray(0x160)
 for off,val in {0:480,4:640,8:960,12:1280,0x10:2,0x28:role}.items():struct.pack_into('<I',params,off,val)
 base=b'/vendor/camera3rd/nti/raisr';params[0x2c:0x2c+len(base)]=base
 u.mem_write(0x10001000,bytes(params));u.reg_write(UC_ARM64_REG_X0,0x10001000)
 u.reg_write(UC_ARM64_REG_SP,0x1000f000);u.reg_write(UC_ARM64_REG_LR,0x10000000)
 seen=[]
 def string(addr):
  data=bytearray()
  while len(data)<4096:
   c=u.mem_read(addr+len(data),1)[0]
   if not c:return bytes(data)
   data.append(c)
  raise ValueError('String too long')
 def hook(uc,addr,length,user):
  a=uc.reg_read(UC_ARM64_REG_X0);b=uc.reg_read(UC_ARM64_REG_X1)
  if addr==0x84f0:ret=len(string(a)) # strlen
  elif addr in (0x84e0,0x84b0): # strcpy/strcat
   dst=a+(len(string(a)) if addr==0x84b0 else 0);uc.mem_write(dst,string(b)+b'\0');ret=a
  elif addr==0x8eb8:seen.append(string(b).decode());ret=3 # stop before parser
  else:return
  uc.reg_write(UC_ARM64_REG_X0,ret);uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
 u.hook_add(UC_HOOK_CODE,hook);u.emu_start(0x96d8,0x10000000,count=10000)
 assert seen==['/vendor/camera3rd/nti/raisr/'+profile+'/raisr_param.xml'],seen
 print('PASS native role/path',role,profile)
