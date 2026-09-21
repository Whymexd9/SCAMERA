#pragma once
#include "vivo-raw-handoff.h"
#include <functional>
using Bytes=std::vector<uint8_t>;
void put32(Bytes& b,size_t p,uint32_t v){for(int i=0;i<4;i++)b.at(p+i)=uint8_t(v>>(8*i));}
void put64(Bytes& b,size_t p,uint64_t v){put32(b,p,uint32_t(v));put32(b,p+4,uint32_t(v>>32));}
void putFloat(Bytes& b,size_t p,float v){uint32_t bits;std::memcpy(&bits,&v,4);put32(b,p,bits);}
Bytes packet(unsigned index){
    constexpr unsigned stride=136;
    Bytes b(vivo_raw::HeaderBytes+stride*64,0xa5);
    std::fill(b.begin(),b.begin()+vivo_raw::HeaderBytes,0);
    put32(b,0,0x31465256);put32(b,4,2);put32(b,8,vivo_raw::HeaderBytes);put32(b,12,stride*64);
    put64(b,16,123);put64(b,24,456);put64(b,32,index+100);put64(b,40,index+1000);
    put32(b,48,3);put32(b,52,index<4?0:index==4?1:index==5?2:3);
    put32(b,56,64);put32(b,60,64);put32(b,64,0);put32(b,68,stride);putFloat(b,72,16383);
    for(size_t i=0;i<4;i++)putFloat(b,76+i*4,64);
    put64(b,92,index+1000);put32(b,100,1);
    for(unsigned field:{2u,6u,13u,14u})putFloat(b,108+field*4,1);
    const float products[]={8,8,8,8,2,.5,16};putFloat(b,108+2*4,products[index]);
    putFloat(b,108+14*4,1000000);putFloat(b,268,.01f);putFloat(b,272,.001f);put32(b,276,100);
    put32(b,280,index==2?1:0);put64(b,284,1);
    for(size_t y=0;y<64;y++)for(size_t x=0;x<64;x++){
        const uint16_t value=uint16_t(index*100+y+x);size_t p=vivo_raw::HeaderBytes+y*stride+x*2;
        b[p]=uint8_t(value);b[p+1]=uint8_t(value>>8);
    }
    return b;
}
void rejects(const std::function<void()>& action){
    bool rejected=false;try{action();}catch(const std::exception&){rejected=true;}
    if(!rejected)throw std::runtime_error("Malformed handoff accepted");
}
void check(bool v){if(!v)throw std::runtime_error("RAW handoff assertion");}
