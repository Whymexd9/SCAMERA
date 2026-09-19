// Preview uses the same ACES and darktable functions as the photo renderer.
uniform sampler2D photoExposureCurve;
uniform float photoWhitePoint;
uniform vec2 photoViewport;
float luminocity(vec3 v){return dot(v,vec3(0.299,0.587,0.114));}
/*PHOTO_HSV*/
/*PHOTO_SATURATION*/
/*PHOTO_ACES*/
/*PHOTO_DARKTABLE*/
float photoGamma(float x){
    float poly=GAMMAX1*x+GAMMAX2*x*x+GAMMAX3*x*x*x;
    return mix(poly,pow(max(x,0.0),1.0/max(PHOTO_GAMMA,0.1)),min(x*9.0,1.0));
}
vec3 photoDisplay(vec3 rgb){
    rgb/=max(photoWhitePoint,0.0001);
#if ACES_ENABLED == 1
    const mat3 SRGB_TO_AP1 = mat3(
        0.613097, 0.070194, 0.020616,
        0.339523, 0.916354, 0.109570,
        0.047379, 0.013452, 0.869815);
    const mat3 AP1_TO_SRGB = mat3(
         1.704858,-0.130076,-0.023964,
        -0.621716, 1.140736,-0.128975,
        -0.083299,-0.010560, 1.152939);
    const mat3 AP1_TO_P3 = mat3(
         1.378234,-0.060836,-0.019672,
        -0.308878, 1.090320,-0.098750,
        -0.069356,-0.029484, 1.118422);
    // Do not clamp AP1 here: tiny negative channel excursions are normal in
    // noisy shadows. Early per-channel clipping correlated the noise and made
    // red/green contour bands. Also do not multiply the low-resolution gain
    // map directly; its interpolation was the source of the broad stripes.
    vec3 ap1 = SRGB_TO_AP1 * rgb;
    ap1 *= exp2(ACES_EXPOSURE) * mix(1.0, 1.0, LTMMIX);
    float y = max(dot(ap1, vec3(0.272229,0.674082,0.053689)), 1e-6);
    float peakScale = clamp(ACES_PEAK / 100.0, 1.0, 40.0);
    float mappedY = acesTone(y,peakScale);
    float refIn=max(ACES_MID_GRAY,0.01);
    float refOut=max(acesTone(refIn,peakScale),1e-5);
    mappedY*=ACES_MID_GRAY/refOut;
    mappedY=mix(y,mappedY,clamp(ACES_TONE_MIX,0.0,1.0));
    mappedY = pow(max(mappedY,0.0), 1.0 / max(ACES_SURROUND,0.25));
    ap1 *= mappedY / y;
    float mx = max(ap1.r,max(ap1.g,ap1.b));
    float mn = min(ap1.r,min(ap1.g,ap1.b));
    float chroma = mx-mn;
    float over = max(mx-1.0,0.0);
    float compress = 1.0/(1.0 + ACES_GAMUT*(chroma+over));
    ap1 = mix(ap1,mix(vec3(mappedY),ap1,compress),ACES_HUE_PROTECTION);
    float hi = smoothstep(0.55,1.0,mappedY)*ACES_HIGHLIGHT_DESAT;
    ap1 = mix(ap1,vec3(mappedY),hi*clamp(over+0.15*chroma,0.0,0.75));
    #if ACES_OUTPUT_P3 == 1
    vec3 displayLinear = max(AP1_TO_P3 * ap1,vec3(0.0));
    #else
    vec3 displayLinear = max(AP1_TO_SRGB * ap1,vec3(0.0));
    #endif
    return clamp(acesEncode(displayLinear),0.0,1.0);
#else
    rgb=clamp(rgb,0.0,1.0);
    rgb=vec3(photoGamma(rgb.r),photoGamma(rgb.g),photoGamma(rgb.b));
    vec3 before=rgb;
    rgb=rgb*rgb*rgb*(-2.0+2.0*PHOTO_TONE_MIX)+rgb*rgb*(3.0-3.0*PHOTO_TONE_MIX)+rgb*PHOTO_TONE_MIX;
    // The min/mid/max tone polynomial is equivalent channelwise only at the extremes.
    float lo=min(before.r,min(before.g,before.b)),hi=max(before.r,max(before.g,before.b));
    float outLo=lo*lo*lo*(-2.0+2.0*PHOTO_TONE_MIX)+lo*lo*(3.0-3.0*PHOTO_TONE_MIX)+lo*PHOTO_TONE_MIX;
    float outHi=hi*hi*hi*(-2.0+2.0*PHOTO_TONE_MIX)+hi*hi*(3.0-3.0*PHOTO_TONE_MIX)+hi*PHOTO_TONE_MIX;
    rgb=vec3(outLo)+(outHi-outLo)*(before-vec3(lo))/max(hi-lo,1e-10);
    rgb=mix(rgb*rgb*rgb*TONEMAPX3+rgb*rgb*TONEMAPX2+rgb*TONEMAPX1,rgb,min(rgb*0.8+0.55,1.0));
    rgb=saturate(rgb,PHOTO_SATURATION,PHOTO_SATURATION*PHOTO_HIGH_SATURATION);
    vec3 curved=0.5+0.5*sin((2.0*rgb-1.0)*1.57079632679);
    rgb=mix(rgb,curved,mix(CONTRAST+SHADOWS,CONTRAST,luminocity(rgb)));
    return rgb;
#endif
}
vec3 applyPhotoLook(vec3 linearRgb){
    vec3 rgb=photoDisplay(linearRgb);
#if DARKTABLE_ENABLED == 1
    rgb=applyDarktableLook(rgb);
#endif
    rgb=clamp(rgb,0.0,1.0);
#if ACES_ENABLED == 0
    float lo=min(rgb.r,min(rgb.g,rgb.b)),hi=max(rgb.r,max(rgb.g,rgb.b));
    float outLo=texture(photoExposureCurve,vec2(lo,0.5)).r;
    float outHi=texture(photoExposureCurve,vec2(hi,0.5)).r;
    rgb=vec3(outLo)+(outHi-outLo)*(rgb-vec3(lo))/max(hi-lo,1e-10);
#endif
    return clamp(rgb,0.0,1.0);
}
