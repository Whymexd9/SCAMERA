#!/usr/bin/env python3
"""Check native allocator ABI and C++ lifetime policy, not device DMA execution."""
import argparse
import hashlib
import struct
import subprocess
import tempfile
from pathlib import Path
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import *
from check_vivo_nice_motion import emulator,invoke,CRE_SHA256

SHA='530eb4c1b911c1e8631042627cfda0042bc150d643ab604d4dcdf6c533171343'

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('platform',type=Path)
    p.add_argument('--cre',type=Path)
    a=p.parse_args();assert hashlib.sha256(a.platform.read_bytes()).hexdigest()==SHA
    u,_=emulator(a.platform)
    handler,instance,vtable,fd,pixels,callback=0x1100000,0x1101000,0x1102000,0x1103000,0x1104000,0x1200000
    u.mem_write(handler,struct.pack('<Q',instance));u.mem_write(instance,struct.pack('<Q',vtable))
    u.mem_write(vtable+0x10,struct.pack('<Q',callback))
    calls=[]
    def allocate(u,address,size,data):
        regs=[u.reg_read(r) for r in (UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X2,UC_ARM64_REG_X3,UC_ARM64_REG_X4)]
        calls.append(regs)
        u.mem_write(regs[2],struct.pack('<i',29));u.mem_write(regs[3],struct.pack('<Q',0x1500000))
        u.reg_write(UC_ARM64_REG_X0,0);u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))
    u.hook_add(UC_HOOK_CODE,allocate,begin=callback,end=callback)
    for size in [1,64,4096,4096*3072*6]:
        for cached in [0,1,2,3]:
            u.mem_write(fd,b'F'*16);u.mem_write(pixels,b'P'*24)
            assert invoke(u,0x8430,[handler,size,cached,fd+4,pixels+8])==0
            assert calls[-1]==[instance,size,fd+4,pixels+8,cached&1]
            assert bytes(u.mem_read(fd,16))==b'F'*4+struct.pack('<i',29)+b'F'*8
            assert bytes(u.mem_read(pixels,24))==b'P'*8+struct.pack('<Q',0x1500000)+b'P'*8
    if a.cre:
        assert hashlib.sha256(a.cre.read_bytes()).hexdigest()==CRE_SHA256
        cre,_=emulator(a.cre)
        for width,height in [(1,1),(63,47),(64,48),(4096,3072),(8192,6144)]:
            # Original constructor with allocation disabled: verifies format,
            # row layout and byte depth, not the physical shared buffer.
            invoke(cre,0x2a1dac,[0x1100000,width,height,14,0,0,0x1004,0],[0,0,0])
            assert struct.unpack('<iii',cre.mem_read(0x1100018,12))==(width,height,width*6)
        print('PASS: original CRE RGB16 constructor layout for 5 image extents')
    with tempfile.TemporaryDirectory(prefix='vivo-buffer-') as tmp:
        root=Path(__file__).resolve().parents[1];binary=Path(tmp)/'check'
        subprocess.run(['c++','-std=c++17','-Wall','-Wextra','-Werror','-I',str(root/'app/src/main/cpp'),
            str(root/'tools/check_vivo_nice_shared_buffer.cpp'),'-o',str(binary)],check=True)
        subprocess.run([str(binary)],check=True)
    print('PASS: 16 original allocation ABI calls; C++ ownership, sync and failure cleanup')
    print('Underlying device allocator replaced by a recording test callback; no device DMA/GPU claim.')

if __name__=='__main__':main()
