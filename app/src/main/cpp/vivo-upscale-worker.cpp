// Experimental device-only adapters for pinned Vivo RAISR and SoftPQE ABIs.
// Not linked into the camera PID. Run via the verified bundle launcher only.
#include <algorithm>
#include <array>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fstream>
#include <fcntl.h>
#include <sys/stat.h>
#include <iostream>
#include <signal.h>
#include <stdexcept>
#include <string>
#include <unistd.h>
#include <vector>
#ifdef __ANDROID__
#include <android/log.h>
#endif
#include "vivo-softpqe-abi.h"
#include "vivo-softpqe-controls.h"
#include "vivo-raisr-abi.h"
#include "vivo-raisr-controls.h"
#ifndef VIVO_VDNN_LIBRARY
#define VIVO_VDNN_LIBRARY "/vendor/lib64/libvdnn.so"
#endif

namespace {
struct Image {
    uint32_t format, width, height, reserved0;
    uint8_t* planes[4];
    uint32_t stride[4], scanline[4], bytes[4];
    uint64_t extra[3];
};
static_assert(sizeof(void*) == 8 && sizeof(Image) == 0x78, "ARM64 VImageEx required");
static_assert(offsetof(Image,planes)==0x10 && offsetof(Image,stride)==0x30 &&
              offsetof(Image,scanline)==0x40 && offsetof(Image,bytes)==0x50, "Image ABI");
using Init=vivo_raisr::Init;
uint32_t integer(const char* s) {
    char* end=nullptr; errno=0; auto v=std::strtoul(s,&end,10);
    if(errno || !*s || *s=='-' || *end || v>1000000)throw std::runtime_error("Invalid integer");
    return static_cast<uint32_t>(v);
}
size_t size(uint32_t w,uint32_t h) {
    if(w<32 || h<32 || w%2 || h%2 || w>19968 || h>14000 || uint64_t(w)*h>96000000)
        throw std::runtime_error("Unsupported YUV dimensions");
    return size_t(w)*h*3/2;
}
std::vector<uint8_t> read(const char* path,size_t expected) {
    std::ifstream in(path,std::ios::binary|std::ios::ate);
    if(!in || in.tellg()!=static_cast<std::streamoff>(expected))throw std::runtime_error("Wrong input byte count");
    std::vector<uint8_t> b(expected);in.seekg(0);in.read(reinterpret_cast<char*>(b.data()),b.size());
    if(!in)throw std::runtime_error("Input read failed");
    return b;
}
// Guard bytes detect linear writes beyond the declared output plane allocation.
Image image(std::vector<uint8_t>& b,uint32_t w,uint32_t h,uint32_t format) {
    Image im{};im.format=format;im.width=w;im.height=h;
    im.planes[0]=b.data();im.planes[1]=b.data()+size_t(w)*h;
    im.stride[0]=im.stride[1]=w;im.scanline[0]=h;im.scanline[1]=h/2;
    im.bytes[0]=w*h;im.bytes[1]=w*h/2;return im;
}
struct Library {
    void* h;
    explicit Library(const char* path):h(dlopen(path,RTLD_NOW|RTLD_LOCAL)) {
        if(!h)throw std::runtime_error(dlerror());
    }
    // One-shot process: keep vendor runtimes mapped through explicit Uninit.
    // Unloading their allocator singletons caused exit 139 after a failed job.
    ~Library()=default;
    template<class T>T symbol(const char* name) {
        dlerror();auto p=dlsym(h,name);const char* e=dlerror();
        if(e||!p)throw std::runtime_error(std::string("Missing symbol: ")+name);
        return reinterpret_cast<T>(p);
    }
};
void nativeLogs() {
#ifdef __ANDROID__
    // VDNN/QNN log through liblog, not stdout. Forward only this worker's
    // messages; do not collect other processes or alter global log properties.
    Library log("liblog.so");
    auto set=reinterpret_cast<void(*)(void(*)(const __android_log_message*))>(
        dlsym(log.h,"__android_log_set_logger"));
    auto priority=reinterpret_cast<int(*)(int)>(dlsym(log.h,"__android_log_set_minimum_priority"));
    if(priority)priority(ANDROID_LOG_DEBUG);
    if(set)set(+[](const __android_log_message* m){
        if(m && m->message)std::fprintf(stderr,"NATIVE/%s: %.16384s\n",m->tag?m->tag:"?",m->message);
    });
#endif
}
void softRuntime() {
#ifdef __ANDROID__
    // Same generation as the HexQuad remosaic runtime. /vendor/lib64/hw is
    // QNN 2.25 / Core 2.18 and is rejected by VDNN qnn_2_28 (needs Core >=2.21).
    Library driver("libcdsprpc.so");
    Library stub("/vendor/npu/lib/libQnnHtpV79Stub.so");
    Library htp("/vendor/npu/lib/libQnnHtp.so");
    struct Version { uint32_t major,minor,patch; };
    struct Provider { uint32_t id;const char* name;Version core,backend;void* slots[5]; };
    static_assert(offsetof(Provider,core)==0x10 && offsetof(Provider,slots)==0x28,"QNN provider prefix");
    const Provider** providers=nullptr;uint32_t count=0;
    auto rc=htp.symbol<uint64_t(*)(const Provider***,uint32_t*)>("QnnInterface_getProviders")(&providers,&count);
    if(rc || !providers || !count || count>16)throw std::runtime_error("SoftPQE QNN providers failed");
    const Provider* api=nullptr;
    for(uint32_t i=0;i<count;++i)if(providers[i] && providers[i]->id==6 &&
        providers[i]->core.major==2 && providers[i]->core.minor==22 && providers[i]->core.patch==0)api=providers[i];
    if(!api || !api->slots[4])throw std::runtime_error("SoftPQE requires pinned QNN Core 2.22.0");
    const char* build=nullptr;
    rc=reinterpret_cast<uint64_t(*)(const char**)>(api->slots[4])(&build);
    if(rc || !build || std::strcmp(build,"v2.29.8.250123143957_105779"))
        throw std::runtime_error("Unexpected SoftPQE QNN build");
    std::cout<<"SOFTPQE QNN Core 2.22.0 SDK="<<build<<std::endl;
#endif
}
std::string softConfig(const std::string& source,const std::string& output,vivo_softpqe::Controls c) {
    if(c.luma==100 && c.chroma==100 && c.sharpen==100)return source;
    const auto slash=output.rfind('/');
    if(slash==std::string::npos)throw std::runtime_error("SoftPQE requires an absolute job path");
    const std::string target=output.substr(0,slash);
    if(target==source)throw std::runtime_error("SoftPQE config must be a private copy");
    for(const char* name:{"softpqe_configs.xml","softpqe_configs_master_2x.xml",
            "softpqe_configs_master_2x_portrait.xml","softpqe_configs_master_2x_skin.xml"}) {
        std::ifstream in(source+"/"+name,std::ios::binary|std::ios::ate);
        if(!in || in.tellg()<0 || in.tellg()>1024*1024)throw std::runtime_error("Cannot read SoftPQE config");
        std::string xml(static_cast<size_t>(in.tellg()),'\0');in.seekg(0);
        if(!in.read(xml.data(),xml.size()))throw std::runtime_error("SoftPQE config read failed");
        xml=std::string(name)=="softpqe_configs.xml"?vivo_softpqe::tuneMain(xml,c):vivo_softpqe::tuneProfile(xml,c);
        // The app owns the parent job directory and unlinks these temporary files.
        int fd=open((target+"/"+name).c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
        if(fd<0)throw std::runtime_error("Cannot create private SoftPQE config");
        size_t done=0;
        while(done<xml.size()) {
            ssize_t n=write(fd,xml.data()+done,xml.size()-done);
            if(n<0 && errno==EINTR)continue;
            if(n<=0){close(fd);throw std::runtime_error("SoftPQE config write failed");}
            done+=size_t(n);
        }
        if(close(fd))throw std::runtime_error("SoftPQE config close failed");
    }
    return target;
}
}
int run(int argc,char** argv) {
    try {
        nativeLogs();
        if(argc==3 && std::string(argv[1])=="--probe") {
            alarm(30); Library library(argv[2]);
            const std::string path(argv[2]);
            if(path.find("libvivo_raisr.so")!=std::string::npos) {
                library.symbol<void*>("vivoRaisrInit");library.symbol<void*>("vivoRaisrProcess");
                library.symbol<void*>("vivoRaisrUninit");
                const auto* v=library.symbol<const int*(*)()>("vivoRaisrGetVersion")();
                if(!v || v[0]!=1 || v[1]!=1 || v[2]!=56)throw std::runtime_error("Unverified RAISR version");
                std::cout<<"RAISR LOAD OK 1.1.56; inference untested\n";
            } else if(path.find("libvivo_softpqe.so")!=std::string::npos) {
                for(const char* s:{"vivoSoftPQEInit","vivoSoftPQEProcess","vivoSoftPQEGetMode","vivoSoftPQEUninit"})
                    library.symbol<void*>(s);
                std::cout<<"SOFTPQE LOAD OK; inference untested\n";
            } else {
                // Recovered 0x38 registration ABI. Call registration only, never factories
                // with fabricated ChiFeature2CreateInputInfo or request/stream pointers.
                struct Ops {
                    uint32_t size, reserved;
                    const char* vendor;
                    uint64_t version;
                    void *create, *query, *vendorTags, *negotiate;
                } ops{};
                static_assert(sizeof(Ops)==0x38, "CHI feature ops ABI");
                library.symbol<void(*)(Ops*)>("ChiFeature2OpsEntry")(&ops);
                if(ops.size!=sizeof(Ops) || !ops.vendor || !ops.create || !ops.query || !ops.negotiate)
                    throw std::runtime_error("Unsupported MFSR feature registration ABI");
                std::cout<<"MFSR REGISTRATION OK; create/query/negotiate registered; "
                         <<"CamX/CHI request graph required; no inference executed\n";
            }
            return 0;
        }
        // library, model directory, input YUV, pre-created EMPTY output path, w,h,ow,oh,format,ISO,role
        if((argc!=13 && argc!=16 && argc!=17) || (std::string(argv[1])!="--raisr" && std::string(argv[1])!="--softpqe"))throw std::runtime_error(
            "Usage: --raisr|--softpqe library profile-dir input output w h ow oh format ISO role [RAISR: strength texture halo | SoftPQE: luma chroma sharpen strength]");
        const bool soft=std::string(argv[1])=="--softpqe";
        const std::string backend=soft?"SOFTPQE":"RAISR";
        const auto w=integer(argv[6]),h=integer(argv[7]),ow=integer(argv[8]),oh=integer(argv[9]);
        const auto format=integer(argv[10]),iso=integer(argv[11]),role=integer(argv[12]);
        vivo_raisr::Controls controls;
        vivo_softpqe::Controls softControls;
        if((soft && argc!=13 && argc!=17) || (!soft && argc!=13 && argc!=16))throw std::runtime_error("Wrong backend controls");
        if(!soft && argc==16)controls={integer(argv[13]),integer(argv[14]),integer(argv[15])};
        if(soft && argc==17)softControls={integer(argv[13]),integer(argv[14]),integer(argv[15]),integer(argv[16])};
        if(controls.strength>100 || controls.texture>100 || controls.halo>100)
            throw std::runtime_error("Invalid RAISR controls");
        if(softControls.luma>100 || softControls.chroma>100 || softControls.sharpen>100 || softControls.strength>100)
            throw std::runtime_error("Invalid SoftPQE controls");
        if(format!=0x11 && format!=0x12)throw std::runtime_error("RAISR accepts only format codes 17/18; verify UV order with a chart");
        if(ow<w || oh<h || uint64_t(ow)*h!=uint64_t(oh)*w || ow>uint64_t(w)*4)
            throw std::runtime_error("Only isotropic 1x-4x enlargement is allowed");
        struct stat st{};
        if(lstat(argv[5],&st)!=0 || !S_ISREG(st.st_mode) || st.st_size!=0)
            throw std::runtime_error("Output must be a pre-created empty regular file");
        if(role!=2 && role!=8)throw std::runtime_error("Only pinned master (2) and tele-3x (8) profiles are supported");
        if(soft && (role!=2 || ow!=uint64_t(w)*2 || oh!=uint64_t(h)*2))
            throw std::runtime_error("SoftPQE shared profile requires profile role 2 and exact 2x");
        const auto bytes=size(ow,oh);auto input=read(argv[4],size(w,h));
        std::vector<uint8_t> output(bytes+4096,0xa5);
        std::string models(argv[3]);
        while(models.size()>1 && models.back()=='/')models.pop_back();
        auto init=vivo_raisr::makeInit(w,h,ow,oh,iso,role,models);
        auto in=image(input,w,h,format),out=image(output,ow,oh,format);
        signal(SIGALRM,SIG_DFL);alarm(180);
        // Like the neural-remosaic worker, select the matching QNN runtime before
        // creating any model session. VDNN's QNN-2.28 runtime is a first-use singleton.
        // APK=0 selects /vendor/npu/lib (QNN 2.29.8); APK=1 would load 2.25.
        if(soft) {
#ifdef __ANDROID__
            static_assert(sizeof(std::string)==24,"Pinned Android libc++ string ABI");
#endif
            softRuntime();
            Library vdnn(VIVO_VDNN_LIBRARY);
            // Public runtime option: standalone unsigned DSP process, as used
            // by the remosaic adapter. No SELinux/signature policy changes.
            std::string config="PLATFORM:SM8750_2_28 APK:0 SIGNEDPD:0";
            void* engine=nullptr;
            int rc=vdnn.symbol<int(*)(void**,const std::string&)>("vdnnPlatformInitV2")(&engine,config);
            std::cout<<"VDNN "<<config<<" init="<<rc<<" engine="<<(engine?"created":"null")<<std::endl;
            if(rc || !engine)throw std::runtime_error("VDNN APK runtime initialization failed");
        }
        Library library(argv[2]);
        if(soft) {
            auto create=library.symbol<int(*)(void**,const void*)>("vivoSoftPQEInit");
            auto process=library.symbol<int(*)(void*,const void*,Image*)>("vivoSoftPQEProcess");
            auto destroy=library.symbol<int(*)(void*)>("vivoSoftPQEUninit");
            auto mode=library.symbol<int(*)(void*)>("vivoSoftPQEGetMode");
            // Core 0x90e84/0x90e94 multiplies gain by 50, not 100.
            const std::string configPath=softConfig(argv[3],argv[5],softControls);
            std::cout<<"SOFTPQE controls luma="<<softControls.luma<<" chroma="<<softControls.chroma
                     <<" sharpen="<<softControls.sharpen<<" strength="<<softControls.strength
                     <<" config="<<configPath<<std::endl;
            auto params=vivo_softpqe::makeInit(w,h,float(iso)/50.f,configPath.c_str(),
                "/vendor/camera3rd/nti/softpqe/model");
            vivo_softpqe::Process frame{};frame.input=&in;
            void* handle=nullptr;int rc=create(&handle,&params);
            if(rc || !handle) {
                if(handle)destroy(handle);
                throw std::runtime_error("SoftPQE Init failed: "+std::to_string(rc));
            }
            // Core sometimes returns success while deliberately bypassing inference.
            const int before=mode(handle);
            const auto bypassBefore=static_cast<const uint8_t*>(handle)[0x5dc8];
            rc=(before==0 && !bypassBefore)?process(handle,&frame,&out):-1;
            const int after=mode(handle);
            const auto bypassAfter=static_cast<const uint8_t*>(handle)[0x5dc8];
            destroy(handle);
            std::cout<<"SoftPQE mode="<<before<<" -> "<<after<<" process="<<rc<<"\n";
            if(rc || before!=0 || after!=0 || bypassBefore || bypassAfter)
                throw std::runtime_error("SoftPQE failed or bypassed inference");
        } else {
            const auto* v=library.symbol<const int*(*)()>("vivoRaisrGetVersion")();
            if(!v||v[0]!=1||v[1]!=1||v[2]!=56)throw std::runtime_error("Unverified RAISR version");
            auto create=library.symbol<int(*)(void**,const Init*)>("vivoRaisrInit");
            auto process=library.symbol<int(*)(void*,const Image*,Image*)>("vivoRaisrProcess");
            auto destroy=library.symbol<int(*)(void*)>("vivoRaisrUninit");
            void* handle=nullptr;int rc=create(&handle,&init);
            if(rc||!handle){if(handle)destroy(handle);throw std::runtime_error("RAISR Init failed: "+std::to_string(rc));}
            rc=process(handle,&in,&out);destroy(handle);
            if(rc)throw std::runtime_error("RAISR Process failed: "+std::to_string(rc));
        }
        if(!std::all_of(output.begin()+bytes,output.end(),[](uint8_t b){return b==0xa5;}))
            throw std::runtime_error(backend+" output buffer overrun");
        if(out.width!=ow || out.height!=oh || out.planes[0]!=output.data() ||
           out.planes[1]!=output.data()+size_t(ow)*oh || out.format!=format ||
           out.stride[0]!=ow || out.stride[1]!=ow || out.scanline[0]!=oh ||
           out.scanline[1]!=oh/2 || out.bytes[0]!=ow*oh || out.bytes[1]!=ow*oh/2)
            throw std::runtime_error(backend+" modified output descriptor");
        // Never report quality/effect merely because vendor code returned success.
        auto range=std::minmax_element(output.begin(),output.begin()+size_t(ow)*oh);
        std::cout<<backend<<" returned 0; output Y range="<<int(*range.first)<<":"<<int(*range.second)<<"; visual verification required\n";
        if(*range.second==0 || (*range.first==0xa5 && *range.second==0xa5))
            throw std::runtime_error(backend+" returned empty/untouched output");
        if(!soft) {
            std::cout<<"RAISR controls strength="<<controls.strength<<" texture="<<controls.texture
                     <<" halo="<<controls.halo<<" ISO="<<iso<<" role="<<role<<" scale="<<init.zoom<<"\n";
            vivo_raisr::finish(input.data(),output.data(),w,h,ow,oh,controls);
        } else {
            vivo_softpqe::mix(input.data(),output.data(),w,h,ow,oh,softControls.strength);
        }
        int fd=open(argv[5],O_WRONLY|O_NOFOLLOW|O_CLOEXEC);
        if(fd<0)throw std::runtime_error("Cannot open output");
        struct stat now{};
        if(fstat(fd,&now)!=0 || now.st_size!=0 || now.st_ino!=st.st_ino || now.st_dev!=st.st_dev) {
            close(fd);throw std::runtime_error("Output changed during processing");
        }
        size_t done=0;
        while(done<bytes) {
            ssize_t n=write(fd,output.data()+done,bytes-done);
            if(n<0 && errno==EINTR)continue;
            if(n<=0){ftruncate(fd,0);close(fd);throw std::runtime_error("Output write failed");}
            done+=size_t(n);
        }
        if(close(fd)!=0)throw std::runtime_error("Output close failed");
        std::cout<<backend<<" EXPERIMENT COMPLETE\n";return 0;
    } catch(const std::exception& e){std::cerr<<"STOP: "<<e.what()<<"\n";return 1;}
}
int main(int argc,char** argv) {
    int rc=run(argc,argv);
    // Explicit vendor Uninit and file close have completed. Avoid vendor atexit
    // allocator teardown in this disposable process; do not turn failures into success.
    std::cout.flush();std::cerr.flush();std::fflush(nullptr);std::_Exit(rc);
}
