#!/usr/bin/env python3
"""Check recovered CRE/TCE boundary operations against unmodified ARM64.

Runs CRE's complete log-EV function and TCE's copy/handle blocks. This does
not run TCE creation, scene selection, GPU processing, or a photographic shot.
"""
import argparse
import ctypes
import hashlib
import math
import random
import struct
import subprocess
import tempfile
from pathlib import Path

from check_vivo_nice_motion import emulator, invoke, CRE_SHA256
from unicorn.arm64_const import *

TCE_SHA256 = '9f5deac3bc68fc86fcf16b98f43c232a9642bc309c7d5d42c88d6a4b596b892d'
DRIVER = r'''
#include <algorithm>
#include "vivo-nice-tce-contract.h"
using namespace vivo_nice::tce_contract;
extern "C" int exposure(const Exposure* p, float* result) {
    try { *result = logExposure(*p); return 0; }
    catch (const std::invalid_argument&) { return 1; }
}
extern "C" int encoding(const Exposure* p, int bits, LogEncoding* result) {
    try { *result = logEncoding(*p, bits); return 0; }
    catch (const std::invalid_argument&) { return 1; }
}
extern "C" void bind_outputs(OutputPrefix* out, const Image* images, uint64_t extra) {
    bindAuxiliaryOutputs(*out, images[0], {images[1], images[2], images[3]}, extra);
}
extern "C" void layout(size_t* out) {
    const size_t values[] = {createArgumentBytes, processArgumentBytes,
        outputMinimumBytes, sizeof(Image), offsetof(Image, nativeHandle),
        offsetof(Image, dataSize)};
    std::copy(std::begin(values), std::end(values), out);
}
'''


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('cre', type=Path)
    ap.add_argument('tce', type=Path)
    args = ap.parse_args()
    for path, expected in [(args.cre, CRE_SHA256), (args.tce, TCE_SHA256)]:
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('Unsupported donor: ' + str(path))
    cre, _ = emulator(args.cre)
    tce, _ = emulator(args.tce)
    with tempfile.TemporaryDirectory(prefix='nice-tce-contract-') as temporary:
        root = Path(temporary)
        (root / 'test.cpp').write_text(DRIVER)
        include = Path(__file__).resolve().parents[1] / 'app/src/main/cpp'
        subprocess.run(['c++', '-std=c++17', '-O2', '-shared', '-fPIC',
            '-Wall', '-Wextra', '-Werror', '-I', str(include),
            str(root / 'test.cpp'), '-o', str(root / 'test.so')], check=True)
        lib = ctypes.CDLL(str(root / 'test.so'))
        lib.exposure.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_float)]
        lib.exposure.restype = ctypes.c_int
        lib.encoding.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p]
        lib.encoding.restype = ctypes.c_int
        lib.layout.argtypes = [ctypes.POINTER(ctypes.c_size_t)]
        layout = (ctypes.c_size_t * 6)()
        lib.layout(layout)
        assert list(layout) == [0x4c8, 0x6d0, 0x2e0, 0x78, 0x70, 0x50]

        # Check the producer of the memcpy size, and execute the actual call.
        # Imported memcpy only implements the requested memory copy.
        source, context = 0x1100000, 0x1200000
        rng = random.Random(20260922)
        for start, end, registers, destination, count in [
            (0x384f50, 0x384f60, {UC_ARM64_REG_X25: context}, context + 0x18, layout[0]),
            (0x39165c, 0x39166c, {UC_ARM64_REG_X20: context}, context + 0x7b8, layout[1]),
        ]:
            payload = bytes(rng.randrange(256) for _ in range(count))
            tce.mem_write(source, payload)
            tce.mem_write(destination - 16, b'\xa5' * (count + 32))
            for register, value in {UC_ARM64_REG_X20: source,
                                    UC_ARM64_REG_X26: source, **registers}.items():
                tce.reg_write(register, value)
            tce.emu_start(start, end, count=20)
            assert tce.reg_read(UC_ARM64_REG_PC) == end
            assert bytes(tce.mem_read(destination - 16, count + 32)) == b'\xa5'*16 + payload + b'\xa5'*16

        # Process mutates both output image handles, including +0xe8.
        # A 64- or 120-byte output allocation is therefore insufficient.
        input_arg, output_arg, internal = 0x1300000, 0x1400000, 0x1500000
        for mask in range(8):
            before_input = bytearray(layout[1])
            before_output = bytearray(layout[2])
            for bit, buffer, offset in [(0, before_input, 0x70),
                                        (1, before_output, 0x70),
                                        (2, before_output, 0xe8)]:
                struct.pack_into('<Q', buffer, offset, 0x1234 if mask & (1 << bit) else 0)
            tce.mem_write(input_arg, bytes(before_input))
            tce.mem_write(output_arg, bytes(before_output))
            tce.mem_write(internal + 0x260, struct.pack('<Q', 0x4321))
            for register, value in [(UC_ARM64_REG_X26, input_arg),
                                     (UC_ARM64_REG_X22, output_arg),
                                     (UC_ARM64_REG_X19, internal)]:
                tce.reg_write(register, value)
            tce.emu_start(0x391898, 0x3918e4, count=40)
            assert tce.reg_read(UC_ARM64_REG_PC) == 0x3918e4
            for bit, buffer, offset in [(0, before_input, 0x70),
                                        (1, before_output, 0x70),
                                        (2, before_output, 0xe8)]:
                if mask & (1 << bit):
                    struct.pack_into('<Q', buffer, offset, 0x4321)
            assert bytes(tce.mem_read(input_arg, layout[1])) == bytes(before_input)
            assert bytes(tce.mem_read(output_arg, layout[2])) == bytes(before_output)

        # Run CRE's actual descriptor-copy block with distinct randomized bytes
        # in every field. Prove that borrowed metadata/pointers and untouched
        # RGB/mode fields survive; this does not execute allocation or TCE GPU.
        lib.bind_outputs.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint64]
        node, capture, auxiliary, args_address = 0x1600000, 0x1610000, 0x1620000, 0x1630000
        for _ in range(24):
            images = bytes(rng.randrange(256) for _ in range(4 * 0x78))
            initial = bytes(rng.randrange(256) for _ in range(layout[2]))
            extra = rng.randrange(1 << 64)
            guard = b'\xa5' * 16
            cre.mem_write(node + 0x10a0 - 16, guard + initial + guard)
            cre.mem_write(capture + 0x90, struct.pack('<Q', auxiliary))
            cre.mem_write(auxiliary + 0x80, images)
            cre.mem_write(args_address + 0x9f0, struct.pack('<Q', extra))
            for register, value in [(UC_ARM64_REG_X20, node), (UC_ARM64_REG_X21, capture),
                                     (UC_ARM64_REG_X9, auxiliary)]:
                cre.reg_write(register, value)
            cre.emu_start(0x38aeac, 0x38afe8, count=100)
            assert cre.reg_read(UC_ARM64_REG_PC) == 0x38afe8
            cre.reg_write(UC_ARM64_REG_X9, args_address)
            cre.emu_start(0x38b01c, 0x38b024, count=3)
            assert cre.reg_read(UC_ARM64_REG_PC) == 0x38b024
            output = ctypes.create_string_buffer(initial, len(initial))
            input_images = ctypes.create_string_buffer(images)
            lib.bind_outputs(output, input_images, extra)
            assert bytes(cre.mem_read(node + 0x10a0 - 16, layout[2] + 32)) == guard + output.raw + guard

        # Original log() imports use host libm, as does the compiled port.
        # Native conversions, signed delta handling and add order are original.
        cases = [(1., 0., 1., 1., 1.), (1., -1000., 1., 1., 1.),
                 (1., 1000., 1., 1., 1.), (2., -0., 100., 4., 8.)]
        cases += [(10**rng.uniform(-5, 5), rng.uniform(-16000, 16000),
                   rng.uniform(-100, 100), 10**rng.uniform(-5, 5),
                   10**rng.uniform(-5, 5)) for _ in range(600)]
        for values in cases:
            payload = struct.pack('<5f', *values)
            cre.mem_write(context + 0x14, bytes(4))  # silent log branch
            cre.mem_write(source, payload)
            invoke(cre, 0x391f88, (context, source))
            native_bits = cre.reg_read(UC_ARM64_REG_S0)
            native_input = ctypes.create_string_buffer(payload)
            actual = ctypes.c_float()
            assert lib.exposure(native_input, ctypes.byref(actual)) == 0
            assert struct.unpack('<I', struct.pack('<f', actual.value))[0] == native_bits, values

        encoded = 0
        for bits in [16, 32]:
            for _ in range(150):
                values = [2**rng.uniform(-2, 3), rng.uniform(-1000, 1000),
                          rng.choice([0., 128., 236.5, 354.5, 9937., 16383., 65535.]),
                          2**rng.uniform(-2, 3), 2**rng.uniform(-2, 3)]
                payload = struct.pack('<5f', *values)
                cre.mem_write(source, payload)
                cre.mem_write(context + 0x1780, payload)
                invoke(cre, 0x391f88, (context, source))
                # Execute original argument-setting blocks, stopping before
                # each GPU setter. No GPU function or pixels are emulated.
                sp = 0x1ffe000
                cre.mem_write(source + 0x100, struct.pack('<I', cre.reg_read(UC_ARM64_REG_S0)))
                for register, value in [(UC_ARM64_REG_SP, sp), (UC_ARM64_REG_X21, source + 0x100),
                                         (UC_ARM64_REG_X20, context),
                                         (UC_ARM64_REG_W27, 1 if bits == 32 else 65535)]:
                    cre.reg_write(register, value)
                native = bytearray()
                for start, end, size in [(0x38e208, 0x38e23c, 4), (0x38e240, 0x38e258, 4),
                                          (0x38e25c, 0x38e288, 2), (0x38e28c, 0x38e2b8, 2),
                                          (0x38e2bc, 0x38e2dc, 4)]:
                    cre.emu_start(start, end, count=60)
                    assert cre.reg_read(UC_ARM64_REG_PC) == end
                    native += bytes(cre.mem_read(sp + 0x80, size))
                data = ctypes.create_string_buffer(payload)
                output = ctypes.create_string_buffer(16)
                assert lib.encoding(data, bits, output) == 0
                assert output.raw == bytes(native), (values, bits)
                encoded += 1

        rejected = 0
        for index in [0, 1, 3, 4]:
            for invalid in [math.nan, math.inf, -math.inf] + ([0., -1.] if index != 1 else []):
                values = [1., 0., 1., 1., 1.]
                values[index] = invalid
                payload = ctypes.create_string_buffer(struct.pack('<5f', *values))
                actual = ctypes.c_float(123.)
                assert lib.exposure(payload, ctypes.byref(actual)) == 1
                assert actual.value == 123.
                rejected += 1
    print(f'PASS: {len(cases)} CRE tone-EV and {encoded} log-encoding argument cases bit-exact; {rejected} invalid inputs rejected; '
          '2 TCE copies, 24 auxiliary-output bindings and 8 handle-mutation blocks verified')
    print('Boundary tests only; original TCE image processing is not connected.')


if __name__ == '__main__':
    main()
