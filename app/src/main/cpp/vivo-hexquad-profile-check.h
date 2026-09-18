#pragma once
#include "vivo-hexquad-iso-diagnostics.h"

namespace vivo_hexquad {
// Explicit experimental-capture policy, not a fallback after a clean-chart
// failure. v10 device reports show deterministic false colour on identical
// noise-free bursts, but accurate colour with independent profiled RAW noise.
// Keep clean-chart results visible in standalone diagnostics. Neither suite
// establishes real-scene quality or full sensor calibration.
template<class Network> bool checkHexProfileCharts(Network& session,int iso,int red,bool cached=false,int scale=2,NoiseScale profile={}) {
    require(scale==1||scale==2,"Invalid profile model scale");profile.validate();
    bool passed=true;double worst=0;int executions=0;
    std::vector<int> sensitivities{100,400};
    if(iso!=100 && iso!=400)sensitivities.push_back(iso);
    if(cached)sensitivities={iso};
    vivo_nn::log("HEX PROFILE GATE BEGIN: independent six-frame synthetic noise; RMSE<=0.045; range=[-0.05,1.25]; actual_ISO="+
                 std::to_string(iso)+" red="+std::to_string(red)+(cached?"; cached full suite + four fresh smoke charts":"; full suite")+"; model=x"+std::to_string(scale)+
                 " variance_factors="+std::to_string(profile.overall)+","+std::to_string(profile.photon)+","+std::to_string(profile.readout)+
                 "; synthetic sensor noise uses original HP9 profile; VST uses selected multipliers");
    for(int sensitivity:sensitivities) {
        auto inverse=NormalVst(sensitivity,profile).inverse();
        // Original flats and gradients plus the .15/.25/.50 gray levels that
        // failed v10 noiseless diagnostics. Every case must pass both seeds.
        const std::vector<int> charts=cached?std::vector<int>{0,2}:std::vector<int>{0,1,2,3,4,5,6,7,8};
        for(int chart:charts)for(uint32_t seed:{1u,2u}) {
            auto fixture=makeIsoInput(sensitivity,red,{{0,0,0}},seed,chart,profile);
            require(session.input.size()==fixture.packed.size(),"Profile input shape mismatch");
            std::copy(fixture.packed.begin(),fixture.packed.end(),session.input.begin());
            const uint64_t before=diagnosticHash(session.input);
            session.execute();++executions;
            require(before==diagnosticHash(session.input),"QNN changed profile input buffer");
            auto score=scoreChart(session.output,inverse,scale,chart,red);
            passed=score.pass()&&passed;worst=std::max(worst,score.rmse);
            std::ostringstream line;line<<std::setprecision(8)<<"HEX PROFILE RESULT: ISO="<<sensitivity
                <<" chart="<<chart<<" seed="<<seed<<" RMSE="<<score.rmse
                <<" max="<<score.maxError<<" meanRGB="<<score.mean[0]<<','<<score.mean[1]<<','<<score.mean[2]
                <<" range_fail="<<score.badRange<<" pass="<<score.pass();
            vivo_nn::log(line.str());
        }
    }
    vivo_nn::log("HEX PROFILE SUMMARY: executions="+std::to_string(executions)+
        " worst_RMSE="+std::to_string(worst)+" capture_gate="+(passed?"PASS":"FAIL")+
        "; experimental only; real scene quality unverified");
    return passed;
}
template<class Network> void requireHexCaptureCharts(Network& session,int iso,int red,bool cached=false,int scale=2,NoiseScale profile={}) {
    if(checkHexProfileCharts(session,iso,red,cached,scale,profile))return;
    diagnoseHexIso(session,iso,red,scale,profile);
    throw std::runtime_error("HexQuad x"+std::to_string(scale)+" selected noise profile failed colour/packing gate; no photograph produced; restore multipliers to 1 or select x2; ISO diagnostics recorded");
}
} // namespace vivo_hexquad
