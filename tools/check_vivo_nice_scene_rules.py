#!/usr/bin/env python3
"""Execute original VAF motion/more-frame decisions against portable rules.

Input measurements and tuning are injected in their recovered native fields;
this does not establish their Camera2 provenance or full scene classification.
"""
import argparse
import ctypes
import hashlib
import itertools
import random
import struct
import subprocess
import tempfile
from pathlib import Path
from check_vivo_nice_motion import emulator,invoke

SHA='3f4ee25636fb04023c338ded9f087734ab8aeb429c4e50adf19a822e5398c188'
DRIVER=r'''
#include "vivo-nice-scene-rules.h"
extern "C" int motion(float value,float lo,float hi) {
 try {auto r=vivo_nice::classifyHdrMotion(value,lo,hi);return r.shaking1+2*r.shaking2;}
 catch(const std::invalid_argument&){return -1;}
}
extern "C" int more(float lux,int threshold,int tripod) {
 try {return vivo_nice::niceHdrMoreFrames(lux,threshold,tripod);}
 catch(const std::invalid_argument&){return -1;}
}
extern "C" int ev0(const int* i,const float* f) {
 try {return vivo_nice::niceHdrMoreEv0({i[0],i[1],i[2],i[3],i[4],i[5],f[0],f[1],f[2],f[3],f[4]});}
 catch(const std::invalid_argument&){return -1;}
}
'''

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('library',type=Path);args=ap.parse_args()
    assert hashlib.sha256(args.library.read_bytes()).hexdigest()==SHA
    u,_=emulator(args.library)
    scene,preview,tuning,motion,log,tripod=0x1100000,0x1200000,0x1300000,0x1400000,0x1500000,0x1600000
    u.mem_write(scene+0x1d8,struct.pack('<Q',preview))
    u.mem_write(scene+0x160,struct.pack('<Q',tuning))
    u.mem_write(scene+0x178,struct.pack('<Q',motion))
    u.mem_write(0x3a8de0,struct.pack('<Q',log));u.mem_write(log,struct.pack('<i',6))
    repo=Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix='nice-scene-rules-') as tmp:
        tmp=Path(tmp);(tmp/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-shared','-fPIC','-Wall','-Wextra','-Werror',
                        '-I',str(repo/'app/src/main/cpp'),str(tmp/'driver.cpp'),'-o',str(tmp/'driver.so')],check=True)
        lib=ctypes.CDLL(str(tmp/'driver.so'))
        lib.motion.argtypes=[ctypes.c_float]*3
        lib.more.argtypes=[ctypes.c_float,ctypes.c_int,ctypes.c_int]
        lib.ev0.argtypes=[ctypes.c_void_p,ctypes.c_void_p]
        rng=random.Random(2454);motion_cases=0
        for lo,hi in [(0.,1.),(1.,1.),(2.,1.),(-2.,-1.)]+[(rng.uniform(-5,5),rng.uniform(-5,5)) for _ in range(100)]:
          for value in (lo,hi,lo-.0001,lo+.0001,hi-.0001,hi+.0001,0.):
            u.mem_write(motion,struct.pack('<2f',lo,hi));u.mem_write(preview+0x3ae0,struct.pack('<f',value))
            a=invoke(u,0x29ab8c,(0,scene));b=invoke(u,0x29ac44,(0,scene))
            assert lib.motion(value,lo,hi)==a+2*b,(value,lo,hi,a,b)
            motion_cases+=1
        more_cases=0
        for threshold,lux,on in itertools.product((-1,0,1,280,320),(-1.9,-1.,-.9,0.,1.,1.9,279.9,280.,280.9,281.,320.,321.),(0,1)):
            u.mem_write(tuning+0x18,struct.pack('<i',threshold))
            u.mem_write(preview+0x43c,struct.pack('<f',lux));u.mem_write(tripod,bytes([on]))
            assert lib.more(lux,threshold,on)==invoke(u,0x29a920,(0,scene,tripod))
            more_cases+=1
        ev_cases=[]
        # Exhaust all UI modes and both zoom interval endpoint conventions.
        for ui,quad,zoom in itertools.product(range(-1,81),(0,1,2),(1.9999,2.,3.,4.,4.0001)):
            ev_cases.append(([2,321,320,ui,0,quad],[zoom,2.,4.,2.,4.]))
        for _ in range(1000):
            ev_cases.append(([rng.randrange(5),rng.randrange(280,330),rng.randrange(280,330),
                rng.choice((1,7,13,39,70,74,77)),rng.randrange(3),rng.randrange(4)],
                [rng.uniform(0,8),2.,4.,3.,7.]))
        for ints,floats in ev_cases:
            lens,lux,threshold,ui,dual,quad=ints
            u.mem_write(tuning,struct.pack('<i',quad));u.mem_write(tuning+0x1c,struct.pack('<i4f',threshold,*floats[1:]))
            u.mem_write(preview+0x470,struct.pack('<i',lens));u.mem_write(preview+0x440,struct.pack('<i',ui))
            u.mem_write(preview+0x3bfc,struct.pack('<i',dual));u.mem_write(scene+0x90,struct.pack('<f',floats[0]))
            actual=lib.ev0((ctypes.c_int*6)(*ints),(ctypes.c_float*5)(*floats))
            expected=invoke(u,0x29a9d8,(0,scene,lux))
            assert actual==expected,(ints,floats,actual,expected)
        assert lib.motion(float('nan'),0,1)==-1
        assert lib.more(float('inf'),320,0)==-1
        print(f'PASS: {motion_cases} native motion pairs, {more_cases} native more-frame decisions, {len(ev_cases)} native more-EV0 decisions')
        print('Native measurement provenance and full scene decision remain outside this test.')

if __name__=='__main__':main()
