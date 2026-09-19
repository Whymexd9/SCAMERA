#!/usr/bin/env python3
"""Host fault-injection test of worker boundaries, using a fake vendor library.
Not a SoftPQE inference/quality test. Requires c++.
"""
from pathlib import Path
import os,subprocess,tempfile,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory() as tmp:
    d=Path(tmp);worker=d/'worker';lib=d/'fake.so';cpp=d/'fake.cpp'
    cpp.write_text(r'''
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
static bool ready=false;
extern "C" {
int vdnnPlatformInitV2(void** out,const std::string& config){
 if(config!="PLATFORM:SM8750_2_28 APK:0 SIGNEDPD:0")return 9;
 if(strcmp(getenv("TEST_FAULT"),"platform")==0)return 8;
 ready=true;*out=&ready;return 0;
}
int vivoSoftPQEInit(void** out,const void* init){
 const auto* p=static_cast<const unsigned char*>(init);uint32_t width,height;
 memcpy(&height,p+4,4);memcpy(&width,p+8,4);
 float gain;memcpy(&gain,p+0x64,4);
 if(!ready || gain!=2.f || width!=32 || height!=32)return 5;
 const char* path;memcpy(&path,p+0x100,sizeof(path));
 if(getenv("TEST_PROFILE") && strcmp(path,getenv("TEST_PROFILE")))return 11;
 *out=calloc(1,0x5dd0);return *out?0:6;
}
int vivoSoftPQEGetMode(void*){return strcmp(getenv("TEST_FAULT"),"bypass")==0?1:0;}
int vivoSoftPQEProcess(void*,const void* input,void* output){
 auto* out=static_cast<unsigned char*>(output);
 const char* mode=getenv("TEST_FAULT");
 if(strcmp(mode,"error")==0)return 7;
 uint32_t bits=0;memcpy(&bits,static_cast<const char*>(input)+0x190,4);if(bits!=8)return 8;
 unsigned char* plane;memcpy(&plane,out+0x10,8);
 memset(plane,128,64*64*3/2);
 if(strcmp(mode,"overrun")==0)plane[64*64*3/2]=0;
 if(strcmp(mode,"descriptor")==0)memset(out+4,0,4);
 if(strcmp(mode,"stride")==0)memset(out+0x30,0,4);
 return 0;
}
int vivoSoftPQEUninit(void* p){free(p);return 0;}
}
''')
    subprocess.run(['c++','-std=c++17','-Wall','-Wextra','-Werror','-DVIVO_VDNN_LIBRARY="'+str(lib)+'"',str(root/'app/src/main/cpp/vivo-upscale-worker.cpp'),'-ldl','-o',str(worker)],check=True)
    subprocess.run(['c++','-shared','-fPIC',str(cpp),'-o',str(lib)],check=True)
    inp=d/'input';out=d/'output';inp.write_bytes(bytes([128])*(32*32*3//2))
    for fault in ['none','platform','bypass','error','overrun','descriptor','stride']:
        out.write_bytes(b'')
        p=subprocess.run([str(worker),'--softpqe',str(lib),'/unused',str(inp),str(out),'32','32','64','64','17','100','2'],env={**os.environ,'TEST_FAULT':fault},capture_output=True,text=True,timeout=10)
        if fault=='none':
            assert p.returncode==0 and 'SOFTPQE EXPERIMENT COMPLETE' in p.stdout,(p.stdout,p.stderr)
            assert out.stat().st_size==64*64*3//2
        else:
            assert p.returncode!=0 and out.stat().st_size==0,(fault,p.stdout,p.stderr)
        print('PASS worker boundary:',fault)
    # Full launcher contract: temporary native tuning is passed to Init, stock
    # templates stay unchanged, and the selected mix reaches the saved bytes.
    config=d/'stock';config.mkdir();job=d/'job';job.mkdir()
    y='<noise1Level>0.4</noise1Level><maxNoise1Level>0.8</maxNoise1Level><noise2Level>0.2</noise2Level>'
    uv='<minLevel>0.1</minLevel><anchorLevel>0.2</anchorLevel><maxLevel>0.8</maxLevel><noise2Level>0.2</noise2Level>'
    sharp=''.join(f'<{t}>0.5</{t}>' for t in ['weight1O','weight1U','weight2O','weight2U','usmStrength'])
    profile=f'<swispConfig><yModelParameters>{y}</yModelParameters><uvModelParameters>{uv}</uvModelParameters><postSharpeningParameters>{sharp}</postSharpeningParameters></swispConfig>'
    (config/'softpqe_configs.xml').write_text('<softpqeConfig><enableUsm>1</enableUsm></softpqeConfig>')
    for suffix in ['', '_portrait', '_skin']:(config/f'softpqe_configs_master_2x{suffix}.xml').write_text(profile)
    before={p.name:p.read_bytes() for p in config.iterdir()}
    inp.write_bytes(bytes([64])*(32*32*3//2));out=job/'output';out.write_bytes(b'')
    p=subprocess.run([str(worker),'--softpqe',str(lib),str(config),str(inp),str(out),'32','32','64','64','17','100','2','25','50','0','50'],
        env={**os.environ,'TEST_FAULT':'none','TEST_PROFILE':str(job)},capture_output=True,text=True,timeout=10)
    assert p.returncode==0,(p.stdout,p.stderr)
    assert out.read_bytes()==bytes([96])*(64*64*3//2)
    assert before=={p.name:p.read_bytes() for p in config.iterdir()}
    assert ET.parse(job/'softpqe_configs.xml').findtext('enableUsm')=='0'
    assert ET.parse(job/'softpqe_configs_master_2x.xml').findtext('yModelParameters/noise1Level')=='0.1'
    print('PASS worker tuning: private configs -> native Init, output mix, unchanged firmware templates')
print('Fake library only; device inference and quality are untested.')
