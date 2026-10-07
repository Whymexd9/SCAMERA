#pragma once
// Crash report of the worker (vivo X200 Pro, MediaTek / Mali, owner's log 2026-10-07: 14 of 14 Hybrid merges ended in
// "WORKER EXIT: signal 11 SIGSEGV" with nothing naming the crash site). On a fatal signal the worker writes, on stderr (the
// app reads it with stdout into the shot report), one line per item before it dies with the same signal:
//   WORKER CRASH: signal 11 SIGSEGV code=1 addr=0x... tid=... thread=<name> stage=<stage of the thread> last=<last stage of any thread>
//   WORKER CRASH pc=0x... <library>+0x<offset> <symbol>   (and lr, then the frame-pointer chain: "WORKER CRASH #n ...")
// Stages: worker_crash::Stage (RAII, per thread) and worker_crash::mark (global) at the steps that call into vendor code
// (CRE, EGL / GL program compile). Everything in the handler is async-signal-safe (write, dladdr on a loaded image, no malloc);
// memory of the frame chain is probed with write() into a pipe (EFAULT instead of a second fault).
#include <signal.h>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <atomic>
#include <cstdint>
#include <cstring>

namespace worker_crash {
inline thread_local const char* tlsStage=nullptr;
inline std::atomic<const char*>& lastStage(){static std::atomic<const char*> s{nullptr};return s;}
// stage names must be string literals or otherwise live for the whole process
inline void mark(const char* stage){tlsStage=stage;lastStage().store(stage,std::memory_order_relaxed);}
// The last report line of the worker (any thread), printed with the crash. Racing writers may garble it, never overrun it.
inline char* lastLine(){static char text[192];return text;}
inline void note(const char* line,size_t n){
    char* t=lastLine();if(n>190)n=190;
    for(size_t i=0;i<n;++i)t[i]=line[i]==10?' ':line[i];
    t[n]=0;
}
struct Stage {
    const char* previous;
    explicit Stage(const char* stage):previous(tlsStage){mark(stage);}
    ~Stage(){tlsStage=previous;}
    Stage(const Stage&)=delete;Stage& operator=(const Stage&)=delete;
};

namespace detail {
inline int probe[2]={-1,-1};
inline char altStack[64*1024];
inline void put(char*& p,char* end,const char* s){while(s&&*s&&p<end)*p++=*s++;}
inline void putHex(char*& p,char* end,uintptr_t v){
    char digits[2+16];int n=0;
    do{digits[n++]="0123456789abcdef"[v&15];v>>=4;}while(v&&n<16);
    put(p,end,"0x");while(n>0&&p<end)*p++=digits[--n];
}
inline void putDec(char*& p,char* end,long v){
    char digits[24];int n=0;bool neg=v<0;unsigned long u=neg?0UL-(unsigned long)v:(unsigned long)v;
    do{digits[n++]=char('0'+u%10);u/=10;}while(u&&n<20);
    if(neg&&p<end)*p++='-';while(n>0&&p<end)*p++=digits[--n];
}
inline void emit(char* begin,char* p){*p++='\n';ssize_t r=::write(2,begin,size_t(p-begin));(void)r;}
// readable: write() of the range into the pipe fails with EFAULT instead of faulting (then drained)
inline bool readable(const void* address,size_t bytes){
    if(probe[1]<0||!address)return false;
    if(::write(probe[1],address,bytes)!=ssize_t(bytes))return false;
    char sink[32];ssize_t r=::read(probe[0],sink,sizeof(sink));(void)r;
    return true;
}
inline void where(char*& p,char* end,uintptr_t pc){
    putHex(p,end,pc);
    Dl_info info{};
    if(pc&&dladdr(reinterpret_cast<void*>(pc),&info)&&info.dli_fname){
        const char* name=info.dli_fname;for(const char* s=name;*s;++s)if(*s=='/')name=s+1;
        put(p,end," ");put(p,end,name);put(p,end,"+");putHex(p,end,pc-reinterpret_cast<uintptr_t>(info.dli_fbase));
        if(info.dli_sname){put(p,end," ");put(p,end,info.dli_sname);put(p,end,"+");putHex(p,end,pc-reinterpret_cast<uintptr_t>(info.dli_saddr));}
    }
}
inline const char* signalName(int sig){
    switch(sig){case SIGSEGV:return "SIGSEGV";case SIGBUS:return "SIGBUS";case SIGFPE:return "SIGFPE";
        case SIGILL:return "SIGILL";case SIGABRT:return "SIGABRT";case SIGTRAP:return "SIGTRAP";default:return "signal";}
}
inline struct sigaction previous[32];
// The /proc/self/maps lines that hold the addresses (syscalls only: safe even when the fault is inside the dynamic linker,
// where dladdr would deadlock on its lock).
inline void mapsLines(const uintptr_t* addresses,int count){
    const int fd=::open("/proc/self/maps",O_RDONLY|O_CLOEXEC);
    if(fd<0)return;
    static char chunk[4096],line[512];
    int used=0;
    auto handleLine=[&](){
        line[used<int(sizeof(line))?used:int(sizeof(line))-1]=0;
        uintptr_t lo=0,hi=0;const char* s=line;
        while(*s&&*s!='-'){char c=*s++;lo=lo*16+uintptr_t(c>='a'?c-'a'+10:c-'0');}
        if(*s=='-')++s;
        while(*s&&*s!=' '){char c=*s++;hi=hi*16+uintptr_t(c>='a'?c-'a'+10:c-'0');}
        for(int i=0;i<count;++i)if(addresses[i]>=lo&&addresses[i]<hi){
            char out[600];char* e=out+sizeof(out)-2;char* p=out;
            put(p,e,"WORKER CRASH map ");putHex(p,e,addresses[i]);put(p,e,": ");put(p,e,line);emit(out,p);break;
        }
    };
    for(;;){
        const ssize_t n=::read(fd,chunk,sizeof(chunk));
        if(n<=0)break;
        for(ssize_t i=0;i<n;++i){
            if(chunk[i]==10){handleLine();used=0;}
            else if(used<int(sizeof(line))-1)line[used++]=chunk[i];
        }
    }
    if(used>0)handleLine();
    ::close(fd);
}
inline void handler(int sig,siginfo_t* info,void* context){
    static std::atomic<int> once{0};
    if(once.fetch_add(1)==0){
        char line[512];char* end=line+sizeof(line)-2;char* p=line;
        put(p,end,"WORKER CRASH: signal ");putDec(p,end,sig);put(p,end," ");put(p,end,signalName(sig));
        if(info){put(p,end," code=");putDec(p,end,info->si_code);put(p,end," addr=");putHex(p,end,reinterpret_cast<uintptr_t>(info->si_addr));}
        put(p,end," tid=");putDec(p,end,long(syscall(SYS_gettid)));
        char name[20]{};if(prctl(PR_GET_NAME,reinterpret_cast<unsigned long>(name),0,0,0)==0){put(p,end," thread=");put(p,end,name);}
        put(p,end," stage=");put(p,end,tlsStage?tlsStage:"-");
        put(p,end," last=");const char* last=lastStage().load(std::memory_order_relaxed);put(p,end,last?last:"-");
        emit(line,p);
        {char text[192];std::memcpy(text,lastLine(),sizeof(text));text[191]=0;
         p=line;put(p,end,"WORKER CRASH after: ");put(p,end,text);emit(line,p);}
#if defined(__aarch64__)
        if(context){
            const auto* uc=static_cast<const ucontext_t*>(context);
            const uintptr_t pc=uc->uc_mcontext.pc,lr=uc->uc_mcontext.regs[30]&0x00ffffffffffffffULL,sp=uc->uc_mcontext.sp;
            p=line;put(p,end,"WORKER CRASH pc=");putHex(p,end,pc);put(p,end," lr=");putHex(p,end,lr);put(p,end," sp=");putHex(p,end,sp);
            put(p,end," fp=");putHex(p,end,uc->uc_mcontext.regs[29]);emit(line,p);
            const uintptr_t both[2]={pc,lr};
            mapsLines(both,2);
            // dladdr takes the linker's lock: a fault inside dlopen / dlsym would hang here, so the process ends in 3 s at most
            alarm(3);
            p=line;put(p,end,"WORKER CRASH at ");where(p,end,pc);emit(line,p);
            p=line;put(p,end,"WORKER CRASH from ");where(p,end,lr);emit(line,p);
            // frame records {fp, lr} upwards from the faulting frame (code built without frame pointers ends the chain early)
            uintptr_t fp=uc->uc_mcontext.regs[29];
            for(int n=0;n<24&&fp&&!(fp&15)&&fp>=sp;++n){
                uintptr_t record[2];
                if(!readable(reinterpret_cast<const void*>(fp),sizeof(record)))break;
                std::memcpy(record,reinterpret_cast<const void*>(fp),sizeof(record));
                const uintptr_t ret=record[1]&0x00ffffffffffffffULL;
                if(!ret)break;
                p=line;put(p,end,"WORKER CRASH #");putDec(p,end,n);put(p,end," ");where(p,end,ret);emit(line,p);
                if(record[0]<=fp)break;
                fp=record[0];
            }
        }
#else
        (void)context;
#endif
    }
    // die with the same signal (the app reports "signal N"): the handler that was there before (the platform's crash dumper or the
    // default action), then a synchronous fault re-executes the instruction; a sent signal is raised again
    alarm(0); // the platform's dumper may take longer than the 3 s above; the exit must stay "signal <sig>"
    if(sig>0&&sig<32)sigaction(sig,&previous[sig],nullptr);
    if(sig==SIGABRT||sig==SIGTRAP||!info||info->si_code<=0)raise(sig);
}
} // namespace detail

// Once, at the start of main (before threads): every thread inherits the handlers; the alternate stack is per thread, so only
// the main thread has one (a stack overflow elsewhere still dies without a line).
inline void install(){
    if(pipe2(detail::probe,O_CLOEXEC|O_NONBLOCK)!=0)detail::probe[0]=detail::probe[1]=-1;
    stack_t ss{};ss.ss_sp=detail::altStack;ss.ss_size=sizeof(detail::altStack);ss.ss_flags=0;sigaltstack(&ss,nullptr);
    struct sigaction sa{};sa.sa_sigaction=detail::handler;sa.sa_flags=SA_SIGINFO|SA_ONSTACK;sigemptyset(&sa.sa_mask);
    for(int sig:{SIGSEGV,SIGBUS,SIGFPE,SIGILL,SIGABRT,SIGTRAP})sigaction(sig,&sa,&detail::previous[sig]);
}
} // namespace worker_crash
