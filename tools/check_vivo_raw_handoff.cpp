#include "vivo-raw-handoff.h"
#include "vivo-raw-nice-input.h"
#include <functional>
#include <iostream>
#include <stdexcept>
#include "vivo_raw_test_packets.h"
int main(){
    std::array<Bytes,7> p;for(unsigned i=0;i<7;i++)p[i]=packet(i);
    auto first=vivo_raw::decode(p[0].data(),p[0].size());
    check(first.pixels.size()==4096 && first.pixels[64]==1 && first.pixels.back()==126);
    vivo_raw::Burst burst(123,456,3,1,1);
    for(unsigned i:{6,3,0,5,2,4,1})burst.accept(p[i].data(),p[i].size());
    auto frames=burst.finish();std::array<unsigned,7> order{2,0,1,3,6,4,5};
    for(size_t i=0;i<7;i++)check(frames[i].frameNumber==100+order[i]);
    rejects([&]{burst.finish();});rejects([&]{burst.accept(p[0].data(),p[0].size());});
    {
        auto equal=p;putFloat(equal[6],108+2*4,8);
        vivo_raw::Burst b(123,456,3,1,1);
        for(auto& item:equal)b.accept(item.data(),item.size());
        auto result=b.finish();
        check(result[4].exposureProduct()==result[0].exposureProduct());
        check(result[4].timestamp!=result[0].timestamp && result[4].pixels[0]!=result[0].pixels[0]);
        putFloat(equal[6],108+2*4,4);
        vivo_raw::Burst invalid(123,456,3,1,1);
        for(auto& item:equal)invalid.accept(item.data(),item.size());
        rejects([&]{invalid.finish();});
    }
    {
        vivo_raw::Burst transaction(123,456,3,1,1);
        for(unsigned i:{6,3,0,5,2,4,1})transaction.accept(p[i].data(),p[i].size());
        vivo_raw::NiceInput owner(transaction);
        const auto& input=owner.view();
        check(input.w==64 && input.h==64 && input.scene.timestamp==1002);
        check(input.scene.flags==0 && input.cameraNoise && input.hasNormalNoise);
        check(input.noiseReferenceSlot==4 && input.raw[0][0]==200 && input.raw[4][0]==600);
        check(input.exposure[0]==1 && input.exposure[4]==2 &&
              input.exposure[5]==.25f && input.exposure[6]==.0625f);
        for(size_t i=0;i<7;i++)check(input.ae[i].timestamp==1000+order[i]);
        rejects([&]{transaction.finish();});
    }
    for(size_t n:{size_t(0),size_t(vivo_raw::HeaderBytes-1),p[0].size()-1})rejects([&]{vivo_raw::decode(p[0].data(),n);});
    for(size_t field:{size_t(0),size_t(4),size_t(8),size_t(12),size_t(52),size_t(56),size_t(64),size_t(68),size_t(280)}){
        auto b=p[0];put32(b,field,0xffffffff);rejects([&]{vivo_raw::decode(b.data(),b.size());});
    }
    for(size_t field:{size_t(72),size_t(76),size_t(268),size_t(272),size_t(108+2*4)}){
        auto b=p[0];put32(b,field,0x7fc00000);rejects([&]{vivo_raw::decode(b.data(),b.size());});
    }
    auto wrongAe=p[0];put64(wrongAe,92,9999);rejects([&]{vivo_raw::decode(wrongAe.data(),wrongAe.size());});
    for(size_t field:{size_t(16),size_t(24),size_t(48)}){
        vivo_raw::Burst b(123,456,3,1,1);auto wrong=p[0];put32(wrong,field,99);
        rejects([&]{b.accept(wrong.data(),wrong.size());});rejects([&]{b.accept(p[0].data(),p[0].size());});
    }
    vivo_raw::Burst duplicate(123,456,3,1,1);duplicate.accept(p[0].data(),p[0].size());
    rejects([&]{duplicate.accept(p[0].data(),p[0].size());});rejects([&]{duplicate.finish();});
    for(unsigned count:{0,5,6}){
        vivo_raw::Burst missing(123,456,3,1,1);for(unsigned i=0;i<count;i++)missing.accept(p[i].data(),p[i].size());
        rejects([&]{missing.finish();});
    }
    for(unsigned variation=0;variation<4;variation++){
        auto inputs=p;
        if(variation==0)put32(inputs[5],52,1);
        if(variation==1)put32(inputs[2],280,0);
        if(variation==2)putFloat(inputs[5],108+2*4,2);
        if(variation==3)put32(inputs[0],280,1);
        vivo_raw::Burst invalid(123,456,3,1,1);for(auto& b:inputs)invalid.accept(b.data(),b.size());
        rejects([&]{invalid.finish();});
    }
    {
        auto paired=p;
        put64(paired[4],284,2);put64(paired[5],284,2);
        for(auto indices:std::array<std::array<unsigned,2>,2>{{{4,2},{5,6}}}) {
            put64(paired[indices[0]],32,100+indices[1]);
            put64(paired[indices[0]],40,1000+indices[1]);
            put64(paired[indices[0]],92,1000+indices[1]);
        }
        vivo_raw::Burst split(123,456,3,1,2);
        for(unsigned i:{5,1,4,6,0,3,2})split.accept(paired[i].data(),paired[i].size());
        vivo_raw::NiceInput owner(split);
        check(owner.view().ae[0].timestamp==owner.view().ae[5].timestamp);
        check(owner.view().ae[4].timestamp==owner.view().ae[6].timestamp);
        check(owner.view().raw[0][0]!=owner.view().raw[5][0]);
        check(owner.view().exposure[5]==.25f && owner.view().exposure[6]==.0625f);
        vivo_raw::Burst wrongStream(123,456,3,1,1);
        rejects([&]{wrongStream.accept(paired[4].data(),paired[4].size());});
        auto forged=p[0];put64(forged,284,2);
        vivo_raw::Burst normalStream(123,456,3,1,2);
        rejects([&]{normalStream.accept(forged.data(),forged.size());});
        auto sameStream=paired[4];put64(sameStream,284,1);
        vivo_raw::Burst duplicateTime(123,456,3,1,1);
        duplicateTime.accept(p[2].data(),p[2].size());
        rejects([&]{duplicateTime.accept(sameStream.data(),sameStream.size());});
        auto oldVersion=p[0];put32(oldVersion,4,1);
        rejects([&]{vivo_raw::decode(oldVersion.data(),oldVersion.size());});
        auto missingStream=p[0];put64(missingStream,284,0);
        rejects([&]{vivo_raw::decode(missingStream.data(),missingStream.size());});
        rejects([&]{vivo_raw::Burst invalid(123,456,3,0,1);});
    }
    std::cout<<"PASS: RAW16 padding, explicit reference/order, measured exposures, exact identity, malformed bounds and closed transactions\n";
}
