#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;   // P228: the live lens filter's tap offsets need the uv precision
uniform samplerExternalOES sTexture;
uniform vec2 resolution;
uniform bool enablePeak;
uniform bool mirror;
// P90: a post-shutter series frame brought back to the viewfinder's exposure (ViewfinderGain), 1.0 for viewfinder frames
uniform float uGain;
out vec4 Output;
in vec2 texCoord;
// P228 / owner, 2026-10-18 («не видна работа лута в видоискателе»): the film LUT on the ISP viewfinder too, as on the
// developed-RAW one (rawdevelop_fs) and the photo (WhyCombine): display values in, mixed by its strength
uniform highp sampler3D uLut;
uniform int uLutOn;
uniform float uLutStrength;
uniform float uLutSize;
uniform vec3 uLutMin;
uniform vec3 uLutScale;
#define LF_SAMPLE(p) texture(sTexture, p).rgb
//#lensfilter_live
void main() {
    vec2 uv = texCoord.xy;
    if(mirror)
        uv.y = 1.0 - uv.y;
    // P228: the screen's pixel steps in uv (the texture is rotated / mirrored to the screen), for the live lens filter
    vec2 px = dFdx(uv), py = dFdy(uv);
    vec4 frame = texture(sTexture, uv);
    vec4 color = frame;
    if (uGain != 1.0) color.rgb = pow(clamp(pow(max(color.rgb, 0.0), vec3(2.2)) * uGain, 0.0, 1.0), vec3(1.0 / 2.2));
    color.rgb = lensFilter(uv, color.rgb, px, -py, resolution);
    if (uLutOn == 1) {
        vec3 lc = (clamp((clamp(color.rgb, 0.0, 1.0) - uLutMin) * uLutScale, 0.0, 1.0) * (uLutSize - 1.0) + 0.5) / uLutSize;
        color.rgb = mix(color.rgb, texture(uLut, lc).rgb, uLutStrength);
    }
    vec2 size = resolution;
    // focus peaking (on the camera's frame itself: the filter and the LUT are no edges)
    // P314: its 9 taps only when it is on (every ISP frame and every pass of the developed RAW to the screen read them for
    // nothing; the output without peaking is the same)
    if (enablePeak) {
        vec4 avg = vec4(0.0);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                avg += texture(sTexture, uv + vec2(i*2, j*2) / size);
            }
        }
        avg /= 9.0;
        float diff = dot(abs(frame - avg), vec4(0.299, 0.587, 0.114, 0.0));
        float denoiseK = 0.05;
        // denoise
        float w = (diff * diff) /(denoiseK + (diff * diff));
        vec4 dc = vec4(1.0,0.0,1.0,0.0);
        color = color + dc*32.0*diff*w;
    }
    Output = color;
}
