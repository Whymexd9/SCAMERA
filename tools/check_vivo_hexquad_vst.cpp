#include "../app/src/main/cpp/vivo-hexquad-vst.h"
#include <cassert>
#include <fstream>
#include <iostream>
using namespace vivo_hexquad;
int main(int argc, char** argv) {
    // Measured with the pinned stock AArch64 functions, not with this formula.
    const int iso[] = {50,100,400,800};
    const int positions[] = {0,1,1638,8192,16383};
    const int expected[4][5] = {{3236,3276,20948,46398,65535},
        {2343,2376,15976,35419,50033},{1722,1735,8619,18964,26763},
        {1661,1667,6267,13617,19185}};
    const float inverseQuarter[] = {.060211181640625f,.10526856035f,.37218296528f,.72728896141f};
    for (int k = 0; k < 4; ++k) {
        NormalVst v(iso[k]); auto a = v.forward(); auto b = v.inverse();
        assert(std::abs(v.norm-125.07814025878906f)<.00002f);
        for (int j = 0; j < 5; ++j) assert(std::abs(int(a[positions[j]])-expected[k][j])<=1);
        assert(std::abs(b[0][16384]-inverseQuarter[k])<1e-6f);
        for (size_t i = 1; i < Levels; ++i) {
            assert(a[i]>=a[i-1]);
            assert(std::abs(b[0][a[i]] - float(i)/16383.f)<.0002f);
        }
        if (argc == 2) {
            std::string prefix(argv[1]);
            std::vector<uint16_t> stock(a.size());
            std::ifstream f(prefix+"/vst-"+std::to_string(iso[k])+".bin", std::ios::binary);
            assert(f.read(reinterpret_cast<char*>(stock.data()), stock.size()*2));
            int worst=0; for(size_t i=0;i<a.size();++i)worst=std::max(worst,std::abs(int(a[i])-int(stock[i])));
            std::vector<float> stockInverse(65536);
            std::ifstream g(prefix+"/ivst-"+std::to_string(iso[k])+".bin", std::ios::binary);
            assert(g.read(reinterpret_cast<char*>(stockInverse.data()), stockInverse.size()*4));
            float error=0;for(size_t i=0;i<65536;++i)error=std::max(error,std::abs(b[0][i]-stockInverse[i]));
            std::cout<<"ISO "<<iso[k]<<" stock VST max LSB="<<worst<<" IVST max error="<<error<<'\n';
            assert(worst<=1 && error<1e-6f);
        }
    }
    std::cout << "HP9 normal VST stock-reference checks passed\n";
}
