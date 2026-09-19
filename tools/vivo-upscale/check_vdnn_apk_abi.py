#!/usr/bin/env python3
"""Execute pinned ARM64 vdnnPlatformInitV2, including its parser and platform map.
Emulate libc/string operations and integer stream extraction. Capture createEngine
configuration instead of loading QNN. No inference is performed.
Usage: check_vdnn_apk_abi.py /path/libvdnn.so
"""
import sys,struct,io,hashlib,json
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import *
from unicorn.arm64_const import *
b=open(sys.argv[1],'rb').read()
manifest=json.loads(Path(__file__).with_name('manifest.json').read_text())
assert hashlib.sha256(b).hexdigest()==next(x['sha256'] for x in manifest['files'] if x['path']=='vendor/lib64/libvdnn.so'),'Unverified VDNN'
e=ELFFile(io.BytesIO(b));u=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
s=[s for s in e.iter_segments() if s['p_type']=='PT_LOAD'];u.mem_map(0,(max(s['p_vaddr']+s['p_memsz'] for s in s)+4095)//4096*4096)
for seg in s:u.mem_write(seg['p_vaddr'],seg.data())
u.mem_map(0x10000000,0x1000000);heap=0x10100000
r=e.get_section_by_name('.rela.plt');p=e.get_section_by_name('.plt');st=e.get_section(r['sh_link']);syms={p['sh_addr']+32+((p['sh_size']-32)//r.num_relocations())*i:st.get_symbol(x['r_info_sym']).name for i,x in enumerate(r.iter_relocations())}
def alloc(n):
 global heap
 ret=heap;heap+=(n+15)//16*16;return ret
def cstr(a):
 out=bytearray()
 while (v:=u.mem_read(a+len(out),1)[0]):out.append(v)
 return bytes(out)
def sstr(a):
 raw=bytes(u.mem_read(a,24))
 if raw[0]&1:
  n,ptr=struct.unpack_from('<QQ',raw,8);return bytes(u.mem_read(ptr,n))
 return raw[1:1+raw[0]//2]
def putstr(a,s):
 if len(s)<23:raw=bytes([len(s)*2])+s+b'\0'*(23-len(s))
 else:
  ptr=alloc(len(s)+1);u.mem_write(ptr,s+b'\0');raw=struct.pack('<QQQ',((len(s)+16)//16*16)|1,len(s),ptr)
 u.mem_write(a,raw)
cfg=b'PLATFORM:SM8750_2_28 APK:0 SIGNEDPD:0';putstr(0x10002000,cfg)
u.reg_write(UC_ARM64_REG_X0,0x10001000);u.reg_write(UC_ARM64_REG_X1,0x10002000);u.reg_write(UC_ARM64_REG_SP,0x100f0000);u.reg_write(UC_ARM64_REG_TPIDR_EL0,0x10003000);u.reg_write(UC_ARM64_REG_LR,0x10000000)
seen=[]
def hook(uc,addr,size,data):
 if addr==0x86324:
  u.reg_write(UC_ARM64_REG_X0,int(sstr(u.reg_read(UC_ARM64_REG_X0))));u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR));return
 if addr not in syms:return
 name=syms[addr];a=[u.reg_read(x) for x in (UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X2,UC_ARM64_REG_X3,UC_ARM64_REG_X4)];ret=0
 if name=='_Znwm':ret=alloc(a[0])
 elif name=='_ZdlPv':pass
 elif name=='__android_log_print':
  pass
 elif name=='__strrchr_chk':
  ix=cstr(a[0]).rfind(bytes([a[1]]));ret=0 if ix<0 else a[0]+ix
 elif name=='memcmp':
  x=bytes(u.mem_read(a[0],a[2]));y=bytes(u.mem_read(a[1],a[2]));ret=(x>y)-(x<y)
 elif name=='memcpy':u.mem_write(a[0],bytes(u.mem_read(a[1],a[2])));ret=a[0]
 elif name=='memmove':u.mem_write(a[0],bytes(u.mem_read(a[1],a[2])));ret=a[0]
 elif name=='memchr':
  ix=bytes(u.mem_read(a[0],a[2])).find(bytes([a[1]]));ret=0 if ix<0 else a[0]+ix
 elif name=='strlen':ret=len(cstr(a[0]))
 elif 'basic_string' in name and 'C1ERKS5_mm' in name:putstr(a[0],sstr(a[1])[a[2]:a[2]+a[3]]);ret=a[0]
 elif 'basic_string' in name and ('C1ERKS5_' in name or 'aSERKS5_' in name):putstr(a[0],sstr(a[1]));ret=a[0]
 elif 'basic_string' in name and '7compareEmmPKcm' in name:
  x=sstr(a[0])[a[1]:a[1]+a[2]];y=bytes(u.mem_read(a[3],a[4]));ret=(x>y)-(x<y)
 elif 'basic_string' in name and '6assignEPKcm' in name:putstr(a[0],bytes(u.mem_read(a[1],a[2])));ret=a[0]
 elif 'basic_string' in name and '6assignERKS5_mm' in name:putstr(a[0],sstr(a[1])[a[2]:a[2]+a[3]]);ret=a[0]
 elif 'basic_string' in name and '6appendEPKcm' in name:putstr(a[0],sstr(a[0])+bytes(u.mem_read(a[1],a[2])));ret=a[0]
 elif name=='vdnnNetCreate':ret=alloc(0x1a8)
 elif name=='_ZN4vdnn3Net12createEngineERKNS_6ConfigE':
  config=bytes(u.mem_read(a[1],0x150));seen.append((struct.unpack_from('<I',config)[0],config[0xe2],config[0x113]));ret=0x10009000
 elif 'stoi' in name:ret=int(sstr(a[0]),a[2])
 else:raise Exception('Unimplemented '+hex(addr)+' '+name+' '+str(a))
 u.reg_write(UC_ARM64_REG_X0,ret&0xffffffffffffffff);u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))
u.hook_add(UC_HOOK_CODE,hook)
try:u.emu_start(0x86b28,0x10000000,count=300000)
except Exception as ex:print('PC',hex(u.reg_read(UC_ARM64_REG_PC)));raise
assert u.reg_read(UC_ARM64_REG_PC)==0x10000000,'Instruction budget exceeded'
assert u.reg_read(UC_ARM64_REG_X0)==0 and seen==[(18,0,0)],seen
print('PASS original VDNN parser / platform map: modelType=18, APK=0, SIGNEDPD=0')
# Execute the original provider-version selection branch, stopping before table
# copying or error logging. No synthetic reimplementation of the comparison.
u.mem_write(0x10004000,struct.pack('<Q',0x10005000))
u.reg_write(UC_ARM64_REG_SP,0x10006000)
u.mem_write(0x1000600c,struct.pack('<I',1))
u.mem_write(0x10006010,struct.pack('<Q',0x10004000))
ends=[]
def selector_stop(uc,addr,size,data):
 if addr in (0x1e5bf0,0x1e5c78):ends.append(addr);uc.emu_stop()
hook_id=u.hook_add(UC_HOOK_CODE,selector_stop)
for major,minor,expected in [(2,18,False),(2,20,False),(2,21,True),(2,22,True),(3,22,False)]:
 u.mem_write(0x10005010,struct.pack('<III',major,minor,0));ends.clear()
 u.emu_start(0x1e5bb8,0x10000000,count=100)
 assert ends==[0x1e5bf0 if expected else 0x1e5c78],(major,minor,ends)
u.hook_del(hook_id)
print('PASS original VDNN QNN selector rejects Core 2.18/2.20, accepts 2.21/2.22')
