#version 300 es
precision highp float;
precision highp sampler2D;
precision highp int;
uniform sampler2D GuideBuffer;
uniform ivec2 size;
uniform ivec2 phase;
uniform ivec4 quadColors;
uniform float blackLevel;
uniform float whiteLevel;
uniform float gainB;
uniform float gainR;
out uvec4 Output;
int colorAt(ivec2 p) {ivec2 r=(p+phase)%8;return quadColors[(r.y/4)*2+r.x/4];}
void main() {
    ivec2 xy=ivec2(gl_FragCoord.xy);
    int target=quadColors[(xy.y%2)*2+xy.x%2];
    vec2 self=texelFetch(GuideBuffer,xy,0).rg;
    float value=self.x;
    if(target!=1) {
        if(colorAt(xy)==target) {value=self.y;} else {
            // Local, regularized C=a*G+b model. Weights follow brightness
            // edges; all positions in the 8x8 CFA have measured support.
            float sw=0.0,sg=0.0,sc=0.0,sgg=0.0,sgc=0.0;
            for(int y=-4;y<=4;++y)for(int x=-4;x<=4;++x) {
                ivec2 p=xy+ivec2(x,y);
                if(any(lessThan(p,ivec2(0)))||any(greaterThanEqual(p,size)))continue;
                if(colorAt(p)!=target)continue;
                vec2 s=texelFetch(GuideBuffer,p,0).rg;
                float dg=s.x-self.x;
                float spatial=1.0/(1.0+0.2*float(x*x+y*y));
                float edge=1.0/(1.0+dg*dg/0.0025);
                float w=spatial*edge;
                sw+=w;sg+=w*s.x;sc+=w*s.y;sgg+=w*s.x*s.x;sgc+=w*s.x*s.y;
            }
            if(sw>0.0) {
                float g=sg/sw,c=sc/sw;
                float variance=max(sgg/sw-g*g,0.0);
                float cov=sgc/sw-g*c;
                // Prior slope 1 preserves achromatic fine detail even when
                // all available colour samples are on a flat background.
                float a=clamp((cov+0.0001)/(variance+0.0001),0.0,4.0);
                value=c+a*(self.x-g);
            } else {
                // Tiny images/degenerate crops: use nearest available colour
                // within one full CFA period rather than emitting zero.
                float distance=1e9;
                for(int y=-8;y<=8;++y)for(int x=-8;x<=8;++x) {
                    ivec2 p=xy+ivec2(x,y);float d=float(x*x+y*y);
                    if(any(lessThan(p,ivec2(0)))||any(greaterThanEqual(p,size)))continue;
                    if(colorAt(p)==target && d<distance) {value=texelFetch(GuideBuffer,p,0).g;distance=d;}
                }
            }
        }
        value/=max(target==0?gainR:gainB,1e-6);
    }
    float raw=clamp(value,0.0,1.0)*max(whiteLevel-blackLevel,1.0)+blackLevel;
    Output=uvec4(uint(clamp(raw+0.5,0.0,65535.0)),0u,0u,0u);
}
