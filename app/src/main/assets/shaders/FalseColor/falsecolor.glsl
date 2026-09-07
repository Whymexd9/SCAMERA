precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
out vec3 Output;
#define SIZE 1,1
#define STRENGTH 0.75
#define PURPLE 0.8
#define GREEN 0.65
#define CA_RED 0.0
#define CA_BLUE 0.0

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
float yOf(vec3 c) { return dot(c, LUMA); }
vec2 chroma(vec3 c) { float y=yOf(c); return vec2(c.r-y, c.b-y); }
vec3 fromYChroma(float y, vec2 ch) {
    float r=y+ch.x, b=y+ch.y;
    float g=(y-0.2126*r-0.0722*b)/0.7152;
    return vec3(r,g,b);
}
vec3 fetchSafe(ivec2 p) { return texelFetch(InputBuffer, clamp(p, ivec2(0), ivec2(SIZE)-1), 0).rgb; }

void main() {
    ivec2 p=ivec2(gl_FragCoord.xy);
    vec2 uv=gl_FragCoord.xy/vec2(SIZE);
    vec2 radial=(uv-0.5)*abs(uv-0.5);
    // Small independent red/blue radial correction.  The range is deliberately
    // conservative: one full slider is roughly one pixel at the frame edge.
    vec2 px=1.0/vec2(SIZE);
    vec3 center=fetchSafe(p);
    center.r=texture(InputBuffer, clamp(uv+radial*px*CA_RED*4.0, px*0.5, 1.0-px*0.5)).r;
    center.b=texture(InputBuffer, clamp(uv+radial*px*CA_BLUE*4.0, px*0.5, 1.0-px*0.5)).b;
    if (STRENGTH <= 0.001) { Output=center; return; }

    float yl=yOf(fetchSafe(p+ivec2(-1,0))), yr=yOf(fetchSafe(p+ivec2(1,0)));
    float yd=yOf(fetchSafe(p+ivec2(0,-1))), yu=yOf(fetchSafe(p+ivec2(0,1)));
    vec2 grad=vec2(yr-yl,yu-yd);
    float edge=clamp(length(grad)*7.0,0.0,1.0);
    ivec2 n=abs(grad.x)>abs(grad.y) ? ivec2(1,0) : ivec2(0,1);

    vec2 c0=chroma(center), cm1=chroma(fetchSafe(p-n)), cp1=chroma(fetchSafe(p+n));
    vec2 cm2=chroma(fetchSafe(p-2*n)), cp2=chroma(fetchSafe(p+2*n));
    // Robust five-sample trimmed chroma mean: suppresses alternating Bayer
    // zipper colour but does not blur chroma away from a luminance edge.
    vec2 lo=min(min(cm2,cm1),min(cp1,cp2));
    vec2 hi=max(max(cm2,cm1),max(cp1,cp2));
    vec2 stable=(cm2+cm1+c0+cp1+cp2-lo-hi)/3.0;
    float anomaly=clamp(length(c0-stable)*10.0,0.0,1.0);
    float y=yOf(center);
    float purple=max(0.0, center.r+center.b-2.0*center.g);
    float green=max(0.0, 2.0*center.g-center.r-center.b);
    float fringe=clamp(purple*PURPLE*4.0+green*GREEN*3.0,0.0,1.0);
    float amount=STRENGTH*edge*max(anomaly,fringe);
    vec2 corrected=mix(c0,stable,amount);
    Output=clamp(fromYChroma(y,corrected),0.0,1.0);
}
