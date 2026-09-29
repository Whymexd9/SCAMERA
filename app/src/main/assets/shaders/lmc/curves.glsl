precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform sampler2D CurveLut;
#define LUTSIZE 1024
out vec3 Output;

// Tone + gamma curve baked into one LUT over display-encoded [0,1], per channel.
float curve(float v) {
    float x = clamp(v, 0.0, 1.0) * (float(LUTSIZE) - 1.0) + 0.5;
    return texture(CurveLut, vec2(x / float(LUTSIZE), 0.5)).r;
}
void main() {
    vec3 rgb = texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0).rgb;
    Output = vec3(curve(rgb.r), curve(rgb.g), curve(rgb.b));
}
