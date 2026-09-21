#!/usr/bin/env python3
import argparse
import ctypes
import hashlib
import math
import random
import struct
import subprocess
import tempfile
from pathlib import Path
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_PC, UC_ARM64_REG_LR, UC_ARM64_REG_S0
from check_vivo_nice_motion import emulator, invoke

SHA='3f4ee25636fb04023c338ded9f087734ab8aeb429c4e50adf19a822e5398c188'
DRIVER=r'''
#include "vivo-nice-scene-rules.h"
extern "C" int normal(const int* v,long long ns) {
 return vivo_nice::niceNormalBack({v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7],ns,bool(v[8])});
}
extern "C" int night(float lux,int threshold,int state,int mode) {
 bool fast=vivo_nice::niceFastNight(lux,threshold,state);
 return int(fast)+2*int(vivo_nice::niceQuickNight(mode,fast));
}
extern "C" int near(float lux,float minimum) {
 return vivo_nice::niceNearMinExposure(lux,minimum);
}
extern "C" int echo(const int* v,float lux) {
 return vivo_nice::niceImageEcho({v[0],v[1],v[2],v[3],lux,bool(v[4]),bool(v[5]),bool(v[6])});
}
'''
p=argparse.ArgumentParser();p.add_argument('library',type=Path);args=p.parse_args()
assert hashlib.sha256(args.library.read_bytes()).hexdigest()==SHA
u,_=emulator(args.library)
scene,preview,tuning,decision,log,ae=0x1100000,0x1200000,0x1300000,0x1400000,0x1500000,0x1600000

def write(addr,fmt,*v):u.mem_write(addr,struct.pack('<'+fmt,*v))
write(scene+0x1d8,'Q',preview);write(scene+0x160,'Q',tuning)
write(preview+8,'Q',ae);write(0x3a8de0,'Q',log);write(log,'i',6)
platform=0
# External platform query and libm only; scene decision instructions are untouched.
def external(uc,addr,size,data):
 if addr==0x37fb50:uc.reg_write(UC_ARM64_REG_X0,platform)
 elif addr==0x382f20:
  value=struct.unpack('<f',struct.pack('<I',uc.reg_read(UC_ARM64_REG_S0)))[0]
  result=struct.unpack('<I',struct.pack('<f',math.log10(value)))[0]
  uc.reg_write(UC_ARM64_REG_S0,result)
 else:return
 uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
u.hook_add(UC_HOOK_CODE,external,begin=0x37fb50,end=0x37fb50)
u.hook_add(UC_HOOK_CODE,external,begin=0x382f20,end=0x382f20)
root=Path(__file__).resolve().parents[1]
rng=random.Random(2454);counts=dict(normal=0,night=0,near=0,echo=0)
with tempfile.TemporaryDirectory() as tmp:
 d=Path(tmp);(d/'driver.cpp').write_text(DRIVER)
 subprocess.run(['c++','-std=c++17','-Wall','-Wextra','-Werror','-O2','-shared','-fPIC','-I',str(root/'app/src/main/cpp'),str(d/'driver.cpp'),'-o',str(d/'driver.so')],check=True)
 lib=ctypes.CDLL(str(d/'driver.so'))
 lib.normal.argtypes=[ctypes.c_void_p,ctypes.c_longlong]
 lib.night.argtypes=[ctypes.c_float,ctypes.c_int,ctypes.c_int,ctypes.c_int]
 lib.near.argtypes=[ctypes.c_float,ctypes.c_float];lib.echo.argtypes=[ctypes.c_void_p,ctypes.c_float]
 for _ in range(3000):
  v=[rng.randrange(500),rng.randrange(500),rng.choice([0,13,15,31]),rng.randrange(2),rng.randrange(39,46),rng.choice([1,70,71,72]),rng.randrange(100),rng.randrange(2),rng.randrange(2)]
  ns=rng.randrange(100000000)
  for off,index in [(0xb4,0),(0x1f0,4),(0x1a4,7)]:write(scene+off,'i',v[index])
  write(scene+0x186,'B',v[8]);write(scene+0x1b0,'q',ns)
  for off,index in [(0x11ac,2),(0x3bd0,3),(0x440,5)]:write(preview+off,'i',v[index])
  write(tuning+8,'i',v[1]);write(tuning+0x64,'i',v[6])
  assert lib.normal((ctypes.c_int*9)(*v),ns)==invoke(u,0x29a714,(decision,scene)),(v,ns)
  counts['normal']+=1
 for state in (0,1,2):
  for mode in (0,1,2):
   for threshold in (-1,0,280,320):
    for lux in (threshold-1.,threshold-.1,float(threshold),threshold+.1,threshold+1.):
     write(preview+0x43c,'f',lux);write(preview+0x3de0,'i',state);write(ae+0xc8,'i',threshold);write(scene+0x134,'i',mode)
     fast=invoke(u,0x29b508,(decision,scene));quick=invoke(u,0x29b470,(decision,scene))
     assert lib.night(lux,threshold,state,mode)==fast+2*quick,(lux,threshold,state,mode)
     assert u.mem_read(decision+0x240,1)[0]==fast
     counts['night']+=1
 for minimum in (1.,1.9,2.,10.,100.,1000.,10000.,100000.):
  for lux in range(-5,400):
   write(scene+0xb0,'f',minimum);write(preview+0x43c,'f',float(lux))
   assert lib.near(lux,minimum)==invoke(u,0x29a83c,(decision,scene)),(lux,minimum)
   counts['near']+=1
 for capture in range(38,47):
  for ui in (-1,0,1,12,13,14,30,31,32,70,71,72,73,100):
   for flags in range(8):
    for platform in (0,1):
     v=[capture,ui,platform,280,flags&1,(flags>>1)&1,(flags>>2)&1]
     for lux in (279.9,280.,280.1):
      write(scene+0x1f0,'i',capture);write(preview+0x440,'i',ui)
      write(preview+0x43c,'f',lux);write(tuning+0x34,'i',280)
      actual=lib.echo((ctypes.c_int*7)(*v),lux)
      expected=invoke(u,0x29b540,(decision,scene,*v[4:]))
      assert actual==expected,(v,lux,actual,expected)
      counts['echo']+=1
print('PASS: original ARM64 scene decisions match portable rules:',counts)
print('These tests do not establish Camera2 input provenance or complete scene classification.')
