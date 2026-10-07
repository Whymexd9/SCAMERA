#pragma once
// P31 (shot speed, research/speed/SHOT_SPEED_PLAN.md W1.2 / W1.4): one persistent pool of worker threads for the hybrid front
// end. While the calling thread runs the CRE alignment (detect / track stay there, in their order), the pool builds what needs
// neither the alignment nor its results: the donor guides of the whole burst and the base-only stages (Shasta, Bento, F6 base,
// RAW CA). Every task computes exactly what the serial code computed (the same functions on the same data); only the thread
// and the time change.
#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <exception>
#include <functional>
#include <future>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

namespace vivo_nice {

class NicePool {
    std::mutex lock;
    std::condition_variable wake;
    std::deque<std::function<void()>> queue;
    std::vector<std::thread> threads;
    static bool& workerFlag(){static thread_local bool flag=false;return flag;}
    explicit NicePool(int n){
        for(int i=0;i<n;++i){
            try{threads.emplace_back([this]{
                workerFlag()=true;
                for(;;){
                    std::function<void()> task;
                    {std::unique_lock<std::mutex> l(lock);wake.wait(l,[&]{return !queue.empty();});task=std::move(queue.front());queue.pop_front();}
                    try{task();}catch(...){} // submit() and rows() keep their own exceptions; nothing may end the thread
                }
            });}catch(const std::exception&){break;} // fewer threads: the queue is shared
        }
    }
public:
    // Hardware threads - 2, 1..6: the calling thread aligns meanwhile and the camera preview keeps running in the app (replays on
    // the OPPO with the preview on: 6 threads aligned as fast as 7, the CRE thread less often slowed). Never destroyed: the threads
    // wait for work until the process exits (no join at exit, no task outliving its data: every caller waits for its own tasks).
    static NicePool& get(){static NicePool* pool=new NicePool(std::clamp(int(std::thread::hardware_concurrency())-2,1,6));return *pool;}
    // True on a pool thread: mergeRowBands then spreads its rows over the pool instead of starting threads of its own.
    static bool inWorker(){return workerFlag();}
    int size() const {return int(threads.size());}
    // A task at the back of the queue; its result (or exception) through the future. A task must not wait for another task.
    template<class F> auto submit(F&& f)->std::future<decltype(f())>{
        using R=decltype(f());
        auto task=std::make_shared<std::packaged_task<R()>>(std::forward<F>(f));
        std::future<R> result=task->get_future();
        if(threads.empty()){(*task)();return result;}
        {std::lock_guard<std::mutex> l(lock);queue.emplace_back([task]{(*task)();});}
        wake.notify_one();
        return result;
    }
    // body(y0, y1) over rows [0, h) in chunks of `chunk`, on the pool and on the calling thread; returns when every row is done.
    // The helpers go to the front of the queue (a stage that started finishes before new stages start) and never wait, so a pool
    // task may call this without deadlocking the pool: the caller takes every chunk itself when no thread is free.
    void rows(int h,int chunk,const std::function<void(int,int)>& body){
        if(h<=0)return;
        chunk=std::max(1,chunk);
        struct State {
            std::atomic<int> next{0},done{0};
            std::mutex m;std::condition_variable cv;
            std::exception_ptr error;
        };
        auto st=std::make_shared<State>();
        const std::function<void(int,int)>* fn=&body; // alive while a chunk runs: the caller returns only after the last one
        auto run=[st,fn,h,chunk]{
            for(int y0=st->next.fetch_add(chunk);y0<h;y0=st->next.fetch_add(chunk)){
                const int y1=std::min(h,y0+chunk);
                try{(*fn)(y0,y1);}
                catch(...){std::lock_guard<std::mutex> l(st->m);if(!st->error)st->error=std::current_exception();}
                if(st->done.fetch_add(y1-y0)+(y1-y0)==h){std::lock_guard<std::mutex> l(st->m);st->cv.notify_all();}
            }
        };
        const int helpers=std::min(size(),(h+chunk-1)/chunk-1);
        if(helpers>0){
            // A helper that cannot be queued (no memory) means fewer helpers, never an exception here: the helpers already queued
            // read `fn` only for a chunk they take, and the wait below outlasts every taken chunk.
            try{std::lock_guard<std::mutex> l(lock);for(int i=0;i<helpers;++i)queue.emplace_front(run);}catch(const std::exception&){}
            wake.notify_all();
        }
        run();
        {std::unique_lock<std::mutex> l(st->m);st->cv.wait(l,[&]{return st->done.load()==h;});}
        if(st->error)std::rethrow_exception(st->error);
    }
};

// Tasks of one caller: every task is waited for before the group goes (also when the caller leaves by an exception), so a task
// may use the caller's locals declared before the group.
class NiceTasks {
    struct State { std::mutex m;std::condition_variable cv;int running=0; };
    std::shared_ptr<State> st=std::make_shared<State>();
public:
    template<class F> auto run(F f)->std::future<decltype(f())>{
        auto s=st;
        {std::lock_guard<std::mutex> l(s->m);++s->running;}
        // counted off when f returns (or throws): from then on the task touches no caller data, only its own result
        struct Done { std::shared_ptr<State> s; ~Done(){std::lock_guard<std::mutex> l(s->m);if(--s->running==0)s->cv.notify_all();} };
        try{
            return NicePool::get().submit([s,f=std::move(f)]() mutable {Done done{s};return f();});
        }catch(...){Done undo{s};throw;}
    }
    void wait(){std::unique_lock<std::mutex> l(st->m);st->cv.wait(l,[&]{return st->running==0;});}
    NiceTasks()=default;
    NiceTasks(const NiceTasks&)=delete;
    ~NiceTasks(){wait();}
};

} // namespace vivo_nice
