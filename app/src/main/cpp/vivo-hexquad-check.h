#pragma once
#include "vivo-hexquad-vst.h"
#include "vivo-neural-runtime.h"

namespace vivo_hexquad {
// Diagnostic only: six identical noiseless charts represent a static burst.
// They are not a substitute for real aligned frames during capture.
inline float chartValue(int chart, int c, float x, float y) {
    static const float flats[4][3] = {{.2f,.2f,.2f},{.55f,.55f,.55f},
                                     {.12f,.35f,.65f},{.65f,.28f,.10f}};
    if(chart<4)return flats[chart][c];
    if(chart==4)return .1f + .55f*(c==0?x/287.f:c==1?y/287.f:(x+y)/574.f);
    return .65f - .55f*(c==0?y/287.f:c==1?x/287.f:(x+y)/574.f);
}
struct HexScore {
    double rmse=0, maxError=0;
    std::array<double,3> mean{{0,0,0}};
    size_t badRange=0;
    bool pass() const { return !badRange && rmse<=.045; }
};
inline HexScore scoreChart(const std::vector<float>& network, const IvstLuts& inverse,
                          int scale, int chart, int red=0) {
    require(scale==1||scale==2,"Invalid chart scale");
    const int side=288*scale, margin=32*scale;
    const CfaOrientation orientation(side,side,red);
    require(network.size()==size_t(side)*side*3,"Invalid graph output size");
    auto rgb=decodeTile(network,inverse,{65535.f,0,0,1,0});
    HexScore result;size_t count=0;
    for(int y=margin;y<side-margin;++y)for(int x=margin;x<side-margin;++x) {
        float ix=(x+.5f)/scale-.5f,iy=(y+.5f)/scale-.5f;
        for(int c=0;c<3;++c) {
            // Compare in sensor coordinates, undoing exactly the input map.
            size_t at=(size_t(orientation.y(y))*side+orientation.x(x))*3+c;
            if(network[at]<-.05f||network[at]>1.25f)++result.badRange;
            double value=rgb[at];double delta=value-chartValue(chart,c,ix,iy);
            result.rmse+=delta*delta;result.maxError=std::max(result.maxError,std::abs(delta));
            result.mean[c]+=value;++count;
        }
    }
    result.rmse=std::sqrt(result.rmse/count);
    for(auto& mean:result.mean)mean/=count/3;
    return result;
}

template<class Network> bool checkHexCharts(Network& session,int scale,int red=0) {
    const CfaOrientation orientation(288,288,red);
    bool passed=true;double worst=0;
    for(int iso:{100,400}) {
        NormalVst transfer(iso);VstLuts forward;auto inverse=transfer.inverse();
        for(auto& lut:forward)lut=transfer.forward();
        std::vector<uint16_t> raw(288*288);
        std::array<TaggedFrame,Frames> frames;
        for(auto& frame:frames)frame={raw.data(),raw.size(),288,288,288};
        for(int chart=0;chart<6;++chart) {
            vivo_nn::log("HEX CHART: x"+std::to_string(scale)+" ISO="+std::to_string(iso)+
                         " chart="+std::to_string(chart)+" CFA=4x4 red="+std::to_string(red)+" frames=6 static");
            for(int y=0;y<288;++y)for(int x=0;x<288;++x) {
                int c=tagSource(0,x,y,red)>>14;
                auto value=uint16_t(std::lround(chartValue(chart,c,float(x),float(y))*16383.f));
                raw[orientation.y(y)*288+orientation.x(x)]=tagSource(value,x,y,red);
            }
            // Copy into the already-bound client buffer: never replace its
            // allocation after QNN descriptors have captured input.data().
            auto packed=packTile(frames,forward,0,0,288,288,{1.f/65535.f,1.f,0});
            require(session.input.size()==packed.size(),"HexQuad input allocation mismatch");
            std::copy(packed.begin(),packed.end(),session.input.begin());
            session.execute();
            auto score=scoreChart(session.output,inverse,scale,chart,red);
            std::ostringstream line;line<<std::setprecision(8)<<"HEX RESULT: RMSE="<<score.rmse
                <<" max="<<score.maxError<<" meanRGB="<<score.mean[0]<<','<<score.mean[1]<<','<<score.mean[2]
                <<" range_fail="<<score.badRange<<" pass="<<score.pass();
            vivo_nn::log(line.str());passed=passed&&score.pass();worst=std::max(worst,score.rmse);
        }
    }
    vivo_nn::log("HEX SUMMARY: x"+std::to_string(scale)+" sensor_red="+std::to_string(red)+" canonical=RGGB worst_RMSE="+std::to_string(worst)+
                 " chart_gate="+(passed?"PASS":"FAIL")+" diagnostic_only=1");
    return passed;
}
} // namespace vivo_hexquad
