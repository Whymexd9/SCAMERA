#!/usr/bin/env python3
"""Host execution of two recovered CL bodies with only type/builtin shims.

Pass decoded-source.txt from the supplied CRE evidence. Tests translations and full projective
warp=2/swarp=6, including upRatio, reflection and donor colour tags.
Does not validate stock alignment, CFA ROI planning, capture or tone.
"""
from pathlib import Path
import re
import argparse,subprocess,tempfile,json,os
parser=argparse.ArgumentParser(description="Compare NICE warp adapters with recovered donor OpenCL bodies. No NPU/photo-quality claim.")
parser.add_argument('decoded_source',type=Path,help="Decoded CRE OpenCL source from the pinned donor; not downloaded by this tool")
parser.add_argument("--sanitize",action="store_true",help="Enable address/undefined checks for kernel bodies and adapters")
args=parser.parse_args()
source=args.decoded_source.read_text();chunks=[]
for name in ['vivoRawBackwardWarp2CanvasOrderBayerBufferCL','vivoRawBackwardWarp2CanvasAlignPerChanInterpBayerCL']:
    start=source.index('void '+name+'(');end=source.index('\n__kernel',start)
    chunks.append(source[start:end])
s=('#define GLOBAL_DIMS_2D int gw,int gh\n#define CHECK_GLOBAL_DIMS_2D(x,y)\n'+'\n'.join(chunks)).replace('__kernel','').replace('__global','')
s=s.replace('(int2)(get_global_id(0), get_global_id(1))','int2{gidX,gidY}')
s=re.sub(r'\(int2\)\((\w+), (\w+)\)',r'int2{\1,\2}',s)
prefix='''#include <algorithm>
#include <cstdint>
using std::clamp;using std::min;
struct int2 { int x,y;int2 operator*(int n)const{return {x*n,y*n};}};
struct float4 {float s0,s1,s2,s3;};
static int gidX,gidY;
'''
harness='''
#include NICE_CAPTURE_HEADER
#include <random>
#include <cassert>
#include <iostream>
int main(){
 vivo_nice::Burst b;b.w=b.h=64;b.white=16383;b.cfa=0;
 std::vector<uint16_t> raw(4096),stock(4096*3);b.raw[0]=raw.data();
 std::mt19937 random(10);for(auto& v:raw)v=random()%16384;
 int pattern[]={0,1,1,2};size_t checked=0;
 for(float dx:{-8.2f,-2.51f,-.5f,0.f,.51f,2.2f,8.2f})for(float dy:{-8.2f,-.3f,0.f,4.8f}){
  float4 l{1,0,dx,0},h{1,dy,0,0};
  for(gidY=0;gidY<32;++gidY)for(gidX=0;gidX<32;++gidX)
   vivoRawBackwardWarp2CanvasOrderBayerBufferCL(32,32,raw.data(),stock.data(),l,h,1,64,64,64,64,64,64,pattern,0);
  for(int y=0;y<64;++y)for(int x=0;x<64;++x){
   assert(stock[y*64+x]==vivo_nice::warpOrderBayer(b,0,x,y,{dx,dy}));++checked;
  }
  for(gidY=0;gidY<64;++gidY)for(gidX=0;gidX<64;++gidX)
   vivoRawBackwardWarp2CanvasAlignPerChanInterpBayerCL(64,64,raw.data(),stock.data(),l,h,1,64,64,64,64,64,64,64);
  for(int y=0;y<64;++y)for(int x=0;x<64;++x){
   auto v=vivo_nice::warpShortRgb(b,0,x,y,{dx,dy});
   for(int c=0;c<3;++c){assert(stock[c*4096+y*64+x]==v[c]);++checked;}
  }
 }
 // Test the projective implementation directly against the untouched kernel
 // bodies, not against a reimplementation of the projection equation.
 std::vector<std::array<float,8>> matrices={
  {1,0,0,0,1,0,0,0}, {1,0,.51f,0,1,-.3f,0,0},
  {.98f,-.17f,5,.17f,.98f,-4,0,0}, {1.2f,.08f,-9,-.03f,.83f,7,0,0},
  {1,.04f,-3,-.06f,1,4,.002f,-.001f},
  {.92f,-.12f,8,.11f,1.07f,-7,-.0015f,.0025f}};
 for(int i=0;i<40;++i){
  auto unit=[&](){return float(random()%2001)/1000.f-1.f;};
  matrices.push_back({1+unit()*.15f,unit()*.2f,unit()*10,unit()*.2f,
                     1+unit()*.15f,unit()*10,unit()*.002f,unit()*.002f});
 }
 for(const auto& m:matrices)for(float ratio:{.75f,1.f,1.25f,2.f}){
  const vivo_nice::BackwardHomography transform{m,ratio};
  float4 l{m[0],m[1],m[2],m[3]},h{m[4],m[5],m[6],m[7]};
  for(gidY=0;gidY<32;++gidY)for(gidX=0;gidX<32;++gidX)
   vivoRawBackwardWarp2CanvasOrderBayerBufferCL(32,32,raw.data(),stock.data(),l,h,ratio,64,64,64,64,64,64,pattern,0);
  for(int y=0;y<64;++y)for(int x=0;x<64;++x){
   assert(stock[y*64+x]==vivo_nice::warpOrderBayerProjective(b,0,x,y,transform));++checked;
  }
  for(gidY=0;gidY<64;++gidY)for(gidX=0;gidX<64;++gidX)
   vivoRawBackwardWarp2CanvasAlignPerChanInterpBayerCL(64,64,raw.data(),stock.data(),l,h,ratio,64,64,64,64,64,64,64);
  for(int y=0;y<64;++y)for(int x=0;x<64;++x){
   auto v=vivo_nice::warpShortRgbProjective(b,0,x,y,transform);
   for(int c=0;c<3;++c){assert(stock[c*4096+y*64+x]==v[c]);++checked;}
  }
 }
 for(int kind=0;kind<4;++kind){
  vivo_nice::BackwardHomography bad;
  if(kind==0)bad.h[6]=-1.f/32;
  if(kind==1)bad.h[0]=std::numeric_limits<float>::quiet_NaN();
  if(kind==2)bad.upRatio=0;
  if(kind==3)bad.h[2]=std::numeric_limits<float>::max();
  bool rejected=false;try{vivo_nice::warpOrderBayerProjective(b,0,32,32,bad);}
  catch(const std::invalid_argument&){rejected=true;}assert(rejected);
 }
 std::cout<<"PASS: "<<checked<<" samples bit-identical to recovered OpenCL kernel bodies (translation, rotation, scale, shear, perspective and upRatio; CPU kernel-body oracle)\\n";
}
'''
header=Path(__file__).resolve().parents[1]/'app/src/main/cpp/vivo-nice-capture.h'
with tempfile.TemporaryDirectory(prefix='nice-stock-warp-') as directory:
    cpp=Path(directory)/'oracle.cpp';exe=Path(directory)/'oracle'
    cpp.write_text(prefix+s+harness.replace('NICE_CAPTURE_HEADER',json.dumps(str(header))))
    flags=['-O1','-g','-fsanitize=address,undefined'] if args.sanitize else ['-O2']
    subprocess.run(['g++','-std=c++17',*flags,str(cpp),'-o',str(exe)],check=True)
    env=os.environ.copy()
    if args.sanitize:env["ASAN_OPTIONS"]="detect_leaks=0" # LSan cannot run in the ptraced host
    subprocess.run([str(exe)],check=True,env=env)
