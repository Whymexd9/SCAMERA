precision highp float;
precision highp sampler2D;
uniform sampler2D InputBuffer;
uniform sampler2D CurveLut;
uniform float satGain;  // colour gain in the mid tones (1 = off)
uniform float whiteDesat;  // colour removed from the bright tones, 0..1 (0 = off)
#define LUTSIZE 1024
out vec3 Output;

// Tone + gamma curve baked into one LUT over display-encoded [0,1], per channel.
float curve(float v) {
    float x = clamp(v, 0.0, 1.0) * (float(LUTSIZE) - 1.0) + 0.5;
    return texture(CurveLut, vec2(x / float(LUTSIZE), 0.5)).r;
}
void main() {
    vec3 rgb = texelFetch(InputBuffer, ivec2(gl_FragCoord.xy), 0).rgb;
    vec3 o = vec3(curve(rgb.r), curve(rgb.g), curve(rgb.b));
    // GCam renders the mid tones with about 1.5-2x the colour of the fused NICE tone and keeps its
    // whites nearly neutral (the fused tone leaves a warm cast on lit white surfaces).
    float l = dot(o, vec3(0.2126, 0.7152, 0.0722));
    float bell = smoothstep(0.04, 0.22, l) * (1.0 - smoothstep(0.70, 0.92, l));
    float keep = 1.0 + (satGain - 1.0) * bell - whiteDesat * smoothstep(0.5, 0.82, l);
    Output = max(vec3(l) + (o - vec3(l)) * max(keep, 0.0), vec3(0.0));
}
