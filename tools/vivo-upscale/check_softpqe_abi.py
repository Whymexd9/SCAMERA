#!/usr/bin/env python3
"""Compare our compiled Init bytes with original VAF ARM64 construction.
Usage: check_softpqe_abi.py /path/libvivo.vaf.algo.softPQE.so
No Init, QNN or image inference executed. Requires c++, pyelftools, unicorn.
"""
import hashlib, io, json, struct, subprocess, sys, tempfile
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X2,
    UC_ARM64_REG_X19,UC_ARM64_REG_X20,UC_ARM64_REG_SP,UC_ARM64_REG_LR,UC_ARM64_REG_PC)
root=Path(__file__).resolve().parents[2]
manifest=json.loads(Path(__file__).with_name('manifest.json').read_text())
binary=Path(sys.argv[1]).read_bytes()
expected=next(x['sha256'] for x in manifest['files'] if x['path']=='vendor/lib64/libvivo.vaf.algo.softPQE.so')
assert hashlib.sha256(binary).hexdigest()==expected,'Unverified VAF binary'
elf=ELFFile(io.BytesIO(binary));segments=[s for s in elf.iter_segments() if s['p_type']=='PT_LOAD']
end=max(s['p_vaddr']+s['p_memsz'] for s in segments)
with tempfile.TemporaryDirectory() as tmp:
    source=Path(tmp)/'builder.cpp';exe=Path(tmp)/'builder'
    source.write_text('''#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include "vivo-softpqe-abi.h"
int main(int,char** a){auto p=vivo_softpqe::makeInit(atoi(a[1]),atoi(a[2]),atof(a[3]),
reinterpret_cast<const char*>(0x10008000),reinterpret_cast<const char*>(0x10009000));
return fwrite(&p,1,sizeof(p),stdout)==sizeof(p)?0:1;}
''')
    subprocess.run(['c++','-std=c++17','-Wall','-Wextra','-Werror','-I',str(root/'app/src/main/cpp'),str(source),'-o',str(exe)],check=True)
    for w,h,gain in [(640,480,1.),(4000,3000,8.),(2048,1536,3.5)]:
        uc=Uc(UC_ARCH_ARM64,UC_MODE_ARM);uc.mem_map(0,(end+4095)//4096*4096)
        for s in segments:uc.mem_write(s['p_vaddr'],s.data())
        uc.mem_map(0x10000000,0x20000);vaf=bytearray(0xc0)
        for off,val in {4:h,8:w,0x88:w,0x8c:h,0x90:2}.items():struct.pack_into('<I',vaf,off,val)
        for off,val in {0x5c:gain,0x60:1.,0x64:1.,0x68:1.,0x6c:1.,0x94:1.,0x9c:1.}.items():struct.pack_into('<f',vaf,off,val)
        struct.pack_into('<QQ',vaf,0x70,0x10008000,0x10009000);vaf[0xb0]=1
        uc.mem_write(0x10001000,bytes(vaf));uc.reg_write(UC_ARM64_REG_X19,0x10001000)
        uc.reg_write(UC_ARM64_REG_X20,0x10002000);uc.reg_write(UC_ARM64_REG_SP,0x10010000)
        def hook(u,address,size,data):
            if address==0x5530:
                u.mem_write(u.reg_read(UC_ARM64_REG_X0),bytes([u.reg_read(UC_ARM64_REG_X1)&255])*u.reg_read(UC_ARM64_REG_X2))
                u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))
        uc.hook_add(UC_HOOK_CODE,hook);uc.emu_start(0x4b80,0x4c20,count=1000)
        canonical=bytes(uc.mem_read(0x10010020,0x268))
        ours=subprocess.check_output([str(exe),str(w),str(h),str(gain)])
        assert ours==canonical,[(hex(i),a,b) for i,(a,b) in enumerate(zip(ours,canonical)) if a!=b]
        print('PASS VAF vs C++ Init',w,h,gain,'all 616 bytes')
print('ABI construction only; metadata assumptions and device inference remain unverified.')
