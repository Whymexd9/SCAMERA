#!/usr/bin/env python3
"""P134: the photo's «Цвет» look and film LUT in the RAW viewfinder (preview/rawdevelop_fs.glsl) on the GPU (moderngl).

A synthetic RGGB RAW of colour patches, greys and skin tones goes through the viewfinder shader:
- with the look off (u_look_on 0) and no LUT it renders the shader before P134 (the reference is this shader with the P134
  code cut out, which is the file before P134 token for token) - bit for bit on a desktop GPU, < 2e-6 allowed;
- the look on with every stage neutral renders the same;
- neutral monochrome is grey; a shadow grading offset tints dark greys, not bright ones; skin protection holds back the
  mixer's saturation on skin; an identity LUT at full strength leaves the output alone (within the 8-bit sub-texel weights)
  and a LUT's domain is honoured.
"""
import re
import sys
from pathlib import Path

import numpy as np

SH = Path(__file__).resolve().parents[1] / 'app/src/main/assets/shaders/preview'
FAILURES = []
TOL = 1e-3


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def without_p134(src):
    """The shader with the P134 stages cut out (the viewfinder before P134)."""
    out = re.sub(r'// P134: the photo.*?uniform vec3 u_lut_scale;\r?\n', '', src, flags=re.S)
    out = re.sub(r'const vec3 LOOK_LUMA.*?return skinW;\r?\n}\r?\n', '', out, flags=re.S)
    out = re.sub(r'    // P134: monochrome.*?u_look_mono\) / dot\(LOOK_LUMA, u_look_mono\), 0.0\)\);\r?\n', '', out, flags=re.S)
    out = re.sub(r'    float look_skin = 0.0;\r?\n    if \(u_look_on == 1\) \{.*?oklab_b = look_lab.z;\r?\n    }\r?\n', '', out, flags=re.S)
    out = re.sub(r'    // P134: the grading.*?color = clamp\(lookFromOklab\(post\), 0.0, 1.0\);\r?\n    }\r?\n', '', out, flags=re.S)
    out = out.replace('(clamp((clamp(color, 0.0, 1.0) - u_lut_min) * u_lut_scale, 0.0, 1.0) * (u_lut_size - 1.0) + 0.5) / u_lut_size',
                      '(clamp(color, 0.0, 1.0) * (u_lut_size - 1.0) + 0.5) / u_lut_size')
    return out


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: viewfinder look check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    src = (SH / 'rawdevelop_fs.glsl').read_text(encoding='utf-8')
    old = without_p134(src)
    check('u_look' not in old and 'LOOK_' not in old, 'the P134 stages were not cut out for the reference')
    vs = ('#version 300 es\nout vec2 texCoord;void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));'
          'texCoord=p;gl_Position=vec4(p*2.-1.,0,1);}')
    prog_new = ctx.program(vertex_shader=vs, fragment_shader=src)
    prog_old = ctx.program(vertex_shader=vs, fragment_shader=old)

    # patches: 8 x 8 cells of 2 x 2 RGGB macro pixels each (white 1023, black 64)
    rng = np.random.default_rng(134)
    n = 64
    rgb = np.concatenate([rng.uniform(0.02, 0.6, (40, 3)),
                          np.repeat(np.geomspace(0.004, 0.7, 12)[:, None], 3, 1),
                          np.array([[0.56, 0.36, 0.26], [0.45, 0.28, 0.19], [0.70, 0.50, 0.40], [0.38, 0.22, 0.15]] * 3)])
    rgb = rgb[:n]
    cells = 8
    raw = np.zeros((cells * 2, cells * 2), np.uint16)
    for i, c in enumerate(rgb):
        y, x = divmod(i, cells)
        v = 64 + np.clip(c, 0, 1) * (1023 - 64)
        raw[y * 2, x * 2], raw[y * 2, x * 2 + 1], raw[y * 2 + 1, x * 2], raw[y * 2 + 1, x * 2 + 1] = v[0], v[1], v[1], v[2]
    t_raw = ctx.texture((raw.shape[1], raw.shape[0]), 1, raw.tobytes(), dtype='u2')
    t_raw.filter = (moderngl.NEAREST, moderngl.NEAREST)
    t_lsc = ctx.texture((1, 1), 3, np.ones(3, np.float32).tobytes(), dtype='f4')
    ident = 17
    b, g, r = np.meshgrid(np.arange(ident), np.arange(ident), np.arange(ident), indexing='ij')
    lut_id = (np.stack([r, g, b], -1) / (ident - 1)).astype(np.float32)

    def lut_tex(lut):
        t = ctx.texture3d((lut.shape[0],) * 3, 3, np.ascontiguousarray(lut, np.float32).tobytes(), dtype='f4')
        t.filter = (moderngl.LINEAR, moderngl.LINEAR)
        t.repeat_x = t.repeat_y = t.repeat_z = False
        return t
    t_lut = lut_tex(lut_id)

    def render(prog, look=None, lut=None, lut_range=(0.0, 1.0)):
        out = ctx.texture((cells, cells), 4, dtype='f4')
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, cells, cells)
        for unit, (name, t) in enumerate([('sTexture16', t_raw), ('u_lsc_map', t_lsc), ('u_lut_tex', lut or t_lut)], start=2):
            if name in prog:
                t.use(unit)
                prog[name].value = unit
        vals = {'width': raw.shape[1], 'height': raw.shape[0], 'bayer_pattern': 0, 'cfa_mode': 0, 'cfa_block_size': 1,
                'crop_left': 0, 'crop_top': 0, 'crop_width': raw.shape[1], 'crop_height': raw.shape[0],
                'u_black_level': (64.0, 64.0, 64.0, 64.0), 'u_white_level': 1023.0, 'r_gain': 1.0, 'g_gain': 1.0, 'b_gain': 1.0,
                'color_transform': tuple(np.eye(3).flatten()), 'u_lsc_intensity': 0.0, 'u_auto_exposure': 1.0,
                'u_contrast_boost': 0.0, 'u_exp_mults': (1.0, 1.0, 1.0, 2.0), 'mirror': 0, 'peaking_enabled': 0,
                'u_ef_enabled': 1, 'u_tonemap_op': 0, 'u_ldc_enabled': 0, 'u_weight_center': 0.5,
                'u_blend_smoothness': 0.15, 'u_macro_contrast': 1.1, 'u_gamma': 2.2, 'u_film_toe': 0.05,
                'u_shadow_vibrance': 1.1, 'u_highlight_vibrance': 1.0, 'u_aces_toe': 0.01, 'u_aces_pre_gain': 1.35,
                'u_aces_a_coeff': 2.7, 'u_aces_d_coeff': 0.59, 'u_layer_weights': (0.7, 0.8, 1.0, 0.5), 'u_lut_enabled': 0,
                'u_look_on': 0}
        if lut is not None:
            vals.update({'u_lut_enabled': 1, 'u_lut_strength': 1.0, 'u_lut_size': float(ident),
                         'u_lut_min': (lut_range[0],) * 3, 'u_lut_scale': (1 / (lut_range[1] - lut_range[0]),) * 3})
        if look is not None:
            vals.update({'u_look_on': 1, 'u_look_mono': (0.0, 0.0, 0.0), 'u_look_skin': (0.0, 0.0, 0.0), 'u_look_vib': (0.0, 0.0),
                         'u_look_mix_on': 0, 'u_look_grade_a': (0.0, 0.0, 0.0, 0.0), 'u_look_grade_b': (0.0, 0.0, 0.0, 0.5)})
            vals.update(look)
        for k, v in vals.items():
            if k in prog:
                prog[k].value = v
        if look is not None and 'u_look_mix' in prog:
            prog['u_look_mix'].value = tuple(look.get('mix', [0.0] * 24))
        ctx.vertex_array(prog, []).render(vertices=3)
        res = np.frombuffer(out.read(), np.float32).reshape(cells, cells, 4)[:, :, :3].reshape(-1, 3)
        fb.release()
        out.release()
        return res

    base_old = render(prog_old)
    base = render(prog_new)
    check(np.all(np.isfinite(base)) and base.max() > 0.2, 'the viewfinder renders nothing')
    # bit for bit on a desktop GPU; a software rasteriser may contract a multiply-add differently around the new branches
    d_old = np.abs(base - base_old).max()
    check(d_old < 2e-6, 'the look off is not the viewfinder before P134 (%.2e)' % d_old)
    neutral = render(prog_new, {})
    d_neutral = np.abs(base - neutral).max()
    check(d_neutral < 2e-6, 'the look on with neutral stages changes the viewfinder (%.2e)' % d_neutral)
    print('look off vs before P134: max %.1e (bit for bit %s); neutral look vs off: max %.1e (%d patches)'
          % (d_old, np.array_equal(base, base_old), d_neutral, n))
    mono = render(prog_new, {'u_look_mono': (1.0, 1.0, 1.0)})
    spread = (mono.max(1) - mono.min(1)).max()
    print('monochrome neutral: max channel spread %.5f' % spread)
    check(spread < 0.004, 'neutral monochrome is not grey (%.4f)' % spread)
    greys = slice(40, 52)
    graded = render(prog_new, {'u_look_grade_a': (0.04, 0.0, 0.0, 0.0)})
    dark, bright = greys.start + 2, greys.stop - 1
    tint_dark = graded[dark, 0] - graded[dark, 1] - (base[dark, 0] - base[dark, 1])
    tint_bright = graded[bright, 0] - graded[bright, 1] - (base[bright, 0] - base[bright, 1])
    print('shadow grading a +0.04: dark grey R-G %+.4f, bright grey %+.4f' % (tint_dark, tint_bright))
    check(tint_dark > 0.01 and abs(tint_bright) < tint_dark * 0.3, 'the shadow grading does not tint the shadows only')
    mix = [0.0] * 24
    mix[0 * 3 + 1] = mix[1 * 3 + 1] = 1.0                 # red and orange saturation +100
    skin_rows = slice(52, 64)
    m_off = render(prog_new, {'u_look_mix_on': 1, 'mix': mix})
    m_on = render(prog_new, {'u_look_mix_on': 1, 'mix': mix, 'u_look_skin': (1.0, 0.0, 0.0)})

    def chroma(c):
        return c.max(1) - c.min(1)
    g_off = (chroma(m_off[skin_rows]) / np.maximum(chroma(base[skin_rows]), 1e-6)).mean()
    g_on = (chroma(m_on[skin_rows]) / np.maximum(chroma(base[skin_rows]), 1e-6)).mean()
    print('skin under the mixer +100: chroma x%.3f unprotected, x%.3f protected' % (g_off, g_on))
    check(g_off > 1.05 and g_on < 1 + (g_off - 1) * 0.6, 'skin protection does not hold back the mixer on skin')
    same = np.abs(render(prog_new, lut=t_lut) - base).max()
    print('identity LUT 17^3: max |with - without| %.6f' % same)
    check(same < TOL, 'the identity LUT changes the viewfinder (%.6f)' % same)
    ranged = render(prog_new, lut=t_lut, lut_range=(0.0, 2.0))
    err = np.abs(ranged - np.clip(base / 2.0, 0, 1)).max()
    print('identity LUT on the domain 0..2: max |GPU - half| %.6f' % err)
    check(err < TOL, 'the LUT domain is not honoured (%.6f)' % err)
    if FAILURES:
        print('viewfinder look check FAILED (%d)' % len(FAILURES))
        return 1
    print('viewfinder look check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
