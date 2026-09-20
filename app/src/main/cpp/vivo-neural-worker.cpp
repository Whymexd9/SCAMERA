#include "vivo-neural-layout-diagnostics.h"
#include "vivo-hexquad-runtime.h"
#include "vivo-hexquad-check.h"
#include "vivo-hexquad-capture.h"
#include "vivo-hexquad-profile-check.h"
#define NICE_HOST_TEST 1
#include "vivo-nice-probe.cpp"
#undef NICE_HOST_TEST
#include "vivo-nice-capture.h"
#include "vivo-nice-tone-probe.h"
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
        vivo_nn::log("Vivo Neural native executable v21 (HP9 hybrid CPU prefetch + GPU post + NPU inference); root="+std::to_string(geteuid()));
        if(argc==2 && std::string(argv[1])=="--transport-check") {
            vivo_nn::log("NATIVE EXEC OK");return 0;
        }
        if(argc==5 && std::string(argv[1])=="--nice-capture") {
            if(geteuid()!=0)throw std::runtime_error("Root worker required");
            signal(SIGALRM,SIG_DFL);alarm(840);
            vivo_nice::MappedNiceBurst mapped(argv[3]);
            auto report=[](const std::string& line){vivo_nn::log(line);};
            report("NICE CAPTURE: original forward weights; Camera2 alignment/calibration adaptation");
            vivo_nice::Graph graph(argv[2],report);
            auto result=vivo_nice::reconstruct(mapped.burst,[&](const std::vector<float>& in,std::vector<float>& out){
                graph.input=in;graph.execute();out=graph.output;
            },report,[&](const std::string& name,const std::vector<float>& data,int w,int h){
                if(!mapped.burst.diagnostics)return;
                std::ofstream f(std::string(argv[2])+"/"+name+".pfm",std::ios::binary);
                if(!f){report("NICE DIAGNOSTIC: cannot open tile dump");return;}
                f<<"PF\n"<<w<<" "<<h<<"\n-1.0\n";
                for(int y=h-1;y>=0;--y)f.write(reinterpret_cast<const char*>(data.data()+size_t(y)*w*3),w*3*sizeof(float));
                if(!f)report("NICE DIAGNOSTIC: incomplete tile dump");
            });
            double sum=0;float maximum=0;
            for(float value:result){sum+=value;maximum=std::max(maximum,value);}
            report("NICE RGB: mean="+std::to_string(sum/result.size())+" max="+std::to_string(maximum));
            std::ofstream file(argv[4],std::ios::binary|std::ios::trunc);
            if(!file)throw std::runtime_error("Cannot open NICE output");
            file.write(reinterpret_cast<const char*>(result.data()),std::streamsize(result.size()*sizeof(float)));
            file.close();if(!file)throw std::runtime_error("Incomplete NICE output");
            alarm(0);report("NICE CAPTURE OK");return 0;
        }
        if(argc==3 && std::string(argv[1])=="--nice-tone-check") {
            if(getuid()!=0)throw std::runtime_error("NICE tone check requires root");
            alarm(360);
            vivo_nice::probeTone(argv[2],[](const std::string& line){std::cout<<line<<std::endl;});
            return 0;
        }
        if(argc==3 && std::string(argv[1])=="--nice-check") {
            if(geteuid()!=0)throw std::runtime_error("Root worker required");
            signal(SIGALRM,SIG_DFL);alarm(150);
            vivo_nice::probe(argv[2],[](const std::string& line){vivo_nn::log(line);});
            alarm(0);vivo_nn::log("NICE RUNTIME CHECK COMPLETE");return 0;
        }
        if(argc==5 && (std::string(argv[1])=="--hexquad-capture" || std::string(argv[1])=="--hexquad-capture-cached")) {
            if(geteuid()!=0)throw std::runtime_error("Root worker required");
            signal(SIGALRM,SIG_DFL);alarm(840);
            {
                vivo_hexquad::MappedBurst mapped(argv[3]);
                auto& burst=mapped.burst;
                vivo_nn::log("HP9 HEXQUAD CAPTURE v2: model=x"+std::to_string(burst.scale)+" actual_frames=6; Tetra4x4; ISO="+std::to_string(burst.iso)+" CFA="+std::to_string(burst.red));
                const double initStart=vivo_hexquad::hexClockMs();
                vivo_hexquad::HexSession session(burst.scale);session.init(argv[2]);
                vivo_nn::log("HEX TIMING ms: model_runtime_init="+std::to_string(vivo_hexquad::hexClockMs()-initStart));
                const double gateStart=vivo_hexquad::hexClockMs();
                vivo_hexquad::requireHexCaptureCharts(session,burst.iso,burst.red,std::string(argv[1])=="--hexquad-capture-cached",burst.scale,burst.noise);
                vivo_nn::log("HEX TIMING ms: profile_gate="+std::to_string(vivo_hexquad::hexClockMs()-gateStart));
                vivo_hexquad::captureHex(session,burst,argv[4]);
            }
            alarm(0);vivo_nn::log("HEXQUAD CAPTURE OK");return 0;
        }
        if(argc==3 && std::string(argv[1])=="--hexquad-check") {
            if(geteuid()!=0)throw std::runtime_error("Root worker required");
            signal(SIGALRM,SIG_DFL);alarm(180);
            vivo_nn::log("HP9 HEXQUAD v5: bundled QNN 2.29.8; canonical RGGB; noiseless stress + profiled capture checks");
            bool x1Passed=false,x2Passed=true,profilePassed=false;
            for(int scale:{1,2}) {
                vivo_hexquad::HexSession session(scale);session.init(argv[2]);
                if(scale==1)x1Passed=vivo_hexquad::checkHexCharts(session,1);
                else {
                    for(int red=0;red<4;++red)
                        x2Passed=vivo_hexquad::checkHexCharts(session,2,red,red==3?800:0)&&x2Passed;
                    vivo_hexquad::diagnoseHexIso(session,800,3);
                    profilePassed=vivo_hexquad::checkHexProfileCharts(session,800,3);
                }
            }
            alarm(0);
            vivo_nn::log(std::string("HEXQUAD CHECK COMPLETE: x2_noiseless_stress=")+(x2Passed?"PASS":"FAIL")+
                         "; x1_noiseless_reference="+(x1Passed?"PASS":"FAIL")+
                         "; x2_profiled_ISO800_BGGR="+(profilePassed?"PASS":"FAIL")+
                         "; experimental capture uses profiled gate at actual ISO/CFA; real quality unverified");
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
