#include "vivo-neural-layout-diagnostics.h"
#include "vivo-hexquad-runtime.h"
#include "vivo-hexquad-check.h"
#include "vivo-hexquad-capture.h"
#include "vivo-hexquad-profile-check.h"
#include "vivo-quad-capture.h"
#define NICE_HOST_TEST 1
#include "vivo-nice-probe.cpp"
#undef NICE_HOST_TEST
#include "vivo-nice-capture.h"
#include "vivo-nice-hybrid.h"
#include "vivo-nice-tone-probe.h"
#include "vivo-nice-stock-motion.h"
#include <memory>
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
    // P31 (W1.0): worker stage times for the shot timeline (NICE WORKER TIMELINE), ms since the worker started
    const auto mainStarted=std::chrono::steady_clock::now();
    auto sinceStart=[&]{return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-mainStarted).count();};
    try {
        vivo_nn::log("Vivo Neural native executable v30 (RAW stream input; HP9 hybrid CPU/GPU/NPU); root="+std::to_string(geteuid()));
        if(argc==2 && std::string(argv[1])=="--transport-check") {
            vivo_nn::log("NATIVE EXEC OK");return 0;
        }
        if(argc==5 && std::string(argv[1])=="--nice-capture") {
            signal(SIGALRM,SIG_DFL);alarm(840);
            // Burst and result arrive as "fd:N" (memfd shared by the app) or as file paths.
            {
                std::ifstream marker(std::string(argv[2])+"/dump-forward");
                std::string target;
                if(marker && std::getline(marker,target) && !target.empty())setenv("SCAM_DUMP_FORWARD",target.c_str(),1);
            }
            auto report=[](const std::string& line){vivo_nn::log(line);};
            // vivo's CRE motion lives in /vendor on vivo only; elsewhere (OPPO etc.)
            // fall back to SCAMERA's own tile alignment instead of failing.
            std::unique_ptr<vivo_nice::StockMotion> motion;
            if(std::getenv("SCAM_NO_CRE"))report("NICE MOTION: SCAMERA tile alignment (SCAM_NO_CRE replay)"); else
            try {
                const bool forceBundled=access((std::string(argv[2])+"/cre-force-bundled").c_str(),F_OK)==0;
                const bool vendorOnly=access((std::string(argv[2])+"/cre-vendor-only").c_str(),F_OK)==0;
                motion=std::make_unique<vivo_nice::StockMotion>(vendorOnly?std::string():std::string(argv[2]),forceBundled);
                report("NICE MOTION: vivo CRE source="+motion->source);
            }
            catch(const std::exception& error) { report(std::string("NICE MOTION: SCAMERA tile alignment (")+error.what()+")"); }
            const double creMs=sinceStart();
            // P30: started before the app has written the burst (job file "wait-go" = the fd of a pipe): the CRE and the GPU
            // driver are initialised meanwhile, then the worker waits for the app's go byte. P31 (W1.7): the warm-up always runs, on
            // threads of its own from here (the app never asked for it on the shot-arena path): the CRE's first detect / track,
            // joined before the first alignment (the burst's results do not depend on it, see StockMotion::warmUp), and
            // eglInitialize (the driver load), joined at the end. SCAM_NO_WARMUP: without it (replay A/B).
            const auto warmStarted=std::chrono::steady_clock::now();
            std::thread creWarm,eglWarm;
            std::atomic<int> creReadyMs{-1};
            if(!std::getenv("SCAM_NO_WARMUP")){
                if(motion)creWarm=std::thread([&]{
                    try{motion->warmUp();}catch(const std::exception&){}
                    creReadyMs=int(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-warmStarted).count());
                });
                eglWarm=std::thread([]{
                    EGLDisplay display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
                    if(display!=EGL_NO_DISPLAY)eglInitialize(display,nullptr,nullptr); // loads the driver; the merge's own init is then a no-op
                });
            }
            struct WarmJoin { std::thread& a;std::thread& b; ~WarmJoin(){if(a.joinable())a.join();if(b.joinable())b.join();} } warmJoin{creWarm,eglWarm};
            auto joinCreWarm=[&]{
                if(!creWarm.joinable())return;
                creWarm.join();
                report("NICE WARMUP: CRE ready in "+std::to_string(creReadyMs.load())+" ms, joined after "
                    +std::to_string(int(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-warmStarted).count()))+" ms");
            };
            {
                int goFd=-1;
                {std::ifstream marker(std::string(argv[2])+"/wait-go");std::string text;if(marker&&std::getline(marker,text)&&!text.empty())goFd=std::atoi(text.c_str());}
                if(goFd>=0){
                    char go=0;ssize_t n;
                    do n=read(goFd,&go,1); while(n<0&&errno==EINTR);
                    close(goFd);
                    if(n!=1)throw std::runtime_error("NICE burst was not delivered by the app");
                    report("NICE WARMUP: burst after "+std::to_string(int(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-warmStarted).count()))+" ms");
                }
            }
            // NCH v10 = the LMC hybrid transport (per-frame roles and noise); anything else is the NICE 7-slot burst.
            vivo_nice::MappedHybridBurst hybridBurst(argv[3]);
            std::unique_ptr<vivo_nice::MappedNiceBurst> mapped;
            if(!hybridBurst.hybrid)mapped=std::make_unique<vivo_nice::MappedNiceBurst>(argv[3]);
            // Debug: keep the burst (touch <external files>/nice_keep) so the merge can be replayed
            // offline with `--nice-capture <job dir> <external files>/nice_burst.bin <out>`. An offline replay
            // (SCAM_HYBRID / SCAM_NO_KEEP in the environment) never overwrites the kept burst of the app.
            if(std::string(argv[3])!="/sdcard/Android/data/org.codeaurora.snapcam/files/nice_burst.bin"
                    &&!std::getenv("SCAM_HYBRID")&&!std::getenv("SCAM_NO_KEEP")
                    &&access("/sdcard/Android/data/org.codeaurora.snapcam/files/nice_keep",F_OK)==0){
                std::ofstream dst("/sdcard/Android/data/org.codeaurora.snapcam/files/nice_burst.bin",std::ios::binary|std::ios::trunc);
                if(mapped)dst.write(static_cast<const char*>(mapped->address),std::streamsize(mapped->length));
                else dst.write(static_cast<const char*>(hybridBurst.address),std::streamsize(hybridBurst.length));
            }
            const char* outputPath=argv[4];
            report("NICE CAPTURE: original forward weights and stock CPU motion; supplied per-frame calibration");
            report(hybridBurst.hybrid?"NICE INPUT: hybrid v10 burst frames="+std::to_string(hybridBurst.input.frames.size()):"NICE INPUT: mapped capture file");
            if(mapped){
            const auto& input=mapped->burst;
            const auto& scene=input.scene;
            report("NICE SCENE: timestamp="+std::to_string(scene.timestamp)
                +" lux="+(scene.hasLux()?std::to_string(scene.lux):"unavailable")
                +" ADRC="+(scene.hasAdrc()?std::to_string(scene.adrc):"unavailable")
                +" flags="+std::to_string(scene.flags)+" luxSource="+std::to_string(scene.luxSource));
            for(size_t i=0;i<input.ae.size();++i) {
                const auto& ae=input.ae[i];
                report("NICE AE: slot="+std::to_string(i)+" timestamp="+std::to_string(ae.timestamp)
                    +" flags="+std::to_string(ae.flags));
                if(ae.hasAec()) {
                    const auto f=ae.fields();
                    report("NICE AE VALUES: lux="+std::to_string(f.lux)+" exposureMs="+std::to_string(f.exposureMs)
                        +" shortGain="+std::to_string(f.shortGain)+" digitalGain="+std::to_string(f.digitalGain)
                        +" rawHdrDrc="+(ae.hasHdrDrc()?std::to_string(ae.drcGain(true)):"unavailable"));
                }
            }
            }
            vivo_nice::NiceAlignment alignment;
            if(motion){
                alignment=[&](vivo_nice::Burst& burst){joinCreWarm();return motion->align(burst,report);};
                // P31 (W1.2): the hybrid merge hands the CRE every frame before its groups: their guides are built ahead on the pool
                vivo_nice::niceAlignmentPrefetch()=[&](const vivo_nice::Burst* base,const std::vector<std::pair<const uint16_t*,float>>& donors){
                    if(base)motion->prefetch(*base,donors); else motion->dropPrefetched();
                };
            }
            const double mappedMs=sinceStart();
            std::vector<uint16_t> mergedDng;
            std::vector<uint8_t> effMap,clipFlags;
            std::vector<float> result;
            // LMC hybrid merge (GCam 6.1 Sabre kernel + LMC 9.6 front end, Bento, Shasta; any GPU, no model):
            // requested by the app with the job marker, or SCAM_HYBRID=1 for an offline replay.
            bool hybrid=hybridBurst.hybrid||access((std::string(argv[2])+"/hybrid-merge").c_str(),F_OK)==0||std::getenv("SCAM_HYBRID");
            std::unique_ptr<vivo_nice::Graph> graph;
            if(!hybrid) {
                // The forward network ships as Hexagon v79 context binaries (SM8750 only); without it the
                // burst is merged by the LMC hybrid (vivo-nice-hybrid.h).
                try{graph=std::make_unique<vivo_nice::Graph>(argv[2],report);}
                catch(const std::exception& error){report(std::string("NICE NPU: neural model unavailable (")+error.what()+"); LMC hybrid merge");hybrid=true;}
            }
            if(hybrid) {
                report("HYBRID MERGE: LMC hybrid requested (Sabre kernel, LMC rejection/weights, Bento, Shasta)");
                { // P30: GPU program binary cache (app cache dir from the job; SCAM_GL_CACHE in an offline replay)
                    std::ifstream cache(std::string(argv[2])+"/gl-cache");std::string dir;
                    if(cache&&std::getline(cache,dir)&&!dir.empty())vivo_nice::hybridProgramCacheDir()=dir;
                    else if(const char* env=std::getenv("SCAM_GL_CACHE"))vivo_nice::hybridProgramCacheDir()=env;
                }
                const auto tuning=vivo_nice::loadHybridTuning(argv[2],report);
                vivo_nice::HybridInput hin=hybridBurst.hybrid?hybridBurst.input:vivo_nice::hybridFromNiceBurst(mapped->burst);
                if(std::getenv("SCAM_MERGED_DNG"))hin.mergedDng=true;
                // Clip flags trailer: asked by the request header (flag 4), the job marker `clip-flags` or, in an offline replay
                // (SCAM_HYBRID) only, the tuning key clipFlags. The app checks the result size exactly: a fallback tuning file
                // (external files dir, /data/local/tmp) must not add a trailer it did not ask for.
                const bool wantClipFlags=hin.clipFlags||(tuning.clipFlags&&std::getenv("SCAM_HYBRID"))
                        ||access((std::string(argv[2])+"/clip-flags").c_str(),F_OK)==0;
                result=vivo_nice::hybridReconstruct(hin,tuning,alignment,report,&mergedDng,&effMap,nullptr,wantClipFlags?&clipFlags:nullptr);
            } else {
            const auto& input=mapped->burst;
            vivo_nice::NiceExecute forward;
            if(graph)forward=[&](const std::vector<float>& in,std::vector<float>& out){
                graph->input=in;graph->execute();out=graph->output;
            };
            result=vivo_nice::reconstruct(input,forward,report,[&](const std::string& name,const std::vector<float>& data,int w,int h){
                if(!input.diagnostics)return;
                std::ofstream f(std::string(argv[2])+"/"+name+".pfm",std::ios::binary);
                if(!f){report("NICE DIAGNOSTIC: cannot open tile dump");return;}
                f<<"PF\n"<<w<<" "<<h<<"\n-1.0\n";
                for(int y=h-1;y>=0;--y)f.write(reinterpret_cast<const char*>(data.data()+size_t(y)*w*3),w*3*sizeof(float));
                if(!f)report("NICE DIAGNOSTIC: incomplete tile dump");
            },alignment,&mergedDng,&effMap);
            }
            const double mergedMs=sinceStart();
            // The CRE's first call prints an unterminated line ("Failed to load symbol ..."): with the warm-up joined here at the
            // latest it lands before the result lines, never in front of "NICE CAPTURE OK" (the app matches that line exactly).
            joinCreWarm();
            // P30: the log line's mean and max on all cores (the sum was ~60 ms on one core at 12 MP, 4x that on the 2x grid).
            double sum=0;float maximum=0;
            {
                std::mutex lock;
                vivo_nice::mergeRowBands(int((result.size()+65535)/65536),[&](int b0,int b1){
                    double s=0;float m=0;
                    for(size_t i=size_t(b0)*65536;i<std::min(result.size(),size_t(b1)*65536);++i){s+=result[i];m=std::max(m,result[i]);}
                    std::lock_guard<std::mutex> guard(lock);sum+=s;maximum=std::max(maximum,m);
                });
            }
            report("NICE RGB: mean="+std::to_string(sum/result.size())+" max="+std::to_string(maximum));
            const int out=vivo_nice::openArgument(outputPath,O_WRONLY|O_CREAT|O_TRUNC);
            if(out<0)throw std::runtime_error("Cannot open NICE output");
            auto writeAll=[&](const void* data,size_t bytes){
                const char* p=static_cast<const char*>(data);
                while(bytes){const ssize_t n=write(out,p,bytes);if(n<0&&errno==EINTR)continue;
                    if(n<=0){close(out);throw std::runtime_error("Incomplete NICE output");}p+=n;bytes-=size_t(n);}
            };
            writeAll(result.data(),result.size()*sizeof(float));
            // Optional trailer: merged Bayer RAW (uint16, sensor layout) for the DNG.
            if(!mergedDng.empty())writeAll(mergedDng.data(),mergedDng.size()*sizeof(uint16_t));
            // Optional second trailer: effective merged frames per pixel (uint8, 1/8 frame), w*h bytes.
            if(!effMap.empty()&&effMap.size()*3==result.size())writeAll(effMap.data(),effMap.size());
            // Optional third trailer (only on request, after the effective map): clip flags, uint8 per output pixel (bits: 0/1/2 R/G/B
            // from the clipped mean, 3 clip border, 4 Bento mask, 5 ultrashort clipped mean).
            if(!clipFlags.empty()&&clipFlags.size()==effMap.size())writeAll(clipFlags.data(),clipFlags.size());
            if(close(out))throw std::runtime_error("Incomplete NICE output");
            {
                char line[200];
                std::snprintf(line,sizeof(line),"NICE WORKER TIMELINE ms: cre=%.0f mapped=%.0f merged=%.0f written=%.0f",creMs,mappedMs,mergedMs,sinceStart());
                report(line);
            }
            alarm(0);report("NICE CAPTURE OK");return 0;
        }
        if(argc==3 && std::string(argv[1])=="--nice-tone-check") {
            alarm(360);
            vivo_nice::probeTone(argv[2],[](const std::string& line){std::cout<<line<<std::endl;});
            return 0;
        }
        if(argc==5 && std::string(argv[1])=="--nice-forward") {
            // Reference runner for weight extraction: N input tiles (float32 NHWC 1x544x544x22)
            // in, N output tiles (1x544x544x3) out. Same graph and runtime as the capture path.
            alarm(600);
            const size_t inTile=size_t(544)*544*22,outTile=size_t(544)*544*3;
            std::ifstream inFile(argv[3],std::ios::binary|std::ios::ate);
            if(!inFile)throw std::runtime_error("Cannot open forward input");
            const size_t bytes=size_t(inFile.tellg());
            if(!bytes||bytes%(inTile*4))throw std::runtime_error("Forward input is not a whole number of tiles");
            const size_t tiles=bytes/(inTile*4);inFile.seekg(0);
            vivo_nice::Graph graph(argv[2],[](const std::string& line){vivo_nn::log(line);});
            std::ofstream outFile(argv[4],std::ios::binary|std::ios::trunc);
            if(!outFile)throw std::runtime_error("Cannot open forward output");
            for(size_t t=0;t<tiles;++t) {
                inFile.read(reinterpret_cast<char*>(graph.input.data()),std::streamsize(inTile*4));
                if(!inFile)throw std::runtime_error("Short forward input");
                graph.execute();
                outFile.write(reinterpret_cast<const char*>(graph.output.data()),std::streamsize(outTile*4));
            }
            outFile.flush();
            vivo_nn::log("NICE FORWARD OK tiles="+std::to_string(tiles));
            alarm(0);return 0;
        }
        if(argc==3 && std::string(argv[1])=="--nice-check") {
            signal(SIGALRM,SIG_DFL);alarm(150);
            vivo_nice::probe(argv[2],[](const std::string& line){vivo_nn::log(line);});
            alarm(0);vivo_nn::log("NICE RUNTIME CHECK COMPLETE");return 0;
        }
        if(argc==5 && (std::string(argv[1])=="--hexquad-capture" || std::string(argv[1])=="--hexquad-capture-cached")) {
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
        if(argc==5 && std::string(argv[1])=="--quad-capture") {
            signal(SIGALRM,SIG_DFL);alarm(600);
            {
                // Debug: keep the transport file when /data/local/tmp/quad_keep exists.
                if(access("/data/local/tmp/quad_keep",F_OK)==0){
                    std::ifstream src(argv[3],std::ios::binary);std::ofstream dst("/data/local/tmp/quad_burst.bin",std::ios::binary|std::ios::trunc);
                    dst<<src.rdbuf();vivo_nn::log("QUAD DEBUG: burst copied to /data/local/tmp/quad_burst.bin");
                }
                vivo_quad::MappedQuadBurst mapped(argv[3]);
                auto& burst=mapped.burst;
                vivo_nn::log(std::string("QUAD CAPTURE: model=")+(burst.model==1?"nice_ldr_hp9_general_roi_quad_x1 (tele)":"nice_ldr_imx06c_general_quad_x1 (main)")+" frames=4; Quad2x2; "+
                    std::to_string(burst.w)+"x"+std::to_string(burst.h)+" ISO="+std::to_string(burst.iso)+" CFA="+std::to_string(burst.red));
                const double initStart=vivo_hexquad::hexClockMs();
                const int net=burst.model==1&&burst.iso>2000?2:burst.model;burst.network=net;
                vivo_nn::log("QUAD NETWORK: "+std::string(net==2?"roi_quad_x1_highdrc (ISO > 2000)":net==1?"roi_quad_x1":"imx06c quad_x1"));
                vivo_hexquad::HexSession session(vivo_hexquad::quadSpec(net));session.init(argv[2]);
                vivo_nn::log("QUAD TIMING ms: model_runtime_init="+std::to_string(vivo_hexquad::hexClockMs()-initStart));
                vivo_quad::captureQuad(session,burst,argv[4]);
                if(access("/data/local/tmp/quad_keep",F_OK)==0){
                    std::ifstream src(argv[4],std::ios::binary);std::ofstream dst("/data/local/tmp/quad_out.bin",std::ios::binary|std::ios::trunc);dst<<src.rdbuf();
                }
            }
            alarm(0);vivo_nn::log("HEXQUAD CAPTURE OK");return 0;
        }
        if(argc==3 && std::string(argv[1])=="--hexquad-check") {
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
