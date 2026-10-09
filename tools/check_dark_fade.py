#!/usr/bin/env python3
"""Dark fade of the denoise keeps dark saturated colours on signed hybrid input, on the SHIPPED shaders.

The denoise fades the colour of pixels darker than mean RGB 0.0008..0.003 to neutral, to hide the black-level tint (mostly
the per-pixel clip bias of the clamped merge RGB, gone on signed hybrid input). It also greyed dark saturated colours: the
teal curtain of the OPPO shot 2026-10-07 (mean RGB ~0.0011) kept 18-54 % of its colour. With darkChromaU / darkChroma =
(lo, hi) (ScamDenoise.darkChroma: 1.5e-4 / 4e-4 on signed hybrid input) a colour whose deviation from neutral |RGB - mean|
exceeds hi keeps it, one below lo still fades.
Checks, for scamdn/cbf (mode 2, the 1x output), scamdn/final2x (the Sabre 2x grid) and chromadn/apply (the NLM engine), on a
dark colour chart (colours and neutral grey with a small tint, mean RGB 2e-4 .. 5e-2, flat patches, no filtering):
- off ((0, 0), clamped input such as SCAM HDR): the fade of the luminance alone, as numpy (the former shaders' output);
- on: every patch whose colour deviation is at least hi keeps >= 99 % of it (the former fade kept < 50 % of these at mean
  RGB <= 0.0012); a neutral patch whose tint is below lo still fades to <= 2 %; patches at mean RGB >= 0.003 are the same
  bit for bit in both modes.
Noise floor (scamdn/cbf and scamdn/final2x darkNoiseU = (x, y), ScamDenoise.darkKeep): the keep floor becomes
max(lo, sqrt(x mean + y)) and the ramp ends at max(hi, 2 floor), so the colour noise the denoise leaves at high ISO fades
while a colour clearly above it stays. On a second chart (neutral tints 2-3e-4 and colours, dark means): every patch keeps
exactly the share the rule gives (numpy, within 2e-3); a tint of 2.5e-4 under a floor of 3e-4 fades to <= 2 % (the fixed
floor alone keeps >= 20 % of it); a colour of at least twice the floor keeps >= 99 %; darkNoiseU (0, 0) and a floor below
lo are bit-identical to the fixed floor; patches at mean RGB >= 0.003 are unchanged. chromadn/apply (the NLM engine) has no
noise floor.
Usage: check_dark_fade.py [--shaders DIR] [--report]
"""
from pathlib import Path
import re
import sys
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
if '--shaders' in sys.argv:
    SH = Path(sys.argv[sys.argv.index('--shaders') + 1])
REPORT = '--report' in sys.argv

try:
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
except Exception:
    ctx = moderngl.create_standalone_context(require=430)
VS = '#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'

FADE = (0.0008, 0.003)
KEEP = (1.5e-4, 4.0e-4)
KY = np.array([0.2126, 0.7152, 0.0721996])
KU = np.array([-0.162450244, -0.546494309, 0.708944715])
KV = np.array([0.999996748, -0.908302439, -0.091693333])
COLOURS = {'teal': (0.62, 1.0, 1.18), 'red': (2.2, 0.75, 0.7), 'blue': (0.7, 0.9, 2.2), 'green': (0.6, 1.25, 0.6),
           'purple': (1.4, 0.7, 1.7)}
LUMS = [2e-4, 4e-4, 8e-4, 12e-4, 20e-4, 40e-4, 1e-2, 5e-2]
P = 16


def source(name):
    out = ['#version 430']
    for line in (SH / name).read_text(encoding='utf-8').splitlines():
        m = re.match(r'\s*#import\s+(\S+)', line)
        if m:
            out.append((SH / 'utils' / ('import_' + m.group(1) + '.glsl')).read_text(encoding='utf-8'))
            continue
        out.append(line)
    return '\n'.join(out)


def texture(a, linear=False):
    a = np.ascontiguousarray(a, np.float32)
    t = ctx.texture((a.shape[1], a.shape[0]), 1 if a.ndim == 2 else a.shape[2], a.tobytes(), dtype='f4')
    f = moderngl.LINEAR if linear else moderngl.NEAREST
    t.filter = (f, f)
    t.repeat_x = t.repeat_y = False
    return t


def draw(name, size, textures, uniforms):
    p = ctx.program(vertex_shader=VS, fragment_shader=source(name))
    out = ctx.texture(size, 4, dtype='f4')
    fbo = ctx.framebuffer([out])
    fbo.use()
    ctx.viewport = (0, 0) + size
    for unit, (k, t) in enumerate(textures.items()):
        t.use(unit)
        if k in p:
            p[k].value = unit
    for k, v in uniforms.items():
        if k in p:
            p[k].value = v
    ctx.vertex_array(p, []).render(vertices=3)
    ctx.finish()
    data = np.frombuffer(out.read(), np.float32).astype(np.float64).reshape(size[1], size[0], 4)[..., :3]
    fbo.release(); out.release(); p.release()
    return data


def chart():
    """Rows: the colours, then neutral grey with a tint of |dev| 1e-4 (< lo); columns: LUMS (mean RGB)."""
    rows = [(k, np.array(v, float)) for k, v in COLOURS.items()] + [('neutral+tint', None)]
    img = np.zeros((len(rows) * P, len(LUMS) * P, 3))
    for j, (name, c) in enumerate(rows):
        for i, m in enumerate(LUMS):
            if c is None:
                tint = np.array([1.0, 0.0, -1.0]) / np.sqrt(2) * 1e-4
                v = m + tint
            else:
                v = c / c.mean() * m
            img[j * P:(j + 1) * P, i * P:(i + 1) * P] = v
    return rows, img


def old_fade(rgb):
    """The fade of the luminance alone (Y kept, U and V scaled), clamped at zero."""
    m = rgb.mean(-1)
    t = np.clip((m - FADE[0]) / (FADE[1] - FADE[0]), 0, 1)
    t = t * t * (3 - 2 * t)
    y = rgb @ KY
    return np.maximum(y[..., None] + t[..., None] * (rgb - y[..., None]), 0)


def run_cbf(img, keep, noise=None):
    yuv = np.stack([img @ KY, img @ KU, img @ KV], -1)
    h, w = img.shape[:2]
    t = texture(yuv)
    u = {'strideU': 1, 'filterU': 0, 'useDeltaU': 0, 'useMapU': 0, 'modeU': 2, 'fadeU': 1, 'darkFadeU': FADE, 'darkChromaU': keep}
    if noise is not None:
        u['darkNoiseU'] = noise
    out = draw('scamdn/cbf.glsl', (w, h), {'InputBuffer': t, 'DeltaUV': t, 'Orig': t, 'StrMap': t}, u)
    t.release()
    return out


def run_final2x(img, keep, noise=None):
    h, w = img.shape[:2]
    t = texture(img)
    d = texture(np.zeros(((h + 1) // 2, (w + 1) // 2, 3)))
    u = {'keepU': 1.0, 'fadeU': 1, 'darkFadeU': FADE, 'darkChromaU': keep}
    if noise is not None:
        u['darkNoiseU'] = noise
    out = draw('scamdn/final2x.glsl', (w, h), {'InputBuffer': t, 'Delta': d}, u)
    t.release(); d.release()
    return out


NOISE_LUMS = [2e-4, 5e-4, 8e-4, 15e-4, 30e-4, 1e-2]
NOISE_ROWS = [('tint 2.0e-4', 2.0e-4), ('tint 2.5e-4', 2.5e-4), ('tint 3.0e-4', 3.0e-4), ('teal 6e-4', 6e-4),
              ('red 9e-4', 9e-4), ('blue 15e-4', 15e-4)]


def noise_chart():
    """Rows: neutral grey with a tint / colours of a given deviation |RGB - mean|; columns: NOISE_LUMS (mean RGB)."""
    dirs = {'tint': np.array([1.0, 0.0, -1.0]), 'teal': np.array([-1.0, 0.3, 0.7]), 'red': np.array([1.0, -0.4, -0.6]),
            'blue': np.array([-0.5, -0.5, 1.0])}
    img = np.zeros((len(NOISE_ROWS) * P, len(NOISE_LUMS) * P, 3))
    for j, (name, d) in enumerate(NOISE_ROWS):
        u = dirs[name.split()[0]]
        u = (u - u.mean()) / np.linalg.norm(u - u.mean())
        for i, m in enumerate(NOISE_LUMS):
            img[j * P:(j + 1) * P, i * P:(i + 1) * P] = m + d * u
    return img


def smooth(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def expected_keep(m, dev, keep, noise):
    """The share of the colour the rule keeps (the colour scales with t: Y kept, U and V times t)."""
    lo = max(keep[0], np.sqrt(max(noise[0] * max(m, 0) + noise[1], 0)))
    return max(smooth(FADE[0], FADE[1], m), smooth(lo, max(keep[1], 2 * lo), dev))


def check_noise_floor(name, fn, fails):
    img = noise_chart()
    n_r, n_c = len(NOISE_ROWS), len(NOISE_LUMS)
    centre = lambda a, j, i: a[j * P + P // 2, i * P + P // 2]
    means = np.array([[centre(img, j, i).mean() for i in range(n_c)] for j in range(n_r)])
    dev_in = np.array([[np.linalg.norm(centre(img, j, i) - means[j, i]) for i in range(n_c)] for j in range(n_r)])
    # a patch with a channel below zero comes out clamped at zero (both modes): not a case of the rule
    valid = np.array([[centre(img, j, i).min() >= 0.0 for i in range(n_c)] for j in range(n_r)])

    def kept(out):
        return np.array([[np.linalg.norm(centre(out, j, i) - centre(out, j, i).mean()) for i in range(n_c)]
                         for j in range(n_r)]) / dev_in

    fixed = fn(img, KEEP)
    if not np.array_equal(fn(img, KEEP, (0.0, 0.0)), fixed):
        fails.append('%s: darkNoiseU (0, 0) is not the fixed floor bit for bit' % name)
    if not np.array_equal(fn(img, KEEP, (0.0, (0.5 * KEEP[0]) ** 2)), fixed):
        fails.append('%s: a noise floor below lo changed the output' % name)
    F = 3e-4
    pix_bright = np.repeat(np.repeat(means >= FADE[1], P, 0), P, 1)
    for noise in ((0.0, F * F), (0.5 * F * F / 8e-4, 0.5 * F * F)):  # constant floor; floor 3e-4 at mean 8e-4 (x term)
        on = fn(img, KEEP, noise)
        k = kept(on)
        exp = np.array([[expected_keep(means[j, i], dev_in[j, i], KEEP, noise) for i in range(n_c)] for j in range(n_r)])
        if REPORT:
            print('%s noise floor %s: kept %% (rule) at mean RGB %s' % (name, noise, ' '.join('%.4f' % m for m in NOISE_LUMS)))
            for j, (rn, _) in enumerate(NOISE_ROWS):
                print('   %-12s %s | rule %s' % (rn, ' '.join('%4.0f' % (100 * v) for v in k[j]),
                                                  ' '.join('%4.0f' % (100 * v) for v in exp[j])))
        err = np.abs(k - exp)[valid].max()
        if err > 2e-3:
            fails.append('%s noise floor %s: kept share off the rule by %.4f' % (name, noise, err))
        if not np.array_equal(on[pix_bright], fixed[pix_bright]):
            fails.append('%s noise floor: patches at mean RGB >= %.4f changed' % (name, FADE[1]))
        if noise[0] == 0.0:
            t25 = [n for n, _ in NOISE_ROWS].index('tint 2.5e-4')
            dark = (means[t25] <= FADE[0]) & valid[t25]
            k_fixed = kept(fixed)
            if not np.all(k[t25][dark] <= 0.02):
                fails.append('%s: a tint below the noise floor kept %.2f of it' % (name, k[t25][dark].max()))
            if not np.all(k_fixed[t25][dark] >= 0.2):
                fails.append('%s: the fixed floor no longer keeps the 2.5e-4 tint (the chart lost its case)' % name)
            strong = valid & (dev_in >= 2 * F)
            if not np.all(k[strong] >= 0.99):
                fails.append('%s: a colour of twice the noise floor faded (min kept %.2f)' % (name, k[strong].min()))


def run_apply(img, keep):
    h, w = img.shape[:2]
    half = img.reshape(h // 2, 2, w // 2, 2, 3).mean(axis=(1, 3))
    u = np.sqrt(np.maximum(img @ KY, 0) + 0.008)
    tex = {'InputBuffer': texture(img), 'Before': texture(half), 'After': texture(half), 'Noisy': texture(u),
           'Clean': texture(u), 'Coarse': texture(np.zeros((h // 4, w // 4)), linear=True)}
    out = draw('chromadn/apply.glsl', (w, h), tex,
               {'sigma': 0.0, 'pxStepU': 1, 'lowRatio': 2.0, 'grain': 1.0, 'offsetC': 0.008, 'lumaAmount': 0.0,
                'chromaAmount': 1.0, 'darkFade': FADE, 'darkChroma': keep, 'useEff': 0})
    for t in tex.values():
        t.release()
    return out


def patch_dev(img, rows):
    """Colour deviation |mean - neutral| of every patch centre: rows x LUMS."""
    out = np.zeros((len(rows), len(LUMS)))
    for j in range(len(rows)):
        for i in range(len(LUMS)):
            v = img[j * P + P // 2, i * P + P // 2]
            out[j, i] = np.linalg.norm(v - v.mean())
    return out


def main():
    rows, img = chart()
    dev_in = patch_dev(img, rows)
    means = np.array([[img[j * P + P // 2, i * P + P // 2].mean() for i in range(len(LUMS))] for j in range(len(rows))])
    fails = []
    for name, fn in (('scamdn/cbf', run_cbf), ('scamdn/final2x', run_final2x), ('chromadn/apply', run_apply)):
        off = fn(img, (0.0, 0.0))
        on = fn(img, KEEP)
        r_off = patch_dev(off, rows) / dev_in
        r_on = patch_dev(on, rows) / dev_in
        coloured = np.zeros_like(r_on, bool)
        coloured[:-1] = dev_in[:-1] >= KEEP[1]
        dark_coloured = coloured & (means <= 12e-4)
        tint_dark = np.zeros_like(r_on, bool)
        tint_dark[-1] = means[-1] <= FADE[0]
        bright = means >= FADE[1]
        if name != 'chromadn/apply':
            # apply sets the colour through its normalised half-resolution colour: the luminance fade is checked through
            # the patch ratios only
            err = np.abs(off - old_fade(img)).max() / img.max()
            if err > 1e-5:
                fails.append('%s off: not the fade of the luminance alone (rel error %.2e)' % (name, err))
        if REPORT:
            print('%s: colour kept at mean RGB %s' % (name, ' '.join('%.4f' % m for m in LUMS)))
            for j, (rn, _) in enumerate(rows):
                print('   %-13s off %s | on %s' % (rn, ' '.join('%4.0f' % (100 * v) for v in r_off[j]),
                                                  ' '.join('%4.0f' % (100 * v) for v in r_on[j])))
        if not np.all(r_on[coloured] >= 0.99):
            fails.append('%s: a dark colour stronger than the tint floor faded (min kept %.2f)' % (name, r_on[coloured].min()))
        if not np.all(r_off[dark_coloured] < 0.5):
            fails.append('%s off: the luminance fade no longer greys the darkest colours (the chart lost its case)' % name)
        if not np.all(r_on[tint_dark] <= 0.02):
            fails.append('%s: a neutral black with a tint below the floor kept its tint (%.2f)' % (name, r_on[tint_dark].max()))
        pix_bright = np.repeat(np.repeat(bright, P, 0), P, 1)
        if not np.array_equal(on[pix_bright], off[pix_bright]):
            fails.append('%s: patches at mean RGB >= %.4f differ between the modes' % (name, FADE[1]))
    for name, fn in (('scamdn/cbf', run_cbf), ('scamdn/final2x', run_final2x)):
        check_noise_floor(name, fn, fails)
    if fails:
        for f in fails:
            print('FAIL:', f)
        sys.exit(1)
    print('dark fade PASS: dark colours above the tint floor kept (cbf, final2x, apply), tinted black still neutral, '
          'bright pixels unchanged; noise floor (cbf, final2x) as the rule: noise-level tint fades, strong colours stay')


if __name__ == '__main__':
    main()
