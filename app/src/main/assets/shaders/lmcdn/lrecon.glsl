precision highp float;
precision highp int;
precision highp sampler2D;
// LMC luma noise reduction, reconstruction of level L (GCam luma_denoise): recon = Y[L] + Up2(delta), delta = the
// denoised minus the noisy next coarser level (on level 0 also minus its 1-2-1 low pass, which is added back at
// the end). Up2 = bilinear x2 at the coarse position p/2 - 0.25 (GCam UpsampleDelta), integer texel fetches.
uniform sampler2D Base;    // Y of this level (.r)
uniform sampler2D Delta;   // delta of the next coarser level (.r)
out float Output;
float up2(sampler2D t, ivec2 p) {
    ivec2 b = p >> 1, o = ((p & 1) << 1) - 1;          // even: -1, odd: +1
    ivec2 n = clamp(b + o, ivec2(0), textureSize(t, 0) - 1);
    b = min(b, textureSize(t, 0) - 1);
    vec4 a = vec4(texelFetch(t, b, 0).r, texelFetch(t, ivec2(n.x, b.y), 0).r,
                  texelFetch(t, ivec2(b.x, n.y), 0).r, texelFetch(t, n, 0).r);
    return dot(a, vec4(0.5625, 0.1875, 0.1875, 0.0625));
}
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    Output = texelFetch(Base, p, 0).r + up2(Delta, p);
}
