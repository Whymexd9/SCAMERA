precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform sampler2D FilterBank;
uniform int yOffset;
out vec4 Output;
#define INPUT_SIZE 1,1
#define OUTPUT_SIZE 1,1
#define BANK_SCALE 2
#define OUTPUT_SCALE 1.0
#define EFFECT 0.7
#define HALO 0.7
#define ALIAS 0.35
#define QUALITY 1
const vec3 YW=vec3(0.2126,0.7152,0.0722);
float luma(vec3 c){return dot(c,YW);}
vec3 sampleInput(vec2 p){
    vec2 uv=(clamp(p,vec2(0.0),vec2(INPUT_SIZE)-1.0)+0.5)/vec2(INPUT_SIZE);
    return texture(InputBuffer,uv).rgb;
}
float sampleY(vec2 p){return luma(sampleInput(p));}
void main(){
    ivec2 op=ivec2(gl_FragCoord.xy)+ivec2(0,yOffset);
    vec2 sp=(vec2(op)+0.5)/OUTPUT_SCALE-0.5;
    ivec2 cp=ivec2(floor(sp+0.5));
    float a=0.0,b=0.0,c=0.0,ws=0.0;
    int rad=QUALITY==1?2:1;
    for(int j=-2;j<=2;j++)for(int i=-2;i<=2;i++){
        if(abs(i)>rad||abs(j)>rad)continue;
        vec2 q=vec2(cp+ivec2(i,j));
        float gx=0.5*(sampleY(q+vec2(1,0))-sampleY(q-vec2(1,0)));
        float gy=0.5*(sampleY(q+vec2(0,1))-sampleY(q-vec2(0,1)));
        float w=QUALITY==1?exp(-float(i*i+j*j)*0.25):1.0;
        a+=w*gx*gx;b+=w*gx*gy;c+=w*gy*gy;ws+=w;
    }
    float disc=sqrt(max(0.0,(a-c)*(a-c)+4.0*b*b));
    float l1=0.5*(a+c+disc),l2=0.5*(a+c-disc);
    float theta=0.5*atan(2.0*b,a-c);if(theta<0.0)theta+=3.14159265359;
    int angle=clamp(int(floor(theta*24.0/3.14159265359)),0,23);
    float descriptor=sqrt(max(0.0,l1/max(ws,1e-6)))*255.0;
    int sb=descriptor<20.0?0:(descriptor<30.0?1:2);
    float coh=(sqrt(max(l1,0.0))-sqrt(max(l2,0.0)))/(sqrt(max(l1,0.0))+sqrt(max(l2,0.0))+1e-7);
    int cb=coh*255.0<102.0?0:(coh*255.0<153.0?1:2);
    int px=int(floor((float(op.x)+0.5)*float(BANK_SCALE)/OUTPUT_SCALE))%BANK_SCALE;
    int py=int(floor((float(op.y)+0.5)*float(BANK_SCALE)/OUTPUT_SCALE))%BANK_SCALE;
    int phase=py*BANK_SCALE+px;
    int kernel=(((phase*24+angle)*3+sb)*3+cb);
    vec3 base=sampleInput(sp);float baseY=luma(base);
    float filtered=0.0,lo=1.0,hi=0.0,mean=0.0,n=0.0;
    int stepv=QUALITY==1?1:2;
    for(int j=-2;j<=2;j++)for(int i=-2;i<=2;i++){
        if((i+2)%stepv!=0||(j+2)%stepv!=0)continue;
        float yy=sampleY(sp+vec2(i,j)/OUTPUT_SCALE);
        filtered+=texelFetch(FilterBank,ivec2((j+2)*5+i+2,kernel),0).r*yy;
    }
    for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++){
        float yy=sampleY(sp+vec2(i,j)/OUTPUT_SCALE);lo=min(lo,yy);hi=max(hi,yy);mean+=yy;n+=1.0;
    }
    mean/=n;float range=hi-lo;
    filtered=clamp(filtered,lo-range*(1.0-HALO)*0.5,hi+range*(1.0-HALO)*0.5);
    float aliasGate=(1.0-coh)*min(1.0,descriptor/30.0);
    filtered=mix(filtered,mean,ALIAS*aliasGate);
    float newY=baseY+(filtered-baseY)*EFFECT;
    vec3 result=base*(newY/max(baseY,1e-5));
    Output=vec4(clamp(result,0.0,1.0),1.0);
}
