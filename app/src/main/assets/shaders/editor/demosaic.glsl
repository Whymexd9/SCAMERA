// P351: a RAW's samples (16-bit, x uScale = 1.0 at white) -> linear camera RGB. Bayer: Malvar-He-Cutler 5x5 (gradient-corrected
// bilinear); the edges are mirrored by two pixels so the CFA phase holds. uKind 1: already RGB (LinearRaw, LibRaw's own demosaic).
uniform highp usampler2D uRawTex;
uniform ivec2 uSize;
uniform ivec4 uCfa;      // colour 0 R / 1 G / 2 B at (0,0) (1,0) (0,1) (1,1) of this texture
uniform float uScale;
uniform int uKind;
out vec4 o;

ivec2 mirror(ivec2 p) {
    p = abs(p);
    return (uSize - 1) - abs((uSize - 1) - p);
}

float px(ivec2 p) { return float(texelFetch(uRawTex, mirror(p), 0).r) * uScale; }

int cfa(ivec2 p) {
    int i = (p.x & 1) + 2 * (p.y & 1);
    return i == 0 ? uCfa.x : (i == 1 ? uCfa.y : (i == 2 ? uCfa.z : uCfa.w));
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    if (uKind == 1) {
        uvec4 v = texelFetch(uRawTex, p, 0);
        o = vec4(vec3(v.rgb) * uScale, 1.0);
        return;
    }
    float C = px(p);
    float N = px(p + ivec2(0, -1)), S = px(p + ivec2(0, 1)), E = px(p + ivec2(1, 0)), W = px(p + ivec2(-1, 0));
    float NN = px(p + ivec2(0, -2)), SS = px(p + ivec2(0, 2)), EE = px(p + ivec2(2, 0)), WW = px(p + ivec2(-2, 0));
    float D = px(p + ivec2(1, 1)) + px(p + ivec2(-1, 1)) + px(p + ivec2(1, -1)) + px(p + ivec2(-1, -1));
    int c = cfa(p);
    vec3 rgb;
    if (c == 1) {
        float hor = (5.0 * C + 4.0 * (E + W) - D - (EE + WW) + 0.5 * (NN + SS)) / 8.0;
        float ver = (5.0 * C + 4.0 * (N + S) - D - (NN + SS) + 0.5 * (EE + WW)) / 8.0;
        rgb = cfa(p + ivec2(1, 0)) == 0 ? vec3(hor, C, ver) : vec3(ver, C, hor);
    } else {
        float g = (4.0 * C + 2.0 * (N + S + E + W) - (NN + SS + EE + WW)) / 8.0;
        float other = (6.0 * C + 2.0 * D - 1.5 * (NN + SS + EE + WW)) / 8.0;
        rgb = c == 0 ? vec3(C, g, other) : vec3(other, g, C);
    }
    o = vec4(max(rgb, 0.0), 1.0);
}
