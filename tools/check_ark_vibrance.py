#!/usr/bin/env python3
"""P419: why/combine ARK_VIBRANCE 1 - the reference app's vibrance in one gain, on the GPU.

With every strength 0 the define is bit for bit the shader without vibrance (ARK_VIBRANCE 0, vibU 0, vibHlU 0) under SKY 0 / 1 and
TONE_OP 0 / 1. Each term raises its own colours only: the pastel strength the low chroma, «saturated» the high one, the sky term
OKLab b < 0 only, the green term a < 0 only, the mid-tones OKLab L 0.35 .. 0.55, the shadows below 0.08 .. 0.25 and the light tones 0.80 ..
0.88 (TONE_OP 1 only; AgX keeps P341's form) and not a near-white's cast (P393's gate). The skin protection holds a skin-hue colour
at 0.30 of the gain (x (1 - 0.70)), protection 0 gives it the full gain; the skin saturation shift moves the skin hue only. Compiles
with COLOUR_LOOK and LIGHT_CHROMA.
"""
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import check_vibrance_hl as cvh  # noqa: E402
import check_why_pyramid as cwp  # noqa: E402

SRC = (cvh.SH / 'why/combine.glsl').read_text(encoding='utf-8').replace('\r\n', '\n')
P = 24
SKIN_HUE = 41.2
# (L, OKLab hue, chroma): scene OKLab before the macro contrast
PATCHES = [(L, h, c) for L in (0.05, 0.18, 0.45, 0.85) for h in (SKIN_HUE, 110.0, 150.0, 250.0, 330.0) for c in (0.003, 0.012, 0.05, 0.085, 0.12)]


def image():
    img = np.zeros((P, len(PATCHES) * P, 3), np.float32)
    for j, (L, h, c) in enumerate(PATCHES):
        img[:, j * P:(j + 1) * P] = cvh.from_oklab(L, c * np.cos(np.radians(h)), c * np.sin(np.radians(h)))
    return img


def chroma(rgb):
    out = {}
    for j, key in enumerate(PATCHES):
        lab = cvh.display_lab(rgb[8:P - 8, j * P + 8:(j + 1) * P - 8].reshape(-1, 3).mean(0))
        out[key] = float(np.hypot(lab[1], lab[2]))
    return out


class Bench(cvh.Bench):
    extra = ()

    def prog(self, name, defines=()):
        return super().prog(name, tuple(defines) + (self.extra if name == 'why/combine' else ()))


def main():
    failures = []

    def check(cond, msg):
        print(('ok   ' if cond else 'FAIL ') + msg)
        if not cond:
            failures.append(msg)

    assert SRC.count('#define ARK_VIBRANCE 0\n') == 1 and SRC.count('#define TONE_OP 0\n') == 1
    tone1 = SRC.replace('#define TONE_OP 0\n', '#define TONE_OP 1\n')
    aces = {'toneOpU': 1, 'acesU': (1.0, 2.61, 0.59, 0.05)}
    off = {'vibU': (0.0, 0.0, 0.0), 'vibHlU': 0.0}
    skin = (1.0, float(np.radians(SKIN_HUE)), 0.16, 0.0)
    zero = dict(off, vibArkU=(0.0, 0.0, 0.0, 0.0), vibSkinU=skin)
    runs = {
        'pastel': {'vibU': (0.4, 0.0, 0.0)}, 'saturated': {'vibArkU': (0.4, 0.0, 0.0, 0.0)},
        'sky': {'vibU': (0.0, 0.4, 0.0)}, 'green': {'vibU': (0.0, 0.0, 0.4)},
        'mid': {'vibArkU': (0.0, 0.0, 0.5, 0.0)}, 'shadows': {'vibArkU': (0.0, 0.0, 0.0, 0.5)},
        'mid0': {'vibArkU': (0.0, 0.0, 0.5, 0.0), 'vibSkinU': (0.0,) + skin[1:]},
        'skinsat': {'vibSkinU': skin[:3] + (0.4,)},
    }
    img = image()
    b = Bench()
    try:
        r0 = b.render(img, [('ref', None, 0, off), ('refsky', None, 1, off), ('ref1', tone1, 0, dict(off, **aces))])
        b.extra = (('ARK_VIBRANCE', '1'),)
        r = b.render(img, [('zero', None, 0, zero), ('zerosky', None, 1, zero), ('zero1', tone1, 0, dict(zero, **aces))]
                     + [(k, None, 0, dict(zero, **o)) for k, o in runs.items()]
                     + [('hl1', tone1, 0, dict(zero, vibArkU=(0.0, 0.25, 0.0, 0.0), **aces)), ('hl1z', tone1, 0, dict(zero, **aces)),
                        ('hl0', None, 0, dict(zero, vibArkU=(0.0, 0.25, 0.0, 0.0)))])
        # compiles with the other defines it meets in the app
        for extra in ((('COLOUR_LOOK', '1'),), (('LIGHT_CHROMA', '1'),), (('SKY', '1'), ('COLOUR_LOOK', '1'), ('LIGHT_CHROMA', '1'))):
            for text in (SRC, tone1):
                defs = [('HL_LANDING', '1'), ('ARK_VIBRANCE', '1')] + list(extra)
                b.gl.ctx.program(vertex_shader=cwp.VS, fragment_shader=cwp.read_program(text, defs)).release()
        print('compiles with COLOUR_LOOK / LIGHT_CHROMA / SKY, TONE_OP 0 / 1')
    finally:
        b.gl.ctx.release()

    for k, ref in (('zero', 'ref'), ('zerosky', 'refsky'), ('zero1', 'ref1')):
        check(np.array_equal(r[k], r0[ref]), 'every strength 0 = no vibrance bit for bit (%s): max |d| %.3g' % (k, np.abs(r[k] - r0[ref]).max()))
    c0 = chroma(r['zero'])
    g = {k: {p: v / max(c0[p], 1e-3) for p, v in chroma(r[k]).items()} for k in runs}

    def gain(run, L, h, c):
        return g[run][(L, h, c)]

    # the pastel strength: low chroma, gone at 0.09+; «saturated»: the reverse (non-skin hue 110, mid L)
    check(gain('pastel', 0.45, 110.0, 0.012) > 1.25 and gain('pastel', 0.45, 110.0, 0.12) < 1.02,
          'pastel: chroma 0.012 x%.3f, 0.12 x%.3f' % (gain('pastel', 0.45, 110.0, 0.012), gain('pastel', 0.45, 110.0, 0.12)))
    # (at L 0.85: a chroma 0.085 at L 0.45 is at the display's gamut edge, the bench's display chroma no longer sees a gain there)
    check(gain('saturated', 0.85, 330.0, 0.085) > 1.25 and gain('saturated', 0.85, 330.0, 0.012) < 1.02,
          'saturated: chroma 0.085 x%.3f, 0.012 x%.3f' % (gain('saturated', 0.85, 330.0, 0.085), gain('saturated', 0.85, 330.0, 0.012)))
    # sky: b < 0 (hue 250, 330); not b > 0 (110). green: a < 0 (150, 250); not a > 0 (330, 41)
    check(gain('sky', 0.45, 250.0, 0.05) > 1.2 and gain('sky', 0.45, 330.0, 0.05) > 1.1 and abs(gain('sky', 0.45, 110.0, 0.05) - 1) < 0.01,
          'sky: hue 250 x%.3f, 330 x%.3f, 110 x%.3f' % (gain('sky', 0.45, 250.0, 0.05), gain('sky', 0.45, 330.0, 0.05),
                                                     gain('sky', 0.45, 110.0, 0.05)))
    check(gain('green', 0.45, 150.0, 0.05) > 1.2 and abs(gain('green', 0.45, 330.0, 0.05) - 1) < 0.01
          and abs(gain('green', 0.45, SKIN_HUE, 0.05) - 1) < 0.01,
          'green: hue 150 x%.3f, 330 x%.3f, skin x%.3f' % (gain('green', 0.45, 150.0, 0.05), gain('green', 0.45, 330.0, 0.05),
                                                        gain('green', 0.45, SKIN_HUE, 0.05)))
    # the tonal masks
    check(gain('mid', 0.45, 110.0, 0.05) > 1.3 and abs(gain('mid', 0.18, 110.0, 0.05) - 1) < 0.02 and abs(gain('mid', 0.85, 110.0, 0.05) - 1) < 0.02,
          'mid-tones: L 0.45 x%.3f, 0.18 x%.3f, 0.85 x%.3f' % (gain('mid', 0.45, 110.0, 0.05), gain('mid', 0.18, 110.0, 0.05),
                                                            gain('mid', 0.85, 110.0, 0.05)))
    check(gain('shadows', 0.18, 250.0, 0.05) > 1.2 and abs(gain('shadows', 0.45, 250.0, 0.05) - 1) < 0.02,
          'shadows: L 0.18 x%.3f, 0.45 x%.3f' % (gain('shadows', 0.18, 250.0, 0.05), gain('shadows', 0.45, 250.0, 0.05)))
    # the skin protection: x (1 - 0.70) of the gain on the skin hue, the full gain without it
    ex, ex_skin, ex_skin0 = (gain('mid', 0.45, 110.0, 0.05) - 1, gain('mid', 0.45, SKIN_HUE, 0.05) - 1, gain('mid0', 0.45, SKIN_HUE, 0.05) - 1)
    check(0.2 <= ex_skin / ex <= 0.42 and abs(ex_skin0 / ex - 1) < 0.08,
          'skin protection: the skin\'s share of the gain %.2f (protection 1), %.2f (0)' % (ex_skin / ex, ex_skin0 / ex))
    check(gain('skinsat', 0.45, SKIN_HUE, 0.05) > 1.2 and abs(gain('skinsat', 0.45, 150.0, 0.05) - 1) < 0.01,
          'skin saturation shift: skin x%.3f, hue 150 x%.3f' % (gain('skinsat', 0.45, SKIN_HUE, 0.05), gain('skinsat', 0.45, 150.0, 0.05)))
    # light tones: TONE_OP 1 only, L 0.85 not L 0.45, not a near-white's cast
    ch1, ch1z, ch0 = chroma(r['hl1']), chroma(r['hl1z']), chroma(r['hl0'])
    hl = ch1[(0.85, 110.0, 0.05)] / ch1z[(0.85, 110.0, 0.05)]
    cast = ch1[(0.85, 110.0, 0.003)] / max(ch1z[(0.85, 110.0, 0.003)], 1e-3)
    mid = ch1[(0.45, 110.0, 0.05)] / ch1z[(0.45, 110.0, 0.05)]
    check(1.12 <= hl <= 1.35 and cast < 1.05 and abs(mid - 1) < 0.01,
          'light tones (TONE_OP 1): L 0.85 x%.3f, a near-white\'s cast x%.3f, L 0.45 x%.3f' % (hl, cast, mid))
    check(np.array_equal(r['hl0'], r['zero']), 'light tones under AgX (TONE_OP 0): no term in the gain (P341\'s form after it)')
    if failures:
        print('%d FAILED' % len(failures))
        return 1
    print('check_ark_vibrance: all passed')
    return 0


if __name__ == '__main__':
    sys.exit(main())
