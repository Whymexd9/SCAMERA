#define LAYOUT //
LAYOUT
precision highp float;
precision highp image2D;
layout(rgba16f,binding=0) readonly uniform image2D inputMask;
layout(rgba16f,binding=1) writeonly uniform image2D outputMask;
void main() {
    ivec2 p=ivec2(gl_GlobalInvocationID.xy), sz=imageSize(outputMask);
    if(any(greaterThanEqual(p,sz)))return;
    float center=imageLoad(inputMask,p).r, low=center, mean=0.0;
    for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++) {
        float v=imageLoad(inputMask,clamp(p+ivec2(x,y),ivec2(0),sz-1)).r;
        low=min(low,v);mean+=v/9.0;
    }
    // Never turn an invalid sample back into a valid one. Soft erosion also
    // prevents a narrow seam between repaired highlights and moving objects.
    imageStore(outputMask,p,vec4(min(center,mix(low,mean,0.25))));
}
