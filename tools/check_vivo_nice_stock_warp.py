#!/usr/bin/env python3
"""Host execution of two recovered CL bodies with only type/builtin shims.

Pass decoded-source.txt from the supplied CRE evidence. Tests the translation
component of warp=2/swarp=6, including border reflection and donor colour tags.
Does not validate stock alignment, CFA ROI planning, capture or tone.
"""
from pathlib import Path
import re
import argparse,subprocess,tempfile,json
parser=argparse.ArgumentParser(description="Compare NICE warp adapters with recovered donor OpenCL bodies. No NPU/photo-quality claim.")
parser.add_argument('decoded_source',type=Path,help="Decoded CRE OpenCL source from the pinned donor; not downloaded by this tool")
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
 std::cout<<"PASS: "<<checked<<" samples bit-identical to recovered OpenCL kernel bodies (translation-only geometry)\\n";
}
'''
header=Path(__file__).resolve().parents[1]/'app/src/main/cpp/vivo-nice-capture.h'
with tempfile.TemporaryDirectory(prefix='nice-stock-warp-') as directory:
    cpp=Path(directory)/'oracle.cpp';exe=Path(directory)/'oracle'
    cpp.write_text(prefix+s+harness.replace('NICE_CAPTURE_HEADER',json.dumps(str(header))))
    subprocess.run(['g++','-std=c++17','-O2',str(cpp),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True)
