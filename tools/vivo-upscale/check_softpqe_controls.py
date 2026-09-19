#!/usr/bin/env python3
"""Test native tuning isolation and output-mix endpoints; no device inference.
Optionally supply the extracted aigc_24M directory to validate real templates.
"""
from pathlib import Path
import subprocess, tempfile, sys, xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[2]

def fixture():
    y='<noise1Level>0.4,0.2</noise1Level><maxNoise1Level>0.8,0.6</maxNoise1Level><noise2Level>0.1,0.2</noise2Level><blurScale>1,1</blurScale>'
    uv='<noise1Level><minLevel>0,0.1</minLevel><anchorLevel>0.2,0.3</anchorLevel><maxLevel>0.6,0.8</maxLevel></noise1Level><noise2Level>0.1,0.2</noise2Level><noise3Level>2,3</noise3Level>'
    sharp=''.join(f'<{t}>0.4</{t}>' for t in ['weight1O','weight1U','weight2O','weight2U','usmStrength'])
    return '<swispConfig><modelName>fixture.vdnn</modelName>'+''.join(
        f'<levConfigData><rawISO>{iso}</rawISO><yModelParameters>{y}</yModelParameters><auxYModelParameters>{y}</auxYModelParameters><uvModelParameters>{uv}</uvModelParameters><postSharpeningParameters>{sharp}</postSharpeningParameters></levConfigData>'
        for iso in [50,800])+'</swispConfig>'

with tempfile.TemporaryDirectory() as tmp:
    d=Path(tmp);src=d/'test.cpp';exe=d/'test'
    src.write_text(r'''
#include "vivo-softpqe-controls.h"
#include <fstream>
#include <iostream>
#include <cassert>
int main(int argc,char** argv){
 using namespace vivo_softpqe;
 if(argc==1){
  const int w=32,h=32,ow=64,oh=64;
  std::vector<uint8_t> in(w*h*3/2,80),out(ow*oh*3/2+16,160);
  for(size_t i=w*h;i<in.size();i+=2){in[i]=50;in[i+1]=190;}
  auto raw=out;mix(in.data(),out.data(),w,h,ow,oh,100);assert(out==raw);
  mix(in.data(),out.data(),w,h,ow,oh,0);
  for(int i=0;i<ow*oh;++i)assert(out[i]==80);
  for(int i=ow*oh;i<ow*oh*3/2;i+=2){assert(out[i]==50);assert(out[i+1]==190);}
  for(size_t i=ow*oh*3/2;i<out.size();++i)assert(out[i]==160);
  out=raw;mix(in.data(),out.data(),w,h,ow,oh,50);assert(out[0]==120);
  assert(out[ow*oh]==105 && out[ow*oh+1]==175);
  return 0;
 }
 std::ifstream f(argv[1]);std::string xml((std::istreambuf_iterator<char>(f)),{});
 Controls c{unsigned(std::stoul(argv[2])),unsigned(std::stoul(argv[3])),unsigned(std::stoul(argv[4])),100};
 std::cout<<(std::string(argv[5])=="main"?tuneMain(xml,c):tuneProfile(xml,c));
}
''')
    subprocess.run(['c++','-std=c++17','-O2','-Wall','-Wextra','-Werror','-fsanitize=address,undefined',
        '-I',str(root/'app/src/main/cpp'),str(src),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True)
    def tune(xml,luma,chroma,sharp,kind='profile'):
        p=d/'profile.xml';p.write_text(xml)
        return subprocess.check_output([str(exe),str(p),str(luma),str(chroma),str(sharp),kind],text=True)
    original=fixture();assert tune(original,100,100,100)==original
    changed=ET.fromstring(tune(original,25,50,40))
    for lev in changed.findall('levConfigData'):
        for tag in ['yModelParameters','auxYModelParameters']:
            assert lev.findtext(tag+'/noise1Level')=='0.1,0.05'
            assert lev.findtext(tag+'/maxNoise1Level')=='0.2,0.15'
            assert lev.findtext(tag+'/blurScale')=='1,1'
        assert lev.findtext('uvModelParameters/noise1Level/maxLevel')=='0.3,0.4'
        assert lev.findtext('uvModelParameters/noise3Level')=='2,3'
        assert lev.findtext('postSharpeningParameters/usmStrength')=='0.16'
    assert [x.text for x in changed.findall('.//rawISO')]==['50','800']
    zero=ET.fromstring(tune(original,0,0,0))
    assert zero.findtext('.//yModelParameters/noise1Level')=='0,0'
    assert all(float(x)>0 for x in zero.findtext('.//yModelParameters/maxNoise1Level').split(','))
    assert zero.findtext('.//postSharpeningParameters/usmStrength')=='0'
    main='<softpqeConfig><enableUsm>1</enableUsm><enableSuperResolution>2</enableSuperResolution></softpqeConfig>'
    assert tune(main,100,100,100,'main')==main
    assert ET.fromstring(tune(main,0,0,0,'main')).findtext('enableUsm')=='0'
    if len(sys.argv)>1:
        for path in Path(sys.argv[1]).glob('*.xml'):
            data=path.read_text();kind='main' if path.name=='softpqe_configs.xml' else 'profile'
            assert tune(data,100,100,100,kind)==data
            ET.fromstring(tune(data,25,50,40,kind));ET.fromstring(tune(data,0,0,0,kind))
    print('PASS SoftPQE: native Y/UV isolation, auxiliary Y, ISO/blur preservation, stock identity, USM off, mix endpoints and guards')
