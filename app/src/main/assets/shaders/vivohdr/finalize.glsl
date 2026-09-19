#define LAYOUT //
LAYOUT
precision highp float;
precision highp image2D;
layout(rgba16f,binding=0) readonly uniform image2D referenceTexture;
layout(rgba16f,binding=1) readonly uniform image2D mergedTexture;
layout(rgba16f,binding=2) readonly uniform image2D massTexture;
layout(rgba16f,binding=3) writeonly uniform image2D outputTexture;
uniform float referenceScale;
uniform vec4 whitePoint;
float peak(vec4 c){return max(max(c.r,c.g),max(c.b,c.a));}
void main() {
    ivec2 p=ivec2(gl_GlobalInvocationID.xy), sz=imageSize(outputTexture);
    if(any(greaterThanEqual(p,sz)))return;
    vec4 ref=imageLoad(referenceTexture,p), state=imageLoad(massTexture,p);
    float clipped=smoothstep(0.90,0.995,peak(ref)/max(referenceScale,1e-8));
    // Feather inward only: rejected donors never become contributors. This
    // final pass does not feed softened pixels back into the radiance average.
    float near=state.a, middle=state.a, far=state.a;
    for(int y=-3;y<=3;y++)for(int x=-3;x<=3;x++) {
        float c=imageLoad(massTexture,clamp(p+ivec2(x,y),ivec2(0),sz-1)).a;
        int radius=max(abs(x),abs(y));
        if(radius<=1)near=min(near,c);
        if(radius<=2)middle=min(middle,c);
        far=min(far,c);
    }
    float support=(state.a+near+middle+far)*0.25;
    float trust=smoothstep(0.0,1.0,support);
    trust*=smoothstep(0.0,0.01,state.r);
    // No reliable short donor: neutralize only the clipped fallback, in the
    // sensor's white-balance ratios. Recovered colour is left untouched.
    vec4 wp=max(whitePoint,vec4(1e-6));
    vec4 neutral=wp*peak(ref/wp);
    vec4 fallback=mix(ref,neutral,clipped);
    vec4 merged=imageLoad(mergedTexture,p);
    imageStore(outputTexture,p,mix(merged,fallback,clipped*(1.0-trust)));
}
