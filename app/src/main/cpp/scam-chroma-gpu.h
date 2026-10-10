#pragma once
// P80 (owner, 2026-10-10: move the CPU steps of the shot to the GPU): mosaicChromaMedian (scam-hybrid.h) on the GPU. The same
// steps in the same order: R/G and B/G ratios, the separable 5-point medians (selections only: the same values), the luma
// high-pass, the soft sign-change count, the box filters and the false-colour blend. The boxes sum their taps in float here (the CPU
// keeps a running double sum) and the soft count runs in float (double there): the result differs by float rounding only.
// One context per call (the merge's context is gone by then); any failure throws and the caller runs the CPU version.
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <future>
#include <memory>
#include <thread>
#include <stdexcept>
#include <string>
#include <vector>

namespace scam {

static const char* kChromaGlslCommon = R"(
precision highp float;
precision highp int;
uniform ivec2 size;
uniform int rowBase;
float ssf(float x){x=clamp(x,0.0,1.0);return x*x*(3.0-2.0*x);}
)";
// rgb -> R/G, B/G ratios (uv) and the luma (L)
static const char* kChromaGlslRatio = R"(
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=0) readonly buffer Rgb{float rgb[];};
layout(std430,binding=1) writeonly buffer Uv{vec2 uv[];};
layout(std430,binding=2) writeonly buffer Ls{float L[];};
uniform float eps;
void main(){
    int x=int(gl_GlobalInvocationID.x),y=rowBase+int(gl_GlobalInvocationID.y);
    if(x>=size.x||y>=size.y)return;
    int i=y*size.x+x;
    float r=rgb[3*i],g=rgb[3*i+1],b=rgb[3*i+2],gg=max(g,0.0)+eps;
    uv[i]=vec2((r+eps)/gg,(b+eps)/gg);
    L[i]=0.25*r+0.5*g+0.25*b;
}
)";
// median of five by selection (mosaicChromaMedian med5), on both ratios; dir 0 horizontal, 1 vertical; taps d apart, clamped
static const char* kChromaGlslMedian = R"(
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=1) readonly buffer In{vec2 a[];};
layout(std430,binding=3) writeonly buffer Out{vec2 o[];};
uniform int d;
uniform int dir;
vec2 med5(vec2 a,vec2 b,vec2 c,vec2 d,vec2 e){
    vec2 t1=min(a,b),t2=max(a,b),t3=min(c,d),t4=max(c,d);
    vec2 lo=max(t1,t3),hi=min(t2,t4);
    vec2 x=min(lo,hi),y=max(lo,hi);
    return max(x,min(y,e));
}
vec2 at(int x,int y,int k){
    if(dir==0)return a[y*size.x+clamp(x+k*d,0,size.x-1)];
    return a[clamp(y+k*d,0,size.y-1)*size.x+x];
}
void main(){
    int x=int(gl_GlobalInvocationID.x),y=rowBase+int(gl_GlobalInvocationID.y);
    if(x>=size.x||y>=size.y)return;
    o[y*size.x+x]=med5(at(x,y,-2),at(x,y,-1),at(x,y,0),at(x,y,1),at(x,y,2));
}
)";
// box mean of radius r (clamped taps); T float or vec2. P80: a group of 64 x 4 outputs (horizontal: 64 columns of 4 rows;
// vertical: 64 rows of 4 columns) loads its 64 + 2r inputs of each line into shared memory once; each output sums its 2r + 1 taps
// in the same order as before (k = -r..r). r <= 32. HIGH_PASS (float): hp 1 = out is L - mean.
static const char* kChromaGlslBox = R"(
layout(local_size_x=64,local_size_y=4) in;
layout(std430,binding=4) readonly buffer In{T a[];};
layout(std430,binding=5) writeonly buffer Out{T o[];};
layout(std430,binding=6) readonly buffer Lf{float lum[];};
uniform int r;
uniform int dir;
uniform int hp;
shared T tile[4][128];
void main(){
    int lx=int(gl_LocalInvocationID.x),ly=int(gl_LocalInvocationID.y);
    // along = the filter direction (x horizontal, y vertical), across = the line; rowBase offsets the dispatched rows
    bool hor=dir==0;
    int along0=hor?int(gl_WorkGroupID.x)*64:rowBase+int(gl_WorkGroupID.x)*64;
    int across=hor?rowBase+int(gl_WorkGroupID.y)*4+ly:int(gl_WorkGroupID.y)*4+ly;
    int n=hor?size.x:size.y,lines=hor?size.y:size.x;
    int line=min(across,lines-1);
    for(int k=lx;k<64+2*r;k+=64){
        int p=clamp(along0-r+k,0,n-1);
        tile[ly][k]=hor?a[line*size.x+p]:a[p*size.x+line];
    }
    barrier();
    int along=along0+lx;
    if(across>=lines||along>=n)return;
    T s=T(0.0);
    for(int k=0;k<=2*r;k++)s+=tile[ly][lx+k];
    T m=s/float(2*r+1);
    int i=hor?across*size.x+along:along*size.x+across;
#ifdef HIGH_PASS
    if(hp!=0){o[i]=lum[i]-m;return;}
#endif
    o[i]=m;
}
)";
// soft sign-change count of the luma high-pass (mosaicSoftSignChange, kMosaicLumaTau)
static const char* kChromaGlslSign = R"(
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=4) readonly buffer Hp{float hp[];};
layout(std430,binding=5) writeonly buffer Sc{float sc[];};
layout(std430,binding=6) readonly buffer Lf{float lum[];};
float soft(float p0,float p1,float gate){
    if(!(gate>0.0))return 0.0;
    float opp=max(0.0,min(p0,-p1))+max(0.0,min(-p0,p1));
    if(opp<=0.0)return 0.0;
    return ssf(opp/(0.25*gate))*ssf(((abs(p0)+abs(p1))/gate-0.75)/0.5);
}
void main(){
    int x=int(gl_GlobalInvocationID.x),y=rowBase+int(gl_GlobalInvocationID.y);
    if(x>=size.x||y>=size.y)return;
    int i=y*size.x+x;
    float a=hp[i],gate=0.04*max(lum[i],1.0e-4),c=0.0;
    if(x+1<size.x)c+=0.5*soft(a,hp[i+1],gate);
    if(y+1<size.y)c+=0.5*soft(a,hp[i+size.x],gate);
    sc[i]=c;
}
)";
// the false-colour blend and the R / B reconstruction
static const char* kChromaGlslBlend = R"(
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=0) buffer Rgb{float rgb[];};
layout(std430,binding=1) readonly buffer Uv{vec2 uv[];};
layout(std430,binding=3) readonly buffer Wide{vec2 wide[];};
layout(std430,binding=4) readonly buffer Z{float z[];};
layout(std430,binding=7) readonly buffer Osc{float osc[];};
uniform float eps;
uniform vec2 zr;        // z0, z1
uniform ivec4 oscU;     // x: 1 = raw oscillation map, y = block, z / w = blocks per row / column
void main(){
    int x=int(gl_GlobalInvocationID.x),y=rowBase+int(gl_GlobalInvocationID.y);
    if(x>=size.x||y>=size.y)return;
    int i=y*size.x+x;
    float t=clamp((z[i]-zr.x)/(zr.y-zr.x),0.0,1.0),k=t*t*(3.0-2.0*t);
    if(oscU.x!=0){
        float o=osc[min(y/oscU.y,oscU.w-1)*oscU.z+min(x/oscU.y,oscU.z-1)];
        float t2=clamp((o-0.2375)/0.2375,0.0,1.0);k=max(k,t2*t2*(3.0-2.0*t2));
    }
    vec2 c=uv[i];c+=k*(wide[i]-c);
    float g=max(rgb[3*i+1],0.0)+eps;
    rgb[3*i]=c.x*g-eps;rgb[3*i+2]=c.y*g-eps;
}
)";

class ChromaMedianGpu {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    GLuint pRatio = 0, pMedian = 0, pBoxF = 0, pBoxV2 = 0, pSign = 0, pBlend = 0;
    enum { kRgb, kUv, kT, kX, kL, kA, kS, kOsc, kSlots };
    GLuint buf[kSlots]{};
    int w = 0, h = 0;
    void cleanup() noexcept {
        if (display == EGL_NO_DISPLAY) return;
        if (context != EGL_NO_CONTEXT && eglMakeCurrent(display, surface, surface, context)) {
            for (GLuint p : {pRatio, pMedian, pBoxF, pBoxV2, pSign, pBlend}) if (p) glDeleteProgram(p);
            glDeleteBuffers(kSlots, buf);
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        eglTerminate(display);
        display = EGL_NO_DISPLAY;
    }
    static void check(const char* where) {
        const GLenum e = glGetError();
        if (e != GL_NO_ERROR) throw std::runtime_error(std::string("CHROMA GPU ") + where + " GL error=" + std::to_string(e));
    }
    static GLuint compile(const char* defs, const char* body) {
        const char* sources[] = {"#version 310 es\n", defs, kChromaGlslCommon, body};
        GLuint shader = glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader, 4, sources, nullptr);
        glCompileShader(shader);
        GLint ok = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
        if (!ok) { char msg[2048]{}; glGetShaderInfoLog(shader, sizeof(msg), nullptr, msg); glDeleteShader(shader); throw std::runtime_error(std::string("CHROMA GPU shader: ") + msg); }
        GLuint program = glCreateProgram();
        glAttachShader(program, shader);
        glLinkProgram(program);
        glDeleteShader(shader);
        glGetProgramiv(program, GL_LINK_STATUS, &ok);
        if (!ok) { char msg[2048]{}; glGetProgramInfoLog(program, sizeof(msg), nullptr, msg); glDeleteProgram(program); throw std::runtime_error(std::string("CHROMA GPU link: ") + msg); }
        return program;
    }
    void alloc(int slot, size_t bytes) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf[slot]);
        glBufferData(GL_SHADER_STORAGE_BUFFER, GLsizeiptr(std::max<size_t>(bytes, 16)), nullptr, GL_DYNAMIC_DRAW);
    }
    void bind(int binding, int slot) { glBindBufferBase(GL_SHADER_STORAGE_BUFFER, GLuint(binding), buf[slot]); }
    // rows in dispatches of 256 (a single long dispatch can trip the GPU hang detection), flushed together
    void run(GLuint p) {
        glUniform2i(glGetUniformLocation(p, "size"), w, h);
        const GLint l = glGetUniformLocation(p, "rowBase");
        for (int r = 0; r < h; r += 256) {
            glUniform1i(l, r);
            glDispatchCompute(GLuint((w + 7) / 8), GLuint((std::min(256, h - r) + 7) / 8), 1);
        }
        glFlush();
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    }
    void box(GLuint p, int in, int out, int r, int dir, int hpMode, int lum) {
        glUseProgram(p);
        bind(4, in); bind(5, out); bind(6, lum);
        glUniform1i(glGetUniformLocation(p, "r"), r);
        glUniform1i(glGetUniformLocation(p, "dir"), dir);
        glUniform1i(glGetUniformLocation(p, "hp"), hpMode);
        glUniform2i(glGetUniformLocation(p, "size"), w, h);
        const GLint l = glGetUniformLocation(p, "rowBase");
        if (dir == 0) { // groups: 64 columns x 4 rows; rows in dispatches of 256
            for (int y = 0; y < h; y += 256) {
                glUniform1i(l, y);
                glDispatchCompute(GLuint((w + 63) / 64), GLuint((std::min(256, h - y) + 3) / 4), 1);
            }
        } else {        // groups: 64 rows x 4 columns; rows in dispatches of 256
            for (int y = 0; y < h; y += 256) {
                glUniform1i(l, y);
                glDispatchCompute(GLuint((std::min(256, h - y) + 63) / 64), GLuint((w + 3) / 4), 1);
            }
        }
        glFlush();
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    }
    using Clock = std::chrono::steady_clock;
    static double ms(Clock::time_point a) { return std::chrono::duration<double, std::milli>(Clock::now() - a).count(); }
public:
    std::string renderer, times;
    ChromaMedianGpu() {
        const auto t0 = Clock::now();
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
            pRatio = compile("", kChromaGlslRatio);
            pMedian = compile("", kChromaGlslMedian);
            pBoxF = compile("#define T float\n#define HIGH_PASS 1\n", kChromaGlslBox);
            pBoxV2 = compile("#define T vec2\n", kChromaGlslBox);
            pSign = compile("", kChromaGlslSign);
            pBlend = compile("", kChromaGlslBlend);
            glGenBuffers(kSlots, buf);
            check("init");
            times = "init " + std::to_string(int(ms(t0)));
        } catch (...) { cleanup(); throw; }
    }
    // P80: the context may be built on one thread (chromaPrewarm, during the merge) and used on another: release() on the first,
    // acquire() on the second (a context is current on one thread at a time); the destructor makes it current where it runs.
    void release() { eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); }
    void acquire() { if (!eglMakeCurrent(display, surface, surface, context)) throw std::runtime_error("CHROMA GPU: context not current"); }
    ChromaMedianGpu(const ChromaMedianGpu&) = delete;
    ChromaMedianGpu& operator=(const ChromaMedianGpu&) = delete;
    ~ChromaMedianGpu() { cleanup(); }
    // GPU memory of one call (B): rgb 12, ratios / medians / wide ratios 3 x 8, scalars 3 x 4 per pixel
    static size_t bytesFor(int w, int h) { return size_t(w) * h * 48; }
    void process(std::vector<float>& rgb, int width, int height, int block, const std::vector<float>* osc) {
        w = width; h = height;
        const size_t P = size_t(w) * h;
        auto t = Clock::now();
        GLint64 maxBlock = 0;
        glGetInteger64v(GL_MAX_SHADER_STORAGE_BLOCK_SIZE, &maxBlock);
        if (maxBlock > 0 && P * 12 > size_t(maxBlock)) throw std::runtime_error("CHROMA GPU: frame larger than a storage block");
        const int d = std::max(1, block / 2), r1 = block, r2 = 2 * block, r3 = 3 * block, bw = w / block, bh = h / block;
        if (r3 > 32) throw std::runtime_error("CHROMA GPU: block " + std::to_string(block) + " beyond the box tiles");
        const bool rawOsc = osc && int(osc->size()) == bw * bh;
        const float eps = 0.002f;
        alloc(kRgb, P * 12);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, GLsizeiptr(P * 12), rgb.data());
        alloc(kUv, P * 8); alloc(kT, P * 8); alloc(kX, P * 8);
        alloc(kL, P * 4); alloc(kA, P * 4); alloc(kS, P * 4);
        alloc(kOsc, rawOsc ? osc->size() * 4 : 16);
        if (rawOsc) glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, GLsizeiptr(osc->size() * 4), osc->data());
        check("upload");
        glFinish(); times += " upload " + std::to_string(int(ms(t))); t = Clock::now();
        // ratios and luma
        glUseProgram(pRatio);
        bind(0, kRgb); bind(1, kUv); bind(2, kL);
        glUniform1f(glGetUniformLocation(pRatio, "eps"), eps);
        run(pRatio);
        // medians: horizontal uv -> t, vertical t -> uv
        glUseProgram(pMedian);
        glUniform1i(glGetUniformLocation(pMedian, "d"), d);
        bind(1, kUv); bind(3, kT);
        glUniform1i(glGetUniformLocation(pMedian, "dir"), 0);
        run(pMedian);
        bind(1, kT); bind(3, kUv);
        glUniform1i(glGetUniformLocation(pMedian, "dir"), 1);
        run(pMedian);
        // luma high-pass hp = L - box(L, r1) into S; sign count into A; z = box(sc, r2) into S
        box(pBoxF, kL, kA, r1, 0, 0, kL);
        box(pBoxF, kA, kS, r1, 1, 1, kL);   // reads the luma for the high-pass
        glUseProgram(pSign);
        bind(4, kS); bind(5, kA); bind(6, kL);
        run(pSign);
        // (the luma binding is the input again where unused: no buffer bound for reading and writing in one dispatch)
        box(pBoxF, kA, kL, r2, 0, 0, kA);
        box(pBoxF, kL, kS, r2, 1, 0, kL);
        // wide ratios: box(box(uv, r3), r3) into X
        box(pBoxV2, kUv, kT, r3, 0, 0, kA);
        box(pBoxV2, kT, kX, r3, 1, 0, kA);
        box(pBoxV2, kX, kT, r3, 0, 0, kA);
        box(pBoxV2, kT, kX, r3, 1, 0, kA);
        // blend and R / B
        glUseProgram(pBlend);
        bind(0, kRgb); bind(1, kUv); bind(3, kX); bind(4, kS); bind(7, kOsc);
        glUniform1f(glGetUniformLocation(pBlend, "eps"), eps);
        glUniform2f(glGetUniformLocation(pBlend, "zr"), 0.51f / (2 * block), 1.02f / (2 * block));
        glUniform4i(glGetUniformLocation(pBlend, "oscU"), rawOsc ? 1 : 0, block, std::max(bw, 1), std::max(bh, 1));
        run(pBlend);
        check("passes");
        glFinish(); times += " passes " + std::to_string(int(ms(t))); t = Clock::now();
        // readback (direct map; Adreno may return a NULL map for a large range: then in chunks)
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buf[kRgb]);
        constexpr size_t kChunk = size_t(32) << 20;
        const size_t bytes = P * 12;
        for (size_t off = 0; off < bytes; off += kChunk) {
            const size_t n = std::min(kChunk, bytes - off);
            const void* m = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, GLintptr(off), GLsizeiptr(n), GL_MAP_READ_BIT);
            if (!m) throw std::runtime_error("CHROMA GPU readback failed");
            std::memcpy(reinterpret_cast<char*>(rgb.data()) + off, m, n);
            if (!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER)) throw std::runtime_error("CHROMA GPU storage invalidated");
        }
        check("readback");
        times += " readback " + std::to_string(int(ms(t)));
    }
};

// P80: IEEE 754 binary16 of a float, rounded to nearest even (as the ARM FCVT / __fp16 conversion): overflow to infinity, NaN kept
// (quiet), subnormal halves below 2^-14. Portable (the host checks build the worker with g++ on x86).
inline uint16_t floatToHalf(float f) {
    uint32_t x; std::memcpy(&x, &f, 4);
    const uint32_t sign = (x >> 16) & 0x8000u;
    const uint32_t absx = x & 0x7FFFFFFFu;
    if (absx >= 0x7F800000u) return uint16_t(sign | 0x7C00u | (absx > 0x7F800000u ? 0x200u | ((absx >> 13) & 0x3FFu) : 0u));
    if (absx >= 0x477FF000u) return uint16_t(sign | 0x7C00u);        // rounds to >= 65520: infinity
    if (absx < 0x38800000u) {                                          // below 2^-14: subnormal half (or zero)
        if (absx < 0x33000000u) return uint16_t(sign);                 // below 2^-25: rounds to zero
        const uint32_t mant = (absx & 0x7FFFFFu) | 0x800000u;
        const int shift = 126 - int(absx >> 23);                       // 14..24
        const uint32_t half = mant >> shift, rem = mant & ((1u << shift) - 1u), mid = 1u << (shift - 1);
        return uint16_t(sign | (half + ((rem > mid || (rem == mid && (half & 1u))) ? 1u : 0u)));
    }
    const uint32_t e = (absx >> 23) - 112u, m = absx & 0x7FFFFFu;      // rebias 127 -> 15
    uint32_t h = (e << 10) | (m >> 13);
    const uint32_t rem = m & 0x1FFFu;
    if (rem > 0x1000u || (rem == 0x1000u && (h & 1u))) ++h;            // may carry into the exponent: correct
    return uint16_t(sign | h);
}

// P80: one context built ahead (during the merge) and one teardown behind (after the median): both off the shot's path. The
// teardown thread is joined before the next one starts and at the process exit (a static destructor), never left running.
struct ChromaGpuSlot {
    std::future<std::unique_ptr<ChromaMedianGpu>> ahead;
    std::thread teardown;
    void join() { if (teardown.joinable()) teardown.join(); }
    ~ChromaGpuSlot() { join(); }
};
inline ChromaGpuSlot& chromaGpuSlot() { static ChromaGpuSlot slot; return slot; }
inline void chromaPrewarm() {
    ChromaGpuSlot& s = chromaGpuSlot();
    if (s.ahead.valid()) return;
    try {
        s.ahead = std::async(std::launch::async, [] { auto g = std::make_unique<ChromaMedianGpu>(); g->release(); return g; });
    } catch (const std::exception&) {}
}
// The prewarmed context (or a new one), current on this thread.
inline std::unique_ptr<ChromaMedianGpu> chromaTake() {
    ChromaGpuSlot& s = chromaGpuSlot();
    std::unique_ptr<ChromaMedianGpu> g;
    if (s.ahead.valid()) { g = s.ahead.get(); g->acquire(); }   // a failed build rethrows here
    else g = std::make_unique<ChromaMedianGpu>();
    return g;
}
inline void chromaGiveBack(std::unique_ptr<ChromaMedianGpu> g) {
    if (!g) return;
    g->release();
    ChromaGpuSlot& s = chromaGpuSlot();
    s.join();
    ChromaMedianGpu* p = g.release();
    try { s.teardown = std::thread([p] { delete p; }); }
    catch (const std::exception&) { delete p; } // no thread: torn down here
}

} // namespace scam
