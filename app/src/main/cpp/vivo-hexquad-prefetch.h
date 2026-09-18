#pragma once
#include <condition_variable>
#include <exception>
#include <functional>
#include <mutex>
#include <stdexcept>
#include <system_error>
#include <thread>

namespace vivo_hexquad {
// One bounded CPU preparation job. Its owner alone calls NPU and GLES.
// wait() is also the barrier before reusing the shared CPU row team.
class TilePreparation {
    std::mutex mutex;
    std::condition_variable ready,finished;
    std::function<void()> task;
    std::exception_ptr failure;
    bool pending=false,stopping=false;
    std::thread worker;
    void loop(){
        std::unique_lock<std::mutex> lock(mutex);
        for(;;){
            ready.wait(lock,[&]{return stopping||pending;});
            if(stopping)return;
            lock.unlock();
            std::exception_ptr error;
            try{task();}catch(...){error=std::current_exception();}
            lock.lock();failure=error;task=nullptr;pending=false;finished.notify_all();
        }
    }
public:
    explicit TilePreparation(bool enabled=true){
        if(enabled)try{worker=std::thread([this]{loop();});}catch(const std::system_error&){}
    }
    TilePreparation(const TilePreparation&)=delete;
    TilePreparation& operator=(const TilePreparation&)=delete;
    bool enabled()const{return worker.joinable();}
    void submit(std::function<void()> fn){
        std::lock_guard<std::mutex> lock(mutex);
        if(!enabled()||pending||failure)throw std::logic_error("Preparation slot unavailable");
        task=std::move(fn);pending=true;ready.notify_one();
    }
    void wait(){
        std::unique_lock<std::mutex> lock(mutex);
        finished.wait(lock,[&]{return !pending;});
        auto error=failure;failure=nullptr;
        if(error)std::rethrow_exception(error);
    }
    ~TilePreparation(){
        // On NPU/GPU failure the producer must finish BEFORE its buffers, LUT,
        // guides and row team leave scope. A destructor never rethrows its error.
        {std::unique_lock<std::mutex> lock(mutex);finished.wait(lock,[&]{return !pending;});stopping=true;}
        ready.notify_one();if(worker.joinable())worker.join();
    }
};
} // namespace vivo_hexquad
