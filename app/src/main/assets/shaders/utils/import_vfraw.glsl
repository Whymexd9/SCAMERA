// P365 (owner, 2026-10-27: «В арк камере тоже есть живой видоискатель, сделай его такимже по требованиям как там»): the RAW
// frame's linear colour for why/combine.glsl RAW_SOURCE 1 - the RAW viewfinder's one fused pass (LiveWhyTone.drawFused: the
// photo's WHY tone straight from the RAW texture, no RGBA16F linear frame and no second pass). The uniforms are
// preview/rawdevelop_fs.glsl's (LiveRawRenderer.bindFrame sets them as for rawdevelop; the RAW on unit 9, the lens-shading map
// on unit 10: why/combine takes 0 - 8). getUnpackedPixel .. getStandardBayerColor are rawdevelop_fs's own, copied unchanged
// (LiveRawRenderer loads rawdevelop_fs without imports; tools/check_vf_fused.py keeps the copies identical).
//  vfColour(pos)    rawdevelop_fs main's colour at a frame position (texCoord) before the lens shading: mirror, LDC, the range-
//                   filtered quad of a plain frame, one colour per 2 x 2 macro pixels of a mosaic or of cell means. The same
//                   values from textureGather (ES 3.1): a filtered quad is 5 gathers instead of 20 fetches, so POINT_FUSED's
//                   3 x 3 colour neighbourhood takes the very colours of P314's linear frame (9 x 5 gathers; unfiltered quads
//                   differed by up to 9 / 255 in noisy shadows: tools/check_vf_fused.py).
//  vfShading(pos)   the lens-shading gain at a frame position, 1 without a map.
//  vfLinear(pos)    the linear frame's value (rawdevelop_fs u_linear_out 1), without its half-float rounding.
precision highp usampler2D;
uniform int mirror;
uniform int cfa_block_size;
uniform usampler2D sTexture16;
uniform int width;
uniform int height;
uniform float r_gain;
uniform float g_gain;
uniform float b_gain;
uniform int crop_left;
uniform int crop_top;
uniform int crop_width;
uniform int crop_height;
uniform int bayer_pattern;
uniform int cfa_mode;
uniform mat3 color_transform;
uniform sampler2D u_lsc_map;
uniform float u_lsc_intensity;
uniform vec4 u_black_level;
uniform float u_white_level;
uniform int u_ldc_enabled;
uniform float u_ldc_aspect;
uniform float u_ldc_inv_aspect;
uniform float u_ldc_k1;
uniform float u_ldc_k2;
uniform float u_ldc_k3;
uniform float u_ldc_p1;
uniform float u_ldc_cx;
uniform float u_ldc_cy;

float getUnpackedPixel(int x, int y) {
    // Preserve CFA phase at borders: +/-2 must stay on the same colour plane.
    int mask = max(cfa_block_size,1)*2 - 1;
    int px = x & mask;
    int py = y & mask;
    x = clamp(x, px, width-1-((width-1-px)&mask));
    y = clamp(y, py, height-1-((height-1-py)&mask));
    return float(texelFetch(sTexture16, ivec2(x,y),0).r);
}
float getMacroPixel(int mx, int my) {
    int block=max(cfa_block_size,1);
    float sum=0.0;
    for(int y=0;y<block;y++) for(int x=0;x<block;x++)
        sum+=getUnpackedPixel(mx*block+x,my*block+y);
    return sum/float(block*block);
}
vec3 apply_wb_and_hl_clip(float R, float G, float B, float r_g, float g_g, float b_g) {
    vec3 c = vec3(R * r_g, G * g_g, B * b_g);
    float raw_max = max(R, max(G, B));
    if (raw_max > 0.85) {
        float hl_fade = smoothstep(0.85, 0.98, raw_max);
        float max_ch = max(c.r, max(c.g, c.b));
        c = mix(c, vec3(max_ch), hl_fade);
    }
    return c;
}
vec3 getFastColor(int mx, int my) {
    int bx = mx & ~1;
    int by = my & ~1;
    float m00 = getMacroPixel(bx, by);
    float m10 = getMacroPixel(bx + 1, by);
    float m01 = getMacroPixel(bx, by + 1);
    float m11 = getMacroPixel(bx + 1, by + 1);
    float R = 0.0, G = 0.0, B = 0.0;
    if (bayer_pattern == 0) {
        float r_val  = max(0.0, (m00 - u_black_level.x) / max(1.0, u_white_level - u_black_level.x));
        float gr_val = max(0.0, (m10 - u_black_level.y) / max(1.0, u_white_level - u_black_level.y));
        float gb_val = max(0.0, (m01 - u_black_level.z) / max(1.0, u_white_level - u_black_level.z));
        float b_val  = max(0.0, (m11 - u_black_level.w) / max(1.0, u_white_level - u_black_level.w));
        R = r_val; G = (gr_val + gb_val) * 0.5; B = b_val;
    } else if (bayer_pattern == 1) {
        float gr_val = max(0.0, (m00 - u_black_level.y) / max(1.0, u_white_level - u_black_level.y));
        float r_val  = max(0.0, (m10 - u_black_level.x) / max(1.0, u_white_level - u_black_level.x));
        float b_val  = max(0.0, (m01 - u_black_level.w) / max(1.0, u_white_level - u_black_level.w));
        float gb_val = max(0.0, (m11 - u_black_level.z) / max(1.0, u_white_level - u_black_level.z));
        R = r_val; G = (gr_val + gb_val) * 0.5; B = b_val;
    } else if (bayer_pattern == 2) {
        float gb_val = max(0.0, (m00 - u_black_level.z) / max(1.0, u_white_level - u_black_level.z));
        float b_val  = max(0.0, (m10 - u_black_level.w) / max(1.0, u_white_level - u_black_level.w));
        float r_val  = max(0.0, (m01 - u_black_level.x) / max(1.0, u_white_level - u_black_level.x));
        float gr_val = max(0.0, (m11 - u_black_level.y) / max(1.0, u_white_level - u_black_level.y));
        R = r_val; G = (gr_val + gb_val) * 0.5; B = b_val;
    } else if (bayer_pattern == 3) {
        float b_val  = max(0.0, (m00 - u_black_level.w) / max(1.0, u_white_level - u_black_level.w));
        float gb_val = max(0.0, (m10 - u_black_level.z) / max(1.0, u_white_level - u_black_level.z));
        float gr_val = max(0.0, (m01 - u_black_level.y) / max(1.0, u_white_level - u_black_level.y));
        float r_val  = max(0.0, (m11 - u_black_level.x) / max(1.0, u_white_level - u_black_level.x));
        R = r_val; G = (gr_val + gb_val) * 0.5; B = b_val;
    }
    return apply_wb_and_hl_clip(R, G, B, r_gain, g_gain, b_gain);
}

float getFilteredBayerPixel(int x, int y, float black_level) {
    float c = max(0.0, (getUnpackedPixel(x, y) - black_level) / max(1.0, u_white_level - black_level));
    float l = max(0.0, (getUnpackedPixel(x - 2, y) - black_level) / max(1.0, u_white_level - black_level));
    float r = max(0.0, (getUnpackedPixel(x + 2, y) - black_level) / max(1.0, u_white_level - black_level));
    float t = max(0.0, (getUnpackedPixel(x, y - 2) - black_level) / max(1.0, u_white_level - black_level));
    float b = max(0.0, (getUnpackedPixel(x, y + 2) - black_level) / max(1.0, u_white_level - black_level));
    float sig = 0.04 + 0.06 * c;
    float inv2 = 0.5 / (sig * sig);
    float wl = exp(-(l - c) * (l - c) * inv2);
    float wr = exp(-(r - c) * (r - c) * inv2);
    float wt = exp(-(t - c) * (t - c) * inv2);
    float wb = exp(-(b - c) * (b - c) * inv2);
    return (c + l * wl + r * wr + t * wt + b * wb) / (1.0 + wl + wr + wt + wb);
}

vec3 getStandardBayerColor(int px, int py) {
    int bx = (px / 2) * 2;
    int by = (py / 2) * 2;
    float R = 0.0, G = 0.0, B = 0.0;
    if (bayer_pattern == 0) {
        float r_val  = getFilteredBayerPixel(bx, by, u_black_level.x);
        float gr_val = getFilteredBayerPixel(bx + 1, by, u_black_level.y);
        float gb_val = getFilteredBayerPixel(bx, by + 1, u_black_level.z);
        float b_val  = getFilteredBayerPixel(bx + 1, by + 1, u_black_level.w);
        R = r_val; G = (gr_val + gb_val) * 0.5; B = b_val;
    } else if (bayer_pattern == 1) {
        float gr_val = getFilteredBayerPixel(bx, by, u_black_level.y);
        float r_val  = getFilteredBayerPixel(bx + 1, by, u_black_level.x);
        float b_val  = getFilteredBayerPixel(bx, by + 1, u_black_level.w);
        float gb_val = getFilteredBayerPixel(bx + 1, by + 1, u_black_level.z);
        R = r_val; G = (gr_val + gb_val) * 0.5; B = b_val;
    } else if (bayer_pattern == 2) {
        float gb_val = getFilteredBayerPixel(bx, by, u_black_level.z);
        float b_val  = getFilteredBayerPixel(bx + 1, by, u_black_level.w);
        float r_val  = getFilteredBayerPixel(bx, by + 1, u_black_level.x);
        float gr_val = getFilteredBayerPixel(bx + 1, by + 1, u_black_level.y);
        R = r_val; G = (gr_val + gb_val) * 0.5; B = b_val;
    } else if (bayer_pattern == 3) {
        float b_val  = getFilteredBayerPixel(bx, by, u_black_level.w);
        float gb_val = getFilteredBayerPixel(bx + 1, by, u_black_level.z);
        float gr_val = getFilteredBayerPixel(bx, by + 1, u_black_level.y);
        float r_val  = getFilteredBayerPixel(bx + 1, by + 1, u_black_level.x);
        R = r_val; G = (gr_val + gb_val) * 0.5; B = b_val;
    }
    return apply_wb_and_hl_clip(R, G, B, r_gain, g_gain, b_gain);
}

// rawdevelop_fs main: the front mirror (the same texture axis as the ISP preview) and the lens distortion correction.
vec2 vfUv(vec2 texPos) {
    float u = texPos.x;
    float v = mirror==1 ? 1.0-texPos.y : texPos.y;
    if (u_ldc_enabled == 1) {
        float nx = (u - u_ldc_cx) * 2.0;
        float ny = (v - u_ldc_cy) * 2.0 * u_ldc_inv_aspect;
        float r = sqrt(nx * nx + ny * ny);
        float r2 = r * r;
        float r4 = r2 * r2;

        float r_scale = 1.0;
        if (abs(u_ldc_p1) > 0.0001) {
            float s = u_ldc_p1;
            if (s > 0.0) {
                r_scale = (r > 0.0001 && r * s < 1.45) ? (tan(r * s) / (r * s)) : 1.0;
            } else {
                float abs_s = -s;
                r_scale = (r > 0.0001) ? (atan(r * abs_s) / (r * abs_s)) : 1.0;
            }
        }

        float zoom = max(0.1, 1.0 + u_ldc_k3);
        float radial = (1.0 + u_ldc_k1 * r2 + u_ldc_k2 * r4) * (r_scale / zoom);

        float src_nx = nx * radial;
        float src_ny = ny * radial;
        u = clamp(src_nx * 0.5 + u_ldc_cx, 0.0, 1.0);
        v = clamp((src_ny * 0.5) * u_ldc_aspect + u_ldc_cy, 0.0, 1.0);
    }
    return vec2(u, v);
}

// rawdevelop_fs main: the block 1 / 2 / 4 as a shift (the crop is never negative: the same as the division)
int vfShift() {
    return cfa_block_size == 4 ? 2 : cfa_block_size == 2 ? 1 : 0;
}

vec3 vfShadingAt(vec2 uv) {
    if (u_lsc_intensity > 0.001) {
        vec2 sensor_uv = vec2(
            float(crop_left) + uv.x * float(crop_width),
            float(crop_top) + uv.y * float(crop_height)
        ) / vec2(float(width), float(height));
        vec3 lsc_gain = texture(u_lsc_map, sensor_uv).rgb;
        if (u_lsc_intensity < 0.999) {
            lsc_gain = mix(vec3(1.0), lsc_gain, u_lsc_intensity);
        }
        return lsc_gain;
    }
    return vec3(1.0);
}

vec3 vfShading(vec2 texPos) {
    return vfShadingAt(vfUv(texPos));
}

// the 2 x 2 sites with the top-left one at (bx, by) (even) in one gather - .w (bx, by), .z (bx + 1, by), .x (bx, by + 1),
// .y (bx + 1, by + 1) - the base kept on the frame as getUnpackedPixel keeps every site (the same CFA phase; even sizes)
vec4 vfQuad(int bx, int by) {
    bx = clamp(bx, 0, (width - 2) & ~1);
    by = clamp(by, 0, (height - 2) & ~1);
    return vec4(textureGather(sTexture16, vec2(float(bx + 1), float(by + 1)) / vec2(float(width), float(height))));
}

// getFilteredBayerPixel's arithmetic on the five values it fetches
float vfFiltered(float cv, float lv, float rv, float tv, float bv, float black_level) {
    float c = max(0.0, (cv - black_level) / max(1.0, u_white_level - black_level));
    float l = max(0.0, (lv - black_level) / max(1.0, u_white_level - black_level));
    float r = max(0.0, (rv - black_level) / max(1.0, u_white_level - black_level));
    float t = max(0.0, (tv - black_level) / max(1.0, u_white_level - black_level));
    float b = max(0.0, (bv - black_level) / max(1.0, u_white_level - black_level));
    float sig = 0.04 + 0.06 * c;
    float inv2 = 0.5 / (sig * sig);
    float wl = exp(-(l - c) * (l - c) * inv2);
    float wr = exp(-(r - c) * (r - c) * inv2);
    float wt = exp(-(t - c) * (t - c) * inv2);
    float wb = exp(-(b - c) * (b - c) * inv2);
    return (c + l * wl + r * wr + t * wt + b * wb) / (1.0 + wl + wr + wt + wb);
}

// getStandardBayerColor of the quad at (bx, by) from five gathers (20 fetches): the quad and its neighbours 2 px away hold every
// tap of the range filter
vec3 vfStandardQuad(int bx, int by) {
    vec4 q = vfQuad(bx, by), ql = vfQuad(bx - 2, by), qr = vfQuad(bx + 2, by), qt = vfQuad(bx, by - 2), qb = vfQuad(bx, by + 2);
    // the black level of each site (u_black_level: R, Gr, Gb, B)
    vec4 site = bayer_pattern == 0 ? u_black_level
              : bayer_pattern == 1 ? vec4(u_black_level.y, u_black_level.x, u_black_level.w, u_black_level.z)
              : bayer_pattern == 2 ? vec4(u_black_level.z, u_black_level.w, u_black_level.x, u_black_level.y)
              : vec4(u_black_level.w, u_black_level.z, u_black_level.y, u_black_level.x);
    float s00 = vfFiltered(q.w, ql.w, qr.w, qt.w, qb.w, site.x);
    float s10 = vfFiltered(q.z, ql.z, qr.z, qt.z, qb.z, site.y);
    float s01 = vfFiltered(q.x, ql.x, qr.x, qt.x, qb.x, site.z);
    float s11 = vfFiltered(q.y, ql.y, qr.y, qt.y, qb.y, site.w);
    float R = 0.0, G = 0.0, B = 0.0;
    if (bayer_pattern == 0) {
        R = s00; G = (s10 + s01) * 0.5; B = s11;
    } else if (bayer_pattern == 1) {
        R = s10; G = (s00 + s11) * 0.5; B = s01;
    } else if (bayer_pattern == 2) {
        R = s01; G = (s11 + s00) * 0.5; B = s10;
    } else if (bayer_pattern == 3) {
        R = s11; G = (s01 + s10) * 0.5; B = s00;
    }
    return apply_wb_and_hl_clip(R, G, B, r_gain, g_gain, b_gain);
}

// getFastColor of a block-1 frame (cell means) at the quad (bx, by) from one gather (4 fetches)
vec3 vfFastQuad(int bx, int by) {
    vec4 q = vfQuad(bx, by);
    float m00 = q.w, m10 = q.z, m01 = q.x, m11 = q.y;
    float R = 0.0, G = 0.0, B = 0.0;
    if (bayer_pattern == 0) {
        float r_val  = max(0.0, (m00 - u_black_level.x) / max(1.0, u_white_level - u_black_level.x));
        float gr_val = max(0.0, (m10 - u_black_level.y) / max(1.0, u_white_level - u_black_level.y));
        float gb_val = max(0.0, (m01 - u_black_level.z) / max(1.0, u_white_level - u_black_level.z));
        float b_val  = max(0.0, (m11 - u_black_level.w) / max(1.0, u_white_level - u_black_level.w));
        R = r_val; G = (gr_val + gb_val) * 0.5; B = b_val;
    } else if (bayer_pattern == 1) {
        float gr_val = max(0.0, (m00 - u_black_level.y) / max(1.0, u_white_level - u_black_level.y));
        float r_val  = max(0.0, (m10 - u_black_level.x) / max(1.0, u_white_level - u_black_level.x));
        float b_val  = max(0.0, (m01 - u_black_level.w) / max(1.0, u_white_level - u_black_level.w));
        float gb_val = max(0.0, (m11 - u_black_level.z) / max(1.0, u_white_level - u_black_level.z));
        R = r_val; G = (gr_val + gb_val) * 0.5; B = b_val;
    } else if (bayer_pattern == 2) {
        float gb_val = max(0.0, (m00 - u_black_level.z) / max(1.0, u_white_level - u_black_level.z));
        float b_val  = max(0.0, (m10 - u_black_level.w) / max(1.0, u_white_level - u_black_level.w));
        float r_val  = max(0.0, (m01 - u_black_level.x) / max(1.0, u_white_level - u_black_level.x));
        float gr_val = max(0.0, (m11 - u_black_level.y) / max(1.0, u_white_level - u_black_level.y));
        R = r_val; G = (gr_val + gb_val) * 0.5; B = b_val;
    } else if (bayer_pattern == 3) {
        float b_val  = max(0.0, (m00 - u_black_level.w) / max(1.0, u_white_level - u_black_level.w));
        float gb_val = max(0.0, (m10 - u_black_level.z) / max(1.0, u_white_level - u_black_level.z));
        float gr_val = max(0.0, (m01 - u_black_level.y) / max(1.0, u_white_level - u_black_level.y));
        float r_val  = max(0.0, (m11 - u_black_level.x) / max(1.0, u_white_level - u_black_level.x));
        R = r_val; G = (gr_val + gb_val) * 0.5; B = b_val;
    }
    return apply_wb_and_hl_clip(R, G, B, r_gain, g_gain, b_gain);
}

// rawdevelop_fs main's colour at the pixel before the lens shading (cC): the range-filtered quad of a plain frame, one colour
// per 2 x 2 macro pixels of a mosaic or of cell means - from gathers when the frame's sizes are even (the camera's RAW always),
// else from rawdevelop_fs's own fetches. VF_TIE (rawdevelop_fs main's RAW_TIE, the same value): a pixel centre exactly on a
// quad's border (every 15th row and column at 1440 x 1920 of 3072 x 4096) takes the quad after it whatever the last bits of its
// position - the rasteriser's texCoord and a neighbour's position from the derivatives (why/combine RAW_SOURCE 1) differ by
// ~1e-4 px, and picked quads 2 px apart there (off-colour pixels beside sharp coloured edges); 1/300 px of shift otherwise
const float VF_TIE = 0.0031;
vec3 vfColour(vec2 texPos) {
    vec2 uv = vfUv(texPos);
    bool even = ((width | height) & 1) == 0;
    if (cfa_mode == 1) {
        int px = int(uv.x * float(crop_width) + VF_TIE) + crop_left;
        int py = int(uv.y * float(crop_height) + VF_TIE) + crop_top;
        return even ? vfStandardQuad((px / 2) * 2, (py / 2) * 2) : getStandardBayerColor(px, py);
    }
    int shift = vfShift();
    int mx = int(uv.x * float(crop_width >> shift) + VF_TIE) + (crop_left >> shift);
    int my = int(uv.y * float(crop_height >> shift) + VF_TIE) + (crop_top >> shift);
    return even && max(cfa_block_size, 1) == 1 ? vfFastQuad(mx & ~1, my & ~1) : getFastColor(mx, my);
}

// P370 (B2): the linear frame's value at the pixel before the clamp at 0 (a colour the matrix put outside sRGB keeps its
// negative channel: why/combine signedHueU)
vec3 vfLinearSigned(vec2 texPos) {
    return color_transform * (vfColour(texPos) * vfShading(texPos));
}

// the linear frame's value at the pixel (rawdevelop_fs u_linear_out 1)
vec3 vfLinear(vec2 texPos) {
    return max(vfLinearSigned(texPos), vec3(0.0));
}
