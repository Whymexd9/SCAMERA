#pragma once
// P28 RAW CA correction of a burst (vivo-nice-rawca.h is the RawTherapee CA_correct_RT port it builds on). Two modes
// (HybridTuning::rawCa):
//   1 "base"   CA_correct_RT's auto fit (all passes) or its manual red / blue estimated once on the base frame; the merged RGB's
//              R / B are moved onto G once by that field (correctRgb). Replaces P19's radial shift.
//   2 "frames" the same estimate; then every frame of the burst, base included, gets CA_correct_RT's correction pass with the
//              base frame's fits (one pass per fitted pass) before alignment / merge: G at the R / B sites with directional
//              weights, the colour difference G - C at the shifted positions interpolated back (gradient weights where the
//              change is large, never raising |G - C|, overshoot -> desaturate), and the base frame's avoid-colour-shift
//              factors. On the GPU (GLES 3.1 compute, its own EGL context, created and destroyed before the merge's), the
//              port on the CPU when the GPU fails.
// Green sites are never written; a clipped R / B site keeps its code; an unchanged site keeps its original code.
// The GLSL bodies have no #version line: the worker prepends "#version 310 es", tools/rawca/check_gpu.py "#version 430" (the
// host check runs them through desktop GL and compares with the port).
#include "vivo-nice-rawca.h"
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <chrono>
#include <cstdio>
#include <functional>
#include <stdexcept>
#include <string>

namespace vivo_rawca {

static const char* kRawCaGlslCommon = R"(
precision highp float;
precision highp int;
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=0) buffer Codes{uint codes[];};   // uint16 sensor codes, two per word (row-major, even width)
layout(std430,binding=1) buffer RbA{float rbA[];};      // R / B sites in RT's scale (0..65535), index (y w + x) / 2
layout(std430,binding=2) buffer RbB{float rbB[];};
layout(std430,binding=3) buffer Gsb{float gsv[];};      // G interpolated at the R / B sites (0..1)
layout(std430,binding=4) buffer Tiles{vec4 tiles[];};   // per pass and tile (vblock, hblock): R rows, R cols, B rows, B cols
layout(std430,binding=5) buffer Fac{float fac[];};      // per pass: red plane, blue plane (avoid colour shift)
uniform ivec2 size;      // w, h
uniform int redPhase;    // sensor phase ((y & 1) << 1 | (x & 1)) of red; blue = redPhase ^ 3
uniform vec4 black;      // per phase (codes)
uniform vec4 scale;      // per phase: RT's working scale (code - black -> 0..65535)
uniform vec4 clipCode;   // per phase: a code at or above it keeps its value
uniform ivec4 blocks;    // hblsz, vblsz, factor plane width, factor plane height
uniform ivec2 base;      // first tile of this pass in Tiles, first factor of this pass in Fac
uniform int useFactors;
uniform int rowBase;     // first row of this dispatch
const float eps=1.0e-5;
int phaseAt(int x,int y){return ((y&1)<<1)|(x&1);}
bool isRB(int x,int y){int p=phaseAt(x,y);return p==redPhase||p==(redPhase^3);}
int reflectI(int v,int n){if(v<0)v=-v;if(v>=n)v=2*(n-1)-v;return clamp(v,0,n-1);}
int site(int x,int y){return (y*size.x+x)>>1;}
uint codeAt(int x,int y){uint i=uint(y)*uint(size.x)+uint(x);uint w=codes[i>>1u];return (i&1u)==0u?(w&0xFFFFu):(w>>16u);}
float rtValue(int x,int y){int p=phaseAt(x,y);return max(float(codeAt(x,y))-black[p],0.0)*scale[p];}
// G (0..1) anywhere (reflected at the frame edges as RT's tile border fill): green sites from the codes, R / B sites interpolated
float Gat(int x,int y){x=reflectI(x,size.x);y=reflectI(y,size.y);return isRB(x,y)?gsv[site(x,y)]:rtValue(x,y)/65535.0;}
float Cat(int x,int y){x=reflectI(x,size.x);y=reflectI(y,size.y);return rbA[site(x,y)]/65535.0;}
int rbCol(int y){int p=phaseAt(0,y);return (p==redPhase||p==(redPhase^3))?0:1;}
float sq(float v){return v*v;}
float intp(float a,float b,float c){return a*(b-c)+c;}
)";

// R / B sites to RT's working scale
static const char* kRawCaGlslInit = R"(
void main(){
    int y=int(gl_GlobalInvocationID.y)+rowBase,x=2*int(gl_GlobalInvocationID.x)+rbCol(y);
    if(y>=size.y||x>=size.x)return;
    rbA[site(x,y)]=rtValue(x,y);
}
)";

// G at the R / B sites with RT's directional weights (CA_correct_RT, manual / given-fit branch of the correction pass)
static const char* kRawCaGlslGreen = R"(
void main(){
    int y=int(gl_GlobalInvocationID.y)+rowBase,x=2*int(gl_GlobalInvocationID.x)+rbCol(y);
    if(y>=size.y||x>=size.x)return;
    float c0=Cat(x,y),gu=Gat(x,y-1),gd=Gat(x,y+1),gl=Gat(x-1,y),gr=Gat(x+1,y);
    float t1=eps+abs(gd-gu),t2=eps+abs(gr-gl);
    float wtu=1.0/sq(t1+abs(c0-Cat(x,y-2))+abs(gu-Gat(x,y-3)));
    float wtd=1.0/sq(t1+abs(c0-Cat(x,y+2))+abs(gd-Gat(x,y+3)));
    float wtl=1.0/sq(t2+abs(c0-Cat(x-2,y))+abs(gl-Gat(x-3,y)));
    float wtr=1.0/sq(t2+abs(c0-Cat(x+2,y))+abs(gr-Gat(x+3,y)));
    gsv[site(x,y)]=(wtu*gu+wtd*gd+wtl*gl+wtr*gr)/(wtu+wtd+wtl+wtr);
}
)";

// CA_correct_RT's correction of one R / B site with the shift of its 112 px tile (RT's tile grid: vblock = y / 112 + 1)
static const char* kRawCaGlslCorrect = R"(
float gint(int tx,int ty,ivec4 s,vec2 f){ // s = rows floor, rows ceil, cols floor, cols ceil; f = cols frac, rows frac
    float hf=intp(f.x,Gat(tx+s.w,ty+s.x),Gat(tx+s.z,ty+s.x));
    float hc=intp(f.x,Gat(tx+s.w,ty+s.y),Gat(tx+s.z,ty+s.y));
    return intp(f.y,hc,hf);
}
void main(){
    int y=int(gl_GlobalInvocationID.y)+rowBase,x=2*int(gl_GlobalInvocationID.x)+rbCol(y);
    if(y>=size.y||x>=size.x)return;
    bool red=phaseAt(x,y)==redPhase;
    int hb=min(x/112+1,blocks.x-1),vb=min(y/112+1,blocks.y-1);
    vec4 t=tiles[base.x+vb*blocks.x+hb];
    float sv=red?t.x:t.z,sh=red?t.y:t.w;
    int vf=int(floor(sv)),vc=int(ceil(sv));if(sv<0.0){int q=vf;vf=vc;vc=q;}
    int hf=int(floor(sh)),hc=int(ceil(sh));if(sh<0.0){int q=hf;hf=hc;hc=q;}
    vec2 f=vec2(abs(sh-float(hf)),abs(sv-float(vf)));
    int d0=sv>0.0?2:-2,d1=sh>0.0?2:-2;
    ivec4 s=ivec4(vf,vc,hf,hc);
    float g00=gint(x,y,s,f),g01=gint(x-d1,y,s,f),g10=gint(x,y-d0,s,f),g11=gint(x-d1,y-d0,s,f);
    float c00=Cat(x,y);
    float D00=g00-c00,D01=g01-Cat(x-d1,y),D10=g10-Cat(x,y-d0),D11=g11-Cat(x-d1,y-d0);
    vec2 h=0.5*f;
    float gx=gsv[site(x,y)];
    float old=gx-c00;
    float di=intp(h.y,intp(h.x,D11,D10),intp(h.x,D01,D00));
    float rb=gx-di,o=c00;
    if(abs(rb-c00)<0.25*(rb+c00)){
        if(abs(old)>abs(di))o=rb;
    } else {
        float p0=1.0/(eps+abs(gx-g00)),p1=1.0/(eps+abs(gx-g01)),p2=1.0/(eps+abs(gx-g10)),p3=1.0/(eps+abs(gx-g11));
        di=(p0*D00+p1*D01+p2*D10+p3*D11)/(p0+p1+p2+p3);
        if(abs(old)>abs(di))o=gx-di;
    }
    if(old*di<0.0)o=gx-0.5*(old+di);
    float v=max(0.0,65535.0*o);
    if(useFactors!=0)v*=fac[base.y+(red?0:blocks.z*blocks.w)+(y>>1)*blocks.z+(x>>1)];
    rbB[site(x,y)]=v;
}
)";

// back to codes: a site keeps its code when unchanged or clipped; one word (two sites of a row) per invocation
static const char* kRawCaGlslFinal = R"(
void main(){
    int y=int(gl_GlobalInvocationID.y)+rowBase,k=int(gl_GlobalInvocationID.x);
    if(y>=size.y||2*k>=size.x)return;
    uint i=(uint(y)*uint(size.x)+uint(2*k))>>1u;
    uint w=codes[i];
    uint o[2];o[0]=w&0xFFFFu;o[1]=w>>16u;
    for(int j=0;j<2;j++){
        int x=2*k+j;
        if(!isRB(x,y))continue;
        int p=phaseAt(x,y);
        if(float(o[j])>=clipCode[p])continue;
        float v=rbA[site(x,y)];
        if(v==rtValue(x,y))continue;
        o[j]=uint(clamp(floor(black[p]+v/scale[p]+0.5),0.0,65535.0));
    }
    codes[i]=o[0]|(o[1]<<16u);
}
)";

class RawCaGpu {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    GLuint progInit = 0, progGreen = 0, progCorrect = 0, progFinal = 0, buffers[6]{}, staging = 0;
    size_t capacity[6]{}, stagingCap = 0;
    int w = 0, h = 0, passes = 0, vblsz = 0, hblsz = 0, fW = 0, fH = 0;
    bool factors = false;
    Bayer bayer;
    float clipLevel = 2.f;
    void cleanup() noexcept {
        if (display == EGL_NO_DISPLAY) return;
        if (context != EGL_NO_CONTEXT && eglMakeCurrent(display, surface, surface, context)) {
            for (GLuint p : {progInit, progGreen, progCorrect, progFinal}) if (p) glDeleteProgram(p);
            glDeleteBuffers(6, buffers);
            if (staging) glDeleteBuffers(1, &staging);
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        eglTerminate(display);
        display = EGL_NO_DISPLAY;
    }
    static void check(const char* where) {
        const GLenum e = glGetError();
        if (e != GL_NO_ERROR) throw std::runtime_error(std::string("RAW CA GPU ") + where + " GL error=" + std::to_string(e));
    }
    static GLuint compile(const char* body) {
        const char* sources[] = {"#version 310 es\n", kRawCaGlslCommon, body};
        GLuint shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, 3, sources, nullptr);
        glCompileShader(shader);
        GLint ok = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
        if (!ok) { char msg[2048]{}; glGetShaderInfoLog(shader, sizeof(msg), nullptr, msg); glDeleteShader(shader); throw std::runtime_error(std::string("RAW CA GPU shader: ") + msg); }
        GLuint program = glCreateProgram();
        glAttachShader(program, shader);
        glLinkProgram(program);
        glDeleteShader(shader);
        glGetProgramiv(program, GL_LINK_STATUS, &ok);
        if (!ok) { char msg[2048]{}; glGetProgramInfoLog(program, sizeof(msg), nullptr, msg); glDeleteProgram(program); throw std::runtime_error(std::string("RAW CA GPU link: ") + msg); }
        return program;
    }
    void reserve(int slot, size_t bytes) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers[slot]);
        if (capacity[slot] < bytes) { glBufferData(GL_SHADER_STORAGE_BUFFER, GLsizeiptr(bytes), nullptr, GL_DYNAMIC_DRAW); capacity[slot] = bytes; }
    }
    void bindAll(int a, int b) { // RbA / RbB slots swapped per pass
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, buffers[0]);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, buffers[a]);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, buffers[b]);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, buffers[3]);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 4, buffers[4]);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, buffers[5]);
    }
    void uniforms(GLuint p, int pass) {
        glUseProgram(p);
        glUniform2i(glGetUniformLocation(p, "size"), w, h);
        glUniform1i(glGetUniformLocation(p, "redPhase"), bayer.cfa);
        const auto s = workingScale(bayer);
        float clip[4];
        for (int k = 0; k < 4; ++k) clip[k] = bayer.black[size_t(k)] + clipLevel * (bayer.white - bayer.black[size_t(k)]);
        glUniform4f(glGetUniformLocation(p, "black"), bayer.black[0], bayer.black[1], bayer.black[2], bayer.black[3]);
        glUniform4f(glGetUniformLocation(p, "scale"), s[0], s[1], s[2], s[3]);
        glUniform4f(glGetUniformLocation(p, "clipCode"), clip[0], clip[1], clip[2], clip[3]);
        glUniform4i(glGetUniformLocation(p, "blocks"), hblsz, vblsz, fW, fH);
        glUniform2i(glGetUniformLocation(p, "base"), pass * vblsz * hblsz, pass * 2 * fW * fH);
        glUniform1i(glGetUniformLocation(p, "useFactors"), factors ? 1 : 0);
    }
    // rows in dispatches of 512 (a single long dispatch can trip the GPU hang detection), flushed together
    void dispatch(GLuint p, int columns) {
        const GLint l = glGetUniformLocation(p, "rowBase");
        for (int r = 0; r < h; r += 512) {
            glUniform1i(l, r);
            glDispatchCompute(GLuint((columns + 7) / 8), GLuint((std::min(512, h - r) + 7) / 8), 1);
        }
        glFlush();
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
    }
    // readback of the codes (direct map, else a staging copy, as HybridGpu::get for Adreno's NULL maps)
    void readCodes(uint16_t* out) {
        const size_t bytes = size_t(w) * h * 2;
        constexpr size_t kChunk = size_t(16) << 20;
        for (size_t off = 0; off < bytes; off += kChunk) {
            const size_t n = std::min(kChunk, bytes - off);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers[0]);
            const void* m = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, GLintptr(off), GLsizeiptr(n), GL_MAP_READ_BIT);
            if (m) {
                std::memcpy(reinterpret_cast<char*>(out) + off, m, n);
                if (!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)) throw std::runtime_error("RAW CA GPU storage invalidated");
                continue;
            }
            glGetError();
            if (!staging) glGenBuffers(1, &staging);
            glBindBuffer(GL_COPY_READ_BUFFER, buffers[0]);
            glBindBuffer(GL_COPY_WRITE_BUFFER, staging);
            if (stagingCap < n) { glBufferData(GL_COPY_WRITE_BUFFER, GLsizeiptr(n), nullptr, GL_DYNAMIC_READ); stagingCap = n; }
            glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, GLintptr(off), 0, GLsizeiptr(n));
            glFinish();
            m = glMapBufferRange(GL_COPY_WRITE_BUFFER, 0, GLsizeiptr(n), GL_MAP_READ_BIT);
            if (!m) throw std::runtime_error("RAW CA GPU readback failed");
            std::memcpy(reinterpret_cast<char*>(out) + off, m, n);
            glUnmapBuffer(GL_COPY_WRITE_BUFFER);
        }
    }
public:
    std::string renderer;
    RawCaGpu() {
        try {
            display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
            if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr) || !eglBindAPI(EGL_OPENGL_ES_API)) throw std::runtime_error("EGL unavailable");
            const EGLint configAttrs[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
            EGLConfig config{};
            EGLint count = 0;
            if (!eglChooseConfig(display, configAttrs, &config, 1, &count) || count != 1) throw std::runtime_error("No EGL compute configuration");
            const EGLint attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 1, EGL_NONE};
            context = eglCreateContext(display, config, EGL_NO_CONTEXT, attrs);
            if (context == EGL_NO_CONTEXT) throw std::runtime_error("Cannot create GLES 3.1 context");
            const EGLint size[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
            surface = eglCreatePbufferSurface(display, config, size);
            if (surface == EGL_NO_SURFACE || !eglMakeCurrent(display, surface, surface, context)) throw std::runtime_error("Cannot activate GLES context");
            const auto* name = glGetString(GL_RENDERER);
            renderer = name ? reinterpret_cast<const char*>(name) : "unknown";
            progInit = compile(kRawCaGlslInit);
            progGreen = compile(kRawCaGlslGreen);
            progCorrect = compile(kRawCaGlslCorrect);
            progFinal = compile(kRawCaGlslFinal);
            glGenBuffers(6, buffers);
            check("init");
        } catch (...) { cleanup(); throw; }
    }
    RawCaGpu(const RawCaGpu&) = delete;
    RawCaGpu& operator=(const RawCaGpu&) = delete;
    ~RawCaGpu() { cleanup(); }
    // The burst's estimate: tile tables and factors of every pass (uploaded once).
    void load(const BaseEstimate& e, float clip) {
        bayer = e.bayer;
        clipLevel = clip;
        w = e.bayer.w; h = e.bayer.h;
        if ((w & 1) || (h & 1)) throw std::runtime_error("RAW CA GPU: odd frame size");
        passes = e.passes; vblsz = e.vblsz; hblsz = e.hblsz;
        factors = !e.factors.empty() && int(e.factors.size()) >= passes;
        fW = factors ? e.factors[0].w : 1;
        fH = factors ? e.factors[0].h : 1;
        const std::vector<float> t = tileTables(e);
        reserve(4, t.size() * 4);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, GLsizeiptr(t.size() * 4), t.data());
        if (factors) {
            const size_t plane = size_t(fW) * fH;
            reserve(5, size_t(passes) * 2 * plane * 4);
            for (int p = 0; p < passes; ++p) {
                glBufferSubData(GL_SHADER_STORAGE_BUFFER, GLintptr(size_t(p) * 2 * plane * 4), GLsizeiptr(plane * 4), e.factors[size_t(p)].red.data());
                glBufferSubData(GL_SHADER_STORAGE_BUFFER, GLintptr((size_t(p) * 2 + 1) * plane * 4), GLsizeiptr(plane * 4), e.factors[size_t(p)].blue.data());
            }
        } else reserve(5, 16);
        const size_t sites = size_t(w) * h / 2;
        reserve(0, size_t(w) * h * 2);
        reserve(1, sites * 4);
        reserve(2, sites * 4);
        reserve(3, sites * 4);
        check("load");
    }
    // One frame: codes in -> corrected codes out (out may be in).
    void correct(const uint16_t* in, uint16_t* out) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers[0]);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, GLsizeiptr(size_t(w) * h * 2), in);
        int a = 1, b = 2;
        bindAll(a, b);
        uniforms(progInit, 0);
        dispatch(progInit, w / 2);
        for (int p = 0; p < passes; ++p) {
            bindAll(a, b);
            uniforms(progGreen, p);
            dispatch(progGreen, w / 2);
            uniforms(progCorrect, p);
            dispatch(progCorrect, w / 2);
            std::swap(a, b);
        }
        bindAll(a, b);
        uniforms(progFinal, 0);
        dispatch(progFinal, w / 2);
        check("correct");
        readCodes(out);
    }
};

// Frames mode: every frame (codes in[i], layout of e.bayer) corrected into out[i] (may equal in[i]). GPU first; when it fails,
// the remaining frames on the CPU. Returns a report line.
inline std::string correctFrames(const BaseEstimate& e, const BurstSettings& s, const std::vector<const uint16_t*>& in, const std::vector<uint16_t*>& out) {
    const auto t0 = std::chrono::steady_clock::now();
    auto ms = [](std::chrono::steady_clock::time_point t) { return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count(); };
    size_t done = 0;
    std::string how;
    if (s.gpu) {
        try {
            RawCaGpu gpu;
            gpu.load(e, s.clipLevel);
            const double initMs = ms(t0);
            for (; done < in.size(); ++done) gpu.correct(in[done], out[done]);
            char l[160];
            std::snprintf(l, sizeof(l), "GPU %s (init %.0f ms)", gpu.renderer.c_str(), initMs);
            how = l;
        } catch (const std::exception& error) {
            how = std::string("GPU failed at frame ") + std::to_string(done) + " (" + error.what() + "), CPU for the rest";
        }
    }
    if (done < in.size()) {
        if (how.empty()) how = "CPU";
        for (; done < in.size(); ++done) correctFrameCpu(e, s, in[done], out[done]);
    }
    char l[96];
    std::snprintf(l, sizeof(l), ", %zu frames in %.0f ms", in.size(), ms(t0));
    return how + l;
}

// ---- glue for the hybrid merge (templates: instantiated in vivo-nice-hybrid.h, where HybridInput / HybridTuning are complete)
template<class Tuning> inline BurstSettings burstSettings(const Tuning& t) {
    BurstSettings s;
    s.mode = t.rawCa;
    s.autoCA = t.rawCaAuto != 0;
    s.red = t.rawCaRed;
    s.blue = t.rawCaBlue;
    s.passes = std::clamp(t.rawCaPasses, 1, 5);
    s.avoid = t.rawCaAvoidShift != 0;
    s.gpu = t.rawCaGpu != 0;
    s.clipLevel = t.clipLevel;
    return s;
}
template<class Input> inline Bayer bayerOf(const Input& in) {
    Bayer b;
    b.w = in.w; b.h = in.h; b.cfa = in.cfa; b.white = in.white;
    for (int k = 0; k < 4; ++k) b.black[size_t(k)] = in.black[size_t(k)];
    return b;
}
// Frames mode: every frame of `in` corrected; in.frames[i].raw then points at the corrected copy in `store` (or, for the
// writable sub-frames of a colour-block mosaic, `inPlace`, at the corrected original). False: nothing changed.
template<class Input, class Tuning>
inline bool hybridRawCaFrames(Input& in, const Tuning& t, const std::function<void(const std::string&)>& report,
                              std::vector<std::vector<uint16_t>>& store, bool inPlace) {
    if (in.frames.empty()) return false;
    const BurstSettings s = burstSettings(t);
    const BaseEstimate e = estimateBase(bayerOf(in), in.frames[0].raw, s);
    if (!e.ok) {
        report("HYBRID RAW CA (frames): not corrected (" + e.why + ");" + e.summary + ", " + std::to_string(int(e.ms)) + " ms");
        return false;
    }
    std::vector<const uint16_t*> src;
    std::vector<uint16_t*> dst;
    if (!inPlace) store.assign(in.frames.size(), {});
    for (size_t i = 0; i < in.frames.size(); ++i) {
        src.push_back(in.frames[i].raw);
        if (inPlace) dst.push_back(const_cast<uint16_t*>(in.frames[i].raw));
        else { store[i].resize(size_t(in.w) * in.h); dst.push_back(store[i].data()); }
    }
    const std::string how = correctFrames(e, s, src, dst);
    for (size_t i = 0; i < in.frames.size(); ++i) in.frames[i].raw = dst[i];
    char head[120];
    std::snprintf(head, sizeof(head), "HYBRID RAW CA (frames): %s %d pass(es)%s, estimate %.0f ms;", e.manual ? "manual" : "auto", e.passes,
                  s.avoid ? " + avoid colour shift" : "", e.ms);
    report(head + e.summary + "; " + how);
    return true;
}
// Base mode, before the merge: the estimate (ok = false: nothing to apply).
template<class Input, class Tuning>
inline BaseEstimate hybridRawCaEstimate(const Input& in, const Tuning& t, const std::function<void(const std::string&)>& report) {
    if (in.frames.empty()) return {};
    BurstSettings s = burstSettings(t);
    const BaseEstimate e = estimateBase(bayerOf(in), in.frames[0].raw, s);
    char head[96];
    std::snprintf(head, sizeof(head), "HYBRID RAW CA (base): %s %d pass(es), %.0f ms;", e.manual ? "manual" : "auto", e.passes, e.ms);
    report(std::string(head) + (e.ok ? std::string() : " not corrected (" + e.why + ");") + e.summary);
    return e;
}
// Base mode, after the merge: the merged RGB (canonical geometry, grid g) moved onto G by the base frame's field, one correctRgb
// per fitted pass in order (as RT corrects the RAW: each pass is partial, its guards keep |G - C| from growing). Synthetic merged
// RGB with known CA (tools/rawca/check_rgb.py, outer-ring edges, 12-bit codes): R 257 -> 72 sequential against 104 with the
// first pass's field and 99 with the passes' fields summed; B 171 -> 86 / 85 / 82.
inline void hybridRawCaApply(std::vector<float>& rgb, int w, int h, int grid, const BaseEstimate& e, bool avoid,
                             const std::function<void(const std::string&)>& report) {
    const auto t0 = std::chrono::steady_clock::now();
    RgbField f;
    f.manual = e.manual;
    f.cared = e.red;
    f.cablue = e.blue;
    f.W = e.bayer.w;
    f.H = e.bayer.h;
    const float g = std::max(e.bayer.wb[1], 1e-6f);
    long n = 0;
    for (int p = 0; p < (e.manual ? 1 : int(e.fits.size())); ++p) {
        if (!e.manual) f.fits = {e.fits[size_t(p)]};
        n += correctRgb(rgb, w, h, grid, e.bayer.cfa & 1, e.bayer.cfa >> 1, f, e.bayer.wb[0] / g, e.bayer.wb[2] / g, 0, avoid);
    }
    char l[200];
    std::snprintf(l, sizeof(l), "HYBRID RAW CA (base): R / B of the %dx%d result moved onto G (%d pass(es)), %ld values changed, %.0f ms", w, h,
                  e.manual ? 1 : int(e.fits.size()), n, std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    report(l);
}

} // namespace vivo_rawca
