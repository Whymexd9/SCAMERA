#!/usr/bin/env python3
"""Synthetic output invariants, not Vivo inference or subjective quality tests."""
from pathlib import Path
import subprocess,tempfile
root=Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory() as tmp:
    source=Path(tmp)/'controls.cpp';exe=Path(tmp)/'controls'
    source.write_text(r'''
#include "vivo-raisr-controls.h"
#include <cassert>
#include <iostream>
using namespace vivo_raisr;
int main(){
 const int w=32,h=32,ow=64,oh=64; const size_t n=ow*oh*3/2;
 std::vector<uint8_t> input(w*h*3/2,128), raw(n,128);
 for(int y=0;y<oh;y++)for(int x=0;x<ow;x++)raw[y*ow+x]=128+(((x+y)&1)?24:-24);
 auto out=raw;finish(input.data(),out.data(),w,h,ow,oh,{100,0,0});assert(out==raw);
 out=raw;finish(input.data(),out.data(),w,h,ow,oh,{0,0,0});
 for(auto v:out)assert(v==128);
 out=raw;finish(input.data(),out.data(),w,h,ow,oh,{100,100,0});
 for(int y=1;y<oh-1;y++)for(int x=1;x<ow-1;x++)assert(std::abs(int(out[y*ow+x])-128)<=3);
 out=raw;finish(input.data(),out.data(),w,h,ow,oh,{100,0,100});
 for(auto v:out)assert(v==128);
 // Sharp step and chroma asymmetry: no new overshoot, UV pair order preserved.
 for(int y=0;y<h;y++)for(int x=0;x<w;x++)input[y*w+x]=x<w/2?32:220;
 for(size_t i=w*h;i<input.size();i+=2){input[i]=70;input[i+1]=180;}
 out=raw;finish(input.data(),out.data(),w,h,ow,oh,{100,0,100});
 for(int y=0;y<oh;y++)for(int x=0;x<ow;x++)assert(out[y*ow+x]>=32 && out[y*ow+x]<=220);
 out=raw;finish(input.data(),out.data(),w,h,ow,oh,{0,100,100});
 for(size_t i=ow*oh;i<n;i+=2){assert(out[i]==70);assert(out[i+1]==180);}
 for(int y=0;y<oh;y++){assert(out[y*ow]==32);assert(out[y*ow+ow-1]==220);}
 // 1x is an exact reference, including the last row and chroma.
 out=input;for(auto& v:out)v=255-v;out.resize(input.size()+16,0xa5);
 finish(input.data(),out.data(),w,h,w,h,{0,60,70});
 assert(std::equal(input.begin(),input.end(),out.begin()));
 for(size_t i=input.size();i<out.size();i++)assert(out[i]==0xa5);
 // High-ISO-like input with broad, curved enhancement residuals. Exercise
 // the user's saved 70/35/70 controls at both native size and 2x output.
 uint32_t seed=17;
 for(int y=0;y<h;y++)for(int x=0;x<w;x++){
   seed=seed*1664525u+1013904223u;input[y*w+x]=128+int(seed>>24)%25-12;
 }
 for(int scale: {1,2}){
   int ww=w*scale,hh=h*scale;
   std::vector<uint8_t> reference(ww*hh*3/2,128), worms=reference;
   finish(input.data(),reference.data(),w,h,ww,hh,{0,0,0});
   for(int y=0;y<hh;y++)for(int x=0;x<ww;x++)
     worms[y*ww+x]=reference[y*ww+x]+int(18*std::sin(x*.65+2*std::sin(y*.31)));
   auto unprotected=worms,protectedOut=worms;
   finish(input.data(),unprotected.data(),w,h,ww,hh,{70,0,70});
   finish(input.data(),protectedOut.data(),w,h,ww,hh,{70,35,70});
   double before=0,after=0;
   for(int y=2;y<hh-2;y++)for(int x=2;x<ww-2;x++){
     int i=y*ww+x;before+=std::abs(int(unprotected[i])-reference[i]);
     after+=std::abs(int(protectedOut[i])-reference[i]);
   }
   std::cout<<"noise residual "<<scale<<"x: "<<after/before<<" of unprotected"<<std::endl;
   assert(after<before*.5);
 }
 // A coherent ramp retains enhancement: protection must not blanket-disable RAISR.
 for(int y=0;y<h;y++)for(int x=0;x<w;x++)input[y*w+x]=40+x*5;
 out=input;for(int y=0;y<h;y++)for(int x=0;x<w;x++)out[y*w+x]+=4;
 finish(input.data(),out.data(),w,h,w,h,{70,35,70});
 for(int y=2;y<h-2;y++)for(int x=2;x<w-2;x++)assert(out[y*w+x]>=input[y*w+x]+2);
 std::cout<<"PASS RAISR raw/baseline endpoints, fine-artifact suppression, halo bounds, NV21 order, row edges/guards\n";
}
''')
    subprocess.run(['c++','-std=c++17','-O2','-Wall','-Wextra','-Werror',
        '-fsanitize=address,undefined','-I',str(root/'app/src/main/cpp'),str(source),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True)
