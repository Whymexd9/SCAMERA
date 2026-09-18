// Render production fusion shaders on GLES; check flat-field radiometry and weight response.
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
#include <map>
#include <regex>
static void require(bool b,const char* m){if(!b)throw std::runtime_error(m);}
static GLuint shader(GLenum kind,const std::string& source){
    GLuint id=glCreateShader(kind);const char* s=source.c_str();glShaderSource(id,1,&s,nullptr);glCompileShader(id);
    GLint ok=0;glGetShaderiv(id,GL_COMPILE_STATUS,&ok);
    if(!ok){char log[4096];glGetShaderInfoLog(id,sizeof(log),nullptr,log);std::cerr<<log;}
    require(ok,"shader compile");return id;
}

static std::string source(const std::string& path){
    std::ifstream f("app/src/main/assets/shaders/"+path+".glsl");require(f.good(),"shader source missing");
    std::string s((std::istreambuf_iterator<char>(f)),{});std::smatch m;
    while(std::regex_search(s,m,std::regex("#import ([A-Za-z_]+)")))
        s.replace(m.position(),m.length(),source("utils/import_"+m[1].str()));
    return s;
}
static GLuint program(const std::string& name,const std::map<std::string,std::string>& defs){
    std::string s=source(name);
    for(const auto& kv:defs)s=std::regex_replace(s,std::regex("#define "+kv.first+" [^\\n]*"),"#define "+kv.first+" "+kv.second);
    GLuint vs=shader(GL_VERTEX_SHADER,"#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}");
    GLuint fs=shader(GL_FRAGMENT_SHADER,"#version 300 es\n"+s),p=glCreateProgram();
    glAttachShader(p,vs);glAttachShader(p,fs);glLinkProgram(p);GLint ok;glGetProgramiv(p,GL_LINK_STATUS,&ok);require(ok,"program link");
    glDeleteShader(vs);glDeleteShader(fs);return p;
}
static GLuint texture(int w,int h,const std::vector<float>& data){
    GLuint t;glGenTextures(1,&t);glBindTexture(GL_TEXTURE_2D,t);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA16F,w,h,0,GL_RGBA,GL_FLOAT,data.empty()?nullptr:data.data());return t;
}
static void bind(GLuint p,const char* name,GLuint tex,int slot){
    glActiveTexture(GL_TEXTURE0+slot);glBindTexture(GL_TEXTURE_2D,tex);glUniform1i(glGetUniformLocation(p,name),slot);
}
static void render(GLuint dst,int w,int h){
    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,dst,0);
    require(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"framebuffer");
    glViewport(0,0,w,h);glDrawArrays(GL_TRIANGLES,0,3);require(glGetError()==GL_NO_ERROR,"render error");
}
int main(){try{
    EGLDisplay d=eglGetDisplay(EGL_DEFAULT_DISPLAY);require(eglInitialize(d,nullptr,nullptr),"eglInitialize");
    require(eglBindAPI(EGL_OPENGL_ES_API),"eglBindAPI");
    const EGLint attr[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_NONE};
    EGLConfig cfg;EGLint count;require(eglChooseConfig(d,attr,&cfg,1,&count)&&count,"config");
    const EGLint pa[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE},ca[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
    EGLSurface surface=eglCreatePbufferSurface(d,cfg,pa);EGLContext ctx=eglCreateContext(d,cfg,EGL_NO_CONTEXT,ca);
    require(eglMakeCurrent(d,surface,surface,ctx),"make current");

    GLuint fbo,vao;glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_FRAMEBUFFER,fbo);
    glGenVertexArrays(1,&vao);glBindVertexArray(vao);glDisable(GL_DITHER);
    const int W=64,H=48;
    GLuint unit=texture(1,1,std::vector<float>(4,1)),base=texture(W,H,{}),packed=texture(W,H,{}),fused=texture(W,H,{}),gain=texture(W,H,{});
    GLuint baseP=program("ltm/exposebayer2",{{"STRLOW","1.0"},{"STRHIGH","1.0"}});
    GLuint fuseP=program("ltm/fusionbayer3",{{"FUSEWEIGHTED","1"}}),mapP=program("ltm/fusionmap",{});
    double largestIdentity=0,changed=0;int cases=0;
    for(float level:{0.f,.001f,.01f,.03f,.18f,.5f,.9f,1.f}){
        GLuint raw=texture(2*W,2*H,std::vector<float>(2*W*2*H*4,level));
        glUseProgram(baseP);bind(baseP,"InputBuffer",raw,0);bind(baseP,"GainMap",unit,1);render(base,W,H);
        float targetGains[2]={};
        for(float boost:{1.f,4.f})for(int target=0;target<2;++target){
            GLuint packP=program("ltm/exposebayer2",{{"FUSEWEIGHTED","1"},{"CURVE","1"},{"STRLOW","1.0"},{"STRHIGH",std::to_string(boost)},{"TARGET",target?"0.8":"0.2"}});
            glUseProgram(packP);bind(packP,"InputBuffer",raw,0);bind(packP,"GainMap",unit,1);bind(packP,"InterpolatedCurve",unit,2);bind(packP,"ShadowMap",unit,3);render(packed,W,H);
            // Flat fields have zero Laplacian detail: the coarsest fusion result
            // is also the expected reconstructed full pyramid (partition of unity).
            glUseProgram(fuseP);bind(fuseP,"normalExpoDiff",packed,0);glUniform1i(glGetUniformLocation(fuseP,"useUpsampled"),0);render(fused,W,H);
            glUseProgram(mapP);bind(mapP,"InputBuffer",fused,0);bind(mapP,"BrBuffer",base,1);render(gain,W,H);
            std::vector<float> out(W*H*4);glReadPixels(0,0,W,H,GL_RGBA,GL_FLOAT,out.data());require(glGetError()==GL_NO_ERROR,"readback");
            for(int i=0;i<W*H;++i){float g=out[i*4];require(std::isfinite(g)&&g>=.99f&&g<=2.01f,"invalid gain on neutral field");
                if(boost==1.f){largestIdentity=std::max(largestIdentity,double(std::abs(g-1)));require(std::abs(g-1)<.005f,"unity exposures changed brightness");}
                require(std::abs(g-out[0])<.005f,"flat field gradient");
            }
            if(boost==4.f)targetGains[target]=out[0];
            glDeleteProgram(packP);++cases;
        }
        changed=std::max(changed,double(std::abs(targetGains[1]-targetGains[0])));glDeleteTextures(1,&raw);
    }
    require(changed>.1,"fusion exposure selection did not affect the gain map");
    std::cout<<"Fusion GLES PASS: "<<cases<<" flat-field fixtures including black/white; unity error="<<largestIdentity<<"; target response="<<changed<<'\n';
    glDeleteProgram(baseP);glDeleteProgram(fuseP);glDeleteProgram(mapP);GLuint tex[]={unit,base,packed,fused,gain};glDeleteTextures(5,tex);
    glDeleteFramebuffers(1,&fbo);glDeleteVertexArrays(1,&vao);
    eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroyContext(d,ctx);eglDestroySurface(d,surface);eglTerminate(d);return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
