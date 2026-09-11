#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES sTexture;
uniform vec2 resolution;
uniform bool enablePeak;
uniform bool mirror;
// Tone curve of the last processed shot, replayed on the live stream so the
// viewfinder shows the tonemapping and shadow/highlight placement the saved
// photo will get. Detail is another matter: merged denoise and MFSR need a
// burst and cannot appear in a live frame.
uniform sampler2D uToneCurve;
uniform bool uLookEnabled;
out vec4 Output;
in vec2 texCoord;
void main() {
    vec2 uv = texCoord.xy;
    if(mirror)
        uv.y = 1.0 - uv.y;
    vec4 color = texture(sTexture, uv);
    vec2 size = resolution;
    // focus peaking
    vec4 avg = vec4(0.0);
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            avg += texture(sTexture, uv + vec2(i*2, j*2) / size);
        }
    }
    avg /= 9.0;
    float diff = dot(abs(color - avg), vec4(0.299, 0.587, 0.114, 0.0));
    float denoiseK = 0.05;
    // denoise
    float w = (diff * diff) /(denoiseK + (diff * diff));
    vec4 dc = vec4(1.0,0.0,1.0,0.0);
    if(enablePeak)
        color = color + dc*32.0*diff*w;
    if (uLookEnabled) {
        // The curve maps linear sensor values to display values, but this stream
        // has already been tonemapped by the ISP. Applying the curve straight to
        // it tonemaps twice and blows the image out, which is what the first
        // build did. Undo the display encoding first, then apply the curve once.
        vec3 lin = pow(clamp(color.rgb, 0.0, 1.0), vec3(2.2));
        color.r = texture(uToneCurve, vec2(clamp(lin.r, 0.0, 1.0), 0.5)).r;
        color.g = texture(uToneCurve, vec2(clamp(lin.g, 0.0, 1.0), 0.5)).r;
        color.b = texture(uToneCurve, vec2(clamp(lin.b, 0.0, 1.0), 0.5)).r;
    }
    Output = color;
}