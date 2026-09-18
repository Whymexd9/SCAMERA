static void injectGpuFault(unsigned calls);
#define HEXQUAD_TEST_EXECUTED injectGpuFault
#define HEXQUAD_ENABLE_GPU 1
#define main hex_speed_fixtures_main
#include "check_vivo_hexquad_speed.cpp"
#undef main
static int faultMode=0;
static void injectGpuFault(unsigned calls){
    if(faultMode==1&&calls==2)glEnable(0xffffffffu); // deliberate GL_INVALID_ENUM
    if(faultMode==2&&calls==1){
        GLint buffer=0;glGetIntegeri_v(GL_SHADER_STORAGE_BUFFER_BINDING,0,&buffer);assert(buffer!=0);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,GLuint(buffer));
        std::vector<uint16_t> wrong(296*304,64);glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(wrong.size()*2),wrong.data());
    }
}
int main(int argc,char** argv){
    if(argc!=5)return 2;
    for(int red=0;red<4;++red)for(int mode=0;mode<3;++mode)for(bool blend:{false,true}){
        std::ostringstream quiet;auto* previous=std::cout.rdbuf(quiet.rdbuf());
        fixture(argv[1],red,mode,blend);
        std::cout.rdbuf(previous);
        fixture(argv[2],red,mode,blend,296,304,true);
    }
    faultMode=1;fixture(argv[3],3,2,true,296,304,true);
    faultMode=2;fixture(argv[4],3,2,true,296,304,true);
    return 0;
}
