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

// The burst geometry the GPU F6 needs (Burst's fields), the grids and the tuning of laFrameField.
struct LaGpuBurst { int w=0,h=0,cfa=0;bool canonical=false;float white=0;std::array<float,4> black{}; };
struct LaGpuGrid { int nx=0,ny=0,win=16,stride=8,s=2; };
struct LaGpuTune { float mu=0.1f,kappa=2.f,maxShift=6.f;int iters=3,itersCoarse=3,medians=2; };
struct LaGpuResult { std::vector<float> field,motion;std::vector<uint32_t> flags;float median=0,p90=0,maxAbsY=0,accepted=0,fromCoarse=0;double ms=0; };

class LaGpu {
    EGLDisplay display=EGL_NO_DISPLAY;
    EGLContext context=EGL_NO_CONTEXT;
    EGLSurface surface=EGL_NO_SURFACE;
    GLuint pGray=0,pDown=0,pBase=0,pLk=0,pStart=0,pAccept=0,pMedian=0,pMotion=0;
    // two frames in flight: the RAW and the outputs (field, Z, flags) alternate between two slots, the rest is the GPU's alone
    enum { kRaw, kRaw2, kR1, kR1b, kR0, kR0b, kS0, kFlags, kZ, kStop, kOutField, kOutField2, kOutZ, kOutZ2, kOutFlags, kOutFlags2, kSlots };
    GLuint buf[kSlots]{};
    size_t cap[kSlots]{};
    enum { kTB0, kTB1, kTD0, kTD1, kTV0, kTV1, kTextures }; // base levels (rgba32f: masked value, gx, gy), donor levels (r32f),
                                                         // the base levels' masked values alone (r32f)
    GLuint tex[kTextures]{};
    LaGpuBurst burst;
    LaGpuGrid g0,g1;
    LaGpuTune tune;
    float v0=0,eps=0;
    int w0=0,h0=0,w1=0,h1=0; // level sizes (gray, L1)
    using Clock=std::chrono::steady_clock;
    void cleanup() noexcept {
        if(display==EGL_NO_DISPLAY)return;
        if(context!=EGL_NO_CONTEXT&&eglMakeCurrent(display,surface,surface,context)){
            for(GLuint p:{pGray,pDown,pBase,pLk,pStart,pAccept,pMedian,pMotion})if(p)glDeleteProgram(p);
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
    void down(int in,int out){
        glUseProgram(pDown);
        sampler(pDown,"a",0,in);image(out,GL_R32F);
        u2(pDown,"outSize",w1,h1);
        dispatch2d(w1,h1);
        barrier();
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
    // Queue the field of one donor (laFrameField with its motion output): homography h (8) / upRatio, gain = 1 / exposure. At most
    // two frames in flight: the GPU runs the next one while collect() reads one back and takes its statistics.
    void submit(const uint16_t* raw,float gain,const float* hm,float upRatio){
        if(submitted-collected>=2)throw std::runtime_error("LA GPU: two frames in flight already");
        const int p=submitted&1;
        submittedAt[p]=Clock::now();
        gray(raw,gain,kTD0,kRaw+p);
        down(kTD0,kTD1);
        const int n1=g1.nx*g1.ny,n0=g0.nx*g0.ny;
        // L1: Lucas-Kanade from the homography, one median
        reserve(kR1,size_t(n1)*8);reserve(kR1b,size_t(n1)*8);
        if(tune.itersCoarse<=0)zero(kR1,size_t(n1)*8);
        lk(g1,kTB1,kTD1,w1,h1,kR1,hm,upRatio,v0*0.25f,std::max(0,tune.itersCoarse),true);
        median(kR1,kR1b,g1);
        // L0 start: the better of the homography and the L1 field
        reserve(kR0,size_t(n0)*8);reserve(kR0b,size_t(n0)*8);reserve(kS0,size_t(n0)*4);reserve(kFlags,size_t(n0)*4);
        glUseProgram(pStart);
        sampler(pStart,"tv",0,kTV0);sampler(pStart,"td",1,kTD0);bind(4,kR0);bind(5,kS0);bind(6,kFlags);bind(7,kR1b);
        tileUniforms(pStart,g0,w0,h0,w0,h0,hm,upRatio,v0);
        dispatchTiles(pStart,n0);
        // L0 Lucas-Kanade
        lk(g0,kTB0,kTD0,w0,h0,kR0,hm,upRatio,v0,std::max(0,tune.iters),false);
        // the Z channel from the raw LK flow, before the acceptance test and any median
        reserve(kZ,size_t(n0)*4);
        glUseProgram(pMotion);
        bind(0,kR0);bind(1,kZ);
        u2(pMotion,"gridSize",g0.nx,g0.ny);glUniform1f(glGetUniformLocation(pMotion,"maxShift"),tune.maxShift);
        glDispatchCompute(GLuint((n0+63)/64),1,1);
        barrier();
        // acceptance
        glUseProgram(pAccept);
        sampler(pAccept,"tv",0,kTV0);sampler(pAccept,"td",1,kTD0);bind(4,kR0);bind(5,kS0);bind(6,kFlags);bind(7,kR1b);
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
    std::vector<LaGpuResult> results;std::vector<char> ready;std::vector<Job> fed;
    std::string failure,name;double built=0;
    std::thread th;
    static bool same(const Job& a,const Job& b){return a.raw==b.raw&&a.gain==b.gain&&a.h==b.h&&a.up==b.up;}
    void run(const Build& build){
        const auto t0=std::chrono::steady_clock::now();
        std::unique_ptr<LaGpu> gpu;
        try{gpu=build();}
        catch(const std::exception& e){std::lock_guard<std::mutex> l(m);failed=true;failure=e.what();cv.notify_all();return;}
        {
            std::lock_guard<std::mutex> l(m);
            built=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();name=gpu->renderer+gpu->cacheNote();
        }
        std::deque<Job> inflight;
        try{
            for(;;){
                Job job;bool have=false;
                {
                    std::unique_lock<std::mutex> l(m);
                    if(inflight.empty())cv.wait(l,[&]{return stop||closed||!queue.empty();});
                    if(stop)break;
                    if(!queue.empty()&&inflight.size()<2){job=queue.front();queue.pop_front();have=true;}
                    else if(inflight.empty())break; // closed, nothing left
                }
                if(have){gpu->submit(job.raw,job.gain,job.h.data(),job.up);inflight.push_back(job);continue;}
                LaGpuResult r=gpu->collect();
                const Job done=inflight.front();inflight.pop_front();
                std::lock_guard<std::mutex> l(m);
                if(same(fed[size_t(done.f)],done)){results[size_t(done.f)]=std::move(r);ready[size_t(done.f)]=1;}
                cv.notify_all();
            }
        }catch(const std::exception& e){std::lock_guard<std::mutex> l(m);failed=true;failure=e.what();cv.notify_all();}
        gpu.reset(); // its objects and context, on the thread that holds them
    }
};

} // namespace scam
