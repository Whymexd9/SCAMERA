#!/usr/bin/env python3
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
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2, UC_ARM64_REG_X3
from unicorn.arm64_const import UC_ARM64_REG_LR, UC_ARM64_REG_SP, UC_ARM64_REG_PC
from unicorn.arm64_const import UC_ARM64_REG_S0, UC_ARM64_REG_S1, UC_ARM64_REG_S2

SHA = 'b2e3e12469a05cf62c2a5892b2b619228fb29a3ccb8fa9fe5f934df0dd5842e9'
DRIVER = r'''
#include "vivo-aec-exposure.h"
extern "C" int run(float* data, float divisor, float gain, uint64_t shutter,
                   float sensorGain, float period, int mode) {
 try {
  auto out = vivo_aec::shortExposure({data[0],data[1],data[2],0x12345678},
       divisor,{gain,shutter,sensorGain,period,mode});
  data[0]=out.shutter;data[1]=out.gain;data[2]=out.ev;
  return out.flags==0x12345678 ? 0 : -2;
 } catch(const std::invalid_argument&) { return -1; }
}
extern "C" void gaps(float* data) {
 auto out=vivo_aec::exposureGaps(data[0],data[1],data[2]);
 data[0]=out.shortEv;data[1]=out.extraShortEv;data[2]=out.longEv;
}
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('library', type=Path)
    args = parser.parse_args()
    assert hashlib.sha256(args.library.read_bytes()).hexdigest() == SHA
    u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    u.mem_map(0, 0x800000)
    u.mem_map(0x1000000, 0x1000000)
    with args.library.open('rb') as file:
        elf = ELFFile(file)
        for segment in elf.iter_segments():
            if segment['p_type'] == 'PT_LOAD':
                u.mem_write(segment['p_vaddr'], segment.data())
    obj, exposure, table, row = 0x1100000, 0x1110000, 0x1120000, 0x1130000
    context, vtable, sensor, state, params, tuning = [0x1140000 + i*0x10000 for i in range(6)]
    stop, logger, context_call, sensor_call = 0x7ff000, 0x7ff100, 0x7ff200, 0x7ff300
    def q(address, value): u.mem_write(address, struct.pack('<Q', value))
    def f(address, value): u.mem_write(address, struct.pack('<f', value))
    q(0x1f9ec0, 0x11b0000)
    q(0x11b0000, logger)
    q(table+0x10, row)
    q(obj+0x550, context)
    q(context, vtable)
    q(vtable+0x298, context_call)
    q(vtable+0xc0, sensor_call)
    q(params+0xa8, tuning)
    stubs = {0x1e8a10: 0, 0x1eac30: 1, logger: 0,
             context_call: state, sensor_call: sensor}
    # Only logging, table-validation status and explicit context inputs are
    # substituted. Exposure arithmetic runs from the pinned donor instructions.
    def hook(uc, address, size, data):
        if address in stubs:
            uc.reg_write(UC_ARM64_REG_X0, stubs[address])
            uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_LR))
    u.hook_add(UC_HOOK_CODE, hook)
    def call(address, values):
        for register, value in zip((UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X2,UC_ARM64_REG_X3), values):
            u.reg_write(register, value)
        u.reg_write(UC_ARM64_REG_SP, 0x1ff0000)
        u.reg_write(UC_ARM64_REG_LR, stop)
        u.emu_start(address, stop, count=100000)
        assert u.reg_read(UC_ARM64_REG_PC) == stop
    root = Path(__file__).resolve().parents[1]
    rng = random.Random(2454)
    with tempfile.TemporaryDirectory(prefix='vivo-aec-') as directory:
        tmp = Path(directory)
        (tmp/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-ffp-contract=off','-shared','-fPIC',
                        '-Wall','-Wextra','-Werror','-I',str(root/'app/src/main/cpp'),
                        str(tmp/'driver.cpp'),'-o',str(tmp/'driver.so')],check=True)
        lib = ctypes.CDLL(str(tmp/'driver.so'))
        lib.run.argtypes = [ctypes.POINTER(ctypes.c_float),ctypes.c_float,ctypes.c_float,
                           ctypes.c_uint64,ctypes.c_float,ctypes.c_float,ctypes.c_int]
        lib.gaps.argtypes = [ctypes.POINTER(ctypes.c_float)]
        for trial in range(1000):
            period = rng.choice([0., 8333333., 10000000.])
            shutter = rng.choice([10000., 1000000., 8333333., 10000000., 20000000., 40000000.])
            gain = rng.uniform(1., 64.)
            ev = rng.choice([-8., -4., -2., 0., 1.])
            divisor = rng.choice([1., 2., 4., 16., 64., 256.])
            min_gain, min_shutter, mode = rng.choice([.5,1.,2.]), rng.choice([1000,10000,100000]), trial%5
            data = (ctypes.c_float*3)(shutter,gain,ev)
            initial = bytes(data)+struct.pack('<I',0x12345678)
            assert lib.run(data,divisor,min_gain,min_shutter,1.,period,mode) == 0
            u.mem_write(exposure-16,b'\xa5'*48)
            u.mem_write(exposure,initial)
            f(row,min_gain);q(row+8,min_shutter);f(sensor+8,1.);f(obj+0x5f0,period)
            u.reg_write(UC_ARM64_REG_S0,struct.unpack('<I',struct.pack('<f',divisor))[0])
            call(0x17aae4,[obj,exposure,table,mode])
            expected = bytes(u.mem_read(exposure,16))
            assert bytes(data)+struct.pack('<I',0x12345678) == expected, (trial,list(data),struct.unpack('<4f',expected))
            assert bytes(u.mem_read(exposure-16,16))+bytes(u.mem_read(exposure+16,16)) == b'\xa5'*32
        for trial in range(400):
            mode = 10 if trial%2 else 9
            u.mem_write(state,struct.pack('<i',mode))
            inputs = [rng.choice([0.,-1.e-7,-1.e-6,-1.,-3.,-8.,2.,6.]) for _ in range(3)]
            data = (ctypes.c_float*3)(*inputs)
            offset = 0x8c if mode==10 else 0x80
            u.mem_write(tuning+offset,bytes(data))
            lib.gaps(data)
            call(0x178a10,[obj,params])
            expected = b''.join(struct.pack('<I',u.reg_read(r)) for r in (UC_ARM64_REG_S0,UC_ARM64_REG_S1,UC_ARM64_REG_S2))
            assert bytes(data)==expected, (inputs,list(data),struct.unpack('<3f',expected))
        for bad in [float('nan'),float('inf'),0.,-1.]:
            data = (ctypes.c_float*3)(10000.,2.,-2.)
            before = bytes(data)
            assert lib.run(data,bad,1.,1000,1.,0.,0)==-1 and bytes(data)==before
    print('PASS: 1000 short exposures and 400 EV-gap cases match donor bytes; invalid divisors rejected')
    print('Table loading, upstream AE, exposure-code dispatch and Camera2 delivery are outside this test.')


if __name__ == '__main__':
    main()
