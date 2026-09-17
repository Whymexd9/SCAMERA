#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;
uniform usampler2D RawBuffer;
uniform sampler2D CoarseBuffer;
uniform ivec2 size;
uniform ivec2 phase;
uniform ivec4 quadColors;
uniform float blackLevel;
uniform float whiteLevel;
uniform float gainB;
uniform float gainR;
uniform float blockGain[64];
uniform vec2 detailTrust;
out vec4 Output;
int quadrant(ivec2 p) { ivec2 r=(p+phase)%8; return (r.y/4)*2+r.x/4; }
float sampleAt(ivec2 p) {
    int q=quadrant(p); ivec2 sub=(p+phase)%4;
    float v=(float(texelFetch(RawBuffer,p,0).r)-blackLevel)/max(whiteLevel-blackLevel,1.0);
    v=clamp(v*blockGain[q*16+sub.y*4+sub.x],0.0,1.0);
    int c=quadColors[q]; return v*(c==0?gainR:c==2?gainB:1.0);
}
float coarseAt(ivec2 xy,int q) {
    ivec2 offset=ivec2(q%2,q/2)*4;
    vec2 t=(vec2(xy+phase-offset)-vec2(1.5))/8.0;
    ivec2 lo=ivec2(floor(t)); vec2 f=fract(t);
    // First/last cell with at least one real sample for this quadrant.
    ivec2 first=max(ivec2(0),(phase-offset+ivec2(4))/8);
    ivec2 last=(size-1+phase-offset)/8;
    last=max(last,first);
    float a=texelFetch(CoarseBuffer,clamp(lo,first,last),0)[q];
    float b=texelFetch(CoarseBuffer,clamp(lo+ivec2(1,0),first,last),0)[q];
    float c=texelFetch(CoarseBuffer,clamp(lo+ivec2(0,1),first,last),0)[q];
    float d=texelFetch(CoarseBuffer,clamp(lo+ivec2(1,1),first,last),0)[q];
    return mix(mix(a,b,f.x),mix(c,d,f.x),f.y);
}
vec2 chroma(ivec2 cell) {
    ivec2 hi=textureSize(CoarseBuffer,0)-1;
    vec4 m=texelFetch(CoarseBuffer,clamp(cell,ivec2(0),hi),0);
    vec3 rgb=vec3(0.0);
    for(int k=0;k<4;++k)rgb[quadColors[k]]+=m[k]*(quadColors[k]==1?0.5:1.0);
    return log(max(rgb.rb,vec2(0.005))/max(rgb.g,0.005));
}
vec3 axisGreen(ivec2 xy,ivec2 dir) {
    float a=0.0,b=0.0; int da=0,db=0;
    for(int d=1;d<=8;++d) {
        ivec2 pa=xy-dir*d,pb=xy+dir*d;
        if(da==0 && all(greaterThanEqual(pa,ivec2(0))) && all(lessThan(pa,size))) {
            if(quadColors[quadrant(pa)]==1) {a=sampleAt(pa);da=d;}
        }
        if(db==0 && all(greaterThanEqual(pb,ivec2(0))) && all(lessThan(pb,size))) {
            if(quadColors[quadrant(pb)]==1) {b=sampleAt(pb);db=d;}
        }
    }
    if(da==0 && db==0)return vec3(0.0);
    if(da==0)return vec3(b,0.0,0.5);
    if(db==0)return vec3(a,0.0,0.5);
    float span=float(da+db);
    return vec3((a*float(db)+b*float(da))/span,abs(a-b)/span,1.0);
}
void main() {
    ivec2 xy=ivec2(gl_FragCoord.xy);
    int q=quadrant(xy),c=quadColors[q]; float raw=sampleAt(xy);
    if(c==1) {Output=vec4(raw,raw,0.0,1.0);return;}
    vec3 h=axisGreen(xy,ivec2(1,0)),v=axisGreen(xy,ivec2(0,1));
    float wh=h.z/(0.01+h.y),wv=v.z/(0.01+v.y);
    float directional=(h.x*wh+v.x*wv)/max(wh+wv,1e-6);
    float baseG=0.0;
    for(int k=0;k<4;++k)if(quadColors[k]==1)baseG+=0.5*coarseAt(xy,k);
    float baseC=coarseAt(xy,q);
    // Transfer measured high-frequency luminance with a local colour ratio.
    // Use less transfer when the supporting colour is dark or saturated.
    float floorSignal=4.0/max(whiteLevel-blackLevel,1.0);
    float ratio=baseG/max(baseC,floorSignal);
    float confidence=min(baseC,baseG)/max(max(baseC,baseG),floorSignal);
    ivec2 cell=(xy+phase)/8;
    vec2 dh=abs(chroma(cell+ivec2(2,0))-chroma(cell-ivec2(2,0)));
    vec2 dv=abs(chroma(cell+ivec2(0,2))-chroma(cell-ivec2(0,2)));
    float edge=max(max(dh.x,dh.y),max(dv.x,dv.y));
    confidence*=1.0/(1.0+pow(edge/0.12,4.0));
    confidence*=smoothstep(floorSignal,4.0*floorSignal,min(baseC,baseG));
    float detail=raw*clamp(ratio,0.25,4.0);
    float green=mix(directional,detail,0.85*confidence*(c==2?detailTrust.x:detailTrust.y));
    Output=vec4(clamp(green,0.0,1.0),raw,0.0,1.0);
}
