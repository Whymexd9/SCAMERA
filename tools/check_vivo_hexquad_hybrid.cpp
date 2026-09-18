#define HEXQUAD_TEST_INPUT_IMMUTABLE 1
#define main speed_main
#include "check_vivo_hexquad_speed.cpp"
#undef main
int main(int argc,char** argv){
    if(argc!=3&&argc!=4)return 2;
    std::ostringstream quiet;auto* previous=std::cout.rdbuf(quiet.rdbuf());
    for(int red=0;red<4;++red)for(int mode=0;mode<3;++mode)for(bool blend:{false,true}){
        if(argc==4&&(red!=3||mode==0||!blend))continue;
        fixture(argv[1],red,mode,blend);
        // Host has no GLES in this check: exercise the real hybrid prefetch
        // with CPU postprocessing fallback, still byte-identical to serial CPU.
        fixture(argv[2],red,mode,blend,296,304,true);
    }
    std::cout.rdbuf(previous);
    assert(quiet.str().find("input_prefetch=1")!=std::string::npos);
    std::puts("Hybrid: all CFA and output modes; inference owns immutable input; CPU fallback completed");
}
