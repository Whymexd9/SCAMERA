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
#include <sys/mman.h>
#include <sys/resource.h>
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
        scam_nn::log("SCAM Neural native executable v31 (SHDR merge; CPU/GPU); root="+std::to_string(geteuid()));
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
            // P220: the heartbeat (HYBRID PROGRESS every 5 s, scam-progress.h) for the app's watchdog and processing ring; the 840 s
            // alarm stays the cap. Stopped before the result lines (the stack unwinds it on a STOP).
            scam_progress::Heartbeat heartbeat([](const std::string& line){scam_nn::log(line);},scam_progress::periodMs());
            scam_progress::pass("cre");
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
                scam_progress::pass("burst"); // P220: the app writes the burst meanwhile
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
            scam_progress::pass("map");
            const bool requestedHalf=access((std::string(argv[2])+"/rgb-half").c_str(),F_OK)==0;
            scam::MappedHybridBurst hybridBurst(argv[3],requestedHalf);
            std::unique_ptr<scam::MappedScamBurst> mapped;
            if(!hybridBurst.hybrid)mapped=std::make_unique<scam::MappedScamBurst>(argv[3]);
            // P188: the app's keep-burst marker names where (its own external files): the burst of this shot, for a replay
            {
                std::ifstream keep(std::string(argv[2])+"/keep-burst");std::string dest;
                if(keep&&std::getline(keep,dest)&&!dest.empty()&&dest!=argv[3]&&!std::getenv("SCAM_HYBRID")){
                    std::ofstream dst(dest,std::ios::binary|std::ios::trunc);
                    // P220: in 64 MB writes, each one progress (a 3.4 GB 50 MP burst took ~7 s, longer on slow storage); the same bytes
                    const char* src=mapped?static_cast<const char*>(mapped->address):static_cast<const char*>(hybridBurst.address);
                    const size_t bytes=mapped?mapped->length:hybridBurst.length;
                    constexpr size_t kChunk=size_t(64)<<20;
                    scam_progress::pass("keep-burst",int64_t((bytes+kChunk-1)/kChunk));
                    for(size_t at=0;at<bytes&&dst;at+=kChunk){dst.write(src+at,std::streamsize(std::min(kChunk,bytes-at)));scam_progress::step();}
                    report(std::string("SCAM KEEP: burst ")+(dst?"kept in ":"NOT kept in ")+dest);
                }
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
            bool noEffMap=false; // P187: header flag 16 (or SCAM_NO_EFFMAP in an offline replay): no effective-frame map
            std::vector<float> result;
            // SCAM Hybrid merge (Sabre 6.1 kernel + SCAM 9.6 front end, Bento, Shasta; any GPU, no model):
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
                if(std::getenv("SCAM_NO_EFFMAP"))hin.noEffMap=true;
                noEffMap=hin.noEffMap;
                if(noEffMap)report("HYBRID EFFECTIVE MAP: not made (header flag 16: the app reads none at this size)");
                worker_crash::mark("hybrid merge");
                scam_progress::pass("prepare"); // P220: the merge's front end (gain / noise checks, mosaic) until its own stages
                result=scam::hybridReconstruct(hin,tuning,alignment,report,&mergedDng,noEffMap?nullptr:&effMap,nullptr,wantClipFlags?&clipFlags:nullptr);
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
            scam_progress::pass("write");
            // write-only: a file (root worker, replay) is written with write(), faster than a mapping on f2fs (P153, 50 MP: 1.5 s
            // mapped against 0.6 s written); the app's memfd (fd:N, read-write) takes the mapped fill below
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
            auto toHalf=[&](uint16_t* dst,size_t i0,size_t i1){
                size_t i=i0;
#if defined(__aarch64__)
                // P153: FCVT (round to nearest even, the default FPCR): the same halves as floatToHalf for every one of the 2^32
                // float bit patterns (checked on the X200 Ultra), eight at a time
                for(;i+8<=i1;i+=8){
                    const float16x4_t a=vcvt_f16_f32(vld1q_f32(result.data()+i)),c=vcvt_f16_f32(vld1q_f32(result.data()+i+4));
                    vst1q_u16(dst+i,vreinterpretq_u16_f16(vcombine_f16(a,c)));
                }
#endif
                for(;i<i1;++i)dst[i]=scam::floatToHalf(result[i]);
            };
            // P153: the halves go straight into the mapped output (below): no 1.2 GB half copy of a 200 MP 2x grid and one pass less;
            // a null source marks that part. Only the plain write path (no mapping) still converts into `half` first.
            const bool halfOut=hybrid&&requestedHalf;
            if(halfOut){
                parts[0]={nullptr,result.size()*sizeof(uint16_t)};
                report("SCAM RGB: written as half floats ("+std::to_string(result.size()*2>>20)+" MB)");
            }
            // Optional trailer: merged Bayer RAW (uint16, sensor layout) for the DNG.
            if(!mergedDng.empty())parts.emplace_back(mergedDng.data(),mergedDng.size()*sizeof(uint16_t));
            // Optional second trailer: effective merged frames per pixel (uint8, 1/8 frame), w*h bytes.
            if(!effMap.empty()&&effMap.size()*3==result.size())parts.emplace_back(effMap.data(),effMap.size());
            // Optional third trailer (only on request, after the effective map): clip flags, uint8 per output pixel (bits: 0/1/2 R/G/B
            // from the clipped mean, 3 clip border, 4 Bento mask, 5 ultrashort clipped mean).
            // P187: without the map (header flag 16) they follow the RGB / the merged RAW directly
            if(!clipFlags.empty()&&(noEffMap?clipFlags.size()*3==result.size():clipFlags.size()==effMap.size()))
                parts.emplace_back(clipFlags.data(),clipFlags.size());
            // P48 (research/speed/PLAIN_SHOT_SPEED.md): the output (the app's memfd, or a replay's file) reserved with fallocate,
            // mapped and filled on all cores: write() moved the 600 MB of the 2x grid on one core (~300 ms on the vivo X200 Ultra).
            // The same bytes in the same order; anything the mapping cannot take (a pipe, a sealed or non-empty file, no memory for
            // the reservation, SCAM_NO_MAP_WRITE) is written as before.
            bool mappedWrite=false;
            const double writeStarted=sinceStart();double filledAt=0;
            // P179 (200 MP: the app was killed for LOW_MEMORY around here): an output above 1 GiB is not reserved at once - the
            // file is sized (sparse) and its pages come as they are filled, while the source pages already written go back
            // (madvise DONTNEED, page-aligned inside the source): the FP32 RGB slice by slice, each trailer after its copy. The
            // same bytes in the same order; the peak drops from the whole output plus every source to about the sources alone.
            auto release=[](const void* data,size_t bytes){
                const uintptr_t page=uintptr_t(sysconf(_SC_PAGESIZE));
                const uintptr_t a=(uintptr_t(data)+page-1)&~(page-1),e=(uintptr_t(data)+bytes)&~(page-1);
                if(e>a)madvise(reinterpret_cast<void*>(a),e-a,MADV_DONTNEED);
            };
            if(!std::getenv("SCAM_NO_MAP_WRITE")){
                size_t total=0;for(const auto& part:parts)total+=part.second;
                const bool sparse=total>(size_t(1)<<30);
                struct stat st{};
                if(total>0&&fstat(out,&st)==0&&S_ISREG(st.st_mode)&&st.st_size==0&&lseek(out,0,SEEK_CUR)==0
                        &&(sparse?ftruncate(out,off_t(total)):fallocate(out,0,0,off_t(total)))==0){
                    void* map=mmap(nullptr,total,PROT_READ|PROT_WRITE,MAP_SHARED,out,0);
                    if(map!=MAP_FAILED){
                        char* dst=static_cast<char*>(map);
                        for(const auto& part:parts){
                            const char* src=static_cast<const char*>(part.first);const size_t bytes=part.second;
                            if(!src){ // P153: the RGB as halves, converted into the mapping on all cores
                                uint16_t* h=reinterpret_cast<uint16_t*>(dst);const size_t n=result.size();
                                constexpr size_t kElems=size_t(1)<<20;
                                // P179: a sparse output in slices of 64 M values, each slice's FP32 pages released once halved
                                const size_t slice=sparse?(size_t(64)<<20):n;
                                for(size_t s0=0;s0<n;s0+=slice){
                                    const size_t s1=std::min(n,s0+slice);
                                    scam::mergeRowBands(int((s1-s0+kElems-1)/kElems),[&](int c0,int c1){
                                        toHalf(h,s0+size_t(c0)*kElems,std::min(s1,s0+size_t(c1)*kElems));
                                    });
                                    if(sparse)release(result.data()+s0,(s1-s0)*sizeof(float));
                                }
                                dst+=bytes;continue;
                            }
                            constexpr size_t kChunk=size_t(4)<<20;
                            scam::mergeRowBands(int((bytes+kChunk-1)/kChunk),[&](int c0,int c1){
                                const size_t a=size_t(c0)*kChunk,e=std::min(bytes,size_t(c1)*kChunk);
                                if(e>a)std::memcpy(dst+a,src+a,e-a);
                            });
                            if(sparse)release(src,bytes);
                            dst+=bytes;
                        }
                        filledAt=sinceStart();
                        mappedWrite=munmap(map,total)==0;
                        if(!mappedWrite){close(out);throw std::runtime_error("Incomplete SCAM output");}
                    } else if(ftruncate(out,0)!=0){close(out);throw std::runtime_error("Incomplete SCAM output");}
                }
            }
            if(!mappedWrite){
                if(halfOut&&!parts[0].first){
                    half.resize(result.size());
                    scam::mergeRowBands(int((result.size()+65535)/65536),[&](int b0,int b1){
                        toHalf(half.data(),size_t(b0)*65536,std::min(result.size(),size_t(b1)*65536));
                    });
                    parts[0]={half.data(),half.size()*sizeof(uint16_t)};
                }
                for(const auto& part:parts)writeAll(part.first,part.second);
            }
            if(close(out))throw std::runtime_error("Incomplete SCAM output");
            {
                size_t total=0;for(const auto& part:parts)total+=part.second;
                char line[200]; // P153: where the output write spends its time (filled = the mapping filled; the rest unmap / close)
                std::snprintf(line,sizeof(line),"SCAM OUTPUT: %zu MB %s in %.0f ms (filled %.0f ms)",total>>20,mappedWrite?"mapped":"written",
                    sinceStart()-writeStarted,filledAt>0?filledAt-writeStarted:0.0);
                report(line);
            }
            {
                char line[200];
                // P179: the worker's peak resident memory (MB) next to its timeline: the measured side of the app's frame budget
                struct rusage usage{};getrusage(RUSAGE_SELF,&usage);
                std::snprintf(line,sizeof(line),"SCAM WORKER TIMELINE ms: cre=%.0f mapped=%.0f merged=%.0f written=%.0f peakRssMB=%ld",creMs,mappedMs,mergedMs,sinceStart(),
                    long(usage.ru_maxrss/1024));
                report(line);
            }
            heartbeat.stop(); // P220: no heartbeat after the completion line
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
        // P175: the neural remosaic (HexQuad / Quad 2x2 NPU captures, their checks, the tele576 network) is gone
        throw std::runtime_error("Worker argument count");
    } catch(const std::exception& e){
        scam_nn::log(std::string("STOP: ")+e.what());
        return 1;
    }
}
