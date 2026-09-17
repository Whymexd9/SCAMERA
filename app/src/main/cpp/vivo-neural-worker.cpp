#include "vivo-neural-layout-diagnostics.h"
#include "vivo-hexquad-runtime.h"
#include "vivo-hexquad-check.h"
#include "vivo-hexquad-capture.h"
#include <cerrno>
#include <cstdlib>
#include <unistd.h>
#include <signal.h>

static int integer(const char* text) {
    char* end=nullptr;errno=0;long v=std::strtol(text,&end,10);
    if(errno || !text[0] || *end || v<0 || v>16000000)throw std::runtime_error("Invalid integer argument");
    return static_cast<int>(v);
}
int main(int argc,char** argv) {
    try {
        vivo_nn::log("Vivo Neural native executable v8 (HP9 HexQuad canonical CFA); root="+std::to_string(geteuid()));
        if(argc==2 && std::string(argv[1])=="--transport-check") {
            vivo_nn::log("NATIVE EXEC OK");return 0;
        }
        if(argc==5 && std::string(argv[1])=="--hexquad-capture") {
            if(geteuid()!=0)throw std::runtime_error("Root worker required");
            signal(SIGALRM,SIG_DFL);alarm(840);
            {
                vivo_hexquad::MappedBurst mapped(argv[3]);
                auto& burst=mapped.burst;
                vivo_nn::log("HP9 HEXQUAD CAPTURE v1: actual_frames=6; Tetra4x4; ISO="+std::to_string(burst.iso)+" CFA="+std::to_string(burst.red));
                vivo_hexquad::HexSession session(2);session.init(argv[2]);
                if(!vivo_hexquad::checkHexCharts(session,2,burst.red))
                    throw std::runtime_error("HexQuad x2 colour/packing gate failed; no photograph produced");
                vivo_hexquad::captureHex(session,burst,argv[4]);
            }
            alarm(0);vivo_nn::log("HEXQUAD CAPTURE OK");return 0;
        }
        if(argc==3 && std::string(argv[1])=="--hexquad-check") {
            if(geteuid()!=0)throw std::runtime_error("Root worker required");
            signal(SIGALRM,SIG_DFL);alarm(180);
            vivo_nn::log("HP9 HEXQUAD v3: bundled QNN 2.29.8; canonical RGGB; stock normal VST; diagnostics only");
            bool x1Passed=false,x2Passed=true;
            for(int scale:{1,2}) {
                vivo_hexquad::HexSession session(scale);session.init(argv[2]);
                if(scale==1)x1Passed=vivo_hexquad::checkHexCharts(session,1);
                else for(int red=0;red<4;++red)
                    x2Passed=vivo_hexquad::checkHexCharts(session,2,red)&&x2Passed;
            }
            alarm(0);
            vivo_nn::log(std::string("HEXQUAD CHECK COMPLETE: x2_all_CFA_gate=")+(x2Passed?"PASS":"FAIL")+
                         "; x1_reference_gate="+(x1Passed?"PASS":"FAIL")+
                         "; diagnostic only; experimental capture is a separate setting");
            return 0;
        }
        if(argc!=2 && argc!=7)throw std::runtime_error("Worker argument count");
        const int width=argc==7?integer(argv[4]):0,height=argc==7?integer(argv[5]):0,redQuad=argc==7?integer(argv[6]):0;
        if(argc==7 && (width<8||height<8||width%8||height%8||static_cast<int64_t>(width)*height>16000000||redQuad>3))
            throw std::runtime_error("Unsupported Tetra frame");
        if(geteuid()!=0)throw std::runtime_error("Root worker required");
        // Hard kernel timeout covers blocked vendor code, not just Java waits.
        signal(SIGALRM,SIG_DFL);alarm(180);
        {
        vivo_nn::Session session;session.init(argv[1]);vivo_nn::Mapping mapping;
        try {mapping=vivo_nn::calibrate(session);}
        catch(const vivo_nn::MappingError&) {
            if(argc==2)vivo_nn::diagnoseLayouts(session);
            throw;
        }
        if(argc==7){
            const std::string ip(argv[2]),op(argv[3]);
            auto bytes=vivo_nn::read(ip);
            if(width<8||height<8||static_cast<int64_t>(width)*height>16000000||bytes.size()!=static_cast<uint64_t>(width)*height*4)
                throw std::runtime_error("Invalid prepared RAW length");
            for(size_t i=0;i<bytes.size();i+=4){float v;std::memcpy(&v,bytes.data()+i,4);if(!std::isfinite(v)||v<0||v>16)throw std::runtime_error("Invalid prepared RAW sample");}
            auto result=vivo_nn::reconstruct(session,mapping,reinterpret_cast<const float*>(bytes.data()),width,height,redQuad);
            std::ofstream f(op,std::ios::binary|std::ios::trunc);if(!f)throw std::runtime_error("Cannot open result");
            f.write(reinterpret_cast<const char*>(result.data()),result.size()*sizeof(float));f.close();
            if(!f)throw std::runtime_error("Result write failed");
            vivo_nn::log("NEURAL FRAME COMPLETE");
        }
        } // Free vendor handles while the hard timeout is still armed.
        alarm(0);vivo_nn::log("NEURAL JOB OK");return 0;
    } catch(const std::exception& e){
        vivo_nn::log(std::string("STOP: ")+e.what());
        return 1;
    }
}
