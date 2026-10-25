#pragma once
// SCAM HDR "SCAM Hybrid" merge (no neural model, any GLES 3.1 GPU):
//   kernel      Sabre 6.1: every output colour is a kernel-weighted average of the burst's own RAW
//               sites of that colour (no demosaic), kernel = anisotropic covariance from the structure
//               tensor of the base frame (narrow across an edge, long along it, wide where flat),
//               base frame added last with a wider kernel where the donors left little weight.
//   front end   SCAM 9.6 (libgcastartup): noise-subtracting rejection D^2 = max(d^2 - (N_ref + N_cur), 0),
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
#include "scam-superres-gpu.h"
#include "scam-chroma-gpu.h" // P80 mosaicChromaMedian on the GPU
#include "scam-la-gpu.h"     // P308 the F6 alignment on the GPU (laGpu)
#include "scam-crash.h"
#include "scam-progress.h" // P220 the heartbeat counters (the app's watchdog and processing ring)
#include "scam-dcg-noise.h" // P320 the DCG two-zone noise fit (dcgNoiseFit)
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <fstream>
#include <mutex>
#include <sstream>
#include <thread>
// POSIX on every target: the program binary cache (trimProgramCache) runs on the host builds of CI too.
#include <dirent.h>
#include <sys/stat.h>
#if defined(__linux__)
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif
#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace scam {

enum HybridRole { kRoleNormal=1, kRoleBracketed=3, kRoleUltrashort=5 };

struct HybridFrame {
    const uint16_t* raw=nullptr;
    int role=kRoleNormal;
    float exposure=1;       // TET ratio to the base frame (bracketed > 1, ultrashort < 1)
    unsigned iso=0;
    float slope=0,offset=0; // noise model of this frame in its own exposure (normalised 0..1 units)
    float orderMs=0;        // capture time relative to the base (for the report only)
    // P320: DCG two-zone noise (HybridInput::dcg, measured by the noise check), own exposure, normalised units: from the level
    // dcgKnee up the read-noise offset is dcgOffsetLcg instead of offset (the slope is shared; blended over 0.9 .. 1.1 x the knee,
    // hybridDcgShare); 0, 0 = one curve (every non-DCG frame)
    float dcgKnee=0,dcgOffsetLcg=0;
};

struct HybridInput {
    int w=0,h=0,cfa=0;
    float white=0;
    std::array<float,4> black{};
    std::vector<HybridFrame> frames; // frames[0] = base (normal)
    bool diagnostics=false,mergedDng=false;
    bool clipFlags=false; // header flag 4: append the per-pixel clip flags (uint8 per output pixel) after the effective map
    bool strict2x=false; // header flag 8: native 2x samples; no coarse-grid resize or sensor-grid fallback
    // header flag 16 (P187): no effective-frame map - the app reads none at this size (above 100 MP: no denoise); the clip-flags
    // trailer then follows the RGB / the merged RAW directly
    bool noEffMap=false;
    // Output grid (NCH v11): 1 = sensor grid (today), 2 = the Sabre 6.1 2x grid (four sub-positions +-0.25 px per
    // sensor pixel, RGB 2w x 2h). The app runs its whole pipeline on that grid and resizes at the end.
    int grid=1;
    // Colour block of the stream (NCH v11 word 14, P14): 0 = unknown (the worker measures it), 1 = plain Bayer, 2 = Quad (2x2
    // same-colour sites, a sensor mode without remosaic), 4 = Tetra (4x4). A block mosaic goes through hybridReconstructMosaic.
    int mosaic=0;
    // Sub-frames of a colour-block mosaic (P22): frames[k*subFrames + s] are the b² plain-Bayer sub-frames of real frame k
    // (hybridReconstructMosaic); 0 = ordinary frames.
    int subFrames=0;
    // The ultrashort frames went through the row check already (hybridUsRowsCheck: the mosaic paths on their binned frames, the
    // plain path once in hybridReconstruct): no second check.
    bool usRowsChecked=false;
    // P310: the plain path's tile check (P290) of the ultrashort frames against the base: the largest |factor - 1| of a colour
    // within 4 tiles of the base's clip, over the frames (LEDs on PWM / ARGB animation 1.0 - 1.2, daylight 0.1); -1 = not measured
    float usFlicker=-1.f;
    // P320: NCH header flag 32: a DCG burst (dual conversion gain: one exposure read at a high and a low conversion gain, fused into one
    // linear frame; the app proved the linearity). Its noise is two zones (the noise check fits them, hybridDcgZones); 0 = every other
    // burst and every old burst, unchanged.
    bool dcg=false;
};
// P320: a DCG burst whose two noise zones were measured (the frames carry dcgKnee / dcgOffsetLcg): the two-zone GPU programs and keys
inline bool hybridDcgZones(const HybridInput& in){
    return in.dcg&&!in.frames.empty()&&in.frames[0].dcgKnee>0&&in.frames[0].dcgOffsetLcg>0;
}
// P320: the LCG zone's share at a level (own exposure): smoothstep over 0.9 .. 1.1 x the knee, as the DCG programs (hybridDcgSources)
// and the app's denoise (NoiseModeler.zoneWeight) blend the two offsets; 0 for a knee <= 0
inline float hybridDcgShare(float level,float knee){
    if(!(knee>0.f))return 0.f;
    const float t=std::clamp((level-0.9f*knee)/(0.2f*knee),0.f,1.f);
    return t*t*(3.f-2.f*t);
}
// P320: the F6 damping floor, the noise variance of the sqrt-domain gray (gray = sqrt(L + O / S): slope / 16 for one curve). A DCG
// burst's LCG offset adds up to (Ol - Oh) / (16 (T + Oh / S)) at the knee and less above it: that worst case is taken (the field is a
// little stiffer in the mid-tones, never refined on the LCG zone's noise). Every other burst: slope / 16.
inline float hybridLaV0(const HybridInput& in,float slope,float offset){
    float v0=slope/16.f;
    if(hybridDcgZones(in)){
        const HybridFrame& b=in.frames[0];
        v0+=std::max(0.f,b.dcgOffsetLcg-offset)/(16.f*(b.dcgKnee+offset/std::max(slope,1e-9f)));
    }
    return v0;
}

// Tuning (SCAM-like; a "key value" text file in the job dir or the external files dir overrides it).
// Frames one hybrid merge takes (the app sends up to 48; a Quad / Tetra burst becomes 4 / 16 plain-Bayer sub-frames per frame).
constexpr int kHybridMaxFrames=128;
// Frames one merge can hold on the GPU: the per-frame tables of kHybCommon live in the uniform block FrameTable, declared [128]
// (6 x 128 x 16 B = 12 KB, within the 16 KB every GLES 3.1 GPU gives a block); a frame index past it reads undefined offsets
// (SSBO reads out of bounds). hybridReconstruct drops the normal donors farthest in time beyond this.
constexpr int kHybridGpuFrames=128;
// Any RAW size (owner 2026-10-07): the hybrid takes every stream whose sensor-grid RGB fits the app's result buffer (12 B/px
// <= 2 GiB, ~178 MP). Up to this many pixels (the old cap) every path is exactly the one before, bit for bit; above it the GPU
// strips, dispatches, the output grid and the memory held follow the real limits (storage block size, readback size, available
// memory), each reported.
constexpr int64_t kHybridClassicPixels=16000000;
// P179 (owner, 2026-10-16: «хотя бы 3 кадра и один из них был ультракороткий», «Сделай 4 кадра»): above this a burst comes sized
// by the app (three N and one ultrashort, the frames it leaves out freed, the camera in its plain session) and merges whole
constexpr int64_t kHybridHugePixels=100000000;
constexpr int kHybridHugeFrames=4;
// MemAvailable of /proc/meminfo in bytes (0: unknown).
inline uint64_t hybridMemAvailable(){
    std::ifstream f("/proc/meminfo");
    std::string key,rest;uint64_t kb=0;
    while(f>>key>>kb){std::getline(f,rest);if(key=="MemAvailable:")return kb*1024;}
    return 0;
}
inline std::string hybridMB(uint64_t bytes){return std::to_string((bytes+(1u<<19))>>20);}
// P213a: the worker's resident memory now and its peak so far (VmRSS, VmHWM of /proc/self/status) in MB, 0 = unknown
inline std::array<long,2> hybridRssMB(){
    std::array<long,2> mb{0,0};
    std::ifstream f("/proc/self/status");std::string line;
    while(std::getline(f,line)){
        const int k=line.rfind("VmRSS:",0)==0?0:line.rfind("VmHWM:",0)==0?1:-1;
        if(k>=0)mb[size_t(k)]=std::atol(line.c_str()+6)/1024;
    }
    return mb;
}

struct HybridTuning {
    // rejection (SCAM 9.6 rejection.cl constants)
    float cdm=0.2f;              // color_difference_multiplier (RGB; SCAM 0.07): at 0.07 wind-moved leaves and branches shifted 0.5-2 px were
                                 // accepted and their samples interleaved with the base (luma zipper, staircase edges); 0.2 on the
                                 // synthetic motion burst: moving edges +0.8 dB, moving texture +1.8 dB, static bursts -4..10 % frames
    float boost=6.0f;            // extra_motion_robustness_boost
    float boostEnable=1.f;       // 1: boost where the F6 local motion varies (v11 Z channel); 2: everywhere (SCAM without a
                                 // motion test, the old switch); 0: off. Without the local alignment 1 does nothing.
    float varianceThreshold=25.f;// motion_robustness_boost_variance_threshold
    // P335 (owner, 2026-10-25: «посмотри как работают у Арк реджекты и подумай как их можно улучшить у нас»): 1 = the motion prior
    // of the rejection as a share (smoothstep 0.5 .. 1.5 x motionThreshold of the Z channel, bilinear between the tiles) instead of
    // a switch on the nearest tile, and half the difference noise forgiven inside it; 0 = the switch as before (replays unchanged;
    // the app sends 1).
    float rejPrior=0.f;
    float motionThreshold=1.5f;  // extra_motion_robustness_motion_threshold: min-max extent of the raw LK flow over the 3x3 tiles (RAW px)
    float filterVariance=0.5f;   // variance scale of the bilinear donor sample (kFilterVarianceScale analogue)
    float dilateOffset=0.2f,dilateScale=2.f; // DilateMask: rej = (sum25 - 0.2) / 2
    float dilateFloor=0.15f;     // per-cell rejection below this is noise, not motion: it does not spread
    float clipLevel=0.98f;       // sample >= clipLevel * white is clipped
    // per-frame weights (sabre::SpatialMerge driver)
    float fwe=1.f;               // frame_weight_exponent
    float weightCap=50.f;        // alt_noise_variance cap
    float lutLo=10.f,lutHi=30.f,lutHiSigma=1.414f;
    // kernel (Sabre 6.1 curves of the SNR of mid grey, see snrKernel())
    float kernelScale=1.f;       // multiplies every kernel sigma
    float widenBelow=4.f;        // base-frame kernel widening below this accumulated donor weight (6.1: 4.0)
    float widenMul=1.826f;       // 6.1: covariance x0.3 = sigma x1.826
    float kernelFloor=0.00005f;  // 6.1 kEpsilon: no hole where the kernel is narrower than the lattice
    // Colour-difference interpolation of the base frame (pass kHybChroma after the merge of a strip): where fewer than widenBelow
    // donors were accepted (wind-moved branches and leaves: the rejection leaves the base alone), R and B come from one Bayer frame
    // on a kernel narrower than the R/B lattice even after the x1.826 widening (across-edge sigma 0.25 px): at a non-R row R is the
    // plain mean of the two R rows across the edge, at an R row the site itself, a period-2 R/B fringe ("zipper") along every such
    // edge. R - G is smooth across a neutral edge: the base's share of R (B) becomes the merged G plus the kernel-weighted
    // difference v - G(site) of its unclipped R (B) sites, G at the site interpolated along the edge from the four orthogonal green
    // neighbours. 0 = off (output byte-identical to the merge without it). The merge program itself is left untouched: on Adreno
    // 750 any edit of it made the whole merge ~15x slower.
    float chromaDiff=1.f;
    int chromaDiffClamp=1;       // 1: the rebuilt value is clamped to the range of the base's own R (B) samples around the pixel (no
                                 // new extremum): next to a 1 px DIAGONAL line the four orthogonal greens of an R site straddle it and
                                 // the difference carries half the step. 0: unclamped.
    float rawTensor=1.f,rawNoise=0.75f; // SCAM guide: tensor of the base greens, noise bias 0.75 Var
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
    // P309 (owner, 2026-10-20: «нам нужно сделать более мягкий переход между экспозициями»): the ramp mask. With the mask above the
    // ultrashort frames take over in about one cell along the bentoNearClip contour: there the share of the ultrashort frames is
    // m U / (m U + (1 - m)(1 + sum A of the others)), which at ~20 N frames stays near 0 until m > 0.9 (OPPO lamp burst: the noise
    // of the merge x2.5 within 6 px). bentoRamp > 0: the mask is the SHARE of the ultrashort frames, smoothstep(bentoRampLo,
    // bentoRampHi) of the base level (each cell's brightest site over its 3x3 cells), only in the bright area connected to a clipped
    // cell (cells >= bentoRampLo) and within bentoRampReach cells of one (dilated, then blurred), 1 on clipped cells, then spread by a
    // gaussian of sigma bentoRamp cells inside that bright area only (never onto the dark side of a sharp edge), never below the share
    // the mask above gave; the frame weights then turn the share into m (hybridReconstruct).
    // 0 = the mask above (replays unchanged); the app sends its own values (bento_ramp*).
    float bentoRamp=0.f,bentoRampLo=0.6f,bentoRampHi=0.97f;
    int bentoRampReach=16;
    // P310 (owner, LED fans and an LED bar: «куча артефактов на подсветке», «проблема с маской бенто, ... исправлять переходы»): the
    // ultrashort frames mixed into the merge site by site, colour by colour (a clipped N sample left out of its colour only) gave
    // isolated bright 2x2 cells, a G/B checker and pink blocks wherever the ultrashort frames saw the light in another colour than
    // the N frames (PWM / ARGB animation: R x1.4, G x0.5 against the N merge on the rings). bentoBlend 1: the merge runs on the N
    // frames and the base alone (as without Bento) and a pass after it (kHybUsBlend) blends the ultrashort frames' own demosaiced
    // RGB into it, one weight for R, G and B, by REGION (a per-pixel test of the ultrashort colour against the N merge tore edges,
    // left holes and doubled moving paper / leaf shadows: their N frames see them elsewhere than the ultrashort ones):
    //  - the N merge's clip mark (smoothstep(bentoBlendClipLo, bentoBlendClipHi) of its brightest colour, or a clipped-mean colour)
    //    spread with full weight bentoBlendNear px and down to 0 at bentoBlendFar px, gated by the bright neighbourhood (a gaussian
    //    of bentoBlendGateSigma px of smoothstep(bentoBlendGateLo, bentoBlendGateHi) of the N merge's level, or the ultrashort
    //    colour's own level: never onto a dark border); the N clip itself always takes the ultrashort colour;
    //  - at least the base frame's cell weight (it reaches across strip borders): smoothstep(bentoBlendLo, bentoBlendHi) of the
    //    cell's brightest site, dilated by bentoBlendDilate cells, a gaussian of bentoBlendSigma cells, gated by the blurred near-clip
    //    share, times the best ultrashort frame's validity;
    //  - neutral light times the best ultrashort frame's check validity (a hand / object moved over a window: the N clip stays).
    // The frames: validity-weighted, a soft rejection against their median level (bentoBlendReject). The P309 ramp is not used then
    // (its K weights and share belong to the merge's own mixing). 0 = as before (replays unchanged); the app sends 1.
    int bentoBlend=0;
    float bentoBlendLo=0.93f,bentoBlendHi=0.99f,bentoBlendSigma=1.5f,bentoBlendReject=0.15f;
    float bentoBlendPixLo=0.7f,bentoBlendPixHi=0.98f,bentoBlendFill=8.f; // without clip flags: the N level ramp; hole fill radius (px)
    float bentoBlendSat=0.35f;   // inside the light only coloured light (saturation >= this) is trusted past the cell check
    // (an N level of 0.95 is still the mean of 20 unclipped frames: the clip mark from 0.97, the base cells from 0.93 - a lamp's
    // whole glow at 0.95 went to three noisier ultrashort frames and its edge showed as a ring of grain)
    float bentoBlendClipLo=0.97f,bentoBlendClipHi=0.995f,bentoBlendNear=3.f,bentoBlendFar=8.f;
    float bentoBlendGateLo=0.5f,bentoBlendGateHi=0.85f,bentoBlendGateSigma=2.f;
    // the ultrashort colour's gain against the N merge (LEDs on PWM / ARGB animation shine in other colours during the ultrashort
    // exposures) from the light's glow within bentoBlendGainRadius px (0 = off; N from bentoBlendGainLo of its clip; see kHybUsBlend
    // stage 2), trusted fully where it holds bentoBlendGainSupport of the kernel's weight, limited to x1/max .. x max
    float bentoBlendGainRadius=12.f,bentoBlendGainSupport=0.15f,bentoBlendGainMax=4.f,bentoBlendGainLo=0.25f;
    // its strength from the tile check's deviation near the clip (HybridInput::usFlicker): 0 below lo, full from hi
    float bentoBlendGainFlickerLo=0.2f,bentoBlendGainFlickerHi=0.4f;
    float bentoBlendChroma=1.f;  // the ultrashort colour's chroma smoothed over 5 x 5 px, luma kept (0 = none .. 1)
    float bentoBlendValidSigma=1.f; // the cells' check planes smoothed by a gaussian of this many cells (0 = as checked)
    int bentoBlendDebug=0;       // diagnostics: 1 = the blend weight as the image, 2 = the gained ultrashort colour alone, 3 = its gain
    int bentoBlendDilate=2;
    float bentoUsWeight=1.f;     // A of the ultrashort frame (driver: 1.0)
    float bentoUsSigma=1.f;      // isotropic kernel sigma (px) of the ultrashort frame: inside the mask it is the only frame,
                                 // and one Bayer frame needs >= ~0.7 px not to leave R/B holes (SCAM: base kernel x2.5 under Bento)
    int bentoValidate=2;         // 1 = the SCAM intensity check runs against EVERY ultrashort frame; a frame's robust weight is scaled by
                                 // its own validity (1 - error) and the mask keeps the best frame's, so the base returns only where no
                                 // ultrashort frame is valid (a hand moving through a lamp: one frame shows it, the other does not);
                                 // 2 = also an UNCLIPPED base cell that the gained ultrashort frame shows much brighter is motion (the
                                 // hand left: the base holds real data there). 0 = the first frame only, the second unchecked
    float bentoMotionMax=4.f;    // Bento is refused (in every mode, "always" included) when more than this percentage of the mask cells
                                 // shows motion in the best ultrashort frame (the SCAM intensity check, with bentoValidate 2 also an
                                 // unclipped base cell the ultrashort frame shows much brighter): a hand or a screen that changed between
                                 // the base and the ultrashort frames would come out as noisy fragments of the moving object. 100 = never.
                                 // Measured (PHY110, 10 bursts): hand in front of a laptop screen at night 8.1 %, static scenes
                                 // 0..0.13 %, handheld 2x daylight with water 2.2 %.
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
    // ship scene (1x): fine detail 0.77 -> 0.96 of WhyCam (water 0.69 -> 0.95) at +6 % sky noise (= WhyCam);
    // research/hybrid5/ark_sharpen_device.md. Not on the 2x grid: a static burst there has no sub-pixel diversity (not measured).
    float dayKernelScale=0.5f;
    // Shasta
    float shastaSharpness=0.35f; // bracketed_sharpness_threshold
    float shastaMaxRatio=32.f;   // max bracketed/base TET ratio
    float shastaSat=0.5f;        // sharpness pixels: guide mean below this share of white in both frames. The guide is a 4x4 mean:
                                 // at 0.9 blocks along bright edges still held clipped sites of the x2 brighter bracketed frame, their
                                 // truncated gradients scored it 74-77 % of the base at the same shutter (all dropped); 0.4-0.6: 96-100 %
    int shastaEnable=1;
    int shastaForce=0;           // P68: 1 = bracketed frames skip the sharpness gate and the ratio limit; a frame is dropped only when
                                 // more than shastaMotionMax % of its unclipped 8x8 blocks moved against the base (shastaMotion)
    float shastaMotionMax=4.f;   // P68: motion fallback of the forced Shasta frames, % of the blocks (100 = never)
    // misc
    int snrFixed=0;
    float snrScale=0.25f;
    int debugFrame=-1;           // merge one donor only (merge_debug_frame_index)
    int grid=0;                  // replay override of the output grid (0 = header, 1 sensor, 2 = Sabre 2x)
    // Highlights and outliers (research/hybrid5/highlights.md P2, P3, P5)
    int cellClip=1;              // 1: a 2x2 cell with any site >= clipLevel is clipped for EVERY colour of that frame (a clip border
                                 // no longer averages green from the dark side only and R/B from both = magenta rim); 0: per site
    float staticShift=0.75f;     // P150: a still burst (fewer than a quarter of the donors moved this far, RAW px at the corners and
                                 // the centre) merges with cellClip 0; 0 = never (cellClip as set)
    float hotSigma=5.f;          // fixed-pattern (hot/warm/dead) sites: deviation from the median of the 8 same-colour neighbours in
                                 // the mean of hotFrames normal frames at the same SENSOR site, in sigma of that mean; 0 = off
    int hotFrames=8;             // normal frames averaged for the fixed-pattern test (base included)
    float hotBaseSigma=7.f;      // transient (RTS) outliers of the base frame alone (it skips rejection), sigma of one frame; 0 = off
    float hotCross=0.35f;        // a real point or line also lifts the adjacent sites of the other colours: above this share of the
                                 // site's excess the site is scene detail and stays
    float hotMaxLevel=0.03f;     // outliers are tested only where the local level is below this share of white (darks: the dots)
    float hotMaxKey=30.f;        // ... and only in shots whose 6.1 SNR key is at most this (night): at ISO 100 the test found 2000
                                 // "outliers" per MP in dark texture, where the dots are not a problem
    // P214k: plausibility cap of the fixed-pattern test, sites per MP of a strip's own rows (plain and native sites alike); 0 = no
    // cap. A sensor's defects are few and spread evenly: the 12 MP night shots flag 95-134/MP, the 8 reference bursts up to
    // 1248/MP; the X7 Ultra's 50 MP bursts (a phone on a table, ISO 1600) 11-25k/MP - the mean of 8 frames of a still burst keeps
    // the scene's texture, and the header noise model of those frames is ~4x low in variance (f0 - f1 of the 191825 burst), so
    // texture and noise peaks pass 5 sigma of that model. Above the cap a strip keeps only its strongest, most isolated sites up
    // to the cap: the rank is the site's excess over the model noise AND the spread of its 8 same-colour neighbours (texture or
    // the real noise there; sd from the frames themselves), so a defect in a flat dark area ranks first and a site in texture last.
    float hotCap=2000.f;
    int bentoScam=1;              // Bento fallback checks: 1 = SCAM 9.6 (intensity error -> inpainting holes, us-clip share), 0 = round 4
    float bentoInvalid=0.9f;     // SCAM min_normalized_intensity_error_threshold (|min(GainUp(us) - base, 0)| over RGB)
    float bentoInpaintMiddle=0.98f,bentoInpaintMin=0.502f; // SCAM max_rgb_clipping_threshold, min_rgb_threshold_for_inpainting
    int clipFlags=0;             // 1: write the clip-flags trailer even when the request does not ask for it (offline replays with
                                 // SCAM_HYBRID only: the app checks the result size)
    // Sabre 6.1 kernel (research/hybrid5/sabre2x_61.md F1-F4): 6.1 sigma curves and SNR key, kernel covariance of every frame from
    // its own RAW (tensor of the quad luma, Wiener noise from the frame's noise model), the 6.1 window (+-1.5 px) and the base
    // widening below 4 accepted frames. 0 = the round-4 kernel (old SCAM super-res curves, base-frame tensor for all frames),
    // 1 = always, 2 = auto: at night (6.1 SNR key <= s61MaxKey) and, with the F6 local alignment on, in daylight on a handheld burst
    // (RMS shift of the donors at the frame centre >= s61MinMotion RAW px). Replays (f6_local_align.md): at night the 6.1 kernel
    // raises the split-half SNR in every band; in daylight with one homography per frame its narrow kernels doubled the frame-to-frame
    // differences (misregistration), with F6 it adds detail in every band (x1.7 above the 1x Nyquist on the handheld day burst);
    // on a static burst (no sub-pixel diversity) it only adds noise (x2-3), so the round-4 kernel stays there.
    int sabre61=2;
    float s61MaxKey=30.f;
    float s61MinMotion=2.f;
    float s61TensorNoise=1.f,s61GdNoise=1.f; // multipliers of the emulated 6.1 noise LUT (tensor, green difference)
    // P332 (owner, 2026-10-25: «исправь чтобы не было мыла», the 6.1 kernel stays «Всегда»): the 6.1 structure tensor from the
    // quad lumas (2x2 means, differences 2 px apart per axis) cannot see detail finer than a 4 px period, and above it the
    // direction flips (a 3 px grating at 17 degrees reads -18): its kernel (along sigma 1.2 px by day) smears such texture.
    // s61Fine > 0: the tensor of the green sites' differences to their diagonal neighbours (1 px per axis, the same rotated form),
    // its noise term x16 (single sites, half the step) times this value. Synthetic handheld burst (12 frames, ISO ~100, F6, day
    // noise x16/x4), grating response 4 % / 30 % contrast at 3 px: 0.22 / 0.37 -> 0.50 / 0.62 at 2, diagonal 3 px 0.29 -> 0.75;
    // flat noise x1.10 (8: x1.0 with 0.31 / 0.61 / 0.65). 0 = the quad tensor (replays unchanged); the app sends 2.
    float s61Fine=0.f;
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
    // bilinearly between tile centres, 2 = constant per tile (NEAREST, as 6.1 / v11).
    int localAlign=1;
    int laWin=16;                // L0 window, gray px (2 RAW px each): 16 = 32x32 RAW windows (6.1 tile, v11 window)
    int laStride=8;              // L0 tile stride, gray px: 8 = 16x16 RAW output tiles (v11 grid)
    int laIters=3,laItersCoarse=3; // LK iterations on L0 / L1
    float laMu=0.1f;             // Levenberg-Marquardt damping (share of the mean tensor eigenvalue)
    float laKappa=2.f;           // noise damping (x window pixels x gray noise variance)
    float laMaxShift=6.f;        // larger residuals (RAW px) are motion, not misregistration: the tile keeps the homography
    int laMedian=2;              // 3x3 median passes over the L0 field
    int laUltrashort=0;          // 1: refine the ultrashort (Bento) frame too (x8..16 gain: noisy, isotropic 1 px kernel)
    int laThreads=6;             // worker threads (one frame each; ~15 MB per thread)
    int laStream=1;              // P33 W2.1: 1 = the field in tile-row bands alongside the GPU merge (LaStream), 0 = the whole field first;
                                 // P48: 1 takes windowed gray rows when the whole gray images do not fit, 2 = always windowed (A/B)
    int laScam=0;                // P33 W2.1: nice value of the LaStream threads (0 = as the merge thread; > 0 leaves the CPU to it)
    // P308 (owner, 2026-10-20: «сделай гпу выравнивание и дай возможность выбора»): 1 = the F6 field on the GPU (scam-la-gpu.h: the
    // same steps, float rounding apart; LaGpuWorker from the alignment on); 0 = the CPU (the banded LaStream alongside the merge, or
    // the whole field first); 2 = auto: the GPU where the CPU field would come whole before the merge (Sabre 2x checked with the
    // whole field, sub-frames, large frames without the large stream), the CPU where its bands stream alongside the merge (hidden
    // there). A GPU failure falls back to the CPU. The app writes it (pref_scam_hybrid_la_engine, default auto).
    int laGpu=0;
    int isoKernel=0;             // diagnostics, round-4 kernel only (sabre61 off): 1 = isotropic sigma = base (no edge shaping), 2 = also no
                                 // flat widening: a "spatial RGB"-like isotropic Gaussian (SCAM 9.6 spatial_rgb: sigma 0.28..0.40 px)
    // P14 / P15 colour-block mosaic: 0 = the header's block, measured from the base frame when the header has none or says
    // plain; 1 = always plain Bayer; 2 / 4 = force Quad / Tetra (replays).
    int mosaicBlock=0;
    // 1: divide out the response of every site class inside the colour block (64 classes, y&7, x&7), the mean ratio of the tiles
    // within 5 % rms; 2 (P188, default): the median ratio in two rounds, the second against the frame's own noise (see
    // hybridMosaicGains); 0: off
    int mosaicGain=2;
    // P268 (owner's X200 Ultra wide / main Quad shots, 2026-10-19: «нужно исправить сетку на 2x2 4x4 ... особенно она заметна на
    // сверхширике»): the site response of a 2x2-OCL Quad sensor changes over the field (IMX06C wide: -16..+16 % per site class from
    // corner to corner), so one gain set per class left a 4 px hatch whose phase flips across the frame. 1: with mosaicGain 2 the
    // gains are measured per zone too and applied as a smooth field (HybridGainField) when the zones differ from the global set.
    int mosaicGainField=1;
    int mosaicGainZones=24;
    int mosaicUsSmooth=1;
    // P269 (owner, 2026-10-19: «С баером тоже не должно быть артефактов, ни фиолетовых засветов, ни штрихов»): the chroma median
    // of the mosaic merges (dual 5-point on R/G, B/G with its false-colour suppression; block 1: taps 1 px) on a plain Bayer merge
    // too: a resting phone's burst has no shifts between its frames, and the merge left a single frame's demosaic colour along
    // slanted bright edges (OPPO 12 MP sill: bright pixels with a high-frequency chroma above 0.05: 359 -> 2, p99 0.073 -> 0.021;
    // signs and a red extinguisher keep their colour). 0: off (the merge as before), n: the median's block.
    int plainChromaMedian=1;
    // P335 (owner, 2026-10-25: «днём слишком сильный хрома шумодав»): the share of that median's colour change a hand-held burst
    // keeps (its donors moved, P150's test): 1 = the whole median as before (replays unchanged), 0 = none; the app sends 0.35.
    // A still burst always takes the whole median.
    float plainChromaHand=1.f;
    // P282 (owner, 2026-10-19: «улучшить ремозаик для однокадра sabre чтобы улучшить движение в случае реджекта для quad и quad
    // quad, заодно и для bayer»): where the merge's donors were rejected (a moving subject: few accepted frames F), the base frame
    // alone is demosaicked from ALL its sites (a green-equivalent luma of every colour, colour differences of the block means with
    // the Gr / Gb phase cancelled and its aliasing damped) instead of each colour from its own sites through the widened kernel.
    // baseDemosaic: its share at F = 0 (0 = off, the merge as before byte for byte), fading out by F as the widening does;
    // baseDemosaicAniso: how much the luma kernel follows the local edge direction (0 = round).
    float baseDemosaic=1.0f;
    float baseDemosaicAniso=0.6f;
    // P335: Bayer base demosaic, share of the false-colour damping by the Gr / Gb step above its noise (0 = off, replays
    // unchanged; the app sends 1)
    float baseDemosaicAlias=0.f;
    float baseDemosaicDetailLo=0.f,baseDemosaicDetailHi=0.01f;
    float baseDemosaicBayerLo=1.3f,baseDemosaicBayerHi=2.5f; // Bayer: the base noise model holds there (flat sky ~1) // off: flat areas measured ~2, structure 20-50 (the base noise model reads low)
    // P269 (owner, 2026-10-19: «Посмотри как работает маска бенто в why кам и посмотри как можно улучшить нашу»): the
    // ultrashort frame's check of the mask reads it at the aligned position (bilinear between the 2x2 cells, as LMC 9.6 renders
    // the ultrashort aligned before its error test) instead of the nearest cell, whose half-cell misregistration along thin
    // bright edges counted as errors (holes, the mask cut back). 0: the nearest cell as before.
    int bentoSubpixel=1;    // P268: the mosaic paths' ultrashort gain check on smooth tiles only (hybridMeasuredGain smooth)  // P268: zones of the field across the longer side (square zones of whole 8 px tiles)
    // P188: 1 (default) = a donor's robustness bilinear between the cell centres in the native mosaic merge; 0 = the nearest cell
    int mosaicRobustBilinear=1;
    // P19 lateral CA of R / B against G (research/RAW_CA_Correction_SABRE.md, librtprocess CA_correct idea): 0 off, 1 auto (a
    // radial model fitted on the base frame, applied to every frame's R / B sites on the GPU before any pass reads them, when it
    // moves R or B by at least caMinShift RAW px at the corner)
    int caCorrect=1;
    float caMinShift=0.25f;
    // P27 (VERIFY-11): 1 (default) = the frames of a bracketed / ultrashort exposure whose data reliably disagrees with their
    // metadata exposure ratio by more than 5 % are merged with the measured ratio (hybridApplyMeasuredGains: enough tiles, low
    // MAD, consistent across the frames of the exposure, 0.5..2 x the metadata), all others with the metadata ratio; 0 = report
    // only (HYBRID GAIN CHECK). -1 is internal (decided before the merge).
    int gainMeasured=1;
    // P71: 1 (default) = the noise model of the frames is checked against the burst (hybridMeasureNoise: base vs the nearest normal
    // frame on flat blocks); when the measured noise is at least 1.5 x the model, every frame's slope / offset is scaled by it (up
    // to x4); P77: at most 0.67 x the model (Redmi Note 11 Pro: 0.06..0.24 in every shot, its HAL profile is ~16 x too high and the
    // rejection took moving objects for noise: ghosts), scaled down by it (down to x1/16). 0 = off. -1 is internal (decided before
    // the merge).
    int noiseMeasured=1;
    // P80: 1 (default) = the chroma median of the mosaic results on the GPU (ChromaMedianGpu, float rounding only), 0 = CPU.
    int chromaGpu=1;
    // P213c: 1 (default) = the CPU chroma median of a native mosaic merge runs on the merged rows while the GPU merges the next
    // strips (MosaicChromaOverlap, the same floats), 0 = after the merge. OPPO X7U, 50 MP FULLRES (two bursts): the chroma after the
    // merge 0.72 / 0.67 s -> 0.04 s, the merge no longer than before; 12 MP with chromaGpu 0: 0.19-0.26 s -> 0.01-0.03 s.
    int chromaOverlap=1;
    // P118 «Sabre 2× только когда есть смысл» (P74's JSR experiment jsrAuto2x; the other JSR experiments are gone): auto2x 1 = a
    // requested 2x grid (Sabre 2x, plain or native mosaic) is merged on the 1x grid and resized 2x when the frames do not sample
    // finer than the sensor (green fill distance above auto2xMaxGap sensor px) or when the light is too low for detail at 2x (SNR
    // of a 2x cell below auto2xMinSnr: the base frame's noise model at 0.1 of white, sqrt(frames / 4) of the merge's averaging).
    // Off here (replays as before); the app writes it with the switch (on by default).
    int auto2x=0;float auto2xMaxGap=0.5f,auto2xMinSnr=20.f;
    // P62 Mochi (v11 PhotometricMerge): each bracketed frame gets a per-tile photometric correction against the base on the GPU
    // (HybridGpu::mochi) before the merge. 0 = off (the merge exactly as before), 1 = the rule (more than 3 non-bracketed frames),
    // 2 = whenever a bracketed frame is merged.
    int mochi=0;
    int mosaicNative2x=1;        // P56: a Quad / Tetra T1 mosaic with the 2x output grid merges it natively (0: resample the sensor-grid result)
    // P56: the native Tetra merge's window (sensor px, 1..8) on the Sabre 2x grid; 0 = 2 x mosaicWindow as on its sensor grid. 5 on the
    // vivo 10x burst (OPPO PHY110): merge 16.3 -> 12.3 s, 62 dB from window 6, flat dark noise +4 %; 4: 8.8 s, 57 dB, noise +20 %
    int mosaicTetraWindow2x=5;
    int mosaicShare=1;           // 1: the sub-frames of one mosaic frame share its local motion (laShareSubFrames), 0: one field each
    // Sub-frames of a mosaic (P22): the Sabre kernel sigmas are in sub-frame px, b native px of the stream. Their density (b² sub-frames
    // per frame) allows a narrower kernel across edges and in texture; the blurred kernel of flat areas (the noise there) stays.
    // Handheld X7 Ultra Quad burst, 0.6 with 24 frames: +14 % fine-band energy at the flat-area noise of 1.0 with 16 frames.
    float mosaicEdgeScale=0.6f;
    int mosaicChroma=1;          // 1: chroma median of the mosaic result (v11 remosaicked: chroma_median dual_5_point), 0: off
    int mosaicFrames=30;         // frames of a mosaic burst merged (b^2 sub-frames each, at most kHybridGpuFrames sub-frames): the
                                 // merge time grows with the sub-frames; 24 Quad frames (96 sub-frames) take about 8 s on the X7 Ultra,
                                 // 16 about 6.8 s (P22: the extra frames pay for the narrower edge kernel). 30 since 7 October 2026
                                 // (owner: towards WhyCam's ~30 merged frames; the ZSL ring sends 30 N): the native merge (one GPU
                                 // slot per frame, strips within the storage block and a quarter of MemAvailable) takes them all, the
                                 // split up to kHybridGpuFrames / b^2 (Quad 30, Tetra 8); above 16 MP both stay within half of
                                 // MemAvailable
    int seriesFrames=0;          // P190: a FULLRES series the app sized against the memory itself (an explicit frame count, a 200 MP
                                 // series above 4 frames): the mosaic frame pick keeps up to this many frames whatever its memory
                                 // budget and mosaicFrames say (the split: at most kHybridGpuFrames / b^2); 0 = the pick as before
    // P29 native mosaic path (research/ark23/quad_report.md section 8, plan P29). mosaicPath 0 = the b^2 sub-frame split above
    // (hybridReconstructMosaic, unchanged); 1 = hybridReconstructMosaicNative: one GPU slot per FRAME (mosaicFrames frames, up to
    // kHybridGpuFrames), the binned burst for alignment / guide / rejection / F6 (one field per frame), and the WhyCam-style RBF
    // kHybMergeMosaic over the raw mosaic itself.
    // DEFAULTS: mosaicPath 1 since P34 (Quad: the native merge, 2x faster than the split on the OPPO with +11-12 % 2-4 px detail at
    // matched noise on the handheld bursts; Tetra too since P35, mosaicTetra 1). The keys below only act with mosaicPath 1;
    // their defaults are the recommended point of the S0 reference (research/p29/S0_results.md section 4: full 7x7
    // window, kernel scale 1, edge scale 0.4, ks 1 / 0.85, no fill) except where noted, and the 6.1 base widening keeps the split's
    // rule (mosaicNativeWiden 0). The S1 parity point (the split's sites and kernel):
    // mosaicWindowFull 0, mosaicNativeEdgeScale 0.6 (= mosaicEdgeScale), mosaicKernelRB 1, mosaicKernelScale 1,
    // mosaicNativeFlatScale 1, mosaicNativeClamp 0. P34 (research/p29/QUAD_COMBINED.md): kernel scale 0.7, flat scale 2.4,
    // eigenvalue clamp in daylight, kernel scale 1 / edge scale 0.6 at night; WhyCam's point is kernel scale 0.5, edge scale 0.5
    // (isz2: WhyCam's noise with 21 frames against its 30, 0.96 of its poster detail).
    // Path 1 merges without the colour-difference (chromaDiff), clip-border (rimRatio) and Bento colour passes: they re-sample the
    // binned base at the binned positions, half a native px (Quad) off the native merge's output, and correct it with sums of the
    // binned base the native merge never added (P29 review). They come back only as native variants of those passes.
    int mosaicPath=1;
    // P195 (owner, 2026-10-17: «добавь так же возможность отключать наш 2х2 и 4х4 ремозаик чтобы получать фото без него»): 1 (default)
    // = our remosaic, every route above unchanged; 0 = no remosaic: every Quad / Tetra colour block is binned into one plain Bayer
    // site (the site-gained, clip-aware block mean the native path builds for its alignment) and the binned burst is merged by the
    // regular Bayer merge (hybridReconstructMosaicBinned: Sabre, rejection, Bento, Shasta, the colour passes) at W/b x H/b, or on
    // its Sabre 2x grid (2W/b x 2H/b) when the app asks for 2x. Only with the block of the request header (the app sized its result
    // for it) or a block forced by tuning (replays), on a frame of whole 2b x 2b periods (hybridMosaicBinnable); otherwise the
    // remosaic stays (reported).
    int mosaicRemosaic=1;
    // P215 (owner, X200 Ultra DNGs of 2026-10-18: «при вытягивании теней есть пастеризация»): 1 (default) = the merged DNG's 16-bit
    // codes get a deterministic triangular dither of +-1 code before rounding (hybridWriteMergedDng): its merged shadows are cleaner
    // than one code step, so a raw converter's shadow push showed the steps as bands; 0 = plain rounding as before. The RGB the
    // merge returns is not touched.
    int dngDither=1;
    // P267 «Наш ремозаик» off (the app's dngMosaic 1): a Quad / Tetra merge writes its merged RAW in the sensor's mosaic layout
    // (each site its own colour of the b x b block) instead of the plain Bayer one: the merged mosaic for an outside remosaic
    int dngMosaic=0;
    int mosaicWindow=3;          // half-width r of the native window (Quad: native px; 1..6). mosaicWindowFull 1: the (2r+1)^2 sites
                                 // around the nearest site (S0's full window, no Chebyshev clip); 0: as the split, |d| <= r with the
                                 // 6.1 window, d in (-4r/3, 4r/3] without it. Quad 3 with mosaicWindowFull 0 = parity with the split
                                 // (its 6.1 window +-1.5 sub-frame px = +-3 px, its two lattice sites per axis and phase = (-4, 4]);
                                 // 2 = WhyCam's 5x5. Tetra T1 (P35): 2r sensor px, at most 8 (3: 13x13; 3 with mosaicWindowFull 0 =
                                 // the split's sites)
    int mosaicWindowFull=1;      // S0: es 0.4 with the full 7x7 window had 10 % less flat noise than the clipped one at equal detail
    float mosaicKernelScale=1.0f; // native kernel precision = binned precision / (b s)^2: 1 = the split's physical kernel (its sigmas
                                 // were in sub-frame px = b native px); 1/b = WhyCam (Quad 0.5). P34: 0.7 with the flat scale 2.4 and
                                 // the eigenvalue clamp below (daylight; mosaicNativeNightKernelScale at night): isz2 detail +12 % at
                                 // the split's noise, hh_1 / hh_2 / hh_4 +11 % (2-4 px band at matched noise). P191: 1.0 - on the
                                 // owner's handheld bursts 0.7 left a diagonal lattice the sharpening amplified (1.0: half of it)
    // mosaicEdgeScale of the native path (across the edge and the base kernel; along the edge and flat areas stay). Its own key so that
    // the split keeps 0.6. S0 (synthetic Quad, 16 frames): 0.4 gave zone plate +1.0, bars +1.4 / +3.2, edges +1.2 dB at unchanged
    // flat noise; 0.3 more detail but +57 % colour error on neutral edges. Device (OPPO, real handheld Quad hand.nch): 0.6 gave
    // +49 % 2-4 px detail at matched flat noise and half the flat phase amplitude of the split, 0.4 0.94x with x1.6 chroma HF: 0.6.
    float mosaicNativeEdgeScale=0.6f;
    // P34: the native path's blurred (flat-area) and along-edge kernels, x sigma (> 1 = wider); across / base follow
    // mosaicNativeEdgeScale, all of them mosaicKernelScale
    // P34 isz2 (against WhyCam's DNG): the flat kernel x1.4 at WhyCam's kernel scale 0.5 took the wall's noise from 1.40 to 1.07
    // at 0.955 -> 0.917 poster detail (the kernel scale alone: 0.6 -> 1.15 / 0.91); 2.4 with kernel scale 0.7 and edge scale 0.6
    float mosaicNativeFlatScale=2.4f,mosaicNativeAlongScale=1.f;
    // P34: the native kernel and edge scales at night (6.1 night key, key <= s61MaxKey; 0 = the daylight values above). The night
    // guide (no daylight noise damping) puts more of a dim scene on the texture kernels, and narrowed there the R / B blocks of the
    // Quad lattice show through (arena11, key 25: a chroma lattice ratio of 263 in texture against 13 for the split).
    float mosaicNativeNightKernelScale=1.f,mosaicNativeNightEdgeScale=0.6f;
    // Tetra (block 4) at the 6.1 night key: kernel scale, edge scale and a multiplier on mosaicNativeFlatScale (0 = the Quad night keys /
    // no multiplier; the flat product is clamped to 4). The Quad night point (kernel 1 / edge 0.6 / flat 2.4) lost 8-19 % of the 4-8 px
    // band of the owner's real 10x Tetra bursts (vivo X200 Ultra, ISO 773 / 423 / key 28) against the split at 1/11-1/13 of its noise.
    // Sweep (kernel 0.7-1, edge 0.4-0.6, flat 1.8-4; scratchpad qual/): the flat kernel buys back noise and the synthetic lattice
    // without touching texture, the edge scale moves detail the most per lattice. Edge 0.4 with the flat kernel x4: 4-8 px band
    // +19 / +8 / +13 % (0.87 / 0.86 / 0.88 of the split), noise 0.13 / 0.14 / 0.10 of the split's (was 0.08-0.09), flat phase
    // amplitude <= 0.05 % (split 0.22-1.4 %); syn_b4 at device noise: background lattice ratio 13.9 (noise level; kernel 0.7 or edge
    // 0.4 with kernel 0.9: 21-26), zone plates +0.2 dB, false colour -9 %, 4-8 px transfer +22 %.
    float mosaicTetraNightKernelScale=1.f,mosaicTetraNightEdgeScale=0.4f,mosaicTetraNightFlatScale=1.6667f;
    // P73: Tetra T1 (block 4) kernel scale by day (0 = mosaicKernelScale). The native precision is the binned one / (b s)^2, so the
    // shared 0.7 made the Tetra kernel twice as wide in sensor px as the Quad one (b 4 against 2): 1 frame of a natural scene kept 2 %
    // of the 0.12-0.2 cyc/px band, 8 frames 59 % (plain Bayer 96 %). 0.35 = the Quad kernel in sensor px. Synthetic natural scene
    // (tools/quad gen_mosaic_burst, daylight noise, 8 frames, OPPO): luma PSNR 33.2 -> 38.2 dB, band transfer 0.59 / 0.29 / 0.10 ->
    // 0.87 / 0.69 / 0.48 (0.12-0.2 / 0.2-0.3 / 0.3-0.42 cyc/px); synthetic chart: neutral false colour 0.037 -> 0.017, edges
    // +1.5 dB, background 75.4 -> 70.6 dB (Quad 66.6, plain Bayer 62.5), lattice 3.3 -> 4.8. Night (6.1 night key) keeps the
    // Tetra night point above.
    float mosaicTetraKernelScale=0.35f;
    int mosaicNativeClamp=2;     // P34: WhyCam's covariance packing range on the native kernel (across-edge sigma >= 0.242 px):
                                 // 1 per component as WhyCam, 2 on the eigenvalues (orientation kept; syn_b2 B/W edge colour
                                 // 0.0137 against 0.0195 for 1 at edge scale 0.5, 0.0126 for the split). Tetra (P35): the range in
                                 // sigma x2 (4x4 blocks: across-edge sigma 0.48..3.96 px)
    // S2: kernel per colour, WhyCam's ks (multipliers on the distance, < 1 = wider): green / red-blue. 1 / 1 = parity (one kernel
    // for every colour, as the split); WhyCam 1.0 / 0.85 (R / B 1.18x wider: a colour with a quarter of the sites), S0's choice
    // (false colour on bars -7 %)
    float mosaicKernelG=1.f,mosaicKernelRB=0.85f;
    // S5: per-frame R / B fill (M5b) before the frame weight: 0 off (parity), 1 WhyCam's colour difference of the 3x3 block means
    // (a colour whose kernel weight in the frame is below mosaicFillSupport x the green weight is topped up to it with G + (R - G)).
    // Needed with narrow native kernels (WhyCam's point, mosaicKernelScale 1/b); 2 (v11 directional) is step S7, not built.
    int mosaicChromaFill=0;
    float mosaicFillSupport=0.25f;
    // S8 / P35 Tetra route with mosaicPath 1: 1 (default since P35) = T1: the Tetra mosaic merged natively (block 4,
    // kHybMergeMosaicFast on the 2x2 cells of the 4x4 colour blocks, window 2 x mosaicWindow sensor px, every frame of the budget);
    // 0 = the sub-frame split as with mosaicPath 0 (16 sub-frames per frame, at most 8 frames); 2 = T2: every 2x2 sub-block of a
    // Tetra block, gained and clip-aware, is one site of a true Quad mosaic at W/2 x H/2 merged by the Quad pass, output bilinear 2x
    // to the sensor grid (on syn_b4 it lost the whole 4-8 px band, transfer 0.039 -> 0.000). P35:
    // T1 against the split on the vivo X200 Ultra (Adreno 830): synthetic Tetra +1.9..+4.7 dB on zone plates / edges / bars,
    // neutral false colour / 4..6, lattice and phase bias lower; at device noise (x6, 4 % site gains) flat noise -46 %; real 10x
    // ISZ Tetra burst 17 frames merged in less time than the split's 8. Clean daylight on the round-4 kernel: flat noise x1.19 of the
    // split's (window-limited; mosaicWindow 4 = 17x17 sites: x1.11, +25 % merge time).
    int mosaicTetra=1;
    // 6.1 base widening rule of the native merge (s61Mode bit 4; F = the accepted donor frames of the output pixel): 0 (default) =
    // the split's rule b^2 F + b^2 - 1 < widenBelow (each frame counted as its b^2 sub-frames plus the base's own b^2 - 1 sub-frames:
    // Quad widens only below F = 0.25, T1 never; the split itself widens only there, so 0 keeps today's moving areas); 1 = S0's rule
    // F < widenBelow (research/p29/S0_results.md section 4, m5ref.py). S0's 1- / 3-frame numbers hold for 1 only. Host replays of
    // the synthetic Quad (binned passes off), 1 against 0: 3 frames bars +3.0 / +2.9 dB, edges +1.7 dB, background +0.5 dB, but
    // more colour on the B/W edge (0.022 -> 0.030) and flat phase bias 0.001 -> 0.004 %; 16 frames +0.05..+0.1 dB, nothing worse.
    // The device sweep (hand, its moving areas) decides.
    // 2 (default since 8 October 2026): smooth widening by the accepted donor frames F of the output pixel, base sigma x
    // mix(M, 1, smoothstep(0, K, F)) with M / K = mosaicNativeWidenMul / mosaicNativeWidenFrames (Quad) or mosaicTetraWidenMul /
    // mosaicTetraWidenFrames (Tetra), whatever s61Mode says. Rules 0 / 1 left the base alone where a moving object rejected the
    // donors: its narrow native kernel on the sparse colour blocks of one frame drew the block lattice ("honeycomb" around a moving
    // boat on the vivo 4x ISZ Tetra shot, a finer 2 px pattern on Quad 2x ISZ). Sweep on synthetic moving-object bursts:
    // research/moving-objects/MOVING_OBJECTS_REPORT.md.
    int mosaicNativeWiden=2;
    float mosaicNativeWidenMul=2.5f,mosaicNativeWidenFrames=4.f,mosaicTetraWidenMul=3.f,mosaicTetraWidenFrames=8.f;
    // P34 diagnostics: 1 = the generic native merge kHybMergeMosaic also for Quad with the full window (where kHybMergeMosaicFast,
    // the same merge on whole colour blocks, runs by default)
    int mosaicGeneric=0;
    // P197 (owner, 2026-10-17: «Сделай для нативной мозаики ту же цветовую проверку Bento, что у 12 Мп»): the ultrashort frame of
    // a mosaic burst (native, split, binned) against the base row band by row band and colour (rolling-shutter flicker), corrected
    // when a band departs by mosaicUsRowsMin or more, refused when its ratios stay inconsistent (hybridUsRowsCheck). 0 = off.
    int mosaicUsRows=1;
    // 8 %: the static owner burst own_114335 (no flicker) shows a +-2..3 % band structure and 5.3 % in its last band (frame edge),
    // the synthetic flicker +-25 % and the owner's flicker shot a gain MAD of 26.9 %
    float mosaicUsRowsMin=0.08f;
    // P290 (owner, 2026-10-20: stepped contours on a sheet of paper lit by a desk lamp, blocky colour along an echo sounder's bright
    // lines): the ultrashort frame against the base tile by tile (hybridUltrashortLocal) after the row profile - lamps on other
    // flicker phases, a screen's backlight. 0 = off (the row profile alone, as before).
    int usLocal=1;
    // P313 (owner, 2026-10-21: vivo X200 Ultra 2x ISZ in daylight, «все фото в области светов очень мыльное и с кучей артефактов»):
    // the tile check above compared the two frames where they lie on the sensor, and a hand-held ultrashort frame is shifted against
    // the base (16 x 4 sensor px on that shot): the tiles along a clipped highlight's edges saw other content in the two frames and
    // read x0.52..1.79, the decision (the largest factor near the clip) corrected a frame the row check had found clean. 1 (default)
    // = the tiles measured at the frames' global translation (hybridUsGlobalShift) and decided against the frame's own level; 0 = as
    // P290 (no shift, the factors against the metadata ratio).
    int usLocalAlign=1;
    // P219 (owner, 2026-10-17: «сделай так чтобы брались все кадры»): the native merge's frame pick (hybridMosaicNativePick). 1
    // (default) = every ultrashort frame of the burst (up to bentoFrames, at most 3, within x1.3 of the shortest) and every bracketed
    // frame within the budget: the first ultrashort and the first bracketed frame ahead of the N frames, a further ultrashort frame
    // where that lowers the noise (the base's clipped share), the others in what the N frames leave; 0 = one ultrashort and one
    // bracketed frame (the pick before P219).
    int mosaicNativePick=1;
    // P313 (owner, 2026-10-21, vivo X200 Ultra 2x ISZ: «все фото в области светов очень мыльное и с кучей артефактов»): on the native
    // mosaic merge Bento's mask took the N frames and the base down by (1 - m) in every mask cell and gave the cell to the ultrashort
    // frame's wide isotropic kernel (sigma ~2 native px): on the ISZ burst the merge's high-pass at 0.70..0.97 of the clip was 100 %
    // the ultrashort frame's, which carries 0.21..0.53 of the N frames' detail (cells the N frames never clipped), and a moving flag
    // came out twice (its ultrashort position over the N one). mosaicUsFill 1 (fast native merge with Bento): the merge runs as without
    // Bento (the N frames and the base at full weight, the ultrashort frames left out) and a pass after it (NAT_US_PASS) mixes the
    // ultrashort colour in by the merge's own clipped share e of its kernel weight (the largest of R, G, B): one share
    // smoothstep(0, mosaicUsFillShare, e) x Bento's weight of the cell (mask x the frame's check) for R, G and B, raising only
    // (max with the merged colour, which is at least the clipped samples' lower bound). Needs the clip flags (the merge leaves e
    // there). 0 = as before (replays unchanged).
    int mosaicUsFill=0;
    float mosaicUsFillShare=0.5f;
};

// restoreSensorOrigin() of scam-capture.h for a grid scaled by `scale` (CFA phase shift in output pixels).
/**
 * P165 (owner, 2026-10-16: «чтобы ультракороткие и длинные кадры были частью склейки у рав файлов», «Шумодава в раве просто не
 * должно быть»): the merged Bayer RAW of the DNG, 16 bit. The merge's level of every sensor Bayer site ({@code sample(x, y, c)},
 * 1 = the N frames' white) is written over the headroom: the merge's highest level, 1 when nothing is above the N clip (as
 * before), at most 16. The highlights Bento took from the ultrashort frames (the merged RGB reaches 3-10x N's white there) were
 * clipped at 1 in the former 14-bit DNG; the app writes BaselineExposure log2(headroom) so a raw converter shows the DNG as
 * bright as before. Nothing but the merge is in it: no denoise.
 */
/** P215: the highest level a merged site may legitimately reach, in N-white units: 1 / the TET ratio of the shortest ultrashort
 * frame (Bento: x8 at 1/8 TET; the measured ratio after hybridApplyMeasuredGains), 1 without one; at most 16. */
inline float hybridDngCeiling(const HybridInput& input){
    float ceiling=1.f;
    for(const HybridFrame& f:input.frames)
        if(f.role==kRoleUltrashort&&f.exposure>0.f&&std::isfinite(f.exposure))ceiling=std::max(ceiling,1.f/f.exposure);
    return std::min(ceiling,16.f);
}
/** P215: triangular dither in (-1, 1) code, a fixed function of the site (the same DNG for the same merge). */
inline float hybridDngDither(int x,int y){
    auto mix=[](uint32_t v){v^=v>>16;v*=0x7feb352dU;v^=v>>15;v*=0x846ca68bU;v^=v>>16;return v;};
    const uint32_t a=mix(uint32_t(x)*0x9E3779B1U^mix(uint32_t(y)+0x632BE5ABU)),b=mix(a^0x85EBCA77U);
    return float(a>>8)*(1.f/16777216.f)+float(b>>8)*(1.f/16777216.f)-1.f;
}
template<class Sample>
inline float hybridWriteMergedDng(std::vector<uint16_t>& dng,int w,int h,const HybridInput& input,const Sample& sample,bool dither=false,
                                  int block=1) {
    // P267: block > 1 - the sensor's Quad / Tetra layout (the colour of the b x b block the site is in, the CFA order of the blocks)
    const auto colour=[&input,block](int x,int y){
        x/=block;y/=block;
        int cx=x-(input.cfa&1),cy=y-(input.cfa>>1);if(cx<0)cx+=2;if(cy<0)cy+=2;
        const int phase=((cy&1)<<1)|(cx&1);return phase==0?0:phase==3?2:1;
    };
    // P215 (owner's X200 Ultra DNGs of 2026-10-18: purple highlights and banded shadows when pushed): the headroom was the highest
    // site of the whole frame (at most 16). A few sites above the merge's real ceiling (G overshoot next to clipped lamps: 99-167
    // sites at >= 16 x N white on 12.6 MP) set it to 16, while every highlight that clipped even in the ultrashort frame sat at its
    // ceiling, 8 x: code 34896 of 65535 in all three channels, far under WhiteLevel 65535. A raw converter took those areas for
    // unclipped grey, its white balance (R x 2.5, B x 1.6) made them purple, and the N range kept one bit less (shadow bands).
    // Now no site exceeds the ceiling of hybridDngCeiling, the headroom is the highest site up to it, and a site at the ceiling is
    // written at 65535 = WhiteLevel: the clipped highlights are clipped for the raw converter too.
    const float ceiling=hybridDngCeiling(input);
    std::mutex peakLock;float peak=1.f;
    mergeRowBands(h,[&](int y0,int y1){
        float local=1.f;
        for(int y=y0;y<y1;++y)for(int x=0;x<w;++x){const float v=std::min(sample(x,y,colour(x,y)),ceiling);if(v>local&&std::isfinite(v))local=v;}
        std::lock_guard<std::mutex> hold(peakLock);peak=std::max(peak,local);
    });
    const float headroom=std::min(peak,ceiling),k=65535.f/input.white;
    dng.assign(size_t(w)*h,0);
    mergeRowBands(h,[&](int y0,int y1){
        for(int y=y0;y<y1;++y)for(int x=0;x<w;++x){
            // P215: a site within 1e-4 of the ceiling is the clip itself (the merged plateau of a highlight clipped in every frame
            // comes out at 7.9996..8 of 8 after the weights): written at WhiteLevel, not one code under it
            const float v=sample(x,y,colour(x,y));
            const float m=!std::isfinite(v)?0.f:headroom>=ceiling&&v>=ceiling*0.9999f?1.f:std::clamp(v/headroom,0.f,1.f);
            const float black=input.black[((y&1)<<1)|(x&1)];
            // P215: the dither stays off a clipped site (exactly WhiteLevel) and keeps every code inside 0..65535
            const float code=(black+m*(input.white-black))*k+(dither&&m<1.f?hybridDngDither(x,y):0.f);
            dng[size_t(y)*w+x]=uint16_t(std::clamp(std::lround(code),0L,m<1.f?65534L:65535L));
        }
    });
    return headroom;
}

inline std::string hybridDngReport(float headroom,int w,int h,float ceiling=0.f,bool dither=false){
    char line[220];
    std::snprintf(line,sizeof(line),"HYBRID DNG: merged Bayer %dx%d, 16-bit, headroom=%.4f (N white at 1/%.3f), ceiling=%.3f%s, no denoise",
                  w,h,headroom,headroom,ceiling,dither?", dithered":"");
    return line;
}

inline void shiftOrigin(std::vector<float>& v,int w,int h,int channels,int dx,int dy) {
    if(dx==0&&dy==0)return;
    // P30: bands of rows on all cores, each bottom-up as before; the dy rows above a band (another band writes them) are read
    // from a copy taken first. Every pixel gets the same source value as in the single pass.
    const int threads=std::max(1,std::min(8,int(std::thread::hardware_concurrency())));
    const int bands=std::max(1,std::min(threads,h/std::max(64,4*dy+4)));
    const size_t row=size_t(w)*channels;
    std::vector<std::vector<float>> above(bands);
    for(int b=1;b<bands;++b){
        const int y0=int(int64_t(h)*b/bands),from=std::max(0,y0-dy);
        above[b].assign(v.begin()+ptrdiff_t(size_t(from)*row),v.begin()+ptrdiff_t(size_t(y0)*row));
    }
    std::vector<std::thread> pool;
    auto band=[&](int b){
        const int y0=int(int64_t(h)*b/bands),y1=int(int64_t(h)*(b+1)/bands),from=std::max(0,y0-dy);
        for(int y=y1-1;y>=y0;--y){
            const int sy=std::max(0,y-dy);
            const float* srcRow=sy>=y0||b==0?v.data()+size_t(sy)*row:above[b].data()+size_t(sy-from)*row;
            float* dstRow=v.data()+size_t(y)*row;
            for(int x=w-1;x>=0;--x){
                const size_t src=size_t(std::max(0,x-dx))*channels,dst=size_t(x)*channels;
                for(int c=0;c<channels;++c)dstRow[dst+c]=srcRow[src+c];
            }
        }
    };
    for(int b=1;b<bands;++b)pool.emplace_back(band,b);
    band(0);
    for(auto& t:pool)t.join();
}

inline int hybridRequestedGrid(const HybridInput& input,const HybridTuning& tune) {
    if(input.strict2x)return input.grid;
    return tune.grid==4||tune.grid==8?tune.grid:tune.grid>0?std::min(tune.grid,2):std::max(1,std::min(input.grid,2));
}

inline bool hybridAuto2x(const HybridInput& input,const HybridTuning& tune,bool fine2x) {
    return tune.auto2x>0&&fine2x&&!input.strict2x;
}

inline HybridTuning loadHybridTuning(const std::string& jobDir,const std::function<void(const std::string&)>& report) {
    HybridTuning t;
    const std::string paths[]={jobDir+"/hybrid_tuning.txt","/sdcard/Download/SCAMERA/hybrid_tuning.txt","/data/local/tmp/hybrid_tuning.txt"};
    for(const std::string& path:paths){
        std::ifstream f(path);if(!f)continue;
        std::string key;float v;std::string applied;
        auto set=[&](const char* name,float* target,int* itarget=nullptr){
            if(key!=name)return false;
            if(target)*target=v;if(itarget)*itarget=int(v);
            applied+=" "+key+"="+std::to_string(v);return true;
        };
        while(f>>key>>v){
            set("cdm",&t.cdm)||set("boost",&t.boost)||set("boostEnable",&t.boostEnable)||set("rejPrior",&t.rejPrior)||set("varianceThreshold",&t.varianceThreshold)
            ||set("motionThreshold",&t.motionThreshold)
            ||set("filterVariance",&t.filterVariance)||set("dilateOffset",&t.dilateOffset)||set("dilateScale",&t.dilateScale)||set("dilateFloor",&t.dilateFloor)
            ||set("clipLevel",&t.clipLevel)||set("fwe",&t.fwe)||set("weightCap",&t.weightCap)||set("lutLo",&t.lutLo)||set("lutHi",&t.lutHi)
            ||set("lutHiSigma",&t.lutHiSigma)||set("kernelScale",&t.kernelScale)||set("widenBelow",&t.widenBelow)||set("widenMul",&t.widenMul)
            ||set("kernelFloor",&t.kernelFloor)||set("chromaDiff",&t.chromaDiff)||set("chromaDiffClamp",nullptr,&t.chromaDiffClamp)||set("rawTensor",&t.rawTensor)||set("rawNoise",&t.rawNoise)||set("bento",nullptr,&t.bento)
            ||set("bentoHighlight",&t.bentoHighlight)||set("bentoDilate",nullptr,&t.bentoDilate)||set("bentoSmooth",&t.bentoSmooth)
            ||set("grid",nullptr,&t.grid)||set("bentoMinClipped",&t.bentoMinClipped)||set("bentoMaxUsClipped",&t.bentoMaxUsClipped)||set("bentoNearClip",&t.bentoNearClip)||set("bentoMaxHole",nullptr,&t.bentoMaxHole)
            ||set("bentoRamp",&t.bentoRamp)||set("bentoRampLo",&t.bentoRampLo)||set("bentoRampHi",&t.bentoRampHi)||set("bentoRampReach",nullptr,&t.bentoRampReach)
            ||set("bentoBlend",nullptr,&t.bentoBlend)||set("bentoBlendLo",&t.bentoBlendLo)||set("bentoBlendHi",&t.bentoBlendHi)
            ||set("bentoBlendSigma",&t.bentoBlendSigma)||set("bentoBlendReject",&t.bentoBlendReject)
            ||set("bentoBlendPixLo",&t.bentoBlendPixLo)||set("bentoBlendPixHi",&t.bentoBlendPixHi)||set("bentoBlendFill",&t.bentoBlendFill)||set("bentoBlendDebug",nullptr,&t.bentoBlendDebug)||set("bentoBlendValidSigma",&t.bentoBlendValidSigma)
            ||set("bentoBlendSat",&t.bentoBlendSat)||set("bentoBlendGateLo",&t.bentoBlendGateLo)||set("bentoBlendGateHi",&t.bentoBlendGateHi)
            ||set("bentoBlendGateSigma",&t.bentoBlendGateSigma)||set("bentoBlendGainRadius",&t.bentoBlendGainRadius)
            ||set("bentoBlendGainSupport",&t.bentoBlendGainSupport)||set("bentoBlendGainMax",&t.bentoBlendGainMax)
            ||set("bentoBlendGainLo",&t.bentoBlendGainLo)||set("bentoBlendGainFlickerLo",&t.bentoBlendGainFlickerLo)
            ||set("bentoBlendGainFlickerHi",&t.bentoBlendGainFlickerHi)
            ||set("bentoBlendChroma",&t.bentoBlendChroma)
            ||set("bentoBlendClipLo",&t.bentoBlendClipLo)||set("bentoBlendClipHi",&t.bentoBlendClipHi)||set("bentoBlendNear",&t.bentoBlendNear)
            ||set("bentoBlendFar",&t.bentoBlendFar)||set("bentoBlendDilate",nullptr,&t.bentoBlendDilate)
            ||set("bentoUsWeight",&t.bentoUsWeight)||set("bentoUsSigma",&t.bentoUsSigma)||set("bentoFrames",nullptr,&t.bentoFrames)||set("bentoValidate",nullptr,&t.bentoValidate)||set("bentoMotionMax",&t.bentoMotionMax)
            ||set("bentoChromaSigma",&t.bentoChromaSigma)||set("bentoChroma",&t.bentoChroma)||set("dayKernelScale",&t.dayKernelScale)||set("shastaSharpness",&t.shastaSharpness)||set("shastaSat",&t.shastaSat)||set("shastaMaxRatio",&t.shastaMaxRatio)
            ||set("shastaEnable",nullptr,&t.shastaEnable)||set("shastaForce",nullptr,&t.shastaForce)||set("shastaMotionMax",&t.shastaMotionMax)||set("snr",nullptr,&t.snrFixed)||set("snrScale",&t.snrScale)||set("debugFrame",nullptr,&t.debugFrame)
            ||set("cellClip",nullptr,&t.cellClip)||set("staticShift",&t.staticShift)||set("hotSigma",&t.hotSigma)||set("hotFrames",nullptr,&t.hotFrames)||set("hotBaseSigma",&t.hotBaseSigma)
            ||set("hotCross",&t.hotCross)||set("hotMaxLevel",&t.hotMaxLevel)||set("hotCap",&t.hotCap)||set("bentoScam",nullptr,&t.bentoScam)||set("bentoInvalid",&t.bentoInvalid)||set("bentoInpaintMiddle",&t.bentoInpaintMiddle)
            ||set("bentoInpaintMin",&t.bentoInpaintMin)||set("clipFlags",nullptr,&t.clipFlags)||set("sabre61",nullptr,&t.sabre61)||set("s61Fine",&t.s61Fine)
            ||set("s61TensorNoise",&t.s61TensorNoise)||set("s61GdNoise",&t.s61GdNoise)||set("s61MinMotion",&t.s61MinMotion)
            ||set("s61DayTensorNoise",&t.s61DayTensorNoise)||set("s61DayGdNoise",&t.s61DayGdNoise)||set("s61Mode",nullptr,&t.s61Mode)||set("s61MaxKey",&t.s61MaxKey)||set("hotMaxKey",&t.hotMaxKey)||set("subset",nullptr,&t.subset)||set("profile",nullptr,&t.profile)
            ||set("rimRatio",nullptr,&t.rimRatio)||set("rimSigma",&t.rimSigma)||set("rimLo",&t.rimLo)||set("rimHi",&t.rimHi)||set("rimStride",nullptr,&t.rimStride)
            ||set("localAlign",nullptr,&t.localAlign)||set("laWin",nullptr,&t.laWin)||set("laStride",nullptr,&t.laStride)||set("laIters",nullptr,&t.laIters)
            ||set("laItersCoarse",nullptr,&t.laItersCoarse)||set("laMu",&t.laMu)||set("laKappa",&t.laKappa)||set("laMaxShift",&t.laMaxShift)
            ||set("laMedian",nullptr,&t.laMedian)||set("laUltrashort",nullptr,&t.laUltrashort)||set("laThreads",nullptr,&t.laThreads)||set("laStream",nullptr,&t.laStream)||set("laGpu",nullptr,&t.laGpu)||set("laScam",nullptr,&t.laScam)||set("isoKernel",nullptr,&t.isoKernel)
            ||set("mosaicBlock",nullptr,&t.mosaicBlock)||set("mosaicGain",nullptr,&t.mosaicGain)||set("mosaicGainField",nullptr,&t.mosaicGainField)||set("mosaicGainZones",nullptr,&t.mosaicGainZones)||set("mosaicUsSmooth",nullptr,&t.mosaicUsSmooth)||set("plainChromaMedian",nullptr,&t.plainChromaMedian)||set("plainChromaHand",&t.plainChromaHand)||set("baseDemosaic",&t.baseDemosaic)||set("baseDemosaicAniso",&t.baseDemosaicAniso)||set("baseDemosaicAlias",&t.baseDemosaicAlias)||set("baseDemosaicBayerLo",&t.baseDemosaicBayerLo)||set("baseDemosaicBayerHi",&t.baseDemosaicBayerHi)||set("baseDemosaicDetailLo",&t.baseDemosaicDetailLo)||set("baseDemosaicDetailHi",&t.baseDemosaicDetailHi)||set("bentoSubpixel",nullptr,&t.bentoSubpixel)||set("mosaicRobustBilinear",nullptr,&t.mosaicRobustBilinear)||set("mosaicFrames",nullptr,&t.mosaicFrames)||set("seriesFrames",nullptr,&t.seriesFrames)||set("mosaicChroma",nullptr,&t.mosaicChroma)||set("mosaicShare",nullptr,&t.mosaicShare)||set("mosaicEdgeScale",&t.mosaicEdgeScale)
            ||set("caCorrect",nullptr,&t.caCorrect)||set("caMinShift",&t.caMinShift)
            // P29 native mosaic path
            ||set("mosaicNative2x",nullptr,&t.mosaicNative2x)||set("mosaicTetraWindow2x",nullptr,&t.mosaicTetraWindow2x)||set("mosaicPath",nullptr,&t.mosaicPath)||set("mosaicRemosaic",nullptr,&t.mosaicRemosaic)||set("mosaicWindow",nullptr,&t.mosaicWindow)||set("mosaicKernelScale",&t.mosaicKernelScale)
            ||set("mosaicWindowFull",nullptr,&t.mosaicWindowFull)||set("mosaicNativeEdgeScale",&t.mosaicNativeEdgeScale)
            ||set("mosaicNativeFlatScale",&t.mosaicNativeFlatScale)||set("mosaicNativeClamp",nullptr,&t.mosaicNativeClamp)
            ||set("mosaicNativeNightKernelScale",&t.mosaicNativeNightKernelScale)||set("mosaicNativeNightEdgeScale",&t.mosaicNativeNightEdgeScale)
            ||set("mosaicTetraNightKernelScale",&t.mosaicTetraNightKernelScale)||set("mosaicTetraKernelScale",&t.mosaicTetraKernelScale)||set("mosaicTetraNightEdgeScale",&t.mosaicTetraNightEdgeScale)
            ||set("mosaicTetraNightFlatScale",&t.mosaicTetraNightFlatScale)||set("mosaicNativeAlongScale",&t.mosaicNativeAlongScale)
            ||set("mosaicKernelG",&t.mosaicKernelG)||set("mosaicKernelRB",&t.mosaicKernelRB)
            ||set("mosaicChromaFill",nullptr,&t.mosaicChromaFill)||set("mosaicFillSupport",&t.mosaicFillSupport)
            ||set("mosaicTetra",nullptr,&t.mosaicTetra)||set("mosaicNativeWiden",nullptr,&t.mosaicNativeWiden)||set("mosaicGeneric",nullptr,&t.mosaicGeneric)
            ||set("mosaicNativeWidenMul",&t.mosaicNativeWidenMul)||set("mosaicNativeWidenFrames",&t.mosaicNativeWidenFrames)
            ||set("mosaicTetraWidenMul",&t.mosaicTetraWidenMul)||set("mosaicTetraWidenFrames",&t.mosaicTetraWidenFrames)
            ||set("mosaicUsRows",nullptr,&t.mosaicUsRows)||set("usLocal",nullptr,&t.usLocal)||set("usLocalAlign",nullptr,&t.usLocalAlign)||set("mosaicUsRowsMin",&t.mosaicUsRowsMin) // P197
            ||set("mosaicNativePick",nullptr,&t.mosaicNativePick) // P219
            ||set("mosaicUsFill",nullptr,&t.mosaicUsFill)||set("mosaicUsFillShare",&t.mosaicUsFillShare) // P313
            // P27
            ||set("gainMeasured",nullptr,&t.gainMeasured)||set("noiseMeasured",nullptr,&t.noiseMeasured)||set("chromaGpu",nullptr,&t.chromaGpu)||set("dngDither",nullptr,&t.dngDither)||set("dngMosaic",nullptr,&t.dngMosaic)
            ||set("chromaOverlap",nullptr,&t.chromaOverlap) // P213c
            ||set("auto2x",nullptr,&t.auto2x)||set("auto2xMaxGap",&t.auto2xMaxGap)||set("auto2xMinSnr",&t.auto2xMinSnr)
            ||set("mochi",nullptr,&t.mochi);
        }
        if(report&&!applied.empty())report("HYBRID TUNING FILE "+path+":"+applied);
        break;
    }
    return t;
}

// ---------------------------------------------------------------------------------------------
// GLSL. kCommonShader (scam-superres-gpu.h) provides the strip-uploaded frames, sampleRaw()
// (canonical RGGB coordinates, normalised by black/white, clipped at 1.0) and origin() (the backward
// homography of frame f at reference pixel (x,y)).
// ---------------------------------------------------------------------------------------------
// The common part of every hybrid program (kCommonShader of scam-superres-gpu.h without its mosaic sampling, which only
// SCAM HDR uses). P14b: the per-frame tables are one std140 uniform block instead of default-block arrays: those were limited to
// 32 frames (48 for fParam) by the uniform slots of the merge program, and a mosaic burst merges 4 sub-frames per frame.
static const char* kHybCommon=R"(#version 310 es
precision highp float;
precision highp int;
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=0) readonly buffer Frames{uint frames[];};
uniform ivec2 size;
uniform int frameCount;
// HybridGpu::kTable* offsets; [128] = kHybridGpuFrames
layout(std140,binding=0) uniform FrameTable{
    uvec4 frameGeo[128]; // x = first site of frame f in Frames, y = first sensor row held, z = rows held, w = upRatio (float bits)
    vec4 hA[128];
    vec4 hB[128];
    vec4 fParam[128];    // x = 1 / exposure ratio (brings frame f to base units), y = scalar weight A (0 = frame skipped),
                         // z = covariance multiplier 1/LUTsigma(A)^2, w = role (1 normal, 3 bracketed, 5 ultrashort)
    vec4 fNoiseP[128];   // xy = slope, offset of frame f in its own exposure
    ivec4 fCells[128];   // x = first donor cell row held for frame f (even frame row / 2), y = rows held, z = first cell in Cells / DCov
};
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
    uvec4 geo=frameGeo[f];
    int ry=clamp(y-int(geo.y),0,int(geo.z)-1);
    uint idx=geo.x+uint(ry*size.x+x);
    uint word=frames[idx>>1];
    uint v=(idx&1u)==0u?(word&0xFFFFu):(word>>16);
    int phase=((y&1)<<1)|(x&1);
    // Not clipped at black: the merges average signed noise, the result is clipped once (a clipped average kept the positive
    // bias of the cut-off negative noise: magenta haze in high-ISO shadows).
    return clamp((float(v)-black[phase])*inv[phase],-0.25,1.0);
}
vec2 origin(int f,int x,int y){
    vec4 a=hA[f],b=hB[f];
    float fx=float(x),fy=float(y);
    float den=b.z*fx+b.w*fy+1.0;
    vec2 p=vec2(a.x*fx+a.y*fy+a.z,a.w*fx+b.x*fy+b.y)/den;
    return p/uintBitsToFloat(frameGeo[f].w);
}
)";

static const char* kHybHelpers=R"(
uniform ivec4 phaseColor;
uniform vec2 baseNoise;      // slope, offset of the base frame (normalised units)
// Per-frame parameters (fParam, fNoiseP, fCells) are in the FrameTable block of kHybCommon.
uniform float clipLevel;
layout(std430,binding=5) buffer Cells{vec4 cells[];};    // donor cell colours (u domain) and clip flag, donor geometry
layout(std430,binding=14) buffer DCov{uvec2 dcov[];};    // Sabre 6.1 kernel precision of every donor cell (half floats)
uniform int chunkU;          // first row of this dispatch inside the pass (passes are split into short dispatches)
uniform int markU;           // 1: the uploaded RAW words carry site flags in bits 14 (outlier site) and 15 (its cell is clipped)
// sampleRaw() of kHybCommon that also returns the site flags (bit 0 outlier: no sample; bit 1 the 2x2 cell of this frame is
// clipped: every colour of it goes to the clipped mean). Without marking (white >= 16384) the words are plain.
float rawSite(int f,int x,int y,out uint fl){
    x+=cfaShift.x;y+=cfaShift.y;
    if(x<0||y<0||x>=size.x||y>=size.y){x=reflectCfa(x,size.x);y=reflectCfa(y,size.y);}
    uvec4 geo=frameGeo[f];
    int ry=clamp(y-int(geo.y),0,int(geo.z)-1);
    uint idx=geo.x+uint(ry*size.x+x);
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
// Sabre 6.1 kernel covariance (guide shader @0xe49bed, GenerateGaussCovariance) of cell (i,j) of frame f, from that frame's
// own RAW: quad luma Y = (sqrt r + sqrt g1 + sqrt g2 + sqrt b)/4 of the 3x3 quads, four diagonal gradient pairs /8 rotated by
// 45 degrees, Wiener-filtered strength (noise from the frame's own model in place of the 6.1 LUT), green-difference blur, 6.1
// sigma mixing. Returns P for kernelW() (exp2(-0.72135 d'Pd) == exp2(-0.5 d'Cd)), divided by kernelScale^2.
// F6 local alignment (scam-hybrid.h laFrameField): per-tile residual offset of every frame (RAW px) on top of its homography.
// Compiled only when the merge uses it (HybridGpu(.., localAlign)): without LOCAL_ALIGN the programs are the ones before F6. Every
// index stays inside the buffer for any uniform values: the compiler may execute the loads of a branch it does not take
// (if-conversion), and an out-of-bounds SSBO read faults the GPU (a phone reboot with an earlier draft).
#ifdef LOCAL_ALIGN
layout(std430,binding=15) readonly buffer LaFlow{vec2 laFlow[];}; // [frame][tile row][tile column]
layout(std430,binding=17) readonly buffer LaMotion{float laZ[];};  // v11 Z channel: local motion extent per tile (RAW px)
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
// Z channel of frame f at the cell whose origin is (x, y): the nearest tile (the extent already spans 3x3 tiles).
float laMotionAt(int f,int x,int y){
    int nx=max(laU.y,1),ny=max(laU.z,1);
    uint fb=uint(clamp(f,0,frameCount-1)*nx*ny);
    vec2 p=(vec2(float(x),float(y))+0.5-laG.xy)*laG.w;
    ivec2 i=clamp(ivec2(p+0.5),ivec2(0),ivec2(nx-1,ny-1));
    return f==0?0.0:laZ[fb+uint(i.y*nx+i.x)];
}
#else
#define originL(f,x,y) origin(f,x,y)
#endif
uniform vec4 k61aU; // covariance_parameters1: f5/f0 (shrunk), 1/(f0 f4) (stretched), f2 (gradient clip), 1/f0 (base); w = 0: off
uniform vec4 k61bU; // 1/(f0 f1) (blurred), 1/f3 (transition), 1/kernelScale^2, tensor noise multiplier
uniform vec4 k61cU; // x: green difference noise multiplier, y: > 0 the fine (green site) tensor, its noise x16 x this (P332)
// P76: quad luma Y and |sqrt g1 - sqrt g2| of quad (i,j) of frame f; kHybCells keeps them in shared memory for the 3x3 cells around.
vec2 sabreQuad61(int f,int i,int j){
    float g=fParam[f].x;int X=2*i,Z=2*j;
    float r=sqrt(max(sampleRawM(f,X,Z)*g,0.0)),g1=sqrt(max(sampleRawM(f,X+1,Z)*g,0.0));
    float g2=sqrt(max(sampleRawM(f,X,Z+1)*g,0.0)),b=sqrt(max(sampleRawM(f,X+1,Z+1)*g,0.0));
    return vec2(0.25*(r+g1+g2+b),abs(g1-g2));
}
vec3 sabreCov61Q(int f,float Y[9],float gd,float lum,vec3 fine);
// P332: the fine tensor of cell (i,j) of frame f from u (sqrt of the gained green sites of its 8x8 site window, 0 at the other
// colours; site (2i-3+x, 2j-3+y) at u[y*8+x]): every green site of the inner 6x6 against its (+1,+1) and (+1,-1) green neighbours,
// summed as sabreCov61Q's quad pairs (dx along (1,1), dy along (1,-1)) in its units (x4 for the half step, x0.5 for 4 pairs x 0.125).
vec3 sabreFine61U(int i,int j,float u[64]){
    float dxx=0.0,dyy=0.0,dxy=0.0,n=0.0;
    for(int y=1;y<7;y++)for(int x=1;x<7;x++){
        if(phaseColor[(((2*j-3+y)&1)<<1)|((2*i-3+x)&1)]!=1)continue;
        float c=u[y*8+x],du=u[(y+1)*8+x+1]-c,dv=u[(y-1)*8+x+1]-c;
        dxx+=du*du;dyy+=dv*dv;dxy+=du*dv;n+=1.0;
    }
    return vec3(dxx,dyy,dxy)*(2.0/max(n,1.0));
}
vec3 sabreFine61(int f,int i,int j){
    float g=fParam[f].x;float u[64];
    for(int y=0;y<8;y++)for(int x=0;x<8;x++){
        int X=2*i-3+x,Z=2*j-3+y;
        u[y*8+x]=phaseColor[((Z&1)<<1)|(X&1)]==1?sqrt(max(sampleRawM(f,X,Z)*g,0.0)):0.0;
    }
    return sabreFine61U(i,j,u);
}
vec3 sabreCov61(int f,int i,int j){
    float Y[9];float gd=0.0,lum=0.0;
    for(int dj=-1;dj<=1;dj++)for(int di=-1;di<=1;di++){
        vec2 q=sabreQuad61(f,i+di,j+dj);
        float w=(di==0?0.5:0.25)*(dj==0?0.5:0.25);
        Y[(dj+1)*3+di+1]=q.x;gd+=q.y*w;lum+=q.x*w;
    }
    return sabreCov61Q(f,Y,gd,lum,k61cU.y>0.0?sabreFine61(f,i,j):vec3(-1.0));
}
// The 6.1 precision from the 3x3 quad lumas Y and the weighted green difference gd / luma lum around the cell; fine.x >= 0: the
// structure tensor is the fine one (sabreFine61U) instead of the quad lumas'.
vec3 sabreCov61Q(int f,float Y[9],float gd,float lum,vec3 fine){
    float g=fParam[f].x;
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
    if(fine.x>=0.0){c=fine;c0=0.5*(c.x+c.y);c1=0.5*(c.y-c.x);nTensor*=16.0*k61cU.y;} // P332: single sites, half the step
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
uniform ivec4 hotU;          // x = frames in hotList, y = words of the mean: the sites' temporal variance follows (P214k; 0 = none)
uniform int ry0;
uniform int ry1;
void main(){
    int x=int(gl_GlobalInvocationID.x),r=chunkU+int(gl_GlobalInvocationID.y);
    if(x>=size.x||r>=2*(ry1-ry0)+4)return;
    int y=2*ry0-2+r;
    float s=0.0;
    for(int l=0;l<hotU.x&&l<16;l++)s+=sampleRawM(hotList[l],x,y);
    meanv[uint(r*size.x+x)]=s/float(max(hotU.x,1));
    if(hotU.y>0){ // P214k: the site's own variance over the hot frames, the real noise of the cap rank (the header model can be off)
        float m=s/float(max(hotU.x,1)),q=0.0;
        for(int l=0;l<hotU.x&&l<16;l++){float d=sampleRawM(hotList[l],x,y)-m;q+=d*d;}
        meanv[uint(hotU.y+r*size.x+x)]=q/float(max(hotU.x-1,1));
    }
}
)";
static const char* kHybFlags=R"(
layout(std430,binding=9) buffer Sums{uint sums[];};
layout(std430,binding=1) readonly buffer MeanBuf{float meanv[];};
// one word per site of the strip rows [2 ry0, 2 ry1) (canonical): bit 0 fixed-pattern outlier, bit 1 base transient outlier,
// bits 8..15 the rank of a fixed-pattern site for the plausibility cap (P214k, with hotU.z)
layout(std430,binding=2) writeonly buffer SiteFlags{uint sflags[];};
uniform ivec4 hotU;          // x = frames in the mean (0: no fixed-pattern test), y = bit 0 fixed-pattern test, bit 1 base transient test,
                             // z = first word of the cap block in Sums (P214k; 0 = no cap), w = first word of the temporal variance in
                             // MeanBuf (P214k; 0 = none: the rank takes the model noise)
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
float vAt(int x,int y){ // P214k: the temporal variance after the mean (hotU.w = its first word; 0 reads the mean: in the buffer)
    if(x<0||x>=size.x)x=reflectCfa(x,size.x);
    return meanv[uint(hotU.w+(y-2*ry0+2)*size.x+x)];
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
// P76: the 12x12 sites around the group's 8x8 sites (mean frame, base frame), read once per group (25 reads of each a site before).
shared float flagMean[144];
shared float flagBase[144];
void main(){
    int x=int(gl_GlobalInvocationID.x),r=chunkU+int(gl_GlobalInvocationID.y);
    int bx=int(gl_WorkGroupID.x)*8-2,br=chunkU+int(gl_WorkGroupID.y)*8-2;
    bool meanTest=hotU.x>=3&&(hotU.y&1)!=0,baseTest=(hotU.y&2)!=0;
    for(int k=int(gl_LocalInvocationIndex);k<144;k+=64){
        int tx=bx+k%12,tr=br+k/12;
        // rows of the strip +-2 only (the mean buffer holds no more): a row past them is read by no site of the strip
        tr=clamp(tr,-2,2*(ry1-ry0)+1);
        flagMean[k]=meanTest?mAt(tx,2*ry0+tr):0.0;
        flagBase[k]=baseTest?sampleRawM(0,tx,2*ry0+tr):0.0;
    }
    barrier();
    if(x>=size.x||r>=2*(ry1-ry0))return;
    int y=2*ry0+r;
    bool green=phaseColor[((y&1)<<1)|(x&1)]==1;
    uint bits=0u;
    float p[25];
    int t0=(r-br-2)*12+(x-bx-2);
    if(meanTest){ // hot and dead sites; sigma of the mean of n frames (the median of 8 adds ~15 %)
        for(int k=0;k<25;k++)p[k]=flagMean[t0+(k/5)*12+k%5];
        vec4 e=siteExcess(p,green);
        if(hotSigU.w<=0.0||e.w<hotSigU.w){
            float sd=sqrt(max((baseNoise.x*max(e.y,0.0)+baseNoise.y)/float(hotU.x),1.0e-14))*1.15;
            float ex=abs(e.x),cr=e.z*sign(e.x);
            if(ex>hotSigU.x*sd&&cr<hotSigU.z*ex&&e.y<0.9)bits|=1u; // a stuck (white) site counts too: its neighbours stay dark
            if((bits&1u)!=0u&&hotU.z>0){ // P214k: its rank for the plausibility cap (bits 8..15), against the noise measured around it
                vec2 m=hotU.w>0?capMeasured(capRing(x,y,green,1),vAt(x,y),float(hotU.x),sd):vec2(sd,0.0);
                bits|=capRank(p,green,ex,m.x,m.y)<<8u;
            }
        }
    }
    if(baseTest){
        for(int k=0;k<25;k++)p[k]=flagBase[t0+(k/5)*12+k%5];
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
        if((bits&1u)!=0u&&hotU.z>0)atomicAdd(sums[uint(hotU.z)+((bits>>8)&255u)],1u); // P214k: the strip's rank histogram
    }
}
)";

// P214k: the plausibility cap of the fixed-pattern test (HybridTuning::hotCap). kHybFlagRank goes before kHybFlags and kHybNatFlags
// (their 5x5 same-colour lattice p): the rank of a flagged site is its excess over the noise of the mean together with the spread
// of the 8 same-colour neighbours of its median (6th - 2nd of the 8 sorted, ~1.7 sigma of flat noise):
// z = ex / sqrt(sd^2 + (0.59 spread)^2), in bins of 1/16 octave (0..255 = z 1/16 .. 4096). sd: the noise measured around the
// site (capRing: the temporal variance of the 8 same-colour sites around it over the hot frames, which kHybMean / kHybNatMean write
// after the mean when the cap is on), not the header model (~4x low in variance on the X7U's 50 MP frames: a noise peak with a
// small spread then outranked a hot pixel); a site whose own temporal variance is far above its ring's blinks (RTS) and ranks
// high (capRank). A defect in a flat dark area keeps z ~ ex / (1.4 sd); a site in texture has the texture's spread in the
// denominator. The 185248 replay (50 MP, every strip over the cap): hot-pixel-like dots the cap let through, 31 ranked against
// the model noise, 16 against the measured noise, 11 with the blink rule too - warm pixels of ~5 DN10 inside ~2 DN10 texture, which
// rank as texture (z ~2 against the strips' cut ~2.6).
// The flag programs add every flagged site of the strip's own rows to a 256-bin histogram (Sums, hotU.z = its first word; 0 = no
// cap); kHybFlagCap (one invocation) turns it into the lowest rank kept within the strip's allowance and clears it for the next
// strip; kHybFlagPrune clears bit 0 of the sites ranked below. A strip within its allowance keeps every flag (rank 0 kept).
static const char* kHybFlagRank=R"(
#define RX(p,q) { float lo_=min(p,q); q=max(p,q); p=lo_; }
float rankSpread8(float a0,float a1,float a2,float a3,float a4,float a5,float a6,float a7){ // median8's network: all 8 sorted
    RX(a0,a2)RX(a1,a3)RX(a4,a6)RX(a5,a7) RX(a0,a4)RX(a1,a5)RX(a2,a6)RX(a3,a7) RX(a0,a1)RX(a2,a3)RX(a4,a5)RX(a6,a7)
    RX(a2,a4)RX(a3,a5) RX(a1,a4)RX(a3,a6) RX(a1,a2)RX(a3,a4)RX(a5,a6)
    return a6-a1;
}
float vAt(int x,int y); // the temporal variance of a site of the mean (each flag program; hotU.w = its first word)
// the summed temporal variances of the 8 same-colour sites of the median's ring (step b): the real noise around a flagged site,
// 56 degrees of freedom at 8 frames (~10 % on sigma). Not the site's own: a blinking (RTS) defect varies several times the noise
// from frame to frame (a Quad block of the 185248 burst: 53..87 DN10 over 17 frames, the ring 2 DN10).
float capRing(int x,int y,bool green,int b){
    return green?vAt(x-b,y-b)+vAt(x+b,y-b)+vAt(x-b,y+b)+vAt(x+b,y+b)+vAt(x-2*b,y)+vAt(x+2*b,y)+vAt(x,y-2*b)+vAt(x,y+2*b)
                :vAt(x-2*b,y)+vAt(x+2*b,y)+vAt(x,y-2*b)+vAt(x,y+2*b)+vAt(x-2*b,y-2*b)+vAt(x+2*b,y-2*b)+vAt(x-2*b,y+2*b)+vAt(x+2*b,y+2*b);
}
#define R5(dx,dy) p[((dy)+2)*5+(dx)+2]
// blink: the site's own temporal variance over its ring's mean (0 = not measured). From 4 (2x sigma; the noise of 8 frames reaches it
// with p ~ 2e-4, independent of the mean that flagged the site) the site blinks: an RTS defect whatever its mean did in the hot
// frames, ranked at least 2 x that sigma ratio. Blinking Quad blocks of the 185248 burst (all four sites 4..8 DN10 up on average,
// 53..87 DN10 from frame to frame, the ring's sigma 2 DN10): one site had a mean excess of 3.4 DN10 in the 8 hot frames beside a 1.5
// DN10 ring spread (z 1.9, below its strip's cut 2.6) and a variance 16x its ring's, another 3.0 DN10 and 5.3x: the cap let them
// through as 6-7 DN10 dots. 104 sites/MP of that strip vary 2.5x their ring's sigma (pure noise: 0.2/MP).
uint capRank(float p[25],bool green,float ex,float sd,float blink){ // the same-colour ring of siteExcess's median
    float s=0.59*(green?rankSpread8(R5(-1,-1),R5(1,-1),R5(-1,1),R5(1,1),R5(-2,0),R5(2,0),R5(0,-2),R5(0,2))
                       :rankSpread8(R5(-2,0),R5(2,0),R5(0,-2),R5(0,2),R5(-2,-2),R5(2,-2),R5(-2,2),R5(2,2)));
    float z=ex/sqrt(max(sd*sd+s*s,1.0e-20));
    if(blink>=4.0)z=max(z,2.0*sqrt(blink));
    return uint(clamp(floor((log2(max(z,1.0e-6))+4.0)*16.0),0.0,255.0));
}
// sd and blink of a flagged site from the temporal variances (vr = capRing, v = the site's own, n frames; model = the test's sd: a
// floor of a quarter of its variance under the ring, so that identical or quantised frames do not make every site blink)
vec2 capMeasured(float vr,float v,float n,float model){
    float ring=max(vr*0.125,0.25*n*(model*model)/(1.15*1.15));
    return vec2(sqrt(max(ring/n,1.0e-14))*1.15,v/ring);
}
)";
// Words of one cap block in Sums: 256 histogram bins, then the lowest rank kept, strips capped, sites cleared (kHybridCapWords).
static const char* kHybFlagCap=R"(#version 310 es
precision highp float;
precision highp int;
layout(local_size_x=1) in;
layout(std430,binding=9) buffer Sums{uint sums[];};
uniform ivec2 capU;          // x = first word of the cap block, y = flagged sites the strip's own rows may keep
void main(){
    uint h=uint(capU.x),allowed=uint(max(capU.y,0)),total=0u,t=0u;
    for(uint q=0u;q<256u;q++)total+=sums[h+q];
    if(total>allowed){
        // the lowest rank whose sites and every stronger one fit the allowance; the top bin stays even when it alone is over
        // (z >= 4096: a defect by any measure)
        uint kept=0u;t=255u;
        for(int q=255;q>=0;q--){kept+=sums[h+uint(q)];if(kept>allowed){t=min(uint(q)+1u,255u);break;}}
        sums[h+257u]+=1u;
    }
    sums[h+256u]=t;
    for(uint q=0u;q<256u;q++)sums[h+q]=0u;
}
)";
static const char* kHybFlagPrune=R"(#version 310 es
precision highp float;
precision highp int;
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=9) buffer Sums{uint sums[];};
layout(std430,binding=2) buffer SiteFlags{uint sflags[];};
uniform ivec4 pruneU;        // x = sites per flag row, y = flag rows of the strip, z / w = its own rows [z, w) (the cleared count)
uniform int capBase;         // first word of the cap block (kHybFlagCap)
uniform int chunkU;
void main(){
    uint t=sums[uint(capBase)+256u];
    int x=int(gl_GlobalInvocationID.x),r=chunkU+int(gl_GlobalInvocationID.y);
    if(t==0u||x>=pruneU.x||r>=pruneU.y)return;
    uint i=uint(r*pruneU.x+x),s=sflags[i];
    if((s&1u)==0u||((s>>8)&255u)>=t)return;
    sflags[i]=s&~1u;
    if(r>=pruneU.z&&r<pruneU.w)atomicAdd(sums[uint(capBase)+258u],1u);
}
)";
constexpr size_t kHybridCapWords=260;

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
layout(std140,binding=0) uniform FrameTable{ // kHybCommon's block: frameGeo.x = first site of frame f, .y / .z = first row / rows held
    uvec4 frameGeo[128];
    vec4 hA[128];
    vec4 hB[128];
    vec4 fParam[128];
    vec4 fNoiseP[128];
    ivec4 fCells[128];
};
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
    X=clamp(X,0,size.x-1);Y=clamp(Y-int(frameGeo[f].y),0,int(frameGeo[f].z)-1);
    uint idx=frameGeo[f].x+uint(Y*size.x+X);
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
// P76: one invocation per word of two rows (rows chunkU + 2 gid.y and the next): the 2x2 cell's clip test (4 RAW sites) once for
// both rows of the cell instead of once per row. A pair past the end of a dispatched run recomputes its row as the next run does
// (the result depends on the RAW values and the site flags only).
void main(){
    int f=frameIdx+int(gl_GlobalInvocationID.z);
    int wx=int(gl_GlobalInvocationID.x),row0=chunkU+2*int(gl_GlobalInvocationID.y);
    if(wx>=size.x/2||row0>=int(frameGeo[f].z))return;
    int lastX=-2,lastY=-2;bool clip=false;
    for(int rr=0;rr<2;rr++){
        int row=row0+rr;
        if(row>=int(frameGeo[f].z))break;
        int Y=int(frameGeo[f].y)+row;
        uint idx=frameGeo[f].x+uint(row*size.x+2*wx);
        uint word=frames[idx>>1],read=word;
        for(int k=0;k<2;k++){
            int X=2*wx+k;
            int x=X-cfaShift.x,y=Y-cfaShift.y;   // canonical
            uint fl=siteOutlier(f,x,y);
            if((mFlagsU.z&1)!=0&&x>=0&&y>=0){
                if((x>>1)!=lastX||(y>>1)!=lastY){lastX=x>>1;lastY=y>>1;clip=cellClipped(f,x>>1,y>>1);} // the sites of one cell share it
                if(clip)fl|=2u;
            }
            uint sh=uint(16*k);
            word=(word&~(0xC000u<<sh))|((fl&3u)<<(14u+sh));
        }
        if(word!=read)frames[idx>>1]=word; // most words carry no flag; an unchanged word is not written back
    }
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
    // diagonal neighbours; SCAM guide_image), less the gradient noise.
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
// P76: the 10x10 quads around the group's 8x8 cells, read once per group (sabreCov61 read each quad for 9 cells: 36 RAW sites a cell).
shared vec2 cellQuad[100];
// P332: the 22x22 sites around them for the fine tensor (sqrt of the gained greens, 0 at the other colours), also once per group
shared float cellSite[484];
void main(){
    int w2=size.x/2;
    int f=int(gl_GlobalInvocationID.z)+1+cellFrameU;
    int fs=clamp(f,0,frameCount-1); // the whole group has one frame (local size z = 1): every branch below is uniform for it
    bool cov=dcovU!=0&&k61aU.w>0.0&&fParam[fs].y>0.0&&int(fParam[fs].w)!=5;
    int bx=int(gl_WorkGroupID.x)*8-1,by=fCells[fs].x+chunkU+int(gl_WorkGroupID.y)*8-1;
    if(cov)for(int k=int(gl_LocalInvocationIndex);k<100;k+=64)cellQuad[k]=sabreQuad61(fs,bx+k%10,by+k/10);
    bool fine=cov&&k61cU.y>0.0;
    if(fine){
        float g=fParam[fs].x;
        for(int k=int(gl_LocalInvocationIndex);k<484;k+=64){
            int X=2*bx-1+k%22,Z=2*by-1+k/22;
            cellSite[k]=phaseColor[((Z&1)<<1)|(X&1)]==1?sqrt(max(sampleRawM(fs,X,Z)*g,0.0)):0.0;
        }
    }
    barrier();
    if(f>=frameCount)return;
    int cx=int(gl_GlobalInvocationID.x),cy=fCells[f].x+chunkU+int(gl_GlobalInvocationID.y);
    if(cx>=w2||cy>=fCells[f].x+fCells[f].y)return;
    bool cl;float gd;
    vec3 u=cellU(f,cx,cy,cl,gd);
    uint idx=uint(fCells[f].z)+uint((cy-fCells[f].x)*w2+cx);
    cells[idx]=vec4(u,cl?1.0:0.0);
    if(cov){
        float Y[9];float qd=0.0,lum=0.0;
        int q0=(cy-by-1)*10+(cx-bx-1);
        for(int dj=-1;dj<=1;dj++)for(int di=-1;di<=1;di++){
            vec2 q=cellQuad[q0+(dj+1)*10+di+1];
            float w=(di==0?0.5:0.25)*(dj==0?0.5:0.25);
            Y[(dj+1)*3+di+1]=q.x;qd+=q.y*w;lum+=q.x*w;
        }
        vec3 fineT=vec3(-1.0);
        if(fine){
            float u[64];int s0=(2*(cy-by)-2)*22+2*(cx-bx)-2; // site (2cx-3, 2cy-3) in cellSite
            for(int y=0;y<8;y++)for(int x=0;x<8;x++)u[y*8+x]=cellSite[s0+y*22+x];
            fineT=sabreFine61U(cx,cy,u);
        }
        vec3 P=sabreCov61Q(f,Y,qd,lum,fineT);
        dcov[idx]=uvec2(packHalf2x16(P.xy),packHalf2x16(vec2(P.z,0.0)));
    }
}
)";

// Rejection per donor frame and base cell (SCAM 9.6 rejection.cl, REJECTION_ONLY path).
static const char* kHybReject=R"(
layout(std430,binding=7) writeonly buffer RawR{float rawR[];};
layout(std430,binding=10) readonly buffer Guide{vec4 guide[];};
uniform int ry0;
uniform int ry1;
uniform vec4 rj; // cdm, boost, variance threshold, filter variance scale
uniform vec4 rk; // boost mode (0 off, 1 local motion, 2 everywhere), motion threshold (RAW px), z: 1 = soft motion prior (P335), 0
#ifdef LOCAL_ALIGN
// P335: the Z channel of frame f bilinear between the tile centres (laMotionAt takes the nearest tile: the boost switched on and off
// in 16 px blocks along a moving edge).
float laMotionSoft(int f,int x,int y){
    int nx=max(laU.y,1),ny=max(laU.z,1);
    uint fb=uint(clamp(f,0,frameCount-1)*nx*ny);
    vec2 p=clamp((vec2(float(x),float(y))+0.5-laG.xy)*laG.w,vec2(0.0),vec2(float(nx-1),float(ny-1)));
    ivec2 i0=clamp(ivec2(p),ivec2(0),ivec2(nx-1,ny-1)),i1=min(i0+1,ivec2(nx-1,ny-1));
    vec2 a=p-vec2(i0);
    float z00=laZ[fb+uint(i0.y*nx+i0.x)],z10=laZ[fb+uint(i0.y*nx+i1.x)],z01=laZ[fb+uint(i1.y*nx+i0.x)],z11=laZ[fb+uint(i1.y*nx+i1.x)];
    return f==0?0.0:mix(mix(z00,z10,a.x),mix(z01,z11,a.x),a.y);
}
#endif
void main(){
    int w2=size.x/2;
    int cx=int(gl_GlobalInvocationID.x),cy=ry0+chunkU+int(gl_GlobalInvocationID.y),f=int(gl_GlobalInvocationID.z)+1;
    if(cx>=w2||cy>=ry1||f>=frameCount)return;
    vec4 G=guide[(cy-ry0)*w2+cx];
    float w=0.0;
    vec2 o=originL(f,2*cx,2*cy)*0.5;
    // P12: a donor cell warped beyond the donor's own frame has no sample there; the clamped edge cell it would read made
    // the vertical streaks of the X200 Ultra hand test. Such cells get weight 0, not the edge.
    bool inside=o.x>=0.0&&o.y>=0.0&&o.x<=float(w2-1)&&o.y<=float(size.y/2-1);
    if(fParam[f].y>0.0&&inside){
        int ix=int(floor(o.x)),iy=int(floor(o.y));
        float fx=o.x-float(ix),fy=o.y-float(iy);
#ifdef SHARED_TILES
        // P76 (Adreno): the 3x3 cells around (ix, iy) read once; the bilinear sample takes 4 of them (13 reads a cell before). Mali
        // keeps the 13 reads (the 9 vec4 held across the variance made the pass slower on the Mali-G57).
        vec4 c3[9];
        for(int k=0;k<9;k++)c3[k]=cellAt(f,ix-1+k%3,iy-1+k/3);
        vec4 c00=c3[4],c10=c3[5],c01=c3[7],c11=c3[8];
#else
        vec4 c00=cellAt(f,ix,iy),c10=cellAt(f,ix+1,iy),c01=cellAt(f,ix,iy+1),c11=cellAt(f,ix+1,iy+1);
#endif
        vec3 g=(c00.xyz*(1.0-fx)+c10.xyz*fx)*(1.0-fy)+(c01.xyz*(1.0-fx)+c11.xyz*fx)*fy;
        bool dclip=max(max(c00.w,c10.w),max(c01.w,c11.w))>0.5;
        // donor texture variance over its 3x3 cells
        vec3 m=vec3(0.0),m2=vec3(0.0);
#ifdef SHARED_TILES
        for(int k=0;k<9;k++){vec3 u=c3[k].xyz;m+=u*(1.0/9.0);m2+=u*u*(1.0/9.0);}
#else
        for(int dj=0;dj<=2;dj++)for(int di=0;di<=2;di++){vec3 u=cellAt(f,ix-1+di,iy-1+dj).xyz;m+=u*(1.0/9.0);m2+=u*u*(1.0/9.0);}
#endif
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
        // SCAM / v11 extra motion robustness: textured base and, with the local alignment, varying local motion
        float prior=rk.x>1.5?1.0:0.0;
        float forgive=1.0; // share of the difference noise forgiven before the distance (1: all of it)
#ifdef LOCAL_ALIGN
        if(rk.x>0.5&&rk.x<1.5){
            if(rk.z>0.5){
                // P335: the motion prior as a share instead of a switch at the threshold (no seam where the varying local motion
                // crosses it), and inside it only half the noise forgiven: a moving surface whose difference sits near the noise
                // level is not taken as noise (Sabre's motion-prior noise subtraction, made continuous)
                prior=smoothstep(0.5*rk.y,1.5*rk.y,laMotionSoft(f,2*cx,2*cy));
                forgive=1.0-0.5*prior;
            } else prior=laMotionAt(f,2*cx,2*cy)>rk.y?1.0:0.0;
        }
#endif
        vec3 d=g-G.xyz;
        vec3 D2=max(d*d-forgive*nv,vec3(0.0));
        float varc=max(2.0*min(G.w,Vcur),nvMean);
        float dist=rj.x*(D2.x+D2.y+D2.z)*(1.0/3.0)/varc;
        float boost=G.w>rj.z*nvMean?mix(1.0,rj.y,prior):1.0;
        w=exp2(-dist*boost);
        // A longer (bracketed) frame clips where the base does not: its clipped cells carry no signal.
        if(int(fParam[f].w)==3&&dclip)w=0.0;
    }
    rawR[(f-1)*(ry1-ry0)*w2+(cy-ry0)*w2+cx]=w;
}
)";

// DilateMask (5x5, SCAM: rej = (sum25 - 0.2)/2), the per-frame scalar weight and the Bento replacement.
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
uniform int validPlanesU; // bentoValidate: per-ultrashort-frame validity planes after the mask plane (0 = none)
uniform float rampKU;     // P309: the ramp mask's K (0 = the previous mask)
#ifdef SHARED_TILES
// P76 (Adreno: SHARED_TILES): the 12x12 rejection weights around the group's 8x8 cells, read once per group (25 reads a cell
// before; Adreno 750 dilate 533 -> 234 ms, float rounding differs by <= 2.3e-6). Mali keeps the plain reads: its shared memory is
// the same cache as the buffer, and the tile and barrier made the pass 20 % slower on the Mali-G57.
shared float dilR[144];
#endif
void main(){
    int w2=size.x/2;
    int cx=int(gl_GlobalInvocationID.x),cy=cy0+chunkU+int(gl_GlobalInvocationID.y),f=int(gl_GlobalInvocationID.z)+1;
#ifdef SHARED_TILES
    {
        int bx=int(gl_WorkGroupID.x)*8-2,by=cy0+chunkU+int(gl_WorkGroupID.y)*8-2,fr=clamp(f,1,max(frameCount-1,1));
        for(int k=int(gl_LocalInvocationIndex);k<144;k+=64){
            int y=clamp(by+k/12,ry0,ry1-1),x=clamp(bx+k%12,0,w2-1);
            dilR[k]=rawR[(fr-1)*(ry1-ry0)*w2+(y-ry0)*w2+x];
        }
    }
    barrier();
#endif
    if(cx>=w2||cy>=cy1||f>=frameCount)return;
    float s=0.0,wc=1.0;
    for(int dj=-2;dj<=2;dj++)for(int di=-2;di<=2;di++){
#ifdef SHARED_TILES
        // the clamped site of the tile: clamp(by + k / 12) is clamp(cy + dj) for the column / row of this cell
        int ty=cy+dj-(cy0+chunkU+int(gl_WorkGroupID.y)*8-2),tx=cx+di-(int(gl_WorkGroupID.x)*8-2);
        float v=dilR[ty*12+tx];
#else
        int y=clamp(cy+dj,ry0,ry1-1),x=clamp(cx+di,0,w2-1);
        float v=rawR[(f-1)*(ry1-ry0)*w2+(y-ry0)*w2+x];
#endif
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
        if(int(fParam[f].w)==5){
            float v=1.0;
            if(validPlanesU>0){int k=0;for(int j=1;j<f;j++)if(int(fParam[j].w)==5)k++;if(k<validPlanesU)v=bmask[(k+1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx];}
            r=m*fParam[f].y*v;
            // P309: where the ramp lets the ultrashort frames into unclipped areas (share below ~1) they follow the N frames' acceptance
            // there: where those are rejected (motion: a hand, a screen) the ultrashort frames go as well; where they agree the
            // ultrashort frames stay (their own rejection would also refuse the few % of level a lamp's flicker leaves). The clipped
            // core keeps them as before (nothing else covers it).
            if(rampKU>0.0){
                float sh=m/max(m+rampKU*(1.0-m),1.0e-6);
                if(sh<1.0){
                    float acc=0.0;int cnt=0;
                    int yy=clamp(cy,ry0,ry1-1);
                    for(int j=1;j<frameCount;j++){int role=int(fParam[j].w);if(role==5||role==3)continue;acc+=rawR[(j-1)*(ry1-ry0)*w2+(yy-ry0)*w2+cx];cnt++;}
                    r*=mix(cnt>0?acc/float(cnt):1.0,1.0,smoothstep(0.9,1.0,sh));
                }
            }
        } else r*=(1.0-m);
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
#elif (defined(NAT_BASEFILL) && NAT_BASEFILL) || (defined(NAT_DEMOSAIC) && NAT_DEMOSAIC)
layout(std430,binding=3) buffer Out{float outRgb[];};
layout(std430,binding=4) writeonly buffer Eff{float eff[];};
layout(std430,binding=6) writeonly buffer CFlags{uint cflags[];};
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
#ifdef JSR_LCA
uniform vec4 jsrCaA; // P74: k1 / k2 of R, k1 / k2 of B (P19 model: displacement 2 (k1 + k2 r^2) q RAW px, q = (cell - centre) / half)
uniform vec4 jsrCaB; // centre x / y (cells), 1 / half
vec2 jsrSite(int c,vec2 s){
    if(c==1)return s;
    vec2 q=(0.5*s-jsrCaB.xy)*jsrCaB.z;float r2=dot(q,q);
    float k=c==0?jsrCaA.x+jsrCaA.y*r2:jsrCaA.z+jsrCaA.w*r2;
    return s-2.0*k*q;
}
#else
#define jsrSite(c,s) (s)
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
            vec2 d=jsrSite(c,vec2(float(sx),float(sy)))-O;
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
            vec2 d=jsrSite(c,vec2(float(sx),float(sy)))-O;
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
#ifdef CHROMA_PASS
// Colour-difference interpolation of the base frame (pass kHybChroma, HybridTuning::chromaDiff). The base's R (B) samples alone
// cannot resolve an edge: with the 6.1 window and an across-edge sigma of 0.25 px every output pixel takes the nearest R sites
// across the edge (a period-2 R/B fringe where the donors were rejected). The difference R - G is smooth across a neutral edge,
// so for every unclipped, unflagged R (B) site v of the base inside the window, G at the site is interpolated from its four
// orthogonal green neighbours with the same kernel relative to the site (one weight per axis: d'Pd is even in d, so along the
// edge they dominate) and v - G(site) is accumulated with the site's kernel weight. A neighbour that is flagged or clipped is
// left out; a site that lost most of the neighbour weight (both along-edge greens) or is flagged/clipped itself gives no
// difference, and the pass keeps the plain path for a colour without any. cdLo/cdHi hold the range of the sites that gave a
// difference (the clamp: no new extremum).
void baseChromaDiff(inout vec2 cdNum,inout vec2 cdDen,inout vec2 cdLo,inout vec2 cdHi,vec2 O,vec3 P,bool win){
    float g=fParam[0].x;
    float wx=kernelW(vec2(1.0,0.0),P),wy=kernelW(vec2(0.0,1.0),P),wMin=0.6*(wx+wy);
    for(int p=0;p<4;p++){
        int px=p&1,py=p>>1,c=phaseColor[p];
        if(c==1)continue;
        int k=c>>1;
        int bx=int(floor((O.x-float(px))*0.5))*2+px,by=int(floor((O.y-float(py))*0.5))*2+py;
        for(int dj=0;dj<=2;dj+=2)for(int di=0;di<=2;di+=2){
            int sx=bx+di,sy=by+dj;
            vec2 d=vec2(float(sx),float(sy))-O;
            if(win&&max(abs(d.x),abs(d.y))>1.5)continue;
            float kw=kernelW(d,P);
            uint fl;
            float v=rawSite(0,sx,sy,fl);
            if((fl&3u)!=0u||v>=clipLevel)continue;
            float gs=0.0,gw=0.0;uint f2;
            float gl=rawSite(0,sx-1,sy,f2);if((f2&3u)==0u&&gl<clipLevel){gs+=wx*gl;gw+=wx;}
            float gr=rawSite(0,sx+1,sy,f2);if((f2&3u)==0u&&gr<clipLevel){gs+=wx*gr;gw+=wx;}
            float gu=rawSite(0,sx,sy-1,f2);if((f2&3u)==0u&&gu<clipLevel){gs+=wy*gu;gw+=wy;}
            float gd=rawSite(0,sx,sy+1,f2);if((f2&3u)==0u&&gd<clipLevel){gs+=wy*gd;gw+=wy;}
            if(gw<wMin)continue;
            cdNum[k]+=kw*(v-gs/gw)*g;cdDen[k]+=kw;
            cdLo[k]=min(cdLo[k],v*g);cdHi[k]=max(cdHi[k],v*g);
        }
    }
}
// P282 (Bayer): the base frame's own demosaic at O where the donors were rejected. A 9x9 site neighbourhood around the nearest
// site; the local direction from the 3x3 sites' gradients (green differences at distance 1 plus same-colour Laplacians); green
// at every red / blue site of the 5x5 core interpolated along that direction with the Laplacian correction (Hamilton-Adams);
// green at O bilinear from the four sites around it; R - G and B - G from the 5x5 core's red / blue sites, weighted by distance
// (narrowed across the edge) and by how close their green is to the green at O (a red / green edge flips R - G: the side O
// belongs to). Returns false (the merge's value stays) at a clipped or flagged site of the neighbourhood.
uniform float rampKU; // P309: the ramp mask's K (0 = the previous mask): the base demosaic fades out over share 0..0.5
uniform vec4 bdU; // x = share at no donor (HybridTuning::baseDemosaic), y = anisotropy, z / w = structure / noise ratio from
                  // which to which the demosaic takes over (flat areas keep the merge's wider, less noisy kernel)
uniform float bdAliasU; // P335: 0..1 share of the false-colour damping (0 = off, the demosaic as before)
bool bayerDemosaic(vec2 O,out vec3 rgb,out float detail){
    ivec2 N=ivec2(floor(O+0.5));
    float A[81];
    for(int j=0;j<9;j++)for(int i=0;i<9;i++){
        uint fl;
        float v=rawSite(0,N.x+i-4,N.y+j-4,fl);
        if((fl&3u)!=0u||v>=clipLevel)return false;
        A[j*9+i]=v;
    }
    float dh=0.0,dv=0.0;
    for(int j=3;j<=5;j++)for(int i=3;i<=5;i++){
        int k=j*9+i;
        dh+=abs(A[k+1]-A[k-1])+0.5*abs(2.0*A[k]-A[k-2]-A[k+2]);
        dv+=abs(A[k+9]-A[k-9])+0.5*abs(2.0*A[k]-A[k-18]-A[k+18]);
    }
    float own=A[40];
    float sig=sqrt(max(baseNoise.x*max(own,0.0)+baseNoise.y,1.0e-10));
    detail=(dh+dv)/(54.0*sig); // ~1 on pure noise (each site's two terms average ~3 sigma per axis)
    float e=3.0*sig+1.0e-5;
    float h2=(dh+e)*(dh+e),v2=(dv+e)*(dv+e);
    float wH=v2/(h2+v2),wV=1.0-wH,aniso=abs(wH-wV);
    // green at the 5x5 core's sites (Gs) and their colours
    float Gs[25];int Cs[25];
    for(int j=0;j<5;j++)for(int i=0;i<5;i++){
        int k=(j+2)*9+i+2;
        int c=phaseColor[(((N.y+j-2)&1)<<1)|((N.x+i-2)&1)];
        Cs[j*5+i]=c;
        if(c==1){Gs[j*5+i]=A[k];continue;}
        float gh=0.5*(A[k-1]+A[k+1])+0.125*(2.0*A[k]-A[k-2]-A[k+2]);
        float gv=0.5*(A[k-9]+A[k+9])+0.125*(2.0*A[k]-A[k-18]-A[k+18]);
        float lo=min(min(A[k-1],A[k+1]),min(A[k-9],A[k+9])),hi=max(max(A[k-1],A[k+1]),max(A[k-9],A[k+9]));
        float r=hi-lo;
        Gs[j*5+i]=clamp(wH*gh+wV*gv,lo-0.5*r,hi+0.5*r); // the Laplacian's overshoot bounded at an edge
    }
    vec2 P=O-vec2(N)+2.0; // O in core coordinates (the core's site (2, 2) is N)
    ivec2 p0=ivec2(floor(P));vec2 f=P-vec2(p0);
    p0=clamp(p0,ivec2(0),ivec2(3));
    float G=mix(mix(Gs[p0.y*5+p0.x],Gs[p0.y*5+p0.x+1],f.x),mix(Gs[(p0.y+1)*5+p0.x],Gs[(p0.y+1)*5+p0.x+1],f.x),f.y);
    float tauG=3.0*sig+0.08*max(G,0.0)+1.0e-4;
    float sa=4.0*bdU.y*aniso;
    vec2 fa=vec2(1.0+sa*wV,1.0+sa*wH); // across a vertical structure (horizontal variation, wV large) narrower in x
    float sR=0.0,nR=0.0,sB=0.0,nB=0.0;
    for(int k=0;k<25;k++){
        int c=Cs[k];
        if(c==1)continue;
        vec2 d=vec2(float(k%5),float(k/5))-P;
        float q=(Gs[k]-G)/tauG;
        float w=exp2(-0.72*(fa.x*d.x*d.x+fa.y*d.y*d.y))/(1.0+q*q);
        float df=A[((k/5)+2)*9+(k%5)+2]-Gs[k];
        if(c==0){sR+=w*df;nR+=w;}else{sB+=w*df;nB+=w;}
    }
    if(nR<=1.0e-6||nB<=1.0e-6)return false;
    float dR=sR/nR,dB=sB/nB;
    if(bdAliasU>0.0){
        // P335 (owner, 2026-10-25: Ark's anti-zipper «попробуй его улучшить и добавить нам»): luma detail near the Bayer Nyquist
        // shows in one frame as a step between the two green phases and as false colour (the zipper / moire of a moving subject);
        // where the Gr / Gb step of the 5x5 core is large against the colour difference the colour is damped (Sabre's motion
        // fallback damping). Unlike that one the step is taken above its own noise (the two phase means' sigma x2) and is
        // reduced along a clear edge direction, so a noisy flat area or a straight edge keeps its colour.
        float g0=0.0,n0=0.0,g1=0.0,n1=0.0;
        for(int k=0;k<25;k++){
            if(Cs[k]!=1)continue;
            float v=A[((k/5)+2)*9+(k%5)+2];
            if(((N.y+k/5)&1)==0){g0+=v;n0+=1.0;}else{g1+=v;n1+=1.0;}
        }
        if(n0>0.0&&n1>0.0){
            float gd=max(abs(g0/n0-g1/n1)-2.0*sig*sqrt(1.0/n0+1.0/n1),0.0)*(1.0-0.7*aniso);
            float ar=1.4*gd/(sqrt(dR*dR+dB*dB)+0.02*max(G,0.0)+1.0e-6);
            float damp=mix(1.0,1.0/(1.0+ar*ar),clamp(bdAliasU,0.0,1.0));
            dR*=damp;dB*=damp;
        }
    }
    rgb=max(vec3(G+dR,G,G+dB),vec3(0.0))*fParam[0].x;
    return true;
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
    vec2 sub=g>1?(vec2(float(sx),float(sy))+0.5)/float(g)-0.5:vec2(0.0); // grid 2: +-0.25 exactly; grid 4 (Tetra sub-frames): +-0.125, +-0.375
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
            // next to its white inside): those are left to the highlight recovery and its defringe (scamhdr/scamrgb).
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

// Colour-difference pass of the base frame (after the merge of a strip, before kHybBento and kHybRim; one invocation per output
// pixel). Only where the merge widened the base kernel (6.1: fewer than widenBelow accepted donor frames; round-4 kernel: donor
// coverage below widenBelow, from the coverage map the merge wrote) the merge's sums of the pixel are recomputed (as kHybRim)
// and the base's share of R (B) becomes the merged G plus the base's mean colour difference (baseChromaDiff), clamped (cdU.y) to
// the range of the base's R (B) samples around the pixel. The donors' share stays as merged.
static const char* kHybChroma=R"(
layout(std430,binding=4) readonly buffer EffR{float effR[];}; // the merge's coverage per output pixel (donor cover + base weight)
uniform vec2 cdU; // x: strength 0..1, y: 1 = clamp to the base's sample range
void main(){
    int g=kG.x,ow=kG.y,w2=size.x/2;
    int ox=int(gl_GlobalInvocationID.x),row=chunkU+int(gl_GlobalInvocationID.y);
    if(ox>=ow||row>=2*(cy1-cy0)*g)return;
    int x=ox/g,y=2*cy0+row/g,sx=ox-(ox/g)*g,sy=row-(row/g)*g;
    int cx=x>>1,cy=y>>1;
    float m=kD.w>0.5?bmask[(cy-cy0)*w2+cx]:0.0;
    int mode=mergeModeU.x;
    float wb=1.0-m;
    if(kE.x>=0.0||mergeModeU.y!=0)wb=0.0;
    if(wb<=0.0)return;
    int i=row*ow+ox;
    // cheap test first: the merge widened the base kernel here
    if((mode&4)!=0){
        float frames=0.0;
        for(int f=1;f<frameCount;f++){
            if(int(fParam[f].w)==5)continue;
            float r=robust[(f-1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx];
            if(r<0.004)continue;
            frames+=r/max(fParam[f].y,1.0e-6);
        }
        if(frames>=kD.x)return;
    } else if(effR[i]-wb>=kD.x)return;
    vec2 pos=vec2(float(x),float(y))+(g>1?(vec2(float(sx),float(sy))+0.5)/float(g)-0.5:vec2(0.0));
    vec2 cell=vec2(float(2*cx),float(2*cy));
    vec3 P=cov[(cy-ry0)*w2+cx].xyz;
    // the merge's samples of this pixel again (kHybMergeMain1: same weights and kernels), donors first
    Acc a;initAcc(a);
    float frames=0.0;
    for(int f=1;f<frameCount;f++){
        float r=robust[(f-1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx];
        if(r<0.004)continue;
        vec2 O=originM(f,2*cx,2*cy)+(pos-cell);
        bool us=int(fParam[f].w)==5;
        vec3 Pf;
        if(us)Pf=vec3(kE.y,kE.y,0.0);
        else if((mode&1)!=0){ vec2 dc=floor((O+0.5)*0.5); Pf=dcovAt(f,int(dc.x),int(dc.y))*fParam[f].z; }
        else Pf=P*fParam[f].z;
        float rw=r/max(fParam[f].y,1.0e-6);
        if(!us)frames+=rw;
        frameSamples(a,f,O,r,rw,Pf,(mode&2)!=0&&!us);
    }
    vec3 num0=a.num,den0=a.den;
    vec3 Pb;float tLow,tDem;
    if((mode&4)!=0){Pb=frames<kD.x?P/(kD.y*kD.y):P;tLow=frames<kD.x?1.0:0.0;tDem=1.0-smoothstep(0.0,kD.x,frames);}
    else {float s=smoothstep(0.5*kD.x,kD.x,a.cover);float widen=mix(kD.y,1.0,s);Pb=P/(widen*widen);tLow=1.0-s;tDem=tLow;}
    tLow*=cdU.x;
    tDem*=bdU.x;
    if(tLow<=0.0&&tDem<=1.0e-3)return;
    bool win=(mode&2)!=0&&m<=0.0;
    frameSamples(a,0,pos,wb,wb,Pb,win);
    if(tLow>0.0&&a.den.y>1.0e-7){
        vec2 cdNum=vec2(0.0),cdDen=vec2(0.0),cdLo=vec2(1.0e9),cdHi=vec2(-1.0e9);
        baseChromaDiff(cdNum,cdDen,cdLo,cdHi,pos,Pb,win);
        float g0=fParam[0].x,G=outRgb[i*3+1];
        for(int k=0;k<2;k++){
            int c=2*k;
            float Db=a.den[c]-den0[c];
            if(cdDen[k]<=1.0e-7||Db<=1.0e-7||a.den[c]<=1.0e-7)continue;
            float rec=G+cdNum[k]/cdDen[k];
            rec=cdU.y>0.0?clamp(rec,cdLo[k],cdHi[k]):clamp(rec,-0.25*g0,g0);
            outRgb[i*3+c]+=tLow*(Db/a.den[c])*(rec-(a.num[c]-num0[c])/Db);
        }
    }
    // P282: the base's own demosaic by the donors' share (after the colour difference: it replaces that share's value)
    vec3 d;float detail;
    float rampT=m<=0.0?1.0:0.0;
    if(rampKU>0.0&&m>0.0)rampT=1.0-smoothstep(0.0,0.5,m/max(m+rampKU*(1.0-m),1.0e-6));
    if(tDem*rampT>1.0e-3&&bayerDemosaic(pos,d,detail)){
        tDem*=rampT*smoothstep(bdU.z,bdU.w,detail);
        for(int c=0;c<3;c++)outRgb[i*3+c]=mix(outRgb[i*3+c],d[c],tDem);
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
    vec2 pos=vec2(float(x),float(y))+(g>1?(vec2(float(sx),float(sy))+0.5)/float(g)-0.5:vec2(0.0));
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

// P310 ultrashort blend (bentoBlend, after the merge of a strip and its colour passes; one invocation per output pixel, two stages):
// the merge holds the N frames and the base alone; here every ultrashort frame demosaiced at the pixel, the frames combined with a
// soft rejection against their median level, and that RGB blended in with ONE weight for the three colours by region (see
// HybridTuning::bentoBlend): no colour of a pixel from other frames than the others, no per-pixel switch between the sources.
static const char* kHybUsBlend=R"(
layout(std430,binding=4) buffer EffBlend{float effB[];};
// per output pixel of the strip (slot 10, free once the strip is merged): x, y.x = the ultrashort colour (half floats), y.y = the
// weight (< 0: no ultrashort colour), z, w.x = its gain (stage 2), w.y = its cover + 8 x the colours at its own clip
layout(std430,binding=10) buffer BlendScratch{uvec4 bs[];};
uniform ivec4 blendCarryU; // x: index in bs of the two carry rows (the log gain of a strip's last row and whether that pixel
                           // blended with it, by the strip's parity y), z: 1 = the previous strip left one
uniform int validPlanesU;  // per-ultrashort-frame validity planes after the weight plane (bentoValidate), then the edge rows plane
uniform vec4 blendU;       // x: soft rejection width (relative level against the frames' median), y: 1 = the merge wrote clip flags,
                           // w: the ultrashort colour's chroma smoothing over 5 x 5 px (0 = none, 1 = full)
uniform int blendStageU;   // 0: the N marks of every pixel (clip flags bits 16-23 clip, 24-31 bright), 1: the weight and the
                           // ultrashort colour, 2: its gain against N, 3: the blend
uniform vec4 blendClipU;   // x, y: N level (its clip = 1) where the clip mark starts / is full; z, w: distance (px) where the weight
                           // starts to fall / has fallen to 0
uniform vec4 blendGateU;   // x, y: level (N clip = 1) where the bright mark starts / is full; z: its gaussian (px) for the gate
uniform vec4 blendGainU;   // the gain (stage 2): x radius (px, 0 = off), y gaussian (px), z measured share of the weight for full
                           // confidence, w largest gain (and 1 / smallest)
uniform vec2 blendGainLoU; // x: the gain is measured on N levels from this share of its clip up, y: its strength (0 = off)
uniform float blendSatU;   // saturation ((max - min) / max of the frame's wide colour) from which a light counts as coloured
uniform vec4 blendPxU;     // x, y: without clip flags, smoothstep of the N merge's brightest colour at the pixel raising the weight
                           // inside the cells' blend zone; z: radius (px) of the hole fill search, 0 = off; w: debug view
// The weight of a cell row of the strip (plane 0) or of the row just above / below it (edge plane: row cy0 - 1, then row cy1).
float blendCell(int x,int y){
    int w2=size.x/2,rows=cy1-cy0;
    x=clamp(x,0,w2-1);
    if(y<cy0)return bmask[(1+validPlanesU)*rows*w2+x];
    if(y>=cy1)return bmask[(1+validPlanesU)*rows*w2+w2+x];
    return bmask[(y-cy0)*w2+x];
}
// bilinear between the cell centres (cell (cx, cy) covers sensor pixels 2 cx .. 2 cx + 1: centre 2 cx + 0.5)
float blendAt(vec2 pos){
    vec2 c=(pos-0.5)*0.5,f=floor(c),t=c-f;
    int x0=int(f.x),y0=int(f.y);
    float a=mix(blendCell(x0,y0),blendCell(x0+1,y0),t.x),b=mix(blendCell(x0,y0+1),blendCell(x0+1,y0+1),t.x);
    return mix(a,b,t.y);
}
// The ultrashort frame f's colour at frame position O, demosaiced at its own sites (WhyCam does the same for its short frames; a
// gaussian splat of three single Bayer frames that hardly move against each other staircased every thin line) and bilinear between
// the four sites around O: green at an R / B site by Hamilton-Adams with soft direction weights, R and B by their colour
// differences at the neighbouring sites (against the green along their smoother direction), the neighbours weighted by how close
// their green is to the pixel's: across a sharp edge (a window frame against a sky x8 brighter) the other side's difference made
// a 2 px zipper of teal / yellow along it. One read of the 6x6 sites the four need. Values in base
// units (the frame's gain); cl collects the colours at the frame's own clip (a clipped site among the 4x4 nearest and the colour
// within 3 % of it: the clipped mean's meaning in the merge, a lower bound for the highlight recovery; the softer values beside a
// clipped spot are real, flagging them gave the recovery white rings around every clipped LED dot).
// green at a red / blue site from its four green neighbours, the smoother direction weighted up (no second-order term: one site
// further would leave the 6x6 window)
float usGreenDir(float l,float r,float u,float d){
    float e=0.02*max(0.25*(l+r+u+d),1.0e-3);
    float wh=1.0/((l-r)*(l-r)+e*e),wv=1.0/((u-d)*(u-d)+e*e);
    return (wh*0.5*(l+r)+wv*0.5*(u+d))/(wh+wv);
}
vec3 usColourAt(int f,vec2 O,inout uint cl){
    vec2 fo=floor(O),t=O-fo;
    int x0=int(fo.x)-2,y0=int(fo.y)-2;
    float g=fParam[f].x;
    float w[36];
    uint nearClip=0u;
    for(int j=0;j<6;j++)for(int i=0;i<6;i++){
        uint fl;float v=rawSite(f,x0+i,y0+j,fl);
        if(i>0&&i<5&&j>0&&j<5&&((fl&2u)!=0u||v>=clipLevel))nearClip|=1u<<uint(phaseColor[(((y0+j)&1)<<1)|((x0+i)&1)]);
        w[j*6+i]=v*g;
    }
    vec3 o=vec3(0.0);
    for(int q=0;q<4;q++){
        int i=2+(q&1),j=2+(q>>1);
        int k=j*6+i;
        int c=phaseColor[(((y0+j)&1)<<1)|((x0+i)&1)];
        vec3 s3;
        if(c==1){
            int ch=phaseColor[(((y0+j)&1)<<1)|((x0+i+1)&1)];
            float G=w[k],ec=0.05*max(G,1.0e-3);ec*=ec;
            float gl=usGreenDir(w[k-2],w[k],w[k-7],w[k+5]),gr=usGreenDir(w[k],w[k+2],w[k-5],w[k+7]);
            float gu=usGreenDir(w[k-7],w[k-5],w[k-12],w[k]),gd=usGreenDir(w[k+5],w[k+7],w[k],w[k+12]);
            float al=1.0/((gl-G)*(gl-G)+ec),ar=1.0/((gr-G)*(gr-G)+ec),au=1.0/((gu-G)*(gu-G)+ec),ad=1.0/((gd-G)*(gd-G)+ec);
            float dh=(al*(w[k-1]-gl)+ar*(w[k+1]-gr))/(al+ar),dv=(au*(w[k-6]-gu)+ad*(w[k+6]-gd))/(au+ad);
            // never past the two sites of that colour it lies between (an overshoot at a x20 edge: a black pixel on a ring)
            float cH=clamp(G+dh,min(w[k-1],w[k+1]),max(w[k-1],w[k+1])),cV=clamp(G+dv,min(w[k-6],w[k+6]),max(w[k-6],w[k+6]));
            s3.g=G;
            if(ch==0){s3.r=cH;s3.b=cV;} else {s3.b=cH;s3.r=cV;}
        } else {
            float X=w[k],l=w[k-1],r=w[k+1],u=w[k-6],d=w[k+6];
            float l2=w[k-2],r2=w[k+2],u2=w[k-12],d2=w[k+12];
            float gh=0.5*(l+r)+0.25*(2.0*X-l2-r2),gv=0.5*(u+d)+0.25*(2.0*X-u2-d2);
            float eh=abs(l-r)+abs(2.0*X-l2-r2),ev=abs(u-d)+abs(2.0*X-u2-d2);
            float e0=0.02*max(X,1.0e-3);
            float wh=1.0/(eh*eh+e0*e0),wv=1.0/(ev*ev+e0*e0);
            // within its four green neighbours: the second-order term overshoots at a steep edge (green 0 beside a ring)
            float G=clamp((wh*gh+wv*gv)/(wh+wv),min(min(l,r),min(u,d)),max(max(l,r),max(u,d)));
            // the other colour on the diagonals, against the green along each one's smoother direction
            float dd=0.0,sd=0.0,ec=0.05*max(G,1.0e-3);ec*=ec;
            for(int e=0;e<4;e++){
                int m=k+((e&1)!=0?1:-1)+((e&2)!=0?6:-6);
                float gm=usGreenDir(w[m-1],w[m+1],w[m-6],w[m+6]),a=1.0/((gm-G)*(gm-G)+ec);
                dd+=a*(w[m]-gm);sd+=a;
            }
            dd/=sd;
            float cD=clamp(G+dd,min(min(w[k-7],w[k-5]),min(w[k+5],w[k+7])),max(max(w[k-7],w[k-5]),max(w[k+5],w[k+7])));
            s3.g=G;
            if(c==0){s3.r=X;s3.b=cD;} else {s3.b=X;s3.r=cD;}
        }
        o+=s3*((q&1)!=0?t.x:1.0-t.x)*((q>>1)!=0?t.y:1.0-t.y);
    }
    for(int c=0;c<3;c++)if((nearClip&(1u<<uint(c)))!=0u&&o[c]>=0.97*clipLevel*g)cl|=1u<<uint(c);
    return o;
}
// The ultrashort frames' colour at position p (sensor px; strip rows only). Each frame's level is its green on the wide Bento
// colour kernel (smooth in space: a per-pixel choice between frames made ragged edges where they differ); the frames weigh by
// their check validity and a soft rejection against the median level (one frame of three on another flicker phase weighs
// little); the colour is the weighted mean of the frames' demosaiced colours. No test against the N merge here (see the caller).
// lvl: the weighted brightest colour of the frames' wide colours (the gate). false: no frame.
bool usAt(vec2 p,bool inLight,out vec3 U,out float cover,out uint uclip,out bool coloured,out float bestV,out float lvl){
    int w2=size.x/2;
    p.y=clamp(p.y,float(2*cy0),float(2*cy1)-0.5);  // the strip's rows (its frames' rows are uploaded around them)
    int cx=clamp(int(floor(p.x*0.5)),0,w2-1),cy=clamp(int(floor(p.y*0.5)),cy0,cy1-1);
    vec2 cell=vec2(float(2*cx),float(2*cy));
    int fr[4];float lum[4],wv[4],mxw[4];
    int n=0,k=0,nc=0,nf=0;
    bestV=0.0;
    for(int f=1;f<frameCount;f++){
        if(int(fParam[f].w)!=5)continue;
        float v=1.0;
        if(k<validPlanesU)v=bmask[(k+1)*(cy1-cy0)*w2+(cy-cy0)*w2+cx];
        k++;
        vec2 O=originM(f,2*cx,2*cy)+(p-cell);
        vec3 uNum=vec3(0.0),uDen=vec3(0.0),uClip=vec3(0.0);
        usWide(uNum,uDen,uClip,f,O,1.0,kE.z>0.0?kE.z:0.25);
        if(uDen.y<=0.0&&uClip.y<=0.0)continue;
        float l=uDen.y>0.0?uNum.y/uDen.y:fParam[f].x*clipLevel;   // green clipped all around: at least its clip
        // The trust inside the light is for COLOURED light only (LEDs on PWM / ARGB effects: their colour and level differ
        // between the exposures and the cell check refuses whole edges of a line). A neutral light (a window, paper in the sun, a
        // lamp) keeps the strict check: there a refused cell is motion, and the N frames' clip is the right answer.
        vec3 wc=vec3(uDen.x>0.0?uNum.x/uDen.x:(uClip.x>0.0?fParam[f].x*clipLevel:l),l,uDen.z>0.0?uNum.z/uDen.z:(uClip.z>0.0?fParam[f].x*clipLevel:l));
        float wmx=max(wc.r,max(wc.g,wc.b));
        nf++;if(wmx>0.0&&(wmx-min(wc.r,min(wc.g,wc.b)))/wmx>=blendSatU)nc++;
        bestV=max(bestV,v);
        if(inLight&&nc*2>nf)v=max(v,0.3);   // coloured light (so far the frames' majority): a residual of the global alignment
        if(v<0.004||n>=4)continue;
        fr[n]=f;lum[n]=l;wv[n]=v;mxw[n]=wmx;n++;
    }
    U=vec3(0.0);cover=0.0;uclip=0u;lvl=0.0;
    coloured=nf>0&&nc*2>nf;
    if(n==0)return false;
    float s0=lum[0],s1=n>1?lum[1]:lum[0],s2=n>2?lum[2]:s1,s3=n>3?lum[3]:s2;
    float lo01=min(s0,s1),hi01=max(s0,s1),lo23=min(s2,s3),hi23=max(s2,s3);
    float med=n==1?s0:n==2?0.5*(s0+s1):n==3?max(min(s0,s1),min(max(s0,s1),s2)):0.5*(max(lo01,lo23)+min(hi01,hi23));
    float sw=0.0,sww=0.0;uclip=7u;
    for(int j=0;j<n;j++){
        float r=lum[j]/max(med,1.0e-6)-1.0;
        float w=wv[j]*exp(-(r*r)/max(blendU.x*blendU.x,1.0e-6));
        if(w<0.004)continue;
        int f=fr[j];
        uint cl=0u;
        U+=w*usColourAt(f,originM(f,2*cx,2*cy)+(p-cell),cl);
        lvl+=w*mxw[j];
        sw+=w;sww+=w*w;
        uclip&=cl;
    }
    if(sw<1.0e-4){uclip=0u;lvl=0.0;return false;}
    U/=sw;lvl/=sw;
    // the cover: the effective frames of the weighted mean (unequal weights count less; the plain sum told the noise reduction
    // of three frames where one dominated). On a lamp's clipped glow their grain against the N merge's (0.56 % against 0.33 %)
    // matches that count in the merge's units. Below 8 (bs packs the colours at their clip above it).
    cover=min(sw*sw/max(sww,1.0e-6),7.9);
    return true;
}
// Stages 0 and 1 (stage 1 stores the ultrashort colour and the weight of the pixel in bs, alpha < 0 = no ultrashort colour).
// The N marks of an output pixel beyond the strip's rows (the strip above was merged in another bank, the one below not yet) from
// the base frame instead: its 2x2 cell's brightest site (its rows are uploaded 3 cells around the strip, clamped beyond). Without
// them the clip's spread and the gate stopped on a straight line at every strip border.
float baseMarks(int qr,int qx,out float mb){
    int g=kG.x;
    int x=clamp(qx/g,0,size.x-1)&~1,y=clamp(2*cy0+(qr<0?qr-g+1:qr)/g,0,size.y-1)&~1;
    float v=0.0;bool clip=false;
    for(int k=0;k<4;k++){
        uint fl;float s=rawSite(0,x+(k&1),y+(k>>1),fl);
        clip=clip||(fl&2u)!=0u||s>=clipLevel;
        v=max(v,s);
    }
    mb=smoothstep(blendGateU.x,blendGateU.y,v);
    return clip||v>=0.98?1.0:smoothstep(blendClipU.x,blendClipU.y,v);
}
void regionStage(int i,int row,int ox,int rows,int ow,bool flags){
    int g=kG.x;
    vec3 N=vec3(outRgb[i*3],outRgb[i*3+1],outRgb[i*3+2]);
    float mxN=max(N.r,max(N.g,N.b));
    uint cfl=flags?cflags[i]:0u;
    bool nClipped=(cfl&7u)!=0u||mxN>=0.98;
    if(blendStageU==0){
        // stage 0: every pixel's N marks, read around by stage 1 (the N merge's clip is the union of its frames' clips and steps
        // by 2x2 cells, wider than the base frame's): bits 16-23 the clip mark (1 = a colour fell back to the clipped mean or
        // reached the clip), bits 24-31 the bright mark of the gate. The flags leave the worker as their low byte.
        if(flags){
            float mc=nClipped?1.0:smoothstep(blendClipU.x,blendClipU.y,mxN),mb=smoothstep(blendGateU.x,blendGateU.y,mxN);
            cflags[i]=(cfl&0xFFFFu)|(uint(mc*255.0+0.5)<<16)|(uint(mb*255.0+0.5)<<24);
        }
        return;
    }
    int x=ox/g,y=2*cy0+row/g,sx=ox-(ox/g)*g,sy=row-(row/g)*g;
    vec2 pos=vec2(float(x),float(y))+(g>1?(vec2(float(sx),float(sy))+0.5)/float(g)-0.5:vec2(0.0));
    float aCell=clamp(blendAt(pos),0.0,1.0);
    // The weight by region, the same for R, G and B (HybridTuning::bentoBlend): the N clip spread over its bright neighbourhood,
    // at least the base frame's cell weight; the N clip itself always 1.
    float alpha,reach=0.0,gateN=0.0,own=0.0;
    if(flags){
        own=float((cfl>>16)&255u)*(1.0/255.0);
        float gs=0.0,gw=0.0;
        int R=int(ceil(blendClipU.w));
        float kg=-0.5/max(blendGateU.z*blendGateU.z,0.01);
        for(int dy=-R;dy<=R;dy++)for(int dx=-R;dx<=R;dx++){
            float d2=float(dx*dx+dy*dy);
            if(d2>blendClipU.w*blendClipU.w)continue;
            int qr=row+dy;
            float m,mbq;
            if(qr>=0&&qr<rows){
                uint c=cflags[qr*ow+clamp(ox+dx,0,ow-1)];
                m=float((c>>16)&255u)*(1.0/255.0);mbq=float(c>>24)*(1.0/255.0);
            } else m=baseMarks(qr,clamp(ox+dx,0,ow-1),mbq);
            if(m>0.0)reach=max(reach,m*(1.0-smoothstep(blendClipU.z,blendClipU.w,sqrt(d2))));
            float k=exp(kg*d2);
            gs+=k*mbq;gw+=k;
        }
        gateN=gs/max(gw,1.0e-6);
        if(reach<=0.002&&aCell<=0.002){bs[i]=uvec4(0u,packHalf2x16(vec2(0.0,-1.0)),0u,0u);return;}
        alpha=max(max(own,aCell),reach*smoothstep(0.08,0.45,gateN));
    } else {
        if(aCell<=0.002&&!nClipped){bs[i]=uvec4(0u,packHalf2x16(vec2(0.0,-1.0)),0u,0u);return;}
        alpha=nClipped?1.0:max(aCell,smoothstep(blendPxU.x,blendPxU.y,mxN)*min(1.0,aCell*5.0));
    }
    vec3 U;float cover,bestV,lvl;uint uclip;bool coloured;
    bool inLight=nClipped||alpha>=0.5||reach>=0.5;
    bool got=usAt(pos,inLight,U,cover,uclip,coloured,bestV,lvl);
    // the gate also opens where the ultrashort colour itself is bright: its light 1-3 px beside the N frames' clipped one (the
    // residual of the global alignment on a thin LED line) is taken whole with the clip it replaces, not cut by N's dark side
    if(got&&flags)alpha=max(alpha,reach*smoothstep(0.08,0.45,max(gateN,smoothstep(blendGateU.x,blendGateU.y,lvl))));
    // The cells' check (the best ultrashort frame's validity: a dark object moved over a clipped window, a hand) scales the
    // weight - the N frames' clip is the right answer there. Not for coloured light (LEDs on PWM / ARGB effects: their colour
    // and level differ between the exposures, the check refused whole edges of a line and left the N clip's steps).
    if(!coloured)alpha*=clamp(bestV,0.0,1.0);
    // a pixel inside the light without any usable ultrashort sample (every frame invalid there): the nearest ultrashort colour
    // around it (8 directions, 2 px steps), so no white / lilac clipped spot stays inside a light
    if(!got&&nClipped&&blendPxU.z>0.0){
        for(int t=0;t<64&&!got;t++){
            float r=2.0*float(1+t/8);
            if(r>blendPxU.z)break;
            float an=0.7853982*float(t%8);
            got=usAt(pos+r*vec2(cos(an),sin(an)),true,U,cover,uclip,coloured,bestV,lvl);
        }
    }
    bs[i]=uvec4(packHalf2x16(U.rg),packHalf2x16(vec2(U.b,got?alpha:-1.0)),packHalf2x16(vec2(1.0)),
                packHalf2x16(vec2(1.0,got?cover+8.0*float(uclip&7u):0.0)));
}
// Stage 2: the ultrashort frames' gain against the N merge around the pixel, per colour: the mean log ratio N / ultrashort over
// the pixels around where both are measured - N in the light's glow (blendGainLoU.x of its clip up) but not clipped nor near it,
// the ultrashort colour not at its clip, their levels within x3 of each other (a moved or 1-3 px shifted light is not a gain).
// LEDs on PWM / ARGB animation shine in other colours during the 7 ms ultrashort exposures than over the N frames (G x0.55,
// B x0.67 on the glow of a ring): their own light sat in a dark magenta seam inside the N frames' glow. The mean of the logs is
// the light the N frames saw and stays near 1 on a pattern that moved (the ratio of sums took bright moved paper as a x0.7 pink
// gain). Two scales: within blendGainU.x px on a 2 px lattice, and where that has too little measured around (the inside of a
// large light) within 8/3 of it on an 8 px lattice; neither: the gain fades to 1. Its strength (blendGainLoU.y) comes from the
// tile check of the frames (HybridInput::usFlicker): daylight with paper in the wind keeps the ultrashort colour as captured.
void gainSums(int row,int ox,int rows,int ow,bool flags,int step,int R,float sigma,inout vec3 sl,inout float sw,inout float sAll){
    float kg=-0.5/max(sigma*sigma,0.25);
    // the lattice is anchored to multiples of the step: one that moved with the pixel gave every other column other samples,
    // a gain stepping by column (stripes on a glow, dots along a ring)
    int r0=(row/step)*step,x0=(ox/step)*step;
    for(int dy=-R;dy<=R+step;dy+=step)for(int dx=-R;dx<=R+step;dx+=step){
        int qr=r0+dy,qx=x0+dx;
        if(qr<0||qr>=rows||qx<0||qx>=ow)continue;
        float ddx=float(qx-ox),ddy=float(qr-row);
        float w=exp(kg*(ddx*ddx+ddy*ddy));
        sAll+=w;
        int q=qr*ow+qx;
        uvec4 bq=bs[q];
        vec2 qa=unpackHalf2x16(bq.y);
        if(qa.y<0.0)continue;                                           // no ultrashort colour there
        if(unpackHalf2x16(bq.w).y>=8.0)continue;                        // an ultrashort colour at its clip
        if(flags&&(cflags[q]&7u)!=0u)continue;                          // N from the clipped mean
        vec3 Nq=vec3(outRgb[q*3],outRgb[q*3+1],outRgb[q*3+2]);
        if(max(Nq.r,max(Nq.g,Nq.b))>=blendClipU.x)continue;             // N at / near its clip
        vec3 Uq=vec3(unpackHalf2x16(bq.x),qa.x);
        float ln=dot(max(Nq,vec3(0.0)),vec3(0.25,0.5,0.25)),lu=dot(max(Uq,vec3(0.0)),vec3(0.25,0.5,0.25));
        // the light's own glow only (N at least blendGainLo of its clip): darker pixels carry the ultrashort frames' small colour
        // bias of the dark (x8 gain), which a clipped neutral sky beside a dark frame took as a pink gain
        float mq=max(Nq.r,max(Nq.g,Nq.b));
        if(mq<blendGainLoU.x||lu<=1.0e-4||lu>3.0*ln||ln>3.0*lu)continue;
        vec3 e=vec3(0.05*mq);
        sl+=w*log((max(Nq,vec3(0.0))+e)/(max(Uq,vec3(0.0))+e));sw+=w;
    }
}
vec3 logGain(vec3 sl,float sw){
    return clamp(sl/max(sw,1.0e-6),-log(blendGainU.w),log(blendGainU.w));
}
void gainStage(int i,int row,int ox,int rows,int ow,bool flags){
    uvec4 b=bs[i];
    vec2 ba=unpackHalf2x16(b.y);
    bool px=ba.y>0.002,last=row==rows-1&&blendCarryU.x>0;
    if(!px&&!last)return;
    vec3 lk=vec3(0.0);
    if(blendGainU.x>0.0&&blendGainLoU.y>0.0){
        int R=int(blendGainU.x),R2=(R*8)/3;
        vec3 sl=vec3(0.0);float sw=0.0,sAll=0.0;
        gainSums(row,ox,rows,ow,flags,2,R,blendGainU.y,sl,sw,sAll);
        float cf=smoothstep(0.0,blendGainU.z,sw/max(sAll,1.0e-6));
        if(cf>0.0)lk=cf*logGain(sl,sw);
        if(cf<1.0){
            vec3 cl=vec3(0.0);float cw=0.0,cAll=0.0;
            gainSums(row,ox,rows,ow,flags,8,R2,0.5*float(R2),cl,cw,cAll);
            float cc=smoothstep(0.0,blendGainU.z,cw/max(cAll,1.0e-6));
            if(cc>0.0)lk+=(1.0-cf)*cc*logGain(cl,cw);
        }
        // The window ends at the strip's rows: the last rows of a strip saw only the rows above them, the first rows of the next
        // strip only the rows below, and the gain stepped there (x1.04 -> x0.75 in R across a ring). The first R2 rows start
        // from the gain the previous strip ended with in that column (blendCarryU) and pass into their own: continuous.
        // Only a column whose last row blended with its gain carries it (the continuity matters only there): a light that starts
        // just below the border takes its own gain, not the x1 of a row that had nothing to blend. (Weighing the carry by its
        // measured confidence instead broke the continuity it is for: a 25 % step inside a clipped lamp-lit wall.)
        if(blendCarryU.z!=0&&row<R2){
            uvec4 c=bs[blendCarryU.x+(1-blendCarryU.y)*ow+ox];
            vec2 cb=unpackHalf2x16(c.y);
            if(cb.y>0.5)lk=mix(vec3(unpackHalf2x16(c.x),cb.x),lk,smoothstep(0.0,float(R2),float(row)+0.5));
        }
    }
    if(last)bs[blendCarryU.x+blendCarryU.y*ow+ox]=uvec4(packHalf2x16(lk.rg),packHalf2x16(vec2(lk.b,px?1.0:0.0)),0u,0u);
    if(!px)return;
    vec3 k=exp(lk*blendGainLoU.y);
    bs[i].z=packHalf2x16(k.rg);
    bs[i].w=packHalf2x16(vec2(k.b,unpackHalf2x16(b.w).y));
}
void main(){
    int g=kG.x,ow=kG.y;
    int rows=2*(cy1-cy0)*g;
    int ox=int(gl_GlobalInvocationID.x),row=chunkU+int(gl_GlobalInvocationID.y);
    if(ox>=ow||row>=rows)return;
    if(kE.x>=0.0)return;                                   // a debug merge of one frame stays as merged
    int i=row*ow+ox;
    bool flags=blendU.y>0.5;
    if(blendStageU<=1){regionStage(i,row,ox,rows,ow,flags);return;}
    if(blendStageU==2){gainStage(i,row,ox,rows,ow,flags);return;}
    // Stage 3: the blend, the ultrashort colour times its gain - every colour alike, one at the frames' own clip too (that colour
    // kept at 1 stepped against the gained colours beside it: dots along a ring's edge). bentoBlendDebug: 1 the weight (grey), 2
    // the gained ultrashort colour alone (0 where none), 3 its gain.
    uvec4 b=bs[i];
    vec2 ba=unpackHalf2x16(b.y),bw=unpackHalf2x16(b.w);
    float alpha=ba.y;
    bool got=alpha>=0.0;
    vec3 U=vec3(unpackHalf2x16(b.x),ba.x),k=vec3(unpackHalf2x16(b.z),bw.x);
    uint uclip=got?uint(bw.y*0.125):0u;
    float cover=got?bw.y-8.0*float(uclip):0.0;
    vec3 Uk=U*k;
    if(blendPxU.w>0.5){
        vec3 d=blendPxU.w<1.5?vec3(max(alpha,0.0)):blendPxU.w<2.5?(got?Uk:vec3(0.0)):(got?k:vec3(0.0));
        outRgb[i*3]=d.x;outRgb[i*3+1]=d.y;outRgb[i*3+2]=d.z;
        return;
    }
    if(!got||alpha<=0.002)return;
    if(blendU.w>0.0){
        // the colour of the gained ultrashort frames over 5 x 5 px (a gaussian), its luma kept: three frames at x8 carry a
        // grainy weak colour (green on a red LED ring), the gain x2.4 made it white speckles on the ring (WhyCam smooths the
        // highlight colour the same way inside its mask)
        vec3 sc=vec3(0.0);
        for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++){
            int qr=row+dy,qx=ox+dx;
            if(qr<0||qr>=rows||qx<0||qx>=ow)continue;
            uvec4 bq=bs[qr*ow+qx];
            vec2 qa=unpackHalf2x16(bq.y);
            if(qa.y<=0.002)continue;   // only pixels whose gain stage 2 computed (the others keep the placeholder 1)
            vec3 Uq=max(vec3(unpackHalf2x16(bq.x),qa.x)*vec3(unpackHalf2x16(bq.z),unpackHalf2x16(bq.w).x),vec3(0.0));
            float a=exp(-0.25*float(dx*dx+dy*dy));
            sc+=a*Uq;
        }
        float lm=dot(sc,vec3(0.25,0.5,0.25)),lu=dot(max(Uk,vec3(0.0)),vec3(0.25,0.5,0.25));
        if(lm>1.0e-6&&lu>0.0)Uk=mix(Uk,sc*(lu/lm),blendU.w);
    }
    vec3 N=vec3(outRgb[i*3],outRgb[i*3+1],outRgb[i*3+2]);
    vec3 o=mix(N,Uk,alpha);
    outRgb[i*3]=o.x;outRgb[i*3+1]=o.y;outRgb[i*3+2]=o.z;
    effB[i]=mix(effB[i],cover,alpha);
    if(flags){
        // the colours of a mostly blended pixel are the ultrashort frames' (clipped only where every frame's colour is at its
        // clip), and the N merge's clip border (bits 3, 6) is not theirs: left on, the highlight recovery's defringe took every
        // LED dot clipped in the ultrashort frames, whose colours reach the clip one by one, for a clip border and drew a white
        // ring around it. Bit 4: inside the Bento blend, bit 5: an ultrashort colour at its own clip. Bits 16-31 (the stage-0
        // marks) stay as they are: only the low byte leaves the worker.
        uint cfl=cflags[i];
        uint hi=cfl&~255u,lo=cfl&255u;
        if(alpha>=0.5){lo&=~(7u|8u|64u);lo|=uclip&7u;if((uclip&7u)==0u)lo&=~32u;}
        lo|=16u;
        if((uclip&7u)!=0u)lo|=32u;
        cflags[i]=hi|lo;
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
    vec2 pos=vec2(float(x),float(y))+(g>1?(vec2(float(sx),float(sy))+0.5)/float(g)-0.5:vec2(0.0));
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

// ---------------------------------------------------------------------------------------------
// P29 native mosaic path (mosaicPath 1, hybridReconstructMosaicNative; research/ark23/quad_report.md section 8). New programs
// only: the plain-Bayer programs above are not touched (kHybMergeMain1 in particular: on Adreno 750 any edit of it made the merge
// ~15x slower). The Frames block holds the BINNED frames (one per frame, w x h = W/b x H/b): the guide, cells, rejection, dilation
// and the rim / Bento / chroma passes run on them as on any plain burst. The raw mosaic of every frame goes to the NFrames block
// (slot 12) and only the programs below read it.
// ---------------------------------------------------------------------------------------------
// Native frame access (after kHybCommon + kHybHelpers). Native canonical coordinates = sensor site - b cfaShift: the canonical shift
// of the binned frames in whole colour blocks, so (x & (b-1)) is the site's position inside its block, x >> log2 b the block (whose
// parity gives the colour), and the site classes (y & 7, x & 7) are taken in sensor coordinates.
static const char* kHybNatAccess=R"(
layout(std430,binding=12) readonly buffer NFrames{uint nwords[];};
layout(std140,binding=1) uniform NatTable{uvec4 nGeo[128];}; // [kHybridGpuFrames]: x = first site of frame f in NFrames, y = first
                                                              // sensor row held, z = rows held (>= 1)
uniform ivec4 natU;   // x = colour block b (2 | 4), y = W, z = H (native sensor sites), w = window half-width r (native px)
uniform ivec4 natV;   // x = log2 b, y = 1: the words are the sensor RAW (site gains applied here), 0: built like the split's sub-frames;
                      // z = first Sums counter of the native outlier counts, w = 1: the merge's full (2r+1)^2 window (mosaicWindowFull)
uniform int natMarkU; // 1: the words carry the site flags of kHybNatMark in bits 14 (outlier) / 15 (cell clipped)
uniform vec4 natW;    // x = white, y = the RAW code from which a site is clipped (white - 1)
uniform vec4 natGain[16]; // site-class gains (hybridMosaicGains), class (y & 7) * 8 + (x & 7) of the SENSOR site: natGain[k >> 2][k & 3]
// P268: the gains as a field (HybridGainField): [zone y][zone x][16 vec4], bilinear between the zone centres
layout(std430,binding=19) readonly buffer NGainField{vec4 ngf[];};
uniform ivec4 natGF;      // x, y = zones (x = 0: no field, natGain alone), z, w = zone size in sensor px
float natGainAt(int k,int X,int Y){
    if(natGF.x==0)return natGain[k>>2][k&3];
    float fx=clamp((float(X)+0.5)/float(natGF.z)-0.5,0.0,float(natGF.x-1)),fy=clamp((float(Y)+0.5)/float(natGF.w)-0.5,0.0,float(natGF.y-1));
    int x0=min(int(fx),natGF.x-2),y0=min(int(fy),natGF.y-2);
    float ax=fx-float(x0),ay=fy-float(y0);
    int i=(y0*natGF.x+x0)*16+(k>>2),c=k&3,r=natGF.x*16;
    float a=ngf[i][c]+(ngf[i+16][c]-ngf[i][c])*ax,b=ngf[i+r][c]+(ngf[i+r+16][c]-ngf[i+r][c])*ax;
    return a+(b-a)*ay;
}
// A site outside the frame: its BLOCK is reflected as reflectCfa() reflects a sub-frame site of the split (colour and the position
// inside the block kept).
int natReflect(int x,int n){
    int v=x>>natV.x;                 // floor division (the shift of a signed int extends the sign)
    int a=x-(v<<natV.x);
    v=reflectCfa(v,n>>natV.x);
    return (v<<natV.x)+a;
}
// Row inside the rows held (n, a multiple of b): a row past them becomes the first / last held row of the same position inside the
// block, as the split clamped a sub-frame row to its first / last held row (the margins of the upload make this rare).
int natRow(int r,int n){
    int m=natU.x-1;
    return r<0?(r&m):r>=n?max(n-natU.x,0)+(r&m):r;
}
// Native site (x, y) (canonical) of the frame whose NatTable entry is geo, normalised exactly as the split normalised its sub-frame
// site: the split built it as vb + (raw - black of the site) x site gain (vb = black of the block's Bayer phase, clamped to
// [0, white], white where the RAW clipped), then sampleRaw() took black and inv of the block's phase, clamped to [-0.25, 1]. With
// natV.y the word is the sensor RAW and that build happens here (S3: no gained copies; the same value without the copy's rounding).
// fl = site flags (bit 0 outlier, bit 1 cell clipped).
float natSiteG(uvec4 geo,int x,int y,out uint fl){
    int X=x+(cfaShift.x<<natV.x),Y=y+(cfaShift.y<<natV.x);
    if(X<0||Y<0||X>=natU.y||Y>=natU.z){X=natReflect(X,natU.y);Y=natReflect(Y,natU.z);}
    int ry=natRow(Y-int(geo.y),int(geo.z));
    uint idx=geo.x+uint(ry*natU.y+X);
    uint word=nwords[idx>>1];
    uint v=(idx&1u)==0u?(word&0xFFFFu):(word>>16);
    fl=natMarkU!=0?(v>>14):0u;v&=natMarkU!=0?0x3FFFu:0xFFFFu;
    int cp=(((Y>>natV.x)&1)<<1)|((X>>natV.x)&1);
    float u=float(v);
    if(natV.y!=0){
        int k=((Y&7)<<3)|(X&7);
        u=u>=natW.y?natW.x:clamp(black[cp]+(u-black[((Y&1)<<1)|(X&1)])*natGainAt(k,X,Y),0.0,natW.x);
    }
    return clamp((u-black[cp])*inv[cp],-0.25,1.0);
}
float natSite(int f,int x,int y,out uint fl){return natSiteG(nGeo[clamp(f,0,127)],x,y,fl);}
// P34: the merges' bound on the native kernel precision (HybridTuning::mosaicNativeClamp)
uniform int natClampU;  // 1: WhyCam's covariance packing range on the native precision, 2: the same bounds on its eigenvalues
// WhyCam v23 stores the kernel covariance C (exp2(-0.5 d'Cd), C = P / ln 2) in a texture with the ranges cov_range_rg / _b
// (quad_report, merge L22-23): C_xx, C_yy in [0.367, 24.8], C_xy in [-6.98, 7.03], i.e. an across-edge sigma >= 0.242 px.
// Clamped per component (1) a narrow kernel on a diagonal edge loses its orientation (diagonal capped, xy capped apart);
// 2 bounds the eigenvalues instead (sigma in [0.242, 1.98] px along both axes of the kernel, orientation kept).
vec3 natEig(vec3 P,float lo,float hi){ // eigenvalues of the precision clamped to [lo, hi], eigenvectors kept
    float m=0.5*(P.x+P.y),h=0.5*(P.x-P.y),r=sqrt(h*h+P.z*P.z);
    float l1=m+r,l2=m-r,c1=clamp(l1,lo,hi),c2=clamp(l2,lo,hi);
    if(r<1.0e-6)return vec3(c1,c1,0.0);
    vec3 vv=vec3(P.x-l2,P.y-l2,P.z)/(2.0*r); // v v' of the larger eigenvalue's unit eigenvector (xx, yy, xy)
    return vec3(c2+(c1-c2)*vv.x,c2+(c1-c2)*vv.y,(c1-c2)*vv.z);
}
vec3 natClamp(vec3 P){
    if(natClampU==1){
        // per component; C_xx capped at 17.19 with C_xy kept up to 4.87 can leave xx yy < xy^2 (a narrow kernel a few degrees off
        // an axis): an indefinite precision whose weight GROWS with distance along one direction (up to ~2^10 inside the 7x7
        // window). |xy| stays below sqrt(xx yy), so the kernel stays a kernel.
        vec3 C=vec3(clamp(P.xy,vec2(0.2544),vec2(17.19)),clamp(P.z,-4.838,4.873));
        float lim=0.99*sqrt(C.x*C.y);
        return vec3(C.xy,clamp(C.z,-lim,lim));
    }
    return natClampU==2?natEig(P,0.2544,17.19):P;
}
)";

// Native outlier sites, as kHybMean / kHybFlags do for a plain burst but on the native sites: the split tested its sub-frames, whose
// lattice is the same position inside the blocks (+-b px = adjacent blocks, other colours; +-2b px = same colour). kHybNatMean: the
// mean of the hot frames at the same SENSOR site for the flag rows +-2b.
static const char* kHybNatMean=R"(
layout(std430,binding=1) writeonly buffer MeanBuf{float meanv[];}; // native canonical rows [natRows.x - 2b, natRows.y + 2b)
uniform int hotList[16];
uniform ivec4 hotU;          // x = frames in hotList, y = words of the mean: the sites' temporal variance follows (P214k; 0 = none)
uniform ivec2 natRows;       // native canonical rows of the strip's site flags
void main(){
    int b=natU.x;
    int x=int(gl_GlobalInvocationID.x),r=chunkU+int(gl_GlobalInvocationID.y);
    if(x>=natU.y||r>=natRows.y-natRows.x+4*b)return;
    int y=natRows.x-2*b+r;
    float s=0.0;
    for(int l=0;l<hotU.x&&l<16;l++){uint fl;s+=natSite(hotList[l],x,y,fl);}
    meanv[uint(r*natU.y+x)]=s/float(max(hotU.x,1));
    if(hotU.y>0){ // P214k: the site's own variance over the hot frames (kHybMean)
        float m=s/float(max(hotU.x,1)),q=0.0;
        for(int l=0;l<hotU.x&&l<16;l++){uint fl;float d=natSite(hotList[l],x,y,fl)-m;q+=d*d;}
        meanv[uint(hotU.y+r*natU.y+x)]=q/float(max(hotU.x-1,1));
    }
}
)";
// kHybNatFlags: one word per native canonical site of the flag rows: bit 0 fixed-pattern outlier (mean of the hot frames), bit 1
// transient outlier of the base, bits 8..15 the cap rank (P214k). siteExcess / median8 as kHybFlags, the 5x5 lattice in steps of
// b px; noise of one native site.
static const char* kHybNatFlags=R"(
layout(std430,binding=9) buffer Sums{uint sums[];};
layout(std430,binding=1) readonly buffer MeanBuf{float meanv[];};
layout(std430,binding=2) writeonly buffer SiteFlags{uint sflags[];}; // native canonical rows [natRows.x, natRows.y)
uniform ivec4 hotU;          // x = frames in the mean (0: no fixed-pattern test), y = bit 0 fixed-pattern test, bit 1 base transient test,
                             // z = first word of the native cap block in Sums (P214k; 0 = no cap), w = first word of the temporal
                             // variance in MeanBuf (P214k; 0 = none)
uniform vec4 hotSigU;        // as kHybFlags
uniform ivec2 natRows;
uniform ivec2 natCount;      // native canonical rows counted for the report (the strip's own rows)
uniform vec2 natNoise;       // slope, offset of one native site of the base (normalised units)
float mAt(int x,int y){
    if(x<0||x>=natU.y)x=natReflect(x,natU.y);
    return meanv[uint((y-natRows.x+2*natU.x)*natU.y+x)];
}
float vAt(int x,int y){ // P214k: the temporal variance after the mean (as kHybFlags)
    if(x<0||x>=natU.y)x=natReflect(x,natU.y);
    return meanv[uint(hotU.w+(y-natRows.x+2*natU.x)*natU.y+x)];
}
#define CX(p,q) { float lo_=min(p,q); q=max(p,q); p=lo_; }
float median8(float a0,float a1,float a2,float a3,float a4,float a5,float a6,float a7){
    CX(a0,a2)CX(a1,a3)CX(a4,a6)CX(a5,a7) CX(a0,a4)CX(a1,a5)CX(a2,a6)CX(a3,a7) CX(a0,a1)CX(a2,a3)CX(a4,a5)CX(a6,a7)
    CX(a2,a4)CX(a3,a5) CX(a1,a4)CX(a3,a6) CX(a1,a2)CX(a3,a4)CX(a5,a6)
    return 0.5*(a3+a4);
}
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
    float level=(P5(-1,0)+P5(1,0)+P5(0,-1)+P5(0,1)+P5(-1,-1)+P5(1,-1)+P5(-1,1)+P5(1,1))*0.125;
    return vec4(c-med,med,cross,level);
}
void main(){
    int b=natU.x;
    int x=int(gl_GlobalInvocationID.x),r=chunkU+int(gl_GlobalInvocationID.y);
    if(x>=natU.y||r>=natRows.y-natRows.x)return;
    int y=natRows.x+r;
    bool green=phaseColor[(((y>>natV.x)&1)<<1)|((x>>natV.x)&1)]==1;
    uint bits=0u;
    float p[25];
    if(hotU.x>=3&&(hotU.y&1)!=0){
        for(int k=0;k<25;k++)p[k]=mAt(x+b*(k%5-2),y+b*(k/5-2));
        vec4 e=siteExcess(p,green);
        if(hotSigU.w<=0.0||e.w<hotSigU.w){
            float sd=sqrt(max((natNoise.x*max(e.y,0.0)+natNoise.y)/float(hotU.x),1.0e-14))*1.15;
            float ex=abs(e.x),cr=e.z*sign(e.x);
            if(ex>hotSigU.x*sd&&cr<hotSigU.z*ex&&e.y<0.9)bits|=1u;
            if((bits&1u)!=0u&&hotU.z>0){ // P214k: the cap rank (kHybFlags)
                vec2 m=hotU.w>0?capMeasured(capRing(x,y,green,b),vAt(x,y),float(hotU.x),sd):vec2(sd,0.0);
                bits|=capRank(p,green,ex,m.x,m.y)<<8u;
            }
        }
    }
    if((hotU.y&2)!=0){
        for(int k=0;k<25;k++){uint fl;p[k]=natSite(0,x+b*(k%5-2),y+b*(k/5-2),fl);}
        vec4 e=siteExcess(p,green);
        if(hotSigU.w<=0.0||e.w<hotSigU.w){
            float sd=sqrt(max(natNoise.x*max(e.y,0.0)+natNoise.y,1.0e-14))*1.1;
            if(e.x>hotSigU.y*sd&&e.z<hotSigU.z*e.x&&e.y<0.9)bits|=2u;
        }
    }
    sflags[uint(r*natU.y+x)]=bits;
    if(bits!=0u&&y>=natCount.x&&y<natCount.y){
        if((bits&1u)!=0u)atomicAdd(sums[natV.z],1u);
        if((bits&2u)!=0u)atomicAdd(sums[natV.z+1],1u);
        if((bits&1u)!=0u&&hotU.z>0)atomicAdd(sums[uint(hotU.z)+((bits>>8)&255u)],1u); // P214k: the strip's rank histogram
    }
}
)";

// Marks the native words of every frame of the strip (one invocation per word = two sites of a row), as kHybMark does for a plain
// burst: bit 14 = outlier site (fixed-pattern sites in every frame, transient ones in the base), bit 15 = the site's cell is clipped
// in this frame. The cell is the split's sub-frame cell: the sites at the same position inside the four blocks of the 2b x 2b
// RGGB super-cell (any non-outlier one >= clipLevel). Standalone program: the NFrames block is writable here.
static const char* kHybNatMark=R"(#version 310 es
precision highp float;
precision highp int;
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=12) buffer NFrames{uint nwords[];};
layout(std430,binding=2) readonly buffer SiteFlags{uint sflags[];}; // native canonical rows [mFlagsU.x, mFlagsU.y)
layout(std140,binding=1) uniform NatTable{uvec4 nGeo[128];};
uniform ivec4 natU;          // as kHybNatAccess
uniform ivec4 natV;
uniform vec4 natW;
uniform vec4 natGain[16];
layout(std430,binding=19) readonly buffer NGainField{vec4 ngf[];}; // P268: as kHybNatAccess
uniform ivec4 natGF;
float natGainAt(int k,int X,int Y){
    if(natGF.x==0)return natGain[k>>2][k&3];
    float fx=clamp((float(X)+0.5)/float(natGF.z)-0.5,0.0,float(natGF.x-1)),fy=clamp((float(Y)+0.5)/float(natGF.w)-0.5,0.0,float(natGF.y-1));
    int x0=min(int(fx),natGF.x-2),y0=min(int(fy),natGF.y-2);
    float ax=fx-float(x0),ay=fy-float(y0);
    int i=(y0*natGF.x+x0)*16+(k>>2),c=k&3,r=natGF.x*16;
    float a=ngf[i][c]+(ngf[i+16][c]-ngf[i][c])*ax,b=ngf[i+r][c]+(ngf[i+r+16][c]-ngf[i+r][c])*ax;
    return a+(b-a)*ay;
}
uniform ivec2 cfaShift;
uniform vec4 black;
uniform vec4 inv;
uniform float clipLevel;
uniform int frameIdx;        // first frame of this dispatch (+ z)
uniform int chunkU;
uniform ivec4 mFlagsU;       // x, y = native canonical rows of SiteFlags, z: 1 cell clip, 2 outliers
uint siteOutlier(int f,int x,int y){ // canonical site
    if((mFlagsU.z&2)==0||x<0||y<0||x>=natU.y||y<mFlagsU.x||y>=mFlagsU.y)return 0u;
    uint s=sflags[uint((y-mFlagsU.x)*natU.y+x)];
    return (s&1u)|(f==0?((s>>1)&1u):0u);
}
float siteValue(int f,int X,int Y){ // normalised sensor site inside the frame's rows (as natSiteG); flag bits masked off
    uvec4 geo=nGeo[f];
    int m=natU.x-1,n=int(geo.z),ry=Y-int(geo.y);
    X=X<0?(X&m):X>=natU.y?natU.y-natU.x+(X&m):X;          // as kHybMark clamps a sub-frame column: same position in the block
    ry=ry<0?(ry&m):ry>=n?max(n-natU.x,0)+(ry&m):ry;
    uint idx=geo.x+uint(ry*natU.y+X);
    uint word=nwords[idx>>1];
    uint v=((idx&1u)==0u?(word&0xFFFFu):(word>>16))&0x3FFFu;
    int cp=(((Y>>natV.x)&1)<<1)|((X>>natV.x)&1);
    float u=float(v);
    if(natV.y!=0){
        int k=((Y&7)<<3)|(X&7);
        u=u>=natW.y?natW.x:clamp(black[cp]+(u-black[((Y&1)<<1)|(X&1)])*natGainAt(k,X,Y),0.0,natW.x);
    }
    return (u-black[cp])*inv[cp];
}
bool cellClipped(int f,int x,int y){ // canonical site
    int s=natV.x,b=natU.x,m=b-1;
    int x0=((x>>(s+1))<<(s+1))+(x&m),y0=((y>>(s+1))<<(s+1))+(y&m);
    for(int p=0;p<4;p++){
        int cx=x0+(p&1)*b,cy=y0+(p>>1)*b;
        if(siteOutlier(f,cx,cy)!=0u)continue;
        if(siteValue(f,cx+(cfaShift.x<<s),cy+(cfaShift.y<<s))>=clipLevel)return true;
    }
    return false;
}
void main(){
    int f=frameIdx+int(gl_GlobalInvocationID.z);
    int wx=int(gl_GlobalInvocationID.x),row=chunkU+int(gl_GlobalInvocationID.y);
    if(f>127||wx>=natU.y/2||row>=int(nGeo[f].z))return;
    int Y=int(nGeo[f].y)+row;
    uint idx=nGeo[f].x+uint(row*natU.y+2*wx);
    uint word=nwords[idx>>1],read=word;
    for(int k=0;k<2;k++){
        int X=2*wx+k;
        int x=X-(cfaShift.x<<natV.x),y=Y-(cfaShift.y<<natV.x); // canonical
        uint fl=siteOutlier(f,x,y);
        if((mFlagsU.z&1)!=0&&x>=0&&y>=0&&cellClipped(f,x,y))fl|=2u;
        uint sh=uint(16*k);
        word=(word&~(0xC000u<<sh))|((fl&3u)<<(14u+sh));
    }
    if(word!=read)nwords[idx>>1]=word; // P76: as kHybMark, an unchanged word is not written back
}
)";

// The native merge (after kHybMergeCommon + kHybNatAccess): one invocation per output pixel of the strip, output layout exactly as
// kHybMergeMain1 on the grid b of the binned frames (outRgb / eff / cflags rows of the strip, row width W), so the effective map and
// the clip flags downstream work unchanged. kHybChroma / kHybBento / kHybRim do NOT apply to it: they sample the binned base at the
// binned position of the output pixel ((ox + 0.5) / b - 0.5, i.e. native ox), while this merge writes native ox - (b-1)/2, and they
// correct with sums of the binned base that this merge never added (P29 review: chroma on, Quad, one frame: edges -1.2 dB, flat
// noise +8 %). hybridReconstructMosaicNative turns them off; native variants of them are not built.
// Accumulation: WhyCam v23 Quad Sabre merge kernel (SampleNeighborhoodQuadRBF, source given to the SCAMERA owner by the WhyCam
// author, research/ark23, its Quad Sabre merge kernel; its logic is ported here with the author's permission): every raw site of
// the window around the frame's sample position, at its native position, colour from its block, anisotropic RBF in native px.
// Ours around it: the binned guide's covariance scaled to native px (natK.x = 1 / (b mosaicKernelScale)^2), bilinear between the
// cell centres at the sample position (S0's sample_cov; the donors' own 6.1 covariance the same way), the binned rejection per frame,
// the F6 offset of the binned frame, site flags and clip handling as frameSamples, base frame last with the widening rule.
// Geometry (the split's convention, which the app and tools/quad/eval_mosaic_burst.py expect): output pixel X sits at native
// sensor position X - (b-1)/2; binned pixel B holds the block b B .. b B + b-1 (centre b B + (b-1)/2).
static const char* kHybMergeMosaic=R"(
// P188 (owner, 2026-10-17: grids, staircases and square halos on every ISZ / FULLRES shot): a donor's robustness bilinear between the 4
// nearest cell centres (cell (cx, cy) holds binned px 2cx..2cx+1, centre 2cx + 0.5) instead of the nearest cell's: the nearest cell
// switched every 4 output px (Quad; 8 Tetra), in phase with the colour lattice - square halos around small dark dots, staircases
// along edges, blocky LCD text. natBilinU 0 = the nearest cell as before (mosaicRobustBilinear 0).
uniform int natBilinU;
float natRobust(int f,vec2 B0,int cx,int cy,int w2){
    int base=(f-1)*(cy1-cy0)*w2;
    if(natBilinU==0)return robust[base+(cy-cy0)*w2+cx];
    vec2 c=(B0-0.5)*0.5,c0=floor(c),t=c-c0;
    int x0=clamp(int(c0.x),0,w2-1),x1=clamp(int(c0.x)+1,0,w2-1);
    int y0=clamp(int(c0.y),cy0,cy1-1),y1=clamp(int(c0.y)+1,cy0,cy1-1);
    float r00=robust[base+(y0-cy0)*w2+x0],r10=robust[base+(y0-cy0)*w2+x1];
    float r01=robust[base+(y1-cy0)*w2+x0],r11=robust[base+(y1-cy0)*w2+x1];
    return mix(mix(r00,r10,t.x),mix(r01,r11,t.x),t.y);
}
uniform vec4 natK; // x = binned precision -> native precision, y / z = ks^2 of green / red-blue (WhyCam's distance multipliers per
                   // colour, mosaicKernelG / RB), w = minimum R / B support of the colour fill relative to green (0 = off)
uniform int natRy1;    // end of the guide cell rows held in Cov (ry1: the strip's cells + 3, clamped to the frame)
uniform int natWidenU; // 6.1 base widening: 0 = the split's rule b^2 F + b^2 - 1 < widenBelow, 1 = S0's rule F < widenBelow,
                       // 2 = smooth by F (any mode): sigma x mix(natWidenS.x, 1, smoothstep(0, natWidenS.y, F))
uniform vec2 natWidenS;
// Precision of the binned guide at binned position B, bilinear between the cell centres (2 c + 0.5) (S0, m5ref.sample_cov): the
// nearest cell kept P constant over 2b x 2b native px squares. Rows within the held ones [ry0, natRy1) (the strip's own +-1 rows are
// always held except at the frame's border, where S0 clamps as well), columns within the frame.
vec3 natCov(vec2 B){
    int w2=size.x/2;
    vec2 c=clamp((B-0.5)*0.5,vec2(0.0,float(ry0)),vec2(float(w2-1),float(natRy1-1)));
    int x0=min(int(floor(c.x)),max(w2-2,0)),y0=max(min(int(floor(c.y)),natRy1-2),ry0);
    int x1=min(x0+1,w2-1),y1=min(y0+1,natRy1-1);
    vec2 t=clamp(c-vec2(float(x0),float(y0)),0.0,1.0);
    vec3 p0=mix(cov[(y0-ry0)*w2+x0].xyz,cov[(y0-ry0)*w2+x1].xyz,t.x);
    vec3 p1=mix(cov[(y1-ry0)*w2+x0].xyz,cov[(y1-ry0)*w2+x1].xyz,t.x);
    return mix(p0,p1,t.y);
}
// The same for the donor's own 6.1 covariance (mergeMode & 1) at its binned position Ob (dcovAt clamps to the cells it holds).
vec3 natDcov(int f,vec2 Ob){
    vec2 c=(Ob-0.5)*0.5,c0=floor(c),t=c-c0;
    int x0=int(c0.x),y0=int(c0.y);
    vec3 p0=mix(dcovAt(f,x0,y0),dcovAt(f,x0+1,y0),t.x);
    vec3 p1=mix(dcovAt(f,x0,y0+1),dcovAt(f,x0+1,y0+1),t.x);
    return mix(p0,p1,t.y);
}
// S5 (mosaicChromaFill 1): WhyCam v23's colour-difference regularisation of SampleNeighborhoodQuadRBF (ported with the author's
// permission, research/ark23, its Quad Sabre merge kernel): a colour whose kernel weight in this frame is below natK.w x the
// green weight gets the missing weight at G + (mean R (B) - mean G) of the 3x3 colour blocks around the sample, G = this frame's
// kernel-weighted green; before the frame's weight is applied. Block means of the unflagged, unclipped sites, each >= 0 (WhyCam's
// clamp); a colour with clipped samples in this frame keeps its clipped mean (a lower bound), no fill.
void natFill(inout vec3 num,inout vec3 den,vec3 cd,uvec4 geo,vec2 O,float g){
    int s=natV.x,b=natU.x,bb=natU.x*natU.x;
    ivec2 q=ivec2(floor(O+0.5))>>s;   // block of the nearest site
    vec3 sum=vec3(0.0),cnt=vec3(0.0);
    for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++){
        int bx=q.x+i,by=q.y+j;
        int c=phaseColor[((by&1)<<1)|(bx&1)];
        float bs=0.0,bn=0.0;
        for(int t=0;t<bb;t++){
            uint fl;
            float v=natSiteG(geo,(bx<<s)+(t&(b-1)),(by<<s)+(t>>s),fl);
            if((fl&3u)!=0u||v>=clipLevel)continue;
            bs+=v;bn+=1.0;
        }
        if(bn>0.0){sum[c]+=max(bs/bn,0.0);cnt[c]+=1.0;}
    }
    if(cnt.y<=0.0)return;
    float gv=num.y/den.y,gm=sum.y/cnt.y*g,need=natK.w*den.y;
    for(int k=0;k<2;k++){
        int c=2*k;
        if(den[c]>=need||cd[c]>0.0||cnt[c]<=0.0)continue;
        num[c]+=max(gv+sum[c]/cnt[c]*g-gm,0.0)*(need-den[c]);
        den[c]=need;
    }
}
// The native sites of frame f around O (canonical native px), weighted by the kernel P (native px), into a: as frameSamples (outlier
// site: no sample; clipped site or a site of a clipped cell: the clipped mean; a clipped longer frame: nothing) with the frame's
// weight r. Window: natV.w = 1 (mosaicWindowFull, S0's full window): the (2r+1)^2 sites around the nearest site N = floor(O + 0.5),
// d in (-r-1/2, r+1/2] per axis; otherwise as the split: win = the 6.1 window |d| <= r per axis (r = 1.5 b: the split's +-1.5
// sub-frame px), else d in (-4r/3, 4r/3] per axis (r = 1.5 b: the two lattice sites per axis and phase of frameSamples on the split's
// sub-frames, +-2 sub-frame px). With natV.w = 0, Quad r = 3 and Tetra r = 6 reproduce the split's sites exactly.
// The frame's sums are formed first and weighted by r once (the colour fill of S5 works on them).
void natSamples(inout Acc a,int f,vec2 O,float r,float cover,vec3 P,bool win){
    float g=fParam[f].x;
    int role=int(fParam[f].w);
    uvec4 geo=nGeo[clamp(f,0,127)];
    int s=natV.x;
    float R=float(natU.w);
    int x0,y0,x1,y1;
    float Rn=R*(4.0/3.0);
    if(natV.w!=0){ivec2 N=ivec2(floor(O+0.5));x0=N.x-natU.w;y0=N.y-natU.w;x1=N.x+natU.w;y1=N.y+natU.w;}
    else if(win){x0=int(ceil(O.x-R));y0=int(ceil(O.y-R));x1=int(floor(O.x+R));y1=int(floor(O.y+R));}
    else{x0=int(floor(O.x-Rn))+1;y0=int(floor(O.y-Rn))+1;x1=int(floor(O.x+Rn));y1=int(floor(O.y+Rn));}
    // at most ceil(8r/3) sites per axis whatever O is (a non-finite position must not make the loop unbounded)
    int span=int(ceil(2.0*Rn))-1;
    x1=min(x1,x0+span);y1=min(y1,y0+span);
    vec3 num=vec3(0.0),den=vec3(0.0),cn=vec3(0.0),cd=vec3(0.0);float cv=0.0,uc=0.0;
    for(int sy=y0;sy<=y1;sy++){
        float dy=float(sy)-O.y;
        int py=((sy>>s)&1)<<1;
        for(int sx=x0;sx<=x1;sx++){
            float dx=float(sx)-O.x;
            int c=phaseColor[py|((sx>>s)&1)];
            float kw=exp2(-0.72135*(c==1?natK.y:natK.z)*(dx*dx*P.x+dy*dy*P.y+2.0*dx*dy*P.z))+kD.z;
            if(kw<0.002&&f!=0&&role!=5)continue;
            uint fl;
            float v=natSiteG(geo,sx,sy,fl);
            if((fl&1u)!=0u)continue;                           // outlier site: no sample at all
            if((fl&2u)!=0u||v>=clipLevel){
                if(role==3)continue;                           // a clipped longer frame is only a lower bound below the base's
                cn[c]+=kw*v*g;cd[c]+=kw;
                if(role==5)uc+=kw;
                continue;
            }
            num[c]+=kw*v*g;den[c]+=kw;
            if(c==1)cv+=kw;
        }
    }
    if(natK.w>0.0&&den.y>1.0e-7&&(den.x<natK.w*den.y||den.z<natK.w*den.y))natFill(num,den,cd,geo,O,g);
    a.num+=r*num;a.den+=r*den;a.clipNum+=r*cn;a.clipDen+=r*cd;a.usClip+=r*uc;a.cover+=cover*cv;
}
// Round-4 kernel (coverage rule of the base widening): the split merged the base's other b^2-1 site classes as donors (their
// sub-frames always pass the rejection against class (0, 0)), and their green kernel weight counted as coverage. The same weight here
// (the donor kernel P, unflagged unclipped sites), so the base widens where it widened in the split.
float natSelfCover(vec2 O,vec3 P,bool win){
    uvec4 geo=nGeo[0];
    int s=natV.x,m=natU.x-1;
    float R=float(natU.w);
    int x0,y0,x1,y1;
    float Rn=R*(4.0/3.0);
    if(natV.w!=0){ivec2 N=ivec2(floor(O+0.5));x0=N.x-natU.w;y0=N.y-natU.w;x1=N.x+natU.w;y1=N.y+natU.w;}
    else if(win){x0=int(ceil(O.x-R));y0=int(ceil(O.y-R));x1=int(floor(O.x+R));y1=int(floor(O.y+R));}
    else{x0=int(floor(O.x-Rn))+1;y0=int(floor(O.y-Rn))+1;x1=int(floor(O.x+Rn));y1=int(floor(O.y+Rn));}
    int span=int(ceil(2.0*Rn))-1;
    x1=min(x1,x0+span);y1=min(y1,y0+span);
    float cv=0.0;
    for(int sy=y0;sy<=y1;sy++){
        float dy=float(sy)-O.y;
        int py=((sy>>s)&1)<<1;
        for(int sx=x0;sx<=x1;sx++){
            if(phaseColor[py|((sx>>s)&1)]!=1||((sx&m)==0&&(sy&m)==0))continue;
            float dx=float(sx)-O.x;
            float kw=exp2(-0.72135*natK.y*(dx*dx*P.x+dy*dy*P.y+2.0*dx*dy*P.z))+kD.z;
            if(kw<0.002)continue;
            uint fl;
            float v=natSiteG(geo,sx,sy,fl);
            if((fl&3u)!=0u||v>=clipLevel)continue;
            cv+=kw;
        }
    }
    return cv;
}
// No sample of colour c at all (outlier sites under the window): the mean of the base's unflagged sites of that colour in the
// no-window range around O (baseFill of the split).
float natBaseFill(int c,vec2 O){
    float Rn=float(natU.w)*(4.0/3.0);
    int s=natV.x,n=int(ceil(2.0*Rn));
    int x0=int(floor(O.x-Rn))+1,y0=int(floor(O.y-Rn))+1;
    float sum=0.0,cnt=0.0;
    for(int j=0;j<n;j++)for(int i=0;i<n;i++){
        int sx=x0+i,sy=y0+j;
        if(phaseColor[(((sy>>s)&1)<<1)|((sx>>s)&1)]!=c)continue;
        uint fl;
        float v=natSite(0,sx,sy,fl);
        if((fl&1u)!=0u)continue;
        sum+=v;cnt+=1.0;
    }
    return cnt>0.0?sum*fParam[0].x/cnt:0.0;
}
void main(){
    int g=kG.x,ow=kG.y,w2=size.x/2;
    int ox=int(gl_GlobalInvocationID.x),row=chunkU+int(gl_GlobalInvocationID.y);
    if(ox>=ow||row>=2*(cy1-cy0)*g)return;
    int b=natU.x,bb=b*b;
    float o=0.5*float(b-1);
    float cs=0.25*float(bb); // natClamp's bounds are WhyCam's Quad range (native px of 2x2 blocks): scaled by (b/2)^2 in precision
    // native canonical position of the output pixel (X - (b-1)/2); P56: on a grid finer than the sites (g = 2b, Sabre 2x of a
    // Quad stream) the output pixel X sits at native (X + 0.5) b / g - 0.5 (exactly X when g = b)
    vec2 S=(vec2(float(ox),float(2*cy0*g+row))+0.5)*(float(b)/float(g))-0.5-o;
    vec2 B0=(S-o)/float(b);                       // the same in the binned base frame
    ivec2 bi=ivec2(floor(B0+0.5));                // nearest binned pixel: its cell gives the robustness, Bento mask and F6 offset
    int cx=clamp(bi.x>>1,0,w2-1),cy=clamp(bi.y>>1,cy0,cy1-1);
    vec3 P=natCov(B0);                            // the guide: bilinear between the cell centres
    float m=kD.w>0.5?bmask[(cy-cy0)*w2+cx]:0.0;
    vec2 cell=vec2(float(2*cx),float(2*cy));
    int mode=mergeModeU.x;
    float wb=1.0-m;
    if(kE.x>=0.0||mergeModeU.y!=0)wb=0.0;
    Acc a;initAcc(a);
    float frames=0.0; // accepted donor frames
    for(int f=1;f<frameCount;f++){
        float r=natRobust(f,B0,cx,cy,w2);
        if(r<0.004)continue;
        if(kE.x>=0.0&&int(kE.x)!=f)continue;
        vec2 Ob=originM(f,2*cx,2*cy)+(B0-cell);    // binned position in frame f (homography and F6 offset of the cell)
        bool us=int(fParam[f].w)==5;
        vec3 Pf;
        if(us)Pf=vec3(kE.y,kE.y,0.0);
        else if((mode&1)!=0)Pf=natDcov(f,Ob)*fParam[f].z;
        else Pf=P*fParam[f].z;
        float rw=r/max(fParam[f].y,1.0e-6);
        if(!us)frames+=rw;
        natSamples(a,f,Ob*float(b)+o,r,rw,natClamp(Pf*(natK.x*cs))/cs,(mode&2)!=0&&!us);
    }
    // Base frame last. 6.1 rule (natWidenU 0) as in the split, where each frame was b^2 sub-frames and the base's own other b^2-1
    // sub-frames were accepted donors: b^2 frames + b^2-1 < widenBelow; natWidenU 1: S0's frames < widenBelow. Round-4 rule on the
    // coverage including those sites.
    if(wb>0.0){
        vec3 Pb;
        if(natWidenU==2){ float widen=mix(natWidenS.x,1.0,smoothstep(0.0,natWidenS.y,frames)); Pb=P/(widen*widen); }
        else if((mode&4)!=0)Pb=(natWidenU!=0?frames:float(bb)*frames+float(bb-1))<kD.x?P/(kD.y*kD.y):P;
        else { float self=natSelfCover(S,P*natK.x,(mode&2)!=0);
               float widen=mix(kD.y,1.0,smoothstep(0.5*kD.x,kD.x,a.cover+self)); Pb=P/(widen*widen); }
        natSamples(a,0,S,wb,wb,natClamp(Pb*(natK.x*cs))/cs,(mode&2)!=0&&m<=0.0);
    }
    vec3 col;uint cfl=0u;
    for(int c=0;c<3;c++){
        // P268 (owner, 2026-10-19: X200 Ultra 2x / wide crops, dark dots and ragged borders along every clipped highlight
        // «нам нужно чтобы таких артефактов не было и без бенто, причём в любом режиме»): a clipped sample is a lower bound of
        // its site, not a missing one. Its colour from the unclipped samples alone took the darker side of a clip edge (a pixel
        // whose own sites clipped came out at the mean of its dark neighbours: G 0.90 -> 0.45 -> 1.00 across a screw head);
        // with the clipped samples at their clip level it is at least that mean (a brighter unclipped estimate, the
        // ultrashort's or a shorter frame's, stays).
        if(a.den[c]>1.0e-7){col[c]=a.num[c]/a.den[c];
            if(a.clipDen[c]>0.0)col[c]=max(col[c],(a.num[c]+a.clipNum[c])/(a.den[c]+a.clipDen[c]));}
        else if(a.clipDen[c]>0.0){col[c]=a.clipNum[c]/a.clipDen[c];cfl|=1u<<uint(c);}
        else col[c]=(natMarkU!=0&&wb>0.0)?natBaseFill(c,S):0.0;
        if(a.clipDen[c]>0.0)cfl|=8u;
    }
    int i=row*ow+ox;
    outRgb[i*3]=col.x;outRgb[i*3+1]=col.y;outRgb[i*3+2]=col.z;
    eff[i]=a.cover+wb;
    if(mergeModeU.z!=0||rimU.x>0.0){
        if(m>0.0)cfl|=16u;
        if(a.usClip>0.0&&(cfl&7u)!=0u)cfl|=32u;
        if(rimU.x>0.0&&(cfl&8u)!=0u&&a.den[1]>1.0e-7){
            float e=0.0;
            for(int c=0;c<3;c++)e=max(e,a.clipDen[c]/max(a.den[c]+a.clipDen[c],1.0e-20));
            cfl|=uint(clamp(e,0.0,1.0)*63.0+0.5)<<8;
        }
        if(rimU.x>0.0&&(cfl&8u)!=0u)cfl|=64u;
        cflags[i]=cfl;
    }
}
)";

// P34: the native Quad merge restructured for speed after WhyCam v23's Quad Sabre merge kernel (research/ark23): whole colour
// blocks are loaded once per frame and output pixel (its q_cache of normalised 2x2 blocks), the loops have constant bounds, the
// site normalisation runs once per site and strip instead of once per tap. kHybMergeMosaic read every site of the window with a
// uniform-bound loop, one 16-bit load per tap behind the kernel test and the site-gain arithmetic per tap: ~4 ns per tap on
// Adreno 750, 54 s per 12 MP Quad merge.
// kHybNatNorm: every held site of every frame of the strip once, normalised exactly as natSiteG (site gains, black, white clip),
// flags in the float bits (outlier site: all ones; site of a clipped cell: bit 30, which no value in [-0.25, 1] has), one uvec4
// per 2x2 sensor cell (TL, TR, BL, BR) of the frame's held rows; NatTable w = the frame's first cell. Quad: the cell is the colour
// block; Tetra (b = 4): a quarter of the 4x4 colour block (WhyCam's "2x2-of-2x2" texel, quad_report section 8.5), colour from the
// 4x4 block's parity. The held rows of a Tetra frame start at and span a multiple of 4 sensor rows (natPlan), so a Tetra block
// is always 2x2 whole cells.
static const char* kHybNatNorm=R"(
layout(std430,binding=18) writeonly buffer NNorm{uvec4 nnorm[];};
uniform int frameIdx;
void main(){
    int f=frameIdx+int(gl_GlobalInvocationID.z);
    int bx=int(gl_GlobalInvocationID.x),rb=chunkU+int(gl_GlobalInvocationID.y);
    if(f>=frameCount||f>127)return;
    uvec4 geo=nGeo[f];
    int BW=natU.y>>1;
    if(bx>=BW||rb>=(int(geo.z)>>1))return;
    uvec4 o;
    for(int k=0;k<4;k++){
        int X=2*bx+(k&1),ry=2*rb+(k>>1),Y=int(geo.y)+ry; // sensor site
        uint idx=geo.x+uint(ry*natU.y+X);
        uint word=nwords[idx>>1];
        uint v=(idx&1u)==0u?(word&0xFFFFu):(word>>16);
        uint fl=natMarkU!=0?(v>>14):0u;v&=natMarkU!=0?0x3FFFu:0xFFFFu;
        int cp=(((Y>>natV.x)&1)<<1)|((X>>natV.x)&1); // colour block b = 1 << natV.x (Tetra: the 2x2 cell is a quarter of a 4x4 block)
        float u=float(v);
        if(natV.y!=0){
            int kk=((Y&7)<<3)|(X&7);
            u=u>=natW.y?natW.x:clamp(black[cp]+(u-black[((Y&1)<<1)|(X&1)])*natGainAt(kk,X,Y),0.0,natW.x);
        }
        uint bits=floatBitsToUint(clamp((u-black[cp])*inv[cp],-0.25,1.0));
        o[k]=(fl&1u)!=0u?0xFFFFFFFFu:(fl&2u)!=0u?(bits|0x40000000u):bits;
    }
    nnorm[geo.w+uint(rb*BW+bx)]=o;
}
)";
// kHybMergeMosaicFast (after kHybMergeCommon + kHybNatAccess; #define NAT_B = colour block 2 (Quad) | 4 (Tetra), NAT_R = window
// half-width (Quad 1..3, Tetra 1..8), NAT_MARKED = the strip's sites carry flags, NAT_FILL = the S5 R / B fill): kHybMergeMosaic
// with the full window (mosaicWindowFull 1) on the 2x2 cells of kHybNatNorm. The (2r+1)^2 sites around N = floor(O + 0.5) lie in
// the (r+1)^2 cells from (N - r) >> 1 (for any r); the cells' sites outside the window weigh 0. A cell is one colour for both
// blocks: Quad colour from the cell's parity, Tetra from its 4x4 block's ((cell >> 1) parity, WhyCam's "2x2-of-2x2" texel), so the
// loop, the one-kernel-constant-per-cell rule and the register budget are the Quad's. Same sites, kernel, weights, flags and clip
// handling as kHybMergeMosaic (the sums in another order: last-bit differences). With NAT_B 2 the preprocessed text is the P34
// Quad program's.
static const char* kHybMergeMosaicFast=R"(
// P188 (owner, 2026-10-17: grids, staircases and square halos on every ISZ / FULLRES shot): a donor's robustness bilinear between the 4
// nearest cell centres (cell (cx, cy) holds binned px 2cx..2cx+1, centre 2cx + 0.5) instead of the nearest cell's: the nearest cell
// switched every 4 output px (Quad; 8 Tetra), in phase with the colour lattice - square halos around small dark dots, staircases
// along edges, blocky LCD text. natBilinU 0 = the nearest cell as before (mosaicRobustBilinear 0).
uniform int natBilinU;
float natRobust(int f,vec2 B0,int cx,int cy,int w2){
    int base=(f-1)*(cy1-cy0)*w2;
    if(natBilinU==0)return robust[base+(cy-cy0)*w2+cx];
    vec2 c=(B0-0.5)*0.5,c0=floor(c),t=c-c0;
    int x0=clamp(int(c0.x),0,w2-1),x1=clamp(int(c0.x)+1,0,w2-1);
    int y0=clamp(int(c0.y),cy0,cy1-1),y1=clamp(int(c0.y)+1,cy0,cy1-1);
    float r00=robust[base+(y0-cy0)*w2+x0],r10=robust[base+(y0-cy0)*w2+x1];
    float r01=robust[base+(y1-cy0)*w2+x0],r11=robust[base+(y1-cy0)*w2+x1];
    return mix(mix(r00,r10,t.x),mix(r01,r11,t.x),t.y);
}
layout(std430,binding=18) readonly buffer NNorm{uvec4 nnorm[];};
uniform vec4 natK;     // as kHybMergeMosaic
uniform int natRy1;
uniform int natWidenU;
uniform vec2 natWidenS; // as kHybMergeMosaic
uniform float rampKU;   // P309: as kHybChroma (the native base demosaic fades out over share 0..0.5 under the ramp)
#if defined(NAT_US_PASS) && NAT_US_PASS
uniform vec2 natUsU;      // P313: x = HybridTuning::mosaicUsFillShare
uniform int validPlanesU; // the ultrashort frames' check planes after Bento's mask (bentoValidate; 0 = the mask holds the check)
// Bento's weight of the k-th ultrashort frame at binned B0: mask x its check plane per cell (as kHybDilate gave it, without A),
// between the 4 nearest cell centres as natRobust (natBilinU)
float natUsCell(int k,int x,int y,int w2){
    int i=(y-cy0)*w2+x;
    return bmask[i]*(k<validPlanesU?bmask[(k+1)*(cy1-cy0)*w2+i]:1.0);
}
float natUsW(int k,vec2 B0,int cx,int cy,int w2){
    if(natBilinU==0)return natUsCell(k,cx,cy,w2);
    vec2 c=(B0-0.5)*0.5,c0=floor(c),t=c-c0;
    int x0=clamp(int(c0.x),0,w2-1),x1=clamp(int(c0.x)+1,0,w2-1);
    int y0=clamp(int(c0.y),cy0,cy1-1),y1=clamp(int(c0.y)+1,cy0,cy1-1);
    return mix(mix(natUsCell(k,x0,y0,w2),natUsCell(k,x1,y0,w2),t.x),mix(natUsCell(k,x0,y1,w2),natUsCell(k,x1,y1,w2),t.x),t.y);
}
#endif
#define NB (NAT_R+1)
vec3 natCov(vec2 B){
    int w2=size.x/2;
    vec2 c=clamp((B-0.5)*0.5,vec2(0.0,float(ry0)),vec2(float(w2-1),float(natRy1-1)));
    int x0=min(int(floor(c.x)),max(w2-2,0)),y0=max(min(int(floor(c.y)),natRy1-2),ry0);
    int x1=min(x0+1,w2-1),y1=min(y0+1,natRy1-1);
    vec2 t=clamp(c-vec2(float(x0),float(y0)),0.0,1.0);
    vec3 p0=mix(cov[(y0-ry0)*w2+x0].xyz,cov[(y0-ry0)*w2+x1].xyz,t.x);
    vec3 p1=mix(cov[(y1-ry0)*w2+x0].xyz,cov[(y1-ry0)*w2+x1].xyz,t.x);
    return mix(p0,p1,t.y);
}
vec3 natDcov(int f,vec2 Ob){
    vec2 c=(Ob-0.5)*0.5,c0=floor(c),t=c-c0;
    int x0=int(c0.x),y0=int(c0.y);
    vec3 p0=mix(dcovAt(f,x0,y0),dcovAt(f,x0+1,y0),t.x);
    vec3 p1=mix(dcovAt(f,x0,y0+1),dcovAt(f,x0+1,y0+1),t.x);
    return mix(p0,p1,t.y);
}
// Canonical colour block (bx, by) of the frame whose NatTable entry is geo: reflected at the frame's border and clamped to its
// held rows block-wise, as natSiteG reflects / clamps a site (position inside the block kept).
int natReflB(int v,int n){v=v<0?-v:v;v=v>=n?2*n-2-v:v;return clamp(v,0,n-1);}
#if NAT_B==4
// Tetra: (bx, by) is a 2x2 cell, (bx >> 1, by >> 1) its 4x4 colour block. natSiteG / natRow reflect and clamp whole 4x4 blocks
// and keep the position inside the block, i.e. the cell's half (bx & 1, by & 1).
uvec4 natBlock(uvec4 geo,int bx,int by){
    int BW=natU.y>>1;
    int tx=bx+(cfaShift.x<<1),ty=by+(cfaShift.y<<1); // sensor cells (cfaShift in 4x4 blocks)
    int sx=(natReflB(tx>>1,natU.y>>2)<<1)|(tx&1),sy=(natReflB(ty>>1,natU.z>>2)<<1)|(ty&1);
    int rt=sy-(int(geo.y)>>1);
    int rb=(clamp(rt>>1,0,max((int(geo.z)>>2)-1,0))<<1)|(rt&1);
    return nnorm[geo.w+uint(rb*BW+sx)];
}
#else
uvec4 natBlock(uvec4 geo,int bx,int by){
    int BW=natU.y>>1;
    int sx=natReflB(bx+cfaShift.x,BW),sy=natReflB(by+cfaShift.y,natU.z>>1);
    int rb=clamp(sy-(int(geo.y)>>1),0,max((int(geo.z)>>1)-1,0));
    return nnorm[geo.w+uint(rb*BW+sx)];
}
#endif
float natVal(uint u,out bool ok,out bool cl){
#if NAT_MARKED
    ok=u!=0xFFFFFFFFu;
    float v=ok?uintBitsToFloat(u&0xBFFFFFFFu):0.0; // an outlier's bits are a NaN: weight 0 times NaN would poison the sums
    cl=(u&0x40000000u)!=0u||v>=clipLevel;
#else
    ok=true;
    float v=uintBitsToFloat(u);
    cl=v>=clipLevel;
#endif
    return v;
}
// Sums of one frame per colour over the window: valid samples (nm, dn) and clipped ones (cn, cd). The (r+1)^2 colour blocks from
// (N - r) >> 1 hold the window's (2r+1)^2 sites; one block at a time (one colour, one kernel constant), its four sites tested
// against the window on their coordinates, kernel and weights exactly as kHybMergeMosaic writes them. selfCover: natSelfCover's sum
// instead (green sites other than the block's first, unflagged, unclipped, the donor rule kw >= 0.002).
// Register-light on purpose: the first form (a row of blocks in a local array, WhyCam's precomputed column terms dx^2 Cxx /
// 2 dx Cxy in local arrays, per block phase sums, a window test on the cache indices) was miscompiled by the Adreno 750 driver
// (OPPO PHY110, 2026-10): some R / B taps got wrong weights (syn_b2, round-4 kernel: R / B off by up to 0.28 at ~6000 pixels,
// G exact), and small changes of its text moved the error around instead of removing it.
struct NatSums{vec3 nm;vec3 dn;vec3 cn;vec3 cd;};
NatSums natTaps(uvec4 geo,vec2 O,vec3 P,bool skipSmall,bool skipClip,bool selfCover){
    ivec2 N=ivec2(floor(O+0.5)),q0=(N-NAT_R)>>1;
    NatSums s;s.nm=vec3(0.0);s.dn=vec3(0.0);s.cn=vec3(0.0);s.cd=vec3(0.0);
    for(int J=0;J<NB;J++)for(int I=0;I<NB;I++){
#if NAT_B==4
        int bx=q0.x+I,by=q0.y+J,ph=(((by>>1)&1)<<1)|((bx>>1)&1); // cell (bx, by) of the 4x4 block (bx >> 1, by >> 1)
        bool first=((bx|by)&1)==0;                                   // the cell holding the block's first site
#else
        int bx=q0.x+I,by=q0.y+J,ph=((by&1)<<1)|(bx&1); // canonical RGGB: phase 0 red, 1 / 2 green, 3 blue
#endif
        bool green=ph==1||ph==2;
        if(selfCover&&!green)continue;
        float kk=-0.72135*(green?natK.y:natK.z);
        uvec4 u=natBlock(geo,bx,by);
        float nm=0.0,dn=0.0,cn=0.0,cd=0.0;
        for(int t=0;t<4;t++){
            int sx=2*bx+(t&1),sy=2*by+(t>>1);
            float dx=float(sx)-O.x,dy=float(sy)-O.y;
            bool ok,cl;
            float v=natVal(u[t],ok,cl);
            float kw=exp2(kk*(dx*dx*P.x+dy*dy*P.y+2.0*dx*dy*P.z))+kD.z;
            bool use=abs(sx-N.x)<=NAT_R&&abs(sy-N.y)<=NAT_R&&ok;
#if NAT_B==4
            if(selfCover)use=use&&!(t==0&&first)&&!cl&&kw>=0.002;
#else
            if(selfCover)use=use&&t!=0&&!cl&&kw>=0.002;
#endif
            else use=use&&!(skipSmall&&kw<0.002)&&!(skipClip&&cl);
            if(!use)continue;
            if(cl){cn+=kw*v;cd+=kw;} else {nm+=kw*v;dn+=kw;}
        }
        vec3 m=ph==0?vec3(1.0,0.0,0.0):ph==3?vec3(0.0,0.0,1.0):vec3(0.0,1.0,0.0);
        s.nm+=m*nm;s.dn+=m*dn;s.cn+=m*cn;s.cd+=m*cd;
    }
    return s;
}
#if NAT_FILL
// natFill of kHybMergeMosaic on the blocks
void natFillC(inout vec3 num,inout vec3 den,vec3 cd,uvec4 geo,vec2 O,float g){
#if NAT_B==4
    ivec2 q=ivec2(floor(O+0.5))>>2; // Tetra: the 3x3 colour blocks of 4x4 sites (2x2 cells each) around the nearest site
    vec3 sum=vec3(0.0),cnt=vec3(0.0);
    for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++){
        int bx=q.x+i,by=q.y+j;
        int c=((by&1)<<1)|(bx&1);c=c==0?0:c==3?2:1;
        float bs=0.0,bn=0.0;
        for(int h=0;h<4;h++){
            uvec4 u=natBlock(geo,2*bx+(h&1),2*by+(h>>1));
            for(int t=0;t<4;t++){
                bool ok,cl;
                float v=natVal(u[t],ok,cl);
                if(!ok||cl)continue;
                bs+=v;bn+=1.0;
            }
        }
        if(bn>0.0){sum[c]+=max(bs/bn,0.0);cnt[c]+=1.0;}
    }
#else
    ivec2 q=ivec2(floor(O+0.5))>>1;
    vec3 sum=vec3(0.0),cnt=vec3(0.0);
    for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++){
        int bx=q.x+i,by=q.y+j;
        int c=((by&1)<<1)|(bx&1);c=c==0?0:c==3?2:1;
        uvec4 u=natBlock(geo,bx,by);
        float bs=0.0,bn=0.0;
        for(int t=0;t<4;t++){
            bool ok,cl;
            float v=natVal(u[t],ok,cl);
            if(!ok||cl)continue;
            bs+=v;bn+=1.0;
        }
        if(bn>0.0){sum[c]+=max(bs/bn,0.0);cnt[c]+=1.0;}
    }
#endif
    if(cnt.y<=0.0)return;
    float gv=num.y/den.y,gm=sum.y/cnt.y*g,need=natK.w*den.y;
    for(int k=0;k<2;k++){
        int c=2*k;
        if(den[c]>=need||cd[c]>0.0||cnt[c]<=0.0)continue;
        num[c]+=max(gv+sum[c]/cnt[c]*g-gm,0.0)*(need-den[c]);
        den[c]=need;
    }
}
#endif
// natSamples of kHybMergeMosaic (full window) on the blocks
void natFrame(inout Acc a,int f,vec2 O,float r,float cover,vec3 P){
    float g=fParam[f].x;
    int role=int(fParam[f].w);
    uvec4 geo=nGeo[clamp(f,0,127)];
    NatSums s=natTaps(geo,O,P,f!=0&&role!=5,role==3,false);
    vec3 num=s.nm*g,den=s.dn,cn=s.cn*g,cd=s.cd;
#if NAT_FILL
    if(natK.w>0.0&&den.y>1.0e-7&&(den.x<natK.w*den.y||den.z<natK.w*den.y))natFillC(num,den,cd,geo,O,g);
#endif
    a.num+=r*num;a.den+=r*den;a.clipNum+=r*cn;a.clipDen+=r*cd;a.cover+=cover*den.y;
    if(role==5)a.usClip+=r*(cd.x+cd.y+cd.z);
}
#if NAT_MARKED
// P204: a colour with no sample at all gets the base's unflagged sites around it - in its own pass (NAT_BASEFILL) over the
// pixels the merge marked: inside the merge program this loop made the Adreno 750 run the marked merge 35-50x slower (OPPO X7
// Ultra 50 MP, 19 frames: 216 s against 3.8 s unmarked; 12 s with the fill moved out), for the same sites and values.
const float NAT_FILL_MARK=-1.0e30;
float natBaseFill(int c,vec2 O){
    float Rn=float(natU.w)*(4.0/3.0);
    int s=natV.x,n=int(ceil(2.0*Rn));
    int x0=int(floor(O.x-Rn))+1,y0=int(floor(O.y-Rn))+1;
    float sum=0.0,cnt=0.0;
    for(int j=0;j<n;j++)for(int i=0;i<n;i++){
        int sx=x0+i,sy=y0+j;
        if(phaseColor[(((sy>>s)&1)<<1)|((sx>>s)&1)]!=c)continue;
        uint fl;
        float v=natSite(0,sx,sy,fl);
        if((fl&1u)!=0u)continue;
        sum+=v;cnt+=1.0;
    }
    return cnt>0.0?sum*fParam[0].x/cnt:0.0;
}
#endif
#if defined(NAT_DEMOSAIC) && NAT_DEMOSAIC
// P282: the base frame alone, demosaicked from all its sites at the native position S (a pixel the donors left to it).
// Cells: 2x2 sites (a Quad colour block; a quarter of a Tetra block). Their means give the colour differences R - G and B - G,
// each taken against the green of its own row (Gr for red, Gb for blue) or of its own column, by the local direction - the
// phase of the blocks cancels; a Gr / Gb disagreement larger than the colour itself is aliasing and damps it. Every site in the
// window then gives a green-equivalent value (its colour difference taken off; red and blue sites count less where the colour is
// strong), summed with a cone kernel stretched along the local edge: the luma at site resolution, without the block lattice.
// Returns false (the merge's value stays) at a clipped or flagged site of the window.
#if NAT_B==4
#define DEM_NC 5
#else
#define DEM_NC 3
#endif
uniform vec4 natDemU; // x = share at F = 0, y = anisotropy, z = cone slope (1 / radius in sites), w = cells a side (3 Quad, 5 Tetra)
uniform vec2 natDemD; // the structure / noise ratio from which (x) to which (y) the demosaic takes over
bool natDemosaic(vec2 S,float g,out vec3 rgb,out float detail){
    int s=natV.x;
    ivec2 N=ivec2(floor(S+0.5));
    ivec2 c0=(N>>1)-ivec2(int(natDemU.w)>>1);
    int nc=int(natDemU.w);
    float sumC[4]=float[4](0.0,0.0,0.0,0.0),wC[4]=float[4](0.0,0.0,0.0,0.0);
    // per-cell arrays at the block's own size (Adreno keeps 9 floats in registers; 25-float arrays went to memory: 1.4x slower)
    float cm[DEM_NC*DEM_NC];vec2 cd[DEM_NC*DEM_NC];
#if NAT_B==2
    float cg[DEM_NC*DEM_NC];
#endif
    float vx=0.0,vy=0.0,meanAll=0.0,nAll=0.0,grad=0.0;
    vec3 lo=vec3(1.0e9),hi=vec3(-1.0e9); // each colour's range over the cells: the result stays inside it (no overshoot at edges)
    for(int j=0;j<5;j++){
        if(j>=nc)break;
        for(int i=0;i<5;i++){
            if(i>=nc)break;
            int cx=c0.x+i,cy=c0.y+j,x=2*cx,y=2*cy;
            uint f0,f1,f2,f3;
            float a=natSite(0,x,y,f0),b=natSite(0,x+1,y,f1),c=natSite(0,x,y+1,f2),d=natSite(0,x+1,y+1,f3);
            if(((f0|f1|f2|f3)&3u)!=0u||max(max(a,b),max(c,d))>=0.995)return false;
            int ph=(((y>>s)&1)<<1)|((x>>s)&1);
            float m=0.25*(a+b+c+d);
            int col=ph==0?0:ph==3?2:1;
            lo[col]=min(lo[col],min(min(a,b),min(c,d)));hi[col]=max(hi[col],max(max(a,b),max(c,d)));
            vec2 dd=(vec2(float(x)+0.5,float(y)+0.5)-S)*0.5;
            float w=exp2(-0.35*dot(dd,dd));
            sumC[ph]+=m*w;wC[ph]+=w;
            cm[j*DEM_NC+i]=m;cd[j*DEM_NC+i]=dd;
#if NAT_B==2
            cg[j*DEM_NC+i]=0.25*(abs(b-a)+abs(d-c)+abs(c-a)+abs(d-b)); // a cell on an edge: its own sites disagree
#endif
            float gx=0.5*(abs(b-a)+abs(d-c)),gy=0.5*(abs(c-a)+abs(d-b));
            vx+=gx;vy+=gy;grad+=0.5*(gx+gy);
            meanAll+=m;nAll+=1.0;
        }
    }
    float own=meanAll/max(nAll,1.0);
    // structure against the base's noise: |a - b| of two noisy samples averages 1.128 sigma (fNoiseP[0]: variance slope, offset)
    float sig=sqrt(max(fNoiseP[0].x*max(own,0.0)+fNoiseP[0].y,1.0e-10));
    detail=grad/max(nAll,1.0)/(1.128*sig);
    float mr=wC[0]>0.0?sumC[0]/wC[0]:own,mgr=wC[1]>0.0?sumC[1]/wC[1]:own,mgb=wC[2]>0.0?sumC[2]/wC[2]:own,mb=wC[3]>0.0?sumC[3]/wC[3]:own;
    // canonical RGGB: Gr (phase 1) shares the red row and the blue column, Gb (phase 2) the blue row and the red column
    float dRow=abs(mr-mgr)+abs(mb-mgb),dCol=abs(mr-mgb)+abs(mb-mgr);
    // the cross-phase pairs only by how much the row and the column pairing disagree, and only where one pairing finds no colour
    // (a neutral detail at the block's period differs along one of them; a colour - flat or in detail - along both)
    float qn=min(dRow,dCol)/(0.1*own+3.0*sig+1.0e-5),neutral=1.0/(1.0+qn*qn);
    vx+=2.0*neutral*max(dRow-dCol,0.0);vy+=2.0*neutral*max(dCol-dRow,0.0);
    // the direction across cells from same-colour cells (the nearest one of the same phase per axis)
    for(int j=0;j<5;j++){
        if(j>=nc)break;
        for(int i=0;i<5;i++){
            if(i>=nc)break;
            int x=2*(c0.x+i),y=2*(c0.y+j);
            int ph=(((y>>s)&1)<<1)|((x>>s)&1);
            for(int o=1;o<=2;o++){
                if(i+o>=nc)break;
                if(((((y>>s)&1)<<1)|(((2*(c0.x+i+o))>>s)&1))==ph){vx+=abs(cm[j*DEM_NC+i+o]-cm[j*DEM_NC+i]);break;}
            }
            for(int o=1;o<=2;o++){
                if(j+o>=nc)break;
                if((((((2*(c0.y+j+o))>>s)&1)<<1)|((x>>s)&1))==ph){vy+=abs(cm[(j+o)*DEM_NC+i]-cm[j*DEM_NC+i]);break;}
            }
        }
    }
    float e=1.0e-4+0.015*own;
    float vx2=(vx+e)*(vx+e),vy2=(vy+e)*(vy+e);
    float wRow=vy2/(vx2+vy2),wCol=1.0-wRow,aniso=abs(wRow-wCol);
    float dR=wRow*(mr-mgr)+wCol*(mr-mgb),dB=wRow*(mb-mgb)+wCol*(mb-mgr);
    float alias=1.5*abs(mgr-mgb)*(1.0-0.7*aniso)/(sqrt(dR*dR+dB*dB)+0.02*own+1.0e-6);
    float damp=1.0/(1.0+alias*alias);
    float gEst=0.5*(mgr+mgb),den=gEst*gEst+0.01;
    float wcR=1.0/(1.0+4.0*dR*dR/den),wcB=1.0/(1.0+4.0*dB*dB/den);
    // the cone along the edge: across a vertical structure (horizontal variation) narrower in x
    float st=natDemU.y*aniso;
    vec2 ax=vec2(1.0+2.0*st*wCol,1.0+2.0*st*wRow);
    float k=natDemU.z;
    int R=int(ceil(1.0/k))+1;
    // two passes over the window: the greens alone, then red and blue sites by how far their green equivalent is from that green
    // (a site across an edge, whose block colour difference is the other side's, adds no zipper)
    float lumG=0.0,wG=0.0,lum=0.0,lw=0.0;
    for(int pass=0;pass<2;pass++){
        for(int j=-4;j<=4;j++){
            if(abs(j)>R)continue;
            for(int i=-4;i<=4;i++){
                if(abs(i)>R)continue;
                int x=N.x+i,y=N.y+j;
                int ph=(((y>>s)&1)<<1)|((x>>s)&1);
                bool green=ph==1||ph==2;
                if(green!=(pass==0))continue;
                float dx=float(x)-S.x,dy=float(y)-S.y;
                float w=max(1.0-k*sqrt(ax.x*dx*dx+ax.y*dy*dy),0.0);
                if(w<=0.0)continue;
                uint fl;
                float v=natSite(0,x,y,fl);
                if((fl&3u)!=0u||v>=0.995)return false;
                if(green){lumG+=w*v;wG+=w;continue;}
                float ge=ph==0?v-dR:v-dB;
                float wc=ph==0?wcR:wcB;
                if(wG>0.0&&s==1){ // Quad: greens in every 2x2 neighbourhood (a Tetra block can hold none near S)
                    float gg=lumG/wG,tau=2.5*sig+0.04*gg+1.0e-4;
                    float q=(ge-gg)/tau;
                    wc/=1.0+q*q;
                }
                lum+=w*wc*ge;lw+=w*wc;
            }
        }
        if(pass==0){
            lum=lumG;lw=wG;
            // the colour differences per red / blue cell against its own row's and column's nearest green cells, the cells
            // weighted by how close that green is to the green at S (and narrowed across the edge): at a red / green edge R - G
            // flips sign, and a mean over cells on both sides gives neither (a fringe); the side S's green belongs to gives it
            if(wG>0.0){
                float GS=lumG/wG,tauG=1.5*sig+0.015*GS+1.0e-4,tauC=2.0*sig+0.03*GS+1.0e-4;
                float sa=6.0*natDemU.y*aniso;
                vec2 fa=vec2(1.0+sa*wCol,1.0+sa*wRow);
                float nR=0.0,sR=0.0,nB=0.0,sB=0.0;
                for(int j=0;j<5;j++){
                    if(j>=nc)break;
                    for(int i=0;i<5;i++){
                        if(i>=nc)break;
                        int x=2*(c0.x+i),y=2*(c0.y+j);
                        int ph=(((y>>s)&1)<<1)|((x>>s)&1);
                        if(ph==1||ph==2)continue;
                        int pr=ph==0?1:2,pc=ph==0?2:1; // canonical RGGB: red's row partner Gr, column Gb; blue's the other way
                        float hs=0.0,hn=0.0,vs=0.0,vn=0.0,eg=0.0;
#if NAT_B==2
                        eg=cg[j*DEM_NC+i];
#endif
                        for(int side=-1;side<=1;side+=2){
                            bool fh=false,fv=false;
                            for(int o=1;o<=2;o++){
                                int ii=i+side*o,jj=j+side*o;
                                if(!fh&&ii>=0&&ii<nc&&(((y>>s)&1)<<1|((2*(c0.x+ii)>>s)&1))==pr){hs+=cm[j*DEM_NC+ii];hn+=1.0;fh=true;
#if NAT_B==2
                                    eg=max(eg,wRow*cg[j*DEM_NC+ii]);
#endif
                                }
                                if(!fv&&jj>=0&&jj<nc&&((((2*(c0.y+jj))>>s)&1)<<1|((x>>s)&1))==pc){vs+=cm[jj*DEM_NC+i];vn+=1.0;fv=true;
#if NAT_B==2
                                    eg=max(eg,wCol*cg[jj*DEM_NC+i]);
#endif
                                }
                            }
                        }
                        if(hn<=0.0||vn<=0.0)continue;
                        float gk=wRow*hs/hn+wCol*vs/vn;
                        vec2 dd=cd[j*DEM_NC+i];
                        float q=(gk-GS)/tauG;
                        float qe=s==1?eg/tauC:0.0; // Quad: a cell (or its partner) across the edge gives a mixed colour difference
                                                  // (a Tetra cell is a quarter of its block: the penalty only lost detail there)
                        float w=exp2(-0.35*(fa.x*dd.x*dd.x+fa.y*dd.y*dd.y))/((1.0+q*q)*(1.0+qe*qe));
                        if(ph==0){sR+=w*(cm[j*DEM_NC+i]-gk);nR+=w;}else{sB+=w*(cm[j*DEM_NC+i]-gk);nB+=w;}
                    }
                }
                if(nR>1.0e-6)dR=sR/nR;
                if(nB>1.0e-6)dB=sB/nB;
                wcR=1.0/(1.0+4.0*dR*dR/den);wcB=1.0/(1.0+4.0*dB*dB/den);
            }
        }
    }
    if(lw<=1.0e-6)return false;
    float G=lum/lw;
    vec3 o=vec3(G+dR*damp,G,G+dB*damp);
    // the luma inside the greens' range (no overshoot at an edge); red and blue keep their colour difference to it (a clamp of
    // their own sites' range would bring the block aliasing back)
    if(hi[1]>=lo[1]){float Gc=clamp(o.y,lo[1],hi[1]);o+=vec3(Gc-o.y);}
    rgb=max(o,vec3(0.0))*g;
    return true;
}
#endif
void main(){
    int g=kG.x,ow=kG.y,w2=size.x/2;
    int ox=int(gl_GlobalInvocationID.x),row=chunkU+int(gl_GlobalInvocationID.y);
    if(ox>=ow||row>=2*(cy1-cy0)*g)return;
#if NAT_B==4
    vec2 X=vec2(float(ox),float(2*cy0*g+row));
    vec2 S=g==4?X-1.5:(X+0.5)*(4.0/float(g))-2.0; // native canonical position of the output pixel (X - (b-1)/2; P56: on the Sabre
                                                  // 2x grid g = 8, X = (X + 0.5) 4 / g - 0.5; the sensor grid's expression kept)
    vec2 B0=(S-1.5)*0.25;                         // the same in the binned base frame
#else
    vec2 S=(vec2(float(ox),float(2*cy0*g+row))+0.5)*(2.0/float(g))-1.0; // native position of the output pixel (X - (b-1)/2;
                                                  // P56: X = (X + 0.5) 2 / g - 0.5 on the 2x grid g = 4, exactly X when g = 2)
    vec2 B0=(S-0.5)*0.5;                          // the same in the binned base frame
#endif
#if defined(NAT_BASEFILL) && NAT_BASEFILL
    {
        int i=row*ow+ox;
        for(int c=0;c<3;c++)if(outRgb[i*3+c]==NAT_FILL_MARK)outRgb[i*3+c]=natBaseFill(c,S);
        return;
    }
#endif
    ivec2 bi=ivec2(floor(B0+0.5));
    int cx=clamp(bi.x>>1,0,w2-1),cy=clamp(bi.y>>1,cy0,cy1-1);
#if defined(NAT_US_PASS) && NAT_US_PASS
    {
        // P313 (mosaicUsFill): the ultrashort frames after the merge, which ran as without Bento (the N frames and the base at full
        // weight in Bento's cells, the ultrashort frames left out: kHybDilate / the merge got Bento off) and left the clipped share e
        // of its kernel weight (the largest of R, G, B; 1 where a colour had clipped samples only) in its clip flags (bits 8..13, as
        // for kHybRim). The ultrashort colour takes share smoothstep(0, natUsU.x, e) x Bento's weight ut of the cell, one share for
        // R, G and B (per colour they left blocks of another hue, P310), and only raises the merged colour: max(u, v) (the merge's
        // value is at least the clipped samples' lower bound; an ultrashort frame darker than it - its gain, its noise - would cut
        // a dark rim into the highlight). Bits 6 and 8..13 are cleared, bit 4 set where the mask is on (the merge's flags without
        // Bento otherwise). Its own pass: on Adreno 750 any change of the merge program's text (the ultrashort sums inside it, or
        // only the base at full weight and the ultrashort frames skipped in its loop) made the merge 9..30x slower.
        if(kE.x>=0.0)return;
        int i=row*ow+ox;
        uint cfl=cflags[i],in0=cfl;
        float e=(cfl&7u)!=0u?1.0:float((cfl>>8)&63u)*(1.0/63.0);
        cfl&=~0x3F40u;
        if(bmask[(cy-cy0)*w2+cx]>0.0)cfl|=16u;
        if(e>0.0){
            vec2 cell=vec2(float(2*cx),float(2*cy));
            vec3 un=vec3(0.0),ud=vec3(0.0),ucn=vec3(0.0),ucd=vec3(0.0);float ut=0.0,ucv=0.0,uclip=0.0;
            int k=0;
            for(int f=1;f<frameCount;f++){
                if(int(fParam[f].w)!=5)continue;
                float r=natUsW(k,B0,cx,cy,w2);k++;
                if(r<0.004)continue;
                vec2 Ob=originM(f,2*cx,2*cy)+(B0-cell);
                float g=fParam[f].x;
                vec3 Pu=vec3(kE.y,kE.y,0.0);
#if NAT_B==4
                NatSums su=natTaps(nGeo[clamp(f,0,127)],Ob*4.0+1.5,natClamp(Pu*(natK.x*4.0))*0.25,false,false,false);
#else
                NatSums su=natTaps(nGeo[clamp(f,0,127)],Ob*2.0+0.5,natClamp(Pu*natK.x),false,false,false);
#endif
                un+=(r*g)*su.nm;ud+=r*su.dn;ucn+=(r*g)*su.cn;ucd+=r*su.cd;
                ut=max(ut,clamp(r,0.0,1.0));ucv+=r*su.dn.y;uclip+=r*(su.cd.x+su.cd.y+su.cd.z);
            }
            float sh=ut*smoothstep(0.0,natUsU.x,e);
            if(sh>0.0){
                for(int c=0;c<3;c++){
                    if(ud[c]<=1.0e-7&&ucd[c]<=0.0)continue;
                    float u;
                    if(ud[c]>1.0e-7){u=un[c]/ud[c];if(ucd[c]>0.0)u=max(u,(un[c]+ucn[c])/(ud[c]+ucd[c]));}
                    else u=ucn[c]/ucd[c]; // only clipped ultrashort samples: their clipped mean (a lower bound)
                    float v=outRgb[i*3+c];
                    outRgb[i*3+c]=mix(v,max(u,v),sh);
                    if(ud[c]>1.0e-7)cfl&=~(1u<<uint(c));
                    if(ucd[c]>0.0)cfl|=8u;
                }
                eff[i]+=sh*ucv;
                if(uclip>0.0&&(cfl&7u)!=0u)cfl|=32u;
            }
        }
        if(cfl!=in0)cflags[i]=cfl;
        return;
    }
#endif
#if defined(NAT_DEMOSAIC) && NAT_DEMOSAIC
    {
        // P282: the share of the base's own demosaic: 1 - smoothstep(0, K, F) of the donors' accepted frames F, as the widening
        float rampT=1.0;
        if(kD.w>0.5){
            float mm=bmask[(cy-cy0)*w2+cx];
            if(rampKU>0.0){if(mm>0.0){rampT=1.0-smoothstep(0.0,0.5,mm/max(mm+rampKU*(1.0-mm),1.0e-6));if(rampT<=0.0)return;}}
            else if(mm>0.0)return; // Bento's highlights keep the merge
        }
        if(kE.x>=0.0||mergeModeU.y!=0)return;
        float K=natWidenS.y>0.0?natWidenS.y:kD.x;
        float frames=0.0;
        for(int f=1;f<frameCount;f++){
            if(int(fParam[f].w)==5)continue;
            float r=natRobust(f,B0,cx,cy,w2);
            if(r<0.004)continue;
            frames+=r/max(fParam[f].y,1.0e-6);
            if(frames>=K)return; // the merge alone from here (most of a static frame: a few donors' reads, not all)
        }
        float t=natDemU.x*(1.0-smoothstep(0.0,max(K,1.0e-3),frames))*rampT;
        if(t<=1.0e-3)return;
        vec3 d;float detail;
        if(!natDemosaic(S,fParam[0].x,d,detail))return;
        t*=smoothstep(natDemD.x,natDemD.y,detail); // flat areas keep the merge's smooth kernel (less noise); structure takes the demosaic
        int i=row*ow+ox;
        for(int c=0;c<3;c++)outRgb[i*3+c]=mix(outRgb[i*3+c],d[c],t);
        return;
    }
#endif
    vec3 P=natCov(B0);
    float m=kD.w>0.5?bmask[(cy-cy0)*w2+cx]:0.0;
    vec2 cell=vec2(float(2*cx),float(2*cy));
    int mode=mergeModeU.x;
    float wb=1.0-m;
    if(kE.x>=0.0||mergeModeU.y!=0)wb=0.0;
    Acc a;initAcc(a);
    float frames=0.0;
    for(int f=1;f<frameCount;f++){
        float r=natRobust(f,B0,cx,cy,w2);
        if(r<0.004)continue;
        if(kE.x>=0.0&&int(kE.x)!=f)continue;
        vec2 Ob=originM(f,2*cx,2*cy)+(B0-cell);
        bool us=int(fParam[f].w)==5;
        vec3 Pf;
        if(us)Pf=vec3(kE.y,kE.y,0.0);
        else if((mode&1)!=0)Pf=natDcov(f,Ob)*fParam[f].z;
        else Pf=P*fParam[f].z;
        float rw=r/max(fParam[f].y,1.0e-6);
        if(!us)frames+=rw;
#if NAT_B==4
        natFrame(a,f,Ob*4.0+1.5,r,rw,natClamp(Pf*(natK.x*4.0))*0.25); // the clamp range twice as wide (sigma) for 4x4 blocks
#else
        natFrame(a,f,Ob*2.0+0.5,r,rw,natClamp(Pf*natK.x));
#endif
    }
    if(wb>0.0){
        vec3 Pb;
        if(natWidenU==2){float widen=mix(natWidenS.x,1.0,smoothstep(0.0,natWidenS.y,frames));Pb=P/(widen*widen);}
#if NAT_B==4
        else if((mode&4)!=0)Pb=(natWidenU!=0?frames:16.0*frames+15.0)<kD.x?P/(kD.y*kD.y):P;
#else
        else if((mode&4)!=0)Pb=(natWidenU!=0?frames:4.0*frames+3.0)<kD.x?P/(kD.y*kD.y):P;
#endif
        else{
            NatSums s=natTaps(nGeo[0],S,P*natK.x,false,false,true);
            float widen=mix(kD.y,1.0,smoothstep(0.5*kD.x,kD.x,a.cover+s.dn.y));Pb=P/(widen*widen);
        }
#if NAT_B==4
        natFrame(a,0,S,wb,wb,natClamp(Pb*(natK.x*4.0))*0.25);
#else
        natFrame(a,0,S,wb,wb,natClamp(Pb*natK.x));
#endif
    }
    vec3 col;uint cfl=0u;
    for(int c=0;c<3;c++){
        // P268 (owner, 2026-10-19: X200 Ultra 2x / wide crops, dark dots and ragged borders along every clipped highlight
        // «нам нужно чтобы таких артефактов не было и без бенто, причём в любом режиме»): a clipped sample is a lower bound of
        // its site, not a missing one. Its colour from the unclipped samples alone took the darker side of a clip edge (a pixel
        // whose own sites clipped came out at the mean of its dark neighbours: G 0.90 -> 0.45 -> 1.00 across a screw head);
        // with the clipped samples at their clip level it is at least that mean (a brighter unclipped estimate, the
        // ultrashort's or a shorter frame's, stays).
        if(a.den[c]>1.0e-7){col[c]=a.num[c]/a.den[c];
            if(a.clipDen[c]>0.0)col[c]=max(col[c],(a.num[c]+a.clipNum[c])/(a.den[c]+a.clipDen[c]));}
        else if(a.clipDen[c]>0.0){col[c]=a.clipNum[c]/a.clipDen[c];cfl|=1u<<uint(c);}
#if NAT_MARKED
        else col[c]=wb>0.0?NAT_FILL_MARK:0.0; // P204: natBaseFill in the NAT_BASEFILL pass
#else
        else col[c]=0.0;
#endif
        if(a.clipDen[c]>0.0)cfl|=8u;
    }
    int i=row*ow+ox;
    outRgb[i*3]=col.x;outRgb[i*3+1]=col.y;outRgb[i*3+2]=col.z;
    eff[i]=a.cover+wb;
    if(mergeModeU.z!=0||rimU.x>0.0){
        if(m>0.0)cfl|=16u;
        if(a.usClip>0.0&&(cfl&7u)!=0u)cfl|=32u;
        if(rimU.x>0.0&&(cfl&8u)!=0u&&a.den[1]>1.0e-7){
            float e=0.0;
            for(int c=0;c<3;c++)e=max(e,a.clipDen[c]/max(a.den[c]+a.clipDen[c],1.0e-20));
            cfl|=uint(clamp(e,0.0,1.0)*63.0+0.5)<<8;
        }
        if(rimU.x>0.0&&(cfl&8u)!=0u)cfl|=64u;
        cflags[i]=cfl;
    }
}
)";

// P30: directory of the GPU program binary cache (empty = no cache), set by the worker from the job's "gl-cache" file.
// Compiling the merge programs cost ~0.6 s of every shot (OPPO Adreno 750: 637 ms of a 702 ms init); a program loaded from
// its own driver binary runs the identical code.
inline std::string& hybridProgramCacheDir(){static std::string dir;return dir;}

// P213c: a consumer of the merged rows while the GPU merges the next strips (the CPU chroma median of the native mosaic merge,
// MosaicChromaOverlap). hybridReconstruct calls begin() before every merge attempt (output width x height, the shift to the RGGB
// origin the rows get before the consumer reads them; a retry starts over), rows() on the merge thread after every strip's
// readback (rows [0, n) of data final, the merge never writes them again), finish() after the merge (true: data holds the shifted
// and consumed result, false: untouched, as merged) and stop() on every way out (an error, a retry, the end): when it returns no
// thread of the sink touches the rows any more.
struct HybridRowsSink {
    virtual ~HybridRowsSink()=default;
    virtual void begin(int width,int height,int dx,int dy)=0;
    virtual void rows(float* data,int n)=0;
    virtual bool finish(float* data)=0;
    virtual void stop()=0;
};

// P268: the site-class gains as a smooth field over the sensor: zx x zy zones of zw x zh sensor px, 64 gains each (sensor class
// (y & 7) * 8 + (x & 7), as the global set), bilinear between the zone centres and constant beyond the outer ones. kHybNatAccess's
// natGainAt reads the same table (binding 19) with the same arithmetic. Off (zx = 0): the global set alone, as before.
struct HybridGainField {
    int zx=0,zy=0,zw=0,zh=0;
    std::vector<float> g;                // [zone y][zone x][64]
    int measured=0;float rmsDev=0;       // zones measured; rms difference of their gains from the global set (the report)
    float noise=0;                       // the measured zones' rms residual from the fitted field
    float signal() const {return rmsDev;}
    bool on() const {return zx>1&&zy>1&&g.size()==size_t(zx)*zy*64;}
    float at(int k,int x,int y) const {
        const float fx=std::clamp((float(x)+0.5f)/float(zw)-0.5f,0.f,float(zx-1)),fy=std::clamp((float(y)+0.5f)/float(zh)-0.5f,0.f,float(zy-1));
        const int x0=std::min(int(fx),zx-2),y0=std::min(int(fy),zy-2);
        const float ax=fx-float(x0),ay=fy-float(y0);
        const float* p=g.data()+(size_t(y0)*zx+x0)*64+k;
        const size_t r=size_t(zx)*64;
        const float a=p[0]+(p[64]-p[0])*ax,c=p[r]+(p[r+64]-p[r])*ax;
        return a+(c-a)*ay;
    }
};

// P29: the raw mosaic behind a binned burst (hybridReconstructMosaicNative -> hybridReconstruct -> HybridGpu::merge). Absent (null)
// everywhere else: the plain-Bayer and the sub-frame paths never see it.
// P290: the ultrashort frame's local exposure-ratio field (hybridUltrashortLocal, below).
struct HybridUsLocal {
    int gw=0,gh=0;float tile=16.f;long tiles=0;float maxDev=0;bool apply=false;
    std::array<std::vector<float>,3> f; // per tile (gw x gh): the factor of R, G, B
    // P313: the field lies on the base: pixel (u, v) of the measured frame shows what base (u - ox, v - oy) shows (the frames'
    // global translation, hybridUsGlobalShift; 0 = as measured)
    float ox=0,oy=0;
    bool on() const {return apply&&gw>0;}
    // factor of colour c at (u, v) px of the measured frame (tile centres at (k + 0.5) tile - 0.5), bilinear, flat beyond the centres
    float at(int c,float u,float v) const {
        u-=ox;v-=oy;
        const float tx=std::clamp((u+0.5f)/tile-0.5f,0.f,float(gw-1)),ty=std::clamp((v+0.5f)/tile-0.5f,0.f,float(gh-1));
        const int x0=std::min(int(tx),std::max(gw-2,0)),y0=std::min(int(ty),std::max(gh-2,0));
        const int x1=std::min(x0+1,gw-1),y1=std::min(y0+1,gh-1);
        const float ax=tx-float(x0),ay=ty-float(y0);
        const std::vector<float>& g=f[c];
        const float top=g[size_t(y0)*gw+x0]*(1.f-ax)+g[size_t(y0)*gw+x1]*ax,bot=g[size_t(y1)*gw+x0]*(1.f-ax)+g[size_t(y1)*gw+x1]*ax;
        return top*(1.f-ay)+bot*ay;
    }
};
struct HybridMosaicNative {
    int block=2,W=0,H=0;                 // colour block b and size of the mosaic the merge reads (W = b x the binned width)
    std::vector<const uint16_t*> frames; // mosaic of frame k of the binned burst (same index), W x H sensor layout: the sensor RAW
                                         // (rawGains, S3) or built like the split's sub-frames (vb + (raw - black) x site gain)
    bool rawGains=false;                 // the frames are the sensor RAW: gains[] apply at read time
    std::array<float,64> gains{};        // site-class gains (sensor class (y&7)*8 + (x&7)), with rawGains
    HybridGainField field;               // P268: the gains as a field over the sensor (with rawGains; off: gains alone)
    int window=3;                        // HybridTuning::mosaicWindow
    bool fullWindow=false;               // HybridTuning::mosaicWindowFull: the (2r+1)^2 sites around the nearest one (S0), else the split's
    float kernelScale=1.f;               // HybridTuning::mosaicKernelScale
    float ksG=1.f,ksRB=1.f;              // HybridTuning::mosaicKernelG / RB (distance multipliers per colour)
    float fillSupport=0.f;               // S5 R / B fill: minimum support relative to green (0 = off)
    float keyNoise=1.f;                  // the binned frames' noise model x this = the noise of one SENSOR site: the SNR keys of the kernel
                                         // curves (Sabre 6.1 / round 4, the night rule, the outlier gate) follow the sensor sites as on the
                                         // split and in S0, not the binned averages (nor T2's 2x2 means)
    int widenRule=0;                     // HybridTuning::mosaicNativeWiden: 0 the split's rule, 1 S0's (below widenBelow FRAMES), 2 smooth
    float widenMul=1.f,widenFrames=1.f;  // rule 2: base sigma x mix(widenMul, 1, smoothstep(0, widenFrames, F))
    bool generic=false;                  // HybridTuning::mosaicGeneric: kHybMergeMosaic even where kHybMergeMosaicFast applies
    int clampCov=0;                      // HybridTuning::mosaicNativeClamp
    float siteSlope=0,siteOffset=0;      // noise model of one merged site of the base (outlier test of kHybNatFlags)
    // P197: the row correction of an ultrashort frame (hybridUsRowsCheck), applied to its sites as they are uploaded (HybridGpu:
    // the clip scan and slot 12), so no corrected copy of a 50 / 200 MP frame is held. Per frame (same index as frames): empty =
    // as captured, else 3 factors (R, G, B) per row of the mosaic. Sensor layout: colour from the block parity and rowCfa, black of
    // the 2x2 sensor phase.
    std::vector<std::vector<float>> rowGain;
    std::vector<HybridUsLocal> local;    // P290: x the tile field of the frame (binned coordinates; !on(): none)
    std::array<float,4> rowBlack{};int rowCfa=0;float rowWhite=0;
    HybridRowsSink* rowsSink=nullptr;    // P213c: the merged rows to this consumer while the merge runs (null: none)
    bool fixesRows(int k) const {return k>=0&&((k<int(rowGain.size())&&!rowGain[k].empty())||(k<int(local.size())&&local[k].on()));}
    // rows [y0, y0 + rows) of frame k, corrected, into dst (rows x W): (v - black) x factor of the row and site colour; a clipped
    // site (>= white - 1, the binning's clip) stays, a corrected value is at most white (then it reads as clipped, as in the binning)
    void fixRows(int k,int y0,int rows,uint16_t* dst) const {
        const uint16_t* src=frames[k];const float* g=k<int(rowGain.size())&&!rowGain[k].empty()?rowGain[k].data():nullptr;
        const HybridUsLocal* l=k<int(local.size())&&local[k].on()?&local[k]:nullptr;
        const float o=0.5f*float(block-1),ib=1.f/float(block);
        std::vector<float> lr; // P290: the tile field along this row, per colour at every binned column (linear between them)
        const int bw=(W+block-1)/block;
        for(int y=y0;y<y0+rows;++y){
            const uint16_t* s=src+size_t(y)*W;uint16_t* d=dst+size_t(y-y0)*W;
            const int py=((y/block)&1)<<1;
            if(l){lr.resize(size_t(bw)*3);const float v=(float(y)-o)*ib;
                for(int I=0;I<bw;++I)for(int c=0;c<3;++c)lr[size_t(I)*3+c]=l->at(c,float(I),v);}
            for(int x=0;x<W;++x){
                const int p=py|((x/block)&1),c=p==rowCfa?0:p==(rowCfa^3)?2:1;
                const float v=float(s[x]),bl=rowBlack[((y&1)<<1)|(x&1)];
                float f=g?g[3*y+c]:1.f;
                if(l){const float u=std::clamp((float(x)-o)*ib,0.f,float(bw-1));const int I=std::min(int(u),bw-2>=0?bw-2:0),I1=std::min(I+1,bw-1);
                      const float a=u-float(I);f*=lr[size_t(I)*3+c]*(1.f-a)+lr[size_t(I1)*3+c]*a;}
                d[x]=v>=rowWhite-1.f?s[x]:uint16_t(std::lround(std::clamp(bl+(v-bl)*f,0.f,rowWhite)));
            }
        }
    }
};

// P62 Mochi (v11 PhotometricMerge, research/mochi/MOCHI_IMPL.md): photometric correction of a bracketed (longer) frame against
// the base before the merge, on the GPU. Standalone programs with their own buffers (bindings 0..5; merge() binds its own again),
// compiled only when the tuning key mochi is on, so a merge without it runs exactly the programs and buffers as before.
// kHybMochiStats: one invocation per 16x16 RAW tile of the bracketed frame (its own geometry). Per 2x2 cell: the frame's sites and
// the base sampled at the same scene point (bilinear on each phase's grid, the frame's homography inverted to first order), both
// unclipped (< clipAt). A cell whose difference exceeds 3 sigma of the noise plus the largest correction kept is motion or
// misalignment, not a photometric offset (the reference weights by its rejection map instead), and is left out. The mean difference
// base - frame of the accepted cells, in the frame's own normalised units, becomes the reference correction: in 10-bit DN zeroed when
// |c_rggb|^2 > 500, otherwise clamped to +-20; with the base SNR weight min(1, snr / 5) (SNRWeight(snr, 5.0)).
static const char* kHybMochiStats=R"(#version 310 es
precision highp float;
precision highp int;
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=0) readonly buffer Base{uint base[];};
layout(std430,binding=1) readonly buffer Alt{uint alt[];};
layout(std430,binding=2) buffer Tiles{vec4 tiles[];};   // per tile: correction (frame's own normalised units) per RAW phase
layout(std430,binding=3) buffer Info{vec4 info[];};     // per tile: x SNR weight, y accepted cells, z zeroed, w measured
uniform ivec2 size;      // w, h of the frames
uniform ivec2 altRows;   // RAW rows held in Alt [x, y)
uniform ivec2 baseRows;  // RAW rows held in Base [x, y)
uniform ivec2 tileRows;  // tile rows of this band [x, y)
uniform ivec2 tileCount; // nx, ny
uniform ivec2 greens;    // the two green RAW phases
uniform vec4 black;
uniform vec4 inv;
uniform vec4 hA;         // homography h0..h3 (base -> frame)
uniform vec4 hB;         // h4..h7
uniform vec4 noise;      // base slope, offset; frame slope, offset (each in its own exposure)
uniform vec2 expo;       // x = exposure ratio t of the frame, y = 1 / t
uniform float clipAt;
uniform float bound;     // the largest correction kept (own normalised units)
float site(bool b,int x,int y){
    uint i=uint((y-(b?baseRows.x:altRows.x))*size.x+x);
    uint word=b?base[i>>1]:alt[i>>1];
    int ph=((y&1)<<1)|(x&1);
    return (float((word>>((i&1u)*16u))&0xFFFFu)-black[ph])*inv[ph];
}
vec2 project(vec2 p){
    return vec2(hA.x*p.x+hA.y*p.y+hA.z,hA.w*p.x+hB.x*p.y+hB.y)/(hB.z*p.x+hB.w*p.y+1.0);
}
void main(){
    ivec2 t=ivec2(gl_GlobalInvocationID.xy);
    int ty=t.y+tileRows.x;
    if(t.x>=tileCount.x||ty>=tileRows.y)return;
    vec4 sd=vec4(0.0);float n=0.0,sg=0.0;
    for(int cy=0;cy<8;++cy)for(int cx=0;cx<8;++cx){
        int X=16*t.x+2*cx,Y=16*ty+2*cy;
        if(X+1>=size.x||Y<altRows.x||Y+1>=altRows.y)continue;
        vec2 q=vec2(float(X),float(Y));
        vec2 d=project(q)-q;     // base -> frame displacement near q
        vec2 uv0=(q-d)*0.5;      // the base cell grid at the same scene point
        vec4 a=vec4(0.0),b=vec4(0.0);bool ok=true;
        for(int ph=0;ph<4;++ph){
            int sx=ph&1,sy=ph>>1;
            float an=site(false,X+sx,Y+sy);
            vec2 i0=floor(uv0),f=uv0-i0;
            int bx=2*int(i0.x)+sx,by=2*int(i0.y)+sy;
            if(an>=clipAt||bx<0||by<baseRows.x||bx+2>=size.x||by+2>=baseRows.y){ok=false;break;}
            float b00=site(true,bx,by),b10=site(true,bx+2,by),b01=site(true,bx,by+2),b11=site(true,bx+2,by+2);
            if(max(max(b00,b10),max(b01,b11))>=clipAt){ok=false;break;}
            b[ph]=mix(mix(b00,b10,f.x),mix(b01,b11,f.x),f.y);
            a[ph]=an;
        }
        if(!ok)continue;
        vec4 dd=b-a*expo.y;      // base units
        vec4 v=noise.x*max(b,0.0)+noise.y+(noise.z*max(a,0.0)+noise.w)*expo.y*expo.y;
        if(any(greaterThan(abs(dd),3.0*sqrt(v)+bound*expo.y)))continue;
        sd+=dd;n+=1.0;sg+=0.5*(b[greens.x]+b[greens.y]);
    }
    int ti=ty*tileCount.x+t.x;
    if(n<16.0){tiles[ti]=vec4(0.0);info[ti]=vec4(0.0,n,0.0,0.0);return;}
    vec4 u=sd/n*expo.x*1023.0; // the reference correction in 10-bit DN of the frame
    float zeroed=dot(u,u)>500.0?1.0:0.0;
    tiles[ti]=zeroed>0.0?vec4(0.0):clamp(u,-20.0,20.0)/1023.0;
    float g=clamp(sg/n,0.0,1.0);
    float snr=g/sqrt(noise.x*g+noise.y+1e-7);
    info[ti]=vec4(min(1.0,max(snr,0.0)/5.0),n,zeroed,1.0);
}
)";
// kHybMochiBlur: the reference "GaussianBlur correction" and "GaussianBlur SNR map" on the tile grid ([1 2 1]^2), the SNR weight applied:
// Final = blur(correction) * blur(weight). Only measured tiles count (renormalised): a tile without accepted cells (motion,
// clipping) takes its neighbours' correction instead of diluting theirs with a zero (the reference rejection-weighted tile mean is 0
// there and its blur pulls the neighbours toward it: around a moving object the background kept up to 2 DN of the offset).
static const char* kHybMochiBlur=R"(#version 310 es
precision highp float;
precision highp int;
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=2) readonly buffer Tiles{vec4 tiles[];};
layout(std430,binding=3) readonly buffer Info{vec4 info[];};
layout(std430,binding=4) writeonly buffer Final{vec4 finalC[];};
uniform ivec2 tileCount;
void main(){
    ivec2 t=ivec2(gl_GlobalInvocationID.xy);
    if(t.x>=tileCount.x||t.y>=tileCount.y)return;
    vec4 c=vec4(0.0);float s=0.0,k=0.0;
    for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx){
        ivec2 p=t+ivec2(dx,dy);
        if(p.x<0||p.y<0||p.x>=tileCount.x||p.y>=tileCount.y)continue;
        int i=p.y*tileCount.x+p.x;
        float wgt=float((2-abs(dx))*(2-abs(dy)))*info[i].w;
        c+=wgt*tiles[i];s+=wgt*info[i].x;k+=wgt;
    }
    finalC[t.y*tileCount.x+t.x]=k>0.0?c/k*(s/k):vec4(0.0);
}
)";
// kHybMochiApply: one invocation per RAW word (two sites of a row) of the frame's band: site + bilinear(Final) in DN, rounded and
// kept below the merge's clip level; a site already >= clipAt (its clip state included) is not touched.
static const char* kHybMochiApply=R"(#version 310 es
precision highp float;
precision highp int;
layout(local_size_x=8,local_size_y=8) in;
layout(std430,binding=1) readonly buffer Alt{uint alt[];};
layout(std430,binding=4) readonly buffer Final{vec4 finalC[];};
layout(std430,binding=5) writeonly buffer Out{uint outw[];};
uniform ivec2 size;
uniform ivec2 altRows;   // RAW rows held in Alt [x, y)
uniform ivec2 applyRows; // RAW rows written [x, y) (Out starts at row x)
uniform ivec2 tileCount;
uniform vec4 black;
uniform vec4 inv;
uniform float clipAt;
uniform float capLevel;
uint corrected(uint v,int x,int y){
    int ph=((y&1)<<1)|(x&1);
    float r=1.0/inv[ph];
    if((float(v)-black[ph])*inv[ph]>=clipAt)return v;
    vec2 tc=clamp((vec2(float(x),float(y))+0.5)/16.0-0.5,vec2(0.0),vec2(tileCount-1));
    ivec2 i0=ivec2(floor(tc));ivec2 i1=min(i0+1,tileCount-1);vec2 f=tc-vec2(i0);
    float c00=finalC[i0.y*tileCount.x+i0.x][ph],c10=finalC[i0.y*tileCount.x+i1.x][ph];
    float c01=finalC[i1.y*tileCount.x+i0.x][ph],c11=finalC[i1.y*tileCount.x+i1.x][ph];
    float c=mix(mix(c00,c10,f.x),mix(c01,c11,f.x),f.y);
    return uint(clamp(floor(float(v)+c*r+0.5),0.0,min(65535.0,black[ph]+capLevel*r)));
}
void main(){
    int xw=int(gl_GlobalInvocationID.x),y=int(gl_GlobalInvocationID.y)+applyRows.x;
    if(2*xw>=size.x||y>=applyRows.y)return;
    uint i=uint((y-altRows.x)*size.x+2*xw);
    uint word=alt[i>>1];
    uint lo=corrected(word&0xFFFFu,2*xw,y),hi=corrected(word>>16,2*xw+1,y);
    outw[uint((y-applyRows.x)*size.x+2*xw)>>1]=lo|(hi<<16);
}
)";

// P33 W2.1 (research/speed/SHOT_SPEED_PLAN.md): the F6 field computed in tile-row bands while the GPU merges (LaStream below).
// HybridGpu::merge waits before each strip for the field rows that strip reads and uploads them; the rows are final when
// waitRows returns (the same floats as the whole-frame laFrameField). waitRows throws HybridLaStreamFailed when F6 failed.
struct HybridLaStreamFailed : std::runtime_error { using std::runtime_error::runtime_error; };
struct HybridLaStream {
    virtual ~HybridLaStream()=default;
    virtual void waitRows(int rows)=0;                 // every streamed frame holds final field / Z rows [0, rows)
    virtual const float* field(int mergeIndex) const=0; // ny x nx x 2 (RAW px) of merge frame i, nullptr = no field (zeros)
    virtual const float* motion(int mergeIndex) const=0;// ny x nx Z channel of merge frame i, nullptr = zeros
    double waitMs=0;int waits=0;                        // time the strips spent waiting (report)
};

// P320 DCG: the programs of a burst with two measured noise zones (HybridGpu(.., dcg)): exact-line replacements in kHybHelpers (noiseU,
// sabreCov61Q), kHybMergeCommon (the Bayer base demosaic's structure test) and kHybMergeMosaicFast (the native one), the read-noise
// offset of the level's zone (fNoiseP[f].z = knee, .w = LCG offset, frame f's own exposure), the HCG one blended into the LCG one over
// 0.9 .. 1.1 x the knee (hybridDcgShare: the app's denoise blends the same way, owner: two zones with a transition); w = 0 or z = 0
// gives the original value. Each line must be found exactly once, else the GPU merge fails naming it (never a silent single-curve DCG
// program). Every other burst compiles the unchanged sources (the same program cache entries); kHybMergeMain1 is not touched.
// kHybGuide's texture floor takes the green difference's noise at the centre's level (noiseU: its zone) instead of the one-curve
// 0.56 sqrt(slope) - the same value below the knee (1.128 sqrt(S / 4)), the LCG offset's above it.
struct HybridDcgSources { std::string helpers,mergeCommon,mosaicFast,guide; };
inline std::string hybridDcgReplace(const std::string& text,const char* what,const char* from,const char* to){
    std::string s=text;
    const size_t at=s.find(from);
    if(at==std::string::npos||s.find(from,at+1)!=std::string::npos)
        throw std::runtime_error(std::string("HYBRID GPU: DCG two-zone programs: the ")+what+" line is not found exactly once");
    s.replace(at,std::strlen(from),to);
    return s;
}
inline const HybridDcgSources& hybridDcgSources(){
    static const HybridDcgSources sources=[]{
        HybridDcgSources d;
        d.helpers=hybridDcgReplace(kHybHelpers,"noiseU variance",
            "\n    float var=fNoiseP[f].xy.x*l*g+fNoiseP[f].xy.y*g*g;\n",
            "\n    float var=fNoiseP[f].xy.x*l*g+((fNoiseP[f].w>0.0&&fNoiseP[f].z>0.0)?mix(fNoiseP[f].xy.y,fNoiseP[f].w,"
            "smoothstep(0.9*fNoiseP[f].z*g,1.1*fNoiseP[f].z*g,l)):fNoiseP[f].xy.y)*g*g;\n");
        // v = lum^2 is the level (base units): the quad lumas are sqrt of the samples without epsU (sabreQuad61)
        d.helpers=hybridDcgReplace(d.helpers,"sabreCov61Q varU",
            "\n    float varU=(nm.x*v*g+nm.y*g*g)/(4.0*v);\n",
            "\n    float varU=(nm.x*v*g+((fNoiseP[f].w>0.0&&fNoiseP[f].z>0.0)?mix(nm.y,fNoiseP[f].w,"
            "smoothstep(0.9*fNoiseP[f].z*g,1.1*fNoiseP[f].z*g,v)):nm.y)*g*g)/(4.0*v);\n");
        d.mergeCommon=hybridDcgReplace(kHybMergeCommon,"Bayer base demosaic sig",
            "\n    float sig=sqrt(max(baseNoise.x*max(own,0.0)+baseNoise.y,1.0e-10));\n",
            "\n    float sig=sqrt(max(baseNoise.x*max(own,0.0)+((fNoiseP[0].w>0.0&&fNoiseP[0].z>0.0)?mix(baseNoise.y,fNoiseP[0].w,"
            "smoothstep(0.9*fNoiseP[0].z,1.1*fNoiseP[0].z,own)):baseNoise.y),1.0e-10));\n");
        d.mosaicFast=hybridDcgReplace(kHybMergeMosaicFast,"native base demosaic sig",
            "\n    float sig=sqrt(max(fNoiseP[0].x*max(own,0.0)+fNoiseP[0].y,1.0e-10));\n",
            "\n    float sig=sqrt(max(fNoiseP[0].x*max(own,0.0)+((fNoiseP[0].w>0.0&&fNoiseP[0].z>0.0)?mix(fNoiseP[0].y,fNoiseP[0].w,"
            "smoothstep(0.9*fNoiseP[0].z,1.1*fNoiseP[0].z,own)):fNoiseP[0].y),1.0e-10));\n");
        d.guide=hybridDcgReplace(kHybGuide,"guide texture floor",
            "\n    float gdTex=max(gdSum-0.56*sqrt(baseNoise.x),0.0);\n",
            "\n    float gdTex=max(gdSum-1.128*sqrt(noiseU(0,Lc)),0.0);\n");
        return d;
    }();
    return sources;
}

class HybridGpu {
    EGLDisplay display=EGL_NO_DISPLAY;
    EGLContext context=EGL_NO_CONTEXT;
    EGLSurface surface=EGL_NO_SURFACE;
    static constexpr int kSlots=19; // 0..17 merge (1 fixed-pattern mean, 2 site flags, 6 clip flags, 14 donor covariance, 15 F6 field,
                                    // 16 F6 offset per strip cell, 17 F6 Z channel), 18 readback staging (kSlots - 1)
    GLuint meanProgram=0,flagsProgram=0,markProgram=0,guideProgram=0,cellsProgram=0,rejectProgram=0,dilateProgram=0,mergeProgram=0,rimProgram=0,bentoProgram=0,chromaProgram=0,blendProgram=0,buffers[kSlots]{};
    // P29 native mosaic (compiled only by HybridGpu(.., nativeMosaic = true)): outlier mean / flags / mark of the native sites and
    // the merge kHybMergeMosaic; the NatTable uniform buffers (binding 1) of the two strip banks
    GLuint natMeanProgram=0,natFlagsProgram=0,natMarkProgram=0,mosaicProgram=0,natTable[2]{};
    // P214k: the plausibility cap of the fixed-pattern test (kHybFlagCap / kHybFlagPrune; compiled with the outlier programs)
    GLuint capProgram=0,pruneProgram=0;
    // P34: the fast native merge (kHybNatNorm + kHybMergeMosaicFast [block Quad / Tetra][window r][marked][fill], compiled when
    // first used) and the normalised 2x2 cells of the strip (binding 18, outside the slots: written and read on the GPU only, one bank)
    GLuint natNormProgram=0,natFast[2][9][2][2]{},natNormBuf=0,natFieldBuf=0;
    GLuint natFastFill[2][9][2]{}; // P204: the base-fill pass of a marked fast merge (block, half-width, R / B fill)
    GLuint natFastDem[2][9][2]{};  // P282: the base demosaic pass (block, half-width, R / B fill)
    GLuint natFastUsPass[2][9][2][2]{}; // P313: the ultrashort pass of mosaicUsFill (block, half-width, marked, R / B fill)
    // P62 Mochi (compiled on first use): programs and buffers (0 base rows, 1 frame rows, 2 tiles, 3 tile info, 4 final, 5 out)
    GLuint mochiStatsProgram=0,mochiBlurProgram=0,mochiApplyProgram=0,mochiBuf[6]{};
    size_t mochiCap[6]{};
    // P80: the base rows already in mochiBuf[0] (frame pointer and rows): the next bracketed frame of the shot reuses them
    const uint16_t* mochiBasePtr=nullptr;int mochiBaseR0=-1,mochiBaseR1=-1;
    size_t natNormCap=0;
    GLuint natFastProgram(int block,int r,bool marked,bool fill,bool baseFill=false,bool demosaic=false,bool usPass=false){
        const bool tetra=block==4;
        r=std::clamp(r,1,tetra?8:3);
        marked=marked||baseFill;
        if(demosaic){baseFill=false;marked=false;}
        if(usPass){baseFill=false;demosaic=false;} // P313
        GLuint& p=usPass?natFastUsPass[tetra?1:0][r][marked?1:0][fill?1:0]
                 :demosaic?natFastDem[tetra?1:0][r][fill?1:0]:baseFill?natFastFill[tetra?1:0][r][fill?1:0]:natFast[tetra?1:0][r][marked?1:0][fill?1:0];
        if(!p){
            const auto t0=std::chrono::steady_clock::now();
            std::string body=std::string(usPass?"#define NAT_US_PASS 1\n":"")+"#define NAT_B "+(tetra?"4":"2")+"\n#define NAT_R "+std::to_string(r)+"\n#define NAT_MARKED "+(marked?"1":"0")
                +"\n#define NAT_FILL "+(fill?"1":"0")+"\n#define NAT_BASEFILL "+(baseFill?"1":"0")+"\n#define NAT_DEMOSAIC "+(demosaic?"1":"0")+"\n"
                +mergeCommon()+kHybNatAccess+mosaicFast();
            // P313: the ultrashort pass reads and writes the merged colour, the effective map and the clip flags (its text alone)
            if(usPass)for(const char* w:{"writeonly buffer Out{","writeonly buffer Eff{","writeonly buffer CFlags{"}){
                const std::string k(w);size_t at;
                while((at=body.find(k))!=std::string::npos)body.replace(at,k.size(),k.substr(10)); // drops "writeonly "
            }
            compileName=usPass?(tetra?"mosaicUsT":"mosaicUs"):demosaic?(tetra?"mosaicDemT":"mosaicDem"):baseFill?(tetra?"mosaicFillT":"mosaicFill"):tetra?"mosaicFastT":"mosaicFast"; // the crash report names the program
            p=compile(body.c_str());
            compileMs+=std::string(usPass?" mosaicUs":demosaic?" mosaicDem":baseFill?" mosaicFill":" mosaicFast")+(tetra?"T":"")+std::to_string(r)+(marked?"m":"")+(fill?"f":"")+"="
                +std::to_string(int(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count()));
        }
        return p;
    }
    // FrameTable (std140, uniform buffer binding 0): six arrays of kHybridGpuFrames 16-byte entries
    GLuint frameTable=0;
    static constexpr size_t kTableGeo=0,kTableHA=1,kTableHB=2,kTableParam=3,kTableNoise=4,kTableCells=5;
    void putTable(size_t array,const void* data,int frames){
        glBindBuffer(GL_UNIFORM_BUFFER,frameTable);
        glBufferSubData(GL_UNIFORM_BUFFER,GLintptr(array*kHybridGpuFrames*16),GLsizeiptr(size_t(frames)*16),data);
    }
    size_t capacity[kSlots]{};
    // P30: strips alternate between two banks of the buffers the CPU writes or reads per strip (RAW rows 0, outputs 3 / 4 / 6,
    // Bento mask 13, the frame table): strip k+1 is uploaded and dispatched while strip k is read back (fence), instead of the
    // GPU idling during every upload and the CPU during every pass. Bank 0 is buffers[] itself. The shaders are unchanged.
    GLuint bankBuffers[kSlots]{},bankTable=0;
    size_t bankCapacity[kSlots]{};
    GLuint& bankBuffer(int slot,int bank){return bank?bankBuffers[slot]:buffers[slot];}
    void reserveBank(int slot,size_t bytes,int bank){
        GLuint& buffer=bankBuffer(slot,bank);size_t& cap=bank?bankCapacity[slot]:capacity[slot];
        if(!buffer)glGenBuffers(1,&buffer);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffer);
        if(cap<bytes){glBufferData(GL_SHADER_STORAGE_BUFFER,GLsizeiptr(bytes),nullptr,GL_DYNAMIC_DRAW);cap=bytes;}
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,GLuint(slot),buffer);
    }
    void putBank(int slot,int bank,size_t offset,const void* data,size_t bytes){
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,bankBuffer(slot,bank));
        glBufferSubData(GL_SHADER_STORAGE_BUFFER,GLintptr(offset),GLsizeiptr(bytes),data);
    }
    GLuint tableOf(int bank){return bank?bankTable:frameTable;}
    void putTableBank(size_t array,const void* data,int frames,int bank){
        glBindBuffer(GL_UNIFORM_BUFFER,tableOf(bank));
        glBufferSubData(GL_UNIFORM_BUFFER,GLintptr(array*kHybridGpuFrames*16),GLsizeiptr(size_t(frames)*16),data);
    }
    void check(const char* where){GLenum e=glGetError();if(e!=GL_NO_ERROR)throw std::runtime_error(std::string("HYBRID GPU ")+where+" GL error="+std::to_string(e));}
    void cleanup() noexcept {
        if(display==EGL_NO_DISPLAY)return;
        if(context!=EGL_NO_CONTEXT&&eglMakeCurrent(display,surface,surface,context)){
            for(GLuint program:{meanProgram,flagsProgram,markProgram,guideProgram,cellsProgram,rejectProgram,dilateProgram,mergeProgram,rimProgram,bentoProgram,chromaProgram,blendProgram})if(program)glDeleteProgram(program);
            for(GLuint program:{natMeanProgram,natFlagsProgram,natMarkProgram,mosaicProgram,natNormProgram})if(program)glDeleteProgram(program); // P29
            for(GLuint program:{capProgram,pruneProgram})if(program)glDeleteProgram(program); // P214k
            for(auto& byBlock:natFast)for(auto& byR:byBlock)for(auto& byMark:byR)for(GLuint program:byMark)if(program)glDeleteProgram(program); // P34
            for(auto& byBlock:natFastFill)for(auto& byR:byBlock)for(GLuint program:byR)if(program)glDeleteProgram(program); // P204
            for(auto& byBlock:natFastUsPass)for(auto& byR:byBlock)for(auto& byMark:byR)for(GLuint program:byMark)if(program)glDeleteProgram(program); // P313
            if(natTable[0]||natTable[1])glDeleteBuffers(2,natTable);
            if(natNormBuf)glDeleteBuffers(1,&natNormBuf);
            if(natFieldBuf)glDeleteBuffers(1,&natFieldBuf); // P268
            for(GLuint program:{mochiStatsProgram,mochiBlurProgram,mochiApplyProgram})if(program)glDeleteProgram(program); // P62
            for(GLuint b:mochiBuf)if(b)glDeleteBuffers(1,&b);
            glDeleteBuffers(kSlots,buffers);
            glDeleteBuffers(kSlots,bankBuffers);
            if(frameTable)glDeleteBuffers(1,&frameTable);
            if(bankTable)glDeleteBuffers(1,&bankTable);
            eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        }
        if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);
        if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
        eglTerminate(display);display=EGL_NO_DISPLAY;
    }
    const char* helpersPrefix=""; // "#define LOCAL_ALIGN 1\n" when the F6 field is used
    std::string driver;            // GL_RENDERER + GL_VERSION: part of the program cache key
    int cacheHits=0,cacheStores=0;
    // Cache file of a program: FNV-1a 64 of the driver and the whole source; "" without a cache directory or binary formats.
    std::string programCacheFile(const std::string& source){
        const std::string& dir=hybridProgramCacheDir();
        if(dir.empty())return "";
        GLint formats=0;glGetIntegerv(GL_NUM_PROGRAM_BINARY_FORMATS,&formats);
        if(formats<=0)return "";
        uint64_t hash=1469598103934665603ULL;
        auto mix=[&](const std::string& text){for(unsigned char c:text){hash^=c;hash*=1099511628211ULL;}hash^=0xff;hash*=1099511628211ULL;};
        mix(driver);mix(source);
        char name[40];std::snprintf(name,sizeof(name),"hyb-%016llx.bin",static_cast<unsigned long long>(hash));
        return dir+"/"+name;
    }
    GLuint loadProgramBinary(const std::string& file){
        std::ifstream in(file,std::ios::binary);
        if(!in)return 0;
        std::vector<char> data((std::istreambuf_iterator<char>(in)),std::istreambuf_iterator<char>());
        if(data.size()<=sizeof(GLenum))return 0;
        GLenum format=0;std::memcpy(&format,data.data(),sizeof(format));
        GLuint program=glCreateProgram();
        glProgramBinary(program,format,data.data()+sizeof(format),GLsizei(data.size()-sizeof(format)));
        GLint ok=0;glGetProgramiv(program,GL_LINK_STATUS,&ok);
        if(!ok||glGetError()!=GL_NO_ERROR){glDeleteProgram(program);std::remove(file.c_str());return 0;} // driver changed: compile again
        ++cacheHits;
        return program;
    }
    void storeProgramBinary(GLuint program,const std::string& file){
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
    // Old builds leave their binaries behind: keep the newest 48 files of the cache directory.
    static void trimProgramCache(){
        const std::string& dir=hybridProgramCacheDir();
        if(dir.empty())return;
        DIR* d=opendir(dir.c_str());if(!d)return;
        std::vector<std::pair<time_t,std::string>> files;
        while(dirent* e=readdir(d)){
            std::string name=e->d_name;if(name.rfind("hyb-",0)!=0)continue;
            struct stat st{};if(stat((dir+"/"+name).c_str(),&st)==0)files.emplace_back(st.st_mtime,dir+"/"+name);
        }
        closedir(d);
        if(files.size()<=48)return;
        std::sort(files.begin(),files.end());
        for(size_t i=0;i+48<files.size();++i)std::remove(files[i].second.c_str());
    }
    // Crash diagnostics (vivo X200 Pro: every Mali worker died after "HYBRID GPU: compile flags"): the program and the GL call in
    // progress, for the worker's crash report. One GPU thread compiles at a time; the text lives for the whole process.
    static char* compileStage(){static char text[96];return text;}
    const char* compileName="?";
    void markCompile(const char* step){
        std::snprintf(compileStage(),96,"GPU compile %s: %s",compileName,step);worker_crash::mark(compileStage());
    }
    // P320: kHybMergeCommon / kHybMergeMosaicFast as this context compiles them (a DCG two-zone burst: hybridDcgSources)
    const char* mergeCommon() const {return dcg?hybridDcgSources().mergeCommon.c_str():kHybMergeCommon;}
    const char* mosaicFast() const {return dcg?hybridDcgSources().mosaicFast.c_str():kHybMergeMosaicFast;}
    GLuint compile(const char* body,bool standalone=false){
        const char* helpers=dcg&&!standalone?hybridDcgSources().helpers.c_str():kHybHelpers; // P320
        std::string source=standalone?std::string(body):std::string(kHybCommon)+helpersPrefix+helpers+body;
        const std::string cacheFile=programCacheFile(source);
        markCompile("glProgramBinary (cache)");
        scam_progress::tick(); // P220: each program is progress (built ahead on a helper thread during the alignment, too)
        if(!cacheFile.empty())if(GLuint cached=loadProgramBinary(cacheFile))return cached;
        markCompile("glCompileShader");
        GLuint shader=glCreateShader(GL_COMPUTE_SHADER);const char* sources[]={kHybCommon,helpersPrefix,helpers,body};
        if(standalone)glShaderSource(shader,1,&body,nullptr); else glShaderSource(shader,4,sources,nullptr);
        glCompileShader(shader);
        GLint ok=0;glGetShaderiv(shader,GL_COMPILE_STATUS,&ok);
        if(!ok){char msg[4096]{};glGetShaderInfoLog(shader,sizeof(msg),nullptr,msg);glDeleteShader(shader);throw std::runtime_error(std::string("HYBRID GPU shader: ")+msg);}
        GLuint program=glCreateProgram();glAttachShader(program,shader);
        if(!cacheFile.empty())glProgramParameteri(program,GL_PROGRAM_BINARY_RETRIEVABLE_HINT,GL_TRUE);
        markCompile("glLinkProgram");
        glLinkProgram(program);glDeleteShader(shader);
        glGetProgramiv(program,GL_LINK_STATUS,&ok);
        if(!ok){char msg[4096]{};glGetProgramInfoLog(program,sizeof(msg),nullptr,msg);glDeleteProgram(program);throw std::runtime_error(std::string("HYBRID GPU link: ")+msg);}
        markCompile("glGetProgramBinary (cache store)");
        if(!cacheFile.empty())storeProgramBinary(program,cacheFile);
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
    void get(int slot,void* data,size_t bytes){getBuffer(buffers[slot],slot,data,bytes,false);}
    // fenced: the caller waited for the fence of the commands that wrote the buffer, so an unsynchronized map (no implicit
    // wait for the commands submitted after them) is tried first; the method ladder below stays the fallback.
    int fencedMapFails=0;
    void getBuffer(GLuint source,int slot,void* data,size_t bytes,bool fenced){
        constexpr size_t kChunk=size_t(32)<<20; // strips are sized so that every readback is one map from offset 0
        for(size_t offset=0;offset<bytes;offset+=kChunk){
            const size_t n=std::min(kChunk,bytes-offset);
            bool done=false;
            if(fenced&&fencedMapFails<2){
                glBindBuffer(GL_SHADER_STORAGE_BUFFER,source);
                if(const void* mapped=tryMap(GL_SHADER_STORAGE_BUFFER,offset,n,GL_MAP_READ_BIT|GL_MAP_UNSYNCHRONIZED_BIT)){
                    std::memcpy(static_cast<char*>(data)+offset,mapped,n);
                    if(!glUnmapBuffer(GL_SHADER_STORAGE_BUFFER))throw std::runtime_error("HYBRID GPU storage invalidated");
                    continue;
                }
                ++fencedMapFails;
            }
            for(int method=mapMethod<0?0:mapMethod;method<5&&!done;++method){
                const void* mapped=nullptr;GLenum target=GL_SHADER_STORAGE_BUFFER;
                if(method==0){glBindBuffer(GL_SHADER_STORAGE_BUFFER,source);mapped=tryMap(target,offset,n,GL_MAP_READ_BIT);}
                else if(method==1){glFinish();glBindBuffer(GL_SHADER_STORAGE_BUFFER,source);mapped=tryMap(target,offset,n,GL_MAP_READ_BIT|GL_MAP_UNSYNCHRONIZED_BIT);}
                else if(method==2){target=GL_COPY_READ_BUFFER;glBindBuffer(target,source);mapped=tryMap(target,offset,n,GL_MAP_READ_BIT);}
                else if(method==3){ // staging copy into a GL_DYNAMIC_READ buffer, mapped from offset 0
                    target=GL_COPY_WRITE_BUFFER;
                    glBindBuffer(GL_COPY_READ_BUFFER,source);glBindBuffer(GL_COPY_WRITE_BUFFER,buffers[kSlots-1]);
                    if(capacity[kSlots-1]<n){glBufferData(GL_COPY_WRITE_BUFFER,GLsizeiptr(n),nullptr,GL_DYNAMIC_READ);capacity[kSlots-1]=n;}
                    glCopyBufferSubData(GL_COPY_READ_BUFFER,GL_COPY_WRITE_BUFFER,GLintptr(offset),0,GLsizeiptr(n));
                    glFinish();
                    mapped=tryMap(target,0,n,GL_MAP_READ_BIT);
                } else { // last resort: a fresh buffer object per chunk
                    target=GL_COPY_WRITE_BUFFER;GLuint tmp=0;glGenBuffers(1,&tmp);
                    glBindBuffer(GL_COPY_READ_BUFFER,source);glBindBuffer(GL_COPY_WRITE_BUFFER,tmp);
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
    // many small flushed submissions cost ~1 ms each. The current program must be bound. flushEach (P29 native merge): every
    // dispatch is its own submission (a pass whose dispatches together run for seconds).
    void dispatchRows(GLuint program,int w2,int rows,int gz,int chunk,int pass,bool barrier=true,bool flushEach=false){
        const GLint l=loc(program,"chunkU");
        const auto t0=std::chrono::steady_clock::now();
        for(int r=0;r<rows;r+=chunk){
            glUniform1i(l,r);
            glDispatchCompute(GLuint((w2+7)/8),GLuint((std::min(chunk,rows-r)+7)/8),GLuint(std::max(gz,1)));
            if(flushEach)glFlush();
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
    int64_t maxGroupsX=0;          // GL_MAX_COMPUTE_WORK_GROUP_COUNT[0] (>= 65535): every dispatch's x extent / 8 must stay within it
    std::function<void(const std::string&)> trace;
    struct Frames {
        int w=0,h=0,cfa=0;
        std::array<float,4> black{},inv{};
        std::array<int,4> phaseColor{};
        std::vector<const uint16_t*> frames;            // 0 = base
        std::vector<BackwardHomography> homography;     // per frame
        std::vector<float> gain,weight,kmul,noiseSlope,noiseOffset;
        std::vector<float> noiseKnee,noiseLcg;          // P320 DCG: per frame fNoiseP.zw (knee, LCG offset); empty / 0 = one curve
        std::vector<int> role;
        float baseSlope=0,baseOffset=0,white=0;
        const std::vector<float>* mask=nullptr;         // per cell (w/2 x h/2), 0 = no Bento
        bool blend=false;                               // P310: mask = the ultrashort blend weight (kHybUsBlend), the merge without them
        float blendGain=1.f;                            // P310: strength of the ultrashort colour's gain against N (kHybUsBlend stage 2)
        float rampK=0.f; // P309: the ramp mask's K (share = m / (m + K (1 - m))); 0 = the previous mask
        std::vector<const std::vector<float>*> maskValid; // bentoValidate: per ultrashort frame in merge order, the validity factor per cell
        std::vector<int> hotList;                       // normal frames of the fixed-pattern outlier test (base first); empty = off
        std::array<float,4> k61a{},k61b{},k61c{};      // Sabre 6.1 kernel uniforms (k61a[3] = 0: round-4 kernel)
        int mergeMode=0;                                // 1 per-frame 6.1 covariance, 2 6.1 window, 4 6.1 base widening
        bool noBase=false;                              // split-half diagnostics: the base is not accumulated
        // F6 local alignment: per merge frame (0 = base, zeros) ny x nx tiles of (dx, dy) RAW px; laMode 0 = off
        const std::vector<float>* laField=nullptr;
        const std::vector<float>* laMotion=nullptr;     // per merge frame ny x nx tiles: local motion extent (RAW px), Z channel
        int laMode=0,laNx=0,laNy=0;
        float laOx=0,laOy=0,laStride=16;
        std::vector<float> laMaxY;                      // per frame: largest |dy| of the field (rows uploaded beyond the homography)
        // P33 W2.1: the field arrives in rows while the merge runs (laField / laMotion unused); laMaxY then holds a bound
        HybridLaStream* laStream=nullptr;
        // P33 W2.2: called on the merge thread after each strip's readback with the output rows [0, rows) of out complete
        std::function<void(std::vector<float>& out,int rows)> rowsDone;
        // P29: the raw mosaic behind these (binned) frames; null = every other merge. nativeFrames: its frames in merge order.
        const HybridMosaicNative* native=nullptr;
        std::vector<const uint16_t*> nativeFrames;
        std::vector<int> nativeRowFix;                  // P197: per merge frame, its index in HybridMosaicNative (row correction), -1 = none
        // The stream is above kHybridClassicPixels (any RAW size): strips sized by the readback, the storage block and the
        // available memory, dispatch chunks scaled by the width. false: the merge exactly as before.
        bool large=false;
    };
    long fixedOutliers=0,baseOutliers=0;                // sites flagged by the last merge
    // P214k: the plausibility cap of the last merge (0 / 0 without it): fixed-pattern sites it cleared and strips over the cap
    // (plain and native sites), the strips of the merge and the cap it ran with (sites per MP, 0 = off)
    long fixedCapped=0,natFixedCapped=0;int capStrips=0,natCapStrips=0,stripCount=0;float capPerMP=0.f;
    long natFixedOutliers=0,natBaseOutliers=0;          // P29: native sites flagged by the last native mosaic merge
    long rimPixels=0;                                   // output pixels whose R/B the clip-border ratios rebuilt
    // rimPass: compile the clip-border colour pass (kHybRim, ~0.14 s on Adreno 750); without it merge() runs as before the pass.
    // localAlign: compile the F6 local offsets into the reject / merge / rim programs (Frames::laMode needs it).
    const bool localAlign=false;
    // bentoPass: compile the Bento colour pass (kHybBento) for a shot with Bento and bentoChromaSigma > 0.
    // chromaPass: compile the colour-difference pass of the base frame (kHybChroma) for chromaDiff > 0.
    // early: optional report of the context and, off Adreno, of every program before it compiles (P26: the vivo X200 Pro's
    // Mali worker died between "HYBRID FRAMES" and the init report, so the failing step was unknown).
    // sumsCarry (any RAW size): the per-frame accepted-weight sums of kHybDilate (up to 255 per cell, uint32) carry into a second
    // word per frame; without it they wrap above 2^32 / 255 cells (67 MP). Report only (robustShare); false = the program as before.
    const bool sumsCarry=false;
    // nativeMosaic (P29): also compile the native mosaic programs (Frames::native needs them).
    // outlierPrograms: compile kHybMean / kHybFlags (the fixed-pattern and base transient outlier tests, hotSigma / hotBaseSigma).
    // false only for a merge with both thresholds 0: the vivo X200 Pro's Mali worker died compiling kHybFlags (owner's log
    // 2026-10-07), the app's retry turns the tests off and the merge then runs without those two programs. true = as before.
    const bool outlierPrograms=true;
    // dcg (P320): the DCG two-zone programs (hybridDcgSources) for a burst whose two noise zones were measured (hybridDcgZones);
    // false = the programs as before.
    const bool dcg=false;
    // blendPass (P310): compile the ultrashort blend (kHybUsBlend) for bentoBlend > 0 on the plain merge.
    explicit HybridGpu(bool rimPass=true,bool withLocalAlign=false,bool bentoPass=false,bool chromaPass=false,
                       const std::function<void(const std::string&)>& early=nullptr,bool withSumsCarry=false,bool nativeMosaic=false,
                       bool withOutlierPrograms=true,bool blendPass=false,bool dcgZones=false)
            :localAlign(withLocalAlign),sumsCarry(withSumsCarry),outlierPrograms(withOutlierPrograms),dcg(dcgZones){
        if(localAlign)helpersPrefix="#define LOCAL_ALIGN 1\n";
        try{
            display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
            worker_crash::mark("GPU context (EGL)");
            if(display==EGL_NO_DISPLAY||!eglInitialize(display,nullptr,nullptr)||!eglBindAPI(EGL_OPENGL_ES_API))throw std::runtime_error("EGL unavailable");
            const EGLint configAttrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
            EGLConfig config{};EGLint count=0;
            if(!eglChooseConfig(display,configAttrs,&config,1,&count)||count!=1)throw std::runtime_error("No EGL compute configuration");
            context=scamProcessingContext(display,config);
            if(context==EGL_NO_CONTEXT)throw std::runtime_error("Cannot create GLES 3.1 context");
            const EGLint size[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};surface=eglCreatePbufferSurface(display,config,size);
            if(surface==EGL_NO_SURFACE||!eglMakeCurrent(display,surface,surface,context))throw std::runtime_error("Cannot activate GLES context");
            const auto* name=glGetString(GL_RENDERER);renderer=name?reinterpret_cast<const char*>(name):"unknown";
            {const auto* version=glGetString(GL_VERSION);driver=renderer+"|"+(version?reinterpret_cast<const char*>(version):"");}
            {GLint64 ssbo=0;GLint tex=0,wg=0;glGetInteger64v(GL_MAX_SHADER_STORAGE_BLOCK_SIZE,&ssbo);glGetIntegerv(GL_MAX_TEXTURE_SIZE,&tex);
             glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_COUNT,1,&wg);maxStorageBlock=size_t(std::max<GLint64>(ssbo,0));
             GLint blocks=0,bindings=0;glGetIntegerv(GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS,&blocks);glGetIntegerv(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS,&bindings);
             limits="ssbo="+std::to_string(ssbo/(1024*1024))+"MB tex="+std::to_string(tex)+" wgY="+std::to_string(wg)
                 +" blocks="+std::to_string(blocks)+" bindings="+std::to_string(bindings);
             GLint wgX=0;glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_COUNT,0,&wgX);maxGroupsX=std::max<GLint>(wgX,0);
             EGLint prio=0; // P57: the priority the driver gave the context (0x3101 high, 0x3102 medium, 0x3103 low)
             if(eglQueryContext(display,context,0x3100,&prio)&&prio)limits+=prio==0x3103?" gpuPrio=low":prio==0x3102?" gpuPrio=medium":" gpuPrio=high";
             else eglGetError();}
            const bool adreno=renderer.find("Adreno")!=std::string::npos;
            if(early)early("HYBRID GPU: context "+renderer+" "+limits+(adreno?"":"; compiling program by program"));
            auto timed=[&](const char* name,const char* body,bool standalone=false){
                if(early&&!adreno)early(std::string("HYBRID GPU: compile ")+name);
                compileName=name;
                const auto t0=std::chrono::steady_clock::now();GLuint p=compile(body,standalone);
                compileMs+=std::string(" ")+name+"="+std::to_string(int(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count()));
                return p;
            };
            if(outlierPrograms){
                meanProgram=timed("mean",kHybMean);
                flagsProgram=timed("flags",(std::string(kHybFlagRank)+kHybFlags).c_str()); // P214k: the cap rank before the body
                capProgram=timed("cap",kHybFlagCap,true);
                pruneProgram=timed("prune",kHybFlagPrune,true);
            } else if(early)early("HYBRID GPU: outlier tests off, mean / flags programs not compiled");
            markProgram=timed("mark",kHybMark,true);
            guideProgram=timed("guide",dcg?hybridDcgSources().guide.c_str():kHybGuide); // P320: the zone's texture floor
            cellsProgram=timed("cells",kHybCells);
            // P76: the shared-memory variants (Adreno only; debugging: SCAM_HYB_NO_TILES=1 builds the Mali variants anywhere)
            const std::string tiles=std::string(adreno&&!std::getenv("SCAM_HYB_NO_TILES")?"#define SHARED_TILES 1\n":"");
            rejectProgram=timed("reject",(tiles+kHybReject).c_str());
            if(sumsCarry){ // the same program with a carry word per frame (the source without it stays byte-identical: same cache entry)
                std::string body=tiles+kHybDilate;
                const std::string plain="atomicAdd(sums[f],uint(clamp(r,0.0,1.0)*255.0+0.5));";
                const size_t at=body.find(plain);
                if(at==std::string::npos)throw std::runtime_error("HYBRID GPU: dilate program without its sum");
                body.replace(at,plain.size(),"{uint v=uint(clamp(r,0.0,1.0)*255.0+0.5);uint old=atomicAdd(sums[f],v);"
                    "if(old>0xFFFFFFFFu-v)atomicAdd(sums[frameCount+3+f],1u);}");
                dilateProgram=timed("dilate",body.c_str());
            } else dilateProgram=timed("dilate",(tiles+kHybDilate).c_str());
            { // debugging: SCAM_HYB_DEFS=A,B -> "#define A" / "#define B" before the merge body
                std::string defs;const char* env=std::getenv("SCAM_HYB_DEFS");
                if(env){std::stringstream s(env);std::string d;while(std::getline(s,d,','))if(!d.empty())defs+="#define "+d+"\n";}
                const std::string body=defs+mergeCommon()+kHybMergeMain1;
                mergeProgram=timed("merge",body.c_str());
            }
            if(rimPass){const std::string body=std::string("#define RIM_STATS 1\n")+mergeCommon()+kHybRim;rimProgram=timed("rim",body.c_str());}
            if(bentoPass){const std::string body=std::string("#define RIM_STATS 1\n#define BENTO_PASS 1\n")+mergeCommon()+kHybBento;bentoProgram=timed("bento",body.c_str());}
            if(blendPass){const std::string body=std::string("#define RIM_STATS 1\n#define BENTO_PASS 1\n")+mergeCommon()+kHybUsBlend;blendProgram=timed("blend",body.c_str());} // P310
            if(chromaPass){const std::string body=std::string("#define RIM_STATS 1\n#define CHROMA_PASS 1\n")+mergeCommon()+kHybChroma;chromaProgram=timed("chroma",body.c_str());}
            if(nativeMosaic){ // P29: new programs only, the ones above are compiled from the same sources as without it
                natMeanProgram=timed("natMean",(std::string(kHybNatAccess)+kHybNatMean).c_str());
                natFlagsProgram=timed("natFlags",(std::string(kHybNatAccess)+kHybFlagRank+kHybNatFlags).c_str());
                natMarkProgram=timed("natMark",kHybNatMark,true);
                natNormProgram=timed("natNorm",(std::string(kHybNatAccess)+kHybNatNorm).c_str());
                // the merge programs when merge() knows the window: kHybMergeMosaicFast (natFastProgram) or kHybMergeMosaic
                glGenBuffers(2,natTable);
                for(GLuint t:natTable){glBindBuffer(GL_UNIFORM_BUFFER,t);glBufferData(GL_UNIFORM_BUFFER,GLsizeiptr(size_t(kHybridGpuFrames)*16),nullptr,GL_DYNAMIC_DRAW);}
            }
            worker_crash::mark("GPU init (buffers)");
            if(cacheStores>0)trimProgramCache();
            if(!hybridProgramCacheDir().empty())compileMs+=" cache="+std::to_string(cacheHits)+"/"+std::to_string(cacheHits+cacheStores)+" hits";
            glGenBuffers(kSlots,buffers);
            glGenBuffers(1,&frameTable);glBindBuffer(GL_UNIFORM_BUFFER,frameTable);
            glBufferData(GL_UNIFORM_BUFFER,GLsizeiptr(6*size_t(kHybridGpuFrames)*16),nullptr,GL_DYNAMIC_DRAW);
            glBindBufferBase(GL_UNIFORM_BUFFER,0,frameTable);
            check("init");
        }catch(...){cleanup();throw;}
    }
    HybridGpu(const HybridGpu&)=delete;
    ~HybridGpu(){cleanup();}
    // P31 (W1.7): built on a helper thread during the front end, merged on the calling thread, destroyed on another: release() on
    // the thread that has the context current, acquire() on the next one (the destructor makes it current itself).
    void release(){if(display!=EGL_NO_DISPLAY)eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);}
    void acquire(){if(display==EGL_NO_DISPLAY||!eglMakeCurrent(display,surface,surface,context))throw std::runtime_error("Cannot activate GLES context");}
    bool hasBentoPass() const {return bentoProgram!=0;}
    bool hasBlendPass() const {return blendProgram!=0;}
    // P62 Mochi: the photometric correction of merge frame f (a bracketed frame) against the base, on the GPU (kHybMochiStats /
    // Blur / Apply), into out (w x h RAW words, the frame's own geometry). Bands of 16-row tiles keep every buffer within the
    // storage block. false (nothing written) when the frame cannot be corrected (upRatio != 1, a band larger than the block).
    struct MochiStats {int tiles=0,measured=0,zeroed=0,bands=0;double meanAbsDn[4]{};double ms=0;};
    bool mochi(const Frames& in,int f,float clipLevel,std::vector<uint16_t>& out,MochiStats& st){
        const auto t0=std::chrono::steady_clock::now();
        const int w=in.w,h=in.h;
        const BackwardHomography& H=in.homography[f];
        if(f<=0||f>=int(in.frames.size())||(w&1)||(h&1)||H.upRatio!=1.f)return false;
        if(!mochiStatsProgram){
            compileName="mochiStats";mochiStatsProgram=compile(kHybMochiStats,true);
            compileName="mochiBlur";mochiBlurProgram=compile(kHybMochiBlur,true);
            compileName="mochiApply";mochiApplyProgram=compile(kHybMochiApply,true);
        }
        const int nx=(w+15)/16,ny=(h+15)/16;
        // base rows behind the frame's rows: the vertical displacement of the homography over the frame (+ the bilinear support)
        float dMin=0,dMax=0;
        for(int k=0;k<9;++k){
            const int x=(k%3)*(w-1)/2,y=(k/3)*(h-1)/2;
            const float dy=H.project(x,y).y-float(y);
            if(!std::isfinite(dy))return false;
            if(k==0||dy<dMin)dMin=dy;
            if(k==0||dy>dMax)dMax=dy;
        }
        const int marginLo=int(std::ceil(std::max(dMax,0.f)))+8,marginHi=int(std::ceil(std::max(-dMin,0.f)))+8;
        const size_t rowBytes=size_t(w)*2,block=std::min<size_t>(maxStorageBlock?maxStorageBlock:(size_t(64)<<20),size_t(64)<<20);
        int bandTiles=int((block/rowBytes-size_t(marginLo+marginHi))/16);
        if(block/rowBytes<=size_t(marginLo+marginHi)+16||bandTiles<1)return false;
        bandTiles=std::min(bandTiles,ny);
        auto buffer=[&](int k,size_t bytes){
            GLuint& b=mochiBuf[k];if(!b)glGenBuffers(1,&b);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER,b);
            if(mochiCap[k]<bytes){glBufferData(GL_SHADER_STORAGE_BUFFER,GLsizeiptr(bytes),nullptr,GL_DYNAMIC_DRAW);mochiCap[k]=bytes;}
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,GLuint(k),b);
        };
        auto upload=[&](int k,const uint16_t* raw,int r0,int r1){
            buffer(k,size_t(r1-r0)*rowBytes);
            glBufferSubData(GL_SHADER_STORAGE_BUFFER,0,GLsizeiptr(size_t(r1-r0)*rowBytes),raw+size_t(r0)*w);
        };
        const size_t tileBytes=size_t(nx)*ny*16;
        buffer(2,tileBytes);buffer(3,tileBytes);buffer(4,tileBytes);
        const float t=1.f/std::max(in.gain[f],1e-6f);
        const int greens[2]={1^in.cfa,2^in.cfa};
        auto common=[&](GLuint p){
            glUseProgram(p);
            glUniform2i(glGetUniformLocation(p,"size"),w,h);
            glUniform2i(glGetUniformLocation(p,"tileCount"),nx,ny);
            glUniform4f(glGetUniformLocation(p,"black"),in.black[0],in.black[1],in.black[2],in.black[3]);
            glUniform4f(glGetUniformLocation(p,"inv"),in.inv[0],in.inv[1],in.inv[2],in.inv[3]);
            glUniform1f(glGetUniformLocation(p,"clipAt"),0.95f);
        };
        // statistics, band by band (the frame's rows and the base rows behind them)
        int altR0=-1,altR1=-1;
        for(int ty0=0;ty0<ny;ty0+=bandTiles){
            const int ty1=std::min(ny,ty0+bandTiles),r0=16*ty0,r1=std::min(h,16*ty1);
            const int b0=std::max(0,r0-marginLo)&~1,b1=std::min(h,r1+marginHi+1)&~1;
            if(mochiBasePtr!=in.frames[0]||mochiBaseR0!=b0||mochiBaseR1!=b1){
                upload(0,in.frames[0],b0,b1);mochiBasePtr=in.frames[0];mochiBaseR0=b0;mochiBaseR1=b1;
            } else buffer(0,size_t(b1-b0)*rowBytes); // the same rows of the same base: bound, not uploaded again
            if(ny>bandTiles)mochiBasePtr=nullptr;   // several bands overwrite it: no reuse
            upload(1,in.frames[f],r0,r1);altR0=r0;altR1=r1;
            buffer(2,tileBytes);buffer(3,tileBytes);
            common(mochiStatsProgram);
            const GLuint p=mochiStatsProgram;
            glUniform2i(glGetUniformLocation(p,"altRows"),r0,r1);
            glUniform2i(glGetUniformLocation(p,"baseRows"),b0,b1);
            glUniform2i(glGetUniformLocation(p,"tileRows"),ty0,ty1);
            glUniform2i(glGetUniformLocation(p,"greens"),greens[0],greens[1]);
            glUniform4f(glGetUniformLocation(p,"hA"),H.h[0],H.h[1],H.h[2],H.h[3]);
            glUniform4f(glGetUniformLocation(p,"hB"),H.h[4],H.h[5],H.h[6],H.h[7]);
            glUniform4f(glGetUniformLocation(p,"noise"),in.baseSlope,in.baseOffset,in.noiseSlope[f],in.noiseOffset[f]);
            glUniform2f(glGetUniformLocation(p,"expo"),t,1.f/t);
            glUniform1f(glGetUniformLocation(p,"bound"),22.4f/1023.f);
            glDispatchCompute(GLuint((nx+7)/8),GLuint((ty1-ty0+7)/8),1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            ++st.bands;
        }
        buffer(2,tileBytes);buffer(3,tileBytes);buffer(4,tileBytes);
        common(mochiBlurProgram);
        glDispatchCompute(GLuint((nx+7)/8),GLuint((ny+7)/8),1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        check("mochi stats");
        // the corrected frame, band by band (the last statistics band is still in Alt)
        out.resize(size_t(w)*h);
        const int lastTy0=((ny-1)/bandTiles)*bandTiles;
        for(int ty0=lastTy0;ty0>=0;ty0-=bandTiles){
            const int ty1=std::min(ny,ty0+bandTiles),r0=16*ty0,r1=std::min(h,16*ty1);
            if(!(ty0==lastTy0&&r0==altR0&&r1==altR1))upload(1,in.frames[f],r0,r1);
            buffer(4,tileBytes);buffer(5,size_t(r1-r0)*rowBytes);
            common(mochiApplyProgram);
            const GLuint p=mochiApplyProgram;
            glUniform2i(glGetUniformLocation(p,"altRows"),r0,r1);
            glUniform2i(glGetUniformLocation(p,"applyRows"),r0,r1);
            glUniform1f(glGetUniformLocation(p,"capLevel"),std::max(clipLevel-0.005f,0.95f));
            glDispatchCompute(GLuint((w/2+7)/8),GLuint((r1-r0+7)/8),1);
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
            check("mochi apply");
            getBuffer(mochiBuf[5],5,out.data()+size_t(r0)*w,size_t(r1-r0)*rowBytes,false);
        }
        { // report: measured / zeroed tiles and the mean |correction| per phase (10-bit DN, before the SNR weight)
            std::vector<float> tiles(size_t(nx)*ny*4),info(size_t(nx)*ny*4);
            getBuffer(mochiBuf[2],2,tiles.data(),tileBytes,false);
            getBuffer(mochiBuf[3],3,info.data(),tileBytes,false);
            st.tiles=nx*ny;
            for(size_t i=0;i<size_t(nx)*ny;++i){
                if(info[i*4+3]<=0.f)continue;
                ++st.measured;if(info[i*4+2]>0.f)++st.zeroed;
                for(int c=0;c<4;++c)st.meanAbsDn[c]+=std::abs(tiles[i*4+c])*1023.0;
            }
            if(st.measured)for(double& v:st.meanAbsDn)v/=st.measured;
        }
        for(int k=0;k<6;++k)if(buffers[k])glBindBufferBase(GL_SHADER_STORAGE_BUFFER,GLuint(k),buffers[k]); // merge()'s bindings
        st.ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();
        return true;
    }
    // P35 / P33: the native mosaic merge programs merge() compiles on first use (prewarm on module open, hybridPrewarmGpu): the fast
    // merge plain and, when the strips may carry site flags, marked; or the generic merge where the fast one does not apply.
    void prewarmNative(int block,int window,bool fill,bool fullWindow,bool generic,bool mayMark,bool demosaic=false){
        if(fullWindow&&window>=1&&window<=(block==4?8:3)&&!(fill&&window<2)&&!generic){
            natFastProgram(block,window,false,fill);
            if(mayMark)natFastProgram(block,window,true,fill);
            if(demosaic)natFastProgram(block,window,false,fill,false,true); // P282
        } else if(!mosaicProgram){
            compileName="mosaic";
            const auto t0=std::chrono::steady_clock::now();
            mosaicProgram=compile((std::string(mergeCommon())+kHybNatAccess+kHybMergeMosaic).c_str());
            compileMs+=" mosaic="+std::to_string(int(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count()));
        }
    }

    // out: RGB (w*g)*(h*g)*3 (base units) on the output grid g (1 = sensor, 2 = Sabre 6.1 2x); effective: donor
    // coverage per output pixel (frames), left empty without wantEffective (P187: nothing makes the effective-frame map from
    // it; the GPU still writes its slot, only the 4 B a pixel are not read back); robustShare[f]: mean accepted weight of frame
    // f after dilation, scalar weight and Bento mask; clipFlags (optional): per output pixel, bit 0/1/2 = R/G/B from the
    // clipped mean, 3 = a clipped sample was excluded, 4 = inside the Bento mask, 5 = the clipped mean includes the ultrashort
    // frame.
    void merge(const Frames& in,const HybridTuning& tune,const SuperResTuning& kernel,bool bento,
               std::vector<float>& out,std::vector<float>& effective,std::vector<double>& robustShare,int grid=1,
               std::vector<uint8_t>* clipFlags=nullptr,bool wantEffective=true){
        const int frames=int(in.frames.size()),w=in.w,h=in.h,w2=w/2,h2=h/2;
        if(frames<1||frames>kHybridGpuFrames||(w&1)||(h&1)||int(in.homography.size())!=frames)throw std::runtime_error("HYBRID GPU unsupported burst shape");
        // grid 8: only the native Tetra merge on the Sabre 2x grid (P56)
        if(grid!=1&&grid!=2&&grid!=4&&!(grid==8&&in.native&&in.native->block==4))throw std::runtime_error("HYBRID GPU grid");
        const int g=grid,ow=w*g;
        // The widest dispatch runs (ow + 7) / 8 work groups in x (the output passes; the site passes w).
        if(maxGroupsX>0&&(int64_t(std::max(ow,w))+7)/8>maxGroupsX)
            throw std::runtime_error("HYBRID GPU: output row "+std::to_string(std::max(ow,w))+" px > work-group limit "+std::to_string(maxGroupsX*8)+" px");
        out.assign(size_t(ow)*h*g*3,0.f);robustShare.assign(frames,1.0);
        if(wantEffective)effective.assign(size_t(ow)*h*g,1.f); else std::vector<float>().swap(effective);
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
            if(size_t(f)<in.noiseKnee.size()&&size_t(f)<in.noiseLcg.size()){noise4[f*4+2]=in.noiseKnee[f];noise4[f*4+3]=in.noiseLcg[f];} // P320
            param[f*4]=in.gain[f];param[f*4+1]=in.weight[f];param[f*4+2]=in.kmul[f];param[f*4+3]=float(in.role[f]);
        }
        { // the frame table: homographies, parameters and noise for the whole merge; the geometry follows per strip
            std::vector<GLuint> geo(size_t(frames)*4,0);
            for(int f=0;f<frames;++f){float u=up[f];std::memcpy(&geo[f*4+3],&u,4);}
            putTable(kTableGeo,geo.data(),frames);putTable(kTableHA,a.data(),frames);putTable(kTableHB,b.data(),frames);
            putTable(kTableParam,param.data(),frames);putTable(kTableNoise,noise4.data(),frames);
            if(!bankTable){glGenBuffers(1,&bankTable);glBindBuffer(GL_UNIFORM_BUFFER,bankTable);
                glBufferData(GL_UNIFORM_BUFFER,GLsizeiptr(6*size_t(kHybridGpuFrames)*16),nullptr,GL_DYNAMIC_DRAW);}
            for(size_t array:{kTableGeo,kTableHA,kTableHB,kTableParam,kTableNoise})
                putTableBank(array,array==kTableGeo?static_cast<const void*>(geo.data()):array==kTableHA?static_cast<const void*>(a.data())
                    :array==kTableHB?static_cast<const void*>(b.data()):array==kTableParam?static_cast<const void*>(param.data()):static_cast<const void*>(noise4.data()),frames,1);
            glBindBufferBase(GL_UNIFORM_BUFFER,0,frameTable);
            check("frame table");
        }
        worker_crash::mark("GPU merge");
        scam_progress::pass("merge",int64_t(h)*g); // P220: the output rows read back (each strip's, below)
        std::vector<GLuint> programs={meanProgram,flagsProgram,guideProgram,cellsProgram,rejectProgram,dilateProgram,mergeProgram};
        if(!flagsProgram)programs.erase(programs.begin(),programs.begin()+2); // outlierPrograms false: not compiled
        if(rimProgram)programs.push_back(rimProgram);
        if(bentoProgram)programs.push_back(bentoProgram);
        if(chromaProgram)programs.push_back(chromaProgram);
        if(blendProgram)programs.push_back(blendProgram);
        // P29 native mosaic (Frames::native): the frames above are the binned frames, the raw mosaic of each goes to slot 12
        const HybridMosaicNative* nat=in.native;
        // P34: Quad (window 1..3) and Tetra (window 1..8) with the full window merge with kHybNatNorm + kHybMergeMosaicFast (plain /
        // marked strips), anything else with kHybMergeMosaic (natMerge = the merge programs either way)
        bool natFastOn=false;GLuint natPlain=0,natMarked=0,natMarkedFill=0,natDem=0;std::vector<GLuint> natMerge;
        // P313: the ultrashort fill (mosaicUsFill) of the fast native merge with Bento: the merge, its dilate and demosaic as without
        // Bento (their uniforms), the ultrashort frames in a pass after them (plain / marked strips)
        bool natUs=false;GLuint natUsPlain=0,natUsMarked=0;
        if(nat){
            if(!natMarkProgram||!natNormProgram)throw std::runtime_error("HYBRID GPU compiled without the native mosaic programs");
            // P56: the output grid is the native sites (g = b) or the Sabre 2x grid of the sensor (g = 2b: 4 Quad, 8 Tetra)
            if(int(in.nativeFrames.size())!=frames||(nat->block!=2&&nat->block!=4)||(g!=nat->block&&g!=2*nat->block)
               ||nat->W!=w*nat->block||nat->H!=h*nat->block)
                throw std::runtime_error("HYBRID GPU native mosaic shape");
            for(const uint16_t* p:in.nativeFrames)if(!p)throw std::runtime_error("HYBRID GPU native mosaic frame");
            const bool fill=nat->fillSupport>0.f;
            natFastOn=nat->fullWindow&&nat->window>=1&&nat->window<=(nat->block==4?8:3)&&!(fill&&nat->window<2)&&!nat->generic;
            if(natFastOn){
                const bool mayMark=in.white>0&&in.white<16384.f&&(tune.cellClip||(!in.hotList.empty()&&(tune.hotSigma>0||tune.hotBaseSigma>0)));
                natPlain=natFastProgram(nat->block,nat->window,false,fill);natMerge.push_back(natPlain);
                if(mayMark){
                    natMarked=natFastProgram(nat->block,nat->window,true,fill);natMerge.push_back(natMarked);
                    natMarkedFill=natFastProgram(nat->block,nat->window,true,fill,true);natMerge.push_back(natMarkedFill); // P204
                }
                // P282: the base's own demosaic where the donors were rejected (baseDemosaic 0: no program, the merge as before)
                if(tune.baseDemosaic>0.f){natDem=natFastProgram(nat->block,nat->window,false,fill,false,true);natMerge.push_back(natDem);}
                natUs=bento&&tune.mosaicUsFill>0&&clipFlags!=nullptr; // the pass reads the merge's clipped share in the clip flags
                if(natUs){
                    natUsPlain=natFastProgram(nat->block,nat->window,false,fill,false,false,true);natMerge.push_back(natUsPlain);
                    if(mayMark){natUsMarked=natFastProgram(nat->block,nat->window,true,fill,false,false,true);natMerge.push_back(natUsMarked);}
                }
                programs.push_back(natNormProgram);
            } else {
                if(!mosaicProgram){
                    const auto t0=std::chrono::steady_clock::now();
                    compileName="mosaic";
                    mosaicProgram=compile((std::string(mergeCommon())+kHybNatAccess+kHybMergeMosaic).c_str());
                    compileMs+=" mosaic="+std::to_string(int(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count()));
                }
                natMerge.push_back(mosaicProgram);
            }
            programs.push_back(natMeanProgram);programs.push_back(natFlagsProgram);
            for(GLuint p:natMerge)programs.push_back(p);
        }
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
            glUniform4i(loc(program,"phaseColor"),in.phaseColor[0],in.phaseColor[1],in.phaseColor[2],in.phaseColor[3]);
            glUniform2f(loc(program,"baseNoise"),in.baseSlope,in.baseOffset);
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
        const size_t laTiles=size_t(std::max(in.laNx,0))*size_t(std::max(in.laNy,0));
        int laUploaded=0; // P33 W2.1: field rows [0, laUploaded) of every frame are in slots 15 / 17
        if(la&&in.laStream){
            if(in.laMode==0||in.laNx<1||in.laNy<1)throw std::runtime_error("HYBRID GPU local alignment field");
            if(maxStorageBlock>0&&size_t(frames)*laTiles*8>maxStorageBlock)
                throw std::runtime_error("HYBRID GPU: local alignment field "+hybridMB(size_t(frames)*laTiles*8)+" MB > storage block "+hybridMB(maxStorageBlock)+" MB");
            // zeros first (the base and frames without a field stay so); the rows of each strip follow before its rejection
            const std::vector<float> zero(size_t(frames)*laTiles*2,0.f);
            reserve(15,zero.size()*4);put(15,0,zero.data(),zero.size()*4);
            reserve(17,zero.size()*2);put(17,0,zero.data(),zero.size()*2);
        } else if(la){
            if(in.laMode==0||!in.laField||in.laNx<1||in.laNy<1||in.laField->size()!=size_t(frames)*in.laNx*in.laNy*2)
                throw std::runtime_error("HYBRID GPU local alignment field");
            // the whole burst's field is one storage block (12 MB at 12 MP x 32 frames; the caller then merges without it)
            if(maxStorageBlock>0&&in.laField->size()*4>maxStorageBlock)
                throw std::runtime_error("HYBRID GPU: local alignment field "+hybridMB(in.laField->size()*4)+" MB > storage block "+hybridMB(maxStorageBlock)+" MB");
            reserve(15,in.laField->size()*4);put(15,0,in.laField->data(),in.laField->size()*4);
            // Z channel: zeros without it (no boost from the motion test)
            const size_t zn=in.laField->size()/2;
            if(in.laMotion&&in.laMotion->size()==zn){reserve(17,zn*4);put(17,0,in.laMotion->data(),zn*4);}
            else{std::vector<float> z(zn,0.f);reserve(17,zn*4);put(17,0,z.data(),zn*4);}
        } else if(in.laMode!=0)throw std::runtime_error("HYBRID GPU compiled without local alignment");
        const bool hot=!in.hotList.empty()&&(tune.hotSigma>0||tune.hotBaseSigma>0)&&flagsProgram;
        // P214k: the plausibility cap of the fixed-pattern test (tune.hotCap sites per MP of a strip's own rows; kHybFlagRank)
        const bool capOn=hot&&tune.hotSigma>0&&tune.hotCap>0.f&&capProgram&&pruneProgram;
        // Site flags ride in the two spare top bits of the uploaded words: needs white < 16384 (RAW10/12/14) and no word above
        // 16383 (checked below with the clip scan: a white level below the codes the sensor really sends would turn bits 14/15 of
        // those codes into flags).
        const bool markable=in.white>0&&in.white<16384.f;
        int flagMode=markable?((tune.cellClip?1:0)|(hot?2:0)):0;
        if(!markable&&(tune.cellClip||hot)&&trace)trace("HYBRID GPU: white level "+std::to_string(in.white)+" leaves no spare bits: cell clip and outlier sites off");
        if(flagsProgram){
            glUseProgram(flagsProgram);
            glUniform4f(loc(flagsProgram,"hotSigU"),tune.hotSigma,tune.hotBaseSigma,tune.hotCross,tune.hotMaxLevel);
        }
        glUseProgram(guideProgram);
        glUniform4f(loc(guideProgram,"kA"),kernel.base,kernel.shrunk,kernel.stretched,kernel.flat);
        glUniform4f(loc(guideProgram,"kB"),kernel.strengthScale,kernel.flat0,kernel.flat1,kernel.texStd);
        // gradient noise of the green tensor: diagonal difference of four samples (+-1/4) of variance slope/4 (u domain)
        glUniform4f(loc(guideProgram,"kC"),kernel.tensorNoise,tune.rawTensor,tune.rawNoise*in.baseSlope*tune.snrScale/16.f,0);
        glUseProgram(cellsProgram);
        glUniform1i(loc(cellsProgram,"dcovU"),(in.mergeMode&1)?1:0); // DCov (slot 14) is sized for the cells only in this mode
        glUseProgram(rejectProgram);
        glUniform4f(loc(rejectProgram,"rj"),tune.cdm,tune.boost,tune.varianceThreshold,tune.filterVariance);
        // boost mode 1 needs the Z channel of the local alignment; without it only the forced mode 2 boosts
        const float boostMode=tune.boostEnable>1.5f?2.f:(tune.boostEnable>0.5f&&localAlign?1.f:0.f);
        glUniform4f(loc(rejectProgram,"rk"),boostMode,tune.motionThreshold,tune.rejPrior>0.f?1.f:0.f,0);
        // P310: with the ultrashort blend the merge (and its colour passes) runs as without Bento: the ultrashort frames get no
        // robust weight (kHybDilate), the base and the N frames their full one; kHybUsBlend blends them in after the strip's merge
        const bool usBlend=bento&&in.blend&&blendProgram!=0&&!in.native;
        if(bento&&in.blend&&!usBlend)throw std::runtime_error("HYBRID GPU: the ultrashort blend needs its program and the plain merge");
        const bool bentoMix=bento&&!usBlend;
        glUseProgram(dilateProgram);
        glUniform4f(loc(dilateProgram,"dl"),tune.dilateOffset,tune.dilateScale,bentoMix&&!natUs?1.f:0.f,tune.dilateFloor); // P313: off under the fill
        glUniform1i(loc(dilateProgram,"validPlanesU"),bentoMix?int(in.maskValid.size()):0);
        glUniform1f(loc(dilateProgram,"rampKU"),bentoMix?in.rampK:0.f); // P309
        glUseProgram(mergeProgram);
        glUniform4f(loc(mergeProgram,"kD"),tune.widenBelow,tune.widenMul,tune.kernelFloor,bentoMix?1.f:0.f);
        {const float us=std::max(0.3f,tune.bentoUsSigma),cs=tune.bentoChromaSigma;
         // The merge program stays exactly as it was: on Adreno 750 even one more term in its clip-flag condition made the whole
         // merge ~15x slower (and changed its rounding). The Bento colour pass gets its own uniforms.
         glUniform4f(loc(mergeProgram,"kE"),float(tune.debugFrame),1.f/(us*us),0,0);
         if(bentoMix&&bentoProgram!=0&&cs>0.f&&tune.bentoChroma>0.f){
             glUseProgram(bentoProgram);
             glUniform4f(loc(bentoProgram,"kE"),float(tune.debugFrame),1.f/(us*us),1.f/(cs*cs),std::clamp(tune.bentoChroma,0.f,16.f));
             glUniform4i(loc(bentoProgram,"kG"),g,ow,0,0);
             // y: no base; z: the merge wrote the clip flags (clip flags or the clip-border pass), else their bits are not read
             glUniform4i(loc(bentoProgram,"mergeModeU"),in.mergeMode,in.noBase?1:0,(clipFlags||(tune.rimRatio!=0&&rimProgram!=0))?1:0,0);
             glUseProgram(mergeProgram);
         }
         if(usBlend){ // P310: kE.z / kE.w = the wide Bento colour kernel and its strength, for the frames' R/G, B/G
             glUseProgram(blendProgram);
             glUniform4f(loc(blendProgram,"kE"),float(tune.debugFrame),1.f/(us*us),cs>0.f?1.f/(cs*cs):0.f,cs>0.f?std::clamp(tune.bentoChroma,0.f,16.f):0.f);
             glUniform4f(loc(blendProgram,"kD"),tune.widenBelow,tune.widenMul,tune.kernelFloor,0.f);
             glUniform4i(loc(blendProgram,"kG"),g,ow,0,0);
             glUniform4i(loc(blendProgram,"mergeModeU"),in.mergeMode,in.noBase?1:0,0,0);
             glUniform1i(loc(blendProgram,"validPlanesU"),int(in.maskValid.size()));
             glUniform4f(loc(blendProgram,"blendU"),std::max(tune.bentoBlendReject,0.01f),(clipFlags||(tune.rimRatio!=0&&rimProgram!=0))?1.f:0.f,
                         0.f,std::clamp(tune.bentoBlendChroma,0.f,1.f));
             glUniform1f(loc(blendProgram,"blendSatU"),std::clamp(tune.bentoBlendSat,0.f,1.f));
             {const float bNear=std::clamp(tune.bentoBlendNear,0.f,11.5f); // far stays at least 0.5 px above near (smoothstep)
              glUniform4f(loc(blendProgram,"blendClipU"),tune.bentoBlendClipLo,std::max(tune.bentoBlendClipHi,tune.bentoBlendClipLo+0.005f),
                          bNear,std::clamp(std::max(tune.bentoBlendFar,bNear+0.5f),0.5f,12.f));}
             glUniform4f(loc(blendProgram,"blendGateU"),tune.bentoBlendGateLo,std::max(tune.bentoBlendGateHi,tune.bentoBlendGateLo+0.01f),
                         std::clamp(tune.bentoBlendGateSigma,0.25f,6.f),0.f);
             glUniform4f(loc(blendProgram,"blendGainU"),std::clamp(tune.bentoBlendGainRadius,0.f,24.f),std::max(0.5f,0.5f*tune.bentoBlendGainRadius),
                         std::clamp(tune.bentoBlendGainSupport,0.01f,1.f),std::clamp(tune.bentoBlendGainMax,1.f,16.f));
             glUniform2f(loc(blendProgram,"blendGainLoU"),std::clamp(tune.bentoBlendGainLo,0.f,0.95f),std::clamp(in.blendGain,0.f,1.f));
             glUniform4f(loc(blendProgram,"blendPxU"),tune.bentoBlendPixLo,std::max(tune.bentoBlendPixHi,tune.bentoBlendPixLo+0.01f),
                         std::clamp(tune.bentoBlendFill,0.f,8.f),float(tune.bentoBlendDebug));
             glUseProgram(mergeProgram);
         }}
        glUniform4i(loc(mergeProgram,"kG"),g,ow,0,0);
        glUniform4i(loc(mergeProgram,"mergeModeU"),in.mergeMode,in.noBase?1:0,clipFlags?1:0,0);
        // Clip-border colour pass (kHybRim): the merge only marks its candidates in the clip flags (the ratio loops inside the merge
        // program made the whole merge ~18x slower on Adreno 750 even with the switch off, three more accumulators ~12 %).
        const bool rim=tune.rimRatio!=0&&rimProgram!=0;
        const bool bentoColourPass=bentoMix&&bentoProgram!=0&&tune.bentoChromaSigma>0.f&&tune.bentoChroma>0.f;
        const bool chromaPass=chromaProgram!=0&&tune.chromaDiff>0.f&&!in.noBase;
        if(chromaPass){
            const float us=std::max(0.3f,tune.bentoUsSigma);
            glUseProgram(chromaProgram);
            glUniform4f(loc(chromaProgram,"kD"),tune.widenBelow,tune.widenMul,tune.kernelFloor,bentoMix?1.f:0.f);
            glUniform4f(loc(chromaProgram,"kE"),float(tune.debugFrame),1.f/(us*us),0,0);
            glUniform4i(loc(chromaProgram,"kG"),g,ow,0,0);
            glUniform4i(loc(chromaProgram,"mergeModeU"),in.mergeMode,in.noBase?1:0,0,0);
            glUniform2f(loc(chromaProgram,"cdU"),std::clamp(tune.chromaDiff,0.f,1.f),tune.chromaDiffClamp?1.f:0.f);
            glUniform1f(loc(chromaProgram,"rampKU"),bentoMix?in.rampK:0.f); // P309
            glUniform4f(loc(chromaProgram,"bdU"),std::clamp(tune.baseDemosaic,0.f,1.f),std::clamp(tune.baseDemosaicAniso,0.f,2.f),
                        tune.baseDemosaicBayerLo,std::max(tune.baseDemosaicBayerHi,tune.baseDemosaicBayerLo+0.01f)); // P282
            glUniform1f(loc(chromaProgram,"bdAliasU"),std::clamp(tune.baseDemosaicAlias,0.f,1.f)); // P335
            glUseProgram(mergeProgram);
        }
        {const float s=std::max(0.3f,tune.rimSigma);
         const float rimU[4]={rim?1.f:0.f,-0.7213475f/(s*s),tune.rimLo,std::max(tune.rimHi,tune.rimLo+1e-4f)};
         glUniform4fv(loc(mergeProgram,"rimU"),1,rimU);
         if(rim){
         glUseProgram(rimProgram);
         glUniform4fv(loc(rimProgram,"rimU"),1,rimU);
         glUniform1i(loc(rimProgram,"rimStrideU"),std::max(1,tune.rimStride));
         glUniform1i(loc(rimProgram,"rimSitesU"),16);
         glUniform4f(loc(rimProgram,"kD"),tune.widenBelow,tune.widenMul,tune.kernelFloor,bentoMix?1.f:0.f);
         {const float us=std::max(0.3f,tune.bentoUsSigma);glUniform4f(loc(rimProgram,"kE"),float(tune.debugFrame),1.f/(us*us),0,0);}
         glUniform4i(loc(rimProgram,"kG"),g,ow,0,0);
         glUniform4i(loc(rimProgram,"mergeModeU"),in.mergeMode,in.noBase?1:0,clipFlags?1:0,0);}}
        // Sums (slot 9): per-frame accepted weight, the two outlier counts, rim pixels (sumsCarry: then the carry word of every frame's
        // weight); P29: then the two native outlier counts (natSum0)
        const size_t natSum0=size_t(std::max(frames,1))*(sumsCarry?2:1)+3;
        if(nat){ // P29: uniforms of the native programs
            // The binned passes re-sample the binned base at the binned positions; the native merge writes other positions and never
            // added the binned base's sums they correct (hybridReconstructMosaicNative turns them off; native variants are not built).
            if(chromaPass||rim||bentoColourPass)throw std::runtime_error("HYBRID GPU: the chroma / rim / Bento colour passes do not apply to the native mosaic merge");
            const int nb=nat->block,ns=nb==4?2:1,nr=std::clamp(nat->window,1,nb==4?8:6);
            const float ks=std::clamp(nat->kernelScale,0.1f,4.f);
            std::array<float,64> ng;ng.fill(1.f);
            if(nat->rawGains)ng=nat->gains;
            // P268: the gain field (binding 19); a field off still binds the 64 gains so every program has its buffer
            const bool gainField=nat->rawGains&&nat->field.on();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER,natFieldBuf?natFieldBuf:(glGenBuffers(1,&natFieldBuf),natFieldBuf));
            if(gainField)glBufferData(GL_SHADER_STORAGE_BUFFER,GLsizeiptr(nat->field.g.size()*sizeof(float)),nat->field.g.data(),GL_STATIC_DRAW);
            else glBufferData(GL_SHADER_STORAGE_BUFFER,GLsizeiptr(ng.size()*sizeof(float)),ng.data(),GL_STATIC_DRAW);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,19,natFieldBuf);
            std::vector<GLuint> natPrograms={natMeanProgram,natFlagsProgram,natMarkProgram};
            if(natFastOn)natPrograms.push_back(natNormProgram);
            for(GLuint p:natMerge)natPrograms.push_back(p);
            for(GLuint program:natPrograms){
                glUseProgram(program);
                glUniform4i(loc(program,"natU"),nb,nat->W,nat->H,nr);
                glUniform4i(loc(program,"natV"),ns,nat->rawGains?1:0,int(natSum0),0); // z: native outlier counters after the plain ones
                glUniform4f(loc(program,"natW"),in.white,in.white-1.f,0.f,0.f);
                glUniform4fv(loc(program,"natGain"),16,ng.data());
                glUniform4i(loc(program,"natGF"),gainField?nat->field.zx:0,gainField?nat->field.zy:0,std::max(nat->field.zw,1),std::max(nat->field.zh,1));
            }
            glUseProgram(natMarkProgram);
            glUniform2i(loc(natMarkProgram,"cfaShift"),in.cfa&1,in.cfa>>1);
            glUniform4f(loc(natMarkProgram,"black"),in.black[0],in.black[1],in.black[2],in.black[3]);
            glUniform4f(loc(natMarkProgram,"inv"),in.inv[0],in.inv[1],in.inv[2],in.inv[3]);
            glUniform1f(loc(natMarkProgram,"clipLevel"),tune.clipLevel);
            glUseProgram(natFlagsProgram);
            glUniform4f(loc(natFlagsProgram,"hotSigU"),tune.hotSigma,tune.hotBaseSigma,tune.hotCross,tune.hotMaxLevel);
            glUniform2f(loc(natFlagsProgram,"natNoise"),nat->siteSlope,nat->siteOffset);
            for(GLuint mosaicProgram:natMerge){ // P34: every merge program of the native path gets the same uniforms
                glUseProgram(mosaicProgram);
                glUniform4i(loc(mosaicProgram,"natV"),ns,nat->rawGains?1:0,int(natSum0),nat->fullWindow?1:0); // w: the merge's window rule
                glUniform1i(loc(mosaicProgram,"natWidenU"),nat->widenRule);
                glUniform1i(loc(mosaicProgram,"natBilinU"),tune.mosaicRobustBilinear); // P188
                glUniform2f(loc(mosaicProgram,"natWidenS"),nat->widenMul,nat->widenFrames);
                glUniform1f(loc(mosaicProgram,"rampKU"),bento?in.rampK:0.f); // P309
                glUniform1i(loc(mosaicProgram,"natClampU"),nat->clampCov); // P34
                const float us=std::max(0.3f,tune.bentoUsSigma),rs=std::max(0.3f,tune.rimSigma);
                glUniform4f(loc(mosaicProgram,"kD"),tune.widenBelow,tune.widenMul,tune.kernelFloor,bento&&!natUs?1.f:0.f); // P313: off under the fill
                if(natUs){glUniform2f(loc(mosaicProgram,"natUsU"),std::clamp(tune.mosaicUsFillShare,0.01f,1.f),0.f);
                          glUniform1i(loc(mosaicProgram,"validPlanesU"),int(in.maskValid.size()));}
                glUniform4f(loc(mosaicProgram,"kE"),float(tune.debugFrame),1.f/(us*us),0,0);
                glUniform4i(loc(mosaicProgram,"kG"),g,ow,0,0);
                glUniform4i(loc(mosaicProgram,"mergeModeU"),in.mergeMode,in.noBase?1:0,clipFlags?1:0,0);
                // P313: under the ultrashort fill the merge leaves its clipped share in the clip flags (the pass clears it)
                const float rimU[4]={rim||natUs?1.f:0.f,-0.7213475f/(rs*rs),tune.rimLo,std::max(tune.rimHi,tune.rimLo+1e-4f)};
                glUniform4fv(loc(mosaicProgram,"rimU"),1,rimU);
                const float kg=std::clamp(nat->ksG,0.25f,4.f),krb=std::clamp(nat->ksRB,0.25f,4.f);
                glUniform4f(loc(mosaicProgram,"natK"),1.f/(float(nb)*ks*float(nb)*ks),kg*kg,krb*krb,std::clamp(nat->fillSupport,0.f,1.f));
                // P282: share, anisotropy, cone slope (radius 2.2 sites Quad, 2.6 Tetra), cells a side (3 Quad, 5 Tetra)
                glUniform4f(loc(mosaicProgram,"natDemU"),std::clamp(tune.baseDemosaic,0.f,1.f),std::clamp(tune.baseDemosaicAniso,0.f,2.f),
                            nb==4?1.f/2.6f:1.f/2.2f,nb==4?5.f:3.f);
                glUniform2f(loc(mosaicProgram,"natDemD"),tune.baseDemosaicDetailLo,std::max(tune.baseDemosaicDetailHi,tune.baseDemosaicDetailLo+0.01f));
            }
            glUseProgram(mergeProgram);
            check("native uniforms");
        }
        // P214k: the two cap blocks (plain sites, native sites; kHybridCapWords each) after the native counters; without the cap
        // Sums is as before
        const size_t capSum0=natSum0+2;
        std::vector<GLuint> zeros(capOn?capSum0+2*kHybridCapWords:natSum0+(nat?2:0),0);
        reserve(9,zeros.size()*4);put(9,0,zeros.data(),zeros.size()*4);
        reserve(12,16); // mosaic frames: unused
        int stripCells=128/(g*g); // 256 output rows per dispatch on the sensor grid; on the 2x grid the strip
                                  // shrinks so the Out readback stays ~12 MB (Adreno refuses larger/offset read maps)
        // Any RAW size: the limits of a strip (in.large only; up to 16 MP the strips and dispatches are exactly as before).
        //   readback  the Out strip stays within today's largest (256 rows x 4624 px x 12 B, 14.2 MB): wider rows, fewer of them
        //   storage   every buffer bound to a program within GL_MAX_SHADER_STORAGE_BLOCK_SIZE (beyond it the reads are undefined;
        //             an earlier out-of-bounds SSBO read rebooted the phone): the strip halves until it fits, an 8-cell strip that
        //             still does not is an error
        //   memory    all strip buffers (both banks) within a quarter of MemAvailable: halved as well, down to 8 cells (soft)
        //   dispatch  rows per dispatch scaled by 4624 / width: the work of one dispatch stays within today's (GPU hang detection)
        const bool large=in.large;
        // P29: the native mosaic merge binds the raw mosaic rows of every frame (slot 12, b^2 x slot 0: the largest strip buffer) and
        // the native site flags (slots 1 / 2): its strips follow the same readback, storage-block and memory limits at any size.
        const bool fit=large||nat!=nullptr;
        constexpr int64_t kReadbackCap=int64_t(256)*4624*12;
        const size_t blockLimit=maxStorageBlock>0?maxStorageBlock:(size_t(128)<<20);
        uint64_t gpuBudget=0;
        if(fit){
            stripCells=std::max(8,std::min(stripCells,int(kReadbackCap/(2*int64_t(g)*ow*12))&~7));
            // P200 (OPPO Find X7 Ultra, 50 MP, 18 native frames: ~400 MB available at the GPU merge after its frames and output):
            // a quarter shrank 191 of 192 strips to 8 cells, whose row margins outweigh them - the merge took 127 s (20 s with
            // 32-cell strips, ~200 MB). Half of what is available, up to 512 MB, when that is more than a quarter (20 frames: a
            // 32-cell strip binds 356 MB; at a 288 MB budget 189 of 190 strips were halved and the merge took 95 s).
            const uint64_t avail=hybridMemAvailable();
            gpuBudget=std::max(avail/4,std::min(avail/2,uint64_t(512)<<20));
        }
        auto chunkOf=[&](int chunk){return large?std::min(chunk,std::max(8,int(int64_t(chunk)*4624/std::max(w,1))&~7)):chunk;};
        // P214k: the cap of one strip's fixed-pattern flags (slot 2, flagRows rows of `width` sites; the strip's own rows [own0, own1)
        // are the ones counted): kHybFlagCap turns the strip's rank histogram into the lowest rank kept within hotCap sites per MP of
        // its own rows, kHybFlagPrune clears bit 0 of the sites ranked below it (nothing when the strip is within the cap)
        auto capStrip=[&](size_t block,int width,int flagRows,int own0,int own1,int chunk){
            const double allowed=std::floor(double(tune.hotCap)*double(width)*double(std::max(own1-own0,0))/1e6);
            glUseProgram(capProgram);
            glUniform2i(loc(capProgram,"capU"),int(block),int(std::min(allowed,2.0e9)));
            glDispatchCompute(1,1,1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            glUseProgram(pruneProgram);
            glUniform4i(loc(pruneProgram,"pruneU"),width,flagRows,own0,own1);
            glUniform1i(loc(pruneProgram,"capBase"),int(block));
            dispatchRows(pruneProgram,width,flagRows,1,chunk,0);
            check("outlier cap");
        };
        if(large&&trace)trace("HYBRID GPU: large frame "+std::to_string(w)+"x"+std::to_string(h)+" grid "+std::to_string(g)+": strips of "
            +std::to_string(stripCells)+" cells (readback "+hybridMB(uint64_t(2)*stripCells*g*ow*12)+" MB), dispatch rows x"+std::to_string(chunkOf(64))
            +"/64, storage block "+hybridMB(blockLimit)+" MB, strip memory budget "+(gpuBudget?hybridMB(gpuBudget)+" MB":std::string("unknown")));
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
        // P29: the same scan of the native mosaic (its own clipped sites and flag bits; the binned averages hide single clipped sites)
        int natFlagMode=0,natBlocks=0;
        std::vector<uint8_t> natClipBlock;
        std::vector<GLuint> natOffsets,natNormOff;std::vector<GLint> natRow0,natRows;
        size_t natNormBlocks=0; // P34: blocks of the strip's normalised frames (kHybNatNorm)
        std::vector<uint16_t> natFixed; // P197: the strip rows of a row-corrected frame (HybridMosaicNative::fixRows)
        if(nat){
            natOffsets.assign(frames,0);natNormOff.assign(frames,0);natRow0.assign(frames,0);natRows.assign(frames,1);
            natFlagMode=markable?((tune.cellClip?1:0)|(hot?2:0)):0;
            natBlocks=(nat->H+kBlock-1)/kBlock;
            natClipBlock.assign(size_t(frames)*natBlocks,0);
            if(natFlagMode){
                float lowest=1e9f;
                if(nat->rawGains){ // RAW codes: the lowest code whose gained value can reach the clip level, or the clipped code
                    float gmax=1e-3f;for(float gk:nat->gains)gmax=std::max(gmax,gk);
                    for(int sp=0;sp<4;++sp)for(int cp=0;cp<4;++cp)lowest=std::min(lowest,in.black[sp]+tune.clipLevel/std::max(in.inv[cp],1e-12f)/gmax);
                    lowest=std::min(lowest,in.white-1.f);
                } else
                for(int p=0;p<4;++p)lowest=std::min(lowest,in.black[p]+tune.clipLevel/std::max(in.inv[p],1e-12f));
                const uint16_t threshold=uint16_t(std::clamp(std::ceil(lowest),0.f,65535.f));
                std::vector<uint16_t> blockMax(size_t(frames)*natBlocks,0);
                const int NW=nat->W,NH=nat->H,nbl=natBlocks;
                mergeRowBands(frames*nbl,[&](int i0,int i1){
                    std::vector<uint16_t> fixed; // P197: the rows of a row-corrected frame as the upload will send them
                    for(int i=i0;i<i1;++i){
                        const int f=i/nbl,bk=i%nbl;
                        const uint16_t* row=in.nativeFrames[f]+size_t(bk)*kBlock*NW;
                        const size_t cnt=size_t(std::min(kBlock,NH-bk*kBlock))*NW;
                        const int nk=f<int(in.nativeRowFix.size())?in.nativeRowFix[f]:-1;
                        if(nat->fixesRows(nk)){fixed.resize(cnt);nat->fixRows(nk,bk*kBlock,int(cnt/NW),fixed.data());row=fixed.data();}
                        uint16_t m=0;for(size_t k=0;k<cnt;++k)m=std::max(m,row[k]);
                        blockMax[i]=m;natClipBlock[i]=(natFlagMode&1)&&m>=threshold;
                    }
                });
                const uint16_t top=*std::max_element(blockMax.begin(),blockMax.end());
                if(top>=16384){
                    natFlagMode=0;
                    if(trace)trace("HYBRID GPU: native mosaic codes up to "+std::to_string(top)+" leave no spare bits: native cell clip and outlier sites off");
                }
            }
        }
        // P30: the readback of strip k runs after strip k+1 is submitted (fence), from the other bank.
        struct PendingStrip{bool active=false;int bank=0,oy0=0,rows2=0,index=0;GLsync fence=nullptr;};
        PendingStrip pending;
        auto readStrip=[&](PendingStrip& p){
            if(!p.active)return;
            const auto readStarted=std::chrono::steady_clock::now();
            bool waited=false;
            scam_progress::stallAt(p.index); // P220: SCAM_STALL_STRIP (replay test of the app's watchdog) only
            { // P220: the fence and the maps behind it are the GPU's time (a hung GPU stops here: the app's watchdog sees fence=)
            scam_progress::FenceWait gpuWait;
            if(p.fence){
                const GLenum r=glClientWaitSync(p.fence,GL_SYNC_FLUSH_COMMANDS_BIT,GLuint64(120)*1000000000ULL);
                waited=r==GL_ALREADY_SIGNALED||r==GL_CONDITION_SATISFIED;
                glDeleteSync(p.fence);p.fence=nullptr;
            }
            getBuffer(bankBuffer(3,p.bank),3,out.data()+size_t(p.oy0)*ow*3,size_t(p.rows2)*ow*3*4,waited);
            if(wantEffective)getBuffer(bankBuffer(4,p.bank),4,effective.data()+size_t(p.oy0)*ow,size_t(p.rows2)*ow*4,waited);
            if(clipFlags){
                flagStrip.resize(size_t(p.rows2)*ow);
                getBuffer(bankBuffer(6,p.bank),6,flagStrip.data(),flagStrip.size()*4,waited);
                uint8_t* dst=clipFlags->data()+size_t(p.oy0)*ow;
                for(size_t i=0;i<flagStrip.size();++i)dst[i]=uint8_t(flagStrip[i]);
            }
            }
            check("readback");
            passMs[6]+=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-readStarted).count();
            p.active=false;
        };
        // Upload the rows of every frame that output rows [y0-6, y1+6) map into (kernel + margins), even-aligned: the plan of the
        // strip of cells [cy0, cy1) (row0 / rows / offsets of every frame, its RAW words and donor cells).
        auto planStrip=[&](int cy0,int cy1,size_t& total,size_t& cellTotal){
            const int ry0=std::max(0,cy0-3),ry1=std::min(h2,cy1+3);
            total=0;cellTotal=0;
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
        };
        // P29: the native rows behind every binned row of the plan (b per binned row: the margins of the binned window cover the
        // native window +-(r+1) px) into natRow0 / natRows / natOffsets; returns the 16-bit words of slot 12 (0 without the native merge)
        auto natPlan=[&]()->size_t{
            if(!nat)return 0;
            const int NW=nat->W,NH=nat->H,nb=nat->block;
            size_t ntotal=0;natNormBlocks=0;
            for(int f=0;f<frames;++f){
                const int r0=std::min(NH-nb,nb*row0[f]),r1=std::min(NH,nb*(row0[f]+rows[f]));
                natRow0[f]=r0;natRows[f]=std::max(1,r1-r0);natOffsets[f]=GLuint(ntotal);ntotal+=size_t(natRows[f])*NW;
                natNormOff[f]=GLuint(natNormBlocks);natNormBlocks+=size_t(NW/2)*(natRows[f]/2);
            }
            return ntotal;
        };
        // Bytes the strip binds per storage slot (the reserve() calls below; slots 0, 3, 4, 6, 12 and 13 exist in both banks).
        auto stripSlots=[&](int cy0,int cy1,size_t total,size_t cellTotal,size_t ntotal,std::array<size_t,kSlots>& s){
            const int ry0=std::max(0,cy0-3),ry1=std::min(h2,cy1+3),rows2=2*(cy1-cy0)*g;
            s.fill(0);
            s[0]=total*2;
            // P214k: with the outlier cap slot 1 holds the temporal variance too (twice this: a few MB a strip, far below any storage
            // block); left out here so that a shot's strips stay exactly as before
            s[1]=(size_t(ry1-ry0)*2+4)*w*4;
            s[2]=size_t(ry1-ry0)*w*2*4;
            s[3]=size_t(rows2)*ow*3*4;
            s[4]=size_t(rows2)*ow*4;
            s[5]=std::max<size_t>(cellTotal,1)*16;
            s[6]=(clipFlags||rim||bentoColourPass||usBlend)?size_t(rows2)*ow*4:16;
            s[7]=size_t(donors)*(ry1-ry0)*w2*4;
            s[8]=size_t(donors)*(cy1-cy0)*w2*4;
            s[10]=s[11]=size_t(ry1-ry0)*w2*16;
            if(usBlend)s[10]=std::max(s[10],(size_t(2)*stripCells*g*ow+size_t(2)*ow)*16); // P310: the blend's scratch and carry rows
            s[13]=size_t(cy1-cy0)*w2*(1+in.maskValid.size())*4;
            s[14]=(in.mergeMode&1)?std::max<size_t>(cellTotal,1)*8:16;
            s[16]=la?size_t(donors)*(cy1-cy0)*w2*4:0;
            if(nat){ // P29: the raw mosaic rows (slot 12) and the native site flags / mean of the flag rows (slots 2 / 1, shared)
                const int NW=nat->W,NH=nat->H,nb=nat->block;
                const int fr0=std::min(NH,2*ry0*nb),fr1=std::min(NH,2*ry1*nb);
                s[12]=ntotal*2;
                s[2]=std::max(s[2],std::max<size_t>(size_t(fr1-fr0)*NW,1)*4);
                if(hot)s[1]=std::max(s[1],(size_t(fr1-fr0)+size_t(4*nb))*NW*4);
            }
        };
        int stripIndex=0,stripsShrunk=0,smallestStrip=stripCells;
        // P200: why the first strip was halved (the slot over the storage block, or the bytes over the memory budget)
        std::string shrinkWhy;
        for(int cy0=0,cy1=0;cy0<h2;cy0=cy1,++stripIndex){
            const int bank=stripIndex&1;
            cy1=std::min(h2,cy0+stripCells);
            size_t total=0,cellTotal=0;
            planStrip(cy0,cy1,total,cellTotal);
            size_t ntotal=natPlan();
            if(fit){ // halve the strip until every slot fits the storage block and the strip memory the budget
                bool shrunk=false;
                for(;;){
                    std::array<size_t,kSlots> slot{};stripSlots(cy0,cy1,total,cellTotal,ntotal,slot);
                    int worst=0;uint64_t sum=0;
                    for(int k=0;k<kSlots;++k){
                        if(slot[k]>slot[worst])worst=k;
                        sum+=uint64_t(slot[k])*((k==0||k==3||k==4||k==6||k==12||k==13)?2:1);
                    }
                    const size_t normBytes=natFastOn?natNormBlocks*16:0; // P34: the normalised blocks (binding 18, one bank)
                    sum+=normBytes;
                    const bool overBlock=slot[worst]>blockLimit||normBytes>blockLimit,overMemory=gpuBudget>0&&sum>gpuBudget;
                    if(!overBlock&&!overMemory)break;
                    if(shrinkWhy.empty())shrinkWhy=std::string(overBlock?"storage slot "+std::to_string(slot[worst]>=normBytes?worst:18)+" "
                        +hybridMB(std::max(slot[worst],normBytes))+" MB > block "+hybridMB(blockLimit)+" MB":"")
                        +(overMemory?std::string(overBlock?", ":"")+"strip "+hybridMB(sum)+" MB > budget "+hybridMB(gpuBudget)+" MB":"")
                        +" at "+std::to_string(cy1-cy0)+" cells";
                    if(cy1-cy0<=8){
                        if(overBlock)throw std::runtime_error("HYBRID GPU: strip needs "+hybridMB(std::max(slot[worst],normBytes))+" MB in storage slot "+std::to_string(slot[worst]>=normBytes?worst:18)
                            +", GPU block limit "+hybridMB(blockLimit)+" MB");
                        break; // the memory budget is a target, not a limit: the smallest strip goes on
                    }
                    cy1=cy0+std::max(8,(cy1-cy0)/2);shrunk=true;
                    planStrip(cy0,cy1,total,cellTotal);
                    ntotal=natPlan();
                }
                if(shrunk){++stripsShrunk;smallestStrip=std::min(smallestStrip,cy1-cy0);}
            }
            const int y0=2*cy0,y1=2*cy1;
            const int ry0=std::max(0,cy0-3),ry1=std::min(h2,cy1+3); // reject/guide margin for the 5x5 dilation
            reserveBank(0,total*2,bank);
            for(int f=0;f<frames;++f)putBank(0,bank,size_t(offsets[f])*2,in.frames[f]+size_t(row0[f])*w,size_t(rows[f])*w*2);
            for(int f=0;f<frames;++f){cellGeom[f*4]=crow0[f];cellGeom[f*4+1]=crows[f];cellGeom[f*4+2]=GLint(cellOff[f]);cellGeom[f*4+3]=0;}
            { // strip geometry of every frame into the frame table (all programs read it from there)
                std::vector<GLuint> geo(size_t(frames)*4);
                for(int f=0;f<frames;++f){geo[f*4]=offsets[f];geo[f*4+1]=GLuint(row0[f]);geo[f*4+2]=GLuint(rows[f]);float u=up[f];std::memcpy(&geo[f*4+3],&u,4);}
                putTableBank(kTableGeo,geo.data(),frames,bank);putTableBank(kTableCells,cellGeom.data(),frames,bank);
                glBindBufferBase(GL_UNIFORM_BUFFER,0,tableOf(bank));
            }
            // P29: the native rows behind every binned row uploaded above (natPlan), slot 12 and NatTable of this strip's bank
            int natStripMode=0;
            if(nat){
                const int NW=nat->W;
                reserveBank(12,ntotal*2,bank);
                for(int f=0;f<frames;++f){
                    const int nk=f<int(in.nativeRowFix.size())?in.nativeRowFix[f]:-1;
                    if(nat->fixesRows(nk)){ // P197: the row-corrected ultrashort frame, through a strip-sized copy
                        natFixed.resize(size_t(natRows[f])*NW);nat->fixRows(nk,natRow0[f],natRows[f],natFixed.data());
                        putBank(12,bank,size_t(natOffsets[f])*2,natFixed.data(),size_t(natRows[f])*NW*2);
                    } else putBank(12,bank,size_t(natOffsets[f])*2,in.nativeFrames[f]+size_t(natRow0[f])*NW,size_t(natRows[f])*NW*2);
                }
                std::vector<GLuint> ngeo(size_t(frames)*4,0);
                for(int f=0;f<frames;++f){ngeo[f*4]=natOffsets[f];ngeo[f*4+1]=GLuint(natRow0[f]);ngeo[f*4+2]=GLuint(natRows[f]);ngeo[f*4+3]=natNormOff[f];}
                glBindBuffer(GL_UNIFORM_BUFFER,natTable[bank]);
                glBufferSubData(GL_UNIFORM_BUFFER,0,GLsizeiptr(ngeo.size()*4),ngeo.data());
                glBindBufferBase(GL_UNIFORM_BUFFER,1,natTable[bank]);
                bool natClipHere=false;
                for(int f=0;f<frames&&!natClipHere;++f)
                    for(int bk=natRow0[f]/kBlock;bk<=(natRow0[f]+natRows[f]-1)/kBlock&&bk<natBlocks;++bk)if(natClipBlock[size_t(f)*natBlocks+bk]){natClipHere=true;break;}
                natStripMode=hot?natFlagMode:(natClipHere?natFlagMode:0);
                check("native upload");
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
            reserve(1,(size_t(ry1-ry0)*2+4)*w*4*(capOn?2:1)); // fixed-pattern mean (rows +-2); P214k: then the sites' temporal variance
            // Bento mask rows of this strip (per cell).
            const size_t planeSize=size_t(cy1-cy0)*w2;
            maskStrip.assign(planeSize*(1+in.maskValid.size())+(usBlend?size_t(2)*w2:0),0.f);
            if(bento&&in.mask&&in.mask->size()==size_t(w2)*h2)std::memcpy(maskStrip.data(),in.mask->data()+size_t(cy0)*w2,planeSize*4);
            for(size_t k=0;k<in.maskValid.size();++k)if(in.maskValid[k]&&in.maskValid[k]->size()==size_t(w2)*h2)std::memcpy(maskStrip.data()+planeSize*(k+1),in.maskValid[k]->data()+size_t(cy0)*w2,planeSize*4);
            if(usBlend&&in.mask&&in.mask->size()==size_t(w2)*h2){ // P310: the weight rows just above and below the strip (bilinear across)
                float* edge=maskStrip.data()+planeSize*(1+in.maskValid.size());
                std::memcpy(edge,in.mask->data()+size_t(std::max(cy0-1,0))*w2,size_t(w2)*4);
                std::memcpy(edge+w2,in.mask->data()+size_t(std::min(cy1,h2-1))*w2,size_t(w2)*4);
            }
            reserveBank(13,maskStrip.size()*4,bank);putBank(13,bank,0,maskStrip.data(),maskStrip.size()*4);
            if(stripMode){
                if(flagsProgram){
                    glUseProgram(flagsProgram);
                    glUniform1i(loc(flagsProgram,"ry0"),ry0);glUniform1i(loc(flagsProgram,"ry1"),ry1);
                    glUniform1i(loc(flagsProgram,"cy0"),cy0);glUniform1i(loc(flagsProgram,"cy1"),cy1);
                }
                if(hot){
                    // fixed-pattern mean: the hot frames that hold every row of the strip +-2 (motion moves the upload window).
                    // The strip rows are canonical; the frame holds sensor rows (canonical + cfa>>1): a row past the window is
                    // clamped to the last one held, a site of the other colour.
                    std::vector<GLint> list;
                    const int cfaY=in.cfa>>1;
                    for(int f:in.hotList)if(row0[f]<=std::max(0,2*ry0-2+cfaY)&&row0[f]+rows[f]>=std::min(h,2*ry1+2+cfaY))list.push_back(f);
                    const int nh=std::min<int>(16,int(list.size()));
                    const int meanWords=(2*(ry1-ry0)+4)*w; // P214k: the temporal variance after the mean (cap rank)
                    glUseProgram(meanProgram);
                    if(nh>0)glUniform1iv(loc(meanProgram,"hotList"),nh,list.data());
                    glUniform4i(loc(meanProgram,"hotU"),nh,capOn?meanWords:0,0,0);
                    glUniform1i(loc(meanProgram,"ry0"),ry0);glUniform1i(loc(meanProgram,"ry1"),ry1);
                    dispatchRows(meanProgram,w,2*(ry1-ry0)+4,1,chunkOf(512),0);
                    glUseProgram(flagsProgram);
                    glUniform4i(loc(flagsProgram,"hotU"),nh,(tune.hotSigma>0?1:0)|(tune.hotBaseSigma>0?2:0),capOn?int(capSum0):0,capOn?meanWords:0);
                    dispatchRows(flagsProgram,w,2*(ry1-ry0),1,chunkOf(128),0);
                    if(capOn)capStrip(capSum0,w,2*(ry1-ry0),2*(cy0-ry0),2*(cy1-ry0),chunkOf(128)); // P214k
                } // without outlier tests the mark pass does not read the site flags
                check("flags");
                // write the flags into the RAW words of every frame (after kHybFlags, which reads the plain values)
                glUseProgram(markProgram); // frame offsets and rows: the frame table
                glUniform4i(loc(markProgram,"mFlagsU"),ry0,ry1,stripMode,0);
                const GLint frameIdx=loc(markProgram,"frameIdx");
                int maxRows=1;for(int f=0;f<frames;++f)maxRows=std::max(maxRows,rows[f]);
                if(stripMode==1){
                    // P33 W2.2: cell clip only (no outlier sites): the rows of the 32-row blocks that hold a clipped sample, and of
                    // their neighbour blocks (a canonical cell may straddle two blocks). Everywhere else the pass writes the words
                    // back unchanged (fresh uploads: flag bits 0, no clipped cell), so it is not dispatched there.
                    const GLint chunkLoc=loc(markProgram,"chunkU");
                    const int chunk=chunkOf(128);
                    const auto markStarted=std::chrono::steady_clock::now();
                    auto clipNear=[&](int f,int bk){
                        for(int d=-1;d<=1;++d){const int bb=bk+d;if(bb>=0&&bb<blocks&&clipBlock[size_t(f)*blocks+bb])return true;}
                        return false;
                    };
                    for(int f=0;f<frames;++f){
                        glUniform1i(frameIdx,f);
                        int runStart=-1;
                        const int firstBlock=row0[f]/kBlock,lastBlock=(row0[f]+rows[f]-1)/kBlock;
                        for(int bk=firstBlock;bk<=lastBlock+1;++bk){
                            const bool need=bk<=lastBlock&&clipNear(f,bk);
                            const int r0=std::clamp(bk*kBlock-row0[f],0,rows[f]);
                            if(need&&runStart<0)runStart=r0;
                            if(!need&&runStart>=0){
                                for(int r=runStart;r<r0;r+=chunk){
                                    glUniform1i(chunkLoc,r);
                                    glDispatchCompute(GLuint((w2+7)/8),GLuint((std::min(chunk,r0-r)+15)/16),1); // two rows an invocation
                                }
                                runStart=-1;
                            }
                        }
                    }
                    glFlush();
                    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                    if(profile){glFinish();passMs[7]+=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-markStarted).count();}
                } else {
                glUniform1i(frameIdx,0);
                // two rows an invocation (kHybMark): chunkU stays in rows, the groups cover half of them
                const GLint chunkLoc=loc(markProgram,"chunkU");
                const int chunk=chunkOf(128);
                const auto markStarted=std::chrono::steady_clock::now();
                for(int r=0;r<maxRows;r+=chunk){
                    glUniform1i(chunkLoc,r);
                    glDispatchCompute(GLuint((w2+7)/8),GLuint((std::min(chunk,maxRows-r)+15)/16),GLuint(std::max(frames,1)));
                }
                glFlush();
                glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                if(profile){glFinish();passMs[7]+=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-markStarted).count();}
                }
                check("mark");
            }
            if(nat&&natStripMode){ // P29: the same flags on the native sites (kHybNatMean / kHybNatFlags / kHybNatMark)
                const int NW=nat->W,NH=nat->H,nb=nat->block;
                const int fr0=std::min(NH,2*ry0*nb),fr1=std::min(NH,2*ry1*nb); // native canonical rows of the strip's cells +-3
                // rows per dispatch scaled by 4624 / width as the large frames' (the work of one dispatch stays within today's)
                auto natChunkOf=[&](int c){return std::min(c,std::max(8,int(int64_t(c)*4624/std::max(NW,1))&~7));};
                reserve(2,std::max<size_t>(size_t(fr1-fr0)*NW,1)*4);
                if(hot){
                    reserve(1,(size_t(fr1-fr0)+size_t(4*nb))*NW*4*(capOn?2:1)); // P214k: then the temporal variance
                    const int meanWords=(fr1-fr0+4*nb)*NW;
                    std::vector<GLint> list;
                    const int cfaY=(in.cfa>>1)*nb;
                    for(int f:in.hotList)if(natRow0[f]<=std::max(0,fr0-2*nb+cfaY)&&natRow0[f]+natRows[f]>=std::min(NH,fr1+2*nb+cfaY))list.push_back(f);
                    const int nh=std::min<int>(16,int(list.size()));
                    glUseProgram(natMeanProgram);
                    if(nh>0)glUniform1iv(loc(natMeanProgram,"hotList"),nh,list.data());
                    glUniform4i(loc(natMeanProgram,"hotU"),nh,capOn?meanWords:0,0,0);
                    glUniform2i(loc(natMeanProgram,"natRows"),fr0,fr1);
                    dispatchRows(natMeanProgram,NW,fr1-fr0+4*nb,1,natChunkOf(512),0);
                    glUseProgram(natFlagsProgram);
                    glUniform4i(loc(natFlagsProgram,"hotU"),nh,(tune.hotSigma>0?1:0)|(tune.hotBaseSigma>0?2:0),capOn?int(capSum0+kHybridCapWords):0,capOn?meanWords:0);
                    glUniform2i(loc(natFlagsProgram,"natRows"),fr0,fr1);
                    glUniform2i(loc(natFlagsProgram,"natCount"),2*cy0*nb,2*cy1*nb);
                    dispatchRows(natFlagsProgram,NW,fr1-fr0,1,natChunkOf(128),0);
                    if(capOn)capStrip(capSum0+kHybridCapWords,NW,fr1-fr0,std::max(0,2*cy0*nb-fr0),std::max(0,std::min(fr1,2*cy1*nb)-fr0),natChunkOf(128)); // P214k
                }
                check("native flags");
                glUseProgram(natMarkProgram);
                glUniform4i(loc(natMarkProgram,"mFlagsU"),fr0,fr1,natStripMode,0);
                int maxRows=1;for(int f=0;f<frames;++f)maxRows=std::max(maxRows,natRows[f]);
                glUniform1i(loc(natMarkProgram,"frameIdx"),0);
                dispatchRows(natMarkProgram,NW/2,maxRows,frames,natChunkOf(128),7);
                check("native mark");
            }
            if(natFastOn){ // P34: every held site of every frame normalised once, one uvec4 per colour block (binding 18)
                glBindBuffer(GL_SHADER_STORAGE_BUFFER,natNormBuf?natNormBuf:(glGenBuffers(1,&natNormBuf),natNormBuf));
                if(natNormCap<natNormBlocks*16){glBufferData(GL_SHADER_STORAGE_BUFFER,GLsizeiptr(natNormBlocks*16),nullptr,GL_DYNAMIC_DRAW);natNormCap=natNormBlocks*16;}
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER,18,natNormBuf);
                glUseProgram(natNormProgram);
                glUniform1i(loc(natNormProgram,"natMarkU"),natStripMode?1:0);
                glUniform1i(loc(natNormProgram,"frameIdx"),0);
                int maxRows=1;for(int f=0;f<frames;++f)maxRows=std::max(maxRows,natRows[f]/2);
                dispatchRows(natNormProgram,nat->W/2,maxRows,frames,std::min(256,std::max(8,int(int64_t(256)*2048/std::max(nat->W/2,1))&~7)),7);
                check("native norm");
            }
            glUseProgram(guideProgram);
            glUniform1i(loc(guideProgram,"ry0"),ry0);glUniform1i(loc(guideProgram,"ry1"),ry1);
            dispatchRows(guideProgram,w2,ry1-ry0,1,chunkOf(256),1);
            check("guide");
            if(frames>1){
                glUseProgram(cellsProgram);
                const GLint cellFrame=loc(cellsProgram,"cellFrameU");
                if(in.mergeMode&1){ // 6.1 covariance (36 RAW sites a cell): one donor per dispatch keeps the reads of a frame together
                    for(int f=1;f<frames;++f){glUniform1i(cellFrame,f-1);dispatchRows(cellsProgram,w2,crows[f],1,chunkOf(256),2,false);}
                    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                } else {
                    int maxRows=1;for(int f=1;f<frames;++f)maxRows=std::max(maxRows,crows[f]);
                    glUniform1i(cellFrame,0);
                    dispatchRows(cellsProgram,w2,maxRows,frames-1,chunkOf(256),2);
                }
                check("cells");
                if(la&&in.laStream&&laUploaded<in.laNy){
                    // P33 W2.1: the field rows this strip reads (after the cells pass, which does not read them: the GPU runs it meanwhile): laFlowAt / laMotionAt at the cells [ry0, ry1) (tile rows i0, i0 + 1 of the
                    // bilinear sample, the nearest of the Z channel), one row more for rounding; earlier rows are already uploaded
                    const float pmax=std::clamp((float(2*(ry1-1))+0.5f-in.laOy)/std::max(in.laStride,1e-3f),0.f,float(in.laNy-1));
                    const int need=std::min(in.laNy,int(pmax)+3);
                    if(need>laUploaded){
                        in.laStream->waitRows(need);
                        const size_t nx=size_t(in.laNx),r0=size_t(laUploaded),nr=size_t(need-laUploaded);
                        for(int f=0;f<frames;++f){
                            if(const float* fld=in.laStream->field(f))put(15,(size_t(f)*laTiles+r0*nx)*8,fld+r0*nx*2,nr*nx*8);
                            if(const float* z=in.laStream->motion(f))put(17,(size_t(f)*laTiles+r0*nx)*4,z+r0*nx,nr*nx*4);
                        }
                        laUploaded=need;
                        check("local alignment rows");
                    }
                }
                glUseProgram(rejectProgram);
                glUniform1i(loc(rejectProgram,"ry0"),ry0);glUniform1i(loc(rejectProgram,"ry1"),ry1);
                dispatchRows(rejectProgram,w2,ry1-ry0,frames-1,chunkOf(256),3);
                glUseProgram(dilateProgram);
                glUniform1i(loc(dilateProgram,"ry0"),ry0);glUniform1i(loc(dilateProgram,"ry1"),ry1);
                glUniform1i(loc(dilateProgram,"cy0"),cy0);glUniform1i(loc(dilateProgram,"cy1"),cy1);
                dispatchRows(dilateProgram,w2,cy1-cy0,frames-1,chunkOf(256),4);
                check("rejection");
            }
            const int rows2=(y1-y0)*g,oy0=y0*g; // output-grid rows of this strip
            reserveBank(3,size_t(rows2)*ow*3*4,bank);reserveBank(4,size_t(rows2)*ow*4,bank);
            reserveBank(6,(clipFlags||rim||bentoColourPass||usBlend)?size_t(rows2)*ow*4:16,bank);
            if(nat){ // P29: the native merge, one invocation per output pixel of the strip, into the same Out / Eff / CFlags layout
                const GLuint mosaicProgram=natFastOn?(natStripMode&&natMarked?natMarked:natPlain):this->mosaicProgram;
                if(natFastOn&&natStripMode&&!natMarked)throw std::runtime_error("HYBRID GPU: native strip with flags but no marked merge program");
                glUseProgram(mosaicProgram);
                glUniform1i(loc(mosaicProgram,"cy0"),cy0);glUniform1i(loc(mosaicProgram,"cy1"),cy1);glUniform1i(loc(mosaicProgram,"ry0"),ry0);
                glUniform1i(loc(mosaicProgram,"natRy1"),ry1);
                glUniform1i(loc(mosaicProgram,"natMarkU"),natStripMode?1:0);
                // One submission per dispatch, at most 16 rows of a 4096-px row with 16 frames (the rows scale by 4096 x 16 / (ow F)).
                // X7 Ultra, full window: syn_b2 (16 frames) merged 24 strips of 128 rows in 34.4 s, ~1.4 s per strip submitted at
                // once; hand (4096 px, 21 frames, real noise) lost the GPU context in its first strips ("readback failed slot=3", the
                // GPU hang detection resetting the context, as for a long single dispatch). Each flush costs ~1 ms.
                // P34: the fast merge, ~10x cheaper per output pixel: 32 rows of 4096 px with 22 frames per submission
                // Tetra: the rows scaled by the 16 cells of the Quad 7x7 window against its (r+1)^2 cells
                const double natCells=nat->block==4?double((std::clamp(nat->window,1,8)+1)*(std::clamp(nat->window,1,8)+1))/16.0:1.0;
                const int natChunk=natFastOn?std::clamp(int(32.0*4096.0*22.0/(double(std::max(ow,1))*std::max(frames,1)*std::max(natCells,1.0)))&~7,8,128)
                                            :std::clamp(int(16.0*4096.0*16.0/(double(std::max(ow,1))*std::max(frames,1)))&~7,8,16);
                dispatchRows(mosaicProgram,ow,rows2,1,natChunk,5,true,true);
                if(natFastOn&&natStripMode&&natMarkedFill){ // P204: the base fill of the colours the marked merge left without a sample
                    glUseProgram(natMarkedFill);
                    glUniform1i(loc(natMarkedFill,"cy0"),cy0);glUniform1i(loc(natMarkedFill,"cy1"),cy1);glUniform1i(loc(natMarkedFill,"ry0"),ry0);
                    glUniform1i(loc(natMarkedFill,"natRy1"),ry1);
                    glUniform1i(loc(natMarkedFill,"natMarkU"),1);
                    dispatchRows(natMarkedFill,ow,rows2,1,std::clamp(natChunk*4,8,128),5,true,true);
                }
                if(natFastOn&&natDem){ // P282: after the merge and its base fill - the base's demosaic where the donors were rejected
                    glUseProgram(natDem);
                    glUniform1i(loc(natDem,"cy0"),cy0);glUniform1i(loc(natDem,"cy1"),cy1);glUniform1i(loc(natDem,"ry0"),ry0);
                    glUniform1i(loc(natDem,"natRy1"),ry1);
                    glUniform1i(loc(natDem,"natMarkU"),natStripMode?1:0);
                    dispatchRows(natDem,ow,rows2,1,std::clamp(natChunk*4,8,128),5,true,true);
                }
                if(natUs){ // P313: last - the ultrashort frames by the merge's clipped share (Bento's weight)
                    const GLuint up=natStripMode&&natUsMarked?natUsMarked:natUsPlain;
                    if(natStripMode&&!natUsMarked)throw std::runtime_error("HYBRID GPU: native strip with flags but no marked ultrashort pass");
                    glUseProgram(up);
                    glUniform1i(loc(up,"cy0"),cy0);glUniform1i(loc(up,"cy1"),cy1);glUniform1i(loc(up,"ry0"),ry0);
                    glUniform1i(loc(up,"natRy1"),ry1);
                    glUniform1i(loc(up,"natMarkU"),natStripMode?1:0);
                    dispatchRows(up,ow,rows2,1,std::clamp(natChunk*4,8,128),5,true,true);
                }
            } else {
            glUseProgram(mergeProgram);
            glUniform1i(loc(mergeProgram,"cy0"),cy0);glUniform1i(loc(mergeProgram,"cy1"),cy1);glUniform1i(loc(mergeProgram,"ry0"),ry0);
            for(int sub=0;sub<g*g;++sub){ // grid 2 / 4: one dispatch per sub-position (same registers as 1x; g^2 passes)
                glUniform4i(loc(mergeProgram,"kG"),g,ow,sub%g,sub/g);
                dispatchRows(mergeProgram,w2,cy1-cy0,1,chunkOf(64),5);
            }
            } // P29: end of the plain merge dispatch (else branch of the native one)
            if(chromaPass){ // after all sub-positions: the base's colour where the merge widened its kernel
                glUseProgram(chromaProgram);
                glUniform1i(loc(chromaProgram,"cy0"),cy0);glUniform1i(loc(chromaProgram,"cy1"),cy1);glUniform1i(loc(chromaProgram,"ry0"),ry0);
                dispatchRows(chromaProgram,ow,rows2,1,chunkOf(64),5);
            }
            if(bentoColourPass){ // after all sub-positions, before the clip-border pass (that one reads the merged colour)
                glUseProgram(bentoProgram);
                glUniform1i(loc(bentoProgram,"cy0"),cy0);glUniform1i(loc(bentoProgram,"cy1"),cy1);glUniform1i(loc(bentoProgram,"ry0"),ry0);
                dispatchRows(bentoProgram,ow,rows2,1,chunkOf(64),5);
            }
            if(rim){ // after all sub-positions: the pass reads the merged colour and the border statistics
                glUseProgram(rimProgram);
                glUniform1i(loc(rimProgram,"cy0"),cy0);glUniform1i(loc(rimProgram,"cy1"),cy1);glUniform1i(loc(rimProgram,"ry0"),ry0);
                dispatchRows(rimProgram,ow,rows2,1,chunkOf(64),5);
            }
            if(usBlend){ // P310: last, on the finished N merge of the strip (its clip-border colour included): the N clip marks, then the blend
                glUseProgram(blendProgram);
                glUniform1i(loc(blendProgram,"cy0"),cy0);glUniform1i(loc(blendProgram,"cy1"),cy1);glUniform1i(loc(blendProgram,"ry0"),ry0);
                // the blend's scratch (the guide is used up) and, behind the longest strip's rows, two carry rows of the gain
                const int carryAt=2*stripCells*g*ow;
                reserve(10,std::max(size_t(ry1-ry0)*w2*16,(size_t(carryAt)+size_t(2)*ow)*16));
                glUniform4i(loc(blendProgram,"blendCarryU"),carryAt,stripIndex&1,stripIndex>0?1:0,0);
                for(int stage=0;stage<4;++stage){
                    if(stage)glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                    glUniform1i(loc(blendProgram,"blendStageU"),stage);
                    dispatchRows(blendProgram,ow,rows2,1,chunkOf(64),5);
                }
            }
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
            check("merge");
            GLsync fence=glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE,0);
            glFlush();
            {const int doneRows=pending.active?pending.oy0+pending.rows2:0;
             readStrip(pending);                     // the previous strip, while the GPU runs this one
             if(doneRows>0)scam_progress::at(doneRows);
             if(doneRows>0&&in.rowsDone)in.rowsDone(out,doneRows);}
            pending.active=true;pending.bank=bank;pending.oy0=oy0;pending.rows2=rows2;pending.index=stripIndex;pending.fence=fence;
        }
        {const int doneRows=pending.active?pending.oy0+pending.rows2:0;
         readStrip(pending);
         if(doneRows>0)scam_progress::at(doneRows);
         if(doneRows>0&&in.rowsDone)in.rowsDone(out,doneRows);}
        if(stripsShrunk&&trace)trace("HYBRID GPU: "+std::to_string(stripsShrunk)+" of "+std::to_string(stripIndex)+" strips shrunk to fit the storage block / memory budget (smallest "
            +std::to_string(smallestStrip)+" cells; first: "+shrinkWhy+")");
        std::vector<GLuint> sums(zeros.size());
        get(9,sums.data(),sums.size()*4);
        if(sumsCarry)for(int f=1;f<frames;++f)robustShare[f]=(double(sums[size_t(frames)+3+f])*4294967296.0+double(sums[f]))/(255.0*double(w2)*h2);
        else for(int f=1;f<frames;++f)robustShare[f]=double(sums[f])/(255.0*double(w2)*h2);
        fixedOutliers=long(sums[size_t(frames)]);baseOutliers=long(sums[size_t(frames)+1]);rimPixels=long(sums[size_t(frames)+2]);
        if(nat){natFixedOutliers=long(sums[natSum0]);natBaseOutliers=long(sums[natSum0+1]);}
        fixedCapped=natFixedCapped=0;capStrips=natCapStrips=0;stripCount=stripIndex;capPerMP=capOn?tune.hotCap:0.f; // P214k
        if(capOn){
            fixedCapped=long(sums[capSum0+258]);capStrips=int(sums[capSum0+257]);
            if(nat){natFixedCapped=long(sums[capSum0+kHybridCapWords+258]);natCapStrips=int(sums[capSum0+kHybridCapWords+257]);}
        }
    }
};

// P35 (with P33): the GPU programs of a module's merge route, built when the module opens (worker --gpu-prewarm), so that the first
// shot loads them from the program cache (hybridProgramCacheDir) instead of compiling them. The same programs the shot's merge
// asks for, from the same tuning: the plain set (hybridReconstruct's early context, an ultrashort frame assumed: every app burst
// carries one), on a stored / declared Quad / Tetra block with mosaicPath 1 the native set with the colour passes off
// (hybridReconstructMosaicNativeMerge) and its merge program for the block's window. The split (mosaicPath 0) merges plain.
inline void hybridPrewarmGpu(const HybridTuning& tune,int block,const std::function<void(const std::string&)>& report){
    const auto t0=std::chrono::steady_clock::now();
    // P195: with «Наш ремозаик» off (mosaicRemosaic 0) a Quad / Tetra module merges its binned Bayer burst on the plain set
    const bool native=block>1&&tune.mosaicRemosaic!=0&&tune.mosaicPath==1&&(block==2||tune.mosaicTetra!=0);
    HybridTuning t=tune;
    if(native){t.chromaDiff=0.f;t.rimRatio=0;t.bentoChroma=0.f;}
    const bool rimPass=t.rimRatio!=0,la=t.localAlign>0,chromaPass=t.chromaDiff>0.f;
    const bool blendPass=t.bento>0&&t.bentoBlend>0&&!native;
    const bool bentoPass=t.bento>0&&!blendPass&&t.bentoChromaSigma>0.f&&t.bentoChroma>0.f;
    const bool outlierPrograms=t.hotSigma>0||t.hotBaseSigma>0;
    HybridGpu gpu(rimPass,la,bentoPass,chromaPass,nullptr,false,native,outlierPrograms,blendPass);
    if(native){
        const bool t2=block==4&&tune.mosaicTetra!=1;
        const int mb=t2?2:block;
        const int window=block==4&&!t2?std::clamp(2*tune.mosaicWindow,1,8):std::clamp(tune.mosaicWindow,1,6);
        const bool fill=tune.mosaicChromaFill==1&&std::clamp(tune.mosaicFillSupport,0.f,1.f)>0.f;
        gpu.prewarmNative(mb,window,fill,tune.mosaicWindowFull!=0,tune.mosaicGeneric!=0,tune.cellClip!=0||outlierPrograms,tune.baseDemosaic>0.f);
    }
    // P308: the GPU F6's programs as well (laGpu: «Где считать F6» = GPU), in their own context (the merge's made current again
    // by its own cleanup); a failure costs only their compile at the shot
    std::string laNote;
    if(tune.laGpu!=0&&tune.localAlign>0){
        try{LaGpu f6(hybridProgramCacheDir());laNote=", F6"+f6.cacheNote();}
        catch(const std::exception& e){laNote=std::string(", F6 failed: ")+e.what();}
    }
    report("HYBRID PREWARM: block "+std::to_string(block)+(native?" native mosaic":" plain")+" programs ready in "
        +std::to_string(int(std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count()))+" ms ("+gpu.renderer+";"+gpu.compileMs+laNote+")");
}

// ---------------------------------------------------------------------------------------------
// CPU side: sharpness (Shasta gate), Bento mask, per-frame weights, assembly.
// ---------------------------------------------------------------------------------------------

// Sharpness of a bracketed frame against the base on the 1/4 green guides (SCAM MeasureSharpnessRaw /
// DiscardBlurryBracketedFrames): squared green gradient per unit exposure^2 with the noise contribution removed,
// over the pixels that are unsaturated in BOTH frames. A long frame clips the highlights the base still resolves;
// scoring each frame over its own unsaturated pixels would drop every bracketed frame of a bright scene.
struct SharpnessPair { double base=0,frame=0; long pixels=0; };
// The full-resolution level of guides(b, f) alone (the same arithmetic, without the pyramid above it).
inline Guide guideLevel0(const Burst& b,int f){
    Guide g{b.w/4,b.h/4,{}};g.v.resize(size_t(g.w)*g.h);
    for(int y=0;y<g.h;++y)for(int x=0;x<g.w;++x){
        float sum=0;int count=0;
        for(int dy=0;dy<4;++dy)for(int dx=0;dx<4;++dx)
            if(b.color(4*x+dx,4*y+dy)==1){sum+=b.sample(f,4*x+dx,4*y+dy);++count;}
        g.v[size_t(y)*g.w+x]=sum/count;
    }
    return g;
}
inline SharpnessPair hybridSharpnessPair(const Guide& qb,const Burst& b,int f,float exposure,float baseSlope,float baseOffset,float slope,float offset,float sat);
inline SharpnessPair hybridSharpnessPair(const Guide& qb,const Guide& qf,float exposure,float baseSlope,float baseOffset,float slope,float offset,float sat);
inline SharpnessPair hybridSharpnessPair(const Burst& b,int f,float exposure,float baseSlope,float baseOffset,float slope,float offset,float sat) {
    return hybridSharpnessPair(guideLevel0(b,0),b,f,exposure,baseSlope,baseOffset,slope,offset,sat);
}
// P30: the base guide is computed once per burst by the caller (it was rebuilt, with its whole pyramid, for every frame).
inline SharpnessPair hybridSharpnessPair(const Guide& qb,const Burst& b,int f,float exposure,float baseSlope,float baseOffset,float slope,float offset,float sat) {
    return hybridSharpnessPair(qb,guideLevel0(b,f),exposure,baseSlope,baseOffset,slope,offset,sat);
}
// P31 (W1.4): both guides given (built during the alignment).
inline SharpnessPair hybridSharpnessPair(const Guide& qb,const Guide& qf,float exposure,float baseSlope,float baseOffset,float slope,float offset,float sat) {
    // sat: guide values are in the frame's own units (0..1 of white), a 4x4 mean (HybridTuning::shastaSat)
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

// P68 motion of a forced Shasta frame (the Bento motion fallback for bracketed frames): 8x8 RAW blocks (4x4 canonical cells),
// the aligned frame gained down to base units against the base, per colour mean. A block moved when a colour differs by more than
// 4 sigma of the two means (noise model of both frames) plus 15 % of the base level (gain mismatch, sub-pixel edges of the block
// mean). Blocks with a site at or above clip in either frame (the brighter frame clips first) are not counted.
struct ShastaMotion { long blocks=0,moved=0; double share() const {return blocks>0?100.0*double(moved)/double(blocks):0.0;} };
inline ShastaMotion shastaMotion(const Burst& b,int slot,const BackwardHomography& h,float exposure,float baseSlope,float baseOffset,
                                 float slope,float offset,float clip){
    ShastaMotion res;
    const int w2=b.w/2,h2=b.h/2,bw=w2/4,bh=h2/4;
    const float e=std::max(exposure,1e-6f);
    std::atomic<long> blocks{0},moved{0};
    mergeRowBands(bh,[&](int y0,int y1){
        long lb=0,lm=0;
        for(int by=y0;by<y1;++by)for(int bx=0;bx<bw;++bx){
            float sb[3]={0,0,0},sf[3]={0,0,0};bool clipped=false;
            for(int cy=4*by;cy<4*by+4&&!clipped;++cy)for(int cx=4*bx;cx<4*bx+4;++cx){
                const DonorPoint o=h.bayerOrigin(2*cx,2*cy);
                const int ux=std::clamp(int(std::lround(o.x*0.5f)),0,w2-1),uy=std::clamp(int(std::lround(o.y*0.5f)),0,h2-1);
                for(int p=0;p<4;++p){
                    const float vb=b.sample(0,2*cx+(p&1),2*cy+(p>>1)),vf=b.sample(slot,2*ux+(p&1),2*uy+(p>>1));
                    if(vb>=clip||vf>=clip){clipped=true;break;}
                    const int k=p==0?0:p==3?2:1; // canonical RGGB
                    sb[k]+=vb;sf[k]+=vf;
                }
                if(clipped)break;
            }
            if(clipped)continue;
            ++lb;
            static const float count[3]={16.f,32.f,16.f};
            for(int k=0;k<3;++k){
                const float mb=sb[k]/count[k],mf=sf[k]/count[k];
                const float var=(baseSlope*mb+baseOffset+(slope*mf+offset)/(e*e))/count[k];
                if(std::abs(mf/e-mb)>4.f*std::sqrt(std::max(var,0.f))+0.15f*mb){++lm;break;}
            }
        }
        blocks+=lb;moved+=lm;
    });
    res.blocks=blocks;res.moved=moved;
    return res;
}

struct BentoResult { bool active=false;std::string reason;double clippedFraction=0,usClippedRatio=0;int largestHole=0,inpaintHole=0;long invalidCells=0,maskCells=0;std::vector<float> mask;
    std::vector<float> smooth,valid; /* the mask before the SCAM check, and the per-cell (1 - error) factor of the checked frame */ };

// P31 (W1.4): the part of bentoMask that reads the base frame alone (clip scan, dilation, smoothing, near-clip factor). It is the
// same for every call of a burst (each ultrashort frame, the validation masks with bento 2): built once, during the alignment.
// mask: the mask before the ultrashort frame's check, built only when a call can use it (clippedFraction above bentoMinClipped, or
// bento 2); full says it was.
struct BentoBase { double clippedFraction=0; bool full=false; std::vector<float> mask;
    std::vector<float> prev; /* P309: with bentoRamp the previous mask: the ramp never gives less than it, the counts of
                                bentoMask stay on its cells */ };
inline BentoBase bentoBase(const Burst& b,const HybridTuning& t) {
    BentoBase res;
    const int w2=b.w/2,h2=b.h/2;
    std::vector<uint8_t> clip(size_t(w2)*h2,0),near(size_t(w2)*h2,0);
    std::atomic<long> clipped{0};
    const float nearLevel=std::min(t.bentoNearClip,t.bentoHighlight);
    const bool ramp=t.bentoRamp>0.f;
    std::vector<float> level(ramp?size_t(w2)*h2:0); // P309: each cell's brightest site
    mergeRowBands(h2,[&](int y0,int y1){
        long lc=0;
        for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
            bool c=false,nr=false;float top=0.f;
            for(int p=0;p<4;++p){const float v=b.sample(0,2*cx+(p&1),2*cy+(p>>1));if(v>=t.bentoHighlight)c=true;if(v>=nearLevel)nr=true;top=std::max(top,v);}
            if(c){clip[size_t(cy)*w2+cx]=1;++lc;}
            if(nr)near[size_t(cy)*w2+cx]=1;
            if(ramp)level[size_t(cy)*w2+cx]=top;
        }
        clipped+=lc;
    });
    res.clippedFraction=double(clipped)/(double(w2)*h2);
    if(res.clippedFraction<=t.bentoMinClipped&&t.bento!=2)return res;
    // dilate: diamond |dx|+|dy| <= r plus the outer ring of the (2r+1)^2 square. P31: stamped around each clipped cell instead of
    // searched around every cell (the same cells: the shape is symmetric); each band writes only its own rows.
    const int r=std::max(0,t.bentoDilate);
    std::vector<uint8_t> dil(size_t(w2)*h2,0);
    mergeRowBands(h2,[&](int y0,int y1){
        for(int yy=std::max(0,y0-r);yy<std::min(h2,y1+r);++yy){
            const uint8_t* row=clip.data()+size_t(yy)*w2;
            for(const uint8_t* p=row;(p=static_cast<const uint8_t*>(std::memchr(p,1,size_t(row+w2-p))))!=nullptr;++p){
                const int xx=int(p-row);
                for(int cy=std::max(y0,yy-r);cy<std::min(y1,yy+r+1);++cy){
                    const int ady=std::abs(cy-yy),k=ady==r?r:r-ady; // |dx| <= k (the diamond, or the whole ring row)
                    uint8_t* out=dil.data()+size_t(cy)*w2;
                    std::fill(out+std::max(0,xx-k),out+std::min(w2,xx+k+1),uint8_t(1));
                    if(ady<r){if(xx-r>=0)out[xx-r]=1;if(xx+r<w2)out[xx+r]=1;} // |dx| == r: the ring's sides
                }
            }
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
    if(ramp){
        // P309: the previous mask's cells: the counts of bentoMask (motion share, ultrashort clipping) stay on them
        res.prev=std::move(mask);
        std::vector<float>().swap(tmp);std::vector<uint8_t>().swap(dil);std::vector<uint8_t>().swap(near);
        // the share of the ultrashort frames. Separable gaussian of a cell plane (sigma cells, window 3 sigma, edges clamped).
        auto blur=[&](std::vector<float>& v,float sigma){
            const int r=std::max(1,int(std::ceil(3.f*sigma)));
            std::vector<float> k(size_t(2*r+1));float ks=0;
            for(int i=-r;i<=r;++i){k[size_t(i+r)]=std::exp(-0.5f*float(i*i)/(sigma*sigma));ks+=k[size_t(i+r)];}
            for(float& x:k)x/=ks;
            std::vector<float> tmp(v.size());
            mergeRowBands(h2,[&](int y0,int y1){
                for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
                    float s=0;for(int i=-r;i<=r;++i)s+=k[size_t(i+r)]*v[size_t(cy)*w2+std::clamp(cx+i,0,w2-1)];
                    tmp[size_t(cy)*w2+cx]=s;
                }
            });
            mergeRowBands(h2,[&](int y0,int y1){
                for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
                    float s=0;for(int i=-r;i<=r;++i)s+=k[size_t(i+r)]*tmp[size_t(std::clamp(cy+i,0,h2-1))*w2+cx];
                    v[size_t(cy)*w2+cx]=s;
                }
            });
        };
        const float lo=std::min(t.bentoRampLo,t.bentoRampHi-0.01f),hi=t.bentoRampHi;
        // the level a bright cell's kernel windows see: the brightest of its 3x3 cells (next to a clip the N frames' samples clip as
        // well and their mean would come out grey: the share must be full there); a cell darker than bentoRampLo keeps its own (the
        // dark side of a sharp edge, a device in front of a window, keeps the N frames' sharp edge)
        std::vector<float> local(level.size());
        mergeRowBands(h2,[&](int y0,int y1){
            for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
                float m=0.f;
                for(int dy=-1;dy<=1;++dy){const int yy=std::clamp(cy+dy,0,h2-1);
                    for(int dx=-1;dx<=1;++dx)m=std::max(m,level[size_t(yy)*w2+std::clamp(cx+dx,0,w2-1)]);}
                local[size_t(cy)*w2+cx]=level[size_t(cy)*w2+cx]>=lo?m:level[size_t(cy)*w2+cx];
            }
        });
        // only the bright area a clip belongs to: cells at or above bentoRampLo reached from a clipped cell through such cells
        // (8-connected); a separate bright object (an LED next to a lamp, flickering in the ultrashort frames) keeps the N frames.
        // Its border needs no fade: the share is 0 there (level bentoRampLo).
        std::vector<float> conn(level.size(),0.f);
        {
            std::vector<int> queue;queue.reserve(std::min(level.size(),size_t(clipped)*4+16));
            for(size_t i=0;i<clip.size();++i)if(clip[i]){conn[i]=1.f;queue.push_back(int(i));}
            for(size_t q=0;q<queue.size();++q){
                const int c=queue[q],cx=c%w2,cy=c/w2;
                for(int dy=-1;dy<=1;++dy){const int yy=cy+dy;if(yy<0||yy>=h2)continue;
                    for(int dx=-1;dx<=1;++dx){const int xx=cx+dx;if(xx<0||xx>=w2)continue;
                        const size_t j=size_t(yy)*w2+xx;
                        if(conn[j]==0.f&&local[j]>=lo){conn[j]=1.f;queue.push_back(int(j));}}}
            }
        }
        level.swap(local);std::vector<float>().swap(local);
        // the reach: clipped cells dilated by bentoRampReach (a square, by row and column counts), then blurred (sigma reach / 4)
        const int reach=std::max(1,t.bentoRampReach);
        std::vector<float> nearClip(size_t(w2)*h2,0.f);
        {
            std::vector<uint8_t> rows(size_t(w2)*h2,0);
            mergeRowBands(h2,[&](int y0,int y1){
                for(int cy=y0;cy<y1;++cy){
                    const uint8_t* c=clip.data()+size_t(cy)*w2;int last=-1000000;
                    std::vector<int> nextOn(size_t(w2)+1,1000000);
                    for(int cx=w2-1;cx>=0;--cx)nextOn[size_t(cx)]=c[cx]?cx:nextOn[size_t(cx)+1];
                    for(int cx=0;cx<w2;++cx){if(c[cx])last=cx;rows[size_t(cy)*w2+cx]=(cx-last<=reach||nextOn[size_t(cx)]-cx<=reach)?1:0;}
                }
            });
            mergeRowBands(w2>=64?w2:64,[&](int x0,int x1){
                for(int cx=x0;cx<std::min(x1,w2);++cx){
                    int last=-1000000;std::vector<int> nextOn(size_t(h2)+1,1000000);
                    for(int cy=h2-1;cy>=0;--cy)nextOn[size_t(cy)]=rows[size_t(cy)*w2+cx]?cy:nextOn[size_t(cy)+1];
                    for(int cy=0;cy<h2;++cy){if(rows[size_t(cy)*w2+cx])last=cy;nearClip[size_t(cy)*w2+cx]=(cy-last<=reach||nextOn[size_t(cy)]-cy<=reach)?1.f:0.f;}
                }
            });
        }
        blur(nearClip,std::max(1.f,float(reach)/4.f));
        std::vector<float> share(size_t(w2)*h2);
        mergeRowBands(h2,[&](int y0,int y1){
            for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
                const size_t i=size_t(cy)*w2+cx;
                if(clip[i]){share[i]=1.f;continue;}
                const float u=std::clamp((level[i]-lo)/(hi-lo),0.f,1.f);
                share[i]=std::clamp(u*u*(3.f-2.f*u)*nearClip[i]*conn[i],0.f,1.f);
            }
        });
        // the share spread by a gaussian of sigma bentoRamp cells, only over the clip's bright area (the max of the two: never
        // lower next to a clip, where the N frames' samples clip as well and their mean would come out grey); the darker side of
        // a sharp edge (a device in front of a window) keeps the N frames: one ultrashort frame is x8..16 noisier there
        {
            std::vector<float> spread=share;
            blur(spread,t.bentoRamp);
            std::vector<float>().swap(level);
            for(size_t i=0;i<share.size();++i)if(conn[i]>0.f)share[i]=std::max(share[i],std::min(spread[i],nearClip[i]));
        }
        res.mask=std::move(share);res.full=true;
        return res;
    }
    res.mask=std::move(mask);res.full=true;
    return res;
}

// Highlight mask of the base (per 2x2 cell): clipped -> dilate r -> gaussian smooth; checked against the
// aligned ultrashort frame (SCAM bento mask.cl + ShouldFallback). base (P31): bentoBase of this burst, built once for every call;
// null (or built without the mask this call needs): built here.
inline BentoResult bentoMask(const Burst& b,int usSlot,const BackwardHomography& usH,float usExposure,const HybridTuning& t,
                             const BentoBase* base=nullptr) {
    BentoResult res;
    const int w2=b.w/2,h2=b.h/2;
    BentoBase own;
    if(!base||(!base->full&&(base->clippedFraction>t.bentoMinClipped||t.bento==2))){own=bentoBase(b,t);base=&own;}
    res.clippedFraction=base->clippedFraction;
    if(res.clippedFraction<=t.bentoMinClipped&&t.bento!=2){res.reason="not enough clipping";return res;}
    std::vector<float> mask;
    if(base==&own)mask=std::move(own.mask); else mask=base->mask;
    std::vector<uint8_t> usClip(size_t(w2)*h2,0);
    std::atomic<long> usClippedInMask{0};
    // The aligned ultrashort frame inside the mask (SCAM Mask::RunWithUltraShortFrame, on the 2x2 cells): its own clipping and,
    // with bentoScam, the intensity error |min(GainUp(us) - base, 0)| over RGB: where the gained ultrashort frame is much DARKER
    // than the base (motion, misalignment) it is invalid, the mask loses that place ((1 - error) * mask), and where the base is
    // clipped as well (middle >= 0.98, smallest >= 0.502) nothing can fill it: an inpainting hole. A clipped ultrashort frame is
    // NOT an error (GainUp = 1 >= base): its cells stay in the mask (the best lower bound; the clip flags mark them).
    std::atomic<long> inMask{0},invalidCells{0};
    std::vector<uint8_t> inpaint(size_t(w2)*h2,0);
    res.smooth=mask;res.valid.assign(size_t(w2)*h2,1.f);
    const float ratio=1.f/std::max(usExposure,1e-6f);
    mergeRowBands(h2,[&](int y0,int y1){
        long lu=0,lm=0,li=0;
        for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
            const size_t i=size_t(cy)*w2+cx;
            // P309: with the ramp the previous mask's cells are checked too (the share takes the larger of the two) and the counts
            // (motion share, ultrashort clipping) stay on them
            const bool prev=t.bentoRamp>0.f&&base->prev.size()==mask.size()&&base->prev[i]>0.f;
            if(mask[i]<=0.f&&!prev)continue;
            const bool counted=t.bentoRamp<=0.f||base->prev.size()!=mask.size()||prev;
            if(counted)++lm;
            const DonorPoint o=usH.bayerOrigin(2*cx,2*cy);
            const int ux=std::clamp(int(std::lround(o.x*0.5f)),0,w2-1),uy=std::clamp(int(std::lround(o.y*0.5f)),0,h2-1);
            float us[4];bool c=false;
            for(int p=0;p<4;++p){us[p]=b.sample(usSlot,2*ux+(p&1),2*uy+(p>>1));if(us[p]>=t.bentoHighlight)c=true;}
            if(c){usClip[i]=1;if(counted)++lu;}
            if(t.bentoSubpixel){
                // each phase bilinear between the four cells around the aligned position (the clip test above stays the
                // nearest cell's: a clipped ultrashort sample is a lower bound, not an error)
                const float fx=std::clamp(o.x*0.5f,0.f,float(w2-1)),fy=std::clamp(o.y*0.5f,0.f,float(h2-1));
                const int x0=std::min(int(fx),w2-2),y0=std::min(int(fy),h2-2);
                const float ax=fx-float(x0),ay=fy-float(y0);
                for(int p=0;p<4;++p){
                    const int px=p&1,py=p>>1;
                    const float a=b.sample(usSlot,2*x0+px,2*y0+py),bb=b.sample(usSlot,2*x0+2+px,2*y0+py);
                    const float cc=b.sample(usSlot,2*x0+px,2*y0+2+py),d=b.sample(usSlot,2*x0+2+px,2*y0+2+py);
                    us[p]=(a+(bb-a)*ax)+((cc+(d-cc)*ax)-(a+(bb-a)*ax))*ay;
                }
            }
            if(!t.bentoScam)continue;
            // canonical RGGB: phase 0 red, 1 and 2 green, 3 blue
            const float base3[3]={b.sample(0,2*cx,2*cy),0.5f*(b.sample(0,2*cx+1,2*cy)+b.sample(0,2*cx,2*cy+1)),b.sample(0,2*cx+1,2*cy+1)};
            const float us3[3]={std::min(us[0]*ratio,1.f),std::min(0.5f*(us[1]+us[2])*ratio,1.f),std::min(us[3]*ratio,1.f)};
            float e2=0;
            for(int k=0;k<3;++k){const float d=std::min(us3[k]-base3[k],0.f);e2+=d*d;}
            float error=std::sqrt(e2);
            if(t.bentoValidate>=2&&std::max({base3[0],base3[1],base3[2]})<t.bentoHighlight){
                // bentoValidate 2: an UNCLIPPED base cell that the gained ultrashort frame shows much BRIGHTER is motion too
                // (the hand moved away and the lamp shows through): the base holds valid data there, the replacement must not run
                float p2=0;for(int k=0;k<3;++k){const float d=std::max(us3[k]-base3[k],0.f);p2+=d*d;}
                error=std::max(error,std::sqrt(p2));
            }
            if(error<t.bentoInvalid)continue;
            if(counted)++li;
            res.valid[i]=std::clamp(1.f-error,0.f,1.f);
            mask[i]=std::clamp((1.f-error)*mask[i],0.f,1.f);
            const float lo=std::min({base3[0],base3[1],base3[2]}),hi=std::max({base3[0],base3[1],base3[2]});
            const float middle=base3[0]+base3[1]+base3[2]-lo-hi;
            if(lo>=t.bentoInpaintMin&&middle>=t.bentoInpaintMiddle)inpaint[i]=1;
        }
        usClippedInMask+=lu;inMask+=lm;invalidCells+=li;
    });
    res.usClippedRatio=inMask>0?double(usClippedInMask)/double(inMask):0.0;
    res.invalidCells=invalidCells;res.maskCells=inMask;
    // largest connected component (SCAM HasLargeHoleNeedingInpainting: cv::connectedComponentsWithStats, 8-connected)
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
    res.largestHole=largestComponent(usClip,false); // round-4 criterion (us-clipped cells); with bentoScam only a metric
    res.inpaintHole=t.bentoScam?largestComponent(inpaint,true):0;
    // SCAM ShouldFallback order: clipped pixels -> inpainting hole -> clipping ratio on the ultrashort frame. Mode 2 (always) runs
    // the checks and reports them like SCAM's force_apply, without falling back.
    std::string would;
    if(res.clippedFraction<=t.bentoMinClipped)would="not enough clipping";
    else if((t.bentoScam?res.inpaintHole:res.largestHole)>=t.bentoMaxHole)would="large inpainting hole";
    else if(res.usClippedRatio>t.bentoMaxUsClipped)would="high clipping ratio on ultrashort";
    if(!would.empty()&&t.bento!=2){res.reason=would;return res;}
    res.active=true;res.reason=would.empty()?"success":"forced; would fall back: "+would;res.mask=std::move(mask);
    return res;
}

// ---------------------------------------------------------------------------------------------
// F6: tile-local refinement of the per-frame homography (research/hybrid5/f6_local_align.md).
// One homography per frame (CRE, 8-bit 1/4 guide) leaves 0.3-0.9 px RMS of local misregistration on handheld bursts (parallax of
// near objects, rolling shutter, lens): narrow Sabre kernels then merge frames that disagree. Per tile, the residual offset of
// every donor against the base is estimated on a two-level gray pyramid (v11 spatial path: Lucas-Kanade, 16x16 RAW output
// tiles from 32x32 RAW windows, median filtering; 6.1: 32x32 RAW tiles) and added to the homography in every GPU pass.
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
    // P48: a donor gray image held as separate rows (LaStream's windowed gray under memory pressure): row y at rows[y], v empty.
    // Only laTilePass reads a donor through row(); every row it reads is held while it runs (LaStream::ensureRows).
    const float* const* rows=nullptr;
    const float* row(int y) const {return rows?rows[y]:v.data()+size_t(y)*w;}
};
// Rows of a level image, in parallel (the base, once) or not (a frame inside the per-frame worker pool).
template<class F> inline void laRows(bool parallel,int h,const F& body){if(parallel)mergeRowBands(h,body); else body(0,h);}
// One gray row j (ow quads) of laGray into o; inv[p] = 1 / (white - black[p]). P48: also LaStream's windowed rows (the same floats).
inline void laGrayRow(const Burst& b,const uint16_t* raw,float gain,float eps,const float* inv,int j,int ow,float* o){
    const int sx=b.cfa&1,sy=b.cfa>>1;
    const int Y=2*j+sy;
    const bool rowsIn=Y+1<b.h;
    const uint16_t* r0=raw+size_t(std::min(Y,b.h-1))*b.w;const uint16_t* r1=rowsIn?r0+b.w:r0;
    const int p0=(Y&1)<<1,p1=((Y+1)&1)<<1;
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
        for(;i+4<=ow&&2*i+sx+7<b.w;i+=4){
            const int X=2*i+sx;
            const uint16x4x2_t a=vld2_u16(r0+X),c=vld2_u16(r1+X);
            const float32x4_t u0=site(a.val[0],b00,i00),u1=site(a.val[1],b01,i01),u2=site(c.val[0],b10,i10),u3=site(c.val[1],b11,i11);
            const float32x4_t sum=vaddq_f32(vaddq_f32(vaddq_f32(u0,u1),u2),u3),mx=vmaxq_f32(vmaxq_f32(u0,u1),vmaxq_f32(u2,u3));
            const float32x4_t val=vsqrtq_f32(vaddq_f32(vmaxq_f32(vmulq_f32(vmulq_f32(quarter,sum),gv),zero),ev));
            vst1q_f32(o+i,vbslq_f32(vcgeq_f32(mx,clipLv),none,val));
        }
    }
#endif
    for(;i<ow;++i){
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
inline void laGrayInv(const Burst& b,float inv[4]){for(int p=0;p<4;++p)inv[p]=1.f/(b.white-b.black[p]);}
// Gray of a frame: canonical quad (i, j) = sensor sites (2i + cfa&1 .. +1, 2j + cfa>>1 .. +1), normalised by black / white of each
// phase and clamped to [0, 1] like Burst::sampleRaw (which handles the reflected border quads); -1 where a site >= 0.95 of white.
inline void laGray(const Burst& b,const uint16_t* raw,float gain,float eps,LaImage& out,bool parallel){
    out.w=b.w/2;out.h=b.h/2;out.v.resize(size_t(out.w)*out.h);
    float inv[4];laGrayInv(b,inv);
    laRows(parallel,out.h,[&](int y0,int y1){
        for(int j=y0;j<y1;++j)laGrayRow(b,raw,gain,eps,inv,j,out.w,out.v.data()+size_t(j)*out.w);
    });
}
// One row of laDown: o[i] from the rows a (2j) and c (2j + 1) of the finer level.
inline void laDownRow(const float* a,const float* c,int ow,float* o){
    for(int i=0;i<ow;++i){
        const float p=a[2*i],q=a[2*i+1],r=c[2*i],s=c[2*i+1];
        o[i]=std::min(std::min(p,q),std::min(r,s))<0.f?-1.f:0.25f*(p+q+r+s);
    }
}
inline void laDown(const LaImage& in,LaImage& out,bool parallel){
    out.w=in.w/2;out.h=in.h/2;out.v.resize(size_t(out.w)*out.h);
    laRows(parallel,out.h,[&](int y0,int y1){
        for(int j=y0;j<y1;++j)laDownRow(in.v.data()+size_t(2*j)*in.w,in.v.data()+size_t(2*j+1)*in.w,out.w,out.v.data()+size_t(j)*out.w);
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
            const size_t kb=size_t(oy+wy)*B.w+ox;
            const float *br=B.v.data()+kb,*d0=D.row(oy+wy+iy)+(ox+ix),*d1=D.row(oy+wy+iy+1)+(ox+ix),*g1r=B.gx.data()+kb,*g2r=B.gy.data()+kb;
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
        const float *a=D.row(y0),*e=D.row(y1); // P48: rows of a windowed donor as well
        for(int wx=0;wx<win;++wx){
            const int x0=std::clamp(ox+wx+ix,0,D.w-1),x1=std::clamp(ox+wx+ix+1,0,D.w-1);
            const float bv=B.v[kb+wx],a00=a[x0],a10=a[x1],a01=e[x0],a11=e[x1];
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
// W2.1: the tile rows [j0, j1) only (every tile is independent: the same floats as the whole level).
inline void laLKRows(const LaImage& B,const LaImage& D,const LaGrid& g,const BackwardHomography& H,float v,float mu,float kappa,int iters,
                     std::vector<float>& r,int j0,int j1){
    const float minN=0.5f*float(g.win*g.win);
    for(int j=j0;j<j1;++j)for(int i=0;i<g.nx;++i){
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
inline void laLK(const LaImage& B,const LaImage& D,const LaGrid& g,const BackwardHomography& H,float v,float mu,float kappa,int iters,
                 std::vector<float>& r){laLKRows(B,D,g,H,v,mu,kappa,iters,r,0,g.ny);}
// 3x3 median of each component (borders replicated), 19-comparator network.
// W2.1: rows [j0, j1) of the median of src into dst (src rows j0-1 .. j1 must be final).
inline void laMedian3Rows(const std::vector<float>& src,std::vector<float>& r,int nx,int ny,int j0,int j1){
    auto cx=[](float& a,float& b){const float lo=std::min(a,b);b=std::max(a,b);a=lo;};
    for(int j=j0;j<j1;++j)for(int i=0;i<nx;++i)for(int c=0;c<2;++c){
        float p[9];int k=0;
        for(int dj=-1;dj<=1;++dj)for(int di=-1;di<=1;++di)
            p[k++]=src[(size_t(std::clamp(j+dj,0,ny-1))*nx+std::clamp(i+di,0,nx-1))*2+c];
        cx(p[1],p[2]);cx(p[4],p[5]);cx(p[7],p[8]);cx(p[0],p[1]);cx(p[3],p[4]);cx(p[6],p[7]);cx(p[1],p[2]);cx(p[4],p[5]);cx(p[7],p[8]);
        cx(p[0],p[3]);cx(p[5],p[8]);cx(p[4],p[7]);cx(p[3],p[6]);cx(p[1],p[4]);cx(p[2],p[5]);cx(p[4],p[7]);cx(p[4],p[2]);cx(p[6],p[4]);cx(p[4],p[2]);
        r[(size_t(j)*nx+i)*2+c]=p[4];
    }
}
inline void laMedian3(std::vector<float>& r,int nx,int ny){const std::vector<float> src=r;laMedian3Rows(src,r,nx,ny,0,ny);}
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
struct LaFrameStats { float median=0,p90=0,maxAbsY=0,accepted=0,fromCoarse=0,motionShare=0; double ms[7]{}; }; // ms: gray, down, L1, start, L0, accept, median
// Base pyramid (built once) and the per-frame field on the L0 grid. One worker thread per frame (no nested parallelism).
struct LaBase { LaImage l0,l1; LaGrid g0,g1; float v0=0; };
// v11 Z channel (rejection.cl motion prior): per tile the length of the min-max extent of the raw LK flow over its 3x3
// tile neighbourhood, taken before the acceptance test (a moving hand is exactly where LK is rejected and the kept field
// stays 0) and before any median. A tile whose LK diverged (non-finite or beyond maxShift) counts as maxShift.
// W2.1: rows [j0, j1) of z (sized nx * ny; raw rows j0-1 .. j1 must be final).
inline void laMotionExtentRows(const std::vector<float>& raw,int nx,int ny,float maxShift,std::vector<float>& z,int j0,int j1){
    auto at=[&](int i,int j,float& x,float& y){
        const size_t k=size_t(std::clamp(j,0,ny-1))*nx+std::clamp(i,0,nx-1);
        x=raw[k*2];y=raw[k*2+1];
        if(!std::isfinite(x)||!std::isfinite(y)||std::hypot(x,y)>maxShift){const float m=std::hypot(x,y);const float s=std::isfinite(m)&&m>0.f?maxShift/m:0.f;
            x=std::isfinite(x)?x*s:maxShift;y=std::isfinite(y)?y*s:0.f;}
    };
    for(int j=j0;j<j1;++j)for(int i=0;i<nx;++i){
        float x0=1e9f,x1=-1e9f,y0=1e9f,y1=-1e9f;
        for(int dj=-1;dj<=1;++dj)for(int di=-1;di<=1;++di){float x,y;at(i+di,j+dj,x,y);x0=std::min(x0,x);x1=std::max(x1,x);y0=std::min(y0,y);y1=std::max(y1,y);}
        z[size_t(j)*nx+i]=std::hypot(x1-x0,y1-y0);
    }
}
inline void laMotionExtent(const std::vector<float>& raw,int nx,int ny,float maxShift,std::vector<float>& z){
    z.assign(size_t(nx)*ny,0.f);laMotionExtentRows(raw,nx,ny,maxShift,z,0,ny);
}
inline void laFrameField(const LaBase& base,const LaImage& D0,const BackwardHomography& H,const HybridTuning& t,
                         std::vector<float>& field,LaFrameStats& st,std::vector<float>* motion=nullptr){
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
    if(motion){
        laMotionExtent(field,g0.nx,g0.ny,t.laMaxShift,*motion);
        long over=0;for(float v:*motion)over+=v>t.motionThreshold;
        st.motionShare=float(over)/float(std::max<size_t>(1,motion->size()));
    }
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

// P33 W2.1: laFrameField of every refined frame in tile-row bands, alongside the GPU merge (HybridLaStream). Phase 1 of a frame
// (gray, L1 field and its median) is queued as soon as the alignment publishes its homography (add), the bands once the merge
// knows the refined frames (commit): bands of every frame in band order, each the start, L0 LK and acceptance test of those tile
// rows (every tile alone, as in laFrameField), and, as the rows below them arrive, the Z channel and the medians (3x3: one row of
// halo each). Every value is the one laFrameField computes (the same functions on the same tiles); only the order of the tiles
// differs. A frame's gray image is freed when its field is complete (or when the merge drops the frame).
// P48 (research/speed/PLAIN_SHOT_SPEED.md): windowed gray. Every frame holding its whole gray image until its field is complete
// costs 4 B per quad and frame (469-497 MB at 12 MP): the stream was refused above MemAvailable / 4 (vivo 2x, Pixel 7) and F6 ran
// whole before the merge (1.5 s on the vivo, 3-16 s on the Pixel). Windowed, phase 1 builds L1 from two gray rows at a time and
// each band computes the gray rows its tile passes can read (laGrayRow, the same floats as laGray) just before it runs; the rows
// no later band of the frame can read go back to a per-frame spare list. The rows a band reads are bounded: the homography's
// translation of every tile of the band, plus the L1 field (|r| <= 4 RAW px per coarse LK step) and the L0 LK steps (<= 1 level
// px each), plus the bilinear row and rounding. The field is bit-identical to the full stream (tools/check_hybrid_prefetch.cpp).
class LaStream final : public HybridLaStream {
public:
    struct Job { int f=0;const uint16_t* raw=nullptr;float gain=1;BackwardHomography H; };
    struct Frame {
        Job job;LaImage D0;std::vector<float> r1,s0,lk,z;std::vector<std::vector<float>> stage;std::vector<uint8_t> ok,coarse,lkDone;
        std::vector<int> stageRows;int lkPrefix=0,zRows=0,ready=0;bool p1=false,done=false,dropped=false;LaFrameStats st;
        // P48 windowed gray: D0.rows = rowPtr; rows [bandLo[k], bandHi[k]] read by band k, loFrom[k] = min bandLo of bands >= k
        std::mutex rowLock;std::vector<const float*> rowPtr;std::vector<std::unique_ptr<float[]>> rowMem,spare;
        std::vector<int> bandLo,bandHi,loFrom;std::vector<uint8_t> bandDone;int bandPrefix=0,freedRows=0,buffers=0;
    };
    static constexpr int kBandRows=4; // tile rows per task (191 rows at 12 MP: 48 bands)
    // P48: gray rows one frame holds at most in the windowed mode, for the memory estimate before the stream starts (the bound of
    // one band's rows, twice for the band in flight and the next, plus 64 rows of rotation / perspective across the frame)
    static int windowRowsEstimate(const HybridTuning& t,int win,int stride){
        return 2*(kBandRows*stride+win+2*(2*std::max(0,t.laItersCoarse)+std::max(0,t.laIters)+3))+64;
    }
    LaStream(const Burst& burst,LaBase&& laBase,float epsilon,const HybridTuning& tune,int threads,bool windowedGray=false)
            :b(burst),base(std::move(laBase)),t(tune),eps(epsilon),started(std::chrono::steady_clock::now()),win(windowedGray){
        bands=(base.g0.ny+kBandRows-1)/kBandRows;
        laGrayInv(b,inv);
        for(int k=0;k<std::max(1,threads);++k){
            try{pool.emplace_back([this]{work();});}catch(const std::exception&){if(pool.empty())throw;break;} // fewer threads
        }
    }
    // the jobs at once (tests, and a merge whose frames are known)
    LaStream(const Burst& burst,LaBase&& laBase,float epsilon,const HybridTuning& tune,const std::vector<Job>& jobs,int threads,
             bool windowedGray=false)
            :LaStream(burst,std::move(laBase),epsilon,tune,threads,windowedGray){
        std::vector<int> fs;for(const auto& job:jobs){add(job);fs.push_back(job.f);}
        commit(fs);
    }
    ~LaStream() override {stop();for(auto& th:pool)if(th.joinable())th.join();}
    LaStream(const LaStream&)=delete;
    const LaGrid& grid0() const {return base.g0;}
    // phase 1 of frame job.f (its homography final); before commit
    void add(const Job& job){
        std::lock_guard<std::mutex> l(m);
        if(committed)return;
        all.push_back(std::make_unique<Frame>());all.back()->job=job;
        queue.emplace_back(int(all.size())-1,-1);cv.notify_one();
    }
    // the refined frames (each added before; ascending f as the merge lists them): their bands are queued, the others dropped
    void commit(const std::vector<int>& refined){
        std::lock_guard<std::mutex> l(m);
        if(committed)return;
        committed=true;
        for(size_t k=0;k<all.size();++k){
            const bool in=std::find(refined.begin(),refined.end(),all[k]->job.f)!=refined.end();
            if(!in){all[k]->dropped=true;if(all[k]->p1)release(*all[k]);}
        }
        for(int f:refined)for(size_t k=0;k<all.size();++k)if(all[k]->job.f==f&&!all[k]->dropped){frames.push_back(int(k));break;}
        // queued phase-1 tasks of dropped frames go; the bands follow in band order
        std::deque<std::pair<int,int>> kept;
        for(const auto& task:queue)if(!all[task.first]->dropped)kept.push_back(task);
        queue.swap(kept);
        for(int band=0;band<bands;++band)for(int k:frames)queue.emplace_back(k,band);
        commitMs=ms(started);
        {bool all1=true;for(int k:frames)all1=all1&&all[k]->p1;if(all1)phase1Ms=commitMs;}
        if(frames.empty())doneMs=commitMs;
        cv.notify_all();
    }
    int frameCount() const {return int(frames.size());}
    void stop(){std::lock_guard<std::mutex> l(m);stopping=true;cv.notify_all();}
    // merge order: frameOfMerge[i] = burst frame of merge slot i; slots without a refined frame read zeros
    void setMergeOrder(const std::vector<int>& frameOfMerge){
        slots.assign(frameOfMerge.size(),-1);
        for(size_t i=0;i<frameOfMerge.size();++i)for(int k:frames)if(all[k]->job.f==frameOfMerge[i])slots[i]=k;
    }
    void waitRows(int rows) override {
        const auto t0=std::chrono::steady_clock::now();
        rows=std::min(rows,base.g0.ny);
        std::unique_lock<std::mutex> l(m);
        bool blocked=false;
        cv.wait(l,[&]{
            if(failed||stopping)return true;
            if(!committed){blocked=true;return false;}
            for(int k:frames)if(all[k]->ready<rows){blocked=true;return false;}
            return true;
        });
        if(failed)throw HybridLaStreamFailed(failure);
        if(stopping)throw HybridLaStreamFailed("stopped");
        if(blocked){waitMs+=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();++waits;}
    }
    // every committed field complete (or failed); false on a failure (failureText())
    bool finish(){
        std::unique_lock<std::mutex> l(m);
        cv.wait(l,[&]{if(failed||stopping)return true;if(!committed)return false;for(int k:frames)if(!all[k]->done)return false;return true;});
        return !failed&&!stopping;
    }
    const std::string& failureText() const {return failure;}
    bool hasField(int i) const {return i>=0&&i<int(slots.size())&&slots[i]>=0;}
    // after waitRows (the frame's arrays exist)
    const float* field(int i) const override {return hasField(i)?all[slots[i]]->stage.back().data():nullptr;}
    const float* motion(int i) const override {return hasField(i)?all[slots[i]]->z.data():nullptr;}
    const Frame& frameAt(int k) const {return *all[frames[k]];}
    double doneMs=0;  // field of every frame complete, after the start
    double phase1Ms=0;// phase 1 of every refined frame done, after the start
    double commitMs=0;// commit, after the start
    bool windowed() const {return win;}
    // P48: the most gray-row bytes all frames held at once (windowed mode)
    double peakRowMB() const {return double(peakRowBytes.load())/1048576.0;}
private:
    using Clock=std::chrono::steady_clock;
    static double ms(Clock::time_point a){return std::chrono::duration<double,std::milli>(Clock::now()-a).count();}
    Burst b;LaBase base;HybridTuning t;float eps;Clock::time_point started;
    bool win=false;float inv[4]{};std::atomic<int64_t> rowBytes{0},peakRowBytes{0};
    std::vector<std::unique_ptr<Frame>> all;std::vector<int> frames,slots;int bands=0,framesDone=0;
    std::deque<std::pair<int,int>> queue; // (index into all, band; -1 = phase 1)
    std::vector<std::thread> pool;
    std::mutex m;std::condition_variable cv;bool committed=false,failed=false,stopping=false;std::string failure;
    void fail(const std::string& why){std::lock_guard<std::mutex> l(m);if(!failed){failed=true;failure=why;}cv.notify_all();}
    void release(Frame& fr){
        if(win){ // P48: every gray row of the frame and its spare buffers
            std::lock_guard<std::mutex> l(fr.rowLock);
            std::vector<std::unique_ptr<float[]>>().swap(fr.rowMem);std::vector<std::unique_ptr<float[]>>().swap(fr.spare);
            std::vector<const float*>().swap(fr.rowPtr);
            rowBytes-=int64_t(fr.buffers)*int64_t(b.w/2)*4;fr.buffers=0;
        }
        fr.D0=LaImage{};std::vector<float>().swap(fr.r1);std::vector<float>().swap(fr.s0);std::vector<float>().swap(fr.lk);
        for(size_t s=0;s+1<fr.stage.size();++s)std::vector<float>().swap(fr.stage[s]);
        if(fr.dropped){std::vector<std::vector<float>>().swap(fr.stage);std::vector<float>().swap(fr.z);}
    }
    void work(){
#if defined(__linux__)
        if(t.laScam>0&&setpriority(PRIO_PROCESS,id_t(syscall(SYS_gettid)),std::min(t.laScam,19))!=0){} // best effort
#endif
        try{
            for(;;){
                std::pair<int,int> task;
                Frame* target=nullptr;
                {
                    std::unique_lock<std::mutex> l(m);
                    cv.wait(l,[&]{return failed||stopping||!queue.empty()||committed;});
                    if(failed||stopping||queue.empty())return; // committed and nothing left
                    task=queue.front();queue.pop_front();
                    if(task.second>=0){ // a band: phase 1 of its frame was queued before it, so it runs or ran already
                        cv.wait(l,[&]{return all[task.first]->p1||failed||stopping;});
                        if(failed||stopping)return;
                    }
                    target=all[task.first].get();
                }
                if(task.second<0)phase1(*target); else runBand(*target,task.second);
            }
        }catch(const std::exception& error){fail(error.what());}
        catch(...){fail("unknown error");}
    }
    void phase1(Frame& fr){
        double part[3]{};auto tick=Clock::now();
        LaImage D1;
        if(win){ // P48: L1 from two gray rows at a time (laDown of laGray, row by row: the same floats); D0 rows come with the bands
            const int w0=b.w/2,h0=b.h/2;
            D1.w=w0/2;D1.h=h0/2;D1.v.resize(size_t(D1.w)*D1.h);
            std::vector<float> ra(static_cast<size_t>(w0)),rc(static_cast<size_t>(w0));
            for(int j=0;j<D1.h;++j){
                laGrayRow(b,fr.job.raw,fr.job.gain,eps,inv,2*j,w0,ra.data());
                laGrayRow(b,fr.job.raw,fr.job.gain,eps,inv,2*j+1,w0,rc.data());
                laDownRow(ra.data(),rc.data(),D1.w,D1.v.data()+size_t(j)*D1.w);
            }
            part[0]=ms(tick);
            windowBounds(fr,w0,h0);
            tick=Clock::now();
        } else {
            laGray(b,fr.job.raw,fr.job.gain,eps,fr.D0,false);part[0]=ms(tick);tick=Clock::now();
            laDown(fr.D0,D1,false);part[1]=ms(tick);tick=Clock::now();
        }
        const LaGrid& g0=base.g0;const LaGrid& g1=base.g1;
        fr.r1.assign(size_t(g1.nx)*g1.ny*2,0.f);
        laLK(base.l1,D1,g1,fr.job.H,base.v0*0.25f,t.laMu,t.laKappa,std::max(0,t.laItersCoarse),fr.r1);
        laMedian3(fr.r1,g1.nx,g1.ny);part[2]=ms(tick);
        const size_t T=size_t(g0.nx)*g0.ny;const int M=std::max(0,t.laMedian);
        fr.lk.assign(T*2,0.f);fr.s0.assign(T,0.f);fr.ok.assign(T,0);fr.coarse.assign(T,0);fr.lkDone.assign(g0.ny,0);fr.z.assign(T,0.f);
        fr.stage.assign(size_t(M)+1,std::vector<float>(T*2,0.f));fr.stageRows.assign(size_t(M)+1,0);
        std::lock_guard<std::mutex> l(m);
        for(int q=0;q<3;++q)fr.st.ms[q]+=part[q];
        fr.p1=true;
        if(fr.dropped)release(fr);
        if(committed){
            bool all1=true;for(int k:frames)all1=all1&&all[k]->p1;
            if(all1&&phase1Ms<=0)phase1Ms=ms(started);
        }
        cv.notify_all();
    }
    // P48: the donor rows of every band. laTilePass reads rows oy + floor(ty) .. oy + floor(ty) + win (clamped to the image), ty =
    // the tile's homography translation + the field's y in level px: the L1 field moves <= 4 RAW px (2 level px) per coarse LK
    // step, each L0 step <= 1 level px; 3 rows more for floor and rounding. A non-finite or out-of-image translation: every row.
    void windowBounds(Frame& fr,int w0,int h0){
        const LaGrid& g0=base.g0;
        const long reach=2L*std::max(0,t.laItersCoarse)+std::max(0,t.laIters)+3;
        fr.bandLo.assign(size_t(bands),0);fr.bandHi.assign(size_t(bands),h0-1);fr.loFrom.assign(size_t(bands)+1,h0);
        fr.bandDone.assign(size_t(bands),0);fr.bandPrefix=0;fr.freedRows=0;
        for(int band=0;band<bands;++band){
            const int j0=band*kBandRows,j1=std::min(g0.ny,j0+kBandRows);
            long lo=std::numeric_limits<long>::max(),hi=std::numeric_limits<long>::min();bool whole=false;
            for(int j=j0;j<j1&&!whole;++j)for(int i=0;i<g0.nx;++i){
                float tx0,ty0;laT0(g0,fr.job.H,i,j,tx0,ty0);
                if(!std::isfinite(ty0)||std::abs(ty0)>float(h0)){whole=true;break;}
                const long iy=long(std::floor(ty0)),oy=long(g0.stride)*j;
                lo=std::min(lo,oy+iy-reach);hi=std::max(hi,oy+iy+g0.win+reach);
            }
            if(whole||lo>hi){lo=0;hi=h0-1;}
            fr.bandLo[band]=int(std::clamp<long>(lo,0,h0-1));fr.bandHi[band]=int(std::clamp<long>(hi,0,h0-1));
        }
        for(int band=bands-1;band>=0;--band)fr.loFrom[band]=std::min(fr.loFrom[size_t(band)+1],fr.bandLo[band]);
        std::lock_guard<std::mutex> l(fr.rowLock);
        fr.rowPtr.assign(size_t(h0),nullptr);fr.rowMem.clear();fr.rowMem.resize(size_t(h0));
        fr.D0=LaImage{};fr.D0.w=w0;fr.D0.h=h0;fr.D0.rows=fr.rowPtr.data();
    }
    // P48: the gray rows band k reads, computed where missing (into a spare buffer of the frame first); the ms it took
    double ensureRows(Frame& fr,int band){
        const auto t0=Clock::now();
        std::lock_guard<std::mutex> l(fr.rowLock);
        const int w0=fr.D0.w;
        for(int y=fr.bandLo[band];y<=fr.bandHi[band];++y){
            if(fr.rowPtr[y])continue;
            std::unique_ptr<float[]> row;
            if(!fr.spare.empty()){row=std::move(fr.spare.back());fr.spare.pop_back();}
            else {
                row.reset(new float[size_t(w0)]);++fr.buffers;
                const int64_t now=rowBytes+=int64_t(w0)*4;
                int64_t peak=peakRowBytes.load();
                while(now>peak&&!peakRowBytes.compare_exchange_weak(peak,now)){}
            }
            laGrayRow(b,fr.job.raw,fr.job.gain,eps,inv,y,w0,row.get());
            fr.rowPtr[y]=row.get();fr.rowMem[y]=std::move(row);
        }
        return ms(t0);
    }
    // P48: band k done; the rows below every band of the frame still to finish go to its spare list
    void bandFinished(Frame& fr,int band){
        std::lock_guard<std::mutex> l(fr.rowLock);
        fr.bandDone[band]=1;
        while(fr.bandPrefix<bands&&fr.bandDone[fr.bandPrefix])++fr.bandPrefix;
        const int limit=fr.bandPrefix<bands?fr.loFrom[fr.bandPrefix]:fr.D0.h;
        for(;fr.freedRows<limit;++fr.freedRows){
            auto& row=fr.rowMem[fr.freedRows];
            if(row){fr.rowPtr[fr.freedRows]=nullptr;fr.spare.push_back(std::move(row));}
        }
    }
    void runBand(Frame& fr,int band){
        const LaGrid& g0=base.g0;const LaGrid& g1=base.g1;
        const int j0=band*kBandRows,j1=std::min(g0.ny,j0+kBandRows);
        const float minN=0.5f*float(g0.win*g0.win);
        const double grayMs=win?ensureRows(fr,band):0.0;
        double part[3]{};auto tick=Clock::now();
        std::vector<float>& field=fr.lk;
        // start: the better of the homography and the L1 field (window SSD); laFrameField's loop on these rows
        for(int j=j0;j<j1;++j)for(int i=0;i<g0.nx;++i){
            const size_t k=size_t(j)*g0.nx+i;
            float tx0,ty0;laT0(g0,fr.job.H,i,j,tx0,ty0);
            const LaTile a=laTilePass(base.l0,fr.D0,g0.stride*i,g0.stride*j,g0.win,tx0,ty0,false);
            fr.s0[k]=a.n>=minN?a.ssd:-1.f;
            float ux,uy;laFieldAt(fr.r1,g1,g0.raw(g0.centre(i)),g0.raw(g0.centre(j)),ux,uy);
            if(a.n>=minN&&(ux!=0.f||uy!=0.f)){
                const LaTile u=laTilePass(base.l0,fr.D0,g0.stride*i,g0.stride*j,g0.win,tx0+ux/float(g0.s),ty0+uy/float(g0.s),false);
                if(u.n>=minN&&u.ssd<a.ssd){field[k*2]=ux;field[k*2+1]=uy;fr.coarse[k]=1;}
            }
        }
        part[0]=ms(tick);tick=Clock::now();
        laLKRows(base.l0,fr.D0,g0,fr.job.H,base.v0,t.laMu,t.laKappa,std::max(0,t.laIters),field,j0,j1);
        part[1]=ms(tick);tick=Clock::now();
        // keep the refinement only where it lowers the SSD of the homography (into stage 0; lk stays as it is for the Z channel)
        std::vector<float>& acc=fr.stage[0];
        for(int j=j0;j<j1;++j)for(int i=0;i<g0.nx;++i){
            const size_t k=size_t(j)*g0.nx+i;
            float rr[2]={field[k*2],field[k*2+1]};
            bool keep=fr.s0[k]>=0.f&&std::isfinite(rr[0])&&std::isfinite(rr[1])&&std::hypot(rr[0],rr[1])<=t.laMaxShift;
            if(keep&&(rr[0]!=0.f||rr[1]!=0.f)){
                float tx0,ty0;laT0(g0,fr.job.H,i,j,tx0,ty0);
                const LaTile f=laTilePass(base.l0,fr.D0,g0.stride*i,g0.stride*j,g0.win,tx0+rr[0]/float(g0.s),ty0+rr[1]/float(g0.s),false);
                keep=f.n>=minN&&f.ssd<fr.s0[k];
            }
            if(!keep){rr[0]=0;rr[1]=0;} else fr.ok[k]=1;
            acc[k*2]=rr[0];acc[k*2+1]=rr[1];
        }
        part[2]=ms(tick);
        if(win)bandFinished(fr,band);
        std::lock_guard<std::mutex> l(m);
        fr.st.ms[0]+=grayMs;fr.st.ms[3]+=part[0];fr.st.ms[4]+=part[1];fr.st.ms[5]+=part[2];
        for(int j=j0;j<j1;++j)fr.lkDone[j]=1;
        advance(fr);
        cv.notify_all();
    }
    // under m: the rows the complete rows allow (Z row j: lk rows j-1..j+1; median s row j: stage s-1 rows j-1..j+1)
    void advance(Frame& fr){
        const int nx=base.g0.nx,ny=base.g0.ny,M=int(fr.stage.size())-1;
        while(fr.lkPrefix<ny&&fr.lkDone[fr.lkPrefix])++fr.lkPrefix;
        const int P=fr.lkPrefix;
        const int zT=P>=ny?ny:std::max(0,P-1);
        auto tick=Clock::now();
        if(zT>fr.zRows){laMotionExtentRows(fr.lk,nx,ny,t.laMaxShift,fr.z,fr.zRows,zT);fr.zRows=zT;}
        fr.stageRows[0]=P;int avail=P;
        for(int s=1;s<=M;++s){
            const int tgt=avail>=ny?ny:std::max(0,avail-1);
            if(tgt>fr.stageRows[s]){laMedian3Rows(fr.stage[s-1],fr.stage[s],nx,ny,fr.stageRows[s],tgt);fr.stageRows[s]=tgt;}
            avail=fr.stageRows[s];
        }
        fr.st.ms[6]+=ms(tick);
        fr.ready=std::min(avail,fr.zRows);
        if(fr.ready>=ny&&!fr.done)complete(fr);
    }
    // laFrameField's statistics of a complete field; the frame's working images are freed
    void complete(Frame& fr){
        const std::vector<float>& field=fr.stage.back();
        long over=0;for(float v:fr.z)over+=v>t.motionThreshold;
        fr.st.motionShare=float(over)/float(std::max<size_t>(1,fr.z.size()));
        std::vector<float> mag(fr.s0.size());float maxY=0;long acc=0,fromCoarse=0;
        for(size_t k=0;k<mag.size();++k){mag[k]=std::hypot(field[k*2],field[k*2+1]);maxY=std::max(maxY,std::abs(field[k*2+1]));acc+=fr.ok[k];fromCoarse+=fr.coarse[k];}
        const size_t mid=mag.size()/2,p90=std::min(mag.size()-1,mag.size()*9/10);
        std::nth_element(mag.begin(),mag.begin()+mid,mag.end());fr.st.median=mag[mid];
        std::nth_element(mag.begin(),mag.begin()+p90,mag.end());fr.st.p90=mag[p90];
        fr.st.maxAbsY=maxY;fr.st.accepted=float(acc)/float(std::max<size_t>(1,mag.size()));fr.st.fromCoarse=float(fromCoarse)/float(std::max<size_t>(1,mag.size()));
        release(fr);
        fr.done=true;
        if(++framesDone==int(frames.size()))doneMs=ms(started);
    }
};

// P22: the b² sub-frames of one mosaic frame are one exposure: the same motion. Their tile fields (each from a quarter of the
// sites) differ by estimation noise (0.15-0.2 sub-frame px rms on a handheld Quad burst of the X7 Ultra, 0.3-0.4 output px) and
// by a possible disparity of the site classes (Quad PD: the sites under one on-chip lens see different halves of the pupil).
// Per tile, field(k, s) = m(k) + d(s), m(0) = d(0) = 0 (the base sub-frame), fitted by alternating means over the observed fields
// (an exact zero is a rejected tile, not an observation); every observed field becomes its fit: the motion averaged over the
// frame's sites, the disparity over all frames. Rejected tiles stay at the homography.
inline void laShareSubFrames(std::vector<std::vector<float>>& fields,int per,std::vector<float>& maxY){
    const int n=int(fields.size());
    if(per<2||n<2*per||n%per)return;
    const int K=n/per;
    size_t T=0;for(const auto& f:fields)if(!f.empty()){T=f.size()/2;break;}
    if(!T)return;
    auto at=[&](int k,int s)->const std::vector<float>*{const auto& f=fields[size_t(k)*per+s];return f.size()==T*2?&f:nullptr;};
    std::vector<std::vector<float>> out(fields.size());
    for(int k=0;k<K;++k)for(int s=0;s<per;++s)if(at(k,s))out[size_t(k)*per+s].assign(T*2,0.f);
    mergeRowBands(int(T),[&](int t0,int t1){
        std::vector<double> m(size_t(K)*2),d(size_t(per)*2);std::vector<int> cm(K);
        for(int t=t0;t<t1;++t){
            auto obs=[&](int k,int s,float& x,float& y){const auto* f=at(k,s);if(!f)return false;x=(*f)[size_t(t)*2];y=(*f)[size_t(t)*2+1];return x!=0.f||y!=0.f;};
            std::fill(m.begin(),m.end(),0.0);std::fill(d.begin(),d.end(),0.0);
            for(int it=0;it<4;++it){
                for(int k=1;k<K;++k){
                    double sx=0,sy=0;int c=0;
                    for(int s=0;s<per;++s){float x,y;if(!obs(k,s,x,y))continue;sx+=x-d[size_t(s)*2];sy+=y-d[size_t(s)*2+1];++c;}
                    m[size_t(k)*2]=c?sx/c:0.0;m[size_t(k)*2+1]=c?sy/c:0.0;cm[k]=c;
                }
                for(int s=1;s<per;++s){ // the disparity from the base's own sub-frame and from frames with at least two sites
                    double sx=0,sy=0;int c=0;
                    for(int k=0;k<K;++k){float x,y;if(!obs(k,s,x,y)||(k>0&&cm[k]<2))continue;sx+=x-m[size_t(k)*2];sy+=y-m[size_t(k)*2+1];++c;}
                    d[size_t(s)*2]=c?sx/c:0.0;d[size_t(s)*2+1]=c?sy/c:0.0;
                }
            }
            for(int k=0;k<K;++k)for(int s=0;s<per;++s){
                if(k==0&&s==0)continue;float x,y;if(!obs(k,s,x,y))continue;
                auto& o=out[size_t(k)*per+s];o[size_t(t)*2]=float(m[size_t(k)*2]+d[size_t(s)*2]);o[size_t(t)*2+1]=float(m[size_t(k)*2+1]+d[size_t(s)*2+1]);
            }
        }
    });
    for(size_t i=0;i<fields.size();++i)if(!out[i].empty()){
        fields[i].swap(out[i]);float my=0;for(size_t t=0;t<T;++t)my=std::max(my,std::abs(fields[i][t*2+1]));maxY[i]=std::max(maxY[i],my);
    }
}

struct HybridStats { double alignMs=0,maskMs=0,mergeMs=0,localAlignMs=0; int merged=0,droppedBracketed=0,mergedNormals=0; bool bento=false; };
// P190: what became of a FULLRES series (tuning seriesFrames): the frames merged against those sent, the N frames apart (the app
// tells the user about N frames left out; an ultrashort or bracketed frame dropped by its quality rules is only logged)
inline void hybridSeriesMerged(const HybridTuning& tune,const HybridInput& input,int merged,int mergedNormals,
                               const std::function<void(const std::string&)>& report){
    if(tune.seriesFrames<=0)return;
    int normals=0;for(const auto& f:input.frames)normals+=f.role==kRoleNormal?1:0;
    report("HYBRID SERIES: merged "+std::to_string(merged)+" of "+std::to_string(input.frames.size())+" sent (N frames "
        +std::to_string(mergedNormals)+" of "+std::to_string(normals)+")");
}
// P190: a FULLRES series the mosaic pick did not keep whole
inline void hybridSeriesCut(const HybridTuning& tune,int kept,int n,const std::string& why,const std::function<void(const std::string&)>& report){
    if(tune.seriesFrames>0&&kept<n)report("HYBRID SERIES CUT: "+std::to_string(kept)+" of "+std::to_string(n)+" ("+why+")");
}

// P19 lateral CA of one frame (tools/quad/measure_raw_ca.py is the reference): G interpolated at the R and B sites from their
// four green neighbours; per 64 x 64-cell tile with gradient and nothing clipped, R (B) matched to G by gain / offset and the
// displacement solved by two Lucas-Kanade steps; the radial model d = (k1 + k2 r^2) p (p from the centre over the half diagonal,
// canonical cells) fitted by least squares, refitted without the worst 20 % of the tiles.
struct HybridCa { bool ok=false; float k1[2]{},k2[2]{},cornerPx[2]{},rmsPx[2]{}; int tiles[2]{}; float cx=0,cy=0,invHalf=0; };
// Lateral CA removed from a merged RGB (canonical geometry, grid g): R and B at output pixel X are taken at the canonical position
// of X plus the model displacement 2 d (RAW px), bilinear; G stays. The merge has aligned the frames and every frame carries the
// same lens CA, so the merged R / B planes are displaced exactly as one frame's; their alignment (gray) does not depend on it.
struct HybridCa;
inline void hybridCorrectCa(std::vector<float>& rgb,int w,int h,int grid,const HybridCa& ca);
inline HybridCa hybridRawCa(const HybridInput& in){
    HybridCa ca;
    const int w2=in.w/2,h2=in.h/2,ox=in.cfa&1,oy=in.cfa>>1;
    if(w2<256||h2<256)return ca;
    const uint16_t* raw=in.frames[0].raw;
    const float bl=0.25f*(in.black[0]+in.black[1]+in.black[2]+in.black[3]);
    const float clip=0.95f*(in.white-bl);
    auto px=[&](int x,int y){x=std::clamp(x+ox,0,in.w-1);y=std::clamp(y+oy,0,in.h-1);return float(raw[size_t(y)*in.w+x])-bl;};
    // R, B and G interpolated at them per cell (i, j), computed where a tile reads them: the same expressions as the w/2 x h/2
    // planes they replace (4 B per pixel, 800 MB at 200 MP), the same values.
    auto cellR=[&](int i,int j){const int x=2*i,y=2*j;return px(x,y);};
    auto cellB=[&](int i,int j){const int x=2*i,y=2*j;return px(x+1,y+1);};
    auto cellGR=[&](int i,int j){const int x=2*i,y=2*j;return 0.25f*(px(x-1,y)+px(x+1,y)+px(x,y-1)+px(x,y+1));};
    auto cellGB=[&](int i,int j){const int x=2*i,y=2*j;return 0.25f*(px(x,y+1)+px(x+2,y+1)+px(x+1,y)+px(x+1,y+2));};
    constexpr int T=64;
    struct Obs{float x,y,dx,dy;};
    std::vector<Obs> obs[2];
    std::mutex m;
    std::vector<std::pair<int,int>> tiles;
    for(int ty=T;ty+2*T<=h2;ty+=T)for(int tx=T;tx+2*T<=w2;tx+=T)tiles.push_back({tx,ty});
    mergeRowBands(int(tiles.size()),[&](int t0,int t1){
        std::vector<float> c(T*T),g(T*T),cs(T*T);
        for(int t=t0;t<t1;++t){
            const int tx=tiles[t].first,ty=tiles[t].second;
            for(int ch=0;ch<2;++ch){
                float cmax=-1e9f,gmax=-1e9f;double gmean=0,grad=0;
                for(int y=0;y<T;++y)for(int x=0;x<T;++x){
                    const int i=tx+x,j=ty+y;
                    const float cv=ch?cellB(i,j):cellR(i,j),gv=ch?cellGB(i,j):cellGR(i,j);
                    c[y*T+x]=cv;g[y*T+x]=gv;cmax=std::max(cmax,cv);gmax=std::max(gmax,gv);gmean+=gv;
                }
                gmean/=T*T;
                if(cmax>=clip||gmax>=clip||gmean<20)continue;
                double mxx=0,mxy=0,myy=0;
                for(int y=1;y<T-1;++y)for(int x=1;x<T-1;++x){const float gx=0.5f*(g[y*T+x+1]-g[y*T+x-1]),gy=0.5f*(g[(y+1)*T+x]-g[(y-1)*T+x]);mxx+=gx*gx;mxy+=gx*gy;myy+=gy*gy;}
                grad=(mxx+myy)/((T-2)*(T-2));
                if(grad<4.0)continue;
                const double det=mxx*myy-mxy*mxy,tr=mxx+myy;
                if(det<=0||tr*tr/det>2*50.0)continue; // condition number
                float dx=0,dy=0;bool good=true;
                for(int it=0;it<2&&good;++it){
                    for(int y=0;y<T;++y)for(int x=0;x<T;++x){ // c sampled at +d (bilinear, clamped)
                        const float sx=std::clamp(x+dx,0.f,float(T-1)),sy=std::clamp(y+dy,0.f,float(T-1));
                        const int x0=std::min(int(sx),T-2),y0=std::min(int(sy),T-2);const float fx=sx-x0,fy=sy-y0;
                        cs[y*T+x]=(c[y0*T+x0]*(1-fx)+c[y0*T+x0+1]*fx)*(1-fy)+(c[(y0+1)*T+x0]*(1-fx)+c[(y0+1)*T+x0+1]*fx)*fy;
                    }
                    double sg=0,sc=0,sgg=0,sgc=0;const double n=T*T;
                    for(int k=0;k<T*T;++k){sg+=g[k];sc+=cs[k];sgg+=double(g[k])*g[k];sgc+=double(g[k])*cs[k];}
                    const double kk=(n*sgc-sg*sc)/std::max(n*sgg-sg*sg,1e-9),bb=(sc-kk*sg)/n;
                    if(!(kk>0.05)){good=false;break;}
                    double bx=0,by=0;
                    for(int y=1;y<T-1;++y)for(int x=1;x<T-1;++x){
                        const float gx=0.5f*(g[y*T+x+1]-g[y*T+x-1]),gy=0.5f*(g[(y+1)*T+x]-g[(y-1)*T+x]);
                        const double e=(cs[y*T+x]-bb)/kk-g[y*T+x];bx+=gx*e;by+=gy*e;
                    }
                    // R(x + d) ~ G(x): e = -grad G . step
                    dx+=float(-(myy*bx-mxy*by)/det);dy+=float(-(mxx*by-mxy*bx)/det);
                }
                if(!good||std::abs(dx)>=2.f||std::abs(dy)>=2.f)continue;
                std::lock_guard<std::mutex> lock(m);obs[ch].push_back({tx+T*0.5f,ty+T*0.5f,dx,dy});
            }
        }
    });
    ca.cx=w2*0.5f;ca.cy=h2*0.5f;const float half=std::hypot(ca.cx,ca.cy);ca.invHalf=1.f/half;
    bool all=true;
    for(int ch=0;ch<2;++ch){
        const auto& o=obs[ch];ca.tiles[ch]=int(o.size());
        if(o.size()<40){all=false;continue;}
        // the measured shift is where R sits against G; the model d holds the opposite sign for the rebuild at s + 2d
        auto fit=[&](const std::vector<bool>& use,double& k1,double& k2){
            double a11=0,a12=0,a22=0,b1=0,b2=0;
            for(size_t i=0;i<o.size();++i){if(!use[i])continue;
                const double px_=(o[i].x-ca.cx)/half,py_=(o[i].y-ca.cy)/half,r2=px_*px_+py_*py_;
                for(int a=0;a<2;++a){const double p=a?py_:px_,y=a?o[i].dy:o[i].dx,f1=p,f2=p*r2;a11+=f1*f1;a12+=f1*f2;a22+=f2*f2;b1+=f1*y;b2+=f2*y;}}
            const double det=a11*a22-a12*a12;if(std::abs(det)<1e-12){k1=k2=0;return;}
            k1=(a22*b1-a12*b2)/det;k2=(a11*b2-a12*b1)/det;
        };
        std::vector<bool> use(o.size(),true);double k1=0,k2=0;fit(use,k1,k2);
        std::vector<double> res(o.size());
        for(size_t i=0;i<o.size();++i){const double px_=(o[i].x-ca.cx)/half,py_=(o[i].y-ca.cy)/half,r2=px_*px_+py_*py_,s=k1+k2*r2;
            res[i]=std::hypot(o[i].dx-s*px_,o[i].dy-s*py_);}
        std::vector<double> sorted=res;std::nth_element(sorted.begin(),sorted.begin()+sorted.size()*8/10,sorted.end());const double cut=sorted[sorted.size()*8/10];
        for(size_t i=0;i<o.size();++i)use[i]=res[i]<=cut;
        fit(use,k1,k2);
        double ss=0;int n=0;
        for(size_t i=0;i<o.size();++i){if(!use[i])continue;const double px_=(o[i].x-ca.cx)/half,py_=(o[i].y-ca.cy)/half,r2=px_*px_+py_*py_,s=k1+k2*r2;
            ss+=std::pow(o[i].dx-s*px_,2)+std::pow(o[i].dy-s*py_,2);n+=2;}
        ca.k1[ch]=float(k1);ca.k2[ch]=float(k2);ca.cornerPx[ch]=float(2*(k1+k2));ca.rmsPx[ch]=float(2*std::sqrt(ss/std::max(n,1)));
    }
    ca.ok=all;
    return ca;
}
// P33 W2.2: hybridCorrectCa in bands that follow the merge's strips (advance(rgb, rows): the rows [0, rows) are final). A band reads
// the R / B of its rows +- margin and its clamps never bind inside the image (|d| <= 4 g < margin), so any band split gives the
// same floats as one pass.
class HybridCaBands {
public:
    HybridCaBands(int width,int height,int grid,const HybridCa& model):w(width),h(height),g(float(grid)),ca(model),
            margin(int(std::ceil(4.f*float(grid)))+2),rx(width),ry(height){
        for(int X=0;X<w;++X)rx[X]=(0.5f*((X+0.5f)/g-0.5f)-ca.cx)*ca.invHalf;
        for(int Y=0;Y<h;++Y)ry[Y]=(0.5f*((Y+0.5f)/g-0.5f)-ca.cy)*ca.invHalf;
    }
    int done() const {return next;}
    void advance(std::vector<float>& rgb,int rows){
        const int limit=rows>=h?h:std::max(0,rows-margin);
        while(next<limit){
            // Bands of up to 512 rows, top to bottom. A band reads the original R / B of its rows +- margin: the rows above it come
            // from the copy kept before the previous band wrote them, the rest from the image (not written yet). Only R and B are
            // held (no copy of the whole RGB: 600 MB on the 2x grid).
            const int y0=next,y1=std::min(limit,y0+512),b0=std::max(0,y0-margin),b1=std::min(h,y1+margin);
            buf.resize(size_t(b1-b0)*w*2);
            mergeRowBands(b1-b0,[&](int a0,int a1){for(int y=b0+a0;y<b0+a1;++y){
                float* d=buf.data()+size_t(y-b0)*w*2;
                if(y<y0){const float* sv=saved.data()+size_t(y-savedFrom)*w*2;std::copy(sv,sv+size_t(w)*2,d);continue;}
                const float* r=rgb.data()+size_t(y)*w*3;
                for(int X=0;X<w;++X){d[2*X]=r[3*X];d[2*X+1]=r[3*X+2];}
            }});
            mergeRowBands(y1-y0,[&](int a0,int a1){
                for(int Y=y0+a0;Y<y0+a1;++Y){
                    float* o=rgb.data()+size_t(Y)*w*3;
                    const float yy=ry[Y];
                    for(int X=0;X<w;++X){
                        const float xx=rx[X],r2=xx*xx+yy*yy;
                        for(int ch=0;ch<2;++ch){
                            const float k=ca.k1[ch]+ca.k2[ch]*r2;
                            const float dx=std::clamp(2.f*k*xx*g,-4.f*g,4.f*g),dy=std::clamp(2.f*k*yy*g,-4.f*g,4.f*g);
                            const float sx=std::clamp(X+dx,0.f,float(w-1)),sy=std::clamp(Y+dy,float(b0),float(b1-1));
                            const int x0=std::min(int(sx),w-2),yq=std::min(int(sy),b1-2);const float fx=sx-x0,fy=sy-yq;
                            const float* p0=buf.data()+(size_t(yq-b0)*w+x0)*2+ch;const float* p1=p0+size_t(w)*2;
                            o[3*X+(ch?2:0)]=(p0[0]*(1-fx)+p0[2]*fx)*(1-fy)+(p1[0]*(1-fx)+p1[2]*fx)*fy;
                        }
                    }
                }
            });
            // the original rows the next band needs above it
            savedFrom=std::max(b0,y1-margin);
            saved.assign(buf.begin()+size_t(savedFrom-b0)*w*2,buf.begin()+size_t(y1-b0)*w*2);
            next=y1;
        }
        if(next>=h){std::vector<float>().swap(buf);std::vector<float>().swap(saved);}
    }
private:
    int w,h;float g;HybridCa ca;int margin;std::vector<float> rx,ry,buf,saved;int savedFrom=0,next=0;
};
inline void hybridCorrectCa(std::vector<float>& rgb,int w,int h,int grid,const HybridCa& ca){
    HybridCaBands bands(w,h,grid,ca);bands.advance(rgb,h);
}

// RAW Bracket 0.2.5 (research/rawbracket/NOTES.md) measured gain: the exposure ratio of a bracketed / ultrashort frame to the
// base from the data, to check the metadata ratio the merge normalises with (a wrong ratio leaves a step where the other
// exposure replaces the base). A grid of up to 32 x 32 tiles of 16 x 16 RAW px; a tile counts when neither frame clips in it and
// its signal s (the mean of both frames on the base scale, s = (base + other / metadata) / 2) puts the darker exposure above the
// noise (8 DN) and the brighter one below 80 % of the range; its ratio must lie within 0.5..2 x the metadata. Median and
// relative MAD over at least 64 tiles. Translation between the frames is not compensated: tiles at moving edges are what the
// MAD rejects. The thresholds act on s, not on each frame's own mean: a test on the darker frame's mean alone kept the tiles
// whose noise or motion made it brighter and dropped the others, which biased a dim ultrashort frame upwards (vivo X200 Ultra
// Tetra 10x night burst, ES at 1/16: +34..37 % with the per-frame test, -3..-7 % with s, metadata ratio exact in daylight).
// smooth (P268, the mosaic paths' ultrashort check): only tiles whose base is smooth (rms within the tile at most 12 % of its mean
// plus 3 DN): a hand-held frame's translation moves the edges inside a textured tile, whose ratio then scatters (vivo X200 Ultra
// main 2x Quad, 2026-10-19: all three ultrashort frames refused at a MAD of 16.8 % with the median 1.2 % from the metadata, and
// every highlight stayed clipped with ragged borders); a flicker band changes the smooth tiles' ratios as well.
struct HybridGain { float measured=0,mad=0; int tiles=0; bool ok=false; };
inline HybridGain hybridMeasuredGain(const HybridInput& in,int f,bool smooth=false){
    HybridGain g;
    const uint16_t* base=in.frames[0].raw;const uint16_t* other=in.frames[f].raw;
    const float expected=in.frames[f].exposure;
    if(!base||!other||!(expected>0.f)||in.w<64||in.h<64||!(in.white>0.f))return g;
    const int tile=16,nx=std::min(32,in.w/tile),ny=std::min(32,in.h/tile);
    const int sx=(in.w-tile)/std::max(1,nx-1),sy=(in.h-tile)/std::max(1,ny-1);
    const float clip=0.98f*in.white;
    std::vector<float> ratios;ratios.reserve(size_t(nx)*ny);
    for(int j=0;j<ny;++j)for(int i=0;i<nx;++i){
        const int x0=(i*sx)&~1,y0=(j*sy)&~1;
        double sb=0,so=0,sb2=0;bool clipped=false;
        for(int y=y0;y<y0+tile&&!clipped;++y)for(int x=x0;x<x0+tile;++x){
            const size_t k=size_t(y)*in.w+x;
            const float bl=in.black[(y&1)*2+(x&1)];
            if(base[k]>=clip||other[k]>=clip){clipped=true;break;}
            const double v=double(base[k])-bl;
            sb+=v;so+=double(other[k])-bl;sb2+=v*v;
        }
        if(clipped)continue;
        const double n=double(tile)*tile,mb=sb/n,mo=so/n,range=in.white-in.black[0];
        if(smooth&&std::sqrt(std::max(0.0,sb2/n-mb*mb))>0.12*mb+3.0)continue;
        const double sig=0.5*(mb+mo/expected); // the tile's signal on the base scale, from both frames alike
        if(mb<=0.0||mo<=0.0||sig*std::min(1.f,expected)<8.0||sig*std::max(1.f,expected)>0.8*range)continue;
        const float r=float(mo/mb);
        if(r<0.5f*expected||r>2.f*expected)continue;
        ratios.push_back(r);
    }
    g.tiles=int(ratios.size());
    if(g.tiles<64)return g;
    std::nth_element(ratios.begin(),ratios.begin()+ratios.size()/2,ratios.end());
    g.measured=ratios[ratios.size()/2];
    std::vector<float> dev(ratios.size());
    for(size_t k=0;k<ratios.size();++k)dev[k]=std::abs(ratios[k]-g.measured);
    std::nth_element(dev.begin(),dev.begin()+dev.size()/2,dev.end());
    g.mad=dev[dev.size()/2]/std::max(g.measured,1e-6f);
    g.ok=g.mad<0.15f;
    return g;
}

// The report line of one frame's measured gain (HYBRID GAIN CHECK; `metadata` = the ratio the capture metadata gives).
inline std::string hybridGainCheckLine(int f,int role,float metadata,const HybridGain& g){
    char line[200];
    if(g.tiles<64)std::snprintf(line,sizeof(line),"HYBRID GAIN CHECK frame=%d role=%d metadata=%.4f: insufficient signal or overlap (%d tiles)",f,role,metadata,g.tiles);
    else std::snprintf(line,sizeof(line),"HYBRID GAIN CHECK frame=%d role=%d metadata=%.4f measured=%.4f (%+.1f %%) tiles=%d mad=%.1f %% -> %s",f,role,metadata,g.measured,
        100.0*(g.measured/metadata-1.0),g.tiles,100.0*g.mad,!g.ok?"inconsistent ratios":std::abs(g.measured/metadata-1.f)>0.05f?"metadata differs":"ok");
    return line;
}

// gainMeasured 1 (the default): the measured exposure ratio replaces the metadata ratio where the data is reliable. Some phones
// deliver bracketed frames whose RAW level is not what the metadata says (vivo X200 Pro: LONG frames at ISO x2 ~4300 measured
// x1.47 of the base instead of x2.0 on 660 tiles, MAD 5 %, the same on all five frames: the sensor's analog gain ends near ISO
// 3200 and the rest is applied after the RAW). The merge normalises every frame by its ratio (merge gain 1 / ratio, the
// alignment guides, the local alignment, the Shasta sharpness test, the Bento ultrashort mask and its factor), so a wrong ratio
// shows as a level step where the other exposure replaces or joins the base.
// The frames of one exposure (same role, metadata ratios within 2 %) are decided together. A frame is reliable with at least
// kGainMinTiles tiles and a relative MAD of at most kGainMaxMad; the group is reliable when at least two of its frames are and
// they make at least half of it, and their measured / metadata factors agree within kGainMaxSpread of their median, or when
// its only reliable frame makes at least half of it with a MAD of at most kGainMaxMadAlone. A reliable group whose factor
// lies within 0.5..2 and differs from 1 by more than 5 % takes it: every frame of the group gets metadata x factor (the
// unreliable frames of the group too: they were captured with the same settings). Every other group keeps its metadata
// ratios bit for bit. Each frame's measurement is reported (HYBRID GAIN CHECK) and each group's decision (HYBRID GAIN).
// Returns the number of frames whose ratio changed.
constexpr int kGainMinTiles=64;
constexpr float kGainMaxMad=0.10f,kGainMaxMadAlone=0.06f,kGainMaxSpread=0.03f,kGainTolerance=0.05f;
inline int hybridApplyMeasuredGains(HybridInput& in,const std::function<void(const std::string&)>& report){
    const int n=int(in.frames.size());
    std::vector<std::future<HybridGain>> pending(n);
    for(int f=1;f<n;++f){
        const int role=in.frames[f].role;
        if(role!=kRoleBracketed&&role!=kRoleUltrashort)continue;
        // the frames in parallel (P30); a thread the system refuses measures on this one
        try{pending[f]=std::async(std::launch::async,[&in,f]{return hybridMeasuredGain(in,f);});}
        catch(const std::system_error&){pending[f]=std::async(std::launch::deferred,[&in,f]{return hybridMeasuredGain(in,f);});}
    }
    std::vector<HybridGain> gains(n);
    std::vector<std::vector<int>> groups;
    for(int f=1;f<n;++f){
        if(!pending[f].valid())continue;
        gains[f]=pending[f].get();
        const HybridFrame& fr=in.frames[f];
        if(report)report(hybridGainCheckLine(f,fr.role,fr.exposure,gains[f]));
        bool joined=false;
        for(auto& grp:groups){
            const HybridFrame& first=in.frames[grp[0]];
            if(first.role==fr.role&&std::abs(std::log(fr.exposure/first.exposure))<std::log(1.02f)){grp.push_back(f);joined=true;break;}
        }
        if(!joined)groups.push_back({f});
    }
    int changed=0;
    for(const auto& grp:groups){
        std::vector<float> factors;float madAlone=0;
        for(int f:grp){
            const HybridGain& g=gains[f];
            const float factor=g.measured/in.frames[f].exposure;
            if(g.tiles>=kGainMinTiles&&g.mad<=kGainMaxMad&&std::isfinite(factor)&&factor>0.f){factors.push_back(factor);madAlone=g.mad;}
        }
        std::vector<float> sorted=factors;std::sort(sorted.begin(),sorted.end());
        const float factor=sorted.empty()?1.f:sorted.size()%2?sorted[sorted.size()/2]:0.5f*(sorted[sorted.size()/2-1]+sorted[sorted.size()/2]);
        float spread=0;for(float c:factors)spread=std::max(spread,std::abs(c/factor-1.f));
        const bool half=2*factors.size()>=grp.size();
        std::string reason;
        char why[64];std::snprintf(why,sizeof(why),"frames disagree (spread %.1f %%)",100.0*spread);
        if(factors.size()>=2&&half&&spread>kGainMaxSpread)reason=why;
        else if(factors.empty()||!half)reason="too few reliable frames";
        else if(factors.size()==1&&madAlone>kGainMaxMadAlone)reason="one reliable frame, mad above the single-frame limit";
        else if(factor<0.5f||factor>2.f)reason="factor outside 0.5..2";
        const bool use=reason.empty()&&std::abs(factor-1.f)>kGainTolerance;
        if(report){
            std::string frames;for(int f:grp)frames+=(frames.empty()?"":",")+std::to_string(f);
            char line[400];
            std::snprintf(line,sizeof(line),"HYBRID GAIN: role=%d metadata=%.4f frames=%s: %zu of %zu reliable (tiles >= %d, mad <= %.0f %%), factor %.4f (%+.1f %%) -> %s%s",
                in.frames[grp[0]].role,in.frames[grp[0]].exposure,frames.c_str(),factors.size(),grp.size(),kGainMinTiles,100.0*kGainMaxMad,factor,100.0*(factor-1.0),
                use?"measured ratio used":!reason.empty()?"metadata kept: ":"metadata kept (agrees within 5 %)",reason.c_str());
            report(line);
        }
        if(!use)continue;
        for(int f:grp){in.frames[f].exposure*=factor;++changed;}
    }
    return changed;
}

// P197 (owner, 2026-10-17: «Сделай для нативной мозаики ту же цветовую проверку Bento, что у 12 Мп»). vivo X200 Ultra, FULLRES
// 50 MP native Quad under a 50 Hz ceiling lamp: the ultrashort frame (2.08 ms at 1/60 N) is read out row after row while the lamp
// flickers at 100 Hz, so every sensor row saw another phase of it - another brightness and, with a white LED (the blue die follows
// the current, the yellow phosphor glows on), another colour. Its GAIN CHECK said "inconsistent ratios" (+10.7 %, MAD 26.9 %),
// Bento replaced the clipped diffuser with it anyway (bento 2, forced) and the lamp came out orange on one side, blue on the other.
// The 12 MP shot of the same lamp was white because its three ultrashort frames were consistent (+2.5 %, MAD 5.1 %), not because
// of a colour check the native path lacked: the plain path's Bento checks (bentoMask: the intensity error against the base, the
// validation of every ultrashort frame, the motion share) run on the binned frames of the native path exactly as on a plain
// burst, and none of them sees a colour inside a clipped base (GainUp(us) is min(., 1) there); the Bento colour pass (kHybBento)
// takes R/G, B/G FROM the ultrashort frames and would paint the same bands. What the mosaic paths (native, split, binned) do now
// with every ultrashort frame they merge, on its binned frame (the b^2 means) against the base's:
//   1. row profile (hybridUltrashortRows): the rows in bands of 32 (h / 32, 4..64 bands: a 100 Hz flicker period spans ~10-20
//      of them on a 12 / 50 MP binned frame), 16 x 16 tiles where neither frame clips and every colour carries signal in both
//      (hybridMeasuredGain's tile rule, per colour; ratios within x0.25..4 of the metadata). Per tile the level ratio g = us / base
//      of green and the colour ratios (us / base of R, B) / g; per band their medians. A band counts when it has >= 24 tiles and
//      its tiles AGREE (relative MAD of g and of both colour ratios <= kUsRowsBandMad, 12 %): rolling-shutter flicker depends on
//      the row alone, so inside a band every tile sees the same factor; a disagreement that is not a row profile (motion, a lamp
//      lighting one side, a column ramp) scatters the tiles of a band and is not "corrected" (a first version with per-colour
//      medians over different tile sets made up a +-30 % colour profile on a synthetic COLUMN ramp: the scene puts other columns
//      into other bands). Factors: G = E / g_band (E = the exposure ratio the merge normalises the frame with: gainMeasured
//      has decided it on the stream frames before the mosaic paths run, and a flickering frame is "inconsistent" there, so it is
//      the metadata; a first version took the frame's median g and left a sinus-flickered frame 14 % bright in the highlights),
//      R / B = G / colour ratio of the band (the colour from the same tiles as the level, so a neutral
//      disagreement never becomes a colour one). Bands that do not count are interpolated; fewer than half count: no profile.
//      The base's colour in the same rows (its exposure spans ~1.7 flicker periods) is what the replacement must match.
//   2. a factor departing from 1 by mosaicUsRowsMin (8 %) or more: the frame is corrected, (v - black) x the factor of its row and
//      colour, linear between band centres (binned frame, split sub-frames, native sites as they are uploaded); clipped sites stay.
//   3. the GAIN CHECK of the (corrected) frame against the base (hybridMeasuredGain, the merge's own check): still inconsistent
//      (MAD >= 15 % on >= 64 tiles) = the disagreement is not a row profile (lamps on other phases, motion): the frame is refused,
//      no Bento from it - the base's clipped highlight stays (clip flags, highlight recovery: neutral white) - in every Bento mode,
//      as the motion refusal (bentoMotionMax): "forced" must not paint with a frame the data contradicts.
// A burst without an ultrashort frame, and one whose ultrashort frame is within 8 % in every band and consistent, merges byte for
// byte as before (new report lines only).
// Owner, 2026-10-19 (X200 Ultra, 12 MP, a 50 Hz ceiling lamp painted light blue; «исправь это во всех режимах»): the plain Bayer
// merge now runs the same check on its ultrashort frames (hybridPlainUsRows, from hybridReconstruct), so every mode does.
// P290: the ultrashort frame's exposure ratio to the base differs from place to place when the scene is lit by lamps on different
// flicker phases (a 2.8 ms frame sees a part of a 120 Hz cycle; the 16.7 ms base two whole ones) or holds a screen: the owner's
// X200 Ultra shot of a ship's bridge (desk lamp, ceiling lamps, an echo sounder) measured 0.1399 over the frame (GAIN CHECK, MAD
// 9.6 %: consistent, no row profile: 15 of 48 bands agreeing) while the sheet under the desk lamp had 0.08..0.11 per colour. Bento
// fills the base's clipped highlight from the ultrashort frames alone, so the sheet's clipped part came out up to 30 % darker and
// greener than the unclipped paper around it, the border following the clip on the binned cells: stepped contours. Measured here:
// 16 x 16 tiles of the (row-corrected) frame against the base where neither clips and each colour carries signal (the row profile's
// tile rule), smooth in the base (texture would turn a small misregistration into a ratio); per tile the green level ratio and
// the R / G, B / G colour ratios, logs; a tile far from its 5 x 5 neighbours' median (motion) dropped. The field is filled into the
// tiles without a measurement (the clipped highlight itself, dark areas) by normalised convolution from coarse (32 tiles) to fine
// (1 tile): a clipped area takes the ratio of the ring of unclipped tiles around it, i.e. of the lamp that lights both. Applied when
// a factor near the base's clipped area (4 tiles) departs by mosaicUsRowsMin or more; a burst whose ultrashort frame agrees there
// merges as before.
// Gaussian blur of a gw x gh grid, zero outside (normalised convolution divides two of these).
inline std::vector<float> hybridGridBlur(const std::vector<float>& a,int gw,int gh,float sigma){
    const int r=std::max(1,int(std::ceil(3.f*sigma)));
    std::vector<float> k(size_t(2*r+1));for(int i=-r;i<=r;++i)k[size_t(i+r)]=std::exp(-0.5f*float(i*i)/(sigma*sigma));
    std::vector<float> t(a.size(),0.f),o(a.size(),0.f);
    // P308: on all cores, and four outputs at a time whose sums run over the same taps (four rows of the horizontal pass, four
    // columns of the vertical one): each output is the same sum in the same order as before, the chains no longer wait on each
    // other (0.2 s of every ultrashort frame of a 50 MP shot one output at a time).
    const float* kp=k.data();const float* ap=a.data();float* tp=t.data();float* op=o.data();
    const int R=r,GW=gw,GH=gh;
    mergeRowBands((gh+3)/4,[=](int q0,int q1){
        for(int q=q0;q<q1;++q){
            const int y=4*q;
            if(y+4<=GH){
                for(int x=0;x<GW;++x){
                    float s0=0,s1=0,s2=0,s3=0;
                    const float* r0=ap+size_t(y)*GW+x;const float* r1=r0+GW;const float* r2=r1+GW;const float* r3=r2+GW;
                    for(int i=std::max(-R,-x);i<=std::min(R,GW-1-x);++i){
                        const float kk=kp[size_t(i+R)];
                        s0+=kk*r0[i];s1+=kk*r1[i];s2+=kk*r2[i];s3+=kk*r3[i];
                    }
                    tp[size_t(y)*GW+x]=s0;tp[size_t(y+1)*GW+x]=s1;tp[size_t(y+2)*GW+x]=s2;tp[size_t(y+3)*GW+x]=s3;
                }
            } else for(int yy=y;yy<GH;++yy)for(int x=0;x<GW;++x){
                float s=0;for(int i=std::max(-R,-x);i<=std::min(R,GW-1-x);++i)s+=kp[size_t(i+R)]*ap[size_t(yy)*GW+x+i];
                tp[size_t(yy)*GW+x]=s;
            }
        }
    });
    mergeRowBands(gh,[=](int y0,int y1){
        for(int y=y0;y<y1;++y){
            const int i0=std::max(-R,-y),i1=std::min(R,GH-1-y);
            int x=0;
            for(;x+4<=GW;x+=4){
                float s0=0,s1=0,s2=0,s3=0;
                for(int i=i0;i<=i1;++i){
                    const float kk=kp[size_t(i+R)];const float* c=tp+size_t(y+i)*GW+x;
                    s0+=kk*c[0];s1+=kk*c[1];s2+=kk*c[2];s3+=kk*c[3];
                }
                op[size_t(y)*GW+x]=s0;op[size_t(y)*GW+x+1]=s1;op[size_t(y)*GW+x+2]=s2;op[size_t(y)*GW+x+3]=s3;
            }
            for(;x<GW;++x){
                float s=0;for(int i=i0;i<=i1;++i)s+=kp[size_t(i+R)]*tp[size_t(y+i)*GW+x];
                op[size_t(y)*GW+x]=s;
            }
        }
    });
    return o;
}
// P313: the global translation of the ultrashort frame `us` against the base (plain Bayer frames w x h, black of the 2x2 phase, us
// at `expected` x the base's exposure), in even px so that every site keeps its colour: us (x + dx, y + dy) shows what base (x, y)
// shows. Coarse: globalShift (scam-capture.h, the alignment's fallback) on a pyramid of S x S block means of both frames (S = 4, 8
// from 3072 px wide: whole 2 x 2 site blocks, colour-balanced; level 0 at most ~512 blocks wide); fine: the 2 x 2 site blocks within
// +-S / 2 px of it by the same bounded relative residual; none beyond 1/16 of the frame. Measured (dx, dy) on the owner's bursts:
// the vivo 2x ISZ shot (2, 8) binned px, the OPPO lamp bursts (4..6, -2..-4) binned and (8, -12) / (4, -8) px, the X8U LED burst
// (10, -8) px - within 2 px of a phase correlation of the frames everywhere; globalShift alone is 4 px coarse ((4, 8) on the ISZ
// shot: the tile check still corrected it).
struct HybridUsShift { int dx=0,dy=0; };
inline HybridUsShift hybridUsGlobalShift(const uint16_t* base,const uint16_t* us,int w,int h,const std::array<float,4>& black,float white,float expected){
    HybridUsShift r;
    const int S=w>=3072?8:4;
    const int gw=w/S,gh=h/S;
    if(!base||!us||gw<64||gh<64||!(expected>0.f)||!(white>0.f))return r;
    const double bl=0.25*(double(black[0])+black[1]+black[2]+black[3]),den=std::max(double(white)-bl,1.0);
    std::vector<Guide> gb(1),gu(1);
    gb[0].w=gu[0].w=gw;gb[0].h=gu[0].h=gh;gb[0].v.assign(size_t(gw)*gh,0.f);gu[0].v.assign(size_t(gw)*gh,0.f);
    mergeRowBands(gh,[&](int y0,int y1){
        for(int Y=y0;Y<y1;++Y)for(int X=0;X<gw;++X){
            uint32_t sb=0,su=0;
            for(int y=Y*S;y<Y*S+S;++y){
                const uint16_t* pb=base+size_t(y)*w+size_t(X)*S;const uint16_t* pu=us+size_t(y)*w+size_t(X)*S;
                for(int x=0;x<S;++x){sb+=pb[x];su+=pu[x];}
            }
            const double nn=double(S)*S;
            gb[0].v[size_t(Y)*gw+X]=float(std::clamp((sb/nn-bl)/den,0.0,1.0));
            gu[0].v[size_t(Y)*gw+X]=float(std::clamp((su/nn-bl)/den,0.0,1.0));
        }
    });
    for(auto* g:{&gb,&gu})while(g->back().w>=64&&g->back().h>=64){ // as guides()
        const Guide& prev=g->back();Guide next{prev.w/2,prev.h/2,{}};next.v.resize(size_t(next.w)*next.h);
        for(int y=0;y<next.h;++y)for(int x=0;x<next.w;++x)
            next.v[size_t(y)*next.w+x]=(prev.at(x*2,y*2)+prev.at(x*2+1,y*2)+prev.at(x*2,y*2+1)+prev.at(x*2+1,y*2+1))*.25f;
        g->push_back(std::move(next));
    }
    const Shift coarse=globalShift(gb,gu,expected); // in level-0 blocks x 4
    const int cx=int(std::lround(coarse.x/4.f))*S/2,cy=int(std::lround(coarse.y/4.f))*S/2; // in 2 x 2 site blocks
    // a match farther than 1/16 of the frame is a repeated texture's alias, not the hand between two frames of a burst (the
    // synthetic sine scene of check_hybrid_us_rows matched at (102, 114) px of 1024 x 768 with a column ramp): measured unshifted
    const int lim=std::max(w,h)/16;
    if(2*std::abs(cx)>lim||2*std::abs(cy)>lim)return r;
    const int qw=w/2,qh=h/2,R=S/4,step=std::max(1,qw/512);
    const double qden=std::max(4.0*white-4.0*bl,1.0);
    auto quad=[&](const uint16_t* f,int qx,int qy){
        const uint16_t* p=f+size_t(2*qy)*w+size_t(2*qx);
        return float((double(p[0])+p[1]+p[w]+p[w+1]-4.0*bl)/qden);
    };
    auto score=[&](int ox,int oy){
        double sum=0;long cnt=0;
        for(int qy=0;qy<qh;qy+=step){
            const int sy=qy+oy;if(sy<0||sy>=qh)continue;
            for(int qx=0;qx<qw;qx+=step){
                const int sx=qx+ox;if(sx<0||sx>=qw)continue;
                const float a=quad(base,qx,qy),v=quad(us,sx,sy);
                if(a>.97f||v>.97f||a<.002f||v<.002f)continue;
                sum+=std::min(std::abs(a-v/expected)/(0.02f+a),.5f);++cnt;
            }
        }
        return cnt>=16?sum/double(cnt):double(INFINITY);
    };
    std::vector<double> scores(size_t(2*R+1)*(2*R+1));
    mergeRowBands(int(scores.size()),[&](int k0,int k1){
        for(int k=k0;k<k1;++k)scores[size_t(k)]=score(cx+k%(2*R+1)-R,cy+k/(2*R+1)-R);
    });
    int bx=cx,by=cy;double best=scores[size_t(R)*(2*R+1)+R];
    for(int k=0;k<int(scores.size());++k)if(scores[size_t(k)]<best){best=scores[size_t(k)];bx=cx+k%(2*R+1)-R;by=cy+k/(2*R+1)-R;}
    if(!std::isfinite(best))return r;
    r.dx=2*bx;r.dy=2*by;
    return r;
}
// base / us: plain Bayer frames w x h (sensor CFA `cfa`, black of the 2x2 phase), us at `expected` x the base's exposure.
// align (P313, HybridTuning::usLocalAlign): the tiles at the frames' global translation, the decision against the frame's level.
inline HybridUsLocal hybridUltrashortLocal(const uint16_t* base,const uint16_t* us,int w,int h,int cfa,const std::array<float,4>& black,
                                           float white,float expected,float minDev,bool align=false){
    HybridUsLocal r;
    const int T=16;
    if(!base||!us||w<4*T||h<4*T||!(expected>0.f)||!(white>0.f))return r;
    const int gw=w/T,gh=h/T;
    r.gw=gw;r.gh=gh;r.tile=float(T);
    const float clip=0.98f*white;
    const double range=double(white)-black[0];
    const size_t n=size_t(gw)*gh;
    std::array<std::vector<float>,3> lv;for(auto& v:lv)v.assign(n,0.f); // log green ratio / expected, log R / G, log B / G
    std::vector<uint8_t> valid(n,0),clipped(n,0);
    // P313: us read at (x + dx, y + dy) (even: the same colour); a site whose partner lies off the frame counts as unmeasured
    const HybridUsShift sh=align?hybridUsGlobalShift(base,us,w,h,black,white,expected):HybridUsShift{};
    const int dx=sh.dx,dy=sh.dy;
    r.ox=float(dx);r.oy=float(dy);
    mergeRowBands(gh,[&](int ty0,int ty1){
        for(int ty=ty0;ty<ty1;++ty)for(int tx=0;tx<gw;++tx){
            double sb[3]{},so[3]{},sq=0;int cnt[3]{},all[3]{};bool baseClip=false;
            for(int y=ty*T;y<ty*T+T;++y)for(int x=tx*T;x<tx*T+T;++x){
                const size_t i=size_t(y)*w+x;
                const int p=((y&1)<<1)|(x&1),c=p==cfa?0:p==(cfa^3)?2:1;
                ++all[c];
                if(base[i]>=clip){baseClip=true;continue;}
                const int ux=x+dx,uy=y+dy;
                if(ux<0||uy<0||ux>=w||uy>=h)continue;
                const uint16_t u=us[size_t(uy)*w+ux];
                if(u>=clip)continue;
                const double b=double(base[i])-black[p];
                sb[c]+=b;so[c]+=double(u)-black[p];++cnt[c];
                if(c==1)sq+=b*b;
            }
            const size_t k=size_t(ty)*gw+tx;
            clipped[k]=baseClip;
            float q[3];bool ok=true;
            for(int c=0;c<3&&ok;++c){
                if(4*cnt[c]<all[c]){ok=false;break;} // at least a quarter of the colour's sites unclipped in both
                const double mb=sb[c]/cnt[c],mo=so[c]/cnt[c],sig=0.5*(mb+mo/expected);
                if(mb<=0.0||mo<=0.0||sig*std::min(1.f,expected)<8.0||sig*std::max(1.f,expected)>0.95*range){ok=false;break;}
                q[c]=float(mo/mb);
                if(q[c]<0.3f*expected||q[c]>3.f*expected)ok=false;
            }
            if(ok){ // smooth in the base: the green sites' spread within 20 % of their mean (shot noise of 8 DN is far below)
                const double mg=sb[1]/cnt[1],var=sq/cnt[1]-mg*mg;
                if(var>0.04*mg*mg)ok=false;
            }
            if(!ok)continue;
            valid[k]=1;
            lv[0][k]=std::log(q[1]/expected);lv[1][k]=std::log(q[0]/q[1]);lv[2][k]=std::log(q[2]/q[1]);
        }
    });
    // a tile far from its 5 x 5 neighbours' median (motion, misregistration at a remaining edge) is no measurement
    std::vector<uint8_t> keep(valid);
    const float lim[3]={0.12f,0.10f,0.10f};
    mergeRowBands(gh,[&](int ty0,int ty1){
        std::vector<float> nb;
        for(int ty=ty0;ty<ty1;++ty)for(int tx=0;tx<gw;++tx){
            const size_t k=size_t(ty)*gw+tx;
            if(!valid[k])continue;
            for(int m=0;m<3&&keep[k];++m){
                nb.clear();
                for(int y=std::max(0,ty-2);y<=std::min(gh-1,ty+2);++y)for(int x=std::max(0,tx-2);x<=std::min(gw-1,tx+2);++x)
                    if(valid[size_t(y)*gw+x])nb.push_back(lv[m][size_t(y)*gw+x]);
                if(nb.size()<5){if(m==0)keep[k]=0;break;}
                std::nth_element(nb.begin(),nb.begin()+nb.size()/2,nb.end());
                if(std::abs(lv[m][k]-nb[nb.size()/2])>lim[m])keep[k]=0;
            }
        }
    });
    for(uint8_t v:keep)r.tiles+=v;
    if(r.tiles<64)return r;
    // normalised convolution from coarse to fine: where a scale's neighbourhood holds measurements its estimate replaces the coarser
    std::vector<float> wgt(n),ones(n,1.f);
    for(size_t k=0;k<n;++k)wgt[k]=keep[k];
    std::array<std::vector<float>,3> field;
    for(int m=0;m<3;++m){
        double s=0;for(size_t k=0;k<n;++k)if(keep[k])s+=lv[m][k];
        field[m].assign(n,float(s/double(r.tiles)));
    }
    for(float sigma:{32.f,16.f,8.f,4.f,2.f,1.f}){
        const std::vector<float> W=hybridGridBlur(wgt,gw,gh,sigma),F=hybridGridBlur(ones,gw,gh,sigma);
        for(int m=0;m<3;++m){
            std::vector<float> wv(n);for(size_t k=0;k<n;++k)wv[k]=keep[k]?lv[m][k]:0.f;
            const std::vector<float> V=hybridGridBlur(wv,gw,gh,sigma);
            for(size_t k=0;k<n;++k){
                if(W[k]<=1e-6f)continue;
                const float conf=std::clamp(W[k]/(0.3f*std::max(F[k],1e-6f)),0.f,1.f);
                field[m][k]=conf*(V[k]/W[k])+(1.f-conf)*field[m][k];
            }
        }
    }
    for(auto& v:r.f)v.assign(n,1.f);
    for(size_t k=0;k<n;++k){
        const float g=std::clamp(std::exp(-field[0][k]),0.4f,2.5f);
        r.f[1][k]=g;r.f[0][k]=std::clamp(g*std::exp(-field[1][k]),0.4f,2.5f);r.f[2][k]=std::clamp(g*std::exp(-field[2][k]),0.4f,2.5f);
    }
    // where it matters: within 4 tiles of the base's clipped tiles (Bento replaces there)
    std::vector<uint8_t> near(n,0);
    for(int ty=0;ty<gh;++ty)for(int tx=0;tx<gw;++tx){
        if(!clipped[size_t(ty)*gw+tx])continue;
        for(int y=std::max(0,ty-4);y<=std::min(gh-1,ty+4);++y)for(int x=std::max(0,tx-4);x<=std::min(gw-1,tx+4);++x)near[size_t(y)*gw+x]=1;
    }
    // P313 (align): against the frame's own level, the median green ratio of the measured tiles - an offset the whole frame shares is
    // the gain check's (hybridApplyMeasuredGains keeps the metadata within kGainTolerance), not a lamp near the clip; the colour
    // ratios against 1 as before (the ISZ shot: 3.3 % below the metadata everywhere, 0.074 -> 0.049 at the clip after the shift)
    float rel=1.f;
    if(align){
        std::vector<float> g;g.reserve(size_t(r.tiles));
        for(size_t k=0;k<n;++k)if(keep[k])g.push_back(lv[0][k]);
        std::nth_element(g.begin(),g.begin()+g.size()/2,g.end());
        rel=std::exp(g[g.size()/2]);
    }
    if(align){
        // P313 (align): decided by the 4th-largest deviation near the clip, not the single largest - a lamp on another flicker
        // phase moves many tiles, one to three tiles are a moving object or what the global shift leaves (the ISZ burst's third
        // ultrashort frame: 8.3 % on its worst tile; o12's paper in the wind 9.6 -> 6.0 %; every lamp and LED burst unchanged)
        std::vector<float> dev;
        for(size_t k=0;k<n;++k)if(near[k]){
            float d=0.f;for(int c=0;c<3;++c)d=std::max(d,std::abs(r.f[c][k]*rel-1.f));
            dev.push_back(d);
        }
        if(!dev.empty()){
            const size_t q=std::min<size_t>(3,dev.size()-1);
            std::nth_element(dev.begin(),dev.begin()+long(q),dev.end(),std::greater<float>());
            r.maxDev=dev[q];
        }
    }else for(size_t k=0;k<n;++k)if(near[k])for(int c=0;c<3;++c)r.maxDev=std::max(r.maxDev,std::abs(r.f[c][k]*rel-1.f));
    r.apply=r.maxDev>=minDev;
    return r;
}
struct HybridUsRows {
    int bands=0;float rows=0;long tiles=0;int measuredBands[3]{};bool measured=false,apply=false;float maxDev=0;
    std::array<std::vector<float>,3> f; // per band: the factor of R, G, B (1 = as captured)
    HybridUsLocal local;                // P290: the tile field measured after the row correction (local.on(): applied as well)
    // P212d (report only, no pixel depends on it): per band the median green level ratio us / base over the exposure ratio - the
    // rolling-shutter flicker profile the app fits 100 / 120 Hz to (RowFlicker, MainsFlicker); NaN where the band does not count
    std::vector<float> g;
    // factor of colour c at row u (px of the measured frame, centres at integers), linear between band centres, flat beyond
    float at(int c,float u) const {
        const float t=(u+0.5f)*float(bands)/rows-0.5f;
        if(!(t>0.f))return f[c][0];
        if(t>=float(bands-1))return f[c][bands-1];
        const int k=int(t);const float a=t-float(k);
        return f[c][k]*(1.f-a)+f[c][k+1]*a;
    }
};
// base / us: plain Bayer frames w x h (sensor CFA `cfa`, black of the 2x2 phase), us at `expected` x the base's exposure.
constexpr float kUsRowsBandMad=0.12f;
inline HybridUsRows hybridUltrashortRows(const uint16_t* base,const uint16_t* us,int w,int h,int cfa,const std::array<float,4>& black,
                                         float white,float expected,float minDev){
    HybridUsRows r;
    if(!base||!us||w<64||h<64||!(expected>0.f)||!(white>0.f))return r;
    const int nb=std::clamp(h/32,4,64),tile=16;
    r.bands=nb;r.rows=float(h);
    const float clip=0.98f*white;
    const double range=double(white)-black[0];
    // per band: the tiles' level ratio g (green) and colour ratios (R / g, B / g)
    std::vector<std::array<std::vector<float>,3>> ratios(nb);
    mergeRowBands(nb,[&](int k0,int k1){
        for(int k=k0;k<k1;++k){
            const int yb0=int(int64_t(k)*h/nb)&~1,yb1=k+1==nb?h:int(int64_t(k+1)*h/nb)&~1;
            for(int y0=yb0;y0+tile<=yb1;y0+=tile)for(int x0=0;x0+tile<=w;x0+=tile){
                double sb[3]{},so[3]{};int cnt[3]{};bool clipped=false;
                for(int y=y0;y<y0+tile&&!clipped;++y)for(int x=x0;x<x0+tile;++x){
                    const size_t i=size_t(y)*w+x;
                    if(base[i]>=clip||us[i]>=clip){clipped=true;break;}
                    const int p=((y&1)<<1)|(x&1),c=p==cfa?0:p==(cfa^3)?2:1;
                    sb[c]+=double(base[i])-black[p];so[c]+=double(us[i])-black[p];++cnt[c];
                }
                if(clipped)continue;
                float q[3];bool ok=true;
                for(int c=0;c<3&&ok;++c){
                    if(!cnt[c]){ok=false;break;}
                    const double mb=sb[c]/cnt[c],mo=so[c]/cnt[c],sig=0.5*(mb+mo/expected);
                    if(mb<=0.0||mo<=0.0||sig*std::min(1.f,expected)<8.0||sig*std::max(1.f,expected)>0.8*range){ok=false;break;}
                    q[c]=float(mo/mb);
                    if(q[c]<0.25f*expected||q[c]>4.f*expected)ok=false;
                }
                if(!ok)continue;
                ratios[k][1].push_back(q[1]);ratios[k][0].push_back(q[0]/q[1]);ratios[k][2].push_back(q[2]/q[1]);
            }
        }
    });
    auto median=[](std::vector<float> v){std::nth_element(v.begin(),v.begin()+v.size()/2,v.end());return v[v.size()/2];};
    auto mad=[&](const std::vector<float>& v,float m){std::vector<float> d(v.size());for(size_t i=0;i<v.size();++i)d[i]=std::abs(v[i]-m);return median(d)/std::max(m,1e-6f);};
    for(auto& band:ratios)r.tiles+=long(band[1].size());
    if(r.tiles<64)return r;
    const float M=expected; // the ratio the merge normalises with (gainMeasured decided it on the stream frames before this)
    std::array<std::vector<float>,3> med;for(auto& m:med)m.assign(nb,0.f);
    std::vector<int> have;
    for(int k=0;k<nb;++k){
        if(ratios[k][1].size()<24)continue;
        bool agree=true;
        for(int c=0;c<3;++c){med[c][k]=median(ratios[k][c]);agree=agree&&mad(ratios[k][c],med[c][k])<=kUsRowsBandMad;}
        if(agree)have.push_back(k);
    }
    for(int c=0;c<3;++c)r.measuredBands[c]=int(have.size());
    r.g.assign(nb,std::numeric_limits<float>::quiet_NaN()); // P212d: the profile line (hybridUsRowsCheck)
    for(int k:have)r.g[k]=med[1][k]/M;
    if(2*int(have.size())<nb)return r;
    r.measured=true;
    std::array<std::vector<float>,3> q;for(auto& v:q)v.assign(nb,1.f);
    for(int c=0;c<3;++c)for(int k=0;k<nb;++k){
        // bands that do not count: interpolated between the nearest that do, flat beyond them
        auto hi=std::lower_bound(have.begin(),have.end(),k);
        if(hi!=have.end()&&*hi==k)q[c][k]=med[c][k];
        else if(hi==have.begin())q[c][k]=med[c][*hi];
        else if(hi==have.end())q[c][k]=med[c][have.back()];
        else {const int a=*(hi-1),b=*hi;q[c][k]=med[c][a]+(med[c][b]-med[c][a])*float(k-a)/float(b-a);}
    }
    for(auto& v:r.f)v.assign(nb,1.f);
    for(int k=0;k<nb;++k){
        r.f[1][k]=M/q[1][k];r.f[0][k]=r.f[1][k]/q[0][k];r.f[2][k]=r.f[1][k]/q[2][k];
        for(int c=0;c<3;++c)r.maxDev=std::max(r.maxDev,std::abs(r.f[c][k]-1.f));
    }
    r.apply=r.maxDev>=minDev;
    return r;
}
// The correction of one value of a binned frame or sub-frame (black bl of its colour block): a clipped value (>= white - 1, the
// binning's clip) stays; a corrected one is at most white (it then reads as clipped, as a clipped site of the binning).
inline uint16_t hybridUsRowFix(uint16_t v,float bl,float white,float f){
    if(float(v)>=white-1.f)return v;
    return uint16_t(std::lround(std::clamp(bl+(float(v)-bl)*f,0.f,white)));
}
// Frame w x h (plain Bayer of the binned grid, CFA `cfa`) in place; its row J sits at row J + shift of the profile's frame.
inline void hybridUsRowsApply(const HybridUsRows& r,uint16_t* frame,int w,int h,int cfa,const std::array<float,4>& black,float white,float shift){
    mergeRowBands(h,[&](int j0,int j1){
        for(int J=j0;J<j1;++J){
            float g[3];for(int c=0;c<3;++c)g[c]=r.at(c,float(J)+shift);
            uint16_t* row=frame+size_t(J)*w;
            for(int I=0;I<w;++I){
                const int p=((J&1)<<1)|(I&1),c=p==cfa?0:p==(cfa^3)?2:1;
                row[I]=hybridUsRowFix(row[I],black[p],white,g[c]);
            }
        }
    });
}
// P290: the local field in place; the frame's pixel (I, J) sits at (I + sx, J + sy) of the field's frame.
inline void hybridUsLocalApply(const HybridUsLocal& l,uint16_t* frame,int w,int h,int cfa,const std::array<float,4>& black,float white,
                               float sx,float sy){
    if(!l.on())return;
    mergeRowBands(h,[&](int j0,int j1){
        for(int J=j0;J<j1;++J){
            uint16_t* row=frame+size_t(J)*w;
            for(int I=0;I<w;++I){
                const int p=((J&1)<<1)|(I&1),c=p==cfa?0:p==(cfa^3)?2:1;
                row[I]=hybridUsRowFix(row[I],black[p],white,l.at(c,float(I)+sx,float(J)+sy));
            }
        }
    });
}
struct HybridUsCheck { bool corrected=false,refused=false; HybridUsRows rows; };
// Steps 1-3 above for ultrashort frame f of `input` (the mosaic burst), whose binned frame `us` (corrected in place) pairs with the
// binned base `base` (w x h, the input's CFA, black and white). `tag` starts the report lines.
inline HybridUsCheck hybridUsRowsCheck(const HybridInput& input,int f,const uint16_t* base,uint16_t* us,int w,int h,const HybridTuning& t,
                                      const std::string& tag,const std::function<void(const std::string&)>& report){
    HybridUsCheck res;
    if(!t.mosaicUsRows||f<=0||f>=int(input.frames.size()))return res;
    const float expected=input.frames[f].exposure;
    res.rows=hybridUltrashortRows(base,us,w,h,input.cfa,input.black,input.white,expected,std::max(0.f,t.mosaicUsRowsMin));
    const HybridUsRows& r=res.rows;
    char line[400];
    if(!r.measured)std::snprintf(line,sizeof(line),"%s: ultrashort frame %d rows against the base: no row profile (%ld tiles; %d of %d bands with agreeing tiles)",
        tag.c_str(),f,r.tiles,r.measuredBands[1],r.bands);
    else {
        float lo[3],hi[3];
        for(int c=0;c<3;++c){lo[c]=*std::min_element(r.f[c].begin(),r.f[c].end());hi[c]=*std::max_element(r.f[c].begin(),r.f[c].end());}
        std::snprintf(line,sizeof(line),"%s: ultrashort frame %d rows against the base: %d bands (%d agreeing), %ld tiles, factors R x%.3f..%.3f G x%.3f..%.3f B x%.3f..%.3f (largest %.1f %%) -> %s",
            tag.c_str(),f,r.bands,r.measuredBands[1],r.tiles,lo[0],hi[0],lo[1],hi[1],lo[2],hi[2],100.0*r.maxDev,r.apply?"corrected (rolling-shutter flicker)":"as captured (within the limit)");
    }
    if(report)report(line);
    // P212d: the green profile of a measured frame for the app's flicker vote (RowFlicker: 100 / 120 Hz at the rolling shutter
    // skew), "-" for a band that does not count; a report line only
    if(report&&r.measured&&int(r.g.size())==r.bands){
        std::string p=tag+": ultrashort frame "+std::to_string(f)+" row profile "+std::to_string(r.bands)+" bands G";
        char v[24];
        for(float g:r.g){if(std::isfinite(g)){std::snprintf(v,sizeof(v)," %.4f",g);p+=v;}else p+=" -";}
        report(p);
    }
    if(r.apply){hybridUsRowsApply(r,us,w,h,input.cfa,input.black,input.white,0.f);res.corrected=true;}
    // P290: what the rows leave, tile by tile
    if(t.usLocal){
        HybridUsLocal& l=res.rows.local;
        l=hybridUltrashortLocal(base,us,w,h,input.cfa,input.black,input.white,expected,std::max(0.f,t.mosaicUsRowsMin),t.usLocalAlign!=0);
        if(l.tiles>=64){
            float lo[3],hi[3];
            for(int c=0;c<3;++c){lo[c]=*std::min_element(l.f[c].begin(),l.f[c].end());hi[c]=*std::max_element(l.f[c].begin(),l.f[c].end());}
            std::snprintf(line,sizeof(line),"%s: ultrashort frame %d tiles against the base: %ld of %dx%d measured%s, factors R x%.3f..%.3f G x%.3f..%.3f B x%.3f..%.3f, near the clipped base %.1f %% -> %s",
                tag.c_str(),f,l.tiles,l.gw,l.gh,t.usLocalAlign?(" at the shift ("+std::to_string(int(l.ox))+", "+std::to_string(int(l.oy))+") px, against the frame's level").c_str():"",
                lo[0],hi[0],lo[1],hi[1],lo[2],hi[2],100.0*l.maxDev,l.apply?"corrected (lamps on other flicker phases)":"as captured (within the limit)");
        } else std::snprintf(line,sizeof(line),"%s: ultrashort frame %d tiles against the base: %ld measured (too few)",tag.c_str(),f,l.tiles);
        if(report)report(line);
        if(l.on()){hybridUsLocalApply(l,us,w,h,input.cfa,input.black,input.white,0.f,0.f);res.corrected=true;}
    }
    // the merge's own gain check on the pair as it will be merged
    HybridInput pair;pair.w=w;pair.h=h;pair.cfa=input.cfa;pair.white=input.white;pair.black=input.black;
    HybridFrame b0=input.frames[0];b0.raw=base;HybridFrame u=input.frames[f];u.raw=us;
    pair.frames.push_back(b0);pair.frames.push_back(u);
    // P268: the mosaic paths measure on smooth tiles (the plain path as before)
    const HybridGain g=hybridMeasuredGain(pair,1,t.mosaicUsSmooth!=0&&tag.rfind("HYBRID MOSAIC",0)==0);
    if(g.tiles>=kGainMinTiles&&!g.ok){
        res.refused=true;
        std::snprintf(line,sizeof(line),"%s: ultrashort frame %d refused: ratios to the base inconsistent%s (measured %.4f, mad %.1f %% on %d tiles): no Bento from it, the clipped base stays (highlight recovery)",
            tag.c_str(),f,res.corrected?" after the correction":"",g.measured,100.0*g.mad,g.tiles);
        if(report)report(line);
    } else if(res.corrected&&report){
        if(g.tiles>=kGainMinTiles)std::snprintf(line,sizeof(line),"%s: ultrashort frame %d after the correction: measured %.4f, mad %.1f %% on %d tiles -> consistent",tag.c_str(),f,g.measured,100.0*g.mad,g.tiles);
        else std::snprintf(line,sizeof(line),"%s: ultrashort frame %d after the correction: %d tiles (too few for the gain check)",tag.c_str(),f,g.tiles);
        report(line);
    }
    return res;
}

// P308: the ultrashort frames of a mosaic burst checked at once, each its own binned frame `us[i]` against the shared base (the small
// tile grids of hybridUltrashortLocal leave most cores idle frame by frame: 0.8 s for three frames of a 50 MP shot): the same
// checks and results, their report lines in frame order.
inline std::vector<HybridUsCheck> hybridUsRowsChecks(const HybridInput& input,const std::vector<int>& frames,const std::vector<uint16_t*>& us,
                                                    const uint16_t* base,int w,int h,const HybridTuning& t,const std::string& tag,
                                                    const std::function<void(const std::string&)>& report){
    const size_t n=frames.size();
    std::vector<HybridUsCheck> out(n);std::vector<std::vector<std::string>> lines(n);
    {
        std::vector<std::future<void>> jobs;
        auto run=[&](size_t i){out[i]=hybridUsRowsCheck(input,frames[i],base,us[i],w,h,t,tag,[&lines,i](const std::string& l){lines[i].push_back(l);});};
        for(size_t i=0;i+1<n;++i){
            try{jobs.push_back(std::async(std::launch::async,run,i));}catch(const std::system_error&){run(i);}
        }
        if(n>0)run(n-1);
        for(auto& j:jobs)j.get();
    }
    if(report)for(const auto& v:lines)for(const auto& l:v)report(l);
    return out;
}

// P310: the largest deviation of the ultrashort frames' tile field near the clip over checks (HybridInput::usFlicker; -1 = none)
inline float hybridUsFlicker(const std::vector<HybridUsCheck>& cs){
    float v=-1.f;
    for(const auto& c:cs){
        if(c.rows.local.tiles>=64)v=std::max(v,c.rows.local.maxDev);
        else if(c.rows.apply)v=std::max(v,c.rows.maxDev);
    }
    return v;
}

// The plain Bayer burst's ultrashort frames through steps 1-3 above (owner, 2026-10-19: «исправь это во всех режимах»), on the
// full-resolution frames against the base: measured first in place (the frames are read-only, mapped); a frame copied only when
// its rows need the correction, and above kPlainUsCopyPixels (a 200 MP frame) such a frame is refused instead of copied. The
// returned input points the corrected frames at `own` (kept alive by the caller) and leaves the refused ones out; frames whose
// rows agree are untouched (the merge as before).
constexpr int64_t kPlainUsCopyPixels=int64_t(64)<<20;
inline HybridInput hybridPlainUsRows(const HybridInput& input,const HybridTuning& t,std::vector<std::vector<uint16_t>>& own,
                                     const std::function<void(const std::string&)>& report){
    HybridInput out=input;out.usRowsChecked=true;
    if(!t.mosaicUsRows||input.frames.size()<2)return out;
    const int w=input.w,h=input.h;
    const size_t pix=size_t(w)*size_t(h);
    const uint16_t* base=input.frames[0].raw;
    std::vector<bool> drop(input.frames.size(),false);
    char line[400];
    for(size_t f=1;f<input.frames.size();++f){
        if(input.frames[f].role!=kRoleUltrashort||!input.frames[f].raw)continue;
        const HybridUsRows r=hybridUltrashortRows(base,input.frames[f].raw,w,h,input.cfa,input.black,input.white,
                                                  input.frames[f].exposure,std::max(0.f,t.mosaicUsRowsMin));
        // P290: rows within the limit, tiles not (lamps on other phases): corrected as well, through the copy below
        bool tilesOff=false;
        if(!r.apply&&t.usLocal){
            const HybridUsLocal lc=hybridUltrashortLocal(base,input.frames[f].raw,w,h,input.cfa,input.black,input.white,
                                                         input.frames[f].exposure,std::max(0.f,t.mosaicUsRowsMin),t.usLocalAlign!=0);
            tilesOff=lc.on();
            if(lc.tiles>=64)out.usFlicker=std::max(out.usFlicker,lc.maxDev);
        }
        if(!r.apply&&!tilesOff){
            // within the limit (or no profile): the merge's own gain check as the mosaic paths do on the frame as captured
            HybridInput pair;pair.w=w;pair.h=h;pair.cfa=input.cfa;pair.white=input.white;pair.black=input.black;
            pair.frames.push_back(input.frames[0]);pair.frames.push_back(input.frames[f]);
            const HybridGain g=hybridMeasuredGain(pair,1);
            if(r.measured){
                std::snprintf(line,sizeof(line),"HYBRID PLAIN: ultrashort frame %d rows against the base: %d bands (%d agreeing), largest %.1f %% -> as captured",
                    int(f),r.bands,r.measuredBands[1],100.0*r.maxDev);
                if(report)report(line);
            }
            if(g.tiles>=kGainMinTiles&&!g.ok){
                drop[f]=true;
                std::snprintf(line,sizeof(line),"HYBRID PLAIN: ultrashort frame %d refused: ratios to the base inconsistent (measured %.4f, mad %.1f %% on %d tiles): no Bento from it, the clipped base stays (highlight recovery)",
                    int(f),g.measured,100.0*g.mad,g.tiles);
                if(report)report(line);
            }
            continue;
        }
        if(int64_t(pix)>kPlainUsCopyPixels){
            drop[f]=true;
            std::snprintf(line,sizeof(line),"HYBRID PLAIN: ultrashort frame %d against the base: %s on a %lld MP frame: refused, the clipped base stays",
                int(f),r.apply?"rows off (rolling-shutter flicker)":"tiles off (lamps on other flicker phases)",(long long)(pix>>20));
            if(report)report(line);
            continue;
        }
        own.emplace_back(input.frames[f].raw,input.frames[f].raw+pix);
        const HybridUsCheck c=hybridUsRowsCheck(input,int(f),base,own.back().data(),w,h,t,"HYBRID PLAIN",report);
        if(c.rows.local.tiles>=64)out.usFlicker=std::max(out.usFlicker,c.rows.local.maxDev);
        else if(c.rows.apply)out.usFlicker=std::max(out.usFlicker,c.rows.maxDev); // rolling-shutter flicker: lamps on other phases
        if(c.refused)drop[f]=true;
        else out.frames[f].raw=own.back().data();
    }
    std::vector<HybridFrame> kept;
    for(size_t f=0;f<out.frames.size();++f)if(!drop[f])kept.push_back(out.frames[f]);
    out.frames=std::move(kept);
    return out;
}

// The merge. `alignment` returns one backward homography per slot of a 7-slot Burst (slot 0 = reference);
// frames beyond six are aligned in groups like the extra ZSL frames of the SCAM path.
struct HybridPresetAlignment { std::vector<BackwardHomography> h; std::vector<bool> aligned; };
inline std::vector<float> hybridReconstructMosaic(const HybridInput& input,int block,const HybridTuning& tune,const ScamAlignment& alignment,
                                                  const std::function<void(const std::string&)>& report,std::vector<uint16_t>* mergedDng,
                                                  std::vector<uint8_t>* effMap,HybridStats* statsOut,std::vector<uint8_t>* clipFlags);
inline int hybridMosaicBlock(const HybridInput& input,const HybridTuning& tune,const std::function<void(const std::string&)>& report);
inline std::vector<float> hybridReconstructMosaicNative(const HybridInput& input,int block,const HybridTuning& tune,const ScamAlignment& alignment,
                                                        const std::function<void(const std::string&)>& report,std::vector<uint16_t>* mergedDng,
                                                        std::vector<uint8_t>* effMap,HybridStats* statsOut,std::vector<uint8_t>* clipFlags);
inline std::vector<float> hybridReconstructMosaicBinned(const HybridInput& input,int block,const HybridTuning& tune,const ScamAlignment& alignment,
                                                        const std::function<void(const std::string&)>& report,std::vector<uint16_t>* mergedDng,
                                                        std::vector<uint8_t>* effMap,HybridStats* statsOut,std::vector<uint8_t>* clipFlags);
// P195: a w x h stream of colour block b bins into plain Bayer (whole 2b x 2b periods, at least 64 binned px a side). The app
// never asks for it since P267 (ScamHybridBurst.dngOnlyBlock: such a shot is merged with mosaicRemosaic 1 and dngMosaic 1).
inline bool hybridMosaicBinnable(int w,int h,int b){
    return (b==2||b==4)&&w%(2*b)==0&&h%(2*b)==0&&w/b>=64&&h/b>=64;
}

// native (P29): the raw mosaic behind a binned burst (hybridReconstructMosaicNative only); null for every other merge.
// P71: noise of the burst against the frames' noise model (owner's Pixel 7, 2x = full-resolution crop of the main sensor: merge
// factors 0.55-0.69 on every frame where 1x had 0.93, the HAL profile 4.1e-5 next to 3.6e-5 of the binned 1x). One green phase of
// the base and of the normal frame nearest in time, 32x32-site blocks: temporal noise = var(base - frame) / 2 (the block's mean
// difference removed), the block counts as flat when the base's own variance is at most 1.5 x that (texture, edges and motion
// raise it far above). Ratio = temporal noise / model (slope x level + offset, mean of both frames); the median of the flat blocks.
// P74 / P118 (Sabre 2x only when it helps, owner 2026-10-10 / 13; idea from Jiangtherapee Super Resolution's "sampling distances / phase redundancy", no
// code taken): how densely the merged frames' sites of one colour cover the CFA period, in sensor px. For every donor its offset to
// the base at 3x3 points of the frame (homography; the F6 field is not known yet), the sites of the colour of all frames brought to
// base positions modulo the colour period 2b (b = colour block: 1 Bayer, 2 Quad, 4 Tetra; binned coordinates x b for a mosaic), and
// the fill distance = the largest distance from any point of the period to the nearest site (median of the 3x3 points). One Bayer
// frame: green 1 px; one Tetra frame: green 2.5 px; well-spread frames: a fraction of a pixel.
struct GridCoverage { double green=0,red=0,greenSingle=0,redSingle=0; int frames=0; };
inline double gridFillDistance(const std::vector<std::array<float,2>>& offsets,int b,bool green){
    const int P=2*b;
    std::vector<std::array<float,2>> pts;
    for(const auto& o:offsets)for(int y=0;y<P;++y)for(int x=0;x<P;++x){
        const int bx=x/b,by=y/b;const bool g=((bx+by)&1)!=0;
        if(green?!g:(bx!=0||by!=0))continue; // canonical RGGB blocks: red (0,0), green (1,0) / (0,1)
        float px=std::fmod(float(x)-o[0],float(P)),py=std::fmod(float(y)-o[1],float(P));
        if(px<0)px+=float(P);
        if(py<0)py+=float(P);
        pts.push_back({px,py});
    }
    if(pts.empty())return double(P);
    const int steps=32;double worst=0;
    for(int j=0;j<steps;++j)for(int i=0;i<steps;++i){
        const float ex=(i+0.5f)*float(P)/steps,ey=(j+0.5f)*float(P)/steps;
        float best=1e9f;
        for(const auto& p:pts){
            float dx=std::abs(ex-p[0]),dy=std::abs(ey-p[1]);
            dx=std::min(dx,float(P)-dx);dy=std::min(dy,float(P)-dy);
            best=std::min(best,dx*dx+dy*dy);
        }
        worst=std::max(worst,double(best));
    }
    return std::sqrt(worst);
}
// local(f, x, y): the F6 offset of frame f at (x, y), stream px (the Tetra native path aligns its binned frames almost entirely there)
inline GridCoverage gridCoverage(const std::vector<BackwardHomography>& H,const std::vector<int>& donors,int w,int h,int b,
                               const std::function<std::array<float,2>(int,int,int)>& local){
    GridCoverage c;c.frames=int(donors.size())+1;
    std::vector<double> g,r;
    for(int sy=1;sy<=3;++sy)for(int sx=1;sx<=3;++sx){
        const int x=w*sx/4,y=h*sy/4;
        std::vector<std::array<float,2>> off{{0.f,0.f}};
        for(int f:donors){
            const DonorPoint p=H[size_t(f)].project(x,y);
            const float up=H[size_t(f)].upRatio>0?H[size_t(f)].upRatio:1.f;
            const std::array<float,2> l=local?local(f,x,y):std::array<float,2>{0.f,0.f};
            off.push_back({(p.x/up-float(x)+l[0])*float(b),(p.y/up-float(y)+l[1])*float(b)});
        }
        g.push_back(gridFillDistance(off,b,true));r.push_back(gridFillDistance(off,b,false));
    }
    std::nth_element(g.begin(),g.begin()+4,g.end());std::nth_element(r.begin(),r.begin()+4,r.end());
    c.green=g[4];c.red=r[4];
    const std::vector<std::array<float,2>> base{{0.f,0.f}};
    c.greenSingle=gridFillDistance(base,b,true);c.redSingle=gridFillDistance(base,b,false);
    return c;
}

struct HybridNoiseCheck {
    double ratio=0; long flat=0,blocks=0; int frame=-1;
    std::vector<std::array<float,2>> dcgBlocks; // P320 a DCG burst: (mean, noise) of every flat block, for the two-zone fit
};
inline HybridNoiseCheck hybridMeasureNoise(const HybridInput& in){
    HybridNoiseCheck res;
    const int n=int(in.frames.size());
    for(int f=1;f<n;++f)if(in.frames[f].role==kRoleNormal&&(res.frame<0||std::abs(in.frames[f].orderMs)<std::abs(in.frames[res.frame].orderMs)))res.frame=f;
    if(res.frame<0||in.w<64||in.h<64)return res;
    const HybridFrame& a=in.frames[0];const HybridFrame& b=in.frames[res.frame];
    const int phase=in.cfa^1,px=phase&1,py=phase>>1; // a green phase (red = cfa, blue = cfa ^ 3)
    const float black=in.black[phase],range=std::max(in.white-black,1.f);
    const double slope=0.5*(double(a.slope)+b.slope),offset=0.5*(double(a.offset)+b.offset);
    const int bw=in.w/64,bh=in.h/64; // 32x32 sites of the phase
    std::vector<float> ratio(size_t(bw)*bh,-1.f);
    std::vector<std::array<float,2>> dcgAt(in.dcg?size_t(bw)*bh:0); // P320: the flat blocks' (mean, noise), DCG only
    mergeRowBands(bh,[&](int y0,int y1){
        for(int by=y0;by<y1;++by)for(int bx=0;bx<bw;++bx){
            double s0=0,s00=0,sd=0,sdd=0;bool bad=false;
            for(int j=0;j<32&&!bad;++j){
                const size_t row=size_t(64*by+2*j+py)*in.w;
                for(int i=0;i<32;++i){
                    const size_t at=row+64*bx+2*i+px;
                    const double v0=(double(a.raw[at])-black)/range,v1=(double(b.raw[at])-black)/range;
                    if(v0>=0.9||v1>=0.9){bad=true;break;}
                    s0+=v0;s00+=v0*v0;const double d=v0-v1;sd+=d;sdd+=d*d;
                }
            }
            if(bad)continue;
            const double m=s0/1024,var0=s00/1024-m*m,noise=0.5*(sdd/1024-(sd/1024)*(sd/1024));
            const double model=slope*std::max(m,0.0)+offset;
            if(m<=0.002||model<=0||noise<=0||var0>1.5*noise)continue;
            ratio[size_t(by)*bw+bx]=float(noise/model);
            if(in.dcg)dcgAt[size_t(by)*bw+bx]={float(m),float(noise)};
        }
    });
    std::vector<float> flat;
    for(float r:ratio)if(r>0)flat.push_back(r);
    if(in.dcg)for(size_t i=0;i<ratio.size();++i)if(ratio[i]>0)res.dcgBlocks.push_back(dcgAt[i]);
    res.blocks=long(bw)*bh;res.flat=long(flat.size());
    if(flat.empty())return res;
    std::nth_element(flat.begin(),flat.begin()+flat.size()/2,flat.end());
    res.ratio=flat[flat.size()/2];
    return res;
}

// P150: donors (homographies 1.., 0 is the base) that moved at least `shift` RAW px against the base at the corners or the centre
// of a w x h frame; *largest gets the largest move.
inline int hybridMovedDonors(const std::vector<BackwardHomography>& homography,int w,int h,float shift,float* largest=nullptr){
    int moved=0;float most=0.f;
    for(size_t i=1;i<homography.size();++i){
        const BackwardHomography& hm=homography[i];float d=0.f;
        for(const auto& q:{std::pair<int,int>{0,0},{w-2,0},{0,h-2},{w-2,h-2},{w/2,h/2}}){
            const DonorPoint pt=hm.project(q.first,q.second);
            d=std::max(d,std::hypot(pt.x/hm.upRatio-float(q.first),pt.y/hm.upRatio-float(q.second)));
        }
        most=std::max(most,d);
        if(d>=shift)++moved;
    }
    if(largest)*largest=most;
    return moved;
}
// P335: IEEE half -> float (the inverse of floatToHalf, every half value exact)
inline float hybridHalfToFloat(uint16_t h){
    const uint32_t s=uint32_t(h&0x8000u)<<16,e=(h>>10)&0x1fu,m=h&0x3ffu;
    uint32_t bits;
    if(e==0){
        if(m==0)bits=s;
        else{int k=-1;uint32_t mm=m;do{++k;mm<<=1;}while(!(mm&0x400u));bits=s|(uint32_t(127-15-k)<<23)|((mm&0x3ffu)<<13);}
    }else if(e==31)bits=s|0x7f800000u|(m<<13);
    else bits=s|((e+112u)<<23)|(m<<13);
    float f;std::memcpy(&f,&bits,4);return f;
}
// P150: a still burst - fewer than a quarter of its donors moved (the rest sample the base's sites)
inline bool hybridStillBurst(int moved,int donors){return donors>0&&moved*4<donors;}
struct MosaicChromaPasses;
inline void mosaicChromaMedian(std::vector<float>& rgb,int w,int h,int block,const std::vector<float>* osc,bool gpu,std::string* how,
                               int bandRows,MosaicChromaPasses* passes); // defined with the mosaic merges (P269: the plain trial)
// P310: the ultrashort blend weight per 2x2 cell of the base frame (HybridTuning::bentoBlend): the cell's brightest site (the
// frame's own black / white, no white balance) through smoothstep(lo, hi), a diamond max of bentoBlendDilate cells, a gaussian of
// bentoBlendSigma cells, times smoothstep(0.08, 0.45) of the blurred near-clip share (smoothstep(0.70, 0.88) of the cell: the
// dilation does not reach onto a dark border), times the best ultrashort frame's validity on the cells the base did not clip (none
// valid: the base stays; a clipped cell has nothing better than the ultrashort frames), a light gaussian last (the validity is per
// cell).
inline std::vector<float> hybridBlendWeight(const HybridInput& input,const HybridTuning& t,
                                            const std::vector<const std::vector<float>*>& valid){
    const int w2=input.w/2,h2=input.h/2;
    const size_t cells=size_t(w2)*h2;
    std::vector<float> lvl(cells,0.f);
    const uint16_t* raw=input.frames[0].raw;
    float inv[4];for(int k=0;k<4;++k)inv[k]=1.f/std::max(input.white-input.black[k],1.f);
    // canonical (RGGB-origin) cells as the GPU and the check planes use them: the CFA shift, the last column / row reflected
    const int sx=input.cfa&1,sy=input.cfa>>1;
    mergeRowBands(h2,[&](int y0,int y1){
        for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
            float m=0.f;
            for(int p=0;p<4;++p){
                int X=2*cx+(p&1)+sx,Y=2*cy+(p>>1)+sy;
                if(X>=input.w)X=2*(input.w-1)-X;
                if(Y>=input.h)Y=2*(input.h-1)-Y;
                const int q=((Y&1)<<1)|(X&1);
                const float v=(float(raw[size_t(Y)*input.w+X])-input.black[q])*inv[q];
                m=std::max(m,v);
            }
            lvl[size_t(cy)*w2+cx]=m;
        }
    });
    auto smooth=[](float a,float b,float x){const float u=std::clamp((x-a)/std::max(b-a,1e-6f),0.f,1.f);return u*u*(3.f-2.f*u);};
    auto gauss=[&](std::vector<float>& v,float sigma){
        if(sigma<=0.05f)return;
        const int r=std::max(1,int(std::ceil(3.f*sigma)));
        std::vector<float> k(size_t(2*r+1));float sum=0;
        for(int i=-r;i<=r;++i){k[size_t(i+r)]=std::exp(-0.5f*float(i*i)/(sigma*sigma));sum+=k[size_t(i+r)];}
        for(float& x:k)x/=sum;
        std::vector<float> tmp(cells);
        mergeRowBands(h2,[&](int y0,int y1){
            for(int y=y0;y<y1;++y)for(int x=0;x<w2;++x){
                float s=0;for(int i=-r;i<=r;++i)s+=k[size_t(i+r)]*v[size_t(y)*w2+std::clamp(x+i,0,w2-1)];
                tmp[size_t(y)*w2+x]=s;
            }
        });
        mergeRowBands(h2,[&](int y0,int y1){
            for(int y=y0;y<y1;++y)for(int x=0;x<w2;++x){
                float s=0;for(int i=-r;i<=r;++i)s+=k[size_t(i+r)]*tmp[size_t(std::clamp(y+i,0,h2-1))*w2+x];
                v[size_t(y)*w2+x]=s;
            }
        });
    };
    std::vector<float> a(cells),near(cells);
    std::vector<uint8_t> core(cells);
    for(size_t i=0;i<cells;++i){a[i]=smooth(t.bentoBlendLo,t.bentoBlendHi,lvl[i]);near[i]=smooth(0.70f,0.88f,lvl[i]);core[i]=lvl[i]>=0.98f;}
    std::vector<float>().swap(lvl);
    for(int it=0;it<std::clamp(t.bentoBlendDilate,0,16);++it){ // diamond: one 4-neighbour max per step
        std::vector<float> d(a);
        mergeRowBands(h2,[&](int y0,int y1){
            for(int y=y0;y<y1;++y)for(int x=0;x<w2;++x){
                const size_t i=size_t(y)*w2+x;float m=a[i];
                if(x>0)m=std::max(m,a[i-1]);
                if(x+1<w2)m=std::max(m,a[i+1]);
                if(y>0)m=std::max(m,a[i-w2]);
                if(y+1<h2)m=std::max(m,a[i+w2]);
                d[i]=m;
            }
        });
        a.swap(d);
    }
    gauss(a,t.bentoBlendSigma);
    gauss(near,t.bentoBlendSigma);
    std::vector<float> best(cells,valid.empty()?1.f:0.f);
    for(const auto* v:valid){
        if(!v||v->size()!=cells){std::fill(best.begin(),best.end(),1.f);break;}
        for(size_t i=0;i<cells;++i)best[i]=std::max(best[i],(*v)[i]);
    }
    // the validity only where the base has data: on its clipped cells nothing better than the ultrashort frames exists (their 2-3 px
    // residual on an LED line made the check refuse whole edges, the N frames' clipped steps then showed)
    for(size_t i=0;i<cells;++i){
        const float v=core[i]?1.f:std::clamp(best[i],0.f,1.f);
        a[i]=std::clamp(a[i]*smooth(0.08f,0.45f,near[i])*v,0.f,1.f);
    }
    gauss(a,0.75f);
    for(float& v:a)v=v<0.002f?0.f:std::min(v,1.f);
    return a;
}

inline std::vector<float> hybridReconstruct(const HybridInput& input,const HybridTuning& tune,
                                            const ScamAlignment& alignment,
                                            const std::function<void(const std::string&)>& report,
                                            std::vector<uint16_t>* mergedDng,std::vector<uint8_t>* effMap,
                                            HybridStats* statsOut=nullptr,std::vector<uint8_t>* clipFlags=nullptr,
                                            const HybridPresetAlignment* preset=nullptr,const HybridMosaicNative* native=nullptr) {
    if(tune.noiseMeasured>0&&input.mosaic==1&&input.subFrames<=1&&!native&&!preset){
        // P71: plain Bayer only (one green phase = every other site; a Quad / Tetra stream mixes colours there), decided once.
        // Above x6 the model is kept: no HAL profile seen was that far off, a mismatch that large is a different header model
        // (the synthetic replay series: x40..x400) or a measurement fault. Below x1/50 as well (identical frames, a frame copied twice).
        const HybridNoiseCheck c=hybridMeasureNoise(input);
        HybridTuning t2=tune;t2.noiseMeasured=-1;
        // P320 DCG (owner: never one noise curve over a DCG frame): the same flat blocks fit var = S m + (m < T ? Oh : Ol)
        // (dcgNoiseFit). Measured: every frame gets (S, Oh) in its slope / offset and (T, Ol) as dcgKnee / dcgOffsetLcg (0, 0 for one
        // zone), the header's frame-to-frame ratios kept (a burst of one model: exactly S, Oh, Ol), no single-k scaling; the factor line
        // says x1 so that the app's denoise does not scale its model again (it takes the zones from the DCG line). Not measured: the
        // single-curve check as for any burst.
        if(input.dcg){
            const DcgNoiseFit z=c.frame>=0?dcgNoiseFit(c.dcgBlocks):DcgNoiseFit{};
            if(z.measured&&z.knee<1.0){
                report("HYBRID NOISE CHECK: base vs frame "+std::to_string(c.frame)+", "+std::to_string(c.flat)+" flat of "
                    +std::to_string(c.blocks)+" blocks, measured / model "+std::to_string(c.ratio)
                    +" -> model x1 (DCG: the measured zones replace the model)");
                char line[384];
                std::snprintf(line,sizeof(line),"HYBRID NOISE CHECK DCG: slope=%.6g hcg=%.6g lcg=%.6g knee=%.6g blocks=%ld/%ld zones=%d",
                    z.slope,z.hcg,z.lcg,z.knee,z.used,c.blocks,z.zones); // blocks: the fit's inliers / the frame's blocks
                report(line);
                std::snprintf(line,sizeof(line),"HYBRID DCG NOISE FIT: one line slope=%.6g offset=%.6g; two zones slope=%.6g hcg=%.6g lcg=%.6g "
                    "knee=%.6g; residual %.4g -> %.4g%s%s",z.oneSlope,z.oneOffset,z.twoSlope,z.twoHcg,z.twoLcg,z.twoKnee,z.resOne,z.resTwo,
                    *z.why?"; one zone: ":"",z.why);
                report(line);
                const HybridFrame& a=input.frames[0];const HybridFrame& bf=input.frames[size_t(c.frame)];
                const double pairSlope=0.5*(double(a.slope)+bf.slope),pairOffset=0.5*(double(a.offset)+bf.offset);
                HybridInput measured=input;
                for(auto& fr:measured.frames){ // a header value <= 0 (the app clamps a negative HAL offset to 0): the pair's (no zero LCG offset)
                    const double rs=pairSlope>0&&fr.slope>0?fr.slope/pairSlope:1.0,ro=pairOffset>0&&fr.offset>0?fr.offset/pairOffset:1.0;
                    fr.slope=float(z.slope*rs);fr.offset=float(z.hcg*ro);
                    fr.dcgKnee=z.zones==2?float(z.knee):0.f;fr.dcgOffsetLcg=z.zones==2?float(z.lcg*ro):0.f;
                }
                return hybridReconstruct(measured,t2,alignment,report,mergedDng,effMap,statsOut,clipFlags,preset,native);
            }
            report(std::string("HYBRID NOISE CHECK DCG: not measured (")+(c.frame<0?"no normal frame to compare":z.measured?"knee out of range":z.why)
                +", "+std::to_string(c.flat)+" flat blocks): the single-curve check");
        }
        const bool up=c.ratio>=1.5&&c.ratio<=6.0,down=c.ratio>=0.02&&c.ratio<=0.67;
        const bool scale=c.flat>=100&&(up||down);
        const double k=up?std::min(c.ratio,4.0):std::max(c.ratio,1.0/16);
        if(c.frame>=0)report("HYBRID NOISE CHECK: base vs frame "+std::to_string(c.frame)+", "+std::to_string(c.flat)+" flat of "
            +std::to_string(c.blocks)+" blocks, measured / model "+std::to_string(c.ratio)
            +(scale?" -> model x"+std::to_string(k)+" for every frame":(c.ratio>6.0||c.ratio<0.02)&&c.flat>=100?" -> model kept (implausible)":" -> model kept"));
        if(scale){
            HybridInput measured=input;
            for(auto& fr:measured.frames){fr.slope*=k;fr.offset*=k;}
            return hybridReconstruct(measured,t2,alignment,report,mergedDng,effMap,statsOut,clipFlags,preset,native);
        }
        return hybridReconstruct(input,t2,alignment,report,mergedDng,effMap,statsOut,clipFlags,preset,native);
    }
    if(tune.gainMeasured>0){
        // gainMeasured 1: the measured ratio where the data is reliable (hybridApplyMeasuredGains), decided once on the stream
        // frames as they came (a colour-block mosaic before its sub-frames or binned frames: one decision for all of them, from
        // every frame of each exposure); then this merge with the decision made (gainMeasured -1: no second measurement or report).
        // A burst without bracketed / ultrashort frames skips it (nothing to decide).
        bool other=false;
        for(size_t f=1;f<input.frames.size();++f)other|=input.frames[f].role==kRoleBracketed||input.frames[f].role==kRoleUltrashort;
        if(other){
            HybridInput measured=input;
            hybridApplyMeasuredGains(measured,report);
            HybridTuning t2=tune;t2.gainMeasured=-1;
            return hybridReconstruct(measured,t2,alignment,report,mergedDng,effMap,statsOut,clipFlags,preset,native);
        }
    }
    // P14 / P15: a colour-block mosaic (sensor mode without remosaic) is merged through its plain-Bayer sub-frames; P29: or, with
    // mosaicPath 1, natively (hybridReconstructMosaicNative)
    if(input.mosaic!=1&&!input.frames.empty()){
        const int block=hybridMosaicBlock(input,tune,report);
        // P195 «Наш ремозаик» off (mosaicRemosaic 0): the colour blocks binned into plain Bayer and merged as such. The app reads a
        // W/b x H/b result only when it sent the block itself (header word 14), so a block the worker measured, or a frame that
        // does not bin whole, keeps the remosaic
        if(block>1&&tune.mosaicRemosaic==0){
            const bool known=input.mosaic==block||tune.mosaicBlock==block;
            if(known&&hybridMosaicBinnable(input.w,input.h,block))
                return hybridReconstructMosaicBinned(input,block,tune,alignment,report,mergedDng,effMap,statsOut,clipFlags);
            report(std::string("HYBRID MOSAIC BINNED: remosaic kept (")+(known?"the frame is not whole "+std::to_string(2*block)+"x"
                +std::to_string(2*block)+" colour periods":"block "+std::to_string(block)+" not sent by the app: it reads the sensor grid")+")");
        }
        // Tetra takes the native path with mosaicTetra 1 (T1, the default since P35) / 2 (T2); 0 keeps it on the split
        if(block>1&&tune.mosaicPath==1&&(block==2||tune.mosaicTetra!=0))
            return hybridReconstructMosaicNative(input,block,tune,alignment,report,mergedDng,effMap,statsOut,clipFlags);
        if(block>1&&tune.mosaicPath==1)report("HYBRID MOSAIC NATIVE: Tetra stays on the sub-frame split (mosaicTetra 0)");
        if(block>1)return hybridReconstructMosaic(input,block,tune,alignment,report,mergedDng,effMap,statsOut,clipFlags);
    }
    // Owner, 2026-10-19 («исправь это во всех режимах»): a plain Bayer burst's ultrashort frames through the row check as the mosaic
    // paths' (their binned / native / split merges arrive here checked or with their own preset / native data)
    if(!input.usRowsChecked&&!native&&!preset&&input.subFrames<=1&&tune.mosaicUsRows){
        bool anyUs=false;
        for(size_t f=1;f<input.frames.size();++f)anyUs|=input.frames[f].role==kRoleUltrashort;
        if(anyUs){
            std::vector<std::vector<uint16_t>> own;
            const HybridInput checked=hybridPlainUsRows(input,tune,own,report);
            return hybridReconstruct(checked,tune,alignment,report,mergedDng,effMap,statsOut,clipFlags,preset,native);
        }
    }
    if(tune.bentoBlend>0&&tune.bentoRamp>0.f&&native==nullptr){
        // P310: on the plain merge the blend replaces the P309 ramp (its share and K weights belong to the merge's own mixing of
        // the frames; the app sends the blend's softness from the same setting as bentoBlendSigma). The native colour-block merge
        // keeps the ramp (no blend pass there).
        HybridTuning t2=tune;t2.bentoRamp=0.f;
        t2.mosaicBlock=1; // a block above 1 has returned before here: no second detection when the header said 0
        return hybridReconstruct(input,t2,alignment,report,mergedDng,effMap,statsOut,clipFlags,preset,native);
    }
    using Clock=std::chrono::steady_clock;
    const auto started=Clock::now();
    auto millis=[](auto d){return std::chrono::duration<double,std::milli>(d).count();};
    if(input.frames.empty()||int(input.frames.size())>kHybridMaxFrames)throw std::runtime_error("HYBRID: 1.."+std::to_string(kHybridMaxFrames)+" frames");
    const int w=input.w,h=input.h;
    // Any RAW size: pixels of the stream (a mosaic's sub-frames: b^2 per stream frame). Above kHybridClassicPixels the merge follows
    // the real limits (memory, GPU strips, output grid); up to it every step is exactly as before.
    const bool large=int64_t(w)*h*std::max(1,input.subFrames)>kHybridClassicPixels;
    // A Burst view for the shared helpers (sampleRaw, guides, alignment): slot 0 = base.
    Burst b;b.w=w;b.h=h;b.cfa=input.cfa;b.white=input.white;b.black=input.black;b.canonicalRggb=true;
    for(int s=0;s<7;++s){b.raw[s]=input.frames[0].raw;b.exposure[s]=1;b.iso[s]=std::max(1u,input.frames[0].iso);}
    const int n=int(input.frames.size());
    HybridStats stats;
    // ---- P31 (shot speed, research/speed/SHOT_SPEED_PLAN.md W1.2 / W1.4 / W1.7): started before the alignment, as none of it needs
    // the alignment: the GPU context and its programs on a helper thread, every guide of the CRE groups on the shared pool and, on
    // the pool as well, the stages that read frames but no homography (gain checks, Shasta guides, the base part of the Bento mask,
    // the F6 base pyramid, the P19 RAW CA fit). Each is the same code on the same data as before, used (and reported) where it was
    // used before. Above kHybridClassicPixels nothing is built ahead (memory). SCAM_NO_PREFETCH / SCAM_NO_EARLY_GPU: replay A/B.
    const bool ahead=!large&&!std::getenv("SCAM_NO_PREFETCH");
    const bool presetAlignment=preset&&int(preset->h.size())==n&&int(preset->aligned.size())==n;
    bool anyBracketed=false,anyUltrashort=false;
    for(int f=1;f<n;++f){anyBracketed|=input.frames[f].role==kRoleBracketed;anyUltrashort|=input.frames[f].role==kRoleUltrashort;}
    // P308: where the F6 field is computed (tune laGpu: 1 GPU, 0 CPU, 2 auto). Auto takes the GPU where the CPU field could not
    // stream alongside the merge (laStreamOn below without the GPU: the whole field would come first). OPPO Find X7 Ultra replays,
    // program cache warm: the streamed CPU field 0.03-0.3 s faster than the GPU field first (it is hidden in the merge), the GPU
    // 0.03-0.9 s faster than the whole CPU field first (x7u_1x 3.95 -> 3.06 s).
    const bool laCpuStreams=tune.laStream!=0&&input.subFrames<=1&&!hybridAuto2x(input,tune,native?hybridRequestedGrid(input,tune)==2*std::max(1,native->block)
                                                                                  :hybridRequestedGrid(input,tune)==2)
        &&!std::getenv("SCAM_NO_PREFETCH")&&(!large||!std::getenv("SCAM_NO_LARGE_F6_STREAM"))&&!std::getenv("SCAM_LA_DUMP")&&!std::getenv("SCAM_NO_F6_STREAM");
    bool laGpuUse=tune.localAlign>0&&n>1&&(tune.laGpu==1||(tune.laGpu==2&&!laCpuStreams));
    if(laGpuUse&&large){
        // the GPU field holds ~11.5 B per RAW pixel from the alignment to the F6 stage (two RAW uploads, the base and donor levels):
        // within a quarter of MemAvailable as the CPU field's large path, else the CPU field
        const uint64_t need=uint64_t(11.5*double(w)*double(h)),avail=hybridMemAvailable();
        if(avail>0&&need>avail/4){
            laGpuUse=false;
            report("HYBRID LOCAL ALIGN: GPU field skipped ("+hybridMB(need)+" MB > a quarter of MemAvailable "+hybridMB(avail)+" MB): the CPU field");
        }
    }
    // the GPU F6 on a thread of its own (LaGpuWorker): context, programs and the base pyramid while the frames align, then each frame
    // as the alignment hands it over (streamFeed below); a thread the system refuses or any failure leaves the CPU field
    std::unique_ptr<LaGpuWorker> laGpuWorker;
    if(laGpuUse)try{laGpuWorker=std::make_unique<LaGpuWorker>(n,[&b,&input,&tune]{
        const float slope=std::max(input.frames[0].slope,1e-9f),offset=std::max(input.frames[0].offset,0.f);
        const float eps=std::max(offset/std::max(slope,1e-9f),1e-5f);
        const int win=std::clamp(tune.laWin,4,kLaMaxWin)&~3,stride=std::clamp(tune.laStride,2,win);
        LaImage l0,l1;l0.w=b.w/2;l0.h=b.h/2;l1.w=l0.w/2;l1.h=l0.h/2;
        const LaGrid a=laGrid(l0,win,stride,2),c=laGrid(l1,16,8,4);
        LaGpuBurst gb;gb.w=b.w;gb.h=b.h;gb.cfa=b.cfa;gb.canonical=b.canonicalRggb;gb.white=b.white;gb.black=b.black;
        LaGpuTune gt;gt.mu=tune.laMu;gt.kappa=tune.laKappa;gt.maxShift=tune.laMaxShift;gt.iters=tune.laIters;
        gt.itersCoarse=tune.laItersCoarse;gt.medians=tune.laMedian;
        auto gpu=std::make_unique<LaGpu>(hybridProgramCacheDir());
        gpu->base(gb,input.frames[0].raw,eps,hybridLaV0(input,slope,offset),LaGpuGrid{a.nx,a.ny,a.win,a.stride,a.s},LaGpuGrid{c.nx,c.ny,c.win,c.stride,c.s},gt);
        return gpu;
    });}catch(const std::exception&){laGpuWorker.reset();}
    // W1.7: the context with the programs this merge will ask for as far as the tuning tells (the local alignment as asked, the
    // Bento colour pass whenever an ultrashort frame may be merged: merge() runs a pass only when its own flags say so).
    struct EarlyGpuLog { std::mutex lock;std::vector<std::string> lines;bool direct=false;double ms=0; };
    auto earlyLog=std::make_shared<EarlyGpuLog>();
    std::future<std::unique_ptr<HybridGpu>> earlyGpu;
    if(!large&&!std::getenv("SCAM_NO_EARLY_GPU")){
        const bool rimPass=tune.rimRatio!=0,la=tune.localAlign>0&&n>1,chromaPass=tune.chromaDiff>0.f,nat=native!=nullptr;
        const bool blendPass=tune.bento>0&&anyUltrashort&&tune.bentoBlend>0&&!nat;
        const bool bentoPass=tune.bento>0&&anyUltrashort&&!blendPass&&tune.bentoChromaSigma>0.f&&tune.bentoChroma>0.f;
        const bool outlierPrograms=tune.hotSigma>0||tune.hotBaseSigma>0,dcgZones=hybridDcgZones(input); // P320
        // a thread the system refuses (std::system_error) leaves earlyGpu empty: the merge builds its context, as before P31
        try{earlyGpu=std::async(std::launch::async,[earlyLog,rimPass,la,bentoPass,chromaPass,nat,outlierPrograms,blendPass,dcgZones]{
            const auto t0=Clock::now();
            // The constructor's report: on Adreno one context line, printed where the merge reports the GPU. Off Adreno it reports
            // program by program (P26: a driver that dies while compiling): those lines go out at once, one write each on stderr
            // (it shares the worker's pipe with stdout), so the last one names the step even if the process dies.
            auto early=[earlyLog](const std::string& line){
                std::lock_guard<std::mutex> l(earlyLog->lock);
                if(earlyLog->direct||line.find("compiling program by program")!=std::string::npos){
                    earlyLog->direct=true;const std::string text=line+"\n";
                    if(::write(2,text.data(),text.size())<0){}
                    return;
                }
                earlyLog->lines.push_back(line);
            };
            auto gpu=std::make_unique<HybridGpu>(rimPass,la,bentoPass,chromaPass,early,false,nat,outlierPrograms,blendPass,dcgZones);
            gpu->release();
            earlyLog->ms=std::chrono::duration<double,std::milli>(Clock::now()-t0).count();
            return gpu;
        });}catch(const std::exception&){}
    }
    // W1.2: the CRE groups' guides (the prefetch drops what the groups did not take after the last group, or on an exception)
    struct PrefetchDrop { bool on=false; ~PrefetchDrop(){if(on&&scamAlignmentPrefetch())scamAlignmentPrefetch()(nullptr,{});} } prefetchDrop;
    if(ahead&&alignment&&!presetAlignment&&n>1&&scamAlignmentPrefetch()){
        std::vector<std::pair<const uint16_t*,float>> donors;
        for(int f=1;f<n;++f)donors.emplace_back(input.frames[f].raw,input.frames[f].exposure);
        scamAlignmentPrefetch()(&b,donors);prefetchDrop.on=true;
    }
    // W1.4: the stages that read frames but no homography, in the order the merge uses them. The task group waits for all of them
    // before this function returns (an exception included): the tasks may use b, input and tune.
    ScamTasks tasks;
    std::vector<std::future<HybridGain>> gains(n);
    for(int f=1;f<n&&tune.gainMeasured==0;++f){ // gainMeasured -1: measured and reported by the decision before this merge
        const auto& fr=input.frames[f];
        if(fr.role!=kRoleBracketed&&fr.role!=kRoleUltrashort)continue;
        if(ahead)gains[f]=tasks.run([&input,f]{return hybridMeasuredGain(input,f);});
        else gains[f]=std::async(std::launch::async,[&input,f]{return hybridMeasuredGain(input,f);});
    }
    std::shared_future<Guide> shastaBase;std::vector<std::shared_future<Guide>> shastaGuides(n);
    if(ahead&&tune.shastaEnable&&anyBracketed){ // every bracketed frame: the alignment decides later which ones are scored
        shastaBase=tasks.run([&b]{return guideLevel0(b,0);}).share();
        for(int f=1;f<n;++f)if(input.frames[f].role==kRoleBracketed)shastaGuides[f]=tasks.run([&b,&input,f]{
            Burst one=b;one.raw[1]=input.frames[f].raw;one.exposure[1]=input.frames[f].exposure;
            return guideLevel0(one,1);
        }).share();
    }
    std::shared_future<BentoBase> bentoAhead;
    if(ahead&&tune.bento>0&&anyUltrashort)bentoAhead=tasks.run([&b,&tune]{return bentoBase(b,tune);}).share();
    struct LaBaseBuilt { LaBase base;double ms=0; };
    std::future<LaBaseBuilt> laAhead;
    // P151: above kHybridClassicPixels as well (FULLRES 50 MP on the X200 Ultra: F6 1.98 s before the merge, the GPU idle): the
    // base pyramid on the pool and the field in bands alongside the merge. The stream's own MemAvailable test (windowed gray rows,
    // else the whole field first) bounds its memory. SCAM_NO_LARGE_F6_STREAM: the whole field first, as before.
    const bool largeStream=large&&!std::getenv("SCAM_NO_PREFETCH")&&!std::getenv("SCAM_NO_LARGE_F6_STREAM")&&input.subFrames<=1;
    if((ahead||largeStream)&&tune.localAlign>0&&n>1&&!laGpuUse)laAhead=tasks.run([&b,&input]{ // P308: the GPU builds its own
        const auto t0=Clock::now();
        LaBaseBuilt built;
        const float slope=std::max(input.frames[0].slope,1e-9f),offset=std::max(input.frames[0].offset,0.f); // baseSlope, baseOffset
        const float eps=std::max(offset/std::max(slope,1e-9f),1e-5f);
        laGray(b,input.frames[0].raw,1.f,eps,built.base.l0,true);
        laDown(built.base.l0,built.base.l1,true);laBaseLevel(built.base.l1,true);laBaseLevel(built.base.l0,true);
        built.ms=std::chrono::duration<double,std::milli>(Clock::now()-t0).count();
        return built;
    });
    std::future<std::pair<HybridCa,double>> caAhead;
    if(ahead&&tune.caCorrect)caAhead=tasks.run([&input]{
        const auto t0=Clock::now();
        const HybridCa ca=hybridRawCa(input);
        return std::make_pair(ca,std::chrono::duration<double,std::milli>(Clock::now()-t0).count());
    });
    // ---- alignment: every frame against the base, groups of six
    std::vector<BackwardHomography> H(n);
    std::vector<bool> aligned(n,true);
    // P334: normal donors the alignment refused, handed to F6 from the identity (left out of the hand-motion and still-burst tests)
    std::vector<bool> idStart(n,false);
    int stillSeen=-1; // P335: the burst's donors (aligned ones) moved: 0, a still burst: 1, not tested: -1 (plainChromaHand)
    // P33 W2.1: the F6 field in tile-row bands (LaStream). Phase 1 of a frame (gray, L1 field) starts as soon as the alignment
    // publishes its homography, the bands once the refined frames are known (commit below) and run alongside the GPU merge. Plain
    // Bayer and the native mosaic's binned frames up to kHybridClassicPixels (the split's sub-frames share their fields frame-wide);
    // every frame holds its gray image (4 B per quad) until its field is complete: within a quarter of MemAvailable, else the
    // whole field first as before. tune laStream 0 / SCAM_NO_F6_STREAM / SCAM_LA_DUMP: the whole field first.
    // P118: a requested 2x grid (Sabre 2x of a plain stream or of a native mosaic) that «Sabre 2× только когда есть смысл» checks
    // with the whole F6 field (not the banded stream)
    const int requestedGrid=hybridRequestedGrid(input,tune);
    const int colourBlock=native?std::max(1,native->block):1;
    const bool fine2x=native?requestedGrid==2*colourBlock:requestedGrid==2;
    std::unique_ptr<LaStream> laStream;double laStreamBaseMs=0;bool laStreamTried=false;
    const bool laStreamOn=tune.localAlign>0&&n>1&&tune.laStream!=0&&!laGpuUse&&!hybridAuto2x(input,tune,fine2x)&&(!large||largeStream)&&input.subFrames<=1&&laAhead.valid()
            &&!std::getenv("SCAM_LA_DUMP")&&!std::getenv("SCAM_NO_F6_STREAM");
    // P308: a frame for the GPU F6 (its homography as the F6 stage would read it)
    auto laGpuJob=[&](int f){
        LaGpuWorker::Job j;j.f=f;j.raw=input.frames[f].raw;j.gain=1.f/input.frames[f].exposure;
        for(int i=0;i<8;++i)j.h[size_t(i)]=float(H[f].h[size_t(i)]);
        j.up=H[f].upRatio;return j;
    };
    auto streamFeed=[&](int f){
        // P308: the GPU F6 takes each frame as soon as its homography is known (the frames the merge leaves out are dropped there)
        if(laGpuWorker&&aligned[f]&&!(input.frames[f].role==kRoleUltrashort&&!tune.laUltrashort))laGpuWorker->add(laGpuJob(f));
        if(!laStreamOn||!aligned[f]||(input.frames[f].role==kRoleUltrashort&&!tune.laUltrashort))return;
        if(!laStream){
            if(laStreamTried)return;
            laStreamTried=true;
            const int win=std::clamp(tune.laWin,4,kLaMaxWin)&~3,stride=std::clamp(tune.laStride,2,win);
            const uint64_t tiles=uint64_t(std::max(1,(w/2-win)/stride+1))*uint64_t(std::max(1,(h/2-win)/stride+1));
            // P48: SCAM_F6_MEMAVAIL_MB stands in for MemAvailable in this decision only (replays of the low-memory route on a
            // phone with more memory); tune laStream 2 forces the windowed gray (replay A/B: the same field)
            uint64_t avail=hybridMemAvailable();
            if(const char* fake=std::getenv("SCAM_F6_MEMAVAIL_MB"))avail=uint64_t(std::max(0L,std::atol(fake)))<<20;
            const uint64_t need=uint64_t(n-1)*(uint64_t(w/2)*uint64_t(h/2)*4+tiles*40);
            const uint64_t needWindowed=uint64_t(n-1)*(uint64_t(LaStream::windowRowsEstimate(tune,win,stride))*uint64_t(w/2)*4+tiles*40)
                                        +uint64_t(std::clamp(tune.laThreads,1,8))*uint64_t(w/4)*uint64_t(h/4)*4;
            const bool forced=tune.laStream==2;
            bool windowed=forced;
            if(!windowed&&avail>0&&need>avail/4){
                if(needWindowed>avail/4){
                    report("HYBRID LOCAL ALIGN: field not streamed ("+hybridMB(need)+" MB of gray images, "+hybridMB(needWindowed)
                        +" MB windowed, MemAvailable "+hybridMB(avail)+" MB)");
                    return;
                }
                windowed=true;
            }
            if(windowed)report(std::string("HYBRID LOCAL ALIGN: field streamed with windowed gray rows (")+(forced?"laStream 2, ":"")
                +hybridMB(need)+" MB of gray images, about "+hybridMB(needWindowed)+" MB windowed, MemAvailable "+hybridMB(avail)+" MB)");
            LaBaseBuilt built=laAhead.get(); // P31: the base pyramid, built on the pool since the alignment started
            LaBase laBase=std::move(built.base);laStreamBaseMs=built.ms;
            laBase.g0=laGrid(laBase.l0,win,stride,2);laBase.g1=laGrid(laBase.l1,16,8,4);
            const float slope=std::max(input.frames[0].slope,1e-9f),offset=std::max(input.frames[0].offset,0.f); // baseSlope, baseOffset
            laBase.v0=hybridLaV0(input,slope,offset);
            const int threads=std::clamp(int(std::thread::hardware_concurrency()),1,std::clamp(tune.laThreads,1,8));
            laStream=std::make_unique<LaStream>(b,std::move(laBase),std::max(offset/std::max(slope,1e-9f),1e-5f),tune,threads,windowed);
        }
        LaStream::Job job;job.f=f;job.raw=input.frames[f].raw;job.gain=1.f/input.frames[f].exposure;job.H=H[f];
        laStream->add(job);
    };
    const auto alignStarted=Clock::now();
    scam_progress::pass("align",n-1); // P220: the frames aligned (a CRE group of up to 6 at a time)
    if(presetAlignment){
        H=preset->h;aligned=preset->aligned; // the mosaic sub-frames: binned alignment plus the known site offsets
    } else if(alignment){
        std::vector<Guide> fallbackRef; // base pyramid of the translation fallback, built on the first failed group
        for(int first=1;first<n;first+=6){
            Burst group=b;
            const int count=std::min(6,n-first);
            struct Done{int k;~Done(){scam_progress::step(k);}} done{count}; // P220: the group, aligned or by the fallback
            for(int j=0;j<count;++j){group.raw[1+j]=input.frames[first+j].raw;group.exposure[1+j]=input.frames[first+j].exposure;group.iso[1+j]=input.frames[first+j].iso;}
            std::array<BackwardHomography,7> hs;
            try{hs=alignment(group);}
            catch(const std::exception& error){
                // A CRE failure (corner detector, singular matrix, guide input) must not lose the shot: this group is aligned by
                // the global translation of the guide pyramids, as without a corner tracker.
                report(std::string("HYBRID ALIGN: CRE failed (")+error.what()+"); global translation for frames "+std::to_string(first)+".."+std::to_string(first+count-1));
                if(fallbackRef.empty())fallbackRef=guides(b,0);
                for(int j=0;j<count;++j){
                    const int f=first+j;
                    Burst one=b;one.raw[1]=input.frames[f].raw;one.exposure[1]=input.frames[f].exposure;
                    const Shift t=globalShift(fallbackRef,guides(one,1),input.frames[f].exposure);
                    BackwardHomography m;m.h={1,0,t.x,0,1,t.y,0,0};H[f]=m;aligned[f]=true;
                    streamFeed(f);
                }
                continue;
            }
            for(int j=0;j<count;++j){
                H[first+j]=hs[1+j];
                try{H[first+j].validate();}catch(const std::exception&){aligned[first+j]=false;H[first+j]=BackwardHomography{};}
                // replaceFailed() points a failed slot at the reference frame (the CRE and its translation refused the frame).
                // P334: a normal donor was merged at the identity without F6 (the comment said "drop it", nothing did): x1.3-1.8
                // the error of a sharp burst for a frame 0.8-2 px off. It now goes to F6 from the identity (F6 absorbs up to
                // laMaxShift, the rejection the rest), which beat dropping it on every synthetic case (dropping: a dark scene whose
                // reference has too few corners loses every donor at once, noise x1.6); without F6 it is dropped below while two
                // aligned normal donors remain. Bracketed and ultrashort frames are dropped as before.
                if(group.raw[1+j]!=input.frames[first+j].raw){
                    if(input.frames[first+j].role==kRoleNormal&&tune.localAlign>0){H[first+j]=BackwardHomography{};idStart[first+j]=true;}
                    else aligned[first+j]=false;
                }
                streamFeed(first+j); // P33 W2.1: its F6 phase 1 now
            }
        }
        if(prefetchDrop.on){scamAlignmentPrefetch()(nullptr,{});prefetchDrop.on=false;}
    } else {
        // No corner tracker: global translation from the guide pyramids. P30: frames are independent, on all cores (the same
        // per-frame result; 904 ms for 26 frames on one core on the X100 Ultra).
        const auto ref=guides(b,0);
        std::atomic<int> next{1};
        auto work=[&]{
            for(int f=next++;f<n;f=next++){
                Burst one=b;one.raw[1]=input.frames[f].raw;one.exposure[1]=input.frames[f].exposure;
                const auto donor=guides(one,1);
                const Shift s=globalShift(ref,donor,input.frames[f].exposure);
                BackwardHomography t;t.h={1,0,s.x,0,1,s.y,0,0};H[f]=t;
                // P308: to the GPU F6 at once (thread-safe; streamFeed below hands the same job again: nothing new)
                if(laGpuWorker&&!(input.frames[f].role==kRoleUltrashort&&!tune.laUltrashort))laGpuWorker->add(laGpuJob(f));
                scam_progress::step(); // P220
            }
        };
        std::vector<std::thread> pool;
        // P308: one core left to the GPU F6's feeder (it uploads each aligned frame meanwhile): the same shifts
        const int cores=std::min(8,int(std::thread::hardware_concurrency()))-(laGpuWorker&&std::thread::hardware_concurrency()>2?1:0);
        const int threads=std::max(1,std::min(n-1,cores));
        for(int t=1;t<threads;++t)pool.emplace_back(work);
        work();
        for(auto& th:pool)th.join();
        for(int f=1;f<n;++f)streamFeed(f);
    }
    stats.alignMs=millis(Clock::now()-alignStarted);
    {
        std::string ids;
        for(int f=1;f<n;++f)if(idStart[f])ids+=(ids.empty()?"":",")+std::to_string(f);
        if(!ids.empty())report("HYBRID ALIGN: frames "+ids+" refused by the alignment: local alignment from the identity and the rejection");
    }
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
    // P29: the binned burst of the native mosaic path averages b^2 sites per pixel; the SNR keys of the kernel curves follow the
    // sites the merge accumulates (HybridMosaicNative::keyNoise), the binned noise model stays for the guide / rejection / F6
    // P320: a DCG burst with two measured zones keys the curves with the read noise of the zone mid grey (0.18) lies in: above the
    // knee the LCG offset (one curve there read a day key where the mid-grey noise is night-like), blended over 0.9 .. 1.1 x the knee
    // as in the programs (hybridDcgShare); every other burst: baseOffset
    const float dcgShare=hybridDcgZones(input)?hybridDcgShare(0.18f,base.dcgKnee):0.f;
    const float keyOffset=dcgShare>0.f?baseOffset+(std::max(base.dcgOffsetLcg,0.f)-baseOffset)*dcgShare:baseOffset;
    const float snr=tune.snrFixed>0?float(tune.snrFixed):native?sabreSnr(baseSlope*native->keyNoise,keyOffset*native->keyNoise,tune.snrScale)
                    :sabreSnr(baseSlope,keyOffset,tune.snrScale);
    snrKernel(kernel,snr);
    kernel.base*=tune.kernelScale;kernel.shrunk*=tune.kernelScale;kernel.stretched*=tune.kernelScale;kernel.flat*=tune.kernelScale;
    if(tune.isoKernel){kernel.shrunk=kernel.stretched=kernel.base;if(tune.isoKernel>=2)kernel.flat=kernel.base;}
    // Sabre 6.1 kernel (sabre2x_61.md F1): key = 0.18/sqrt(O + 0.18 S) of the base frame (no snrScale), 6.1 curves f0..f3
    // (the Sabre 6.1 curves in research), f4 = 4, f5 = 2.2; uniforms exactly as the reference library 0x723f04-0x724008.
    std::array<float,4> k61a{},k61b{},k61c{};
    const float key61=tune.snrFixed>0?float(tune.snrFixed):native?sabreSnr(baseSlope*native->keyNoise,keyOffset*native->keyNoise,1.f)
                      :sabreSnr(baseSlope,keyOffset,1.f);
    if(hybridDcgZones(input))report("HYBRID DCG: two noise zones (knee "+std::to_string(base.dcgKnee)+", offset "+std::to_string(baseOffset)+" / "
        +std::to_string(base.dcgOffsetLcg)+"): the GPU programs take the offset of each level's zone (blended over 0.9 .. 1.1 x the knee); Sabre key "
        +std::to_string(key61)+" snr "+std::to_string(snr)+" from the offset at 0.18 (LCG share "+std::to_string(dcgShare)+")");
    // Hand motion of the burst: RMS shift of the aligned normal donors at the frame centre (sub-pixel diversity for the 6.1 kernel).
    double motion=0;
    {
        int cnt=0;
        for(int f=1;f<n;++f){
            if(input.frames[f].role!=kRoleNormal||!aligned[f]||idStart[f])continue;
            const DonorPoint p=H[f].project(w/2,h/2);
            const double dx=p.x/H[f].upRatio-w/2,dy=p.y/H[f].upRatio-h/2;
            motion+=dx*dx+dy*dy;++cnt;
        }
        motion=cnt?std::sqrt(motion/cnt):0.0;
    }
    // P306 (owner, 2026-10-20: «мы на 200мп будем снимать исключительно днём», «4x isz тоже только днём, Тетра в целом будет
    // использоваться только днём»): a native Tetra merge (block 4: 200 MP FULLRES, 4x ISZ) is a daylight shot. Its sensor-site SNR
    // key stays at or below s61MaxKey even at base ISO (vivo X200 Ultra tele HP9, ISO 50: key 28.25), which put every Tetra shot on
    // the Tetra night point (kernel 1, edge 0.4, flat x4) and the night noise LUT. The daylight point on the owner's 200 MP ship
    // burst (5 frames, OPPO replay): 1.2-2.4 px band of the text +10 %, 2.4-4.8 px +3 %, stack edge R / B misregistration about the
    // same, flat noise unchanged.
    const bool tetraDay=native&&native->block==4;
    if(tetraDay&&key61<=tune.s61MaxKey)report("HYBRID KERNEL: Tetra: daylight kernel point (key "+std::to_string(key61)
        +" <= "+std::to_string(tune.s61MaxKey)+"; Tetra is shot by day only)");
    const bool night61=key61<=tune.s61MaxKey&&!tetraDay,handheld61=tune.localAlign>0&&motion>=tune.s61MinMotion;
    const bool sabre61=tune.sabre61==1||(tune.sabre61==2&&(night61||handheld61));
    HybridMosaicNative nativeNight; // P34: the native kernel scale at night (mosaicNativeNightKernelScale)
    // Tetra (4x4 colour blocks) has its own night point (mosaicTetraNight*; 0 = the Quad keys above); Quad is unchanged
    const bool tetraNight=native&&night61&&native->block==4;
    const float nightKs=tetraNight&&tune.mosaicTetraNightKernelScale>0.f?tune.mosaicTetraNightKernelScale:tune.mosaicNativeNightKernelScale;
    const float nightEs=tetraNight&&tune.mosaicTetraNightEdgeScale>0.f?tune.mosaicTetraNightEdgeScale:tune.mosaicNativeNightEdgeScale;
    const float flatScale=tetraNight&&tune.mosaicTetraNightFlatScale>0.f?tune.mosaicNativeFlatScale*tune.mosaicTetraNightFlatScale:tune.mosaicNativeFlatScale;
    if(native&&night61&&nightKs>0.f&&nightKs!=native->kernelScale){
        nativeNight=*native;nativeNight.kernelScale=std::clamp(nightKs,0.1f,4.f);native=&nativeNight;
        report("HYBRID KERNEL: native mosaic at night (key "+std::to_string(key61)+(tetraNight?", Tetra":"")+"): kernel scale "
            +std::to_string(nativeNight.kernelScale)+", edge scale "+std::to_string(nightEs)+", flat kernel x"+std::to_string(std::clamp(flatScale,0.25f,4.f)));
    }
    const int gridOut=requestedGrid;
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
        // P34: the native path's night edge scale (mosaicNativeNightEdgeScale)
        const float esTune=native&&night61&&nightEs>0.f?nightEs:tune.mosaicEdgeScale;
        if((input.subFrames>1||native)&&esTune>0.f&&esTune!=1.f){
            // across the edge and the base kernel (p ~ 1 / sigma); along the edge and the blurred kernel of flat areas stay
            const float es=std::clamp(esTune,0.25f,2.f);k61a[0]/=es;k61a[3]/=es;
            report(std::string("HYBRID KERNEL: ")+(native?"native mosaic":"mosaic sub-frames")+", kernel across edges and base x"+std::to_string(es));
        }
        if(native&&(flatScale!=1.f||tune.mosaicNativeAlongScale!=1.f)){ // P34
            const float fs=std::clamp(flatScale,0.25f,4.f),as=std::clamp(tune.mosaicNativeAlongScale,0.25f,4.f);
            k61a[1]/=as;
            report("HYBRID KERNEL: native mosaic, along-edge kernel x"+std::to_string(as)+", flat kernel x"+std::to_string(fs));
        }
        // daylight multipliers only with the local alignment (they were measured with it): localAlign 0 keeps the merge before F6
        // for every sabre61 setting, forced 6.1 in daylight included
        const bool dayNoise=!night61&&tune.localAlign>0;
        const float tn=dayNoise?tune.s61DayTensorNoise:tune.s61TensorNoise,gn=dayNoise?tune.s61DayGdNoise:tune.s61GdNoise;
        k61b={1.f/(f0*f1),1.f/f3,1.f/(ks*ks),tn>0?tn:1.f};
        if(native&&flatScale!=1.f)k61b[0]/=std::clamp(flatScale,0.25f,4.f); // P34
        k61c={gn>0?gn:1.f,tune.s61Fine>0.f?tune.s61Fine:0.f,0,0};
        auto sigma=[&](float p){return std::to_string(ks/(p*std::sqrt(std::log(2.f))));};
        report("HYBRID KERNEL: Sabre 6.1 baseNoise slope="+std::to_string(baseSlope)+" offset="+std::to_string(baseOffset)+" key="+std::to_string(key)
            +" f0="+std::to_string(f0)+" f1="+std::to_string(f1)+" f2="+std::to_string(f2)+" f3="+std::to_string(f3)
            +" sigma across="+sigma(k61a[0])+" base="+sigma(k61a[3])+" along="+sigma(k61a[1])+" blurred="+sigma(k61b[0])
            +" ("+((tune.s61Mode&1)?"per-frame covariance":"base covariance for all frames")+((tune.s61Mode&2)?", window 1.5 px":"")
            +((tune.s61Mode&4)?", base x0.3 below "+std::to_string(tune.widenBelow)+" frames":", base widening by donor coverage")+")"
            +(tune.sabre61==2?std::string(" auto (")+(night61?"night key":"handheld with local alignment")+")":std::string())
            +" noise x"+std::to_string(k61b[3])+"/x"+std::to_string(k61c[0])
            +(k61c[1]>0.f?" fine tensor (noise x"+std::to_string(16.f*k61c[1])+")":std::string(" quad tensor"))+" motion="+std::to_string(motion)+" px");
    } else {
    if(tune.sabre61==2)report("HYBRID KERNEL: Sabre 6.1 kernel off (auto: key "+std::to_string(key61)+" > "+std::to_string(tune.s61MaxKey)
        +", motion "+std::to_string(motion)+" px "+(tune.localAlign>0?"< "+std::to_string(tune.s61MinMotion):std::string("without local alignment"))+")");
    report("HYBRID KERNEL: baseNoise slope="+std::to_string(baseSlope)+" offset="+std::to_string(baseOffset)+" snr="+std::to_string(snr)+" across="+std::to_string(kernel.shrunk)+" base="+std::to_string(kernel.base)
        +" along="+std::to_string(kernel.stretched)+" flat="+std::to_string(kernel.flat)+" thresholds="+std::to_string(kernel.flat0)+"/"+std::to_string(kernel.flat1));
    }
    if(tune.chromaDiff>0.f)report("HYBRID CHROMA DIFF: base R/B = merged G + <R-G>/<B-G> of the base sites where the base kernel widens (strength "
        +std::to_string(std::clamp(tune.chromaDiff,0.f,1.f))+((tune.s61Mode&4)&&sabre61?", below "+std::to_string(tune.widenBelow)+" accepted frames":", by donor coverage")
        +(tune.chromaDiffClamp?", clamped to the base's sample range)":", unclamped)"));
    // ---- measured gain of the other exposures against their metadata ratio (report only with gainMeasured 0; P12c). P30: frames
    // in parallel; P31: started before the alignment (gains above).
    for(int f=1;f<n;++f){
        if(!gains[f].valid())continue;
        report(hybridGainCheckLine(f,input.frames[f].role,input.frames[f].exposure,gains[f].get()));
    }
    // ---- Shasta: bracketed frames softer than the base are dropped; too long a ratio drops them all
    std::vector<bool> keep(n,true);
    {
        // P334: normal donors the alignment refused without F6 to refine them (localAlign 0, the conservative retry; the mosaic
        // split's sub-frames): left out while two aligned normal donors remain, else merged at the identity as before
        int alignedNormals=0;
        for(int f=1;f<n;++f)if(input.frames[f].role==kRoleNormal&&aligned[f])++alignedNormals;
        for(int f=1;f<n;++f){
            if(input.frames[f].role!=kRoleNormal||aligned[f])continue;
            if(alignedNormals>=2){keep[f]=false;report("HYBRID ALIGN: frame "+std::to_string(f)+" refused by the alignment: left out");}
            else report("HYBRID ALIGN: frame "+std::to_string(f)+" refused by the alignment: merged at the identity ("
                +std::to_string(alignedNormals)+" aligned normal donors)");
        }
    }
    {
        float maxRatio=1;
        // P30: the base guide once, the frames' sharpness in parallel; decisions and report in frame order as before.
        std::vector<std::future<SharpnessPair>> sharp(n);
        std::shared_ptr<Guide> baseGuide;
        for(int f=1;f<n;++f){
            const auto& fr=input.frames[f];
            if(fr.role!=kRoleBracketed||!tune.shastaEnable||!aligned[f])continue;
            if(shastaGuides[f].valid()){ // P31: both guides built during the alignment; the scores on the pool (nothing left to wait for)
                shastaBase.wait();shastaGuides[f].wait();
                sharp[f]=tasks.run([qb=shastaBase,qf=shastaGuides[f],frame=input.frames[f],baseSlope,baseOffset,sat=std::clamp(tune.shastaSat,0.05f,0.95f)]{
                    return hybridSharpnessPair(qb.get(),qf.get(),frame.exposure,baseSlope,baseOffset,frame.slope,frame.offset,sat);
                });
                continue;
            }
            if(!baseGuide)baseGuide=std::make_shared<Guide>(guideLevel0(b,0));
            sharp[f]=std::async(std::launch::async,[&,f,baseGuide]{
                const auto& frame=input.frames[f];
                Burst one=b;one.raw[1]=frame.raw;one.exposure[1]=frame.exposure;
                return hybridSharpnessPair(*baseGuide,one,1,frame.exposure,baseSlope,baseOffset,frame.slope,frame.offset,std::clamp(tune.shastaSat,0.05f,0.95f));
            });
        }
        for(int f=1;f<n;++f){
            const auto& fr=input.frames[f];
            if(fr.role!=kRoleBracketed)continue;
            if(!tune.shastaEnable||!aligned[f]){keep[f]=false;++stats.droppedBracketed;continue;}
            const SharpnessPair sp=sharp[f].get();
            const double pct=sp.base>0?sp.frame/sp.base:1.0;
            report("HYBRID SHASTA sharpness frame="+std::to_string(f)+" score="+std::to_string(sp.frame)+" base="+std::to_string(sp.base)
                +" pixels="+std::to_string(sp.pixels)+" ("+std::to_string(100*pct)+" % of base)");
            if(tune.shastaForce){
                // P68 forced Shasta: no sharpness gate, no ratio limit; the frame falls back only on motion (as Bento)
                if(tune.shastaMotionMax<100.f){
                    Burst one=b;one.raw[1]=fr.raw;one.exposure[1]=fr.exposure;
                    const ShastaMotion m=shastaMotion(one,1,H[f],fr.exposure,baseSlope,baseOffset,fr.slope,fr.offset,0.95f);
                    const bool drop=m.share()>tune.shastaMotionMax;
                    report("HYBRID SHASTA motion frame="+std::to_string(f)+" moved="+std::to_string(m.moved)+" of "+std::to_string(m.blocks)
                        +" blocks ("+std::to_string(m.share())+" %"+(drop?" > "+std::to_string(tune.shastaMotionMax)+" %: dropped)":", forced)"));
                    if(drop){keep[f]=false;++stats.droppedBracketed;continue;}
                }
                continue;
            }
            if(pct<tune.shastaSharpness){keep[f]=false;++stats.droppedBracketed;continue;}
            maxRatio=std::max(maxRatio,fr.exposure);
        }
        if(maxRatio>tune.shastaMaxRatio){
            for(int f=1;f<n;++f)if(input.frames[f].role==kRoleBracketed&&keep[f]){keep[f]=false;++stats.droppedBracketed;}
            report("HYBRID SHASTA: TET ratio "+std::to_string(maxRatio)+" above the limit; all bracketed frames dropped");
        }
        shastaBase={};shastaGuides.clear(); // P31: the guides are not held through the merge
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
    BentoResult bento;std::vector<std::vector<float>> bentoValids;
    std::vector<float> bentoShare; // P309: the ramp mask's share of the ultrashort frames before the check (bentoRamp > 0)
    std::vector<float> bentoPrev,bentoBest; // P309: the previous mask (the ramp never gives less), the cells' best validity
    std::vector<uint8_t> bentoPrevOn; // P309: the previous mask's cells: the clip flags' bit 4 (inside the Bento mask) stays on them
    float bentoK=0.f; // P309: (1 + A of the other frames) / A of the ultrashort frames
    const auto maskStarted=Clock::now();
    scam_progress::pass("bento"); // P220
    if(us>=0&&tune.bento>0){
        // P30: the masks of the other ultrashort frames (validation) are built alongside the first one. Any RAW size: above 16 MP
        // one after the other (each mask holds ~5.5 B per pixel while it is built; otherMask() below computes it on demand).
        // P31 (W1.4): the base part of every mask below was built during the alignment (bentoAhead); the masks then run on the pool.
        // The validation masks are needed only when the first one can be applied: not started when its base has no mask.
        const BentoBase* shared=bentoAhead.valid()?&bentoAhead.get():nullptr;
        // P309: a large burst builds no base ahead; the ramp's (several planes, blurs) is built once here for every mask below
        BentoBase onceBase;
        if(!shared&&tune.bentoRamp>0.f&&input.frames[us].exposure<1.f){onceBase=bentoBase(b,tune);shared=&onceBase;}
        auto poolMask=[&b,&tune,&bentoAhead](const HybridFrame& fr,const BackwardHomography& h,int mode){
            // the task holds the base itself (a validation mask still running when an exception leaves this block outlives it)
            return [&b,&tune,base=bentoAhead,raw=fr.raw,exposure=fr.exposure,h,mode]{
                Burst one=b;one.raw[1]=raw;one.exposure[1]=exposure;
                HybridTuning t2=tune;if(mode)t2.bento=mode;
                return bentoMask(one,1,h,exposure,t2,&base.get());
            };
        };
        std::vector<std::future<BentoResult>> otherMasks(usFrames.size());
        if(input.frames[us].exposure<1.f&&tune.bentoValidate>=1&&!large&&(!shared||shared->full))
            for(size_t k=1;k<usFrames.size();++k){
                if(shared&&bentoAhead.valid()){otherMasks[k]=tasks.run(poolMask(input.frames[usFrames[k]],H[usFrames[k]],2));continue;}
                otherMasks[k]=std::async(std::launch::async,[&,k]{
                const int f=usFrames[k];
                Burst one=b;one.raw[1]=input.frames[f].raw;one.exposure[1]=input.frames[f].exposure;
                HybridTuning t2=tune;t2.bento=2;
                return bentoMask(one,1,H[f],input.frames[f].exposure,t2);
            });
            }
        if(input.frames[us].exposure>=1.f){bento.reason="ultrashort frame is not the shortest";}
        else if(shared&&bentoAhead.valid())bento=tasks.run(poolMask(input.frames[us],H[us],0)).get();
        else {
            Burst one=b;one.raw[1]=input.frames[us].raw;one.exposure[1]=input.frames[us].exposure;
            bento=bentoMask(one,1,H[us],input.frames[us].exposure,tune,shared);
        }
        auto otherMask=[&](size_t k){
            if(otherMasks[k].valid())return otherMasks[k].get();
            const int f=usFrames[k];
            Burst one=b;one.raw[1]=input.frames[f].raw;one.exposure[1]=input.frames[f].exposure;
            HybridTuning t2=tune;t2.bento=2;
            return bentoMask(one,1,H[f],input.frames[f].exposure,t2,shared);
        };
        std::vector<long> usInvalid{bento.invalidCells}; // per ultrashort frame (merge order of usFrames), for the motion share
        auto frameLine=[&](int f,const BentoResult& r){
            report("HYBRID BENTO frame="+std::to_string(f)+" invalid="+std::to_string(r.invalidCells)+" hole="+std::to_string(r.inpaintHole)
                +" largestHole="+std::to_string(r.largestHole)+" usClipped="+std::to_string(r.usClippedRatio));
        };
        if(us>=0&&input.frames[us].exposure<1.f)frameLine(us,bento);
        if(bento.active&&tune.bentoValidate>=1&&usFrames.size()>1){
            // every ultrashort frame checked; mask = smooth x max_k valid_k; per-frame factors for the GPU (dilate pass)
            // P31: the validity planes are moved, not copied (bento.valid is freed below anyway)
            std::vector<std::vector<float>> valids;valids.push_back(std::move(bento.valid));
            std::string line="HYBRID BENTO VALIDATE: per-frame invalid cells";
            line+=" "+std::to_string(us)+":"+std::to_string(bento.invalidCells);
            for(size_t k=1;k<usFrames.size();++k){
                const int f=usFrames[k];
                BentoResult r=otherMask(k);
                frameLine(f,r);usInvalid.push_back(r.invalidCells);
                valids.push_back(std::move(r.valid));line+=" "+std::to_string(f)+":"+std::to_string(r.invalidCells);
            }
            // P31: blocks of cells on all cores (integer counts, every cell's value as before)
            if(tune.bentoRamp>0.f)bentoBest.assign(bento.mask.size(),1.f); // P309: the ramp's conversion scales by it
            std::atomic<long> onlyBaseSum{0},anyInvalidSum{0};
            const size_t cells=bento.mask.size();
            mergeRowBands(int((cells+4095)/4096),[&](int k0,int k1){
                long onlyBaseLocal=0,anyInvalidLocal=0;
                for(size_t i=size_t(k0)*4096;i<std::min(cells,size_t(k1)*4096);++i){
                    float best=0;for(const auto& v:valids)best=std::max(best,v[i]);
                    if(bento.smooth[i]>0.f){if(best<1.f)++anyInvalidLocal;if(best<=0.f)++onlyBaseLocal;}
                    bento.mask[i]=std::clamp(bento.smooth[i]*best,0.f,1.f);
                    if(!bentoBest.empty())bentoBest[i]=best;
                }
                onlyBaseSum+=onlyBaseLocal;anyInvalidSum+=anyInvalidLocal;
            });
            const long onlyBase=onlyBaseSum,anyInvalid=anyInvalidSum;
            bentoValids=std::move(valids);
            report(line+" | cells invalid in some frame="+std::to_string(anyInvalid)+" in every frame (base stays)="+std::to_string(onlyBase));
        }
        // Motion in the mask: share of its cells the SCAM check marks invalid in the BEST ultrashort frame (one frame: that frame).
        double motionShare=0;
        if(bento.maskCells>0){
            long fewest=bento.invalidCells;
            for(size_t k=1;k<usFrames.size()&&tune.bentoValidate>=1;++k)fewest=std::min(fewest,usInvalid.size()>k?usInvalid[k]:fewest);
            motionShare=100.0*double(fewest)/double(bento.maskCells);
        }
        if(bento.active&&tune.bentoMotionMax<100.f&&motionShare>tune.bentoMotionMax){
            bento.active=false;
            bento.reason="motion: "+std::to_string(motionShare)+" % of the mask > "+std::to_string(tune.bentoMotionMax)+" %"+(tune.bento==2?" (refused also in mode always)":"");
        }
        report("HYBRID BENTO: "+std::string(bento.active?"applied":"not applied")+" ("+bento.reason+") motion="+std::to_string(motionShare)+"% of "+std::to_string(bento.maskCells)+" cells clipped="+std::to_string(bento.clippedFraction)
            +" usClippedRatio="+std::to_string(bento.usClippedRatio)+" largestHole="+std::to_string(bento.largestHole)
            +" inpaintHole="+std::to_string(bento.inpaintHole)+" invalid="+std::to_string(bento.invalidCells)
            +" checks="+(tune.bentoScam?"scam":"round4")+" factor="+std::to_string(1.f/input.frames[us].exposure)
            +" frames="+std::to_string(usFrames.size())+" chroma sigma="+std::to_string(tune.bentoChromaSigma));
        // the mask before the check and the first frame's validity were for the validation only (bentoValids holds its copy);
        // P309: with the ramp the share before the check stays for its conversion to m (frame weights, below)
        if(bento.active&&tune.bentoRamp>0.f){
            bentoShare=std::move(bento.smooth);
            if(shared&&shared->prev.size()==bentoShare.size())bentoPrev=shared->prev;
            if(bentoBest.empty()&&bento.valid.size()==bentoShare.size())bentoBest=bento.valid; // one ultrashort frame: its own check
        }
        // P310: one checked ultrashort frame (the planes above come from two or more): its own check is the blend's validity plane
        // (the merge's Bento mask held it; the blend weighs by the planes only)
        if(tune.bentoBlend>0&&native==nullptr&&bentoValids.empty()&&!bento.valid.empty()&&bento.valid.size()==bento.mask.size())
            bentoValids.push_back(std::move(bento.valid));
        std::vector<float>().swap(bento.smooth);std::vector<float>().swap(bento.valid);
        // P31: a validation mask nobody took (the first mask was not applied) ends here, as the std::async futures made it end
        // before: it does not run on into the local alignment and the merge (all cores, ~50 MB at 12 MP while it is built).
        for(auto& m:otherMasks)if(m.valid())m.wait();
    } else if(us>=0)report("HYBRID BENTO: disabled by tuning");
    bentoAhead={}; // P31: the shared base is not held through the merge (a task still running keeps its own reference)
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
    std::vector<std::vector<float>> laFields(n),laMotions(n);std::vector<float> laMaxY(n,0.f);
    LaGrid laG0;bool laOn=false;
    if(tune.localAlign>0&&n>1){
        const auto laStarted=Clock::now();
        std::vector<int> jobs;
        for(int f=1;f<n;++f){
            if(!keep[f]||!aligned[f])continue;
            if(input.frames[f].role==kRoleUltrashort&&!tune.laUltrashort)continue;
            jobs.push_back(f);
        }
        if(!laStream)scam_progress::pass("local-align",int64_t(jobs.size())); // P220: the whole-field F6 before the merge, per frame
        if(laStream&&jobs.empty())laStream.reset();
        if(laGpuWorker&&jobs.empty())laGpuWorker.reset(); // P308: nothing to refine: its thread, context and buffers go now
        if(laStream){ // P33 W2.1: the bands of the refined frames (phase 1 ran during the alignment), alongside the merge
            laStream->commit(jobs);
            laOn=true;laG0=laStream->grid0();
            stats.localAlignMs=millis(Clock::now()-laStarted);
        } else {
        LaBase laBase; // ~40 MB on 12 MP, freed before the GPU merge
        const float eps=std::max(baseOffset/std::max(baseSlope,1e-9f),1e-5f);
        double baseMs=0;
        const int win=std::clamp(tune.laWin,4,kLaMaxWin)&~3,stride=std::clamp(tune.laStride,2,win);
        std::vector<LaFrameStats> fstats(n);
        // P308: the field on the GPU (tune laGpu): the base pyramid and every frame there; any failure leaves it to the CPU below
        bool laGpuDone=false;std::string laGpuNote;
        if(laGpuUse&&!jobs.empty()){
            const auto gpuStarted=Clock::now();
            try{
                laBase.l0.w=b.w/2;laBase.l0.h=b.h/2;laBase.l1.w=laBase.l0.w/2;laBase.l1.h=laBase.l0.h/2; // the levels' sizes (laGrid)
                laBase.g0=laGrid(laBase.l0,win,stride,2);laBase.g1=laGrid(laBase.l1,16,8,4);
                if(!laGpuWorker)throw std::runtime_error("not prepared");
                for(int f:jobs)laGpuWorker->add(laGpuJob(f)); // nothing new for the frames the alignment handed over
                laGpuWorker->retain(jobs); // the frames the merge leaves out leave the queue
                const int early=laGpuWorker->readyCount(jobs);
                if(!laGpuWorker->wait(jobs))throw std::runtime_error(laGpuWorker->error());
                const double waitedMs=millis(Clock::now()-gpuStarted);
                baseMs=laGpuWorker->baseMs();
                std::vector<LaGpuResult> taken(jobs.size());
                for(size_t i=0;i<jobs.size();++i)taken[i]=laGpuWorker->take(jobs[i]);
                const std::string gpuName=laGpuWorker->renderer();
                laGpuWorker.reset(); // its thread ends, the context and buffers are freed before the merge
                { // the frames' statistics on the cores (each frame alone, as laFrameField takes them)
                    std::atomic<size_t> next{0};
                    auto work=[&]{for(size_t i=next++;i<taken.size();i=next++)LaGpu::stats(taken[i]);};
                    std::vector<std::thread> pool;
                    const size_t threads=std::min<size_t>(taken.size(),std::max(1u,std::min(8u,std::thread::hardware_concurrency())));
                    for(size_t t=1;t<threads;++t){try{pool.emplace_back(work);}catch(const std::system_error&){break;}}
                    work();
                    for(auto& th:pool)th.join();
                }
                for(size_t i=0;i<jobs.size();++i){
                    const int f=jobs[i];
                    LaGpuResult& r=taken[i];
                    laFields[f]=std::move(r.field);laMotions[f]=std::move(r.motion);
                    LaFrameStats& st=fstats[f];
                    st.median=r.median;st.p90=r.p90;st.maxAbsY=r.maxAbsY;st.accepted=r.accepted;st.fromCoarse=r.fromCoarse;
                    long over=0;for(float v:laMotions[f])over+=v>tune.motionThreshold;
                    st.motionShare=float(over)/float(std::max<size_t>(1,laMotions[f].size()));
                    st.ms[4]=r.ms;
                    scam_progress::step(); // P220
                }
                laGpuDone=true;
                char tail[160];
                std::snprintf(tail,sizeof(tail),": context, programs and base %.0f ms from the alignment on; %d frames, %d of them done before this stage, waited %.0f ms for the rest",
                    baseMs,int(jobs.size()),early,waitedMs);
                laGpuNote=std::string("HYBRID LOCAL ALIGN: GPU")+(tune.laGpu==2?" (auto: the CPU field would come whole before the merge)":"")+" ("+gpuName+")"+tail;
            }catch(const std::exception& e){
                laGpuWorker.reset();
                for(int f:jobs){laFields[f].clear();laMotions[f].clear();fstats[f]=LaFrameStats();}
                laBase=LaBase();baseMs=0;
                report(std::string("HYBRID LOCAL ALIGN: GPU failed (")+e.what()+"); the CPU computes the field");
            }
        }
        if(!laGpuDone){
        if(laAhead.valid()){LaBaseBuilt built=laAhead.get();laBase=std::move(built.base);baseMs=built.ms;} // P31: built during the alignment
        else if(laGpuUse&&jobs.empty()){laBase.l0.w=b.w/2;laBase.l0.h=b.h/2;laBase.l1.w=laBase.l0.w/2;laBase.l1.h=laBase.l0.h/2;} // P308: nothing to refine
        else {
        laGray(b,input.frames[0].raw,1.f,eps,laBase.l0,true);
        laDown(laBase.l0,laBase.l1,true);laBaseLevel(laBase.l1,true);laBaseLevel(laBase.l0,true);
        baseMs=millis(Clock::now()-laStarted);
        }
        laBase.g0=laGrid(laBase.l0,win,stride,2);laBase.g1=laGrid(laBase.l1,16,8,4);
        }
        laBase.v0=hybridLaV0(input,baseSlope,baseOffset);
        {
        // One worker per frame at a time (gray 12 MB + L1 3 MB each): long-lived threads, so the scheduler moves them to the big
        // cores (short parallel sections per pass stayed on the small ones: 3.3 s for 19 frames against 0.8 s for this pool on Adreno 750 phones (SM8650)).
        // An exception must not leave a worker thread (std::terminate: the shot is lost): it stops the pool, the merge then uses
        // the homographies only.
        std::atomic<bool> laFailed{false};std::string laFailure;std::mutex laFailureMutex;
        if(laGpuDone)report(laGpuNote);
        else {
            int threads=std::clamp(int(std::thread::hardware_concurrency()),1,std::clamp(tune.laThreads,1,8));
            if(large){ // any RAW size: every worker holds its frame's gray pyramid (1.25 B per pixel), together within MemAvailable / 4
                const uint64_t avail=hybridMemAvailable();
                const double perThread=1.25*double(w)*h;
                if(avail>0){
                    const int byMemory=std::clamp(int(std::min(64.0,double(avail)*0.25/perThread)),1,threads);
                    if(byMemory<threads)report("HYBRID LOCAL ALIGN: "+std::to_string(byMemory)+" of "+std::to_string(threads)+" threads (gray pyramid "
                        +hybridMB(uint64_t(perThread))+" MB each, MemAvailable "+hybridMB(avail)+" MB)");
                    threads=byMemory;
                }
            }
            std::atomic<int> next{0};
            auto work=[&]{
                try{
                    LaImage D0;
                    for(int k=next.fetch_add(1);k<int(jobs.size())&&!laFailed;k=next.fetch_add(1)){
                        const int f=jobs[k];
                        const auto g0=Clock::now();
                        laGray(b,input.frames[f].raw,1.f/input.frames[f].exposure,eps,D0,false);
                        fstats[f].ms[0]=millis(Clock::now()-g0);
                        laFrameField(laBase,D0,H[f],tune,laFields[f],fstats[f],&laMotions[f]);
                        scam_progress::step(); // P220
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
            for(auto& z:laMotions)z.clear();
            jobs.clear();
            report("HYBRID LOCAL ALIGN: failed ("+laFailure+"); merging with the homographies only");
        }
        std::string per;double partMs[7]{};
        for(int f:jobs){
            const LaFrameStats& st=fstats[f];
            laMaxY[f]=st.maxAbsY;
            for(int k=0;k<7;++k)partMs[k]+=st.ms[k];
            char v[96];std::snprintf(v,sizeof(v)," %d:%.2f/%.2f/%.0f%%/z%.0f%%",f,st.median,st.p90,100.f*st.accepted,100.f*st.motionShare);per+=v;
        }
        if(input.subFrames>1&&tune.mosaicShare&&!jobs.empty()){
            laShareSubFrames(laFields,input.subFrames,laMaxY);
            per+=" (fields shared by the "+std::to_string(input.subFrames)+" sub-frames of each frame)";
        }
        const int done=int(jobs.size());
        const double grayMs=partMs[0];
        stats.localAlignMs=millis(Clock::now()-laStarted);
        laOn=done>0;laG0=laBase.g0;
        char line[340];
        std::snprintf(line,sizeof(line),"HYBRID LOCAL ALIGN: %s%s, %d frames, tiles %dx%d (%d RAW px, window %d RAW px), %.0f ms (gray %.0f ms thread sum) mu=%.2f kappa=%.1f maxShift=%.1f medians=%d; per frame |r| median/p90 RAW px / refined tiles / tiles over the motion threshold (rejection boost):",
            tune.localAlign==2?"constant per tile":"bilinear field",laGpuDone?" (GPU)":"",done,laBase.g0.nx,laBase.g0.ny,2*stride,2*win,stats.localAlignMs,grayMs,tune.laMu,tune.laKappa,tune.laMaxShift,tune.laMedian);
        report(line+per);
        if(tune.profile){
            std::snprintf(line,sizeof(line),"HYBRID LOCAL ALIGN ms (thread sums): base=%.0f gray=%.0f down=%.0f L1=%.0f start=%.0f L0=%.0f accept=%.0f median=%.0f",
                baseMs,partMs[0],partMs[1],partMs[2],partMs[3],partMs[4],partMs[5],partMs[6]);
            report(line);
        }
        } // P33 W2.1: end of the whole-field path
        }
    } else report("HYBRID LOCAL ALIGN: off");
    // ---- P118 Sabre 2x only when it helps (P74's jsrAuto2x, now with the light): the phase coverage of the burst and the SNR of a
    // 2x cell decide whether a requested 2x grid is merged as such or on the 1x grid and resized
    bool coarse2x=false;
    if(hybridAuto2x(input,tune,fine2x)){
        std::vector<int> donors;
        for(int f=1;f<n;++f)if(keep[f]&&aligned[f]&&input.frames[f].role!=kRoleUltrashort)donors.push_back(f);
        // the F6 field (whole-field path: the measure needs it before the merge), nearest tile, RAW px of the stream
        auto local=[&](int f,int x,int y)->std::array<float,2>{
            if(!laOn||size_t(f)>=laFields.size()||laFields[size_t(f)].size()!=size_t(laG0.nx)*laG0.ny*2)return {0.f,0.f};
            const float st=float(2*laG0.stride),o=laG0.raw(laG0.centre(0));
            const int i=std::clamp(int(std::lround((x+0.5f-o)/st)),0,laG0.nx-1),j=std::clamp(int(std::lround((y+0.5f-o)/st)),0,laG0.ny-1);
            const size_t k=(size_t(j)*laG0.nx+i)*2;
            return {laFields[size_t(f)][k],laFields[size_t(f)][k+1]};
        };
        const GridCoverage cov=gridCoverage(H,donors,w,h,colourBlock,local);
        // light: the base frame's SNR at 0.1 of white (its noise model, normalised units), averaged by the frames that share a
        // 2x cell (a quarter of them each); below auto2xMinSnr the 2x grid shows its lattice and noise, not detail
        // P320: a DCG burst with two measured zones: the read noise of the zone 0.1 lies in (blended as the programs do)
        const double level=0.1;
        const double share2x=hybridDcgZones(input)?hybridDcgShare(float(level),input.frames[0].dcgKnee):0.0;
        const double offset2x=double(baseOffset)+(std::max(double(input.frames[0].dcgOffsetLcg),0.0)-double(baseOffset))*share2x;
        const double snr1=level/std::sqrt(std::max(1e-12,double(baseSlope)*level+offset2x));
        const double snr2x=snr1*std::sqrt(double(cov.frames)/4.0);
        const bool sparse=cov.green>tune.auto2xMaxGap,dark=snr2x<tune.auto2xMinSnr;
        coarse2x=sparse||dark;
        char line[320];
        std::snprintf(line,sizeof(line),"HYBRID SABRE 2X: %d frames, green fill distance %.2f px (limit %.2f, one frame %.2f), SNR of a 2x cell %.1f"
            " (limit %.0f) -> %s",cov.frames,cov.green,double(tune.auto2xMaxGap),cov.greenSingle,snr2x,double(tune.auto2xMinSnr),
            !coarse2x?"2x grid kept":sparse&&dark?"1x grid, resized (no finer sampling, too dark)":sparse?"1x grid, resized (no finer sampling)"
            :"1x grid, resized (too dark)");
        report(line);
    }
    // ---- per-frame weights (SCAM driver): A = min(cap, ((TET_f/TET_b)^2 read_b/read_f)^fwe), LUTsigma(A)
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
        in.noiseKnee.push_back(std::max(fr.dcgKnee,0.f));in.noiseLcg.push_back(std::max(fr.dcgOffsetLcg,0.f)); // P320 (0, 0: one curve)
        index.push_back(f);
        char line[96];std::snprintf(line,sizeof(line)," | %d %d %.4f %.2f %.3f",f,fr.role,t,A,ls);table+=line;
    }
    report(table);
    if(bento.active&&tune.bentoRamp>0.f){
        // P309: the ultrashort frames' A x 16 under the ramp (the share is set by the mask below either way): the merge drops a
        // frame below r 0.004, so near the share's top the N frames would stop at once (~8 % of the share at 20 N); with x16 that
        // step is < 1 %. K leaves out the bracketed frames that clip in the ramp (base level (lo + hi) / 2 x their exposure >=
        // the clip): they add nothing there.
        const float mid=0.5f*(tune.bentoRampLo+tune.bentoRampHi);
        double others=0,ultra=0;
        for(size_t k=0;k<in.weight.size();++k){
            if(in.role[k]==kRoleUltrashort){in.weight[k]*=16.f;ultra+=double(in.weight[k]);continue;}
            if(in.role[k]==kRoleBracketed&&mid*input.frames[size_t(index[k])].exposure>=tune.bentoHighlight)continue;
            others+=double(in.weight[k]);
        }
        bentoK=ultra>0?float(others/ultra):0.f;
        in.rampK=bentoK;
    }
    if(bento.active&&tune.bentoRamp>0.f&&bentoShare.size()==bento.mask.size()&&bentoK>0.f){
        // P309: the share s of the ultrashort frames into the mask m the merge weighs with: the base (1 - m), the other frames
        // r (1 - m) A, the ultrashort frames m A; with K = (1 + their A) / (the ultrashort frames' A) the share is s when
        // m = s K / (1 + s (K - 1)). A cell's check (the best frame's validity, mask / share) scales m as before.
        // The previous mask's share at the frame weights it was made for (K without the x16): the ramp never gives the ultrashort
        // frames less than it did (the dark side of a lamp's steep glow, the cells next to a clip).
        const float K=bentoK,Kprev=bentoK*16.f;
        const bool prev=bentoPrev.size()==bentoShare.size(),checked=bentoBest.size()==bentoShare.size();
        mergeRowBands(int((bento.mask.size()+4095)/4096),[&](int k0,int k1){
            for(size_t i=size_t(k0)*4096;i<std::min(bento.mask.size(),size_t(k1)*4096);++i){
                const float mp=prev?bentoPrev[i]:0.f;
                const float sh=std::max(bentoShare[i],mp>0.f?mp/(mp+Kprev*(1.f-mp)):0.f);
                if(sh<=0.f){bento.mask[i]=0.f;continue;}
                const float best=checked?std::clamp(bentoBest[i],0.f,1.f):bentoShare[i]>0.f?std::clamp(bento.mask[i]/bentoShare[i],0.f,1.f):1.f;
                // the check scales the mask at the weights it was made for (Kprev), as before: an invalid cell keeps the share it had
                const float mo=sh*Kprev/(1.f+sh*(Kprev-1.f))*best;
                const float s2=mo>0.f?mo/(mo+Kprev*(1.f-mo)):0.f;
                bento.mask[i]=std::clamp(s2*K/(1.f+s2*(K-1.f)),0.f,1.f);
            }
        });
        if(prev){bentoPrevOn.resize(bentoPrev.size());for(size_t i=0;i<bentoPrev.size();++i)bentoPrevOn[i]=bentoPrev[i]>0.f?1:0;}
        std::vector<float>().swap(bentoPrev);std::vector<float>().swap(bentoBest);
        char line[200];
        std::snprintf(line,sizeof(line),"HYBRID BENTO RAMP: share smoothstep(%.2f, %.2f) of the base level (sigma %.1f cells, reach %d cells) -> mask with K = %.2f (ultrashort A x 16)",
            tune.bentoRampLo,tune.bentoRampHi,tune.bentoRamp,tune.bentoRampReach,K);
        report(line);
        std::vector<float>().swap(bentoShare);
    }
    if(bento.active)in.mask=&bento.mask;
    if(bento.active&&!bentoValids.empty()){ // validity planes in merge order of the ultrashort frames
        for(int f=1;f<n;++f){if(!keep[f]||input.frames[f].role!=kRoleUltrashort)continue;
            size_t k=0;for(;k<usFrames.size();++k)if(usFrames[k]==f)break;
            in.maskValid.push_back(k<bentoValids.size()?&bentoValids[k]
                                   :(tune.bentoBlend>0&&native==nullptr&&bentoValids.size()==1?&bentoValids[0]:nullptr));}
    }
    std::vector<float> blendWeight;
    std::vector<std::vector<float>> blendValids;
    if(bento.active&&tune.bentoBlend>0&&native==nullptr){
        // the cells' check as smooth planes (a gaussian of bentoBlendValidSigma cells): its hard 2x2 cells, where the frames
        // moved or flickered, switched between the ultrashort frames and the N frames cell by cell (the 2x2 squares, torn
        // edges); smooth, the weight passes over a few cells (as WhyCam cleans and blurs its validity)
        const int w2=w/2,h2=h/2;
        for(const auto* v:in.maskValid){
            if(!v||v->size()!=size_t(w2)*h2){blendValids.emplace_back();continue;}
            std::vector<float> sm(*v),tmp(sm.size());
            const float sg=std::max(0.f,tune.bentoBlendValidSigma);
            if(sg>0.05f){
                const int r=std::max(1,int(std::ceil(3.f*sg)));
                std::vector<float> kk(size_t(2*r+1));float ks=0;
                for(int i=-r;i<=r;++i){kk[size_t(i+r)]=std::exp(-0.5f*float(i*i)/(sg*sg));ks+=kk[size_t(i+r)];}
                for(float& q:kk)q/=ks;
                mergeRowBands(h2,[&](int y0,int y1){for(int y=y0;y<y1;++y)for(int x=0;x<w2;++x){float a=0;for(int i=-r;i<=r;++i)a+=kk[size_t(i+r)]*sm[size_t(y)*w2+std::clamp(x+i,0,w2-1)];tmp[size_t(y)*w2+x]=a;}});
                mergeRowBands(h2,[&](int y0,int y1){for(int y=y0;y<y1;++y)for(int x=0;x<w2;++x){float a=0;for(int i=-r;i<=r;++i)a+=kk[size_t(i+r)]*tmp[size_t(std::clamp(y+i,0,h2-1))*w2+x];sm[size_t(y)*w2+x]=a;}});
            }
            blendValids.push_back(std::move(sm));
        }
        for(size_t k=0;k<in.maskValid.size();++k)if(!blendValids[k].empty())in.maskValid[k]=&blendValids[k];
        // P310: the ultrashort frames' blend weight per cell (see HybridTuning::bentoBlend); the merge itself runs without them
        const auto t0=Clock::now();
        blendWeight=hybridBlendWeight(input,tune,in.maskValid);
        double on=0,full=0;for(float v:blendWeight){on+=v>0.01f;full+=v>0.99f;}
        in.mask=&blendWeight;in.blend=true;
        // the gain only where the ultrashort frames saw the lights in other levels / colours than the N frames (the tile check);
        // unmeasured (too few tiles: a dark scene of lamps): on
        if(input.usFlicker>=0.f){
            const float u=std::clamp((input.usFlicker-tune.bentoBlendGainFlickerLo)/std::max(tune.bentoBlendGainFlickerHi-tune.bentoBlendGainFlickerLo,0.01f),0.f,1.f);
            in.blendGain=u*u*(3.f-2.f*u);
        }
        char line[260];
        std::snprintf(line,sizeof(line),"HYBRID BENTO BLEND: ultrashort RGB blended after the merge by region (the N clip spread %.0f..%.0f px,"
            " at least the base cells' weight: %.0f cells > 0.01, %.0f full), gain against N x%.2f (tile check %.2f) (%.0f ms)",
            tune.bentoBlendNear,tune.bentoBlendFar,on,full,in.blendGain,input.usFlicker,millis(Clock::now()-t0));
        report(line);
    }
    // F6 field in merge order (0 = base and frames without a field: zeros)
    std::vector<float> laAll,laZAll;
    if(laOn&&laStream){ // P33 W2.1: rows arrive during the merge; the strip windows take the field's bound (|dy| <= laMaxShift)
        laStream->setMergeOrder(index);
        in.laMaxY.assign(index.size(),0.f);
        for(size_t i=0;i<index.size();++i)if(laStream->hasField(int(i)))in.laMaxY[i]=std::max(tune.laMaxShift,0.f);
        in.laStream=laStream.get();in.laMode=tune.localAlign==2?2:1;in.laNx=laG0.nx;in.laNy=laG0.ny;
        in.laOx=in.laOy=laG0.raw(laG0.centre(0));in.laStride=float(2*laG0.stride);
    } else if(laOn){
        const size_t tiles=size_t(laG0.nx)*laG0.ny*2;
        laAll.assign(index.size()*tiles,0.f);in.laMaxY.assign(index.size(),0.f);
        laZAll.assign(index.size()*(tiles/2),0.f);
        for(size_t i=0;i<index.size();++i){
            const auto& fld=laFields[index[i]];
            if(fld.size()==tiles){std::copy(fld.begin(),fld.end(),laAll.begin()+i*tiles);in.laMaxY[i]=laMaxY[index[i]];}
            const auto& z=laMotions[index[i]];
            if(z.size()==tiles/2)std::copy(z.begin(),z.end(),laZAll.begin()+i*(tiles/2));
        }
        std::vector<std::vector<float>>().swap(laFields);std::vector<std::vector<float>>().swap(laMotions); // copied in merge order
        in.laField=&laAll;in.laMotion=&laZAll;in.laMode=tune.localAlign==2?2:1;in.laNx=laG0.nx;in.laNy=laG0.ny;
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
    if(native){ // P29: the mosaic of every merged frame, in merge order
        in.native=native;
        for(int f:index)in.nativeFrames.push_back(f<int(native->frames.size())?native->frames[f]:nullptr);
        for(int f:index)in.nativeRowFix.push_back(native->fixesRows(f)?f:-1); // P197
    }
    in.k61a=k61a;in.k61b=k61b;in.k61c=k61c;
    in.mergeMode=sabre61?(tune.s61Mode&7):0;
    HybridCa caModel;
    if(tune.caCorrect){ // P19 lateral CA of R / B, measured on the base frame, corrected on the merged RGB
        const auto caStarted=Clock::now();
        HybridCa ca;double caMs=0;
        if(caAhead.valid()){const auto built=caAhead.get();ca=built.first;caMs=built.second;} // P31: fitted during the alignment
        else {ca=hybridRawCa(input);caMs=millis(Clock::now()-caStarted);}
        char line[260];
        const bool worth=ca.ok&&(std::abs(ca.cornerPx[0])>=tune.caMinShift||std::abs(ca.cornerPx[1])>=tune.caMinShift)
                &&ca.rmsPx[0]<0.4f&&ca.rmsPx[1]<0.4f;
        std::snprintf(line,sizeof(line),"HYBRID RAW CA: R corner %+.2f px (rms %.2f, %d tiles) B corner %+.2f px (rms %.2f, %d tiles) -> %s, %.0f ms",
            ca.cornerPx[0],ca.rmsPx[0],ca.tiles[0],ca.cornerPx[1],ca.rmsPx[1],ca.tiles[1],
            worth?"corrected":!ca.ok?"not measurable":"below the threshold",caMs);
        report(line);
        if(worth)caModel=ca;
    }
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
    // grid 4 only for the Tetra sub-frames of hybridReconstructMosaic (their 4x grid is the sensor grid of the stream) and the native
    // mosaic merge (Tetra sensor grid, Quad Sabre 2x grid); grid 8 only for the native Tetra Sabre 2x grid (P56)
    int grid=requestedGrid;
    const int up2x=coarse2x&&grid>=2?2:1; // P118 Sabre 2x not worth it: merged on the coarse grid, resized 2x at the end
    if(up2x==2)grid/=2;
    const int outW=w*grid,outH=h*grid;
    in.large=large;
    // P213c: the native merge's rows to its consumer (native->rowsSink: the CPU chroma median) as they are read back, where nothing
    // between the merge and the shift to the RGGB origin below changes them: no R / B resample (P19), no merged DNG from these rows,
    // no 2x resize of a coarse grid (P118). The consumer shifts the rows itself (the same copies as shiftOrigin), so the output leaves
    // here shifted and consumed. The guard (after out: it goes first) stops the consumer on every way out, before out goes.
    HybridRowsSink* const sink=native&&native->rowsSink&&!caModel.ok&&!(mergedDng&&input.mergedDng)&&up2x==1?native->rowsSink:nullptr;
    struct SinkStop {HybridRowsSink* sink;~SinkStop(){if(sink)sink->stop();}} sinkStop{sink};
    bool sunk=false;
    // P31 (W1.7): the context goes to a helper thread after the merge (its buffers and programs take 19-57 ms to destroy), joined
    // before this function returns; the CPU tail below runs meanwhile.
    struct GpuTeardown {
        std::thread thread;double ms=0;
        void start(std::unique_ptr<HybridGpu> gpu){
            join();gpu->release();
            // A thread the system refuses must not fail the merge that already succeeded (gpuMerge(true) would merge again
            // without the local offsets): the context is then destroyed right here, as before P31.
            try{thread=std::thread([this,g=std::move(gpu)]() mutable {
                const auto t0=std::chrono::steady_clock::now();g.reset();
                ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();
            });}catch(const std::exception&){}
        }
        void join(){if(thread.joinable())thread.join();}
        ~GpuTeardown(){join();}
    } teardown;
    // P33 W2.2: the P19 R / B resample of the merged rows while the GPU merges the next strips (new bands for every attempt)
    std::unique_ptr<HybridCaBands> caBands;double caBandsMs=0;
    // P62 Mochi: the corrected bracketed frames (in.frames points at them once made; made once, a retry merges them again)
    std::vector<std::vector<uint16_t>> mochiRaw;bool mochiPending=tune.mochi>0;
    auto gpuMerge=[&](bool withLocalAlign){
        const auto gpuStarted=Clock::now();
        scam_progress::pass("gpu"); // P220: the context and its programs (each program a tick), then Mochi and the strips
        if(caModel.ok){
            caBands=std::make_unique<HybridCaBands>(w*grid,h*grid,grid,caModel);caBandsMs=0;
            in.rowsDone=[&](std::vector<float>& rgb,int rows){const auto t0=Clock::now();caBands->advance(rgb,rows);caBandsMs+=millis(Clock::now()-t0);};
        }
        if(sink){ // P213c: a new consumer for every attempt (begin stops the one of a failed attempt before the merge writes out again)
            sink->begin(outW,outH,(input.cfa&1)*grid,(input.cfa>>1)*grid);
            in.rowsDone=[sink](std::vector<float>& rgb,int rows){sink->rows(rgb.data(),rows);};
        }
        const bool blendPass=bento.active&&in.blend;
        const bool bentoPass=bento.active&&!in.blend&&tune.bentoChromaSigma>0.f&&tune.bentoChroma>0.f;
        std::unique_ptr<HybridGpu> held;std::string built;
        if(earlyGpu.valid()){ // P31 (W1.7): the context built during the front end, when it holds the programs this merge needs
            try{held=earlyGpu.get();}catch(const std::exception&){held.reset();} // built again below, as before
            {std::lock_guard<std::mutex> l(earlyLog->lock);for(const auto& line:earlyLog->lines)report(line);earlyLog->lines.clear();}
            if(held&&(held->localAlign!=withLocalAlign||(bentoPass&&!held->hasBentoPass())||(blendPass&&!held->hasBlendPass())
                      ||held->dcg!=hybridDcgZones(input)))held.reset(); // P320: the DCG two-zone programs or not
            if(held){
                try{held->acquire();built=" built ahead in "+std::to_string(int(earlyLog->ms))+" ms";}catch(const std::exception&){held.reset();}
            }
        }
        if(!held)held=std::make_unique<HybridGpu>(tune.rimRatio!=0,withLocalAlign,bentoPass,tune.chromaDiff>0.f,report,large,native!=nullptr,
                tune.hotSigma>0||tune.hotBaseSigma>0,blendPass,hybridDcgZones(input));
        HybridGpu& gpu=*held;gpu.trace=report;gpu.profile=tune.profile!=0;gpu.profilePasses=tune.profile>=2;
        report("HYBRID GPU: "+gpu.renderer+" frames="+std::to_string(in.frames.size())+" limits "+gpu.limits
            +" init ms="+std::to_string(int(millis(Clock::now()-gpuStarted)))+" (compile"+gpu.compileMs+")"+built);
        if(mochiPending){
            mochiPending=false;
            int bracketed=0,others=1;
            for(size_t i=1;i<in.frames.size();++i)++(in.role[i]==kRoleBracketed?bracketed:others);
            if(native)report("HYBRID MOCHI: off (native mosaic merge)");
            else if(!bracketed)report("HYBRID MOCHI: no bracketed frame in the merge");
            else if(tune.mochi==1&&others<=3)report("HYBRID MOCHI: off ("+std::to_string(others)+" non-bracketed frames, the rule needs more than 3; mochi 2 forces it)");
            else {
                mochiRaw.reserve(size_t(bracketed));
                scam_progress::pass("mochi",bracketed); // P220
                for(size_t i=1;i<in.frames.size();++i){
                    if(in.role[i]!=kRoleBracketed)continue;
                    struct Done{~Done(){scam_progress::step();}} done; // P220: each bracketed frame, corrected or not
                    HybridGpu::MochiStats st;mochiRaw.emplace_back();
                    try{
                        if(!gpu.mochi(in,int(i),tune.clipLevel,mochiRaw.back(),st)){
                            report("HYBRID MOCHI: frame "+std::to_string(index[i])+" not corrected (scaled homography or rows beyond the storage block)");
                            mochiRaw.pop_back();continue;
                        }
                    }catch(const std::exception& error){
                        while(glGetError()!=GL_NO_ERROR){}
                        report(std::string("HYBRID MOCHI: failed (")+error.what()+"); the remaining frames merge uncorrected");
                        mochiRaw.pop_back();break;
                    }
                    in.frames[i]=mochiRaw.back().data();
                    char line[260];
                    std::snprintf(line,sizeof(line),"HYBRID MOCHI: frame %d (TET x%.2f) corrected: %d of %d tiles measured, %d zeroed (|c|^2 > 500), mean |c| %.2f/%.2f/%.2f/%.2f DN10 per RAW phase, %d band(s), %.0f ms",
                        index[i],1.f/in.gain[i],st.measured,st.tiles,st.zeroed,st.meanAbsDn[0],st.meanAbsDn[1],st.meanAbsDn[2],st.meanAbsDn[3],st.bands,st.ms);
                    report(line);
                }
            }
        }
        // P150: a still burst (tripod, a phone on a table) samples the same sites in every frame. cellClip then takes the same
        // sites next to a clip away from every frame, and a thin dark detail in front of a clipped light loses every sample of a
        // colour phase: dashed wires, dotted edges and purple dots along a lit lamp (owner's chandelier, vivo V2419A, 2026-10-16;
        // the checker sits on one Bayer phase). With motion other frames hold those phases. A still burst merges per site
        // (synthetic still burst on the X200 Ultra: error at the detail 0.079 -> 0.034, chroma p99 0.24 -> 0.15; the green-clipped
        // sky of the magenta-rim case unchanged); a moving burst keeps cellClip (its merge unchanged).
        HybridTuning mergeTune=tune;
        // P334: frames the alignment refused (identity start of F6) say nothing about the hand's motion
        std::vector<BackwardHomography> movedH;
        for(size_t i=0;i<in.homography.size();++i)if(i==0||!idStart[size_t(index[i])])movedH.push_back(in.homography[i]);
        if(tune.cellClip&&tune.staticShift>0.f&&movedH.size()>1){
            const int donors=int(movedH.size())-1;float largest=0.f;
            const int moved=hybridMovedDonors(movedH,w,h,tune.staticShift,&largest);
            if(hybridStillBurst(moved,donors)){
                mergeTune.cellClip=0;
                char line[200];
                std::snprintf(line,sizeof(line),"HYBRID STILL: %d of %d donors moved >= %.2f px (largest %.2f px): cell clip off, per-site clip",
                    moved,donors,tune.staticShift,largest);
                report(line);
            }
        }
        if(movedH.size()>1) // P335: for the plain chroma median's share (any cellClip setting)
            stillSeen=hybridStillBurst(hybridMovedDonors(movedH,w,h,tune.staticShift>0.f?tune.staticShift:0.75f),int(movedH.size())-1)?1:0;
        gpu.merge(in,mergeTune,kernel,bento.active,out,effective,share,grid,clipFlags?&flagsRaw:nullptr,effMap!=nullptr);
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
        if(native&&outliers){ // P29: the binned line above counts binned sites; these are the native sites the merge skipped
            const double nmp=double(native->W)*native->H/1e6;
            std::snprintf(line,sizeof(line),"HYBRID MOSAIC NATIVE OUTLIERS: fixed-pattern sites=%ld (%.1f/MP) base transient=%ld (%.1f/MP)",
                gpu.natFixedOutliers,gpu.natFixedOutliers/nmp,gpu.natBaseOutliers,gpu.natBaseOutliers/nmp);
            report(line);
        }
        if(outliers&&gpu.capPerMP>0.f){ // P214k: the counts above are before the cap; the merge skipped the sites left after it
            char cl[320];
            const long kept=gpu.fixedOutliers-gpu.fixedCapped;
            int n=std::snprintf(cl,sizeof(cl),"HYBRID OUTLIER CAP: %.0f fixed-pattern sites/MP per strip: %d of %d strips over it, sites %ld -> %ld (%.1f/MP)",
                gpu.capPerMP,gpu.capStrips,gpu.stripCount,gpu.fixedOutliers,kept,kept/mp);
            if(native&&n>0&&n<int(sizeof(cl))){
                const double nmp=double(native->W)*native->H/1e6;const long nk=gpu.natFixedOutliers-gpu.natFixedCapped;
                std::snprintf(cl+n,sizeof(cl)-size_t(n),"; native %d of %d strips, sites %ld -> %ld (%.1f/MP)",gpu.natCapStrips,gpu.stripCount,
                    gpu.natFixedOutliers,nk,nk/nmp);
            }
            report(cl);
        }
        if(tune.rimRatio)std::snprintf(line,sizeof(line),"HYBRID RIM: clip-border colour from real-site ratios, pixels=%ld (%.2f %% of the output) sigma=%.2f ramp=%.3f..%.3f stride=%d",
            gpu.rimPixels,100.0*double(gpu.rimPixels)/(double(outW)*outH),tune.rimSigma,tune.rimLo,tune.rimHi,tune.rimStride);
        else std::snprintf(line,sizeof(line),"HYBRID RIM: off");
        report(line);
        if(!large)teardown.start(std::move(held)); // above kHybridClassicPixels destroyed here, as before (memory)
    };
    // P33 W2.1: the report of the streamed field (the same line as the whole-field path, after the merge)
    auto reportStream=[&]{
        if(!laStream||!laStream->finish())return;
        std::string per;double partMs[7]{},grayMs=0;
        for(int k=0;k<laStream->frameCount();++k){
            const auto& fr=laStream->frameAt(k);const LaFrameStats& st=fr.st;
            for(int q=0;q<7;++q)partMs[q]+=st.ms[q];
            char v[96];std::snprintf(v,sizeof(v)," %d:%.2f/%.2f/%.0f%%/z%.0f%%",fr.job.f,st.median,st.p90,100.f*st.accepted,100.f*st.motionShare);per+=v;
        }
        grayMs=partMs[0];
        char line[320];
        std::snprintf(line,sizeof(line),"HYBRID LOCAL ALIGN: %s, %d frames, tiles %dx%d (%d RAW px, window %d RAW px), %.0f ms (gray %.0f ms thread sum) mu=%.2f kappa=%.1f maxShift=%.1f medians=%d; per frame |r| median/p90 RAW px / refined tiles / tiles over the motion threshold (rejection boost):",
            tune.localAlign==2?"constant per tile":"bilinear field",laStream->frameCount(),laG0.nx,laG0.ny,2*laG0.stride,2*laG0.win,laStream->doneMs,grayMs,tune.laMu,tune.laKappa,tune.laMaxShift,tune.laMedian);
        report(line+per);
        std::snprintf(line,sizeof(line),"HYBRID F6 STREAM: phase 1 from the alignment, bands of %d tile rows alongside the merge: committed %.0f ms, phase 1 done %.0f ms, field done %.0f ms after the first frame; strips waited %.0f ms (%d waits)",
            LaStream::kBandRows,laStream->commitMs,laStream->phase1Ms,laStream->doneMs,laStream->waitMs,laStream->waits);
        if(laStream->windowed()){ // P48
            char more[96];std::snprintf(more,sizeof(more),"; windowed gray rows, %.0f MB held at most",laStream->peakRowMB());
            report(line+std::string(more));
        } else report(line);
        if(tune.profile){
            std::snprintf(line,sizeof(line),"HYBRID LOCAL ALIGN ms (thread sums): base=%.0f gray=%.0f down=%.0f L1=%.0f start=%.0f L0=%.0f accept=%.0f median=%.0f",
                laStreamBaseMs,partMs[0],partMs[1],partMs[2],partMs[3],partMs[4],partMs[5],partMs[6]);
            report(line);
        }
    };
    auto dropStream=[&]{if(laStream)laStream->stop();in.laStream=nullptr;};
    if(laOn){
        // The F6 programs need two more storage bindings (15, 16) and change the reject / dilate / merge / rim passes: a GPU that
        // refuses them (compile/link, bindings) or a pass that fails must not lose the shot. Merge again with the homographies
        // only (the context of the failed attempt is destroyed first); a 6.1 kernel chosen only for the alignment goes as well.
        // P29: a failed native mosaic merge is not retried here (its programs, its slot 12 or its long passes fail without F6 as well,
        // and a retry costs the whole native merge again): hybridReconstructMosaicNative merges the burst on the split instead.
        try{gpuMerge(true);reportStream();}
        catch(const HybridLaStreamFailed& error){ // P33 W2.1: F6 failed while the merge ran: as a failed whole-field F6 before it
            dropStream();
            report(std::string("HYBRID LOCAL ALIGN: failed (")+error.what()+"); merging with the homographies only");
            in.laField=nullptr;in.laMotion=nullptr;in.laMode=0;in.laMaxY.clear();laOn=false;
            dropDay61("it was chosen for the local alignment, which gave no field");
            gpuMerge(false);
        }
        catch(const std::exception& error){
            dropStream();
            if(native)throw;
            report(std::string("HYBRID LOCAL ALIGN: GPU merge with the local offsets failed (")+error.what()+"); merging with the homographies only");
            in.laField=nullptr;in.laMotion=nullptr;in.laMode=0;in.laMaxY.clear();laOn=false;
            dropDay61("it was chosen for the local alignment");
            gpuMerge(false);
        }
    } else gpuMerge(false);
    if(sink){sunk=sink->finish(out.data());in.rowsDone=nullptr;} // P213c: the last rows (the consumer reports its wait itself)
    scam_progress::pass("finish"); // P220: CA, the DNG RAW, the maps and flags of the merged result
    if(caModel.ok){ // P19: R and B of the merged RGB moved onto G (the frames all carry the lens's CA, the merge keeps it)
        const auto caStarted=Clock::now();
        const int during=caBands?caBands->done():0;
        if(caBands)caBands->advance(out,outH); else hybridCorrectCa(out,outW,outH,grid,caModel);
        in.rowsDone=nullptr;caBands.reset();
        report("HYBRID RAW CA: R / B resampled on the "+std::to_string(outW)+"x"+std::to_string(outH)+" result in "+std::to_string(int(millis(Clock::now()-caStarted)+caBandsMs))
            +" ms ("+std::to_string(during)+" rows during the merge, "+std::to_string(int(millis(Clock::now()-caStarted)))+" ms after it)");
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
    if(grid>1)report("HYBRID OUTPUT: Sabre "+std::to_string(grid)+"x grid "+std::to_string(outW)+"x"+std::to_string(outH)+" (kernel in sensor px)");
    stats.mergeMs=millis(Clock::now()-mergeStarted);
    stats.merged=int(in.frames.size());
    stats.mergedNormals=0;for(auto r:in.role)stats.mergedNormals+=r==kRoleNormal?1:0;
    if(!preset&&!native)hybridSeriesMerged(tune,input,stats.merged,stats.mergedNormals,report);
    {
        std::string line="HYBRID MERGE FACTORS:";
        double avg=0;int cnt=0;
        for(size_t i=1;i<share.size();++i){char v[48];std::snprintf(v,sizeof(v)," %d:%.2f",index[i],share[i]);line+=v;avg+=share[i];++cnt;}
        report(line+(cnt?"  average="+std::to_string(avg/cnt):""));
    }
    // ---- merged Bayer RAW for the DNG (sensor layout, 16 bit over the headroom: P165), from the RGB
    if(mergedDng&&input.mergedDng){
        const std::vector<float>& srcRgb=grid==2?sensorRgb:out;
        const int dx=input.cfa&1,dy=input.cfa>>1;
        const float headroom=hybridWriteMergedDng(*mergedDng,w,h,input,[&](int x,int y,int c){
            int cx=x-dx,cy=y-dy;if(cx<0)cx+=2;if(cy<0)cy+=2;
            return srcRgb[(size_t(cy)*w+cx)*3+c];
        },tune.dngDither!=0);
        report(hybridDngReport(headroom,w,h,hybridDngCeiling(input),tune.dngDither!=0));
    }
    std::vector<float>().swap(sensorRgb); // the output stage holds no more than it needs (any RAW size: ~16 B per output pixel)
    // CFA phase shift to the canonical RGGB origin, in output-grid pixels (P213c: done by the consumer of the rows when it took them)
    if(!sunk)shiftOrigin(out,outW,outH,3,(input.cfa&1)*grid,(input.cfa>>1)*grid);
    if(!sunk&&!native&&tune.plainChromaMedian>0){
        const auto t0=Clock::now();std::string how;
        // P335: a hand-held burst (its donors moved) samples every colour phase at sub-pixel offsets: its colour is real down to
        // ~1 px and the median (5 taps 1 px apart) took fine colour texture away; it keeps plainChromaHand of the median's
        // change there (R, B kept as halves meanwhile: 4 B per pixel), a still burst (P269's case: a resting phone, one frame's
        // demosaic colour along slanted edges) the whole median
        const float keepMedian=stillSeen==0?std::clamp(tune.plainChromaHand,0.f,1.f):1.f;
        std::vector<uint16_t> rb;
        const size_t px=size_t(outW)*outH;
        if(keepMedian>0.f&&keepMedian<1.f){
            rb.resize(px*2);
            mergeRowBands(outH,[&](int y0,int y1){
                for(size_t i=size_t(y0)*outW;i<size_t(y1)*outW;++i){rb[2*i]=floatToHalf(out[3*i]);rb[2*i+1]=floatToHalf(out[3*i+2]);}
            });
        }
        if(keepMedian>0.f)mosaicChromaMedian(out,outW,outH,std::max(1,tune.plainChromaMedian)*grid,nullptr,tune.chromaGpu>0,&how,0,nullptr);
        if(!rb.empty()){
            mergeRowBands(outH,[&](int y0,int y1){
                for(size_t i=size_t(y0)*outW;i<size_t(y1)*outW;++i){
                    const float r=hybridHalfToFloat(rb[2*i]),b=hybridHalfToFloat(rb[2*i+1]);
                    out[3*i]=r+keepMedian*(out[3*i]-r);out[3*i+2]=b+keepMedian*(out[3*i+2]-b);
                }
            });
        }
        report("HYBRID PLAIN: chroma median (block "+std::to_string(tune.plainChromaMedian)+", "+(keepMedian>0.f?how:std::string("skipped"))
            +(stillSeen==0?", hand-held: x"+std::to_string(keepMedian):stillSeen==1?", still burst: whole":"")+") "
            +std::to_string(int(millis(Clock::now()-t0)))+" ms");
    }
    if(effMap){
        std::vector<float> sample;sample.reserve(effective.size()/7+1);
        for(size_t i=0;i<effective.size();i+=7)sample.push_back(effective[i]);
        float median=1.f;
        if(!sample.empty()){std::nth_element(sample.begin(),sample.begin()+sample.size()/2,sample.end());median=std::max(sample[sample.size()/2],1.f);}
        const float codeScale=64.f/median;
        effMap->assign(size_t(outW)*outH,0);
        const int dx=(input.cfa&1)*grid,dy=(input.cfa>>1)*grid;
        mergeRowBands(outH,[&](int y0,int y1){ // P30: rows on all cores (the same codes)
            for(int y=y0;y<y1;++y)for(int x=0;x<outW;++x){
                const size_t src=size_t(std::max(0,y-dy))*outW+std::max(0,x-dx);
                (*effMap)[size_t(y)*outW+x]=uint8_t(std::clamp(std::lround(effective[src]*codeScale),1L,255L));
            }
        });
        report("HYBRID EFFECTIVE MAP: median frames="+std::to_string(median)+" code scale="+std::to_string(codeScale));
    }
    std::vector<float>().swap(effective);
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
        std::mutex histLock;
        mergeRowBands(outH,[&](int y0,int y1){ // P30: rows on all cores, one histogram per band
            long local[256]{};
            const int cw=w/2,ch=h/2;
            for(int y=y0;y<y1;++y)for(int x=0;x<outW;++x){
                uint8_t v=flagsRaw.empty()?0:flagsRaw[size_t(std::max(0,y-dy))*outW+std::max(0,x-dx)];
                // P309: the ramp spreads the mask (m > 0) over a clip's glow; the post's "inside the Bento mask" (bit 4: the clip
                // level of the ultrashort frames, the light statistics of hlrecovery) stays where the previous mask was
                if((v&16)!=0&&bentoPrevOn.size()==size_t(cw)*size_t(ch)){
                    const size_t c=size_t(std::min(ch-1,std::max(0,y-dy)/(2*grid)))*size_t(cw)+size_t(std::min(cw-1,std::max(0,x-dx)/(2*grid)));
                    if(!bentoPrevOn[c])v&=uint8_t(~16u);
                }
                (*clipFlags)[size_t(y)*outW+x]=v;
                ++local[v]; // 50 M pixels on the 2x grid: one increment each, the bit counts from the histogram
            }
            std::lock_guard<std::mutex> lock(histLock);
            for(int v=0;v<256;++v)hist[v]+=local[v];
        });
        for(int v=1;v<256;++v)for(int k=0;k<7;++k)if(v&(1<<k))counts[k]+=hist[v];
        report("HYBRID CLIP FLAGS: trailer "+std::to_string(outW)+"x"+std::to_string(outH)+" uint8 (bits R,G,B clipped mean, border, Bento, ultrashort) R="
            +std::to_string(counts[0])+" G="+std::to_string(counts[1])+" B="+std::to_string(counts[2])+" border="+std::to_string(counts[3])
            +" bento="+std::to_string(counts[4])+" ultrashort="+std::to_string(counts[5])+(counts[6]?" rimChecked="+std::to_string(counts[6]):std::string()));
    }
    std::vector<uint8_t>().swap(flagsRaw);
    if(tune.profile&&teardown.thread.joinable()){ // P31 (W1.7): what the helper thread took off the merge
        const auto waitStarted=Clock::now();teardown.join();
        report("HYBRID GPU: context destroyed on a helper thread in "+std::to_string(int(teardown.ms))+" ms (waited "
            +std::to_string(int(millis(Clock::now()-waitStarted)))+" ms for it)");
    }
    report("HYBRID STAGES ms: align="+std::to_string(stats.alignMs)+" localAlign="+std::to_string(stats.localAlignMs)+" mask="+std::to_string(stats.maskMs)+" merge="+std::to_string(stats.mergeMs)
        +" total="+std::to_string(millis(Clock::now()-started))+" merged="+std::to_string(stats.merged)+" droppedBracketed="+std::to_string(stats.droppedBracketed)
        +" bento="+std::to_string(stats.bento));
    if(up2x==2){ // P118: the coarse-grid result to the requested grid (output X at coarse (X + 0.5) / 2 - 0.5)
        const int W2=outW*2,H2=outH*2;
        std::vector<float> up(size_t(W2)*H2*3);
        mergeRowBands(H2,[&](int y0,int y1){
            for(int Y=y0;Y<y1;++Y){
                const float fy=std::clamp((Y+0.5f)*0.5f-0.5f,0.f,float(outH-1));const int iy=std::min(int(fy),std::max(outH-2,0));const float wy=fy-iy;
                for(int X=0;X<W2;++X){
                    const float fx=std::clamp((X+0.5f)*0.5f-0.5f,0.f,float(outW-1));const int ix=std::min(int(fx),std::max(outW-2,0));const float wx=fx-ix;
                    const size_t a=(size_t(iy)*outW+ix)*3,o=(size_t(Y)*W2+X)*3,dn=outH>1?size_t(outW)*3:0,rt=outW>1?3:0;
                    for(int c=0;c<3;++c)up[o+c]=(out[a+c]*(1-wx)+out[a+rt+c]*wx)*(1-wy)+(out[a+dn+c]*(1-wx)+out[a+dn+rt+c]*wx)*wy;
                }
            }
        });
        out.swap(up);
        auto nearest=[&](std::vector<uint8_t>* v){
            if(!v||v->size()!=size_t(outW)*outH)return;
            std::vector<uint8_t> u(size_t(W2)*H2);
            for(int Y=0;Y<H2;++Y)for(int X=0;X<W2;++X)u[size_t(Y)*W2+X]=(*v)[size_t(std::min(Y/2,outH-1))*outW+std::min(X/2,outW-1)];
            v->swap(u);
        };
        nearest(effMap);nearest(clipFlags);
        report("HYBRID SABRE 2X: merged "+std::to_string(outW)+"x"+std::to_string(outH)+", resized to "+std::to_string(W2)+"x"+std::to_string(H2));
    }
    if(statsOut)*statsOut=stats;
    return out;
}

// ---------------------------------------------------------------------------------------------
// P14 / P15: colour-block mosaics (Quad 2x2, Tetra 4x4: a sensor mode without remosaic) merged straight into RGB.
// v11 QuadBayerRgbMerge (the v11 quad_bayer_rgb_merge.cl in research): align on the binned image, accumulate every
// frame's own raw sites into RGB. Here without a new merge program (kHybMergeMain1 only got the general grid): a block-b mosaic frame is b^2
// plain Bayer frames of (w/b) x (h/b), one per site position (a, c) inside the colour block. Sub-frame (a, c) holds site
// (b I + a, b J + c) of every block (I, J), which carries the Bayer colour of the block. The sub-frames are one exposure at known
// offsets, so their homographies are the binned frame's homography plus the site offset. The plain-Bayer merge of these sub-frames
// on its b x grid (2x for Quad, 4x for Tetra) accumulates exactly the raw sites at their sensor positions into an RGB of w x h,
// and its kernel works on the colour lattice of the mosaic (the Bayer lattice of the sub-frames), so the 6.1 window never misses a
// colour. Output pixel X sits at sensor position b ((X + 0.5) / b - 0.5) = X - (b-1)/2: a constant offset against the sensor grid.
struct MosaicDetect { int block=1; int votes[3]{}; int tiles=0; bool confident=false; };
// Every 8x8 tile of the frame centre is fitted to the colour-block models of block 1, 2, 4 (each the mean of its four phase
// classes inside the tile). The CFA's own model leaves noise and texture only; a wrong one also the colour step between the
// classes. A tile votes when its best model leaves at most 1/3 of the residual of the next one; periodic scene detail at a period
// dividing 8 (bars, a zone plate) can vote for a wrong model, but only where it is (summed phase means carried it into the whole
// answer: a test chart read as no clear model). Confident with >= 50 votes and 60 % for one block; clipped / black tiles skipped.
// The same model as MosaicBlockDetector.java.
inline MosaicDetect detectMosaicBlock(const uint16_t* raw,int w,int h,const std::array<float,4>& black,float white){
    MosaicDetect d;
    if(!raw||w<64||h<64)return d;
    const int x0=(w/10)&~7,x1=(w*9/10)&~7,y0=(h/10)&~7,y1=(h*9/10)&~7;
    const double bl=0.25*(black[0]+black[1]+black[2]+black[3]);
    const double clip=bl+0.95*(double(white)-bl);
    const int blocks[3]={1,2,4};
    auto cls=[](int k,int b){return ((((k>>3)/b)&1)<<1)|(((k&7)/b)&1);};
    double t[64];
    for(int ty=y0;ty+8<=y1;ty+=8)for(int tx=x0;tx+8<=x1;tx+=8){
        double s=0;bool clipped=false;
        for(int y=0;y<8;++y){const uint16_t* r=raw+size_t(ty+y)*w+tx;for(int x=0;x<8;++x){if(r[x]>=clip)clipped=true;t[(y<<3)|x]=double(r[x])-bl;s+=t[(y<<3)|x];}}
        if(clipped||!(s>128.0))continue;
        ++d.tiles;
        double res[3]{};
        for(int i=0;i<3;++i){
            double cs[4]{};
            for(int k=0;k<64;++k)cs[cls(k,blocks[i])]+=t[k];
            for(double& c:cs)c/=16.0;
            for(int k=0;k<64;++k){const double e=t[k]-cs[cls(k,blocks[i])];res[i]+=e*e;}
        }
        int best=0;for(int i=1;i<3;++i)if(res[i]<res[best])best=i;
        double second=1e300;for(int i=0;i<3;++i)if(i!=best)second=std::min(second,res[i]);
        if(res[best]*3.0<second)++d.votes[best];
    }
    const int total=d.votes[0]+d.votes[1]+d.votes[2];
    int best=0;for(int i=1;i<3;++i)if(d.votes[i]>d.votes[best])best=i;
    d.confident=total>=50&&d.votes[best]>=0.6*total;
    d.block=d.confident?blocks[best]:1;
    return d;
}
inline int hybridMosaicBlock(const HybridInput& input,const HybridTuning& tune,const std::function<void(const std::string&)>& report){
    scam_progress::pass("mosaic-detect"); // P220
    if(tune.mosaicBlock==1||tune.mosaicBlock==2||tune.mosaicBlock==4){
        if(tune.mosaicBlock>1)report("HYBRID MOSAIC: block "+std::to_string(tune.mosaicBlock)+" forced by tuning");
        return tune.mosaicBlock;
    }
    // P219 (speed B1): the block of the header is the answer (the app measured it on the same frame or stored it for the sensor
    // mode); the detector ran over every 8x8 tile of the frame centre only for this log line - 0.5-0.7 s per 50 MP shot, 2.5 s at
    // 200 MP (owner's logs of 2026-10-17)
    if(input.mosaic==2||input.mosaic==4){
        report("HYBRID MOSAIC: header block "+std::to_string(input.mosaic)+", detection skipped (the app's block)");
        return input.mosaic;
    }
    const MosaicDetect d=detectMosaicBlock(input.frames[0].raw,input.w,input.h,input.black,input.white);
    char line[240];
    std::snprintf(line,sizeof(line),"HYBRID MOSAIC: header block %d, measured block %d (%s; tile votes b1/b2/b4 %d/%d/%d of %d tiles)",
        input.mosaic,d.block,d.confident?"confident":"no clear answer",d.votes[0],d.votes[1],d.votes[2],d.tiles);
    report(line);
    return d.block;
}
// Relative response of the 64 site classes (y&7, x&7) of the mosaic, from the normal frames given: per smooth 8x8 tile (every site
// within 5 % rms of its colour's tile mean, nothing clipped, above the noise) the ratio of each site to the mean of its colour in
// the tile, averaged over the tiles. Class means over the whole frame (mosaicSiteGain of SCAM HDR) took periodic scene detail
// (bars, a zone plate at a period dividing 8) for a response difference: a 4-8 % false lattice on a test chart. Returned as the
// factor that brings a class to its colour's mean.
// P188 (owner, 2026-10-17: «На всех isz и фулрезах я получаю вот такие артефакты сетки»): the vivo X200 Ultra's sensors have a
// fixed response pattern over the 64 classes (the HP9 Tetra telephoto 0.85..1.09, the same in every frame). The mean of the 5 % rms
// tiles found no tile in a dark scene (ISO 800 10x: no correction, and the native merge, whose kernel reads each site of a colour
// block, drew every site's own gain as a 2 / 4 / 8 px lattice: 600x the background at 2 px), and with a large spread it took the
// tiles where the noise happened to hide the pattern (the gains came out too small). Mode 2: the MEDIAN of the per-tile ratios
// (a histogram per class: robust to detail and noise, any number of tiles) in two rounds - every tile within the levels whose
// colours vary by less than 25 % rms, then, with the first gains divided out, the tiles whose remaining spread is within twice the
// frame's own noise (its noise model) plus 3 % rms. The ratios counted are always the raw ones; a frame above ~200k tiles is
// sampled (every step-th tile).
// P268: the gain field from the zones' sums (sum T / sum pred per class, normalised to the colour group's mean in the zone as the
// global set is; zones with at least 24 tiles). The response of a 2x2-OCL sensor changes smoothly over the field (the chief ray
// angle), the scene's bias of a zone does not (a test chart's patches: steps of 8 % from one zone to the next): each class is a
// cubic in the zone position, fitted with Tukey weights (3 rounds, scale 1.48 x the median residual), so zones the scene biased
// drop out and the holes are the cubic too. rmsDev: the fitted field against the global set over the measured zones (the signal);
// noise: the measured zones' rms residual from it. False with too few zones.
inline bool hybridGainFieldFromZones(HybridGainField& out,const std::vector<double>& sT,const std::vector<double>& sP,
                                     const std::vector<int>& tiles,const int* grpOf,const std::array<float,64>& global){
    const int zx=out.zx,zy=out.zy,Z=zx*zy;
    std::vector<double> r(size_t(Z)*64,1.0);
    std::vector<int> mz;
    out.measured=0;out.rmsDev=0;out.noise=0;
    for(int z=0;z<Z;++z){
        if(tiles[z]<24)continue;
        double gm[5]{};int cnt[5]{};bool bad=false;
        for(int k=0;k<64&&!bad;++k){
            const double p=sP[size_t(z)*64+k];
            if(!(p>1e-12)){bad=true;break;}
            r[size_t(z)*64+k]=sT[size_t(z)*64+k]/p;gm[grpOf[k]]+=r[size_t(z)*64+k];++cnt[grpOf[k]];
        }
        if(bad)continue;
        for(int k=0;k<64;++k){const int g=grpOf[k];r[size_t(z)*64+k]/=gm[g]/cnt[g];}
        mz.push_back(z);
    }
    out.measured=int(mz.size());
    if(out.measured<std::max(20,Z/4))return false;
    constexpr int T=10; // 1, u, v, u2, uv, v2, u3, u2v, uv2, v3
    auto basis=[&](int z,double* b){
        const double u=(double(z%zx)+0.5)/zx*2.0-1.0,v=(double(z/zx)+0.5)/zy*2.0-1.0;
        b[0]=1;b[1]=u;b[2]=v;b[3]=u*u;b[4]=u*v;b[5]=v*v;b[6]=u*u*u;b[7]=u*u*v;b[8]=u*v*v;b[9]=v*v*v;
    };
    std::vector<double> B(mz.size()*T);
    for(size_t i=0;i<mz.size();++i)basis(mz[i],&B[i*T]);
    std::vector<double> fit(size_t(Z)*64,1.0);
    double res2=0,dev2=0;long resN=0;
    for(int k=0;k<64;++k){
        std::vector<double> w(mz.size());
        for(size_t i=0;i<mz.size();++i)w[i]=std::min(double(tiles[mz[i]]),400.0);
        double c[T]{};
        for(int round=0;round<4;++round){
            double A[T][T+1]{};
            for(size_t i=0;i<mz.size();++i){
                if(w[i]<=0)continue;
                const double* b=&B[i*T];const double y=r[size_t(mz[i])*64+k];
                for(int p=0;p<T;++p){for(int q=0;q<T;++q)A[p][q]+=w[i]*b[p]*b[q];A[p][T]+=w[i]*b[p]*y;}
            }
            for(int p=0;p<T;++p)A[p][p]+=1e-9*(A[0][0]+1.0); // a tiny ridge: a degenerate layout stays solvable
            bool singular=false;
            for(int p=0;p<T&&!singular;++p){ // Gaussian elimination with partial pivoting
                int piv=p;for(int q=p+1;q<T;++q)if(std::abs(A[q][p])>std::abs(A[piv][p]))piv=q;
                if(std::abs(A[piv][p])<1e-15){singular=true;break;}
                if(piv!=p)for(int q=0;q<=T;++q)std::swap(A[p][q],A[piv][q]);
                for(int q=p+1;q<T;++q){const double m=A[q][p]/A[p][p];for(int t=p;t<=T;++t)A[q][t]-=m*A[p][t];}
            }
            if(singular)return false;
            for(int p=T-1;p>=0;--p){double v=A[p][T];for(int q=p+1;q<T;++q)v-=A[p][q]*c[q];c[p]=v/A[p][p];}
            if(round==3)break;
            std::vector<double> ad(mz.size());
            for(size_t i=0;i<mz.size();++i){double v=0;for(int p=0;p<T;++p)v+=c[p]*B[i*T+p];ad[i]=std::abs(r[size_t(mz[i])*64+k]-v);}
            std::vector<double> sorted(ad);std::nth_element(sorted.begin(),sorted.begin()+sorted.size()/2,sorted.end());
            const double scale=std::max(1.4826*sorted[sorted.size()/2],1e-4),cut=4.685*scale;
            for(size_t i=0;i<mz.size();++i){
                const double t=ad[i]/cut;
                w[i]=t>=1.0?0.0:std::min(double(tiles[mz[i]]),400.0)*(1.0-t*t)*(1.0-t*t);
            }
        }
        double lo=1e9,hi=-1e9;
        for(size_t i=0;i<mz.size();++i){const double y=r[size_t(mz[i])*64+k];lo=std::min(lo,y);hi=std::max(hi,y);}
        for(int z=0;z<Z;++z){
            double b[T];basis(z,b);double v=0;for(int p=0;p<T;++p)v+=c[p]*b[p];
            fit[size_t(z)*64+k]=std::clamp(v,lo,hi); // no extrapolation beyond the measured ratios
        }
        for(size_t i=0;i<mz.size();++i){
            const double e=r[size_t(mz[i])*64+k]-fit[size_t(mz[i])*64+k];res2+=e*e;
            const double d=fit[size_t(mz[i])*64+k]*double(global[k])-1.0;dev2+=d*d;++resN;
        }
    }
    out.rmsDev=resN?float(std::sqrt(dev2/resN)):0.f;
    out.noise=resN?float(std::sqrt(res2/resN)):0.f;
    out.g.assign(size_t(Z)*64,1.f);
    for(int z=0;z<Z;++z){
        double gm[5]{};int cnt[5]{};
        for(int k=0;k<64;++k){gm[grpOf[k]]+=fit[size_t(z)*64+k];++cnt[grpOf[k]];}
        for(int k=0;k<64;++k){const int g=grpOf[k];out.g[size_t(z)*64+k]=float(std::clamp(gm[g]/cnt[g]/fit[size_t(z)*64+k],0.75,1.33));}
    }
    return true;
}
inline std::array<float,64> hybridMosaicGainsRobust(const HybridInput& in,const std::vector<int>& frames,int block,float& spread,long* tilesUsed,
                                                   float* errOut=nullptr,HybridGainField* field=nullptr,int zonesAcross=24){
    // P188c (WhyCam 1.56 study, 2026-10-17: no site correction there; a simulation of the HP9 pattern, 16 frames: residual 0.11 %
    // against 0.50 % for the median of per-frame ratios): every picked tile is the MEAN of all the normal frames in SENSOR
    // coordinates (the pattern sits on the sensor and adds up, the hand-held scene blurs, the noise falls by sqrt(N)); each site is
    // predicted from its colour group's mean in the tile plus the gradient of that group between the neighbouring tiles (a tile
    // holds every class once, so its mean carries no pattern; a plane fitted inside it would eat the pattern's left / right part);
    // the gain of a class is the RATIO OF SUMS over the tiles (texture spreads it, it does not bias it), in two rounds: tiles
    // within 25 % rms, then with the first gains divided out within twice the mean's noise plus 10 % rms. Groups: R, B and the
    // two greens together (the merge pools them). errOut: half the rms difference of the estimates of even and odd tiles.
    const double bl=0.25*(in.black[0]+in.black[1]+in.black[2]+in.black[3]),range=double(in.white)-bl;
    const int greenParity=in.cfa==0||in.cfa==3?1:0;
    int grpOf[64];
    for(int k=0;k<64;++k){const int c=((((k>>3)/block)&1)<<1)|(((k&7)/block)&1);grpOf[k]=((c>>1)^(c&1))==greenParity?4:c;}
    const long tilesX=in.w/8,tilesY=in.h/8;
    const int n=int(frames.size());
    std::array<float,64> gain;gain.fill(1.f);spread=0;
    if(tilesUsed)*tilesUsed=0;
    if(errOut)*errOut=0.f;
    if(n<1||range<=0||tilesX<3||tilesY<3)return gain;
    // ~60k tiles at most (every tile reads its 4 neighbours too)
    const int step=std::max(1,int(std::ceil(std::sqrt(double(tilesX)*double(tilesY)/60000.0))));
    double slope=0,offset=0;
    for(int f:frames){slope+=std::max(0.0,double(in.frames[f].slope));offset+=std::max(0.0,double(in.frames[f].offset));}
    slope/=n;offset/=n;
    // mean of the frames over one tile; false when a site clipped in any frame
    auto tileMean=[&](long tx,long ty,double* t)->bool{
        for(int k=0;k<64;++k)t[k]=0;
        for(int f:frames){
            const uint16_t* data=in.frames[f].raw;
            for(int y=0;y<8;++y){
                const uint16_t* r=data+size_t(ty*8+y)*in.w+size_t(tx)*8;
                for(int x=0;x<8;++x){const double v=(double(r[x])-bl)/range;if(v>0.8)return false;t[(y<<3)|x]+=v;}
            }
        }
        for(int k=0;k<64;++k)t[k]/=n;
        return true;
    };
    auto groupMeans=[&](const double* t,double* m){
        int cnt[5]{};for(int g=0;g<5;++g)m[g]=0;
        for(int k=0;k<64;++k){m[grpOf[k]]+=t[k];++cnt[grpOf[k]];}
        for(int g=0;g<5;++g)if(cnt[g])m[g]/=cnt[g];
    };
    // the centre of each group's sites in the tile
    double cxg[5]{},cyg[5]{};{int cnt[5]{};for(int k=0;k<64;++k){cxg[grpOf[k]]+=k&7;cyg[grpOf[k]]+=k>>3;++cnt[grpOf[k]];}
        for(int g=0;g<5;++g)if(cnt[g]){cxg[g]/=cnt[g];cyg[g]/=cnt[g];}}
    std::array<double,64> g1;g1.fill(1.0);
    long used=0,used1=0;
    // P308 (owner, 2026-10-20: «нам нужно перевести всё на гпу»; «на качество ... не должно влиять»): a tile's means, its neighbours'
    // group means and its prediction do not change between the rounds: computed once per picked tile, on all cores (before: the two
    // rounds serially, every tile's 5 means of n frames read again in each, 1.5 s of a 50 MP shot), then the rounds and the
    // field's second pass over them in the tiles' order. The same arithmetic in the same order: the same gains and field.
    struct RespTile { int row;long tx,ty;double t[64],pred[64]; };
    const int tileRows=tilesY>2?int((tilesY-2+step-1)/step):0;
    std::vector<RespTile> picked;std::vector<size_t> rowStart(size_t(tileRows)+1,0);
    {
        std::vector<std::vector<RespTile>> perRow(static_cast<size_t>(tileRows));
        mergeRowBands(tileRows,[&](int r0,int r1){
            for(int r=r0;r<r1;++r){
                const long ty=1+long(r)*step;
                if(ty+1>=tilesY)continue;
                for(long tx=1;tx+1<tilesX;tx+=step){
                    RespTile c;c.row=r;c.tx=tx;c.ty=ty;
                    double tl[64],tr[64],tu[64],td[64];
                    if(!tileMean(tx,ty,c.t)||!tileMean(tx-1,ty,tl)||!tileMean(tx+1,ty,tr)||!tileMean(tx,ty-1,tu)||!tileMean(tx,ty+1,td))continue;
                    double m[5],ml[5],mr[5],mu[5],md[5];
                    groupMeans(c.t,m);groupMeans(tl,ml);groupMeans(tr,mr);groupMeans(tu,mu);groupMeans(td,md);
                    // each colour's mean above four times the noise of a mean over its sites and the frames
                    bool dim=false;
                    for(int k=0;k<64&&!dim;++k){
                        const int g=grpOf[k],cnt=g==4?32:16;
                        dim=m[g]<std::max(0.002,4.0*std::sqrt(std::max(slope*m[g]+offset,0.0)/(double(n)*cnt)));
                    }
                    if(dim)continue;
                    for(int k=0;k<64;++k){
                        const int g=grpOf[k];
                        const double gx=(mr[g]-ml[g])/16.0,gy=(md[g]-mu[g])/16.0;
                        c.pred[k]=m[g]+gx*((k&7)-cxg[g])+gy*((k>>3)-cyg[g]);
                    }
                    perRow[size_t(r)].push_back(c);
                }
            }
        });
        size_t total=0;for(const auto& v:perRow)total+=v.size();
        picked.reserve(total);
        for(int r=0;r<tileRows;++r){rowStart[size_t(r)]=picked.size();picked.insert(picked.end(),perRow[size_t(r)].begin(),perRow[size_t(r)].end());}
        rowStart[size_t(tileRows)]=picked.size();
    }
    // P268: the first round's sums per zone (zonesAcross across the longer side, square, whole 8 px tiles)
    std::vector<double> zT,zP;std::vector<int> zTiles;
    int zx=0,zy=0,zs=0;
    if(field){
        zs=std::max(64,((std::max(in.w,in.h)/std::clamp(zonesAcross,4,64))+7)&~7);
        zx=(in.w+zs-1)/zs;zy=(in.h+zs-1)/zs;
        zT.assign(size_t(zx)*zy*64,0.0);zP.assign(size_t(zx)*zy*64,0.0);zTiles.assign(size_t(zx)*zy,0);
    }
    std::array<double,64> sumT{},sumP{},sumTe{},sumPe{},sumTo{},sumPo{};
    for(int round=0;round<2;++round){
        sumT.fill(0);sumP.fill(0);sumTe.fill(0);sumPe.fill(0);sumTo.fill(0);sumPo.fill(0);used=0;
        for(const RespTile& c:picked){
            const long tx=c.tx,ty=c.ty;const double* t=c.t;const double* pred=c.pred;
            double e=0,l=0,nv=0;
            for(int k=0;k<64;++k){
                const double d=t[k]/g1[k]-pred[k];
                e+=d*d;l+=pred[k]*pred[k];nv+=(slope*std::max(t[k],0.0)+offset)/n;
            }
            if(round==0?e>0.25*0.25*l:e>2.0*nv+0.10*0.10*l)continue;
            const bool even=((tx/step)+(ty/step))%2==0;
            if(field&&round==0){
                const int z=std::min(zy-1,int((ty*8+4)/zs))*zx+std::min(zx-1,int((tx*8+4)/zs));
                for(int k=0;k<64;++k){zT[size_t(z)*64+k]+=t[k];zP[size_t(z)*64+k]+=pred[k];}
                ++zTiles[z];
            }
            for(int k=0;k<64;++k){
                sumT[k]+=t[k];sumP[k]+=pred[k];
                if(even){sumTe[k]+=t[k];sumPe[k]+=pred[k];}else{sumTo[k]+=t[k];sumPo[k]+=pred[k];}
            }
            ++used;
        }
        if(used<200)break;
        // per class sum T / sum pred, normalised to its group's mean (the colour means stay)
        auto estimate=[&](const std::array<double,64>& T,const std::array<double,64>& Pr,std::array<double,64>& out){
            double gm[5]{};int cnt[5]{};
            for(int k=0;k<64;++k){out[k]=Pr[k]>1e-12?T[k]/Pr[k]:1.0;gm[grpOf[k]]+=out[k];++cnt[grpOf[k]];}
            for(int k=0;k<64;++k){const int g=grpOf[k];if(cnt[g]&&gm[g]>0)out[k]/=gm[g]/cnt[g];}
        };
        estimate(sumT,sumP,g1);
        if(round==0)used1=used;
        else if(errOut){
            std::array<double,64> ge{},go{};estimate(sumTe,sumPe,ge);estimate(sumTo,sumPo,go);
            double s2=0;for(int k=0;k<64;++k)s2+=(ge[k]-go[k])*(ge[k]-go[k]);
            *errOut=float(0.5*std::sqrt(s2/64.0));
        }
    }
    if(used<200)used=used1;
    if(tilesUsed)*tilesUsed=used;
    if(used<200)return gain;
    float lo=1.f,hi=1.f;
    for(int k=0;k<64;++k){gain[k]=float(std::clamp(1.0/g1[k],0.75,1.33));lo=std::min(lo,gain[k]);hi=std::max(hi,gain[k]);}
    spread=hi-lo;
    if(field){
        HybridGainField f;f.zx=zx;f.zy=zy;f.zw=zs;f.zh=zs;
        bool built=hybridGainFieldFromZones(f,zT,zP,zTiles,grpOf,gain);
        // a second pass with that first field divided out and the global set's strict tile test (twice the mean's noise plus 10 %
        // rms): the textured tiles the first round let in carry the scene in their sites, which biases a zone (synthetic bursts
        // without any site pattern showed a 1.3 % "field" from them)
        if(built&&f.signal()>0.006f){
            std::fill(zT.begin(),zT.end(),0.0);std::fill(zP.begin(),zP.end(),0.0);std::fill(zTiles.begin(),zTiles.end(),0);
            // the bands' sums added in band order afterwards (the same field whatever the threads' timing)
            struct Band{int r0;std::vector<double> T,P;std::vector<int> n;};
            std::vector<Band> bands;std::mutex zLock;
            mergeRowBands(tileRows,[&](int r0,int r1){
            std::vector<double> lT(zT.size(),0.0),lP(zP.size(),0.0);std::vector<int> lTiles(zTiles.size(),0);
            for(size_t i=rowStart[size_t(r0)];i<rowStart[size_t(r1)];++i){
                const RespTile& c=picked[i];
                const long tx=c.tx,ty=c.ty;const double* t=c.t;const double* pred=c.pred;
                double e=0,l=0,nv=0;
                const int cx=int(tx*8+4),cy=int(ty*8+4);
                for(int k=0;k<64;++k){
                    const double d=t[k]*double(f.at(k,cx,cy))-pred[k];
                    e+=d*d;l+=pred[k]*pred[k];nv+=(slope*std::max(t[k],0.0)+offset)/n;
                }
                if(e>2.0*nv+0.10*0.10*l)continue;
                const int z=std::min(zy-1,int((ty*8+4)/zs))*zx+std::min(zx-1,int((tx*8+4)/zs));
                for(int k=0;k<64;++k){lT[size_t(z)*64+k]+=t[k];lP[size_t(z)*64+k]+=pred[k];}
                ++lTiles[z];
            }
            std::lock_guard<std::mutex> hold(zLock);
            bands.push_back(Band{r0,std::move(lT),std::move(lP),std::move(lTiles)});
            });
            std::sort(bands.begin(),bands.end(),[](const Band& a,const Band& b){return a.r0<b.r0;});
            for(const Band& band:bands){
                for(size_t i=0;i<zT.size();++i){zT[i]+=band.T[i];zP[i]+=band.P[i];}
                for(size_t i=0;i<zTiles.size();++i)zTiles[i]+=band.n[i];
            }
            HybridGainField f2;f2.zx=zx;f2.zy=zy;f2.zw=zs;f2.zh=zs;
            built=hybridGainFieldFromZones(f2,zT,zP,zTiles,grpOf,gain);
            if(built)f=std::move(f2);
        }
        // the field only where its zones differ from the global set beyond their noise (signal above 1 %): elsewhere the merge
        // stays as before
        field->measured=f.measured;field->rmsDev=f.rmsDev;field->noise=f.noise;field->zx=zx;field->zy=zy;field->zw=zs;field->zh=zs;
        if(built&&f.signal()>0.01f)field->g.swap(f.g);else field->g.clear();
    }
    return gain;
}
inline std::array<float,64> hybridMosaicGains(const HybridInput& in,const std::vector<int>& frames,int block,float& spread,long* tilesUsed=nullptr,int mode=1,float* err=nullptr,
                                              HybridGainField* field=nullptr,int zonesAcross=24){
    if(mode>=2)return hybridMosaicGainsRobust(in,frames,block,spread,tilesUsed,err,field,zonesAcross);
    std::array<double,64> sum{};long tiles=0;
    const double bl=0.25*(in.black[0]+in.black[1]+in.black[2]+in.black[3]),range=double(in.white)-bl;
    auto cls=[&](int k){return ((((k>>3)/block)&1)<<1)|(((k&7)/block)&1);};
    double t[64];
    for(int f:frames){
        const uint16_t* data=in.frames[f].raw;
        for(int ty=0;ty+8<=in.h;ty+=8)for(int tx=0;tx+8<=in.w;tx+=8){
            bool bad=false;
            for(int y=0;y<8&&!bad;++y){const uint16_t* r=data+size_t(ty+y)*in.w+tx;for(int x=0;x<8;++x){const double v=(double(r[x])-bl)/range;if(v<0.03||v>0.8){bad=true;break;}t[(y<<3)|x]=v;}}
            if(bad)continue;
            double m[4]{};for(int k=0;k<64;++k)m[cls(k)]+=t[k]/16.0;
            double e=0,l=0;for(int k=0;k<64;++k){const double d=t[k]-m[cls(k)];e+=d*d;l+=m[cls(k)]*m[cls(k)];}
            if(e>0.05*0.05*l)continue;
            for(int k=0;k<64;++k)sum[k]+=t[k]/m[cls(k)];
            ++tiles;
        }
    }
    if(tilesUsed)*tilesUsed=tiles;
    std::array<float,64> gain;gain.fill(1.f);spread=0;
    if(tiles<200)return gain; // too few smooth tiles: no correction
    // tile position (y&7, x&7) = sensor class: tiles start at multiples of 8
    float lo=1.f,hi=1.f;
    for(int k=0;k<64;++k){const double r=sum[k]/double(tiles);gain[k]=r>1e-6?float(std::clamp(1.0/r,0.75,1.33)):1.f;lo=std::min(lo,gain[k]);hi=std::max(hi,gain[k]);}
    spread=hi-lo;
    return gain;
}
// Continuous sign change of a pair of deviations p0, p1 with the swing gate (mosaicChromaMedian and the oscillation maps of its
// callers). It was a hard count (p0 p1 < 0 and |p0| + |p1| > gate), so a last-bit change of the merge at a zero crossing or at the
// gate flipped whole pixels of colour (the fast and the generic native Quad merges, 6e-7 apart, differed by up to 0.28 at ~0.05 %
// of the pixels on the OPPO, 2.3e-3 on syn_b2 on the vivo). Now a weight in [0, 1]: the opposition min(|p0|, |p1|) of opposite
// signs ramps to 1 over `tau` x gate, the swing |p0| + |p1| over 0.75..1.25 gate (smoothstep). A grating (p1 ~ -p0) and the two
// sides of an edge pass as before; a pair with one side near zero counts in part (the callers' thresholds make up for that).
inline float mosaicSoftSignChange(double p0,double p1,double gate,double tau){
    // a NaN deviation counts as no change, as in the hard test (p0 p1 < 0 is false); a NaN p1 alone would give a NaN weight
    if(!(gate>0.0)||std::isnan(p0)||std::isnan(p1))return 0.f;
    const double opp=std::max(0.0,std::min(p0,-p1))+std::max(0.0,std::min(-p0,p1));
    if(opp<=0.0)return 0.f;
    auto ss=[](double x){x=std::clamp(x,0.0,1.0);return x*x*(3.0-2.0*x);};
    return float(ss(opp/(tau*gate))*ss(((std::abs(p0)+std::abs(p1))/gate-0.75)/0.5));
}
// The oscillation maps of the base RAW sites (hybridReconstructMosaic / hybridReconstructMosaicNative, input side: independent of
// the merge) use a steep opposition ramp, so that they count almost as the hard test did; the luma count of the merged result
// (mosaicChromaMedian) a wide one, which keeps a last-bit difference of the merge a last-bit difference of the colour.
constexpr double kMosaicOscTau=0.05,kMosaicLumaTau=0.25;
// P187: the chroma median below takes the GPU only up to 16.8 MP of its result. P219: the mosaic merges build the context of
// chromaPrewarm only for a result within it (it was any sensor up to 4 x that: every 50 MP Quad merge built a context its CPU
// median never took, held to the process exit).
constexpr int64_t kChromaGpuPixels=16800000;
inline bool chromaGpuPossible(int64_t resultPixels){return resultPixels<=kChromaGpuPixels;}
// P213b: the CPU chroma median streams down the frame in steps of B rows (kMosaicChromaBandRows): every pass reads and writes
// rings of a few dozen rows instead of full-frame planes (P213a on the OPPO, 50 MP: 6 float planes besides rgb, 1.15 GB, and ~0.8 s
// of its 1.65 s went to allocating, first-touching and freeing them; the box passes streamed 3 planes each through DRAM). The
// values are the full-frame path's, bit for bit: every pass is the same per-value operations in the same order (the same float
// expressions, so the same FMA contractions of the compiler; the same median selections; the box sums in double with the same
// additions in the same order: a column sum runs from row 0 down the whole frame, carried from step to step, a row sum from
// column 0 along the whole row). The rows of the input a result row needs below itself: ratios, horizontal median, luma and its
// box rows 0; vertical median 2d; luma box columns and high-pass r1; sign map r1 + 1 (and one pixel to the right); its box
// r1 + 1 + r2; the ratio boxes 2d + r3 and 2d + 2 r3 = blend and rgb (Quad 14 rows, Tetra 28). A step advances every pass by at
// most B rows as far as its input allows; a ring holds B + 2d + 2 r3 + 2 rows (the most a pass reads back from the newest row a
// step wrote; tools/check_chroma_band.cpp checks every ring row read with SCAM_CHROMA_RING_CHECK). The bounded-rows strips above
// 64 MP (their column sums restarted at every strip: <= 7e-7 from the planes) are gone: every size gets the full-frame values.
// A step is six phases (rows: whole rows of one ring each; columns: bands of kMosaicChromaCols columns of one ring each) on a team
// of threads (the big cores, mosaicChromaThreads), items handed out one at a time, a spinning barrier between phases; every value
// is computed by one thread, in the same order whatever the thread count or B. The median, ratio, luma, column-sum and median-
// column loops vectorise as written (the compiler keeps the selects and the float operations); the sign map and the blend, scalar
// with data-dependent branches, run on NEON lanes as compare-and-select (mosaicSignRow, mosaicBlendRow).
constexpr int kMosaicChromaBandRows=32,kMosaicChromaCols=128;
// P213a / P213b: wall time of the phases of the streamed CPU chroma median (summed over the steps, on the calling thread: a phase
// ends when its slowest thread does), for one report line of the callers. start = rings, sums and threads; end = threads joined.
struct MosaicChromaPasses {
    enum Phase{kStart,kRows1,kCols1,kRows2,kCols2,kRows3,kCols3,kEnd,kCount};
    double ms[kCount]{};
    bool cpu=false;int steps=0,bandRows=0,threads=0,rings=0,ringRows=0,ringWidth=0;
    size_t bytes=0;                            // rings, column sums and the threads' scratch rows
    std::array<long,2> rssBefore{},rssAfter{}; // the callers: hybridRssMB() before and after the call (VmHWM after > before: a new peak)
    std::string text() const {
        double sum=0;for(double v:ms)sum+=v;
        char line[1000];
        std::snprintf(line,sizeof(line),"%.0f ms in %d steps of %d rows on %d threads: start %.0f, rows 1 (ratios, median rows, luma, its box rows) %.0f, "
            "columns 1 (median columns, luma box columns, high-pass) %.0f, rows 2 (box rows u v) %.0f, columns 2 (sign map, box columns u v) %.0f, "
            "rows 3 (box rows sign map, u v) %.0f, columns 3 (box columns sign map, u v, blend, rgb) %.0f, end %.0f; %d rings of %d rows x %d "
            "+ sums %.1f MB besides rgb; worker RSS %ld MB (peak %ld) before, %ld MB (peak %ld) after",
            sum,steps,bandRows,threads,ms[kStart],ms[kRows1],ms[kCols1],ms[kRows2],ms[kCols2],ms[kRows3],ms[kCols3],ms[kEnd],
            rings,ringRows,ringWidth,double(bytes)/1048576.0,rssBefore[0],rssBefore[1],rssAfter[0],rssAfter[1]);
        return line;
    }
};
// P213b: the threads of the streamed chroma median: the CPUs of at least half the largest capacity (sysfs cpu_capacity), at most
// 8. The phases end at a barrier, so a little core holding the last item holds every thread: on the OPPO X7U (8 Gen 3: two
// A520 of capacity 379 beside 867..1024) 6 threads took 390 ms where 8 took 460. Without capacities: the hardware threads.
inline int mosaicChromaThreads(){
    static const int threads=[]{
        const int n=int(std::clamp(std::thread::hardware_concurrency(),1u,64u));
        std::vector<long> capacity;
        for(int c=0;c<n;++c){
            std::ifstream f("/sys/devices/system/cpu/cpu"+std::to_string(c)+"/cpu_capacity");long v=0;
            if(!(f>>v)||v<=0)return std::min(n,8);
            capacity.push_back(v);
        }
        const long top=*std::max_element(capacity.begin(),capacity.end());
        return int(std::clamp<long>(std::count_if(capacity.begin(),capacity.end(),[&](long v){return 2*v>=top;}),1,8));
    }();
    return threads;
}
// P213b: the threads of one MosaicChromaStream::advance. body(id) runs on `want` threads (id 0 = the caller; fewer when a thread
// cannot be started), take() hands out the items of a phase, sync() ends the phase (a spinning barrier: the phases last ~0.1 ms;
// the last thread to arrive resets the items for the next one). body must not throw (it allocates nothing).
class MosaicChromaTeam {
    int n=1;
    std::atomic<int> arrived{0},sense{0},items{0};
public:
    int size() const {return n;}
    int take(){return items.fetch_add(1,std::memory_order_relaxed);}
    void sync(int& mine){
        mine^=1;
        if(n==1){items.store(0,std::memory_order_relaxed);return;}
        if(arrived.fetch_add(1,std::memory_order_acq_rel)==n-1){
            arrived.store(0,std::memory_order_relaxed);items.store(0,std::memory_order_relaxed);
            sense.store(mine,std::memory_order_release);
        } else {
            for(int spin=0;sense.load(std::memory_order_acquire)!=mine;++spin){
                if(spin>=4096)std::this_thread::yield();
#if defined(__aarch64__)
                else __asm__ __volatile__("yield");
#endif
            }
        }
    }
    template<class F> void run(int want,const F& body){
        std::vector<std::thread> helpers;
        std::atomic<bool> go{false};
        try{helpers.reserve(size_t(std::max(0,want-1)));}catch(const std::exception&){want=1;}
        for(int i=1;i<want;++i){
            try{helpers.emplace_back([&body,&go,i]{while(!go.load(std::memory_order_acquire))std::this_thread::yield();body(i);});}
            catch(const std::exception&){break;}
        }
        n=int(helpers.size())+1;
        go.store(true,std::memory_order_release);
        body(0);
        for(std::thread& t:helpers)t.join();
    }
};
// Median of five by selection only (no arithmetic: the same value nth_element picked, ~10x faster): x, y = the 2nd and 3rd of
// a..d, the median is e clamped to [x, y].
inline float mosaicMed5(float a,float b,float c,float d,float e){
    const float t1=std::min(a,b),t2=std::max(a,b),t3=std::min(c,d),t4=std::max(c,d);
    const float lo=std::max(t1,t3),hi=std::min(t2,t4);
    const float x=std::min(lo,hi),y=std::max(lo,hi);
    return std::max(x,std::min(y,e));
}
// The horizontal 5-point median of one row, taps d px apart (P76: the clamps only at the borders).
inline void mosaicMedianRow(const float* r,float* o,int w,int d){
    const int in0=std::min(2*d,w),in1=std::max(in0,w-2*d);
    auto edge=[&](int x){auto at=[&](int k){return r[std::clamp(x+k*d,0,w-1)];};o[x]=mosaicMed5(at(-2),at(-1),at(0),at(1),at(2));};
    for(int x=0;x<in0;++x)edge(x);
    for(int x=in0;x<in1;++x)o[x]=mosaicMed5(r[x-2*d],r[x-d],r[x],r[x+d],r[x+2*d]);
    for(int x=in1;x<w;++x)edge(x);
}
// The row pass of a box filter of radius r (borders clamped): a running double sum from column 0, the float difference of the
// samples entering and leaving added to it.
inline void mosaicBoxRow(const float* a,float* o,int w,int r){
    double acc=0;
    for(int x=-r;x<=r;++x)acc+=a[std::clamp(x,0,w-1)];
    for(int x=0;x<w;++x){o[x]=float(acc/(2*r+1));acc+=a[std::min(x+r+1,w-1)]-a[std::max(x-r,0)];}
}
// The sign-change map of one row of the luma high-pass, columns [x0, x1) (the false-colour suppression of mosaicChromaMedian):
// half the soft sign change towards the right neighbour (none in the last column) plus half towards the one below (none in the
// last row: below = nullptr), the gate 4 % of the luma.
inline void mosaicSignRow(const float* hp,const float* below,const float* L,float* o,int x0,int x1,int w){
    int x=x0;
#if defined(__aarch64__)&&!defined(SCAM_CHROMA_SCALAR) // (the check builds the scalar loops on the phone too: what CI on x86 runs)
    // P213b: four pixels at a time, branch-free (the scalar loop below: ~50 cycles per soft sign change, most of them mispredicted
    // branches on noise). Every lane the operations of the scalar code in the same order: mosaicSoftSignChange on two double
    // lanes (std::min / std::max / std::clamp as compare and select: the same values for NaN and signed zeros; its early
    // returns as a select of +0; 3 - 2x fused as the compiler contracts it), c += 0.5f s fused as well.
    const float32x4_t half=vdupq_n_f32(0.5f),floor=vdupq_n_f32(1e-4f),share=vdupq_n_f32(0.04f);
    auto soft=[](float64x2_t p0,float64x2_t p1,float64x2_t gate){
        const float64x2_t zero=vdupq_n_f64(0.0),one=vdupq_n_f64(1.0);
        auto lo=[](float64x2_t a,float64x2_t b){return vbslq_f64(vcltq_f64(b,a),b,a);}; // std::min(a, b)
        auto hi=[](float64x2_t a,float64x2_t b){return vbslq_f64(vcltq_f64(a,b),b,a);}; // std::max(a, b)
        auto ss=[&](float64x2_t v){
            v=vbslq_f64(vcltq_f64(v,zero),zero,vbslq_f64(vcltq_f64(one,v),one,v)); // std::clamp(v, 0, 1)
            return vmulq_f64(vmulq_f64(v,v),vfmsq_f64(vdupq_n_f64(3.0),vdupq_n_f64(2.0),v));
        };
        const uint64x2_t live=vandq_u64(vcgtq_f64(gate,zero),vandq_u64(vceqq_f64(p0,p0),vceqq_f64(p1,p1)));
        const float64x2_t opp=vaddq_f64(hi(zero,lo(p0,vnegq_f64(p1))),hi(zero,lo(vnegq_f64(p0),p1)));
        const uint64x2_t open=vandq_u64(live,vcgtq_f64(opp,zero));
        const float64x2_t a=ss(vdivq_f64(opp,vmulq_f64(vdupq_n_f64(kMosaicLumaTau),gate)));
        const float64x2_t b=ss(vmulq_f64(vsubq_f64(vdivq_f64(vaddq_f64(vabsq_f64(p0),vabsq_f64(p1)),gate),vdupq_n_f64(0.75)),vdupq_n_f64(2.0)));
        return vbsl_f32(vmovn_u64(open),vcvt_f32_f64(vmulq_f64(a,b)),vdup_n_f32(0.f));
    };
    auto both=[&](float32x4_t p0,float32x4_t p1,float32x4_t gate){
        return vcombine_f32(soft(vcvt_f64_f32(vget_low_f32(p0)),vcvt_f64_f32(vget_low_f32(p1)),vcvt_f64_f32(vget_low_f32(gate))),
                            soft(vcvt_high_f64_f32(p0),vcvt_high_f64_f32(p1),vcvt_high_f64_f32(gate)));
    };
    for(;x+4<=x1&&x+4<w;x+=4){
        const float32x4_t a=vld1q_f32(hp+x),l=vld1q_f32(L+x);
        const float32x4_t gate=vmulq_f32(share,vbslq_f32(vcltq_f32(l,floor),floor,l)); // 0.04f std::max(L, 1e-4f)
        float32x4_t c=vfmaq_f32(vdupq_n_f32(0.f),half,both(a,vld1q_f32(hp+x+1),gate));
        if(below)c=vfmaq_f32(c,half,both(a,vld1q_f32(below+x),gate));
        vst1q_f32(o+x,c);
    }
#endif
    for(;x<x1;++x){
        const float a=hp[x],gate=0.04f*std::max(L[x],1e-4f);float c=0;
        if(x+1<w)c+=0.5f*mosaicSoftSignChange(a,hp[x+1],gate,kMosaicLumaTau);
        if(below)c+=0.5f*mosaicSoftSignChange(a,below[x],gate,kMosaicLumaTau);
        o[x]=c;
    }
}
// P306 (owner, 2026-10-20, 200 MP Tetra: «фиолетовые обводки» - dotted pink outlines along every dark-on-bright edge): with taps
// d >= 2 apart (Tetra: 2 px) the five taps of the separable median are all of one column (row) parity, so the even and the odd
// columns were filtered apart; across an edge their medians differ and the colour alternated pixel by pixel (in the 200 MP chimney
// crop 79 % of the R/G second differences alternated in sign, 59 % before the median). The medians the blend takes are smoothed
// by [1 2 1] x [1 2 1] / 16 (zero at the 2 px period) for d >= 2: Tetra, and a Quad merged on the Sabre 2x grid (its block in
// output px is 4: OPPO q1x, edge pixels with a false colour above 5 % 25.3 -> 20.3 %, the alternation 0.58 -> 0.32 of the R/G
// second differences, the gradient of real colour edges -2.6 %); Quad on the sensor grid (d = 1) and plain Bayer are untouched.
// One row of it, columns [x0, x1), from the rows above (a), at (b) and below (c), borders clamped. Additions only (b + b, then left to right):
// no multiply-add the compiler could contract differently here and in the reference of tools/check_chroma_band_ref.h.
inline float mosaicTap121(const float* r,int x,int w){
    const float b=r[x],t=b+b;
    float s=r[std::max(x-1,0)]+t;
    s=s+r[std::min(x+1,w-1)];
    return s*0.25f;
}
inline void mosaicSmooth121Row(const float* a,const float* b,const float* c,float* o,int x0,int x1,int w){
    for(int x=x0;x<x1;++x){
        const float m=mosaicTap121(b,x,w),t=m+m;
        float s=mosaicTap121(a,x,w)+t;
        s=s+mosaicTap121(c,x,w);
        o[x]=s*0.25f;
    }
}
// Blend and rgb of one row, columns [x0, x1): the median ratios mu, mv go to their wide averages ul, vl (indexed from x0) by
// the ramp of the boxed sign-change map z (and at least by the oscillation weight kosc of the base sites, indexed from x0;
// nullptr: none), then R and B from the ratios and green (px: the row of rgb). e as in MosaicChromaStream.
inline void mosaicBlendRow(const float* z,const float* mu,const float* mv,const float* ul,const float* vl,const float* kosc,float* px,
                           int x0,int x1,float z0,float z1,float e){
    int X=x0;
#if defined(__aarch64__)&&!defined(SCAM_CHROMA_SCALAR)
    // P213b: four pixels at a time, the operations of the scalar loop below in the same order (clamp and max as compare and
    // select; 3 - 2t, u + k (ul - u) and u g - e fused as the compiler contracts them); green is stored back as it was read
    const float32x4_t zero=vdupq_n_f32(0.f),one=vdupq_n_f32(1.f),three=vdupq_n_f32(3.f),two=vdupq_n_f32(2.f);
    const float32x4_t vz0=vdupq_n_f32(z0),span=vdupq_n_f32(z1-z0),ve=vdupq_n_f32(e),vme=vdupq_n_f32(-e);
    for(;X+4<=x1;X+=4){
        const int j=X-x0;
        float32x4_t t=vdivq_f32(vsubq_f32(vld1q_f32(z+X),vz0),span);
        t=vbslq_f32(vcltq_f32(t,zero),zero,vbslq_f32(vcltq_f32(one,t),one,t));    // std::clamp(t, 0, 1)
        float32x4_t k=vmulq_f32(vmulq_f32(t,t),vfmsq_f32(three,two,t));
        if(kosc){const float32x4_t k2=vld1q_f32(kosc+j);k=vbslq_f32(vcltq_f32(k,k2),k2,k);} // std::max(k, k2)
        float32x4_t u=vld1q_f32(mu+X);u=vfmaq_f32(u,k,vsubq_f32(vld1q_f32(ul+j),u));
        float32x4_t v=vld1q_f32(mv+X);v=vfmaq_f32(v,k,vsubq_f32(vld1q_f32(vl+j),v));
        float32x4x3_t p=vld3q_f32(px+size_t(X)*3);
        const float32x4_t g=vaddq_f32(vbslq_f32(vcltq_f32(p.val[1],zero),zero,p.val[1]),ve); // std::max(G, 0) + e
        p.val[0]=vfmaq_f32(vme,u,g);p.val[2]=vfmaq_f32(vme,v,g);
        vst3q_f32(px+size_t(X)*3,p);
    }
#endif
    for(;X<x1;++X){
        const float t=std::clamp((z[X]-z0)/(z1-z0),0.f,1.f);float k=t*t*(3.f-2.f*t);
        if(kosc)k=std::max(k,kosc[X-x0]); // detail the merge already smoothed in the luma but the sites still show
        float u=mu[X];u+=k*(ul[X-x0]-u);float v=mv[X];v+=k*(vl[X-x0]-v);
        const float g=std::max(px[X*3+1],0.f)+e;px[X*3]=u*g-e;px[X*3+2]=v*g-e;
    }
}
// The chroma median of rgb (w x h x 3, in place) streamed in steps (see above). advance(ready): rows [0, ready) of rgb are final
// input, every step they allow runs (several calls may feed the rows as they come); rows [0, done()) then hold the result.
class MosaicChromaStream {
    enum Ring{kHu,kHv,kL,kBL,kHp,kMu,kMv,kBU1,kBV1,kSc,kU1,kV1,kBsc,kBU2,kBV2,kZ,kRings};
    enum Sum{kSumHp,kSumU1,kSumV1,kSumZ,kSumUl,kSumVl,kSums};
    // rows [0, x) done of: input read (ratios, horizontal medians, luma, its box rows), vertical medians, high-pass, sign map and
    // its box rows, its box columns (z), the first ratio boxes (u1), blend and rgb (out)
    struct Progress {
        int in=0,m=0,hp=0,sc=0,z=0,u1=0,out=0;
        bool operator==(const Progress& o) const {return in==o.in&&m==o.m&&hp==o.hp&&sc==o.sc&&z==o.z&&u1==o.u1&&out==o.out;}
    };
    float* rgb;int w,h,b,d,r1,r2,r3,B,cap,ws,nt,ntMax;
    // the oscillation map: block row J at osc + (J % oscCap) bw (the whole map: oscCap = bh; P213c: a ring of rows, oscTags = the
    // row every slot holds, checked with SCAM_CHROMA_RING_CHECK)
    const float* osc=nullptr;int bw=0,bh=0,oscCap=1;const int* oscTags=nullptr;float z0=0,z1=0;
    std::vector<float> store,scratch;float* rings=nullptr;size_t scratchPer=0;
    std::vector<double> sums;
    Progress p;
    MosaicChromaPasses* passes;
    // Chroma as ratios to green (camera RGB before WB: differences R-G of a bright neighbour put on a dark line drove R and B
    // below zero, a green rim on every thin dark line). e keeps the ratio of black pixels finite. The green term is max(G, 0) + e
    // (it was G + e: a pole at G = -e, where a last-bit change of a dark pixel swung its ratio from +inf to -inf); the
    // reconstruction uses the same term, so a ratio the passes leave unchanged gives back R and B exactly.
    static constexpr float e=0.002f;
#ifdef SCAM_CHROMA_RING_CHECK
    std::vector<int> tags; // the row every ring slot holds: a read of any other row aborts the check
    void mark(int k,int y0,int y1){for(int y=y0;y<y1;++y)tags[size_t(k)*cap+size_t(y%cap)]=y;}
    void expect(int k,int y) const {
        const int held=tags[size_t(k)*cap+size_t(y%cap)];
        if(held!=y){std::fprintf(stderr,"chroma ring %d: row %d read, the slot holds row %d\n",k,y,held);std::abort();}
    }
#endif
    float* put(int k,int y){return rings+(size_t(k)*cap+size_t(y%cap))*ws;}
    const float* get(int k,int y) const {
#ifdef SCAM_CHROMA_RING_CHECK
        expect(k,y);
#endif
        return rings+(size_t(k)*cap+size_t(y%cap))*ws;
    }
    // one row of the column pass of a box filter (radius r) of ring `in`, columns [x0, x1): the sums a[] are those of row y
    // afterwards (row 0: the clamped rows -r..r added; then the float difference of the row entering and the one leaving)
    void boxColumns(int in,int r,int y,int x0,int x1,double* a) const {
        if(y==0){
            for(int x=x0;x<x1;++x)a[x]=0;
            for(int k=-r;k<=r;++k){const float* s=get(in,std::clamp(k,0,h-1));for(int x=x0;x<x1;++x)a[x]+=s[x];}
        } else {
            const float* add=get(in,std::min(y+r,h-1));const float* sub=get(in,std::max(y-1-r,0));
            for(int x=x0;x<x1;++x)a[x]+=add[x]-sub[x];
        }
    }
    Progress next(const Progress& q,int ready) const {
        auto avail=[this](int made,int lag){return made>=h?h:std::max(0,made-lag);};
        auto step=[this](int from,int to){return std::max(from,std::min(to,from+B));};
        Progress n;
        n.in=step(q.in,ready);
        n.m=step(q.m,avail(n.in,2*d));
        n.hp=step(q.hp,avail(n.in,r1));
        n.sc=step(q.sc,avail(n.hp,1));
        n.z=step(q.z,avail(n.sc,r2));
        n.u1=step(q.u1,avail(n.m,r3));
        n.out=step(q.out,std::min(avail(n.u1,r3),n.z));
        return n;
    }
public:
    MosaicChromaStream(float* image,int width,int height,int block,const std::vector<float>* oscillation=nullptr,int bandRows=0,
                       int threads=0,MosaicChromaPasses* pass=nullptr)
        :rgb(image),w(std::max(0,width)),h(std::max(0,height)),b(std::max(1,block)),passes(pass){
        const auto t0=std::chrono::steady_clock::now();
        d=std::max(1,b/2);r1=b;r2=2*b;r3=3*b;
        B=bandRows>0?bandRows:kMosaicChromaBandRows;
        cap=B+2*d+2*r3+2+(d>=2?2:0); // P306: the [1 2 1] smoothing of the medians reads one row more back
        ws=(w+15)&~15; // rows of whole cache lines: the column bands of two threads never share one
        nt=threads>0?threads:(ScamPool::inWorker()||h<64)?1:mosaicChromaThreads();
        ntMax=nt;
        z0=0.51f/(2*b);z1=1.02f/(2*b);
        // P306 (owner, 2026-10-20: «нам важно чтобы артефакты ушли на гранях, но цветовые переходы при этом не были испорчены»):
        // taps d >= 2 (Tetra; Quad on the 2x grid): the ramp at half the counts. A post or wire narrower than the colour block turns the luma
        // high-pass twice and counted below the ramp's start: the railings of the owner's 200 MP ship kept red / blue stripes. Half
        // the ramp (OPPO replay of that burst): edge pixels with a false colour above 5 % 3.8 -> 1.5 % (its 50 MP shot: 1.4 %); the
        // letters' colour (red 'NO SMOKING', 'SAFETY FIRST') and the width of real colour transitions (an orange lifeboat on white
        // 13 px, the red hull on the sea 20 px) unchanged. The GPU path (scam-chroma-gpu.h) and tools/check_chroma_band_ref.h follow.
        if(d>=2){z0*=0.5f;z1*=0.5f;}
        bw=w/b;bh=h/b;
        if(oscillation&&bw>0&&bh>0&&oscillation->size()==size_t(bw)*bh){osc=oscillation->data();oscCap=bh;}
        store.assign(size_t(kRings)*cap*ws+16,0.f);
        rings=store.data()+((64-(reinterpret_cast<uintptr_t>(store.data())&63))&63)/sizeof(float);
        sums.assign(size_t(kSums)*ws,0.0);
        scratchPer=size_t(ws)*(d>=2?3:1)+3*size_t(kMosaicChromaCols)+16; // P306: + the smoothed medians (d >= 2)
        scratch.assign(scratchPer*size_t(nt),0.f);
#ifdef SCAM_CHROMA_RING_CHECK
        tags.assign(size_t(kRings)*cap,-1);
#endif
        if(passes){
            passes->cpu=true;passes->bandRows=B;passes->rings=kRings;passes->ringRows=cap;passes->ringWidth=ws;
            passes->bytes=(store.size()+scratch.size())*sizeof(float)+sums.size()*sizeof(double);
            passes->ms[MosaicChromaPasses::kStart]+=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-t0).count();
        }
    }
    int done() const {return p.out;}
    // P213c: the oscillation map as a ring of cap block rows (row J at ring + (J % cap) bw; the caller keeps the rows the next
    // advance() reads in it: block rows min(y / b, h / b - 1) of the result rows y it may finish); tags as oscTags above
    void oscRing(const float* ring,int rows,const int* tags=nullptr){
        if(ring&&bw>0&&bh>0&&rows>0){osc=ring;oscCap=rows;oscTags=tags;} else {osc=nullptr;oscTags=nullptr;}
    }
    // P213c: the threads of the next advance() calls, at most those of the constructor (every count gives the same floats)
    void threads(int n){nt=std::clamp(n,1,ntMax);}
    int threads() const {return nt;}
    // P213c: the image is set when its rows come (before the first advance()); rows a result row needs below itself (the stream
    // stops that far behind the rows it is given, until it is given the last one)
    void image(float* p){rgb=p;}
    int lagRows() const {return std::max(2*d+2*r3,r1+1+r2);}
    void advance(int ready){
        ready=std::clamp(ready,0,h);
        const Progress start=p;
        if(next(start,ready)==start)return;
        using Clock=std::chrono::steady_clock;
        constexpr int kCols=kMosaicChromaCols;
        const int nc=(w+kCols-1)/kCols;
        Progress end=start;
        MosaicChromaTeam team;
        Clock::time_point lapped=Clock::now();
        team.run(nt,[&](int id){
            using P=MosaicChromaPasses;
            auto lap=[&](int k){if(id==0&&passes){const auto now=Clock::now();passes->ms[k]+=std::chrono::duration<double,std::milli>(now-lapped).count();lapped=now;}};
            lap(P::kStart);
            int sense=0;
            float* ratio=scratch.data()+scratchPer*size_t(id);float* ul=ratio+ws;float* vl=ul+kCols;float* kosc=vl+kCols;
            float* smu=d>=2?kosc+kCols:nullptr;float* smv=d>=2?smu+ws:nullptr; // P306: the smoothed medians of a row (whole width)
            // the items [0, count) of one phase over the team, then the barrier
            auto phase=[&](int count,const auto& item){for(int k=team.take();k<count;k=team.take())item(k);team.sync(sense);};
#ifdef SCAM_CHROMA_RING_CHECK
            // the slots a phase writes are tagged before it starts (no phase reads a ring it writes, but z within one band)
            auto tag=[&](std::initializer_list<std::array<int,3>> list){if(id==0)for(const auto& t:list)mark(t[0],t[1],t[2]);team.sync(sense);};
#else
            auto tag=[](std::initializer_list<std::array<int,3>>){};
#endif
            Progress q=start;
            for(;;){
                const Progress n=next(q,ready);
                if(n==q)break;
                // rows 1: ratios u, v of a row and their horizontal medians; its luma and the luma's box rows (r1)
                tag({{kHu,q.in,n.in},{kHv,q.in,n.in},{kL,q.in,n.in},{kBL,q.in,n.in}});
                phase(3*(n.in-q.in),[&](int k){
                    const int y=q.in+k/3;const float* px=rgb+size_t(y)*w*3;
                    if(k%3==0){
                        for(int x=0;x<w;++x){const float g=std::max(px[x*3+1],0.f)+e;ratio[x]=(px[x*3]+e)/g;}
                        mosaicMedianRow(ratio,put(kHu,y),w,d);
                    } else if(k%3==1){
                        for(int x=0;x<w;++x){const float g=std::max(px[x*3+1],0.f)+e;ratio[x]=(px[x*3+2]+e)/g;}
                        mosaicMedianRow(ratio,put(kHv,y),w,d);
                    } else {
                        float* L=put(kL,y);
                        for(int x=0;x<w;++x)L[x]=0.25f*px[x*3]+0.5f*px[x*3+1]+0.25f*px[x*3+2];
                        mosaicBoxRow(L,put(kBL,y),w,r1);
                    }
                });
                lap(P::kRows1);
                // columns 1: vertical medians (the five rows clamped at the borders); luma box columns, high-pass = luma - box
                tag({{kMu,q.m,n.m},{kMv,q.m,n.m},{kHp,q.hp,n.hp}});
                phase(3*nc,[&](int k){
                    const int kind=k/nc,x0=(k%nc)*kCols,x1=std::min(w,x0+kCols);
                    if(kind<2){
                        const int in=kind?kHv:kHu,out=kind?kMv:kMu;
                        for(int y=q.m;y<n.m;++y){
                            const float* a0=get(in,std::clamp(y-2*d,0,h-1));const float* a1=get(in,std::clamp(y-d,0,h-1));
                            const float* a2=get(in,y);const float* a3=get(in,std::clamp(y+d,0,h-1));
                            const float* a4=get(in,std::clamp(y+2*d,0,h-1));float* o=put(out,y);
                            for(int x=x0;x<x1;++x)o[x]=mosaicMed5(a0[x],a1[x],a2[x],a3[x],a4[x]);
                        }
                    } else {
                        double* a=sums.data()+size_t(kSumHp)*ws;
                        for(int y=q.hp;y<n.hp;++y){
                            boxColumns(kBL,r1,y,x0,x1,a);
                            const float* L=get(kL,y);float* o=put(kHp,y);
                            for(int x=x0;x<x1;++x){const float box=float(a[x]/(2*r1+1));o[x]=L[x]-box;}
                        }
                    }
                });
                lap(P::kCols1);
                // rows 2: box rows (r3) of the medians u, v
                tag({{kBU1,q.m,n.m},{kBV1,q.m,n.m}});
                phase(2*(n.m-q.m),[&](int k){
                    const int y=q.m+k/2;
                    if(k%2==0)mosaicBoxRow(get(kMu,y),put(kBU1,y),w,r3); else mosaicBoxRow(get(kMv,y),put(kBV1,y),w,r3);
                });
                lap(P::kRows2);
                // columns 2: the sign map (False-colour suppression, see mosaicChromaMedian); box columns (r3) of u, v
                tag({{kSc,q.sc,n.sc},{kU1,q.u1,n.u1},{kV1,q.u1,n.u1}});
                phase(3*nc,[&](int k){
                    const int kind=k/nc,x0=(k%nc)*kCols,x1=std::min(w,x0+kCols);
                    if(kind==0){
                        for(int y=q.sc;y<n.sc;++y){
                            mosaicSignRow(get(kHp,y),y+1<h?get(kHp,y+1):nullptr,get(kL,y),put(kSc,y),x0,x1,w);
                        }
                    } else {
                        const int in=kind==1?kBU1:kBV1,out=kind==1?kU1:kV1;double* a=sums.data()+size_t(kind==1?kSumU1:kSumV1)*ws;
                        for(int y=q.u1;y<n.u1;++y){
                            boxColumns(in,r3,y,x0,x1,a);
                            float* o=put(out,y);for(int x=x0;x<x1;++x)o[x]=float(a[x]/(2*r3+1));
                        }
                    }
                });
                lap(P::kCols2);
                // rows 3: box rows of the sign map (r2) and of the boxed u, v (r3, the second box)
                tag({{kBsc,q.sc,n.sc},{kBU2,q.u1,n.u1},{kBV2,q.u1,n.u1}});
                const int signRows=n.sc-q.sc;
                phase(signRows+2*(n.u1-q.u1),[&](int k){
                    if(k<signRows){const int y=q.sc+k;mosaicBoxRow(get(kSc,y),put(kBsc,y),w,r2);return;}
                    const int y=q.u1+(k-signRows)/2;
                    if((k-signRows)%2==0)mosaicBoxRow(get(kU1,y),put(kBU2,y),w,r3); else mosaicBoxRow(get(kV1,y),put(kBV2,y),w,r3);
                });
                lap(P::kRows3);
                // columns 3: box columns of the sign map (z, r2) and of u, v (the second box, r3), then blend and rgb
                tag({{kZ,q.z,n.z}});
                phase(nc,[&](int c){
                    const int x0=c*kCols,x1=std::min(w,x0+kCols);
                    double* az=sums.data()+size_t(kSumZ)*ws;
                    for(int y=q.z;y<n.z;++y){
                        boxColumns(kBsc,r2,y,x0,x1,az);
                        float* o=put(kZ,y);for(int x=x0;x<x1;++x)o[x]=float(az[x]/(2*r2+1));
                    }
                    double* au=sums.data()+size_t(kSumUl)*ws;double* av=sums.data()+size_t(kSumVl)*ws;
                    int oscRowNow=-1; // the oscillation weights of the band's pixels (kosc) are those of this block row
                    for(int y=q.out;y<n.out;++y){
                        boxColumns(kBU2,r3,y,x0,x1,au);
                        boxColumns(kBV2,r3,y,x0,x1,av);
                        for(int x=x0;x<x1;++x){ul[x-x0]=float(au[x]/(2*r3+1));vl[x-x0]=float(av[x]/(2*r3+1));}
                        if(osc&&std::min(y/b,bh-1)!=oscRowNow){
                            oscRowNow=std::min(y/b,bh-1);
#ifdef SCAM_CHROMA_RING_CHECK
                            if(oscTags&&oscTags[oscRowNow%oscCap]!=oscRowNow){std::fprintf(stderr,"chroma oscillation ring: block row %d read, the slot holds %d\n",oscRowNow,oscTags[oscRowNow%oscCap]);std::abort();}
#endif
                            const float* oscRow=osc+size_t(oscRowNow%oscCap)*bw;
                            for(int X=x0;X<x1;++X){
                                const float o=oscRow[std::min(X/b,bw-1)];
                                const float t2=std::clamp((o-0.2375f)/0.2375f,0.f,1.f);kosc[X-x0]=t2*t2*(3.f-2.f*t2);
                            }
                        }
                        const float* mu=get(kMu,y);const float* mv=get(kMv,y);
                        if(d>=2){ // P306: [1 2 1] x [1 2 1] of the medians (the two parities of the taps meet)
                            const int ya=std::max(y-1,0),yc=std::min(y+1,h-1);
                            mosaicSmooth121Row(get(kMu,ya),mu,get(kMu,yc),smu,x0,x1,w);
                            mosaicSmooth121Row(get(kMv,ya),mv,get(kMv,yc),smv,x0,x1,w);
                            mu=smu;mv=smv;
                        }
                        mosaicBlendRow(get(kZ,y),mu,mv,ul,vl,osc?kosc:nullptr,rgb+size_t(y)*w*3,x0,x1,z0,z1,e);
                    }
                });
                lap(P::kCols3);
                q=n;
                if(id==0&&passes)++passes->steps;
            }
            if(id==0)end=q;
        });
        p=end;
        if(passes){
            passes->threads=std::max(passes->threads,team.size());
            passes->ms[MosaicChromaPasses::kEnd]+=std::chrono::duration<double,std::milli>(Clock::now()-lapped).count();
        }
    }
};

// Chroma moire of a mosaic result: a colour lattice b times coarser than the sites aliases fine luma detail (a zone plate, fabric,
// distant foliage) into pink / green rings. v11 turns on chroma_median_filter_type dual_5_point (plus its false-colour
// suppression) for remosaicked streams; here the separable 5-point median (horizontal, then vertical) of R/G and B/G, taps
// d = max(1, b/2) px apart (Quad: 5 px, Tetra: 9 px). Green stays; R and B follow the median differences. Colour detail finer
// than the colour block is not real in a mosaic.
// False-colour suppression (v11 enable_false_color_suppression for remosaicked streams): where the luma oscillates faster
// than the colour lattice can follow, the colour is aliased (zone-plate rings, fabric moire) and becomes the wide average
// colour around (the ratios boxed twice, radius 3b). Oscillation: sign changes of the luma high-pass (luma - its box, radius b)
// between neighbours, counted only where its swing exceeds 4 % of the level (noise and smooth areas stay out; both tests are
// soft, mosaicSoftSignChange); a single edge changes sign along one line only (a few % of the window), a grating of period p in
// 1/p of the pixels. The count is boxed (radius 2b); the weight ramps over 0.51..1.02 / (2b), the colour Nyquist period of the
// mosaic (soft counts are a little lower than the former hard ones on a grating: the ramp starts at 0.51 / (2b), was 0.6, the
// raw-site oscillation at 0.2375, was 0.25, which kept the false colour of syn_b2 / syn_b4 and the real bursts). Every decision
// of this pass is continuous (median, ratios, soft counts, smoothstep ramps, max), so a tiny change of the merge gives a tiny
// change of the colour.
// osc (optional): per colour block of the base RAW, the oscillation of its own sites (see hybridReconstructMosaic), (w/b) x (h/b).
// P80: gpu = try ChromaMedianGpu first (float rounding of its boxes and soft counts only); *how gets "gpu" or "cpu (reason)".
// P213b: the CPU path is MosaicChromaStream (any size; bandRows = its step, 0: kMosaicChromaBandRows; every step gives the same
// floats).
inline void mosaicChromaMedian(std::vector<float>& rgb,int w,int h,int block,const std::vector<float>* osc=nullptr,bool gpu=false,
                               std::string* how=nullptr,int bandRows=0,MosaicChromaPasses* passes=nullptr){
    if(gpu){
        // within the GPU storage of one call and a quarter of MemAvailable (any RAW size: 8192x6144 needs 2.4 GB, it stays here)
        const uint64_t avail=hybridMemAvailable();
        const size_t need=ChromaMedianGpu::bytesFor(w,h);
        if(chromaGpuPossible(int64_t(w)*h)&&(avail==0||need<avail/4)){
            try{auto g=chromaTake();g->process(rgb,w,h,block,osc);if(how)*how="gpu: "+g->times+" ms";chromaGiveBack(std::move(g));return;}
            catch(const std::exception& e){if(how)*how=std::string("cpu (")+e.what()+")";}
        } else if(how)*how="cpu (size)";
    } else if(how)*how="cpu";
    if(w<=0||h<=0||block<1||rgb.size()<size_t(w)*h*3)return;
    MosaicChromaStream stream(rgb.data(),w,h,block,osc,bandRows,0,passes);
    stream.advance(h);
}
// P213c: the oscillation of the base RAW sites per colour block of a native mosaic merge (hybridReconstructMosaicNativeMerge's
// map, the statements it ran inline before): f = the soft sign changes of a block's sites around their mean (pairs to the right
// and below, the gate 4 % of the mean plus 3 sigma of the site noise), the map = the 5x5 mean of f (clipped at the borders). Every
// block row of f depends on the RAW only and every row of the map on five rows of f: the map in any bands of rows, on any thread,
// gives the same floats (the merge's chroma consumer makes it a few rows at a time, MosaicChromaOverlap).
struct MosaicOscSource {
    const uint16_t* raw=nullptr;           // the base frame's sensor RAW (W wide), its sites gained by their class...
    const std::array<float,64>* gain=nullptr;
    const uint16_t* quad=nullptr;          // ...or (Tetra T2) the base's Quad mosaic (MW wide, gained already)
    std::array<float,4> black{};
    int W=0,MW=0,block=2,bw=0,bh=0;        // block = sites per colour block and axis (<= 8); bw x bh blocks
    double range=1;float slope=0,offset=0; // white - mean black; the site noise model (normalised units)
    // f of block row J into f[0, bw)
    void signRow(int J,float* f) const {
        const int bq=block;
        double d[64];
        for(int I=0;I<bw;++I){
            double m=0;
            for(int c=0;c<bq;++c)for(int a=0;a<bq;++a){
                const int x=bq*I+a,y=bq*J+c;
                // P188b: the site-class gains here too (the T2 Quad mosaic has them already), so the sensor's own response
                // pattern does not count as oscillation
                d[size_t(c)*bq+a]=quad?double(quad[size_t(y)*MW+x])-black[((J&1)<<1)|(I&1)]
                                      :(double(raw[size_t(y)*W+x])-black[((y&1)<<1)|(x&1)])*(*gain)[((y&7)<<3)|(x&7)];
                m+=d[size_t(c)*bq+a];
            }
            m/=double(bq*bq);
            const double sigma=std::sqrt(std::max(slope*std::max(m,0.0)/range+offset,0.0))*range;
            const double gate=0.04*std::max(m,0.0)+3.0*sigma;
            double changes=0;int pairs=0; // soft counts (mosaicSoftSignChange)
            for(int c=0;c<bq;++c)for(int a=0;a<bq;++a){
                const double p0=d[size_t(c)*bq+a]-m;
                if(a+1<bq){++pairs;changes+=mosaicSoftSignChange(p0,d[size_t(c)*bq+a+1]-m,gate,kMosaicOscTau);}
                if(c+1<bq){++pairs;changes+=mosaicSoftSignChange(p0,d[size_t(c+1)*bq+a]-m,gate,kMosaicOscTau);}
            }
            f[I]=pairs?float(changes/pairs):0.f;
        }
    }
    // block row J of the map into o[0, bw) from the rows of f around it (row(j): f row j, 0 <= j < bh; rows J - 2 .. J + 2 read)
    template<class Row> void boxRow(int J,const Row& row,float* o) const {
        for(int I=0;I<bw;++I){
            double s=0;int cnt=0;
            for(int dj=-2;dj<=2;++dj)for(int di=-2;di<=2;++di){const int y=J+dj,x=I+di;if(x<0||y<0||x>=bw||y>=bh)continue;s+=row(y)[x];++cnt;}
            o[I]=float(s/std::max(cnt,1));
        }
    }
};
// P213c: the CPU chroma median of a native mosaic merge on its rows while the GPU merges the next strips (a HybridRowsSink:
// hybridReconstruct feeds it, hybridReconstructMosaicNativeMerge reads the result). On 50 MP the merge reads back a strip of 128
// rows every ~0.4 s and the chroma of a strip takes ~10 ms of the big cores (P213b), so all but the last strip's leaves the shot's
// time (P213b: 0.65 s after the merge, 0.24 s of it the oscillation map). The floats are those of shiftOrigin and then
// mosaicChromaMedian on the whole result, bit for bit:
//  - rows reach the consumer only when final: rows(n) comes after the readback of the strip that ends at row n, and the merge never
//    writes rows [0, n) again (later strips land below; the counter under the mutex orders the readback's writes before the
//    consumer's reads; the consumer writes only rows [0, n));
//  - the shift to the RGGB origin (shiftOrigin: row y <- row max(0, y - dy), column x <- column max(0, x - dx), the same float
//    copies) runs top-down in place through a ring of dy + 1 unshifted rows: shifted row y needs unshifted rows <= y only;
//  - MosaicChromaStream takes the shifted rows as they come (its floats depend neither on how the rows are fed nor on its threads);
//  - the oscillation map (MosaicOscSource, the statements of the whole map) is made in block rows ahead of the blend that reads
//    it, in a ring (the whole map is 50 MB at 50 MP, 200 MB at 200 MP; the rings ~3 MB): a feed is at most kFeed rows, so the
//    block rows one advance() reads (from its first unfinished row to the rows it was given) fit the ring, and after every feed
//    the rows of two feeds more are made (the rows the ring can take without losing one the next advance reads).
// The stream runs on half the big cores while the merge goes on (the merge thread uploads the next strips meanwhile; a strip's
// chroma has ~0.3 s), on all of them for the last two strips: the merge reads them back together (its last readback follows the
// one before by ~10 ms on the OPPO), they are what is left after the merge (50 MP: ~40 ms of the 0.65 s the chroma median took
// after it, the oscillation rows of the last strips made already). Memory: the stream's rings (~25 MB at 50 MP),
// the oscillation rings and dy + 1 rows, nothing per frame, all of it allocated in begin(): once the consumer changed a row nothing
// can fail any more, so finish() returns either the whole result or (begin failed: memory, no thread) the rows untouched, and the
// caller runs the chroma median after the merge as before.
class MosaicChromaOverlap final : public HybridRowsSink {
    using Clock=std::chrono::steady_clock;
    static double millis(Clock::duration t){return std::chrono::duration<double,std::milli>(t).count();}
    static constexpr int kFeed=128;
    const int w,h,b;
    const MosaicOscSource src;
    int all=1,during=1;
    // one attempt (begin .. stop)
    std::unique_ptr<MosaicChromaStream> stream;
    std::vector<float> shiftRing,oscRows,fRows;std::vector<int> oscTags,fTags; // fTags: SCAM_CHROMA_RING_CHECK
    int dx=0,dy=0,oscCap=0,fCap=0,oscMade=0,fMade=0;
    bool active=false;
    std::thread consumer;
    std::mutex lock;std::condition_variable wake;
    // under the lock: the merge's output and its rows [0, ready) final; the consumer's state
    float* data=nullptr;
    int ready=0;
    int stripRows=0; // the largest step of ready (the merge's strip)
    bool stopping=false,exited=false,complete=false,touched=false,failed=false;
    Clock::time_point lastRows{},lastDone{};
    std::atomic<int> doneRows{0}; // the stream's finished rows (the report: how many the merge's last readback found done)
    // the block rows [0, need) of the oscillation map made (f first: its rows up to need + 1), on n threads
    void makeOsc(int need,int n){
        need=std::min(need,src.bh);
        if(need<=oscMade)return;
        const int fNeed=std::min(src.bh,need+2),fFrom=fMade,oFrom=oscMade;
        auto fRow=[this](int j){
#ifdef SCAM_CHROMA_RING_CHECK
            if(fTags[size_t(j%fCap)]!=j){std::fprintf(stderr,"chroma f ring: block row %d read, the slot holds %d\n",j,fTags[size_t(j%fCap)]);std::abort();}
#endif
            return static_cast<const float*>(fRows.data()+size_t(j%fCap)*src.bw);
        };
        MosaicChromaTeam team;
        team.run(n,[&](int){
            int sense=0;
            for(int k=team.take();fFrom+k<fNeed;k=team.take()){
                const int j=fFrom+k;src.signRow(j,fRows.data()+size_t(j%fCap)*src.bw);
#ifdef SCAM_CHROMA_RING_CHECK
                fTags[size_t(j%fCap)]=j;
#endif
            }
            team.sync(sense);
            for(int k=team.take();oFrom+k<need;k=team.take()){
                const int J=oFrom+k;
                src.boxRow(J,fRow,oscRows.data()+size_t(J%oscCap)*src.bw);oscTags[size_t(J%oscCap)]=J;
            }
            team.sync(sense);
        });
        fMade=fNeed;oscMade=need;
    }
    // rows [y0, y1) of data shifted to the RGGB origin in place (the rows above y0 shifted already, those from y0 on as merged)
    void shift(int y0,int y1){
        const size_t row=size_t(w)*3;
        for(int y=y0;y<y1;++y){
            float* r=data+size_t(y)*row;
            std::memcpy(shiftRing.data()+size_t(y%(dy+1))*row,r,row*sizeof(float));
            const float* s=shiftRing.data()+size_t(std::max(0,y-dy)%(dy+1))*row;
            const int lead=std::min(dx,w);
            for(int x=0;x<lead;++x)for(int c=0;c<3;++c)r[size_t(x)*3+c]=s[c];
            if(w>dx)std::memcpy(r+size_t(dx)*3,s,size_t(w-dx)*3*sizeof(float));
        }
    }
    void run(){
        try{
            int fed=0;
            for(bool more=true;more;){
                int r=0,strip=0;
                {
                    std::unique_lock<std::mutex> l(lock);
                    wake.wait(l,[&]{return stopping||ready>fed;});
                    if(stopping)break;
                    r=ready;strip=stripRows;
                    if(fed==0)stream->image(data);
                    touched=true;
                }
                while(fed<r){
                    // all the threads for the rows of the last two strips: the merge reads them back together (its last strip is read
                    // right after the one before: on the OPPO 10 ms apart, not a strip's ~0.3 s), they are the time after the merge
                    const int to=std::min(r,fed+kFeed),n=h-to<=strip?all:during;
                    const auto t0=Clock::now();
                    if(dx||dy)shift(fed,to);
                    const auto t1=Clock::now();shiftMs+=millis(t1-t0);
                    makeOsc(to>=h?src.bh:(to-1)/b+1,n); // the block rows of the result rows this advance may finish
                    oscMs+=millis(Clock::now()-t1);
                    stream->threads(n);stream->advance(to);doneRows.store(stream->done(),std::memory_order_relaxed);
                    // the block rows ahead as far as the ring keeps those the next advance reads (from its first unfinished row on):
                    // the rows after the merge's last readback find them made
                    if(to<h){const auto t2=Clock::now();makeOsc(stream->done()/b+oscCap,n);oscMs+=millis(Clock::now()-t2);}
                    fed=to;++feeds;
                    std::lock_guard<std::mutex> l(lock);
                    if(stopping){more=false;break;}
                }
                if(more&&fed>=h){ // every row given: the stream finished them all (else a fault: finish() must not wait for rows)
                    std::lock_guard<std::mutex> l(lock);
                    if(stream->done()>=h){complete=true;lastDone=Clock::now();} else failed=true;
                    more=false;
                }
            }
        }catch(...){std::lock_guard<std::mutex> l(lock);failed=true;}
        {std::lock_guard<std::mutex> l(lock);exited=true;}
        wake.notify_all();
    }
public:
    MosaicChromaPasses passes;
    double shiftMs=0,oscMs=0,waitMs=0,lastMs=0;int feeds=0,rowsBeforeLast=0;size_t bytes=0;
    std::string why="the merge did not feed it (an R / B resample, a merged DNG or a 2x resize before the shift)"; // why no result
    MosaicChromaOverlap(int width,int height,int block,const MosaicOscSource& oscillation)
        :w(std::max(0,width)),h(std::max(0,height)),b(std::max(1,block)),src(oscillation){
        all=std::max(1,mosaicChromaThreads());during=std::max(1,(all+1)/2);
    }
    ~MosaicChromaOverlap() override {stop();}
    MosaicChromaOverlap(const MosaicChromaOverlap&)=delete;
    MosaicChromaOverlap& operator=(const MosaicChromaOverlap&)=delete;
    // the threads of the stream: during the merge, and for the rows after its last readback (tests: any counts, the same floats)
    void threads(int whileMerging,int last){during=std::max(1,whileMerging);all=std::max(1,last);}
    // true after a finish() that returned the result
    bool done() const {return complete&&!active;}
    void begin(int width,int height,int shiftX,int shiftY) override {
        stop();
        complete=touched=failed=exited=false;ready=stripRows=0;data=nullptr;why="the merge failed";
        passes=MosaicChromaPasses();shiftMs=oscMs=waitMs=lastMs=0;feeds=rowsBeforeLast=0;bytes=0;doneRows.store(0);
        if(width!=w||height!=h||w<=0||h<=0){why="the merge's grid "+std::to_string(width)+"x"+std::to_string(height)+" is not the chroma's";return;}
        if(src.bw!=w/b||src.bh!=h/b||src.bw<=0||src.bh<=0||src.block<1||src.block>8||(!src.quad&&(!src.raw||!src.gain))){why="no oscillation map of this grid";return;}
        dx=std::max(0,shiftX);dy=std::max(0,shiftY);
        try{
            passes.rssBefore=hybridRssMB();
            stream=std::make_unique<MosaicChromaStream>(nullptr,w,h,b,nullptr,0,std::max(all,during),&passes);
            // the block rows one advance() reads: from its first unfinished row (at most kFeed + lag rows above the rows given), and
            // two feeds more made ahead (the last two strips, which come together, find their rows made); f: the rows of a jump
            oscCap=(3*kFeed+stream->lagRows())/b+8;fCap=oscCap+8;oscMade=fMade=0;
            oscRows.assign(size_t(oscCap)*src.bw,0.f);fRows.assign(size_t(fCap)*src.bw,0.f);oscTags.assign(size_t(oscCap),-1);
#ifdef SCAM_CHROMA_RING_CHECK
            fTags.assign(size_t(fCap),-1);
#endif
            stream->oscRing(oscRows.data(),oscCap,oscTags.data());
            if(dx||dy)shiftRing.assign(size_t(dy+1)*w*3,0.f); else std::vector<float>().swap(shiftRing);
            bytes=passes.bytes+(oscRows.size()+fRows.size()+shiftRing.size())*sizeof(float);
            consumer=std::thread([this]{run();});
            active=true;why.clear();
        }catch(const std::exception& e){
            stream.reset();why=std::string("no consumer (")+e.what()+")";active=false;
        }
    }
    void rows(float* p,int n) override {
        if(!active)return;
        {
            std::lock_guard<std::mutex> l(lock);
            if(!data)data=p;
            if(n>=h&&ready<h){lastRows=Clock::now();rowsBeforeLast=doneRows.load(std::memory_order_relaxed);}
            stripRows=std::max(stripRows,std::min(n,h)-ready);
            ready=std::max(ready,std::min(n,h));
        }
        wake.notify_all();
    }
    bool finish(float* p) override {
        if(!active)return false;
        const auto t0=Clock::now();
        rows(p,h);
        {std::unique_lock<std::mutex> l(lock);wake.wait(l,[&]{return exited;});}
        consumer.join();
        active=false;
        waitMs=millis(Clock::now()-t0);
        passes.rssAfter=hybridRssMB();
        if(complete&&!failed){lastMs=millis(lastDone-lastRows);return true;}
        complete=false;
        if(touched)throw std::runtime_error("HYBRID MOSAIC NATIVE: the chroma median during the merge stopped half way");
        why="the consumer failed before the first row";
        return false;
    }
    void stop() override {
        {std::lock_guard<std::mutex> l(lock);stopping=true;}
        wake.notify_all();
        if(consumer.joinable())consumer.join();
        std::lock_guard<std::mutex> l(lock);
        stopping=false;active=false;
    }
    // the report of a finished overlap (the line "chroma median CPU")
    std::string text() const {
        char line[480];
        std::snprintf(line,sizeof(line),"during the merge (%d of %d rows done before its last readback), %d feeds of up to %d rows on %d threads "
            "(the last rows on %d): shift to the RGGB origin %.0f ms (%d, %d px), oscillation rows %.0f ms, the last rows %.0f ms after the "
            "last readback, waited %.0f ms after the merge; %.1f MB besides rgb; passes ",
            rowsBeforeLast,h,feeds,kFeed,during,all,shiftMs,dx,dy,oscMs,lastMs,waitMs,double(bytes)/1048576.0);
        return line+passes.text();
    }
};
// Frames the sub-frame split picks of n: kHybridGpuFrames / b^2 (per = b^2 sub-frames each) and mosaicFrames. Any RAW size (avail =
// MemAvailable above kHybridClassicPixels, else 0): the worker copies every picked frame (its b^2 sub-frames, 2 B per stream pixel,
// and the binned frame of the alignment, 2/b^2 B); the sub-frame merge then adds ~20 B per stream pixel (RGB, coverage, flags,
// Bento, F6). The frames stay within half of MemAvailable, at least two (the base and one donor). P190: a FULLRES series (tuning
// seriesFrames) floors it, up to the split's kHybridGpuFrames / b^2.
inline int hybridMosaicSplitBudget(int n,int W,int Ht,int per,const HybridTuning& tune,uint64_t avail,
                                   const std::function<void(const std::string&)>& report){
    int budget=std::max(1,std::min(kHybridGpuFrames/per,std::max(2,tune.mosaicFrames)));
    if(avail>0){
        const double P=double(W)*Ht,perFrame=2.0*P*(1.0+1.0/per);
        const int byMemory=std::max(2,int(std::max(0.0,0.5*double(avail)-20.0*P)/perFrame));
        if(byMemory<budget){
            report("HYBRID MOSAIC: "+std::to_string(byMemory)+" frames (memory budget "+hybridMB(uint64_t(0.5*double(avail)))+" MB of MemAvailable "
                +hybridMB(avail)+" MB, "+hybridMB(uint64_t(perFrame))+" MB per frame)");
            budget=byMemory;
        }
    }
    if(tune.seriesFrames>0){
        const int want=std::min({n,tune.seriesFrames,kHybridGpuFrames/per});
        if(budget<want){
            report("HYBRID SERIES: budget "+std::to_string(budget)+" -> "+std::to_string(want)+" (the app sized the series)");
            budget=want;
        }
    }
    return budget;
}
// Frames the native mosaic merge picks of n (one GPU slot each, kHybridGpuFrames, and mosaicFrames). Any RAW size (avail as above),
// as the split: every picked frame adds its binned copy (2 B per binned pixel, 2/b^2 B per stream pixel, bb = b^2) and for T2 its
// Quad mosaic (2/4 B per stream pixel; Quad and T1 read the worker's RAW itself); the merge and the output then add ~20 B per
// stream pixel (RGB, coverage, flags, chroma median). The frames stay within half of MemAvailable, at least two; a burst above
// kHybridHugePixels keeps kHybridHugeFrames (P179); P190: a FULLRES series (tuning seriesFrames) floors it.
inline int hybridMosaicNativeBudget(int n,int W,int Ht,int bb,bool t2,const HybridTuning& tune,uint64_t avail,
                                    const std::function<void(const std::string&)>& report){
    int budget=std::max(1,std::min(kHybridGpuFrames,std::max(2,tune.mosaicFrames)));
    if(avail>0){
        // P191 (owner's 50 MP shots, 2026-10-17: 2 of 19 frames merged, «Сетка не исчезла»): 80 % of MemAvailable, not half -
        // at 50 MP the output's ~20 B per pixel (1 GB) alone took more than half on the OPPO Find X7 Ultra (1.7 GB available), so
        // every frame after the second was dropped although each one adds only its binned copy (24 MB); the burst itself is the
        // app's shared memory, already counted outside MemAvailable
        constexpr double kNativeShare=0.8;
        const double P=double(W)*Ht,perFrame=2.0*P/bb+(t2?0.5*P:0.0);
        const int byMemory=std::max(2,int(std::max(0.0,kNativeShare*double(avail)-20.0*P)/perFrame));
        if(byMemory<budget){
            report("HYBRID MOSAIC NATIVE: "+std::to_string(byMemory)+" frames (memory budget "+hybridMB(uint64_t(kNativeShare*double(avail)))+" MB of MemAvailable "
                +hybridMB(avail)+" MB, "+hybridMB(uint64_t(perFrame))+" MB per frame)");
            budget=byMemory;
        }
    }
    if(int64_t(W)*Ht>kHybridHugePixels&&budget<std::min(n,kHybridHugeFrames)){
        budget=std::min(n,kHybridHugeFrames);
        report("HYBRID MOSAIC NATIVE: "+std::to_string(budget)+" frames (a "+std::to_string(int64_t(W)*Ht/1000000)+" MP burst "
            +(budget>=n?std::string("merges whole, its ultrashort too)"):"keeps at least "+std::to_string(budget)+" frames)"));
    }
    if(tune.seriesFrames>0){
        const int want=std::min({n,tune.seriesFrames,kHybridGpuFrames});
        if(budget<want){
            report("HYBRID SERIES: budget "+std::to_string(budget)+" -> "+std::to_string(want)+" (the app sized the series)");
            budget=want;
        }
    }
    return budget;
}
// P219: the frames a mosaic merge picks of the burst within `budget` frames, shared by the sub-frame split (its rules since P63)
// and the native merge: the base; the N frames closest in time; the ultrashort frames (Bento) by exposure, the shortest and those
// within x1.3 of it (the replacement of hybridReconstruct takes no other), up to maxUs, from usFrom frames of budget on; the
// bracketed frames (Shasta) closest in time, up to maxBr, from 6 frames of budget on. The first reserveUs ultrashort and the first
// reserveBr bracketed frames take their slots ahead of the N frames (the N frames farthest in time give way to them), the others
// take the slots the N frames leave; an ultrashort frame after the first takes the slot of an N frame only while the base keeps
// keepN N frames. Order: the base, the N frames, the ultrashort frames, the bracketed frames.
//   split: maxUs = bentoFrames (at most 3), every bracketed frame, all of them reserved, keepN 0 (its pick of P63, unchanged);
//   native: hybridMosaicNativePick.
inline std::vector<int> hybridMosaicPick(const HybridInput& input,const HybridTuning& tune,int budget,int usFrom,int maxUs,int maxBr,
                                         int reserveUs,int reserveBr,int keepN=0){
    const int n=int(input.frames.size());
    std::vector<int> normals,usList,brList;
    for(int f=1;f<n;++f){
        const auto& fr=input.frames[f];
        if(fr.role==kRoleNormal)normals.push_back(f);
        else if(fr.role==kRoleUltrashort&&tune.bento>0)usList.push_back(f);
        else if(fr.role==kRoleBracketed&&tune.shastaEnable)brList.push_back(f);
    }
    const float t0=input.frames[0].orderMs;
    std::stable_sort(normals.begin(),normals.end(),[&](int a,int c){return std::abs(input.frames[a].orderMs-t0)<std::abs(input.frames[c].orderMs-t0);});
    std::stable_sort(usList.begin(),usList.end(),[&](int a,int c){return input.frames[a].exposure<input.frames[c].exposure;});
    std::stable_sort(brList.begin(),brList.end(),[&](int a,int c){return std::abs(input.frames[a].orderMs-t0)<std::abs(input.frames[c].orderMs-t0);});
    std::vector<int> us;
    for(int f:usList){
        if(int(us.size())>=maxUs)break;
        if(us.empty()||std::abs(std::log(input.frames[f].exposure/input.frames[usList[0]].exposure))<std::log(1.3f))us.push_back(f);
    }
    if(int(brList.size())>maxBr)brList.resize(size_t(std::max(0,maxBr)));
    const bool usRoom=budget>=usFrom,brRoom=budget>=6;
    int usAhead=usRoom?std::min(int(us.size()),reserveUs):0;
    const int brAhead=brRoom?std::min(int(brList.size()),reserveBr):0;
    auto extrasFor=[&](int u){return u+(brAhead>0?std::min(brAhead,std::max(0,budget-u-1)):0);};
    while(usAhead>1&&budget-1-extrasFor(usAhead)<keepN)--usAhead;
    const int extras=extrasFor(usAhead);
    std::vector<int> pick{0},usPick,brPick;
    for(int f:normals)if(int(pick.size())<budget-extras)pick.push_back(f);
    auto room=[&]{return int(pick.size()+usPick.size()+brPick.size())<budget;};
    for(size_t i=0;i<us.size()&&int(i)<usAhead&&room();++i)usPick.push_back(us[i]);
    for(size_t i=0;i<brList.size()&&int(i)<brAhead&&room();++i)brPick.push_back(brList[i]);
    for(size_t i=usPick.size();usRoom&&i<us.size()&&room();++i)usPick.push_back(us[i]);
    for(size_t i=brPick.size();brRoom&&i<brList.size()&&room();++i)brPick.push_back(brList[i]);
    pick.insert(pick.end(),usPick.begin(),usPick.end());
    pick.insert(pick.end(),brPick.begin(),brPick.end());
    return pick;
}
// P219: the share of Bento's 2x2 cells the base of a native mosaic merge (colour block b) clips: bentoBase's clip scan (a cell with a
// binned site at bentoHighlight of white or above; Bento runs on the binned burst of hybridReconstruct) on the base binned as the
// merge bins it (a block with a clipped site white, else the mean), without the site-class gains: they come from the pick (0.89 ..
// 1.14 on the owner's bursts) and move only the cells of an unclipped block at the edge of bentoHighlight. It estimates the area
// the ultrashort frames replace (the mask is these cells dilated; at most bentoMinClipped of them: Bento not applied).
inline double hybridMosaicBaseClipped(const HybridInput& input,const HybridTuning& tune,int b){
    if(input.frames.empty()||!input.frames[0].raw||b<1)return 0;
    const int W=input.w,vw=input.w/b,vh=input.h/b;
    if(vw<2||vh<2)return 0;
    std::vector<uint16_t> binned(size_t(vw)*vh);
    const uint16_t* raw=input.frames[0].raw;
    const float clipAt=input.white-1.f;
    mergeRowBands(vh,[&](int j0,int j1){
        for(int J=j0;J<j1;++J)for(int I=0;I<vw;++I){
            const float vb=input.black[((J&1)<<1)|(I&1)];
            double acc=0;bool clipped=false;
            for(int c=0;c<b;++c)for(int a=0;a<b;++a){
                const int x=b*I+a,y=b*J+c;
                const float v=float(raw[size_t(y)*W+x]),sb=input.black[((y&1)<<1)|(x&1)];
                acc+=v>=clipAt?input.white:std::clamp(vb+(v-sb),0.f,input.white);clipped|=v>=clipAt;
            }
            binned[size_t(J)*vw+I]=clipped?uint16_t(std::lround(input.white)):uint16_t(std::lround(acc/(b*b)));
        }
    });
    Burst bin;bin.w=vw;bin.h=vh;bin.cfa=input.cfa;bin.white=input.white;bin.black=input.black;bin.canonicalRggb=true;bin.raw[0]=binned.data();
    const int w2=vw/2,h2=vh/2;
    std::atomic<long> clipped{0};
    mergeRowBands(h2,[&](int y0,int y1){ // bentoBase's clip scan
        long lc=0;
        for(int cy=y0;cy<y1;++cy)for(int cx=0;cx<w2;++cx){
            bool c=false;
            for(int p=0;p<4;++p)c|=bin.sample(0,2*cx+(p&1),2*cy+(p>>1))>=tune.bentoHighlight;
            lc+=c?1:0;
        }
        clipped+=lc;
    });
    return double(clipped)/(double(w2)*h2);
}
// P219 (owner, 2026-10-17: «сделай так чтобы брались все кадры»): the native merge's pick (hybridMosaicPick). mosaicNativePick 1:
// every ultrashort frame Bento merges (bentoFrames, at most 3, within x1.3 of the shortest) and every bracketed frame. With a
// budget short of the burst the first ultrashort and the first bracketed frame keep their slots ahead of the N frames (as before
// P219); a further ultrashort frame takes the slot of an N frame only where that lowers the noise of the picture: inside the
// clipped share f of the base (hybridMosaicBaseClipped) its noise falls by 1 - sqrt(u / (u + 1)), elsewhere by one N frame less
// it rises by sqrt(m / (m - 1)) - 1 (m the base and the N frames kept), so it moves when f (1 - sqrt(u / (u + 1))) > (1 - f)
// (sqrt(m / (m - 1)) - 1) - 13 % of the frame for the second of 12 N, 6 % for the second of 28 - and the base keeps the N frames
// its first ultrashort frame needs (usFrom - 2: two, one for a huge burst); else it takes only what the N frames leave. Measured on
// the owner's 185248 in 14 frames (f 0.5 %): 10 N + 3 ultrashort instead of 12 + 1 lowered the noise inside Bento x0.62 and raised
// it x1.11 over the other 99.4 % of the frame (x1.12 in the darkest tiles). The other bracketed frames take what is left too (the
// Shasta motion gate refuses many: 2 of 3 on 185248, so a reserved slot would trade a merged N frame for a refused one).
// mosaicNativePick 0: one ultrashort and one bracketed frame (the pick before P219).
// reserveBr (P313): the bracketed frames that take their slots ahead of the N frames (1 as P219; the night rule of the caller more).
inline std::vector<int> hybridMosaicNativePick(const HybridInput& input,const HybridTuning& tune,int budget,int usFrom,double clipped,int reserveBr=1){
    const bool every=tune.mosaicNativePick!=0;
    const int maxUs=every?std::clamp(tune.bentoFrames,1,3):1,maxBr=every?std::numeric_limits<int>::max():1,keepN=std::max(0,usFrom-2);
    const double f=std::clamp(clipped,0.0,1.0);
    int reserve=1;
    std::vector<int> pick=hybridMosaicPick(input,tune,budget,usFrom,maxUs,maxBr,reserve,reserveBr,keepN);
    for(int k=2;k<=maxUs&&f>0.0;++k){
        int m=0,u=0;for(int fr:pick){m+=input.frames[fr].role==kRoleNormal?1:0;u+=input.frames[fr].role==kRoleUltrashort?1:0;}
        if(u>=k){reserve=k;continue;} // in already, in a slot the N frames left
        if(u<k-1||m<2)break;
        const double gain=1.0-std::sqrt(double(k-1)/double(k)),cost=std::sqrt(double(m)/double(m-1))-1.0;
        if(!(f*gain>(1.0-f)*cost))break;
        std::vector<int> next=hybridMosaicPick(input,tune,budget,usFrom,maxUs,maxBr,k,reserveBr,keepN);
        int u2=0;for(int fr:next)u2+=input.frames[fr].role==kRoleUltrashort?1:0;
        if(u2<k)break; // the N frames the first ultrashort frame needs (keepN) hold the slot
        reserve=k;pick.swap(next);
    }
    return pick;
}
inline std::vector<float> hybridReconstructMosaic(const HybridInput& input,int block,const HybridTuning& tune,const ScamAlignment& alignment,
                                                  const std::function<void(const std::string&)>& report,std::vector<uint16_t>* mergedDng,
                                                  std::vector<uint8_t>* effMap,HybridStats* statsOut,std::vector<uint8_t>* clipFlags){
    if(input.strict2x)throw std::runtime_error("HYBRID: strict Sabre 2x needs the native mosaic merge, not sub-frame resampling");
    scam_progress::pass("mosaic-split"); // P220: the sub-frames (their merge reports its own stages)
    using Clock=std::chrono::steady_clock;
    const auto started=Clock::now();
    auto millis=[](auto d){return std::chrono::duration<double,std::milli>(d).count();};
    const int b=block,W=input.w,Ht=input.h;
    if((W%(2*b))||(Ht%(2*b))||W/b<64||Ht/b<64)throw std::runtime_error("HYBRID MOSAIC: frame size not a multiple of the colour block");
    // P80: the chroma median's GPU context builds during the merge (P219: for a result of W x H it can take)
    if(tune.chromaGpu>0&&chromaGpuPossible(int64_t(W)*Ht))chromaPrewarm();
    const int vw=W/b,vh=Ht/b,per=b*b,n=int(input.frames.size());
    // ---- real frames within the GPU capacity (kHybridGpuFrames sub-frames): the base, the normals closest in time, the
    // ultrashort (Bento, up to bentoFrames) and the bracketed (Shasta) frames when there is room (hybridMosaicPick, P63's rules)
    const int budget=hybridMosaicSplitBudget(n,W,Ht,per,tune,int64_t(W)*Ht>kHybridClassicPixels?hybridMemAvailable():0,report);
    constexpr int kAll=std::numeric_limits<int>::max();
    const std::vector<int> pick=hybridMosaicPick(input,tune,budget,4,std::clamp(tune.bentoFrames,1,3),kAll,kAll,kAll);
    {
        std::string line="HYBRID MOSAIC: block "+std::to_string(b)+", "+std::to_string(per)+" plain-Bayer sub-frames "+std::to_string(vw)+"x"+std::to_string(vh)
            +" per frame, "+std::to_string(pick.size())+" of "+std::to_string(n)+" frames (GPU holds "+std::to_string(kHybridGpuFrames)+" sub-frames):";
        for(int f:pick)line+=" "+std::to_string(f)+(input.frames[f].role==kRoleNormal?"N":input.frames[f].role==kRoleUltrashort?"U":"L");
        report(line);
    }
    hybridSeriesCut(tune,int(pick.size()),n,budget>=n?"frame rules":budget>=kHybridGpuFrames/per?"split holds "+std::to_string(kHybridGpuFrames/per)+" frames"
        :"memory",report);
    // ---- response of the site classes (the normal frames picked, up to 4); P268: and its field over the sensor
    std::array<float,64> gain;gain.fill(1.f);
    HybridGainField field;
    if(tune.mosaicGain){
        std::vector<int> gf;for(int f:pick)if(input.frames[f].role==kRoleNormal&&gf.size()<(tune.mosaicGain>=2?16u:4u))gf.push_back(f);
        float spread=0,gainErr=0;long used=0;gain=hybridMosaicGains(input,gf,b,spread,&used,tune.mosaicGain,&gainErr,tune.mosaicGainField?&field:nullptr,tune.mosaicGainZones);
        if(field.zx>0){
            char fl[240];std::snprintf(fl,sizeof(fl),"HYBRID MOSAIC%s: site response field %dx%d zones of %d px (%d measured): %.2f %% rms from the global gains, zone noise %.2f %%, signal %.2f %% -> %s",
                "",field.zx,field.zy,field.zw,field.measured,100.0*field.rmsDev,100.0*field.noise,100.0*field.signal(),field.on()?"applied":"not needed");
            report(fl);
        }
        char line[260];std::snprintf(line,sizeof(line),"HYBRID MOSAIC: site-class response from %d frames, %ld smooth tiles, gain range %.4f..%.4f (spread %.2f %%), error %.3f %%",int(gf.size()),used,
            *std::min_element(gain.begin(),gain.end()),*std::max_element(gain.begin(),gain.end()),100.0*spread,100.0*gainErr);
        report(line);
    }
    // ---- sub-frames and binned frames (alignment). Canonical class of sensor site (x, y): the gain table is indexed in sensor
    // coordinates (the classes repeat every 8 sites in both). A clipped site stays clipped whatever its gain.
    const size_t vpix=size_t(vw)*vh;
    std::vector<uint16_t> sub(vpix*per*pick.size()),binned(vpix*pick.size());
    const float clipAt=input.white-1.f;
    const bool fieldOn=field.on(); // P268
    mergeRowBands(vh,[&](int j0,int j1){
        std::vector<float> rg(fieldOn?size_t(b)*W:0); // P268: as the native binning
        for(int J=j0;J<j1;++J){
          if(fieldOn)for(int c=0;c<b;++c){const int y=b*J+c;for(int x=0;x<W;++x)rg[size_t(c)*W+x]=field.at(((y&7)<<3)|(x&7),x,y);}
          for(size_t k=0;k<pick.size();++k){
            const uint16_t* raw=input.frames[pick[k]].raw;
            for(int I=0;I<vw;++I){
                const int vphase=((J&1)<<1)|(I&1);
                const float vb=input.black[vphase];
                double acc=0;
                for(int c=0;c<b;++c)for(int a=0;a<b;++a){
                    const int x=b*I+a,y=b*J+c;
                    const float v=float(raw[size_t(y)*W+x]);
                    const float sb=input.black[((y&1)<<1)|(x&1)];
                    const float lin=(v-sb)*(fieldOn?rg[size_t(c)*W+x]:gain[((y&7)<<3)|(x&7)]);
                    const float out=v>=clipAt?input.white:std::clamp(vb+lin,0.f,input.white);
                    sub[(k*per+size_t(c*b+a))*vpix+size_t(J)*vw+I]=uint16_t(std::lround(out));
                    acc+=out;
                }
                binned[k*vpix+size_t(J)*vw+I]=uint16_t(std::lround(acc/per));
            }
          }
        }
    });
    // P197: every ultrashort frame against the base, row band by row band and colour (hybridUsRowsCheck, on the binned frames):
    // corrected in its binned frame (the alignment) and its sub-frames (sub-frame (a, c) row J = binned row J + (c - (b-1)/2) / b),
    // or refused (not merged)
    std::vector<bool> usRefused(pick.size(),false);
    std::vector<size_t> usK;std::vector<int> usF;std::vector<uint16_t*> usP;
    for(size_t k=1;k<pick.size();++k)if(input.frames[pick[k]].role==kRoleUltrashort){usK.push_back(k);usF.push_back(pick[k]);usP.push_back(binned.data()+k*vpix);}
    const std::vector<HybridUsCheck> usChecks=hybridUsRowsChecks(input,usF,usP,binned.data(),vw,vh,tune,"HYBRID MOSAIC",report);
    const float usFlicker=hybridUsFlicker(usChecks);
    for(size_t u=0;u<usK.size();++u){
        const size_t k=usK[u];
        const HybridUsCheck& uc=usChecks[u];
        if(uc.refused){usRefused[k]=true;continue;}
        if(uc.corrected)for(int c=0;c<b;++c)for(int a=0;a<b;++a){
            uint16_t* sf=sub.data()+(k*per+size_t(c*b+a))*vpix;
            if(uc.rows.apply)hybridUsRowsApply(uc.rows,sf,vw,vh,input.cfa,input.black,input.white,(float(c)-0.5f*float(b-1))/float(b));
            hybridUsLocalApply(uc.rows.local,sf,vw,vh,input.cfa,input.black,input.white,(float(a)-0.5f*float(b-1))/float(b),(float(c)-0.5f*float(b-1))/float(b));
        }
    }
    // ---- alignment of the binned frames against the binned base (groups of six, as hybridReconstruct)
    const auto alignStarted=Clock::now();
    const int m=int(pick.size());
    std::vector<BackwardHomography> Hr(m);std::vector<bool> ok(m,true);
    Burst bb;bb.w=vw;bb.h=vh;bb.cfa=input.cfa;bb.white=input.white;bb.black=input.black;bb.canonicalRggb=true;
    for(int s=0;s<7;++s){bb.raw[s]=binned.data();bb.exposure[s]=1;bb.iso[s]=std::max(1u,input.frames[0].iso);}
    if(alignment){
        std::vector<Guide> fallbackRef; // as hybridReconstruct: a failed CRE group takes the global translation
        for(int first=1;first<m;first+=6){
            Burst group=bb;const int count=std::min(6,m-first);
            for(int j=0;j<count;++j){const auto& fr=input.frames[pick[first+j]];group.raw[1+j]=binned.data()+size_t(first+j)*vpix;group.exposure[1+j]=fr.exposure;group.iso[1+j]=fr.iso;}
            std::array<BackwardHomography,7> hs;
            try{hs=alignment(group);}
            catch(const std::exception& error){
                report(std::string("HYBRID ALIGN: CRE failed (")+error.what()+"); global translation for binned frames "+std::to_string(first)+".."+std::to_string(first+count-1));
                if(fallbackRef.empty())fallbackRef=guides(bb,0);
                for(int j=0;j<count;++j){
                    const int k=first+j;
                    Burst one=bb;one.raw[1]=binned.data()+size_t(k)*vpix;one.exposure[1]=input.frames[pick[k]].exposure;
                    const Shift sh=globalShift(fallbackRef,guides(one,1),input.frames[pick[k]].exposure);
                    BackwardHomography t;t.h={1,0,sh.x,0,1,sh.y,0,0};Hr[k]=t;ok[k]=true;
                }
                continue;
            }
            for(int j=0;j<count;++j){
                Hr[first+j]=hs[1+j];
                try{Hr[first+j].validate();}catch(const std::exception&){ok[first+j]=false;}
                if(group.raw[1+j]!=binned.data()+size_t(first+j)*vpix)ok[first+j]=false;
            }
        }
    } else {
        const auto ref=guides(bb,0);
        for(int k=1;k<m;++k){
            Burst one=bb;one.raw[1]=binned.data()+size_t(k)*vpix;one.exposure[1]=input.frames[pick[k]].exposure;
            const Shift sh=globalShift(ref,guides(one,1),input.frames[pick[k]].exposure);
            BackwardHomography t;t.h={1,0,sh.x,0,1,sh.y,0,0};Hr[k]=t;
        }
    }
    const double alignMs=millis(Clock::now()-alignStarted);
    std::vector<uint16_t>().swap(binned); // the alignment's only input (bb above): not held through the merge
    // ---- the sub-frame input. Coordinates: base sub-frame (0,0) pixel V = sensor b V. Binned pixel B = sensor b B + o, o = (b-1)/2.
    // Frame k binned: B_k = Hr(B_0); its sub-frame (a, c): V_k = B_k + (o - (a, c)) / b, with B_0 = V_0 - o / b.
    HybridInput vin;vin.w=vw;vin.h=vh;vin.cfa=input.cfa;vin.white=input.white;vin.black=input.black;vin.diagnostics=input.diagnostics;
    vin.mergedDng=false;vin.clipFlags=input.clipFlags;vin.grid=2;vin.mosaic=1;vin.subFrames=per;vin.usFlicker=usFlicker;
    HybridPresetAlignment preset;
    const double o=0.5*(b-1);
    auto compose=[&](const BackwardHomography& h,double ax,double ay){
        // M(V) = (1/u) P(V - o/b) + (o - a) / b with P the projective map of h: A = T(t2) S(1/u) P T(t1)
        const auto& q=h.h;const double u=h.upRatio>0?h.upRatio:1.0;
        const double t1=-o/b,t2x=(o-ax)/b,t2y=(o-ay)/b;
        double P[3][3]={{q[0],q[1],q[2]},{q[3],q[4],q[5]},{q[6],q[7],1.0}};
        double A[3][3];
        for(int r=0;r<3;++r){ // P T(t1): column 2 += t1 (col0 + col1)
            A[r][0]=P[r][0];A[r][1]=P[r][1];A[r][2]=P[r][2]+t1*(P[r][0]+P[r][1]);
        }
        for(int c=0;c<3;++c){A[0][c]=A[0][c]/u+t2x*A[2][c];A[1][c]=A[1][c]/u+t2y*A[2][c];} // T(t2) S(1/u)
        BackwardHomography r;const double z=A[2][2];
        r.h={float(A[0][0]/z),float(A[0][1]/z),float(A[0][2]/z),float(A[1][0]/z),float(A[1][1]/z),float(A[1][2]/z),float(A[2][0]/z),float(A[2][1]/z)};
        r.upRatio=1;return r;
    };
    for(int k=0;k<m;++k){
        if(usRefused[k])continue; // P197
        const HybridFrame& fr=input.frames[pick[k]];
        for(int c=0;c<b;++c)for(int a=0;a<b;++a){
            HybridFrame v=fr;v.raw=sub.data()+(size_t(k)*per+size_t(c*b+a))*vpix;
            vin.frames.push_back(v);
            if(k==0&&a==0&&c==0){preset.h.push_back(BackwardHomography{});preset.aligned.push_back(true);continue;}
            preset.h.push_back(k==0?compose(BackwardHomography{},a,c):compose(Hr[k],a,c));
            preset.aligned.push_back(ok[k]);
        }
    }
    // the sub-frames' grid of b x their size is the sensor grid of the stream: Quad 2x, Tetra 4x
    HybridTuning vt=tune;vt.grid=b;vt.mosaicBlock=1;vt.caCorrect=0;
    vt.bentoFrames=std::min(4,per); // every sub-frame of the one ultrashort frame (the merge holds at most four)
    report("HYBRID MOSAIC: binned alignment "+std::to_string(int(alignMs))+" ms; merging "+std::to_string(vin.frames.size())+" sub-frames on their "+std::to_string(b)+"x grid -> "
        +std::to_string(b*vw)+"x"+std::to_string(b*vh));
    std::vector<uint8_t> vEff,vClip;
    HybridStats st;
    std::vector<float> rgb=hybridReconstruct(vin,vt,alignment,report,nullptr,effMap?&vEff:nullptr,&st,clipFlags?&vClip:nullptr,&preset);
    hybridSeriesMerged(tune,input,st.merged/per,st.mergedNormals/per,report);
    std::vector<uint16_t>().swap(sub); // the sub-frames (vin) are merged: not held through the chroma median and the output
    st.alignMs+=alignMs;
    // ---- to the requested output: sensor grid w x h, or the 2x grid (bilinear: output X sits on sensor position X/2 - 0.25)
    const int ow=b*vw,oh=b*vh;                    // what the sub-frame merge gives (w x h)
    if(tune.mosaicChroma){
        const auto t0=Clock::now();
        // Oscillation of the base frame's own sites per colour block: the share of adjacent same-colour site pairs inside the block
        // whose deviations from the block mean change sign with a swing above 4 % of the level plus 3 noise sigma, averaged over
        // 5 x 5 blocks. A grating finer than the colour lattice changes sign at most pairs, a straight edge at about one per row
        // of the blocks it crosses (a few % of the window), noise stays below the swing.
        std::vector<float> osc;
        if(b>=2){
            const int bw=W/b,bh=Ht/b;
            std::vector<float> f(size_t(bw)*bh,0.f);
            const uint16_t* raw=input.frames[0].raw;
            const double range=double(input.white)-0.25*(input.black[0]+input.black[1]+input.black[2]+input.black[3]);
            const float slope=std::max(input.frames[0].slope,1e-9f),offset=std::max(input.frames[0].offset,0.f);
            mergeRowBands(bh,[&](int j0,int j1){
                std::vector<double> d(size_t(b)*b);
                for(int J=j0;J<j1;++J)for(int I=0;I<bw;++I){
                    double m=0;
                    for(int c=0;c<b;++c)for(int a=0;a<b;++a){const int x=b*I+a,y=b*J+c;d[size_t(c)*b+a]=double(raw[size_t(y)*W+x])-input.black[((y&1)<<1)|(x&1)];m+=d[size_t(c)*b+a];}
                    m/=double(b*b);
                    const double sigma=std::sqrt(std::max(slope*std::max(m,0.0)/range+offset,0.0))*range;
                    const double gate=0.04*std::max(m,0.0)+3.0*sigma;
                    double changes=0;int pairs=0; // soft counts (mosaicSoftSignChange)
                    for(int c=0;c<b;++c)for(int a=0;a<b;++a){
                        const double p0=d[size_t(c)*b+a]-m;
                        if(a+1<b){++pairs;changes+=mosaicSoftSignChange(p0,d[size_t(c)*b+a+1]-m,gate,kMosaicOscTau);}
                        if(c+1<b){++pairs;changes+=mosaicSoftSignChange(p0,d[size_t(c+1)*b+a]-m,gate,kMosaicOscTau);}
                    }
                    f[size_t(J)*bw+I]=pairs?float(changes/pairs):0.f;
                }
            });
            osc.assign(f.size(),0.f);
            mergeRowBands(bh,[&](int j0,int j1){for(int J=j0;J<j1;++J)for(int I=0;I<bw;++I){
                double s=0;int n=0;
                for(int dj=-2;dj<=2;++dj)for(int di=-2;di<=2;++di){const int y=J+dj,x=I+di;if(x<0||y<0||x>=bw||y>=bh)continue;s+=f[size_t(y)*bw+x];++n;}
                osc[size_t(J)*bw+I]=float(s/std::max(n,1));}});
        }
        std::string how;MosaicChromaPasses passes;
        const auto oscDone=Clock::now();passes.rssBefore=hybridRssMB();
        mosaicChromaMedian(rgb,ow,oh,b,osc.empty()?nullptr:&osc,tune.chromaGpu>0,&how,0,&passes);
        passes.rssAfter=hybridRssMB();
        report("HYBRID MOSAIC: chroma median (dual 5-point, taps "+std::to_string(std::max(1,b/2))+" px, "+how+") "+std::to_string(int(millis(Clock::now()-t0)))+" ms");
        if(passes.cpu)report("HYBRID MOSAIC: chroma median CPU: oscillation map "+std::to_string(int(millis(oscDone-t0)))+" ms, "+passes.text()); // P213a
    }
    int gridOut=tune.grid>0?std::min(tune.grid,2):std::max(1,std::min(input.grid,2)); // replays: the tuning grid
    const int tw=W*gridOut,th=Ht*gridOut;
    std::vector<float> out;std::vector<uint8_t> eOut,cOut;
    if(tw==ow&&th==oh){out.swap(rgb);eOut.swap(vEff);cOut.swap(vClip);}
    else {
        out.assign(size_t(tw)*th*3,0.f);
        if(!vEff.empty())eOut.assign(size_t(tw)*th,0);
        if(!vClip.empty())cOut.assign(size_t(tw)*th,0);
        const double sx=double(ow)/tw,sy=double(oh)/th;
        mergeRowBands(th,[&](int y0,int y1){
            for(int Y=y0;Y<y1;++Y){
                const double fy=std::clamp((Y+0.5)*sy-0.5,0.0,double(oh-1));const int iy=std::min(int(fy),oh-2);const float wy=float(fy-iy);
                for(int X=0;X<tw;++X){
                    const double fx=std::clamp((X+0.5)*sx-0.5,0.0,double(ow-1));const int ix=std::min(int(fx),ow-2);const float wx=float(fx-ix);
                    const size_t a=(size_t(iy)*ow+ix)*3,o2=(size_t(Y)*tw+X)*3;
                    for(int c=0;c<3;++c)out[o2+c]=(rgb[a+c]*(1-wx)+rgb[a+3+c]*wx)*(1-wy)+(rgb[a+size_t(ow)*3+c]*(1-wx)+rgb[a+size_t(ow)*3+3+c]*wx)*wy;
                    const size_t ns=size_t(std::min(oh-1,int(fy+0.5)))*ow+std::min(ow-1,int(fx+0.5));
                    if(!eOut.empty())eOut[size_t(Y)*tw+X]=vEff[ns];
                    if(!cOut.empty())cOut[size_t(Y)*tw+X]=vClip[ns];
                }
            }
        });
        report("HYBRID MOSAIC: "+std::to_string(ow)+"x"+std::to_string(oh)+" resampled to the requested "+std::to_string(tw)+"x"+std::to_string(th));
    }
    if(effMap)effMap->swap(eOut);
    if(clipFlags)clipFlags->swap(cOut);
    // ---- merged Bayer RAW for the DNG (sensor layout, 16 bit over the headroom: P165): every sensor Bayer site from the RGB
    if(mergedDng&&input.mergedDng){
        const int dngBlock=tune.dngMosaic!=0&&(block==2||block==4)?block:1; // P267
        const float headroom=hybridWriteMergedDng(*mergedDng,W,Ht,input,[&](int x,int y,int c){
            return out[(size_t(y*gridOut)*tw+size_t(x*gridOut))*3+c];
        },tune.dngDither!=0,dngBlock);
        report(hybridDngReport(headroom,W,Ht,hybridDngCeiling(input),tune.dngDither!=0));
        if(dngBlock>1)report("HYBRID DNG: merged mosaic layout, colour block "+std::to_string(dngBlock)+" (dngMosaic 1, no remosaic)");
    }
    report("HYBRID MOSAIC: total "+std::to_string(int(millis(Clock::now()-started)))+" ms");
    if(statsOut)*statsOut=st;
    return out;
}

// ---------------------------------------------------------------------------------------------
// P29 native mosaic path (mosaicPath 1; research/ark23/quad_report.md section 8, plan P29). Instead of the b^2 sub-frame split of
// hybridReconstructMosaic, every frame keeps ONE GPU slot:
//   M0  frame pick by frames (budget min(kHybridGpuFrames, mosaicFrames)), the split's rules otherwise;
//   M1  site-class gains (hybridMosaicGains): into the binned frames as the split built its sub-frames, at read time in the merge;
//   M2  binned frames (W/b x H/b, as the split's alignment frames): hybridReconstruct runs its whole front end on them as a plain burst
//       (alignment, Shasta, Bento, F6 with one field per frame, weights, outlier sites, guide, rejection, dilation), with the noise
//       of a b^2 average and the SNR keys of the sensor sites (as the split and S0);
//   M5  the merge pass is kHybMergeMosaic over the raw mosaic (HybridMosaicNative, slot 12), output on the grid b = W x H in the
//       split's geometry (output X at sensor X - (b-1)/2). The binned colour passes (kHybChroma, kHybBento, kHybRim) are off: they
//       work on the binned base at the binned positions (see kHybMergeMosaic);
//   M7  chroma median and false-colour suppression of the split (same code and defaults), M8 to the requested grid.
// Tetra (S8, P35): T1 (mosaicTetra 1, the default since P35) merges the Tetra mosaic natively (block 4: kHybMergeMosaicFast on the
// 2x2 cells of the 4x4 colour blocks, window 2 x mosaicWindow sensor px); T2 (mosaicTetra 2) bins every 2x2 sub-block of a Tetra
// block into one site of a true Quad mosaic at W/2 x H/2 and runs the Quad pass on it, output bilinear 2x to the sensor grid;
// mosaicTetra 0 keeps Tetra on the split.
// The plain-Bayer path and mosaicPath 0 never come here. A failure of the native merge (GPU programs, storage, a lost context)
// merges the burst on the split instead (hybridReconstructMosaicNative below).
// P308: storage the binning writes in full (no zeroing first: 0.1 s of a 50 MP shot's 400 MB on one thread)
template<class T> struct HybridNoInitAlloc : std::allocator<T> {
    template<class U> struct rebind { using other=HybridNoInitAlloc<U>; };
    HybridNoInitAlloc()=default;
    template<class U> HybridNoInitAlloc(const HybridNoInitAlloc<U>&) noexcept {}
    template<class U> void construct(U* p) noexcept {::new(static_cast<void*>(p)) U;}
    template<class U,class... A> void construct(U* p,A&&... a){::new(static_cast<void*>(p)) U(std::forward<A>(a)...);}
};
// P308: the binned values of one block row (B x B sites a block; rows of `stride` floats, their clip flags alike): the sum of the
// block's sites in double in the site loop's order (row c, then column a), its mean rounded as lround (the value is >= 0)
template<int B> inline void mosaicBinBlocks(const float* out,const uint8_t* clip,size_t stride,uint16_t* dst,int vw,uint16_t whiteCode){
    for(int I=0;I<vw;++I){
        double acc=0;int clipped=0;
        for(int c=0;c<B;++c){
            const float* o=out+size_t(c)*stride+size_t(B)*I;const uint8_t* cl=clip+size_t(c)*stride+size_t(B)*I;
            for(int a=0;a<B;++a){acc+=o[a];clipped|=cl[a];}
        }
        const double m=acc/(B*B),fl=std::floor(m);
        dst[I]=clipped?whiteCode:uint16_t(m-fl>=0.5?fl+1.0:fl);
    }
}
inline std::vector<float> hybridReconstructMosaicNativeMerge(const HybridInput& input,int block,const HybridTuning& tune,const ScamAlignment& alignment,
                                                             const std::function<void(const std::string&)>& report,std::vector<uint16_t>* mergedDng,
                                                             std::vector<uint8_t>* effMap,HybridStats* statsOut,std::vector<uint8_t>* clipFlags){
    if(input.strict2x&&((block==4&&tune.mosaicTetra!=1)||tune.mosaicNative2x==0))
        throw std::runtime_error("HYBRID: strict Sabre 2x needs native mosaic sites; resampling is disabled");
    scam_progress::pass("mosaic"); // P220: the frame pick and the site-class response (the binning below has its rows)
    using Clock=std::chrono::steady_clock;
    const auto started=Clock::now();
    auto millis=[](auto d){return std::chrono::duration<double,std::milli>(d).count();};
    const int b=block,W=input.w,Ht=input.h,bb=b*b;
    if((W%(2*b))||(Ht%(2*b))||W/b<64||Ht/b<64)throw std::runtime_error("HYBRID MOSAIC: frame size not a multiple of the colour block");
    const int vw=W/b,vh=Ht/b,n=int(input.frames.size());
    // The mosaic the merge reads: the sensor's (Quad; Tetra T1), or for Tetra T2 the Quad mosaic of its 2x2 sub-blocks.
    const bool t2=b==4&&tune.mosaicTetra!=1;
    const int mb=t2?2:b,MW=t2?W/2:W,MH=t2?Ht/2:Ht,sub=b/mb; // merge block, size, sensor sites per merge site and axis
    // ---- frames within the GPU capacity, one slot each (hybridMosaicNativePick): the base, the normals closest in time, the
    // ultrashort (Bento) and bracketed (Shasta) frames. P219 (owner, 2026-10-17: «сделай так чтобы брались все кадры»): every
    // ultrashort frame the Bento of hybridReconstruct merges (bentoFrames, as the plain Bayer burst) and every bracketed frame, each
    // one judged there as in a plain burst (Bento validation per frame, the Shasta sharpness / motion gates); the merge program takes
    // any number of both (per-frame roles). Before P219 one of each (mosaicNativePick 0): 2 of 3 ultrashort and 2 of 3 bracketed
    // frames of every FULLRES shot were dropped («HYBRID SERIES CUT: 14 of 18 (frame rules)»).
    // P35: the window follows the colour block: mosaicWindow is in Quad sites (2x2 blocks), a Tetra T1 window is twice as wide in
    // sensor px (the same 1.5 binned px for the default 3), at most 8 (17x17 sites, 81 cells of kHybMergeMosaicFast); T2 runs the
    // Quad pass on its Quad mosaic with mosaicWindow itself
    // P56: a Tetra T1 burst merged natively on the Sabre 2x grid (4x the output pixels of its sensor grid) takes its own window
    // (mosaicTetraWindow2x sensor px, 0 = as the sensor grid)
    const int gridAsked=input.strict2x?2:tune.grid>0?std::min(tune.grid,2):std::max(1,std::min(input.grid,2));
    const bool tetra2x=b==4&&!t2&&gridAsked==2&&tune.mosaicNative2x!=0;
    const int window=b==4&&!t2?std::clamp(tetra2x&&tune.mosaicTetraWindow2x>0?tune.mosaicTetraWindow2x:2*tune.mosaicWindow,1,8)
                              :std::clamp(tune.mosaicWindow,1,6);
    const float fill=tune.mosaicChromaFill==1?std::clamp(tune.mosaicFillSupport,0.f,1.f):0.f;
    const bool fullWindow=tune.mosaicWindowFull!=0;
    // P313 (review): the ultrashort fill runs in the fast native merge only (HybridGpu::merge natUs: the full window of 1..3 sites,
    // Tetra T1 1..8, no R/B fill at window 1, not the generic merge, clip flags) - elsewhere Bento stays inside the merge, and the P219
    // pick with it
    const bool fastFill=tune.mosaicUsFill>0&&tune.bento>0&&clipFlags!=nullptr&&fullWindow&&window>=1&&window<=(mb==4?8:3)
        &&!(fill>0.f&&window<2)&&tune.mosaicGeneric==0;
    int usCount=0;for(const auto& fr:input.frames)usCount+=fr.role==kRoleUltrashort?1:0;
    // P313 (owner, 2026-10-21: «нам нужно чтобы Тетра и квад брали 3 ультракоротких кадра»): with the ultrashort fill (mosaicUsFill:
    // the ultrashort frames fill only what the N frames clip, in a pass after the merge) every ultrashort frame Bento takes
    // (bentoFrames, at most 3) gets a slot of its own on top of the N frames' (mosaicFrames + the further ones, within the memory
    // budget) and ahead of them, instead of competing with an N frame for one - the vivo 2x ISZ burst kept 1 of 3 (28 N + 1 + 1 of
    // 30 slots) because a second one in an N slot would have raised the noise over the 97 % of the frame its clip does not cover
    const int usWanted=std::min(usCount,std::clamp(tune.bentoFrames,1,3));
    const bool usAll=fastFill&&tune.mosaicNativePick!=0&&usWanted>1;
    // P313 (owner: «длинных достаточно 2-3, 5 не нужны»): at night (the kernel's night rule: key61 of the base - these frames are
    // sensor sites, the key of one site - at or below s61MaxKey, never Tetra, which P306 shoots by day only) up to 3 bracketed frames
    // take slots of their own ahead of the N frames as well: their 2x exposure lowers the noise of the shadows there (plain bursts:
    // hh_1 -45 % in the deepest shadows, x7u_1x -16..-19 %), by day it does nothing (o12 0 %; the vivo 2x ISZ day burst, key 54.7,
    // +2 % with its long frame in an N slot), so by day one keeps its slot as in P219
    int brCount=0;for(const auto& fr:input.frames)brCount+=fr.role==kRoleBracketed?1:0;
    const float key=tune.snrFixed>0?float(tune.snrFixed):sabreSnr(std::max(input.frames[0].slope,1e-9f),std::max(input.frames[0].offset,0.f),1.f);
    const int brWanted=std::min(brCount,3);
    const bool brAll=tune.shastaEnable&&tune.mosaicNativePick!=0&&b!=4&&brWanted>1&&key<=tune.s61MaxKey;
    HybridTuning budgetTune=tune;
    budgetTune.mosaicFrames=tune.mosaicFrames+(usAll?usWanted-1:0)+(brAll?brWanted-1:0);
    const uint64_t avail=int64_t(W)*Ht>kHybridClassicPixels?hybridMemAvailable():0;
    const int budget=hybridMosaicNativeBudget(n,W,Ht,bb,t2,budgetTune,avail,report);
    // P313 (review): slots of their own only as far as the raise of mosaicFrames got through the memory budget - a budget short of it
    // would trade N frames for them (the noise outside the clip up, the trade P219's rule refuses); with room for the whole burst
    // every frame is picked either way
    const int grown=budget>=n?n:!(usAll||brAll)?0
        :std::max(0,budget-hybridMosaicNativeBudget(n,W,Ht,bb,t2,tune,avail,[](const std::string&){}));
    const int reserveUs=usAll?1+std::min(usWanted-1,grown):1;
    const int reserveBr=brAll?1+std::min(brWanted-1,std::max(0,grown-(reserveUs-1))):1;
    const bool usOwn=reserveUs>1;
    const bool huge=int64_t(W)*Ht>kHybridHugePixels;
    // the ultrashort takes a slot from 4 frames on (from 3 for a huge burst: two N and the ultrashort)
    const int usFrom=huge?3:4;
    // a budget short of the burst: the ultrashort frames after the first take N slots only where that lowers the noise (the base
    // scan only then: with room for the whole burst every frame is picked either way)
    const bool usShort=!usOwn&&tune.mosaicNativePick!=0&&tune.bento>0&&budget<n&&usCount>1;
    const double clippedShare=usShort?hybridMosaicBaseClipped(input,tune,b):0.0;
    // every bracketed frame as in P219 (the reserved ones ahead of the N frames, the others in the slots the N frames leave)
    const std::vector<int> pick=usOwn?hybridMosaicPick(input,tune,budget,usFrom,reserveUs,std::numeric_limits<int>::max(),reserveUs,reserveBr,std::max(0,usFrom-2))
                                     :hybridMosaicNativePick(input,tune,budget,usFrom,clippedShare,reserveBr);
    if(usAll||brAll){
        int inUs=0,inBr=0;for(int f:pick){inUs+=input.frames[f].role==kRoleUltrashort?1:0;inBr+=input.frames[f].role==kRoleBracketed?1:0;}
        report("HYBRID MOSAIC NATIVE: "+std::to_string(budget)+" of "+std::to_string(n)+" frames: "+std::to_string(inUs)+" ultrashort"
            +(usOwn?" in slots of their own (the ultrashort fill)":usAll?" (no slots of their own: the memory budget)":"")+", "
            +std::to_string(inBr)+" bracketed"+(reserveBr>1?" in slots of their own (night: key "+std::to_string(key)+")"
            :brAll?" (night: no slots of their own: the memory budget)":""));
    }
    if(usShort){
        int inPick=0;for(int f:pick)inPick+=input.frames[f].role==kRoleUltrashort?1:0;
        char line[240];std::snprintf(line,sizeof(line),"HYBRID MOSAIC NATIVE: %d of %d frames: %d ultrashort frames picked, the base clips %.3f %% of"
            " Bento's cells (an ultrashort frame after the first takes an N slot only when that lowers the noise of the picture)",
            budget,n,inPick,100.0*clippedShare);
        report(line);
    }
    hybridSeriesCut(tune,int(pick.size()),n,budget>=n?"frame rules":"memory",report);
    // P80: the chroma median's GPU context builds during the merge. P219: only for a result the median takes on the GPU (ow x oh
    // below: Quad / T1 W x H, 4x that on the natively merged 2x grid, T2 a quarter), not for every 50 MP Quad merge
    const int k2Out=gridAsked==2&&!t2&&tune.mosaicNative2x!=0?2:1;
    if(tune.chromaGpu>0&&chromaGpuPossible(t2?int64_t(W)*Ht/4:int64_t(W)*Ht*k2Out*k2Out))chromaPrewarm();
    // P73: Tetra T1 by day has its own scale (the Quad kernel in sensor px)
    const float kernelScale=std::clamp(mb==4&&tune.mosaicTetraKernelScale>0.f?tune.mosaicTetraKernelScale:tune.mosaicKernelScale,0.1f,4.f);
    const float ksG=std::clamp(tune.mosaicKernelG,0.25f,4.f),ksRB=std::clamp(tune.mosaicKernelRB,0.25f,4.f);
    if(tune.mosaicChromaFill>1)report("HYBRID MOSAIC NATIVE: chroma fill "+std::to_string(tune.mosaicChromaFill)+" (directional, S7) is not built: fill off");
    {
        char head[400];
        std::snprintf(head,sizeof(head),"HYBRID MOSAIC NATIVE: block %d%s, %d of %d frames (one GPU slot each, binned %dx%d, merged mosaic %dx%d block %d), window %d px %s, kernel scale %.3f, edge scale %.3f, ks G %.3f R/B %.3f, R/B fill %.2f:",
            b,t2?" (Tetra T2: 2x2 sub-blocks binned into a Quad mosaic)":b==4?" (Tetra T1: native)":"",int(pick.size()),n,vw,vh,MW,MH,mb,window,fullWindow?"full":"split",kernelScale,
            tune.mosaicNativeEdgeScale,ksG,ksRB,fill);
        std::string line=head;
        for(int f:pick)line+=" "+std::to_string(f)+(input.frames[f].role==kRoleNormal?"N":input.frames[f].role==kRoleUltrashort?"U":"L");
        report(line);
    }
    // ---- response of the site classes (the normal frames picked, up to 4); P268: and its field over the sensor
    const auto gainStart=Clock::now();
    std::array<float,64> gain;gain.fill(1.f);
    HybridGainField field;
    if(tune.mosaicGain){
        std::vector<int> gf;for(int f:pick)if(input.frames[f].role==kRoleNormal&&gf.size()<(tune.mosaicGain>=2?16u:4u))gf.push_back(f);
        float spread=0,gainErr=0;long used=0;gain=hybridMosaicGains(input,gf,b,spread,&used,tune.mosaicGain,&gainErr,tune.mosaicGainField?&field:nullptr,tune.mosaicGainZones);
        if(field.zx>0){
            char fl[240];std::snprintf(fl,sizeof(fl),"HYBRID MOSAIC%s: site response field %dx%d zones of %d px (%d measured): %.2f %% rms from the global gains, zone noise %.2f %%, signal %.2f %% -> %s",
                " NATIVE",field.zx,field.zy,field.zw,field.measured,100.0*field.rmsDev,100.0*field.noise,100.0*field.signal(),field.on()?"applied":"not needed");
            report(fl);
        }

        char line[260];std::snprintf(line,sizeof(line),"HYBRID MOSAIC NATIVE: site-class response from %d frames, %ld smooth tiles, gain range %.4f..%.4f (spread %.2f %%), error %.3f %%",int(gf.size()),used,
            *std::min_element(gain.begin(),gain.end()),*std::max_element(gain.begin(),gain.end()),100.0*spread,100.0*gainErr);
        report(line);
    }
    // ---- the binned frames. Every site as the split built it into its sub-frame: vb + (raw - black of the site) x gain of its class
    // (sensor (y&7, x&7)), vb = black of the block's Bayer phase, a clipped site stays white; the merge reads the sensor RAW itself
    // and applies the same gains at read time (S3: kHybNatAccess natSiteG, no gained copy of the mosaic).
    // A binned pixel is the block mean, or white when any of its sites clipped: the split saw every site's clip in its sub-frames
    // (cell clip, Bento mask, Shasta's unsaturated pixels, the rejection of a clipped longer frame); a mean of clipped and unclipped
    // sites would hide it (replay of a x1.6 synthetic burst: Bento mask 1.8 % of the cells instead of 5.6 %).
    // T2: the Quad site (u, v) is the mean of the gained sensor sites (2u..2u+1, 2v..2v+1) (white when one clipped), with the black of
    // its Tetra block's phase (vb, as the split's sub-frames), so the merge reads it without gains (natV.y = 0).
    const auto binStart=Clock::now();
    const size_t vpix=size_t(vw)*vh,qpix=size_t(MW)*MH;
    std::vector<uint16_t,HybridNoInitAlloc<uint16_t>> binned(vpix*pick.size()); // every value written below
    std::vector<uint16_t> quad(t2?qpix*pick.size():0);
    const float clipAt=input.white-1.f;
    const bool fieldOn=field.on(); // P268
    scam_progress::pass("mosaic-bin",vh); // P220: the binned rows (every picked frame)
    mergeRowBands(vh,[&](int j0,int j1){
        struct Done{int k;~Done(){scam_progress::step(k);}} done{j1-j0};
        // P268: the field's gains of the block's rows once for every frame (rows outside, frames inside)
        std::vector<float> rg(fieldOn||!t2?size_t(b)*W:0);
        // P308 (owner, 2026-10-20: «нам нужно перевести всё на гпу»; «на качество ... не должно влиять»): Quad / Tetra T1 by rows,
        // the same float steps per site over a whole sensor row (the loops the compiler vectorises), then every block's sum in double
        // in the order of the site loop below (c, then a) and its rounding (lround of a value >= 0): the same binned frames, 0.7 s ->
        // a fraction of it on a 50 MP shot. (A GPU pass would first copy the RAW of every frame, 1.6 GB, as long as this takes.)
        std::vector<float> outRow(t2?0:size_t(b)*W),vbRow(t2?0:size_t(W));std::vector<uint8_t> clipRow(t2?0:size_t(b)*W);
        const uint16_t whiteCode=uint16_t(std::lround(input.white));
        for(int J=j0;J<j1;++J){
          if(fieldOn)for(int c=0;c<b;++c){const int y=b*J+c;for(int x=0;x<W;++x)rg[size_t(c)*W+x]=field.at(((y&7)<<3)|(x&7),x,y);}
          else if(!t2)for(int c=0;c<b;++c){const int y=b*J+c;for(int x=0;x<W;++x)rg[size_t(c)*W+x]=gain[((y&7)<<3)|(x&7)];}
          if(!t2){
            for(int x=0;x<W;++x)vbRow[size_t(x)]=input.black[((J&1)<<1)|((x/b)&1)];
            // locals: the lambda's captures may alias the rows written (the loop would not vectorise)
            const float white=input.white,clipL=clipAt;const int wl=W,bl=b,vwl=vw;
            for(size_t k=0;k<pick.size();++k){
                const uint16_t* raw=input.frames[pick[k]].raw;
                for(int c=0;c<bl;++c){
                    const int y=bl*J+c;
                    const uint16_t* src=raw+size_t(y)*wl;
                    const float sb0=input.black[((y&1)<<1)],sb1=input.black[((y&1)<<1)|1];
                    const float* g=rg.data()+size_t(c)*wl;const float* vbr=vbRow.data();
                    float* o=outRow.data()+size_t(c)*wl;uint8_t* cl=clipRow.data()+size_t(c)*wl;
                    for(int x=0;x<wl;++x){
                        const float v=float(src[x]);
                        const float sb=(x&1)?sb1:sb0;
                        const float lin=(v-sb)*g[x];
                        const float sum=vbr[x]+lin;
                        const bool clip=v>=clipL;
                        o[x]=clip?white:(sum<0.f?0.f:(white<sum?white:sum));
                        cl[x]=clip?1:0;
                    }
                }
                uint16_t* dst=binned.data()+k*vpix+size_t(J)*vwl;
                if(bl==2)mosaicBinBlocks<2>(outRow.data(),clipRow.data(),size_t(wl),dst,vwl,whiteCode);
                else if(bl==4)mosaicBinBlocks<4>(outRow.data(),clipRow.data(),size_t(wl),dst,vwl,whiteCode);
                else for(int I=0;I<vwl;++I){
                    double acc=0;bool clipped=false;
                    for(int c=0;c<bl;++c){
                        const float* o=outRow.data()+size_t(c)*wl+size_t(bl)*I;const uint8_t* cl=clipRow.data()+size_t(c)*wl+size_t(bl)*I;
                        for(int a=0;a<bl;++a){acc+=o[a];clipped|=cl[a]!=0;}
                    }
                    const double m=acc/bb,fl=std::floor(m);
                    dst[I]=clipped?whiteCode:uint16_t(m-fl>=0.5?fl+1.0:fl);
                }
            }
            continue;
          }
          for(size_t k=0;k<pick.size();++k){
            const uint16_t* raw=input.frames[pick[k]].raw;
            for(int I=0;I<vw;++I){
                const float vb=input.black[((J&1)<<1)|(I&1)];
                double acc=0;bool clipped=false;
                double subAcc[4]{};bool subClip[4]{};
                for(int c=0;c<b;++c)for(int a=0;a<b;++a){
                    const int x=b*I+a,y=b*J+c;
                    const float v=float(raw[size_t(y)*W+x]);
                    const float sb=input.black[((y&1)<<1)|(x&1)];
                    const float lin=(v-sb)*(fieldOn?rg[size_t(c)*W+x]:gain[((y&7)<<3)|(x&7)]);
                    const float out=v>=clipAt?input.white:std::clamp(vb+lin,0.f,input.white);
                    acc+=out;clipped|=v>=clipAt;
                    if(t2){const int q=((c>>1)<<1)|(a>>1);subAcc[q]+=out;subClip[q]=subClip[q]||v>=clipAt;}
                }
                binned[k*vpix+size_t(J)*vw+I]=clipped?uint16_t(std::lround(input.white)):uint16_t(std::lround(acc/bb));
                if(t2)for(int q=0;q<4;++q)
                    quad[k*qpix+size_t(2*J+(q>>1))*MW+size_t(2*I+(q&1))]=subClip[q]?uint16_t(std::lround(input.white)):uint16_t(std::lround(subAcc[q]/4.0));
            }
          }
        }
    });
    // ---- P197: the ultrashort frame against the base, row band by row band and colour (hybridUsRowsCheck): its binned frame
    // corrected here, its native sites as the GPU uploads them (nat.rowGain; T2: its Quad mosaic here), or the frame refused
    std::vector<std::vector<float>> usRowGain(pick.size());std::vector<bool> usRefused(pick.size(),false);
    std::vector<HybridUsLocal> usLocal(pick.size());
    std::vector<size_t> usK;std::vector<int> usF;std::vector<uint16_t*> usP;
    for(size_t k=1;k<pick.size();++k)if(input.frames[pick[k]].role==kRoleUltrashort){usK.push_back(k);usF.push_back(pick[k]);usP.push_back(binned.data()+k*vpix);}
    const std::vector<HybridUsCheck> usChecks=hybridUsRowsChecks(input,usF,usP,binned.data(),vw,vh,tune,"HYBRID MOSAIC NATIVE",report);
    for(size_t u=0;u<usK.size();++u){
        const size_t k=usK[u];
        const HybridUsCheck& uc=usChecks[u];
        if(uc.refused){usRefused[k]=true;continue;}
        if(!uc.corrected)continue;
        if(t2){ // the Quad site (qx, qy): the mean of sensor rows 2 qy, 2 qy + 1 (centre 2 qy + 0.5), colour and black of its Tetra block
            uint16_t* q=quad.data()+k*qpix;
            mergeRowBands(MH,[&](int y0,int y1){
                for(int qy=y0;qy<y1;++qy){
                    const float vy=(float(sub*qy)+0.5f*float(sub-1)-0.5f*float(b-1))/float(b);
                    float g[3];for(int c=0;c<3;++c)g[c]=uc.rows.apply?uc.rows.at(c,vy):1.f;
                    for(int qx=0;qx<MW;++qx){
                        const int p=(((qy>>1)&1)<<1)|((qx>>1)&1),c=p==input.cfa?0:p==(input.cfa^3)?2:1;
                        const float l=uc.rows.local.on()?uc.rows.local.at(c,(float(sub*qx)+0.5f*float(sub-1)-0.5f*float(b-1))/float(b),vy):1.f;
                        uint16_t& v=q[size_t(qy)*MW+qx];v=hybridUsRowFix(v,input.black[p],input.white,g[c]*l);
                    }
                }
            });
        } else { // sensor row y: binned row (y - (b-1)/2) / b
            usRowGain[k].assign(size_t(MH)*3,1.f);
            if(uc.rows.apply)for(int y=0;y<MH;++y)for(int c=0;c<3;++c)usRowGain[k][size_t(y)*3+c]=uc.rows.at(c,(float(y)-0.5f*float(b-1))/float(b));
            if(uc.rows.local.on())usLocal[k]=uc.rows.local; // P290: x the tile field, site by site as uploaded
        }
    }
    // ---- the binned burst through hybridReconstruct with the native merge pass
    // The requested output: sensor grid W x H, or the 2x grid. P56: a Quad or Tetra T1 mosaic merges
    // the 2x grid itself (kHybMergeMosaic / Fast at g = 2b: the Sabre sub-positions of the native sites); T2 resamples its
    // sensor-grid result below.
    int gridOut=input.strict2x?2:tune.grid>0?std::min(tune.grid,2):std::max(1,std::min(input.grid,2));
    const int k2=gridOut==2&&!t2&&tune.mosaicNative2x!=0?2:1;
    if(input.strict2x&&k2!=2)throw std::runtime_error("HYBRID: strict Sabre 2x needs native mosaic sites; resampling is disabled");
    if(gridOut==2)report(k2==2?"HYBRID MOSAIC NATIVE: Sabre 2x grid merged natively ("+std::to_string(2*W)+"x"+std::to_string(2*Ht)+", "
            +(mb==4?"Tetra":"Quad")+" sites at +-0.25 px)"
        :std::string("HYBRID MOSAIC NATIVE: 2x grid resampled from the sensor-grid merge (")+(t2?"Tetra T2":"mosaicNative2x 0")+")");
    HybridInput bin;bin.w=vw;bin.h=vh;bin.cfa=input.cfa;bin.white=input.white;bin.black=input.black;bin.diagnostics=input.diagnostics;
    bin.mergedDng=false;bin.clipFlags=input.clipFlags;bin.grid=mb*k2;bin.mosaic=1;bin.subFrames=0;bin.strict2x=input.strict2x;
    HybridMosaicNative nat;nat.block=mb;nat.W=MW;nat.H=MH;nat.window=window;nat.fullWindow=fullWindow;nat.kernelScale=kernelScale;nat.ksG=ksG;nat.ksRB=ksRB;nat.fillSupport=fill;
    nat.rawGains=!t2;nat.gains=gain;nat.field=field;nat.widenRule=std::clamp(tune.mosaicNativeWiden,0,2);
    nat.widenMul=std::clamp(mb==4?tune.mosaicTetraWidenMul:tune.mosaicNativeWidenMul,1.f,8.f);
    nat.widenFrames=std::max(mb==4?tune.mosaicTetraWidenFrames:tune.mosaicNativeWidenFrames,0.01f);
    if(nat.widenRule==2)report("HYBRID MOSAIC NATIVE: base widening by accepted frames F: sigma x"+std::to_string(nat.widenMul)
        +" at F = 0 to x1 at F >= "+std::to_string(nat.widenFrames)+" (moving areas)");
    if(fastFill)report("HYBRID MOSAIC NATIVE: ultrashort fill (P313): the merge as without Bento, the ultrashort frames after it by"
        " the merge's clipped share smoothstep(0, "+std::to_string(tune.mosaicUsFillShare)+") x Bento's weight, raising only (fast merge with clip flags)");
    nat.generic=tune.mosaicGeneric!=0;nat.clampCov=std::clamp(tune.mosaicNativeClamp,0,2);
    // a binned pixel averages b^2 sites: the kernel keys (6.1 / round-4 curves, the 6.1 night rule, the outlier gate hotMaxKey) follow
    // one SENSOR site (noise x b^2 of the binned one), as the split and S0 (run_s0.py: key of the site noise); T2's merged 2x2 means
    // would raise them ~2x (a Tetra burst at sensor key 15..30 would lose the night kernel and the outlier test)
    nat.keyNoise=float(bb);
    nat.siteSlope=std::max(input.frames[0].slope,1e-9f)/float(sub*sub);nat.siteOffset=std::max(input.frames[0].offset,0.f)/float(sub*sub);
    nat.rowBlack=input.black;nat.rowCfa=input.cfa;nat.rowWhite=input.white; // P197: the row correction's sensor layout
    for(size_t k=0;k<pick.size();++k){
        if(usRefused[k])continue; // P197: an ultrashort frame whose ratios stay inconsistent is not merged
        HybridFrame v=input.frames[pick[k]];
        v.raw=binned.data()+k*vpix;
        v.slope/=float(bb);v.offset/=float(bb); // a binned pixel is the mean of b^2 sites
        bin.frames.push_back(v);
        nat.frames.push_back(t2?quad.data()+k*qpix:input.frames[pick[k]].raw);
        nat.rowGain.push_back(std::move(usRowGain[k]));
        nat.local.push_back(std::move(usLocal[k]));
    }
    HybridTuning vt=tune;vt.grid=mb*k2;vt.mosaicBlock=1;vt.caCorrect=0;
    vt.mosaicEdgeScale=tune.mosaicNativeEdgeScale; // the native path's own edge scale (hybridReconstruct applies it with `native`)
    if(vt.chromaDiff>0.f||vt.rimRatio!=0||vt.bentoChroma>0.f){
        // the binned colour passes would correct the native output at the wrong positions with the binned base's sums (P29 review).
        // P197: none of them checks the ultrashort frame's colour (kHybBento takes its ratios FROM it); that check is the row profile
        // above (hybridUsRowsCheck), and the Bento mask / validation run on the binned frames here as on a plain burst
        report("HYBRID MOSAIC NATIVE: colour-difference, clip-border and Bento colour passes off (they work on the binned frames; native variants not built)");
        vt.chromaDiff=0.f;vt.rimRatio=0;vt.bentoChroma=0.f;
    }
    // ---- chroma median and false-colour suppression (hybridReconstructMosaic's, unchanged code and defaults, on the merged mosaic's
    // grid and block; kept apart from it so that mosaicPath 0 stays untouched). T2: the oscillation of the Quad sites (the merge's);
    // the sensor sites of the Tetra blocks (the split's map, S0's) tripled T2's zone-plate false colour on syn_b4 (0.0135 -> 0.039).
    const int ow=mb*k2*vw,oh=mb*k2*vh;
    MosaicOscSource oscSource; // P213c: the oscillation map of the base sites (the statements of the map made here before)
    oscSource.raw=input.frames[0].raw;oscSource.gain=&gain;oscSource.quad=t2?quad.data():nullptr;oscSource.black=input.black;
    oscSource.W=W;oscSource.MW=MW;oscSource.block=mb;oscSource.bw=MW/mb;oscSource.bh=MH/mb;
    oscSource.range=double(input.white)-0.25*(input.black[0]+input.black[1]+input.black[2]+input.black[3]);
    oscSource.slope=std::max(input.frames[0].slope,1e-9f)/float(sub*sub);oscSource.offset=std::max(input.frames[0].offset,0.f)/float(sub*sub);
    // P213c (chromaOverlap 1): the CPU chroma median on the merged rows while the GPU merges the next strips (MosaicChromaOverlap,
    // the same floats), where the result takes the CPU path for sure (chromaGpu 0, or above the GPU's 16.8 MP: mosaicChromaMedian's
    // own test; the GPU path and a GPU refused for memory stay after the merge). hybridReconstruct feeds it only where nothing
    // between its merge and its shift to the RGGB origin changes the rows (else it is left unused and the chroma runs below).
    std::unique_ptr<MosaicChromaOverlap> overlap;
    if(tune.mosaicChroma&&tune.chromaOverlap>0&&!(tune.chromaGpu>0&&int64_t(ow)*oh<=int64_t(16800000))){
        overlap=std::make_unique<MosaicChromaOverlap>(ow,oh,mb*k2,oscSource);
        nat.rowsSink=overlap.get();
    }
    std::vector<uint8_t> vEff,vClip;
    HybridStats st;
    const auto prepDone=Clock::now(); // P80: the CPU steps around the merge in the total report
    std::vector<float> rgb=hybridReconstruct(bin,vt,alignment,report,nullptr,effMap?&vEff:nullptr,&st,clipFlags?&vClip:nullptr,nullptr,&nat);
    nat.rowsSink=nullptr;
    hybridSeriesMerged(tune,input,st.merged,st.mergedNormals,report);
    const auto mergeDone=Clock::now();
    decltype(binned)().swap(binned);
    if(tune.mosaicChroma&&overlap&&overlap->done()){
        const std::string how=tune.chromaGpu>0?"cpu (size)":"cpu";
        report("HYBRID MOSAIC NATIVE: chroma median (dual 5-point, taps "+std::to_string(std::max(1,mb*k2/2))+" px of the "+std::to_string(ow)+"x"+std::to_string(oh)+" result, "+how
            +", during the merge) "+std::to_string(int(overlap->waitMs))+" ms after it");
        report("HYBRID MOSAIC NATIVE: chroma median CPU: "+overlap->text());
    } else if(tune.mosaicChroma){
        if(overlap&&!overlap->why.empty())report("HYBRID MOSAIC NATIVE: chroma median not during the merge: "+overlap->why);
        overlap.reset();
        const auto c0=Clock::now();
        std::vector<float> osc;
        {
            const int bw=oscSource.bw,bh=oscSource.bh;
            std::vector<float> f(size_t(bw)*bh,0.f);
            mergeRowBands(bh,[&](int j0,int j1){for(int J=j0;J<j1;++J)oscSource.signRow(J,f.data()+size_t(J)*bw);});
            osc.assign(f.size(),0.f);
            mergeRowBands(bh,[&](int j0,int j1){
                for(int J=j0;J<j1;++J)oscSource.boxRow(J,[&](int j){return static_cast<const float*>(f.data()+size_t(j)*bw);},osc.data()+size_t(J)*bw);
            });
        }
        std::string how;MosaicChromaPasses passes;
        const auto oscDone=Clock::now();passes.rssBefore=hybridRssMB();
        mosaicChromaMedian(rgb,ow,oh,mb*k2,&osc,tune.chromaGpu>0,&how,0,&passes); // P56: the block (and its taps) in output px
        passes.rssAfter=hybridRssMB();
        report("HYBRID MOSAIC NATIVE: chroma median (dual 5-point, taps "+std::to_string(std::max(1,mb*k2/2))+" px of the "+std::to_string(ow)+"x"+std::to_string(oh)+" result, "+how+") "
            +std::to_string(int(millis(Clock::now()-c0)))+" ms");
        if(passes.cpu)report("HYBRID MOSAIC NATIVE: chroma median CPU: oscillation map "+std::to_string(int(millis(oscDone-c0)))+" ms, "+passes.text()); // P213a
    }
    overlap.reset();
    std::vector<uint16_t>().swap(quad);
    // ---- to the requested output: sensor grid W x H, or the 2x grid. Target pixel X is the W-grid position fx = (X + 0.5) W / tw - 0.5
    // (the split's resize), at sensor fx - (b-1)/2; merged pixel i sits at sensor alpha (i - (mb-1)/2) + (alpha-1)/2 (alpha = sensor px
    // per merged px: 1, or 2 for T2), so it is read (bilinear) at i = (fx - (b-1)/2 - (alpha-1)/2) / alpha + (mb-1)/2.
    const int tw=W*gridOut,th=Ht*gridOut; // gridOut decided above (P56: a natively merged 2x grid is already tw x th)
    const double alpha=double(sub),shift=-0.5*(b-1)-0.5*(alpha-1.0),om=0.5*(mb-1);
    std::vector<float> out;std::vector<uint8_t> eOut,cOut;
    if(tw==ow&&th==oh){out.swap(rgb);eOut.swap(vEff);cOut.swap(vClip);}
    else {
        out.assign(size_t(tw)*th*3,0.f);
        if(!vEff.empty())eOut.assign(size_t(tw)*th,0);
        if(!vClip.empty())cOut.assign(size_t(tw)*th,0);
        const double sx=double(W)/tw,sy=double(Ht)/th;
        mergeRowBands(th,[&](int y0,int y1){
            for(int Y=y0;Y<y1;++Y){
                const double fy=std::clamp(((Y+0.5)*sy-0.5+shift)/alpha+om,0.0,double(oh-1));const int iy=std::min(int(fy),oh-2);const float wy=float(fy-iy);
                for(int X=0;X<tw;++X){
                    const double fx=std::clamp(((X+0.5)*sx-0.5+shift)/alpha+om,0.0,double(ow-1));const int ix=std::min(int(fx),ow-2);const float wx=float(fx-ix);
                    const size_t a=(size_t(iy)*ow+ix)*3,o2=(size_t(Y)*tw+X)*3;
                    for(int c=0;c<3;++c)out[o2+c]=(rgb[a+c]*(1-wx)+rgb[a+3+c]*wx)*(1-wy)+(rgb[a+size_t(ow)*3+c]*(1-wx)+rgb[a+size_t(ow)*3+3+c]*wx)*wy;
                    const size_t ns=size_t(std::min(oh-1,int(fy+0.5)))*ow+std::min(ow-1,int(fx+0.5));
                    if(!eOut.empty())eOut[size_t(Y)*tw+X]=vEff[ns];
                    if(!cOut.empty())cOut[size_t(Y)*tw+X]=vClip[ns];
                }
            }
        });
        report("HYBRID MOSAIC NATIVE: "+std::to_string(ow)+"x"+std::to_string(oh)+" resampled to the requested "+std::to_string(tw)+"x"+std::to_string(th));
    }
    if(effMap)effMap->swap(eOut);
    if(clipFlags)clipFlags->swap(cOut);
    // ---- merged Bayer RAW for the DNG (sensor layout, 16 bit over the headroom: P165): every sensor Bayer site from the RGB
    if(mergedDng&&input.mergedDng){
        const int dngBlock=tune.dngMosaic!=0&&(block==2||block==4)?block:1; // P267
        const float headroom=hybridWriteMergedDng(*mergedDng,W,Ht,input,[&](int x,int y,int c){
            return out[(size_t(y*gridOut)*tw+size_t(x*gridOut))*3+c];
        },tune.dngDither!=0,dngBlock);
        report(hybridDngReport(headroom,W,Ht,hybridDngCeiling(input),tune.dngDither!=0));
        if(dngBlock>1)report("HYBRID DNG: merged mosaic layout, colour block "+std::to_string(dngBlock)+" (dngMosaic 1, no remosaic)");
    }
    report("HYBRID MOSAIC NATIVE: total "+std::to_string(int(millis(Clock::now()-started)))+" ms (before the merge "
        +std::to_string(int(millis(prepDone-started)))+": pick "+std::to_string(int(millis(gainStart-started)))+" response "
        +std::to_string(int(millis(binStart-gainStart)))+" binning "+std::to_string(int(millis(prepDone-binStart)))+", merge "+std::to_string(int(millis(mergeDone-prepDone)))+", after it "
        +std::to_string(int(millis(Clock::now()-mergeDone)))+")");
    if(statsOut)*statsOut=st;
    return out;
}
// The native path with its fallback: a native merge that fails (its GPU programs, slot 12 beyond the storage block, a lost GPU
// context, memory) must not lose the shot. The burst is merged on the sub-frame split (mosaicPath 0) instead; the outputs
// (effective map, clip flags, DNG, stats) are written only by the merge that returns.
inline std::vector<float> hybridReconstructMosaicNative(const HybridInput& input,int block,const HybridTuning& tune,const ScamAlignment& alignment,
                                                        const std::function<void(const std::string&)>& report,std::vector<uint16_t>* mergedDng,
                                                        std::vector<uint8_t>* effMap,HybridStats* statsOut,std::vector<uint8_t>* clipFlags){
    try{
        return hybridReconstructMosaicNativeMerge(input,block,tune,alignment,report,mergedDng,effMap,statsOut,clipFlags);
    } catch(const std::exception& error){
        if(input.strict2x)throw;
        report(std::string("HYBRID MOSAIC NATIVE: failed (")+error.what()+"); merging on the sub-frame split (mosaicPath 0) instead");
    }
    return hybridReconstructMosaic(input,block,tune,alignment,report,mergedDng,effMap,statsOut,clipFlags);
}

// P195 «Наш ремозаик» off (owner, 2026-10-17: «добавь так же возможность отключать наш 2х2 и 4х4 ремозаик чтобы получать фото без
// него»; tuning mosaicRemosaic 0). No remosaic at all: every b x b colour block of the mosaic becomes ONE site of a plain Bayer
// frame at W/b x H/b - the block's colour is the CFA phase of its block, (I & 1, J & 1) - and the binned burst is merged as an
// ordinary Bayer burst by hybridReconstruct: the alignment, F6, rejection, Sabre kernel, Bento, Shasta, the colour passes, the RAW
// CA fit and the noise check all as for a plain stream, so the photo has no remosaic lattice or maze. The binned site is the one
// the native path builds for its alignment (hybridReconstructMosaicNativeMerge, unchanged there): the sites gained by their class
// response (mosaicGain), with the black of the block's Bayer phase, the block's mean, or white when one of its sites clipped. Its
// noise is the b^2 average's (slope / offset / b^2) and stays so for the merge's keys: the binned frame IS the stream now.
// Frames: the base, the normals closest in time within mosaicFrames (kHybridGpuFrames) and the memory (the binned copies, 2 / b^2 B
// per stream pixel, and ~20 B per output pixel within 80 % of MemAvailable above kHybridClassicPixels), every ultrashort and
// bracketed frame (the plain merge applies its own rules to them); a FULLRES series (seriesFrames) floors it as for the native
// pick, a 200 MP burst keeps kHybridHugeFrames.
// Output: W/b x H/b on the sensor-grid request, 2W/b x 2H/b on the Sabre 2x grid (the plain merge's own 2x grid, its auto2x rule
// included); the merged DNG (flag 2) is the binned Bayer at W/b x H/b (the app writes it at that size); effective map and clip
// flags on the output grid. Quad 4096x3072 (12 MP ISZ): 2048x1536, 2x 4096x3072; Quad 8192x6144 (50 MP): 4096x3072, 2x
// 8192x6144; Tetra 16384x12288 (200 MP): 4096x3072, 2x 8192x6144.
inline std::vector<float> hybridReconstructMosaicBinned(const HybridInput& input,int block,const HybridTuning& tune,const ScamAlignment& alignment,
                                                        const std::function<void(const std::string&)>& report,std::vector<uint16_t>* mergedDng,
                                                        std::vector<uint8_t>* effMap,HybridStats* statsOut,std::vector<uint8_t>* clipFlags){
    using Clock=std::chrono::steady_clock;
    const auto started=Clock::now();
    auto millis=[](auto d){return std::chrono::duration<double,std::milli>(d).count();};
    const int b=block,W=input.w,Ht=input.h,bb=b*b,n=int(input.frames.size());
    if(!hybridMosaicBinnable(W,Ht,b))throw std::runtime_error("HYBRID MOSAIC BINNED: frame size not a multiple of the colour block");
    scam_progress::pass("mosaic-binned"); // P220: the frame pick, the response and the binning (the plain merge then reports its own)
    const int vw=W/b,vh=Ht/b;
    const int gridOut=input.strict2x?2:tune.grid>0?std::min(tune.grid,2):std::max(1,std::min(input.grid,2));
    // ---- frames: the budget of the binned copies and the output
    int budget=std::max(1,std::min(kHybridGpuFrames,std::max(2,tune.mosaicFrames)));
    if(int64_t(W)*Ht>kHybridClassicPixels){
        const uint64_t avail=hybridMemAvailable();
        if(avail>0){
            const double P=double(W)*Ht,perFrame=2.0*P/bb,out=20.0*P/bb*gridOut*gridOut;
            const int byMemory=std::max(2,int(std::max(0.0,0.8*double(avail)-out)/perFrame));
            if(byMemory<budget){
                report("HYBRID MOSAIC BINNED: "+std::to_string(byMemory)+" frames (memory budget "+hybridMB(uint64_t(0.8*double(avail)))
                    +" MB of MemAvailable "+hybridMB(avail)+" MB, "+hybridMB(uint64_t(perFrame))+" MB per frame)");
                budget=byMemory;
            }
        }
    }
    if(int64_t(W)*Ht>kHybridHugePixels)budget=std::max(budget,std::min(n,kHybridHugeFrames));
    if(tune.seriesFrames>0)budget=std::max(budget,std::min({n,tune.seriesFrames,kHybridGpuFrames}));
    std::vector<int> normals,extras;
    for(int f=1;f<n;++f){
        const auto& fr=input.frames[f];
        if(fr.role==kRoleNormal)normals.push_back(f);
        else if((fr.role==kRoleUltrashort&&tune.bento>0)||(fr.role==kRoleBracketed&&tune.shastaEnable))extras.push_back(f);
    }
    const float t0=input.frames[0].orderMs;
    std::stable_sort(normals.begin(),normals.end(),[&](int a,int c){return std::abs(input.frames[a].orderMs-t0)<std::abs(input.frames[c].orderMs-t0);});
    std::vector<int> pick{0};
    const int normalSlots=std::max(1,budget-int(extras.size()));
    for(int f:normals)if(int(pick.size())<normalSlots)pick.push_back(f);
    for(int f:extras)if(int(pick.size())<kHybridGpuFrames)pick.push_back(f);
    std::sort(pick.begin()+1,pick.end());
    hybridSeriesCut(tune,int(pick.size()),n,budget>=n?"frame rules":"memory",report);
    {
        std::string line="HYBRID MOSAIC BINNED: block "+std::to_string(b)+" without remosaic (mosaicRemosaic 0): "+std::to_string(pick.size())
            +" of "+std::to_string(n)+" frames binned to plain Bayer "+std::to_string(vw)+"x"+std::to_string(vh)+", output "
            +std::to_string(vw*gridOut)+"x"+std::to_string(vh*gridOut)+(gridOut==2?" (Sabre 2x grid)":"")+":";
        for(int f:pick)line+=" "+std::to_string(f)+(input.frames[f].role==kRoleNormal?"N":input.frames[f].role==kRoleUltrashort?"U":"L");
        report(line);
    }
    // ---- response of the site classes (the native path's: the normal frames picked, up to 4 / 16)
    std::array<float,64> gain;gain.fill(1.f);
    if(tune.mosaicGain){
        std::vector<int> gf;for(int f:pick)if(input.frames[f].role==kRoleNormal&&gf.size()<(tune.mosaicGain>=2?16u:4u))gf.push_back(f);
        float spread=0,gainErr=0;long used=0;gain=hybridMosaicGains(input,gf,b,spread,&used,tune.mosaicGain,&gainErr);
        char line[260];std::snprintf(line,sizeof(line),"HYBRID MOSAIC BINNED: site-class response from %d frames, %ld smooth tiles, gain range %.4f..%.4f (spread %.2f %%), error %.3f %%",int(gf.size()),used,
            *std::min_element(gain.begin(),gain.end()),*std::max_element(gain.begin(),gain.end()),100.0*spread,100.0*gainErr);
        report(line);
    }
    // ---- the binned frames (the native path's binning without T2): vb + mean of (raw - black of the site) x gain of its class,
    // white when any site of the block clipped
    const auto binStart=Clock::now();
    const size_t vpix=size_t(vw)*vh;
    std::vector<uint16_t> binned(vpix*pick.size());
    const float clipAt=input.white-1.f;
    mergeRowBands(vh,[&](int j0,int j1){
        for(size_t k=0;k<pick.size();++k){
            const uint16_t* raw=input.frames[pick[k]].raw;
            for(int J=j0;J<j1;++J)for(int I=0;I<vw;++I){
                const float vb=input.black[((J&1)<<1)|(I&1)];
                double acc=0;bool clipped=false;
                for(int c=0;c<b;++c)for(int a=0;a<b;++a){
                    const int x=b*I+a,y=b*J+c;
                    const float v=float(raw[size_t(y)*W+x]);
                    const float sb=input.black[((y&1)<<1)|(x&1)];
                    const float lin=(v-sb)*gain[((y&7)<<3)|(x&7)];
                    acc+=v>=clipAt?input.white:std::clamp(vb+lin,0.f,input.white);clipped|=v>=clipAt;
                }
                binned[k*vpix+size_t(J)*vw+I]=clipped?uint16_t(std::lround(input.white)):uint16_t(std::lround(acc/bb));
            }
        }
    });
    const double binMs=millis(Clock::now()-binStart);
    // P197: every ultrashort frame against the base, row band by row band and colour (hybridUsRowsCheck): corrected in place or
    // refused (not merged)
    std::vector<bool> usRefused(pick.size(),false);
    float usFlicker=-1.f;
    {
        std::vector<size_t> usK;std::vector<int> usF;std::vector<uint16_t*> usP;
        for(size_t k=1;k<pick.size();++k)if(input.frames[pick[k]].role==kRoleUltrashort){usK.push_back(k);usF.push_back(pick[k]);usP.push_back(binned.data()+k*vpix);}
        const std::vector<HybridUsCheck> usChecks=hybridUsRowsChecks(input,usF,usP,binned.data(),vw,vh,tune,"HYBRID MOSAIC BINNED",report);
        for(size_t u=0;u<usK.size();++u)usRefused[usK[u]]=usChecks[u].refused;
        usFlicker=hybridUsFlicker(usChecks);
    }
    // ---- the binned burst through the plain merge
    HybridInput bin;bin.w=vw;bin.h=vh;bin.cfa=input.cfa;bin.white=input.white;bin.black=input.black;bin.diagnostics=input.diagnostics;
    bin.mergedDng=input.mergedDng;bin.clipFlags=input.clipFlags;bin.noEffMap=input.noEffMap;bin.grid=gridOut;bin.mosaic=1;bin.subFrames=0;
    bin.strict2x=input.strict2x;bin.usRowsChecked=true;bin.usFlicker=usFlicker;
    for(size_t k=0;k<pick.size();++k){
        if(usRefused[k])continue; // P197
        HybridFrame v=input.frames[pick[k]];
        v.raw=binned.data()+k*vpix;
        v.slope/=float(bb);v.offset/=float(bb); // a binned pixel is the mean of b^2 sites
        bin.frames.push_back(v);
    }
    HybridTuning vt=tune;vt.grid=gridOut;vt.mosaicBlock=1;vt.seriesFrames=0;
    HybridStats st;
    std::vector<float> rgb=hybridReconstruct(bin,vt,alignment,report,mergedDng,effMap,&st,clipFlags);
    hybridSeriesMerged(tune,input,st.merged,st.mergedNormals,report);
    report("HYBRID MOSAIC BINNED: total "+std::to_string(int(millis(Clock::now()-started)))+" ms (binning "+std::to_string(int(binMs))+")");
    if(statsOut)*statsOut=st;
    return rgb;
}

// NCH v10 — the hybrid transport written by ScamHybridBurst.java: header 128 B (magic, version 10, w, h, cfa,
// frameCount, white, black[4], flags, baseIndex), frameCount x 32 B frame table (role, exposure ratio to the
// base, iso, noise slope, noise offset, order ms, flags, reserved), then the uint16 planes in sensor layout.
struct MappedHybridBurst {
    void* address=MAP_FAILED;size_t length=0;HybridInput input;bool hybrid=false;
    explicit MappedHybridBurst(const std::string& path, bool halfRgb=false) {
        const int fd=openArgument(path,O_RDONLY);
        if(fd<0)throw std::runtime_error("Cannot open SCAM burst");
        struct stat st{};
        if(fstat(fd,&st)||st.st_size<128){close(fd);throw std::runtime_error("Invalid SCAM file size");}
        length=size_t(st.st_size);address=mmap(nullptr,length,PROT_READ,MAP_PRIVATE,fd,0);close(fd);
        if(address==MAP_FAILED)throw std::runtime_error("Cannot map SCAM burst");
        uint32_t h[32];std::memcpy(h,address,128);
        if(h[0]!=0x3143484e||(h[1]!=10&&h[1]!=11&&h[1]!=12)){munmap(address,length);address=MAP_FAILED;return;}
        try {
            // The Java RGB buffer holds the selected FP16/FP32 transport, not the worker's float32 intermediates.
            if(h[2]<64||h[3]<64||h[2]>65534||h[3]>65534||h[2]%2||h[3]%2||h[4]>3||h[5]<1||h[5]>64)
                throw std::runtime_error("Unsupported hybrid burst dimensions/CFA/count");
            const uint64_t streamPixels=uint64_t(h[2])*h[3];
            input.w=int(h[2]);input.h=int(h[3]);input.cfa=int(h[4]);
            const int n=int(h[5]);
            std::memcpy(&input.white,h+6,4);std::memcpy(input.black.data(),h+7,16);
            const uint32_t flags=h[11];input.diagnostics=flags&1;input.mergedDng=flags&2;input.clipFlags=flags&4;input.strict2x=flags&8;
            input.noEffMap=flags&16;
            input.dcg=flags&32; // P320
            if(h[1]>=11){ // v11: h[12] = base index (0), h[13] = output grid 1|2, h[14] = colour block 0 (unknown) | 1 | 2 | 4
                if(h[13]!=0&&h[13]!=1&&h[13]!=2)throw std::runtime_error("Unsupported hybrid output grid");
                input.grid=h[13]==0?1:int(h[13]);
                if(h[14]!=0&&h[14]!=1&&h[14]!=2&&h[14]!=4)throw std::runtime_error("Unsupported hybrid colour block");
                input.mosaic=int(h[14]);
                if(input.strict2x&&input.grid!=2)throw std::runtime_error("Hybrid: strict Sabre 2x requires output grid 2");
            }
            const uint64_t rgbBytes=streamPixels*uint64_t(input.grid)*input.grid*(halfRgb?6:12);
            if(rgbBytes>uint64_t(INT32_MAX))
                throw std::runtime_error("Hybrid: "+std::to_string(h[2])+"x"+std::to_string(h[3])+" needs a "+hybridMB(rgbBytes)
                    +" MB result, above 2 GiB: select FP16 or reduce the output grid");
            if(!std::isfinite(input.white)||input.white<=1||input.white>65535)throw std::runtime_error("Hybrid white level");
            for(float b:input.black)if(!std::isfinite(b)||b<0||b+1>=input.white)throw std::runtime_error("Hybrid black level");
            const size_t pixels=size_t(input.w)*input.h,headerBytes=128+32*size_t(n);
            // v12 (P30): every frame at its own page of the shared memfd (the app's shot arena), the table holds the page.
            const bool paged=h[1]>=12;
            if(!paged&&length!=headerBytes+pixels*2*size_t(n))throw std::runtime_error("Truncated hybrid burst");
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
                if(paged){
                    uint32_t page;std::memcpy(&page,r+28,4);
                    const size_t at=size_t(page)*4096;
                    if(at<headerBytes||at+pixels*2>length)throw std::runtime_error("Hybrid frame outside the shared burst");
                    f.raw=reinterpret_cast<const uint16_t*>(static_cast<const uint8_t*>(address)+at);
                }
                input.frames.push_back(f);
            }
            hybrid=true;
        }catch(...){munmap(address,length);address=MAP_FAILED;throw;}
    }
    MappedHybridBurst(const MappedHybridBurst&)=delete;
    ~MappedHybridBurst(){if(address!=MAP_FAILED)munmap(address,length);}
};

// SCAM 7-slot transport (N0..N3, L, S, ES + extra N) seen as a hybrid burst: the longest frame is the
// bracketed one, the shortest of S/ES the ultrashort one, S otherwise dropped.
inline HybridInput hybridFromScamBurst(const Burst& b) {
    HybridInput in;in.w=b.w;in.h=b.h;in.cfa=b.cfa;in.white=b.white;in.black=b.black;in.diagnostics=b.diagnostics;in.mergedDng=b.mergedDng;
    const ScamNoise nn=b.hasNormalNoise?b.normalNoise:b.cameraNoise?b.noise:imx06cHdrNoise(b.iso[forwardReferenceSlot]);
    auto add=[&](const uint16_t* raw,int role,float exposure,unsigned iso,ScamNoise noise){
        HybridFrame f;f.raw=raw;f.role=role;f.exposure=exposure;f.iso=iso;f.slope=noise.slope;f.offset=noise.offset;in.frames.push_back(f);
    };
    add(b.raw[forwardReferenceSlot],kRoleNormal,1.f,b.iso[forwardReferenceSlot],nn);
    for(int s=0;s<4;++s)if(s!=forwardReferenceSlot&&b.raw[s]!=b.raw[forwardReferenceSlot])add(b.raw[s],kRoleNormal,1.f,b.iso[s],nn);
    for(const uint16_t* e:b.extraNormals)add(e,kRoleNormal,1.f,b.iso[forwardReferenceSlot],nn);
    // L (slot 4): bracketed when it is a real longer exposure; its noise model = the N model at its gain.
    if(b.syntheticLong<=0&&b.exposure[4]>1.5f&&b.raw[4]!=b.raw[forwardReferenceSlot]){
        ScamNoise ln=b.noiseReferenceSlot==4&&b.cameraNoise?b.noise:nn;
        add(b.raw[4],kRoleBracketed,b.exposure[4],b.iso[4],ln);
    }
    // Ultrashort: the short frame closest to SCAM's TET_base/8 (S at -3 EV rather than ES at -6 EV when both exist).
    auto distance=[&](int s){return std::abs(std::log(std::max(b.exposure[s],1e-6f)*8.f));};
    const int shortest=distance(6)<distance(5)?6:5;
    if(b.exposure[shortest]<1.f&&b.raw[shortest]!=b.raw[forwardReferenceSlot]){
        // the short frame runs at a lower gain than N: read noise scales with gain^2, shot noise with gain
        const float gRatio=float(b.iso[shortest])/float(std::max(1u,b.iso[forwardReferenceSlot]));
        ScamNoise sn{nn.slope*gRatio,nn.offset*gRatio*gRatio};
        add(b.raw[shortest],kRoleUltrashort,b.exposure[shortest],b.iso[shortest],sn);
    }
    return in;
}
} // namespace scam
