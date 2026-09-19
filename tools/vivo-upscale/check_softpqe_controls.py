#!/usr/bin/env python3
"""Pixel tests of output controls, not a device/QNN inference test.
Optional argument: extracted stock aigc_24M directory for main XML validation.
"""
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
// Out-of-place reference with all source residuals computed before any write.
std::vector<uint8_t> oracle(const std::vector<uint8_t>& in,const std::vector<uint8_t>& native,
                            int w,int h,unsigned yAmount,unsigned uvAmount) {
 auto result=native;const int ow=w*2,oh=h*2;
 for(int plane=0;plane<2;++plane){
  int ch=plane?2:1,iw=w/ch,ih=h/ch,pw=iw*2,ph=ih*2;
  size_t si=plane?size_t(w)*h:0,di=plane?size_t(ow)*oh:0;
  std::vector<float> residual(size_t(iw)*ih*ch);
  for(int y=0;y<ih;++y)for(int x=0;x<iw;++x)for(int c=0;c<ch;++c){
   size_t o=di+(size_t(y*2)*pw+x*2)*ch+c;
   residual[(size_t(y)*iw+x)*ch+c]=in[si+(size_t(y)*iw+x)*ch+c]-(native[o]+native[o+ch]+native[o+pw*ch]+native[o+(pw+1)*ch])*.25f;
  }
  for(int y=0;y<ph;++y)for(int x=0;x<pw;++x)for(int c=0;c<ch;++c){
   float sy=std::clamp((y+.5f)*.5f-.5f,0.f,float(ih-1)),sx=std::clamp((x+.5f)*.5f-.5f,0.f,float(iw-1));
   int x0=int(sx),y0=int(sy);float fx=sx-x0,fy=sy-y0;
   auto sample=[&](int xx,int yy){return residual[(size_t(std::min(yy,ih-1))*iw+std::min(xx,iw-1))*ch+c];};
   float a=sample(x0,y0)*(1-fx)+sample(x0+1,y0)*fx;
   float b=sample(x0,y0+1)*(1-fx)+sample(x0+1,y0+1)*fx;
   size_t o=di+(size_t(y)*pw+x)*ch+c;
   result[o]=uint8_t(std::clamp(std::lround(native[o]+(100-(plane?uvAmount:yAmount))*.01f*(a*(1-fy)+b*fy)),0l,255l));
  }
 }
 return result;
}
int main(int argc,char** argv){
 if(argc>1){std::ifstream f(argv[1]);std::string xml((std::istreambuf_iterator<char>(f)),{});std::cout<<tuneMain(xml,{});return 0;}
 for(auto dims:{std::pair<int,int>{2,2},{32,24},{24,32}}){
  int w=dims.first,h=dims.second,ow=w*2,oh=h*2;size_t bytes=size_t(ow)*oh*3/2;
  std::vector<uint8_t> in(size_t(w)*h*3/2),raw(bytes+16,0xa5);
  uint32_t state=1729;auto rnd=[&](){state=1664525*state+1013904223;return uint8_t(state>>24);};
  for(auto& v:in)v=rnd();for(size_t i=0;i<bytes;++i)raw[i]=rnd();
  auto out=raw;auto stats=finish(in.data(),out.data(),w,h,ow,oh,{100,100,0,100});assert(out==raw && stats.luma==0 && stats.chroma==0 && stats.sharpen==0);
  for(unsigned y:{0u,37u,100u})for(unsigned uv:{0u,53u,100u}){
   out=raw;stats=finish(in.data(),out.data(),w,h,ow,oh,{y,uv,0,100});assert(out==oracle(in,raw,w,h,y,uv));
   if(y<100)assert(stats.luma>0);if(uv<100)assert(stats.chroma>0);
  }
  out=raw;finish(in.data(),out.data(),w,h,ow,oh,{0,0,100,0});auto expected=raw;mix(in.data(),expected.data(),w,h,ow,oh,0);assert(out==expected);
  out=raw;stats=finish(in.data(),out.data(),w,h,ow,oh,{100,100,100,100});assert(stats.sharpen>0);
  assert(std::equal(out.begin()+ow*oh,out.end(),raw.begin()+ow*oh));
  for(int i=0;i<ow*oh;++i)assert(std::abs(int(out[i])-raw[i])<=12);
 }
 // Flat neural output vs noisy input: independent Y/UV controls must change
 // pixels at 0/50/100 even if every native noise coefficient was zero.
 int w=32,h=24,ow=64,oh=48;std::vector<uint8_t> in(w*h*3/2),raw(ow*oh*3/2,128);
 for(size_t i=0;i<in.size();++i)in[i]=uint8_t(110+((i*17+i/32)%37));
 auto a=raw,b=raw;auto zero=finish(in.data(),a.data(),w,h,ow,oh,{0,100,0,100});auto half=finish(in.data(),b.data(),w,h,ow,oh,{50,100,0,100});
 assert(zero.luma>half.luma && half.luma>0 && a!=b && b!=raw);assert(std::equal(a.begin()+ow*oh,a.end(),raw.begin()+ow*oh));
 a=raw;auto uv=finish(in.data(),a.data(),w,h,ow,oh,{100,0,0,100});assert(uv.chroma>0 && std::equal(a.begin(),a.begin()+ow*oh,raw.begin()));
 // Constant field must not acquire edges; USM gain is continuous on an edge.
 a=raw;assert(sharpenPlane(a.data(),ow,oh,100)==0 && a==raw);
 for(int y=0;y<oh;++y)for(int x=ow/2;x<ow;++x)raw[y*ow+x]=200;
 a=raw;b=raw;double s100=sharpenPlane(a.data(),ow,oh,100),s50=sharpenPlane(b.data(),ow,oh,50);assert(s100>s50 && s50>0);
 bool rejected=false;try{finish(in.data(),a.data(),w,h,ow,oh,{101,0,0,100});}catch(const std::runtime_error&){rejected=true;}assert(rejected);
 std::cout<<"PASS SoftPQE pixels: Y/UV 0/50/100, independent planes, scalar oracle, sharp off/gain/flat field, mix zero, borders and guards\n";
}
''')
    subprocess.run(['c++','-std=c++17','-O2','-Wall','-Wextra','-Werror','-Wno-misleading-indentation','-fsanitize=address,undefined','-I',str(root/'app/src/main/cpp'),str(src),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True)
    if len(sys.argv)>1:
        p=Path(sys.argv[1])/'softpqe_configs.xml';original=ET.parse(p)
        tuned=ET.fromstring(subprocess.check_output([str(exe),str(p)],text=True))
        for tag in ['enableUsm','enableSharpen','enablePartialSharpen']:assert tuned.findtext('.//'+tag)=='0'
        for tag in ['enableSuperResolution','defaultAlgoConfig','processMode']:assert tuned.findtext(tag)==original.findtext(tag)
        print('PASS original XML: only native sharpening switches disabled; SR profile preserved')
