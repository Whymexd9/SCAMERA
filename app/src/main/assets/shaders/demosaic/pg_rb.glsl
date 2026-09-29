precision highp float;
precision mediump sampler2D;
// Pixel-grouping demosaic (GCam), step 2: red/blue by hue transit along the
// green: linear in green where green is monotonic across the pair, otherwise
// the average plus the green Laplacian. At R/B sites the diagonal with the
// smaller gradient is used.
uniform sampler2D RawBuffer;
uniform sampler2D GreenBuffer;
uniform int yOffset;
uniform vec4 neutral;
out vec3 Output;
ivec2 sz;
ivec2 mirrorPhase(ivec2 p){
    if(p.x<0)p.x=-p.x; if(p.y<0)p.y=-p.y;
    if(p.x>=sz.x)p.x=2*(sz.x-1)-p.x; if(p.y>=sz.y)p.y=2*(sz.y-1)-p.y;
    return p;
}
float S(ivec2 p){
    p=mirrorPhase(p);
    return texelFetch(RawBuffer,p,0).r/neutral[((p.y&1)<<1)|(p.x&1)];
}
float G(ivec2 p){ return texelFetch(GreenBuffer,mirrorPhase(p),0).r; }
float hueTransit(float l1,float l2,float l3,float v1,float v3){
    if((l1<l2&&l2<l3)||(l1>l2&&l2>l3))return v1+(v3-v1)*(l2-l1)/(l3-l1);
    return (v1+v3)*0.5+(l2*2.0-l1-l3)*0.25;
}
float diagonal(ivec2 xy,float g){
    ivec2 ne=xy+ivec2(1,-1),sw=xy+ivec2(-1,1),nw=xy+ivec2(-1,-1),se=xy+ivec2(1,1);
    float gNE=abs(S(ne)-S(sw))+abs(G(ne)-g)+abs(g-G(sw));
    float gNW=abs(S(nw)-S(se))+abs(G(nw)-g)+abs(g-G(se));
    return gNE<gNW?hueTransit(G(ne),g,G(sw),S(ne),S(sw)):hueTransit(G(nw),g,G(se),S(nw),S(se));
}
void main(){
    ivec2 xy=ivec2(gl_FragCoord.xy);
    xy+=ivec2(0,yOffset);
    sz=textureSize(RawBuffer,0);
    float g=G(xy),r,b;
    int fx=xy.x&1,fy=xy.y&1;
    ivec2 w=xy+ivec2(-1,0),e=xy+ivec2(1,0),n=xy+ivec2(0,-1),s=xy+ivec2(0,1);
    if(fx==0&&fy==0){r=S(xy);b=diagonal(xy,g);}
    else if(fx==1&&fy==1){b=S(xy);r=diagonal(xy,g);}
    else if(fx==1){ // green on a red row: red left/right, blue above/below
        r=hueTransit(G(w),g,G(e),S(w),S(e));b=hueTransit(G(n),g,G(s),S(n),S(s));
    } else {        // green on a blue row
        b=hueTransit(G(w),g,G(e),S(w),S(e));r=hueTransit(G(n),g,G(s),S(n),S(s));
    }
    Output=clamp(vec3(r,g,b)*neutral.rga,0.0,1.0);
}
