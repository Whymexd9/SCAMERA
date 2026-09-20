#!/usr/bin/env python3
"""Check recovered capture-control fields against original ARM64 load blocks.

Executes the pinned core's frame, batch, flag and threshold extraction blocks,
not its complete query, scheduling policy or HAL. No algorithm instructions are
patched. Also checks our explicit untrusted-input rejection and output reset.
Requires pyelftools, Unicorn and a C++17 compiler.
"""
import argparse
import ctypes
import hashlib
import random
import struct
import subprocess
import tempfile
from pathlib import Path
from check_vivo_nice_motion import emulator
from unicorn.arm64_const import *

SHA = 'c3449dbb9867118173abc649de1fbab7edebebd826603cabe51fb9b58c0f07c5'
SIZE = 0xb30
DRIVER = r'''
#include "vivo-vcf-capture-control.h"
extern "C" int decode(const void* p, size_t n, unsigned char* out) {
    vivo_vcf::CaptureControlFields c;
    c.frameCount=777; c.frames[0].gain=777;
    auto status=vivo_vcf::readCaptureControlFields(p,n,c);
    std::memset(out,0,0xb30);
    auto w=[&](size_t pos, uint32_t x) {
        for(int i=0;i<4;++i)out[pos+i]=uint8_t(x>>(8*i));
    };
    auto f=[&](size_t pos,float x) {uint32_t b;std::memcpy(&b,&x,4);w(pos,b);};
    w(0,c.frameCount);w(4,c.batchCount);w(8,c.remosaicType);
    out[12]=c.needImageEcho;out[13]=c.needImageEchoYuvProcess;
    out[14]=c.needSelectPreferred;out[15]=c.needSubCam;out[16]=c.remosaicSizeType;
    for(size_t i=0;i<c.MaxBatches;++i){w(0x14+4*i,c.batchFrameCounts[i]);w(0x54+4*i,c.batchAlgoTypes[i]);}
    for(size_t i=0;i<c.MaxFrames;++i){auto& v=c.frames[i];size_t o=0x94+20*i;
        w(o,v.format);f(o+4,v.ev);f(o+8,v.gain);f(o+12,v.shutter);w(o+16,v.direction);}
    w(0xb10,c.shot2shotDepth);w(0xb14,c.countDown);w(0xb18,c.frameCatchMode);
    f(0xb28,c.pastFrameIsoThreshold);f(0xb2c,c.pastFrameExposureThreshold);
    return int(status);
}
'''


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('library', type=Path)
    args = ap.parse_args()
    if hashlib.sha256(args.library.read_bytes()).hexdigest() != SHA:
        raise SystemExit('Unsupported libvcf_core.so')
    u, _ = emulator(args.library)
    wire, native, stack = 0x1070000, 0x1080000, 0x1ffe000
    u.reg_write(UC_ARM64_REG_SP, stack)
    u.mem_write(stack + 0x30, struct.pack('<Q', wire))
    u.reg_write(UC_ARM64_REG_X20, native)
    rng = random.Random(20260920)
    blocks = 0
    with tempfile.TemporaryDirectory(prefix='vcf-control-') as tmp:
        tmp = Path(tmp)
        (tmp/'driver.cpp').write_text(DRIVER)
        include = Path(__file__).resolve().parents[1]/'app/src/main/cpp'
        subprocess.run(['c++', '-std=c++17', '-O2', '-shared', '-fPIC', '-Wall', '-Wextra', '-Werror',
                        '-I', str(include), str(tmp/'driver.cpp'), '-o', str(tmp/'driver.so')], check=True)
        decode = ctypes.CDLL(str(tmp/'driver.so')).decode
        decode.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p]
        decode.restype = ctypes.c_int
        def run(blob, count=None):
            # Deliberately unaligned source, with distinct bytes at both ends.
            data = ctypes.create_string_buffer(b'\xa5'+blob+b'\x5a')
            out = ctypes.create_string_buffer(SIZE)
            status = decode(ctypes.addressof(data)+1, len(blob) if count is None else count, out)
            return status, out.raw
        for case in range(40):
            source = bytearray(rng.randbytes(SIZE))
            nf, nb = (32, 16) if case == 0 else (rng.randrange(1, 33), rng.randrange(1, 17))
            struct.pack_into('<II', source, 0, nf, nb)
            for i in range(nf):
                struct.pack_into('<fff', source, 0x98+20*i,
                                 rng.uniform(-12,12), rng.uniform(0,256), rng.uniform(0,1000000))
            struct.pack_into('<ff', source, 0xb28, .125, .375)
            status, decoded = run(source)
            assert status == 0
            u.mem_write(wire, bytes(source))
            for i in range(nf):
                u.reg_write(UC_ARM64_REG_X26, i)
                u.emu_start(0x596a0, 0x596dc, count=40)
                fields = [u.reg_read(r) for r in (UC_ARM64_REG_W25, UC_ARM64_REG_S10,
                          UC_ARM64_REG_S9, UC_ARM64_REG_S8, UC_ARM64_REG_W22)]
                assert struct.pack('<5I', *fields) == decoded[0x94+20*i:0xa8+20*i]
                blocks += 1
            for i in range(nb):
                u.reg_write(UC_ARM64_REG_X22, i)
                u.reg_write(UC_ARM64_REG_X20, native)
                u.emu_start(0x5954c, 0x59578, count=40)
                for offset in (0x14+4*i, 0x54+4*i):
                    assert bytes(u.mem_read(native+offset, 4)) == decoded[offset:offset+4]
                blocks += 1
            u.reg_write(UC_ARM64_REG_X20, native)
            u.emu_start(0x593cc, 0x59424, count=40)
            assert bytes(u.mem_read(native+12,5)) == decoded[12:17]
            u.reg_write(UC_ARM64_REG_X8, wire)
            u.emu_start(0x5ae7c, 0x5ae8c, count=10)
            assert bytes(u.mem_read(native+0x174,8)) == decoded[0xb28:0xb30]
            blocks += 2
        rejected = 0
        for length in range(SIZE):
            status, decoded = run(source[:length])
            assert status == 2 and decoded == bytes(SIZE), (length,status)
            rejected += 1
        for nf, nb, expected in [(0,1,3),(1,0,3),(0,0,3),(33,1,4),(1,17,4),(0xffffffff,1,4)]:
            bad = bytearray(source);struct.pack_into('<II',bad,0,nf,nb)
            status, decoded = run(bad)
            assert status == expected and decoded == bytes(SIZE)
            rejected += 1
        for offset in (0x98,0x9c,0xa0,0xb28,0xb2c):
            for value in (float('inf'),float('-inf'),float('nan')):
                bad=bytearray(source);struct.pack_into('<f',bad,offset,value)
                status, decoded=run(bad)
                assert status == 5 and decoded == bytes(SIZE)
                rejected += 1
        out=ctypes.create_string_buffer(SIZE)
        assert decode(None,0,out)==1 and out.raw==bytes(SIZE)
        assert decode(None,SIZE,out)==2 and out.raw==bytes(SIZE)
        print(f'PASS {blocks} original ARM64 field-extraction blocks; {rejected+2} rejection/reset cases')
        print('Full query, shutter units, direction enums and capture integration are not tested.')


if __name__ == '__main__':
    main()
