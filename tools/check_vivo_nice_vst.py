#!/usr/bin/env python3
"""Differential test: independent VST against the supplied CRE ARM64 function.
Requires unicorn and pyelftools; vendor code remains external, hash-pinned.
Only noise lookup is stubbed with explicit coefficients, not the VST function.
"""
import argparse, ctypes, hashlib, random, struct, subprocess, tempfile
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import *
from decode_vivo_nice_kernels import DONOR_SHA256

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('library', type=Path)
    args = parser.parse_args()
    assert hashlib.sha256(args.library.read_bytes()).hexdigest() == DONOR_SHA256
    root = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory() as temp:
        cpp = Path(temp) / 'vst.cpp'
        cpp.write_text('#include "vivo-nice-preprocess.h"\nextern "C" void run(const float* p, unsigned bits, unsigned outbits, uint16_t* out) {\n'
                       'vivo_nice::VstMode2 v{p[0],p[1],p[2],p[3],p[4],p[5],p[6],p[7],{p[8],p[9],p[10]},bits,outbits};\n'
                       'auto a=vivo_nice::makeVstMode2(v); std::copy(a.begin(),a.end(),out); }\n')
        so = Path(temp) / 'vst.so'
        subprocess.run(['g++','-std=c++17','-O2','-ffp-contract=off','-shared','-fPIC',
                        '-I'+str(root/'app/src/main/cpp'),str(cpp),'-o',str(so)],check=True)
        lib = ctypes.CDLL(str(so))
        lib.run.argtypes = [ctypes.POINTER(ctypes.c_float),ctypes.c_uint,ctypes.c_uint,ctypes.POINTER(ctypes.c_uint16)]
        u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        u.mem_map(0, 0x500000)
        with args.library.open('rb') as f:
            elf = ELFFile(f)
            for seg in elf.iter_segments():
                if seg['p_type'] == 'PT_LOAD': u.mem_write(seg['p_vaddr'],seg.data())
        u.mem_map(0x1000000, 0x200000)
        u.reg_write(UC_ARM64_REG_CPACR_EL1, 3 << 20)
        u.reg_write(UC_ARM64_REG_TPIDR_EL0, 0x1100000)
        paddr, obj, meta, lut = 0x1000000,0x1001000,0x1002000,0x1010000
        def write(a,fmt,*v):u.mem_write(a,struct.pack('<'+fmt,*v))
        coeffs = {}
        def noise(uc,address,size,data):
            iso=uc.reg_read(UC_ARM64_REG_W1)
            offset,slope=coeffs[iso]
            write(uc.reg_read(UC_ARM64_REG_X2),'f',offset)
            write(uc.reg_read(UC_ARM64_REG_X3),'f',slope)
            uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
        u.hook_add(UC_HOOK_CODE,noise,begin=0x2a9d48,end=0x2a9d48)
        rng=random.Random(30230)
        total=0; worst=0
        for case in range(18):
            bits=14 if case<3 else 10
            outbits=15 if case%2 else 16
            black=rng.choice([0., 64/1023, 1024/16383])
            slope=rng.uniform(.0001,.01); ns=rng.uniform(.0001,.01)
            no=rng.uniform(1e-8,1e-5); ev=rng.choice([.125,1.,4.])
            nev=rng.choice([.25,1.,8.]); norm=rng.uniform(30.,250.)
            enabled=case%3 !=0; mult=nev**.5 if enabled else 1.
            gains=[rng.uniform(.5,2.5) for _ in range(3)] if case%2 else [1.,1.,1.]
            values=(ctypes.c_float*11)(black,slope,ns,no,ev,nev,norm,mult,*gains)
            coeffs={100:(no*2,slope),200:(no,ns)}
            u.mem_write(paddr,bytes(0x100));u.mem_write(meta,bytes(0x100))
            write(obj+0xa0,'Q',lut)
            write(paddr+8,'f',values[0]);write(paddr+0x14,'f',values[6])
            write(paddr+0x24,'I',int(enabled));write(paddr+0x28,'II',bits,outbits)
            write(paddr+0x34,'II',100,200);write(paddr+0x40,'f',values[5])
            write(paddr+0x54,'I',1);write(paddr+0x5c,'I',2)
            write(meta+0xe0,'fff',*gains)
            u.reg_write(UC_ARM64_REG_SP,0x11ff000)
            u.reg_write(UC_ARM64_REG_LR,0x4ff000)
            for reg,v in [(UC_ARM64_REG_X0,obj),(UC_ARM64_REG_X1,paddr),(UC_ARM64_REG_X2,meta),
                          (UC_ARM64_REG_W5,0),(UC_ARM64_REG_W6,0)]:u.reg_write(reg,v)
            u.reg_write(UC_ARM64_REG_S0,struct.unpack('<I',struct.pack('<f',ev))[0])
            u.emu_start(0x2da2c0,0x4ff000,count=12000000)
            if u.reg_read(UC_ARM64_REG_PC)!=0x4ff000:raise RuntimeError('CRE did not return')
            count=3*(1<<bits)
            donor=struct.unpack('<'+'H'*count,bytes(u.mem_read(lut,count*2)))
            actual=(ctypes.c_uint16*count)();lib.run(values,bits,outbits,actual)
            diff=max(abs(a-b) for a,b in zip(donor,actual));worst=max(worst,diff);total+=count
            if diff>1:raise AssertionError((case,diff, list(values)))
        print(f'PASS: {total} VST LUT entries vs original ARM64; maximum difference {worst} uint16 units (limit 1)')

if __name__=='__main__':main()
