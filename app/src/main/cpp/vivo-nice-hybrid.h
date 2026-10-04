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
#include <thread>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif

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
    int bentoFrames=2;           // ultrashort frames merged inside the mask (the app exposes 1 or 2 at the same exposure): the second
                                 // one halves the x8..16 noise of the replacement and, with the hand shake between them, fills the
                                 // R/B lattice gaps of a single Bayer frame. Each gets bentoUsWeight / count (the mask transition stays).
    float bentoChromaSigma=2.f;  // Bento colour: inside the mask R and B are G x the R/G, B/G ratios of the ultrashort frames on an
                                 // isotropic kernel of this sigma (sensor px); 0 = off. With the 1 px kernel R and B take the nearest
                                 // R/B site (every second row/column): along a slanted highlight edge they step every 2 px against G,
                                 // an orange/blue dashed line (already in a plain demosaic of the RAW).
    float bentoChroma=4.f;       // strength: the colour replaces R/B by min(1, this x the ultrashort share of the pixel): across the smooth
                                 // mask edge the N frames take over, their clip-border colour steps as well (pair-2 sill: 32 % -> 7 % of
                                 // the Bento edge pixels with a colour step above 0.1; 1.0: 11 %)
    // Daylight kernel: where the round-4 kernel merges a daylight burst on the sensor grid (6.1 SNR key above s61MaxKey; a static
    // burst, the handheld one takes the 6.1 kernel), every sigma x this, ramped in over key s61MaxKey..2 s61MaxKey. 0.5 on the still
    // ship scene (1x): fine detail 0.77 -> 0.96 of ArkCam (water 0.69 -> 0.95) at +6 % sky noise (= ArkCam);
    // research/hybrid5/ark_sharpen_device.md. Not on the 2x grid: a static burst there has no sub-pixel diversity (not measured).
    float dayKernelScale=0.5f;
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
    // 1 = always, 2 = auto: at night (6.1 SNR key <= s61MaxKey) and, with the F6 local alignment on, in daylight on a handheld burst
    // (RMS shift of the donors at the frame centre >= s61MinMotion RAW px). Replays (f6_local_align.md): at night the 6.1 kernel
    // raises the split-half SNR in every band; in daylight with one homography per frame its narrow kernels doubled the frame-to-frame
    // differences (misregistration), with F6 it adds detail in every band (x1.7 above the 1x Nyquist on the handheld day burst);
    // on a static burst (no sub-pixel diversity) it only adds noise (x2-3), so the round-4 kernel stays there.
    int sabre61=2;
    float s61MaxKey=30.f;
    float s61MinMotion=2.f;
    float s61TensorNoise=1.f,s61GdNoise=1.f; // multipliers of the emulated 6.1 noise LUT (tensor, green difference)
    // ... in daylight (key > s61MaxKey) instead: the emulated LUT leaves flat areas on the narrow "structure" kernel at ISO 100-200
    // (smooth-area noise x2.3 against the round-4 kernel); 16 / 4 keep the detail (-1 %) at x1.45 (f6_local_align.md)
    float s61DayTensorNoise=16.f,s61DayGdNoise=4.f;
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
    // F6 local alignment (research/hybrid5/f6_local_align.md): a per-tile residual offset of every donor on top of its homography,
    // Lucas-Kanade on a two-level gray pyramid. 0 = off (output byte-identical to the merge without it), 1 = field interpolated
    // bilinearly between tile centres, 2 = constant per tile (NEAREST, as GCam 6.1 / 11).
    int localAlign=1;
    int laWin=16;                // L0 window, gray px (2 RAW px each): 16 = 32x32 RAW windows (6.1 tile, GCam 11 window)
    int laStride=8;              // L0 tile stride, gray px: 8 = 16x16 RAW output tiles (GCam 11 grid)
    int laIters=3,laItersCoarse=3; // LK iterations on L0 / L1
    float laMu=0.1f;             // Levenberg-Marquardt damping (share of the mean tensor eigenvalue)
    float laKappa=2.f;           // noise damping (x window pixels x gray noise variance)
    float laMaxShift=6.f;        // larger residuals (RAW px) are motion, not misregistration: the tile keeps the homography
    int laMedian=2;              // 3x3 median passes over the L0 field
    int laUltrashort=0;          // 1: refine the ultrashort (Bento) frame too (x8..16 gain: noisy, isotropic 1 px kernel)
    int laThreads=6;             // worker threads (one frame each; ~15 MB per thread)
    int isoKernel=0;             // diagnostics, round-4 kernel only (sabre61 off): 1 = isotropic sigma = base (no edge shaping), 2 = also no
                                 // flat widening: a "spatial RGB"-like isotropic Gaussian (LMC 9.6 spatial_rgb: sigma 0.28..0.40 px)
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
            ||set("bentoUsWeight",&t.bentoUsWeight)||set("bentoUsSigma",&t.bentoUsSigma)||set("bentoFrames",nullptr,&t.bentoFrames)
            ||set("bentoChromaSigma",&t.bentoChromaSigma)||set("bentoChroma",&t.bentoChroma)||set("dayKernelScale",&t.dayKernelScale)||set("shastaSharpness",&t.shastaSharpness)||set("shastaMaxRatio",&t.shastaMaxRatio)
            ||set("shastaEnable",nullptr,&t.shastaEnable)||set("snr",nullptr,&t.snrFixed)||set("snrScale",&t.snrScale)||set("debugFrame",nullptr,&t.debugFrame)
            ||set("cellClip",nullptr,&t.cellClip)||set("hotSigma",&t.hotSigma)||set("hotFrames",nullptr,&t.hotFrames)||set("hotBaseSigma",&t.hotBaseSigma)
            ||set("hotCross",&t.hotCross)||set("hotMaxLevel",&t.hotMaxLevel)||set("bentoLmc",nullptr,&t.bentoLmc)||set("bentoInvalid",&t.bentoInvalid)||set("bentoInpaintMiddle",&t.bentoInpaintMiddle)
            ||set("bentoInpaintMin",&t.bentoInpaintMin)||set("clipFlags",nullptr,&t.clipFlags)||set("sabre61",nullptr,&t.sabre61)
            ||set("s61TensorNoise",&t.s61TensorNoise)||set("s61GdNoise",&t.s61GdNoise)||set("s61MinMotion",&t.s61MinMotion)
            ||set("s61DayTensorNoise",&t.s61DayTensorNoise)||set("s61DayGdNoise",&t.s61DayGdNoise)||set("s61Mode",nullptr,&t.s61Mode)||set("s61MaxKey",&t.s61MaxKey)||set("hotMaxKey",&t.hotMaxKey)||set("subset",nullptr,&t.subset)||set("profile",nullptr,&t.profile)
            ||set("rimRatio",nullptr,&t.rimRatio)||set("rimSigma",&t.rimSigma)||set("rimLo",&t.rimLo)||set("rimHi",&t.rimHi)||set("rimStride",nullptr,&t.rimStride)
            ||set("localAlign",nullptr,&t.localAlign)||set("laWin",nullptr,&t.laWin)||set("laStride",nullptr,&t.laStride)||set("laIters",nullptr,&t.laIters)
            ||set("laItersCoarse",nullptr,&t.laItersCoarse)||set("laMu",&t.laMu)||set("laKappa",&t.laKappa)||set("laMaxShift",&t.laMaxShift)
            ||set("laMedian",nullptr,&t.laMedian)||set("laUltrashort",nullptr,&t.laUltrashort)||set("laThreads",nullptr,&t.laThreads)||set("isoKernel",nullptr,&t.isoKernel);
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
// F6 local alignment (vivo-nice-hybrid.h laFrameField): per-tile residual offset of every frame (RAW px) on top of its homography.
// Compiled only when the merge uses it (HybridGpu(.., localAlign)): without LOCAL_ALIGN the programs are the ones before F6. Every
// index stays inside the buffer for any uniform values: the compiler may execute the loads of a branch it does not take
// (if-conversion), and an out-of-bounds SSBO read faults the GPU (a phone reboot with an earlier draft).
#ifdef LOCAL_ALIGN
layout(std430,binding=15) readonly buffer LaFlow{vec2 laFlow[];}; // [frame][tile row][tile column]
uniform ivec4 laU;   // x: 1 bilinear between tile centres, 2 constant per tile (NEAREST); y, z = tiles per row / column (>= 1)
uniform vec4 laG;    // xy = RAW position of the centre of tile (0,0), z = tile stride (RAW px), w = 1 / stride
// Local offset of frame f at the cell whose origin is (x, y) (0 for the base).
vec2 laFlowAt(int f,int x,int y){
    int nx=max(laU.y,1),ny=max(laU.z,1);
    uint fb=uint(clamp(f,0,frameCount-1)*nx*ny);
    vec2 p=(vec2(float(x),float(y))+0.5-laG.xy)*laG.w;
    p=clamp(p,vec2(0.0),vec2(float(nx-1),float(ny-1)));
    ivec2 i0=clamp(ivec2(p),ivec2(0),ivec2(nx-1,ny-1)),i1=min(i0+1,ivec2(nx-1,ny-1));
    vec2 a=laU.x==2?vec2(greaterThanEqual(p-vec2(i0),vec2(0.5))):p-vec2(i0); // NEAREST: the closer centre
    vec2 v00=laFlow[fb+uint(i0.y*nx+i0.x)],v10=laFlow[fb+uint(i0.y*nx+i1.x)];
    vec2 v01=laFlow[fb+uint(i1.y*nx+i0.x)],v11=laFlow[fb+uint(i1.y*nx+i1.x)];
    vec2 d=mix(mix(v00,v10,a.x),mix(v01,v11,a.x),a.y);
    return f==0?vec2(0.0):d;
}
// origin() of kCommonShader plus the local offset (rejection: once per frame and cell). The merge and the rim pass read the
// offsets the dilation pass stored per cell (four loads and the interpolation inside the merge loop made it 20x slower on
// Adreno 750: the GPU hang detection reset the context).
vec2 originL(int f,int x,int y){return origin(f,x,y)+laFlowAt(f,x,y);}
#else
#define originL(f,x,y) origin(f,x,y)
#endif
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
        vec2 o=originL(f,2*cx,2*cy)*0.5;
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
#ifdef LOCAL_ALIGN
layout(std430,binding=16) writeonly buffer LaCell{uint laCell[];}; // F6 offset per donor and cell of the strip (half2)
#endif
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
#ifdef LOCAL_ALIGN
    laCell[(f-1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx]=packHalf2x16(laFlowAt(f,2*cx,2*cy)); // read by the merge and the rim pass
#endif
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
#ifdef LOCAL_ALIGN
layout(std430,binding=16) readonly buffer LaCell{uint laCell[];};
// origin() plus the F6 offset kHybDilate stored for donor f (>= 1) and the strip cell (x/2, y/2). Indices clamped: a load the
// compiler hoists out of a branch (f = 0 in the rim loop) stays inside the buffer.
vec2 originM(int f,int x,int y){
    int w2=size.x/2,f1=clamp(f,1,max(frameCount-1,1))-1,cx=clamp(x>>1,0,w2-1),cy=clamp((y>>1)-cy0,0,max(cy1-cy0-1,0));
    return origin(f,x,y)+unpackHalf2x16(laCell[uint(f1*(cy1-cy0)*w2+cy*w2+cx)]);
}
#else
#define originM(f,x,y) origin(f,x,y)
#endif
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
#ifdef BENTO_PASS
// Bento colour pass (kHybBento; kE.z = 1/sigma^2 of the wide kernel): the colour sums of an ultrashort frame on a wider isotropic kernel (lattice
// sites +-2 per axis and phase), clipped samples counted apart per colour. Inside the mask the ultrashort frames are the only ones:
// with their 1 px kernel R and B come from the nearest R/B site, which steps every 2 px along a slanted edge (orange/blue dashes).
void usWide(inout vec3 num,inout vec3 den,inout vec3 clipW,int f,vec2 O,float r,float P){
    float g=fParam[f].x;
    for(int p=0;p<4;p++){
        int px=p&1,py=p>>1,c=phaseColor[p];
        int bx=int(floor((O.x-float(px))*0.5))*2+px,by=int(floor((O.y-float(py))*0.5))*2+py;
        for(int dj=-2;dj<=4;dj+=2)for(int di=-2;di<=4;di+=2){
            int sx=bx+di,sy=by+dj;
            vec2 d=vec2(float(sx),float(sy))-O;
            float kw=r*exp2(-0.72135*dot(d,d)*P);
            uint fl=0u;
            float v=markU!=0?rawSite(f,sx,sy,fl):sampleRaw(f,sx,sy);
            if((fl&1u)!=0u)continue;
            if((fl&2u)!=0u||v>=clipLevel){clipW[c]+=kw;continue;}
            num[c]+=kw*v*g;den[c]+=kw;
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
            vec2 oc=originM(f,2*cx,2*cy);
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

// Bento colour pass (after the merge of a strip, before kHybRim; one invocation per output pixel, only pixels inside the Bento
// mask work). R and B of that share become the merged G x the R/G, B/G ratios of the ultrashort frames on a
// wide isotropic kernel (bentoChromaSigma): a neutral edge keeps its ratio across the edge, no step every 2 px. Only where green and
// that colour have no clipped ultrashort sample in the wide window (there kHybRim rebuilds the colour).
static const char* kHybBento=R"(
void main(){
    int g=kG.x,ow=kG.y,w2=size.x/2;
    int ox=int(gl_GlobalInvocationID.x),row=chunkU+int(gl_GlobalInvocationID.y);
    if(ox>=ow||row>=2*(cy1-cy0)*g)return;
    int x=ox/g,y=2*cy0+row/g,sx=ox-(ox/g)*g,sy=row-(row/g)*g;
    int cx=x>>1,cy=y>>1;
    float m=bmask[(cy-cy0)*w2+cx];
    if(m<=0.0)return;
    int i=row*ow+ox;
    uint cfl=mergeModeU.z!=0?cflags[i]:0u;
    vec2 pos=vec2(float(x),float(y))+(g==2?vec2(sx==0?-0.25:0.25,sy==0?-0.25:0.25):vec2(0.0));
    vec2 cell=vec2(float(2*cx),float(2*cy));
    // Share of the ultrashort frames in the merge of this pixel, from the frame weights of the cell (the merge's kernel sums are
    // not kept: one more accumulator in its frame loop made the whole merge ~6x slower on Adreno 750): inside the mask 1, across
    // its smooth edge the ~20 donors and the base take over.
    float wu=0.0,wo=(kE.x>=0.0||mergeModeU.y!=0)?0.0:1.0-m;
    vec3 uNum=vec3(0.0),uDen=vec3(0.0),uClip=vec3(0.0);
    for(int f=1;f<frameCount;f++){
        float r=robust[(f-1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx];
        if(r<0.004)continue;
        if(kE.x>=0.0&&int(kE.x)!=f)continue;
        if(int(fParam[f].w)!=5){wo+=r;continue;}
        wu+=r;
        usWide(uNum,uDen,uClip,f,originM(f,2*cx,2*cy)+(pos-cell),r,kE.z);
    }
    if(wu<=0.0||uDen.y<=0.0||uClip.y>0.0)return;
    float gw=uNum.y/uDen.y;
    if(gw<=1.0e-6)return;
    float s=min(kE.w*wu/(wu+wo),1.0);
    float G=outRgb[i*3+1];
    // a colour that fell back to the clipped mean (bits 0/2) is a lower bound for the highlight recovery: left alone
    if(uDen.x>0.0&&uClip.x<=0.0&&(cfl&1u)==0u)outRgb[i*3]=mix(outRgb[i*3],G*(uNum.x/uDen.x)/gw,s);
    if(uDen.z>0.0&&uClip.z<=0.0&&(cfl&4u)==0u)outRgb[i*3+2]=mix(outRgb[i*3+2],G*(uNum.z/uDen.z)/gw,s);
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
            O=originM(f,2*cx,2*cy)+(pos-cell);
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
            O=originM(f,2*cx,2*cy)+(pos-cell);
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
    static constexpr int kSlots=18; // 0..16 merge (1 fixed-pattern mean, 2 site flags, 6 clip flags, 14 donor covariance, 15 F6 field,
                                    // 16 F6 offset per strip cell), 17 readback staging
    GLuint meanProgram=0,flagsProgram=0,markProgram=0,guideProgram=0,cellsProgram=0,rejectProgram=0,dilateProgram=0,mergeProgram=0,rimProgram=0,bentoProgram=0,buffers[kSlots]{};
    size_t capacity[kSlots]{};
    void check(const char* where){GLenum e=glGetError();if(e!=GL_NO_ERROR)throw std::runtime_error(std::string("HYBRID GPU ")+where+" GL error="+std::to_string(e));}
    void cleanup() noexcept {
        if(display==EGL_NO_DISPLAY)return;
        if(context!=EGL_NO_CONTEXT&&eglMakeCurrent(display,surface,surface,context)){
            for(GLuint program:{meanProgram,flagsProgram,markProgram,guideProgram,cellsProgram,rejectProgram,dilateProgram,mergeProgram,rimProgram,bentoProgram})if(program)glDeleteProgram(program);
            glDeleteBuffers(kSlots,buffers);
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        }
        if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);
        if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
        eglTerminate(display);display=EGL_NO_DISPLAY;
    }
    const char* helpersPrefix=""; // "#define LOCAL_ALIGN 1\n" when the F6 field is used
    GLuint compile(const char* body,bool standalone=false){
        GLuint shader=glCreateShader(GL_COMPUTE_SHADER);const char* sources[]={kCommonShader,helpersPrefix,kHybHelpers,body};
        if(standalone)glShaderSource(shader,1,&body,nullptr); else glShaderSource(shader,4,sources,nullptr);
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
        if(profile){
            glFinish();const double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();passMs[pass]+=ms;
            if(profilePasses&&trace){
                // glGetError clears the flag: an error read here is thrown as check() would have done after the pass
                const GLenum err=glGetError();
                char l[96];std::snprintf(l,sizeof(l),"HYBRID GPU pass %d rows=%d z=%d ms=%.1f err=%d",pass,rows,gz,ms,int(err));trace(l);
                if(err!=GL_NO_ERROR)throw std::runtime_error("HYBRID GPU pass "+std::to_string(pass)+" GL error="+std::to_string(err));
            }
        }
    }
public:
    bool profile=false;            // glFinish after every pass and add its time to passMs
    bool profilePasses=false;      // with profile: one report line per pass (profile 2; finds a pass that hangs)
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
        // F6 local alignment: per merge frame (0 = base, zeros) ny x nx tiles of (dx, dy) RAW px; laMode 0 = off
        const std::vector<float>* laField=nullptr;
        int laMode=0,laNx=0,laNy=0;
        float laOx=0,laOy=0,laStride=16;
        std::vector<float> laMaxY;                      // per frame: largest |dy| of the field (rows uploaded beyond the homography)
    };
    long fixedOutliers=0,baseOutliers=0;                // sites flagged by the last merge
    long rimPixels=0;                                   // output pixels whose R/B the clip-border ratios rebuilt
    // rimPass: compile the clip-border colour pass (kHybRim, ~0.14 s on Adreno 750); without it merge() runs as before the pass.
    // localAlign: compile the F6 local offsets into the reject / merge / rim programs (Frames::laMode needs it).
    const bool localAlign=false;
    // bentoPass: compile the Bento colour pass (kHybBento) for a shot with Bento and bentoChromaSigma > 0.
    explicit HybridGpu(bool rimPass=true,bool withLocalAlign=false,bool bentoPass=false):localAlign(withLocalAlign){
        if(localAlign)helpersPrefix="#define LOCAL_ALIGN 1\n";
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
            if(bentoPass){const std::string body=std::string("#define RIM_STATS 1\n#define BENTO_PASS 1\n")+kHybMergeCommon+kHybBento;bentoProgram=timed("bento",body.c_str());}
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
        if(bentoProgram)programs.push_back(bentoProgram);
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
            if(localAlign){
                glUniform4i(loc(program,"laU"),in.laMode,in.laNx,in.laNy,0);
                glUniform4f(loc(program,"laG"),in.laOx,in.laOy,in.laStride,1.f/std::max(in.laStride,1e-3f));
            }
        }
        // F6 field: the whole burst once (frames x tiles x 8 B, ~12 MB for 32 frames of 12 MP). The programs compiled with it read
        // binding 15 for every donor: they need the field (zeros = the homography).
        const bool la=localAlign;
        if(la){
            if(in.laMode==0||!in.laField||in.laNx<1||in.laNy<1||in.laField->size()!=size_t(frames)*in.laNx*in.laNy*2)
                throw std::runtime_error("HYBRID GPU local alignment field");
            reserve(15,in.laField->size()*4);put(15,0,in.laField->data(),in.laField->size()*4);
        } else if(in.laMode!=0)throw std::runtime_error("HYBRID GPU compiled without local alignment");
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
        {const float us=std::max(0.3f,tune.bentoUsSigma),cs=tune.bentoChromaSigma;
         // The merge program stays exactly as it was: on Adreno 750 even one more term in its clip-flag condition made the whole
         // merge ~15x slower (and changed its rounding). The Bento colour pass gets its own uniforms.
         glUniform4f(loc(mergeProgram,"kE"),float(tune.debugFrame),1.f/(us*us),0,0);
         if(bento&&bentoProgram!=0&&cs>0.f&&tune.bentoChroma>0.f){
             glUseProgram(bentoProgram);
             glUniform4f(loc(bentoProgram,"kE"),float(tune.debugFrame),1.f/(us*us),1.f/(cs*cs),std::clamp(tune.bentoChroma,0.f,16.f));
             glUniform4i(loc(bentoProgram,"kG"),g,ow,0,0);
             // y: no base; z: the merge wrote the clip flags (clip flags or the clip-border pass), else their bits are not read
             glUniform4i(loc(bentoProgram,"mergeModeU"),in.mergeMode,in.noBase?1:0,(clipFlags||(tune.rimRatio!=0&&rimProgram!=0))?1:0,0);
             glUseProgram(mergeProgram);
         }}
        glUniform4i(loc(mergeProgram,"kG"),g,ow,0,0);
        glUniform4i(loc(mergeProgram,"mergeModeU"),in.mergeMode,in.noBase?1:0,clipFlags?1:0,0);
        // Clip-border colour pass (kHybRim): the merge only marks its candidates in the clip flags (the ratio loops inside the merge
        // program made the whole merge ~18x slower on Adreno 750 even with the switch off, three more accumulators ~12 %).
        const bool rim=tune.rimRatio!=0&&rimProgram!=0;
        const bool bentoColourPass=bento&&bentoProgram!=0&&tune.bentoChromaSigma>0.f&&tune.bentoChroma>0.f;
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
                // F6: the local offsets move the samples beyond the homography's rows by up to the field's largest |dy|
                const int extra=la&&f<int(in.laMaxY.size())?int(std::ceil(in.laMaxY[f]))+1:0;
                int r0=std::max(0,int(std::floor(lo))-8-extra)&~1,r1=std::min(h,(int(std::ceil(hi))+9+extra)&~1);
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
            if(la)reserve(16,size_t(donors)*(cy1-cy0)*w2*4);
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
            reserve(6,(clipFlags||rim||bentoColourPass)?size_t(rows2)*ow*4:16);
            glUseProgram(mergeProgram);
            glUniform1i(loc(mergeProgram,"cy0"),cy0);glUniform1i(loc(mergeProgram,"cy1"),cy1);glUniform1i(loc(mergeProgram,"ry0"),ry0);
            for(int sub=0;sub<g*g;++sub){ // grid 2: one dispatch per sub-position (same registers as 1x; four passes)
                glUniform4i(loc(mergeProgram,"kG"),g,ow,sub&1,sub>>1);
                dispatchRows(mergeProgram,w2,cy1-cy0,1,64,5);
            }
            if(bentoColourPass){ // after all sub-positions, before the clip-border pass (that one reads the merged colour)
                glUseProgram(bentoProgram);
                glUniform1i(loc(bentoProgram,"cy0"),cy0);glUniform1i(loc(bentoProgram,"cy1"),cy1);glUniform1i(loc(bentoProgram,"ry0"),ry0);
                dispatchRows(bentoProgram,ow,rows2,1,64,5);
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

// ---------------------------------------------------------------------------------------------
// F6: tile-local refinement of the per-frame homography (research/hybrid5/f6_local_align.md).
// One homography per frame (CRE, 8-bit 1/4 guide) leaves 0.3-0.9 px RMS of local misregistration on handheld bursts (parallax of
// near objects, rolling shutter, lens): narrow Sabre kernels then merge frames that disagree. Per tile, the residual offset of
// every donor against the base is estimated on a two-level gray pyramid (GCam 11 spatial path: Lucas-Kanade, 16x16 RAW output
// tiles from 32x32 RAW windows, median filtering; GCam 6.1: 32x32 RAW tiles) and added to the homography in every GPU pass.
//   gray     2x2 quad mean (canonical cell), gain-normalised to base units, sqrt domain (noise variance ~ slope/16 everywhere);
//            sites >= 0.95 of white in the frame's own units mark the quad clipped (excluded from the sums)
//   L1       gray/2, windows 16 px (64 RAW) every 8 px (32 RAW), LK from the homography
//   L0       gray, windows laWin px (2 laWin RAW) every laStride px; start = the better (window SSD) of the homography and the L1
//            field; LK; the result stands only where it lowers the SSD of the homography and |r| <= laMaxShift, else the tile
//            keeps the homography (flat, clipped, occluded or moving tiles); then laMedian 3x3 medians
//   LK step  (A + lambda I) d = sum grad(B) (B - D(x + t)) on mean-normalised windows, lambda = laMu tr(A)/2 + laKappa n v:
//            Levenberg-Marquardt damping keeps the along-edge component of an edge tile (aperture problem) from drifting,
//            the noise term keeps flat noisy tiles at the start; steps clamped to 1 level px
// The window is a pure translation (the homography's Jacobian differs from 1 by < 0.5 %: < 0.06 px at the window edge, zero mean).
struct LaImage {
    int w=0,h=0;
    std::vector<float> v;       // sqrt-domain gray; < 0 = no sample (clipped quad; base: also next to one, its gradient is not real)
    std::vector<float> gx,gy;   // central differences (base only)
};
// Rows of a level image, in parallel (the base, once) or not (a frame inside the per-frame worker pool).
template<class F> inline void laRows(bool parallel,int h,const F& body){if(parallel)mergeRowBands(h,body); else body(0,h);}
// Gray of a frame: canonical quad (i, j) = sensor sites (2i + cfa&1 .. +1, 2j + cfa>>1 .. +1), normalised by black / white of each
// phase and clamped to [0, 1] like Burst::sampleRaw (which handles the reflected border quads); -1 where a site >= 0.95 of white.
inline void laGray(const Burst& b,const uint16_t* raw,float gain,float eps,LaImage& out,bool parallel){
    out.w=b.w/2;out.h=b.h/2;out.v.resize(size_t(out.w)*out.h);
    const int sx=b.cfa&1,sy=b.cfa>>1;
    float inv[4];for(int p=0;p<4;++p)inv[p]=1.f/(b.white-b.black[p]);
    laRows(parallel,out.h,[&](int y0,int y1){
        for(int j=y0;j<y1;++j){
            const int Y=2*j+sy;
            const bool rowsIn=Y+1<b.h;
            const uint16_t* r0=raw+size_t(std::min(Y,b.h-1))*b.w;const uint16_t* r1=rowsIn?r0+b.w:r0;
            const int p0=(Y&1)<<1,p1=((Y+1)&1)<<1;
            float* o=out.v.data()+size_t(j)*out.w;
            int i=0;
#if defined(__aarch64__)
            if(rowsIn){ // four quads per step, the same arithmetic in the same order as the scalar loop (identical floats)
                const int q0=sx&1,q1=(sx+1)&1;
                const float32x4_t b00=vdupq_n_f32(b.black[p0|q0]),b01=vdupq_n_f32(b.black[p0|q1]),b10=vdupq_n_f32(b.black[p1|q0]),b11=vdupq_n_f32(b.black[p1|q1]);
                const float32x4_t i00=vdupq_n_f32(inv[p0|q0]),i01=vdupq_n_f32(inv[p0|q1]),i10=vdupq_n_f32(inv[p1|q0]),i11=vdupq_n_f32(inv[p1|q1]);
                const float32x4_t zero=vdupq_n_f32(0.f),one=vdupq_n_f32(1.f),quarter=vdupq_n_f32(0.25f),gv=vdupq_n_f32(gain),ev=vdupq_n_f32(eps);
                const float32x4_t clipLv=vdupq_n_f32(0.95f),none=vdupq_n_f32(-1.f);
                auto site=[&](uint16x4_t v,float32x4_t bl,float32x4_t iv){
                    return vminq_f32(vmaxq_f32(vmulq_f32(vsubq_f32(vcvtq_f32_u32(vmovl_u16(v)),bl),iv),zero),one);
                };
                for(;i+4<=out.w&&2*i+sx+7<b.w;i+=4){
                    const int X=2*i+sx;
                    const uint16x4x2_t a=vld2_u16(r0+X),c=vld2_u16(r1+X);
                    const float32x4_t u0=site(a.val[0],b00,i00),u1=site(a.val[1],b01,i01),u2=site(c.val[0],b10,i10),u3=site(c.val[1],b11,i11);
                    const float32x4_t sum=vaddq_f32(vaddq_f32(vaddq_f32(u0,u1),u2),u3),mx=vmaxq_f32(vmaxq_f32(u0,u1),vmaxq_f32(u2,u3));
                    const float32x4_t val=vsqrtq_f32(vaddq_f32(vmaxq_f32(vmulq_f32(vmulq_f32(quarter,sum),gv),zero),ev));
                    vst1q_f32(o+i,vbslq_f32(vcgeq_f32(mx,clipLv),none,val));
                }
            }
#endif
            for(;i<out.w;++i){
                const int X=2*i+sx;
                float s=0,m=0;
                if(rowsIn&&X+1<b.w){
                    const int q0=X&1,q1=(X+1)&1;
                    const float v[4]={(float(r0[X])-b.black[p0|q0])*inv[p0|q0],(float(r0[X+1])-b.black[p0|q1])*inv[p0|q1],
                                      (float(r1[X])-b.black[p1|q0])*inv[p1|q0],(float(r1[X+1])-b.black[p1|q1])*inv[p1|q1]};
                    for(float u:v){u=std::clamp(u,0.f,1.f);s+=u;m=std::max(m,u);}
                } else for(int p=0;p<4;++p){const float u=b.sampleRaw(raw,2*i+(p&1),2*j+(p>>1));s+=u;m=std::max(m,u);}
                o[i]=m>=0.95f?-1.f:std::sqrt(std::max(0.25f*s*gain,0.f)+eps);
            }
        }
    });
}
inline void laDown(const LaImage& in,LaImage& out,bool parallel){
    out.w=in.w/2;out.h=in.h/2;out.v.resize(size_t(out.w)*out.h);
    laRows(parallel,out.h,[&](int y0,int y1){
        for(int j=y0;j<y1;++j)for(int i=0;i<out.w;++i){
            const size_t a=size_t(2*j)*in.w+2*i,b=a+in.w;
            const float p=in.v[a],q=in.v[a+1],r=in.v[b],s=in.v[b+1];
            out.v[size_t(j)*out.w+i]=std::min(std::min(p,q),std::min(r,s))<0.f?-1.f:0.25f*(p+q+r+s);
        }
    });
}
// Base level: central differences, then every pixel whose difference stencil touches a clipped quad loses its sample.
inline void laBaseLevel(LaImage& im,bool parallel){
    im.gx.assign(im.v.size(),0.f);im.gy.assign(im.v.size(),0.f);
    std::vector<float> masked(im.v.size());
    laRows(parallel,im.h,[&](int y0,int y1){
        for(int j=y0;j<y1;++j)for(int i=0;i<im.w;++i){
            const size_t k=size_t(j)*im.w+i;
            const int il=std::max(i-1,0),ir=std::min(i+1,im.w-1),ju=std::max(j-1,0),jd=std::min(j+1,im.h-1);
            const float l=im.v[size_t(j)*im.w+il],r=im.v[size_t(j)*im.w+ir],u=im.v[size_t(ju)*im.w+i],d=im.v[size_t(jd)*im.w+i];
            im.gx[k]=(r-l)/float(std::max(ir-il,1));im.gy[k]=(d-u)/float(std::max(jd-ju,1));
            masked[k]=std::min(std::min(std::min(l,r),std::min(u,d)),im.v[k])<0.f?-1.f:im.v[k];
        }
    });
    im.v.swap(masked);
}
// One window: donor samples at translation (tx, ty) level px (bilinear, constant weights), residual e = (B - mean B) - (D - mean D)
// over the pixels with a base sample and four donor samples (m = 1): its energy (SSD per pixel) and the LK system, from one pass of
// sums (values taken relative to the base at the window centre, so the differences of moments keep their precision in float):
//   n SSD = sum m (b - d)^2 - n (mb - md)^2,  bx = sum m gx b - sum m gx d - (mb - md) sum m gx  (by alike),  A = sum m g g'.
struct LaTile { float n=0,ssd=0,axx=0,ayy=0,axy=0,bx=0,by=0; };
constexpr int kLaMaxWin=32;
struct LaSums { float n=0,sb=0,sd=0,sbb=0,sdd=0,sbd=0,gx=0,gy=0,gxb=0,gyb=0,gxd=0,gyd=0,axx=0,ayy=0,axy=0; };
inline LaTile laTilePass(const LaImage& B,const LaImage& D,int ox,int oy,int win,float tx,float ty,bool system){
    const float fx0=std::floor(tx),fy0=std::floor(ty);
    const int ix=int(fx0),iy=int(fy0);
    const float fx=tx-fx0,fy=ty-fy0,w00=(1-fx)*(1-fy),w10=fx*(1-fy),w01=(1-fx)*fy,w11=fx*fy;
    const float c=std::max(B.v[size_t(oy+win/2)*B.w+ox+win/2],0.f);
    LaSums S;
    const bool inside=ox+ix>=0&&ox+ix+win<D.w&&oy+iy>=0&&oy+iy+win<D.h;
    bool done=false;
#if defined(__aarch64__)
    if(inside&&(win&3)==0){
        const float32x4_t zero=vdupq_n_f32(0);
        float32x4_t n=zero,sb=zero,sd=zero,sbb=zero,sdd=zero,sbd=zero,gx=zero,gy=zero,gxb=zero,gyb=zero,gxd=zero,gyd=zero,axx=zero,ayy=zero,axy=zero;
        const uint32x4_t one=vreinterpretq_u32_f32(vdupq_n_f32(1.f));
        const float32x4_t cc=vdupq_n_f32(c),v00=vdupq_n_f32(w00),v10=vdupq_n_f32(w10),v01=vdupq_n_f32(w01),v11=vdupq_n_f32(w11);
        for(int wy=0;wy<win;++wy){
            const size_t kb=size_t(oy+wy)*B.w+ox,k0=size_t(oy+wy+iy)*D.w+size_t(ox+ix),k1=k0+D.w;
            const float *br=B.v.data()+kb,*d0=D.v.data()+k0,*d1=D.v.data()+k1,*g1r=B.gx.data()+kb,*g2r=B.gy.data()+kb;
            for(int x=0;x<win;x+=4){
                const float32x4_t bv=vld1q_f32(br+x),a00=vld1q_f32(d0+x),a10=vld1q_f32(d0+x+1),a01=vld1q_f32(d1+x),a11=vld1q_f32(d1+x+1);
                const float32x4_t mn=vminq_f32(vminq_f32(bv,vminq_f32(a00,a10)),vminq_f32(a01,a11));
                const float32x4_t m=vreinterpretq_f32_u32(vandq_u32(vcgeq_f32(mn,zero),one));
                const float32x4_t b=vsubq_f32(bv,cc);
                float32x4_t d=vmulq_f32(v00,a00);d=vfmaq_f32(d,v10,a10);d=vfmaq_f32(d,v01,a01);d=vfmaq_f32(d,v11,a11);d=vsubq_f32(d,cc);
                const float32x4_t mb=vmulq_f32(m,b),md=vmulq_f32(m,d);
                n=vaddq_f32(n,m);sb=vaddq_f32(sb,mb);sd=vaddq_f32(sd,md);
                sbb=vfmaq_f32(sbb,mb,b);sdd=vfmaq_f32(sdd,md,d);sbd=vfmaq_f32(sbd,mb,d);
                if(system){
                    const float32x4_t g1=vld1q_f32(g1r+x),g2=vld1q_f32(g2r+x),mg1=vmulq_f32(m,g1),mg2=vmulq_f32(m,g2);
                    gx=vaddq_f32(gx,mg1);gy=vaddq_f32(gy,mg2);
                    gxb=vfmaq_f32(gxb,mg1,b);gyb=vfmaq_f32(gyb,mg2,b);gxd=vfmaq_f32(gxd,mg1,d);gyd=vfmaq_f32(gyd,mg2,d);
                    axx=vfmaq_f32(axx,mg1,g1);ayy=vfmaq_f32(ayy,mg2,g2);axy=vfmaq_f32(axy,mg1,g2);
                }
            }
        }
        S.n=vaddvq_f32(n);S.sb=vaddvq_f32(sb);S.sd=vaddvq_f32(sd);S.sbb=vaddvq_f32(sbb);S.sdd=vaddvq_f32(sdd);S.sbd=vaddvq_f32(sbd);
        S.gx=vaddvq_f32(gx);S.gy=vaddvq_f32(gy);S.gxb=vaddvq_f32(gxb);S.gyb=vaddvq_f32(gyb);S.gxd=vaddvq_f32(gxd);S.gyd=vaddvq_f32(gyd);
        S.axx=vaddvq_f32(axx);S.ayy=vaddvq_f32(ayy);S.axy=vaddvq_f32(axy);
        done=true;
    }
#endif
    if(!done)for(int wy=0;wy<win;++wy){ // scalar: borders (clamped donor reads) and other architectures
        const int Y=oy+wy;
        const size_t kb=size_t(Y)*B.w+ox;
        const int y0=std::clamp(Y+iy,0,D.h-1),y1=std::clamp(Y+iy+1,0,D.h-1);
        const size_t a=size_t(y0)*D.w,e=size_t(y1)*D.w;
        for(int wx=0;wx<win;++wx){
            const int x0=std::clamp(ox+wx+ix,0,D.w-1),x1=std::clamp(ox+wx+ix+1,0,D.w-1);
            const float bv=B.v[kb+wx],a00=D.v[a+x0],a10=D.v[a+x1],a01=D.v[e+x0],a11=D.v[e+x1];
            const float m=std::min(std::min(bv,std::min(a00,a10)),std::min(a01,a11))>=0.f?1.f:0.f;
            const float b=bv-c,d=w00*a00+w10*a10+w01*a01+w11*a11-c;
            S.n+=m;S.sb+=m*b;S.sd+=m*d;S.sbb+=m*b*b;S.sdd+=m*d*d;S.sbd+=m*b*d;
            if(system){
                const float g1=B.gx[kb+wx],g2=B.gy[kb+wx];
                S.gx+=m*g1;S.gy+=m*g2;S.gxb+=m*g1*b;S.gyb+=m*g2*b;S.gxd+=m*g1*d;S.gyd+=m*g2*d;S.axx+=m*g1*g1;S.ayy+=m*g2*g2;S.axy+=m*g1*g2;
            }
        }
    }
    LaTile t;t.n=S.n;
    if(S.n<1)return t;
    const float mb=S.sb/S.n,md=S.sd/S.n,dm=mb-md;
    t.ssd=std::max((S.sbb-2.f*S.sbd+S.sdd)/S.n-dm*dm,0.f);
    t.axx=S.axx;t.ayy=S.ayy;t.axy=S.axy;
    t.bx=S.gxb-S.gxd-dm*S.gx;t.by=S.gyb-S.gyd-dm*S.gy;
    return t;
}
struct LaGrid {
    int nx=0,ny=0,win=16,stride=8,s=2; // tiles, window and stride (level px), RAW px per level px
    float centre(int i) const {return float(stride*i)+0.5f*float(win-1);}  // level px
    float raw(float c) const {return float(s)*c+0.5f*float(s-1);}         // level px -> RAW px
};
inline LaGrid laGrid(const LaImage& im,int win,int stride,int s){
    LaGrid g;g.win=win;g.stride=stride;g.s=s;g.nx=std::max(1,(im.w-win)/stride+1);g.ny=std::max(1,(im.h-win)/stride+1);return g;
}
// Translation of the homography at the centre of tile (i, j), level px.
inline void laT0(const LaGrid& g,const BackwardHomography& H,int i,int j,float& tx,float& ty){
    const float cx=g.centre(i),cy=g.centre(j);
    const float X=g.raw(cx),Y=g.raw(cy);
    const float den=H.h[6]*X+H.h[7]*Y+1.f;
    const float hx=(H.h[0]*X+H.h[1]*Y+H.h[2])/den/H.upRatio,hy=(H.h[3]*X+H.h[4]*Y+H.h[5])/den/H.upRatio;
    tx=(hx-0.5f*float(g.s-1))/float(g.s)-cx;ty=(hy-0.5f*float(g.s-1))/float(g.s)-cy;
}
// Lucas-Kanade iterations on every tile of a level: r (RAW px, ny*nx*2) in/out.
inline void laLK(const LaImage& B,const LaImage& D,const LaGrid& g,const BackwardHomography& H,float v,float mu,float kappa,int iters,
                 std::vector<float>& r){
    const float minN=0.5f*float(g.win*g.win);
    for(int j=0;j<g.ny;++j)for(int i=0;i<g.nx;++i){
        float tx0,ty0;laT0(g,H,i,j,tx0,ty0);
        float* rr=r.data()+(size_t(j)*g.nx+i)*2;
        for(int it=0;it<iters;++it){
            const LaTile t=laTilePass(B,D,g.stride*i,g.stride*j,g.win,tx0+rr[0]/float(g.s),ty0+rr[1]/float(g.s),true);
            if(t.n<minN)break;
            const float lam=mu*0.5f*(t.axx+t.ayy)+kappa*t.n*v;
            const float a=t.axx+lam,c=t.ayy+lam,b=t.axy,det=a*c-b*b;
            if(!(det>1e-30f))break;
            const float dx=std::clamp((c*t.bx-b*t.by)/det,-1.f,1.f),dy=std::clamp((a*t.by-b*t.bx)/det,-1.f,1.f);
            rr[0]+=dx*float(g.s);rr[1]+=dy*float(g.s);
        }
    }
}
// 3x3 median of each component (borders replicated), 19-comparator network.
inline void laMedian3(std::vector<float>& r,int nx,int ny){
    const std::vector<float> src=r;
    auto cx=[](float& a,float& b){const float lo=std::min(a,b);b=std::max(a,b);a=lo;};
    for(int j=0;j<ny;++j)for(int i=0;i<nx;++i)for(int c=0;c<2;++c){
        float p[9];int k=0;
        for(int dj=-1;dj<=1;++dj)for(int di=-1;di<=1;++di)
            p[k++]=src[(size_t(std::clamp(j+dj,0,ny-1))*nx+std::clamp(i+di,0,nx-1))*2+c];
        cx(p[1],p[2]);cx(p[4],p[5]);cx(p[7],p[8]);cx(p[0],p[1]);cx(p[3],p[4]);cx(p[6],p[7]);cx(p[1],p[2]);cx(p[4],p[5]);cx(p[7],p[8]);
        cx(p[0],p[3]);cx(p[5],p[8]);cx(p[4],p[7]);cx(p[3],p[6]);cx(p[1],p[4]);cx(p[2],p[5]);cx(p[4],p[7]);cx(p[4],p[2]);cx(p[6],p[4]);cx(p[4],p[2]);
        r[(size_t(j)*nx+i)*2+c]=p[4];
    }
}
// Bilinear sample of a level-L field (RAW px) at RAW position (X, Y).
inline void laFieldAt(const std::vector<float>& r,const LaGrid& g,float X,float Y,float& fx,float& fy){
    const float u=std::clamp(((X-0.5f*float(g.s-1))/float(g.s)-0.5f*float(g.win-1))/float(g.stride),0.f,float(g.nx-1));
    const float w=std::clamp(((Y-0.5f*float(g.s-1))/float(g.s)-0.5f*float(g.win-1))/float(g.stride),0.f,float(g.ny-1));
    const int i0=std::min(int(u),g.nx-1),j0=std::min(int(w),g.ny-1),i1=std::min(i0+1,g.nx-1),j1=std::min(j0+1,g.ny-1);
    const float a=u-float(i0),b=w-float(j0);
    for(int c=0;c<2;++c){
        const float v=(r[(size_t(j0)*g.nx+i0)*2+c]*(1-a)+r[(size_t(j0)*g.nx+i1)*2+c]*a)*(1-b)
                     +(r[(size_t(j1)*g.nx+i0)*2+c]*(1-a)+r[(size_t(j1)*g.nx+i1)*2+c]*a)*b;
        (c?fy:fx)=v;
    }
}
struct LaFrameStats { float median=0,p90=0,maxAbsY=0,accepted=0,fromCoarse=0; double ms[7]{}; }; // ms: gray, down, L1, start, L0, accept, median
// Base pyramid (built once) and the per-frame field on the L0 grid. One worker thread per frame (no nested parallelism).
struct LaBase { LaImage l0,l1; LaGrid g0,g1; float v0=0; };
inline void laFrameField(const LaBase& base,const LaImage& D0,const BackwardHomography& H,const HybridTuning& t,
                         std::vector<float>& field,LaFrameStats& st){
    using LaClock=std::chrono::steady_clock;
    auto tick=LaClock::now();
    auto lap=[&](int k){const auto now=LaClock::now();st.ms[k]+=std::chrono::duration<double,std::milli>(now-tick).count();tick=now;};
    LaImage D1;laDown(D0,D1,false);lap(1);
    const LaGrid& g0=base.g0;const LaGrid& g1=base.g1;
    std::vector<float> r1(size_t(g1.nx)*g1.ny*2,0.f);
    laLK(base.l1,D1,g1,H,base.v0*0.25f,t.laMu,t.laKappa,std::max(0,t.laItersCoarse),r1);
    laMedian3(r1,g1.nx,g1.ny);lap(2);
    field.assign(size_t(g0.nx)*g0.ny*2,0.f);
    std::vector<float> s0(size_t(g0.nx)*g0.ny,0.f);
    std::vector<uint8_t> ok(s0.size(),0),coarse(s0.size(),0);
    const float minN=0.5f*float(g0.win*g0.win);
    // start: the better of the homography and the L1 field (window SSD)
    for(int j=0;j<g0.ny;++j)for(int i=0;i<g0.nx;++i){
        const size_t k=size_t(j)*g0.nx+i;
        float tx0,ty0;laT0(g0,H,i,j,tx0,ty0);
        const LaTile a=laTilePass(base.l0,D0,g0.stride*i,g0.stride*j,g0.win,tx0,ty0,false);
        s0[k]=a.n>=minN?a.ssd:-1.f;
        float ux,uy;laFieldAt(r1,g1,g0.raw(g0.centre(i)),g0.raw(g0.centre(j)),ux,uy);
        if(a.n>=minN&&(ux!=0.f||uy!=0.f)){
            const LaTile u=laTilePass(base.l0,D0,g0.stride*i,g0.stride*j,g0.win,tx0+ux/float(g0.s),ty0+uy/float(g0.s),false);
            if(u.n>=minN&&u.ssd<a.ssd){field[k*2]=ux;field[k*2+1]=uy;coarse[k]=1;}
        }
    }
    lap(3);
    laLK(base.l0,D0,g0,H,base.v0,t.laMu,t.laKappa,std::max(0,t.laIters),field);lap(4);
    // keep the refinement only where it lowers the SSD of the homography
    for(int j=0;j<g0.ny;++j)for(int i=0;i<g0.nx;++i){
        const size_t k=size_t(j)*g0.nx+i;
        float* rr=field.data()+k*2;
        bool keep=s0[k]>=0.f&&std::isfinite(rr[0])&&std::isfinite(rr[1])&&std::hypot(rr[0],rr[1])<=t.laMaxShift;
        if(keep&&(rr[0]!=0.f||rr[1]!=0.f)){
            float tx0,ty0;laT0(g0,H,i,j,tx0,ty0);
            const LaTile f=laTilePass(base.l0,D0,g0.stride*i,g0.stride*j,g0.win,tx0+rr[0]/float(g0.s),ty0+rr[1]/float(g0.s),false);
            keep=f.n>=minN&&f.ssd<s0[k];
        }
        if(!keep){rr[0]=0;rr[1]=0;} else ok[k]=1;
    }
    lap(5);
    for(int m=0;m<std::max(0,t.laMedian);++m)laMedian3(field,g0.nx,g0.ny);
    lap(6);
    std::vector<float> mag(s0.size());float maxY=0;long acc=0,fromCoarse=0;
    for(size_t k=0;k<mag.size();++k){mag[k]=std::hypot(field[k*2],field[k*2+1]);maxY=std::max(maxY,std::abs(field[k*2+1]));acc+=ok[k];fromCoarse+=coarse[k];}
    const size_t mid=mag.size()/2,p90=std::min(mag.size()-1,mag.size()*9/10);
    std::nth_element(mag.begin(),mag.begin()+mid,mag.end());st.median=mag[mid];
    std::nth_element(mag.begin(),mag.begin()+p90,mag.end());st.p90=mag[p90];
    st.maxAbsY=maxY;st.accepted=float(acc)/float(std::max<size_t>(1,mag.size()));st.fromCoarse=float(fromCoarse)/float(std::max<size_t>(1,mag.size()));
}

struct HybridStats { double alignMs=0,maskMs=0,mergeMs=0,localAlignMs=0; int merged=0,droppedBracketed=0; bool bento=false; };

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
    if(tune.isoKernel){kernel.shrunk=kernel.stretched=kernel.base;if(tune.isoKernel>=2)kernel.flat=kernel.base;}
    // Sabre 6.1 kernel (sabre2x_61.md F1): key = 0.18/sqrt(O + 0.18 S) of the base frame (no snrScale), GCam 6.1 curves f0..f3
    // (research/gcam61/sabre_curves.json), f4 = 4, f5 = 2.2; uniforms exactly as libgcam 0x723f04-0x724008.
    std::array<float,4> k61a{},k61b{},k61c{};
    const float key61=tune.snrFixed>0?float(tune.snrFixed):sabreSnr(baseSlope,baseOffset,1.f);
    // Hand motion of the burst: RMS shift of the aligned normal donors at the frame centre (sub-pixel diversity for the 6.1 kernel).
    double motion=0;
    {
        int cnt=0;
        for(int f=1;f<n;++f){
            if(input.frames[f].role!=kRoleNormal||!aligned[f])continue;
            const DonorPoint p=H[f].project(w/2,h/2);
            const double dx=p.x/H[f].upRatio-w/2,dy=p.y/H[f].upRatio-h/2;
            motion+=dx*dx+dy*dy;++cnt;
        }
        motion=cnt?std::sqrt(motion/cnt):0.0;
    }
    const bool night61=key61<=tune.s61MaxKey,handheld61=tune.localAlign>0&&motion>=tune.s61MinMotion;
    const bool sabre61=tune.sabre61==1||(tune.sabre61==2&&(night61||handheld61));
    const int gridOut=tune.grid>0?std::min(tune.grid,2):std::max(1,std::min(input.grid,2));
    if(!sabre61&&gridOut==1&&tune.dayKernelScale>0.f&&tune.dayKernelScale!=1.f&&key61>tune.s61MaxKey){
        const float lo=std::max(tune.s61MaxKey,1.f),t=std::clamp((key61-lo)/lo,0.f,1.f),s=1.f+(tune.dayKernelScale-1.f)*t*t*(3.f-2.f*t);
        kernel.base*=s;kernel.shrunk*=s;kernel.stretched*=s;kernel.flat*=s;
        report("HYBRID KERNEL: daylight round-4 kernel x"+std::to_string(s)+" (key "+std::to_string(key61)+", motion "+std::to_string(motion)+" px)");
    }
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
        // daylight multipliers only with the local alignment (they were measured with it): localAlign 0 keeps the merge before F6
        // for every sabre61 setting, forced 6.1 in daylight included
        const bool dayNoise=!night61&&tune.localAlign>0;
        const float tn=dayNoise?tune.s61DayTensorNoise:tune.s61TensorNoise,gn=dayNoise?tune.s61DayGdNoise:tune.s61GdNoise;
        k61b={1.f/(f0*f1),1.f/f3,1.f/(ks*ks),tn>0?tn:1.f};
        k61c={gn>0?gn:1.f,0,0,0};
        auto sigma=[&](float p){return std::to_string(ks/(p*std::sqrt(std::log(2.f))));};
        report("HYBRID KERNEL: Sabre 6.1 baseNoise slope="+std::to_string(baseSlope)+" offset="+std::to_string(baseOffset)+" key="+std::to_string(key)
            +" f0="+std::to_string(f0)+" f1="+std::to_string(f1)+" f2="+std::to_string(f2)+" f3="+std::to_string(f3)
            +" sigma across="+sigma(k61a[0])+" base="+sigma(k61a[3])+" along="+sigma(k61a[1])+" blurred="+sigma(k61b[0])
            +" ("+((tune.s61Mode&1)?"per-frame covariance":"base covariance for all frames")+((tune.s61Mode&2)?", window 1.5 px":"")
            +((tune.s61Mode&4)?", base x0.3 below "+std::to_string(tune.widenBelow)+" frames":", base widening by donor coverage")+")"
            +(tune.sabre61==2?std::string(" auto (")+(night61?"night key":"handheld with local alignment")+")":std::string())
            +" noise x"+std::to_string(k61b[3])+"/x"+std::to_string(k61c[0])+" motion="+std::to_string(motion)+" px");
    } else {
    if(tune.sabre61==2)report("HYBRID KERNEL: Sabre 6.1 kernel off (auto: key "+std::to_string(key61)+" > "+std::to_string(tune.s61MaxKey)
        +", motion "+std::to_string(motion)+" px "+(tune.localAlign>0?"< "+std::to_string(tune.s61MinMotion):std::string("without local alignment"))+")");
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
    // a second ultrashort frame (bentoFrames 2) at the same exposure (within x1.3) joins the replacement; any other is dropped
    std::vector<int> usFrames;
    if(us>=0)usFrames.push_back(us);
    for(int f=1;f<n;++f){
        if(input.frames[f].role!=kRoleUltrashort||f==us)continue;
        const bool same=us>=0&&aligned[f]&&std::abs(std::log(input.frames[f].exposure/input.frames[us].exposure))<std::log(1.3f);
        if(same&&int(usFrames.size())<std::clamp(tune.bentoFrames,1,4))usFrames.push_back(f); else keep[f]=false;
    }
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
            +" checks="+(tune.bentoLmc?"lmc":"round4")+" factor="+std::to_string(1.f/input.frames[us].exposure)
            +" frames="+std::to_string(usFrames.size())+" chroma sigma="+std::to_string(tune.bentoChromaSigma));
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
    if(us>=0&&!bento.active)for(int f:usFrames)keep[f]=false;
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
    // ---- F6: tile-local refinement of the homographies (frames that are merged; the ultrashort frame keeps its homography)
    std::vector<std::vector<float>> laFields(n);std::vector<float> laMaxY(n,0.f);
    LaGrid laG0;bool laOn=false;
    if(tune.localAlign>0&&n>1){
        const auto laStarted=Clock::now();
        LaBase laBase; // ~40 MB on 12 MP, freed before the GPU merge
        const float eps=std::max(baseOffset/std::max(baseSlope,1e-9f),1e-5f);
        laGray(b,input.frames[0].raw,1.f,eps,laBase.l0,true);
        laDown(laBase.l0,laBase.l1,true);laBaseLevel(laBase.l1,true);laBaseLevel(laBase.l0,true);
        const double baseMs=millis(Clock::now()-laStarted);
        const int win=std::clamp(tune.laWin,4,kLaMaxWin)&~3,stride=std::clamp(tune.laStride,2,win);
        laBase.g0=laGrid(laBase.l0,win,stride,2);laBase.g1=laGrid(laBase.l1,16,8,4);
        laBase.v0=baseSlope/16.f;
        std::vector<int> jobs;
        for(int f=1;f<n;++f){
            if(!keep[f]||!aligned[f])continue;
            if(input.frames[f].role==kRoleUltrashort&&!tune.laUltrashort)continue;
            jobs.push_back(f);
        }
        // One worker per frame at a time (gray 12 MB + L1 3 MB each): long-lived threads, so the scheduler moves them to the big
        // cores (short parallel sections per pass stayed on the small ones: 3.3 s for 19 frames against 0.8 s for this pool on Adreno 750 phones (SM8650)).
        std::vector<LaFrameStats> fstats(n);
        // An exception must not leave a worker thread (std::terminate: the shot is lost): it stops the pool, the merge then uses
        // the homographies only.
        std::atomic<bool> laFailed{false};std::string laFailure;std::mutex laFailureMutex;
        {
            const int threads=std::clamp(int(std::thread::hardware_concurrency()),1,std::clamp(tune.laThreads,1,8));
            std::atomic<int> next{0};
            auto work=[&]{
                try{
                    LaImage D0;
                    for(int k=next.fetch_add(1);k<int(jobs.size())&&!laFailed;k=next.fetch_add(1)){
                        const int f=jobs[k];
                        const auto g0=Clock::now();
                        laGray(b,input.frames[f].raw,1.f/input.frames[f].exposure,eps,D0,false);
                        fstats[f].ms[0]=millis(Clock::now()-g0);
                        laFrameField(laBase,D0,H[f],tune,laFields[f],fstats[f]);
                    }
                }catch(const std::exception& error){
                    std::lock_guard<std::mutex> lock(laFailureMutex);if(!laFailed.exchange(true))laFailure=error.what();
                }catch(...){
                    std::lock_guard<std::mutex> lock(laFailureMutex);if(!laFailed.exchange(true))laFailure="unknown error";
                }
            };
            std::vector<std::thread> pool;
            for(int t=1;t<std::min<int>(threads,int(jobs.size()));++t){
                try{pool.emplace_back(work);}catch(const std::exception&){break;} // fewer threads: the others take the frames
            }
            work();
            for(auto& th:pool)th.join();
        }
        if(laFailed){
            for(auto& fld:laFields)fld.clear();
            jobs.clear();
            report("HYBRID LOCAL ALIGN: failed ("+laFailure+"); merging with the homographies only");
        }
        std::string per;double partMs[7]{};
        for(int f:jobs){
            const LaFrameStats& st=fstats[f];
            laMaxY[f]=st.maxAbsY;
            for(int k=0;k<7;++k)partMs[k]+=st.ms[k];
            char v[80];std::snprintf(v,sizeof(v)," %d:%.2f/%.2f/%.0f%%",f,st.median,st.p90,100.f*st.accepted);per+=v;
        }
        const int done=int(jobs.size());
        const double grayMs=partMs[0];
        stats.localAlignMs=millis(Clock::now()-laStarted);
        laOn=done>0;laG0=laBase.g0;
        char line[320];
        std::snprintf(line,sizeof(line),"HYBRID LOCAL ALIGN: %s, %d frames, tiles %dx%d (%d RAW px, window %d RAW px), %.0f ms (gray %.0f ms thread sum) mu=%.2f kappa=%.1f maxShift=%.1f medians=%d; per frame |r| median/p90 RAW px / refined tiles:",
            tune.localAlign==2?"constant per tile":"bilinear field",done,laBase.g0.nx,laBase.g0.ny,2*stride,2*win,stats.localAlignMs,grayMs,tune.laMu,tune.laKappa,tune.laMaxShift,tune.laMedian);
        report(line+per);
        if(tune.profile){
            std::snprintf(line,sizeof(line),"HYBRID LOCAL ALIGN ms (thread sums): base=%.0f gray=%.0f down=%.0f L1=%.0f start=%.0f L0=%.0f accept=%.0f median=%.0f",
                baseMs,partMs[0],partMs[1],partMs[2],partMs[3],partMs[4],partMs[5],partMs[6]);
            report(line);
        }
    } else report("HYBRID LOCAL ALIGN: off");
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
            if(fr.role==kRoleUltrashort)A=tune.bentoUsWeight/float(std::max<size_t>(usFrames.size(),1));
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
    // F6 field in merge order (0 = base and frames without a field: zeros)
    std::vector<float> laAll;
    if(laOn){
        const size_t tiles=size_t(laG0.nx)*laG0.ny*2;
        laAll.assign(index.size()*tiles,0.f);in.laMaxY.assign(index.size(),0.f);
        for(size_t i=0;i<index.size();++i){
            const auto& fld=laFields[index[i]];
            if(fld.size()==tiles){std::copy(fld.begin(),fld.end(),laAll.begin()+i*tiles);in.laMaxY[i]=laMaxY[index[i]];}
        }
        in.laField=&laAll;in.laMode=tune.localAlign==2?2:1;in.laNx=laG0.nx;in.laNy=laG0.ny;
        // tile (i, j) centre: level px stride*i + (win-1)/2 -> RAW 2c + 0.5
        in.laOx=in.laOy=laG0.raw(laG0.centre(0));in.laStride=float(2*laG0.stride);
        if(const char* dump=std::getenv("SCAM_LA_DUMP")){ // replay diagnostics: the field of every merged frame
            std::ofstream fdump(dump,std::ios::binary);
            const int32_t hd[8]={0x3641414c,int32_t(index.size()),laG0.nx,laG0.ny,2*laG0.stride,2*laG0.win,0,0};
            fdump.write(reinterpret_cast<const char*>(hd),sizeof(hd));
            for(size_t i=0;i<index.size();++i){const int32_t idx=index[i];fdump.write(reinterpret_cast<const char*>(&idx),4);}
            fdump.write(reinterpret_cast<const char*>(laAll.data()),std::streamsize(laAll.size()*4));
            report(std::string("HYBRID LOCAL ALIGN: field dumped to ")+dump);
        }
    }
    in.k61a=k61a;in.k61b=k61b;in.k61c=k61c;
    in.mergeMode=sabre61?(tune.s61Mode&7):0;
    // A daylight 6.1 kernel is chosen for the local alignment (it loses without it): where the field is missing (F6 failed) or
    // the GPU cannot use it, the round-4 kernel instead.
    auto dropDay61=[&](const char* why){
        if(!(sabre61&&tune.sabre61==2&&!night61))return;
        in.k61a={};in.k61b={};in.k61c={};in.mergeMode=0;
        report(std::string("HYBRID KERNEL: Sabre 6.1 kernel off (")+why+")");
    };
    if(tune.localAlign>0&&!laOn)dropDay61("it was chosen for the local alignment, which gave no field");
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
    auto gpuMerge=[&](bool withLocalAlign){
        const auto gpuStarted=Clock::now();
        HybridGpu gpu(tune.rimRatio!=0,withLocalAlign,bento.active&&tune.bentoChromaSigma>0.f&&tune.bentoChroma>0.f);gpu.trace=report;gpu.profile=tune.profile!=0;gpu.profilePasses=tune.profile>=2;
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
    };
    if(laOn){
        // The F6 programs need two more storage bindings (15, 16) and change the reject / dilate / merge / rim passes: a GPU that
        // refuses them (compile/link, bindings) or a pass that fails must not lose the shot. Merge again with the homographies
        // only (the context of the failed attempt is destroyed first); a 6.1 kernel chosen only for the alignment goes as well.
        try{gpuMerge(true);}
        catch(const std::exception& error){
            report(std::string("HYBRID LOCAL ALIGN: GPU merge with the local offsets failed (")+error.what()+"); merging with the homographies only");
            in.laField=nullptr;in.laMode=0;in.laMaxY.clear();laOn=false;
            dropDay61("it was chosen for the local alignment");
            gpuMerge(false);
        }
    } else gpuMerge(false);
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
    report("HYBRID STAGES ms: align="+std::to_string(stats.alignMs)+" localAlign="+std::to_string(stats.localAlignMs)+" mask="+std::to_string(stats.maskMs)+" merge="+std::to_string(stats.mergeMs)
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
