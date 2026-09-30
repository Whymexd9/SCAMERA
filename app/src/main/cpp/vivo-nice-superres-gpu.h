#pragma once
// GPU (GLES 3.1 compute) version of the super-resolution merge in
// vivo-nice-superres.h: same per-cell arithmetic, one invocation per 2x2 cell.
// The CPU merge took ~4 s of the ~10 s NICE reconstruction on 8 threads; the
// GPU sat idle during the whole worker run. The frames are uploaded in strips
// (only the rows each frame's homography maps into the strip), so the GPU never
// holds the full ~0.5 GB burst. Robustness stays on the CPU (it is cheap and its
// 3x3 erosion needs whole-cell neighbourhoods).
#include "vivo-nice-homography.h"
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

namespace vivo_nice {

struct SuperResGpuInput {
    int w=0,h=0;
    int cfa=0;                        // canonical shift: x+=cfa&1, y+=cfa>>1
    std::array<float,4> black{},inv{};
    std::array<int,4> phaseColor{};
    std::vector<const uint16_t*> frames;           // frame 0 = reference
    std::vector<BackwardHomography> homography;    // per frame (frame 0 unused)
    const std::vector<float>* model=nullptr;       // RGB, w*h*3
    float invScale=1;
};

// Shared by both programs: strip-uploaded frames, canonical CFA shift, black/white
// normalisation and the per-frame backward homography.
static const char* kCommonShader=R"(#version 310 es
precision highp float;
precision highp int;
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=0) readonly buffer Frames{uint frames[];};
uniform ivec2 size;
uniform int frameCount;
uniform uint frameOffset[32];
uniform int frameRow0[32];
uniform int frameRows[32];
uniform vec4 hA[32];
uniform vec4 hB[32];
uniform float upRatio[32];
uniform ivec2 cfaShift;
uniform vec4 black;
uniform vec4 inv;
int reflectCfa(int x,int n){
    for(int i=0;i<8 && (x<0||x>=n);i++){ if(x<0)x=-x; else x=2*(n-1)-x; }
    return clamp(x,0,n-1);
}
float sampleRaw(int f,int x,int y){
    x+=cfaShift.x;y+=cfaShift.y;
    if(x<0||y<0||x>=size.x||y>=size.y){x=reflectCfa(x,size.x);y=reflectCfa(y,size.y);}
    int ry=clamp(y-frameRow0[f],0,frameRows[f]-1);
    uint idx=frameOffset[f]+uint(ry*size.x+x);
    uint word=frames[idx>>1];
    uint v=(idx&1u)==0u?(word&0xFFFFu):(word>>16);
    int phase=((y&1)<<1)|(x&1);
    // Not clipped at black: the merges average signed noise, the result is clipped
    // once. Averaging clipped samples kept the positive bias of the cut-off
    // negative noise: a lifted, magenta (after WB) haze in high-ISO shadows.
    return clamp((float(v)-black[phase])*inv[phase],-0.25,1.0);
}
vec2 origin(int f,int x,int y){
    vec4 a=hA[f],b=hB[f];
    float fx=float(x),fy=float(y);
    float den=b.z*fx+b.w*fy+1.0;
    vec2 p=vec2(a.x*fx+a.y*fy+a.z,a.w*fx+b.x*fy+b.y)/den;
    return p/upRatio[f];
}
)";

// Super-resolution merge (vivo-nice-superres.h), one invocation per 2x2 cell.
static const char* kSuperResShader=R"(
layout(std430,binding=1) readonly buffer Model{float model[];};
layout(std430,binding=8) readonly buffer Robust{float robust[];};
layout(std430,binding=3) writeonly buffer Out{float outRgb[];};
layout(std430,binding=4) writeonly buffer Eff{float eff[];};
uniform int cy0;
uniform int cy1;
uniform ivec4 phaseColor;
uniform int modelY0;
uniform int modelRows;
uniform float invScale;
uniform vec2 noise; // single-frame noise model: slope, offset (RAW units)
const float sigma=0.7;
const float prior=0.02;
const float relativeFloor=0.004;
const float achromatic=0.75;
const float chromaFloor=0.2;
float modelAt(int x,int y,int c){
    int ry=clamp(clamp(y,0,size.y-1)-modelY0,0,modelRows-1);
    return model[(ry*size.x+clamp(x,0,size.x-1))*3+c];
}
float modelBilinear(int mx,int my,float fx,float fy,int c){
    mx=clamp(mx,0,size.x-2);my=clamp(my,0,size.y-2);
    float p0=modelAt(mx,my,c),p1=modelAt(mx+1,my,c),q0=modelAt(mx,my+1,c),q1=modelAt(mx+1,my+1,c);
    return ((p0*(1.0-fx)+p1*fx)*(1.0-fy)+(q0*(1.0-fx)+q1*fx)*fy)*invScale;
}
float k1(float d){ return exp(-d*d/(2.0*sigma*sigma)); }
int roundInt(float v){ return int(floor(v+0.5)); }
float robustAt(int f,int cx,int cy){
    int w2=size.x/2;
    return robust[(f-1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx];
}
void main(){
    int w2=size.x/2;
    int cx=int(gl_GlobalInvocationID.x),cy=cy0+int(gl_GlobalInvocationID.y);
    if(cx>=w2||cy>=cy1)return;
    vec3 num[4];vec3 den[4];float numA[4];float denA[4];float denA2[4];
    for(int q=0;q<4;q++){num[q]=vec3(0.0);den[q]=vec3(prior);numA[q]=0.0;denA[q]=prior;denA2[q]=0.0;}
    for(int q=0;q<4;q++){
        int x=2*cx+(q&1),y=2*cy+(q>>1);
        for(int p=0;p<4;p++){
            int px=p&1,py=p>>1,c=phaseColor[p];
            int bx=((x-px)&~1)+px,by=((y-py)&~1)+py;
            for(int dj=0;dj<=2;dj+=2)for(int di=0;di<=2;di+=2){
                int sx=bx+di,sy=by+dj;
                float kw=k1(float(sx-x))*k1(float(sy-y));
                if(kw<0.01)continue;
                float v=sampleRaw(0,sx,sy);
                if(v>=0.95)continue;
                float m=modelAt(sx,sy,c)*invScale;
                num[q][c]+=kw*(v-m);den[q][c]+=kw;
                float ka=kw*kw;numA[q]+=ka*(v-m)/(m+relativeFloor);denA[q]+=ka;denA2[q]+=ka*ka;
            }
        }
    }
    for(int f=1;f<frameCount;f++){
        float r=robustAt(f,cx,cy);
        if(r<0.02)continue;
        vec2 oc=origin(f,2*cx,2*cy);
        float tx=float(2*cx)-oc.x,ty=float(2*cy)-oc.y;
        int itx=int(floor(tx)),ity=int(floor(ty));
        float mfx=tx-float(itx),mfy=ty-float(ity);
        for(int q=0;q<4;q++){
            float ox=oc.x+float(q&1),oy=oc.y+float(q>>1);
            for(int p=0;p<4;p++){
                int px=p&1,py=p>>1,c=phaseColor[p];
                int sx=2*roundInt((ox-float(px))*0.5)+px,sy=2*roundInt((oy-float(py))*0.5)+py;
                float dx=float(sx)-ox,dy=float(sy)-oy;
                float kw=r*k1(dx)*k1(dy);
                if(kw<0.005)continue;
                float v=sampleRaw(f,sx,sy);
                if(v>=0.95)continue;
                float m=modelBilinear(sx+itx,sy+ity,mfx,mfy,c);
                num[q][c]+=kw*(v-m);den[q][c]+=kw;
                float ka=kw*kw;numA[q]+=ka*(v-m)/(m+relativeFloor);denA[q]+=ka;denA2[q]+=ka*ka;
            }
        }
    }
    int y0=2*cy0;
    for(int q=0;q<4;q++){
        int x=2*cx+(q&1),y=2*cy+(q>>1);
        float rel=numA[q]/denA[q];
        float e=denA[q]-prior;
        float frames=denA2[q]>0.0?max(1.0,e*e/denA2[q]):1.0;
        int o=((y-y0)*size.x+x)*3;
        for(int c=0;c<3;c++){
            float mc=modelAt(x,y,c)*invScale;
            // Colour detail: the model's colour ratios carry the colour of the scene's fine
            // structure only as far as the network kept it; where the merge is clean enough
            // (signal well above the merged noise) the true per-colour residual is taken in
            // full, as a GCam merge does, instead of a quarter of it.
            float snr=mc/sqrt(max(noise.x*max(mc,0.0)+noise.y,1e-9)/frames);
            float a=mix(chromaFloor,achromatic,1.0-smoothstep(10.0,50.0,snr));
            // Where rejection (motion) left only a frame or two, the residual is mostly single-frame
            // noise: fall back towards the model instead (Sabre widens the base frame's kernel
            // there for the same reason).
            float cover=smoothstep(1.3,5.0,frames);
            outRgb[o+c]=max(0.0,mc+cover*(a*(mc+relativeFloor)*rel+(1.0-a)*num[q][c]/den[q][c]));
        }
        eff[(y-y0)*size.x+x]=frames;
    }
}
)";

// Extra-N slot merge (vivo-nice-capture.h): frame 0 is the slot's own frame, the
// others are its extras; same-colour bilinear donors, noise-scaled weights.
static const char* kSlotShader=R"(
layout(std430,binding=5) writeonly buffer SlotOut{uint slotOut[];};
layout(std430,binding=6) writeonly buffer SlotUsed{float slotUsed[];};
uniform int y0;
uniform int y1;
uniform vec2 noise; // slope, offset
float sameColour(int f,int x,int y){
    vec2 o=origin(f,x&~1,y&~1);
    float qx=max(0.0,o.x*0.5),qy=max(0.0,o.y*0.5);
    int ix=int(qx),iy=int(qy);float fx=qx-float(ix),fy=qy-float(iy);
    float v[4];
    for(int k=0;k<4;k++){
        int sx=2*(ix+(k&1))+(x&1),sy=2*(iy+(k>>1))+(y&1);
        for(int i=0;i<4 && sx>size.x-1;i++)sx-=2;
        for(int i=0;i<4 && sy>size.y-1;i++)sy-=2;
        v[k]=sampleRaw(f,sx,sy);
    }
    return (v[0]*(1.0-fx)+v[1]*fx)*(1.0-fy)+(v[2]*(1.0-fx)+v[3]*fx)*fy;
}
void main(){
    int x=int(gl_GlobalInvocationID.x),y=y0+int(gl_GlobalInvocationID.y);
    if(x>=size.x||y>=y1)return;
    float own=sameColour(0,x,y);
    float sigma=sqrt(max(2.0*(noise.x*max(own,0.0)+noise.y),1e-12));
    float sum=own,weight=1.0,used=0.0;
    for(int f=1;f<frameCount;f++){
        float v=sameColour(f,x,y);
        if(v>=0.95||own>=0.95)continue;
        float d=(v-own)/(3.0*sigma);
        float w=exp(-d*d);
        sum+=w*v;weight+=w;used+=w;
    }
    int i=(y-y0)*size.x+x;
    slotOut[i]=uint(clamp(floor(sum/weight*16383.0+0.5),0.0,16383.0));
    slotUsed[i]=used;
}
)";

// Robustness, step 1: per 2x2 cell and donor frame, agreement of the donor's
// cell mean (bilinear at its aligned position) with the reference cell mean,
// against the noise model plus a 4% share of the local 3x3 reference range.
static const char* kRobustShader=R"(
layout(std430,binding=7) writeonly buffer RawR{float rawR[];};
uniform int ry0;
uniform int ry1;
uniform vec2 noise;
float cellMean(int f,int a,int c){
    int w2=size.x/2,h2=size.y/2;
    a=clamp(a,0,w2-1);c=clamp(c,0,h2-1);
    return 0.25*(sampleRaw(f,2*a,2*c)+sampleRaw(f,2*a+1,2*c)+sampleRaw(f,2*a,2*c+1)+sampleRaw(f,2*a+1,2*c+1));
}
void main(){
    int w2=size.x/2;
    int cx=int(gl_GlobalInvocationID.x),cy=ry0+int(gl_GlobalInvocationID.y),f=int(gl_GlobalInvocationID.z)+1;
    if(cx>=w2||cy>=ry1||f>=frameCount)return;
    float m=cellMean(0,cx,cy),lo=1e9,hi=-1e9;
    for(int dj=-1;dj<=1;dj++)for(int di=-1;di<=1;di++){float v=cellMean(0,cx+di,cy+dj);lo=min(lo,v);hi=max(hi,v);}
    vec2 o=origin(f,2*cx,2*cy)*0.5;
    int ix=int(floor(o.x)),iy=int(floor(o.y));
    float fx=o.x-float(ix),fy=o.y-float(iy);
    float g=(cellMean(f,ix,iy)*(1.0-fx)+cellMean(f,ix+1,iy)*fx)*(1.0-fy)+(cellMean(f,ix,iy+1)*(1.0-fx)+cellMean(f,ix+1,iy+1)*fx)*fy;
    float r=0.0;
    if(m<0.9&&g<0.9){
        float var=max(noise.x*max(m,0.0)+noise.y,1e-9)*0.5;
        float range=hi-lo;
        float tol2=9.0*var+(0.04*range)*(0.04*range);
        float d=g-m;
        r=max(0.0,(exp(-d*d/tol2)-0.25)*(1.0/0.75));
    }
    rawR[(f-1)*(ry1-ry0)*w2+(cy-ry0)*w2+cx]=floor(r*255.0+0.5)*(1.0/255.0);
}
)";

// Robustness, step 2: 3x3 minimum (a frame is used only where it agrees all
// around, so moving objects are not partially mixed) times the user merge weight.
static const char* kErodeShader=R"(
layout(std430,binding=7) readonly buffer RawR{float rawR[];};
layout(std430,binding=8) writeonly buffer Robust{float robust[];};
layout(std430,binding=9) buffer Sums{uint sums[];};
uniform int ry0;
uniform int ry1;
uniform int cy0;
uniform int cy1;
uniform float mergeWeight;
void main(){
    int w2=size.x/2;
    int cx=int(gl_GlobalInvocationID.x),cy=cy0+int(gl_GlobalInvocationID.y),f=int(gl_GlobalInvocationID.z)+1;
    if(cx>=w2||cy>=cy1||f>=frameCount)return;
    float lo=1.0;
    for(int dj=-1;dj<=1;dj++)for(int di=-1;di<=1;di++){
        int y=clamp(cy+dj,ry0,ry1-1),x=clamp(cx+di,0,w2-1);
        lo=min(lo,rawR[(f-1)*(ry1-ry0)*w2+(y-ry0)*w2+x]);
    }
    float r=floor(lo*mergeWeight*255.0+0.5)*(1.0/255.0);
    robust[(f-1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx]=r;
    atomicAdd(sums[f],uint(r*255.0+0.5));
}
)";

class SuperResGpu {
    EGLDisplay display=EGL_NO_DISPLAY;
    EGLContext context=EGL_NO_CONTEXT;
    EGLSurface surface=EGL_NO_SURFACE;
    GLuint srProgram=0,slotProgram=0,robustProgram=0,erodeProgram=0,buffers[10]{};
    size_t capacity[10]{};
    void check(const char* where){GLenum e=glGetError();if(e!=GL_NO_ERROR)throw std::runtime_error(std::string("NICE GPU ")+where+" GL error="+std::to_string(e));}
    void cleanup() noexcept {
        if(display==EGL_NO_DISPLAY)return;
        if(context!=EGL_NO_CONTEXT&&eglMakeCurrent(display,surface,surface,context)){
            if(srProgram)glDeleteProgram(srProgram);
            if(slotProgram)glDeleteProgram(slotProgram);
            if(robustProgram)glDeleteProgram(robustProgram);
            if(erodeProgram)glDeleteProgram(erodeProgram);
            glDeleteBuffers(10,buffers);
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        }
        if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);
        if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
        eglTerminate(display);display=EGL_NO_DISPLAY;
    }
    GLuint compile(const char* body){
        GLuint shader=glCreateShader(GL_COMPUTE_SHADER);const char* sources[]={kCommonShader,body};
        glShaderSource(shader,2,sources,nullptr);glCompileShader(shader);
        GLint ok=0;glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
        if(!ok){char msg[2048]{};glGetShaderInfoLog(shader,sizeof(msg),nullptr,msg);glDeleteShader(shader);throw std::runtime_error(std::string("NICE GPU shader: ")+msg);}
        GLuint program=glCreateProgram();glAttachShader(program,shader);glLinkProgram(program);glDeleteShader(shader);
        glGetProgramiv(program,GL_LINK_STATUS,&ok);
        if(!ok){char msg[2048]{};glGetProgramInfoLog(program,sizeof(msg),nullptr,msg);glDeleteProgram(program);throw std::runtime_error(std::string("NICE GPU link: ")+msg);}
        return program;
    }
    void reserve(int slot,size_t bytes){
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[slot]);
        if(capacity[slot]<bytes){glBufferData(GL_SHADER_STORAGE_BUFFER,GLsizeiptr(bytes),nullptr,GL_DYNAMIC_DRAW);capacity[slot]=bytes;}
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,GLuint(slot),buffers[slot]);
    }
    void put(int slot,size_t offset,const void* data,size_t bytes){
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[slot]);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER,GLintptr(offset),GLsizeiptr(bytes),data);
    }
    void get(int slot,void* data,size_t bytes){
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[slot]);
        const void* mapped=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(bytes),GL_MAP_READ_BIT);
        if(!mapped)throw std::runtime_error("NICE GPU readback failed");
        std::memcpy(data,mapped,bytes);
        if(!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER))throw std::runtime_error("NICE GPU storage invalidated");
    }
    // Frame uniforms (normalisation, homographies) of a program.
    void frameUniforms(GLuint program,const SuperResGpuInput& in){
        const int frames=int(in.frames.size());
        glUseProgram(program);
        auto loc=[&](const char* n){return glGetUniformLocation(program,n);};
        glUniform2i(loc("size"),in.w,in.h);
        glUniform1i(loc("frameCount"),frames);
        glUniform2i(loc("cfaShift"),in.cfa&1,in.cfa>>1);
        glUniform4f(loc("black"),in.black[0],in.black[1],in.black[2],in.black[3]);
        glUniform4f(loc("inv"),in.inv[0],in.inv[1],in.inv[2],in.inv[3]);
        std::vector<float> a(size_t(frames)*4),b(size_t(frames)*4),up(frames,1.f);
        for(int f=0;f<frames;++f){
            const auto& m=in.homography[f];
            a[f*4]=m.h[0];a[f*4+1]=m.h[1];a[f*4+2]=m.h[2];a[f*4+3]=m.h[3];
            b[f*4]=m.h[4];b[f*4+1]=m.h[5];b[f*4+2]=m.h[6];b[f*4+3]=m.h[7];up[f]=m.upRatio;
        }
        glUniform4fv(loc("hA"),frames,a.data());glUniform4fv(loc("hB"),frames,b.data());glUniform1fv(loc("upRatio"),frames,up.data());
    }
    // Upload the rows of every frame that output rows [y0,y1) map into
    // (+ kernel, CFA-shift and border-reflection margin).
    void uploadStrip(std::initializer_list<GLuint> programs,const SuperResGpuInput& in,int y0,int y1){
        const int frames=int(in.frames.size()),w=in.w,h=in.h;
        std::vector<GLuint> offsets(frames);std::vector<GLint> row0(frames),rows(frames);
        size_t total=0;
        for(int f=0;f<frames;++f){
            float lo=1e9f,hi=-1e9f;
            for(int y:{y0,y1})for(int x:{0,w-2}){
                DonorPoint p=in.homography[f].project(x,y);
                const float py=p.y/in.homography[f].upRatio;lo=std::min(lo,py);hi=std::max(hi,py);
            }
            int r0=std::max(0,int(std::floor(lo))-6),r1=std::min(h,int(std::ceil(hi))+8);
            if(r1<=r0){r0=0;r1=std::min(h,8);}
            row0[f]=r0;rows[f]=r1-r0;offsets[f]=GLuint(total);total+=size_t(rows[f])*w;
        }
        reserve(0,total*2);
        for(int f=0;f<frames;++f)put(0,size_t(offsets[f])*2,in.frames[f]+size_t(row0[f])*w,size_t(rows[f])*w*2);
        for(GLuint program:programs){
            glUseProgram(program);
            glUniform1uiv(glGetUniformLocation(program,"frameOffset"),frames,offsets.data());
            glUniform1iv(glGetUniformLocation(program,"frameRow0"),frames,row0.data());
            glUniform1iv(glGetUniformLocation(program,"frameRows"),frames,rows.data());
        }
    }
    static void validate(const SuperResGpuInput& in){
        const int frames=int(in.frames.size());
        if(frames<1||frames>32||(in.w&1)||(in.h&1)||int(in.homography.size())!=frames)
            throw std::runtime_error("NICE GPU unsupported burst shape");
    }
public:
    std::string renderer;
    SuperResGpu(){
        try{
            display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
            if(display==EGL_NO_DISPLAY||!eglInitialize(display,nullptr,nullptr)||!eglBindAPI(EGL_OPENGL_ES_API))throw std::runtime_error("EGL unavailable");
            const EGLint configAttrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
            EGLConfig config{};EGLint count=0;
            if(!eglChooseConfig(display,configAttrs,&config,1,&count)||count!=1)throw std::runtime_error("No EGL compute configuration");
            const EGLint attrs[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_CONTEXT_MINOR_VERSION,1,EGL_NONE};
            context=eglCreateContext(display,config,EGL_NO_CONTEXT,attrs);
            if(context==EGL_NO_CONTEXT)throw std::runtime_error("Cannot create GLES 3.1 context");
            const EGLint size[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};surface=eglCreatePbufferSurface(display,config,size);
            if(surface==EGL_NO_SURFACE||!eglMakeCurrent(display,surface,surface,context))throw std::runtime_error("Cannot activate GLES context");
            const auto* name=glGetString(GL_RENDERER);renderer=name?reinterpret_cast<const char*>(name):"unknown";
            srProgram=compile(kSuperResShader);
            slotProgram=compile(kSlotShader);
            robustProgram=compile(kRobustShader);
            erodeProgram=compile(kErodeShader);
            glGenBuffers(10,buffers);check("init");
        }catch(...){cleanup();throw;}
    }
    SuperResGpu(const SuperResGpu&)=delete;
    ~SuperResGpu(){cleanup();}

    // Robustness (per 2x2 cell, per donor frame) and the merge, all on the GPU.
    // robustShare[f]: mean robustness of frame f (for the report).
    void merge(const SuperResGpuInput& in,float noiseSlope,float noiseOffset,float mergeWeight,
               std::vector<float>& out,std::vector<float>& effective,std::vector<double>& robustShare){
        validate(in);
        const int w=in.w,h=in.h,w2=w/2,h2=h/2,frames=int(in.frames.size());
        out.assign(size_t(w)*h*3,0.f);effective.assign(size_t(w)*h,1.f);
        robustShare.assign(frames,1.0);
        frameUniforms(srProgram,in);frameUniforms(robustProgram,in);frameUniforms(erodeProgram,in);
        glUseProgram(robustProgram);glUniform2f(glGetUniformLocation(robustProgram,"noise"),noiseSlope,noiseOffset);
        glUseProgram(erodeProgram);glUniform1f(glGetUniformLocation(erodeProgram,"mergeWeight"),std::clamp(mergeWeight,0.f,1.f));
        std::vector<GLuint> zeros(size_t(std::max(frames,1)),0);
        reserve(9,zeros.size()*4);put(9,0,zeros.data(),zeros.size()*4);
        glUseProgram(srProgram);
        auto loc=[&](const char* n){return glGetUniformLocation(srProgram,n);};
        glUniform4i(loc("phaseColor"),in.phaseColor[0],in.phaseColor[1],in.phaseColor[2],in.phaseColor[3]);
        glUniform1f(loc("invScale"),in.invScale);
        glUniform2f(loc("noise"),noiseSlope,noiseOffset);
        constexpr int stripCells=128; // 256 output rows per dispatch
        const int donors=std::max(1,frames-1);
        for(int cy0=0;cy0<h2;cy0+=stripCells){
            const int cy1=std::min(h2,cy0+stripCells),y0=2*cy0,y1=2*cy1;
            const int ry0=std::max(0,cy0-1),ry1=std::min(h2,cy1+1);
            // Frame rows for the merge and for the robustness margin (one cell each side).
            uploadStrip({srProgram,robustProgram,erodeProgram},in,std::max(0,y0-4),std::min(h,y1+4));
            const int my0=std::max(0,y0-4),my1=std::min(h,y1+5);
            reserve(1,size_t(my1-my0)*w*3*4);put(1,0,in.model->data()+size_t(my0)*w*3,size_t(my1-my0)*w*3*4);
            reserve(7,size_t(donors)*(ry1-ry0)*w2*4);
            reserve(8,size_t(donors)*(cy1-cy0)*w2*4);
            reserve(9,zeros.size()*4);
            if(frames>1){
                glUseProgram(robustProgram);
                glUniform1i(glGetUniformLocation(robustProgram,"ry0"),ry0);glUniform1i(glGetUniformLocation(robustProgram,"ry1"),ry1);
                glDispatchCompute(GLuint((w2+7)/8),GLuint((ry1-ry0+7)/8),GLuint(frames-1));
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                glUseProgram(erodeProgram);
                glUniform1i(glGetUniformLocation(erodeProgram,"ry0"),ry0);glUniform1i(glGetUniformLocation(erodeProgram,"ry1"),ry1);
                glUniform1i(glGetUniformLocation(erodeProgram,"cy0"),cy0);glUniform1i(glGetUniformLocation(erodeProgram,"cy1"),cy1);
                glDispatchCompute(GLuint((w2+7)/8),GLuint((cy1-cy0+7)/8),GLuint(frames-1));
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                check("robustness");
            }
            reserve(3,size_t(y1-y0)*w*3*4);reserve(4,size_t(y1-y0)*w*4);
            glUseProgram(srProgram);
            glUniform1i(loc("cy0"),cy0);glUniform1i(loc("cy1"),cy1);
            glUniform1i(loc("modelY0"),my0);glUniform1i(loc("modelRows"),my1-my0);
            glDispatchCompute(GLuint((w2+7)/8),GLuint((cy1-cy0+7)/8),1);
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
            check("dispatch");
            get(3,out.data()+size_t(y0)*w*3,size_t(y1-y0)*w*3*4);
            get(4,effective.data()+size_t(y0)*w,size_t(y1-y0)*w*4);
            check("readback");
        }
        std::vector<GLuint> sums(zeros.size());
        get(9,sums.data(),sums.size()*4);
        for(int f=1;f<frames;++f)robustShare[f]=double(sums[f])/(255.0*double(w2)*h2);
    }

    // Extra-N slot merge: in.frames[0] is the slot frame (identity homography for
    // the reference slot), the rest its extras. Returns the summed donor weight.
    double slotMerge(const SuperResGpuInput& in,float noiseSlope,float noiseOffset,std::vector<uint16_t>& merged){
        validate(in);
        const int w=in.w,h=in.h;
        merged.resize(size_t(w)*h);
        frameUniforms(slotProgram,in);
        glUniform2f(glGetUniformLocation(slotProgram,"noise"),noiseSlope,noiseOffset);
        constexpr int strip=256;
        std::vector<uint32_t> values;std::vector<float> used;double accepted=0;
        for(int y0=0;y0<h;y0+=strip){
            const int y1=std::min(h,y0+strip);
            uploadStrip({slotProgram},in,y0,y1);
            reserve(5,size_t(y1-y0)*w*4);reserve(6,size_t(y1-y0)*w*4);
            glUseProgram(slotProgram);
            glUniform1i(glGetUniformLocation(slotProgram,"y0"),y0);glUniform1i(glGetUniformLocation(slotProgram,"y1"),y1);
            glDispatchCompute(GLuint((w+7)/8),GLuint((y1-y0+7)/8),1);
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
            check("slot dispatch");
            values.resize(size_t(y1-y0)*w);used.resize(values.size());
            get(5,values.data(),values.size()*4);get(6,used.data(),used.size()*4);
            check("slot readback");
            uint16_t* dst=merged.data()+size_t(y0)*w;
            for(size_t i=0;i<values.size();++i){dst[i]=uint16_t(values[i]);accepted+=used[i];}
        }
        return accepted;
    }
};
} // namespace vivo_nice
