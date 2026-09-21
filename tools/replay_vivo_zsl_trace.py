#!/usr/bin/env python3
import ctypes
import struct
import subprocess
import sys
import tempfile
from pathlib import Path
from check_vivo_vcf_nice_hdr_plan import DRIVER

sys.path.insert(0, str(Path(__file__).parent/'vivo-zsl-live'))
from analyze import events, block, analyze


def replay(path):
    trace = events(path)
    summary = analyze(trace)
    before = next(e for e in trace if e['event'] == 'nice_enter')
    after = next(e for e in trace if e['event'] == 'nice_leave' and e['id'] == before['id'])
    preview = block(before['preview'], 0x3e48)
    initial = bytearray(block(before['control'], 0xb34)[:0xb30])
    expected = block(after['control'], 0xb34)
    raw = block(before['rawFrames'], 320)
    query = block(before['query'], 0x48)
    past, future = struct.unpack_from('<II', preview, 0x2d08)
    arrays = b''.join(preview[o:o+64] for o in [0x2d10,0x2d50,0x2d90,0x2dd0,0x2ec0,0x2f00])
    # The transport reader rejects zero-count defaults; the producer initializes
    # these two counts unconditionally before consuming the preserved fields.
    struct.pack_into('<II', initial, 0, 1, 1)
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-shared','-fPIC','-Wall','-Wextra','-Werror',
            '-I',str(Path(__file__).resolve().parents[1]/'app/src/main/cpp'),str(root/'driver.cpp'),
            '-o',str(root/'driver.so')],check=True)
        driver = ctypes.CDLL(str(root/'driver.so')).build
        driver.argtypes = [ctypes.c_uint32,ctypes.c_uint32,ctypes.c_int,ctypes.c_int,ctypes.c_int,
                           ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p]
        output = ctypes.create_string_buffer(0xc74)
        status = driver(past,future,struct.unpack_from('<I',preview,0x3d8c)[0]!=0,
            block(before['imageEchoWithPast'],1)[0]!=0,struct.unpack_from('<i',query,0x20)[0],
            ctypes.create_string_buffer(arrays),ctypes.create_string_buffer(bytes(initial)),
            ctypes.create_string_buffer(raw),output)
        if status != 0: raise AssertionError('Recorded query rejected by port: '+str(status))
        for start,end in [(0,0x314),(0xb18,0xb1c),(0xb20,0xb24)]:
            if output.raw[start:end] != expected[start:end]:
                raise AssertionError('Recorded control mismatch at '+hex(start))
        if output.raw[0xb30:0xc70] != block(after['rawFrames'],320):
            raise AssertionError('Recorded RAW descriptor mismatch')
        if output.raw[0xc70:] != block(after['query'],0x48)[0x44:0x48]:
            raise AssertionError('Recorded shutter accumulation mismatch')
    print('PASS: portable NICE plan matches recorded phone output: counts, batches, frames, catch mode, RAW descriptors and shutter sum')
    print('Future delivery observed:',summary['futureDeliveryObserved'])
    print('This replays the recorded scene decision; it does not generate that decision or exercise Camera2/NPU.')

if __name__ == '__main__': replay(Path(sys.argv[1]))
