#!/usr/bin/env python3
"""Execute pinned Vivo ARM64 RANSAC and LK wrapper; not a capture parity test.

Requires pyelftools and unicorn. No algorithm instructions are replaced. Import
shims provide a bounded allocator, memory operations, Python libm, single-thread
C++ initialization, clocks and silent logging. Destruction is deferred until the
emulator is discarded. Optional libvivo.mempool loading fails deliberately to
exercise the donor's built-in allocation path. No proprietary binary is stored
in this repository. Each case gets an independent process image.
"""
import argparse
import hashlib
import math
import random
import struct
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import *

CRE_SHA256 = '41b277753f7fedbe4dda1e1c4b76d2086d6e79768904d8a4922b48a0e023e76e'
STOP = 0x7ff000


def emulator(library):
    u=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
    u.mem_map(0,0x800000);u.mem_map(0x1000000,0x1000000);u.mem_map(0x2000000,0x4000000);u.mem_map(0x8000000,0x100000)
    stubs={};calls={};heap=0x2000000
    with open(library,'rb') as f:
     e=ELFFile(f)
     for s in e.iter_segments():
      if s['p_type']=='PT_LOAD':u.mem_write(s['p_vaddr'],s.data())
     syms=e.get_section_by_name('.dynsym')
     for s in e.iter_sections():
      if s['sh_type'] not in ('SHT_RELA','SHT_REL'):continue
      for r in s.iter_relocations():
       typ=r['r_info_type'];off=r['r_offset'];add=r.entry.get('r_addend',0)
       if typ==1027:u.mem_write(off,struct.pack('<Q',add));continue
       if typ in (1025,1026,257):
        sym=syms.get_symbol(r['r_info_sym']);val=sym['st_value']
        if not val:
         val=0x8000000+len(stubs)*16;stubs[val]=sym.name
        u.mem_write(off,struct.pack('<Q',val+add))
    
    def hook(u,addr,size,data):
     nonlocal heap
     name=stubs.get(addr)
     if not name:raise RuntimeError(hex(addr))
     calls[name]=calls.get(name,0)+1
     regs=[u.reg_read(r) for r in (UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X2)]
     a,b,c=regs;ret=0
     if name in ('malloc','_Znwm','_Znam'):
      ret=heap;heap+=(a+255)&~255
      if heap>0x6000000:raise RuntimeError('oracle heap exhausted')
     elif name=='_ZNSt6__ndk112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEC1ERKS5_':
      value=bytes(u.mem_read(b,24))
      if value[0]&1:raise RuntimeError('oracle supports only short-string copy')
      u.mem_write(a,value);ret=a
     elif name=='__system_property_get':u.mem_write(b,b'\0')
     elif name=='__cxa_guard_acquire':ret=0 if u.mem_read(a,1)[0] else 1
     elif name=='__cxa_guard_release':u.mem_write(a,b'\x01')
     elif name=='dlopen':
      library_name=bytes(u.mem_read(a,128)).split(b'\0',1)[0]
      if library_name != b'libvivo.mempool.so':raise RuntimeError(f'unexpected dlopen: {library_name!r}')
      ret=0  # Exercise the original built-in allocator fallback.
     elif name in ('__v_android_log_print','printf','__cxa_atexit'):pass
     elif name=='strncmp':
      left=bytes(u.mem_read(a,c)).split(b'\0',1)[0];right=bytes(u.mem_read(b,c)).split(b'\0',1)[0]
      ret=((left>right)-(left<right))&0xffffffffffffffff
     elif name=='strlen':
      while u.mem_read(a+ret,1)[0]:ret+=1
     elif name=='calloc':
      ret=heap;heap+=(a*b+255)&~255
      if heap>0x6000000:raise RuntimeError('oracle heap exhausted')
      u.mem_write(ret,bytes(a*b))
     elif name in ('free','_ZdlPv','_ZdaPv','__android_log_print','__android_log_write','__cxa_finalize','_ZNSt6__ndk119__shared_weak_count14__release_weakEv'):pass
     elif name=='memcpy' or name=='memmove':u.mem_write(a,bytes(u.mem_read(b,c)));ret=a
     elif name=='memset':u.mem_write(a,bytes([b&255])*c);ret=a
     elif name=='gettimeofday':u.mem_write(a,struct.pack('<qq',1,0))
     elif name=='clock_gettime':u.mem_write(b,struct.pack('<qq',1,0))
     elif name in ('log','hypot','pow'):
      val=struct.unpack('<d',struct.pack('<Q',u.reg_read(UC_ARM64_REG_D0)))[0]
      other=struct.unpack('<d',struct.pack('<Q',u.reg_read(UC_ARM64_REG_D1)))[0]
      result=math.log(val) if name=='log' else math.hypot(val,other) if name=='hypot' else math.pow(val,other)
      u.reg_write(UC_ARM64_REG_D0,struct.unpack('<Q',struct.pack('<d',result))[0])
     else:raise RuntimeError('unhandled '+name+' '+str(regs)+' lr='+hex(u.reg_read(UC_ARM64_REG_LR)))
     u.reg_write(UC_ARM64_REG_X0,ret);u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))
    u.hook_add(UC_HOOK_CODE,hook,begin=0x8000000,end=0x80fffff)
    u.reg_write(UC_ARM64_REG_CPACR_EL1,3<<20);u.reg_write(UC_ARM64_REG_TPIDR_EL0,0x1000000)
    return u, calls


def invoke(u, address, args, stack_args=()):
    sp=0x1fff000
    if stack_args:
        u.mem_write(sp,struct.pack('<'+'Q'*len(stack_args),*stack_args))
    for index,value in enumerate(args):
        u.reg_write(globals()['UC_ARM64_REG_X'+str(index)],value)
    u.reg_write(UC_ARM64_REG_SP,sp)
    u.reg_write(UC_ARM64_REG_LR,STOP)
    u.emu_start(address,STOP,count=100_000_000)
    if u.reg_read(UC_ARM64_REG_PC)!=STOP:
        raise RuntimeError('instruction budget exhausted')
    return u.reg_read(UC_ARM64_REG_X0)&0xffffffff


def project(h,x,y):
    den=h[6]*x+h[7]*y+h[8]
    return ((h[0]*x+h[1]*y+h[2])/den,(h[3]*x+h[4]*y+h[5])/den)


def points_write(u,address,points):
    u.mem_write(address,b''.join(struct.pack('<2f',*p) for p in points))


def check_ransac(library):
    points=[(float(x*23+13),float(y*19+11)) for y in range(8) for x in range(8)]
    matrices=[(1,0,4,0,1,-3,0,0,1),(.98,-.17,5,.17,.98,-4,0,0,1),
              (1,.06,-3,-.04,1,5,.0003,-.0002,1)]
    for matrix in matrices:
        for outliers in (0,8,20):
            u,_=emulator(library)
            targets=[project(matrix,*p) for p in points]
            for i in range(outliers):
                targets[i]=(targets[i][0]+30+i*3,targets[i][1]-40-i*2)
            points_write(u,0x1010000,points)
            points_write(u,0x1020000,targets)
            u.mem_write(0x104003c,struct.pack('<IfI',100,.995,3))
            status=invoke(u,0x28ef00,
                (0x1010000,0x1020000,0x1030000,0x1040000,len(points),30,30,1),
                (0x1050000,0))
            h=struct.unpack('<9d',u.mem_read(0x1030000,72))
            error=max(math.dist(project(h,*p),project(matrix,*p)) for p in points[outliers:])
            assert status==0 and math.isfinite(error) and error<.001,(status,h,error)
            print(f'RANSAC outliers={outliers}/64 max_error={error:.9f}px')


def check_lk(library):
    width=height=128
    rng=random.Random(42)
    noise=[rng.randrange(256) for _ in range(width*height)]
    raw=bytes(sum(noise[max(0,min(height-1,y+dy))*width+max(0,min(width-1,x+dx))]
                  for dy in range(-1,2) for dx in range(-1,2))//9
              for y in range(height) for x in range(width))
    points=[(float(x),float(y)) for y in range(24,105,12) for x in range(24,105,12)]
    for dx,dy in [(0,0),(2,1),(-2,-1),(1,-2)]:
        u,calls=emulator(library)
        shifted=bytes(raw[max(0,min(height-1,y-dy))*width+max(0,min(width-1,x-dx))]
                      for y in range(height) for x in range(width))
        for desc,data,pixels in [(0x1060000,0x1100000,raw),(0x1060100,0x1200000,shifted)]:
            u.mem_write(data,pixels)
            u.mem_write(desc,struct.pack('<III',9,width,height))
            u.mem_write(desc+16,struct.pack('<Q',data))
            u.mem_write(desc+48,struct.pack('<I',width))
        points_write(u,0x1010000,points)
        u.mem_write(0x1040030,struct.pack('<fIfIfI',.01,20,.7,100,.995,3))
        status=invoke(u,0x29a548,(0x1060000,0x1060100,0x1010000,0x1020000,
                                len(points),0x1050000,0x1040000,0x1030000),(0,))
        count=struct.unpack('<I',u.mem_read(0x1050000,4))[0]
        h=struct.unpack('<9f',u.mem_read(0x1030000,36))
        # Wrapper returns donor->reference H, although LK tracks reference->donor.
        error=max(math.dist(project(h,x+dx,y+dy),(x,y)) for x,y in points)
        tracked=[struct.unpack('<2f',u.mem_read(0x1020000+i*8,8)) for i in range(count)]
        tracking_error=max(math.dist(p,(x+dx,y+dy)) for p,(x,y) in zip(tracked,points))
        assert status==0 and count==len(points),(status,count)
        assert math.isfinite(error) and error<.05,(h,error)
        assert math.isfinite(tracking_error) and tracking_error<.05,tracking_error
        print(f'LK+RANSAC shift=({dx},{dy}) accepted={count} '
              f'H_error={error:.6f}px tracking_error={tracking_error:.6f}px')



def check_corner_chain(library):
    u,_=emulator(library)
    width=height=256
    rng=random.Random(42)
    raw=bytes(rng.randrange(256) for _ in range(width*height))
    shifted=bytes(raw[max(0,y-1)*width+max(0,x-2)]
                  for y in range(height) for x in range(width))
    for desc,data,pixels in [(0x1060000,0x1100000,raw),(0x1060100,0x1200000,shifted)]:
        u.mem_write(data,pixels)
        u.mem_write(desc,struct.pack('<III',9,width,height))
        u.mem_write(desc+16,struct.pack('<Q',data))
        u.mem_write(desc+48,struct.pack('<I',width))
    # CPU path; maxCorners, minCorners, maxCandidates, minDistance,
    # useHarris, HarrisK, scale, quality. No hand-picked corner coordinates.
    u.mem_write(0x1040000,struct.pack('<QIIIIIfIf',0,1000,0,32768,16,0,.04,4,.01))
    status=invoke(u,0x2914d0,(0x1040000,0x1060000,0,0x1010000,0x1050000,0))
    count=struct.unpack('<I',u.mem_read(0x1050000,4))[0]
    assert status==0 and count==105,(status,count)
    points=[struct.unpack('<2f',u.mem_read(0x1010000+i*8,8)) for i in range(count)]
    assert all(0<=x<width and 0<=y<height for x,y in points)
    u.mem_write(0x1040030,struct.pack('<fIfIfI',.01,20,.7,100,.995,3))
    status=invoke(u,0x29a548,(0x1060000,0x1060100,0x1010000,0x1020000,
                            count,0x1050000,0x1040000,0x1030000),(0,))
    accepted=struct.unpack('<I',u.mem_read(0x1050000,4))[0]
    h=struct.unpack('<9f',u.mem_read(0x1030000,36))
    error=max(math.dist(project(h,x+2,y+1),(x,y)) for x,y in points)
    assert status==0 and accepted==count,(status,accepted)
    assert math.isfinite(error) and error<.2,(h,error)
    print(f'Corners+LK+RANSAC detected={count} accepted={accepted} H_error={error:.6f}px')



def check_defaults(library):
    u,_=emulator(library)
    u.mem_write(0x1030000,b'\x06HDR\0'+bytes(19))
    invoke(u,0x3ce2a0,(0x1040000,0x1030000))
    assert bytes(u.mem_read(0x104563c,4))==struct.pack('<f',.6)
    assert struct.unpack('<I',u.mem_read(0x10456bc,4))[0]==0
    assert struct.unpack('<I',u.mem_read(0x1045718,4))[0]==4
    print('PASS: original defaults gamma=.6, failed-frame method=0, guide scale=4')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('library',type=Path,help='exact libvivo_nice_cre.so from donor')
    args=parser.parse_args()
    if hashlib.sha256(args.library.read_bytes()).hexdigest()!=CRE_SHA256:
        parser.error('unsupported CRE binary: SHA-256 mismatch')
    check_defaults(args.library)
    check_ransac(args.library)
    check_lk(args.library)
    check_corner_chain(args.library)
    print('PASS: 9 original RANSAC and 4 original LK+RANSAC synthetic cases; '
          'plus original corner detection chain; capture integration and device quality remain untested.')


if __name__=='__main__':
    main()
