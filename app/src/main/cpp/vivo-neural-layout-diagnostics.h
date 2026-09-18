#pragma once
#include "vivo-neural-runtime.h"

// Diagnostic hypotheses only. None of these alternative layouts may enable
// capture: stock transport and its original four-chart gate remain authoritative.
namespace vivo_nn {
struct LayoutEvidence {
    struct Moment { double sum=0,square=0;size_t count=0; };
    std::array<Moment,256> phase{}; // 2x2 tensor cells, 64 channels each
    double freeColourError=0;
    bool finite=true;
    explicit LayoutEvidence(const std::vector<float>& output,const float* rgb) {
        if(output.size()!=size_t(OUTPUT_TILE)*OUTPUT_TILE)
            throw std::runtime_error("Diagnostic tensor size mismatch");
        size_t samples=0;
        for(int y=32;y<112;++y)for(int x=32;x<112;++x)for(int c=0;c<64;++c) {
            float v=output[(y*144+x)*64+c];
            if(!std::isfinite(v)){finite=false;continue;}
            // Do not clip or normalize an incorrect response into agreement.
            double linear=double(v)*v;
            auto& m=phase[((y&1)*2+(x&1))*64+c];
            m.sum+=linear;m.square+=linear*linear;++m.count;
            double nearest=std::numeric_limits<double>::infinity();
            for(int k=0;k<3;++k)nearest=std::min(nearest,(linear-rgb[k])*(linear-rgb[k]));
            freeColourError+=nearest;++samples;
        }
        freeColourError=finite&&samples?std::sqrt(freeColourError/samples):std::numeric_limits<double>::infinity();
    }
    // 0: measured stock Morton8; 1: raster space-to-depth8;
    // 2: four Bayer colour planes, each with raster-ordered 4x4 positions.
    // The latter two are hypotheses, NOT asserted stock formats.
    static int channel(int x,int y,int order) {
        if(order==0)return morton(x,y,3);
        if(order==1)return y*8+x;
        if(order==2)return ((y&1)*2+(x&1))*16+(y/2)*4+x/2;
        throw std::runtime_error("Invalid diagnostic channel order");
    }
    double rmse(const float* rgb,int order,int block,int redQuad) const {
        if(!finite)return std::numeric_limits<double>::infinity();
        double error=0;size_t count=0;
        for(int y=0;y<16;++y)for(int x=0;x<16;++x) {
            const auto& m=phase[((y/8)*2+x/8)*64+channel(x%8,y%8,order)];
            int tx=(redQuad&1)?15-x:x,ty=(redQuad&2)?15-y:y;
            double target=rgb[color(tx,ty,block)];
            error+=std::max(0.0,m.square-2*target*m.sum+m.count*target*target);count+=m.count;
        }
        return count?std::sqrt(error/count):std::numeric_limits<double>::infinity();
    }
};
inline int diagnosticInputChannel(int x,int y,bool raster) {
    return raster?(y%4)*4+x%4:morton(x%4,y%4,2);
}
template<class Network> void diagnoseLayouts(Network& s) {
    log("LAYOUT DIAGNOSTICS: probe only; candidates cannot enable capture");
    const float charts[4][3]={{.2f,.2f,.2f},{.55f,.55f,.55f},{.12f,.35f,.65f},{.65f,.28f,.10f}};
    struct Hypothesis {int block;bool raster;};
    // Raster4 and Morton4 are identical on a flat same-colour 4x4 block,
    // so that duplicate is omitted. Bayer1 is only a model-identification test;
    // it is not a change to the phone's Tetra4 sensor layout.
    const Hypothesis hypotheses[]={{4,false},{2,false},{1,false},{2,true},{1,true}};
    const char* orders[]={"stock_morton8","raster8_hypothesis","colour_planes4_hypothesis"};
    double globalBest=std::numeric_limits<double>::infinity();std::string bestDescription;
    for(const auto& h:hypotheses) {
        struct Score {int order,block,red;double worst=0;};
        std::vector<Score> scores;
        for(int order=0;order<3;++order)for(int block:{1,2,4,8})for(int red=0;red<4;++red)scores.push_back({order,block,red});
        std::string name=std::string(h.raster?"raster4":"stock_morton4")+" input_block="+std::to_string(h.block);
        bool valid=true;
        // Neutral inputs are identical for every packing and already ran in calibrate().
        // This fallback compares the two colour charts only.
        for(int chart=2;chart<4;++chart) {
            const float* rgb=charts[chart];
            log("LAYOUT INPUT: "+name+" chart="+std::to_string(chart+1));
            for(int y=0;y<INPUT_TILE;++y)for(int x=0;x<INPUT_TILE;++x)
                s.input[((y/4)*144+x/4)*16+diagnosticInputChannel(x,y,h.raster)]=std::sqrt(rgb[color(x,y,h.block)]);
            try{s.execute();}
            catch(const OutputError& e){
                // Inspect a rejected tensor for evidence only; never use it as
                // a calibrated mapping or as an output frame. Driver errors
                // are not caught and still abort the job immediately.
                log(std::string("LAYOUT RANGE REJECTED: ")+e.what());
            }
            LayoutEvidence evidence(s.output,rgb);
            if(!evidence.finite){log("LAYOUT NONFINITE: hypothesis rejected");valid=false;break;}
            log("LAYOUT FREE-COLOUR RMSE: "+std::to_string(evidence.freeColourError));
            for(auto& score:scores)score.worst=std::max(score.worst,evidence.rmse(rgb,score.order,score.block,score.red));
            if(chart>=2) {
                // All channels, with both tensor-cell parities: enough to see
                // period-8 versus period-16 colour structure without a RAW dump.
                for(int parity=0;parity<4;++parity) {
                    std::ostringstream row;row<<std::setprecision(5)<<"LAYOUT MEAN LINEAR parity="<<parity<<": ";
                    for(int c=0;c<64;++c){auto& m=evidence.phase[parity*64+c];row<<m.sum/m.count<<' ';}
                    log(row.str());
                }
            }
        }
        if(!valid)continue;
        std::stable_sort(scores.begin(),scores.end(),[](const Score& a,const Score& b){return a.worst<b.worst;});
        for(int i=0;i<3;++i){const auto& score=scores[i];
            std::string description=name+" output="+orders[score.order]+" output_block="+std::to_string(score.block)+" red_quad="+std::to_string(score.red);
            log("LAYOUT RANK "+std::to_string(i+1)+": "+description+" worst_colour_unclamped_RMSE="+std::to_string(score.worst));
            if(score.worst<globalBest){globalBest=score.worst;bestDescription=description;}
        }
    }
    log("LAYOUT BEST (diagnostic only): "+bestDescription+" RMSE="+std::to_string(globalBest));
    log("LAYOUT RESULT: no capture mapping installed; flat fields cannot establish spatial reconstruction");
}
} // namespace vivo_nn
