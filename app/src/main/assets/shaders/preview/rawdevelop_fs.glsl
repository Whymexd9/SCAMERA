#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp usampler2D;
precision highp sampler3D;
in vec2 texCoord;
uniform int mirror;
uniform int cfa_block_size;
uniform sampler2D sTexture;
uniform usampler2D sTexture16;
uniform int width;
uniform int height;
uniform int stride;
uniform float r_gain;
uniform float g_gain;
uniform float b_gain;
uniform int crop_left;
uniform int crop_top;
uniform int crop_width;
uniform int crop_height;
uniform int bayer_pattern;
uniform int cfa_mode;
uniform float u_auto_exposure;
uniform int u_ef_enabled;
uniform vec4 u_exp_mults;
uniform vec4 u_layer_weights;
uniform float u_weight_center;
uniform float u_blend_smoothness;
uniform float u_contrast_boost;
uniform float u_macro_contrast;
uniform int u_tonemap_op;
uniform float u_gamma;
uniform float u_film_toe;
uniform float u_vibrance;
uniform float u_vibrance_sky;
uniform float u_vibrance_green;
uniform float u_shadow_vibrance;
uniform float u_highlight_vibrance;
uniform float u_highlight_rolloff;
uniform float u_ef_chroma_denoise;
uniform float u_target_white_point;
uniform float u_aces_pre_gain;
uniform float u_aces_toe;
uniform float u_aces_a_coeff;
uniform float u_aces_d_coeff;
uniform float u_reinhard_pre_gain;
uniform float u_reinhard_w;
uniform float u_reinhard_post_gain;
uniform float u_lottes_pre_gain;
uniform float u_lottes_a;
uniform float u_lottes_d;
uniform float u_uchi_pre_gain;
uniform float u_uchi_post_gain;
uniform float u_uchi_p;
uniform float u_uchi_a;
uniform float u_uchi_m;
uniform float u_uchi_l;
uniform float u_uchi_c;
uniform float u_uchi_b;
uniform float u_sig_contrast;
uniform float u_sig_mid_gray;
uniform mat3 color_transform;
uniform int peaking_enabled;
uniform vec3 peaking_color;
uniform sampler2D u_lsc_map;
uniform float u_lsc_intensity;
uniform vec4 u_black_level;
uniform float u_white_level;
uniform int u_raw_format;
uniform highp sampler3D u_lut_tex;
uniform int u_lut_enabled;
uniform float u_lut_strength;
uniform float u_lut_size;
uniform int u_ldc_enabled;
uniform float u_ldc_aspect;
uniform float u_ldc_inv_aspect;
uniform float u_ldc_k1;
uniform float u_ldc_k2;
uniform float u_ldc_k3;
uniform float u_ldc_p1;
uniform float u_ldc_cx;
uniform float u_ldc_cy;
out vec4 FragColor;

// Adapted from the user-supplied libvf_demosaic.so (SHA-256 78a014...4285).
// Camera2 supplies RAW_SENSOR here; packed inputs are not guessed from bit depth.
float getUnpackedPixel(int x, int y) {
    // Preserve CFA phase at borders: +/-2 must stay on the same colour plane.
    int block = max(cfa_block_size,1);
    int period = block*2;
    int px = ((x % period) + period) % period;
    int py = ((y % period) + period) % period;
    x = clamp(x, px, width-1-((width-1-px)%period));
    y = clamp(y, py, height-1-((height-1-py)%period));
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

vec3 srgb_to_ap1(vec3 c) {
    return vec3(
        0.613097 * c.r + 0.339523 * c.g + 0.047379 * c.b,
        0.070198 * c.r + 0.916354 * c.g + 0.013448 * c.b,
        0.020616 * c.r + 0.109592 * c.g + 0.869791 * c.b
    );
}
vec3 ap1_to_srgb(vec3 c) {
    return vec3(
         1.705051 * c.r - 0.621869 * c.g - 0.083182 * c.b,
        -0.130256 * c.r + 1.140736 * c.g - 0.010480 * c.b,
        -0.024007 * c.r - 0.128985 * c.g + 1.152992 * c.b
    );
}
float acescg_curve(float v, float toe, float pre_gain, float a_coeff, float d_coeff) {
    float x = max(v, 0.0) * pre_gain;
    float b = max(toe * 0.75, 0.005);
    float e = max(0.14 - (toe - 0.04) * 0.8, 0.03);
    return (x * (a_coeff * x + b)) / (x * (2.43 * x + d_coeff) + e);
}
vec3 apply_aces(vec3 ap1, float toe, float pre_gain, float a_coeff, float d_coeff) {
    return vec3(
        acescg_curve(ap1.r, toe, pre_gain, a_coeff, d_coeff),
        acescg_curve(ap1.g, toe, pre_gain, a_coeff, d_coeff),
        acescg_curve(ap1.b, toe, pre_gain, a_coeff, d_coeff)
    );
}
vec3 apply_reinhard(vec3 ap1, float pre_gain, float W, float post_gain) {
    vec3 c = ap1 * pre_gain;
    return ((c * (1.0 + c / (W * W))) / (1.0 + c)) * post_gain;
}
vec3 apply_lottes(vec3 ap1, float pre_gain, float a, float d) {
    vec3 x = ap1 * pre_gain;
    const float b_param = 1.042;
    const float c_param = 0.043;
    vec3 x_a = pow(max(x, vec3(0.00001)), vec3(a));
    vec3 x_ad = pow(max(x, vec3(0.00001)), vec3(a * d));
    return x_a / (x_ad * b_param + c_param);
}
float uchimura_curve(float x, float P, float a, float m, float l, float c, float b_offset) {
    float l0 = ((P - m) * l) / a;
    float S1 = m + a * l0;
    float CP = -((a * P) / (P - S1)) / P;
    float S0 = m + l0;
    float w0 = 1.0 - smoothstep(0.0, m, x);
    float w2 = step(m + l0, x);
    float w1 = 1.0 - w0 - w2;
    float T = m * pow(max(x / m, 0.00001), c) + b_offset;
    float S = P - (P - S1) * exp(CP * (x - S0));
    float L = m + a * (x - m);
    return T * w0 + L * w1 + S * w2;
}
vec3 apply_uchimura(vec3 ap1, float pre_gain, float post_gain, float P, float a, float m, float l, float c, float b_offset) {
    vec3 color = ap1 * pre_gain;
    return vec3(
        uchimura_curve(color.r, P, a, m, l, c, b_offset),
        uchimura_curve(color.g, P, a, m, l, c, b_offset),
        uchimura_curve(color.b, P, a, m, l, c, b_offset)
    ) * post_gain;
}
vec3 apply_sigmoid(vec3 ap1, float contrast, float mid_gray) {
    vec3 x_c = pow(max(ap1, vec3(0.00001)), vec3(contrast));
    float m_c = pow(mid_gray, contrast);
    return x_c / (x_c + m_c);
}
float compute_ef_multiplier(float lin_Y, vec4 exp_mults, vec4 layer_weights, float aces_toe, float weight_center, float blend_smoothness) {
    float mult = 1.0;
    float sh_mult = exp_mults.w;
    float max_sh_y = weight_center * 0.5;
    if (sh_mult > 1.001 && lin_Y < max_sh_y) {
        float k_anchor = max(0.0005, aces_toe * 0.2);
        float start_y = blend_smoothness * 0.0133;
        float sh_mask = 1.0 - smoothstep(start_y, max_sh_y, lin_Y);
        float sh_shape = lin_Y / (lin_Y + k_anchor);
        float sh_scale = layer_weights.w * 1.7;
        mult += (sh_mult - 1.0) * sh_shape * sh_mask * sh_scale;
    }
    float hl_mult = exp_mults.y;
    float y_th = 0.6 + weight_center * 0.5;
    if (hl_mult < 0.999 && lin_Y > y_th) {
        float dy = lin_Y - y_th;
        float k = (1.0 / max(hl_mult, 0.01) - 1.0) * layer_weights.y * 0.625;
        float c_hl = y_th + dy / (1.0 + k * dy);
        mult *= (c_hl / lin_Y);
    }
    return mult;
}
vec3 processPipeline(vec3 color, float corner_gain) {
    color = color_transform * color;
    color = color * u_auto_exposure;

    float Y_raw = dot(color, vec3(0.2126, 0.7152, 0.0722));
    float base_multiplier = 1.0;
    if (u_ef_enabled == 1) {
        base_multiplier = compute_ef_multiplier(Y_raw, u_exp_mults, u_layer_weights, u_aces_toe, u_weight_center, u_blend_smoothness);
        color *= base_multiplier;
    }

    float L_in = max(0.0, 0.4122214708 * color.r + 0.5363325363 * color.g + 0.0514459929 * color.b);
    float M_in = max(0.0, 0.2119034982 * color.r + 0.6806995451 * color.g + 0.1073969566 * color.b);
    float S_in = max(0.0, 0.0883024619 * color.r + 0.2817188376 * color.g + 0.6299787005 * color.b);
    float l_ = pow(L_in, 1.0 / 3.0);
    float m_ = pow(M_in, 1.0 / 3.0);
    float s_ = pow(S_in, 1.0 / 3.0);
    float oklab_L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_;
    float oklab_a = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_;
    float oklab_b = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_;

    if (abs(u_macro_contrast - 1.0) > 0.001) {
        float pivot_L = 0.40;
        oklab_L = max(0.0001, pivot_L + (oklab_L - pivot_L) * u_macro_contrast);
    }

    float Y = dot(color, vec3(0.2126, 0.7152, 0.0722));
    float sat = max(sqrt(oklab_a * oklab_a + oklab_b * oklab_b), 0.0001);
    float sat_prot = max(1.0 - sat * 2.0, 0.0);
    float shadow_mask = (1.0 - smoothstep(0.05, 0.35, Y)) * smoothstep(0.008, 0.045, Y);
    float pull_boost = log(max(base_multiplier, 1.0)) * 0.4342945;
    float shadow_boost = max(u_shadow_vibrance, 0.0) * (0.30 + 0.25 * pull_boost) * shadow_mask;
    float hl_mask = smoothstep(0.35, 0.85, Y_raw);
    float hl_pull = log(max(1.0 / max(base_multiplier, 0.05), 1.0)) * 0.4342945;
    float sat_boost_limit = clamp(1.0 - (sat - 0.08) / 0.12, 0.0, 1.0);
    float target_sat = max(u_highlight_vibrance, 0.0) * (1.0 + 0.85 * hl_pull * sat_boost_limit);
    float sat_diff = target_sat - 1.0;
    if (sat_diff > 0.0) {
        sat_diff *= sat_prot;
    }
    float hl_sat_mult = max(0.0, 1.0 + sat_diff * hl_mask);
    float sat_mult = (1.0 + (shadow_boost + u_vibrance) * sat_prot) * hl_sat_mult;
    if (abs(u_vibrance_sky) > 0.001 && oklab_b < 0.0) {
        float sky_mask = clamp(-oklab_b / sat, 0.0, 1.0);
        sat_mult += u_vibrance_sky * sky_mask * sat_prot;
    }
    if (abs(u_vibrance_green) > 0.001 && oklab_a < 0.0) {
        float green_mask = clamp(-oklab_a / sat, 0.0, 1.0);
        sat_mult += u_vibrance_green * green_mask * sat_prot;
    }
    oklab_a *= sat_mult;
    oklab_b *= sat_mult;

    // Viewfinder Balanced Shadow Chroma Suppression:
    // Retains natural colors and warmth in shadows (never B&W), while suppressing neon confetti
    float total_boost = u_auto_exposure * max(base_multiplier, 1.0) * corner_gain;

    // Shadow threshold: covers deep shadows and transition zone (L < 0.22..0.38)
    float clean_thresh = clamp(0.22 + 0.03 * total_boost, 0.22, 0.38);
    float dark_t = clamp(oklab_L / clean_thresh, 0.0, 1.0);
    float shadow_snr = dark_t * dark_t * (3.0 - 2.0 * dark_t);

    // Balanced floor: keeps ~32% color in the absolute deepest shadows, fading smoothly to 100% in midtones
    float min_chroma = 0.32;
    float vf_chroma_clean = mix(min_chroma, 1.0, shadow_snr);
    oklab_a *= vf_chroma_clean;
    oklab_b *= vf_chroma_clean;

    // Outlier Clamp: clips neon confetti (> 0.035 in shadows, > 0.26 in midtones)
    float max_safe_sat = mix(0.035, 0.26, smoothstep(0.02, 0.45, oklab_L));
    float cur_sat = sqrt(oklab_a * oklab_a + oklab_b * oklab_b);
    if (cur_sat > max_safe_sat) {
        float s_scale = max_safe_sat / cur_sat;
        oklab_a *= s_scale;
        oklab_b *= s_scale;
    }

    // Optional additional desat if user explicitly enabled pref_sharp_ef_chroma_denoise_key
    if (u_ef_chroma_denoise > 0.001 && base_multiplier > 1.5) {
        float lift_penalty = clamp((base_multiplier - 1.5) * 0.25, 0.0, 1.0);
        float extra_desat = max(1.0 - lift_penalty * u_ef_chroma_denoise * 0.5, 0.4);
        oklab_a *= extra_desat;
        oklab_b *= extra_desat;
    }

    l_ = oklab_L + 0.3963377774 * oklab_a + 0.2158037573 * oklab_b;
    m_ = oklab_L - 0.1055613458 * oklab_a - 0.0638541728 * oklab_b;
    s_ = oklab_L - 0.0894841775 * oklab_a - 1.2914855480 * oklab_b;
    l_ = max(0.0, l_);
    m_ = max(0.0, m_);
    s_ = max(0.0, s_);
    l_ = l_ * l_ * l_;
    m_ = m_ * m_ * m_;
    s_ = s_ * s_ * s_;
    vec3 lin_rgb = vec3(
        +4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_,
        -1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_,
        -0.0041960863 * l_ - 0.7034186147 * m_ + 1.7076147010 * s_
    );

    // Smooth Gamut Compression for negative linear RGB (prevents harsh step artifacts)
    float min_c = min(lin_rgb.r, min(lin_rgb.g, lin_rgb.b));
    float lin_luma = dot(lin_rgb, vec3(0.2126, 0.7152, 0.0722));
    if (min_c < 0.0 && lin_luma > 0.0001) {
        float T = lin_luma / (lin_luma - min_c);
        lin_rgb = mix(vec3(lin_luma), lin_rgb, T);
    }
    color = max(lin_rgb, vec3(0.0));

    float norm = max(color.r, max(color.g, color.b));
    norm = max(norm, 0.000001);
    float mapped_norm;
    if (u_tonemap_op == 1) {
        float c = max(norm, 0.0) * (u_reinhard_pre_gain + u_contrast_boost);
        mapped_norm = ((c * (1.0 + c / (u_reinhard_w * u_reinhard_w))) / (1.0 + c)) * u_reinhard_post_gain;
    } else if (u_tonemap_op == 2) {
        float x = max(norm, 0.0) * u_lottes_pre_gain;
        float a_val = u_lottes_a + u_contrast_boost;
        float x_a = pow(max(x, 0.00001), a_val);
        float x_ad = pow(max(x, 0.00001), a_val * u_lottes_d);
        mapped_norm = x_a / (x_ad * 1.042 + 0.043);
    } else if (u_tonemap_op == 3) {
        float x = max(norm, 0.0) * u_uchi_pre_gain;
        mapped_norm = uchimura_curve(x, u_uchi_p, u_uchi_a + u_contrast_boost * 0.75, u_uchi_m, u_uchi_l, u_uchi_c, u_uchi_b) * u_uchi_post_gain;
    } else if (u_tonemap_op == 4) {
        float x_c = pow(max(norm, 0.00001), u_sig_contrast + u_contrast_boost);
        float m_c = pow(u_sig_mid_gray, u_sig_contrast + u_contrast_boost);
        mapped_norm = x_c / (x_c + m_c);
    } else {
        float pre_gain = u_aces_pre_gain + u_contrast_boost;
        float toe = max(0.005, u_aces_toe - u_contrast_boost * 0.1);
        mapped_norm = acescg_curve(norm, toe, pre_gain, u_aces_a_coeff, u_aces_d_coeff);
    }
    float base_tm_gain = mapped_norm / norm;
    vec3 graded = color * base_tm_gain;
    color = clamp(graded, 0.0, 1.0);

    float inv_gamma = 1.0 / max(0.1, u_gamma);
    color = pow(clamp(color, vec3(0.0), vec3(1.0)), vec3(inv_gamma));

    if (u_film_toe > 0.0) {
        float luma_sdr = max(dot(color, vec3(0.2126, 0.7152, 0.0722)), 0.0001);
        float toe_limit = 0.15;
        if (luma_sdr < toe_limit) {
            float norm_luma = luma_sdr / toe_limit;
            float crushed = pow(max(norm_luma, 0.000001), 1.0 + u_film_toe);
            float toe_ratio = (crushed * toe_limit) / luma_sdr;
            color *= toe_ratio;
        }
    }
    return clamp(color, 0.0, 1.0);
}

void main() {
    float u = mirror==1 ? 1.0-texCoord.x : texCoord.x;
    float v = texCoord.y;
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
    vec3 cC;
    if (cfa_mode == 1) {
        int px = int(u * float(crop_width)) + crop_left;
        int py = int(v * float(crop_height)) + crop_top;
        cC = getStandardBayerColor(px, py);
    } else {
        int mx = int(u * float(crop_width / cfa_block_size)) + (crop_left / cfa_block_size);
        int my = int(v * float(crop_height / cfa_block_size)) + (crop_top / cfa_block_size);
        cC = getFastColor(mx, my);
    }
    float corner_gain = 1.0;
    if (u_lsc_intensity > 0.001) {
        vec2 sensor_uv = vec2(
            float(crop_left) + u * float(crop_width),
            float(crop_top) + v * float(crop_height)
        ) / vec2(float(width), float(height));
        vec3 lsc_gain = texture(u_lsc_map, sensor_uv).rgb;
        if (u_lsc_intensity < 0.999) {
            lsc_gain = mix(vec3(1.0), lsc_gain, u_lsc_intensity);
        }
        corner_gain = max(lsc_gain.r, max(lsc_gain.g, lsc_gain.b));
        cC *= lsc_gain;
    } else {
        vec2 ndc = (vec2(u, v) - 0.5) * 2.0;
        corner_gain = 1.0 + 1.5 * dot(ndc, ndc);
    }
    vec3 color = processPipeline(cC, corner_gain);
    if (u_lut_enabled == 1) {
        vec3 lut_coord = (clamp(color, 0.0, 1.0) * (u_lut_size - 1.0) + 0.5) / u_lut_size;
        vec3 lut_color = texture(u_lut_tex, lut_coord).rgb;
        color = mix(color, lut_color, u_lut_strength);
    }
    if (peaking_enabled == 1) {
        float m_L, m_R, m_T, m_B;
        if (cfa_mode == 1) {
            int px = int(u * float(crop_width)) + crop_left;
            int py = int(v * float(crop_height)) + crop_top;
            m_L = getStandardBayerColor(px - 2, py).g;
            m_R = getStandardBayerColor(px + 2, py).g;
            m_T = getStandardBayerColor(px, py - 2).g;
            m_B = getStandardBayerColor(px, py + 2).g;
        } else {
            int mx = int(u * float(crop_width / cfa_block_size)) + (crop_left / cfa_block_size);
            int my = int(v * float(crop_height / cfa_block_size)) + (crop_top / cfa_block_size);
            m_L = getFastColor(mx - 2, my).g;
            m_R = getFastColor(mx + 2, my).g;
            m_T = getFastColor(mx, my - 2).g;
            m_B = getFastColor(mx, my + 2).g;
        }

        float lL = pow(clamp(m_L * u_auto_exposure, 0.0, 1.0), 1.0/2.2);
        float lR = pow(clamp(m_R * u_auto_exposure, 0.0, 1.0), 1.0/2.2);
        float lT = pow(clamp(m_T * u_auto_exposure, 0.0, 1.0), 1.0/2.2);
        float lB = pow(clamp(m_B * u_auto_exposure, 0.0, 1.0), 1.0/2.2);

        float gx = lR - lL;
        float gy = lB - lT;
        float edge = sqrt(gx*gx + gy*gy);

        float noise_floor = clamp(0.02 + (u_auto_exposure - 1.0) * 0.02, 0.02, 0.15);
        float intensity = smoothstep(noise_floor, noise_floor + 0.08, edge);
        if (intensity > 0.0) {
            color = mix(color, peaking_color, clamp(intensity * 1.5, 0.0, 1.0));
        }
    }
    FragColor = vec4(color, 1.0);
}
