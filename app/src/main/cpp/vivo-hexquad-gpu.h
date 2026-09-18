#pragma once
#include "vivo-hexquad-detail.h"
#include "vivo-hexquad-vst.h"
#include <string>
#include <stdexcept>
#include <cstring>
#include <limits>
#if defined(__ANDROID__) || defined(HEXQUAD_ENABLE_GPU)
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include "vivo-hexquad-gpu-shaders.h"
#endif

namespace vivo_hexquad {
template<class Source> class GpuPost {
#if defined(__ANDROID__) || defined(HEXQUAD_ENABLE_GPU)
    const Source& b;
    EGLDisplay display=EGL_NO_DISPLAY;
    EGLContext context=EGL_NO_CONTEXT;
    EGLSurface surface=EGL_NO_SURFACE;
    GLuint buffers[8]{},programs[3]{};
    size_t capacity[8]{};
    bool hybrid;
    void glCheck(const char* where){GLenum error=glGetError();if(error!=GL_NO_ERROR)throw std::runtime_error(std::string(where)+" GL error="+std::to_string(error));}
    void cleanup() noexcept {
        if(display!=EGL_NO_DISPLAY){
            if(context!=EGL_NO_CONTEXT&&eglMakeCurrent(display,surface,surface,context)){
                for(auto p:programs)if(p)glDeleteProgram(p);
                glDeleteBuffers(8,buffers);
                eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
            }
            if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);
            if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
            eglTerminate(display);
        }
    }
    GLuint compile(const char* body){
        GLuint shader=glCreateShader(GL_COMPUTE_SHADER);const char* sources[]={GpuCommon,body};
        glShaderSource(shader,2,sources,nullptr);glCompileShader(shader);GLint ok=0;glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
        if(!ok){char msg[2048]{};glGetShaderInfoLog(shader,sizeof(msg),nullptr,msg);glDeleteShader(shader);throw std::runtime_error(std::string("GPU shader: ")+msg);}
        GLuint program=glCreateProgram();glAttachShader(program,shader);glLinkProgram(program);glDeleteShader(shader);glGetProgramiv(program,GL_LINK_STATUS,&ok);
        if(!ok){char msg[2048]{};glGetProgramInfoLog(program,sizeof(msg),nullptr,msg);glDeleteProgram(program);throw std::runtime_error(std::string("GPU link: ")+msg);}
        return program;
    }
    void upload(int slot,const void* data,size_t bytes,GLenum usage=GL_STATIC_DRAW){
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[slot]);
        if(capacity[slot]!=bytes){glBufferData(GL_SHADER_STORAGE_BUFFER,GLsizeiptr(bytes),data,usage);capacity[slot]=bytes;}
        else if(data)glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(bytes),data);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,GLuint(slot),buffers[slot]);glCheck("GPU buffer");
    }
    void uniforms(GLuint program,int ox,int oy,int gx,int gy,int gw,int gh){
        glUseProgram(program);
        auto i=[&](const char* n,int v){glUniform1i(glGetUniformLocation(program,n),v);};
        auto f=[&](const char* n,float v){glUniform1f(glGetUniformLocation(program,n),v);};
        glUniform2i(glGetUniformLocation(program,"imageSize"),b.w,b.h);
        glUniform2i(glGetUniformLocation(program,"origin"),ox,oy);
        glUniform2i(glGetUniformLocation(program,"guideOrigin"),gx,gy);
        glUniform2i(glGetUniformLocation(program,"guideSize"),gw,gh);
        i("red",b.red);i("scale",b.scale);i("fullOutput",b.fullResolution);i("hybrid",hybrid);
        glUniform3f(glGetUniformLocation(program,"neutral"),b.neutral[0],b.neutral[1],b.neutral[2]);
        f("black",b.black);f("white",b.white);f("luma",b.luma);f("chroma",b.chroma);f("textureStrength",b.texture);
        const NormalVst physical(b.iso);f("shot",physical.shot);f("variance",physical.variance);
    }
    void dispatch(GLuint program,int w,int h,int ox,int oy,int gx,int gy,int gw,int gh){
        uniforms(program,ox,oy,gx,gy,gw,gh);glDispatchCompute(GLuint((w+7)/8),GLuint((h+7)/8),1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);glCheck("GPU dispatch");
    }
public:
    std::string renderer;
    GpuPost(const Source& source,const TetraDetailReference<Source>* reference,const IvstLuts& inverse):b(source),hybrid(reference!=nullptr){
        try{
            display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
            if(display==EGL_NO_DISPLAY||!eglInitialize(display,nullptr,nullptr)||!eglBindAPI(EGL_OPENGL_ES_API))throw std::runtime_error("EGL initialization unavailable");
            const EGLint configAttrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
            EGLConfig config{};EGLint count=0;
            if(!eglChooseConfig(display,configAttrs,&config,1,&count)||count!=1)throw std::runtime_error("No EGL compute configuration");
            const EGLint attrs[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_CONTEXT_MINOR_VERSION,1,EGL_NONE};
            context=eglCreateContext(display,config,EGL_NO_CONTEXT,attrs);
            if(context==EGL_NO_CONTEXT)throw std::runtime_error("Cannot create GLES 3.1 context");
            const EGLint size[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};surface=eglCreatePbufferSurface(display,config,size);
            if(surface==EGL_NO_SURFACE||!eglMakeCurrent(display,surface,surface,context))throw std::runtime_error("Cannot activate GLES context");
            GLint major=0,minor=0,blocks=0,bindings=0;GLint64 maxBuffer=0;
            glGetIntegerv(GL_MAJOR_VERSION,&major);glGetIntegerv(GL_MINOR_VERSION,&minor);
            glGetIntegerv(GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS,&blocks);glGetIntegerv(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS,&bindings);glGetInteger64v(GL_MAX_SHADER_STORAGE_BLOCK_SIZE,&maxBuffer);
            if(major<3||(major==3&&minor<1)||blocks<8||bindings<8||maxBuffer<GLint64(size_t(b.w)*b.h*2))throw std::runtime_error("Insufficient GLES compute limits");
            const auto* name=glGetString(GL_RENDERER);renderer=name?reinterpret_cast<const char*>(name):"unknown";
            programs[0]=compile(GpuGreen);programs[1]=compile(GpuReference);programs[2]=compile(GpuAssembly);
            glGenBuffers(8,buffers);
            upload(0,b.raw[0],size_t(b.w)*b.h*2);
            float empty[12]{};
            upload(1,reference?reference->coarseData():empty,reference?reference->coarseBytes():sizeof(empty));
            upload(2,b.gain.data(),b.gain.size()*sizeof(float));
            upload(3,nullptr,234*234*sizeof(float),GL_DYNAMIC_DRAW);
            upload(4,nullptr,226*226*4*sizeof(float),GL_DYNAMIC_DRAW);
            upload(5,nullptr,size_t(288*b.scale)*288*b.scale*3*sizeof(float),GL_DYNAMIC_DRAW);
            std::vector<float> luts;for(const auto& channel:inverse)luts.insert(luts.end(),channel.begin(),channel.end());
            upload(6,luts.data(),luts.size()*sizeof(float));
            upload(7,nullptr,size_t(b.fullResolution?448:224)*(b.fullResolution?448:224)*sizeof(float),GL_DYNAMIC_DRAW);
        }catch(...){cleanup();throw;}
    }
    GpuPost(const GpuPost&)=delete;
    ~GpuPost(){cleanup();}
    std::vector<float> render(const std::vector<float>& neural,int ox,int oy){
        if(neural.size()!=size_t(288*b.scale)*288*b.scale*3)throw std::runtime_error("GPU neural shape mismatch");
        const int side=b.fullResolution?448:224;
        std::vector<float> out(size_t(side)*side,std::numeric_limits<float>::quiet_NaN());
        upload(5,neural.data(),neural.size()*sizeof(float),GL_DYNAMIC_DRAW);
        // Detect unwritten output, as on the NPU path. Readback synchronizes this
        // tile before buffers are reused; no implicit GPU/NPU shared ownership.
        upload(7,out.data(),out.size()*sizeof(float),GL_DYNAMIC_DRAW);
        const int gx=std::max(0,ox-5),gy=std::max(0,oy-5),gw=std::min(b.w,ox+229)-gx,gh=std::min(b.h,oy+229)-gy;
        if(hybrid){dispatch(programs[0],gw,gh,ox,oy,gx,gy,gw,gh);dispatch(programs[1],226,226,ox,oy,gx,gy,gw,gh);}
        dispatch(programs[2],side,side,ox,oy,gx,gy,gw,gh);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[7]);
        const void* mapped=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(out.size()*sizeof(float)),GL_MAP_READ_BIT);
        if(!mapped)throw std::runtime_error("GPU readback failed");
        std::memcpy(out.data(),mapped,out.size()*sizeof(float));
        if(!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER))throw std::runtime_error("GPU result storage invalidated");
        glCheck("GPU readback");
        for(float v:out)if(!std::isfinite(v))throw std::runtime_error("GPU output nonfinite or unwritten");
        return out;
    }
#else
public:
    std::string renderer;
    GpuPost(const Source&,const TetraDetailReference<Source>*,const IvstLuts&){throw std::runtime_error("GPU support not compiled");}
    std::vector<float> render(const std::vector<float>&,int,int){throw std::runtime_error("GPU support not compiled");}
#endif
};
} // namespace vivo_hexquad
