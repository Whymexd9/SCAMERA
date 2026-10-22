#!/usr/bin/env python3
"""P134 «Цвет»: why/combine.glsl with COLOUR_LOOK 1 on the GPU (moderngl).

    python3 tools/check_colour_look.py

- every colour setting at its default (COLOUR_LOOK 1, neutral uniforms) renders bit for bit what COLOUR_LOOK 0 renders;
- each block does what its row says: monochrome (neutral / filters), skin protection of vibrance and of the AgX saturation
  above 1, the mixer (hue,
  saturation, lightness of one band only), the grading zones, the hue-lock modes, the gamut compression, the skin hue.
The white-balance rows are a CPU matrix (processing.color.ColourLook, ColourLookTest)."""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_colour_output import SH, expand_imports, oklab, set_define  # noqa: E402

failures = []


def check(cond, msg):
    if not cond:
        failures.append(msg)
        print('FAIL: ' + msg)


def hue(lin):
    lab = oklab(np.maximum(lin, 0))
    return np.degrees(np.arctan2(lab[:, 2], lab[:, 1])) % 360, np.hypot(lab[:, 1], lab[:, 2]), lab[:, 0]


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: colour look check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    src = expand_imports((SH / 'why/combine.glsl').read_text(encoding='utf-8'))
    rng = np.random.default_rng(134)
    # a hue circle of moderately saturated colours at three exposures, skin tones, greys and colours outside sRGB
    ang = np.radians(np.arange(0, 360, 4))
    lab = np.stack([np.full_like(ang, 0.6), 0.09 * np.cos(ang), 0.09 * np.sin(ang)], 1)
    m1 = np.array([[1, 0.3963377774, 0.2158037573], [1, -0.1055613458, -0.0638541728], [1, -0.0894841775, -1.2914855480]])
    lms = (lab @ m1.T) ** 3
    m2 = np.array([[4.0767416621, -3.3077115913, 0.2309699292], [-1.2684380046, 2.6097574011, -0.3413193965],
                   [-0.0041960863, -0.7034186147, 1.7076147010]])
    circle = np.maximum(lms @ m2.T, 0)
    circle = np.concatenate([circle * s for s in (0.08, 0.25, 0.6)])
    skin = np.array([[0.56, 0.36, 0.26], [0.45, 0.28, 0.19], [0.70, 0.50, 0.40], [0.38, 0.22, 0.15], [0.62, 0.42, 0.33]])
    skin = np.concatenate([skin * s for s in (0.15, 0.3, 0.5)])
    greys = np.repeat(np.geomspace(0.003, 0.9, 40)[:, None], 3, 1)
    wide = np.array([[1, -0.05, -0.05], [-0.06, 1, -0.04], [-0.03, -0.04, 1], [1, 0.6, -0.08], [-0.1, 0.8, 1]]) * 0.35
    colours = np.concatenate([circle, skin, greys, wide]).astype(np.float32)
    n = len(colours)
    k_circle, k_skin, k_grey = len(circle), len(skin), len(greys)
    W = 64
    H = -(-n // W)
    img = np.zeros((H, W, 4), np.float32)
    img.reshape(-1, 4)[:n, :3] = colours
    lum = np.array([0.2126, 0.7152, 0.0722])
    y = np.maximum(np.maximum(colours @ lum, colours.max(1) * 0.5), 1e-4)
    grid = np.linspace(0, 2.51 / 2.43 - 0.0101, 20000)
    b, e = 0.0375, 0.132
    A, B, C = 2.43 * grid - 2.51, 0.59 * grid - b, e * grid
    table = np.maximum((-B - np.sqrt(np.maximum(B * B - 4 * A * C, 0))) / (2 * A - 1e-6) / 1.5, 0)
    fused = np.zeros((H, W, 4), np.float32)
    fused.reshape(-1, 4)[:n, 0] = np.interp(np.minimum(y, table.max()), table, grid) ** (1 / 2.2)

    def tex(a):
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t
    ones = tex(np.ones((1, 1, 4), np.float32))
    t_img, t_fused = tex(img), tex(fused)
    vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
    neutral = {'skinU': (0.0, 0.0, 0.0), 'vibLU': (0.0, 0.0), 'mixOnU': 0, 'monoU': (0.0, 0.0, 0.0), 'gamutU': 0.0,
               'hueLockU': 0, 'gradeAU': (0.0, 0.0, 0.0, 0.0), 'gradeBU': (0.0, 0.0, 0.0, 0.5)}

    def render(look=None, mix=None, vib=(0.0, 0.4, 0.2), p3='0', agx_sat=1.0):
        shader = set_define(set_define(src, 'P3_OUT', p3), 'COLOUR_LOOK', '1' if look is not None else '0')
        prog = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + shader)
        out = ctx.texture((W, H), 4, dtype='f4')
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, W, H)
        units = {'InputBuffer': t_img, 'GainMap': ones, 'WhyLow': t_img, 'WhyColour': t_img, 'WhyFused': t_fused,
                 'WhyDetailRef': t_img, 'WhyLumaS': t_img}
        for i, (k, t) in enumerate(units.items()):
            if k in prog:
                t.use(i)
                prog[k].value = i
        eye = tuple(np.eye(3).flatten())
        vals = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0,
                'fU': 1, 'colourFU': 1, 'aeU': 1.0, 'clipU': 1.0, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.1,
                'vibU': vib, 'detailGainU': 0.0, 'deltaChromaU': 1.0, 'filmToeU': 0.1, 'ditherU': 0,
                'agxAU': (2.7, 1.35, 1.6, agx_sat), 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'headroomU': 0, 'hlWhiteU': 0.15}
        if look is not None:
            vals.update(neutral)
            vals.update(look)
        for k, v in vals.items():
            if k in prog:
                prog[k].value = v
        if look is not None and 'mixU' in prog:
            prog['mixU'].value = tuple(mix if mix is not None else [0.0] * 24)
        ctx.vertex_array(prog, []).render(vertices=3)
        res = np.frombuffer(out.read(), np.float32).reshape(H, W, 4)[:, :, :3].reshape(-1, 3)[:n]
        fb.release()
        out.release()
        prog.release()
        return np.maximum(res, 0) ** 2.2   # display-encoded -> linear

    base = render()
    same = render({})
    check(np.array_equal(base, same), 'COLOUR_LOOK 1 at the defaults is not COLOUR_LOOK 0 bit for bit')
    p3_same = np.array_equal(render(p3='1'), render({}, p3='1'))
    check(p3_same, 'COLOUR_LOOK 1 at the defaults changes the Display P3 render')
    print('defaults: COLOUR_LOOK 1 = COLOUR_LOOK 0 bit for bit (sRGB %s, P3 %s, %d colours)' % (np.array_equal(base, same), p3_same, n))

    h0, c0, l0 = hue(base)
    # monochrome
    mono = render({'monoU': (1.0, 1.0, 1.0)})
    spread = (mono.max(1) - mono.min(1)).max()
    print('monochrome neutral: max channel spread %.5f' % spread)
    check(spread < 0.004, 'neutral monochrome is not grey (spread %.4f)' % spread)
    red = render({'monoU': (1.0, 0.2, 0.02)})
    hb = h0[:k_circle]
    blues = (hb > 240) & (hb < 290)
    reds = (hb > 10) & (hb < 45)
    yb, yr = red[:k_circle].mean(1), mono[:k_circle].mean(1)
    print('red filter: blues %.3fx, reds %.3fx the neutral grey' % ((yb[blues] / yr[blues]).mean(), (yb[reds] / yr[reds]).mean()))
    check((yb[blues] / yr[blues]).mean() < 0.85 and (yb[reds] / yr[reds]).mean() > 1.1, 'the red filter does not darken blue / lighten red')
    # skin protection of vibrance
    vib_on = render({}, vib=(1.0, 0.0, 0.0))
    vib_prot = render({'skinU': (1.0, 0.0, 0.0)}, vib=(1.0, 0.0, 0.0))
    s0 = slice(k_circle, k_circle + k_skin)
    _, cs_off, _ = hue(vib_on[s0])
    _, cs_on, _ = hue(vib_prot[s0])
    _, cs_base, _ = hue(render({}, vib=(0.0, 0.0, 0.0))[s0])
    gain_off, gain_on = (cs_off / cs_base).mean(), (cs_on / cs_base).mean()
    print('skin under vibrance 1: chroma x%.3f unprotected, x%.3f protected' % (gain_off, gain_on))
    check(gain_on < 1 + (gain_off - 1) * 0.5, 'skin protection does not hold back the vibrance on skin')
    # skin protection of the AgX saturation above 1 (the «Custom» AgX look): skin held back, the hue circle untouched
    sat_off = render({}, vib=(0.0, 0.0, 0.0), agx_sat=1.5)
    sat_prot = render({'skinU': (1.0, 0.0, 0.0)}, vib=(0.0, 0.0, 0.0), agx_sat=1.5)
    _, ca_off, _ = hue(sat_off[s0])
    _, ca_on, _ = hue(sat_prot[s0])
    g_off, g_on = (ca_off / cs_base).mean(), (ca_on / cs_base).mean()
    print('skin under AgX saturation 1.5: chroma x%.3f unprotected, x%.3f protected' % (g_off, g_on))
    check(g_off > 1.1, 'the test is moot: the AgX saturation does not raise the skin chroma')
    check(g_on < 1 + (g_off - 1) * 0.5, 'skin protection does not hold back the AgX saturation on skin')
    _, cb_off, _ = hue(sat_off[:k_circle])
    _, cb_on, _ = hue(sat_prot[:k_circle])
    far = np.abs(((h0[:k_circle] - 52 + 180) % 360) - 180) > 60
    keep = np.abs(cb_on[far] / np.maximum(cb_off[far], 1e-6) - 1).max()
    print('  colours far from skin: max chroma change by the protection %.4f' % keep)
    check(keep < 0.01, 'the skin protection of the AgX saturation reaches colours far from skin (%.4f)' % keep)
    lower = np.abs(render({'skinU': (1.0, 0.0, 0.0)}, vib=(0.0, 0.0, 0.0), agx_sat=0.7)
                   - render({}, vib=(0.0, 0.0, 0.0), agx_sat=0.7)).max()
    print('  AgX saturation 0.7: max |protected - unprotected| %.2e' % lower)
    check(lower < 1e-6, 'the skin protection changes a lowered AgX saturation (%.2e)' % lower)
    # mixer: blue saturation -100 -> blues lose chroma, reds keep theirs
    mix = [0.0] * 24
    mix[5 * 3 + 1] = -1.0
    mixed = render({'mixOnU': 1}, mix=mix)
    hm, cm, _ = hue(mixed[:k_circle])
    cb = c0[:k_circle]
    print('mixer blue saturation -100: blues chroma x%.2f, reds x%.3f' % ((cm[blues] / cb[blues]).mean(), (cm[reds] / cb[reds]).mean()))
    check((cm[blues] / cb[blues]).mean() < 0.3 and abs((cm[reds] / cb[reds]).mean() - 1) < 0.02, 'the mixer is not band-limited')
    mix = [0.0] * 24
    mix[3 * 3] = 1.0   # green hue +100
    turned = render({'mixOnU': 1}, mix=mix)
    ht, _, _ = hue(turned[:k_circle])
    greens = (hb > 130) & (hb < 156)
    dh = ((ht - hb + 180) % 360 - 180)[greens]
    print('mixer green hue +100: hue %+.1f deg (greens)' % dh.mean())
    check(dh.mean() > 8, 'the mixer hue does not turn the greens')
    mix = [0.0] * 24
    mix[1 * 3 + 2] = 1.0   # orange lightness +100
    lit = render({'mixOnU': 1}, mix=mix)
    _, _, ll = hue(lit[:k_circle])
    oranges = (hb > 45) & (hb < 62)
    print('mixer orange lightness +100: L %+.3f (oranges), %+.4f (blues)' % ((ll - l0[:k_circle])[oranges].mean(), (ll - l0[:k_circle])[blues].mean()))
    check((ll - l0[:k_circle])[oranges].mean() > 0.02 and abs((ll - l0[:k_circle])[blues].mean()) < 0.002, 'the mixer lightness is not band-limited')
    # grading: warm highlights, teal shadows on the greys
    g = slice(k_circle + k_skin, k_circle + k_skin + k_grey)
    warm, teal = 0.06 * np.array([np.cos(np.radians(70)), np.sin(np.radians(70))]), 0.06 * np.array([np.cos(np.radians(200)), np.sin(np.radians(200))])
    graded = render({'gradeAU': (teal[0], teal[1], 0.0, 0.0), 'gradeBU': (warm[0], warm[1], 0.0, 0.5)})
    lg = oklab(np.maximum(graded[g], 0))
    lg0 = oklab(np.maximum(base[g], 0))
    bright, dark = lg0[:, 0] > 0.8, lg0[:, 0] < 0.3
    print('grading: bright greys b %+.3f, dark greys a %+.3f' % (lg[bright, 2].mean(), lg[dark, 1].mean()))
    check(lg[bright, 2].mean() > 0.01 and lg[dark, 1].mean() < -0.01, 'the grading zones do not tint highlights / shadows')
    # hue lock modes
    off = render({'hueLockU': 2})
    blue_only = render({'hueLockU': 1})
    ho, _, _ = hue(off[:k_circle])
    hbo, _, _ = hue(blue_only[:k_circle])
    d_off = np.abs((ho - h0[:k_circle] + 180) % 360 - 180)
    d_blue = np.abs((hbo - h0[:k_circle] + 180) % 360 - 180)
    print('hue lock off: hue moves up to %.1f deg; blues only: up to %.1f deg (blues %.2f)' % (d_off.max(), d_blue.max(), d_blue[blues].max()))
    check(d_off.max() > 0.5 and d_blue[blues].max() < d_off[blues].max() + 1e-6, 'the hue-lock modes do not differ as they should')
    # gamut compression: the wide colours keep their hue better
    w = slice(n - len(wide), n)
    comp = render({'gamutU': 1.0})
    calm = slice(k_circle, k_circle + k_skin + k_grey)   # skin and greys: far from the gamut boundary
    print('gamut compression: wide colours changed by up to %.4f, skin / greys by %.6f (linear)'
          % (np.abs(comp[w] - base[w]).max(), np.abs(comp[calm] - base[calm]).max()))
    check(np.abs(comp[w] - base[w]).max() > 1e-4 and np.abs(comp[calm] - base[calm]).max() < 1e-5,
          'the gamut compression does not act near the gamut boundary only')
    # skin hue: +100 turns skin towards yellow (higher OKLab hue)
    turned = render({'skinU': (0.0, 1.0, 0.0)})
    hs, _, _ = hue(turned[s0])
    hs0, _, _ = hue(base[s0])
    print('skin hue +100: %+.1f deg' % ((hs - hs0 + 180) % 360 - 180).mean())
    check(((hs - hs0 + 180) % 360 - 180).mean() > 2, 'the skin hue does not turn skin')
    ctx.release()
    if failures:
        print('colour look check FAIL (%d)' % len(failures))
        return 1
    print('colour look check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
