#!/usr/bin/env python3
"""Execute pinned TCE FastTM normalization and log-output conversion on ARM64.

This recovers a host contract, not a connected photographic tone pipeline.
No library instructions or imported functions are replaced. Optional source
extraction preserves the original embedded OpenCL text for further inspection.
"""
import argparse
import ctypes
import subprocess
import tempfile
import hashlib
import json
import re
import struct
from pathlib import Path

import numpy as np
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM
from unicorn.arm64_const import (UC_ARM64_REG_CPACR_EL1, UC_ARM64_REG_SP,
    UC_ARM64_REG_LR, UC_ARM64_REG_PC, UC_ARM64_REG_X0, UC_ARM64_REG_X1,
    UC_ARM64_REG_X2, UC_ARM64_REG_X3, UC_ARM64_REG_X4, UC_ARM64_REG_S0)

DONOR_SHA256 = '9f5deac3bc68fc86fcf16b98f43c232a9642bc309c7d5d42c88d6a4b596b892d'


def image_descriptor(width, height, address, stride):
    result = bytearray(64)
    struct.pack_into('<III', result, 0, 0, width, height)
    struct.pack_into('<Q', result, 16, address)
    struct.pack_into('<I', result, 48, stride)
    return bytes(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('library', type=Path)
    parser.add_argument('--sources', type=Path, help='Optional OpenCL evidence output directory')
    args = parser.parse_args()
    if hashlib.sha256(args.library.read_bytes()).hexdigest() != DONOR_SHA256:
        raise ValueError('Unsupported TCE donor')
    u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    u.mem_map(0, 0x800000)
    u.mem_map(0x1000000, 0x400000)
    with args.library.open('rb') as f:
        elf = ELFFile(f)
        for segment in elf.iter_segments():
            if segment['p_type'] == 'PT_LOAD':
                u.mem_write(segment['p_vaddr'], segment.data())
        if args.sources:
            args.sources.mkdir(parents=True, exist_ok=True)
            ro = elf.get_section_by_name('.rodata')
            sources = []
            for match in re.finditer(rb'[\x09\x0a\x0d\x20-\x7e]{200,}', ro.data()):
                if b'__kernel' not in match[0]:
                    continue
                address = ro['sh_addr'] + match.start()
                name = f'tce-{address:x}.cl'
                (args.sources / name).write_bytes(match[0])
                sources.append({'address': hex(address), 'file': name,
                                'sha256': hashlib.sha256(match[0]).hexdigest()})
            (args.sources / 'provenance.json').write_text(json.dumps({
                'library_sha256': DONOR_SHA256, 'sources': sources}, indent=2) + '\n')
    u.reg_write(UC_ARM64_REG_CPACR_EL1, 3 << 20)
    obj, src, dst, srcdata, dstdata = 0x1000000, 0x1000100, 0x1000200, 0x1010000, 0x1110000
    stop = 0x5ff000
    # The log-domain float-output branch uses this pinned table, not a direct
    # multiplication by 16383. It indexes trunc(clamp(value,0,1)*9937).
    exp_table = np.frombuffer(bytes(u.mem_read(0x4c5aa, 9938 * 2)), dtype='<u2')
    # Compare deployable C++ conversion to the same unmodified ARM oracle.
    temporary = tempfile.TemporaryDirectory(prefix='nice-tce-cpp-')
    directory = Path(temporary.name)
    header = Path(__file__).resolve().parents[1] / 'app/src/main/cpp/vivo-nice-tone-conversion.h'
    source = directory / 'conversion.cpp'
    source.write_text('#include "' + str(header) + '\"\n' + r'''
extern "C" float normalize(unsigned short input) {
    return vivo_nice::FastTmConversion::normalize(input);
}
extern "C" unsigned short convert(float input, const unsigned short* lut) {
    vivo_nice::FastTmConversion::ExpTable table;
    std::copy_n(lut,table.size(),table.begin());
    return vivo_nice::FastTmConversion::logOutput(input,table);
}
''')
    subprocess.run(['c++','-std=c++17','-O2','-shared','-fPIC',str(source),'-o',str(directory/'conversion.so')],check=True)
    cpp = ctypes.CDLL(str(directory/'conversion.so'))
    cpp.normalize.argtypes=[ctypes.c_uint16];cpp.normalize.restype=ctypes.c_float
    cpp.convert.argtypes=[ctypes.c_float,ctypes.POINTER(ctypes.c_uint16)];cpp.convert.restype=ctypes.c_uint16
    table_ptr=exp_table.ctypes.data_as(ctypes.POINTER(ctypes.c_uint16))
    checked = 0
    for width, height in [(7, 5), (8, 5), (9, 5), (520, 43)]:
        count = width * height * 3
        raw = (np.arange(count, dtype=np.uint32) % 65536).astype('<u2').reshape(height, width * 3)
        for function, input_data, expected in [
            (0x39dee4, raw, raw.astype(np.float32) / np.float32(15615)),
            (0x39e670, np.linspace(-.5, 1.5, count, dtype=np.float32).reshape(raw.shape), None),
        ]:
            if expected is None:
                indices = (np.clip(input_data, 0, 1) * np.float32(9937)).astype(np.uint32)
                expected = np.minimum(exp_table[indices], 16383).astype('<u2')
            if function == 0x39dee4:
                actual = np.array([cpp.normalize(int(x)) for x in input_data.flat],dtype=np.float32).reshape(expected.shape)
            else:
                actual = np.array([cpp.convert(float(x),table_ptr) for x in input_data.flat],dtype=np.uint16).reshape(expected.shape)
            assert actual.tobytes() == expected.tobytes(), ('C++ conversion',hex(function),width,height)
            # Odd extra padding tests byte strides and ensures row padding is
            # neither interpreted as image data nor overwritten by SIMD tails.
            in_stride = input_data.shape[1] * input_data.dtype.itemsize + 16
            out_stride = expected.shape[1] * expected.dtype.itemsize + 32
            in_bytes = b''.join(row.tobytes() + b'\xa5' * 16 for row in input_data)
            output = bytearray(b'\xa5' * (height * out_stride))
            for y, row in enumerate(expected):
                output[y*out_stride:y*out_stride+row.nbytes] = row.tobytes()
            u.mem_write(srcdata, in_bytes)
            u.mem_write(dstdata, b'\xa5' * len(output))
            u.mem_write(src, image_descriptor(width, height, srcdata, in_stride))
            u.mem_write(dst, image_descriptor(width, height, dstdata, out_stride))
            for reg, value in [(UC_ARM64_REG_SP, 0x13ff000), (UC_ARM64_REG_LR, stop),
                (UC_ARM64_REG_X0, obj), (UC_ARM64_REG_X1, src), (UC_ARM64_REG_X2, dst),
                (UC_ARM64_REG_X3, 2), (UC_ARM64_REG_X4, 0),
                (UC_ARM64_REG_S0, struct.unpack('<I', struct.pack('<f', 15615))[0])]:
                u.reg_write(reg, value)
            u.emu_start(function, stop, count=8000000)
            assert u.reg_read(UC_ARM64_REG_PC) == stop, 'Original function did not return'
            assert u.reg_read(UC_ARM64_REG_X0) == 0, 'Original function returned an error'
            assert bytes(u.mem_read(dstdata, len(output))) == bytes(output), (hex(function), width, height)
            checked += count
    print(f'PASS: {checked} C++ TCE normalization/log-output samples match original ARM64 exactly; padding intact')
    temporary.cleanup()
    print('This is host arithmetic validation; real-image FastTM integration remains separate.')


if __name__ == '__main__':
    main()
