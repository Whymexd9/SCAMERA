#!/usr/bin/env python3
"""Verify XML ref/refn exposure-level semantics using original CRE ARM64.

This executes the complete radiometric selector (0x35e604), not the higher
level frame-quality sorter. It deliberately uses five exposure levels and a
seven-frame vector to distinguish exposure-level selection from slot indexing.
"""
import argparse
import hashlib
import struct
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import (UC_ARM64_REG_CPACR_EL1, UC_ARM64_REG_SP,
    UC_ARM64_REG_LR, UC_ARM64_REG_PC, UC_ARM64_REG_X0, UC_ARM64_REG_X1,
    UC_ARM64_REG_X2, UC_ARM64_REG_X3, UC_ARM64_REG_X4)
from decode_vivo_nice_kernels import DONOR_SHA256


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('library',type=Path)
    args=p.parse_args()
    if hashlib.sha256(args.library.read_bytes()).hexdigest()!=DONOR_SHA256:
        raise ValueError('Unsupported donor')
    u=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
    u.mem_map(0,0x500000);u.mem_map(0x1000000,0x200000)
    with args.library.open('rb') as f:
        for s in ELFFile(f).iter_segments():
            if s['p_type']=='PT_LOAD':u.mem_write(s['p_vaddr'],s.data())
    u.reg_write(UC_ARM64_REG_CPACR_EL1,3<<20)
    obj,state,model,vec,ev,iso,level=0x1000000,0x1001000,0x1002000,0x1003000,0x1003100,0x1003200,0x1003300
    def word(addr,value):u.mem_write(addr,struct.pack('<I',value))
    u.mem_write(obj+0x90,struct.pack('<Q',state))
    u.mem_write(state,struct.pack('<Q',model))
    u.mem_write(vec,struct.pack('<QQQ',0x1010000,0x1010038,0x1010038))
    exposures=[1.,4.,16.,64.,256.];isos=[50.,100.,200.,400.,800.]
    u.mem_write(ev,struct.pack('<5f',*exposures));u.mem_write(iso,struct.pack('<5f',*isos));word(level,3)
    for ref in range(5):
        for refn in range(5):
            word(model+0x1ec,ref);word(model+0x1f0,refn)
            for reg,value in [(UC_ARM64_REG_SP,0x11ff000),(UC_ARM64_REG_LR,0x4ff000),
                (UC_ARM64_REG_X0,obj),(UC_ARM64_REG_X1,vec),(UC_ARM64_REG_X2,ev),
                (UC_ARM64_REG_X3,iso),(UC_ARM64_REG_X4,level)]:u.reg_write(reg,value)
            u.emu_start(0x35e604,0x4ff000,count=10000)
            assert u.reg_read(UC_ARM64_REG_PC)==0x4ff000,'Original selector did not return'
            assert struct.unpack('<If',u.mem_read(state+0xe4,8))==(int(isos[ref]),exposures[ref])
            assert struct.unpack('<If',u.mem_read(state+0xf4,8))==(int(isos[refn]),exposures[refn])
    print('PASS: 25 ref/refn combinations select radiometric exposure levels, independently of the seven input slots')


if __name__=='__main__':main()
