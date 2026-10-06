// Viewfinder look of the live RAW preview: the former PhotonCamera gamma / tone curve at its fixed defaults.
uniform sampler2D photoExposureCurve;
uniform float photoWhitePoint;
uniform vec2 photoViewport;
float luminocity(vec3 v){return dot(v,vec3(0.299,0.587,0.114));}
/*PHOTO_HSV*/
/*PHOTO_SATURATION*/
float photoGamma(float x){
    float poly=GAMMAX1*x+GAMMAX2*x*x+GAMMAX3*x*x*x;
    return mix(poly,pow(max(x,0.0),1.0/max(PHOTO_GAMMA,0.1)),min(x*9.0,1.0));
}
vec3 photoDisplay(vec3 rgb){
    rgb/=max(photoWhitePoint,0.0001);
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
}
vec3 applyPhotoLook(vec3 linearRgb){
    vec3 rgb=photoDisplay(linearRgb);
    rgb=clamp(rgb,0.0,1.0);
    float lo=min(rgb.r,min(rgb.g,rgb.b)),hi=max(rgb.r,max(rgb.g,rgb.b));
    float outLo=texture(photoExposureCurve,vec2(lo,0.5)).r;
    float outHi=texture(photoExposureCurve,vec2(hi,0.5)).r;
    rgb=vec3(outLo)+(outHi-outLo)*(rgb-vec3(lo))/max(hi-lo,1e-10);
    return clamp(rgb,0.0,1.0);
}
