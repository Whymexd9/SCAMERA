#!/usr/bin/env python3
"""SR-only configuration and independent chroma interpolation; no QNN device claim."""
from pathlib import Path
import subprocess, tempfile, sys, xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory() as tmp:
    d=Path(tmp);src=d/'test.cpp';exe=d/'test'
    src.write_text(r'''
#include "vivo-softpqe-controls.h"
#include <fstream>
#include <iostream>
#include <cassert>
using namespace vivo_softpqe;
int main(int argc,char** argv){
 if(argc>1){std::ifstream f(argv[1]);std::string xml((std::istreambuf_iterator<char>(f)),{});
   std::cout<<(std::string(argv[2])=="main"?tuneMain(xml):tuneProfile(xml));return 0;}
 for(auto dims:{std::pair<int,int>{2,2},{32,24},{24,32}}){
  int w=dims.first,h=dims.second,ow=w*2,oh=h*2;size_t bytes=size_t(ow)*oh*3/2;
  std::vector<uint8_t> input(size_t(w)*h*3/2,0),output(bytes+16,0xa5);
  for(int y=0;y<h/2;++y)for(int x=0;x<w/2;++x){
   input[size_t(w)*h+y*w+x*2]=uint8_t(40+(x*13+y*7)%130);
   input[size_t(w)*h+y*w+x*2+1]=uint8_t(210-(x*9+y*11)%130);
  }
  upscaleChroma(input.data(),output.data(),w,h);
  for(size_t i=0;i<size_t(ow)*oh;++i)assert(output[i]==0xa5); // Never touches native Y.
  for(size_t i=bytes;i<output.size();++i)assert(output[i]==0xa5);
  // Independent direct 2-D evaluation, double precision, no row-cache reuse.
  auto sinc=[](double x){if(std::abs(x)<1e-12)return 1.;double p=x*3.14159265358979323846;return std::sin(p)/p;};
  for(int y=0;y<h;++y)for(int x=0;x<w;++x)for(int c=0;c<2;++c){
   double cx=(x+.5)/2-.5,cy=(y+.5)/2-.5,value=0,total=0;
   for(int sy=0;sy<h/2;++sy)for(int sx=0;sx<w/2;++sx){
    double dx=sx-cx,dy=sy-cy;if(std::abs(dx)>=2 || std::abs(dy)>=2)continue;
    double weight=sinc(dx)*sinc(dx/2)*sinc(dy)*sinc(dy/2);
    value+=input[size_t(w)*h+sy*w+sx*2+c]*weight;total+=weight;
   }
   int expected=std::max(0l,std::min(255l,std::lround(value/total)));
   assert(std::abs(int(output[size_t(ow)*oh+y*ow+x*2+c])-expected)<=1);
  }
  std::fill(input.begin(),input.end(),128);upscaleChroma(input.data(),output.data(),w,h);
  for(size_t i=size_t(ow)*oh;i<bytes;++i)assert(output[i]==128);
 }
 assert(constantList(" .2, 0.5,0.9 ","0")=="0,0,0");
 bool rejected=false;try{constantList("not a number","0");}catch(const std::exception&){rejected=true;}assert(rejected);
 rejected=false;try{tuneMain("<softpqeConfig/>");}catch(const std::exception&){rejected=true;}assert(rejected);
 std::cout<<"PASS SR-only chroma: double-precision reference, V/U order, native Y identity, borders/guards, DC and config rejection\n";
}
''')
    subprocess.run(['c++','-std=c++17','-O2','-Wall','-Wextra','-Werror','-Wno-misleading-indentation','-fsanitize=address,undefined','-I',str(root/'app/src/main/cpp'),str(src),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True)
    if len(sys.argv)>1:
        directory=Path(sys.argv[1]);p=directory/'softpqe_configs.xml';original=ET.parse(p)
        tuned=ET.fromstring(subprocess.check_output([str(exe),str(p),'main'],text=True))
        for tag in ['enableUsm','enableSharpen','enablePartialSharpen']:assert tuned.findtext('.//'+tag)=='0'
        assert tuned.findtext('processMode')=='2'
        for tag in ['enableSuperResolution','defaultAlgoConfig']:assert tuned.findtext(tag)==original.findtext(tag)
        for p in directory.glob('softpqe_configs_master_2x*.xml'):
            original=ET.parse(p).getroot();tuned=ET.fromstring(subprocess.check_output([str(exe),str(p),'profile'],text=True))
            for tag in ['yModelName','auxYModelName','uvModelName','yModelOutputFakeQuantScale','yModelOutputFakeQuantOffset','auxYModelOutputFakeQuantScale','auxYModelOutputFakeQuantOffset','enableAuxYProcess']:
                assert tuned.findtext(tag)==original.findtext(tag),(p,tag)
            for section in ['yModelParameters','auxYModelParameters']:
                for block in tuned.findall('.//'+section):
                    for tag in ['noise1Level','noise2Level']:assert set(float(x) for x in block.findtext(tag).split(','))=={0.}
                    assert set(float(x) for x in block.findtext('maxNoise1Level').split(','))=={1e-6}
                assert [x.text for x in tuned.findall('.//'+section+'/blurScale')]==[x.text for x in original.findall('.//'+section+'/blurScale')]
            for block in tuned.findall('.//postSharpeningParameters'):
                for tag in ['weight1O','weight1U','weight2O','weight2U','usmStrength']:assert set(float(x) for x in block.findtext(tag).split(','))=={0.}
            assert [x.text for x in tuned.findall('.//rawISO')]==[x.text for x in original.findall('.//rawISO')]
            print('PASS original profile: zero noise conditioning/sharp weights, preserved SR models/quantization/blur/ISO',p.name)
        print('PASS main XML: Y only, post sharpening disabled, SR enabled')
