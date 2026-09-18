#!/usr/bin/env python3
"""Compare all Init bytes against original ARM64 VAF fillInitParams.
Usage: check_raisr_vaf.py /path/libvivo.vaf.algo.raisr.so
Only copies parameters; no native image processing is executed.
"""
import hashlib,io,json,struct,subprocess,sys,tempfile
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE
from unicorn.arm64_const import *
root=Path(__file__).resolve().parents[2]
manifest=json.loads(Path(__file__).with_name('manifest.json').read_text())
b=Path(sys.argv[1]).read_bytes()
assert hashlib.sha256(b).hexdigest()==next(x['sha256'] for x in manifest['files'] if x['path']=='vendor/lib64/libvivo.vaf.algo.raisr.so')
elf=ELFFile(io.BytesIO(b));segments=[s for s in elf.iter_segments() if s['p_type']=='PT_LOAD']
end=max(s['p_vaddr']+s['p_memsz'] for s in segments)
with tempfile.TemporaryDirectory() as tmp:
    src=Path(tmp)/'builder.cpp';exe=Path(tmp)/'builder'
    src.write_text('''#include "vivo-raisr-abi.h"
#include <cstdio>
#include <cstdlib>
int main(int,char** a){auto p=vivo_raisr::makeInit(640,480,atoi(a[1]),atoi(a[2]),atoi(a[3]),atoi(a[4]),"/vendor/camera3rd/nti/raisr");
return fwrite(&p,1,sizeof(p),stdout)==sizeof(p)?0:1;}
''')
    subprocess.run(['c++','-std=c++17','-I',str(root/'app/src/main/cpp'),str(src),'-o',str(exe)],check=True)
    for ow,oh,iso,role in [(640,480,100,2),(1280,960,750,8),(2560,1920,3200,2)]:
        u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,(end+4095)//4096*4096)
        for s in segments:u.mem_write(s['p_vaddr'],s.data())
        u.mem_map(0x10000000,0x20000);params=bytearray(0x118)
        for off,val in {0:480,4:640,8:oh,12:ow,0x14:2,0xf4:iso,0xf8:role,0x100:4}.items():struct.pack_into('<I',params,off,val)
        struct.pack_into('<f',params,0x10,ow/640);struct.pack_into('<Q',params,0x108,0x10008000)
        u.mem_write(0x10008000,b'/vendor/camera3rd/nti/raisr\0');u.mem_write(0x10001000,bytes(params))
        u.reg_write(UC_ARM64_REG_X0,0x10002000);u.reg_write(UC_ARM64_REG_X1,0x10001000)
        u.reg_write(UC_ARM64_REG_SP,0x10010000)
        def hook(uc,addr,size,data):
            if addr==0x49d8: # bounded printf("%s", modelPath)
                p=uc.reg_read(UC_ARM64_REG_X4);out=bytearray()
                while (v:=uc.mem_read(p+len(out),1)[0]):out.append(v)
                uc.mem_write(uc.reg_read(UC_ARM64_REG_X0),bytes(out)+b'\0')
                uc.reg_write(UC_ARM64_REG_X0,len(out));uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
        u.hook_add(UC_HOOK_CODE,hook);u.emu_start(0x4a88,0x4b28,count=500)
        expected=bytes(u.mem_read(0x10002020,0x160))
        actual=subprocess.check_output([str(exe),str(ow),str(oh),str(iso),str(role)])
        assert actual==expected,[(hex(i),a,b) for i,(a,b) in enumerate(zip(actual,expected)) if a!=b]
        print('PASS RAISR VAF vs compiled Init:',ow,oh,iso,role,'all 352 bytes')
