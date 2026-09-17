#include "../app/src/main/cpp/vivo-hexquad-profile-check.h"
#include <cassert>
using namespace vivo_hexquad;
struct DiagnosticNetwork {
    std::vector<float> input=std::vector<float>(288u*288u*18),output=std::vector<float>(576u*576u*3);
    float* bound=input.data();int calls=0;bool invalid=false,mutate=false;
    void execute(){
        assert(input.data()==bound);++calls;
        // Deliberately biased output: diagnostic results must never authorize
        // capture or silently replace its failed colour gate.
        NormalVst transfer(800);auto lut=transfer.forward();
        for(size_t i=0;i<output.size();++i)output[i]=lut[unsigned(std::lround((i%3==2?.125f:.2f)*16383.f))]/65535.f;
        if(invalid)output[0]=std::numeric_limits<float>::quiet_NaN();
        if(mutate)input[0]=input[0]+.01f;
    }
};
int main(){
    NormalVst transfer(800);const double expectedVariance=transfer.shot*.2+transfer.variance;
    const auto clean=makeIsoInput(800,3,{{.2f,.2f,.2f}},0);
    for(int red=0;red<4;++red){
        auto input=makeIsoInput(800,red,{{.2f,.2f,.2f}},0);
        assert(input.packed==clean.packed); // Flat canonical support is identical for all CFAs.
    }
    auto noisy=makeIsoInput(800,3,{{.2f,.2f,.2f}},1);
    assert(diagnosticHash(noisy.packed)==diagnosticHash(makeIsoInput(800,3,{{.2f,.2f,.2f}},1).packed));
    assert(diagnosticHash(noisy.packed)!=diagnosticHash(makeIsoInput(800,3,{{.2f,.2f,.2f}},2).packed));
    for(size_t f=0;f<Frames;++f){
        assert(std::abs(noisy.mean[f])<.0005);
        assert(std::abs(noisy.variance[f]/expectedVariance-1)<.03);
        size_t different=0;
        for(int y=0;y<288;++y)for(int x=0;x<288;++x){
            int c=(x%8<4&&y%8<4)?0:(x%8>=4&&y%8>=4)?2:1;
            size_t p=(size_t(y)*288+x)*18;
            for(int channel=0;channel<3;++channel){
                float v=noisy.packed[p+f*3+channel];assert(channel==c?v>0:v==0);
            }
            if(f)different+=noisy.packed[p+f*3+c]!=noisy.packed[p+c];
        }
        if(f)assert(different>80000); // Independent noise in six frame slots.
    }
    std::ostringstream messages;auto* previous=std::cout.rdbuf(messages.rdbuf());
    DiagnosticNetwork net;diagnoseHexIso(net,800,3);assert(net.calls==17);
    assert(messages.str().find("repeat_max=0 repeat_RMSE=0")!=std::string::npos);
    assert(messages.str().find("meets_chart_limits=0")!=std::string::npos);
    assert(messages.str().find("capture gate unchanged")!=std::string::npos);
    DiagnosticNetwork rejected;bool failed=false;
    try{requireHexCaptureCharts(rejected,800,3);}catch(const std::runtime_error&){failed=true;}
    assert(failed&&rejected.calls==71); // 54 strict profiled charts + 17 diagnostics, still rejects.
    for(int kind:{0,1}){
        DiagnosticNetwork bad;bad.invalid=kind==0;bad.mutate=kind==1;bool caught=false;
        try{diagnoseHexIso(bad,800,3);}catch(const std::invalid_argument&){caught=true;}assert(caught);
    }
    std::cout.rdbuf(previous);
    std::cout<<"HexQuad ISO diagnostics: deterministic independent noise, repeat checks, no capture gate bypass PASS\n";
}
