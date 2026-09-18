#include "../app/src/main/cpp/vivo-hexquad-parallel.h"
#include <cassert>
#include <stdexcept>
#include <atomic>
#include <cstdio>
using namespace vivo_hexquad;
int main(){
    for(unsigned threads:{1u,2u,4u,99u}){
        RowExecutor team(threads);assert(team.size()>=1&&team.size()<=4);
        for(int rows:{0,1,2,3,7,224,288,449}){
            std::vector<int> v(rows,0);
            for(int repeat=0;repeat<40;++repeat)team.run(rows,[&](int y){++v[y];});
            for(int n:v)assert(n==40);
        }
        // An exception on any worker reaches the owner after the barrier;
        // the same team remains usable and every other task has completed.
        for(int fail:{0,3,7}){
            std::atomic<int> active{0};bool rejected=false;
            try{team.run(8,[&](int y){++active;--active;if(y==fail)throw std::runtime_error("expected");});}
            catch(const std::runtime_error&){rejected=true;}
            assert(rejected&&active==0);
            std::vector<int> v(8,0);team.run(8,[&](int y){v[y]=y+1;});
            for(int y=0;y<8;++y)assert(v[y]==y+1);
        }
    }
    std::puts("Bounded row team: once-only coverage, repeated barriers, exception forwarding and recovery PASS");
}
