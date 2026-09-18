// Render the production fragment shader on GLES; compare against double-precision math.
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>
static void require(bool b,const char* m){if(!b)throw std::runtime_error(m);}
static GLuint shader(GLenum kind,const std::string& source){
    GLuint id=glCreateShader(kind);const char* s=source.c_str();glShaderSource(id,1,&s,nullptr);glCompileShader(id);
    GLint ok=0;glGetShaderiv(id,GL_COMPILE_STATUS,&ok);
    if(!ok){char log[4096];glGetShaderInfoLog(id,sizeof(log),nullptr,log);std::cerr<<log;}
    require(ok,"shader compile");return id;
}
static double decode(double x){return x<=.04045?x/12.92:std::pow((x+.055)/1.055,2.4);}
static double encode(double x){return x<=.0031308?12.92*x:1.055*std::pow(x,1/2.4)-.055;}
static std::array<double,3> reference(const float* rgb,double ev,bool linear){
    std::array<double,3> r{{rgb[0],rgb[1],rgb[2]}};
    if(ev==0)return r;
    if(!linear)for(auto& x:r)x=decode(x);
    const double g=std::exp2(ev),peak=*std::max_element(r.begin(),r.end());
    const double outputPeak=ev>0?g*peak/(1+(g-1)*peak):g*peak;
    for(auto& x:r){x=peak>0?x/peak*outputPeak:0;if(!linear)x=encode(x);}
    return r;
}
int main(){try{
    EGLDisplay d=eglGetDisplay(EGL_DEFAULT_DISPLAY);require(eglInitialize(d,nullptr,nullptr),"eglInitialize");
    require(eglBindAPI(EGL_OPENGL_ES_API),"eglBindAPI");
    const EGLint attr[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_NONE};
    EGLConfig cfg;EGLint count;require(eglChooseConfig(d,attr,&cfg,1,&count)&&count,"config");
    const EGLint pa[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE},ca[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
    EGLSurface surface=eglCreatePbufferSurface(d,cfg,pa);EGLContext ctx=eglCreateContext(d,cfg,EGL_NO_CONTEXT,ca);
    require(eglMakeCurrent(d,surface,surface,ctx),"make current");
    constexpr int W=1024,H=8;std::vector<float> input(W*H*4,1),output(input.size()),previous(input.size(),0);
    for(int y=0;y<H;++y)for(int x=0;x<W;++x){
        const float t=float(x)/(W-1);float* p=&input[(y*W+x)*4];
        switch(y){
        case 0:p[0]=p[1]=p[2]=t;break;
        case 1:p[0]=t;p[1]=p[2]=0;break;
        case 2:p[1]=t;p[0]=p[2]=0;break;
        case 3:p[2]=t;p[0]=p[1]=0;break;
        case 4:p[0]=t;p[1]=t*.37f;p[2]=t*.08f;break;
        case 5:p[0]=p[1]=p[2]=t*.06f;break;
        case 6:p[0]=.04f+t*.06f;p[1]=.2f+t*.55f;p[2]=.05f+t*.22f;break;
        default:p[0]=.8f+t*.2f;p[1]=.2f+t*.1f;p[2]=.7f+t*.25f;break;
        }
    }
    GLuint textures[2],fbo,vao;glGenTextures(2,textures);
    for(int i=0;i<2;++i){glBindTexture(GL_TEXTURE_2D,textures[i]);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA32F,W,H,0,GL_RGBA,GL_FLOAT,i?nullptr:input.data());}
    glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[1],0);
    require(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"float framebuffer");
    glGenVertexArrays(1,&vao);glBindVertexArray(vao);glViewport(0,0,W,H);glDisable(GL_DITHER);
    std::ifstream file("app/src/main/assets/shaders/HexQuad/exposure.glsl");require(file.good(),"production shader missing");
    const std::string body((std::istreambuf_iterator<char>(file)),{});
    const GLuint vs=shader(GL_VERTEX_SHADER,"#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.0-1.0,0,1);}");
    double worst=0;size_t checked=0;
    for(bool linear:{false,true}){
        std::string source=body;if(linear){auto at=source.find("#define LINEARINPUT 0");require(at!=std::string::npos,"define missing");source.replace(at,20,"#define LINEARINPUT 1");}
        GLuint fs=shader(GL_FRAGMENT_SHADER,"#version 300 es\n"+source),program=glCreateProgram();
        glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);GLint ok=0;glGetProgramiv(program,GL_LINK_STATUS,&ok);require(ok,"link");
        glUseProgram(program);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,textures[0]);glUniform1i(glGetUniformLocation(program,"InputBuffer"),0);
        std::fill(previous.begin(),previous.end(),0);
        for(float ev:{-2.f,-1.f,-.5f,-.1f,0.f,.1f,.5f,1.f,2.f}){
            glUniform1f(glGetUniformLocation(program,"exposureEv"),ev);glDrawArrays(GL_TRIANGLES,0,3);glReadPixels(0,0,W,H,GL_RGBA,GL_FLOAT,output.data());
            require(glGetError()==GL_NO_ERROR,"GLES execution");
            for(int i=0;i<W*H;++i){auto expected=reference(&input[i*4],ev,linear);
                for(int c=0;c<3;++c){float v=output[i*4+c];double error=std::abs(v-expected[c]);worst=std::max(worst,error);
                    require(std::isfinite(v)&&v>=0&&v<=1,"finite range");require(error<2e-5,"reference mismatch");
                    require(v+2e-6>=previous[i*4+c],"EV must monotonically brighten");
                    if(ev==0)require(v==input[i*4+c],"zero must be exact identity");
                    if(ev>0)require(v+2e-6>=input[i*4+c],"positive EV darkened a channel");
                    if(ev<0)require(v<=input[i*4+c]+2e-6,"negative EV brightened a channel");
                    ++checked;
                }
            }
            for(int x=0;x<W;++x){const float* p=&output[x*4];require(p[0]==p[1]&&p[1]==p[2],"neutral tint");if(x)require(p[0]>=output[(x-1)*4],"gray ramp monotonicity");}
            if(ev>0)require(std::abs(output[(W-1)*4]-1)<2e-6,"white anchor");
            previous=output;
        }
        glDeleteProgram(program);glDeleteShader(fs);
    }
    std::cout<<"Display exposure PASS: "<<checked<<" channel samples; encoded/linear, -2..+2 EV, exact zero, black/white, neutral and saturated ramps; max error="<<worst<<'\n';
    glDeleteShader(vs);glDeleteVertexArrays(1,&vao);glDeleteFramebuffers(1,&fbo);glDeleteTextures(2,textures);
    eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroyContext(d,ctx);eglDestroySurface(d,surface);eglTerminate(d);return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
