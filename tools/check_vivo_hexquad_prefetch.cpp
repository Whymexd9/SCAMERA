#include "../app/src/main/cpp/vivo-hexquad-prefetch.h"
#include "../app/src/main/cpp/vivo-hexquad-parallel.h"
#include <atomic>
#include <cassert>
#include <future>
#include <cstdio>
using namespace vivo_hexquad;
int main(){
    RowExecutor team(4);
    TilePreparation queue;assert(queue.enabled());
    // Deterministic rendezvous proves independent owner/producer progress,
    // without a timing assertion that could pass by chance on a slow host.
    std::promise<void> entered,release;auto gate=release.get_future();
    queue.submit([&]{entered.set_value();gate.wait();});
    entered.get_future().wait();release.set_value();queue.wait();
    std::vector<int> rows(288);
    for(int i=0;i<100;++i){
        queue.submit([&]{team.run(288,[&](int y){++rows[y];});});queue.wait();
        team.run(288,[&](int y){++rows[y];});
    }
    for(int n:rows)assert(n==200);
    for(int fail:{0,145,287}){
        queue.submit([&]{team.run(288,[&](int y){if(y==fail)throw std::runtime_error("packing failed");});});
        bool rejected=false;try{queue.wait();}catch(const std::runtime_error&){rejected=true;}assert(rejected);
        queue.submit([&]{team.run(288,[&](int y){rows[y]=y;});});queue.wait();
        for(int y=0;y<288;++y)assert(rows[y]==y);
    }
    // Destruction on an owner exception joins the in-flight producer, including
    // its own error, so RAW/LUT/row-team captures can safely leave scope.
    std::atomic<bool> completed{false};
    try{
        TilePreparation dying;
        dying.submit([&]{team.run(288,[&](int y){rows[y]=y+1;});completed=true;throw std::runtime_error("producer error");});
        throw std::runtime_error("NPU failed");
    }catch(const std::runtime_error&){}
    assert(completed);
    TilePreparation disabled(false);assert(!disabled.enabled());disabled.wait();
    std::puts("Preparation: overlap rendezvous, shared-team barriers, errors, recovery, owner failure and disabled path PASS");
}
