#include "vivo-neural-runtime.h"
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
        vivo_nn::log("Vivo Neural native executable v4 (halo-aware validation); root="+std::to_string(geteuid()));
        if(argc==2 && std::string(argv[1])=="--transport-check") {
            vivo_nn::log("NATIVE EXEC OK");return 0;
        }
        if(argc!=2 && argc!=7)throw std::runtime_error("Worker argument count");
        const int width=argc==7?integer(argv[4]):0,height=argc==7?integer(argv[5]):0,redQuad=argc==7?integer(argv[6]):0;
        if(argc==7 && (width<8||height<8||width%8||height%8||static_cast<int64_t>(width)*height>16000000||redQuad>3))
            throw std::runtime_error("Unsupported Tetra frame");
        if(geteuid()!=0)throw std::runtime_error("Root worker required");
        // Hard kernel timeout covers blocked vendor code, not just Java waits.
        signal(SIGALRM,SIG_DFL);alarm(180);
        {
        vivo_nn::Session session;session.init(argv[1]);auto mapping=vivo_nn::calibrate(session);
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

