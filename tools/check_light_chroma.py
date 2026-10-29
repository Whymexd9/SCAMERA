#!/usr/bin/env python3
"""P393 (owner, 2026-10-29: sunlit yellow birches pale peach-white beside ArkCam's saturated yellow): why/combine.glsl LIGHT_CHROMA
on the GPU (moderngl), the shipped shader with and without the define, as WhyCombine / LiveWhyTone draw it (AgX, the photo's
uniforms, the colour fed so that the fusion's gain is 1: the exposed light reaches the tone unchanged, fU 1).

Exposure ramps (0.5 EV steps, the colour's luminance 0.045 .. 1.44; a 0.1 EV ramp of the leaf for continuity) of the colours of the
owner's birch shot (OPPO X8U 15:41:26: the leaves' linear sRGB 1 : 0.53 - 0.59 : 0.18 - 0.23) and of the scenes the term must leave
alone. Checks:
- the leaves in the light tones: C* up (L* ~84 / ~88: >= 10 / >= 8 more than before), never above the colour's own mid-tone chroma
  (+2), the lightness kept (|dL*| <= 1 everywhere), monotone luminance, no step along the fine ramp (|dC*| per 0.1 EV at most 3.5 or
  AgX's own + 0.7); the hue turns only as far as the locked hue cannot hold that chroma (P393 review: the turn in every light tone
  made skin, lamps and sunsets yellower - the owner's other photos are too yellow already): at L* ~84, where sRGB holds the leaf's
  chroma at its own hue, within 1.5 deg of before; at L* ~88, where it cannot, yellow (CIELAB hue >= 78, before ~73);
- P393 review (B1, the hue snapped to AgX's where the target passed the chroma: 13 - 23 dE76 in 0.005 EV): 0.005 EV ramps of the
  ColorChecker orange and orange-yellow, a sodium lamp's glow, amber LEDs, the leaf and an orange - the largest dE76 between
  neighbours at most before's + 0.3, the largest CIELAB hue step at most max(before's, 0.25) + 0.25 deg; the scene-hue band's edge
  (a sunset gradient from golden to red-orange, 0.004 EV x 0.27 deg per pixel): at most 2.5 dE76 between neighbouring rows, the
  exposure direction as before + 0.3;
- P393 review (M3 / M4 / M5): bit-identical - common skin (ColorChecker light / dark, Fitzpatrick III - V, medium, tan, dark, blush,
  Monk 7, at 0.18 x 2^0 .. 2^3), a red-orange sunset glow (P341's) and maple red, the greens (a green leaf, lime, ColorChecker
  yellow-green), the blues (sky blue, deep blue, pale sky, cyan) without SKY and under the P378 mask's soft edge (0.1 .. 0.5), and
  in the leaves' own hue under the saturation gate Monk 5, a lit lampshade and a yellow wallpaper; Fitzpatrick II, a warm-lit face,
  an orange-red glow (the hue band's edge), an olive skin and Monk 6 (the saturation gate's) within 3 dE76; rows from a pale to a
  saturated orange-yellow (the saturation gate's edge) at most before's + 0.8 dE76 (and 2.5) between neighbours;
- bit-identical (max |d| 0): pale colours (skin, a cream wall, a neutral, lavender: the light-tone vibrance's domain), every colour at
  the grey and below (exposed luminance <= 0.18), lights (exposed brightest channel >= 2^1.5 x the AgX white 0.18 x 2^3.5), TONE_OP 1
  (ACES / Uchimura / the own curve), the sky under a full P378 mask (SKY 1, WhySky 1);
- glslc: LIGHT_CHROMA 1 with every other define family the app combines (P3_OUT, COLOUR_LOOK, TONE_OP, SKY, POINT_FUSED, RAW_SOURCE,
  HL_LANDING, HL_OPPOSED, HEADROOM_WIDE, SUN_BLOOM, MACRO_TOE, FILM_LUT, FILM_SIM).
Usage: check_light_chroma.py [--report]
"""
import os
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from check_colour_output import expand_imports, set_define, find_glslc  # noqa: E402
import check_why_highlights as cwh  # noqa: E402

SH = ROOT / 'app/src/main/assets/shaders'
REPORT = '--report' in sys.argv
FAILURES = []
LUMA = np.array([0.2126, 0.7152, 0.0722])
AGX_WHITE = 0.18 * 2 ** 3.5
LIGHT = 2 ** 1.5 * AGX_WHITE        # the term's chroma ends at 2 x the AgX white, its hue at 2^1.5 x (why/combine LIGHT_CHROMA)
COLOURS = {
    'leaf bright': (1.0, 0.53, 0.20), 'leaf mid': (1.0, 0.59, 0.29), 'golden': (1.0, 0.70, 0.12), 'orange': (1.0, 0.45, 0.10),
    'skin': (0.62, 0.45, 0.36), 'cream wall': (1.0, 0.90, 0.75), 'neutral': (0.5, 0.5, 0.5), 'lavender': (0.9, 0.8, 1.0),
    'sky blue': (0.45, 0.60, 0.90), 'deep blue': (0.25, 0.45, 0.95), 'green leaf': (0.30, 0.50, 0.12), 'red LED': (1.50, 0.12, 0.10),
    'violet LED': (1.14, 0.16, 1.71),
}
PALE = ('skin', 'cream wall', 'neutral', 'lavender')
EV = np.arange(-1.0, 5.01, 0.5)          # luminance 0.09 x 2^EV
FINE = np.arange(0.0, 4.01, 0.1)


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def srgb_lab(disp):
    """Display-encoded sRGB (0..1) -> CIELAB (D65)."""
    c = np.clip(disp, 0, 1)
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    m = np.array([[0.4124564, 0.3575761, 0.1804375], [0.2126729, 0.7151522, 0.0721750], [0.0193339, 0.1191920, 0.9503041]])
    xyz = lin @ m.T / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > (6 / 29) ** 3, np.cbrt(xyz), xyz / (3 * (6 / 29) ** 2) + 4 / 29)
    lab = np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], -1)
    return lab[..., 0], np.hypot(lab[..., 1], lab[..., 2]), np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360


def oklab_hue(disp):
    c = np.clip(disp, 0, 1)
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    lms = np.cbrt(np.maximum(lin @ np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
                                               [0.0883024619, 0.2817188376, 0.6299787005]]).T, 0))
    a = 1.9779984951 * lms[..., 0] - 2.4285922050 * lms[..., 1] + 0.4505937099 * lms[..., 2]
    b = 0.0259040371 * lms[..., 0] + 0.7827717662 * lms[..., 1] - 0.8086757660 * lms[..., 2]
    return np.degrees(np.arctan2(b, a)) % 360


class Renderer:
    def __init__(self):
        try:
            self.ctx = moderngl.create_standalone_context(backend='egl', require=430)
        except Exception:
            self.ctx = moderngl.create_standalone_context(require=430)
        self.progs = {}

    def tex(self, a):
        t = self.ctx.texture((a.shape[1], a.shape[0]), 4, a.astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        t.repeat_x = t.repeat_y = False
        return t

    def prog(self, defines):
        key = tuple(sorted(defines.items()))
        if key not in self.progs:
            src = (SH / 'why/combine.glsl').read_text(encoding='utf-8')
            for k, v in defines.items():
                src = set_define(src, k, v)
            vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
            self.progs[key] = self.ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + expand_imports(src))
        return self.progs[key]

    def render(self, rows, scales, defines, extra=None, sky=None):
        """rows: linear colours (normalised to luminance 1); scales: exposed luminance per column -> display sRGB (rows x cols x 3)."""
        H, W = len(rows), len(scales)
        orig = np.zeros((H, W, 4), np.float32)
        fused = np.zeros_like(orig)
        for r, rgb in enumerate(rows):
            for i, s in enumerate(scales):
                c = np.asarray(rgb) * s
                orig[r, i, :3] = c
                fused[r, i, 0] = cwh.fused_for(max(max(float(c @ LUMA), c.max() * 0.5), 1e-4))
        prog = self.prog(defines)
        out = self.ctx.texture((W, H), 4, dtype='f4')
        fb = self.ctx.framebuffer([out])
        fb.use()
        self.ctx.viewport = (0, 0, W, H)
        t_orig, t_fused, ones = self.tex(orig), self.tex(fused), self.tex(np.ones((1, 1, 4), np.float32))
        t_sky = self.tex(np.full((1, 1, 4), sky if sky is not None else 0.0, np.float32))
        units = {'InputBuffer': t_orig, 'GainMap': ones, 'WhyLow': t_orig, 'WhyColour': t_orig, 'WhyFused': t_fused,
                 'WhyDetailRef': t_orig, 'WhyLumaS': t_orig, 'WhySky': t_sky}
        for i, (k, t) in enumerate(units.items()):
            if k in prog:
                t.use(i)
                prog[k].value = i
        eye = tuple(np.eye(3).flatten())
        vals = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0, 'fU': 1, 'colourFU': 1,
                'aeU': 1.0, 'clipU': 4.0, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.1, 'vibU': (0.0, 0.4, 0.2), 'vibHlU': 1.0,
                'detailGainU': 0.0, 'deltaChromaU': 1.0, 'filmToeU': 0.1, 'ditherU': 0, 'agxAU': (2.7, 1.35, 1.6, 1.0),
                'agxBU': (-8.5, 3.5, 0.3, 4.0), 'headroomU': 1, 'hlWhiteU': 0.15, 'signedHueU': 1, 'lightChromaU': 1.0,
                'skyU': (1.0, 0.94, 0.91, 2.0), 'skyMU': (1.0, 1.0, 0.2, 0.05), 'skyTopU': (0.0, 1.0),
                'toneOpU': 1, 'acesU': (1.0, 2.51, 0.59, 0.05), 'uchiAU': (1.0, 0.9, 1.0, 1.0), 'uchiBU': (0.22, 0.4, 1.33, 0.0)}
        vals.update(extra or {})
        for k, v in vals.items():
            if k in prog:
                prog[k].value = v
        self.ctx.vertex_array(prog, []).render(vertices=3)
        img = np.frombuffer(out.read(), np.float32).reshape(H, W, 4)[:, :, :3].copy()
        for t in (out, t_orig, t_fused, ones, t_sky):
            t.release()
        fb.release()
        return img


def norm(rgb):
    rgb = np.asarray(rgb, np.float64)
    return rgb / (rgb @ LUMA)


def ramps(r):
    names = list(COLOURS)
    rows = [norm(COLOURS[n]) for n in names]
    scales = 0.09 * 2.0 ** EV
    base = {'MACRO_TOE': '1'}
    before = r.render(rows, scales, base)
    after = r.render(rows, scales, dict(base, LIGHT_CHROMA='1'))
    Lb, Cb, hb = srgb_lab(before)
    La, Ca, ha = srgb_lab(after)
    if REPORT:
        print('EV over a luminance of 0.09: ' + ' '.join('%+5.1f' % e for e in EV))
        for i, n in enumerate(names):
            print('%-11s B ' % n + ' '.join('%2.0f/%2.0f/%3.0f' % v for v in zip(Lb[i], Cb[i], hb[i])))
            print('%-11s A ' % '' + ' '.join('%2.0f/%2.0f/%3.0f' % v for v in zip(La[i], Ca[i], ha[i])))
    diff = np.abs(after - before).max(-1)
    for i, n in enumerate(names):
        rgb = rows[i]
        lum = scales
        exposed_max = rgb.max() * scales
        check(np.abs(La[i] - Lb[i]).max() <= 1.0, '%s: the lightness moved %.2f L*' % (n, np.abs(La[i] - Lb[i]).max()))
        y = (np.clip(after[i], 0, 1) ** 2.2) @ LUMA
        check(np.all(np.diff(y) > -1e-4), '%s: the output luminance falls along the ramp' % n)
        mids = lum <= 0.18 + 1e-6
        check(diff[i][mids].max() == 0.0, '%s: changed at the grey or below (%.4f)' % (n, diff[i][mids].max()))
        lights = exposed_max >= LIGHT
        if lights.any():
            check(diff[i][lights].max() == 0.0, '%s: a light at 2^1.5 x the AgX white changed (%.4f)' % (n, diff[i][lights].max()))
        if n in PALE:
            check(diff[i].max() == 0.0, '%s: a pale colour changed (%.4f)' % (n, diff[i].max()))
        if n in ('sky blue', 'deep blue', 'green leaf'):
            check(diff[i].max() == 0.0, '%s: changed (%.4f; the term is the orange-yellow hues\' only)' % (n, diff[i].max()))
        if n in ('leaf bright', 'leaf mid', 'golden', 'orange'):
            peak = Ca[i][lum <= 0.27].max()
            check(Ca[i].max() <= peak + 2.0, '%s: C* %.1f above its mid-tone %.1f' % (n, Ca[i].max(), peak))
    i = names.index('leaf bright')
    for ev, gain in ((2.5, 10.0), (3.0, 8.0)):
        k = int(np.argmin(np.abs(EV - ev)))
        check(Ca[i][k] >= Cb[i][k] + gain, 'leaf at L* %.0f: C* %.1f -> %.1f (needs +%.0f)' % (La[i][k], Cb[i][k], Ca[i][k], gain))
    # the hue: kept where sRGB holds the raised chroma at the leaf's own hue (L* ~84), yellow where it cannot (L* ~88)
    k = int(np.argmin(np.abs(EV - 2.5)))
    check(abs((ha[i][k] - hb[i][k] + 180) % 360 - 180) <= 1.5, 'leaf at L* %.0f: hue %.1f -> %.1f (the gamut holds its chroma there)'
          % (La[i][k], hb[i][k], ha[i][k]))
    k = int(np.argmin(np.abs(EV - 3.0)))
    check(ha[i][k] >= 78.0, 'leaf at L* %.0f: hue %.1f (yellow from 78; before %.1f)' % (La[i][k], ha[i][k], hb[i][k]))
    a, b = int(np.argmin(np.abs(EV - 2.5))), int(np.argmin(np.abs(EV - 3.0)))
    print('ramps: the leaf at L* %.0f / %.0f C* %.1f / %.1f -> %.1f / %.1f, hue %.0f / %.0f -> %.0f / %.0f; pale colours, the grey and below, '
          'lights unchanged' % (La[i][a], La[i][b], Cb[i][a], Cb[i][b], Ca[i][a], Ca[i][b], hb[i][a], hb[i][b], ha[i][a], ha[i][b]))
    # continuity: the fine ramp's chroma never steps more than the AgX shoulder itself does
    rows_f = [norm(COLOURS['leaf bright']), norm(COLOURS['orange']), norm(COLOURS['green leaf'])]
    scales_f = 0.09 * 2.0 ** FINE
    fb_ = r.render(rows_f, scales_f, base)
    fa_ = r.render(rows_f, scales_f, dict(base, LIGHT_CHROMA='1'))
    for j, n in enumerate(('leaf bright', 'orange', 'green leaf')):
        _, cb, _ = srgb_lab(fb_[j])
        _, ca, _ = srgb_lab(fa_[j])
        step_b, step_a = np.abs(np.diff(cb)).max(), np.abs(np.diff(ca)).max()
        check(step_a <= max(3.5, step_b + 0.7), '%s: C* steps %.1f per 0.1 EV (before %.1f)' % (n, step_a, step_b))
        if REPORT:
            print('  fine ramp %s: largest C* step %.2f per 0.1 EV (before %.2f)' % (n, step_a, step_b))
    # the other operators: compiled out
    for op in (1, 2, 3):
        t0 = r.render(rows, scales, dict(base, TONE_OP='1'), {'toneOpU': op, 'curveU': [(0.0, 0.0, 0.0, 0.0)] * 64})
        t1 = r.render(rows, scales, dict(base, TONE_OP='1', LIGHT_CHROMA='1'), {'toneOpU': op, 'curveU': [(0.0, 0.0, 0.0, 0.0)] * 64})
        check(np.abs(t1 - t0).max() == 0.0, 'TONE_OP %d: changed by LIGHT_CHROMA' % op)
    # the sky: a full P378 mask leaves every colour as it was; no mask: the leaves as without SKY
    s0 = r.render(rows, scales, dict(base, SKY='1'), sky=1.0)
    s1 = r.render(rows, scales, dict(base, SKY='1', LIGHT_CHROMA='1'), sky=1.0)
    check(np.abs(s1 - s0).max() == 0.0, 'the sky under a full mask changed (%.4f)' % np.abs(s1 - s0).max())
    n0 = r.render(rows, scales, dict(base, SKY='1', LIGHT_CHROMA='1'), sky=0.0)
    check(np.abs(n0 - after).max() < 1e-5, 'SKY 1 with an empty mask is not the term without SKY (%.6f)' % np.abs(n0 - after).max())
    # Display P3: the same term (the leaf more saturated in the light tones too)
    p0 = r.render(rows, scales, dict(base, P3_OUT='1'))
    p1 = r.render(rows, scales, dict(base, P3_OUT='1', LIGHT_CHROMA='1'))
    k = int(np.argmin(np.abs(EV - 2.5)))
    sat = lambda v: 1 - v.min() / max(v.max(), 1e-6)
    check(sat(p1[i][k]) > sat(p0[i][k]) + 0.05, 'P3: the leaf at +2.5 EV not more saturated (%.3f -> %.3f)' % (sat(p0[i][k]), sat(p1[i][k])))
    print('sky (full mask), TONE_OP 1 (ACES / Uchimura / own curve): bit-identical; empty mask = the term; Display P3: the leaf %.3f -> %.3f '
          'saturation' % (sat(p0[i][k]), sat(p1[i][k])))


def lin8(s):
    c = np.asarray(s, np.float64) / 255.0
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def lab_ab(disp):
    L, C, h = srgb_lab(disp)
    return np.stack([L, C * np.cos(np.radians(h)), C * np.sin(np.radians(h))], -1)


def continuity(r):
    """P393 review B1: no step along a 0.005 EV exposure ramp, none across the scene-hue band's edge."""
    base = {'MACRO_TOE': '1'}
    cols = {'ColorChecker orange': lin8((214, 126, 44)), 'ColorChecker orange-yellow': lin8((224, 163, 46)),
            'sodium glow': (1.0, 0.42, 0.04), 'amber LED': (1.0, 0.5, 0.0), 'amber': (1.0, 0.35, 0.02), 'leaf bright': COLOURS['leaf bright'],
            'orange': COLOURS['orange'], 'golden': COLOURS['golden']}
    fine = np.arange(-1.0, 5.0, 0.005)
    scales = 0.09 * 2.0 ** fine
    names = list(cols)
    rows = [norm(cols[n]) for n in names]
    b0 = r.render(rows, scales, base)
    b1 = r.render(rows, scales, dict(base, LIGHT_CHROMA='1'))
    worst = []
    for i, n in enumerate(names):
        s0 = np.linalg.norm(np.diff(lab_ab(b0[i]), axis=0), axis=-1).max()
        s1 = np.linalg.norm(np.diff(lab_ab(b1[i]), axis=0), axis=-1).max()
        _, C0, h0 = srgb_lab(b0[i])
        _, C1, h1 = srgb_lab(b1[i])
        dh0 = (np.abs((np.diff(h0) + 180) % 360 - 180) * (C0[1:] > 5)).max()
        dh1 = (np.abs((np.diff(h1) + 180) % 360 - 180) * (C1[1:] > 5)).max()
        check(s1 <= s0 + 0.3, '%s: a step of %.2f dE76 in 0.005 EV (before %.2f)' % (n, s1, s0))
        check(dh1 <= max(dh0, 0.25) + 0.25, '%s: a hue step of %.2f deg in 0.005 EV (before %.2f)' % (n, dh1, dh0))
        worst.append('%s %.2f/%.2f' % (n, s1, s0))
        if REPORT:
            print('  0.005 EV ramp %s: largest dE76 step %.2f (before %.2f), hue step %.2f (before %.2f)' % (n, s1, s0, dh1, dh0))
    # the band's edge: rows from golden to red-orange (scene hue ~89 -> 57 deg, ~0.27 deg a row), the light 0.25 .. 2 x 2^3 along x
    rows = [norm(np.array([1.0, 0.70, 0.18]) * (1 - t) + np.array([1.0, 0.32, 0.06]) * t) for t in np.linspace(0, 1, 96)]
    scales = 0.25 * 2.0 ** np.arange(0, 3.0, 0.004)
    g0 = lab_ab(r.render(rows, scales, base))
    g1 = lab_ab(r.render(rows, scales, dict(base, LIGHT_CHROMA='1')))
    v1 = np.linalg.norm(np.diff(g1, axis=0), axis=-1).max()
    x0 = np.linalg.norm(np.diff(g0, axis=1), axis=-1).max()
    x1 = np.linalg.norm(np.diff(g1, axis=1), axis=-1).max()
    check(v1 <= 2.5, 'sunset gradient: %.2f dE76 between neighbouring rows (the hue band\'s edge)' % v1)
    check(x1 <= x0 + 0.3, 'sunset gradient: %.2f dE76 between neighbours along the light (before %.2f)' % (x1, x0))
    # the saturation gate's edge (LC_SAT): rows from a pale to a saturated orange-yellow (128 rows, ~0.5 dE76 a row before), the light
    # 0.25 .. 2 x 2^3 along x - the gate steepens the rows' steps, never into a contour
    sat_steps = []
    for pale, full in (((1.0, 0.75, 0.50), (1.0, 0.50, 0.12)), ((1.0, 0.80, 0.55), (1.0, 0.62, 0.10))):
        rows = [norm(np.array(pale) * (1 - t) + np.array(full) * t) for t in np.linspace(0, 1, 128)]
        scales = 0.25 * 2.0 ** np.arange(0, 3.0, 0.02)
        q0 = np.linalg.norm(np.diff(lab_ab(r.render(rows, scales, base)), axis=0), axis=-1).max()
        q1 = np.linalg.norm(np.diff(lab_ab(r.render(rows, scales, dict(base, LIGHT_CHROMA='1'))), axis=0), axis=-1).max()
        check(q1 <= min(q0 + 0.8, 2.5), 'pale -> saturated %s: %.2f dE76 between neighbouring rows (before %.2f; the saturation gate\'s '
              'edge)' % (full, q1, q0))
        sat_steps.append('%.2f / %.2f' % (q1, q0))
    print('continuity: 0.005 EV ramps, largest dE76 step after / before: %s; the sunset gradient %.2f between rows, %.2f along the light '
          '(before %.2f); pale -> saturated rows %s' % (', '.join(worst), v1, x1, x0, ', '.join(sat_steps)))


def scope(r):
    """P393 review M3 - M5: skin, red-orange glows, warm interiors, greens and blues as before."""
    base = {'MACRO_TOE': '1'}
    same = {'ColorChecker light skin': lin8((194, 150, 130)), 'ColorChecker dark skin': lin8((115, 82, 68)),
            'Fitzpatrick III': lin8((210, 158, 124)), 'Fitzpatrick IV': lin8((186, 119, 80)), 'Fitzpatrick V': lin8((165, 93, 43)),
            'medium skin': lin8((198, 150, 120)), 'tan skin': lin8((190, 130, 90)), 'dark skin': lin8((140, 90, 60)),
            'blush': lin8((220, 140, 130)), 'Monk 7': lin8((0x82, 0x5c, 0x43)), 'red-orange glow': (1.0, 0.30, 0.06),
            'maple red': (1.0, 0.25, 0.05), 'lime': (0.5, 0.8, 0.1), 'ColorChecker yellow-green': lin8((157, 188, 64)),
            'pale sky': (0.6, 0.7, 0.9), 'cyan': (0.10, 0.70, 0.80),
            # the leaves' own hue, under the saturation gate (LC_SAT 0.14: the mid rendition's C / L 0.098 / 0.124 / 0.111)
            'Monk 5': lin8((0xd7, 0xbd, 0x96)), 'lampshade': (1.0, 0.7, 0.35), 'yellow wallpaper': (1.0, 0.85, 0.45)}
    # the band's edges (hue: Fitzpatrick II, a warm-lit face, an orange-red glow; saturation: an olive skin, Monk 6 - C / L 0.139,
    # 0.145)
    near = {'Fitzpatrick II': lin8((231, 180, 143)), 'warm-lit face': lin8((225, 160, 110)), 'orange-red glow': (1.0, 0.32, 0.06),
            'olive skin': lin8((180, 140, 100)), 'Monk 6': lin8((0xa0, 0x7e, 0x56))}
    allc = dict(same, **near)
    names = list(allc)
    rows = [norm(allc[n]) for n in names]
    scales = 0.18 * 2.0 ** np.arange(0.0, 3.01, 0.25)
    b0 = r.render(rows, scales, base)
    b1 = r.render(rows, scales, dict(base, LIGHT_CHROMA='1'))
    rep = []
    for i, n in enumerate(names):
        d = np.abs(b1[i] - b0[i]).max()
        de = np.linalg.norm(lab_ab(b1[i]) - lab_ab(b0[i]), axis=-1).max()
        if n in same:
            check(d == 0.0, '%s: changed (%.4f)' % (n, d))
        else:
            check(de <= 3.0, '%s: %.2f dE76 (the band\'s edge)' % (n, de))
            rep.append('%s %.1f' % (n, de))
    # the sky's blues under the P378 mask's soft edge (the sky between leaves) and without SKY
    blues = [norm(COLOURS['sky blue']), norm(COLOURS['deep blue']), norm((0.6, 0.7, 0.9))]
    sc = 0.09 * 2.0 ** EV
    for m in (0.1, 0.25, 0.5):
        s0 = r.render(blues, sc, dict(base, SKY='1'), sky=m)
        s1 = r.render(blues, sc, dict(base, SKY='1', LIGHT_CHROMA='1'), sky=m)
        check(np.abs(s1 - s0).max() == 0.0, 'blues under a sky mask of %.2f changed (%.4f)' % (m, np.abs(s1 - s0).max()))
    print('scope: %d skin / glow / interior / green / blue colours bit-identical, blues under the mask\'s edge too; dE76 at the edges %s '
          '(max over 0.18 x 2^0 .. 2^3)' % (len(same), ', '.join(rep)))


def glslc_part():
    glslc = find_glslc()
    if not glslc:
        print('glslc not found: compile skipped')
        return
    cases = [{}, {'P3_OUT': '1'}, {'COLOUR_LOOK': '1'}, {'P3_OUT': '1', 'COLOUR_LOOK': '1'}, {'TONE_OP': '1'},
             {'TONE_OP': '1', 'COLOUR_LOOK': '1', 'P3_OUT': '1'}, {'SKY': '1'}, {'SKY': '1', 'POINT_FUSED': '1'},
             {'SKY': '1', 'POINT_FUSED': '1', 'RAW_SOURCE': '1', 'MACRO_TOE': '1'},
             {'HL_LANDING': '1', 'HL_OPPOSED': '1', 'HEADROOM_WIDE': '1', 'SUN_BLOOM': '1', 'MACRO_TOE': '1', 'SKY': '1'},
             {'FILM_LUT': '1', 'COLOUR_LOOK': '1'}, {'FILM_SIM': '1', 'P3_OUT': '1'},
             {'POINT_FUSED': '1', 'COLOUR_LOOK': '1', 'FILM_LUT': '1', 'P3_OUT': '1', 'SKY': '1'}]
    for defines in cases:
        src = (SH / 'why/combine.glsl').read_text(encoding='utf-8')
        for k, v in dict(defines, LIGHT_CHROMA='1').items():
            src = set_define(src, k, v)
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / 'x.frag'
            f.write_text('#version 310 es\n' + expand_imports(src), encoding='utf-8')
            res = subprocess.run([glslc, '--target-env=opengl', '-fshader-stage=fragment', '-std=310es', '-fauto-map-locations',
                                  '-fauto-bind-uniforms', '-o', os.devnull, str(f)], capture_output=True, text=True)
            check(res.returncode == 0, 'glslc LIGHT_CHROMA 1 + %s:\n%s' % (defines, res.stderr))
    print('glslc: LIGHT_CHROMA 1 with %d define sets compiled' % len(cases))


def main():
    glslc_part()
    r = Renderer()
    try:
        ramps(r)
        continuity(r)
        scope(r)
    finally:
        r.ctx.release()
    if FAILURES:
        print('light chroma check FAIL (%d)' % len(FAILURES))
        sys.exit(1)
    print('light chroma check PASS')


if __name__ == '__main__':
    main()
