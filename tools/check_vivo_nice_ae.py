#!/usr/bin/env python3
"""Verify PD2454 AE mappings against original ARM64 and Java->native transport.

Does not execute TCE images or the dynamic scene decision. No camera service is
accessed. Only the original adapter's log call is stubbed in the tested blocks.
"""
import argparse
import ctypes
import hashlib
import random
import struct
import subprocess
import tempfile
from pathlib import Path
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import *
from check_vivo_nice_motion import emulator, STOP
from check_nice_reference_metadata import STUBS, ROOT, BASE
from read_vivo_algo_metadata import read_schema

VCF_SHA = 'f5fdf379e0316fee77a0ff7760ef247881e4dfade98f3a7bc8b9b8f93c12e901'
VAF_SHA = '3f4ee25636fb04023c338ded9f087734ab8aeb429c4e50adf19a822e5398c188'
DRIVER = r'''
#include "vivo-nice-capture.h"
extern "C" int decode(const unsigned char* record,float* out) {
 try {
  vivo_nice::NiceAe a;a.read(record);auto f=a.fields();
  float v[]={f.lux,f.shortGain,f.digitalGain,f.analogGain,f.exposureMs,f.aeDrc,a.drcGain(false),a.drcGain(true)};
  std::memcpy(out,v,sizeof(v));return 0;
 } catch(const std::exception&){return -1;}
}
extern "C" int transport(const char* path) {
 try {
  vivo_nice::MappedNiceBurst b(path);
  const unsigned flags[]={85,0,130,41,5,69,85};
  for(int i=0;i<7;++i) {
   const auto& a=b.burst.ae[i];
   if(a.timestamp!=123456789012ULL+i || a.flags!=flags[i])return 10+i;
   if(b.burst.raw[i][0]!=200+i || b.burst.raw[i][4095]!=200+i)return 20+i;
  }
  if(b.burst.ae[0].fields().lux!=100.f || b.burst.ae[0].fields().shortGain!=4.f ||
     b.burst.ae[0].fields().digitalGain!=2.f || b.burst.ae[0].fields().exposureMs!=10.f ||
     b.burst.ae[0].drcGain(true)!=3.f || b.burst.ae[0].drcGain(false)!=5.f)return 30;
  if(b.burst.ae[4].drcGain(true)!=1.f || b.burst.ae[5].drcGain(false)!=2.5f)return 31;
  try {b.burst.ae[1].fields();return 32;}catch(const std::runtime_error&){}
  try {b.burst.ae[3].drcGain(true);return 33;}catch(const std::runtime_error&){}
  try {b.burst.ae[4].drcGain(false);return 34;}catch(const std::runtime_error&){}
  return 0;
 }catch(const std::exception&){return -1;}
}
'''
JAVA = r'''
import android.hardware.camera2.CaptureResult;
import com.particlesdevs.photoncamera.processing.ImageFrame;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNiceAe;
import com.particlesdevs.photoncamera.processing.opengl.postpipeline.VivoNiceBurst;
import com.particlesdevs.photoncamera.processing.render.Parameters;
import java.nio.*;
public class Check {
 static final CaptureResult.Key<float[]> AEC=new CaptureResult.Key<>("vivo.control.Vivo3rdAlgoAECFrameControl",float[].class);
 static final CaptureResult.Key<Float> HDR=new CaptureResult.Key<>("vivo.parameter.rawHDRCaptureDrcGain",Float.class);
 static final CaptureResult.Key<Float> CAPTURE=new CaptureResult.Key<>("vivo.feedback.aeAdrcGainCapture",Float.class);
 static final CaptureResult.Key<int[]> MODE=new CaptureResult.Key<>("vcf.parameter.seamlessMode",int[].class);
 public static void main(String[] args)throws Exception {
  ByteBuffer buffer=ByteBuffer.allocate(7*VivoNiceAe.TRANSPORT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
  java.util.List<ImageFrame> frames=new java.util.ArrayList<>();
  for(int i=0;i<7;++i) {
   ByteBuffer raw=ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
   for(int pixel=0;pixel<4096;++pixel)raw.putShort((short)(200+i));raw.clear();
   ImageFrame frame=new ImageFrame(raw);frame.timestamp=123456789012L+i;frame.width=frame.height=64;
   frame.pair=new com.particlesdevs.photoncamera.processing.parameters.IsoExpoSelector.ExpoPair();
   frame.pair.isLongFrame=i==4;frame.pair.isHighlightFrame=i>=5;
   CaptureResult r=new CaptureResult();r.put(CaptureResult.SENSOR_TIMESTAMP,frame.timestamp);
   r.put(CaptureResult.SENSOR_SENSITIVITY,100);
   r.put(CaptureResult.SENSOR_EXPOSURE_TIME,i==4?40000000L:i==5?2500000L:i==6?1250000L:10000000L);
   float[] a=new float[35];a[0]=100+i;a[2]=4;a[6]=2.5f;a[13]=2;a[14]=10000000;
   if(i!=1)r.put(AEC,a);
   if(i==0||i==6){r.put(HDR,3f);r.put(CAPTURE,5f);r.put(MODE,new int[]{4,0,0});}
   if(i==2){r.put(AEC,new float[34]);r.put(MODE,new int[]{4});}
   if(i==3){r.put(HDR,Float.NaN);r.put(CAPTURE,Float.POSITIVE_INFINITY);}
   if(i==4)r.put(HDR,0f);
   if(i==5){r.put(HDR,2f);r.put(MODE,new int[]{0,0,0});}
   frame.setCaptureMetadata(r);VivoNiceAe snapshot=VivoNiceAe.fromFrame(frame);
   a[0]=9999; // Defensive copy must survive vendor array reuse.
   snapshot.writeTransport(buffer);
   frame.timestamp++;
   try {VivoNiceAe.fromFrame(frame);throw new AssertionError("wrong timestamp accepted");}
   catch(IllegalArgumentException expected){}
   try {snapshot.writeTransport(ByteBuffer.allocate(176));throw new AssertionError("wrong endian accepted");}
   catch(IllegalArgumentException expected){}
   frame.timestamp--;a[0]=100+i;frames.add(frame);
  }
  java.nio.file.Files.write(java.nio.file.Path.of(args[0]),buffer.array());
  var constructor=VivoNiceBurst.class.getDeclaredConstructor(java.util.List.class,Parameters.class);
  constructor.setAccessible(true);var burst=constructor.newInstance(frames,new Parameters());
  var write=VivoNiceBurst.class.getDeclaredMethod("write",java.io.File.class);write.setAccessible(true);
  write.invoke(burst,new java.io.File(args[1]));
 }
}
'''


def run_block(u,start,end):
    stop_hook=u.hook_add(UC_HOOK_CODE,lambda u,a,s,d:u.emu_stop(),begin=end,end=end)
    u.emu_start(start,STOP,count=500)
    u.hook_del(stop_hook)
    assert u.reg_read(UC_ARM64_REG_PC)==end


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('metadata','vcf','vaf'):parser.add_argument(name,type=Path)
    args=parser.parse_args()
    for path,sha in ((args.vcf,VCF_SHA),(args.vaf,VAF_SHA)):
        assert hashlib.sha256(path.read_bytes()).hexdigest()==sha
    tags=read_schema(args.metadata)
    assert len(tags)==1249
    u,_=emulator(args.metadata)
    owner=0x1100000
    # This donor uses Android packed .rela.dyn. Resolve the three data-symbol
    # GOT entries needed by InitializeAlgoTagInfo (not instruction patches).
    for got,symbol in [(0x1c828,0x20000),(0x1c830,0x20030),(0x1c838,0x24e70)]:
        u.mem_write(got,struct.pack('<Q',symbol))
    u.reg_write(UC_ARM64_REG_SP,0x1ff0000)
    u.reg_write(UC_ARM64_REG_X0,owner);u.reg_write(UC_ARM64_REG_LR,STOP)
    u.emu_start(0x15194,STOP,count=500000)
    assert u.reg_read(UC_ARM64_REG_PC)==STOP and u.reg_read(UC_ARM64_REG_X0)==0
    u.mem_write(0x24f18,bytes(u.mem_read(owner,0x30)));u.mem_write(0x24f48,b'\1')
    for tag in tags:
        u.reg_write(UC_ARM64_REG_X0,tag['id']);u.reg_write(UC_ARM64_REG_LR,STOP)
        u.emu_start(0x13748,STOP,count=1000)
        text=bytes(u.mem_read(u.reg_read(UC_ARM64_REG_X0),256)).split(b'\0',1)[0].decode()
        assert text==tag['name']
        u.reg_write(UC_ARM64_REG_X0,tag['id']);u.reg_write(UC_ARM64_REG_LR,STOP)
        u.emu_start(0x13824,STOP,count=1000)
        assert u.reg_read(UC_ARM64_REG_W0)==tag['type']
    expected={0:'luxindex',4:'adrc_gain',7:'flash_real_gain',0x16:'isp_gain_short',0x22:'exp_time_short',0x3015f:'raw_hdr_capture_drcgain'}
    for tag in tags:
        if tag['id'] in expected:assert tag['name']==expected[tag['id']] and tag['type']==2 and tag['count']==1
    print('PASS: all 1249 metadata names/types match original initialization and lookup functions')
    vcf,_=emulator(args.vcf);vaf,_=emulator(args.vaf)
    def log(u,address,size,data):u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))
    vcf.hook_add(UC_HOOK_CODE,log,begin=0x12b030,end=0x12b030)
    sp,camera,extra=0x1ff0000,0x1100000,0x1120000
    vcf.reg_write(UC_ARM64_REG_SP,sp)
    rng=random.Random(20260927)
    with tempfile.TemporaryDirectory(prefix='nice-ae-') as temp:
        temp=Path(temp);(temp/'ae.cpp').write_text(DRIVER)
        subprocess.run(['c++','-std=c++17','-O2','-Wall','-Wextra','-Werror','-shared','-fPIC',
            '-I',str(ROOT/'app/src/main/cpp'),str(temp/'ae.cpp'),'-o',str(temp/'ae.so')],check=True)
        dll=ctypes.CDLL(str(temp/'ae.so'));dll.decode.argtypes=[ctypes.c_void_p,ctypes.c_void_p]
        dll.transport.argtypes=[ctypes.c_char_p]
        for case in range(256):
            a=[rng.uniform(1,500) for _ in range(35)];a[14]=rng.uniform(1000,3e10)
            a[0]=rng.uniform(-10,600);mode=[0,4,0x500,7][case%4]
            capture=[0.,-1.,8.,4.5][(case//4)%4];hdr=rng.uniform(-2,16)
            record=struct.pack('<QIi35fff12x',123456789012,85,mode,*a,hdr,capture)
            assert len(record)==176
            out=ctypes.create_string_buffer(32);assert dll.decode(record,out)==0
            vcf.mem_write(sp+0x1490,record[16:156]);vcf.mem_write(sp+0x1768,struct.pack('<Q',extra))
            vcf.mem_write(extra,struct.pack('<f',capture));vcf.mem_write(sp+0x4e40,struct.pack('<i',mode))
            vcf.reg_write(UC_ARM64_REG_X19,camera)
            run_block(vcf,0xdce34,0xdceac)
            assert vcf.reg_read(UC_ARM64_REG_PC)==0xdceac
            native_drc=bytes(vcf.mem_read(camera+0x890,4))
            run_block(vcf,0xdceac,0xdd030)
            assert vcf.reg_read(UC_ARM64_REG_PC)==0xdd030
            # Execute actual internal metadata publish callbacks, not just the copy.
            values={}
            def publish(u,address,size,data):
                keyptr=u.reg_read(UC_ARM64_REG_X2);dataptr=u.reg_read(UC_ARM64_REG_X3)
                key=struct.unpack('<I',u.mem_read(keyptr,4))[0]
                pointer=struct.unpack('<Q',u.mem_read(dataptr,8))[0]
                values[key]=bytes(u.mem_read(pointer,4))
                u.reg_write(UC_ARM64_REG_PC,u.reg_read(UC_ARM64_REG_LR))
            vcf.mem_write(sp+0x4900,bytes(vcf.mem_read(camera,0x1000)))
            callback=0x7fe000;obj=0x1130000;vtable=0x1140000;holder=0x1150000
            vcf.mem_write(holder,struct.pack('<Q',obj));vcf.mem_write(obj,struct.pack('<Q',vtable))
            vcf.mem_write(vtable+0x30,struct.pack('<Q',callback));vcf.reg_write(UC_ARM64_REG_X28,holder)
            hook=vcf.hook_add(UC_HOOK_CODE,publish,begin=callback,end=callback)
            for start,end in [(0xcb41c,0xcb464),(0xcb5b0,0xcb5f0),(0xcb790,0xcb7d0),(0xcb7d0,0xcb818),(0xcba78,0xcbac0)]:
                vcf.reg_write(UC_ARM64_REG_X22,sp+0x4900)
                if start==0xcb5b0:vcf.reg_write(UC_ARM64_REG_W23,4)
                run_block(vcf,start,end)
                assert vcf.reg_read(UC_ARM64_REG_PC)==end,(hex(start),hex(vcf.reg_read(UC_ARM64_REG_PC)))
            vcf.hook_del(hook)
            # VAF's division and HDR DRC clamp, using original instructions.
            vaf.mem_write(camera+0x1f20,values[7]);vaf.mem_write(camera+0x1fc0,values[0x16])
            vaf.mem_write(extra+0x34,bytes(4));vaf.reg_write(UC_ARM64_REG_X10,extra);vaf.reg_write(UC_ARM64_REG_X12,camera)
            vaf.emu_start(0x33d134,0x33d150,count=30)
            analog=bytes(vaf.mem_read(camera+0x1f70,4))
            vaf.reg_write(UC_ARM64_REG_SP,sp);vaf.mem_write(sp+0x1a4,struct.pack('<f',hdr))
            vaf.mem_write(extra,bytes(4));vaf.reg_write(UC_ARM64_REG_X23,extra);vaf.reg_write(UC_ARM64_REG_X21,camera)
            vaf.emu_start(0x33c420,0x33c438,count=30)
            expected_bytes=values[0]+values[7]+values[0x16]+analog+values[0x22]+record[40:44]+native_drc+bytes(vaf.mem_read(camera+0x2010,4))
            assert out.raw==expected_bytes,(case,struct.unpack('<8f',out.raw),struct.unpack('<8f',expected_bytes))
        print('PASS: 256 original RAW AE conversions, internal tag publications, analog gain divisions and HDR DRC clamps')
        stubs=dict(STUBS);stubs['Check.java']=JAVA
        stubs[BASE+'util/Allocator.java']=stubs[BASE+'util/Allocator.java'].replace(
            'return ByteBuffer.allocate(n);',
            'ByteBuffer out=ByteBuffer.allocate(n);out.put(b.duplicate());out.clear();return out;',1)
        stubs.update({
            'android/content/Context.java':'package android.content; public class Context {}',
            'android/os/Build.java':'package android.os; public class Build {public static final String MANUFACTURER="vivo",DEVICE="PD2454";}',
            BASE+'util/Log.java':'package com.particlesdevs.photoncamera.util; public class Log {public static void d(String a,String b){} public static void i(String a,String b){}}',
            BASE+'processing/parameters/IsoExpoSelector.java':'package com.particlesdevs.photoncamera.processing.parameters; public class IsoExpoSelector {public static class ExpoPair {public boolean isHighlightFrame,isLongFrame;}}',
            BASE+'processing/render/Parameters.java':'''package com.particlesdevs.photoncamera.processing.render;
             public class Parameters {public static class Size {public int x=64,y=64;}
              public Size rawSize=new Size();public int cfaPattern=0,physicalID=3;
              public float whiteLevel=16383;public float[] blackLevel=new float[4];public boolean quadCfa=false;}''',
            BASE+'settings/PreferenceKeys.java':'''package com.particlesdevs.photoncamera.settings;
             public class PreferenceKeys {public static boolean isNiceDiagnosticsEnabled(){return false;}
              public static boolean isRemosaicEnabled(){return false;}}''',
            BASE+'processing/opengl/postpipeline/NiceDiagnostics.java':'''package com.particlesdevs.photoncamera.processing.opengl.postpipeline;
             public class NiceDiagnostics {public static void begin(Object a,Object b,Object c,Object d){}}''',
            BASE+'processing/opengl/postpipeline/VivoNeuralClient.java':'''package com.particlesdevs.photoncamera.processing.opengl.postpipeline;
             public class VivoNeuralClient {public static java.nio.ByteBuffer processNiceBurst(Object a,Object b){return null;}}''',
        })
        for name,content in stubs.items():
            p=temp/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
        source=ROOT/'app/src/main/java'
        subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(temp),
            *map(str,temp.rglob('*.java')),str(source/BASE/'processing/ImageFrame.java'),
            *[str(source/BASE/('processing/opengl/postpipeline/'+name+'.java'))
              for name in ('VivoNiceAe','VivoNiceScene','VivoNiceBurst')]],check=True)
        subprocess.run(['java','-cp',str(temp),'Check',str(temp/'records'),str(temp/'actual-burst')],check=True)
        actual_code=dll.transport(str(temp/'actual-burst').encode());assert actual_code==0,actual_code
        records=(temp/'records').read_bytes()
        header=bytearray(160);struct.pack_into('<6If',header,0,0x3143484e,7,64,64,0,7,16383.)
        struct.pack_into('<7f',header,44,1,1,1,1,4,.25,.125);struct.pack_into('<7I',header,72,*([100]*7))
        struct.pack_into('<ffIff',header,100,.00015,.000002,0,.00015,.000002)
        struct.pack_into('<Q',header,128,123456789012)
        pixels=b''.join(struct.pack('<H',200+i)*4096 for i in range(7));valid=header+records+pixels
        path=temp/'burst';path.write_bytes(valid);assert dll.transport(str(path).encode())==0
        corruptions=[(160,0),(168,3),(324,1),(160+176+8,256),(160+176+16,0x3f800000)]
        for offset,value in corruptions:
            bad=bytearray(valid);struct.pack_into('<I',bad,offset,value);path.write_bytes(bad)
            assert dll.transport(str(path).encode())==-1,(offset,value)
        for length in (159,1391,len(valid)-1):
            path.write_bytes(valid[:length]);assert dll.transport(str(path).encode())==-1
        print('PASS: actual Java per-RAW snapshots reach native v7 reader; missing/invalid fields, immutable copies, timestamps, corruption and all seven RAW offsets checked')


if __name__=='__main__':main()
