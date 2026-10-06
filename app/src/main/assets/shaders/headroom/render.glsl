precision highp float;
precision highp sampler2D;

/*
 * Motion V2 headroom tone mapping.
 *
 * Values below 0.50 linear are untouched; above that a log-shaped shoulder
 * spreads the remaining display range according to the physical scene
 * headroom (sceneWhite). Compression is one scalar acting on a
 * max-RGB/luminance guide so channel ratios (hue) survive the shoulder, and
 * only the final part of the headroom converges to neutral display white.
 *
 * SDR base render of SCAM HDR / the hybrid without the ARK tone: the
 * exposure multiplier arrives from the LinearExposure node (linear-histogram
 * percentiles), color is matrix-only (sensor -> ProPhoto -> sRGB with
 * white-point WB), and the lens shading GainMap plus the
 * NiceExposureFusion FusionMap are applied as linear gains before the
 * curve. No cubes/CLUTs/LUTs.
 */

uniform sampler2D InputBuffer;
uniform sampler2D GainMap;
uniform sampler2D FusionMap;
//Color mat's
uniform mat3 sensorToIntermediate; // Color transform from sensor to a wide-gamut colorspace
uniform mat3 intermediateToSRGB; // Color transform from wide-gamut colorspace to sRGB
uniform float displayGain; // Linear exposure multiplier from LinearExposure
uniform float toneAmount;
uniform float localContrast;
uniform float shadowLift;
uniform float sceneWhite; // Scene headroom, 0.90*displayGain clamped to [1, sceneWhiteMax]
uniform float outputExposureScale; // Global output exposure (~-0.32 EV at 0.80)
uniform float highlightNeutralStart; // Shoulder position where highlights start fading to white
uniform float displayNeutralStart; // Display-linear level where near-white starts losing tint (1 = off)
uniform ivec4 activeSize;
uniform int pxStepU; // output grid step (2 on the Sabre 2x grid, else 1): the 5x5 windows below are dilated by it so they cover the same sensor area as at 1x
uniform float bentoReal;      // 1 = highlights come from the Bento ultrashort frame (real colour, nothing clipped at 1.0)
uniform vec3 castTint; // share of the scene light's colour kept in the picture (1,1,1 = fully white balanced)
uniform vec4 castRange; // the cast fades out below these luminances (x..y: the dark parts of a night scene are lit by the sky, not by the lamps) and, above the x..y peak channel level (z..w), darkens instead of pushing a channel past white (a lamp-lit white wall stays warm as in the stock render instead of clipping to white)

#define NEUTRALPOINT 0.0,0.0,0.0
#define FUSION 0
#define MANUAL_TONE 0
#define AGX 0
#define GCAM 0

#if GCAM == 1
// SCAM HDR soft tone (a GCam/LMC-like render): a local highlight knee on the large-scale level, a global
// toe + soft-shoulder curve on the luminance (hue kept), mild saturation and near-white desaturation.
uniform sampler2D GcamBase;   // large-scale log2 luminance (agxbase.glsl)
uniform vec4 gcamKnee;        // knee stops, knee start (scene level, display-linear), unused, unused
uniform vec4 gcamCurve;       // toe constant, shoulder start, white point, unused
uniform vec4 gcamColor;       // saturation, near-white desaturation amount, where it starts, unused
uniform float hueGain[12];    // chroma gain per hue (OKLab hue, nodes every 30 degrees from +a), interpolated linearly
uniform float hueShift[12];   // hue rotation per hue node in radians
uniform vec3 hueTint;         // neutral shift in OKLab a, b and 1 when the look is active

float gcamShoulder(float v) {
    float s=gcamCurve.y, W=gcamCurve.z;
    if(v<=s) return v;
    float u=(v-s)/(1.0-s);
    float uw=(W-s)/(1.0-s);
    return min(s+(1.0-s)*u*(1.0+u/(uw*uw))/(1.0+u),1.0);
}
#endif

#if AGX == 1
// AgX picture formation (Kraken-AgX, sobotka/AgX-Resolve; see AgxTone.java).
uniform mat3 agxInset;        // per-primary attenuation / hue flight (Rec.709)
uniform mat3 agxOutset;       // purity restore
uniform float agxExposure;    // linear exposure multiplier
uniform vec2 agxRange;        // min/max EV around 0.18
uniform vec4 agxCurve;        // px, py, slope, unused
uniform vec4 agxPowers;       // toe power, shoulder power, toe scale, shoulder scale
uniform vec4 agxLook;         // CDL slope, offset, power, saturation
uniform sampler2D AgxBase;    // large-scale log2 luminance (agxbase.glsl)
uniform vec2 agxLocal;        // local highlight compression: strength 0..1, start EV above grey
uniform vec2 agxHiDesat;      // near-white tint removal: amount 0..1, display level where it starts (SCAM HDR)

float agxSigmoid(float x) {
    float px=agxCurve.x,py=agxCurve.y,slope=agxCurve.z;
    if(x>=px) {
        float ms=slope*(x-px)/agxPowers.w;
        return agxPowers.w*ms/pow(1.0+pow(ms,agxPowers.y),1.0/agxPowers.y)+py;
    }
    float mr=slope*(px-x)/agxPowers.z;
    return py-agxPowers.z*mr/pow(1.0+pow(mr,agxPowers.x),1.0/agxPowers.x);
}

/* Scene-linear Rec.709 (0.18 = middle grey) -> display-linear Rec.709. */
vec3 agxForm(vec3 lin) {
    vec3 v=agxInset*(max(lin,vec3(0.0))*agxExposure);
    v=max(v,vec3(1.0e-10));
    v=clamp((log2(v/0.18)-agxRange.x)/(agxRange.y-agxRange.x),0.0,1.0);
    v=vec3(agxSigmoid(v.r),agxSigmoid(v.g),agxSigmoid(v.b));
    // Look (CDL + saturation) on the formed, display-encoded values.
    v=pow(max(v*agxLook.x+vec3(agxLook.y),vec3(0.0)),vec3(agxLook.z));
    float luma=dot(v,vec3(0.2126,0.7152,0.0722));
    v=vec3(luma)+agxLook.w*(v-vec3(luma));
    if(agxHiDesat.x>0.0) {
        // Stock-like near-white desaturation: bright areas (windows, sky, lamps) lose the
        // residual cast of white balance and the look's saturation boost instead of
        // going pink/purple next to the clipped channel.
        float le=dot(v,vec3(0.2126,0.7152,0.0722));
        v=mix(v,vec3(le),smoothstep(agxHiDesat.y,0.98,le)*agxHiDesat.x);
    }
    v=pow(max(v,vec3(0.0)),vec3(2.2));
    return max(agxOutset*v,vec3(0.0));
}
#endif
#if MANUAL_TONE == 1
uniform float manualExposure;
uniform float manualContrast;
uniform float manualGamma;
uniform float manualSaturation;
uniform float manualBlack;
uniform float manualWhite;
#endif
#define luminocity(x) dot(x.rgb, vec3(0.299, 0.587, 0.114))

#import coords
#import interpolation

out vec4 Output;

float max3(vec3 v) {
    return max(v.r,max(v.g,v.b));
}

float luminance(vec3 c) {
    return dot(c,vec3(0.2126,0.7152,0.0722));
}

float srgbEncode(float x) {
    x=max(x,0.0);
    return x<=0.0031308
            ? 12.92*x
            : 1.055*pow(x,1.0/2.4)-0.055;
}

vec3 srgbEncode(vec3 x) {
    return vec3(
            srgbEncode(x.r),
            srgbEncode(x.g),
            srgbEncode(x.b));
}

/*
 * Bounded log-luma unsharp mask, faded out in deep shadows and highlights to
 * avoid noise, halos and highlight-edge exaggeration. The window reuses the
 * center pixel's exposure/white point (lens shading and fusion maps are low
 * frequency); the log residual itself is invariant to that scale.
 */
float localLogLumaMean(ivec2 xy, float exposure, vec3 neutralPoint) {
    ivec2 sz=textureSize(InputBuffer,0);
    float sum=0.0;
    float wsum=0.0;
    for(int oy=-2;oy<=2;oy++) {
        for(int ox=-2;ox<=2;ox++) {
            ivec2 p=clamp(xy+ivec2(ox,oy)*max(pxStepU,1),ivec2(0),sz-ivec2(1));
            vec3 wb=max(texelFetch(InputBuffer,p,0).rgb,vec3(0.0))*neutralPoint*exposure;
            float y=max(luminance(wb),0.0);
            float r2=float(ox*ox+oy*oy);
            float w=exp(-0.55*r2);
            sum+=w*log(1.0e-4+y);
            wsum+=w;
        }
    }
    return sum/max(wsum,1.0e-6);
}

/* Linear below 0.50; above, allocate the remaining display range by a
 * log-shaped shoulder over the available scene headroom. The white point is
 * per-pixel: scaled by the same local gain (lens shading, fusion map) as the
 * exposure, so the local sensor white maps exactly to display white. */
float mapHeadroomLuminance(float y, float whitePoint) {
    const float start=0.50;
    if(y<=start) return y;

    float x=clamp(
            (y-start)/max(whitePoint-start,1.0e-6),
            0.0,
            1.0);

    /* Map to the inverse of the output exposure so the final SDR endpoint
     * can actually reach 1.0 after the global scale. */
    float preScaleDisplayWhite=1.0/max(outputExposureScale,1.0e-6);

    /* C1 shoulder: log(1+a*x)/log(1+a) with a chosen so the slope at the
     * shoulder start is exactly 1 (a/log(1+a) = k). A fixed shape dropped the
     * slope from 1 to ~0.3 at y=0.5 and drew a contour through every smooth
     * light falloff; now compression grows gradually towards white. */
    float k=(whitePoint-start)/max(preScaleDisplayWhite-start,1.0e-6);
    float shaped;
    if(k<=1.001) {
        shaped=x*k;
    } else {
        // Newton on g(a)=a-k*log(1+a) from above the positive root (g is convex).
        float a=max(2.0*k*log(k),1.0e-3);
        for(int i=0;i<6;i++) {
            float g=a-k*log(1.0+a);
            float dg=1.0-k/(1.0+a);
            a=max(a-g/max(dg,1.0e-4),1.0e-3);
        }
        shaped=log(1.0+a*x)/log(1.0+a);
    }
    return mix(y,start+(preScaleDisplayWhite-start)*shaped,clamp(toneAmount,0.0,1.0));
}

/* Chroma-preserving headroom compression: the curve acts on one scalar guide
 * (max of luminance and max RGB) and rescales all channels uniformly. */
vec3 mapExtendedLinearHeadroom(vec3 rgb, float whitePoint) {
    rgb=max(rgb,vec3(0.0));
    float y=max(luminance(rgb),0.0);
    float peak=max3(rgb);
    float guide=max(y,peak);
    if(guide<=1.0e-7) return rgb;

    float mappedGuide=mapHeadroomLuminance(guide,whitePoint);
    vec3 mapped=rgb*(mappedGuide/guide);

    /* Colour fades to neutral display white over the upper half of the
     * shoulder, as in the stock render. Converging only in the last 18% left
     * light sources' own tint (green LED diffusers) as a coloured band just
     * inside the white core. */
    const float start=0.50;
    float headroomPosition=clamp(
            (guide-start)/max(whitePoint-start,1.0e-6),
            0.0,
            1.0);
    float neutralMix=smoothstep(highlightNeutralStart,1.0,headroomPosition);
    /* Stock-like near-white desaturation on the display value: bright
     * diffusers and lit surfaces just below white lose the light's tint. */
    float displayY=mappedGuide*outputExposureScale;
    neutralMix=max(neutralMix,smoothstep(displayNeutralStart,1.0,displayY));
    return mix(mapped,vec3(mappedGuide),neutralMix);
}

/* sRGB cannot encode a channel above 1.0. If one saturated channel still
 * exceeds the display gamut, shrink chroma uniformly around white instead of
 * independently clipping R/G/B. */
#if GCAM == 1
// Colour look of the SCAM HDR render: chroma gain and hue rotation by hue in OKLab (a render of the same scene by the
// reference cameras keeps blues, greens and cyans clearly more saturated than the plain sensor matrix gives them).
vec3 colourLook(vec3 rgb) {
    float l_=pow(max(dot(vec3(0.4122214708,0.5363325363,0.0514459929),rgb),0.0),1.0/3.0);
    float m_=pow(max(dot(vec3(0.2119034982,0.6806995451,0.1073969566),rgb),0.0),1.0/3.0);
    float s_=pow(max(dot(vec3(0.0883024619,0.2817188376,0.6299787005),rgb),0.0),1.0/3.0);
    float L=0.2104542553*l_+0.7936177850*m_-0.0040720468*s_;
    float a=1.9779984951*l_-2.4285922050*m_+0.4505937099*s_;
    float b=0.0259040371*l_+0.7827717662*m_-0.8086757660*s_;
    float C=length(vec2(a,b));
    float h=atan(b,a);
    float t=(h<0.0 ? h+6.28318531 : h)*(12.0/6.28318531);
    int i0=int(floor(t))%12;
    int i1=(i0+1)%12;
    float f=t-floor(t);
    float g=mix(hueGain[i0],hueGain[i1],f);
    float r=mix(hueShift[i0],hueShift[i1],f);
    float h2=h+r;
    float C2=C*g;
    a=C2*cos(h2)+hueTint.x*exp(-C/0.03);
    b=C2*sin(h2)+hueTint.y*exp(-C/0.03);
    float lq=L+0.3963377774*a+0.2158037573*b;
    float mq=L-0.1055613458*a-0.0638541728*b;
    float sq=L-0.0894841775*a-1.2914855480*b;
    lq=lq*lq*lq; mq=mq*mq*mq; sq=sq*sq*sq;
    return vec3(4.0767416621*lq-3.3077115913*mq+0.2309699292*sq,
               -1.2684380046*lq+2.6097574011*mq-0.3413193965*sq,
               -0.0041960863*lq-0.7034186147*mq+1.7076147010*sq);
}

#endif

vec3 fitDisplayGamut(vec3 rgb) {
    rgb=max(rgb,vec3(0.0));
    float peak=max3(rgb);
    if(peak<=1.0) return rgb;

    vec3 hueSafe=rgb/max(peak,1.0e-6);
    float overflow=clamp((peak-1.0)/0.25,0.0,1.0);
    return mix(hueSafe,vec3(1.0),smoothstep(0.0,1.0,overflow));
}

/* Clipped highlights turn magenta: every sensor channel saturates at about the same
 * raw level, so white balance (R and B gains > G) leaves R,B > G in blown windows/sky
 * while a real bright pixel is never that far from neutral. A bright pixel whose R and
 * B are both above G is a clipped white: return it to neutral white (its brightest
 * channel). Saturated coloured objects are untouched (strongly non-magenta or dark). */
vec3 neutralizeClippedMagenta(vec3 rgb) {
    rgb=max(rgb,vec3(0.0));
    float y=luminance(rgb);
    float tint=min(rgb.r,rgb.b)/max(rgb.g,1.0e-4);
    float t=smoothstep(1.03,1.15,tint)*smoothstep(0.25,0.5,y);
    return mix(rgb,vec3(max(max3(rgb),y)),t);
}

#if MANUAL_TONE == 1
// Artistic controls on display-linear luminance. They do not change the
// sensor/model transfer functions or require a proprietary TCE context.
vec3 manualGrade(vec3 rgb) {
    float y=clamp(luminance(rgb),0.0,1.0);
    float target=y;
    if(manualContrast!=1.0 && y>0.0 && y<1.0) {
        // Monotonic contrast about 18% grey, with fixed black/white endpoints.
        const float pivot=0.18;
        float odds=pow((y/(1.0-y))/(pivot/(1.0-pivot)),manualContrast);
        target=(pivot*odds)/(1.0-pivot+pivot*odds);
    }
    if(manualGamma!=1.0) target=pow(target,1.0/manualGamma);
    // Separate luminance and chroma; compress chroma only as far as required
    // to fit SDR. This keeps neutral pixels neutral, even at saturation=2.
    vec3 chroma=(rgb-vec3(y))*(y>1.0e-7 ? target/y : 0.0)*manualSaturation;
    float extent=max(chroma.r,max(chroma.g,chroma.b));
    float depth=-min(chroma.r,min(chroma.g,chroma.b));
    float fit=1.0;
    if(extent>0.0) fit=min(fit,(1.0-target)/extent);
    if(depth>0.0) fit=min(fit,target/depth);
    return vec3(target)+chroma*max(fit,0.0);
}
#endif

void main() {
    ivec2 xy=ivec2(gl_FragCoord.xy);
    xy=mirrorCoords(xy,activeSize);
    vec3 inColor=max(texelFetch(InputBuffer,xy,0).rgb,vec3(0.0));

    /* Fusion map guided local gain: same 5x5 linear-model fit as the
     * former PhotonCamera Initial stage. */
    float tonemapGain=1.0;
    #if FUSION == 1
    vec4 moments=vec4(0.0);
    ivec2 sz=textureSize(InputBuffer,0);
    for(int i=-2;i<=2;i++) {
        for(int j=-2;j<=2;j++) {
            ivec2 p=clamp(xy+ivec2(i,j)*max(pxStepU,1),ivec2(0),sz-ivec2(1));
            vec2 offset=vec2(float(i),float(j))*float(max(pxStepU,1));
            float lightness=dot(texelFetch(InputBuffer,p,0).rgb,vec3(1.0/3.0));
            float gain=texture(FusionMap,(gl_FragCoord.xy+offset)/vec2(sz)).r;
            moments+=vec4(lightness,gain,lightness*lightness,lightness*gain);
        }
    }
    moments*=1.0/25.0;
    float meanX=moments.x;
    float meanY=moments.y;
    float covXY=moments.w-meanX*meanY;
    float varX=moments.z-meanX*meanX;
    float a=covXY/(max(varX,0.0)+3e-04);
    tonemapGain=a*luminocity(inColor)+(meanY-a*meanX);
    #endif
    /* Guard the fit: a negative slope would flip the pixel sign, a runaway
     * slope would blow the local exposure. */
    tonemapGain=clamp(tonemapGain,0.0,3.0);

    /* Lens shading gain: same bicubic sampling as the former PhotonCamera Initial stage. */
    vec4 gains=textureBicubicHardware(GainMap,vec2(xy)/vec2(textureSize(InputBuffer,0)));
    gains.rgb=vec3(gains.r,(gains.g+gains.b)/2.0,gains.a);
    float gainsVal=dot(gains.rgb,vec3(1.0/3.0));
    #if GCAM == 1
    // Only a part of the vignetting is lifted (GCam / LMC renders of the same scene keep about 60 % of it in the log).
    gainsVal=pow(max(gainsVal,1.0e-3),gcamColor.w);
    #endif

    vec3 neutralPoint=vec3(NEUTRALPOINT);
    float localGain=gainsVal*tonemapGain;
    float exposure=displayGain*localGain;
    #if MANUAL_TONE == 1
    exposure*=manualExposure;
    #endif
    vec3 wb=inColor*neutralPoint*exposure;

    /* Pre-tone local contrast (motionv2 reference-safe microcontrast). */
    float y=max(luminance(wb),0.0);
    if(y>1.0e-7) {
        float detail=log(1.0e-4+y)-localLogLumaMean(xy,exposure,neutralPoint);
        detail=clamp(detail,-0.20,0.20);
        float shadowGate=smoothstep(0.025,0.12,y);
        float highlightGate=1.0-smoothstep(0.55,0.92,y);
        float gate=shadowGate*highlightGate;
        wb*=exp(localContrast*gate*detail);
    }

    /* The lens/fusion local gain also scales the shoulder's white point, so
     * locally-flat-fielded highlights keep the same highlight rolloff instead
     * of stacking above the global headroom and clipping to flat white. */
    float whitePoint=max(sceneWhite*clamp(localGain,0.25,4.0),0.55);

    // Bounded shadow gain; black remains black and the shoulder is unaffected.
    wb*=1.0+shadowLift*(1.0-smoothstep(0.0,0.35,y));
    vec3 linearSrgb=intermediateToSRGB*sensorToIntermediate*wb;
    {
        float castY=max(luminance(linearSrgb),0.0);
        vec3 castMax=castTint/max(max3(castTint),1.0e-3);
        vec3 castLocal=mix(castTint,castMax,smoothstep(castRange.z,castRange.w,max3(linearSrgb)));
        linearSrgb*=mix(vec3(1.0),castLocal,smoothstep(castRange.x,castRange.y,castY));
    }
    #if GCAM == 1
    {
        vec2 uv=(vec2(xy)+0.5)/vec2(textureSize(InputBuffer,0));
        float base=texture(GcamBase,uv).r;
        float own=log2(max(luminance(inColor*neutralPoint),1.0e-7));
        float level=mix(base,own,smoothstep(1.0,2.0,abs(own-base)));
        float ev=level+log2(max(exposure,1.0e-7)/max(gcamKnee.y,1.0e-4));
        float x=max(ev,0.0);
        linearSrgb*=exp2(-(x-x/(1.0+x/max(gcamKnee.x,0.1))));
        float lv=max(luminance(linearSrgb),0.0);
        float lt=lv*lv/(lv+max(gcamCurve.x,1.0e-5));
        float f=gcamShoulder(lt);
        linearSrgb=lv>1.0e-7 ? linearSrgb*(f/lv) : vec3(0.0);
        float lo=luminance(linearSrgb);
        linearSrgb=vec3(lo)+gcamColor.x*(linearSrgb-vec3(lo));
        if (hueTint.z>0.5) linearSrgb=colourLook(max(linearSrgb,vec3(0.0)));
        linearSrgb=mix(linearSrgb,vec3(lo),smoothstep(gcamColor.z,0.98,lo)*gcamColor.y);
        linearSrgb=fitDisplayGamut(linearSrgb);
    }
    #elif AGX == 1
    /* Local highlight range (GCam/LMC-like local tone mapping): a bright region
     * (window, sky) is pulled down by its large-scale level only, so detail and
     * local contrast inside it survive and midtones stay untouched. Across a
     * strong edge (window frame) the pixel uses its own level instead of the
     * blurred base, so the wall next to a window gets no dark halo. */
    if(agxLocal.x>0.0) {
        vec2 uv=(vec2(xy)+0.5)/vec2(textureSize(InputBuffer,0));
        float base=texture(AgxBase,uv).r;
        float own=log2(max(luminance(inColor*neutralPoint),1.0e-7));
        float level=mix(base,own,smoothstep(1.0,2.0,abs(own-base)));
        float ev=level+log2(max(exposure*agxExposure,1.0e-7)/0.18);
        /* Hyperbolic knee: slope 1 up to the start, then the range above it folds into a
         * finite number of stops (asymptote R) instead of a fixed fraction of it, so a window
         * that is 8 stops over the room keeps its texture and does not clip. Strength 0.7 is
         * R=2.5 stops; strength 1 clamps to the start. */
        float x=max(ev-agxLocal.y,0.0);
        float knee=max(0.5,(1.0-agxLocal.x)*8.34);
        linearSrgb*=exp2(-(x-x/(1.0+x/knee)));
    }
    // AgX replaces the headroom shoulder, output scale and gamut fit: it forms
    // highlights itself (they attenuate toward white instead of clipping).
    linearSrgb=agxForm(linearSrgb);
    #else
    linearSrgb=mapExtendedLinearHeadroom(linearSrgb,whitePoint);
    linearSrgb*=outputExposureScale;
    linearSrgb=fitDisplayGamut(linearSrgb);
    #endif

    if(bentoReal<0.5)linearSrgb=neutralizeClippedMagenta(linearSrgb);
    #if MANUAL_TONE == 1
    // Exact neutral bypass preserves the previous renderer at defaults.
    if(manualContrast!=1.0 || manualGamma!=1.0 || manualSaturation!=1.0)
        linearSrgb=manualGrade(linearSrgb);
    #endif
    vec3 encoded=clamp(srgbEncode(linearSrgb),vec3(0.0),vec3(1.0));
    #if MANUAL_TONE == 1
    encoded=vec3(manualBlack)+(manualWhite-manualBlack)*encoded;
    #endif
    /* Triangular dither of +-1 LSB before the 8-bit output: smooth, strongly
     * compressed gradients otherwise break into visible steps. */
    vec2 fc=gl_FragCoord.xy;
    float n1=fract(sin(dot(fc,vec2(12.9898,78.233)))*43758.5453);
    float n2=fract(sin(dot(fc,vec2(39.3468,11.1353)))*24634.6345);
    #if GCAM == 1
    // NiceSharpen dithers the final 8-bit output after its filters.
    #else
    encoded=clamp(encoded+vec3((n1+n2-1.0)/255.0),vec3(0.0),vec3(1.0));
    #endif
    Output=vec4(encoded,1.0);
}
