#!/usr/bin/env python3
"""Compare complete original TCE gamma-region selection with the C++ port.

Uses synthetic tables and thresholds, not fabricated capture lux metadata.
The original routine runs unchanged with logging disabled. Includes scalar,
SIMD and remainder lengths and the native no-write-above-last-range behavior.
"""
import argparse
import ctypes
import hashlib
import random
import struct
import subprocess
import tempfile
from pathlib import Path
from check_vivo_nice_motion import emulator, invoke
from check_vivo_nice_tce_contract import TCE_SHA256

DRIVER = r'''
#include "vivo-nice-tce-gamma.h"
extern "C" int select_gamma(int32_t lux, int n, int size, const int32_t* lower,
        const int32_t* upper, const int32_t* tables, int32_t* result) {
    std::vector<vivo_nice::tce_contract::GammaRegion> regions;
    for (int i=0;i<n;++i) regions.push_back({lower[i],upper[i],
        std::vector<int32_t>(tables+i*size,tables+(i+1)*size)});
    std::vector<int32_t> output(result,result+size);
    try {
        bool selected=vivo_nice::tce_contract::selectGamma(lux,regions,output);
        std::copy(output.begin(),output.end(),result);
        return selected?1:0;
    } catch(const std::invalid_argument&) {return -1;}
}
'''


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('library', type=Path)
    args = ap.parse_args()
    if hashlib.sha256(args.library.read_bytes()).hexdigest() != TCE_SHA256:
        raise ValueError('Unsupported TCE donor')
    u, _ = emulator(args.library)
    rng = random.Random(20260923)
    lower_addr, upper_addr, table_addr, output_addr = 0x1100000, 0x1110000, 0x1120000, 0x1160000
    with tempfile.TemporaryDirectory(prefix='tce-gamma-') as temporary:
        root = Path(temporary)
        (root/'test.cpp').write_text(DRIVER)
        include = Path(__file__).resolve().parents[1]/'app/src/main/cpp'
        subprocess.run(['c++','-std=c++17','-O2','-shared','-fPIC','-Wall','-Wextra','-Werror',
                        '-I',str(include),str(root/'test.cpp'),'-o',str(root/'test.so')],check=True)
        lib = ctypes.CDLL(str(root/'test.so'))
        lib.select_gamma.argtypes = [ctypes.c_int32, ctypes.c_int, ctypes.c_int] + [ctypes.c_void_p]*4
        lib.select_gamma.restype = ctypes.c_int
        checked = 0
        for size in [1, 7, 8, 9, 16, 31, 256, 257]:
            for region_count in [1, 2, 4, 20]:
                lower, upper = [], []
                edge = -200
                for _ in range(region_count):
                    lower.append(edge + rng.randrange(0, 25))
                    upper.append(lower[-1] + rng.randrange(0, 25))
                    edge = upper[-1]
                tables = [rng.randrange(65536) for _ in range(region_count*size)]
                points = sorted(set([lower[0]-1, upper[-1]+1] +
                    [v+d for v in lower+upper for d in [-1,0,1]] +
                    [rng.randrange(lower[0]-10,upper[-1]+11) for _ in range(10)]))
                lo = (ctypes.c_int32*region_count)(*lower)
                hi = (ctypes.c_int32*region_count)(*upper)
                ts = (ctypes.c_int32*len(tables))(*tables)
                u.mem_write(lower_addr,bytes(lo));u.mem_write(upper_addr,bytes(hi))
                u.mem_write(table_addr,bytes(ts))
                for lux in points:
                    marker = [0x12345678]*size
                    output = (ctypes.c_int32*size)(*marker)
                    guard = b'\xa5'*16
                    u.mem_write(output_addr-16,guard+bytes(output)+guard)
                    invoke(u,0x3b1590,(0,lux & 0xffffffff,region_count,size,
                                      lower_addr,upper_addr,table_addr,output_addr))
                    result = lib.select_gamma(lux,region_count,size,lo,hi,ts,output)
                    assert result == int(lux <= upper[-1]), (lux,lower,upper,result)
                    expected = bytes(u.mem_read(output_addr-16,size*4+32))
                    assert expected == guard+bytes(output)+guard, (size,lux,lower,upper)
                    checked += 1

        # Added validation must leave the caller's output unchanged on failure.
        invalid = [([2],[1],[0]), ([0,5],[10,20],[0,1]), ([0],[1],[-1]), ([0],[1],[65536])]
        for lower,upper,tables in invalid:
            n=len(lower);lo=(ctypes.c_int32*n)(*lower);hi=(ctypes.c_int32*n)(*upper)
            ts=(ctypes.c_int32*n)(*tables);out=(ctypes.c_int32*1)(123)
            assert lib.select_gamma(0,n,1,lo,hi,ts,out)==-1
            assert out[0]==123
    print(f'PASS: {checked} full original TCE gamma selections match C++; output guards intact; 4 invalid cases rejected')
    print('Scene lux acquisition and photographic TCE execution are not covered.')


if __name__ == '__main__':
    main()
