#pragma once
#include <algorithm>
#include <condition_variable>
#include <exception>
#include <functional>
#include <mutex>
#include <system_error>
#include <thread>
#include <vector>

namespace vivo_hexquad {
// One bounded team for a capture. Only independent rows run concurrently;
// reductions within a pixel and overlapping tiles keep their original order.
// QNN calls stay on the owner thread. No worker spins while the NPU is busy.
class RowExecutor {
    std::mutex mutex;
    std::condition_variable ready, finished;
    std::vector<std::thread> workers;
    std::function<void(int)> task;
    std::exception_ptr failure;
    size_t generation=0, pending=0;
    int rows=0;
    bool stopping=false;
    void work(size_t index) noexcept {
        try {
            const size_t participants=workers.size()+1;
            const int begin=int(size_t(rows)*index/participants);
            const int end=int(size_t(rows)*(index+1)/participants);
            for(int y=begin;y<end;++y)task(y);
        } catch(...) {
            std::lock_guard<std::mutex> lock(mutex);
            if(!failure)failure=std::current_exception();
        }
    }
    void loop(size_t index) {
        size_t seen=0;
        std::unique_lock<std::mutex> lock(mutex);
        for(;;){
            ready.wait(lock,[&]{return stopping||generation!=seen;});
            if(stopping)return;
            seen=generation;lock.unlock();work(index);lock.lock();
            if(--pending==0)finished.notify_one();
        }
    }
public:
    explicit RowExecutor(unsigned count=0) {
        if(!count)count=std::max(1u,std::min(4u,std::thread::hardware_concurrency()));
        count=std::max(1u,std::min(4u,count));workers.reserve(count-1);
        // Thread resource exhaustion degrades to fewer CPU workers, never to
        // a different reconstruction or an unhandled joinable-thread destructor.
        for(unsigned i=1;i<count;++i){
            try{workers.emplace_back([this,i]{loop(i);});}
            catch(const std::system_error&){break;}
        }
    }
    RowExecutor(const RowExecutor&)=delete;
    RowExecutor& operator=(const RowExecutor&)=delete;
    ~RowExecutor(){
        {std::lock_guard<std::mutex> lock(mutex);stopping=true;}
        ready.notify_all();for(auto& worker:workers)worker.join();
    }
    unsigned size()const{return unsigned(workers.size()+1);}
    void run(int count,const std::function<void(int)>& fn){
        if(count<=0)return;
        if(workers.empty()){for(int y=0;y<count;++y)fn(y);return;}
        {
            std::lock_guard<std::mutex> lock(mutex);
            task=fn;rows=count;failure=nullptr;pending=workers.size();++generation;
        }
        ready.notify_all();work(0);
        std::unique_lock<std::mutex> lock(mutex);
        finished.wait(lock,[&]{return pending==0;});
        task=nullptr;
        if(failure)std::rethrow_exception(failure);
    }
};
template<class Function> inline void independentRows(RowExecutor* team,int count,Function fn){
    if(team)team->run(count,fn);else for(int y=0;y<count;++y)fn(y);
}
} // namespace vivo_hexquad
