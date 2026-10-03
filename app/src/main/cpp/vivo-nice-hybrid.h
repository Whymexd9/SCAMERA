#pragma once
// SCAM HDR "LMC hybrid" merge (no neural model, any GLES 3.1 GPU):
//   kernel      GCam 6.1 Sabre: every output colour is a kernel-weighted average of the burst's own RAW
//               sites of that colour (no demosaic), kernel = anisotropic covariance from the structure
//               tensor of the base frame (narrow across an edge, long along it, wide where flat),
//               base frame added last with a wider kernel where the donors left little weight.
//   front end   LMC 9.6 (libgcastartup): noise-subtracting rejection D^2 = max(d^2 - (N_ref + N_cur), 0),
//               normalised by max(2 min(V_ref, V_cur), N), colour-difference multiplier, 5x5 dilation,
//               per-frame scalar weight A = min(50, ((TET_f/TET_b)^2 read_b/read_f)^fwe) with the
//               kernel widening LUT {10 -> 1, 30 -> 1.414}.
//   Bento       an ultrashort frame (TET_base/8) replaces the clipped highlights of the base: the base
//               and the normal frames lose their weight inside the highlight mask, the ultrashort frame
//               is merged only there. Output stays in base-frame units, so highlights run above 1.0.
//   Shasta      bracketed long frames (about 4x the base) merge into the shadows with their read-noise
//               weight; their clipped samples are rejected; frames softer than 0.8 of the base are dropped.
// Input frames are plain Bayer uint16 of one sensor (any bit depth: black/white per shot), aligned by
// one backward homography each (vivo CRE LK/RANSAC or the bundled copy). Output: linear RGB (w*h*3,
// float) in base units, canonical RGGB geometry (the caller restores the sensor origin), plus the
// effective-frames map and the merged Bayer RAW for the DNG.
#include "vivo-nice-superres-gpu.h"
#include <atomic>
#include <chrono>
#include <fstream>
#include <mutex>
#include <sstream>

namespace vivo_nice {

enum HybridRole { kRoleNormal=1, kRoleBracketed=3, kRoleUltrashort=5 };

struct HybridFrame {
    const uint16_t* raw=nullptr;
    int role=kRoleNormal;
    float exposure=1;       // TET ratio to the base frame (bracketed > 1, ultrashort < 1)
    unsigned iso=0;
    float slope=0,offset=0; // noise model of this frame in its own exposure (normalised 0..1 units)
    float orderMs=0;        // capture time relative to the base (for the report only)
};

struct HybridInput {
    int w=0,h=0,cfa=0;
    float white=0;
    std::array<float,4> black{};
    std::vector<HybridFrame> frames; // frames[0] = base (normal)
    bool diagnostics=false,mergedDng=false;
    bool clipFlags=false; // header flag 4: append the per-pixel clip flags (uint8 per output pixel) after the effective map
    // Output grid (NCH v11): 1 = sensor grid (today), 2 = the Sabre 6.1 2x grid (four sub-positions +-0.25 px per
    // sensor pixel, RGB 2w x 2h). The app runs its whole pipeline on that grid and resizes at the end.
    int grid=1;
};

// Tuning (LMC-like; a "key value" text file in the job dir or the external files dir overrides it).
// Frames per burst the shaders hold in uniform arrays (vivo ships 30 N frames + ultrashort + up to 3 bracketed).
constexpr int kHybridMaxFrames=48;
// Frames one merge can hold on the GPU: the per-frame geometry of kCommonShader (vivo-nice-superres-gpu.h: frameOffset,
// frameRow0, frameRows, hA, hB, upRatio) is declared [32]; a frame index past it reads undefined offsets (SSBO reads out of
// bounds). hybridReconstruct drops the normal donors farthest in time beyond this.
constexpr int kHybridGpuFrames=32;

struct HybridTuning {
    // rejection (LMC 9.6 rejection.cl constants)
    float cdm=0.07f;             // color_difference_multiplier (RGB)
    float boost=6.0f;            // extra_motion_robustness_boost
    float boostEnable=0.f;       // flow inhomogeneity is unknown with one homography per frame: off by default
    float varianceThreshold=25.f;// motion_robustness_boost_variance_threshold
    float filterVariance=0.5f;   // variance scale of the bilinear donor sample (kFilterVarianceScale analogue)
    float dilateOffset=0.2f,dilateScale=2.f; // DilateMask: rej = (sum25 - 0.2) / 2
    float dilateFloor=0.15f;     // per-cell rejection below this is noise, not motion: it does not spread
    float clipLevel=0.98f;       // sample >= clipLevel * white is clipped
    // per-frame weights (sabre::SpatialMerge driver)
    float fwe=1.f;               // frame_weight_exponent
    float weightCap=50.f;        // alt_noise_variance cap
    float lutLo=10.f,lutHi=30.f,lutHiSigma=1.414f;
    // kernel (GCam 6.1 Sabre curves of the SNR of mid grey, see snrKernel())
    float kernelScale=1.f;       // multiplies every kernel sigma
    float widenBelow=4.f;        // base-frame kernel widening below this accumulated donor weight (6.1: 4.0)
    float widenMul=1.826f;       // 6.1: covariance x0.3 = sigma x1.826
    float kernelFloor=0.00005f;  // 6.1 kEpsilon: no hole where the kernel is narrower than the lattice
    float rawTensor=1.f,rawNoise=0.75f; // LMC guide: tensor of the base greens, noise bias 0.75 Var
    // Bento
    int bento=1;                 // 0 off, 1 auto (fallback checks), 2 force
    float bentoHighlight=0.98f;  // GenerateHighlightMask threshold (250/255)
    int bentoDilate=4;           // Mask_Dilate radius
    float bentoSmooth=1.f;       // Mask_Smooth sigma (7x7)
    float bentoMinClipped=0.00039f; // HasSufficientClippedPixels
    float bentoMaxUsClipped=0.62f;  // HasHighClippingRatioOnUltrashortFrame
    float bentoNearClip=0.85f;      // the dilated mask keeps only cells near saturation (any sample >= this, r = 1, sigma = 1):
                                    // elsewhere in the dilation band the 20 N frames beat the one ultrashort frame (x8..16 gain)
    int bentoMaxHole=15;         // HasLargeHoleNeedingInpainting (connected clipped us cells)
    float bentoUsWeight=1.f;     // A of the ultrashort frame (driver: 1.0)
    float bentoUsSigma=1.f;      // isotropic kernel sigma (px) of the ultrashort frame: inside the mask it is the only frame,
                                 // and one Bayer frame needs >= ~0.7 px not to leave R/B holes (LMC: base kernel x2.5 under Bento)
    // Shasta
    float shastaSharpness=0.8f;  // bracketed_sharpness_threshold
    float shastaMaxRatio=32.f;   // max bracketed/base TET ratio
    int shastaEnable=1;
    // misc
    int snrFixed=0;
    float snrScale=0.25f;
    int debugFrame=-1;           // merge one donor only (merge_debug_frame_index)
    int grid=0;                  // replay override of the output grid (0 = header, 1 sensor, 2 = Sabre 2x)
    // Highlights and outliers (research/hybrid5/highlights.md P2, P3, P5)
    int cellClip=1;              // 1: a 2x2 cell with any site >= clipLevel is clipped for EVERY colour of that frame (a clip border
                                 // no longer averages green from the dark side only and R/B from both = magenta rim); 0: per site
    float hotSigma=5.f;          // fixed-pattern (hot/warm/dead) sites: deviation from the median of the 8 same-colour neighbours in
                                 // the mean of hotFrames normal frames at the same SENSOR site, in sigma of that mean; 0 = off
    int hotFrames=8;             // normal frames averaged for the fixed-pattern test (base included)
    float hotBaseSigma=7.f;      // transient (RTS) outliers of the base frame alone (it skips rejection), sigma of one frame; 0 = off
    float hotCross=0.35f;        // a real point or line also lifts the adjacent sites of the other colours: above this share of the
                                 // site's excess the site is scene detail and stays
    float hotMaxLevel=0.03f;     // outliers are tested only where the local level is below this share of white (darks: the dots)
    float hotMaxKey=30.f;        // ... and only in shots whose 6.1 SNR key is at most this (night): at ISO 100 the test found 2000
                                 // "outliers" per MP in dark texture, where the dots are not a problem
    int bentoLmc=1;              // Bento fallback checks: 1 = LMC 9.6 (intensity error -> inpainting holes, us-clip share), 0 = round 4
    float bentoInvalid=0.9f;     // LMC min_normalized_intensity_error_threshold (|min(GainUp(us) - base, 0)| over RGB)
    float bentoInpaintMiddle=0.98f,bentoInpaintMin=0.502f; // LMC max_rgb_clipping_threshold, min_rgb_threshold_for_inpainting
    int clipFlags=0;             // 1: write the clip-flags trailer even when the request does not ask for it (offline replays with
                                 // SCAM_HYBRID only: the app checks the result size)
    // Sabre 6.1 kernel (research/hybrid5/sabre2x_61.md F1-F4): 6.1 sigma curves and SNR key, kernel covariance of every frame from
    // its own RAW (tensor of the quad luma, Wiener noise from the frame's noise model), the 6.1 window (+-1.5 px) and the base
    // widening below 4 accepted frames. 0 = the round-4 kernel (old NICE super-res curves, base-frame tensor for all frames),
    // 1 = always, 2 = auto: only when the 6.1 SNR key is at most s61MaxKey (night). Replays (impl_worker.md): at night (key 12.6)
    // the 6.1 kernel raises the split-half SNR in every band; in daylight (key 67) its narrow kernels double the frame-to-frame
    // differences of textured areas (residual misregistration with one homography per frame) until a local alignment exists.
    int sabre61=2;
    float s61MaxKey=30.f;
    float s61TensorNoise=1.f,s61GdNoise=1.f; // multipliers of the emulated 6.1 noise LUT (tensor, green difference)
    int s61Mode=7;               // parts of the 6.1 merge on top of its kernel curves: 1 covariance of every frame from its own RAW,
                                 // 2 window +-1.5 px, 4 base widening below widenBelow accepted frames
    int subset=0;                // diagnostics (split half): 1 = odd normal donors, 2 = even ones; no base, no Bento, no long frames
    int profile=0;               // diagnostics: time every GPU pass (glFinish after each)
    // Clip-border colour (research/hybrid5/fix_rim.md): at a sharp clip edge every colour keeps only its unclipped lattice sites,
    // which sit on other rows/columns for R, G and B (a colour with none left takes the clipped mean of the bright side), so
    // across an edge that rises x2-3 per pixel R and B come from other scene levels than G: a red/blue dashed rim. Where a
    // colour lost at least rimLo..rimHi of its kernel weight to clipped samples, R and B are rebuilt as G times the ratios
    // R/G, B/G of the real (unclipped) sites around the pixel. 0 = off (output byte-identical to the merge without it).
    int rimRatio=1;
    float rimSigma=1.5f;         // isotropic kernel of the ratio sites (sensor px)
    float rimLo=0.02f,rimHi=0.2f;// ramp of the largest excluded (clipped) weight share of a colour
    int rimStride=4;             // donors used for the ratios: every rimStride-th (the base and the ultrashort always)
};

// restoreSensorOrigin() of vivo-nice-capture.h for a grid scaled by `scale` (CFA phase shift in output pixels).
inline void shiftOrigin(std::vector<float>& v,int w,int h,int channels,int dx,int dy) {
    if(dx==0&&dy==0)return;
    for(int y=h-1;y>=0;--y)for(int x=w-1;x>=0;--x){
        const size_t src=(size_t(std::max(0,y-dy))*w+std::max(0,x-dx))*channels,dst=(size_t(y)*w+x)*channels;
        for(int c=0;c<channels;++c)v[dst+c]=v[src+c];
    }
}

inline HybridTuning loadHybridTuning(const std::string& jobDir,const std::function<void(const std::string&)>& report) {
    HybridTuning t;
    const std::string paths[]={jobDir+"/hybrid_tuning.txt","/sdcard/Android/data/org.codeaurora.snapcam/files/hybrid_tuning.txt","/data/local/tmp/hybrid_tuning.txt"};
    for(const std::string& path:paths){
        std::ifstream f(path);if(!f)continue;
        std::string key;float v;std::string applied;
        auto set=[&](const char* name,float* target,int* itarget=nullptr){
            if(key!=name)return false;
            if(target)*target=v;if(itarget)*itarget=int(v);
            applied+=" "+key+"="+std::to_string(v);return true;
        };
        while(f>>key>>v){
            set("cdm",&t.cdm)||set("boost",&t.boost)||set("boostEnable",&t.boostEnable)||set("varianceThreshold",&t.varianceThreshold)
            ||set("filterVariance",&t.filterVariance)||set("dilateOffset",&t.dilateOffset)||set("dilateScale",&t.dilateScale)||set("dilateFloor",&t.dilateFloor)
            ||set("clipLevel",&t.clipLevel)||set("fwe",&t.fwe)||set("weightCap",&t.weightCap)||set("lutLo",&t.lutLo)||set("lutHi",&t.lutHi)
            ||set("lutHiSigma",&t.lutHiSigma)||set("kernelScale",&t.kernelScale)||set("widenBelow",&t.widenBelow)||set("widenMul",&t.widenMul)
            ||set("kernelFloor",&t.kernelFloor)||set("rawTensor",&t.rawTensor)||set("rawNoise",&t.rawNoise)||set("bento",nullptr,&t.bento)
            ||set("bentoHighlight",&t.bentoHighlight)||set("bentoDilate",nullptr,&t.bentoDilate)||set("bentoSmooth",&t.bentoSmooth)
            ||set("grid",nullptr,&t.grid)||set("bentoMinClipped",&t.bentoMinClipped)||set("bentoMaxUsClipped",&t.bentoMaxUsClipped)||set("bentoNearClip",&t.bentoNearClip)||set("bentoMaxHole",nullptr,&t.bentoMaxHole)
            ||set("bentoUsWeight",&t.bentoUsWeight)||set("bentoUsSigma",&t.bentoUsSigma)||set("shastaSharpness",&t.shastaSharpness)||set("shastaMaxRatio",&t.shastaMaxRatio)
            ||set("shastaEnable",nullptr,&t.shastaEnable)||set("snr",nullptr,&t.snrFixed)||set("snrScale",&t.snrScale)||set("debugFrame",nullptr,&t.debugFrame)
            ||set("cellClip",nullptr,&t.cellClip)||set("hotSigma",&t.hotSigma)||set("hotFrames",nullptr,&t.hotFrames)||set("hotBaseSigma",&t.hotBaseSigma)
            ||set("hotCross",&t.hotCross)||set("hotMaxLevel",&t.hotMaxLevel)||set("bentoLmc",nullptr,&t.bentoLmc)||set("bentoInvalid",&t.bentoInvalid)||set("bentoInpaintMiddle",&t.bentoInpaintMiddle)
            ||set("bentoInpaintMin",&t.bentoInpaintMin)||set("clipFlags",nullptr,&t.clipFlags)||set("sabre61",nullptr,&t.sabre61)
            ||set("s61TensorNoise",&t.s61TensorNoise)||set("s61GdNoise",&t.s61GdNoise)||set("s61Mode",nullptr,&t.s61Mode)||set("s61MaxKey",&t.s61MaxKey)||set("hotMaxKey",&t.hotMaxKey)||set("subset",nullptr,&t.subset)||set("profile",nullptr,&t.profile)
            ||set("rimRatio",nullptr,&t.rimRatio)||set("rimSigma",&t.rimSigma)||set("rimLo",&t.rimLo)||set("rimHi",&t.rimHi)||set("rimStride",nullptr,&t.rimStride);
        }
        if(report&&!applied.empty())report("HYBRID TUNING FILE "+path+":"+applied);
        break;
    }
    return t;
}

// ---------------------------------------------------------------------------------------------
// GLSL. kCommonShader (vivo-nice-superres-gpu.h) provides the strip-uploaded frames, sampleRaw()
// (canonical RGGB coordinates, normalised by black/white, clipped at 1.0) and origin() (the backward
// homography of frame f at reference pixel (x,y)).
// ---------------------------------------------------------------------------------------------
static const char* kHybHelpers=R"(
uniform ivec4 phaseColor;
uniform vec2 baseNoise;      // slope, offset of the base frame (normalised units)
// Per-frame parameters packed four to a vector (scalar uniform arrays take a whole vector slot each on some GPUs; with the
// kCommonShader arrays the merge program went past 512 slots).
uniform vec4 fParam[48];     // x = 1 / exposure ratio (brings frame f to base units), y = scalar weight A (0 = frame skipped),
                             // z = covariance multiplier 1/LUTsigma(A)^2, w = role (1 normal, 3 bracketed, 5 ultrashort)
uniform vec4 fNoiseP[48];    // xy = slope, offset of frame f in its own exposure
uniform ivec4 fCells[48];    // x = first donor cell row held for frame f (even frame row / 2), y = rows held, z = first cell in Cells / DCov
uniform float clipLevel;
layout(std430,binding=5) buffer Cells{vec4 cells[];};    // donor cell colours (u domain) and clip flag, donor geometry
layout(std430,binding=14) buffer DCov{uvec2 dcov[];};    // Sabre 6.1 kernel precision of every donor cell (half floats)
uniform int chunkU;          // first row of this dispatch inside the pass (passes are split into short dispatches)
uniform int markU;           // 1: the uploaded RAW words carry site flags in bits 14 (outlier site) and 15 (its cell is clipped)
// sampleRaw() of kCommonShader that also returns the site flags (bit 0 outlier: no sample; bit 1 the 2x2 cell of this frame is
// clipped: every colour of it goes to the clipped mean). Without marking (white >= 16384) the words are plain.
float rawSite(int f,int x,int y,out uint fl){
    x+=cfaShift.x;y+=cfaShift.y;
    if(x<0||y<0||x>=size.x||y>=size.y){x=reflectCfa(x,size.x);y=reflectCfa(y,size.y);}
    int ry=clamp(y-frameRow0[f],0,frameRows[f]-1);
    uint idx=frameOffset[f]+uint(ry*size.x+x);
    uint word=frames[idx>>1];
    uint v=(idx&1u)==0u?(word&0xFFFFu):(word>>16);
    fl=markU!=0?(v>>14):0u;v&=markU!=0?0x3FFFu:0xFFFFu;
    int phase=((y&1)<<1)|(x&1);
    return clamp((float(v)-black[phase])*inv[phase],-0.25,1.0);
}
float sampleRawM(int f,int x,int y){uint fl;return rawSite(f,x,y,fl);}
float epsU(){ return max(baseNoise.y/max(baseNoise.x,1.0e-9),1.0e-5); }
// u-domain noise variance of frame f, brought to base units, at scene level L (base units).
float noiseU(int f,float L){
    float g=fParam[f].x;float l=max(L,0.0);
    float var=fNoiseP[f].xy.x*l*g+fNoiseP[f].xy.y*g*g;
    return var/(4.0*(l+epsU()));
}
// Colour of cell (i,j) of frame f in the u domain (base units); clipped = any of its (valid) sites at white. An outlier site
// takes the other green of the cell or the same colour of the next cell, so that the guide and the rejection see the scene.
vec3 cellU(int f,int i,int j,out bool clipped,out float gd){
    float g=fParam[f].x;float g0=0.0,g1=0.0;int gi=0;vec3 r=vec3(0.0);bool c=false;
    float v[4];uint hot=0u;
    for(int p=0;p<4;p++){
        uint fl;
        v[p]=rawSite(f,2*i+(p&1),2*j+(p>>1),fl);
        if((fl&1u)!=0u)hot|=1u<<uint(p);
        else if(v[p]>=clipLevel)c=true;
    }
    if(hot!=0u)for(int p=0;p<4;p++){
        if(((hot>>uint(p))&1u)==0u)continue;
        if(phaseColor[p]==1&&((hot>>uint(3-p))&1u)==0u)v[p]=v[3-p];
        else v[p]=sampleRawM(f,2*(i>0?i-1:i+1)+(p&1),2*j+(p>>1));
    }
    for(int p=0;p<4;p++){
        int col=phaseColor[p];float x=v[p]*g;
        if(col==1){ if(gi==0)g0=x; else g1=x; gi++; }
        else if(col==0)r.r=x; else r.b=x;
    }
    r.g=0.5*(g0+g1);clipped=c;
    float e=epsU();
    gd=abs(sqrt(max(g0,0.0)+e)-sqrt(max(g1,0.0)+e));
    return sqrt(max(r,vec3(0.0))+vec3(e));
}
vec4 cellAt(int f,int i,int j){
    int w2=size.x/2;
    i=clamp(i,0,w2-1);j=clamp(j-fCells[f].x,0,fCells[f].y-1);
    return cells[uint(fCells[f].z)+uint(j*w2+i)];
}
vec3 dcovAt(int f,int i,int j){
    int w2=size.x/2;
    i=clamp(i,0,w2-1);j=clamp(j-fCells[f].x,0,fCells[f].y-1);
    uvec2 u=dcov[uint(fCells[f].z)+uint(j*w2+i)];
    return vec3(unpackHalf2x16(u.x),unpackHalf2x16(u.y).x);
}
// GCam 6.1 Sabre kernel covariance (guide shader @0xe49bed, GenerateGaussCovariance) of cell (i,j) of frame f, from that frame's
// own RAW: quad luma Y = (sqrt r + sqrt g1 + sqrt g2 + sqrt b)/4 of the 3x3 quads, four diagonal gradient pairs /8 rotated by
// 45 degrees, Wiener-filtered strength (noise from the frame's own model in place of the 6.1 LUT), green-difference blur, 6.1
// sigma mixing. Returns P for kernelW() (exp2(-0.72135 d'Pd) == exp2(-0.5 d'Cd)), divided by kernelScale^2.
uniform vec4 k61aU; // covariance_parameters1: f5/f0 (shrunk), 1/(f0 f4) (stretched), f2 (gradient clip), 1/f0 (base); w = 0: off
uniform vec4 k61bU; // 1/(f0 f1) (blurred), 1/f3 (transition), 1/kernelScale^2, tensor noise multiplier
uniform vec4 k61cU; // green difference noise multiplier
vec3 sabreCov61(int f,int i,int j){
    float Y[9];float gd=0.0,lum=0.0;float g=fParam[f].x;
    for(int dj=-1;dj<=1;dj++)for(int di=-1;di<=1;di++){
        int X=2*(i+di),Z=2*(j+dj);
        float r=sqrt(max(sampleRawM(f,X,Z)*g,0.0)),g1=sqrt(max(sampleRawM(f,X+1,Z)*g,0.0));
        float g2=sqrt(max(sampleRawM(f,X,Z+1)*g,0.0)),b=sqrt(max(sampleRawM(f,X+1,Z+1)*g,0.0));
        float w=(di==0?0.5:0.25)*(dj==0?0.5:0.25);
        float y=0.25*(r+g1+g2+b);
        Y[(dj+1)*3+di+1]=y;gd+=abs(g1-g2)*w;lum+=y*w;
    }
    float v=max(lum*lum,1.0e-6);vec2 nm=fNoiseP[f].xy;
    float varU=(nm.x*v*g+nm.y*g*g)/(4.0*v);
    // tensor: E[eigenvalue] of pure noise = var(Y) = varU/4 (gradient^2 units); green difference: the shader filters an AMPLITUDE
    // (gd*gd/(gd+N)), so N is the expected |sqrt g1 - sqrt g2| of noise, sqrt(4 varU/pi) (a variance there never filters anything)
    float nTensor=0.25*varU*(k61bU.w>0.0?k61bU.w:1.0),nGd=1.1284*sqrt(varU)*(k61cU.x>0.0?k61cU.x:1.0);
    float dxx=0.0,dyy=0.0,dxy=0.0;
    for(int y=0;y<2;y++)for(int x=0;x<2;x++){
        float dx=Y[(y+1)*3+x+1]-Y[y*3+x],dy=Y[y*3+x+1]-Y[(y+1)*3+x];
        dxx+=dx*dx;dyy+=dy*dy;dxy+=dx*dy;
    }
    vec3 c=vec3(dxx,dyy,dxy)*0.125;float c0=0.5*(c.x+c.y),c1=0.5*(c.y-c.x);
    vec3 s=vec3(c0+c.z,c0-c.z,c1);                                   // RotateCovariance
    float tr=s.x+s.y,df=s.x-s.y,sq=sqrt(max(df*df+4.0*s.z*s.z,0.0)),l1=0.5*(tr+sq),l2=0.5*(tr-sq);
    vec2 e1=vec2(1.0,0.0),e2=vec2(0.0,1.0);
    if(abs(s.z)>1.0e-4){e1=normalize(vec2(s.z,l1-s.x))*-sign(s.z);e2=vec2(-e1.y,e1.x);}
    else if(s.x<s.y){e1=vec2(0.0,1.0);e2=vec2(1.0,0.0);}
    float sv1=sqrt(max(l1,0.0)),sv2=sqrt(max(l2,0.0));
    float l1w=max(l1,0.0);l1w*=l1w/(l1w+nTensor+1.0e-20);
    float strength=sqrt(l1w),coh=(sv1-sv2)/(sv1+sv2+1.0e-6);
    gd*=gd/(gd+nGd+1.0e-20);
    float blur=clamp(1.0-(max(strength,gd)-k61aU.z)*k61bU.y,0.0,1.0);
    float an=mix(k61aU.w,k61aU.x,min(coh,strength*5.0));
    float s1=mix(an,k61bU.x,blur),s2=mix(mix(k61aU.w,k61aU.y,coh),k61bU.x,blur);
    mat2 R=mat2(e1,e2);mat2 rr=transpose(R)*mat2(s1*s1,0.0,0.0,s2*s2)*R;
    return vec3(rr[0].x,rr[1].y,rr[0].y)*(0.693147*(k61bU.z>0.0?k61bU.z:1.0));
}
)";

// Outlier sites of the strip rows (before the guide), one invocation per site: fixed-pattern outliers in the mean of hotU.x
// normal frames at the same SENSOR site (warm/hot/dead pixels sit at the same sensor position in every frame and every shot), and
// transient outliers of the base frame alone (RTS: the base skips the rejection and, as the rejection reference, makes the donors
// look different there). A site is an outlier when it leaves the median of its 8 same-colour neighbours by more than T sigma of the
// noise model and the adjacent sites of the other colours do not follow it (a real point or line does); only in the dark.
// kHybMean first writes the mean of the fixed-pattern frames for the strip rows +-2.
static const char* kHybMean=R"(
layout(std430,binding=1) writeonly buffer MeanBuf{float meanv[];}; // sites of rows [2 ry0 - 2, 2 ry1 + 2), canonical
uniform int hotList[16];     // normal frames of the fixed-pattern mean (all hold the strip rows +-2)
uniform ivec4 hotU;          // x = frames in hotList
uniform int ry0;
uniform int ry1;
void main(){
    int x=int(gl_GlobalInvocationID.x),r=chunkU+int(gl_GlobalInvocationID.y);
    if(x>=size.x||r>=2*(ry1-ry0)+4)return;
    int y=2*ry0-2+r;
    float s=0.0;
    for(int l=0;l<hotU.x&&l<16;l++)s+=sampleRawM(hotList[l],x,y);
    meanv[uint(r*size.x+x)]=s/float(max(hotU.x,1));
}
)";
static const char* kHybFlags=R"(
layout(std430,binding=9) buffer Sums{uint sums[];};
layout(std430,binding=1) readonly buffer MeanBuf{float meanv[];};
// one word per site of the strip rows [2 ry0, 2 ry1) (canonical): bit 0 fixed-pattern outlier, bit 1 base transient outlier
layout(std430,binding=2) writeonly buffer SiteFlags{uint sflags[];};
uniform ivec4 hotU;          // x = frames in the mean (0: no fixed-pattern test), y = bit 0 fixed-pattern test, bit 1 base transient test
uniform vec4 hotSigU;        // x = fixed-pattern threshold (sigma of the mean), y = base threshold (sigma of one frame), z = cross share,
                             // w = highest local level (of white) tested
uniform int ry0;
uniform int ry1;
uniform int cy0;
uniform int cy1;
float mAt(int x,int y){ // mean frame, canonical site (rows within the buffer by construction)
    if(x<0||x>=size.x)x=reflectCfa(x,size.x);
    return meanv[uint((y-2*ry0+2)*size.x+x)];
}
#define CX(p,q) { float lo_=min(p,q); q=max(p,q); p=lo_; }
float median8(float a0,float a1,float a2,float a3,float a4,float a5,float a6,float a7){ // 19-comparator sorting network
    CX(a0,a2)CX(a1,a3)CX(a4,a6)CX(a5,a7) CX(a0,a4)CX(a1,a5)CX(a2,a6)CX(a3,a7) CX(a0,a1)CX(a2,a3)CX(a4,a5)CX(a6,a7)
    CX(a2,a4)CX(a3,a5) CX(a1,a4)CX(a3,a6) CX(a1,a2)CX(a3,a4)CX(a5,a6)
    return 0.5*(a3+a4);
}
// .x = site - median of its 8 same-colour neighbours, .y = that median, .z = cross-colour excess (the adjacent sites of the other
// colours against the next ring of the same colours), .w = mean of the 8 sites around (local level); p = 5x5 sites around the site.
#define P5(dx,dy) p[((dy)+2)*5+(dx)+2]
vec4 siteExcess(float p[25],bool green){
    float med,cross;
    if(green){
        med=median8(P5(-1,-1),P5(1,-1),P5(-1,1),P5(1,1),P5(-2,0),P5(2,0),P5(0,-2),P5(0,2));
        float hi=0.5*(P5(-1,0)+P5(1,0)),ho=0.25*(P5(-1,-2)+P5(1,-2)+P5(-1,2)+P5(1,2));
        float vi=0.5*(P5(0,-1)+P5(0,1)),vo=0.25*(P5(-2,-1)+P5(2,-1)+P5(-2,1)+P5(2,1));
        cross=0.5*((hi-ho)+(vi-vo));
    } else {
        med=median8(P5(-2,0),P5(2,0),P5(0,-2),P5(0,2),P5(-2,-2),P5(2,-2),P5(-2,2),P5(2,2));
        float gi=0.25*(P5(-1,0)+P5(1,0)+P5(0,-1)+P5(0,1));
        float go=0.125*(P5(-1,-2)+P5(1,-2)+P5(-1,2)+P5(1,2)+P5(-2,-1)+P5(2,-1)+P5(-2,1)+P5(2,1));
        cross=gi-go;
    }
    float c=P5(0,0);
    float level=(P5(-1,0)+P5(1,0)+P5(0,-1)+P5(0,1)+P5(-1,-1)+P5(1,-1)+P5(-1,1)+P5(1,1))*0.125; // around the site, without it
    return vec4(c-med,med,cross,level);
}
void main(){
    int x=int(gl_GlobalInvocationID.x),r=chunkU+int(gl_GlobalInvocationID.y);
    if(x>=size.x||r>=2*(ry1-ry0))return;
    int y=2*ry0+r;
    bool green=phaseColor[((y&1)<<1)|(x&1)]==1;
    uint bits=0u;
    float p[25];
    if(hotU.x>=3&&(hotU.y&1)!=0){ // hot and dead sites; sigma of the mean of n frames (the median of 8 adds ~15 %)
        for(int k=0;k<25;k++)p[k]=mAt(x-2+k%5,y-2+k/5);
        vec4 e=siteExcess(p,green);
        if(hotSigU.w<=0.0||e.w<hotSigU.w){
            float sd=sqrt(max((baseNoise.x*max(e.y,0.0)+baseNoise.y)/float(hotU.x),1.0e-14))*1.15;
            float ex=abs(e.x),cr=e.z*sign(e.x);
            if(ex>hotSigU.x*sd&&cr<hotSigU.z*ex&&e.y<0.9)bits|=1u; // a stuck (white) site counts too: its neighbours stay dark
        }
    }
    if((hotU.y&2)!=0){
        for(int k=0;k<25;k++)p[k]=sampleRawM(0,x-2+k%5,y-2+k/5);
        vec4 e=siteExcess(p,green);
        if(hotSigU.w<=0.0||e.w<hotSigU.w){
            float sd=sqrt(max(baseNoise.x*max(e.y,0.0)+baseNoise.y,1.0e-14))*1.1;
            if(e.x>hotSigU.y*sd&&e.z<hotSigU.z*e.x&&e.y<0.9)bits|=2u;
        }
    }
    sflags[uint(r*size.x+x)]=bits;
    if(bits!=0u&&(y>>1)>=cy0&&(y>>1)<cy1){
        if((bits&1u)!=0u)atomicAdd(sums[frameCount],1u);
        if((bits&2u)!=0u)atomicAdd(sums[frameCount+1],1u);
    }
}
)";

// Marks the uploaded RAW words of every frame of the strip (one invocation per word = two sites of a row): bit 14 = outlier site
// (fixed-pattern sites of the strip rows in every frame, transient ones in the base), bit 15 = the site's 2x2 cell (canonical) is
// clipped in this frame (any of its non-outlier sites >= clipLevel). Standalone program: the Frames block is writable here.
static const char* kHybMark=R"(#version 310 es
precision highp float;
precision highp int;
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=0) buffer Frames{uint frames[];};
layout(std430,binding=2) readonly buffer SiteFlags{uint sflags[];}; // per canonical site of rows [2 x, 2 y) of mFlagsU
uniform ivec2 size;
uniform ivec2 cfaShift;
uniform vec4 black;
uniform vec4 inv;
uniform uint mOffset[48];    // first site of frame f in Frames
uniform ivec2 mRows[48];     // first sensor row held, rows held
uniform int frameIdx;        // first frame of this dispatch (+ z)
uniform int chunkU;
uniform ivec4 mFlagsU;       // x,y = cell rows of the strip (SiteFlags holds the site rows [2x, 2y)), z: 1 cell clip, 2 outliers
uniform float clipLevel;
uint siteOutlier(int f,int x,int y){ // canonical site
    if((mFlagsU.z&2)==0||x<0||y<0||x>=size.x||y<2*mFlagsU.x||y>=2*mFlagsU.y)return 0u;
    uint s=sflags[uint((y-2*mFlagsU.x)*size.x+x)];
    return (s&1u)|(f==0?((s>>1)&1u):0u);
}
uint rawAt(int f,int X,int Y){ // sensor site inside the frame's rows; flag bits masked off (other invocations write them)
    X=clamp(X,0,size.x-1);Y=clamp(Y-mRows[f].x,0,mRows[f].y-1);
    uint idx=mOffset[f]+uint(Y*size.x+X);
    uint word=frames[idx>>1];
    return ((idx&1u)==0u?(word&0xFFFFu):(word>>16))&0x3FFFu;
}
bool cellClipped(int f,int ci,int cj){ // canonical cell
    for(int p=0;p<4;p++){
        int x=2*ci+(p&1),y=2*cj+(p>>1);
        if(siteOutlier(f,x,y)!=0u)continue;
        int X=x+cfaShift.x,Y=y+cfaShift.y;
        int ph=((Y&1)<<1)|(X&1);
        if((float(rawAt(f,X,Y))-black[ph])*inv[ph]>=clipLevel)return true;
    }
    return false;
}
void main(){
    int f=frameIdx+int(gl_GlobalInvocationID.z);
    int wx=int(gl_GlobalInvocationID.x),row=chunkU+int(gl_GlobalInvocationID.y);
    if(wx>=size.x/2||row>=mRows[f].y)return;
    int Y=mRows[f].x+row;
    uint idx=mOffset[f]+uint(row*size.x+2*wx);
    uint word=frames[idx>>1];
    int lastCell=-2;bool clip=false;
    for(int k=0;k<2;k++){
        int X=2*wx+k;
        int x=X-cfaShift.x,y=Y-cfaShift.y;   // canonical
        uint fl=siteOutlier(f,x,y);
        if((mFlagsU.z&1)!=0&&x>=0&&y>=0){
            if((x>>1)!=lastCell){lastCell=x>>1;clip=cellClipped(f,x>>1,y>>1);} // both sites share the cell unless the CFA shifts x
            if(clip)fl|=2u;
        }
        uint sh=uint(16*k);
        word=(word&~(0xC000u<<sh))|((fl&3u)<<(14u+sh));
    }
    frames[idx>>1]=word;
}
)";

// Base frame guide per 2x2 cell: colour, texture variance, kernel covariance (Sabre 6.1 of the base RAW, or the round-4 tensor
// of the raw greens).
static const char* kHybGuide=R"(
layout(std430,binding=10) writeonly buffer Guide{vec4 guide[];};
layout(std430,binding=11) writeonly buffer Cov{vec4 cov[];};
uniform int ry0;
uniform int ry1;
uniform vec4 kA; // base, shrunk, stretched, flat (sigma, sensor px)
uniform vec4 kB; // strength scale, flat0, flat1, texStd
uniform vec4 kC; // tensor noise floor, raw tensor weight, raw noise bias
void main(){
    int w2=size.x/2,h2=size.y/2;
    int cx=int(gl_GlobalInvocationID.x),cy=ry0+chunkU+int(gl_GlobalInvocationID.y);
    if(cx>=w2||cy>=ry1)return;
    vec3 mean=vec3(0.0),mean2=vec3(0.0),centre=vec3(0.0);float gdSum=0.0;bool centreClip=false;
    for(int dj=-1;dj<=1;dj++)for(int di=-1;di<=1;di++){
        bool cl;float gd;
        vec3 uv=cellU(0,clamp(cx+di,0,w2-1),clamp(cy+dj,0,h2-1),cl,gd);
        mean+=uv*(1.0/9.0);mean2+=uv*uv*(1.0/9.0);
        gdSum+=gd*((di==0)?0.5:0.25)*((dj==0)?0.5:0.25);
        if(di==0&&dj==0){centre=uv;centreClip=cl;}
    }
    vec3 var=max(mean2-mean*mean,vec3(0.0));
    float s2=(var.x+var.y+var.z)*(1.0/3.0);
    float Lc=max(dot(centre*centre,vec3(1.0/3.0))-epsU(),0.0);
    float nvMean=noiseU(0,Lc)*0.742;
    float gdTex=max(gdSum-0.56*sqrt(baseNoise.x),0.0);
    float s2tex=max(max(s2-nvMean,0.0),gdTex*gdTex);
    int gi=(cy-ry0)*w2+cx;
    guide[gi]=vec4(centre,s2tex);
    if(k61aU.w>0.0){ cov[gi]=vec4(sabreCov61(0,cx,cy),centreClip?1.0:0.0); return; }
    // Structure tensor of the base greens (18 greens of the 6x6 site window, gradients from the four
    // diagonal neighbours; LMC guide_image), less the gradient noise.
    float ug[64];
    float e=epsU();
    for(int j=0;j<8;j++)for(int i=0;i<8;i++){
        int X=2*cx-3+i,Y=2*cy-3+j;
        ug[j*8+i]=(phaseColor[((Y&1)<<1)|(X&1)]==1)?sqrt(max(sampleRawM(0,X,Y),0.0)+e):0.0;
    }
    float txx=0.0,tyy=0.0,txy=0.0,rn=0.0;
    for(int j=1;j<7;j++)for(int i=1;i<7;i++){
        int X=2*cx-3+i,Y=2*cy-3+j;
        if(phaseColor[((Y&1)<<1)|(X&1)]!=1)continue;
        float a=ug[(j+1)*8+i+1],b=ug[(j-1)*8+i+1],c=ug[(j+1)*8+i-1],d=ug[(j-1)*8+i-1];
        float gx=0.25*(a+b-c-d),gy=0.25*(a+c-b-d);
        txx+=gx*gx;tyy+=gy*gy;txy+=gx*gy;rn+=1.0;
    }
    rn=1.0/max(rn,1.0);
    txx=max(txx*rn-kC.z,0.0);tyy=max(tyy*rn-kC.z,0.0);txy*=rn;
    float lim=sqrt(txx*tyy);txy=clamp(txy,-lim,lim);
    float tr=txx+tyy,df=txx-tyy,sq=sqrt(max(df*df+4.0*txy*txy,0.0));
    float l1=0.5*(tr+sq),l2=max(0.5*(tr-sq),0.0);
    vec2 e1=vec2(1.0,0.0);
    if(abs(txy)>1.0e-9){ e1=normalize(vec2(txy,l1-txx)); }
    else if(txx<tyy){ e1=vec2(0.0,1.0); }
    vec2 e2=vec2(-e1.y,e1.x);
    float sv1=sqrt(l1),sv2=sqrt(l2);
    float l1w=l1*l1/(l1+kC.x*kC.x+1.0e-12);
    float strength=sqrt(l1w);
    float coherence=(sv1-sv2)/(sv1+sv2+1.0e-6);
    float dominant=max(strength,kB.w*sqrt(s2tex));
    float flatness=1.0-smoothstep(kB.y,kB.z,dominant);
    float across=mix(kA.x,kA.y,min(coherence,strength*kB.x));
    float along=mix(kA.x,kA.z,coherence);
    across=mix(across,kA.w,flatness);
    along=mix(along,kA.w,flatness);
    float ia=1.0/(across*across),il=1.0/(along*along);
    cov[gi]=vec4(e1.x*e1.x*ia+e2.x*e2.x*il,e1.y*e1.y*ia+e2.y*e2.y*il,e1.x*e1.y*ia+e2.x*e2.y*il,centreClip?1.0:0.0);
}
)";

// Donor cell colours in donor geometry (one pass per strip, all donors): the rejection reads them bilinearly
// and takes the donor's local texture from the 3x3 neighbourhood without re-reading the RAW. With the Sabre 6.1 kernel also
// the kernel precision of every donor cell from the donor's own RAW (6.1: GenerateHalfSizeRefColorTexture per frame).
static const char* kHybCells=R"(
uniform int cellFrameU; // first donor of this dispatch - 1
uniform int dcovU;      // 1: DCov holds every donor cell (merge mode bit 1) and gets the 6.1 precision; 0 / unset: not written
                        // (with the 6.1 kernel but without the per-frame covariance DCov is a 16-byte stub)
void main(){
    int w2=size.x/2;
    int f=int(gl_GlobalInvocationID.z)+1+cellFrameU;
    if(f>=frameCount)return;
    int cx=int(gl_GlobalInvocationID.x),cy=fCells[f].x+chunkU+int(gl_GlobalInvocationID.y);
    if(cx>=w2||cy>=fCells[f].x+fCells[f].y)return;
    bool cl;float gd;
    vec3 u=cellU(f,cx,cy,cl,gd);
    uint idx=uint(fCells[f].z)+uint((cy-fCells[f].x)*w2+cx);
    cells[idx]=vec4(u,cl?1.0:0.0);
    if(dcovU!=0&&k61aU.w>0.0&&fParam[f].y>0.0&&int(fParam[f].w)!=5){
        vec3 P=sabreCov61(f,cx,cy);
        dcov[idx]=uvec2(packHalf2x16(P.xy),packHalf2x16(vec2(P.z,0.0)));
    }
}
)";

// Rejection per donor frame and base cell (LMC 9.6 rejection.cl, REJECTION_ONLY path).
static const char* kHybReject=R"(
layout(std430,binding=7) writeonly buffer RawR{float rawR[];};
layout(std430,binding=10) readonly buffer Guide{vec4 guide[];};
uniform int ry0;
uniform int ry1;
uniform vec4 rj; // cdm, boost, variance threshold, filter variance scale
uniform vec4 rk; // boost enable, 0, 0, 0
void main(){
    int w2=size.x/2;
    int cx=int(gl_GlobalInvocationID.x),cy=ry0+chunkU+int(gl_GlobalInvocationID.y),f=int(gl_GlobalInvocationID.z)+1;
    if(cx>=w2||cy>=ry1||f>=frameCount)return;
    vec4 G=guide[(cy-ry0)*w2+cx];
    float w=0.0;
    if(fParam[f].y>0.0){
        vec2 o=origin(f,2*cx,2*cy)*0.5;
        int ix=int(floor(o.x)),iy=int(floor(o.y));
        float fx=o.x-float(ix),fy=o.y-float(iy);
        vec4 c00=cellAt(f,ix,iy),c10=cellAt(f,ix+1,iy),c01=cellAt(f,ix,iy+1),c11=cellAt(f,ix+1,iy+1);
        vec3 g=(c00.xyz*(1.0-fx)+c10.xyz*fx)*(1.0-fy)+(c01.xyz*(1.0-fx)+c11.xyz*fx)*fy;
        bool dclip=max(max(c00.w,c10.w),max(c01.w,c11.w))>0.5;
        // donor texture variance over its 3x3 cells
        vec3 m=vec3(0.0),m2=vec3(0.0);
        for(int dj=0;dj<=2;dj++)for(int di=0;di<=2;di++){vec3 u=cellAt(f,ix-1+di,iy-1+dj).xyz;m+=u*(1.0/9.0);m2+=u*u*(1.0/9.0);}
        vec3 dv=max(m2-m*m,vec3(0.0));
        // 9-cell variance estimate of noise alone: (1 + 0.5 + 1)/3 of the single-site u variance, times 8/9.
        float Lf=max(dot(m*m,vec3(1.0/3.0))-epsU(),0.0);
        float Vcur=max((dv.x+dv.y+dv.z)*(1.0/3.0)-noiseU(f,Lf)*0.742,0.0);
        float Lb=max(dot(G.xyz*G.xyz,vec3(1.0/3.0))-epsU(),0.0);
        // Variance of (donor - base): the base cell is read unfiltered, the donor cell is a bilinear sample
        // (variance scaled by rj.w); the green of a cell averages two sites.
        float nvs=noiseU(0,Lb)+rj.w*noiseU(f,Lb);
        vec3 nv=nvs*vec3(1.0,0.5,1.0);
        float nvMean=(nv.x+nv.y+nv.z)*(1.0/3.0);
        vec3 d=g-G.xyz;
        vec3 D2=max(d*d-nv,vec3(0.0));
        float varc=max(2.0*min(G.w,Vcur),nvMean);
        float dist=rj.x*(D2.x+D2.y+D2.z)*(1.0/3.0)/varc;
        float boost=(rk.x>0.5&&G.w>rj.z*nvMean)?rj.y:1.0;
        w=exp2(-dist*boost);
        // A longer (bracketed) frame clips where the base does not: its clipped cells carry no signal.
        if(int(fParam[f].w)==3&&dclip)w=0.0;
    }
    rawR[(f-1)*(ry1-ry0)*w2+(cy-ry0)*w2+cx]=w;
}
)";

// DilateMask (5x5, LMC: rej = (sum25 - 0.2)/2), the per-frame scalar weight and the Bento replacement.
static const char* kHybDilate=R"(
layout(std430,binding=7) readonly buffer RawR{float rawR[];};
layout(std430,binding=8) writeonly buffer Robust{float robust[];};
layout(std430,binding=9) buffer Sums{uint sums[];};
layout(std430,binding=13) readonly buffer Mask{float bmask[];};
uniform int ry0;
uniform int ry1;
uniform int cy0;
uniform int cy1;
uniform vec4 dl; // offset, scale, bento active, floor (rejection below it does not spread)
void main(){
    int w2=size.x/2;
    int cx=int(gl_GlobalInvocationID.x),cy=cy0+chunkU+int(gl_GlobalInvocationID.y),f=int(gl_GlobalInvocationID.z)+1;
    if(cx>=w2||cy>=cy1||f>=frameCount)return;
    float s=0.0,wc=1.0;
    for(int dj=-2;dj<=2;dj++)for(int di=-2;di<=2;di++){
        int y=clamp(cy+dj,ry0,ry1-1),x=clamp(cx+di,0,w2-1);
        float v=rawR[(f-1)*(ry1-ry0)*w2+(y-ry0)*w2+x];
        s+=max(1.0-v-dl.w,0.0)/max(1.0-dl.w,1.0e-3);
        if(di==0&&dj==0)wc=v;
    }
    float rej=clamp((s-dl.x)/max(dl.y,1.0e-3),0.0,1.0);
    float r=min(wc,1.0-rej)*fParam[f].y;
    if(dl.z>0.5){
        float m=bmask[(cy-cy0)*w2+cx];
        if(int(fParam[f].w)==5)r=m*fParam[f].y; else r*=(1.0-m);
    } else if(int(fParam[f].w)==5)r=0.0;
    robust[(f-1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx]=r;
    atomicAdd(sums[f],uint(clamp(r,0.0,1.0)*255.0+0.5));
}
)";

// Accumulation: for every output pixel, the RAW sites of every colour around the aligned position of each
// frame, weighted by the anisotropic kernel, the frame's robustness and scalar weight; base frame last.
static const char* kHybMergeCommon=R"(
layout(std430,binding=8) readonly buffer Robust{float robust[];};
#ifdef RIM_STATS
layout(std430,binding=3) buffer Out{float outRgb[];};
layout(std430,binding=6) buffer CFlags{uint cflags[];};
#else
layout(std430,binding=3) writeonly buffer Out{float outRgb[];};
layout(std430,binding=4) writeonly buffer Eff{float eff[];};
layout(std430,binding=6) writeonly buffer CFlags{uint cflags[];};
#endif
layout(std430,binding=11) readonly buffer Cov{vec4 cov[];};
layout(std430,binding=13) readonly buffer Mask{float bmask[];};
uniform int cy0;
uniform int cy1;
uniform int ry0;
uniform vec4 kD; // widen below, widen multiplier, kernel floor, bento active
uniform vec4 kE; // debug frame (-1 = all), ultrashort kernel precision 1/sigma^2, 0, 0
uniform ivec4 kG; // output grid (1|2), output row width, sub-position x (0|1), sub-position y (0|1)
uniform ivec4 mergeModeU; // x: 1 per-frame 6.1 covariance, 2 6.1 window, 4 6.1 base widening (frames); y: no base; z: clip flags
uniform vec4 rimU;        // x: 1 = clip-border colour pass (kHybRim) on: the merge marks its candidates in the clip flags
#ifdef RIM_STATS
// kHybRim: trueDen = the part of clipDen from sites at or above the clip (the colour itself saturated); the rest of clipDen is real
// values of the other sites of clipped cells (cellClip). Not in the merge itself: three more accumulators in its frame loop cost
// ~12 % of the merge time on Adreno 750.
struct Acc{vec3 num;vec3 den;float cover;vec3 clipNum;vec3 clipDen;float usClip;vec3 trueDen;};
void initAcc(out Acc a){a.num=vec3(0.0);a.den=vec3(0.0);a.cover=0.0;a.clipNum=vec3(0.0);a.clipDen=vec3(0.0);a.usClip=0.0;a.trueDen=vec3(0.0);}
#else
struct Acc{vec3 num;vec3 den;float cover;vec3 clipNum;vec3 clipDen;float usClip;};
void initAcc(out Acc a){a.num=vec3(0.0);a.den=vec3(0.0);a.cover=0.0;a.clipNum=vec3(0.0);a.clipDen=vec3(0.0);a.usClip=0.0;}
#endif
float kernelW(vec2 d,vec3 P){
    return exp2(-0.72135*(d.x*d.x*P.x+d.y*d.y*P.y+2.0*d.x*d.y*P.z))+kD.z; // exp(-0.5 d'Pd) + floor
}
// Sites of frame f around position O (frame coordinates): the two lattice sites per axis of every colour phase. Site flags come
// with the RAW word: an outlier site gives no sample; a site of a clipped cell sends its value to the clipped mean (used only
// where nothing valid is left), so a clip border does not average one colour from one side only. win = the 6.1 window
// max(|dx|,|dy|) <= 1.5.
void frameSamples(inout Acc a,int f,vec2 O,float r,float cover,vec3 P,bool win){
    float g=fParam[f].x;
    int role=int(fParam[f].w);
    for(int p=0;p<4;p++){
        int px=p&1,py=p>>1,c=phaseColor[p];
        int bx=int(floor((O.x-float(px))*0.5))*2+px,by=int(floor((O.y-float(py))*0.5))*2+py;
        for(int dj=0;dj<=2;dj+=2)for(int di=0;di<=2;di+=2){
            int sx=bx+di,sy=by+dj;
            vec2 d=vec2(float(sx),float(sy))-O;
            if(win&&max(abs(d.x),abs(d.y))>1.5)continue;
            float kw=kernelW(d,P);
            if(kw<0.002&&f!=0&&role!=5)continue;
            uint fl;
            float v=rawSite(f,sx,sy,fl);
            if((fl&1u)!=0u)continue;                           // outlier site: no sample at all
            if((fl&2u)!=0u||v>=clipLevel){
                if(role==3)continue;                           // a clipped longer frame is only a lower bound below the base's
                a.clipNum[c]+=r*kw*v*g;a.clipDen[c]+=r*kw;
#ifdef RIM_STATS
                if(v>=clipLevel)a.trueDen[c]+=r*kw;
#endif
                if(role==5)a.usClip+=r*kw;
                continue;
            }
            a.num[c]+=r*kw*v*g;a.den[c]+=r*kw;
            if(c==1)a.cover+=cover*kw;
        }
    }
}
#ifndef RIM_STATS
// Mean of the base frame's sites of colour c on the lattice around O (two sites per axis and phase, no window, no kernel), outlier
// sites skipped: the fill of a colour that lost every sample to outlier sites (see main).
float baseFill(int c,vec2 O){
    float s=0.0,n=0.0;
    for(int p=0;p<4;p++){
        if(phaseColor[p]!=c)continue;
        int px=p&1,py=p>>1;
        int bx=int(floor((O.x-float(px))*0.5))*2+px,by=int(floor((O.y-float(py))*0.5))*2+py;
        for(int dj=0;dj<=2;dj+=2)for(int di=0;di<=2;di+=2){
            uint fl;
            float v=rawSite(0,bx+di,by+dj,fl);
            if((fl&1u)!=0u)continue;
            s+=v;n+=1.0;
        }
    }
    return n>0.0?s*fParam[0].x/n:0.0;
}
// The same without site flags (strips with nothing marked: no outlier test, no clipped sample), as fast as before the flags.
void frameSamplesPlain(inout Acc a,int f,vec2 O,float r,float cover,vec3 P,bool win){
    float g=fParam[f].x;
    int role=int(fParam[f].w);
    for(int p=0;p<4;p++){
        int px=p&1,py=p>>1,c=phaseColor[p];
        int bx=int(floor((O.x-float(px))*0.5))*2+px,by=int(floor((O.y-float(py))*0.5))*2+py;
        for(int dj=0;dj<=2;dj+=2)for(int di=0;di<=2;di+=2){
            int sx=bx+di,sy=by+dj;
            vec2 d=vec2(float(sx),float(sy))-O;
            if(win&&max(abs(d.x),abs(d.y))>1.5)continue;
            float kw=kernelW(d,P);
            if(kw<0.002&&f!=0&&role!=5)continue;
            float v=sampleRaw(f,sx,sy);
            if(v>=clipLevel){
                if(role==3)continue;                           // a clipped longer frame is only a lower bound below the base's
                a.clipNum[c]+=r*kw*v*g;a.clipDen[c]+=r*kw;
                if(role==5)a.usClip+=r*kw;
                continue;
            }
            a.num[c]+=r*kw*v*g;a.den[c]+=r*kw;
            if(c==1)a.cover+=cover*kw;
        }
    }
}
#endif
)";

// Grid 1 (sensor grid): one evaluation per sensor pixel.
static const char* kHybMergeMain1=R"(
void main(){
    int w2=size.x/2;
    int cx=int(gl_GlobalInvocationID.x),cy=cy0+chunkU+int(gl_GlobalInvocationID.y);
    if(cx>=w2||cy>=cy1)return;
    vec4 cv=cov[(cy-ry0)*w2+cx];
    vec3 P=cv.xyz;
    float m=kD.w>0.5?bmask[(cy-cy0)*w2+cx]:0.0;
    // Sabre 6.1 2x grid: the output pixel centres fall on sensor positions x/2 - 0.25, i.e. the sub-positions
    // +-0.25 px of every sensor pixel; one dispatch per sub-position, the kernel stays in sensor pixel units.
    int g=kG.x,ow=kG.y,sx=kG.z,sy=kG.w;
    vec2 sub=g==2?vec2(sx==0?-0.25:0.25,sy==0?-0.25:0.25):vec2(0.0);
    int oy0=2*cy0*g;
    vec2 cell=vec2(float(2*cx),float(2*cy));
    int mode=mergeModeU.x;
    float wb=1.0-m;
    if(kE.x>=0.0||mergeModeU.y!=0)wb=0.0;
    for(int q=0;q<4;q++){
        int x=2*cx+(q&1),y=2*cy+(q>>1);
        vec2 pos=vec2(float(x),float(y))+sub;
        Acc a;initAcc(a);
        float frames=0.0; // accepted donor frames (6.1 accumulated_frame_weights)
        for(int f=1;f<frameCount;f++){
            float r=robust[(f-1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx];
            if(r<0.004)continue;
            if(kE.x>=0.0&&int(kE.x)!=f)continue;
            vec2 oc=origin(f,2*cx,2*cy);
            vec2 O=oc+(pos-cell);
            // ultrashort frame: isotropic kernel wide enough for a single Bayer frame (the base guide is an edge
            // along every highlight border, its across-edge sigma leaves R/B holes = green/magenta zipper)
            bool us=int(fParam[f].w)==5;
            vec3 Pf;
            if(us)Pf=vec3(kE.y,kE.y,0.0);
            else if((mode&1)!=0){ vec2 dc=floor((O+0.5)*0.5); Pf=dcovAt(f,int(dc.x),int(dc.y))*fParam[f].z; } // 6.1: NEAREST at the sample
            else Pf=P*fParam[f].z;
            float rw=r/max(fParam[f].y,1.0e-6);
            if(!us)frames+=rw;
            if(markU!=0)frameSamples(a,f,O,r,rw,Pf,(mode&2)!=0&&!us); else frameSamplesPlain(a,f,O,r,rw,Pf,(mode&2)!=0&&!us);
        }
        // Base frame last: inside the Bento mask it yields to the ultrashort frame; where the donors left
        // little coverage its kernel widens (6.1: covariance x0.3 below 4 accepted frames).
        if(wb>0.0){
            vec3 Pb;
            if((mode&4)!=0)Pb=frames<kD.x?P/(kD.y*kD.y):P;
            else { float widen=mix(kD.y,1.0,smoothstep(0.5*kD.x,kD.x,a.cover)); Pb=P/(widen*widen); }
            if(markU!=0)frameSamples(a,0,pos,wb,wb,Pb,(mode&2)!=0&&m<=0.0); else frameSamplesPlain(a,0,pos,wb,wb,Pb,(mode&2)!=0&&m<=0.0);
        }
        vec3 col;uint cfl=0u;
        for(int c=0;c<3;c++){
            if(a.den[c]>1.0e-7)col[c]=a.num[c]/a.den[c];
            else if(a.clipDen[c]>0.0){col[c]=a.clipNum[c]/a.clipDen[c];cfl|=1u<<uint(c);} // everything clipped: keep the clipped level
            // No sample at all: an outlier site under the 6.1 window (+-1.5 px; the next R/B site is 2 px away). Where the frames do
            // not move against each other (tripod) every frame skips the same site and the colour came out 0 (static burst replay:
            // ~750 R/B holes): the base frame's lattice around the pixel fills it.
            else col[c]=(markU!=0&&wb>0.0)?baseFill(c,pos):0.0;
            if(a.clipDen[c]>0.0)cfl|=8u;
        }
        int ox=x*g+sx,oy=y*g+sy;
        int o=((oy-oy0)*ow+ox)*3;
        outRgb[o]=col.x;outRgb[o+1]=col.y;outRgb[o+2]=col.z;
        eff[(oy-oy0)*ow+ox]=a.cover+wb;
        if(mergeModeU.z!=0||rimU.x>0.0){
            if(m>0.0)cfl|=16u;
            if(a.usClip>0.0&&(cfl&7u)!=0u)cfl|=32u;
            // candidates of kHybRim (bits 8-13; the trailer keeps the low byte): the largest share of a colour's kernel weight that
            // went to clipped samples, 0 = left alone. Only pixels with a real green: where green fell back to the clipped mean too
            // (inside the highlight), the colour of the real sites around may be that of another surface (a lamp rim measured bluish
            // next to its white inside): those are left to the highlight recovery and its defringe (vivohdr/nicergb).
            if(rimU.x>0.0&&(cfl&8u)!=0u&&a.den[1]>1.0e-7){
                float e=0.0;
                for(int c=0;c<3;c++)e=max(e,a.clipDen[c]/max(a.den[c]+a.clipDen[c],1.0e-20));
                cfl|=uint(clamp(e,0.0,1.0)*63.0+0.5)<<8;
            }
            // bit 6: the clip-border colour of this pixel is the worker's (kHybRim ran on the merge): consumers skip their own
            // blanket border defringe, which inside a Bento highlight (bit 3 almost everywhere) also took the colour of real lights
            if(rimU.x>0.0&&(cfl&8u)!=0u)cfl|=64u;
            cflags[(oy-oy0)*ow+ox]=cfl;
        }
    }
}
)";

// Clip-border colour pass (after the merge of a strip, one invocation per output pixel; only pixels whose clip flags carry the
// border statistics of the merge do any work). R and B become G x the ratios of the real sites around, blended in by the largest
// share of a colour's kernel weight that went to clipped samples (rimU.zw); green (the level) stays unless a saturated R or B proves
// it too low. A colour that saturated itself (its excluded weight mostly at or above the clip: a lower bound for the highlight
// recovery) never drops below the merge.
static const char* kHybRim=R"(
layout(std430,binding=9) buffer Sums{uint sums[];}; // [frameCount + 2]: output pixels rebuilt from the ratios
// rimU (kHybMergeCommon): x = 1 on, y = ratio kernel exponent -log2(e)/(2 sigma^2), z/w = ramp of the largest excluded weight share
uniform int rimStrideU;   // donors that give ratios: every rimStrideU-th (the base and the ultrashort always)
uniform int rimSitesU;    // 16 = the 4x4 lattice; a uniform trip count keeps the compiler from unrolling the ~300 reads (compile time)
// Clip-border colour (research/hybrid5/fix_rim.md). At a sharp clip edge every colour keeps only its unclipped lattice sites. They
// lie on other rows/columns for R, G and B (R and G1 on even rows, G2 and B on odd ones), and a colour with none left in its
// two-site lattice takes the clipped mean, i.e. the real values of the clipped cells on the BRIGHT side. Across an edge that rises
// x2-3 per sensor row R and B then come from other scene levels than G: R one row further out = cyan, R from the bright side while
// G and B come from the row before = red (B alike at the other lattice phase); the dashes follow the edge crossing the 2x2 cells.
// The colour is rebuilt from ratios measured where they are real: every R (B) site of the 4x4 lattice around O whose value and
// whose green neighbours are below the clip, the green interpolated AT the site along the smoother direction (the same-row pair
// for a horizontal edge, the same-column pair for a vertical one), so the ratio does not depend on the steep profile.
float rimSite(int f,int x,int y,out bool ok){
    uint fl=0u;
    float v=markU!=0?rawSite(f,x,y,fl):sampleRaw(f,x,y);
    ok=(fl&1u)==0u&&v<clipLevel;
    return v;
}
// acc.xy += (R, G at the R sites), acc.zw += (B, G at the B sites), weighted by r * kernel, base units; accW = the weights.
void rimRatios(inout vec4 acc,inout vec2 accW,int f,vec2 O,float r){
    float g=fParam[f].x*r;
    for(int k=0;k<2;k++){
        int p=0;
        for(int q=0;q<4;q++)if(phaseColor[q]==2*k)p=q;            // the red (k = 0) or blue (k = 1) phase
        int px=p&1,py=p>>1;
        int bx=int(floor((O.x-float(px))*0.5))*2+px,by=int(floor((O.y-float(py))*0.5))*2+py;
        for(int n=0;n<rimSitesU;n++){
            int qx=bx+2*(n&3)-2,qy=by+2*(n>>2)-2;
            vec2 d=vec2(float(qx),float(qy))-O;
            float w=exp2(rimU.y*dot(d,d));
            if(w<0.01)continue;
            bool ok,l,rt,u,dn;
            float v=rimSite(f,qx,qy,ok);
            if(!ok)continue;
            float gl=rimSite(f,qx-1,qy,l),gr=rimSite(f,qx+1,qy,rt),gu=rimSite(f,qx,qy-1,u),gd=rimSite(f,qx,qy+1,dn);
            // signed RAW (black-level noise down to -0.25): a negative pair sum must not make the weight negative or infinite
            float wh=(l&&rt)?1.0/(abs(gl-gr)+0.002+0.05*max(gl+gr,0.0)):0.0;
            float wv=(u&&dn)?1.0/(abs(gu-gd)+0.002+0.05*max(gu+gd,0.0)):0.0;
            if(wh+wv<=0.0)continue;
            float gs=(wh*(gl+gr)+wv*(gu+gd))*0.5/(wh+wv);
            if(k==0)acc.xy+=(w*g)*vec2(v,gs); else acc.zw+=(w*g)*vec2(v,gs);
            accW[k]+=w*g;
        }
    }
}
void main(){
    int g=kG.x,ow=kG.y,w2=size.x/2;
    int ox=int(gl_GlobalInvocationID.x),row=chunkU+int(gl_GlobalInvocationID.y);
    if(ox>=ow||row>=2*(cy1-cy0)*g)return;
    int i=row*ow+ox;
    uint cfl=cflags[i];
    uint e6=(cfl>>8)&63u;
    if(e6==0u)return;
    float t=smoothstep(rimU.z,rimU.w,float(e6)*(1.0/63.0));
    if(t<=0.0)return;
    cfl&=255u;
    int x=ox/g,y=2*cy0+row/g,sx=ox-(ox/g)*g,sy=row-(row/g)*g;
    int cx=x>>1,cy=y>>1;
    vec2 pos=vec2(float(x),float(y))+(g==2?vec2(sx==0?-0.25:0.25,sy==0?-0.25:0.25):vec2(0.0));
    vec2 cell=vec2(float(2*cx),float(2*cy));
    // The merge's samples of this pixel again (kHybMergeMain1: same weights and kernels), now with the saturated share of every
    // colour.
    vec3 P=cov[(cy-ry0)*w2+cx].xyz;
    float m=kD.w>0.5?bmask[(cy-cy0)*w2+cx]:0.0;
    int mode=mergeModeU.x;
    float wb=1.0-m;
    if(kE.x>=0.0||mergeModeU.y!=0)wb=0.0;
    // One call site per sampler (donors, then the base last as in the merge): every inlined copy costs compile time.
    Acc a;initAcc(a);
    float frames=0.0;
    for(int k=1;k<=frameCount;k++){
        int f=k<frameCount?k:0;
        float r,rw;vec2 O;vec3 Pf;bool win;
        if(f!=0){
            r=robust[(f-1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx];
            if(r<0.004)continue;
            if(kE.x>=0.0&&int(kE.x)!=f)continue;
            O=origin(f,2*cx,2*cy)+(pos-cell);
            bool us=int(fParam[f].w)==5;
            if(us)Pf=vec3(kE.y,kE.y,0.0);
            else if((mode&1)!=0){ vec2 dc=floor((O+0.5)*0.5); Pf=dcovAt(f,int(dc.x),int(dc.y))*fParam[f].z; }
            else Pf=P*fParam[f].z;
            rw=r/max(fParam[f].y,1.0e-6);
            if(!us)frames+=rw;
            win=(mode&2)!=0&&!us;
        } else {
            if(wb<=0.0)continue;
            if((mode&4)!=0)Pf=frames<kD.x?P/(kD.y*kD.y):P;
            else { float widen=mix(kD.y,1.0,smoothstep(0.5*kD.x,kD.x,a.cover)); Pf=P/(widen*widen); }
            r=wb;rw=wb;O=pos;win=(mode&2)!=0&&m<=0.0;
        }
        frameSamples(a,f,O,r,rw,Pf,win); // without marking rawSite returns the plain words and no flags
    }
    vec3 ts=clamp(a.trueDen/max(a.den+a.clipDen,vec3(1.0e-20)),0.0,1.0);
    vec4 acc=vec4(0.0);vec2 accW=vec2(0.0);
    for(int k=1;k<=frameCount;k++){
        int f=k<frameCount?k:0;
        float r;vec2 O;
        if(f!=0){
            r=robust[(f-1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx];
            if(r<0.004)continue;
            if(kE.x>=0.0&&int(kE.x)!=f)continue;
            if(int(fParam[f].w)!=5&&rimStrideU>1&&(f%rimStrideU)!=0)continue;
            O=origin(f,2*cx,2*cy)+(pos-cell);
        } else {
            if(wb<=0.0)continue;
            r=wb;O=pos;
        }
        rimRatios(acc,accW,f,O,r);
    }
    vec3 col=vec3(outRgb[i*3],outRgb[i*3+1],outRgb[i*3+2]);
    // A ratio needs sites that carry signal: where every real site around is dark background (a sharp light on black, the
    // sites next to it excluded by their clipped greens), R/G and B/G are noise over noise (synthetic white light: rho ~0 ->
    // G x10^4, R 0 = green dots). Mean G at the ratio sites >= 0.3 % of the pixel's brightest merged channel (real bursts:
    // >= 0.56 %; research/hybrid5/review_fix_rim.md).
    float sig=0.003*max(max(col.r,col.g),col.b);
    bvec2 has=bvec2(acc.y>1.0e-12&&acc.y>=sig*accW.x,acc.w>1.0e-12&&acc.w>=sig*accW.y);
    if(!has.x&&!has.y)return;
    vec2 rho=max(vec2(has.x?acc.x/acc.y:0.0,has.y?acc.z/acc.w:0.0),vec2(0.0)); // dark noisy sites: never a negative R/B
    // A colour that fell back to the clipped mean of samples at the clip (bit c, mostly saturated) is a lower bound. Where green
    // lost weight to saturated sites as well, the green of the merge comes from one side of the edge only and is too low: the
    // level rises until that lower bound fits the real ratio (G of a white light from its clipped B). Where green did not
    // saturate (a red or blue light: green is real) the level stays.
    float L=col.g;
    if(ts.y>0.25){
        // ratio floor 0.15 (review_fix_rim.md): the lifts seen on lamp0322/hh2241/user use R/G 0.31-0.36, B/G 0.52-0.6; a ratio
        // measured near 0 on dark surroundings must not raise the level x10^4 (was max(rho, 1e-4))
        if(has.x&&(cfl&1u)!=0u&&ts.x>0.5)L=max(L,col.r/max(rho.x,0.15));
        if(has.y&&(cfl&4u)!=0u&&ts.z>0.5)L=max(L,col.b/max(rho.y,0.15));
    }
    vec3 nc=col;nc.g=L;
    for(int k=0;k<2;k++){
        int c=2*k;
        if(!has[k])continue;
        bool sat=(cfl&(1u<<uint(c)))!=0u&&ts[c]>0.5;               // its own clipped mean stays a lower bound
        nc[c]=sat?max(col[c],L*rho[k]):L*rho[k];
        if(t>=0.5&&!sat)cfl&=~(1u<<uint(c));                       // no longer the clipped mean
    }
    col=mix(col,nc,t);
    if((cfl&7u)==0u)cfl&=~32u;
    outRgb[i*3]=col.x;outRgb[i*3+1]=col.y;outRgb[i*3+2]=col.z;
    cflags[i]=cfl;
    atomicAdd(sums[frameCount+2],1u);
}
)";

class HybridGpu {
    EGLDisplay display=EGL_NO_DISPLAY;
    EGLContext context=EGL_NO_CONTEXT;
    EGLSurface surface=EGL_NO_SURFACE;
    static constexpr int kSlots=16; // 0..14 merge (1 fixed-pattern mean, 2 site flags, 6 clip flags, 14 donor covariance), 15 readback staging
    GLuint meanProgram=0,flagsProgram=0,markProgram=0,guideProgram=0,cellsProgram=0,rejectProgram=0,dilateProgram=0,mergeProgram=0,rimProgram=0,buffers[kSlots]{};
    size_t capacity[kSlots]{};
    void check(const char* where){GLenum e=glGetError();if(e!=GL_NO_ERROR)throw std::runtime_error(std::string("HYBRID GPU ")+where+" GL error="+std::to_string(e));}
    void cleanup() noexcept {
        if(display==EGL_NO_DISPLAY)return;
        if(context!=EGL_NO_CONTEXT&&eglMakeCurrent(display,surface,surface,context)){
            for(GLuint program:{meanProgram,flagsProgram,markProgram,guideProgram,cellsProgram,rejectProgram,dilateProgram,mergeProgram,rimProgram})if(program)glDeleteProgram(program);
            glDeleteBuffers(kSlots,buffers);
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        }
        if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);
        if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
        eglTerminate(display);display=EGL_NO_DISPLAY;
    }
    GLuint compile(const char* body,bool standalone=false){
        GLuint shader=glCreateShader(GL_COMPUTE_SHADER);const char* sources[]={kCommonShader,kHybHelpers,body};
        if(standalone)glShaderSource(shader,1,&body,nullptr); else glShaderSource(shader,3,sources,nullptr);
        glCompileShader(shader);
        GLint ok=0;glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
        if(!ok){char msg[4096]{};glGetShaderInfoLog(shader,sizeof(msg),nullptr,msg);glDeleteShader(shader);throw std::runtime_error(std::string("HYBRID GPU shader: ")+msg);}
        GLuint program=glCreateProgram();glAttachShader(program,shader);glLinkProgram(program);glDeleteShader(shader);
        glGetProgramiv(program,GL_LINK_STATUS,&ok);
        if(!ok){char msg[4096]{};glGetProgramInfoLog(program,sizeof(msg),nullptr,msg);glDeleteProgram(program);throw std::runtime_error(std::string("HYBRID GPU link: ")+msg);}
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
    // Readback. Adreno 750 returns NULL (no GL error) from glMapBufferRange for some ranges (seen: a 50 MB range,
    // and the second 8 MB chunk of the same buffer); the ladder below tries the variants and keeps the first that
    // works for the rest of the run.
    int mapMethod=-1;
    const void* tryMap(GLenum target,size_t offset,size_t n,GLbitfield flags){
        const void* p=glMapBufferRange(target,GLintptr(offset),GLsizeiptr(n),flags);
        if(!p)glGetError();
        return p;
    }
    void get(int slot,void* data,size_t bytes){
        constexpr size_t kChunk=size_t(32)<<20; // strips are sized so that every readback is one map from offset 0
        for(size_t offset=0;offset<bytes;offset+=kChunk){
            const size_t n=std::min(kChunk,bytes-offset);
            bool done=false;
            for(int method=mapMethod<0?0:mapMethod;method<5&&!done;++method){
                const void* mapped=nullptr;GLenum target=GL_SHADER_STORAGE_BUFFER;
                if(method==0){glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[slot]);mapped=tryMap(target,offset,n,GL_MAP_READ_BIT);}
                else if(method==1){glFinish();glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[slot]);mapped=tryMap(target,offset,n,GL_MAP_READ_BIT|GL_MAP_UNSYNCHRONIZED_BIT);}
                else if(method==2){target=GL_COPY_READ_BUFFER;glBindBuffer(target,buffers[slot]);mapped=tryMap(target,offset,n,GL_MAP_READ_BIT);}
                else if(method==3){ // staging copy into a GL_DYNAMIC_READ buffer, mapped from offset 0
                    target=GL_COPY_WRITE_BUFFER;
                    glBindBuffer(GL_COPY_READ_BUFFER,buffers[slot]);glBindBuffer(GL_COPY_WRITE_BUFFER,buffers[kSlots-1]);
                    if(capacity[kSlots-1]<n){glBufferData(GL_COPY_WRITE_BUFFER,GLsizeiptr(n),nullptr,GL_DYNAMIC_READ);capacity[kSlots-1]=n;}
                    glCopyBufferSubData(GL_COPY_READ_BUFFER,GL_COPY_WRITE_BUFFER,GLintptr(offset),0,GLsizeiptr(n));
                    glFinish();
                    mapped=tryMap(target,0,n,GL_MAP_READ_BIT);
                } else { // last resort: a fresh buffer object per chunk
                    target=GL_COPY_WRITE_BUFFER;GLuint tmp=0;glGenBuffers(1,&tmp);
                    glBindBuffer(GL_COPY_READ_BUFFER,buffers[slot]);glBindBuffer(GL_COPY_WRITE_BUFFER,tmp);
                    glBufferData(GL_COPY_WRITE_BUFFER,GLsizeiptr(n),nullptr,GL_DYNAMIC_READ);
                    glCopyBufferSubData(GL_COPY_READ_BUFFER,GL_COPY_WRITE_BUFFER,GLintptr(offset),0,GLsizeiptr(n));
                    glFinish();
                    mapped=tryMap(target,0,n,GL_MAP_READ_BIT);
                    if(mapped){std::memcpy(static_cast<char*>(data)+offset,mapped,n);glUnmapBuffer(target);glDeleteBuffers(1,&tmp);mapMethod=method;done=true;break;}
                    glDeleteBuffers(1,&tmp);
                }
                if(mapped){
                    std::memcpy(static_cast<char*>(data)+offset,mapped,n);
                    if(!glUnmapBuffer(target))throw std::runtime_error("HYBRID GPU storage invalidated");
                    if(mapMethod!=method){mapMethod=method;if(method>0&&trace)trace("HYBRID GPU readback method "+std::to_string(method));}
                    done=true;
                }
            }
            if(!done)throw std::runtime_error("HYBRID GPU readback failed slot="+std::to_string(slot)+" offset="+std::to_string(offset)+" bytes="+std::to_string(n)+" total="+std::to_string(bytes));
        }
    }
    static GLint loc(GLuint program,const char* n){return glGetUniformLocation(program,n);}
    // A pass may be split into dispatches of `chunk` rows (a multiple of the 8-row workgroup), flushed together: a single
    // dispatch that runs for seconds trips the kernel's GPU hang detection (the context is reset, every later map returns NULL),
    // many small flushed submissions cost ~1 ms each. The current program must be bound.
    void dispatchRows(GLuint program,int w2,int rows,int gz,int chunk,int pass,bool barrier=true){
        const GLint l=loc(program,"chunkU");
        const auto t0=std::chrono::steady_clock::now();
        for(int r=0;r<rows;r+=chunk){
            glUniform1i(l,r);
            glDispatchCompute(GLuint((w2+7)/8),GLuint((std::min(chunk,rows-r)+7)/8),GLuint(std::max(gz,1)));
        }
        glFlush();
        if(barrier)glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        if(profile){glFinish();passMs[pass]+=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();}
    }
public:
    bool profile=false;            // glFinish after every pass and add its time to passMs
    double passMs[8]{};            // flags, guide, cells, reject, dilate, merge, readback, mark
    std::string renderer,limits,compileMs;size_t maxStorageBlock=0;
    std::function<void(const std::string&)> trace;
    struct Frames {
        int w=0,h=0,cfa=0;
        std::array<float,4> black{},inv{};
        std::array<int,4> phaseColor{};
        std::vector<const uint16_t*> frames;            // 0 = base
        std::vector<BackwardHomography> homography;     // per frame
        std::vector<float> gain,weight,kmul,noiseSlope,noiseOffset;
        std::vector<int> role;
        float baseSlope=0,baseOffset=0,white=0;
        const std::vector<float>* mask=nullptr;         // per cell (w/2 x h/2), 0 = no Bento
        std::vector<int> hotList;                       // normal frames of the fixed-pattern outlier test (base first); empty = off
        std::array<float,4> k61a{},k61b{},k61c{};      // Sabre 6.1 kernel uniforms (k61a[3] = 0: round-4 kernel)
        int mergeMode=0;                                // 1 per-frame 6.1 covariance, 2 6.1 window, 4 6.1 base widening
        bool noBase=false;                              // split-half diagnostics: the base is not accumulated
    };
    long fixedOutliers=0,baseOutliers=0;                // sites flagged by the last merge
    long rimPixels=0;                                   // output pixels whose R/B the clip-border ratios rebuilt
    // rimPass: compile the clip-border colour pass (kHybRim, ~0.14 s on Adreno 750); without it merge() runs as before the pass.
    explicit HybridGpu(bool rimPass=true){
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
            {GLint64 ssbo=0;GLint tex=0,wg=0;glGetInteger64v(GL_MAX_SHADER_STORAGE_BLOCK_SIZE,&ssbo);glGetIntegerv(GL_MAX_TEXTURE_SIZE,&tex);
             glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_COUNT,1,&wg);maxStorageBlock=size_t(std::max<GLint64>(ssbo,0));
             GLint blocks=0,bindings=0;glGetIntegerv(GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS,&blocks);glGetIntegerv(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS,&bindings);
             limits="ssbo="+std::to_string(ssbo/(1024*1024))+"MB tex="+std::to_string(tex)+" wgY="+std::to_string(wg)
                 +" blocks="+std::to_string(blocks)+" bindings="+std::to_string(bindings);}
            auto timed=[&](const char* name,const char* body,bool standalone=false){
                const auto t0=std::chrono::steady_clock::now();GLuint p=compile(body,standalone);
                compileMs+=std::string(" ")+name+"="+std::to_string(int(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count()));
                return p;
            };
            meanProgram=timed("mean",kHybMean);
            flagsProgram=timed("flags",kHybFlags);
            markProgram=timed("mark",kHybMark,true);
            guideProgram=timed("guide",kHybGuide);
            cellsProgram=timed("cells",kHybCells);
            rejectProgram=timed("reject",kHybReject);
            dilateProgram=timed("dilate",kHybDilate);
            { // debugging: SCAM_HYB_DEFS=A,B -> "#define A" / "#define B" before the merge body
                std::string defs;const char* env=std::getenv("SCAM_HYB_DEFS");
                if(env){std::stringstream s(env);std::string d;while(std::getline(s,d,','))if(!d.empty())defs+="#define "+d+"\n";}
                const std::string body=defs+kHybMergeCommon+kHybMergeMain1;
                mergeProgram=timed("merge",body.c_str());
            }
            if(rimPass){const std::string body=std::string("#define RIM_STATS 1\n")+kHybMergeCommon+kHybRim;rimProgram=timed("rim",body.c_str());}
            glGenBuffers(kSlots,buffers);check("init");
        }catch(...){cleanup();throw;}
    }
    HybridGpu(const HybridGpu&)=delete;
    ~HybridGpu(){cleanup();}

    // out: RGB (w*g)*(h*g)*3 (base units) on the output grid g (1 = sensor, 2 = Sabre 6.1 2x); effective: donor
    // coverage per output pixel (frames); robustShare[f]: mean accepted weight of frame f after dilation, scalar
    // weight and Bento mask; clipFlags (optional): per output pixel, bit 0/1/2 = R/G/B from the clipped mean, 3 = a clipped
    // sample was excluded, 4 = inside the Bento mask, 5 = the clipped mean includes the ultrashort frame.
    void merge(const Frames& in,const HybridTuning& tune,const SuperResTuning& kernel,bool bento,
               std::vector<float>& out,std::vector<float>& effective,std::vector<double>& robustShare,int grid=1,
               std::vector<uint8_t>* clipFlags=nullptr){
        const int frames=int(in.frames.size()),w=in.w,h=in.h,w2=w/2,h2=h/2;
        if(frames<1||frames>kHybridGpuFrames||(w&1)||(h&1)||int(in.homography.size())!=frames)throw std::runtime_error("HYBRID GPU unsupported burst shape");
        if(grid!=1&&grid!=2)throw std::runtime_error("HYBRID GPU grid");
        const int g=grid,ow=w*g;
        out.assign(size_t(ow)*h*g*3,0.f);effective.assign(size_t(ow)*h*g,1.f);robustShare.assign(frames,1.0);
        if(clipFlags)clipFlags->assign(size_t(ow)*h*g,0);
        // Uniforms common to all programs.
        std::vector<float> a(size_t(frames)*4),b(size_t(frames)*4),up(frames,1.f);
        for(int f=0;f<frames;++f){
            const auto& m=in.homography[f];
            a[f*4]=m.h[0];a[f*4+1]=m.h[1];a[f*4+2]=m.h[2];a[f*4+3]=m.h[3];
            b[f*4]=m.h[4];b[f*4+1]=m.h[5];b[f*4+2]=m.h[6];b[f*4+3]=m.h[7];up[f]=m.upRatio;
        }
        std::vector<float> noise4(size_t(frames)*4,0.f),param(size_t(frames)*4);
        for(int f=0;f<frames;++f){
            noise4[f*4]=in.noiseSlope[f];noise4[f*4+1]=in.noiseOffset[f];
            param[f*4]=in.gain[f];param[f*4+1]=in.weight[f];param[f*4+2]=in.kmul[f];param[f*4+3]=float(in.role[f]);
        }
        std::vector<GLuint> programs={meanProgram,flagsProgram,guideProgram,cellsProgram,rejectProgram,dilateProgram,mergeProgram};
        if(rimProgram)programs.push_back(rimProgram);
        glUseProgram(markProgram);
        glUniform2i(loc(markProgram,"size"),w,h);glUniform2i(loc(markProgram,"cfaShift"),in.cfa&1,in.cfa>>1);
        glUniform4f(loc(markProgram,"black"),in.black[0],in.black[1],in.black[2],in.black[3]);
        glUniform4f(loc(markProgram,"inv"),in.inv[0],in.inv[1],in.inv[2],in.inv[3]);
        glUniform1f(loc(markProgram,"clipLevel"),tune.clipLevel);
        for(GLuint program:programs){
            glUseProgram(program);
            glUniform2i(loc(program,"size"),w,h);
            glUniform1i(loc(program,"frameCount"),frames);
            glUniform2i(loc(program,"cfaShift"),in.cfa&1,in.cfa>>1);
            glUniform4f(loc(program,"black"),in.black[0],in.black[1],in.black[2],in.black[3]);
            glUniform4f(loc(program,"inv"),in.inv[0],in.inv[1],in.inv[2],in.inv[3]);
            glUniform4fv(loc(program,"hA"),frames,a.data());glUniform4fv(loc(program,"hB"),frames,b.data());glUniform1fv(loc(program,"upRatio"),frames,up.data());
            glUniform1i(loc(program,"mosaicBlock"),0);
            glUniform4i(loc(program,"phaseColor"),in.phaseColor[0],in.phaseColor[1],in.phaseColor[2],in.phaseColor[3]);
            glUniform2f(loc(program,"baseNoise"),in.baseSlope,in.baseOffset);
            glUniform4fv(loc(program,"fParam"),frames,param.data());
            glUniform4fv(loc(program,"fNoiseP"),frames,noise4.data());
            glUniform1f(loc(program,"clipLevel"),tune.clipLevel);
            glUniform4f(loc(program,"k61aU"),in.k61a[0],in.k61a[1],in.k61a[2],in.k61a[3]);
            glUniform4f(loc(program,"k61bU"),in.k61b[0],in.k61b[1],in.k61b[2],in.k61b[3]);
            glUniform4f(loc(program,"k61cU"),in.k61c[0],in.k61c[1],in.k61c[2],in.k61c[3]);
        }
        const bool hot=!in.hotList.empty()&&(tune.hotSigma>0||tune.hotBaseSigma>0);
        // Site flags ride in the two spare top bits of the uploaded words: needs white < 16384 (RAW10/12/14) and no word above
        // 16383 (checked below with the clip scan: a white level below the codes the sensor really sends would turn bits 14/15 of
        // those codes into flags).
        const bool markable=in.white>0&&in.white<16384.f;
        int flagMode=markable?((tune.cellClip?1:0)|(hot?2:0)):0;
        if(!markable&&(tune.cellClip||hot)&&trace)trace("HYBRID GPU: white level "+std::to_string(in.white)+" leaves no spare bits: cell clip and outlier sites off");
        glUseProgram(flagsProgram);
        glUniform4f(loc(flagsProgram,"hotSigU"),tune.hotSigma,tune.hotBaseSigma,tune.hotCross,tune.hotMaxLevel);
        glUseProgram(guideProgram);
        glUniform4f(loc(guideProgram,"kA"),kernel.base,kernel.shrunk,kernel.stretched,kernel.flat);
        glUniform4f(loc(guideProgram,"kB"),kernel.strengthScale,kernel.flat0,kernel.flat1,kernel.texStd);
        // gradient noise of the green tensor: diagonal difference of four samples (+-1/4) of variance slope/4 (u domain)
        glUniform4f(loc(guideProgram,"kC"),kernel.tensorNoise,tune.rawTensor,tune.rawNoise*in.baseSlope*tune.snrScale/16.f,0);
        glUseProgram(cellsProgram);
        glUniform1i(loc(cellsProgram,"dcovU"),(in.mergeMode&1)?1:0); // DCov (slot 14) is sized for the cells only in this mode
        glUseProgram(rejectProgram);
        glUniform4f(loc(rejectProgram,"rj"),tune.cdm,tune.boost,tune.varianceThreshold,tune.filterVariance);
        glUniform4f(loc(rejectProgram,"rk"),tune.boostEnable,0,0,0);
        glUseProgram(dilateProgram);
        glUniform4f(loc(dilateProgram,"dl"),tune.dilateOffset,tune.dilateScale,bento?1.f:0.f,tune.dilateFloor);
        glUseProgram(mergeProgram);
        glUniform4f(loc(mergeProgram,"kD"),tune.widenBelow,tune.widenMul,tune.kernelFloor,bento?1.f:0.f);
        {const float us=std::max(0.3f,tune.bentoUsSigma);glUniform4f(loc(mergeProgram,"kE"),float(tune.debugFrame),1.f/(us*us),0,0);}
        glUniform4i(loc(mergeProgram,"kG"),g,ow,0,0);
        glUniform4i(loc(mergeProgram,"mergeModeU"),in.mergeMode,in.noBase?1:0,clipFlags?1:0,0);
        // Clip-border colour pass (kHybRim): the merge only marks its candidates in the clip flags (the ratio loops inside the merge
        // program made the whole merge ~18x slower on Adreno 750 even with the switch off, three more accumulators ~12 %).
        const bool rim=tune.rimRatio!=0&&rimProgram!=0;
        {const float s=std::max(0.3f,tune.rimSigma);
         const float rimU[4]={rim?1.f:0.f,-0.7213475f/(s*s),tune.rimLo,std::max(tune.rimHi,tune.rimLo+1e-4f)};
         glUniform4fv(loc(mergeProgram,"rimU"),1,rimU);
         if(rim){
         glUseProgram(rimProgram);
         glUniform4fv(loc(rimProgram,"rimU"),1,rimU);
         glUniform1i(loc(rimProgram,"rimStrideU"),std::max(1,tune.rimStride));
         glUniform1i(loc(rimProgram,"rimSitesU"),16);
         glUniform4f(loc(rimProgram,"kD"),tune.widenBelow,tune.widenMul,tune.kernelFloor,bento?1.f:0.f);
         {const float us=std::max(0.3f,tune.bentoUsSigma);glUniform4f(loc(rimProgram,"kE"),float(tune.debugFrame),1.f/(us*us),0,0);}
         glUniform4i(loc(rimProgram,"kG"),g,ow,0,0);
         glUniform4i(loc(rimProgram,"mergeModeU"),in.mergeMode,in.noBase?1:0,clipFlags?1:0,0);}}
        std::vector<GLuint> zeros(size_t(std::max(frames,1))+3,0); // per-frame accepted weight, the two outlier counts, rim pixels
        reserve(9,zeros.size()*4);put(9,0,zeros.data(),zeros.size()*4);
        reserve(12,16); // mosaic frames: unused
        const int stripCells=128/(g*g); // 256 output rows per dispatch on the sensor grid; on the 2x grid the strip
                                        // shrinks so the Out readback stays ~12 MB (Adreno refuses larger/offset read maps)
        const int donors=std::max(1,frames-1);
        std::vector<GLuint> offsets(frames),cellOff(frames);std::vector<GLint> row0(frames),rows(frames),crow0(frames),crows(frames),cellGeom(size_t(frames)*4);
        std::vector<float> maskStrip;std::vector<GLuint> flagStrip;
        // Clipped samples per frame and 32-row block (CPU, once): without outlier tests a strip whose frames hold no clipped
        // sample needs no marking (most strips of most shots). The same scan finds the largest RAW word of the burst.
        constexpr int kBlock=32;
        const int blocks=(h+kBlock-1)/kBlock;
        std::vector<uint8_t> clipBlock(size_t(frames)*blocks,0);
        if(flagMode){
            float lowest=1e9f;
            for(int p=0;p<4;++p)lowest=std::min(lowest,in.black[p]+tune.clipLevel/std::max(in.inv[p],1e-12f));
            const uint16_t threshold=uint16_t(std::clamp(std::ceil(lowest),0.f,65535.f));
            std::vector<uint16_t> blockMax(size_t(frames)*blocks,0);
            mergeRowBands(frames*blocks,[&](int i0,int i1){
                for(int i=i0;i<i1;++i){
                    const int f=i/blocks,b=i%blocks;
                    const uint16_t* row=in.frames[f]+size_t(b)*kBlock*w;
                    const size_t n=size_t(std::min(kBlock,h-b*kBlock))*w;
                    uint16_t m=0;for(size_t k=0;k<n;++k)m=std::max(m,row[k]);
                    blockMax[i]=m;clipBlock[i]=(flagMode&1)&&m>=threshold;
                }
            });
            const uint16_t top=*std::max_element(blockMax.begin(),blockMax.end());
            if(top>=16384){
                flagMode=0;
                if(trace)trace("HYBRID GPU: RAW codes up to "+std::to_string(top)+" with white level "+std::to_string(in.white)
                    +" leave no spare bits: cell clip and outlier sites off");
            }
        }
        for(int cy0=0;cy0<h2;cy0+=stripCells){
            const int cy1=std::min(h2,cy0+stripCells),y0=2*cy0,y1=2*cy1;
            const int ry0=std::max(0,cy0-3),ry1=std::min(h2,cy1+3); // reject/guide margin for the 5x5 dilation
            // Upload the rows of every frame that output rows [y0-6, y1+6) map into (kernel + margins), even-aligned.
            size_t total=0,cellTotal=0;
            for(int f=0;f<frames;++f){
                float lo=1e9f,hi=-1e9f;
                for(int y:{std::max(0,2*ry0-4),std::min(h,2*ry1+4)})for(int x:{0,w-2}){
                    DonorPoint p=in.homography[f].project(x,y);
                    const float py=p.y/in.homography[f].upRatio;lo=std::min(lo,py);hi=std::max(hi,py);
                }
                int r0=std::max(0,int(std::floor(lo))-8)&~1,r1=std::min(h,(int(std::ceil(hi))+9)&~1);
                if(r1<=r0){r0=0;r1=std::min(h,8);}
                row0[f]=r0;rows[f]=r1-r0;offsets[f]=GLuint(total);total+=size_t(rows[f])*w;
                crow0[f]=r0/2;crows[f]=rows[f]/2;cellOff[f]=GLuint(cellTotal);cellTotal+=size_t(crows[f])*w2;
            }
            reserve(0,total*2);
            for(int f=0;f<frames;++f)put(0,size_t(offsets[f])*2,in.frames[f]+size_t(row0[f])*w,size_t(rows[f])*w*2);
            for(int f=0;f<frames;++f){cellGeom[f*4]=crow0[f];cellGeom[f*4+1]=crows[f];cellGeom[f*4+2]=GLint(cellOff[f]);cellGeom[f*4+3]=0;}
            for(GLuint program:programs){
                glUseProgram(program);
                glUniform1uiv(loc(program,"frameOffset"),frames,offsets.data());
                glUniform1iv(loc(program,"frameRow0"),frames,row0.data());
                glUniform1iv(loc(program,"frameRows"),frames,rows.data());
                glUniform4iv(loc(program,"fCells"),frames,cellGeom.data());
            }
            bool clipHere=false;
            for(int f=0;f<frames&&!clipHere;++f)
                for(int b=row0[f]/kBlock;b<=(row0[f]+rows[f]-1)/kBlock&&b<blocks;++b)if(clipBlock[size_t(f)*blocks+b]){clipHere=true;break;}
            const int stripMode=hot?flagMode:(clipHere?flagMode:0); // marking needed: outlier tests or clipped samples
            for(GLuint program:programs){glUseProgram(program);glUniform1i(loc(program,"markU"),stripMode?1:0);}
            reserve(10,size_t(ry1-ry0)*w2*16);reserve(11,size_t(ry1-ry0)*w2*16);
            reserve(5,std::max<size_t>(cellTotal,1)*16);
            reserve(14,(in.mergeMode&1)?std::max<size_t>(cellTotal,1)*8:16);
            reserve(7,size_t(donors)*(ry1-ry0)*w2*4);
            reserve(8,size_t(donors)*(cy1-cy0)*w2*4);
            reserve(9,zeros.size()*4);
            reserve(2,size_t(ry1-ry0)*w*2*4);           // site flags of the strip rows
            reserve(1,(size_t(ry1-ry0)*2+4)*w*4);       // fixed-pattern mean (rows +-2)
            // Bento mask rows of this strip (per cell).
            maskStrip.assign(size_t(cy1-cy0)*w2,0.f);
            if(bento&&in.mask&&in.mask->size()==size_t(w2)*h2)std::memcpy(maskStrip.data(),in.mask->data()+size_t(cy0)*w2,maskStrip.size()*4);
            reserve(13,maskStrip.size()*4);put(13,0,maskStrip.data(),maskStrip.size()*4);
            if(stripMode){
                glUseProgram(flagsProgram);
                glUniform1i(loc(flagsProgram,"ry0"),ry0);glUniform1i(loc(flagsProgram,"ry1"),ry1);
                glUniform1i(loc(flagsProgram,"cy0"),cy0);glUniform1i(loc(flagsProgram,"cy1"),cy1);
                if(hot){
                    // fixed-pattern mean: the hot frames that hold every row of the strip +-2 (motion moves the upload window).
                    // The strip rows are canonical; the frame holds sensor rows (canonical + cfa>>1): a row past the window is
                    // clamped to the last one held, a site of the other colour.
                    std::vector<GLint> list;
                    const int cfaY=in.cfa>>1;
                    for(int f:in.hotList)if(row0[f]<=std::max(0,2*ry0-2+cfaY)&&row0[f]+rows[f]>=std::min(h,2*ry1+2+cfaY))list.push_back(f);
                    const int nh=std::min<int>(16,int(list.size()));
                    glUseProgram(meanProgram);
                    if(nh>0)glUniform1iv(loc(meanProgram,"hotList"),nh,list.data());
                    glUniform4i(loc(meanProgram,"hotU"),nh,0,0,0);
                    glUniform1i(loc(meanProgram,"ry0"),ry0);glUniform1i(loc(meanProgram,"ry1"),ry1);
                    dispatchRows(meanProgram,w,2*(ry1-ry0)+4,1,512,0);
                    glUseProgram(flagsProgram);
                    glUniform4i(loc(flagsProgram,"hotU"),nh,(tune.hotSigma>0?1:0)|(tune.hotBaseSigma>0?2:0),0,0);
                    dispatchRows(flagsProgram,w,2*(ry1-ry0),1,128,0);
                } // without outlier tests the mark pass does not read the site flags
                check("flags");
                // write the flags into the RAW words of every frame (after kHybFlags, which reads the plain values)
                glUseProgram(markProgram);
                std::vector<GLint> mrows(size_t(frames)*2);
                for(int f=0;f<frames;++f){mrows[f*2]=row0[f];mrows[f*2+1]=rows[f];}
                glUniform1uiv(loc(markProgram,"mOffset"),frames,offsets.data());
                glUniform2iv(loc(markProgram,"mRows"),frames,mrows.data());
                glUniform4i(loc(markProgram,"mFlagsU"),ry0,ry1,stripMode,0);
                const GLint frameIdx=loc(markProgram,"frameIdx");
                int maxRows=1;for(int f=0;f<frames;++f)maxRows=std::max(maxRows,rows[f]);
                glUniform1i(frameIdx,0);
                dispatchRows(markProgram,w2,maxRows,frames,128,7);
                check("mark");
            }
            glUseProgram(guideProgram);
            glUniform1i(loc(guideProgram,"ry0"),ry0);glUniform1i(loc(guideProgram,"ry1"),ry1);
            dispatchRows(guideProgram,w2,ry1-ry0,1,256,1);
            check("guide");
            if(frames>1){
                glUseProgram(cellsProgram);
                const GLint cellFrame=loc(cellsProgram,"cellFrameU");
                if(in.mergeMode&1){ // 6.1 covariance (36 RAW sites a cell): one donor per dispatch keeps the reads of a frame together
                    for(int f=1;f<frames;++f){glUniform1i(cellFrame,f-1);dispatchRows(cellsProgram,w2,crows[f],1,256,2,false);}
                    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                } else {
                    int maxRows=1;for(int f=1;f<frames;++f)maxRows=std::max(maxRows,crows[f]);
                    glUniform1i(cellFrame,0);
                    dispatchRows(cellsProgram,w2,maxRows,frames-1,256,2);
                }
                check("cells");
                glUseProgram(rejectProgram);
                glUniform1i(loc(rejectProgram,"ry0"),ry0);glUniform1i(loc(rejectProgram,"ry1"),ry1);
                dispatchRows(rejectProgram,w2,ry1-ry0,frames-1,256,3);
                glUseProgram(dilateProgram);
                glUniform1i(loc(dilateProgram,"ry0"),ry0);glUniform1i(loc(dilateProgram,"ry1"),ry1);
                glUniform1i(loc(dilateProgram,"cy0"),cy0);glUniform1i(loc(dilateProgram,"cy1"),cy1);
                dispatchRows(dilateProgram,w2,cy1-cy0,frames-1,256,4);
                check("rejection");
            }
            const int rows2=(y1-y0)*g,oy0=y0*g; // output-grid rows of this strip
            reserve(3,size_t(rows2)*ow*3*4);reserve(4,size_t(rows2)*ow*4);
            reserve(6,(clipFlags||rim)?size_t(rows2)*ow*4:16);
            glUseProgram(mergeProgram);
            glUniform1i(loc(mergeProgram,"cy0"),cy0);glUniform1i(loc(mergeProgram,"cy1"),cy1);glUniform1i(loc(mergeProgram,"ry0"),ry0);
            for(int sub=0;sub<g*g;++sub){ // grid 2: one dispatch per sub-position (same registers as 1x; four passes)
                glUniform4i(loc(mergeProgram,"kG"),g,ow,sub&1,sub>>1);
                dispatchRows(mergeProgram,w2,cy1-cy0,1,64,5);
            }
            if(rim){ // after all sub-positions: the pass reads the merged colour and the border statistics
                glUseProgram(rimProgram);
                glUniform1i(loc(rimProgram,"cy0"),cy0);glUniform1i(loc(rimProgram,"cy1"),cy1);glUniform1i(loc(rimProgram,"ry0"),ry0);
                dispatchRows(rimProgram,ow,rows2,1,64,5);
            }
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
            check("merge");
            const auto readStarted=std::chrono::steady_clock::now();
            get(3,out.data()+size_t(oy0)*ow*3,size_t(rows2)*ow*3*4);
            get(4,effective.data()+size_t(oy0)*ow,size_t(rows2)*ow*4);
            if(clipFlags){
                flagStrip.resize(size_t(rows2)*ow);
                get(6,flagStrip.data(),flagStrip.size()*4);
                uint8_t* dst=clipFlags->data()+size_t(oy0)*ow;
                for(size_t i=0;i<flagStrip.size();++i)dst[i]=uint8_t(flagStrip[i]);
            }
            check("readback");
            passMs[6]+=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-readStarted).count();
        }
        std::vector<GLuint> sums(zeros.size());
        get(9,sums.data(),sums.size()*4);
        for(int f=1;f<frames;++f)robustShare[f]=double(sums[f])/(255.0*double(w2)*h2);
        fixedOutliers=long(sums[size_t(frames)]);baseOutliers=long(sums[size_t(frames)+1]);rimPixels=long(sums[size_t(frames)+2]);
    }
};

// ---------------------------------------------------------------------------------------------
// CPU side: sharpness (Shasta gate), Bento mask, per-frame weights, assembly.
// ---------------------------------------------------------------------------------------------

// Sharpness of a bracketed frame against the base on the 1/4 green guides (LMC MeasureSharpnessRaw /
// DiscardBlurryBracketedFrames): squared green gradient per unit exposure^2 with the noise contribution removed,
// over the pixels that are unsaturated in BOTH frames. A long frame clips the highlights the base still resolves;
// scoring each frame over its own unsaturated pixels would drop every bracketed frame of a bright scene.
struct SharpnessPair { double base=0,frame=0; long pixels=0; };
inline SharpnessPair hybridSharpnessPair(const Burst& b,int f,float exposure,float baseSlope,float baseOffset,float slope,float offset) {
    const Guide qb=guides(b,0)[0];
    const Guide qf=guides(b,f)[0];
    constexpr float sat=0.9f; // guide values are in the frame's own units (0..1 of white)
    double gb=0,gf=0,mb=0,mf=0;long n=0;
    for(int y=1;y<qb.h-1;++y)for(int x=1;x<qb.w-1;++x){
        const float cb=qb.at(x,y),lb=qb.at(x-1,y),rb=qb.at(x+1,y);
        const float cf=qf.at(x,y),lf=qf.at(x-1,y),rf=qf.at(x+1,y);
        if(cb>=sat||lb>=sat||rb>=sat||cf>=sat||lf>=sat||rf>=sat)continue;
        gb+=double(rb-cb)*(rb-cb)+double(lb-cb)*(lb-cb);
        gf+=double(rf-cf)*(rf-cf)+double(lf-cf)*(lf-cf);
        mb+=cb;mf+=cf;++n;
    }
    SharpnessPair p;p.pixels=n;
    if(n==0)return p;
    mb/=double(n);mf/=double(n);
    // 16 sites averaged per guide pixel of which 8 are green: variance of the mean = var/8; a difference doubles it, two differences 4x.
    const double nb=4.0*(double(baseSlope)*mb+baseOffset)/8.0,nf=4.0*(double(slope)*mf+offset)/8.0;
    p.base=std::max(gb/double(n)-nb,0.0);
    p.frame=std::max(gf/double(n)-nf,0.0)/(double(exposure)*exposure);
    return p;
}

struct BentoResult { bool active=false;std::string reason;double clippedFraction=0,usClippedRatio=0;int largestHole=0,inpaintHole=0;long invalidCells=0;std::vector<float> mask; };

// Highlight mask of the base (per 2x2 cell): clipped -> dilate r -> gaussian smooth; checked against the
// aligned ultrashort frame (LMC bento mask.cl + ShouldFallback).
inline BentoResult bentoMask(const Burst& b,int usSlot,const BackwardHomography& usH,float usExposure,const HybridTuning& t) {
    BentoResult res;
    const int w2=b.w/2,h2=b.h/2;
    std::vector<uint8_t> clip(size_t(w2)*h2,0),usClip(size_t(w2)*h2,0),near(size_t(w2)*h2,0);
    std::atomic<long> clipped{0},usClippedInMask{0};
    const float nearLevel=std::min(t.bentoNearClip,t.bentoHighlight);
    mergeRowBands(h2,[&](int y0,int y1){
        long lc=0;
        for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
            bool c=false,nr=false;
            for(int p=0;p<4;++p){const float v=b.sample(0,2*cx+(p&1),2*cy+(p>>1));if(v>=t.bentoHighlight)c=true;if(v>=nearLevel)nr=true;}
            if(c){clip[size_t(cy)*w2+cx]=1;++lc;}
            if(nr)near[size_t(cy)*w2+cx]=1;
        }
        clipped+=lc;
    });
    res.clippedFraction=double(clipped)/(double(w2)*h2);
    if(res.clippedFraction<=t.bentoMinClipped&&t.bento!=2){res.reason="not enough clipping";return res;}
    // dilate: diamond |dx|+|dy| <= r plus the outer ring of the (2r+1)^2 square
    const int r=std::max(0,t.bentoDilate);
    std::vector<uint8_t> dil(size_t(w2)*h2,0);
    mergeRowBands(h2,[&](int y0,int y1){
        for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
            bool on=false;
            for(int dy=-r;dy<=r&&!on;++dy){
                const int yy=cy+dy;if(yy<0||yy>=h2)continue;
                for(int dx=-r;dx<=r;++dx){
                    const int xx=cx+dx;if(xx<0||xx>=w2)continue;
                    const bool shape=(std::abs(dx)+std::abs(dy)<=r)||(std::abs(dx)==r||std::abs(dy)==r);
                    if(shape&&clip[size_t(yy)*w2+xx]){on=true;break;}
                }
            }
            dil[size_t(cy)*w2+cx]=on?1:0;
        }
    });
    // gaussian smooth 7x7 (sigma)
    const float sigma=std::max(t.bentoSmooth,0.01f);
    float k[7];float ks=0;for(int i=-3;i<=3;++i){k[i+3]=std::exp(-0.5f*i*i/(sigma*sigma));ks+=k[i+3];}
    for(float& v:k)v/=ks;
    std::vector<float> tmp(size_t(w2)*h2),mask(size_t(w2)*h2);
    mergeRowBands(h2,[&](int y0,int y1){
        for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
            float s=0;for(int i=-3;i<=3;++i)s+=k[i+3]*dil[size_t(cy)*w2+std::clamp(cx+i,0,w2-1)];
            tmp[size_t(cy)*w2+cx]=s;
        }
    });
    mergeRowBands(h2,[&](int y0,int y1){
        for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
            float s=0;for(int i=-3;i<=3;++i)s+=k[i+3]*tmp[size_t(std::clamp(cy+i,0,h2-1))*w2+cx];
            mask[size_t(cy)*w2+cx]=std::clamp(s,0.f,1.f);
        }
    });
    // Keep the replacement where the base is saturated or nearly so: the dilation band around a highlight also
    // covers dark neighbours (window rubber next to a white frame), where one ultrashort frame at x8..16 gain is
    // far noisier than the merged N frames. near := dilate(any sample >= nearLevel, r = 1) smoothed with sigma 1.
    if(t.bentoNearClip<t.bentoHighlight){
        std::vector<uint8_t> nd(size_t(w2)*h2,0);
        mergeRowBands(h2,[&](int y0,int y1){
            for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
                bool on=false;
                for(int dy=-1;dy<=1&&!on;++dy){const int yy=cy+dy;if(yy<0||yy>=h2)continue;
                    for(int dx=-1;dx<=1;++dx){const int xx=cx+dx;if(xx<0||xx>=w2)continue;if(near[size_t(yy)*w2+xx]){on=true;break;}}}
                nd[size_t(cy)*w2+cx]=on?1:0;
            }
        });
        float g[3];float gs=0;for(int i=-1;i<=1;++i){g[i+1]=std::exp(-0.5f*i*i);gs+=g[i+1];}
        for(float& v:g)v/=gs;
        mergeRowBands(h2,[&](int y0,int y1){
            for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
                float s=0;for(int i=-1;i<=1;++i)s+=g[i+1]*nd[size_t(cy)*w2+std::clamp(cx+i,0,w2-1)];
                tmp[size_t(cy)*w2+cx]=s;
            }
        });
        mergeRowBands(h2,[&](int y0,int y1){
            for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
                float s=0;for(int i=-1;i<=1;++i)s+=g[i+1]*tmp[size_t(std::clamp(cy+i,0,h2-1))*w2+cx];
                mask[size_t(cy)*w2+cx]*=std::clamp(s,0.f,1.f);
            }
        });
    }
    // The aligned ultrashort frame inside the mask (LMC Mask::RunWithUltraShortFrame, on the 2x2 cells): its own clipping and,
    // with bentoLmc, the intensity error |min(GainUp(us) - base, 0)| over RGB: where the gained ultrashort frame is much DARKER
    // than the base (motion, misalignment) it is invalid, the mask loses that place ((1 - error) * mask), and where the base is
    // clipped as well (middle >= 0.98, smallest >= 0.502) nothing can fill it: an inpainting hole. A clipped ultrashort frame is
    // NOT an error (GainUp = 1 >= base): its cells stay in the mask (the best lower bound; the clip flags mark them).
    std::atomic<long> inMask{0},invalidCells{0};
    std::vector<uint8_t> inpaint(size_t(w2)*h2,0);
    const float ratio=1.f/std::max(usExposure,1e-6f);
    mergeRowBands(h2,[&](int y0,int y1){
        long lu=0,lm=0,li=0;
        for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
            const size_t i=size_t(cy)*w2+cx;
            if(mask[i]<=0.f)continue;
            ++lm;
            const DonorPoint o=usH.bayerOrigin(2*cx,2*cy);
            const int ux=std::clamp(int(std::lround(o.x*0.5f)),0,w2-1),uy=std::clamp(int(std::lround(o.y*0.5f)),0,h2-1);
            float us[4];bool c=false;
            for(int p=0;p<4;++p){us[p]=b.sample(usSlot,2*ux+(p&1),2*uy+(p>>1));if(us[p]>=t.bentoHighlight)c=true;}
            if(c){usClip[i]=1;++lu;}
            if(!t.bentoLmc)continue;
            // canonical RGGB: phase 0 red, 1 and 2 green, 3 blue
            const float base3[3]={b.sample(0,2*cx,2*cy),0.5f*(b.sample(0,2*cx+1,2*cy)+b.sample(0,2*cx,2*cy+1)),b.sample(0,2*cx+1,2*cy+1)};
            const float us3[3]={std::min(us[0]*ratio,1.f),std::min(0.5f*(us[1]+us[2])*ratio,1.f),std::min(us[3]*ratio,1.f)};
            float e2=0;
            for(int k=0;k<3;++k){const float d=std::min(us3[k]-base3[k],0.f);e2+=d*d;}
            const float error=std::sqrt(e2);
            if(error<t.bentoInvalid)continue;
            ++li;
            mask[i]=std::clamp((1.f-error)*mask[i],0.f,1.f);
            const float lo=std::min({base3[0],base3[1],base3[2]}),hi=std::max({base3[0],base3[1],base3[2]});
            const float middle=base3[0]+base3[1]+base3[2]-lo-hi;
            if(lo>=t.bentoInpaintMin&&middle>=t.bentoInpaintMiddle)inpaint[i]=1;
        }
        usClippedInMask+=lu;inMask+=lm;invalidCells+=li;
    });
    res.usClippedRatio=inMask>0?double(usClippedInMask)/double(inMask):0.0;
    res.invalidCells=invalidCells;
    // largest connected component (LMC HasLargeHoleNeedingInpainting: cv::connectedComponentsWithStats, 8-connected)
    auto largestComponent=[&](const std::vector<uint8_t>& on,bool eight){
        std::vector<int> stack;int largest=0;
        std::vector<uint8_t> seen(on.size(),0);
        static const int nb[8][2]={{1,0},{-1,0},{0,1},{0,-1},{1,1},{-1,1},{1,-1},{-1,-1}};
        for(size_t i=0;i<on.size();++i){
            if(!on[i]||seen[i])continue;
            int area=0;stack.clear();stack.push_back(int(i));seen[i]=1;
            while(!stack.empty()){
                const int c=stack.back();stack.pop_back();++area;
                const int cx=c%w2,cy=c/w2;
                for(int k=0;k<(eight?8:4);++k){const int xx=cx+nb[k][0],yy=cy+nb[k][1];if(xx<0||yy<0||xx>=w2||yy>=h2)continue;const size_t j=size_t(yy)*w2+xx;if(on[j]&&!seen[j]){seen[j]=1;stack.push_back(int(j));}}
            }
            largest=std::max(largest,area);
            if(largest>4096)break;
        }
        return largest;
    };
    res.largestHole=largestComponent(usClip,false); // round-4 criterion (us-clipped cells); with bentoLmc only a metric
    res.inpaintHole=t.bentoLmc?largestComponent(inpaint,true):0;
    // LMC ShouldFallback order: clipped pixels -> inpainting hole -> clipping ratio on the ultrashort frame. Mode 2 (always) runs
    // the checks and reports them like LMC's force_apply, without falling back.
    std::string would;
    if(res.clippedFraction<=t.bentoMinClipped)would="not enough clipping";
    else if((t.bentoLmc?res.inpaintHole:res.largestHole)>=t.bentoMaxHole)would="large inpainting hole";
    else if(res.usClippedRatio>t.bentoMaxUsClipped)would="high clipping ratio on ultrashort";
    if(!would.empty()&&t.bento!=2){res.reason=would;return res;}
    res.active=true;res.reason=would.empty()?"success":"forced; would fall back: "+would;res.mask=std::move(mask);
    return res;
}

struct HybridStats { double alignMs=0,maskMs=0,mergeMs=0; int merged=0,droppedBracketed=0; bool bento=false; };

// The merge. `alignment` returns one backward homography per slot of a 7-slot Burst (slot 0 = reference);
// frames beyond six are aligned in groups like the extra ZSL frames of the NICE path.
inline std::vector<float> hybridReconstruct(const HybridInput& input,const HybridTuning& tune,
                                            const NiceAlignment& alignment,
                                            const std::function<void(const std::string&)>& report,
                                            std::vector<uint16_t>* mergedDng,std::vector<uint8_t>* effMap,
                                            HybridStats* statsOut=nullptr,std::vector<uint8_t>* clipFlags=nullptr) {
    using Clock=std::chrono::steady_clock;
    const auto started=Clock::now();
    auto millis=[](auto d){return std::chrono::duration<double,std::milli>(d).count();};
    if(input.frames.empty()||int(input.frames.size())>kHybridMaxFrames)throw std::runtime_error("HYBRID: 1.."+std::to_string(kHybridMaxFrames)+" frames");
    const int w=input.w,h=input.h;
    // A Burst view for the shared helpers (sampleRaw, guides, alignment): slot 0 = base.
    Burst b;b.w=w;b.h=h;b.cfa=input.cfa;b.white=input.white;b.black=input.black;b.canonicalRggb=true;
    for(int s=0;s<7;++s){b.raw[s]=input.frames[0].raw;b.exposure[s]=1;b.iso[s]=std::max(1u,input.frames[0].iso);}
    const int n=int(input.frames.size());
    HybridStats stats;
    // ---- alignment: every frame against the base, groups of six
    std::vector<BackwardHomography> H(n);
    std::vector<bool> aligned(n,true);
    const auto alignStarted=Clock::now();
    if(alignment){
        for(int first=1;first<n;first+=6){
            Burst group=b;
            const int count=std::min(6,n-first);
            for(int j=0;j<count;++j){group.raw[1+j]=input.frames[first+j].raw;group.exposure[1+j]=input.frames[first+j].exposure;group.iso[1+j]=input.frames[first+j].iso;}
            const auto hs=alignment(group);
            for(int j=0;j<count;++j){
                H[first+j]=hs[1+j];
                try{H[first+j].validate();}catch(const std::exception&){aligned[first+j]=false;}
                // replaceFailed() points a failed slot at the reference frame: detect and drop it.
                if(group.raw[1+j]!=input.frames[first+j].raw)aligned[first+j]=false;
            }
        }
    } else {
        // No corner tracker: global translation from the guide pyramids.
        const auto ref=guides(b,0);
        for(int f=1;f<n;++f){
            Burst one=b;one.raw[1]=input.frames[f].raw;one.exposure[1]=input.frames[f].exposure;
            const auto donor=guides(one,1);
            const Shift s=globalShift(ref,donor,input.frames[f].exposure);
            BackwardHomography t;t.h={1,0,s.x,0,1,s.y,0,0};H[f]=t;
        }
    }
    stats.alignMs=millis(Clock::now()-alignStarted);
    if(tune.profile){ // the homographies, so the residual alignment error can be measured offline (sabre2x_61.md F7b / R4)
        for(int f=1;f<n;++f){
            char line[200];const auto& m=H[f].h;
            std::snprintf(line,sizeof(line),"HYBRID H f=%d aligned=%d up=%.4f h=%.7g %.7g %.7g %.7g %.7g %.7g %.7g %.7g",f,int(aligned[f]),H[f].upRatio,m[0],m[1],m[2],m[3],m[4],m[5],m[6],m[7]);
            report(line);
        }
    }
    // ---- base noise model, SNR, kernel curves
    const HybridFrame& base=input.frames[0];
    const float baseSlope=std::max(base.slope,1e-9f),baseOffset=std::max(base.offset,0.f);
    SuperResTuning kernel;
    const float snr=tune.snrFixed>0?float(tune.snrFixed):sabreSnr(baseSlope,baseOffset,tune.snrScale);
    snrKernel(kernel,snr);
    kernel.base*=tune.kernelScale;kernel.shrunk*=tune.kernelScale;kernel.stretched*=tune.kernelScale;kernel.flat*=tune.kernelScale;
    // Sabre 6.1 kernel (sabre2x_61.md F1): key = 0.18/sqrt(O + 0.18 S) of the base frame (no snrScale), GCam 6.1 curves f0..f3
    // (research/gcam61/sabre_curves.json), f4 = 4, f5 = 2.2; uniforms exactly as libgcam 0x723f04-0x724008.
    std::array<float,4> k61a{},k61b{},k61c{};
    const float key61=tune.snrFixed>0?float(tune.snrFixed):sabreSnr(baseSlope,baseOffset,1.f);
    const bool sabre61=tune.sabre61==1||(tune.sabre61==2&&key61<=tune.s61MaxKey);
    const bool outliers=(tune.hotSigma>0||tune.hotBaseSigma>0)&&key61<=tune.hotMaxKey;
    if(sabre61){
        static const float kf0[]={8.f,16.f},vf0[]={0.33f,0.25f};
        static const float kf1[]={6.f,12.f,30.f},vf1[]={5.f,4.f,3.f};
        static const float kf2[]={1.f,4.f,14.f,30.f},vf2[]={0.01f,0.002f,0.001f,0.001f};
        static const float kf3[]={1.f,3.f,12.f,30.f},vf3[]={0.02f,0.015f,0.009f,0.006f};
        const float key=key61;
        const float f0=sabreCurve(key,kf0,vf0),f1=sabreCurve(key,kf1,vf1),f2=sabreCurve(key,kf2,vf2),f3=sabreCurve(key,kf3,vf3),f4=4.f,f5=2.2f;
        const float ks=std::max(tune.kernelScale,0.05f);
        k61a={f5/f0,1.f/(f0*f4),f2,1.f/f0};
        k61b={1.f/(f0*f1),1.f/f3,1.f/(ks*ks),tune.s61TensorNoise>0?tune.s61TensorNoise:1.f};
        k61c={tune.s61GdNoise>0?tune.s61GdNoise:1.f,0,0,0};
        auto sigma=[&](float p){return std::to_string(ks/(p*std::sqrt(std::log(2.f))));};
        report("HYBRID KERNEL: Sabre 6.1 baseNoise slope="+std::to_string(baseSlope)+" offset="+std::to_string(baseOffset)+" key="+std::to_string(key)
            +" f0="+std::to_string(f0)+" f1="+std::to_string(f1)+" f2="+std::to_string(f2)+" f3="+std::to_string(f3)
            +" sigma across="+sigma(k61a[0])+" base="+sigma(k61a[3])+" along="+sigma(k61a[1])+" blurred="+sigma(k61b[0])
            +" ("+((tune.s61Mode&1)?"per-frame covariance":"base covariance for all frames")+((tune.s61Mode&2)?", window 1.5 px":"")
            +((tune.s61Mode&4)?", base x0.3 below "+std::to_string(tune.widenBelow)+" frames":", base widening by donor coverage")+")"
            +(tune.sabre61==2?" auto":""));
    } else {
    if(tune.sabre61==2)report("HYBRID KERNEL: Sabre 6.1 kernel off (auto: key "+std::to_string(key61)+" > "+std::to_string(tune.s61MaxKey)+")");
    report("HYBRID KERNEL: baseNoise slope="+std::to_string(baseSlope)+" offset="+std::to_string(baseOffset)+" snr="+std::to_string(snr)+" across="+std::to_string(kernel.shrunk)+" base="+std::to_string(kernel.base)
        +" along="+std::to_string(kernel.stretched)+" flat="+std::to_string(kernel.flat)+" thresholds="+std::to_string(kernel.flat0)+"/"+std::to_string(kernel.flat1));
    }
    // ---- Shasta: bracketed frames softer than the base are dropped; too long a ratio drops them all
    std::vector<bool> keep(n,true);
    {
        float maxRatio=1;
        for(int f=1;f<n;++f){
            const auto& fr=input.frames[f];
            if(fr.role!=kRoleBracketed)continue;
            if(!tune.shastaEnable||!aligned[f]){keep[f]=false;++stats.droppedBracketed;continue;}
            Burst one=b;one.raw[1]=fr.raw;one.exposure[1]=fr.exposure;
            const SharpnessPair sp=hybridSharpnessPair(one,1,fr.exposure,baseSlope,baseOffset,fr.slope,fr.offset);
            const double pct=sp.base>0?sp.frame/sp.base:1.0;
            report("HYBRID SHASTA sharpness frame="+std::to_string(f)+" score="+std::to_string(sp.frame)+" base="+std::to_string(sp.base)
                +" pixels="+std::to_string(sp.pixels)+" ("+std::to_string(100*pct)+" % of base)");
            if(pct<tune.shastaSharpness){keep[f]=false;++stats.droppedBracketed;continue;}
            maxRatio=std::max(maxRatio,fr.exposure);
        }
        if(maxRatio>tune.shastaMaxRatio){
            for(int f=1;f<n;++f)if(input.frames[f].role==kRoleBracketed&&keep[f]){keep[f]=false;++stats.droppedBracketed;}
            report("HYBRID SHASTA: TET ratio "+std::to_string(maxRatio)+" above the limit; all bracketed frames dropped");
        }
    }
    // ---- Bento: the ultrashort frame with the lowest exposure
    int us=-1;
    for(int f=1;f<n;++f)if(input.frames[f].role==kRoleUltrashort&&aligned[f]&&(us<0||input.frames[f].exposure<input.frames[us].exposure))us=f;
    for(int f=1;f<n;++f)if(input.frames[f].role==kRoleUltrashort&&f!=us)keep[f]=false;
    BentoResult bento;
    const auto maskStarted=Clock::now();
    if(us>=0&&tune.bento>0){
        if(input.frames[us].exposure>=1.f){bento.reason="ultrashort frame is not the shortest";}
        else {
            Burst one=b;one.raw[1]=input.frames[us].raw;one.exposure[1]=input.frames[us].exposure;
            bento=bentoMask(one,1,H[us],input.frames[us].exposure,tune);
        }
        report("HYBRID BENTO: "+std::string(bento.active?"applied":"not applied")+" ("+bento.reason+") clipped="+std::to_string(bento.clippedFraction)
            +" usClippedRatio="+std::to_string(bento.usClippedRatio)+" largestHole="+std::to_string(bento.largestHole)
            +" inpaintHole="+std::to_string(bento.inpaintHole)+" invalid="+std::to_string(bento.invalidCells)
            +" checks="+(tune.bentoLmc?"lmc":"round4")+" factor="+std::to_string(1.f/input.frames[us].exposure));
    } else if(us>=0)report("HYBRID BENTO: disabled by tuning");
    // Split-half diagnostics: odd or even normal donors only, no base, no Bento, no long frames (the base noise would be common
    // to both halves).
    if(tune.subset==1||tune.subset==2){
        int ordinal=0;
        for(int f=1;f<n;++f){
            if(input.frames[f].role!=kRoleNormal){keep[f]=false;continue;}
            if(!keep[f])continue;
            ++ordinal;
            if((ordinal&1)!=(tune.subset==1?1:0))keep[f]=false;
        }
        bento.active=false;
        report("HYBRID SUBSET: "+std::string(tune.subset==1?"odd":"even")+" normal donors, base not accumulated, no Bento/Shasta");
    }
    if(us>=0&&!bento.active)keep[us]=false;
    { // GPU capacity (kHybridGpuFrames): the app may send up to kHybridMaxFrames; drop the normal donors farthest from the base in time
        int kept=1;std::vector<int> normals;
        for(int f=1;f<n;++f)if(keep[f]){++kept;if(input.frames[f].role==kRoleNormal)normals.push_back(f);}
        if(kept>kHybridGpuFrames){
            const float t0=input.frames[0].orderMs;
            std::stable_sort(normals.begin(),normals.end(),[&](int a,int c){return std::abs(input.frames[a].orderMs-t0)<std::abs(input.frames[c].orderMs-t0);});
            int dropped=0;
            for(int i=int(normals.size())-1;i>=0&&kept>kHybridGpuFrames;--i,--kept,++dropped)keep[normals[i]]=false;
            report("HYBRID FRAMES: "+std::to_string(dropped)+" normal donors dropped (GPU holds "+std::to_string(kHybridGpuFrames)+" frames)");
        }
    }
    stats.maskMs=millis(Clock::now()-maskStarted);
    stats.bento=bento.active;
    // ---- per-frame weights (LMC driver): A = min(cap, ((TET_f/TET_b)^2 read_b/read_f)^fwe), LUTsigma(A)
    HybridGpu::Frames in;
    in.w=w;in.h=h;in.cfa=input.cfa;
    for(int k=0;k<4;++k){in.black[k]=input.black[k];in.inv[k]=1.f/(input.white-input.black[k]);}
    // canonical RGGB: phase 0 red, 1/2 green, 3 blue
    in.phaseColor={0,1,1,2};
    in.baseSlope=baseSlope;in.baseOffset=baseOffset;in.white=input.white;
    std::vector<int> index;
    auto lutSigma=[&](float A){
        if(A<=tune.lutLo)return 1.f;
        if(A>=tune.lutHi)return tune.lutHiSigma;
        return 1.f+(tune.lutHiSigma-1.f)*(A-tune.lutLo)/(tune.lutHi-tune.lutLo);
    };
    std::string table="HYBRID FRAMES: idx role TET weight sigmaMul";
    for(int f=0;f<n;++f){
        if(f>0&&!keep[f])continue;
        const auto& fr=input.frames[f];
        const float t=fr.exposure;
        float A=1.f;
        if(f>0){
            const float readB=std::max(baseOffset,1e-12f),readF=std::max(fr.offset,1e-12f);
            A=std::min(tune.weightCap,std::pow(t*t*readB/readF,tune.fwe));
            if(fr.role==kRoleUltrashort)A=tune.bentoUsWeight;
            if(!std::isfinite(A)||A<=0)A=1.f;
        }
        const float ls=lutSigma(A);
        in.frames.push_back(fr.raw);
        in.homography.push_back(f==0?BackwardHomography{}:H[f]);
        in.gain.push_back(1.f/t);
        in.weight.push_back(A);
        in.kmul.push_back(1.f/(ls*ls));
        in.role.push_back(fr.role);
        in.noiseSlope.push_back(std::max(fr.slope,1e-9f));
        in.noiseOffset.push_back(std::max(fr.offset,0.f));
        index.push_back(f);
        char line[96];std::snprintf(line,sizeof(line)," | %d %d %.4f %.2f %.3f",f,fr.role,t,A,ls);table+=line;
    }
    report(table);
    if(bento.active)in.mask=&bento.mask;
    in.k61a=k61a;in.k61b=k61b;in.k61c=k61c;
    in.mergeMode=sabre61?(tune.s61Mode&7):0;
    in.noBase=tune.subset==1||tune.subset==2;
    // Fixed-pattern outlier test: the base and up to hotFrames-1 more normal frames spread over the burst.
    if(outliers){
        std::vector<int> normals;
        for(size_t i=0;i<index.size();++i)
            if(in.role[i]==kRoleNormal&&std::abs(input.frames[index[i]].exposure-1.f)<0.02f)normals.push_back(int(i));
        const int want=std::clamp(tune.hotFrames,1,16),others=int(normals.size())-1;
        in.hotList.push_back(0);
        if(want>1&&others>0){
            const int step=std::max(1,others/(want-1));
            for(int i=1;i<int(normals.size())&&int(in.hotList.size())<want;i+=step)in.hotList.push_back(normals[i]);
        }
    }
    // ---- merge
    const auto mergeStarted=Clock::now();
    std::vector<float> out,effective,sensorRgb;std::vector<double> share;std::vector<uint8_t> flagsRaw;
    const int grid=tune.grid>0?std::min(tune.grid,2):std::max(1,std::min(input.grid,2));
    const int outW=w*grid,outH=h*grid;
    {
        const auto gpuStarted=Clock::now();
        HybridGpu gpu(tune.rimRatio!=0);gpu.trace=report;gpu.profile=tune.profile!=0;
        report("HYBRID GPU: "+gpu.renderer+" frames="+std::to_string(in.frames.size())+" limits "+gpu.limits
            +" init ms="+std::to_string(int(millis(Clock::now()-gpuStarted)))+" (compile"+gpu.compileMs+")");
        gpu.merge(in,tune,kernel,bento.active,out,effective,share,grid,clipFlags?&flagsRaw:nullptr);
        if(gpu.profile){
            const double* p=gpu.passMs;char pl[200];
            std::snprintf(pl,sizeof(pl),"HYBRID GPU ms: flags=%.0f mark=%.0f guide=%.0f cells=%.0f reject=%.0f dilate=%.0f merge=%.0f readback=%.0f",p[0],p[7],p[1],p[2],p[3],p[4],p[5],p[6]);
            report(pl);
        }
        const double mp=double(w)*h/1e6;
        char line[240];
        if(outliers)std::snprintf(line,sizeof(line),"HYBRID OUTLIERS: fixed-pattern sites=%ld (%.1f/MP, mean of %d frames, %.1f sigma) base transient=%ld (%.1f/MP, %.1f sigma) cellClip=%d",
            gpu.fixedOutliers,gpu.fixedOutliers/mp,int(in.hotList.size()),tune.hotSigma,gpu.baseOutliers,gpu.baseOutliers/mp,tune.hotBaseSigma,tune.cellClip);
        else std::snprintf(line,sizeof(line),"HYBRID OUTLIERS: off (key %.1f > %.1f or thresholds 0) cellClip=%d",key61,tune.hotMaxKey,tune.cellClip);
        report(line);
        if(tune.rimRatio)std::snprintf(line,sizeof(line),"HYBRID RIM: clip-border colour from real-site ratios, pixels=%ld (%.2f %% of the output) sigma=%.2f ramp=%.3f..%.3f stride=%d",
            gpu.rimPixels,100.0*double(gpu.rimPixels)/(double(outW)*outH),tune.rimSigma,tune.rimLo,tune.rimHi,tune.rimStride);
        else std::snprintf(line,sizeof(line),"HYBRID RIM: off");
        report(line);
    }
    if(grid==2&&mergedDng&&input.mergedDng){ // sensor-grid RGB for the DNG: mean of the 2x2 sub-positions
        sensorRgb.assign(size_t(w)*h*3,0.f);
        mergeRowBands(h,[&](int y0,int y1){
            for(int y=y0;y<y1;++y)for(int x=0;x<w;++x)for(int c=0;c<3;++c){
                const size_t o=(size_t(2*y)*outW+2*x)*3+c;
                sensorRgb[(size_t(y)*w+x)*3+c]=0.25f*(out[o]+out[o+3]+out[o+size_t(outW)*3]+out[o+size_t(outW)*3+3]);
            }
        });
    }
    if(grid==2)report("HYBRID OUTPUT: Sabre 2x grid "+std::to_string(outW)+"x"+std::to_string(outH)+" (sub-positions +-0.25 px, kernel in sensor px)");
    stats.mergeMs=millis(Clock::now()-mergeStarted);
    stats.merged=int(in.frames.size());
    {
        std::string line="HYBRID MERGE FACTORS:";
        double avg=0;int cnt=0;
        for(size_t i=1;i<share.size();++i){char v[48];std::snprintf(v,sizeof(v)," %d:%.2f",index[i],share[i]);line+=v;avg+=share[i];++cnt;}
        report(line+(cnt?"  average="+std::to_string(avg/cnt):""));
    }
    // ---- merged Bayer RAW for the DNG (sensor layout, 14-bit scale like the NICE path), from the RGB
    if(mergedDng&&input.mergedDng){
        const float k=16383.f/input.white;
        const int dx=input.cfa&1,dy=input.cfa>>1;
        mergedDng->assign(size_t(w)*h,0);
        mergeRowBands(h,[&](int y0,int y1){
            for(int y=y0;y<y1;++y)for(int x=0;x<w;++x){
                int cx=x-dx,cy=y-dy;if(cx<0)cx+=2;if(cy<0)cy+=2;
                const int phase=((cy&1)<<1)|(cx&1);const int c=phase==0?0:phase==3?2:1;
                const std::vector<float>& srcRgb=grid==2?sensorRgb:out;
                const float m=std::clamp(srcRgb[(size_t(cy)*w+cx)*3+c],0.f,1.f);
                const float black=input.black[((y&1)<<1)|(x&1)];
                (*mergedDng)[size_t(y)*w+x]=uint16_t(std::clamp(std::lround((black+m*(input.white-black))*k),0L,16383L));
            }
        });
    }
    // CFA phase shift to the canonical RGGB origin, in output-grid pixels.
    shiftOrigin(out,outW,outH,3,(input.cfa&1)*grid,(input.cfa>>1)*grid);
    if(effMap){
        std::vector<float> sample;
        for(size_t i=0;i<effective.size();i+=7)sample.push_back(effective[i]);
        float median=1.f;
        if(!sample.empty()){std::nth_element(sample.begin(),sample.begin()+sample.size()/2,sample.end());median=std::max(sample[sample.size()/2],1.f);}
        const float codeScale=64.f/median;
        effMap->assign(size_t(outW)*outH,0);
        const int dx=(input.cfa&1)*grid,dy=(input.cfa>>1)*grid;
        for(int y=0;y<outH;++y)for(int x=0;x<outW;++x){
            const size_t src=size_t(std::max(0,y-dy))*outW+std::max(0,x-dx);
            (*effMap)[size_t(y)*outW+x]=uint8_t(std::clamp(std::lround(effective[src]*codeScale),1L,255L));
        }
        report("HYBRID EFFECTIVE MAP: median frames="+std::to_string(median)+" code scale="+std::to_string(codeScale));
    }
    // Clip flags trailer (uint8 per output pixel, same grid and origin as the effective map): bit 0/1/2 = R/G/B came from the
    // clipped mean (no sample of that colour outside clipped cells in the kernel window: a lower bound where that colour itself
    // clipped; with cellClip the other colours of a clipped cell keep their real values, so consumers still compare the value
    // with the clip level), 3 = a sample of a clipped cell was excluded near this pixel (clip border), 4 = inside the Bento mask,
    // 5 = the clipped mean holds the ultrashort frame (clip at k x white instead of 1 x white), 6 = bit 3 and the clip-border
    // colour pass (kHybRim, rimRatio) ran: R/B there are the worker's (rebuilt from real-site ratios where they were inconsistent).
    if(clipFlags){
        clipFlags->assign(size_t(outW)*outH,0);
        const int dx=(input.cfa&1)*grid,dy=(input.cfa>>1)*grid;
        long counts[7]{},hist[256]{};
        for(int y=0;y<outH;++y)for(int x=0;x<outW;++x){
            const uint8_t v=flagsRaw.empty()?0:flagsRaw[size_t(std::max(0,y-dy))*outW+std::max(0,x-dx)];
            (*clipFlags)[size_t(y)*outW+x]=v;
            ++hist[v]; // 50 M pixels on the 2x grid: one increment each, the bit counts from the histogram
        }
        for(int v=1;v<256;++v)for(int k=0;k<7;++k)if(v&(1<<k))counts[k]+=hist[v];
        report("HYBRID CLIP FLAGS: trailer "+std::to_string(outW)+"x"+std::to_string(outH)+" uint8 (bits R,G,B clipped mean, border, Bento, ultrashort) R="
            +std::to_string(counts[0])+" G="+std::to_string(counts[1])+" B="+std::to_string(counts[2])+" border="+std::to_string(counts[3])
            +" bento="+std::to_string(counts[4])+" ultrashort="+std::to_string(counts[5])+(counts[6]?" rimChecked="+std::to_string(counts[6]):std::string()));
    }
    report("HYBRID STAGES ms: align="+std::to_string(stats.alignMs)+" mask="+std::to_string(stats.maskMs)+" merge="+std::to_string(stats.mergeMs)
        +" total="+std::to_string(millis(Clock::now()-started))+" merged="+std::to_string(stats.merged)+" droppedBracketed="+std::to_string(stats.droppedBracketed)
        +" bento="+std::to_string(stats.bento));
    if(statsOut)*statsOut=stats;
    return out;
}

// NCH v10 — the hybrid transport written by LmcHybridBurst.java: header 128 B (magic, version 10, w, h, cfa,
// frameCount, white, black[4], flags, baseIndex), frameCount x 32 B frame table (role, exposure ratio to the
// base, iso, noise slope, noise offset, order ms, flags, reserved), then the uint16 planes in sensor layout.
struct MappedHybridBurst {
    void* address=MAP_FAILED;size_t length=0;HybridInput input;bool hybrid=false;
    explicit MappedHybridBurst(const std::string& path) {
        const int fd=openArgument(path,O_RDONLY);
        if(fd<0)throw std::runtime_error("Cannot open NICE burst");
        struct stat st{};
        if(fstat(fd,&st)||st.st_size<128){close(fd);throw std::runtime_error("Invalid NICE file size");}
        length=size_t(st.st_size);address=mmap(nullptr,length,PROT_READ,MAP_PRIVATE,fd,0);close(fd);
        if(address==MAP_FAILED)throw std::runtime_error("Cannot map NICE burst");
        uint32_t h[32];std::memcpy(h,address,128);
        if(h[0]!=0x3143484e||(h[1]!=10&&h[1]!=11)){munmap(address,length);address=MAP_FAILED;return;}
        try {
            if(h[2]<64||h[3]<64||h[2]%2||h[3]%2||uint64_t(h[2])*h[3]>16000000||h[4]>3||h[5]<1||h[5]>64)
                throw std::runtime_error("Unsupported hybrid burst dimensions/CFA/count");
            input.w=int(h[2]);input.h=int(h[3]);input.cfa=int(h[4]);
            const int n=int(h[5]);
            std::memcpy(&input.white,h+6,4);std::memcpy(input.black.data(),h+7,16);
            const uint32_t flags=h[11];input.diagnostics=flags&1;input.mergedDng=flags&2;input.clipFlags=flags&4;
            if(h[1]>=11){ // v11: h[12] = base index (0), h[13] = output grid 1|2
                if(h[13]!=0&&h[13]!=1&&h[13]!=2)throw std::runtime_error("Unsupported hybrid output grid");
                input.grid=h[13]==0?1:int(h[13]);
            }
            if(!std::isfinite(input.white)||input.white<=1||input.white>65535)throw std::runtime_error("Hybrid white level");
            for(float b:input.black)if(!std::isfinite(b)||b<0||b+1>=input.white)throw std::runtime_error("Hybrid black level");
            const size_t pixels=size_t(input.w)*input.h,headerBytes=128+32*size_t(n);
            if(length!=headerBytes+pixels*2*size_t(n))throw std::runtime_error("Truncated hybrid burst");
            const auto* table=static_cast<const uint8_t*>(address)+128;
            const auto* data=reinterpret_cast<const uint16_t*>(static_cast<const uint8_t*>(address)+headerBytes);
            for(int i=0;i<n;++i){
                const uint8_t* r=table+32*i;
                HybridFrame f;uint32_t role,iso;
                std::memcpy(&role,r,4);std::memcpy(&f.exposure,r+4,4);std::memcpy(&iso,r+8,4);
                std::memcpy(&f.slope,r+12,4);std::memcpy(&f.offset,r+16,4);std::memcpy(&f.orderMs,r+20,4);
                if(role!=kRoleNormal&&role!=kRoleBracketed&&role!=kRoleUltrashort)throw std::runtime_error("Hybrid frame role");
                if(!std::isfinite(f.exposure)||f.exposure<1.f/512||f.exposure>512||(i==0&&std::abs(f.exposure-1)>1e-5f))
                    throw std::runtime_error("Hybrid frame exposure");
                if(!std::isfinite(f.slope)||f.slope<=0||!std::isfinite(f.offset)||f.offset<0)throw std::runtime_error("Hybrid frame noise");
                f.role=int(role);f.iso=iso;f.raw=data+size_t(i)*pixels;
                input.frames.push_back(f);
            }
            hybrid=true;
        }catch(...){munmap(address,length);address=MAP_FAILED;throw;}
    }
    MappedHybridBurst(const MappedHybridBurst&)=delete;
    ~MappedHybridBurst(){if(address!=MAP_FAILED)munmap(address,length);}
};

// NICE 7-slot transport (N0..N3, L, S, ES + extra N) seen as a hybrid burst: the longest frame is the
// bracketed one, the shortest of S/ES the ultrashort one, S otherwise dropped.
inline HybridInput hybridFromNiceBurst(const Burst& b) {
    HybridInput in;in.w=b.w;in.h=b.h;in.cfa=b.cfa;in.white=b.white;in.black=b.black;in.diagnostics=b.diagnostics;in.mergedDng=b.mergedDng;
    const NiceNoise nn=b.hasNormalNoise?b.normalNoise:b.cameraNoise?b.noise:imx06cHdrNoise(b.iso[forwardReferenceSlot]);
    auto add=[&](const uint16_t* raw,int role,float exposure,unsigned iso,NiceNoise noise){
        HybridFrame f;f.raw=raw;f.role=role;f.exposure=exposure;f.iso=iso;f.slope=noise.slope;f.offset=noise.offset;in.frames.push_back(f);
    };
    add(b.raw[forwardReferenceSlot],kRoleNormal,1.f,b.iso[forwardReferenceSlot],nn);
    for(int s=0;s<4;++s)if(s!=forwardReferenceSlot&&b.raw[s]!=b.raw[forwardReferenceSlot])add(b.raw[s],kRoleNormal,1.f,b.iso[s],nn);
    for(const uint16_t* e:b.extraNormals)add(e,kRoleNormal,1.f,b.iso[forwardReferenceSlot],nn);
    // L (slot 4): bracketed when it is a real longer exposure; its noise model = the N model at its gain.
    if(b.syntheticLong<=0&&b.exposure[4]>1.5f&&b.raw[4]!=b.raw[forwardReferenceSlot]){
        NiceNoise ln=b.noiseReferenceSlot==4&&b.cameraNoise?b.noise:nn;
        add(b.raw[4],kRoleBracketed,b.exposure[4],b.iso[4],ln);
    }
    // Ultrashort: the short frame closest to LMC's TET_base/8 (S at -3 EV rather than ES at -6 EV when both exist).
    auto distance=[&](int s){return std::abs(std::log(std::max(b.exposure[s],1e-6f)*8.f));};
    const int shortest=distance(6)<distance(5)?6:5;
    if(b.exposure[shortest]<1.f&&b.raw[shortest]!=b.raw[forwardReferenceSlot]){
        // the short frame runs at a lower gain than N: read noise scales with gain^2, shot noise with gain
        const float gRatio=float(b.iso[shortest])/float(std::max(1u,b.iso[forwardReferenceSlot]));
        NiceNoise sn{nn.slope*gRatio,nn.offset*gRatio*gRatio};
        add(b.raw[shortest],kRoleUltrashort,b.exposure[shortest],b.iso[shortest],sn);
    }
    return in;
}
} // namespace vivo_nice
