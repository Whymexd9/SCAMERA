precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform int rotation;
out vec4 Output;
void main() {
    vec2 uv=gl_FragCoord.xy/vec2(512.0,384.0);
    if(rotation==90) uv=vec2(uv.y,1.0-uv.x);
    else if(rotation==180) uv=1.0-uv;
    else if(rotation==270) uv=vec2(1.0-uv.y,uv.x);
    // At this stage RGB has passed the chosen display/tone pipeline. Only the
    // model thumbnail is bounded; the full-resolution image retains headroom.
    Output=vec4(clamp(texture(InputBuffer,uv).rgb,0.0,1.0),1.0);
}
