#!/usr/bin/env python3
"""Compare the production selector with the COMPLETE original ARM64 function.

Only platform properties, libm and logging are shims. Original stage/sunset/
high-lux/motion/EV0 helpers, branching and fallback table scan execute unchanged.
Native-domain synthetic inputs do not establish Camera2 measurement provenance.
"""
import argparse
import ctypes
import hashlib
import random
import re
import struct
import subprocess
import tempfile
from pathlib import Path
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import *
from check_vivo_nice_motion import emulator, invoke

SHA = '3f4ee25636fb04023c338ded9f087734ab8aeb429c4e50adf19a822e5398c188'
ROOT = Path(__file__).resolve().parents[1]
TYPES = {'int':ctypes.c_int, 'int64_t':ctypes.c_int64, 'float':ctypes.c_float, 'bool':ctypes.c_bool}
body = (ROOT/'app/src/main/cpp/vivo-nice-scene-selector.h').read_text().split('struct NiceSceneInputs {')[1].split('};')[0]
FIELDS = [(name.strip(), TYPES[kind]) for kind,names in re.findall(r'(int64_t|int|float|bool)\s+([^;]+);',body) for name in names.split(',')]
class Inputs(ctypes.Structure):
    _fields_ = FIELDS
post_body = (ROOT/'app/src/main/cpp/vivo-nice-scene-selector.h').read_text().split('struct NiceHdrPostInputs {')[1].split('};')[0]
POST_FIELDS = [(name.strip(), TYPES[kind]) for kind,names in re.findall(r'(int64_t|int|float|bool)\s+([^;]+);',post_body) for name in names.split(',')]
class PostInputs(ctypes.Structure):
    _fields_ = POST_FIELDS

DRIVER = r'''
#include "vivo-nice-scene-selector.h"
extern "C" int input_size() {return sizeof(vivo_nice::NiceSceneInputs);}
extern "C" void select_scene(const vivo_nice::NiceSceneInputs* p,const int* last,int* out) {
    vivo_nice::tuning::HdrRow rows[34]{};
    for(int i=0;i<34;++i) {rows[i].evSize=1;rows[i].ev[0]=last[i];}
    auto s=vivo_nice::selectNiceScene(*p,[&](int mode)->const vivo_nice::tuning::HdrRow& {return rows[mode];});
    out[0]=s.mode;out[1]=s.dualRawType;out[2]=s.moreEv0Frames;
}
extern "C" void finish_scene(const vivo_nice::NiceSceneInputs* p,const vivo_nice::NiceHdrPostInputs* h,const int* last,uint64_t* out) {
    vivo_nice::tuning::HdrRow rows[34]{};
    for(int i=0;i<34;++i) {rows[i].evSize=1;rows[i].ev[0]=last[i];}
    auto r=vivo_nice::finishNiceHdrScene(*p,*h,[&](int mode)->const vivo_nice::tuning::HdrRow& {return rows[mode];});
    out[0]=r.flags;out[1]=r.motionClass;out[2]=r.forceBack;out[3]=r.quickNightClass;
    out[4]=r.nightScene;out[5]=r.extreme;out[6]=r.imageEcho;
}
'''

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('library',type=Path)
    ap.add_argument('--cases',type=int,default=10000)
    args=ap.parse_args()
    assert hashlib.sha256(args.library.read_bytes()).hexdigest()==SHA
    u,_=emulator(args.library)
    scene,preview,tuning,motion,decision,log,group,evs= [0x1100000+i*0x10000 for i in range(8)]
    def write(a,fmt,*v):u.mem_write(a,struct.pack('<'+fmt,*v))
    def string(a):
        out=bytearray()
        while u.mem_read(a+len(out),1)[0]:out.extend(u.mem_read(a+len(out),1))
        return bytes(out)
    # Android property_get copies its default when no override exists. In
    # particular forceDebug uses '-1'; returning 0 would disable every detector.
    def platform(uc,a,size,data):
        x0,x1,x2=[uc.reg_read(r) for r in (UC_ARM64_REG_X0,UC_ARM64_REG_X1,UC_ARM64_REG_X2)]
        if a==0x37e010:
            value=string(x2);uc.mem_write(x1,value+b'\0');uc.reg_write(UC_ARM64_REG_X0,len(value))
        elif a==0x37e020:
            match=re.match(rb'\s*([+-]?\d+)',string(x0))
            uc.reg_write(UC_ARM64_REG_X0,(int(match[1]) if match else 0)&0xffffffffffffffff)
        elif a==0x37fb50:uc.reg_write(UC_ARM64_REG_X0,0)
        elif a==0x37d0a0:pass
        elif a==0x37d0b0:uc.reg_write(UC_ARM64_REG_X0,0)
        elif a==0x37e060:uc.reg_write(UC_ARM64_REG_X0,x1)
        elif a==0x3823b0:
            val=struct.unpack('<f',struct.pack('<I',uc.reg_read(UC_ARM64_REG_S0)))[0]
            uc.reg_write(UC_ARM64_REG_S0,struct.unpack('<I',struct.pack('<f',2.**val))[0])
        else:return
        uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
    for address in (0x37e010,0x37e020,0x37e060,0x3823b0,0x37d0a0,0x37d0b0,0x37fb50):
        u.hook_add(UC_HOOK_CODE,platform,begin=address,end=address)
    write(scene+0x1d8,'Q',preview);write(scene+0x160,'Q',tuning);write(scene+0x178,'Q',motion)
    write(preview+0x3bf0,'Q',group);write(0x3a8de0,'Q',log);write(log,'i',6)
    # Recover native row offsets from its actual jump table, independently of
    # the portable hdrVariant names and lookup.
    offsets=[]
    for mode in range(34):
        off=0x20
        if 2<=mode<=32:
            target=0x29ede0+4*u.mem_read(0x11737c+mode-2,1)[0]
            if target==0x29eefc:off=0
            else:
                insn=struct.unpack('<I',u.mem_read(target+4,4))[0]
                assert insn & 0xffc003ff == 0x91000109,hex(insn)
                off=(insn>>10)&0xfff
        offsets.append(off)
    rng=random.Random(24540921);modes=set();count=0;post_count=0
    with tempfile.TemporaryDirectory(prefix='nice-selector-') as temp:
        temp=Path(temp);(temp/'driver.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-ffp-contract=off','-Wall','-Wextra','-Werror','-shared','-fPIC',
                        '-I',str(ROOT/'app/src/main/cpp'),str(temp/'driver.cpp'),'-o',str(temp/'driver.so')],check=True)
        lib=ctypes.CDLL(str(temp/'driver.so'))
        assert lib.input_size()==ctypes.sizeof(Inputs)
        lib.select_scene.argtypes=[ctypes.POINTER(Inputs),ctypes.c_void_p,ctypes.c_void_p]
        lib.finish_scene.argtypes=[ctypes.POINTER(Inputs),ctypes.POINTER(PostInputs),ctypes.c_void_p,ctypes.c_void_p]
        for index in range(args.cases):
            p=Inputs()
            for name,typ in FIELDS:
                setattr(p,name, bool(rng.randrange(2)) if typ==ctypes.c_bool else rng.choice((0,1,2,4,100,300)))
            p.captureType=rng.choice((41,41,41,41,43,42))
            p.sensorQuadMode=rng.choice((0,0,2,2,1))
            p.tuningQuadMode=rng.choice((0,2));p.lens=rng.randrange(4)
            p.uiMode=rng.choice((1,7,31,70,71,74,77))
            p.sceneMode=rng.choice((0,1,10,13,39,40))
            p.algoSceneMode=rng.randrange(2)
            p.seamlessMode=rng.choice((0,4,0x500));p.previewHdrVersion=rng.randrange(3)
            p.hdrType=rng.randrange(3);p.dualRawType=rng.randrange(3);p.previousMode=7
            p.luxIndex=rng.choice((279,280,281,321));p.lux=rng.choice((279.9,280.,280.9,321.))
            p.moreFrameThreshold=rng.choice((-1,280,320))
            p.moreEv0Threshold=280;p.stageTeleThreshold=280;p.stageWideThreshold=320;p.highLuxThreshold=280
            p.exposureIndex=rng.choice((0,1,20));p.exposureIndexThreshold=2
            p.exposureTime=rng.choice((1.,2.,3.));p.hdrGain=rng.choice((1.,2.,3.))
            p.sensorGain=rng.choice((1.,2.,3.));p.referenceGain=rng.choice((1.,2.,3.))
            p.exposureProductThreshold=4.;p.exposureIndexScaleThreshold=10.;p.exposureBias=rng.choice((-1.,0.,1.))
            p.motionLower=1.;p.motionUpper=2.;p.motion=rng.choice((0.,1.,1.5,2.,3.))
            p.sunsetScore=rng.choice((.5,.75,1.));p.sunsetThreshold=.75
            p.motionPortraitExposureThreshold=2.;p.motionPortraitState=rng.choice((0,1))
            p.motionPortraitFlags=rng.randrange(4);p.forcedMode=rng.randrange(2)
            p.uiFlags=rng.randrange(17);p.manualExposureNs=rng.choice((999999,1000000,1999999,2000000,3000000));p.timeThresholdMs=2
            p.zoom=rng.choice((1.,2.,3.,4.,5.));p.binningZoomLower=2.;p.binningZoomUpper=4.;p.quadZoomLower=2.;p.quadZoomUpper=4.
            for off,name,fmt in (
                (0x1f0,'captureType','i'),(0x90,'zoom','f'),(0x134,'quickNightMode','i'),(0x185,'stageEnabled','B'),
                (0x186,'forceBack','B'),(0x1a4,'forcedMode','i'),(0x1a8,'stageTeleThreshold','i'),(0x1ac,'stageWideThreshold','i'),
                (0x1b0,'manualExposureNs','q'),(0x1b8,'motionPortraitEnabled','B'),(0x1c0,'motionPortraitExposureThreshold','f'),
                (0xd4,'motionPortraitFlags','i'),(0x234,'uiFlags','i')):write(scene+off,fmt,getattr(p,name))
            write(scene+0x210,'i',int(p.tripod));write(scene+0xa8,'Q',1<<20);write(scene+0x118,'i',1)
            for off,name,fmt in (
                (0x470,'lens','i'),(0x440,'uiMode','i'),(0x11ac,'sceneMode','i'),(0x5f38,'algoSceneMode','i'),
                (0x5f0c,'seamlessMode','i'),(0x3c04,'previewHdrVersion','i'),(0x3c00,'hdrType','i'),
                (0x3bfc,'dualRawType','i'),(0x3bec,'previousMode','i'),(0x43c,'lux','f'),(0x3ae0,'motion','f'),
                (0x404,'exposureIndex','i'),(0x41c,'exposureTime','f'),(0x428,'hdrGain','f'),(0x420,'sensorGain','f'),
                (0x414,'referenceGain','f'),(0x4a0,'exposureBias','f'),(0x4d0,'sunsetScore','f'),
                (0x328,'motionPortraitState','i'),(0x3c10,'livePhoto','B')):write(preview+off,fmt,getattr(p,name))
            for off,name,fmt in (
                (0,'tuningQuadMode','i'),(0xc,'exposureIndexThreshold','i'),(0x10,'exposureProductThreshold','f'),
                (0x18,'moreFrameThreshold','i'),(0x1c,'moreEv0Threshold','i'),(0x20,'binningZoomLower','f'),
                (0x24,'binningZoomUpper','f'),(0x28,'quadZoomLower','f'),(0x2c,'quadZoomUpper','f'),
                (0x44,'exposureIndexScaleThreshold','f'),(0x48,'sunsetThreshold','f'),(0x64,'timeThresholdMs','i'),
                (0x70,'highLuxThreshold','i')):write(tuning+off,fmt,getattr(p,name))
            write(motion,'2f',p.motionLower,p.motionUpper)
            write(decision+0x2c8,'B',p.fastNight);write(decision+0x2d8,'2i',p.sensorQuadMode,p.luxIndex)
            values={off:rng.choice((-2,-1,0,1,2)) for off in offsets}
            for off,value in values.items():
                addr=evs+off;write(addr,'i',value);write(group+off+8,'2Q',addr,addr+4)
            last=(ctypes.c_int*34)(*(values[off] for off in offsets));out=(ctypes.c_int*3)()
            lib.select_scene(ctypes.byref(p),last,out)
            invoke(u,0x29bfb0,(decision,scene))
            expected=(struct.unpack('<i',u.mem_read(preview+0x3bec,4))[0],
                      struct.unpack('<i',u.mem_read(preview+0x3bfc,4))[0],u.mem_read(preview+0x3bf9,1)[0])
            assert tuple(out)==expected,(index,tuple(out),expected,{n:getattr(p,n) for n,_ in FIELDS})
            modes.add(out[0]);count+=1
            if p.captureType in (41,43):
                h=PostInputs()
                for name,typ in POST_FIELDS:
                    setattr(h,name,bool(rng.randrange(2)) if typ==ctypes.c_bool else rng.choice((0,1,2,280,320)))
                h.captureMode=rng.choice((0,24));h.luxBoundary1=280;h.luxBoundary2=320
                h.extremeThreshold=320.;h.extremeAutoThreshold=280.;h.extremeHysteresis=10.
                ae=0x1190000;write(preview+8,'Q',ae)
                write(ae+0xd0,'3f',h.extremeThreshold,h.extremeHysteresis,h.extremeAutoThreshold)
                write(preview+0x444,'i',h.exposureMode);write(preview+0x3c18,'i',h.forwardBlock)
                write(scene+0x1d0,'i',h.captureMode);write(scene+0x1cc,'B',h.portrait)
                write(scene+0x170,'f',h.motionScore);write(tuning+0x14,'i',h.motionThreshold)
                write(tuning+0x34,'3i',h.echoLuxThreshold,h.luxBoundary1,h.luxBoundary2)
                write(decision+0x2d0,'7B',1,0,0,0,0,0,0)
                write(decision+0x2e0,'2B',h.previousExtreme,h.nightScene)
                write(scene+0x260,'Q',0)
                # Each postProcess invocation starts with the original input
                # dual type, before selectNiceScene can update it.
                write(preview+0x3bfc,'i',p.dualRawType)
                finished=(ctypes.c_uint64*7)()
                lib.finish_scene(ctypes.byref(p),ctypes.byref(h),last,finished)
                invoke(u,0x29b9a0,(decision,scene))
                expected_post=(struct.unpack('<Q',u.mem_read(scene+0x260,8))[0],
                    struct.unpack('<i',u.mem_read(scene+0x268,4))[0],
                    struct.unpack('<i',u.mem_read(scene+0x26c,4))[0],
                    struct.unpack('<i',u.mem_read(scene+0x25c,4))[0],
                    u.mem_read(scene+0x158,1)[0],u.mem_read(decision+0x2e0,1)[0],
                    u.mem_read(preview+0x3bf8,1)[0])
                assert tuple(finished)==expected_post,('post',index,tuple(finished),expected_post,
                    {n:getattr(p,n) for n,_ in FIELDS},{n:getattr(h,n) for n,_ in POST_FIELDS})
                post_count+=1
    print(f'PASS: {count} complete native selector executions; modes {sorted(modes)}')
    print(f'PASS: {post_count} complete native HDR postProcess executions (selector, flags, hysteresis, echo)')
    print('No APK built. Camera2 provenance, AE scheduling and device delivery remain separate contracts.')

if __name__=='__main__':main()
