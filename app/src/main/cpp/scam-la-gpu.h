#pragma once
// P308 (owner, 2026-10-20: «нам нужно перевести всё на гпу», «сделай гпу выравнивание и дай возможность выбора», «один в один
// выравнивание как Why Кам не делаем, если возможно переводим наше на гпу»): our F6 tile-local alignment (scam-hybrid.h, laGray /
// laDown / laBaseLevel / laTilePass / laLK / laMedian3 / laMotionExtent / laFrameField) on the GPU, the same steps on the same
// tiles: the gray pyramid of the base and of each donor, the L1 Lucas-Kanade field and its median, the L0 start (the better of the
// homography and the L1 field), the L0 Lucas-Kanade, the Z channel (motion extent), the acceptance test and the medians. 16
// invocations share a tile's window sums (4 tiles per workgroup, the sums reduced in shared memory); the results differ from the
// CPU's NEON sums by float rounding only. The field, the Z channel and the per-frame statistics are those laFrameField gives the
// merge (the whole-field path, before the merge); two frames are in flight (submit / collect). LaGpuWorker runs it on a thread of
// its own from the alignment on: each frame as soon as its homography is known.
// tune laGpu 1 (pref_scam_hybrid_la_engine «Где считать F6» = GPU, the app default); any failure throws and the CPU computes the field.
#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include "scam-superres-gpu.h" // scamProcessingContext (P57: a low-priority context, the viewfinder first)
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <deque>
#include <functional>
#include <future>
#include <memory>
#include <mutex>
#include <thread>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>
#include <vector>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>

namespace scam {

static const char* kLaGlslCommon = R"(
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp image2D;
)";

// gray of one canonical quad (laGrayRow): the quad's four sites normalised by black / white of each phase and clamped to [0, 1],
// -1 where a site >= 0.95 of white, else sqrt(max(sum / 4 x gain, 0) + eps); quads leaving the frame read Burst::sampleRaw
static const char* kLaGlslGray = R"(
layout(local_size_x=16,local_size_y=16) in;
layout(std430,binding=0) readonly buffer Raw{uint raw[];};
layout(r32f,binding=0) writeonly uniform highp image2D o;
uniform ivec2 rawSize;   // w, h of the RAW
uniform ivec2 outSize;   // w / 2, h / 2
uniform ivec2 origin;    // cfa & 1, cfa >> 1
uniform vec4 black;      // per phase ((y & 1) << 1 | (x & 1))
uniform vec4 inv;        // 1 / (white - black)
uniform float white;
uniform float gain;
uniform float eps;
uniform int canon;       // Burst::canonicalRggb
float rawAt(int x,int y){int i=y*rawSize.x+x;uint v=raw[i>>1];return float((i&1)==0?(v&0xFFFFu):(v>>16));}
int reflectCfa(int x,int n){for(int k=0;k<64&&(x<0||x>=n);k++){if(x<0)x=-x;else x=2*(n-1)-x;}return x;}
float sampleRaw(int x,int y){
    if(canon!=0){
        x+=origin.x;y+=origin.y;
        if(x<0||x>=rawSize.x||y<0||y>=rawSize.y){x=reflectCfa(x,rawSize.x);y=reflectCfa(y,rawSize.y);}
    }
    float b=black[((y&1)<<1)|(x&1)];
    return clamp((rawAt(x,y)-b)/(white-b),0.0,1.0);
}
void main(){
    int i=int(gl_GlobalInvocationID.x),j=int(gl_GlobalInvocationID.y);
    if(i>=outSize.x||j>=outSize.y)return;
    int X=2*i+origin.x,Y=2*j+origin.y;
    float s=0.0,m=0.0;
    if(Y+1<rawSize.y&&X+1<rawSize.x){
        int p0=(Y&1)<<1,p1=((Y+1)&1)<<1,q0=X&1,q1=(X+1)&1;
        float v[4];
        v[0]=(rawAt(X,Y)-black[p0|q0])*inv[p0|q0];
        v[1]=(rawAt(X+1,Y)-black[p0|q1])*inv[p0|q1];
        v[2]=(rawAt(X,Y+1)-black[p1|q0])*inv[p1|q0];
        v[3]=(rawAt(X+1,Y+1)-black[p1|q1])*inv[p1|q1];
        for(int k=0;k<4;k++){float u=clamp(v[k],0.0,1.0);s+=u;m=max(m,u);}
    } else {
        for(int p=0;p<4;p++){float u=sampleRaw(2*i+(p&1),2*j+(p>>1));s+=u;m=max(m,u);}
    }
    imageStore(o,ivec2(i,j),vec4(m>=0.95?-1.0:sqrt(max(0.25*s*gain,0.0)+eps)));
}
)";

// laDownRow: the 2x2 mean, -1 when any of the four has no sample
static const char* kLaGlslDown = R"(
layout(local_size_x=16,local_size_y=16) in;
uniform highp sampler2D a;
layout(r32f,binding=0) writeonly uniform highp image2D o;
uniform ivec2 outSize;
void main(){
    int i=int(gl_GlobalInvocationID.x),j=int(gl_GlobalInvocationID.y);
    if(i>=outSize.x||j>=outSize.y)return;
    float p=texelFetch(a,ivec2(2*i,2*j),0).x,q=texelFetch(a,ivec2(2*i+1,2*j),0).x;
    float r=texelFetch(a,ivec2(2*i,2*j+1),0).x,s=texelFetch(a,ivec2(2*i+1,2*j+1),0).x;
    imageStore(o,ivec2(i,j),vec4(min(min(p,q),min(r,s))<0.0?-1.0:0.25*(p+q+r+s)));
}
)";

// laBaseLevel: central differences (clamped stencil), then the sample is lost where the stencil touches a clipped quad; one texel
// of the base level holds (masked value, gx, gy)
static const char* kLaGlslBase = R"(
layout(local_size_x=16,local_size_y=16) in;
uniform highp sampler2D v;
layout(rgba32f,binding=0) writeonly uniform highp image2D ob;
layout(r32f,binding=1) writeonly uniform highp image2D ov;  // the masked value alone (the passes without gradients)
uniform ivec2 size;
float at(int x,int y){return texelFetch(v,ivec2(x,y),0).x;}
void main(){
    int i=int(gl_GlobalInvocationID.x),j=int(gl_GlobalInvocationID.y);
    if(i>=size.x||j>=size.y)return;
    int il=max(i-1,0),ir=min(i+1,size.x-1),ju=max(j-1,0),jd=min(j+1,size.y-1);
    float l=at(il,j),r=at(ir,j),u=at(i,ju),d=at(i,jd),c=at(i,j);
    float gx=(r-l)/float(max(ir-il,1)),gy=(d-u)/float(max(jd-ju,1));
    float mv=min(min(min(l,r),min(u,d)),c)<0.0?-1.0:c;
    imageStore(ob,ivec2(i,j),vec4(mv,gx,gy,0.0));
    imageStore(ov,ivec2(i,j),vec4(mv));
}
)";

// The tile programs: laT0, laTilePass (clamped donor reads), laFieldAt; LA_P = 16 invocations per tile, 4 tiles per workgroup of
// 64: the window's pixels spread over a tile's invocations (consecutive pixels of a window row in neighbouring invocations), their
// sums reduced in shared memory (the barriers in main itself, outside any flow control, as GLSL ES 3.1 asks; the steps written out
// by LA_REDUCE, 16 -> 1; MODE 1 and 2 reduce only the sums of the SSD). 16 pixels per invocation on the 16x16 windows: one
// invocation per tile left the sums serial, 64 per tile spent the pass in the reduction (67 ms a 12 MP frame on Adreno 750). The
// levels are textures: a base texel holds (masked value, gx, gy), the donor's bilinear quad is one textureGather (exact texels,
// clamped to the edge as laTilePass clamps its reads; the SSBO loads cost 98 ms a frame).
//   MODE 0  one Lucas-Kanade iteration on the field r (laLKRows: the host dispatches laIters of them); a tile whose window has too
//           few samples or a singular system stops (stop[k]) and keeps its offset, as the CPU loop's break
//   MODE 1  the L0 start: s0 = the window SSD at the homography (-1 when too few samples), the L1 field where it lowers the SSD
//   MODE 2  the acceptance test: the refinement stays where it lowers the SSD of the homography and |r| <= maxShift
static const char* kLaGlslTile = R"(
layout(local_size_x=64) in;
uniform highp sampler2D tb;  // the base level: masked value, gx, gy (MODE 0)
uniform highp sampler2D tv;  // the base level's masked value alone (MODE 1 and 2: a quarter of the texels' bytes)
uniform highp sampler2D td;  // the donor level
layout(std430,binding=4) buffer Field{vec2 r[];};
layout(std430,binding=5) buffer S0{float s0[];};
layout(std430,binding=6) buffer Flags{uint flags[];};   // bit 0: accepted, bit 1: started from the L1 field
layout(std430,binding=7) readonly buffer Coarse{vec2 r1[];};
layout(std430,binding=8) buffer Stop{uint stop[];};
uniform ivec2 dSize;
uniform ivec4 grid;        // nx, ny, win, stride
uniform int sPx;           // RAW px per level px
uniform ivec4 grid1;       // the L1 grid (MODE 1): nx, ny, win, stride
uniform int s1Px;
uniform float hm[8];
uniform float upRatio;
uniform vec4 lk;           // v, mu, kappa, minN
uniform float maxShift;
uniform int tileBase;
uniform int first;         // MODE 0, the first iteration: bit 0 the stop flags start cleared, bit 1 the field starts at zero (L1)
#define LA_P 16
#define LA_T 4
shared vec4 red[4][64];
float centre(int i){return float(grid.w*i)+0.5*float(grid.z-1);}
float rawOf(float c){return float(sPx)*c+0.5*float(sPx-1);}
vec2 t0(int i,int j){
    float cx=centre(i),cy=centre(j),X=rawOf(cx),Y=rawOf(cy);
    float den=hm[6]*X+hm[7]*Y+1.0;
    float hx=(hm[0]*X+hm[1]*Y+hm[2])/den/upRatio,hy=(hm[3]*X+hm[4]*Y+hm[5])/den/upRatio;
    return vec2((hx-0.5*float(sPx-1))/float(sPx)-cx,(hy-0.5*float(sPx-1))/float(sPx)-cy);
}
// this invocation's share of the window sums of laTilePass in four vectors (no arrays: Adreno keeps them out of the registers):
// o0 = (n, sb, sd, sbb), o1 = (sdd, sbd, gx, gy), o2 = (gxb, gyb, gxd, gyd), o3 = (axx, ayy, axy, 0)
#if MODE == 0
void partial(int ox,int oy,int win,vec2 t,bool system,int pid,bool on,out vec4 o0,out vec4 o1,out vec4 o2,out vec4 o3){
    o0=vec4(0.0);o1=vec4(0.0);o2=vec4(0.0);o3=vec4(0.0);
    if(!on)return;
    float fx0=floor(t.x),fy0=floor(t.y);
    int ix=int(clamp(fx0,-65536.0,65536.0)),iy=int(clamp(fy0,-65536.0,65536.0));
    float fx=t.x-fx0,fy=t.y-fy0,w00=(1.0-fx)*(1.0-fy),w10=fx*(1.0-fy),w01=(1.0-fx)*fy,w11=fx*fy;
    float c=max(texelFetch(tb,ivec2(ox+win/2,oy+win/2),0).x,0.0);
    vec2 invD=1.0/vec2(dSize);
    int wy=pid/win,wx=pid-wy*win;
    for(int q=pid;q<win*win;q+=LA_P){
        vec4 B=texelFetch(tb,ivec2(ox+wx,oy+wy),0);
        // the texels (x, y) .. (x + 1, y + 1): gather returns (x, y + 1), (x + 1, y + 1), (x + 1, y), (x, y)
        vec4 A=textureGather(td,(vec2(float(ox+wx+ix),float(oy+wy+iy))+1.0)*invD);
        float b0=B.x,a00=A.w,a10=A.z,a01=A.x,a11=A.y;
        float m=min(min(b0,min(a00,a10)),min(a01,a11))>=0.0?1.0:0.0;
        float b=b0-c,d=w00*a00+w10*a10+w01*a01+w11*a11-c;
        o0+=m*vec4(1.0,b,d,b*b);o1.xy+=m*vec2(d*d,b*d);
        if(system){
            float g1=B.y,g2=B.z;
            o1.zw+=m*vec2(g1,g2);o2+=m*vec4(g1*b,g2*b,g1*d,g2*d);o3.xyz+=m*vec3(g1*g1,g2*g2,g1*g2);
        }
        wx+=LA_P;
        while(wx>=win){wx-=win;wy++;}
    }
}
#else
// the SSD sums alone (o0, o1.xy) of the windows at t and, with two (MODE 1), at t2 as well: one base texel for both, each window's
// sums in the order of partial()
void partials(int ox,int oy,int win,vec2 t,vec2 t2,bool two,int pid,bool on,out vec4 o0,out vec4 o1,out vec4 p0,out vec4 p1){
    o0=vec4(0.0);o1=vec4(0.0);p0=vec4(0.0);p1=vec4(0.0);
    if(!on)return;
    float fx0=floor(t.x),fy0=floor(t.y);
    int ix=int(clamp(fx0,-65536.0,65536.0)),iy=int(clamp(fy0,-65536.0,65536.0));
    float fx=t.x-fx0,fy=t.y-fy0,w00=(1.0-fx)*(1.0-fy),w10=fx*(1.0-fy),w01=(1.0-fx)*fy,w11=fx*fy;
    float gx0=floor(t2.x),gy0=floor(t2.y);
    int jx=int(clamp(gx0,-65536.0,65536.0)),jy=int(clamp(gy0,-65536.0,65536.0));
    float hx=t2.x-gx0,hy=t2.y-gy0,v00=(1.0-hx)*(1.0-hy),v10=hx*(1.0-hy),v01=(1.0-hx)*hy,v11=hx*hy;
    float c=max(texelFetch(tv,ivec2(ox+win/2,oy+win/2),0).x,0.0);
    vec2 invD=1.0/vec2(dSize);
    int wy=pid/win,wx=pid-wy*win;
    for(int q=pid;q<win*win;q+=LA_P){
        float b0=texelFetch(tv,ivec2(ox+wx,oy+wy),0).x;
        // the texels (x, y) .. (x + 1, y + 1): gather returns (x, y + 1), (x + 1, y + 1), (x + 1, y), (x, y)
        vec4 A=textureGather(td,(vec2(float(ox+wx+ix),float(oy+wy+iy))+1.0)*invD);
        float a00=A.w,a10=A.z,a01=A.x,a11=A.y;
        float m=min(min(b0,min(a00,a10)),min(a01,a11))>=0.0?1.0:0.0;
        float b=b0-c,d=w00*a00+w10*a10+w01*a01+w11*a11-c;
        o0+=m*vec4(1.0,b,d,b*b);o1.xy+=m*vec2(d*d,b*d);
        if(two){
            vec4 E=textureGather(td,(vec2(float(ox+wx+jx),float(oy+wy+jy))+1.0)*invD);
            float e00=E.w,e10=E.z,e01=E.x,e11=E.y;
            float n=min(min(b0,min(e00,e10)),min(e01,e11))>=0.0?1.0:0.0;
            float e=v00*e00+v10*e10+v01*e01+v11*e11-c;
            p0+=n*vec4(1.0,b,e,b*b);p1.xy+=n*vec2(e*e,b*e);
        }
        wx+=LA_P;
        while(wx>=win){wx-=win;wy++;}
    }
}
#endif
struct Tile{float n,ssd,axx,ayy,axy,bx,by;};
Tile finish(vec4 o0,vec4 o1,vec4 o2,vec4 o3){
    Tile o=Tile(o0.x,0.0,0.0,0.0,0.0,0.0,0.0);
    if(o0.x<1.0)return o;
    float mb=o0.y/o0.x,md=o0.z/o0.x,dm=mb-md;
    o.ssd=max((o0.w-2.0*o1.y+o1.x)/o0.x-dm*dm,0.0);
    o.axx=o3.x;o.ayy=o3.y;o.axy=o3.z;
    o.bx=o2.x-o2.z-dm*o1.z;o.by=o2.y-o2.w-dm*o1.w;
    return o;
}
// laFieldAt: the bilinear L1 field at RAW (X, Y)
vec2 fieldAt(float X,float Y){
    float u=clamp(((X-0.5*float(s1Px-1))/float(s1Px)-0.5*float(grid1.z-1))/float(grid1.w),0.0,float(grid1.x-1));
    float w=clamp(((Y-0.5*float(s1Px-1))/float(s1Px)-0.5*float(grid1.z-1))/float(grid1.w),0.0,float(grid1.y-1));
    int i0=min(int(u),grid1.x-1),j0=min(int(w),grid1.y-1),i1=min(i0+1,grid1.x-1),j1=min(j0+1,grid1.y-1);
    float a=u-float(i0),b=w-float(j0);
    vec2 v00=r1[j0*grid1.x+i0],v10=r1[j0*grid1.x+i1],v01=r1[j1*grid1.x+i0],v11=r1[j1*grid1.x+i1];
    return (v00*(1.0-a)+v10*a)*(1.0-b)+(v01*(1.0-a)+v11*a)*b;
}
#define LA_STEP(H,NV) memoryBarrierShared();barrier();if(pid<H){for(int q=0;q<NV;q++)red[q][lid]+=red[q][lid+H];}
#define LA_REDUCE(NV,X0,X1,X2,X3) red[0][lid]=X0;red[1][lid]=X1;red[2][lid]=X2;red[3][lid]=X3; LA_STEP(8,NV) LA_STEP(4,NV) LA_STEP(2,NV) LA_STEP(1,NV) memoryBarrierShared();barrier();X0=red[0][lid-pid];X1=red[1][lid-pid];X2=red[2][lid-pid];X3=red[3][lid-pid];memoryBarrierShared();barrier();
void main(){
    int lid=int(gl_LocalInvocationID.x),pid=lid%LA_P;
    int k=tileBase+int(gl_WorkGroupID.x)*LA_T+lid/LA_P;
    bool valid=k<grid.x*grid.y;
    int kk=valid?k:0;
    int i=kk%grid.x,j=kk/grid.x,ox=grid.w*i,oy=grid.w*j,win=grid.z;
    vec2 tz=t0(i,j);
    float s=float(sPx),minN=lk.w;
#if MODE == 0
    vec2 rr=(first&2)!=0?vec2(0.0):r[kk];
    uint st=(first&1)!=0?0u:stop[kk];
    vec4 e0,e1,e2,e3;
    partial(ox,oy,win,tz+rr/s,true,pid,valid&&st==0u,e0,e1,e2,e3);
    LA_REDUCE(4,e0,e1,e2,e3)
    if(pid==0&&valid&&st==0u){
        Tile t=finish(e0,e1,e2,e3);
        if(t.n<minN)st=1u;
        else {
            float lam=lk.y*0.5*(t.axx+t.ayy)+lk.z*t.n*lk.x;
            float a=t.axx+lam,c=t.ayy+lam,b=t.axy,det=a*c-b*b;
            if(!(det>1e-30))st=1u;
            else rr+=vec2(clamp((c*t.bx-b*t.by)/det,-1.0,1.0),clamp((a*t.by-b*t.bx)/det,-1.0,1.0))*s;
        }
        r[k]=rr;stop[k]=st;
    }
#elif MODE == 1
    vec2 u=fieldAt(rawOf(centre(i)),rawOf(centre(j)));
    vec4 a0,a1,a2=vec4(0.0),a3=vec4(0.0),c0,c1,c2=vec4(0.0),c3=vec4(0.0);
    partials(ox,oy,win,tz,tz+u/s,true,pid,valid,a0,a1,c0,c1);
    LA_REDUCE(2,a0,a1,a2,a3)
    LA_REDUCE(2,c0,c1,c2,c3)
    if(pid==0&&valid){
        Tile a=finish(a0,a1,a2,a3),b=finish(c0,c1,c2,c3);
        s0[k]=a.n>=minN?a.ssd:-1.0;
        vec2 init=vec2(0.0);uint fl=0u;
        if(a.n>=minN&&(u.x!=0.0||u.y!=0.0)&&b.n>=minN&&b.ssd<a.ssd){init=u;fl=2u;}
        r[k]=init;flags[k]=fl;
    }
#else
    vec2 rr=r[kk];
    bool fin=!(isnan(rr.x)||isinf(rr.x)||isnan(rr.y)||isinf(rr.y));
    vec4 f0,f1,f2=vec4(0.0),f3=vec4(0.0),g0,g1;
    partials(ox,oy,win,tz+(fin?rr:vec2(0.0))/s,vec2(0.0),false,pid,valid,f0,f1,g0,g1);
    LA_REDUCE(2,f0,f1,f2,f3)
    if(pid==0&&valid){
        bool keep=s0[k]>=0.0&&fin&&length(rr)<=maxShift;
        if(keep&&(rr.x!=0.0||rr.y!=0.0)){Tile f=finish(f0,f1,f2,f3);keep=f.n>=minN&&f.ssd<s0[k];}
        if(!keep)r[k]=vec2(0.0); else flags[k]|=1u;
    }
#endif
}
)";

// laMedian3Rows: 3x3 median of each component (borders replicated), the 19-comparator network
static const char* kLaGlslMedian = R"(
layout(local_size_x=64) in;
layout(std430,binding=0) readonly buffer In{vec2 a[];};
layout(std430,binding=1) writeonly buffer Out{vec2 o[];};
uniform ivec2 gridSize;
void cx(inout float a,inout float b){float lo=min(a,b);b=max(a,b);a=lo;}
void main(){
    int k=int(gl_GlobalInvocationID.x);
    if(k>=gridSize.x*gridSize.y)return;
    int i=k%gridSize.x,j=k/gridSize.x;
    vec2 res;
    for(int c=0;c<2;c++){
        float p[9];int n=0;
        for(int dj=-1;dj<=1;dj++)for(int di=-1;di<=1;di++){
            vec2 v=a[clamp(j+dj,0,gridSize.y-1)*gridSize.x+clamp(i+di,0,gridSize.x-1)];
            p[n++]=c==0?v.x:v.y;
        }
        cx(p[1],p[2]);cx(p[4],p[5]);cx(p[7],p[8]);cx(p[0],p[1]);cx(p[3],p[4]);cx(p[6],p[7]);cx(p[1],p[2]);cx(p[4],p[5]);cx(p[7],p[8]);
        cx(p[0],p[3]);cx(p[5],p[8]);cx(p[4],p[7]);cx(p[3],p[6]);cx(p[1],p[4]);cx(p[2],p[5]);cx(p[4],p[7]);cx(p[4],p[2]);cx(p[6],p[4]);cx(p[4],p[2]);
        if(c==0)res.x=p[4]; else res.y=p[4];
    }
    o[k]=res;
}
)";

// laMotionExtentRows: the length of the min-max extent of the raw LK flow over the 3x3 tiles (a diverged tile counts as maxShift)
static const char* kLaGlslMotion = R"(
layout(local_size_x=64) in;
layout(std430,binding=0) readonly buffer In{vec2 a[];};
layout(std430,binding=1) writeonly buffer Out{float z[];};
uniform ivec2 gridSize;
uniform float maxShift;
vec2 at(int i,int j){
    vec2 v=a[clamp(j,0,gridSize.y-1)*gridSize.x+clamp(i,0,gridSize.x-1)];
    float x=v.x,y=v.y;
    bool fx=!(isnan(x)||isinf(x)),fy=!(isnan(y)||isinf(y));
    float m=length(v);
    if(!fx||!fy||m>maxShift){
        bool fm=!(isnan(m)||isinf(m));
        float s=fm&&m>0.0?maxShift/m:0.0;
        x=fx?x*s:maxShift;y=fy?y*s:0.0;
    }
    return vec2(x,y);
}
void main(){
    int k=int(gl_GlobalInvocationID.x);
    if(k>=gridSize.x*gridSize.y)return;
    int i=k%gridSize.x,j=k/gridSize.x;
    vec2 lo=vec2(1e9),hi=vec2(-1e9);
    for(int dj=-1;dj<=1;dj++)for(int di=-1;di<=1;di++){vec2 v=at(i+di,j+dj);lo=min(lo,v);hi=max(hi,v);}
    z[k]=length(hi-lo);
}
)";

// P364 GPU homography (tune gpuAlign; ArkCam 2.01's HomographyEngine shape: corners on a 24 x 24 grid, a pyramidal KLT per corner,
// RANSAC on the tracks), on the gray pyramid of the F6 above (sqrt of the gained quads, -1 at a clip):
// the base's corners: per grid cell the strongest Shi-Tomasi minimum eigenvalue (5 x 5 central differences, x255 scale as an 8-bit
// guide) among every 2nd pixel, a 9 x 9 patch around it free of clipped quads
static const char* kLaGlslCorners = R"(
layout(local_size_x=8,local_size_y=8) in;
uniform highp sampler2D v;   // base L0, masked value
uniform ivec2 size;
uniform ivec2 cells;
layout(std430,binding=0) writeonly buffer Corners{vec4 corners[];}; // x, y (L0 px), score (-1: none)
shared float sScore[64];
shared vec2 sPt[64];
float px(int x,int y){return texelFetch(v,ivec2(clamp(x,0,size.x-1),clamp(y,0,size.y-1)),0).x;}
void main(){
    uint tid=gl_LocalInvocationIndex;
    int cx=int(gl_WorkGroupID.x),cy=int(gl_WorkGroupID.y);
    int sx=cx*size.x/cells.x+8,ex=(cx+1)*size.x/cells.x-8,sy=cy*size.y/cells.y+8,ey=(cy+1)*size.y/cells.y-8;
    float best=-1.0;vec2 bp=vec2(0.0);
    for(int y=sy+int(gl_LocalInvocationID.y)*2;y<ey;y+=16){
        for(int x=sx+int(gl_LocalInvocationID.x)*2;x<ex;x+=16){
            float c=px(x,y);
            if(c<0.0)continue;
            float d=abs(c-px(x,y-3))+abs(c-px(x,y+3))+abs(c-px(x-3,y))+abs(c-px(x+3,y));
            if(d*255.0<12.0)continue;
            float gxx=0.0,gyy=0.0,gxy=0.0;
            for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++){
                float ix=(px(x+dx+1,y+dy)-px(x+dx-1,y+dy))*255.0,iy=(px(x+dx,y+dy+1)-px(x+dx,y+dy-1))*255.0;
                gxx+=ix*ix;gyy+=iy*iy;gxy+=ix*iy;
            }
            float tr=gxx+gyy,det=gxx*gyy-gxy*gxy,me=0.5*(tr-sqrt(max(0.0,tr*tr-4.0*det)));
            if(me<=best||me<=30.0)continue;
            bool clip=false;
            for(int dy=-5;dy<=5&&!clip;dy++)for(int dx=-5;dx<=5;dx++)if(px(x+dx,y+dy)<0.0){clip=true;break;}
            if(clip)continue;
            best=me;bp=vec2(float(x),float(y));
        }
    }
    sScore[tid]=best;sPt[tid]=bp;
    barrier();
    for(uint st=32u;st>0u;st>>=1u){
        if(tid<st&&sScore[tid+st]>sScore[tid]){sScore[tid]=sScore[tid+st];sPt[tid]=sPt[tid+st];}
        barrier();
    }
    if(tid==0u)corners[cy*cells.x+cx]=vec4(sPt[0],sScore[0],0.0);
}
)";

// the coarse translation: per shift (dx, dy) of the donor's L3 within +-R, the mean |base - donor| over the quads both hold
static const char* kLaGlslCoarse = R"(
layout(local_size_x=256) in;
uniform highp sampler2D b3;
uniform highp sampler2D d3;
uniform ivec2 size;
uniform int R;
layout(std430,binding=0) writeonly buffer Sad{vec2 sad[];};
shared vec2 red[256];
void main(){
    uint tid=gl_LocalInvocationIndex;
    int k=int(gl_WorkGroupID.x),n=2*R+1,dx=k%n-R,dy=k/n-R;
    float s=0.0,c=0.0;
    for(int i=int(tid);i<size.x*size.y;i+=256){
        int x=i%size.x,y=i/size.x,X=x+dx,Y=y+dy;
        if(X<0||Y<0||X>=size.x||Y>=size.y)continue;
        float a=texelFetch(b3,ivec2(x,y),0).x,d=texelFetch(d3,ivec2(X,Y),0).x;
        if(a<0.0||d<0.0)continue;
        s+=abs(a-d);c+=1.0;
    }
    red[tid]=vec2(s,c);
    barrier();
    for(uint st=128u;st>0u;st>>=1u){if(tid<st)red[tid]+=red[tid+st];barrier();}
    if(tid==0u)sad[k]=red[0];
}
)";

// the coarse search's best shift (the lowest mean, the first index on a tie) and its parabola sub-pixel, for the KLT
static const char* kLaGlslArgmin = R"(
layout(local_size_x=256) in;
layout(std430,binding=0) readonly buffer Sad{vec2 sad[];};
layout(std430,binding=1) writeonly buffer Start{vec4 start[];}; // L3 px x, y; z 1 = found; w the mean
uniform int R;
uniform float minCount;
shared float sv[256];
shared int si[256];
float val(int k){vec2 v=sad[k];return v.y>=minCount?v.x/v.y:1.0e30;}
void main(){
    uint tid=gl_LocalInvocationIndex;
    int n=(2*R+1)*(2*R+1);
    float best=1.0e30;int bi=-1;
    for(int k=int(tid);k<n;k+=256){float v=val(k);if(v<best){best=v;bi=k;}}
    sv[tid]=best;si[tid]=bi;
    barrier();
    for(uint st=128u;st>0u;st>>=1u){
        if(tid<st){
            float o=sv[tid+st];int oi=si[tid+st];
            if(oi>=0&&(o<sv[tid]||(o==sv[tid]&&(si[tid]<0||oi<si[tid])))){sv[tid]=o;si[tid]=oi;}
        }
        barrier();
    }
    if(tid==0u){
        int k=si[0];
        if(k<0||sv[0]>=1.0e29){start[0]=vec4(0.0,0.0,0.0,1.0e30);return;}
        int m=2*R+1,bx=k%m-R,by=k/m-R;
        float fx=0.0,fy=0.0;
        if(bx>-R&&bx<R){float a=val(k-1),b=val(k),c=val(k+1);float d=a-2.0*b+c;if(a<1.0e29&&c<1.0e29&&d>1.0e-12)fx=clamp(0.5*(a-c)/d,-0.5,0.5);}
        if(by>-R&&by<R){float a=val(k-m),b=val(k),c=val(k+m);float d=a-2.0*b+c;if(a<1.0e29&&c<1.0e29&&d>1.0e-12)fy=clamp(0.5*(a-c)/d,-0.5,0.5);}
        start[0]=vec4(float(bx)+fx,float(by)+fy,1.0,sv[0]);
    }
}
)";

// KLT of every corner: L1 (from the start homography), then L0; 9 x 9 patches of the base (its masked levels), the donor bilinear;
// a clipped quad on either side leaves its pixel out
static const char* kLaGlslKlt = R"(
layout(local_size_x=64) in;
uniform highp sampler2D b0;
uniform highp sampler2D b1;
uniform highp sampler2D d0;
uniform highp sampler2D d1;
uniform ivec2 size0;
uniform ivec2 size1;
uniform int count;
layout(std430,binding=0) readonly buffer Corners{vec4 corners[];};
layout(std430,binding=1) writeonly buffer Tracked{vec4 tracked[];}; // donor L0 x, y, status (1 tracked), mean SSD; [count] = the start
layout(std430,binding=2) readonly buffer Start{vec4 start[];};       // the coarse translation (L3 px), z 1 = found
float tex(highp sampler2D t,ivec2 s,int x,int y){return texelFetch(t,ivec2(clamp(x,0,s.x-1),clamp(y,0,s.y-1)),0).x;}
// bilinear donor sample at p (texel centres at integers); negative when a texel of the four is clipped or p leaves the level
float bil(highp sampler2D t,ivec2 s,vec2 p){
    if(p.x<0.0||p.y<0.0||p.x>float(s.x-1)||p.y>float(s.y-1))return -1.0;
    vec2 f=floor(p);ivec2 i=ivec2(f);vec2 a=p-f;
    float p00=tex(t,s,i.x,i.y),p10=tex(t,s,i.x+1,i.y),p01=tex(t,s,i.x,i.y+1),p11=tex(t,s,i.x+1,i.y+1);
    if(min(min(p00,p10),min(p01,p11))<0.0)return -1.0;
    return mix(mix(p00,p10,a.x),mix(p01,p11,a.x),a.y);
}
// one level: base patch centred at integer r, donor point q (in / out); false when lost
bool level(highp sampler2D bt,highp sampler2D dt,ivec2 s,ivec2 r,inout vec2 q,out float ssd){
    float I[81],Ix[81],Iy[81];
    float gxx=0.0,gyy=0.0,gxy=0.0;int k=0;
    for(int dy=-4;dy<=4;dy++)for(int dx=-4;dx<=4;dx++){
        float c=tex(bt,s,r.x+dx,r.y+dy),l=tex(bt,s,r.x+dx-1,r.y+dy),rr=tex(bt,s,r.x+dx+1,r.y+dy),u=tex(bt,s,r.x+dx,r.y+dy-1),d=tex(bt,s,r.x+dx,r.y+dy+1);
        bool ok=min(min(c,l),min(min(rr,u),d))>=0.0;
        I[k]=ok?c*255.0:-1.0;
        Ix[k]=ok?(rr-l)*127.5:0.0;Iy[k]=ok?(d-u)*127.5:0.0;
        gxx+=Ix[k]*Ix[k];gyy+=Iy[k]*Iy[k];gxy+=Ix[k]*Iy[k];k++;
    }
    float det=gxx*gyy-gxy*gxy;
    ssd=1.0e9;
    if(det<1.0)return false;
    float inv=1.0/det;
    for(int it=0;it<12;it++){
        float bx=0.0,by=0.0;k=0;
        for(int dy=-4;dy<=4;dy++)for(int dx=-4;dx<=4;dx++){
            if(I[k]>=0.0){
                float J=bil(dt,s,q+vec2(float(dx),float(dy)));
                if(J>=0.0){float e=I[k]-J*255.0;bx+=e*Ix[k];by+=e*Iy[k];}
            }
            k++;
        }
        float ux=clamp((gyy*bx-gxy*by)*inv,-3.0,3.0),uy=clamp((gxx*by-gxy*bx)*inv,-3.0,3.0);
        q+=vec2(ux,uy);
        if(q.x<4.0||q.y<4.0||q.x>float(s.x-5)||q.y>float(s.y-5))return false;
        if(ux*ux+uy*uy<0.0009)break;
    }
    float e2=0.0,m=0.0;k=0;
    for(int dy=-4;dy<=4;dy++)for(int dx=-4;dx<=4;dx++){
        if(I[k]>=0.0){float J=bil(dt,s,q+vec2(float(dx),float(dy)));if(J>=0.0){float e=I[k]-J*255.0;e2+=e*e;m+=1.0;}}
        k++;
    }
    if(m<40.0)return false;
    ssd=e2/m;
    return true;
}
void main(){
    int t=int(gl_GlobalInvocationID.x);
    vec4 st=start[0];
    if(t==0)tracked[count]=st;
    if(t>=count)return;
    vec4 c=corners[t];
    tracked[t]=vec4(0.0,0.0,0.0,1.0e9);
    if(c.z<0.0||st.z<0.5)return;
    vec2 r0=c.xy;
    vec2 h0=r0+8.0*st.xy; // L3 px = 8 L0 px
    // L1: L1 px i holds L0 px 2i, 2i + 1 (its centre is L0 2i + 0.5)
    ivec2 r1=ivec2(floor((r0-0.5)*0.5+0.5));
    vec2 q1=vec2(r1)+(h0-r0)*0.5;
    float e1;
    if(!level(b1,d1,size1,r1,q1,e1))return;
    vec2 q0=r0+(q1-vec2(r1))*2.0;
    float e0;
    if(!level(b0,d0,size0,ivec2(r0),q0,e0))return;
    if(e0>2500.0)return;
    tracked[t]=vec4(q0,1.0,e0);
}
)";

// The burst geometry the GPU F6 needs (Burst's fields), the grids and the tuning of laFrameField.
struct LaGpuBurst { int w=0,h=0,cfa=0;bool canonical=false;float white=0;std::array<float,4> black{}; };
struct LaGpuGrid { int nx=0,ny=0,win=16,stride=8,s=2; };
struct LaGpuTune { float mu=0.1f,kappa=2.f,maxShift=6.f;int iters=3,itersCoarse=3,medians=2; };
struct LaGpuResult { std::vector<float> field,motion;std::vector<uint32_t> flags;float median=0,p90=0,maxAbsY=0,accepted=0,fromCoarse=0;double ms=0; };
// P364: a donor's GPU homography (base canonical RAW px -> donor canonical RAW px, h33 = 1)
struct LaGpuHom { bool ok=false;std::array<float,8> h{1,0,0,0,1,0,0,0};int corners=0,tracked=0,inliers=0;float rms=0,coarseX=0,coarseY=0;double ms=0,msGray=0,msCoarse=0,msKlt=0,msRansac=0;std::string why; };

// P364: the homography of point pairs (base, donor; canonical RAW px): RANSAC of 4-point DLTs (deterministic: seeded by the
// frame), then least squares on the inliers twice. Normalised coordinates, h33 = 1, double precision.
inline bool laSolveH(const std::vector<std::array<double,4>>& pts,const std::vector<int>& use,double cx,double cy,double sc,std::array<double,9>& H){
    // normalised: x' = (x - cx) sc for both frames
    double A[8][9]={};
    for(int idx:use){
        const double x=(pts[size_t(idx)][0]-cx)*sc,y=(pts[size_t(idx)][1]-cy)*sc,u=(pts[size_t(idx)][2]-cx)*sc,v=(pts[size_t(idx)][3]-cy)*sc;
        const double r1[9]={x,y,1,0,0,0,-u*x,-u*y,u},r2[9]={0,0,0,x,y,1,-v*x,-v*y,v};
        for(int i=0;i<8;++i)for(int j=0;j<9;++j)A[i][j]+=r1[i]*r1[j]+r2[i]*r2[j];
    }
    for(int i=0;i<8;++i){ // Gauss-Jordan on the normal equations [AtA | Atb]
        int piv=i;for(int r=i+1;r<8;++r)if(std::abs(A[r][i])>std::abs(A[piv][i]))piv=r;
        if(std::abs(A[piv][i])<1e-12)return false;
        if(piv!=i)for(int j=0;j<9;++j)std::swap(A[i][j],A[piv][j]);
        const double d=A[i][i];for(int j=i;j<9;++j)A[i][j]/=d;
        for(int r=0;r<8;++r)if(r!=i){const double f=A[r][i];if(f!=0)for(int j=i;j<9;++j)A[r][j]-=f*A[i][j];}
    }
    double hn[9];for(int i=0;i<8;++i)hn[i]=A[i][8];hn[8]=1;
    // denormalise: H = T^-1 Hn T, T = [sc 0 -sc cx; 0 sc -sc cy; 0 0 1]
    const double T[9]={sc,0,-sc*cx,0,sc,-sc*cy,0,0,1},Ti[9]={1/sc,0,cx,0,1/sc,cy,0,0,1};
    double M[9]={},R[9]={};
    for(int i=0;i<3;++i)for(int j=0;j<3;++j)for(int k=0;k<3;++k)M[i*3+j]+=hn[i*3+k]*T[k*3+j];
    for(int i=0;i<3;++i)for(int j=0;j<3;++j)for(int k=0;k<3;++k)R[i*3+j]+=Ti[i*3+k]*M[k*3+j];
    if(!(std::abs(R[8])>1e-12))return false;
    for(int i=0;i<9;++i)H[size_t(i)]=R[i]/R[8];
    for(double v:H)if(!std::isfinite(v))return false;
    return true;
}
inline double laReproj(const std::array<double,9>& H,const std::array<double,4>& p){
    const double d=H[6]*p[0]+H[7]*p[1]+H[8];
    if(!(std::abs(d)>1e-9))return 1e9;
    const double u=(H[0]*p[0]+H[1]*p[1]+H[2])/d,v=(H[3]*p[0]+H[4]*p[1]+H[5])/d;
    return std::hypot(u-p[2],v-p[3]);
}
inline bool laRansacH(const std::vector<std::array<double,4>>& pts,uint32_t seed,double thr,std::array<double,9>& best,int& inliers,double& rms){
    const int n=int(pts.size());
    inliers=0;rms=0;
    if(n<8)return false;
    double cx=0,cy=0;for(const auto& p:pts){cx+=p[0];cy+=p[1];}cx/=n;cy/=n;
    double md=0;for(const auto& p:pts)md+=std::hypot(p[0]-cx,p[1]-cy);md/=n;
    const double sc=md>0?std::sqrt(2.0)/md:1.0;
    uint32_t st=seed?seed:0x9e3779b9u;
    auto rnd=[&]{st^=st<<13;st^=st>>17;st^=st<<5;return st;};
    int bestCount=-1;std::array<double,9> H{};
    int maxIt=400; // adaptive: enough draws for a 99.9 % chance of an all-inlier sample at the best ratio so far (at least 60)
    for(int it=0;it<maxIt;++it){
        int idx[4];
        for(int k=0;k<4;++k){
            for(int tries=0;tries<16;++tries){idx[k]=int(rnd()%uint32_t(n));bool dup=false;for(int j=0;j<k;++j)dup|=idx[j]==idx[k];if(!dup)break;}
        }
        if(idx[0]==idx[1]||idx[0]==idx[2]||idx[0]==idx[3]||idx[1]==idx[2]||idx[1]==idx[3]||idx[2]==idx[3])continue;
        std::array<double,9> h{};
        if(!laSolveH(pts,{idx[0],idx[1],idx[2],idx[3]},cx,cy,sc,h))continue;
        int cnt=0;for(const auto& p:pts)cnt+=laReproj(h,p)<thr;
        if(cnt>bestCount){
            bestCount=cnt;H=h;
            const double w4=std::pow(double(cnt)/n,4.0);
            if(w4>0&&w4<1)maxIt=std::clamp(int(std::ceil(std::log(0.001)/std::log(1.0-w4))),60,400);
            else if(w4>=1)maxIt=60;
        }
    }
    if(bestCount<8)return false;
    for(int pass=0;pass<3;++pass){ // least squares on the inliers, the inliers again
        std::vector<int> use;
        for(int i=0;i<n;++i)if(laReproj(H,pts[size_t(i)])<(pass==2?0.75*thr:thr))use.push_back(i);
        if(int(use.size())<8)break;
        std::array<double,9> h{};
        if(!laSolveH(pts,use,cx,cy,sc,h))break;
        H=h;
    }
    double s2=0;int cnt=0;
    for(const auto& p:pts){const double e=laReproj(H,p);if(e<thr){s2+=e*e;++cnt;}}
    inliers=cnt;rms=cnt?std::sqrt(s2/cnt):0;best=H;
    return true;
}

class LaGpu {
    EGLDisplay display=EGL_NO_DISPLAY;
    EGLContext context=EGL_NO_CONTEXT;
    EGLSurface surface=EGL_NO_SURFACE;
    GLuint pGray=0,pDown=0,pBase=0,pLk=0,pStart=0,pAccept=0,pMedian=0,pMotion=0;
    GLuint pCorners=0,pCoarse=0,pKlt=0,pArgmin=0; // P364 GPU homography (compiled on first use)
    // two frames in flight: the RAW and the outputs (field, Z, flags) alternate between two slots, the rest is the GPU's alone
    enum { kRaw, kRaw2, kR1, kR1b, kR0, kR0b, kS0, kFlags, kZ, kStop, kOutField, kOutField2, kOutZ, kOutZ2, kOutFlags, kOutFlags2,
           kRawE, kRawE2, kCorners, kSad, kStart, kTracked, kTracked2, kSlots };
    GLuint buf[kSlots]{};
    size_t cap[kSlots]{};
    enum { kTB0, kTB1, kTD0, kTD1, kTV0, kTV1,             // base levels (rgba32f: masked value, gx, gy), donor levels (r32f),
                                                         // the base levels' masked values alone (r32f)
           kTV2, kTV3, kTE0, kTE1, kTE2, kTE3,               // P364: the base's L2 / L3, the homography donor's L0..L3 (r32f)
           kTF0, kTF1, kTF2, kTF3, kTextures };              // and those of the second homography in flight
    GLuint tex[kTextures]{};
    LaGpuBurst burst;
    LaGpuGrid g0,g1;
    LaGpuTune tune;
    float v0=0,eps=0;
    int w0=0,h0=0,w1=0,h1=0; // level sizes (gray, L1)
    int w2=0,h2=0,w3=0,h3=0; // P364: L2, L3 (the homography's coarse search)
    bool homReady=false;int cornerCount=0;
    std::vector<float> cornersHost; // the base's corners (read once)
    int homSubmitted=0,homCollected=0;int homFrame[2]{};std::chrono::steady_clock::time_point homAt[2];
    int homLastSlot=0; // the slot of the last collected homography (its levels stay until a homography is submitted there)
    using Clock=std::chrono::steady_clock;
    void cleanup() noexcept {
        if(display==EGL_NO_DISPLAY)return;
        if(context!=EGL_NO_CONTEXT&&eglMakeCurrent(display,surface,surface,context)){
            for(GLuint p:{pGray,pDown,pBase,pLk,pStart,pAccept,pMedian,pMotion,pCorners,pCoarse,pKlt,pArgmin})if(p)glDeleteProgram(p);
            glDeleteBuffers(kSlots,buf);
            glDeleteTextures(kTextures,tex);
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        }
        if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);
        if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
        display=EGL_NO_DISPLAY;
    }
    static void check(const char* where){
        const GLenum e=glGetError();
        if(e!=GL_NO_ERROR)throw std::runtime_error(std::string("LA GPU ")+where+" GL error="+std::to_string(e));
    }
    // P308: the program binary cache of the merge (hybridProgramCacheDir, its key: FNV-1a 64 of the driver and the whole source),
    // files "la-*.bin" of their own (the newest 24 kept): the shot loads what the app's prewarm or an earlier shot compiled
    std::string cacheDir,driver;
    int cacheHits=0,cacheStores=0;
    std::string cacheFile(const std::string& source){
        if(cacheDir.empty())return "";
        GLint formats=0;glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS,&formats);
        if(formats<=0)return "";
        uint64_t hash=1469598103934665603ULL;
        auto mix=[&](const std::string& text){for(unsigned char c:text){hash^=c;hash*=1099511628211ULL;}hash^=0xff;hash*=1099511628211ULL;};
        mix(driver);mix(source);
        char name[40];std::snprintf(name,sizeof(name),"la-%016llx.bin",static_cast<unsigned long long>(hash));
        return cacheDir+"/"+name;
    }
    GLuint loadBinary(const std::string& file){
        std::ifstream in(file,std::ios::binary);
        if(!in)return 0;
        std::vector<char> data((std::istreambuf_iterator<char>(in)),std::istreambuf_iterator<char>());
        if(data.size()<=sizeof(GLenum))return 0;
        GLenum format=0;std::memcpy(&format,data.data(),sizeof(format));
        GLuint program=glCreateProgram();
        glProgramBinary(program,format,data.data()+sizeof(format),GLsizei(data.size()-sizeof(format)));
        GLint ok=0;glGetProgramiv(program,GL_LINK_STATUS,&ok);
        if(!ok||glGetError()!=GL_NO_ERROR){glDeleteProgram(program);std::remove(file.c_str());return 0;} // another driver: compile again
        ++cacheHits;
        return program;
    }
    void storeBinary(GLuint program,const std::string& file){
        GLint length=0;glGetProgramiv(program,GL_PROGRAM_BINARY_LENGTH,&length);
        if(length<=0){glGetError();return;}
        std::vector<char> data(sizeof(GLenum)+size_t(length));
        GLenum format=0;GLsizei written=0;
        glGetProgramBinary(program,length,&written,&format,data.data()+sizeof(GLenum));
        if(glGetError()!=GL_NO_ERROR||written<=0)return;
        std::memcpy(data.data(),&format,sizeof(format));
        const std::string tmp=file+".tmp"+std::to_string(getpid());
        {std::ofstream out(tmp,std::ios::binary|std::ios::trunc);if(!out)return;out.write(data.data(),std::streamsize(sizeof(GLenum)+size_t(written)));if(!out){std::remove(tmp.c_str());return;}}
        if(std::rename(tmp.c_str(),file.c_str())!=0){std::remove(tmp.c_str());return;}
        ++cacheStores;
    }
    void trimCache(){
        if(cacheDir.empty())return;
        DIR* d=opendir(cacheDir.c_str());if(!d)return;
        std::vector<std::pair<time_t,std::string>> files;
        while(dirent* e=readdir(d)){
            std::string name=e->d_name;if(name.rfind("la-",0)!=0)continue;
            struct stat st{};if(stat((cacheDir+"/"+name).c_str(),&st)==0)files.emplace_back(st.st_mtime,cacheDir+"/"+name);
        }
        closedir(d);
        if(files.size()<=24)return;
        std::sort(files.begin(),files.end());
        for(size_t i=0;i+24<files.size();++i)std::remove(files[i].second.c_str());
    }
    GLuint compile(const char* defs,const char* body){
        const char* sources[]={"#version 310 es\n",defs,kLaGlslCommon,body};
        const std::string file=cacheFile(std::string(sources[0])+sources[1]+sources[2]+sources[3]);
        if(!file.empty())if(GLuint cached=loadBinary(file))return cached;
        GLuint shader=glCreateShader(GL_COMPUTE_SHADER);
        glShaderSource(shader,4,sources,nullptr);
        glCompileShader(shader);
        GLint ok=0;glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
        if(!ok){char msg[2048]{};glGetShaderInfoLog(shader,sizeof(msg),nullptr,msg);glDeleteShader(shader);throw std::runtime_error(std::string("LA GPU shader: ")+msg);}
        GLuint program=glCreateProgram();
        glAttachShader(program,shader);
        if(!file.empty())glProgramParameteri(program,GL_PROGRAM_BINARY_RETRIEVABLE_HINT,GL_TRUE);
        glLinkProgram(program);glDeleteShader(shader);
        glGetProgramiv(program,GL_LINK_STATUS,&ok);
        if(!ok){char msg[2048]{};glGetProgramInfoLog(program,sizeof(msg),nullptr,msg);glDeleteProgram(program);throw std::runtime_error(std::string("LA GPU link: ")+msg);}
        if(!file.empty())storeBinary(program,file);
        return program;
    }
    void reserve(int slot,size_t bytes){
        bytes=std::max<size_t>(bytes,16);
        if(cap[slot]>=bytes)return;
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buf[slot]);
        glBufferData(GL_SHADER_STORAGE_BUFFER,GLsizeiptr(bytes),nullptr,GL_DYNAMIC_DRAW);
        cap[slot]=bytes;
    }
    void bind(int binding,int slot){glBindBufferBase(GL_SHADER_STORAGE_BUFFER,GLuint(binding),buf[slot]);}
    static void barrier(){glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT|GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);}
    void texture(int t,GLenum format,int w,int h){
        glBindTexture(GL_TEXTURE_2D,tex[t]);
        glTexStorage2D(GL_TEXTURE_2D,1,format,w,h);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    }
    void sampler(GLuint p,const char* name,int unit,int t){
        glActiveTexture(GLenum(GL_TEXTURE0+unit));glBindTexture(GL_TEXTURE_2D,tex[t]);
        glUniform1i(glGetUniformLocation(p,name),unit);
    }
    void image(int t,GLenum format){glBindImageTexture(0,tex[t],0,GL_FALSE,0,GL_WRITE_ONLY,format);}
    static void u2(GLuint p,const char* n,int a,int b){glUniform2i(glGetUniformLocation(p,n),a,b);}
    // a 2D pass over a level image (a few ms: one dispatch)
    static void dispatch2d(int w,int h){glDispatchCompute(GLuint((w+15)/16),GLuint((h+15)/16),1);}
    void upload(const uint16_t* raw,int slot){
        const size_t bytes=size_t(burst.w)*burst.h*2;
        reserve(slot,bytes);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buf[slot]);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(bytes),raw);
    }
    void gray(const uint16_t* raw,float gain,int out,int slot){
        upload(raw,slot);
        glUseProgram(pGray);
        bind(0,slot);image(out,GL_R32F);
        u2(pGray,"rawSize",burst.w,burst.h);u2(pGray,"outSize",w0,h0);u2(pGray,"origin",burst.cfa&1,burst.cfa>>1);
        float inv[4];for(int p=0;p<4;++p)inv[p]=1.f/(burst.white-burst.black[p]);
        glUniform4f(glGetUniformLocation(pGray,"black"),burst.black[0],burst.black[1],burst.black[2],burst.black[3]);
        glUniform4f(glGetUniformLocation(pGray,"inv"),inv[0],inv[1],inv[2],inv[3]);
        glUniform1f(glGetUniformLocation(pGray,"white"),burst.white);
        glUniform1f(glGetUniformLocation(pGray,"gain"),gain);
        glUniform1f(glGetUniformLocation(pGray,"eps"),eps);
        glUniform1i(glGetUniformLocation(pGray,"canon"),burst.canonical?1:0);
        dispatch2d(w0,h0);
        barrier();
    }
    void down(int in,int out){downTo(in,out,w1,h1);}
    void downTo(int in,int out,int ow,int oh){
        glUseProgram(pDown);
        sampler(pDown,"a",0,in);image(out,GL_R32F);
        u2(pDown,"outSize",ow,oh);
        dispatch2d(ow,oh);
        barrier();
    }
public:
    // P364: the homography's programs (from the binary cache: the prewarm builds them before the burst arrives)
    void prepareHomography(){
        if(!pCorners){pCorners=compile("",kLaGlslCorners);pCoarse=compile("",kLaGlslCoarse);pKlt=compile("",kLaGlslKlt);pArgmin=compile("",kLaGlslArgmin);}
    }
private:
    // P364: the homography's base side once: L2 / L3 of the base and its corners (24 x 24 cells)
    void homBase(){
        if(homReady)return;
        prepareHomography();
        w2=std::max(1,w1/2);h2=std::max(1,h1/2);w3=std::max(1,w2/2);h3=std::max(1,h2/2);
        texture(kTV2,GL_R32F,w2,h2);texture(kTV3,GL_R32F,w3,h3);
        texture(kTE0,GL_R32F,w0,h0);texture(kTE1,GL_R32F,w1,h1);texture(kTE2,GL_R32F,w2,h2);texture(kTE3,GL_R32F,w3,h3);
        texture(kTF0,GL_R32F,w0,h0);texture(kTF1,GL_R32F,w1,h1);texture(kTF2,GL_R32F,w2,h2);texture(kTF3,GL_R32F,w3,h3);
        downTo(kTV1,kTV2,w2,h2);downTo(kTV2,kTV3,w3,h3);
        constexpr int cells=24;cornerCount=cells*cells;
        reserve(kCorners,size_t(cornerCount)*16);
        glUseProgram(pCorners);
        sampler(pCorners,"v",0,kTV0);u2(pCorners,"size",w0,h0);u2(pCorners,"cells",cells,cells);
        bind(0,kCorners);
        glDispatchCompute(GLuint(cells),GLuint(cells),1);
        barrier();
        read(kCorners,cornersHost,size_t(cornerCount)*4);
        check("homography base");
        homReady=true;
    }
    // the gray level `in` -> the base texture `out` (the masked samples take the level's place, as laBaseLevel swaps them in)
    void baseLevel(int in,int out,int value,int w,int h){
        glUseProgram(pBase);
        sampler(pBase,"v",0,in);image(out,GL_RGBA32F);glBindImageTexture(1,tex[value],0,GL_FALSE,0,GL_WRITE_ONLY,GL_R32F);
        u2(pBase,"size",w,h);
        dispatch2d(w,h);
        barrier();
    }
    void tileUniforms(GLuint p,const LaGpuGrid& g,int bw,int bh,int dw,int dh,const float* hm,float upRatio,float v){
        (void)bw;(void)bh;u2(p,"dSize",dw,dh);
        glUniform4i(glGetUniformLocation(p,"grid"),g.nx,g.ny,g.win,g.stride);
        glUniform1i(glGetUniformLocation(p,"sPx"),g.s);
        glUniform4i(glGetUniformLocation(p,"grid1"),g1.nx,g1.ny,g1.win,g1.stride);
        glUniform1i(glGetUniformLocation(p,"s1Px"),g1.s);
        glUniform1fv(glGetUniformLocation(p,"hm"),8,hm);
        glUniform1f(glGetUniformLocation(p,"upRatio"),upRatio);
        glUniform4f(glGetUniformLocation(p,"lk"),v,tune.mu,tune.kappa,0.5f*float(g.win*g.win));
        glUniform1f(glGetUniformLocation(p,"maxShift"),tune.maxShift);
    }
    // a level's tiles, 4 per workgroup (LA_T), in dispatches of at most 65535 groups
    static void dispatchTiles(GLuint p,int tiles){
        const GLint base=glGetUniformLocation(p,"tileBase");
        constexpr int kPerGroup=4,kMaxGroups=65535;
        for(int t=0;t<tiles;t+=kPerGroup*kMaxGroups){
            glUniform1i(base,t);
            glDispatchCompute(GLuint(std::min(kMaxGroups,(tiles-t+kPerGroup-1)/kPerGroup)),1,1);
        }
        glFlush();
        barrier();
    }
    // laLK: iters iterations of the Lucas-Kanade program on the field in slot `field` (the first clears the tiles' stop flags and,
    // with fromZero, starts the field at zero)
    void lk(const LaGpuGrid& g,int tb,int td,int bw,int bh,int field,const float* hm,float upRatio,float v,int iters,bool fromZero){
        const int tiles=g.nx*g.ny;
        reserve(kStop,size_t(tiles)*4);
        glUseProgram(pLk);
        sampler(pLk,"tb",0,tb);sampler(pLk,"td",1,td);bind(4,field);bind(8,kStop);
        tileUniforms(pLk,g,bw,bh,bw,bh,hm,upRatio,v);
        const GLint firstU=glGetUniformLocation(pLk,"first");
        for(int it=0;it<iters;++it){glUniform1i(firstU,it>0?0:fromZero?3:1);dispatchTiles(pLk,tiles);}
    }
    void copy(int from,int to,size_t bytes){
        reserve(to,bytes);
        glBindBuffer(GL_COPY_READ_BUFFER,buf[from]);glBindBuffer(GL_COPY_WRITE_BUFFER,buf[to]);
        glCopyBufferSubData(GL_COPY_READ_BUFFER,GL_COPY_WRITE_BUFFER,0,0,GLsizeiptr(bytes));
    }
    void median(int in,int out,const LaGpuGrid& g){
        glUseProgram(pMedian);
        bind(0,in);bind(1,out);
        u2(pMedian,"gridSize",g.nx,g.ny);
        glDispatchCompute(GLuint((g.nx*g.ny+63)/64),1,1);
        barrier();
    }
    template<class T> void read(int slot,std::vector<T>& out,size_t count){
        out.resize(count);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buf[slot]);
        const void* m=glMapBufferRange(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(count*sizeof(T)),GL_MAP_READ_BIT);
        if(!m)throw std::runtime_error("LA GPU readback failed");
        std::memcpy(out.data(),m,count*sizeof(T));
        if(!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER))throw std::runtime_error("LA GPU storage invalidated");
    }
    void zero(int slot,size_t bytes){
        reserve(slot,bytes);
        std::vector<uint8_t> z(bytes,0);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buf[slot]);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(bytes),z.data());
    }
    int submitted=0,collected=0;
    Clock::time_point submittedAt[2];
public:
    std::string renderer;
    std::string cacheNote() const {return cacheDir.empty()?std::string():" (programs "+std::to_string(cacheHits)+" of 8 from the cache)";}
    // cache: the program binary cache directory ("" = compile every program)
    explicit LaGpu(const std::string& cache=""){
        cacheDir=cache;
        try{
            display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
            if(display==EGL_NO_DISPLAY||!eglInitialize(display,nullptr,nullptr)||!eglBindAPI(EGL_OPENGL_ES_API))throw std::runtime_error("EGL unavailable");
            const EGLint configAttrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
            EGLConfig config{};EGLint count=0;
            if(!eglChooseConfig(display,configAttrs,&config,1,&count)||count!=1)throw std::runtime_error("No EGL compute configuration");
            context=scamProcessingContext(display,config);
            if(context==EGL_NO_CONTEXT)throw std::runtime_error("Cannot create GLES 3.1 context");
            const EGLint size[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
            surface=eglCreatePbufferSurface(display,config,size);
            if(surface==EGL_NO_SURFACE||!eglMakeCurrent(display,surface,surface,context))throw std::runtime_error("Cannot activate GLES context");
            const auto* name=glGetString(GL_RENDERER);renderer=name?reinterpret_cast<const char*>(name):"unknown";
            const auto* version=glGetString(GL_VERSION);driver=renderer+" "+(version?reinterpret_cast<const char*>(version):"");
            pGray=compile("",kLaGlslGray);pDown=compile("",kLaGlslDown);pBase=compile("",kLaGlslBase);
            pLk=compile("#define MODE 0\n",kLaGlslTile);pStart=compile("#define MODE 1\n",kLaGlslTile);pAccept=compile("#define MODE 2\n",kLaGlslTile);
            pMedian=compile("",kLaGlslMedian);pMotion=compile("",kLaGlslMotion);
            if(cacheStores>0)trimCache();
            glGenBuffers(kSlots,buf);
            glGenTextures(kTextures,tex);
            check("init");
        }catch(...){cleanup();throw;}
    }
    // a context built on one thread (the prewarm during the alignment) and used on another: release() there, acquire() here
    void release(){eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);}
    void acquire(){if(!eglMakeCurrent(display,surface,surface,context))throw std::runtime_error("LA GPU: context not current");}
    LaGpu(const LaGpu&)=delete;
    LaGpu& operator=(const LaGpu&)=delete;
    ~LaGpu(){cleanup();}
    // The base pyramid (laGray, laDown, laBaseLevel of L1 and L0) and the grids. v0 = the base's slope / 16 (laBase.v0).
    void base(const LaGpuBurst& b,const uint16_t* raw,float epsilon,float v,const LaGpuGrid& grid0,const LaGpuGrid& grid1,const LaGpuTune& t){
        burst=b;eps=epsilon;v0=v;g0=grid0;g1=grid1;tune=t;
        w0=b.w/2;h0=b.h/2;w1=w0/2;h1=h0/2;
        if(w0<4||h0<4||b.w%2||b.h%2)throw std::runtime_error("LA GPU: frame "+std::to_string(b.w)+"x"+std::to_string(b.h));
        GLint64 block=0;glGetInteger64v(GL_MAX_SHADER_STORAGE_BLOCK_SIZE,&block);
        if(block>0&&uint64_t(b.w)*uint64_t(b.h)*2>uint64_t(block))throw std::runtime_error("LA GPU: the RAW is larger than a storage block");
        texture(kTB0,GL_RGBA32F,w0,h0);texture(kTB1,GL_RGBA32F,w1,h1);texture(kTD0,GL_R32F,w0,h0);texture(kTD1,GL_R32F,w1,h1);
        texture(kTV0,GL_R32F,w0,h0);texture(kTV1,GL_R32F,w1,h1);
        gray(raw,1.f,kTD0,kRaw); // the base's gray in the donor textures first
        down(kTD0,kTD1);
        baseLevel(kTD1,kTB1,kTV1,w1,h1);
        baseLevel(kTD0,kTB0,kTV0,w0,h0);
        check("base");
    }
    // P364: the homography of one donor in two halves, two in flight (the CPU's RANSAC of one while the GPU runs the next):
    // homSubmit - its gray pyramid, the coarse translation (L3, +-12 px = +-192 RAW px) and its best shift on the GPU, the KLT of the
    // base's corners from it; homCollect - the tracks read back, RANSAC + least squares (2 RAW px) on the CPU. gain = 1 / exposure,
    // frame = the RANSAC seed.
    int homInFlight() const {return homSubmitted-homCollected;}
    void homSubmit(const uint16_t* raw,float gain,int frame){
        if(homInFlight()>=2)throw std::runtime_error("LA GPU: two homographies in flight already");
        homBase();
        const int p=homSubmitted&1;
        homAt[p]=Clock::now();homFrame[p]=frame;
        const int L0=p?kTF0:kTE0,L1=p?kTF1:kTE1,L2=p?kTF2:kTE2,L3=p?kTF3:kTE3;
        gray(raw,gain,L0,kRawE+p);
        downTo(L0,L1,w1,h1);downTo(L1,L2,w2,h2);downTo(L2,L3,w3,h3);
        constexpr int R=12,shifts=(2*R+1)*(2*R+1);
        reserve(kSad,size_t(shifts)*8);reserve(kStart,16);
        glUseProgram(pCoarse);
        sampler(pCoarse,"b3",0,kTV3);sampler(pCoarse,"d3",1,L3);u2(pCoarse,"size",w3,h3);
        glUniform1i(glGetUniformLocation(pCoarse,"R"),R);bind(0,kSad);
        glDispatchCompute(GLuint(shifts),1,1);
        barrier();
        glUseProgram(pArgmin);
        glUniform1i(glGetUniformLocation(pArgmin,"R"),R);glUniform1f(glGetUniformLocation(pArgmin,"minCount"),0.3f*float(w3)*float(h3));
        bind(0,kSad);bind(1,kStart);
        glDispatchCompute(1,1,1);
        barrier();
        reserve(kTracked+p,size_t(cornerCount+1)*16);
        glUseProgram(pKlt);
        sampler(pKlt,"b0",0,kTV0);sampler(pKlt,"b1",1,kTV1);sampler(pKlt,"d0",2,L0);sampler(pKlt,"d1",3,L1);
        u2(pKlt,"size0",w0,h0);u2(pKlt,"size1",w1,h1);glUniform1i(glGetUniformLocation(pKlt,"count"),cornerCount);
        bind(0,kCorners);bind(1,kTracked+p);bind(2,kStart);
        glDispatchCompute(GLuint((cornerCount+63)/64),1,1);
        barrier();
        check("homography");
        glFlush();
        ++homSubmitted;
    }
    LaGpuHom homCollect(){
        if(homCollected>=homSubmitted)throw std::runtime_error("LA GPU: no homography in flight");
        const int p=homCollected&1;
        LaGpuHom out;
        std::vector<float> tracked;read(kTracked+p,tracked,size_t(cornerCount+1)*4);
        check("homography readback");
        const int frame=homFrame[p];
        ++homCollected;homLastSlot=p;
        const float* st=tracked.data()+size_t(cornerCount)*4;
        out.coarseX=16.f*st[0];out.coarseY=16.f*st[1]; // RAW px
        out.msKlt=std::chrono::duration<double,std::milli>(Clock::now()-homAt[p]).count();
        std::vector<std::array<double,4>> pts;
        for(int k=0;k<cornerCount;++k){
            if(cornersHost[size_t(k)*4+2]>=0.f)++out.corners;
            if(tracked[size_t(k)*4+2]<0.5f)continue;
            // L0 px c -> canonical RAW 2 c + 0.5
            pts.push_back({2.0*cornersHost[size_t(k)*4]+0.5,2.0*cornersHost[size_t(k)*4+1]+0.5,2.0*tracked[size_t(k)*4]+0.5,2.0*tracked[size_t(k)*4+1]+0.5});
        }
        out.tracked=int(pts.size());
        if(st[2]<0.5f){out.why="no coarse match";out.ms=std::chrono::duration<double,std::milli>(Clock::now()-homAt[p]).count();return out;}
        std::array<double,9> H{};double rms=0;
        const bool solved=laRansacH(pts,0x9e3779b9u^uint32_t(frame*2654435761u),2.0,H,out.inliers,rms);
        out.rms=float(rms);
        out.msRansac=std::chrono::duration<double,std::milli>(Clock::now()-homAt[p]).count();
        if(!solved)out.why="RANSAC failed";
        else if(out.inliers<60||out.inliers<0.2*out.tracked)out.why="too few inliers"; // (a night ultrashort frame: ~1/3 of the tracks)
        else {for(int i=0;i<8;++i)out.h[size_t(i)]=float(H[size_t(i)]);out.ok=true;out.why="ok";}
        out.ms=std::chrono::duration<double,std::milli>(Clock::now()-homAt[p]).count();
        return out;
    }
    // Queue the field of one donor (laFrameField with its motion output): homography h (8) / upRatio, gain = 1 / exposure. At most
    // two frames in flight: the GPU runs the next one while collect() reads one back and takes its statistics.
    void submit(const uint16_t* raw,float gain,const float* hm,float upRatio){
        if(submitted-collected>=2)throw std::runtime_error("LA GPU: two frames in flight already");
        const int p=submitted&1;
        submittedAt[p]=Clock::now();
        gray(raw,gain,kTD0,kRaw+p);
        down(kTD0,kTD1);
        fieldPasses(kTD0,kTD1,hm,upRatio);
    }
    // P364: the field of the donor whose homography was collected last, on its gray levels (the same gray, the same field: no second
    // upload of the RAW)
    void submitFromHomography(const float* hm,float upRatio){
        if(submitted-collected>=2)throw std::runtime_error("LA GPU: two frames in flight already");
        submittedAt[submitted&1]=Clock::now();
        fieldPasses(homLastSlot?kTF0:kTE0,homLastSlot?kTF1:kTE1,hm,upRatio);
    }
    void fieldPasses(int D0,int D1,const float* hm,float upRatio){
        const int p=submitted&1;
        const int n1=g1.nx*g1.ny,n0=g0.nx*g0.ny;
        // L1: Lucas-Kanade from the homography, one median
        reserve(kR1,size_t(n1)*8);reserve(kR1b,size_t(n1)*8);
        if(tune.itersCoarse<=0)zero(kR1,size_t(n1)*8);
        lk(g1,kTB1,D1,w1,h1,kR1,hm,upRatio,v0*0.25f,std::max(0,tune.itersCoarse),true);
        median(kR1,kR1b,g1);
        // L0 start: the better of the homography and the L1 field
        reserve(kR0,size_t(n0)*8);reserve(kR0b,size_t(n0)*8);reserve(kS0,size_t(n0)*4);reserve(kFlags,size_t(n0)*4);
        glUseProgram(pStart);
        sampler(pStart,"tv",0,kTV0);sampler(pStart,"td",1,D0);bind(4,kR0);bind(5,kS0);bind(6,kFlags);bind(7,kR1b);
        tileUniforms(pStart,g0,w0,h0,w0,h0,hm,upRatio,v0);
        dispatchTiles(pStart,n0);
        // L0 Lucas-Kanade
        lk(g0,kTB0,D0,w0,h0,kR0,hm,upRatio,v0,std::max(0,tune.iters),false);
        // the Z channel from the raw LK flow, before the acceptance test and any median
        reserve(kZ,size_t(n0)*4);
        glUseProgram(pMotion);
        bind(0,kR0);bind(1,kZ);
        u2(pMotion,"gridSize",g0.nx,g0.ny);glUniform1f(glGetUniformLocation(pMotion,"maxShift"),tune.maxShift);
        glDispatchCompute(GLuint((n0+63)/64),1,1);
        barrier();
        // acceptance
        glUseProgram(pAccept);
        sampler(pAccept,"tv",0,kTV0);sampler(pAccept,"td",1,D0);bind(4,kR0);bind(5,kS0);bind(6,kFlags);bind(7,kR1b);
        tileUniforms(pAccept,g0,w0,h0,w0,h0,hm,upRatio,v0);
        dispatchTiles(pAccept,n0);
        // medians (ping-pong; the result ends in kR0)
        for(int m=0;m<std::max(0,tune.medians);++m){
            median(kR0,kR0b,g0);
            copy(kR0b,kR0,size_t(n0)*8);
            barrier();
        }
        // this frame's outputs, out of the way of the next one
        copy(kR0,kOutField+p,size_t(n0)*8);copy(kZ,kOutZ+p,size_t(n0)*4);copy(kFlags,kOutFlags+p,size_t(n0)*4);
        barrier();
        check("frame");
        glFlush();
        ++submitted;
    }
    // The oldest queued frame: its field, Z channel and the statistics of laFrameField; ms = from its submit to here.
    LaGpuResult collect(){
        if(collected>=submitted)throw std::runtime_error("LA GPU: no frame in flight");
        const int p=collected&1,n0=g0.nx*g0.ny;
        LaGpuResult res;
        read(kOutField+p,res.field,size_t(n0)*2);
        read(kOutZ+p,res.motion,size_t(n0));
        read(kOutFlags+p,res.flags,size_t(n0));
        check("readback");
        ++collected;
        res.ms=std::chrono::duration<double,std::milli>(Clock::now()-submittedAt[p]).count();
        return res;
    }
    // The statistics of laFrameField from a collected frame (its flags released): off the GPU thread, which then only feeds the GPU.
    static void stats(LaGpuResult& res){
        const size_t n0=res.flags.size();
        std::vector<float> mag(n0);float maxY=0;long acc=0,fromCoarse=0;
        for(size_t k=0;k<n0;++k){
            mag[k]=std::hypot(res.field[k*2],res.field[k*2+1]);maxY=std::max(maxY,std::abs(res.field[k*2+1]));
            acc+=res.flags[k]&1u;fromCoarse+=(res.flags[k]>>1)&1u;
        }
        if(n0){
            const size_t mid=n0/2,p90=std::min(n0-1,n0*9/10);
            std::nth_element(mag.begin(),mag.begin()+mid,mag.end());res.median=mag[mid];
            std::nth_element(mag.begin(),mag.begin()+p90,mag.end());res.p90=mag[p90];
        }
        res.maxAbsY=maxY;res.accepted=float(acc)/float(std::max<size_t>(1,n0));res.fromCoarse=float(fromCoarse)/float(std::max<size_t>(1,n0));
        std::vector<uint32_t>().swap(res.flags);
    }
};

// P364: the GPU F6 / alignment context with its programs, made on the worker's EGL warm-up thread while it waits for the burst
// (its checks then run beside it); the F6 worker takes it instead of making its own (null: none was started, or it failed)
struct LaGpuPrewarm { std::mutex m;std::condition_variable cv;bool started=false,done=false;std::unique_ptr<LaGpu> gpu; };
inline LaGpuPrewarm& laGpuPrewarm(){static LaGpuPrewarm p;return p;}
inline void laGpuPrewarmMake(const std::string& cache){
    LaGpuPrewarm& p=laGpuPrewarm();
    {std::lock_guard<std::mutex> l(p.m);p.started=true;}
    std::unique_ptr<LaGpu> g;
    try{g=std::make_unique<LaGpu>(cache);g->prepareHomography();g->release();}catch(...){g.reset();}
    {std::lock_guard<std::mutex> l(p.m);p.gpu=std::move(g);p.done=true;}
    p.cv.notify_all();
}
inline std::unique_ptr<LaGpu> laGpuPrewarmTake(){
    LaGpuPrewarm& p=laGpuPrewarm();
    std::unique_lock<std::mutex> l(p.m);
    if(!p.started)return nullptr;
    p.cv.wait(l,[&]{return p.done;});
    return std::move(p.gpu);
}

// P308 (owner, 2026-10-20: «постарайся ускорить гпу выравнивание», «на качество ускорение не должно влиять»): the GPU F6 on a thread
// of its own, where its context lives: built (context, programs, the base pyramid) while the frames align, then each frame handed
// over as soon as its homography is known (the alignment's groups; the CPU aligns the next ones meanwhile), two in flight. The
// same submit / collect as frame by frame: the same fields. A frame handed over again with another homography is computed again.
class LaGpuWorker {
public:
    struct Job { int f=0;const uint16_t* raw=nullptr;float gain=1.f;std::array<float,8> h{};float up=1.f; };
    using Build=std::function<std::unique_ptr<LaGpu>()>;
    LaGpuWorker(int frames,Build build):results(size_t(frames)),ready(size_t(frames),0),fed(size_t(frames)){
        th=std::thread([this,build]{run(build);});
    }
    LaGpuWorker(const LaGpuWorker&)=delete;
    LaGpuWorker& operator=(const LaGpuWorker&)=delete;
    ~LaGpuWorker(){{std::lock_guard<std::mutex> l(m);stop=true;}cv.notify_all();if(th.joinable())th.join();}
    void add(const Job& job){
        std::lock_guard<std::mutex> l(m);
        if(failed||closed||job.f<0||size_t(job.f)>=fed.size())return;
        Job& last=fed[size_t(job.f)];
        if(same(last,job))return;
        last=job;ready[size_t(job.f)]=0;queue.push_back(job);
        cv.notify_all();
    }
    // the frames' fields ready; false when the GPU failed (error())
    bool wait(const std::vector<int>& frames){
        std::unique_lock<std::mutex> l(m);
        cv.wait(l,[&]{if(failed)return true;for(int f:frames)if(!ready[size_t(f)])return false;return true;});
        return !failed;
    }
    int readyCount(const std::vector<int>& frames){
        std::lock_guard<std::mutex> l(m);
        int k=0;for(int f:frames)k+=ready[size_t(f)]?1:0;
        return k;
    }
    LaGpuResult take(int f){std::lock_guard<std::mutex> l(m);return std::move(results[size_t(f)]);}
    // P364: a donor's GPU homography, before any queued field (the alignment waits for it)
    std::shared_future<LaGpuHom> estimate(int f,const uint16_t* raw,float gain,bool field=false){
        auto pr=std::make_shared<std::promise<LaGpuHom>>();
        std::shared_future<LaGpuHom> fut=pr->get_future().share();
        {
            std::lock_guard<std::mutex> l(m);
            if(failed||closed||stop){LaGpuHom h;h.why=failed?"GPU failed: "+failure:"closed";pr->set_value(h);return fut;}
            homQueue.push_back(HomJob{f,raw,gain,pr,field});
        }
        cv.notify_all();
        return fut;
    }
    // only these frames still matter: the others leave the queue (the merge dropped them after the alignment handed them over)
    void retain(const std::vector<int>& frames){
        std::lock_guard<std::mutex> l(m);
        std::vector<char> keep(fed.size(),0);
        for(int f:frames)if(f>=0&&size_t(f)<keep.size())keep[size_t(f)]=1;
        std::deque<Job> left;
        for(const Job& j:queue)if(keep[size_t(j.f)])left.push_back(j);
        queue.swap(left);
    }
    // no more frames: the thread ends after the frames in flight (the queue dropped) and frees the context there
    void close(){{std::lock_guard<std::mutex> l(m);closed=true;queue.clear();}cv.notify_all();}
    std::string error(){std::lock_guard<std::mutex> l(m);return failure;}
    std::string renderer(){std::lock_guard<std::mutex> l(m);return name;}
    double baseMs(){std::lock_guard<std::mutex> l(m);return built;}
private:
    std::mutex m;std::condition_variable cv;
    std::deque<Job> queue;bool closed=false,stop=false,failed=false;
    struct HomJob { int f=0;const uint16_t* raw=nullptr;float gain=1.f;std::shared_ptr<std::promise<LaGpuHom>> pr;bool field=false; };
    std::deque<HomJob> homQueue; // P364
    void failHoms(const std::string& why){ // under the lock: every waiting estimate gets an answer
        for(HomJob& j:homQueue){LaGpuHom h;h.why=why;j.pr->set_value(h);}
        homQueue.clear();
    }
    std::vector<LaGpuResult> results;std::vector<char> ready;std::vector<Job> fed;
    std::string failure,name;double built=0;
    std::thread th;
    static bool same(const Job& a,const Job& b){return a.raw==b.raw&&a.gain==b.gain&&a.h==b.h&&a.up==b.up;}
    void run(const Build& build){
        const auto t0=std::chrono::steady_clock::now();
        std::unique_ptr<LaGpu> gpu;
        try{gpu=build();}
        catch(const std::exception& e){std::lock_guard<std::mutex> l(m);failed=true;failure=e.what();failHoms(failure);cv.notify_all();return;}
        {
            std::lock_guard<std::mutex> l(m);
            built=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();name=gpu->renderer+gpu->cacheNote();
        }
        std::deque<Job> inflight;
        std::deque<HomJob> homInflight; // P364
        try{
            for(;;){
                Job job;bool have=false;
                HomJob hom;bool haveHom=false,collectHom=false;
                {
                    std::unique_lock<std::mutex> l(m);
                    if(inflight.empty()&&homInflight.empty())cv.wait(l,[&]{return stop||closed||!queue.empty()||!homQueue.empty();});
                    if(stop)break;
                    if(!homQueue.empty()&&homInflight.size()<2){hom=homQueue.front();homQueue.pop_front();haveHom=true;}
                    else if(!homInflight.empty())collectHom=true; // the alignment waits for these first
                    else if(!queue.empty()&&inflight.size()<2){job=queue.front();queue.pop_front();have=true;}
                    else if(inflight.empty()&&homInflight.empty())break; // closed, nothing left
                }
                if(haveHom){ // P364: the alignment waits for it; two in flight
                    try{gpu->homSubmit(hom.raw,hom.gain,hom.f);homInflight.push_back(hom);}
                    catch(const std::exception& e){LaGpuHom r;r.why=std::string("GPU error: ")+e.what();while(glGetError()!=GL_NO_ERROR){}hom.pr->set_value(r);}
                    continue;
                }
                if(collectHom){
                    HomJob done=homInflight.front();homInflight.pop_front();
                    LaGpuHom r;
                    bool collected=false;
                    try{r=gpu->homCollect();collected=true;}
                    catch(const std::exception& e){r=LaGpuHom{};r.why=std::string("GPU error: ")+e.what();while(glGetError()!=GL_NO_ERROR){}}
                    // P364: the frame's field right away on the levels the homography just used (the alignment hands it over with this
                    // homography: add() then finds it fed), before the next homography takes that slot
                    if(collected&&r.ok&&done.field){
                        Job fj;fj.f=done.f;fj.raw=done.raw;fj.gain=done.gain;fj.h=r.h;fj.up=1.f;
                        if(inflight.size()>=2){ // a slot first
                            LaGpuResult fr=gpu->collect();
                            const Job old=inflight.front();inflight.pop_front();
                            std::lock_guard<std::mutex> l(m);
                            if(same(fed[size_t(old.f)],old)){results[size_t(old.f)]=std::move(fr);ready[size_t(old.f)]=1;}
                        }
                        {
                            std::lock_guard<std::mutex> l(m);
                            if(!failed&&!closed&&fj.f>=0&&size_t(fj.f)<fed.size()){
                                fed[size_t(fj.f)]=fj;ready[size_t(fj.f)]=0;
                                std::deque<Job> left;for(const Job& j:queue)if(j.f!=fj.f)left.push_back(j);queue.swap(left);
                            } else fj.f=-1;
                        }
                        if(fj.f>=0){gpu->submitFromHomography(fj.h.data(),fj.up);inflight.push_back(fj);}
                    }
                    done.pr->set_value(r);
                    cv.notify_all();
                    continue;
                }
                if(have){gpu->submit(job.raw,job.gain,job.h.data(),job.up);inflight.push_back(job);continue;}
                LaGpuResult r=gpu->collect();
                const Job done=inflight.front();inflight.pop_front();
                std::lock_guard<std::mutex> l(m);
                if(same(fed[size_t(done.f)],done)){results[size_t(done.f)]=std::move(r);ready[size_t(done.f)]=1;}
                cv.notify_all();
            }
        }catch(const std::exception& e){std::lock_guard<std::mutex> l(m);failed=true;failure=e.what();cv.notify_all();}
        {std::lock_guard<std::mutex> l(m);for(HomJob& j:homInflight)homQueue.push_front(j);homInflight.clear();failHoms(failed?"GPU failed: "+failure:"closed");}
        gpu.reset(); // its objects and context, on the thread that holds them
    }
};

} // namespace scam
