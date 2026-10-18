#pragma once
// P220 (roadmap stability 8): the merge worker's progress, for the app's watchdog and its processing ring. Before it the app
// waited 900-3600 s for a worker that hung, the worker's own alarm fired at 840 s, and the conservative retry that followed could
// wait as long again (~28 min with the FULLRES shutter held). A single pass may legitimately be silent for minutes (a 245 s
// native merge before P204), so the app cannot judge a worker by the gaps between its log lines: it judges it by this.
// The merge calls pass() at each stage and step() / at() as its units complete (alignment groups, GPU strips read back, Mochi
// frames, kept-burst chunks), tick() for work that completes on a helper thread (a GPU program built ahead); FenceWait marks a
// thread waiting for the GPU (the strip readback's 120 s fence and the map behind it). A heartbeat thread prints every 5 s
//   HYBRID PROGRESS pass=<name> done=<k>/<n> seq=<events> slowest=<ms>[ fence=<ms>]
// seq counts every pass start, step and tick (the app: no change for max(90 s, 4 x slowest) = stuck), slowest is the longest
// time between two events so far (the current gap excluded), fence how long a thread has been waiting for the GPU (a hung GPU:
// the app skips its conservative retry). The heartbeat only reads these counters and logs: the merge and its output are the same.
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace scam_progress {
struct State {
    std::mutex lock;                 // pass / done / total / seq / the gaps change together (a few hundred events a merge)
    const char* pass="start";        // a string literal: printed by the heartbeat thread at any time
    long long done=0,total=0,seq=0;
    long long lastNs=0,slowestNs=0;
    std::atomic<int> fenceDepth{0};
    std::atomic<long long> fenceSinceNs{0};
};
inline State& state(){static State s;return s;}
inline long long nowNs(){
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
namespace detail {
inline void event(State& s){ // under s.lock
    const long long now=nowNs();
    if(s.lastNs>0)s.slowestNs=std::max(s.slowestNs,now-s.lastNs);
    s.lastNs=now;++s.seq;
}
}
// A new stage of `total` units (0: a stage without units, its start is the progress).
inline void pass(const char* name,long long total=0){
    State& s=state();std::lock_guard<std::mutex> l(s.lock);
    s.pass=name;s.done=0;s.total=std::max(0LL,total);detail::event(s);
}
// k more units of the stage done.
inline void step(long long k=1){State& s=state();std::lock_guard<std::mutex> l(s.lock);s.done+=k;detail::event(s);}
// `done` units of the stage done (the GPU merge: output rows read back).
inline void at(long long done){State& s=state();std::lock_guard<std::mutex> l(s.lock);s.done=done;detail::event(s);}
// Work that completed outside the stage's units (another thread): progress, the stage's counter unchanged.
inline void tick(){State& s=state();std::lock_guard<std::mutex> l(s.lock);detail::event(s);}
// A thread waits for the GPU while this lives (nested / several threads: since the first one began).
struct FenceWait {
    FenceWait(){State& s=state();if(s.fenceDepth.fetch_add(1)==0)s.fenceSinceNs=nowNs();}
    ~FenceWait(){state().fenceDepth.fetch_sub(1);}
    FenceWait(const FenceWait&)=delete;FenceWait& operator=(const FenceWait&)=delete;
};
// The heartbeat line of the current state.
inline std::string line(){
    State& s=state();
    char text[224];int n;
    {
        std::lock_guard<std::mutex> l(s.lock);
        n=std::snprintf(text,sizeof(text),"HYBRID PROGRESS pass=%s done=%lld/%lld seq=%lld slowest=%lld",s.pass,s.done,s.total,s.seq,
            s.slowestNs/1000000);
    }
    if(s.fenceDepth.load()>0&&n>0&&n<int(sizeof(text)))
        std::snprintf(text+n,sizeof(text)-size_t(n)," fence=%lld",std::max(0LL,nowNs()-s.fenceSinceNs.load())/1000000);
    return text;
}
// The heartbeat period: 5 s; SCAM_PROGRESS_MS in a replay (0: none).
inline int periodMs(){
    const char* e=std::getenv("SCAM_PROGRESS_MS");
    return e?std::clamp(std::atoi(e),0,600000):5000;
}
// The heartbeat of a merge job: line() every period (the first one a period after the start), until stop() or the end of scope.
class Heartbeat {
public:
    Heartbeat(std::function<void(const std::string&)> out,int periodMs):out_(std::move(out)){
        if(periodMs<=0)return;
        // a thread the system refuses costs the heartbeat only (the app then watches the 840 s alarm, as before P220)
        try{thread_=std::thread([this,periodMs]{
            std::unique_lock<std::mutex> l(lock_);
            while(!cv_.wait_for(l,std::chrono::milliseconds(periodMs),[this]{return stopping_;})){
                l.unlock();out_(line());l.lock();
            }
        });}catch(const std::exception&){}
    }
    void stop(){
        {std::lock_guard<std::mutex> l(lock_);stopping_=true;}
        cv_.notify_all();
        if(thread_.joinable())thread_.join();
    }
    ~Heartbeat(){stop();}
    Heartbeat(const Heartbeat&)=delete;Heartbeat& operator=(const Heartbeat&)=delete;
private:
    std::function<void(const std::string&)> out_;
    std::mutex lock_;std::condition_variable cv_;bool stopping_=false;
    std::thread thread_;
};
// P220 replay test of the app's watchdog (the app never sets it): SCAM_STALL_STRIP=<strip>,<seconds>[,fence] holds the GPU merge
// before the readback of that strip (0 = the first), inside the fence wait with ",fence". Parsed once.
struct Stall { int strip=-1,seconds=0;bool fence=false; };
inline const Stall& stall(){
    static const Stall s=[]{
        Stall r;const char* e=std::getenv("SCAM_STALL_STRIP");
        if(!e)return r;
        int strip=-1,seconds=0;char fence[8]={0};
        const int got=std::sscanf(e,"%d,%d,%7s",&strip,&seconds,fence);
        if(got>=2&&strip>=0&&seconds>0){r.strip=strip;r.seconds=std::min(seconds,3600);r.fence=got==3&&std::string(fence)=="fence";}
        return r;
    }();
    return s;
}
inline void stallAt(int strip){
    const Stall& s=stall();
    if(s.strip!=strip)return;
    std::fprintf(stderr,"HYBRID STALL TEST: strip %d held %d s%s\n",strip,s.seconds,s.fence?" in the fence wait":"");
    if(s.fence){FenceWait wait;std::this_thread::sleep_for(std::chrono::seconds(s.seconds));}
    else std::this_thread::sleep_for(std::chrono::seconds(s.seconds));
}
} // namespace scam_progress
