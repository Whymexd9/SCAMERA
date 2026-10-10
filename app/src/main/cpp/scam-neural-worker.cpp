#include "scam-neural-layout-diagnostics.h"
#include "scam-hexquad-runtime.h"
#include "scam-hexquad-check.h"
#include "scam-hexquad-capture.h"
#include "scam-hexquad-profile-check.h"
#include "scam-quad-capture.h"
#define SCAM_HOST_TEST 1
#include "scam-probe.cpp"
#undef SCAM_HOST_TEST
#include "scam-capture.h"
#include "scam-hybrid.h"
#include "scam-tone-probe.h"
#include "scam-stock-motion.h"
#include "scam-crash.h"
#include <memory>
#include <cerrno>
#include <cstdlib>
#include <unistd.h>
#include <signal.h>
#include <sys/resource.h>

static int integer(const char* text) {
    char* end=nullptr;errno=0;long v=std::strtol(text,&end,10);
    if(errno || !text[0] || *end || v<0 || v>16000000)throw std::runtime_error("Invalid integer argument");
    return static_cast<int>(v);
}
int main(int argc,char** argv) {
    // P31 (W1.0): worker stage times for the shot timeline (SCAM WORKER TIMELINE), ms since the worker started
    const auto mainStarted=std::chrono::steady_clock::now();
    auto sinceStart=[&]{return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-mainStarted).count();};
    // A fatal signal writes "WORKER CRASH: ..." lines (signal, thread, stage, pc / lr with library offsets) before the worker dies
    // (vivo X200 Pro, owner's log 2026-10-07: 14 SIGSEGV exits, none naming the crash site).
    worker_crash::install();
    try {
        scam_nn::log("SCAM Neural native executable v30 (RAW stream input; HP9 hybrid CPU/GPU/NPU); root="+std::to_string(geteuid()));
        if(argc==2 && std::string(argv[1])=="--transport-check") {
            scam_nn::log("NATIVE EXEC OK");return 0;
        }
        if(argc==2 && std::string(argv[1])=="--crash-check") { // the crash report on this device: a null write on a helper thread
            std::thread([]{worker_crash::Stage stage("crash check");volatile int* p=nullptr;*p=1;}).join();
            return 0;
        }
        if(argc==4 && std::string(argv[1])=="--gpu-prewarm") { // P35: <job dir> <colour block>: the merge route's GPU programs into the cache
            // Runs while the camera session starts (and possibly while a shot merges): the compile threads of the driver inherit
            // this thread's nice value, so the whole prewarm yields the CPU to the camera, the viewfinder and the shot's worker.
            if(setpriority(PRIO_PROCESS,0,10)!=0){} // best effort
            // A driver that hangs must not hold the app's prewarm thread: it reads the output until the worker exits.
            signal(SIGALRM,SIG_DFL);alarm(60);
            auto report=[](const std::string& line){worker_crash::note(line.data(),line.size());scam_nn::log(line);};
            std::ifstream cache(std::string(argv[2])+"/gl-cache");std::string dir;
            if(cache&&std::getline(cache,dir)&&!dir.empty())scam::hybridProgramCacheDir()=dir;
            else if(const char* env=std::getenv("SCAM_GL_CACHE"))scam::hybridProgramCacheDir()=env;
            if(scam::hybridProgramCacheDir().empty())throw std::runtime_error("HYBRID PREWARM: no program cache");
            worker_crash::mark("GPU prewarm");
            scam::hybridPrewarmGpu(scam::loadHybridTuning(argv[2],report),integer(argv[3]),report);
            scam_nn::log("SCAM PREWARM OK");return 0;
        }
        if(argc==5 && std::string(argv[1])=="--scam-capture") {
            signal(SIGALRM,SIG_DFL);alarm(840);
            // Burst and result arrive as "fd:N" (memfd shared by the app) or as file paths.
            {
                std::ifstream marker(std::string(argv[2])+"/dump-forward");
                std::string target;
                if(marker && std::getline(marker,target) && !target.empty())setenv("SCAM_DUMP_FORWARD",target.c_str(),1);
            }
            auto report=[](const std::string& line){worker_crash::note(line.data(),line.size());scam_nn::log(line);};
            // vivo's CRE motion lives in /vendor on vivo only; elsewhere (OPPO etc.)
            // fall back to SCAMERA's own tile alignment instead of failing.
            std::unique_ptr<scam::StockMotion> motion;
            // Job marker cre-off: the app's retry after a worker that died inside the CRE (its stage in the crash report).
            if(std::getenv("SCAM_NO_CRE"))report("SCAM MOTION: SCAMERA tile alignment (SCAM_NO_CRE replay)"); else
            if(access((std::string(argv[2])+"/cre-off").c_str(),F_OK)==0)report("SCAM MOTION: SCAMERA tile alignment (CRE off for this job)"); else
            try {
                worker_crash::Stage stage("CRE load (dlopen)");
                const bool forceBundled=access((std::string(argv[2])+"/cre-force-bundled").c_str(),F_OK)==0;
                const bool vendorOnly=access((std::string(argv[2])+"/cre-vendor-only").c_str(),F_OK)==0;
                motion=std::make_unique<scam::StockMotion>(vendorOnly?std::string():std::string(argv[2]),forceBundled);
                report("SCAM MOTION: vivo CRE source="+motion->source);
            }
            catch(const std::exception& error) { report(std::string("SCAM MOTION: SCAMERA tile alignment (")+error.what()+")"); }
            const double creMs=sinceStart();
            // P30: started before the app has written the burst (job file "wait-go" = the fd of a pipe): the CRE and the GPU
            // driver are initialised meanwhile, then the worker waits for the app's go byte. P31 (W1.7): the warm-up always runs, on
            // threads of its own from here (the app never asked for it on the shot-arena path): the CRE's first detect / track,
            // joined before the first alignment (the burst's results do not depend on it, see StockMotion::warmUp), and
            // eglInitialize (the driver load), joined at the end. SCAM_NO_WARMUP: without it (replay A/B).
            const auto warmStarted=std::chrono::steady_clock::now();
            std::thread creWarm,eglWarm;
            std::atomic<int> creReadyMs{-1};
            // Joins whatever was started, on every way out (before the threads are started: a second thread the system refuses
            // must not leave the first one joinable, std::terminate).
            struct WarmJoin { std::thread& a;std::thread& b; ~WarmJoin(){if(a.joinable())a.join();if(b.joinable())b.join();} } warmJoin{creWarm,eglWarm};
            if(!std::getenv("SCAM_NO_WARMUP")){
                // A thread the system refuses costs only its warm-up (the first alignment and the merge initialise as before), never
                // the shot.
                if(motion)try{creWarm=std::thread([&]{
                    worker_crash::Stage stage("CRE warm-up");
                    try{motion->warmUp();}catch(const std::exception&){}
                    creReadyMs=int(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-warmStarted).count());
                });}catch(const std::exception&){}
                try{eglWarm=std::thread([]{
                    worker_crash::Stage stage("EGL warm-up (driver load)");
                    EGLDisplay display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
                    if(display!=EGL_NO_DISPLAY)eglInitialize(display,nullptr,nullptr); // loads the driver; the merge's own init is then a no-op
                });}catch(const std::exception&){}
            }
            auto joinCreWarm=[&]{
                if(!creWarm.joinable())return;
                creWarm.join();
                report("SCAM WARMUP: CRE ready in "+std::to_string(creReadyMs.load())+" ms, joined after "
                    +std::to_string(int(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-warmStarted).count()))+" ms");
            };
            {
                int goFd=-1;
                {std::ifstream marker(std::string(argv[2])+"/wait-go");std::string text;if(marker&&std::getline(marker,text)&&!text.empty())goFd=std::atoi(text.c_str());}
                if(goFd>=0){
                    char go=0;ssize_t n;
                    do n=read(goFd,&go,1); while(n<0&&errno==EINTR);
                    close(goFd);
                    if(n!=1)throw std::runtime_error("SCAM burst was not delivered by the app");
                    report("SCAM WARMUP: burst after "+std::to_string(int(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-warmStarted).count()))+" ms");
                }
            }
            // NCH v10 = the SCAM Hybrid transport (per-frame roles and noise); anything else is the SCAM 7-slot burst.
            worker_crash::mark("burst map");
            scam::MappedHybridBurst hybridBurst(argv[3]);
            std::unique_ptr<scam::MappedScamBurst> mapped;
            if(!hybridBurst.hybrid)mapped=std::make_unique<scam::MappedScamBurst>(argv[3]);
            // Debug: keep the burst (touch <external files>/scam_keep) so the merge can be replayed
            // offline with `--scam-capture <job dir> <external files>/scam_burst.bin <out>`. An offline replay
            // (SCAM_HYBRID / SCAM_NO_KEEP in the environment) never overwrites the kept burst of the app.
            if(std::string(argv[3])!="/sdcard/Android/data/org.codeaurora.snapcam/files/scam_burst.bin"
                    &&!std::getenv("SCAM_HYBRID")&&!std::getenv("SCAM_NO_KEEP")
                    &&access("/sdcard/Android/data/org.codeaurora.snapcam/files/scam_keep",F_OK)==0){
                std::ofstream dst("/sdcard/Android/data/org.codeaurora.snapcam/files/scam_burst.bin",std::ios::binary|std::ios::trunc);
                if(mapped)dst.write(static_cast<const char*>(mapped->address),std::streamsize(mapped->length));
                else dst.write(static_cast<const char*>(hybridBurst.address),std::streamsize(hybridBurst.length));
            }
            const char* outputPath=argv[4];
            report("SCAM CAPTURE: original forward weights and stock CPU motion; supplied per-frame calibration");
            report(hybridBurst.hybrid?"SCAM INPUT: hybrid v10 burst frames="+std::to_string(hybridBurst.input.frames.size()):"SCAM INPUT: mapped capture file");
            if(mapped){
            const auto& input=mapped->burst;
            const auto& scene=input.scene;
            report("SCAM SCENE: timestamp="+std::to_string(scene.timestamp)
                +" lux="+(scene.hasLux()?std::to_string(scene.lux):"unavailable")
                +" ADRC="+(scene.hasAdrc()?std::to_string(scene.adrc):"unavailable")
                +" flags="+std::to_string(scene.flags)+" luxSource="+std::to_string(scene.luxSource));
            for(size_t i=0;i<input.ae.size();++i) {
                const auto& ae=input.ae[i];
                report("SCAM AE: slot="+std::to_string(i)+" timestamp="+std::to_string(ae.timestamp)
                    +" flags="+std::to_string(ae.flags));
                if(ae.hasAec()) {
                    const auto f=ae.fields();
                    report("SCAM AE VALUES: lux="+std::to_string(f.lux)+" exposureMs="+std::to_string(f.exposureMs)
                        +" shortGain="+std::to_string(f.shortGain)+" digitalGain="+std::to_string(f.digitalGain)
                        +" rawHdrDrc="+(ae.hasHdrDrc()?std::to_string(ae.drcGain(true)):"unavailable"));
                }
            }
            }
            scam::ScamAlignment alignment;
            if(motion){
                alignment=[&](scam::Burst& burst){joinCreWarm();return motion->align(burst,report);};
                // P31 (W1.2): the hybrid merge hands the CRE every frame before its groups: their guides are built ahead on the pool
                scam::scamAlignmentPrefetch()=[&](const scam::Burst* base,const std::vector<std::pair<const uint16_t*,float>>& donors){
                    if(base)motion->prefetch(*base,donors); else motion->dropPrefetched();
                };
            }
            const double mappedMs=sinceStart();
            std::vector<uint16_t> mergedDng;
            std::vector<uint8_t> effMap,clipFlags;
            std::vector<float> result;
            // SCAM Hybrid merge (GCam 6.1 Sabre kernel + SCAM 9.6 front end, Bento, Shasta; any GPU, no model):
            // requested by the app with the job marker, or SCAM_HYBRID=1 for an offline replay.
            bool hybrid=hybridBurst.hybrid||access((std::string(argv[2])+"/hybrid-merge").c_str(),F_OK)==0||std::getenv("SCAM_HYBRID");
            std::unique_ptr<scam::Graph> graph;
            if(!hybrid) {
                // P31: the HTP runtime sets ADSP_LIBRARY_PATH (setenv, which can move the environment array): the warm-up threads
                // (the CRE's first calls, the EGL driver load) are joined first, so no getenv of theirs runs meanwhile.
                joinCreWarm();
                if(eglWarm.joinable())eglWarm.join();
                // The forward network ships as Hexagon v79 context binaries (SM8750 only); without it the
                // burst is merged by the SCAM Hybrid (scam-hybrid.h).
                try{graph=std::make_unique<scam::Graph>(argv[2],report);}
                catch(const std::exception& error){report(std::string("SCAM NPU: neural model unavailable (")+error.what()+"); SCAM Hybrid merge");hybrid=true;}
            }
            if(hybrid) {
                report("HYBRID MERGE: SCAM Hybrid requested (Sabre kernel, SCAM rejection/weights, Bento, Shasta)");
                { // P30: GPU program binary cache (app cache dir from the job; SCAM_GL_CACHE in an offline replay)
                    std::ifstream cache(std::string(argv[2])+"/gl-cache");std::string dir;
                    if(cache&&std::getline(cache,dir)&&!dir.empty())scam::hybridProgramCacheDir()=dir;
                    else if(const char* env=std::getenv("SCAM_GL_CACHE"))scam::hybridProgramCacheDir()=env;
                }
                const auto tuning=scam::loadHybridTuning(argv[2],report);
                scam::HybridInput hin=hybridBurst.hybrid?hybridBurst.input:scam::hybridFromScamBurst(mapped->burst);
                if(std::getenv("SCAM_MERGED_DNG"))hin.mergedDng=true;
                // Clip flags trailer: asked by the request header (flag 4), the job marker `clip-flags` or, in an offline replay
                // (SCAM_HYBRID) only, the tuning key clipFlags. The app checks the result size exactly: a fallback tuning file
                // (external files dir, /data/local/tmp) must not add a trailer it did not ask for.
                const bool wantClipFlags=hin.clipFlags||(tuning.clipFlags&&std::getenv("SCAM_HYBRID"))
                        ||access((std::string(argv[2])+"/clip-flags").c_str(),F_OK)==0;
                worker_crash::mark("hybrid merge");
                result=scam::hybridReconstruct(hin,tuning,alignment,report,&mergedDng,&effMap,nullptr,wantClipFlags?&clipFlags:nullptr);
            } else {
            const auto& input=mapped->burst;
            scam::ScamExecute forward;
            if(graph)forward=[&](const std::vector<float>& in,std::vector<float>& out){
                graph->input=in;graph->execute();out=graph->output;
            };
            result=scam::reconstruct(input,forward,report,[&](const std::string& name,const std::vector<float>& data,int w,int h){
                if(!input.diagnostics)return;
                std::ofstream f(std::string(argv[2])+"/"+name+".pfm",std::ios::binary);
                if(!f){report("SCAM DIAGNOSTIC: cannot open tile dump");return;}
                f<<"PF\n"<<w<<" "<<h<<"\n-1.0\n";
                for(int y=h-1;y>=0;--y)f.write(reinterpret_cast<const char*>(data.data()+size_t(y)*w*3),w*3*sizeof(float));
                if(!f)report("SCAM DIAGNOSTIC: incomplete tile dump");
            },alignment,&mergedDng,&effMap);
            }
            const double mergedMs=sinceStart();
            // The CRE's first call prints an unterminated line ("Failed to load symbol ..."): with the warm-up joined here at the
            // latest it lands before the result lines, never in front of "SCAM CAPTURE OK" (the app matches that line exactly).
            joinCreWarm();
            // P30: the log line's mean and max on all cores (the sum was ~60 ms on one core at 12 MP, 4x that on the 2x grid).
            double sum=0;float maximum=0;
            {
                std::mutex lock;
                scam::mergeRowBands(int((result.size()+65535)/65536),[&](int b0,int b1){
                    double s=0;float m=0;
                    for(size_t i=size_t(b0)*65536;i<std::min(result.size(),size_t(b1)*65536);++i){s+=result[i];m=std::max(m,result[i]);}
                    std::lock_guard<std::mutex> guard(lock);sum+=s;maximum=std::max(maximum,m);
                });
            }
            report("SCAM RGB: mean="+std::to_string(sum/result.size())+" max="+std::to_string(maximum));
            worker_crash::mark("output write");
            const int out=scam::openArgument(outputPath,O_WRONLY|O_CREAT|O_TRUNC);
            if(out<0)throw std::runtime_error("Cannot open SCAM output");
            auto writeAll=[&](const void* data,size_t bytes){
                const char* p=static_cast<const char*>(data);
                while(bytes){const ssize_t n=write(out,p,bytes);if(n<0&&errno==EINTR)continue;
                    if(n<=0){close(out);throw std::runtime_error("Incomplete SCAM output");}p+=n;bytes-=size_t(n);}
            };
            std::vector<std::pair<const void*,size_t>> parts{{result.data(),result.size()*sizeof(float)}};
            // P80: the job marker `rgb-half` (the app asks for it, SCAM Hybrid only): the RGB as IEEE half floats (round to nearest
            // even), half the bytes to write, read and upload; the app's first stage stores it in half floats anyway (its input
            // texture is sampled into RGBA16F). The trailers stay as they are.
            std::vector<uint16_t> half; // (scam::floatToHalf: portable, the host checks build this file with g++ on x86 too)
            if(hybrid&&access((std::string(argv[2])+"/rgb-half").c_str(),F_OK)==0){
                half.resize(result.size());
                scam::mergeRowBands(int((result.size()+65535)/65536),[&](int b0,int b1){
                    for(size_t i=size_t(b0)*65536;i<std::min(result.size(),size_t(b1)*65536);++i){
                        half[i]=scam::floatToHalf(result[i]);
                    }
                });
                parts[0]={half.data(),half.size()*sizeof(uint16_t)};
                report("SCAM RGB: written as half floats ("+std::to_string(half.size()*2>>20)+" MB)");
            }
            // Optional trailer: merged Bayer RAW (uint16, sensor layout) for the DNG.
            if(!mergedDng.empty())parts.emplace_back(mergedDng.data(),mergedDng.size()*sizeof(uint16_t));
            // Optional second trailer: effective merged frames per pixel (uint8, 1/8 frame), w*h bytes.
            if(!effMap.empty()&&effMap.size()*3==result.size())parts.emplace_back(effMap.data(),effMap.size());
            // Optional third trailer (only on request, after the effective map): clip flags, uint8 per output pixel (bits: 0/1/2 R/G/B
            // from the clipped mean, 3 clip border, 4 Bento mask, 5 ultrashort clipped mean).
            if(!clipFlags.empty()&&clipFlags.size()==effMap.size())parts.emplace_back(clipFlags.data(),clipFlags.size());
            // P48 (research/speed/PLAIN_SHOT_SPEED.md): the output (the app's memfd, or a replay's file) reserved with fallocate,
            // mapped and filled on all cores: write() moved the 600 MB of the 2x grid on one core (~300 ms on the vivo X200 Ultra).
            // The same bytes in the same order; anything the mapping cannot take (a pipe, a sealed or non-empty file, no memory for
            // the reservation, SCAM_NO_MAP_WRITE) is written as before.
            bool mappedWrite=false;
            if(!std::getenv("SCAM_NO_MAP_WRITE")){
                size_t total=0;for(const auto& part:parts)total+=part.second;
                struct stat st{};
                if(total>0&&fstat(out,&st)==0&&S_ISREG(st.st_mode)&&st.st_size==0&&lseek(out,0,SEEK_CUR)==0
                        &&fallocate(out,0,0,off_t(total))==0){
                    void* map=mmap(nullptr,total,PROT_READ|PROT_WRITE,MAP_SHARED,out,0);
                    if(map!=MAP_FAILED){
                        char* dst=static_cast<char*>(map);
                        for(const auto& part:parts){
                            const char* src=static_cast<const char*>(part.first);const size_t bytes=part.second;
                            constexpr size_t kChunk=size_t(4)<<20;
                            scam::mergeRowBands(int((bytes+kChunk-1)/kChunk),[&](int c0,int c1){
                                const size_t a=size_t(c0)*kChunk,e=std::min(bytes,size_t(c1)*kChunk);
                                if(e>a)std::memcpy(dst+a,src+a,e-a);
                            });
                            dst+=bytes;
                        }
                        mappedWrite=munmap(map,total)==0;
                        if(!mappedWrite){close(out);throw std::runtime_error("Incomplete SCAM output");}
                    } else if(ftruncate(out,0)!=0){close(out);throw std::runtime_error("Incomplete SCAM output");}
                }
            }
            if(!mappedWrite)for(const auto& part:parts)writeAll(part.first,part.second);
            if(close(out))throw std::runtime_error("Incomplete SCAM output");
            {
                char line[200];
                std::snprintf(line,sizeof(line),"SCAM WORKER TIMELINE ms: cre=%.0f mapped=%.0f merged=%.0f written=%.0f",creMs,mappedMs,mergedMs,sinceStart());
                report(line);
            }
            alarm(0);report("SCAM CAPTURE OK");return 0;
        }
        if(argc==3 && std::string(argv[1])=="--scam-tone-check") {
            alarm(360);
            scam::probeTone(argv[2],[](const std::string& line){scam_nn::log(line);});
            return 0;
        }
        if(argc==5 && std::string(argv[1])=="--scam-forward") {
            // Reference runner for weight extraction: N input tiles (float32 NHWC 1x544x544x22)
            // in, N output tiles (1x544x544x3) out. Same graph and runtime as the capture path.
            alarm(600);
            const size_t inTile=size_t(544)*544*22,outTile=size_t(544)*544*3;
            std::ifstream inFile(argv[3],std::ios::binary|std::ios::ate);
            if(!inFile)throw std::runtime_error("Cannot open forward input");
            const size_t bytes=size_t(inFile.tellg());
            if(!bytes||bytes%(inTile*4))throw std::runtime_error("Forward input is not a whole number of tiles");
            const size_t tiles=bytes/(inTile*4);inFile.seekg(0);
            scam::Graph graph(argv[2],[](const std::string& line){scam_nn::log(line);});
            std::ofstream outFile(argv[4],std::ios::binary|std::ios::trunc);
            if(!outFile)throw std::runtime_error("Cannot open forward output");
            for(size_t t=0;t<tiles;++t) {
                inFile.read(reinterpret_cast<char*>(graph.input.data()),std::streamsize(inTile*4));
                if(!inFile)throw std::runtime_error("Short forward input");
                graph.execute();
                outFile.write(reinterpret_cast<const char*>(graph.output.data()),std::streamsize(outTile*4));
            }
            outFile.flush();
            scam_nn::log("SCAM FORWARD OK tiles="+std::to_string(tiles));
            alarm(0);return 0;
        }
        if(argc==3 && std::string(argv[1])=="--scam-check") {
            signal(SIGALRM,SIG_DFL);alarm(150);
            scam::probe(argv[2],[](const std::string& line){scam_nn::log(line);});
            alarm(0);scam_nn::log("SCAM RUNTIME CHECK COMPLETE");return 0;
        }
        if(argc==5 && (std::string(argv[1])=="--hexquad-capture" || std::string(argv[1])=="--hexquad-capture-cached")) {
            signal(SIGALRM,SIG_DFL);alarm(840);
            {
                scam_hexquad::MappedBurst mapped(argv[3]);
                auto& burst=mapped.burst;
                scam_nn::log("HP9 HEXQUAD CAPTURE v2: model=x"+std::to_string(burst.scale)+" actual_frames=6; Tetra4x4; ISO="+std::to_string(burst.iso)+" CFA="+std::to_string(burst.red));
                const double initStart=scam_hexquad::hexClockMs();
                scam_hexquad::HexSession session(burst.scale);session.init(argv[2]);
                scam_nn::log("HEX TIMING ms: model_runtime_init="+std::to_string(scam_hexquad::hexClockMs()-initStart));
                const double gateStart=scam_hexquad::hexClockMs();
                scam_hexquad::requireHexCaptureCharts(session,burst.iso,burst.red,std::string(argv[1])=="--hexquad-capture-cached",burst.scale,burst.noise);
                scam_nn::log("HEX TIMING ms: profile_gate="+std::to_string(scam_hexquad::hexClockMs()-gateStart));
                scam_hexquad::captureHex(session,burst,argv[4]);
            }
            alarm(0);scam_nn::log("HEXQUAD CAPTURE OK");return 0;
        }
        if(argc==5 && std::string(argv[1])=="--quad-capture") {
            signal(SIGALRM,SIG_DFL);alarm(600);
            {
                // Debug: keep the transport file when /data/local/tmp/quad_keep exists.
                if(access("/data/local/tmp/quad_keep",F_OK)==0){
                    std::ifstream src(argv[3],std::ios::binary);std::ofstream dst("/data/local/tmp/quad_burst.bin",std::ios::binary|std::ios::trunc);
                    dst<<src.rdbuf();scam_nn::log("QUAD DEBUG: burst copied to /data/local/tmp/quad_burst.bin");
                }
                scam_quad::MappedQuadBurst mapped(argv[3]);
                auto& burst=mapped.burst;
                scam_nn::log(std::string("QUAD CAPTURE: model=")+(burst.model==1?"scam_ldr_hp9_general_roi_quad_x1 (tele)":"scam_ldr_imx06c_general_quad_x1 (main)")+" frames=4; Quad2x2; "+
                    std::to_string(burst.w)+"x"+std::to_string(burst.h)+" ISO="+std::to_string(burst.iso)+" CFA="+std::to_string(burst.red));
                const double initStart=scam_hexquad::hexClockMs();
                const int net=burst.model==1&&burst.iso>2000?2:burst.model;burst.network=net;
                scam_nn::log("QUAD NETWORK: "+std::string(net==2?"roi_quad_x1_highdrc (ISO > 2000)":net==1?"roi_quad_x1":"imx06c quad_x1"));
                scam_hexquad::HexSession session(scam_hexquad::quadSpec(net));session.init(argv[2]);
                scam_nn::log("QUAD TIMING ms: model_runtime_init="+std::to_string(scam_hexquad::hexClockMs()-initStart));
                scam_quad::captureQuad(session,burst,argv[4]);
                if(access("/data/local/tmp/quad_keep",F_OK)==0){
                    std::ifstream src(argv[4],std::ios::binary);std::ofstream dst("/data/local/tmp/quad_out.bin",std::ios::binary|std::ios::trunc);dst<<src.rdbuf();
                }
            }
            alarm(0);scam_nn::log("HEXQUAD CAPTURE OK");return 0;
        }
        if(argc==3 && std::string(argv[1])=="--hexquad-check") {
            signal(SIGALRM,SIG_DFL);alarm(180);
            scam_nn::log("HP9 HEXQUAD v5: bundled QNN 2.29.8; canonical RGGB; noiseless stress + profiled capture checks");
            bool x1Passed=false,x2Passed=true,profilePassed=false;
            for(int scale:{1,2}) {
                scam_hexquad::HexSession session(scale);session.init(argv[2]);
                if(scale==1)x1Passed=scam_hexquad::checkHexCharts(session,1);
                else {
                    for(int red=0;red<4;++red)
                        x2Passed=scam_hexquad::checkHexCharts(session,2,red,red==3?800:0)&&x2Passed;
                    scam_hexquad::diagnoseHexIso(session,800,3);
                    profilePassed=scam_hexquad::checkHexProfileCharts(session,800,3);
                }
            }
            alarm(0);
            scam_nn::log(std::string("HEXQUAD CHECK COMPLETE: x2_noiseless_stress=")+(x2Passed?"PASS":"FAIL")+
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
        scam_nn::Session session;session.init(argv[1]);scam_nn::Mapping mapping;
        try {mapping=scam_nn::calibrate(session);}
        catch(const scam_nn::MappingError&) {
            if(argc==2)scam_nn::diagnoseLayouts(session);
            throw;
        }
        if(argc==7){
            const std::string ip(argv[2]),op(argv[3]);
            auto bytes=scam_nn::read(ip);
            if(width<8||height<8||static_cast<int64_t>(width)*height>16000000||bytes.size()!=static_cast<uint64_t>(width)*height*4)
                throw std::runtime_error("Invalid prepared RAW length");
            for(size_t i=0;i<bytes.size();i+=4){float v;std::memcpy(&v,bytes.data()+i,4);if(!std::isfinite(v)||v<0||v>16)throw std::runtime_error("Invalid prepared RAW sample");}
            auto result=scam_nn::reconstruct(session,mapping,reinterpret_cast<const float*>(bytes.data()),width,height,redQuad);
            std::ofstream f(op,std::ios::binary|std::ios::trunc);if(!f)throw std::runtime_error("Cannot open result");
            f.write(reinterpret_cast<const char*>(result.data()),result.size()*sizeof(float));f.close();
            if(!f)throw std::runtime_error("Result write failed");
            scam_nn::log("NEURAL FRAME COMPLETE");
        }
        } // Free vendor handles while the hard timeout is still armed.
        alarm(0);scam_nn::log("NEURAL JOB OK");return 0;
    } catch(const std::exception& e){
        scam_nn::log(std::string("STOP: ")+e.what());
        return 1;
    }
}
