// P220: the merge worker's heartbeat (scam-progress.h), compiled with the merge (scam-hybrid.h) as the worker is. The line the
// app's WorkerWatchdog parses ("HYBRID PROGRESS pass=<name> done=<k>/<n> seq=<events> slowest=<ms>[ fence=<ms>]"): pass / step /
// at / tick move seq, pass resets the units, slowest is the longest gap between two events (the open gap excluded), fence= only
// while a thread waits for the GPU (nested and concurrent waits count from the first); the counters stay exact under concurrent
// steps; the heartbeat prints every period (the first one a period after the start, none after stop(), stop() at once); a
// stalled merge keeps its seq in every line while its heartbeat goes on; SCAM_STALL_STRIP holds exactly the named strip (inside
// the fence wait with ",fence"); SCAM_PROGRESS_MS sets the period (0: none).
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

static int failures=0;
#define CHECK(c) do{if(!(c)){std::fprintf(stderr,"FAIL %s:%d: %s\n",__FILE__,__LINE__,#c);++failures;}}while(0)

struct Parsed { std::string pass;long long done=-1,total=-1,seq=-1,slowest=-1,fence=-1; };
static bool parse(const std::string& line,Parsed& p){
    char pass[64]={0};
    if(std::sscanf(line.c_str(),"HYBRID PROGRESS pass=%63s done=%lld/%lld seq=%lld slowest=%lld",pass,&p.done,&p.total,&p.seq,&p.slowest)!=5)return false;
    p.pass=pass;
    const size_t at=line.find(" fence=");
    p.fence=at==std::string::npos?-1:std::atoll(line.c_str()+at+7);
    return true;
}
static Parsed now(){Parsed p;CHECK(parse(scam_progress::line(),p));return p;}
static void sleepMs(int ms){std::this_thread::sleep_for(std::chrono::milliseconds(ms));}

int main(){
    // ---- the counters
    Parsed p=now();
    CHECK(p.pass=="start"&&p.done==0&&p.total==0&&p.seq==0&&p.slowest==0&&p.fence==-1);
    scam_progress::pass("align",5);
    scam_progress::step(2);
    p=now();
    CHECK(p.pass=="align"&&p.done==2&&p.total==5&&p.seq==2);
    sleepMs(150);
    p=now();
    CHECK(p.seq==2&&p.slowest<150); // the open gap is not a step yet
    scam_progress::step();
    p=now();
    CHECK(p.done==3&&p.seq==3&&p.slowest>=150&&p.slowest<1000);
    const long long slow=p.slowest;
    scam_progress::pass("merge",6144);
    p=now();
    CHECK(p.pass=="merge"&&p.done==0&&p.total==6144&&p.seq==4&&p.slowest==slow);
    scam_progress::at(256);scam_progress::at(512);
    p=now();
    CHECK(p.done==512&&p.seq==6);
    scam_progress::tick();
    p=now();
    CHECK(p.done==512&&p.seq==7&&p.pass=="merge");
    scam_progress::pass("finish",-3);
    p=now();
    CHECK(p.pass=="finish"&&p.total==0);

    // ---- the GPU wait
    {
        scam_progress::FenceWait wait;
        sleepMs(60);
        p=now();
        CHECK(p.fence>=60&&p.fence<2000);
        {
            scam_progress::FenceWait inner; // nested: from the outer one's start
            p=now();
            CHECK(p.fence>=60);
        }
        p=now();
        CHECK(p.fence>=60);
    }
    p=now();
    CHECK(p.fence==-1);
    {
        long long seen=-1;
        std::thread other([&]{scam_progress::FenceWait w;sleepMs(80);});
        sleepMs(30);
        {scam_progress::FenceWait w;sleepMs(10);}
        p=now();seen=p.fence; // the other thread still waits
        other.join();
        CHECK(seen>=30);
        CHECK(now().fence==-1);
    }

    // ---- exact under concurrent steps (8 threads, the native binning's bands and the pool)
    {
        scam_progress::pass("mosaic-bin",80000);
        const long long seq0=now().seq;
        std::vector<std::thread> pool;
        for(int t=0;t<8;++t)pool.emplace_back([]{for(int i=0;i<10000;++i)scam_progress::step();});
        for(auto& t:pool)t.join();
        p=now();
        CHECK(p.done==80000&&p.seq==seq0+80000);
    }

    // ---- the heartbeat thread
    {
        std::mutex lock;std::vector<std::pair<std::string,long long>> lines;
        const auto t0=std::chrono::steady_clock::now();
        auto ms=[&]{return (long long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-t0).count();};
        scam_progress::Heartbeat beat([&](const std::string& l){std::lock_guard<std::mutex> g(lock);lines.emplace_back(l,ms());},50);
        sleepMs(20);
        {std::lock_guard<std::mutex> g(lock);CHECK(lines.empty());} // the first line a period after the start
        sleepMs(250);
        const long long stopAt=ms();
        beat.stop();
        CHECK(ms()-stopAt<40); // stop() does not wait for the period
        const size_t n=lines.size();
        CHECK(n>=3&&n<=6);
        for(const auto& l:lines){Parsed q;CHECK(parse(l.first,q));}
        CHECK(lines.front().second>=45);
        sleepMs(120);
        CHECK(lines.size()==n); // none after stop()
    }
    {
        // a stalled merge: seq stays in every line while the heartbeat goes on; a moving one changes it
        std::vector<std::string> lines;std::mutex lock;
        scam_progress::pass("merge",100);
        scam_progress::Heartbeat beat([&](const std::string& l){std::lock_guard<std::mutex> g(lock);lines.push_back(l);},20);
        sleepMs(130);
        beat.stop();
        CHECK(lines.size()>=4);
        long long first=-1;bool same=true;
        for(const auto& l:lines){Parsed q;CHECK(parse(l,q));if(first<0)first=q.seq;same=same&&q.seq==first&&q.done==0;}
        CHECK(same);
        lines.clear();
        scam_progress::Heartbeat moving([&](const std::string& l){std::lock_guard<std::mutex> g(lock);lines.push_back(l);},20);
        for(int i=1;i<=7;++i){sleepMs(20);scam_progress::at(i*10);}
        moving.stop();
        std::vector<long long> seqs;for(const auto& l:lines){Parsed q;if(parse(l,q))seqs.push_back(q.seq);}
        CHECK(seqs.size()>=3&&seqs.back()>seqs.front());
    }
    {
        scam_progress::Heartbeat none([](const std::string&){std::fprintf(stderr,"FAIL: a heartbeat of period 0 printed\n");std::exit(1);},0);
        sleepMs(30);
    }

    // ---- SCAM_STALL_STRIP (parsed once, at the first use) and SCAM_PROGRESS_MS
    setenv("SCAM_STALL_STRIP","3,1,fence",1);
    {
        const auto t0=std::chrono::steady_clock::now();
        scam_progress::stallAt(2);
        CHECK(std::chrono::steady_clock::now()-t0<std::chrono::milliseconds(200));
        long long during=-1;
        std::thread watch([&]{sleepMs(500);during=now().fence;});
        scam_progress::stallAt(3);
        watch.join();
        CHECK(std::chrono::steady_clock::now()-t0>=std::chrono::milliseconds(1000));
        CHECK(during>=400);
        CHECK(now().fence==-1);
        CHECK(scam_progress::stall().strip==3&&scam_progress::stall().seconds==1&&scam_progress::stall().fence);
    }
    unsetenv("SCAM_PROGRESS_MS");
    CHECK(scam_progress::periodMs()==5000);
    setenv("SCAM_PROGRESS_MS","0",1);
    CHECK(scam_progress::periodMs()==0);
    setenv("SCAM_PROGRESS_MS","250",1);
    CHECK(scam_progress::periodMs()==250);

    if(failures){std::fprintf(stderr,"check_worker_progress: %d failure(s)\n",failures);return 1;}
    std::printf("check_worker_progress: OK (counters, slowest gap, GPU wait, 80000 concurrent steps, heartbeat period / stop, stalled seq, stall hook, period env)\n");
    return 0;
}
