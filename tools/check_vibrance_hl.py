#!/usr/bin/env python3
"""P393: the light-tone vibrance's shape (why/combine.glsl vibHlShapeU, WhyTone.vibranceHlShape) on the host GPU.

Owner, 2026-10-29: «У нас что-то с желтыми цветом ... В последней паре более жёлтое фото наше» (pairs against the reference app
on the X200 Ultra). P341's light-tone vibrance ramped in over OKLab L 0.45 .. 0.75 with its neutral gate open from chroma 0.002 ..
0.008: a cream wall, a white ceiling or a sunlit facade in the mid-light tones took x1.9 - 2.0 of the scene's chroma (the tone
gives the mid-tones x1.5) and a near-white's white-balance cast doubled. P393: for pale colour (chroma under 0.020) the ramp 0.50
.. 0.85 (the light tones keep the mid-tones' saturation, the AgX shoulder's loss made up above) and the gate 0.010 .. 0.025 (a cast
not raised, the gate at most 10 % steeper than P341's); from chroma 0.020 .. 0.035 up and under the sky mask P341's / P378's as before (P393
review: the pale class reaching to 0.04 .. 0.07 took a skin tone or a yellow at L* 55 - 76 to x0.74 - 0.88 of P341's chroma, the
owner's trees pair - ours already paler than the reference app's - went the wrong way).

The photo's passes (why/low, the lpyr fusion, why/combine with the photo's uniforms; check_why_sky's bench) on flat patches of an
OKLab grid (lightness 0.30 .. 1.05 x chroma 0.004 .. 0.09 x nine hues) at a flat fusion. Gates:
- off-switch: vibHlShapeU = P341's shape renders the shader with P341's / P378's blocks (taken from this one) bit for bit, SKY 0
  and SKY 1 (off the mask the block is SKY 0's statement: a pixel there the same bit for bit in both variants);
- the shader's unset default = WhyTone.VIBRANCE_HL_SHAPE (read from the source) bit for bit;
- a white-balance cast (chroma 0.004 - 0.008) is not raised: its display chroma within x1.15 of the tone without the vibrance at
  every lightness (P341: up to x2.6; what is left is the sky vibrance's own lift of a bluish cast towards the gate);
- a pale colour (chroma 0.012 - 0.020; P393 review: no longer 0.02 - 0.035, which are clearly coloured surfaces) in the light
  tones (L 0.55 - 0.95) at most x1.05 the saturation the tone gives it in the mid-tones (its largest chroma over the scene's at
  the display lightness, L 0.30 - 0.50, without the vibrance): no more saturated than the mid-tones (P341: x1.25 of that and more);
- a clearly coloured surface (chroma 0.035 up) as P341 within float rounding (2e-6), and explicitly warm ones (hues 30 - 100,
  chroma 0.035 - 0.06, L 0.5 - 0.8) and skin tones (hues 45 / 55, chroma 0.035 - 0.06, L 0.55 - 0.85) at >= 0.95 / 0.97 of P341's
  display chroma; a bright pale colour (the sunset sky off the mask, chroma 0.03 - 0.06, L 0.85 - 0.95) keeps >= 90 % of it;
  OKLab hue within 0.5 deg and L* within 1.0 of P341 everywhere;
- the vibrance's largest chroma slope along a fine ramp (d display C* / d display C* without it) at most 10 % above P341's
  (chroma noise and cast gradients on a white wall: the gate 0.010 .. 0.020 of P393's first version was x1.27 of P341's, the
  room burst's white walls' chroma noise x1.41; 0.010 .. 0.025 x1.09 / x1.12);
- under a full sky mask (SKY 1) every patch as P378 within float rounding (2e-6: the ramp's blend is arithmetic on uniforms there);
- the other tone operators (TONE_OP 1: ACES, Uchimura; P407: the reference's own term there, x (1 + 0.25 vibHlU) over OKLab L
  0.60 .. 0.80 .. 0.88 .. 0.98 with this cast gate - P341's, tuned on AgX's shoulder, over-saturated the per-channel curves'
  light tones): a cast not raised (x1.15; with the gate open the bench sees it raised), nothing changed below L 0.55, a coloured
  light tone at L 0.80 x1.10 .. x1.45 in display chroma (x1.25 in the scene), under ACES the pale colour at most x1.05 of its
  mid-tones.
Usage: check_vibrance_hl.py [--report]
"""
from pathlib import Path
import re
import sys

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import check_why_pyramid as cwp  # noqa: E402
import check_why_sky as cws  # noqa: E402
from check_colour_output import P341_VIB_BLOCK, P393_VIB_BLOCK  # noqa: E402

REPORT = '--report' in sys.argv
SH = ROOT / 'app/src/main/assets/shaders'
P341_SHAPE = (0.45, 0.75, 0.002, 0.008)
# P393 review: the first version's cast gate (0.010 .. 0.020) - x1.27 of P341's chroma slope, white walls' chroma noise x1.41
OPEN_GATE = (0.57, 0.89, 0.0, 0.0005)   # P407: the cast gate open from chroma 0 (the bench's sanity under TONE_OP 1)
NARROW_GATE = (0.50, 0.85, 0.010, 0.020)
# the vibrance's largest chroma slope at most 10 % above P341's (0.010 .. 0.025: x1.09; the room burst's white walls x1.12)
SLOPE_LIMIT = 1.10
# the SKY 1 block of P378 (before P393), for the reference shader
P378_SKY_BLOCK = ('    if (vibHlU > 0.0) {\n'
                  '        float c0 = length(lab.yz);\n'
                  '        float neutralGate = skyNeutral > 0.0 ? mix(smoothstep(0.002, 0.008, c0), smoothstep(0.015, 0.03, c0), skyNeutral)\n'
                  '                                             : smoothstep(0.002, 0.008, c0);\n'
                  '        lab.yz *= 1.0 + 1.6 * vibHlU * smoothstep(0.45, 0.75, lab.x) * neutralGate * max(1.0 - c0 / 0.10, 0.0);\n'
                  '    }\n')
P393_SKY_BLOCK = re.compile(r'    if \(vibHlU > 0\.0\) \{\n        float c0 = length\(lab\.yz\);\n        vec4 hlShape = vibHlShape\(\);\n'
                            r'        if \(skyNeutral > 0\.0\) \{\n.*?\n        \}\n    \}\n', re.S)


def java_shape():
    src = (ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/WhyTone.java').read_text(encoding='utf-8')
    m = re.search(r'VIBRANCE_HL_SHAPE = \{([^}]*)\}', src)
    p = re.search(r'VIBRANCE_HL_SHAPE_P341 = \{([^}]*)\}', src)
    return (tuple(float(v.strip().rstrip('f')) for v in m.group(1).split(',')),
            tuple(float(v.strip().rstrip('f')) for v in p.group(1).split(',')))


def p341_source():
    """why/combine.glsl with P341's / P378's vibrance blocks in place of P393's (the reference of the off-switch)."""
    src = (SH / 'why/combine.glsl').read_text(encoding='utf-8').replace('\r\n', '\n')
    assert src.count(P393_VIB_BLOCK) == 1, 'combine.glsl: no single P393 block (SKY 0)'
    src = src.replace(P393_VIB_BLOCK, P341_VIB_BLOCK, 1)
    assert len(P393_SKY_BLOCK.findall(src)) == 1, 'combine.glsl: no single P393 block (SKY 1)'
    return P393_SKY_BLOCK.sub(lambda _: P378_SKY_BLOCK, src, count=1)


class Bench(cws.Sky):
    """check_why_sky's passes without its Java decision; why/combine from a source text when one is given."""

    def __init__(self):
        self.gl = cws.cwb.Gl()
        self.mode = 'bands'   # P391: the photo's fusion as shipped (check_why_sky's default)
        self.progs = {}
        self.java = None
        self.combine_text = None

    def prog(self, name, defines=()):
        text = self.combine_text if name == 'why/combine' and self.combine_text is not None else None
        key = (name, tuple(defines), text)   # the text itself: two source variants in one render are two programs
        if key not in self.progs:
            src = cwp.read_program(text if text is not None else (cws.SH / (name + '.glsl')).read_text(), list(defines))
            self.progs[key] = self.gl.ctx.program(vertex_shader=cwp.VS, fragment_shader=src)
        return self.progs[key]

    def render(self, full, renders):
        """renders: [(name, combine text or None, SKY 0/1, uniform overrides)] -> {name: display RGB}."""
        ae = 1.0
        t_full, ones, low, ref = self.low(full, ae)
        uni = dict(cws.FUSION, expU=(1.0, 1.0, 1.0, 1.0))
        fused = self.fusion(low, ae, uni)
        hl = self.hlmask(low, 8.0)
        h, w = full.shape[:2]
        sky = self.tex(np.ones(((h + 1) // 2, (w + 1) // 2, 2), np.float32), 'f2', linear=True)
        out = {}
        for name, text, sky_on, over in renders:
            self.combine_text = text
            defines = [('HL_LANDING', '1')] + ([('SKY', '1')] if sky_on else [])
            eye = tuple(np.eye(3).flatten())
            u = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0, 'fU': 2,
                 'colourFU': 2, 'signedColourU': 0, 'signedHueU': 1, 'aeU': ae, 'clipU': 8.0, 'toeU': 0.05, 'gammaInvU': 1 / 2.2,
                 'macroU': 1.1, 'vibU': (0.0, 0.4, 0.2), 'vibHlU': 1.0, 'vibHlShapeU': (0.0, 0.0, 0.0, 0.0), 'chromaDnU': 0.0,
                 'clarityU': 0.0, 'flatProtectU': 0.0, 'detailGainU': 1.0, 'detailRefU': 1, 'sharpU': 0, 'fullInputU': 1,
                 'sharpToneU': 1.0, 'deltaChromaU': 1.0, 'filmToeU': 0.1, 'agxAU': (2.7, 1.35, 1.6, 1.0),
                 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'ditherU': 0, 'headroomU': 1, 'hlWhiteU': 0.15, 'hlSoftU': 0.7,
                 'skyU': (1.0,) + cws.SKY_TONE, 'skyMU': (1.0, 1.0, cws.SKY_GLOW, cws.SKY_VIBRANCE), 'skyTopU': (0.0, 1.0)}
            u.update(over)
            textures = [('InputBuffer', t_full), ('GainMap', ones), ('WhyLow', low), ('WhyColour', low), ('WhyFused', fused),
                        ('WhyDetailRef', ref), ('WhyLumaS', low), ('WhyHl', hl)]
            if sky_on:
                textures.append(('WhySky', sky))
            o = self.draw('why/combine', tuple(defines), w, h, 4, 'f4', textures, u)
            out[name] = self.gl.read(o)[..., :3]
        self.combine_text = None
        return out


M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566], [0.0883024619, 0.2817188376, 0.6299787005]])
M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099], [0.0259040371, 0.7827717662, -0.8086757660]])
XYZ = np.array([[0.4123908, 0.3575843, 0.1804808], [0.2126390, 0.7151687, 0.0721923], [0.0193308, 0.1191948, 0.9505322]])


def from_oklab(L, a, b):
    return np.linalg.solve(M1, np.linalg.solve(M2, np.array([L, a, b])) ** 3)


def display_oklab_hue(rgb):
    v = np.clip(rgb, 0, 1)
    lin = np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)
    ok = np.cbrt(np.maximum(lin @ M1.T, 0)) @ M2.T
    return float(np.degrees(np.arctan2(ok[2], ok[1])) % 360)


def scene_chroma_at(rgb_scene, rgb_display):
    """Lab chroma of the scene colour scaled to the display colour's luminance (the chroma a hue- and saturation-keeping tone
    would show)."""
    v = np.clip(rgb_display, 0, 1)
    yd = np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4) @ XYZ[1]
    lin = rgb_scene * yd / max(rgb_scene @ XYZ[1], 1e-9)
    xyz = lin @ XYZ.T / XYZ.sum(1)
    f = np.where(xyz > 216 / 24389, np.cbrt(np.maximum(xyz, 0)), (24389 / 27 * xyz + 16) / 116)
    return float(np.hypot(500 * (f[0] - f[1]), 200 * (f[1] - f[2])))


def display_lab(rgb):
    v = np.clip(rgb, 0, 1)
    lin = np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)
    xyz = lin @ XYZ.T / XYZ.sum(1)
    f = np.where(xyz > 216 / 24389, np.cbrt(np.maximum(xyz, 0)), (24389 / 27 * xyz + 16) / 116)
    return np.stack([116 * f[..., 1] - 16, 500 * (f[..., 0] - f[..., 1]), 200 * (f[..., 1] - f[..., 2])], -1)


LS = np.round(np.arange(0.30, 1.051, 0.05), 3)
HUES = (30, 45, 55, 60, 90, 110, 150, 250, 330)
CHROMAS = (0.004, 0.008, 0.012, 0.016, 0.020, 0.025, 0.030, 0.035, 0.045, 0.05, 0.06, 0.07, 0.09)
P = 24


def grid():
    rows = [(h, c) for h in HUES for c in CHROMAS]
    img = np.zeros((len(rows) * P, len(LS) * P, 3), np.float32)
    for i, (h, c) in enumerate(rows):
        for j, L in enumerate(LS):
            img[i * P:(i + 1) * P, j * P:(j + 1) * P] = from_oklab(L, c * np.cos(np.radians(h)), c * np.sin(np.radians(h)))
    return img, rows


def centre(rgb, i, j):
    return rgb[i * P + 8:(i + 1) * P - 8, j * P + 8:(j + 1) * P - 8]


def patches(rgb, rows, scene):
    """Each patch's centre: {(hue, chroma, L): (L*, C*, OKLab hue, the scene's C* at its luminance)}."""
    out = {}
    for i, (h, c) in enumerate(rows):
        for j, L in enumerate(LS):
            m = centre(rgb, i, j).reshape(-1, 3).mean(0)
            lab = display_lab(m)
            out[(h, c, L)] = (lab[0], float(np.hypot(lab[1], lab[2])), display_oklab_hue(m),
                              scene_chroma_at(centre(scene, i, j).reshape(-1, 3).mean(0).astype(np.float64), m))
    return out


RAMP_C = np.round(np.arange(0.002, 0.0451, 0.0005), 4)
RAMP_L = (0.60, 0.70, 0.80, 0.90, 0.95)
RAMP_H = (60, 90, 110, 250, 330)
RP = 8


def ramp_image():
    """A fine chroma ramp (0.002 .. 0.045 by 0.0005) at five lightnesses and five hues: the factor's chroma slope on the GPU."""
    rows = [(h, L) for h in RAMP_H for L in RAMP_L]
    img = np.zeros((len(rows) * RP, len(RAMP_C) * RP, 3), np.float32)
    for i, (h, L) in enumerate(rows):
        for j, c in enumerate(RAMP_C):
            img[i * RP:(i + 1) * RP, j * RP:(j + 1) * RP] = from_oklab(L, c * np.cos(np.radians(h)), c * np.sin(np.radians(h)))
    return img, rows


def ramp_chroma(rgb, rows):
    out = np.zeros((len(rows), len(RAMP_C)))
    for i in range(len(rows)):
        for j in range(len(RAMP_C)):
            lab = display_lab(rgb[i * RP + 2:(i + 1) * RP - 2, j * RP + 2:(j + 1) * RP - 2].reshape(-1, 3).mean(0))
            out[i, j] = np.hypot(lab[1], lab[2])
    return out


def max_slope(c, c0):
    """The largest d(display C*) / d(display C* without the vibrance) along the ramp (the factor's chroma slope)."""
    d, d0 = np.diff(c, axis=1), np.diff(c0, axis=1)
    return float((d / np.maximum(d0, 1e-6)).max())


def main():
    failures = []

    def check(cond, msg):
        if not cond:
            failures.append(msg)
            print('FAIL: ' + msg)

    shape, p341 = java_shape()
    check(p341 == P341_SHAPE, 'WhyTone.VIBRANCE_HL_SHAPE_P341 is not P341\'s shape: %s' % (p341,))
    ref = p341_source()
    src = (SH / 'why/combine.glsl').read_text(encoding='utf-8').replace('\r\n', '\n')
    assert src.count('#define TONE_OP 0\n') == 1, 'combine.glsl: no "#define TONE_OP 0"'
    tone1 = src.replace('#define TONE_OP 0\n', '#define TONE_OP 1\n')
    ops = {'ACES': {'toneOpU': 1, 'acesU': (1.0, 2.61, 0.59, 0.05)},
           'Uchimura': {'toneOpU': 2, 'uchiAU': (1.0, 0.9, 1.0, 1.0), 'uchiBU': (0.22, 0.4, 1.33, 0.0)}}
    img, rows = grid()
    rimg, rrows = ramp_image()
    b = Bench()
    try:
        r = b.render(img, [('vib0', None, 0, {'vibHlU': 0.0}), ('cur', None, 0, {}), ('java', None, 0, {'vibHlShapeU': shape}),
                           ('off', None, 0, {'vibHlShapeU': P341_SHAPE}), ('ref', ref, 0, {}),
                           ('skycur', None, 1, {}), ('skyref', ref, 1, {}), ('skyoff', None, 1, {'vibHlShapeU': P341_SHAPE})]
                     + [(op + k, tone1, 0, dict(u, **o)) for op, u in ops.items()
                        for k, o in (('vib0', {'vibHlU': 0.0}), ('cur', {}), ('open', {'vibHlShapeU': OPEN_GATE}))])
        rr = b.render(rimg, [('vib0', None, 0, {'vibHlU': 0.0}), ('cur', None, 0, {}), ('off', None, 0, {'vibHlShapeU': P341_SHAPE}),
                             ('narrow', None, 0, {'vibHlShapeU': NARROW_GATE})])
    finally:
        b.gl.ctx.release()
    print('off-switch against P341\'s / P378\'s blocks: max |d| %.3g (SKY 0), %.3g (SKY 1); a full sky mask against P378: %.3g'
          % (np.abs(r['off'] - r['ref']).max(), np.abs(r['skyoff'] - r['skyref']).max(), np.abs(r['skycur'] - r['skyref']).max()))
    check(np.array_equal(r['off'], r['ref']), 'vibHlShapeU = P341\'s shape is not P341\'s block bit for bit (SKY 0): max |d| %.3g'
          % np.abs(r['off'] - r['ref']).max())
    check(np.array_equal(r['skyoff'], r['skyref']), 'P341\'s shape under SKY 1 is not P378\'s block bit for bit: max |d| %.3g'
          % np.abs(r['skyoff'] - r['skyref']).max())
    check(np.array_equal(r['cur'], r['java']), 'the shader\'s unset default is not WhyTone.VIBRANCE_HL_SHAPE %s: max |d| %.3g'
          % (shape, np.abs(r['cur'] - r['java']).max()))
    check(np.abs(r['skycur'] - r['skyref']).max() <= 2e-6, 'a full sky mask is not P378\'s vibrance: max |d| %.3g'
          % np.abs(r['skycur'] - r['skyref']).max())
    v0, cur, old = patches(r['vib0'], rows, img), patches(r['cur'], rows, img), patches(r['ref'], rows, img)
    worst = {'cast': (0, None), 'cast_old': (0, None), 'pale': (0, None), 'pale_old': (0, None), 'bright': (9, None), 'hue': (0, None),
             'L': (0, None), 'sat': (0, None), 'warm': (9, None), 'skin': (9, None), 'mid': (9, None), 'mid_hi': (0, None)}

    def track(k, v, key, high=True):
        if (v > worst[k][0]) if high else (v < worst[k][0]):
            worst[k] = (v, key)

    def mid_sat(pat, h, c):
        return max(pat[(h, c, m)][1] / max(pat[(h, c, m)][3], 0.3) for m in LS if m <= 0.50)

    for (h, c, L), (l1, c1, h1, cs) in cur.items():
        l0, c0, _, _ = v0[(h, c, L)]
        lo, co, ho, _ = old[(h, c, L)]
        key = (h, c, L)
        if c <= 0.008 and L >= 0.45 and c0 > 0.3:
            track('cast', c1 / c0, key)
            track('cast_old', co / c0, key)
        if 0.012 <= c <= 0.020 and 0.55 <= L <= 0.95:
            mid = mid_sat(v0, h, c)
            track('pale', c1 / max(cs, 0.3) / mid, key)
            track('pale_old', co / max(cs, 0.3) / mid, key)
        if 0.03 <= c <= 0.06 and 0.85 <= L <= 0.95:
            track('bright', c1 / co, key, high=False)
        if 30 <= h <= 100 and 0.035 <= c <= 0.06 and 0.5 <= L <= 0.8:
            track('warm', c1 / co, key, high=False)
        if h in (45, 55) and 0.035 <= c <= 0.06 and 0.55 <= L <= 0.85:
            track('skin', c1 / co, key, high=False)
        if 0.020 < c < 0.035 and 0.5 <= L <= 0.95 and co >= 3:
            track('mid', c1 / co, key, high=False)
            track('mid_hi', c1 / co, key)
        if c >= 0.035:
            i = rows.index((h, c))
            j = list(LS).index(L)
            track('sat', float(np.abs(centre(r['cur'], i, j) - centre(r['ref'], i, j)).max()), key)
        if c1 >= 3 and co >= 3:
            track('hue', abs((h1 - ho + 180) % 360 - 180), key)
        track('L', abs(l1 - lo), key)
    print('white-balance cast (chroma <= 0.008, L >= 0.45): display chroma x%.3f of the tone without the vibrance at worst %s (P341: x%.2f %s)'
          % (worst['cast'][0], worst['cast'][1], worst['cast_old'][0], worst['cast_old'][1]))
    print('pale colour (chroma 0.012 - 0.020, L 0.55 - 0.95): saturation x%.3f of the tone\'s own in the mid-tones at worst %s (P341: x%.2f %s)'
          % (worst['pale'][0], worst['pale'][1], worst['pale_old'][0], worst['pale_old'][1]))
    print('clearly coloured (chroma >= 0.035): max |d| %.3g against P341; warm (hues 30 - 100, chroma 0.035 - 0.06, L 0.5 - 0.8) %.1f %%, '
          'skin (hues 45 / 55) %.1f %% of P341\'s display chroma at least; between (chroma 0.020 - 0.035) %.1f .. %.1f %%'
          % (worst['sat'][0], 100 * worst['warm'][0], 100 * worst['skin'][0], 100 * worst['mid'][0], 100 * worst['mid_hi'][0]))
    print('bright pale colour (chroma 0.03 - 0.06, L 0.85 - 0.95): %.1f %% of P341\'s display chroma at least %s'
          % (100 * worst['bright'][0], worst['bright'][1]))
    print('OKLab hue %.2f deg, L* %.2f at worst against P341' % (worst['hue'][0], worst['L'][0]))
    check(worst['cast'][0] <= 1.15, 'a white-balance cast is raised x%.3f at %s' % worst['cast'])
    check(worst['cast_old'][0] >= 1.5, 'the bench no longer sees P341 raising a cast (x%.2f): it measures nothing' % worst['cast_old'][0])
    check(worst['pale'][0] <= 1.05, 'a pale light-tone colour is x%.3f as saturated as the tone makes the mid-tones at %s' % worst['pale'])
    check(worst['pale_old'][0] >= 1.25, 'the bench no longer sees P341 over-saturating pale mid-light colour (x%.2f)' % worst['pale_old'][0])
    check(worst['bright'][0] >= 0.90, 'a bright pale colour keeps only %.1f %% of P341\'s chroma at %s' % (100 * worst['bright'][0], worst['bright'][1]))
    check(worst['sat'][0] <= 2e-6, 'a clearly coloured surface (chroma >= 0.035) differs from P341: max |d| %.3g at %s' % worst['sat'])
    check(worst['warm'][0] >= 0.95, 'a warm surface keeps only %.1f %% of P341\'s chroma at %s (the owner\'s trees: ours already paler)'
          % (100 * worst['warm'][0], worst['warm'][1]))
    check(worst['skin'][0] >= 0.97, 'a skin tone keeps only %.1f %% of P341\'s chroma at %s' % (100 * worst['skin'][0], worst['skin'][1]))
    check(worst['hue'][0] <= 0.5, 'the hue moved %.2f deg at %s' % worst['hue'])
    check(worst['L'][0] <= 1.0, 'L* moved %.2f at %s' % worst['L'])
    # the chroma slope of the factor along a fine ramp (the GPU's), against P341's
    rc = {k: ramp_chroma(v, rrows) for k, v in rr.items()}
    s_cur, s_old, s_narrow = (max_slope(rc[k], rc['vib0']) for k in ('cur', 'off', 'narrow'))
    print('the largest chroma slope of the vibrance along a fine ramp (chroma 0.002 - 0.045, L 0.60 - 0.95): x%.2f (P341 x%.2f, '
          'the gate 0.010 .. 0.020 of P393\'s first version x%.2f)' % (s_cur, s_old, s_narrow))
    check(s_old >= 3.0, 'the ramp no longer sees P341\'s gate (slope x%.2f): it measures nothing' % s_old)
    check(s_narrow > s_old * SLOPE_LIMIT, 'the ramp no longer sees the steep 0.010 .. 0.020 gate (x%.2f): it measures nothing' % s_narrow)
    check(s_cur <= s_old * SLOPE_LIMIT, 'the vibrance is x%.2f steeper in chroma than P341\'s (x%.2f against x%.2f): chroma noise and cast '
          'gradients on white walls stretched more' % (s_cur / s_old, s_cur, s_old))
    # TONE_OP 1 (P407): the reference's own light-tone term, x (1 + 0.25 vibHlU) over OKLab L 0.60 .. 0.80 .. 0.88 .. 0.98, with the cast gate
    for op in ops:
        o0, oc, oo = (patches(r[op + k], rows, img) for k in ('vib0', 'cur', 'open'))
        cast = max(oc[k][1] / o0_[1] for k, o0_ in o0.items() if k[1] <= 0.008 and k[2] >= 0.45 and o0_[1] > 0.3)
        cast_open = max(oo[k][1] / o0[k][1] for k in o0 if k[1] <= 0.008 and k[2] >= 0.45 and o0[k][1] > 0.3)
        low = max(float(np.abs(centre(r[op + 'cur'], rows.index((h, c)), list(LS).index(L)) - centre(r[op + 'vib0'], rows.index((h, c)),
                                                                                                   list(LS).index(L))).max())
                  for (h, c, L) in o0 if L <= 0.55)
        full = [oc[k][1] / o0[k][1] for k in o0 if k[1] >= 0.035 and k[2] == 0.80 and o0[k][1] > 3]
        pale = max(oc[k][1] / max(oc[k][3], 0.3) / mid_sat(o0, k[0], k[1]) for k in o0 if 0.012 <= k[1] <= 0.020 and 0.55 <= k[2] <= 0.95)
        print('TONE_OP 1 %s: cast x%.3f (the gate open x%.2f); below L 0.55 max |d| %.3g; coloured at L 0.80 x%.3f .. x%.3f; '
              'pale light tones x%.3f of the mid-tones' % (op, cast, cast_open, low, min(full), max(full), pale))
        check(cast <= 1.15, 'TONE_OP 1 %s: a white-balance cast is raised x%.3f' % (op, cast))
        check(cast_open >= 1.15, 'TONE_OP 1 %s: the bench no longer sees an ungated term raising a cast (x%.2f)' % (op, cast_open))
        check(low == 0.0, 'TONE_OP 1 %s: the term reaches below OKLab L 0.60 (max |d| %.3g)' % (op, low))
        # x1.25 in the scene; the display chroma after the operator and the gamut cut x1.14 .. x1.43 (ACES steepest)
        check(1.10 <= min(full) and max(full) <= 1.45, 'TONE_OP 1 %s: a coloured light tone x%.3f .. x%.3f, not the reference\'s x1.25'
              % (op, min(full), max(full)))
        if op == 'ACES':
            check(pale <= 1.05, 'TONE_OP 1 ACES: a pale light-tone colour x%.3f as saturated as the mid-tones' % pale)
    if REPORT:
        for h in HUES:
            for c in CHROMAS:
                print('hue %3d chroma %.3f  P341 x' % (h, c) + ' '.join('%4.2f' % (old[(h, c, L)][1] / max(v0[(h, c, L)][1], 1e-6)) for L in LS))
                print('                    P393 x' + ' '.join('%4.2f' % (cur[(h, c, L)][1] / max(v0[(h, c, L)][1], 1e-6)) for L in LS))
    if failures:
        sys.exit(1)
    print('PASS: the light-tone vibrance raises no white-balance cast, keeps pale light tones at the mid-tones\' saturation, keeps '
          'clearly coloured surfaces (skin, warm colours), the sky as P341 / P378, the bright pale sky >= 90 %, is no steeper in '
          'chroma than P341 by more than 10 %, under ACES / Uchimura the reference's light-tone term with the cast gate; its off-switch is P341 / P378 bit for bit')


if __name__ == '__main__':
    main()
