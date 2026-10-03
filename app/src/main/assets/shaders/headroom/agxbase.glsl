precision highp float;
precision highp sampler2D;
// AgX local highlight range, step 1: large-scale log2 luminance at 1/16 of the
// SENSOR grid (HeadroomRender.buildAgxBase downsamples by 4*outputScale, then 4,
// so a texel is 16 sensor px on the 1x and on the Sabre 2x grid alike); Gaussian
// sigma 2.5 texels = ~40 sensor px. render.glsl compares it with each pixel to
// decide how far a bright region sits above grey.
uniform sampler2D InputBuffer; // downsampled linear camera RGB
uniform vec3 neutral;          // white balance, as in render.glsl
out float Output;
void main() {
    ivec2 xy=ivec2(gl_FragCoord.xy);
    ivec2 sz=textureSize(InputBuffer,0);
    float sum=0.0,wsum=0.0;
    for(int j=-4;j<=4;j++)for(int i=-4;i<=4;i++){
        ivec2 p=clamp(xy+ivec2(i,j),ivec2(0),sz-ivec2(1));
        vec3 c=max(texelFetch(InputBuffer,p,0).rgb,vec3(0.0))*neutral;
        float w=exp(-float(i*i+j*j)/(2.0*2.5*2.5));
        sum+=w*log2(max(dot(c,vec3(0.2126,0.7152,0.0722)),1.0e-7));
        wsum+=w;
    }
    Output=sum/wsum;
}
