#pragma once
#include "vivo-hexquad-check.h"

namespace vivo_hexquad {
// Diagnostic inputs only. Never dither photographs or select a different ISO
// from these results. Profiled test fixtures are also used by the explicit
// capture gate; real RAW samples never enter this synthetic-input builder.
struct ProfileNoise {
    uint32_t state;
    explicit ProfileNoise(uint32_t seed):state(seed) { require(seed!=0,"Zero diagnostic seed"); }
    float normal() {
        // Twelve independent uniform draws: deterministic, unit-variance CLT
        // approximation, not a claimed exact sensor noise distribution.
        float sum=0;
        for(int i=0;i<12;++i){state^=state<<13;state^=state>>17;state^=state<<5;
            sum+=float(state>>8)*(1.f/16777216.f);}
        return sum-6.f;
    }
};
inline uint64_t diagnosticHash(const std::vector<float>& data) {
    uint64_t h=14695981039346656037ULL;
    for(float v:data){uint32_t bits;std::memcpy(&bits,&v,sizeof(bits));h^=bits;h*=1099511628211ULL;}
    return h;
}
struct DiagnosticInput {
    std::vector<float> packed;
    std::array<double,Frames> mean{},variance{};
    float encodedMin=1,encodedMax=0;
};
inline DiagnosticInput makeIsoInput(int iso,int red,const std::array<float,3>& rgb,uint32_t seed,int chart=-1,NoiseScale profile={}) {
    require(chart>=-1 && chart<9,"Invalid diagnostic chart");
    const CfaOrientation orientation(288,288,red);
    NormalVst transfer(iso,profile),physical(iso);auto lut=transfer.forward();
    for(float v:rgb)require(std::isfinite(v)&&v>=0&&v<=1,"Invalid diagnostic target");
    ProfileNoise noise(seed?seed:1);
    DiagnosticInput result;result.packed.assign(288u*288u*18,0);
    for(size_t f=0;f<Frames;++f){double sum=0,squared=0;
        for(int y=0;y<288;++y)for(int x=0;x<288;++x){
            int c=tagSource(0,x,y,red)>>14;
            const float target=chart<0?rgb[c]:chartValue(chart,c,float(x),float(y));
            float value=target;
            if(seed)value+=noise.normal()*std::sqrt(physical.shot*value+physical.variance);
            value=std::max(0.f,std::min(1.f,value));
            auto raw=unsigned(std::lround(value*16383.f));
            double residual=raw/16383.0-target;sum+=residual;squared+=residual*residual;
            float encoded=lut[c*Levels+raw]*(1.f/65535.f);
            result.encodedMin=std::min(result.encodedMin,encoded);result.encodedMax=std::max(result.encodedMax,encoded);
            size_t p=(size_t(orientation.y(y))*288+orientation.x(x))*18+f*3+c;
            result.packed[p]=encoded;
        }
        result.mean[f]=sum/(288*288);result.variance[f]=squared/(288*288)-result.mean[f]*result.mean[f];
    }
    return result;
}
inline HexScore scoreIsoFlat(const std::vector<float>& network,const IvstLuts& inverse,
                            const std::array<float,3>& rgb,int scale=2) {
    require(scale==1||scale==2,"Invalid diagnostic scale");
    const int side=288*scale,margin=32*scale;
    require(network.size()==size_t(side)*side*3,"Unexpected diagnostic output shape");
    auto decoded=decodeTile(network,inverse,{65535.f,0,0,1,0});
    HexScore score;size_t count=0;
    for(int y=margin;y<side-margin;++y)for(int x=margin;x<side-margin;++x)for(int c=0;c<3;++c){
        size_t i=(size_t(y)*side+x)*3+c;
        score.badRange+=network[i]<-.05f||network[i]>1.25f;
        double error=decoded[i]-rgb[c];score.rmse+=error*error;
        score.maxError=std::max(score.maxError,std::abs(error));score.mean[c]+=decoded[i];++count;
    }
    score.rmse=std::sqrt(score.rmse/count);for(double& v:score.mean)v/=count/3;
    return score;
}

// 17 bounded executions: identical-input repeat, gray sweep, history repeat,
// three independent noisy gray bursts, and two seeds for each coloured flat.
// Only synthetic inputs are used. No model weights or runtime are changed.
template<class Network> void diagnoseHexIso(Network& session,int iso,int red,int scale=2,NoiseScale profile={}) {
    require(scale==1||scale==2,"Invalid diagnostic scale");
    const int side=288*scale,margin=32*scale;
    vivo_nn::log("HEX ISO DIAG BEGIN: ISO="+std::to_string(iso)+" red="+std::to_string(red)+
        "; synthetic only; no gate override; no ISO substitution");
    NormalVst transfer(iso,profile);auto inverse=transfer.inverse();
    std::vector<float> baseline;uint64_t baselineInput=0;
    int executions=0;
    auto run=[&](const char* name,std::array<float,3> rgb,uint32_t seed,bool repeat){
        auto fixture=makeIsoInput(iso,red,rgb,seed,-1,profile);
        require(session.input.size()==fixture.packed.size(),"Diagnostic input shape mismatch");
        std::copy(fixture.packed.begin(),fixture.packed.end(),session.input.begin());
        uint64_t before=diagnosticHash(session.input);
        session.execute();++executions;
        uint64_t after=diagnosticHash(session.input);
        auto score=scoreIsoFlat(session.output,inverse,rgb,scale);
        std::ostringstream line;line<<std::setprecision(8)<<"HEX ISO DIAG: "<<name<<" ISO="<<iso
            <<" target="<<rgb[0]<<','<<rgb[1]<<','<<rgb[2]<<" seed="<<seed
            <<" meanRGB="<<score.mean[0]<<','<<score.mean[1]<<','<<score.mean[2]
            <<" RMSE="<<score.rmse<<" max="<<score.maxError<<" range_fail="<<score.badRange
            <<" meets_chart_limits="<<score.pass()<<" input_hash="<<std::hex<<before<<std::dec
            <<" input_changed="<<(before!=after)<<" encoded="<<fixture.encodedMin<<','<<fixture.encodedMax;
        if(baseline.empty()){baseline=session.output;baselineInput=before;}
        else if(repeat){
            require(before==baselineInput,"Repeat diagnostic input changed");
            double delta=0,square=0;size_t count=0;
            for(int y=margin;y<side-margin;++y)for(int x=margin;x<side-margin;++x)for(int c=0;c<3;++c){
                size_t i=(size_t(y)*side+x)*3+c;double d=double(session.output[i])-baseline[i];
                delta=std::max(delta,std::abs(d));square+=d*d;++count;
            }
            line<<" repeat_max="<<delta<<" repeat_RMSE="<<std::sqrt(square/count);
        }
        vivo_nn::log(line.str());
        if(seed){
            std::ostringstream noise;noise<<std::setprecision(8)<<"HEX ISO NOISE: seed="<<seed<<" frame_mean_residual/variance=";
            for(size_t f=0;f<Frames;++f)noise<<' '<<fixture.mean[f]<<'/'<<fixture.variance[f];
            vivo_nn::log(noise.str());
        }
        require(before==after,"QNN changed diagnostic input buffer");
    };
    run("gray_baseline",{{.2f,.2f,.2f}},0,false);
    run("gray_repeat",{{.2f,.2f,.2f}},0,true);
    for(float level:{.05f,.10f,.15f,.25f,.35f,.50f,.75f})run("gray_sweep",{{level,level,level}},0,false);
    run("gray_after_history",{{.2f,.2f,.2f}},0,true);
    for(uint32_t seed:{1u,2u,3u})run("gray_profile_noise",{{.2f,.2f,.2f}},seed,false);
    for(uint32_t seed:{1u,2u}){
        run("blue_profile_noise",{{.12f,.35f,.65f}},seed,false);
        run("red_profile_noise",{{.65f,.28f,.10f}},seed,false);
    }
    vivo_nn::log("HEX ISO DIAG END: executions="+std::to_string(executions)+"; diagnostic only; capture gate unchanged");
}
} // namespace vivo_hexquad
