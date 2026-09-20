precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform sampler2D GainMap;
uniform vec3 whitePoint;
uniform vec2 inverseSize;
uniform ivec2 cropOffset;
out vec3 Output;
void main(){
    ivec2 p=clamp(ivec2(gl_FragCoord.xy)+cropOffset,ivec2(0),textureSize(InputBuffer,0)-1);
    vec4 sites=texture(GainMap,vec2(p)*inverseSize);
    vec3 gains=vec3(sites.r,(sites.g+sites.b)*.5,sites.a);
    gains/=max(dot(gains,vec3(1.0/3.0)),1e-6);
    Output=max(texelFetch(InputBuffer,p,0).rgb,vec3(0))*gains/max(whitePoint,vec3(1e-6));
}
