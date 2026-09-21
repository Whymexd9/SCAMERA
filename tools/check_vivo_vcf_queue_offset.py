#!/usr/bin/env python3
import ctypes,hashlib,random,struct,subprocess,sys,tempfile
from pathlib import Path
from check_vivo_nice_motion import emulator,invoke
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import *

library=Path(sys.argv[1])
assert hashlib.sha256(library.read_bytes()).hexdigest()=='93a3149a70fea5f177bb0542278c47ec232fc477aa96cb7a63534b1f6b5f8dad'
u,_=emulator(library)
u.mem_write(0x2ffb40,struct.pack('<Q',0x1068000))
u.mem_write(0x2ffb48,struct.pack('<Q',0x1068100))
seen=[];motion_result=0;motion_next=0

def hook(u,pc,size,data):
    if pc==0x2f2a20:
        initial=ctypes.c_int32(u.reg_read(UC_ARM64_REG_W1)).value
        ptr=u.reg_read(UC_ARM64_REG_X5)
        seen.append((initial,struct.unpack('<i',u.mem_read(ptr,4))[0]))
        u.mem_write(ptr,struct.pack('<i',motion_next))
        u.reg_write(UC_ARM64_REG_X0,motion_result&0xffffffff)
    elif pc==0x2f2a08:
        u.reg_write(UC_ARM64_REG_PC,0x140078);return
    elif pc==0x2f0728:
        u.mem_write(u.reg_read(UC_ARM64_REG_X0),bytes(u.reg_read(UC_ARM64_REG_W2)))
    else:raise AssertionError(hex(pc))
    u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))
u.hook_add(UC_HOOK_CODE,lambda u,pc,size,data:u.reg_write(UC_ARM64_REG_PC,0x127744),begin=0x12763c,end=0x12763c)
for pc in (0x2f2a20,0x2f2a08,0x2f0728):u.hook_add(UC_HOOK_CODE,hook,begin=pc,end=pc)
source=r'''
#include "vivo-vcf-queue-offset.h"
extern "C" void check(const uint32_t* ids,int n,unsigned requested,int mode,int s0,int s1,
 const uint32_t* req,int rn,int next,int mr,int mn,int* out) {
 std::vector<uint32_t> q(ids,ids+n),r(req,req+rn);out[2]=0;out[3]=0;out[4]=0;
 out[0]=vivo_vcf::queueOffset(q,requested,mode,s0,s1,r,next,[&](int initial,int& only){
   out[2]=1;out[3]=initial;out[4]=only;only=mn;return mr;
 });out[1]=next;out[5]=vivo_vcf::pastQueueStart(out[0],q.size());
}
'''
rng=random.Random(2454)
with tempfile.TemporaryDirectory() as d:
 p=Path(d);(p/'check.cpp').write_text(source)
 subprocess.run(['c++','-std=c++17','-O2','-shared','-fPIC','-Wall','-Wextra','-Werror',
 '-I',str(Path(__file__).resolve().parents[1]/'app/src/main/cpp'),str(p/'check.cpp'),'-o',str(p/'check.so')],check=True)
 check=ctypes.CDLL(str(p/'check.so')).check
 check.argtypes=[ctypes.c_void_p,ctypes.c_int,ctypes.c_uint]+[ctypes.c_int]*3+[ctypes.c_void_p]+[ctypes.c_int]*4+[ctypes.c_void_p]
 for trial in range(1200):
    n=rng.randrange(33);needed=rng.randrange(33);mode=rng.randrange(-1,9)
    ids=[rng.randrange(20) for _ in range(n)];req=[rng.randrange(22) for _ in range(rng.randrange(4))]
    s0,s1=rng.randrange(2),rng.randrange(2);initial_next=rng.randrange(3)
    motion_result=rng.randrange(-4,40);motion_next=rng.randrange(3);seen.clear()
    a=(ctypes.c_uint32*n)(*ids);b=(ctypes.c_uint32*len(req))(*req);out=(ctypes.c_int*6)()
    check(a,n,needed,mode,s0,s1,b,len(req),initial_next,motion_result,motion_next,out)
    data=bytearray(n*48)
    for i,v in enumerate(ids):struct.pack_into('<I',data,i*48+0x24,v)
    if data:u.mem_write(0x1100000,bytes(data))
    u.mem_write(0x1110000,struct.pack('<QQQ',0x1100000,0x1100000+n*48,0x1100000+n*48))
    u.mem_write(0x1120000,struct.pack('<QQQ',0x1121000,0x1121000+4*len(req),0x1121000+4*len(req)))
    if req:u.mem_write(0x1121000,struct.pack('<'+'I'*len(req),*req))
    u.mem_write(0x1130000,bytes(48));u.mem_write(0x1140000,bytes([s0,s1])+bytes(30))
    u.mem_write(0x1150000,struct.pack('<i',initial_next))
    actual=invoke(u,0x142dc0,(needed,0x1110000,123456,mode&0xffffffff,0x1150000,0x1120000,0x1130000,0),(0,0x1140000,0x1160000))
    actual=ctypes.c_int32(actual).value
    nxt=struct.unpack('<i',u.mem_read(0x1150000,4))[0]
    assert (actual,nxt)==tuple(out[:2]),(trial,mode,s0,s1,ids,req,actual,nxt,list(out))
    assert bool(seen)==bool(out[2])
    if seen:assert seen==[(out[3],out[4])]
    u.mem_write(0x11701c0,struct.pack('<QQ',0x1100000,0x1100000+48*n))
    u.reg_write(UC_ARM64_REG_X22,0x1170000);u.reg_write(UC_ARM64_REG_W25,actual&0xffffffff)
    u.emu_start(0x127618,0x127748,count=100)
    assert out[5]==u.reg_read(UC_ARM64_REG_W25)
print('PASS: 1200 original getOffset/request-ID dispatch cases; motion body is an explicit external callback.')
