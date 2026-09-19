precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform vec2 noiseModel;
uniform float lumaAmount;
uniform float chromaAmount;
uniform int radiusStep;
out vec4 Output;
void main() {
    ivec2 p=ivec2(gl_FragCoord.xy), sz=textureSize(InputBuffer,0);
    vec3 c=max(texelFetch(InputBuffer,p,0).rgb,vec3(0));
    float y=dot(c,vec3(1.0/3.0));
    vec3 sum=vec3(0);float mass=0.0;
    vec3 variance=max(c*noiseModel.x+noiseModel.y,vec3(1e-9));
    float strength=max(lumaAmount,chromaAmount);
    for(int j=-2;j<=2;j++)for(int i=-2;i<=2;i++) {
        vec3 v=max(texelFetch(InputBuffer,clamp(p+ivec2(i,j)*radiusStep,ivec2(0),sz-1),0).rgb,vec3(0));
        vec3 d=v-c;
        float distance=dot(d*d/variance,vec3(1.0/3.0));
        float w=exp(-0.3*float(i*i+j*j)-distance/max(4.0*strength*strength,0.01));
        sum+=v*w;mass+=w;
    }
    vec3 filtered=sum/max(mass,1e-8);
    float fy=dot(filtered,vec3(1.0/3.0));
    // Independent luma/chroma controls, both exactly bypassed at zero.
    float outputY=mix(y,fy,clamp(lumaAmount,0.0,1.0));
    vec3 chroma=mix(c-vec3(y),filtered-vec3(fy),clamp(chromaAmount,0.0,1.0));
    Output=vec4(max(vec3(outputY)+chroma,vec3(0)),1);
}
