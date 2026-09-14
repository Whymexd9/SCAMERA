// Seeds the Richardson-Lucy estimate. RT starts from luminance clamped at zero
// (deconvsharpening, rtengine/ipsharpen.cc) and iterates in that single channel.
precision highp float;
precision mediump sampler2D;
uniform sampler2D InputBuffer;
out vec3 Output;

void main() {
    ivec2 xy = ivec2(gl_FragCoord.xy);
    float y = dot(texelFetch(InputBuffer, xy, 0).rgb, vec3(0.2126, 0.7152, 0.0722));
    Output = vec3(max(y, 0.0), 0.0, 0.0);
}
