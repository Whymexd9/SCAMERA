precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform sampler2D GainMap;
uniform vec3 whitePoint;
uniform vec3 clipLevel;
uniform vec2 inverseSize;
uniform ivec2 cropOffset;
out vec3 Output;
void main(){
    ivec2 p=clamp(ivec2(gl_FragCoord.xy)+cropOffset,ivec2(0),textureSize(InputBuffer,0)-1);
    vec4 sites=texture(GainMap,vec2(p)*inverseSize);
    vec3 gains=vec3(sites.r,(sites.g+sites.b)*.5,sites.a);
    gains/=max(dot(gains,vec3(1.0/3.0)),1e-6);
    vec3 raw=max(texelFetch(InputBuffer,p,0).rgb,vec3(0));
    vec3 c=raw*gains/max(whitePoint,vec3(1e-6));
    // Blown highlights: once any channel reaches its clip level the colour is no
    // longer measured; fade it to neutral at its brightest channel (white, not
    // the pink a clipped green leaves after white balance).
    float over=max(max(raw.r/clipLevel.r,raw.g/clipLevel.g),raw.b/clipLevel.b);
    float t=smoothstep(0.85,0.98,over);
    Output=mix(c,vec3(max(c.r,max(c.g,c.b))),t);
}
