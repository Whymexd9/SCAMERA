#!/usr/bin/env python3
"""Compare portable table lookup with the complete pinned ARM64 function."""
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
    UC_ARM64_REG_X2, UC_ARM64_REG_LR, UC_ARM64_REG_SP, UC_ARM64_REG_PC)

SHA = 'b2e3e12469a05cf62c2a5892b2b619228fb29a3ccb8fa9fe5f934df0dd5842e9'
DRIVER = r'''
#include "vivo-aec-table.h"
#include <cstring>
extern "C" int lookup(unsigned char* result, float divisor, unsigned count,
                       const float* gains, const uint64_t* shutters, const unsigned* flags) {
 try {
  std::vector<vivo_aec::TableRow> rows;
  for (unsigned i=0; i<count; ++i) rows.push_back({gains[i],shutters[i],flags[i]!=0});
  float wanted;std::memcpy(&wanted,result+20,4);
  auto out=vivo_aec::tableExposure(wanted,divisor,rows);
  std::memcpy(result,&out.gain,4);std::memcpy(result+8,&out.shutter,8);
  std::memcpy(result+20,&out.product,4);
  return 0;
 } catch (const std::invalid_argument&) { return -1; }
}
'''

def f32(value):
    return struct.unpack('<f',struct.pack('<f',value))[0]

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('library',type=Path)
    args=parser.parse_args()
    assert hashlib.sha256(args.library.read_bytes()).hexdigest()==SHA
    u=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
    u.mem_map(0,0x800000);u.mem_map(0x1000000,0x1000000)
    with args.library.open('rb') as file:
        for segment in ELFFile(file).iter_segments():
            if segment['p_type']=='PT_LOAD':u.mem_write(segment['p_vaddr'],segment.data())
    obj, exposure, table, rows=0x1100000,0x1110000,0x1120000,0x1130000
    stop,logger=0x7ff000,0x7ff100
    u.mem_write(0x1f9ec0,struct.pack('<Q',0x11b0000))
    u.mem_write(0x11b0000,struct.pack('<Q',logger))
    # Only logging is substituted. Clamp, interval selection, float arithmetic
    # and conversion to integer shutter execute unchanged donor instructions.
    def hook(uc,address,size,data):
        if address in (logger,0x1e8a10):
            uc.reg_write(UC_ARM64_REG_X0,0)
            uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
    u.hook_add(UC_HOOK_CODE,hook)
    root=Path(__file__).resolve().parents[1]
    rng=random.Random(24541774)
    total=0
    with tempfile.TemporaryDirectory(prefix='vivo-aec-table-') as directory:
        tmp=Path(directory);(tmp/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-ffp-contract=off','-shared','-fPIC',
            '-Wall','-Wextra','-Werror','-I',str(root/'app/src/main/cpp'),
            str(tmp/'driver.cpp'),'-o',str(tmp/'driver.so')],check=True)
        lib=ctypes.CDLL(str(tmp/'driver.so'))
        lib.lookup.argtypes=[ctypes.POINTER(ctypes.c_ubyte),ctypes.c_float,ctypes.c_uint,
            ctypes.POINTER(ctypes.c_float),ctypes.POINTER(ctypes.c_uint64),ctypes.POINTER(ctypes.c_uint)]
        def run(gains,shutters,flags,divisor,target,valid=True):
            nonlocal total
            count=len(gains)
            g=(ctypes.c_float*count)(*gains);s=(ctypes.c_uint64*count)(*shutters)
            f=(ctypes.c_uint*count)(*flags)
            initial=bytearray(b'\xa5'*24)
            struct.pack_into('<f',initial,20,target)
            out=(ctypes.c_ubyte*24).from_buffer_copy(initial)
            status=lib.lookup(out,divisor,count,g,s,f)
            if not valid:
                assert status==-1 and bytes(out)==initial
                return
            assert status==0
            u.mem_write(table,struct.pack('<fIQQQ',divisor,count,0,rows,0))
            raw=b''.join(struct.pack('<fIQII',g[i],0,s[i],f[i],0) for i in range(count))
            u.mem_write(rows,raw)
            u.mem_write(exposure-16,b'\xa5'*16+bytes(initial)+b'\xa5'*16)
            for register,value in ((UC_ARM64_REG_X0,obj),(UC_ARM64_REG_X1,exposure),
                                   (UC_ARM64_REG_X2,table),(UC_ARM64_REG_SP,0x1ff0000),
                                   (UC_ARM64_REG_LR,stop)):
                u.reg_write(register,value)
            u.emu_start(0x1774d4,stop,count=100000)
            assert u.reg_read(UC_ARM64_REG_PC)==stop and u.reg_read(UC_ARM64_REG_X0)==0
            expected=bytes(u.mem_read(exposure,24))
            assert bytes(out)==expected,(gains,shutters,flags,divisor,target,bytes(out).hex(),expected.hex())
            assert bytes(u.mem_read(exposure-16,16))+bytes(u.mem_read(exposure+24,16))==b'\xa5'*32
            assert bytes(u.mem_read(rows,len(raw)))==raw
            total+=1
        for trial in range(150):
            count=rng.choice([2,3,8,16]);gain=rng.uniform(.5,2);shutter=rng.randint(1000,100000)
            gains=[];shutters=[]
            for i in range(count):
                gains.append(f32(gain));shutters.append(shutter)
                gain*=rng.uniform(1.05,1.8);shutter+=rng.randint(10001,900000)
            flags=[rng.choice([0,1,2]) for _ in gains]
            divisor=f32(rng.choice([.25,.7,1.,2.,4.]))
            products=[f32(g*f32(s)) for g,s in zip(gains,shutters)]
            targets=[products[0]*.5,products[-1]*2]
            targets+=products
            targets += [rng.uniform(products[0],products[-1]) for _ in range(12)]
            for target in targets:run(gains,shutters,flags,divisor,f32(target*divisor))
        # Both orders, unchanged gain/shutter intervals, non-float-exact integer
        # bases, endpoint equality and adjacent float32 values.
        for flags in ([0,0,0],[1,1,1],[0,1,0]):
            gains=[1.,2.,2.];shutters=[16777217,16777217,33333333]
            for boundary in (16777216.,33554432.,66666664.):
                bits=struct.unpack('<I',struct.pack('<f',boundary))[0]
                for delta in (-1,0,1):
                    run(gains,shutters,flags,1.,struct.unpack('<f',struct.pack('<I',bits+delta))[0])
        for bad in (0.,-1.,float('nan'),float('inf')):
            run([1.,2.],[1000,2000],[0,1],bad,1500,False)
            run([1.,2.],[1000,2000],[0,1],1.,bad,False)
        for gains,shutters in (([],[]),([1.],[1000]),([1.,1.],[1000,1000]),
                              ([2.,1.],[2000,1000]),([1.,float('nan')],[1000,2000]),
                              ([1.,2.],[0,2000])):
            run(gains,shutters,[0]*len(gains),1.,1500,False)
    print(f'PASS: {total} table lookups match complete donor output bytes; invalid tables rejected')
    print('Selected tuning provenance, normal-EV adjustment and Camera2 gain mapping remain outside this test.')

if __name__=='__main__':main()
