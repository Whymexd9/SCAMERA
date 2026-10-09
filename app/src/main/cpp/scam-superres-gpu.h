#pragma once
// GPU (GLES 3.1 compute) version of the super-resolution merge in
// scam-superres.h: same per-cell arithmetic, one invocation per 2x2 cell.
// The CPU merge took ~4 s of the ~10 s SCAM reconstruction on 8 threads; the
// GPU sat idle during the whole worker run. The frames are uploaded in strips
// (only the rows each frame's homography maps into the strip), so the GPU never
// holds the full ~0.5 GB burst. Robustness stays on the CPU (it is cheap and its
// 3x3 erosion needs whole-cell neighbourhoods).
#include "scam-homography.h"
#include <EGL/egl.h>
#include <fstream>
#include <functional>
#include <GLES3/gl31.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <array>
#include <cstring>
#include <cstdlib>
#include <stdexcept>
#include <string>
#include <vector>

namespace scam {

struct SuperResGpuInput {
    int w=0,h=0;
    int cfa=0;                        // canonical shift: x+=cfa&1, y+=cfa>>1
    std::array<float,4> black{},inv{};
    std::array<int,4> phaseColor{};
    std::vector<const uint16_t*> frames;           // frame 0 = reference
    std::vector<BackwardHomography> homography;    // per frame (frame 0 unused)
    const std::vector<float>* model=nullptr;       // RGB, w*h*3
    float invScale=1;
    // Quad / Tetra stream: the merge's donor sites are the frames' own mosaic samples (one colour per block of
    // mosaicBlock x mosaicBlock sites, 2 or 4), parallel to `frames` (the plain-bayer remosaic of the same frames,
    // which guide the robustness and the model). mosaicColor: colour of the block phase (by) * 2 + (bx) in sensor
    // coordinates; siteGain: relative sensitivity per site class (y&7)*8+(x&7).
    int mosaicBlock=0;
    std::vector<const uint16_t*> mosaicFrames;
    std::array<int,4> mosaicColor{0,1,1,2};
    std::array<float,64> siteGain{};
};


// Merge tuning. Defaults are the shipped values; a debug file with "key value" lines
// (<external files>/scam_sr.txt or /data/local/tmp/scam_sr.txt) overrides them so the merge can be
// tuned on a replayed burst without rebuilding.
inline std::function<void(const std::string&)> g_superResReport;
struct SuperResTuning {
    int legacy=0;          // 1: the former isotropic kernel + 3x3-minimum robustness
    int grid=1;            // sub-pixel evaluations per axis for detailed pixels (1 or 2): 2 = merge on a 2x grid
                           // and average back to the sensor grid (anti-aliased, supersampled)
    float subDetail=0.3f;  // detail measure above which the 2x grid is used
    // Kernel (Gaussian standard deviations in sensor pixels) from the structure tensor of the model
    // luma, after Wronski et al. / GCam Sabre: narrow across a coherent edge, long along it, wide
    // and isotropic in flat areas.
    // Negative = follows the SNR of the reference frame (Sabre: every kernel parameter is a curve of the
    // SNR of mid grey, see snrKernel()); a value from scam_sr.txt overrides that parameter.
    float base=-1.f,shrunk=-1.f,stretched=-1.f,flat=-1.f;
    float strengthScale=50.f;   // edge amount: coherence limited by strength * scale
    float flat0=-1.f,flat1=-1.f;  // gradient (u per pixel) below which the kernel is blurred
    float snrScale=0.25f;       // noise variance scale of the model input (applied to the SNR key only)
    int snrFixed=0;             // >0: use this SNR instead of the one derived from the noise model
    float rawTensor=0.f;        // weight of the structure tensor of the reference frame's raw greens (SCAM guide), added to the model's
    float rawNoise=1.f;         // bias (noise) removed from the raw tensor, in units of the expected gradient noise^2
    int subset=0;               // debug: 1 = odd donor frames only, 2 = even donor frames only (split-half noise estimate)
    float texStd=0.25f;         // weight of the local raw std in the "detail" measure
    float tensorNoise=0.0015f;  // gradient noise floor (u per pixel)
    // Robustness: Wiener-shrunk colour differences against max(noise, share of the local texture).
    float robustK=3.f,robustSigmas=3.f,robustTex=0.25f,dilate=4.f;
    // Base frame (added last): wider kernel where little donor weight accumulated.
    float widenBelow=2.5f,widenMul=1.6f;
    float subShrink=0.8f;      // kernel scale on the 2x sub-grid
    float legacySigma=0.55f;
};
inline SuperResTuning loadSuperResTuning(const std::function<void(const std::string&)>& report) {
    SuperResTuning t;
    g_superResReport=report;
    for(const char* path:{"/sdcard/Android/data/org.codeaurora.snapcam/files/scam_sr.txt","/data/local/tmp/scam_sr.txt"}){
        std::ifstream f(path);if(!f)continue;
        std::string key;float v;std::string applied;
        while(f>>key>>v){
            float* target=nullptr;
            if(key=="legacy")t.legacy=int(v);else if(key=="grid")t.grid=int(v);
            else if(key=="subDetail")target=&t.subDetail;else if(key=="base")target=&t.base;else if(key=="shrunk")target=&t.shrunk;
            else if(key=="stretched")target=&t.stretched;else if(key=="flat")target=&t.flat;else if(key=="strengthScale")target=&t.strengthScale;
            else if(key=="flat0")target=&t.flat0;else if(key=="flat1")target=&t.flat1;else if(key=="texStd")target=&t.texStd;
            else if(key=="tensorNoise")target=&t.tensorNoise;else if(key=="robustK")target=&t.robustK;
            else if(key=="robustSigmas")target=&t.robustSigmas;else if(key=="robustTex")target=&t.robustTex;else if(key=="dilate")target=&t.dilate;
            else if(key=="rawTensor")target=&t.rawTensor;else if(key=="rawNoise")target=&t.rawNoise;
            else if(key=="snrScale")target=&t.snrScale;else if(key=="snr"){t.snrFixed=int(v);applied+=" snr="+std::to_string(v);continue;}
            else if(key=="subset"){t.subset=int(v);applied+=" subset="+std::to_string(v);continue;}
            else if(key=="widenBelow")target=&t.widenBelow;else if(key=="widenMul")target=&t.widenMul;else if(key=="legacySigma")target=&t.legacySigma;else if(key=="subShrink")target=&t.subShrink;
            else continue;
            if(target)*target=v;
            applied+=" "+key+"="+std::to_string(v);
        }
        if(report&&!applied.empty())report("SCAM SUPERRES TUNING FILE "+std::string(path)+":"+applied);
        break;
    }
    return t;
}

// Piecewise-linear curve (values outside the keys are clamped), as the GCam Sabre parameter curves.
template<size_t N> inline float sabreCurve(float x,const float (&k)[N],const float (&v)[N]){
    if(x<=k[0])return v[0];
    for(size_t i=1;i<N;++i)if(x<=k[i])return v[i-1]+(v[i]-v[i-1])*(x-k[i-1])/(k[i]-k[i-1]);
    return v[N-1];
}
// SNR of mid grey (18 %) of the reference frame: the key of the Sabre curves.
inline float sabreSnr(float slope,float offset,float scale){
    return 0.18f/std::sqrt(std::max(scale*(offset+slope*0.18f),1e-10f));
}
// Kernel parameters for an SNR. Sabre 6.1 (true gaussian sigmas, sensor pixels): across an edge 0.18 -> 0.14, base 0.40 -> 0.30,
// along 1.6 -> 1.2, blurred 1.9 -> 0.9 from SNR 8 to 30; structure threshold f2 0.01 -> 0.001, transition f3 0.02 -> 0.006.
// Luminance is merged with sigma/sqrt2 here (and colour with sigma), hence the larger values than in Sabre.
inline void snrKernel(SuperResTuning& t,float snr){
    static const float k[]={4.f,8.f,14.f,30.f};
    static const float shrunk[]={0.40f,0.32f,0.28f,0.22f};
    static const float base[]={0.56f,0.46f,0.40f,0.32f};
    static const float stretched[]={1.90f,1.65f,1.42f,1.18f};
    static const float flat[]={2.50f,2.05f,1.65f,1.27f};
    static const float f0[]={0.0020f,0.0013f,0.0009f,0.0005f};
    static const float f1[]={0.0120f,0.0085f,0.0060f,0.0040f};
    if(t.shrunk<0)t.shrunk=sabreCurve(snr,k,shrunk);
    if(t.base<0)t.base=sabreCurve(snr,k,base);
    if(t.stretched<0)t.stretched=sabreCurve(snr,k,stretched);
    if(t.flat<0)t.flat=sabreCurve(snr,k,flat);
    if(t.flat0<0)t.flat0=sabreCurve(snr,k,f0);
    if(t.flat1<0)t.flat1=sabreCurve(snr,k,f1);
}

// Shared by both programs: strip-uploaded frames, canonical CFA shift, black/white
// normalisation and the per-frame backward homography.
static const char* kCommonShader=R"(#version 310 es
precision highp float;
precision highp int;
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=0) readonly buffer Frames{uint frames[];};
layout(std430,binding=12) readonly buffer MFrames{uint mframes[];};
uniform int mosaicBlock;     // 0 = plain bayer donors; 2 / 4 = mosaic donor sites (sampleMosaic)
uniform ivec4 mosaicColor;
uniform float siteGain[64];
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
// Raw site of the frame's own mosaic (same canonical coordinates and strip layout as sampleRaw), its colour from the
// block phase of the site actually read, and the per-class response correction.
float sampleMosaic(int f,int x,int y,out int c){
    x+=cfaShift.x;y+=cfaShift.y;
    if(x<0||y<0||x>=size.x||y>=size.y){x=reflectCfa(x,size.x);y=reflectCfa(y,size.y);}
    int ry=clamp(y-frameRow0[f],0,frameRows[f]-1);
    uint idx=frameOffset[f]+uint(ry*size.x+x);
    uint word=mframes[idx>>1];
    uint v=(idx&1u)==0u?(word&0xFFFFu):(word>>16);
    int phase=((y&1)<<1)|(x&1);
    int bs=max(mosaicBlock,1);
    c=mosaicColor[(((y/bs)&1)<<1)|((x/bs)&1)];
    return clamp((float(v)-black[phase])*inv[phase]*siteGain[((y&7)<<3)|(x&7)],-0.25,1.0);
}
vec2 origin(int f,int x,int y){
    vec4 a=hA[f],b=hB[f];
    float fx=float(x),fy=float(y);
    float den=b.z*fx+b.w*fy+1.0;
    vec2 p=vec2(a.x*fx+a.y*fy+a.z,a.w*fx+b.x*fy+b.y)/den;
    return p/upRatio[f];
}
)";

// Super-resolution merge (scam-superres.h), one invocation per 2x2 cell.
static const char* kSuperResShaderLegacy=R"(
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
const float sigma=0.55;
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

// Extra-N slot merge (scam-capture.h): frame 0 is the slot's own frame, the
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
static const char* kRobustShaderLegacy=R"(
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
static const char* kErodeShaderLegacy=R"(
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

// ---------------------------------------------------------------------------------------------
// Merge after Wronski et al. (2019) / the GCam 6.1 "Sabre" shaders.
//
//  1. u-plane:  sqrt(model luma + eps), the variance-stabilised domain (noise variance of one raw
//               site is slope/4 everywhere).
//  2. guide:    per 2x2 cell (a Bayer quad): reference colour (R, G, B in u), its local texture
//               variance (3x3 cells, noise removed), and from the structure tensor of the model
//               luma the kernel precision matrix (narrow across an edge, long along it, wide where
//               flat) and a detail measure.
//  3. robust:   per donor frame and cell, Wiener-shrunk colour difference in u against the larger
//               of the noise and a share of the local texture (the alignment error grows with it).
//  4. dilate:   soft spread of rejections to the neighbouring cells (not a hard 3x3 minimum).
//  5. merge:    per output pixel, every donor frame's raw sites around the aligned position weighted
//               by the anisotropic kernel (and its robustness); the base frame is added last with a
//               wider kernel where little donor weight accumulated. The merged residual against the
//               model is added to the model as before. Detailed pixels are evaluated on a 2x2 grid of
//               sub-positions (merge on a 2x grid) and averaged back: no aliasing, cleaner edges.
// ---------------------------------------------------------------------------------------------
static const char* kSrHelpers=R"(
uniform ivec4 phaseColor;
uniform vec2 noise; // single-frame noise model: slope, offset (RAW units)
float epsU(){ return max(noise.y/max(noise.x,1.0e-9),1.0e-5); }
// Colour (u domain) of the 2x2 cell (i,j) of frame f; gd = |sqrt(G1)-sqrt(G2)|, rawMax = brightest colour.
vec3 quadU(int f,int i,int j,out float gd,out float rawMax){
    float g0=0.0,g1=0.0;int gi=0;vec3 r=vec3(0.0);
    for(int p=0;p<4;p++){
        int px=p&1,py=p>>1;int c=phaseColor[p];
        float v=sampleRaw(f,2*i+px,2*j+py);
        if(c==1){ if(gi==0)g0=v; else g1=v; gi++; }
        else if(c==0)r.r=v; else r.b=v;
    }
    r.g=0.5*(g0+g1);
    float e=epsU();
    gd=abs(sqrt(max(g0,0.0)+e)-sqrt(max(g1,0.0)+e));
    rawMax=max(r.r,max(r.g,r.b));
    return sqrt(max(r,vec3(0.0))+vec3(e));
}
)";

static const char* kUPlaneShader=R"(
layout(std430,binding=1) readonly buffer Model{float model[];};
layout(std430,binding=2) writeonly buffer UPlane{float uplane[];};
uniform int modelRows;
uniform float invScale;
void main(){
    int x=int(gl_GlobalInvocationID.x),ry=int(gl_GlobalInvocationID.y);
    if(x>=size.x||ry>=modelRows)return;
    int o=(ry*size.x+x)*3;
    float m=(model[o]+model[o+1]+model[o+2])*(1.0/3.0)*invScale;
    uplane[ry*size.x+x]=sqrt(max(m,0.0)+epsU());
}
)";

static const char* kGuideShader=R"(
layout(std430,binding=2) readonly buffer UPlane{float uplane[];};
layout(std430,binding=10) writeonly buffer Guide{vec4 guide[];};
layout(std430,binding=11) writeonly buffer Cov{vec4 cov[];};
uniform int ry0;
uniform int ry1;
uniform int uRow0;
uniform int uRows;
uniform vec4 kA; // base, shrunk, stretched, flat (sigma in sensor pixels)
uniform vec4 kB; // strength scale, flat0, flat1, texStd
uniform vec4 kC; // tensor noise
uniform vec4 kF; // raw green tensor: weight, noise bias (u per pixel)^2
float uAt(int x,int y){
    x=clamp(x,0,size.x-1);y=clamp(y,0,size.y-1);
    return uplane[clamp(y-uRow0,0,uRows-1)*size.x+x];
}
void main(){
    int w2=size.x/2,h2=size.y/2;
    int cx=int(gl_GlobalInvocationID.x),cy=ry0+int(gl_GlobalInvocationID.y);
    if(cx>=w2||cy>=ry1)return;
    // Reference statistics over the 3x3 cells.
    vec3 mean=vec3(0.0),mean2=vec3(0.0),centre=vec3(0.0);float gdSum=0.0;
    for(int dj=-1;dj<=1;dj++)for(int di=-1;di<=1;di++){
        float gd,rm;
        vec3 uv=quadU(0,clamp(cx+di,0,w2-1),clamp(cy+dj,0,h2-1),gd,rm);
        mean+=uv*(1.0/9.0);mean2+=uv*uv*(1.0/9.0);
        gdSum+=gd*((di==0)?0.5:0.25)*((dj==0)?0.5:0.25);
        if(di==0&&dj==0)centre=uv;
    }
    vec3 var=max(mean2-mean*mean,vec3(0.0));
    float s2=(var.x+var.y+var.z)*(1.0/3.0);
    float nvMean=noise.x*0.2083*0.89;              // noise variance of the 9-sample estimate
    float gdTex=max(gdSum-0.56*sqrt(noise.x),0.0); // aliasing in the green pair above the noise
    float s2tex=max(max(s2-nvMean,0.0),gdTex*gdTex);
    int gi=(cy-ry0)*w2+cx;
    guide[gi]=vec4(centre,s2tex);
    // Structure tensor of the model luma over the 4x4 pixels of the cell and its surround.
    float uu[36];
    for(int j=0;j<6;j++)for(int i=0;i<6;i++)uu[j*6+i]=uAt(2*cx-2+i,2*cy-2+j);
    float txx=0.0,tyy=0.0,txy=0.0;
    for(int j=1;j<=4;j++)for(int i=1;i<=4;i++){
        float gx=0.5*(uu[j*6+i+1]-uu[j*6+i-1]);
        float gy=0.5*(uu[(j+1)*6+i]-uu[(j-1)*6+i]);
        txx+=gx*gx;tyy+=gy*gy;txy+=gx*gy;
    }
    txx*=(1.0/16.0);tyy*=(1.0/16.0);txy*=(1.0/16.0);
    if(kF.x>0.0){
        // Structure tensor of the raw greens of the reference frame (18 green sites of the 6x6 window, gradient from the four
        // diagonal neighbours), less the gradient noise: sees lines the network has smoothed away.
        float ug[64];
        float e=epsU();
        for(int j=0;j<8;j++)for(int i=0;i<8;i++){
            int X=2*cx-3+i,Y=2*cy-3+j;
            ug[j*8+i]=(phaseColor[((Y&1)<<1)|(X&1)]==1)?sqrt(max(sampleRaw(0,X,Y),0.0)+e):0.0;
        }
        float rxx=0.0,ryy=0.0,rxy=0.0,rn=0.0;
        for(int j=1;j<7;j++)for(int i=1;i<7;i++){
            int X=2*cx-3+i,Y=2*cy-3+j;
            if(phaseColor[((Y&1)<<1)|(X&1)]!=1)continue;
            float a=ug[(j+1)*8+i+1],b=ug[(j-1)*8+i+1],c=ug[(j+1)*8+i-1],d=ug[(j-1)*8+i-1];
            float gx=0.25*(a+b-c-d),gy=0.25*(a+c-b-d);
            rxx+=gx*gx;ryy+=gy*gy;rxy+=gx*gy;rn+=1.0;
        }
        rn=1.0/max(rn,1.0);
        rxx=max(rxx*rn-kF.y,0.0);ryy=max(ryy*rn-kF.y,0.0);rxy*=rn;
        // the cross term shrinks with the diagonal it can no longer support
        float lim=sqrt(rxx*ryy);rxy=clamp(rxy,-lim,lim);
        txx+=kF.x*rxx;tyy+=kF.x*ryy;txy+=kF.x*rxy;
    }
    float tr=txx+tyy,df=txx-tyy,sq=sqrt(max(df*df+4.0*txy*txy,0.0));
    float l1=0.5*(tr+sq),l2=max(0.5*(tr-sq),0.0);
    vec2 e1=vec2(1.0,0.0);
    if(abs(txy)>1.0e-9){ e1=normalize(vec2(txy,l1-txx)); }
    else if(txx<tyy){ e1=vec2(0.0,1.0); }
    vec2 e2=vec2(-e1.y,e1.x);
    float sv1=sqrt(l1),sv2=sqrt(l2);
    float l1w=l1*l1/(l1+kC.x*kC.x+1.0e-12);        // noise-filtered strength (Sabre)
    float strength=sqrt(l1w);
    float coherence=(sv1-sv2)/(sv1+sv2+1.0e-6);
    float dominant=max(strength,kB.w*sqrt(s2tex));
    float flatness=1.0-smoothstep(kB.y,kB.z,dominant);
    float across=mix(kA.x,kA.y,min(coherence,strength*kB.x));
    float along=mix(kA.x,kA.z,coherence);
    across=mix(across,kA.w,flatness);
    along=mix(along,kA.w,flatness);
    float ia=1.0/(across*across),il=1.0/(along*along);
    cov[gi]=vec4(e1.x*e1.x*ia+e2.x*e2.x*il,e1.y*e1.y*ia+e2.y*e2.y*il,e1.x*e1.y*ia+e2.x*e2.y*il,1.0-flatness);
}
)";

static const char* kRobustShader=R"(
layout(std430,binding=7) writeonly buffer RawR{float rawR[];};
layout(std430,binding=10) readonly buffer Guide{vec4 guide[];};
uniform int ry0;
uniform int ry1;
uniform vec4 rb; // K, sigmas^2, texture tolerance^2
void main(){
    int w2=size.x/2,h2=size.y/2;
    int cx=int(gl_GlobalInvocationID.x),cy=ry0+int(gl_GlobalInvocationID.y),f=int(gl_GlobalInvocationID.z)+1;
    if(cx>=w2||cy>=ry1||f>=frameCount)return;
    vec4 G=guide[(cy-ry0)*w2+cx];
    vec2 o=origin(f,2*cx,2*cy)*0.5;
    int ix=int(floor(o.x)),iy=int(floor(o.y));
    float fx=o.x-float(ix),fy=o.y-float(iy);
    float gd,r00,r10,r01,r11;
    vec3 u00=quadU(f,clamp(ix,0,w2-1),clamp(iy,0,h2-1),gd,r00);
    vec3 u10=quadU(f,clamp(ix+1,0,w2-1),clamp(iy,0,h2-1),gd,r10);
    vec3 u01=quadU(f,clamp(ix,0,w2-1),clamp(iy+1,0,h2-1),gd,r01);
    vec3 u11=quadU(f,clamp(ix+1,0,w2-1),clamp(iy+1,0,h2-1),gd,r11);
    vec3 g=(u00*(1.0-fx)+u10*fx)*(1.0-fy)+(u01*(1.0-fx)+u11*fx)*fy;
    float e=epsU();
    float uClip=sqrt(0.9+e);
    float r=0.0;
    if(max(G.x,max(G.y,G.z))<uClip&&max(max(r00,r10),max(r01,r11))<0.9){
        vec3 nv=noise.x*vec3(0.5,0.25,0.5);      // variance of a difference of two cells
        vec3 d=g-G.xyz;vec3 d2=d*d;
        vec3 shr=d2*d2/(d2+nv);                   // Wiener-like shrinkage of the difference
        float nvMean=(nv.x+nv.y+nv.z)*(1.0/3.0);
        float tol2=max(rb.y*nvMean,rb.z*G.w);
        float D=(shr.x+shr.y+shr.z)*(1.0/3.0)/tol2;
        r=exp2(-rb.x*max(D-1.0,0.0));
    }
    rawR[(f-1)*(ry1-ry0)*w2+(cy-ry0)*w2+cx]=floor(r*255.0+0.5)*(1.0/255.0);
}
)";

static const char* kDilateShader=R"(
layout(std430,binding=7) readonly buffer RawR{float rawR[];};
layout(std430,binding=8) writeonly buffer Robust{float robust[];};
layout(std430,binding=9) buffer Sums{uint sums[];};
uniform int ry0;
uniform int ry1;
uniform int cy0;
uniform int cy1;
uniform float mergeWeight;
uniform float dil;
void main(){
    int w2=size.x/2;
    int cx=int(gl_GlobalInvocationID.x),cy=cy0+int(gl_GlobalInvocationID.y),f=int(gl_GlobalInvocationID.z)+1;
    if(cx>=w2||cy>=cy1||f>=frameCount)return;
    float s=0.0,wc=1.0;
    for(int dj=-1;dj<=1;dj++)for(int di=-1;di<=1;di++){
        int y=clamp(cy+dj,ry0,ry1-1),x=clamp(cx+di,0,w2-1);
        float v=rawR[(f-1)*(ry1-ry0)*w2+(y-ry0)*w2+x];
        s+=1.0-v;
        if(di==0&&dj==0)wc=v;
    }
    float r=min(wc,1.0-clamp(s/max(dil,1.0e-3),0.0,1.0));
    r=floor(r*mergeWeight*255.0+0.5)*(1.0/255.0);
    robust[(f-1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx]=r;
    atomicAdd(sums[f],uint(r*255.0+0.5));
}
)";

static const char* kSuperResShader=R"(
layout(std430,binding=1) readonly buffer Model{float model[];};
layout(std430,binding=8) readonly buffer Robust{float robust[];};
layout(std430,binding=3) writeonly buffer Out{float outRgb[];};
layout(std430,binding=4) writeonly buffer Eff{float eff[];};
layout(std430,binding=11) readonly buffer Cov{vec4 cov[];};
uniform int cy0;
uniform int cy1;
uniform int ry0;
uniform int modelY0;
uniform int modelRows;
uniform float invScale;
uniform vec4 kD;    // sub-grid per axis, sub-detail threshold, widen below, widen multiplier
uniform vec4 kE;    // subset (debug)
uniform float subShrink; // kernel scale on the sub-grid (averaging the sub-positions widens the result)
const float prior=0.02;
const float relativeFloor=0.004;
const float achromatic=0.75;
const float chromaFloor=0.2;
struct Acc{vec3 num;vec3 den;float numA;float denA;float denA2;};
void initAcc(out Acc a){a.num=vec3(0.0);a.den=vec3(prior);a.numA=0.0;a.denA=prior;a.denA2=0.0;}
void addS(inout Acc a,int c,float kw,float v,float m){
    a.num[c]+=kw*(v-m);a.den[c]+=kw;
    float ka=kw*kw; // narrower kernel (sigma/sqrt2) for luminance
    a.numA+=ka*(v-m)/(m+relativeFloor);a.denA+=ka;a.denA2+=ka*ka;
}
float modelAt(int x,int y,int c){
    int ry=clamp(clamp(y,0,size.y-1)-modelY0,0,modelRows-1);
    return model[(ry*size.x+clamp(x,0,size.x-1))*3+c];
}
float modelBilinear(int mx,int my,float fx,float fy,int c){
    mx=clamp(mx,0,size.x-2);my=clamp(my,0,size.y-2);
    float p0=modelAt(mx,my,c),p1=modelAt(mx+1,my,c),q0=modelAt(mx,my+1,c),q1=modelAt(mx+1,my+1,c);
    return ((p0*(1.0-fx)+p1*fx)*(1.0-fy)+(q0*(1.0-fx)+q1*fx)*fy)*invScale;
}
float kernelW(vec2 d,vec3 P){
    return exp2(-0.72135*(d.x*d.x*P.x+d.y*d.y*P.y+2.0*d.x*d.y*P.z)); // exp(-0.5 d'Pd)
}
float robustAt(int f,int cx,int cy){
    int w2=size.x/2;
    return robust[(f-1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx];
}
// Raw sites of frame 0 around pos: the two lattice sites per axis of every colour phase.
void refSamples(inout Acc a,vec2 pos,vec3 P){
    if(mosaicBlock>0){
        // The same 4x4 site window as the lattice loops below, every site with the colour of its own block.
        int bx=int(floor(pos.x))-1,by=int(floor(pos.y))-1;
        for(int j=0;j<4;j++)for(int i=0;i<4;i++){
            int sx=bx+i,sy=by+j;
            float kw=kernelW(vec2(float(sx),float(sy))-pos,P);
            if(kw<0.004)continue;
            int c;float v=sampleMosaic(0,sx,sy,c);
            if(v>=0.95)continue;
            addS(a,c,kw,v,modelAt(sx,sy,c)*invScale);
        }
        return;
    }
    for(int p=0;p<4;p++){
        int px=p&1,py=p>>1,c=phaseColor[p];
        int bx=int(floor((pos.x-float(px))*0.5))*2+px,by=int(floor((pos.y-float(py))*0.5))*2+py;
        for(int dj=0;dj<=2;dj+=2)for(int di=0;di<=2;di+=2){
            int sx=bx+di,sy=by+dj;
            float kw=kernelW(vec2(float(sx),float(sy))-pos,P);
            if(kw<0.004)continue;
            float v=sampleRaw(0,sx,sy);
            if(v>=0.95)continue;
            addS(a,c,kw,v,modelAt(sx,sy,c)*invScale);
        }
    }
}
// Raw sites of donor frame f around the aligned position of pos.
void donorSamples(inout Acc a,int f,vec2 pos,int cx,int cy,float r,vec3 P){
    vec2 cell=vec2(float(2*cx),float(2*cy));
    vec2 oc=origin(f,2*cx,2*cy);
    vec2 O=oc+(pos-cell);
    vec2 t=cell-oc;
    int itx=int(floor(t.x)),ity=int(floor(t.y));
    float mfx=t.x-float(itx),mfy=t.y-float(ity);
    if(mosaicBlock>0){
        int bx=int(floor(O.x))-1,by=int(floor(O.y))-1;
        for(int j=0;j<4;j++)for(int i=0;i<4;i++){
            int sx=bx+i,sy=by+j;
            float kw=r*kernelW(vec2(float(sx),float(sy))-O,P);
            if(kw<0.003)continue;
            int c;float v=sampleMosaic(f,sx,sy,c);
            if(v>=0.95)continue;
            addS(a,c,kw,v,modelBilinear(sx+itx,sy+ity,mfx,mfy,c));
        }
        return;
    }
    for(int p=0;p<4;p++){
        int px=p&1,py=p>>1,c=phaseColor[p];
        int bx=int(floor((O.x-float(px))*0.5))*2+px,by=int(floor((O.y-float(py))*0.5))*2+py;
        for(int dj=0;dj<=2;dj+=2)for(int di=0;di<=2;di+=2){
            int sx=bx+di,sy=by+dj;
            float kw=r*kernelW(vec2(float(sx),float(sy))-O,P);
            if(kw<0.003)continue;
            float v=sampleRaw(f,sx,sy);
            if(v>=0.95)continue;
            addS(a,c,kw,v,modelBilinear(sx+itx,sy+ity,mfx,mfy,c));
        }
    }
}
vec3 modelAtPos(vec2 pos){
    int ix=int(floor(pos.x)),iy=int(floor(pos.y));
    float fx=pos.x-float(ix),fy=pos.y-float(iy);
    return vec3(modelBilinear(ix,iy,fx,fy,0),modelBilinear(ix,iy,fx,fy,1),modelBilinear(ix,iy,fx,fy,2));
}
void main(){
    int w2=size.x/2;
    int cx=int(gl_GlobalInvocationID.x),cy=cy0+int(gl_GlobalInvocationID.y);
    if(cx>=w2||cy>=cy1)return;
    vec4 cv=cov[(cy-ry0)*w2+cx];
    vec3 P=cv.xyz;
    int ns=(kD.x>1.5&&cv.w>kD.y)?2:1;
    int y0=2*cy0;
    for(int q=0;q<4;q++){
        int x=2*cx+(q&1),y=2*cy+(q>>1);
        vec3 col=vec3(0.0);float framesSum=0.0;
        for(int sy=0;sy<ns;sy++)for(int sx=0;sx<ns;sx++){
            vec2 pos=vec2(float(x),float(y));
            if(ns>1)pos+=(vec2(float(sx),float(sy))-0.5)*0.5;
            Acc a;initAcc(a);
            vec3 Pe=ns>1?P/(subShrink*subShrink):P;
            for(int f=1;f<frameCount;f++){
                float r=robustAt(f,cx,cy);
                if(r<0.02)continue;
                if(kE.x>0.5&&(f&1)!=(int(kE.x)&1))continue; // debug split-half: 1 = odd donors, 2 = even donors
                donorSamples(a,f,pos,cx,cy,r,Pe);
            }
            // Base frame last: where the donors left little weight (rejection, motion) its kernel widens.
            float donorFrames=a.denA2>0.0?(a.denA-prior)*(a.denA-prior)/a.denA2:0.0;
            float widen=mix(kD.w,1.0,smoothstep(0.5*kD.z,kD.z,donorFrames));
            refSamples(a,pos,Pe/(widen*widen));
            float rel=a.numA/a.denA;
            float frames=a.denA2>0.0?max(1.0,(a.denA-prior)*(a.denA-prior)/a.denA2):1.0;
            vec3 m=modelAtPos(pos);
            for(int c=0;c<3;c++){
                float mc=m[c];
                // Colour detail: where the merge is clean enough (signal well above the merged noise)
                // the true per-colour residual is taken in full, as a GCam merge does.
                float snr=mc/sqrt(max(noise.x*max(mc,0.0)+noise.y,1.0e-9)/frames);
                float aa=mix(chromaFloor,achromatic,1.0-smoothstep(10.0,50.0,snr));
                // Where rejection left only a frame or two, the residual is mostly single-frame noise:
                // fall back towards the model.
                float cover=smoothstep(1.3,5.0,frames);
                // Mosaic donor sites: inside a Quad / Tetra block the window holds one colour only, so on a still burst a
                // colour can arrive with (almost) no weight at all; its own residual would then be noise (a magenta lattice
                // on flat walls at night). Such a colour follows the relative residual of the covered ones instead.
                if(mosaicBlock>0)aa=mix(1.0,aa,smoothstep(0.05,0.6,a.den[c]));
                col[c]+=max(0.0,mc+cover*(aa*(mc+relativeFloor)*rel+(1.0-aa)*a.num[c]/max(a.den[c],1.0e-6)));
            }
            framesSum+=frames;
        }
        float inv=1.0/float(ns*ns);
        int o=((y-y0)*size.x+x)*3;
        outRgb[o]=col.x*inv;outRgb[o+1]=col.y*inv;outRgb[o+2]=col.z*inv;
        eff[(y-y0)*size.x+x]=framesSum*inv;
    }
}
)";

// P57: the worker's GL contexts at low GPU priority (EGL_IMG_context_priority, Adreno / Mali / PowerVR), so the GPU preempts
// the merge for the viewfinder's and the compositor's draws (owner: the viewfinder stuttered heavily while a photo was processed;
// the X7 Ultra merge submits strips of ~1.4 s). The attribute is a hint: a driver without the extension, or one that refuses
// it, gets the context as before (and SCAM_GPU_NORMAL_PRIORITY=1 keeps it as before for comparisons). Results are unchanged.
inline EGLContext scamProcessingContext(EGLDisplay display,EGLConfig config){
    const char* ext=eglQueryString(display,EGL_EXTENSIONS);
    if(ext&&std::strstr(ext,"EGL_IMG_context_priority")&&!std::getenv("SCAM_GPU_NORMAL_PRIORITY")){
        const EGLint low[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_CONTEXT_MINOR_VERSION,1,0x3100 /*EGL_CONTEXT_PRIORITY_LEVEL_IMG*/,
                            0x3103 /*EGL_CONTEXT_PRIORITY_LOW_IMG*/,EGL_NONE};
        EGLContext c=eglCreateContext(display,config,EGL_NO_CONTEXT,low);
        if(c!=EGL_NO_CONTEXT)return c;
        eglGetError();
    }
    const EGLint attrs[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_CONTEXT_MINOR_VERSION,1,EGL_NONE};
    return eglCreateContext(display,config,EGL_NO_CONTEXT,attrs);
}

class SuperResGpu {
    EGLDisplay display=EGL_NO_DISPLAY;
    EGLContext context=EGL_NO_CONTEXT;
    EGLSurface surface=EGL_NO_SURFACE;
    GLuint srProgram=0,slotProgram=0,robustProgram=0,dilateProgram=0,uplaneProgram=0,guideProgram=0;
    GLuint srProgramLegacy=0,robustProgramLegacy=0,erodeProgramLegacy=0,buffers[14]{};
    size_t capacity[14]{};
    void check(const char* where){GLenum e=glGetError();if(e!=GL_NO_ERROR)throw std::runtime_error(std::string("SCAM GPU ")+where+" GL error="+std::to_string(e));}
    void cleanup() noexcept {
        if(display==EGL_NO_DISPLAY)return;
        if(context!=EGL_NO_CONTEXT&&eglMakeCurrent(display,surface,surface,context)){
            for(GLuint program:{srProgram,slotProgram,robustProgram,dilateProgram,uplaneProgram,guideProgram,srProgramLegacy,robustProgramLegacy,erodeProgramLegacy})
                if(program)glDeleteProgram(program);
            glDeleteBuffers(14,buffers);
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        }
        if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);
        if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
        eglTerminate(display);display=EGL_NO_DISPLAY;
    }
    GLuint compile(const char* body,bool helpers=false){
        GLuint shader=glCreateShader(GL_COMPUTE_SHADER);const char* sources[]={kCommonShader,helpers?kSrHelpers:"",body};
        glShaderSource(shader,3,sources,nullptr);glCompileShader(shader);
        GLint ok=0;glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
        if(!ok){char msg[2048]{};glGetShaderInfoLog(shader,sizeof(msg),nullptr,msg);glDeleteShader(shader);throw std::runtime_error(std::string("SCAM GPU shader: ")+msg);}
        GLuint program=glCreateProgram();glAttachShader(program,shader);glLinkProgram(program);glDeleteShader(shader);
        glGetProgramiv(program,GL_LINK_STATUS,&ok);
        if(!ok){char msg[2048]{};glGetProgramInfoLog(program,sizeof(msg),nullptr,msg);glDeleteProgram(program);throw std::runtime_error(std::string("SCAM GPU link: ")+msg);}
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
        if(!mapped)throw std::runtime_error("SCAM GPU readback failed");
        std::memcpy(data,mapped,bytes);
        if(!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER))throw std::runtime_error("SCAM GPU storage invalidated");
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
        if(in.mosaicBlock>0&&int(in.mosaicFrames.size())==frames){
            reserve(12,total*2);
            for(int f=0;f<frames;++f)put(12,size_t(offsets[f])*2,in.mosaicFrames[f]+size_t(row0[f])*w,size_t(rows[f])*w*2);
        } else reserve(12,16);
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
            throw std::runtime_error("SCAM GPU unsupported burst shape");
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
            context=scamProcessingContext(display,config);
            if(context==EGL_NO_CONTEXT)throw std::runtime_error("Cannot create GLES 3.1 context");
            const EGLint size[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};surface=eglCreatePbufferSurface(display,config,size);
            if(surface==EGL_NO_SURFACE||!eglMakeCurrent(display,surface,surface,context))throw std::runtime_error("Cannot activate GLES context");
            const auto* name=glGetString(GL_RENDERER);renderer=name?reinterpret_cast<const char*>(name):"unknown";
            slotProgram=compile(kSlotShader);
            srProgramLegacy=compile(kSuperResShaderLegacy);
            robustProgramLegacy=compile(kRobustShaderLegacy);
            erodeProgramLegacy=compile(kErodeShaderLegacy);
            srProgram=compile(kSuperResShader,true);
            robustProgram=compile(kRobustShader,true);
            dilateProgram=compile(kDilateShader);
            uplaneProgram=compile(kUPlaneShader,true);
            guideProgram=compile(kGuideShader,true);
            glGenBuffers(14,buffers);check("init");
        }catch(...){cleanup();throw;}
    }
    SuperResGpu(const SuperResGpu&)=delete;
    ~SuperResGpu(){cleanup();}

    // Robustness (per 2x2 cell, per donor frame) and the merge, all on the GPU.
    // robustShare[f]: mean robustness of frame f (for the report).
    void merge(const SuperResGpuInput& in,float noiseSlope,float noiseOffset,float mergeWeight,
               std::vector<float>& out,std::vector<float>& effective,std::vector<double>& robustShare,
               const SuperResTuning& tuneIn={}){
        if(tuneIn.legacy){mergeLegacy(in,noiseSlope,noiseOffset,mergeWeight,out,effective,robustShare);return;}
        SuperResTuning tune=tuneIn;
        const float snr=tune.snrFixed>0?float(tune.snrFixed):sabreSnr(noiseSlope,noiseOffset,tune.snrScale);
        snrKernel(tune,snr);
        if(g_superResReport)g_superResReport("SCAM SUPERRES KERNEL: snr="+std::to_string(snr)+" across="+std::to_string(tune.shrunk)+" base="+std::to_string(tune.base)
            +" along="+std::to_string(tune.stretched)+" flat="+std::to_string(tune.flat)+" thresholds="+std::to_string(tune.flat0)+"/"+std::to_string(tune.flat1));
        validate(in);
        const int w=in.w,h=in.h,w2=w/2,h2=h/2,frames=int(in.frames.size());
        out.assign(size_t(w)*h*3,0.f);effective.assign(size_t(w)*h,1.f);
        robustShare.assign(frames,1.0);
        for(GLuint program:{srProgram,robustProgram,dilateProgram,uplaneProgram,guideProgram})frameUniforms(program,in);
        auto common=[&](GLuint program){
            glUseProgram(program);
            glUniform4i(glGetUniformLocation(program,"phaseColor"),in.phaseColor[0],in.phaseColor[1],in.phaseColor[2],in.phaseColor[3]);
            glUniform2f(glGetUniformLocation(program,"noise"),noiseSlope,noiseOffset);
        };
        auto uni1i=[&](GLuint program,const char* n,int v){glUniform1i(glGetUniformLocation(program,n),v);};
        for(GLuint program:{srProgram,robustProgram,uplaneProgram,guideProgram})common(program);
        glUseProgram(uplaneProgram);glUniform1f(glGetUniformLocation(uplaneProgram,"invScale"),in.invScale);
        glUseProgram(guideProgram);
        glUniform4f(glGetUniformLocation(guideProgram,"kA"),tune.base,tune.shrunk,tune.stretched,tune.flat);
        glUniform4f(glGetUniformLocation(guideProgram,"kB"),tune.strengthScale,tune.flat0,tune.flat1,tune.texStd);
        glUniform4f(glGetUniformLocation(guideProgram,"kC"),tune.tensorNoise,0,0,0);
        // gradient noise of the raw-green tensor: a diagonal difference of four samples (+-1/4) of variance slope/4 (u domain)
        glUniform4f(glGetUniformLocation(guideProgram,"kF"),tune.rawTensor,tune.rawNoise*noiseSlope*tune.snrScale/16.f,0,0);
        glUseProgram(robustProgram);
        glUniform4f(glGetUniformLocation(robustProgram,"rb"),tune.robustK,tune.robustSigmas*tune.robustSigmas,tune.robustTex*tune.robustTex,0);
        glUseProgram(dilateProgram);
        glUniform1f(glGetUniformLocation(dilateProgram,"mergeWeight"),std::clamp(mergeWeight,0.f,1.f));
        glUniform1f(glGetUniformLocation(dilateProgram,"dil"),tune.dilate);
        glUseProgram(srProgram);
        const bool mosaic=in.mosaicBlock>0&&int(in.mosaicFrames.size())==frames;
        glUniform1i(glGetUniformLocation(srProgram,"mosaicBlock"),mosaic?in.mosaicBlock:0);
        glUniform4i(glGetUniformLocation(srProgram,"mosaicColor"),in.mosaicColor[0],in.mosaicColor[1],in.mosaicColor[2],in.mosaicColor[3]);
        std::array<float,64> gains=in.siteGain;
        if(!(gains[0]>0.f))gains.fill(1.f);
        glUniform1fv(glGetUniformLocation(srProgram,"siteGain"),64,gains.data());
        if(mosaic&&g_superResReport)g_superResReport("SCAM SUPERRES MOSAIC: block="+std::to_string(in.mosaicBlock)+" donor sites are the frames' own mosaic samples");
        glUniform1f(glGetUniformLocation(srProgram,"invScale"),in.invScale);
        glUniform4f(glGetUniformLocation(srProgram,"kD"),float(tune.grid),tune.subDetail,tune.widenBelow,tune.widenMul);
        glUniform1f(glGetUniformLocation(srProgram,"subShrink"),tune.subShrink);
        glUniform4f(glGetUniformLocation(srProgram,"kE"),float(tune.subset),0,0,0);
        std::vector<GLuint> zeros(size_t(std::max(frames,1)),0);
        reserve(9,zeros.size()*4);put(9,0,zeros.data(),zeros.size()*4);
        constexpr int stripCells=128; // 256 output rows per dispatch
        const int donors=std::max(1,frames-1);
        for(int cy0=0;cy0<h2;cy0+=stripCells){
            const int cy1=std::min(h2,cy0+stripCells),y0=2*cy0,y1=2*cy1;
            const int ry0=std::max(0,cy0-1),ry1=std::min(h2,cy1+1);
            uploadStrip({srProgram,robustProgram,dilateProgram,guideProgram},in,std::max(0,y0-4),std::min(h,y1+4));
            const int my0=std::max(0,y0-4),my1=std::min(h,y1+5);
            reserve(1,size_t(my1-my0)*w*3*4);put(1,0,in.model->data()+size_t(my0)*w*3,size_t(my1-my0)*w*3*4);
            reserve(2,size_t(my1-my0)*w*4);
            reserve(10,size_t(ry1-ry0)*w2*16);reserve(11,size_t(ry1-ry0)*w2*16);
            reserve(7,size_t(donors)*(ry1-ry0)*w2*4);
            reserve(8,size_t(donors)*(cy1-cy0)*w2*4);
            reserve(9,zeros.size()*4);
            // u plane of the model, then guide + kernel covariance per cell.
            glUseProgram(uplaneProgram);uni1i(uplaneProgram,"modelRows",my1-my0);
            glDispatchCompute(GLuint((w+7)/8),GLuint((my1-my0+7)/8),1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glUseProgram(guideProgram);
            uni1i(guideProgram,"ry0",ry0);uni1i(guideProgram,"ry1",ry1);uni1i(guideProgram,"uRow0",my0);uni1i(guideProgram,"uRows",my1-my0);
            glDispatchCompute(GLuint((w2+7)/8),GLuint((ry1-ry0+7)/8),1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            check("guide");
            if(frames>1){
                glUseProgram(robustProgram);
                uni1i(robustProgram,"ry0",ry0);uni1i(robustProgram,"ry1",ry1);
                glDispatchCompute(GLuint((w2+7)/8),GLuint((ry1-ry0+7)/8),GLuint(frames-1));
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                glUseProgram(dilateProgram);
                uni1i(dilateProgram,"ry0",ry0);uni1i(dilateProgram,"ry1",ry1);uni1i(dilateProgram,"cy0",cy0);uni1i(dilateProgram,"cy1",cy1);
                glDispatchCompute(GLuint((w2+7)/8),GLuint((cy1-cy0+7)/8),GLuint(frames-1));
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                check("robustness");
            }
            reserve(3,size_t(y1-y0)*w*3*4);reserve(4,size_t(y1-y0)*w*4);
            glUseProgram(srProgram);
            uni1i(srProgram,"cy0",cy0);uni1i(srProgram,"cy1",cy1);uni1i(srProgram,"ry0",ry0);
            uni1i(srProgram,"modelY0",my0);uni1i(srProgram,"modelRows",my1-my0);
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

    // The former merge (isotropic kernel, 3x3-minimum robustness), kept for A/B comparison (tuning legacy=1).
    // robustShare[f]: mean robustness of frame f (for the report).
    void mergeLegacy(const SuperResGpuInput& in,float noiseSlope,float noiseOffset,float mergeWeight,
               std::vector<float>& out,std::vector<float>& effective,std::vector<double>& robustShare){
        validate(in);
        const int w=in.w,h=in.h,w2=w/2,h2=h/2,frames=int(in.frames.size());
        out.assign(size_t(w)*h*3,0.f);effective.assign(size_t(w)*h,1.f);
        robustShare.assign(frames,1.0);
        frameUniforms(srProgramLegacy,in);frameUniforms(robustProgramLegacy,in);frameUniforms(erodeProgramLegacy,in);
        glUseProgram(robustProgramLegacy);glUniform2f(glGetUniformLocation(robustProgramLegacy,"noise"),noiseSlope,noiseOffset);
        glUseProgram(erodeProgramLegacy);glUniform1f(glGetUniformLocation(erodeProgramLegacy,"mergeWeight"),std::clamp(mergeWeight,0.f,1.f));
        std::vector<GLuint> zeros(size_t(std::max(frames,1)),0);
        reserve(9,zeros.size()*4);put(9,0,zeros.data(),zeros.size()*4);
        glUseProgram(srProgramLegacy);
        auto loc=[&](const char* n){return glGetUniformLocation(srProgramLegacy,n);};
        glUniform4i(loc("phaseColor"),in.phaseColor[0],in.phaseColor[1],in.phaseColor[2],in.phaseColor[3]);
        glUniform1f(loc("invScale"),in.invScale);
        glUniform2f(loc("noise"),noiseSlope,noiseOffset);
        constexpr int stripCells=128; // 256 output rows per dispatch
        const int donors=std::max(1,frames-1);
        for(int cy0=0;cy0<h2;cy0+=stripCells){
            const int cy1=std::min(h2,cy0+stripCells),y0=2*cy0,y1=2*cy1;
            const int ry0=std::max(0,cy0-1),ry1=std::min(h2,cy1+1);
            // Frame rows for the merge and for the robustness margin (one cell each side).
            uploadStrip({srProgramLegacy,robustProgramLegacy,erodeProgramLegacy},in,std::max(0,y0-4),std::min(h,y1+4));
            const int my0=std::max(0,y0-4),my1=std::min(h,y1+5);
            reserve(1,size_t(my1-my0)*w*3*4);put(1,0,in.model->data()+size_t(my0)*w*3,size_t(my1-my0)*w*3*4);
            reserve(7,size_t(donors)*(ry1-ry0)*w2*4);
            reserve(8,size_t(donors)*(cy1-cy0)*w2*4);
            reserve(9,zeros.size()*4);
            if(frames>1){
                glUseProgram(robustProgramLegacy);
                glUniform1i(glGetUniformLocation(robustProgramLegacy,"ry0"),ry0);glUniform1i(glGetUniformLocation(robustProgramLegacy,"ry1"),ry1);
                glDispatchCompute(GLuint((w2+7)/8),GLuint((ry1-ry0+7)/8),GLuint(frames-1));
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                glUseProgram(erodeProgramLegacy);
                glUniform1i(glGetUniformLocation(erodeProgramLegacy,"ry0"),ry0);glUniform1i(glGetUniformLocation(erodeProgramLegacy,"ry1"),ry1);
                glUniform1i(glGetUniformLocation(erodeProgramLegacy,"cy0"),cy0);glUniform1i(glGetUniformLocation(erodeProgramLegacy,"cy1"),cy1);
                glDispatchCompute(GLuint((w2+7)/8),GLuint((cy1-cy0+7)/8),GLuint(frames-1));
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                check("robustness");
            }
            reserve(3,size_t(y1-y0)*w*3*4);reserve(4,size_t(y1-y0)*w*4);
            glUseProgram(srProgramLegacy);
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
} // namespace scam
