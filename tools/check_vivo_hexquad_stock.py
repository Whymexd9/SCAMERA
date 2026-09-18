#!/usr/bin/env python3
"""Reproduce the stock HP9 normal-exposure LUTs (requires unicorn, pyelftools).
Only the pinned library is accepted; no NPU is emulated. Outputs stay local.
"""
import struct,hashlib
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import *
from unicorn.arm64_const import *
import argparse
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--library',type=Path,required=True)
parser.add_argument('--output-dir',type=Path,required=True)
args=parser.parse_args();args.output_dir.mkdir(parents=True,exist_ok=True)
p=args.library
assert hashlib.sha256(p.read_bytes()).hexdigest()=='41b277753f7fedbe4dda1e1c4b76d2086d6e79768904d8a4922b48a0e023e76e'
f=p.open('rb');e=ELFFile(f);u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,0x500000);u.mem_map(0x1000000,0x2000000)
for s in e.iter_segments():
 if s['p_type']=='PT_LOAD':u.mem_write(s['p_vaddr'],s.data())
regs=[UC_ARM64_REG_X0+i for i in range(8)];heap=0x1800000

def put(p,fmt,*v):u.mem_write(p,struct.pack('<'+fmt,*v))
def get(p,fmt):return struct.unpack('<'+fmt,u.mem_read(p,struct.calcsize('<'+fmt)))[0]
def alloc(n):
 global heap
 ret=heap;heap+=(n+63)//64*64;assert heap<0x2800000;return ret

def hook(u,a,n,data):
 ret=0
 if a==0x39cc08:ret=0x1100000
 elif a==0x39f7ec:ret=alloc(get(u.reg_read(UC_ARM64_REG_X1),'I'))
 elif a==0x46d2a0:ret=alloc(u.reg_read(UC_ARM64_REG_X0))
 elif a!=0x46d200:raise Exception('unexpected hook '+hex(a))
 u.reg_write(UC_ARM64_REG_X0,ret);u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))
for a in [0x39cc08,0x39f7ec,0x46d2a0,0x46d200]:u.hook_add(UC_HOOK_CODE,hook,begin=a,end=a)
u.reg_write(UC_ARM64_REG_CPACR_EL1,3<<20);u.reg_write(UC_ARM64_REG_TPIDR_EL0,0x1000000)
def call(a,*args):
 for r,v in zip(regs,args):u.reg_write(r,v)
 u.reg_write(UC_ARM64_REG_SP,0x2ff0000);u.reg_write(UC_ARM64_REG_LR,0x1000400)
 u.emu_start(a,0x1000400,count=20000000)
 assert u.reg_read(UC_ARM64_REG_PC)==0x1000400
 return u.reg_read(UC_ARM64_REG_X0)
def s(i,v):u.reg_write(UC_ARM64_REG_S0+i,struct.unpack('<I',struct.pack('<f',v))[0])
noise=0x1010000;params=0x1020000;obj=0x1030000;img=0x1040000;outimg=0x1050000;vst=0x1060000
put(noise,'5f',.0009349135,.0186120867,.0000017738,.0001141268,.0289801844);put(noise+20,'I',100000);put(noise+0xa8,'I',1)
put(img+0xe0,'3f',1,1,1);put(img+0x68,'I',16);put(outimg+0x68,'I',32)
for iso in (50,100,400,800):
 u.mem_write(params,b'\0'*256);u.mem_write(obj,b'\0'*256);put(params,'Q',noise);put(params+0x18,'f',1);put(params+0x20,'I',1);put(params+0x28,'II',14,16);put(params+0x5c,'I',1)
 s(0,1);s(1,1);s(2,0);call(0x2d8850,params,iso,iso)
 print('ISO',iso,'norm',get(params+0x14,'f'),'shotnorm',get(params+0x10,'f'),'readnorm',get(params+0xc,'f'))
 put(obj+0xa0,'Q',vst);s(0,1);assert call(0x2da2c0,obj,params,img,0,0,0,0)==0
 vb=bytes(u.mem_read(vst,16384*3*2));(args.output_dir/('vst-'+str(iso)+'.bin')).write_bytes(vb)
 u.mem_write(obj,b'\0'*256);s(0,1);assert call(0x2dc068,obj,params,img,outimg,0)==0
 inv=bytes(u.mem_read(get(obj+0xa0,'Q'),65536*4));assert inv==bytes(u.mem_read(get(obj+0xb0,'Q'),65536*4))==bytes(u.mem_read(get(obj+0xc0,'Q'),65536*4));(args.output_dir/('ivst-'+str(iso)+'.bin')).write_bytes(inv)
 print('vst',[struct.unpack_from('<H',vb,i*2)[0] for i in (0,1,1638,8192,16383)],'ivst',[struct.unpack_from('<f',inv,i*4)[0] for i in (0,1,16384,32768,65535)])
