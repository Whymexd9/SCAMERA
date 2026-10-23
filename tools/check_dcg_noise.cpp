// P320: the DCG two-zone noise fit (app/src/main/cpp/scam-dcg-noise.h) on synthetic flat blocks, measured as the worker's noise
// check measures them (hybridMeasureNoise: 32 x 32 sites of a green phase, noise = half the variance of the base - frame difference).
//   g++ -std=c++17 -O2 -Wall -Wextra tools/check_dcg_noise.cpp -o /tmp/check-dcg-noise && /tmp/check-dcg-noise
#include "../app/src/main/cpp/scam-dcg-noise.h"
#include <cstdint>
#include <cstdio>
#include <random>
#include <string>

namespace {
int failures=0;
void expect(bool ok,const char* what){
    std::printf("%s %s\n",ok?"PASS":"FAIL",what);
    if(!ok)++failures;
}
bool near(double got,double want,double tol){return std::abs(got-want)<=tol*std::abs(want);}
// Normal deviates from mt19937 (Box-Muller): the same numbers with every standard library
struct Rng {
    std::mt19937 g;bool has=false;double spare=0;
    explicit Rng(uint32_t seed):g(seed){}
    double uniform(){return (double(g())+0.5)/4294967296.0;}
    double normal(){
        if(has){has=false;return spare;}
        const double u=uniform(),v=uniform(),r=std::sqrt(-2.0*std::log(u));
        spare=r*std::sin(6.283185307179586*v);has=true;
        return r*std::cos(6.283185307179586*v);
    }
};
struct Truth { double s,oh,ol,knee; };
double modelAt(const Truth& t,double m){return t.s*m+(m<t.knee?t.oh:t.ol);}
// n blocks, means log-uniform in [lo, hi]; outliers: that share of the blocks with x[2, 6] the noise (motion, texture the flat test let
// through) or x0.3 (a frame pair that shares part of its noise)
std::vector<std::array<float,2>> blocks(const Truth& t,int n,double lo,double hi,double outliers,uint32_t seed){
    Rng r(seed);std::vector<std::array<float,2>> out;
    for(int i=0;i<n;++i){
        const double m=lo*std::exp(std::log(hi/lo)*r.uniform()),var=modelAt(t,m);
        double sd=0,sdd=0; // d = v0 - v1 of two frames with this variance each
        for(int k=0;k<1024;++k){const double d=std::sqrt(2.0*var)*r.normal();sd+=d;sdd+=d*d;}
        double noise=0.5*(sdd/1024-(sd/1024)*(sd/1024));
        if(r.uniform()<outliers)noise*=r.uniform()<0.8?2.0+4.0*r.uniform():0.3;
        out.push_back({float(m),float(noise)});
    }
    return out;
}
void report(const char* name,const scam::DcgNoiseFit& f){
    std::printf("  %s: measured=%d zones=%d slope=%.6g hcg=%.6g lcg=%.6g knee=%.6g blocks=%ld/%ld res one=%.4g two=%.4g (%s)\n",name,
        int(f.measured),f.zones,f.slope,f.hcg,f.lcg,f.knee,f.used,f.blocks,f.resOne,f.resTwo,f.why);
}
void twoZones(const char* name,const Truth& t,int n,double outliers,uint32_t seed,bool offsetHcg=true){
    const scam::DcgNoiseFit f=scam::dcgNoiseFit(blocks(t,n,0.003,0.85,outliers,seed));
    report(name,f);
    std::string what=std::string(name)+": two zones, slope / lcg / knee"+(offsetHcg?" / hcg":"")+" within 10 %";
    expect(f.measured&&f.zones==2&&near(f.slope,t.s,0.1)&&near(f.lcg,t.ol,0.1)&&near(f.knee,t.knee,0.1)&&(!offsetHcg||near(f.hcg,t.oh,0.1)),
           what.c_str());
}
} // namespace

int main(){
    // a clear DCG step (read noise x3.5 in sigma above the knee), both offsets visible against the shot noise
    const Truth clear{2e-4,2e-6,2.4e-5,0.15};
    twoZones("clear step",clear,1500,0.0,1);
    twoZones("clear step, 5 % outliers",clear,1500,0.05,2);
    twoZones("clear step, 5 % outliers, 300 blocks",clear,300,0.05,3);
    twoZones("knee 0.06",Truth{2e-4,2e-6,2.4e-5,0.06},1500,0.05,4);
    twoZones("6000 blocks (every 2nd fitted)",clear,6000,0.05,12);
    // X300 Ultra-like 14-bit DCG (8x gain ratio, 1.5 DN HCG read of 7672 DN, 1 e-/DN): an HCG offset far below the shot noise of
    // every block (only its zone, not its value, is measurable); the LCG offset, the slope and the knee are
    twoZones("X300U-like",Truth{1.3e-4,3.8e-8,2.4e-6,0.125},2500,0.05,5,false);
    { // one zone: the same offset on both sides
        const Truth t{2e-4,5e-6,5e-6,0.15};
        for(uint32_t seed:{6u,7u,8u}){
            const scam::DcgNoiseFit f=scam::dcgNoiseFit(blocks(t,1500,0.003,0.85,seed==8u?0.05:0.0,seed));
            report("one zone",f);
            expect(f.measured&&f.zones==1&&f.knee==0&&f.lcg==f.hcg&&near(f.slope,t.s,0.1)&&near(f.hcg,t.oh,0.1),"one zone: zones 1, slope / offset within 10 %");
        }
    }
    { // too few flat blocks
        const scam::DcgNoiseFit f=scam::dcgNoiseFit(blocks(clear,99,0.003,0.85,0.0,9));
        report("99 blocks",f);
        expect(!f.measured&&f.zones==0,"99 blocks: not measured");
        auto b=blocks(clear,120,0.003,0.85,0.0,10);
        for(int i=0;i<21;++i)b[size_t(i)][1]=i%3==0?0.f:i%3==1?-1e-6f:NAN; // unusable entries do not count
        const scam::DcgNoiseFit g=scam::dcgNoiseFit(b);
        report("120 blocks, 21 unusable",g);
        expect(!g.measured&&g.blocks==99,"120 blocks with 21 unusable: not measured");
        const scam::DcgNoiseFit e=scam::dcgNoiseFit({});
        expect(!e.measured,"no blocks: not measured");
    }
    { // exactly the model (no noise): recovered exactly, MAD 0 does not trim everything
        std::vector<std::array<float,2>> b;
        for(int i=0;i<400;++i){const double m=0.003+0.8*i/399.0;b.push_back({float(m),float(modelAt(clear,m))});}
        const scam::DcgNoiseFit f=scam::dcgNoiseFit(b);
        report("noiseless",f);
        expect(f.measured&&f.zones==2&&near(f.slope,clear.s,0.01)&&near(f.hcg,clear.oh,0.01)&&near(f.lcg,clear.ol,0.01)&&near(f.knee,clear.knee,0.02),
               "noiseless: exact two zones");
    }
    { // a falling step (LCG offset below HCG: not DCG) is one zone
        const scam::DcgNoiseFit f=scam::dcgNoiseFit(blocks(Truth{2e-4,2.4e-5,2e-6,0.15},1500,0.003,0.85,0.0,11));
        report("falling step",f);
        expect(f.measured&&f.zones==1,"falling step: one zone");
    }
    std::printf(failures?"check_dcg_noise: %d FAILED\n":"check_dcg_noise: OK\n",failures);
    return failures?1:0;
}
