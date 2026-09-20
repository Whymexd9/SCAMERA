#!/usr/bin/env python3
"""Compare connected forward tile planning with pinned CRE ARM64 execution.

The original block planner executes unmodified. Only the caller's explicit
unit-scale input dimensions/strides and model tile/context sizes are supplied.
This verifies crop planning, not motion estimation or photographic inference.
"""
import argparse, ctypes, hashlib, struct, subprocess, tempfile
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import (UC_ARM64_REG_CPACR_EL1, UC_ARM64_REG_SP,
                                UC_ARM64_REG_LR, UC_ARM64_REG_X0, UC_ARM64_REG_X1,
                                UC_ARM64_REG_PC)
from decode_vivo_nice_kernels import DONOR_SHA256


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('library',type=Path)
    args=parser.parse_args()
    if hashlib.sha256(args.library.read_bytes()).hexdigest()!=DONOR_SHA256:
        raise ValueError('Unsupported CRE donor')
    root=Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix='nice-stock-profile-') as temp:
        cpp=Path(temp)/'profile.cpp';so=Path(temp)/'profile.so'
        cpp.write_text('#include "vivo-nice-profile.h"\n'
          'extern "C" int plan(int w,int h,int* out){\n'
          'auto xs=vivo_nice::forwardTileAxis(w),ys=vivo_nice::forwardTileAxis(h);\n'
          'for(auto y:ys)for(auto x:xs){\n'
          '*out++=y.inputOrigin*w+x.inputOrigin;*out++=y.outputOrigin*w+x.outputOrigin;\n'
          '*out++=x.outputSize;*out++=y.outputSize;*out++=y.crop*544+x.crop;}\n'
          'return xs.size()*ys.size();}\n')
        subprocess.run(['g++','-std=c++17','-O2','-shared','-fPIC',
          '-I'+str(root/'app/src/main/cpp'),str(cpp),'-o',str(so)],check=True)
        lib=ctypes.CDLL(str(so));lib.plan.argtypes=[ctypes.c_int,ctypes.c_int,ctypes.POINTER(ctypes.c_int)]
        u=Uc(UC_ARCH_ARM64,UC_MODE_ARM);u.mem_map(0,0x500000);u.mem_map(0x1000000,0x200000)
        with args.library.open('rb') as f:
            for segment in ELFFile(f).iter_segments():
                if segment['p_type']=='PT_LOAD':u.mem_write(segment['p_vaddr'],segment.data())
        u.reg_write(UC_ARM64_REG_CPACR_EL1,3<<20)
        p,obj=0x1000000,0x1010000
        sizes=[64,510,512,514,526,528,530,542,544,546,600,1024,1030,1040,1042,3072,4096]
        cases=tiles=0
        for w in sizes:
            for h in sizes:
                u.mem_write(obj,bytes(200000))
                # CRE BlockInit supplies 544-2*16 work dimensions. Last six
                # fields are the unit input/output coordinate scale ratios.
                u.mem_write(p,struct.pack('<15i',w,h,w,w,1,512,512,16,16,1,1,1,1,1,1))
                for reg,val in [(UC_ARM64_REG_SP,0x11ff000),(UC_ARM64_REG_LR,0x4ff000),
                                (UC_ARM64_REG_X0,obj),(UC_ARM64_REG_X1,p)]:u.reg_write(reg,val)
                u.emu_start(0x3dba84,0x4ff000,count=1000000)
                assert u.reg_read(UC_ARM64_REG_PC)==0x4ff000,'Stock planner did not return'
                count=struct.unpack('<i',u.mem_read(obj+0x38,4))[0]
                actual=(ctypes.c_int*(2000*5))();assert lib.plan(w,h,actual)==count,(w,h,count)
                for i in range(count):
                    row=struct.unpack('<19i',u.mem_read(obj+60+i*76,76))
                    expected=(row[0],row[1],row[6],row[7],row[10])
                    assert tuple(actual[i*5:i*5+5])==expected,(w,h,i,expected,actual[i*5:i*5+5])
                    src,dst,ww,hh,crop=expected
                    assert crop%544+ww<=544 and crop//544+hh<=544
                    assert dst%w+ww<=w and dst//w+hh<=h
                cases+=1;tiles+=count
        print(f'PASS: {cases} image sizes, {tiles} tile descriptors match original CRE ARM64 exactly')

if __name__=='__main__':main()
