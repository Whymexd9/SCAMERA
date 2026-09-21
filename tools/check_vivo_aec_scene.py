#!/usr/bin/env python3
"""Compare scene/flag/table dispatch with original pinned ARM64 functions."""
import argparse
import ctypes
import hashlib
from pathlib import Path
import random
import struct
import subprocess
import tempfile
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (UC_ARM64_REG_X0, UC_ARM64_REG_X1,
    UC_ARM64_REG_X2, UC_ARM64_REG_X3, UC_ARM64_REG_LR,
    UC_ARM64_REG_SP, UC_ARM64_REG_PC)

SHA = 'b2e3e12469a05cf62c2a5892b2b619228fb29a3ccb8fa9fe5f934df0dd5842e9'
DRIVER = r'''
#include "vivo-aec-scene.h"
extern "C" int scene(int64_t s, unsigned o) {return vivo_aec::hdrRunMode(s,o);}
extern "C" void flags(uint64_t p,unsigned o,int m,int disabled,unsigned prior,unsigned* out) {
 auto f=vivo_aec::updateCaptureFlags(p,o,m,disabled!=0,prior);
 out[0]=f.base;out[1]=f.alternate;out[2]=f.tableVariant;out[3]=f.decrease;
 out[4]=f.special;out[5]=f.family;out[6]=f.modeOverride;
}
extern "C" unsigned type(int m,int history,const unsigned* p) {
 return vivo_aec::exposureTableType(m,history,{p[0],p[1],p[2],p[3],p[4],p[5],p[6]});
}
extern "C" uint64_t selection(int m,unsigned t) {
 auto s=vivo_aec::hdrModeAndTableId(m,t);return s.hdrFlags | (uint64_t(s.tableId)<<32);
}
extern "C" int alternate(int m) {return vivo_aec::usesAlternateTuningBank(m);}
'''

def main():
    ap=argparse.ArgumentParser();ap.add_argument('library',type=Path);args=ap.parse_args()
    assert hashlib.sha256(args.library.read_bytes()).hexdigest()==SHA
    u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,0x800000);u.mem_map(0x1000000,0x1000000)
    with args.library.open('rb') as f:
        for segment in ELFFile(f).iter_segments():
            if segment['p_type']=='PT_LOAD':u.mem_write(segment['p_vaddr'],segment.data())
    obj,out,context,vtable,state=0x1100000,0x1110000,0x1120000,0x1130000,0x1140000
    stop,logger,accessor=0x7ff000,0x7ff100,0x7ff200
    def q(p,x):u.mem_write(p,struct.pack('<Q',x))
    def w(p,x):u.mem_write(p,struct.pack('<I',x & 0xffffffff))
    q(0x1f9ec0,0x1150000);q(0x1150000,logger)
    q(0x1fa0f8,0x1160000)  # Names accessed only for logging.
    q(obj+0x550,context);q(context,vtable);q(vtable+0x298,accessor)
    def hook(uc,address,size,data):
        if address in (logger,0x1e8a10,accessor):
            uc.reg_write(UC_ARM64_REG_X0,state if address==accessor else 0)
            uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
        elif address==0x1eac50:uc.reg_write(UC_ARM64_REG_PC,0x177d14)
        elif address==0x1eac60:uc.reg_write(UC_ARM64_REG_PC,0x177dbc)
    u.hook_add(UC_HOOK_CODE,hook)
    def execute(address,*values):
        for reg,val in zip((UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X2,UC_ARM64_REG_X3),values):
            u.reg_write(reg,val & 0xffffffffffffffff)
        u.reg_write(UC_ARM64_REG_SP,0x1ff0000);u.reg_write(UC_ARM64_REG_LR,stop)
        u.emu_start(address,stop,count=10000)
        assert u.reg_read(UC_ARM64_REG_PC)==stop
        return u.reg_read(UC_ARM64_REG_X0)
    root=Path(__file__).resolve().parents[1];rng=random.Random(245421);counts={}
    with tempfile.TemporaryDirectory(prefix='ae-scene-') as tmp:
        p=Path(tmp);(p/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-shared','-fPIC','-Wall','-Wextra','-Werror',
            '-I',str(root/'app/src/main/cpp'),str(p/'driver.cpp'),'-o',str(p/'driver.so')],check=True)
        lib=ctypes.CDLL(str(p/'driver.so'));uint=ctypes.c_uint
        lib.scene.argtypes=[ctypes.c_int64,uint]
        lib.flags.argtypes=[ctypes.c_uint64,uint,ctypes.c_int,ctypes.c_int,uint,ctypes.POINTER(uint)]
        lib.type.argtypes=[ctypes.c_int,ctypes.c_int,ctypes.POINTER(uint)];lib.type.restype=uint
        lib.selection.argtypes=[ctypes.c_int,uint];lib.selection.restype=ctypes.c_uint64
        lib.alternate.argtypes=[ctypes.c_int]
        scenes=[0x80000,0x100000,0x380000,0x1100000,0x200000,0x680000,0x10000,
            0x180000,0x700000,0x280000,0x300000,0x400000,0x780000,0x800000,
            0xc80000,0xd80000,0xc00000,0xd00000,0x1180000,-1,0,2**63-1,-2**63]
        for scene in [s+d for s in scenes for d in (-1,0,1) if -2**63<=s+d<2**63]:
            for override in (0,1,2,15):
                w(obj+0x640,override);u.mem_write(out,b'\xa5'*12)
                execute(0x177b38,obj,scene,out+4)
                assert bytes(u.mem_read(out,12))==b'\xa5'*4+struct.pack('<i',lib.scene(scene,override))+b'\xa5'*4
                counts['scene']=counts.get('scene',0)+1
        for mode in range(-1,17):
            for i in range(100):
                packed=0 if i==0 else rng.getrandbits(64);override=rng.getrandbits(32)
                disabled=i%2;prior=rng.randrange(16)
                q(state+0x860,packed);w(state+0x868,override);w(state+0x8c4,disabled)
                w(obj+0x61c,mode);w(obj+0x640,prior)
                u.mem_write(obj+0x644,b'\xa5'*24)
                actual=(uint*7)();lib.flags(packed,override,mode,disabled,prior,actual)
                execute(0x1782a0,obj)
                expected=bytes(u.mem_read(obj+0x644,24))+bytes(u.mem_read(obj+0x640,4))
                assert bytes(actual)==expected,(mode,hex(packed),list(actual),expected.hex())
                counts['flags']=counts.get('flags',0)+1
                history=rng.choice([-1,0,1,2])
                got=lib.type(mode,history,actual)
                assert got==execute(0x177d14,obj,mode,history)
                counts['type']=counts.get('type',0)+1
                u.mem_write(out,b'\xa5'*132)
                execute(0x177f20,obj,mode,out,history)
                expected=lib.selection(mode,got)
                assert struct.unpack('<I',u.mem_read(out+0x68,4))[0]==(expected&0xffffffff)
                assert bytes(u.mem_read(out+0x74,8))==struct.pack('<II',got,expected>>32)
                counts['composed']=counts.get('composed',0)+1
            for table in range(17):
                assert lib.selection(mode,table)==execute(0x177dbc,mode,table,0)
                counts['selection']=counts.get('selection',0)+1
            # Execute the bank-selection branch, with different pointer markers.
            from unicorn.arm64_const import UC_ARM64_REG_W26, UC_ARM64_REG_X23, UC_ARM64_REG_X8
            u.reg_write(UC_ARM64_REG_W26,(mode-14)&0xffffffff)
            u.reg_write(UC_ARM64_REG_X23,state)
            q(state+0xb0,0x1230000);q(state+0xb8,0x1240000)
            u.emu_start(0x179144,0x179158,count=20)
            assert u.reg_read(UC_ARM64_REG_PC)==0x179158
            assert u.reg_read(UC_ARM64_REG_X8)==(0x1240000 if lib.alternate(mode) else 0x1230000)
            counts['bank']=counts.get('bank',0)+1
    print('PASS: original ARM64 scene/table dispatch:',counts)
    print('Live scene/flags, loaded sensor bank, EV orchestration and Camera2 gain mapping are not supplied by this test.')

if __name__=='__main__':main()
