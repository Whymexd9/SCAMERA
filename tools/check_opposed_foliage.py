#!/usr/bin/env python3
"""P393 (owner, 2026-10-29: sunlit yellow birches pale peach beside ArkCam): ScamRgb's «Inpaint opposed» branch (scamhdr/scamrgb
oppU 1, the pixels without ultrashort content: a single frame, «Движение», a refused Bento) on textured sunlit foliage, with and
without oppLocalU (a clipped channel also takes the local light's colour, hlrecovery/chanprep + reduce x2 as ScamRgb runs them).

RawTherapee's reference rebuilds a clipped channel from the other two with ONE additive chrominance for the frame (here numpy's,
tools/check_highlight_opposed.py's reference = the GPU's within 1e-3); on leaves with dark gaps it comes out strongly negative for
G, so yellow leaves turned orange and then salmon / pink as G stayed at its clip. Scene: the owner's birch leaves (white-balanced
camera RGB 1 : 0.667 : 0.43 of 03-ScamRgb, the shot's white point 0.463 / 1 / 0.648 and colour matrix), a leaf texture with 15 %
dark gaps, exposed from below G's clip to past R's; the worker's flags (bits 0-2 per clipped channel, bit 3).
Checks (oppLocalU 1):
- the clipped leaves' OKLab hue (linear sRGB through the shot's matrix) within 6 deg of their true colour at every exposure, their
  OKLab C / L within 0.6 .. 1.4 x of it (oppLocalU 0: up to 57 deg off - reported);
- unclipped pixels and every pixel without a clipped channel bit-identical to oppLocalU 0; the rule only raises a channel;
- lights stay as they were made (tools/check_highlight_opposed.py's scenes: a neutral sun, one in a warm flare, a red sunset rim and an
  orange lamp, the lamp rows at 16 px blocks): the cores with every channel at its clip and the sun's clip band neutral (chroma < 0.005),
  a coloured light keeps its hue (3 deg) and chroma (>= 0.95 x median) against oppLocalU 0;
- P393 review (B2: the local light is the bright unclipped pixels around, which need not be the surface): yellow leaves over a blue
  sky (the light is the sky's) with R and G clipped keep RT's hue (median within 1 deg of it; the light's blue made them lime, OKLab
  hue 130 for 69) and never turn green (p95 of the hue past the true one <= 15 deg); the leaves' clipped gradient beside green,
  sky-blue, red, white and yellow neighbours (smooth and textured: the light a mix of both surfaces): p95 of the hue past the true one
  at most 6 deg over RT's (or over 0; the light at the neutral prior set G = R: +48), the median |hue off| at most RT's + 3, and along
  a smooth gradient no step between neighbours over RT's largest + 0.008 OKLab.
Usage: check_opposed_foliage.py [--report]
"""
from pathlib import Path
import sys

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_highlight_opposed as cho  # noqa: E402

REPORT = '--report' in sys.argv
FAILURES = []
WP = np.array([0.46289062, 1.0, 0.6484375])            # the birch shot's neutral (camera RGB of white)
CCM = np.array([[1.9921875, -0.9765625, -0.015625], [-0.1875, 1.6796875, -0.4921875], [0.03125, -0.7265625, 1.6953125]])
LEAF = np.array([1.0, 0.667, 0.43])                     # the leaves' white-balanced camera RGB (03-ScamRgb, L* 65 - 85)


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def scamrgb(raw, flags, wp, chroma, local):
    """chanprep (8 px blocks), reduce x2, scamrgb with the flags, oppU 1 and the given chrominance, as ScamRgb.Run."""
    h, w = flags.shape
    inp = cho.texture(np.dstack([raw, np.ones((h, w, 1))]).astype(np.float32))
    gm = cho.texture(np.ones((1, 1, 4), np.float16), 'f2', linear=True)
    ft = cho.texture(flags.astype(np.uint8), 'f1')
    common = {'whitePoint': tuple(map(float, wp)), 'inverseSize': (1.0 / w, 1.0 / h), 'cropOffset': (0, 0), 'clipLoU': (1.0, 1.0, 1.0),
              'clipHiU': (0.0, 0.0, 0.0), 'clipFlagsU': 1, 'clipHiUnflaggedU': 0, 'usWhiteU': 0.0}
    block = 8
    near = ((w + block - 1) // block, (h + block - 1) // block)
    c8 = cho.pass_to(cho.program('hlrecovery/chanprep.glsl'), near, {'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft},
                     dict(common, blockU=block, pxStepU=1), linear=True)
    wide = ((near[0] + 3) // 4, (near[1] + 3) // 4)
    c32 = cho.pass_to(cho.program('hlrecovery/reduce.glsl'), wide, {'InputBuffer': c8}, {}, linear=True)
    c128 = cho.pass_to(cho.program('hlrecovery/reduce.glsl'), ((wide[0] + 3) // 4, (wide[1] + 3) // 4), {'InputBuffer': c32}, {}, linear=True)
    out = cho.pass_to(cho.program('scamhdr/scamrgb.glsl'), (w, h),
                      {'InputBuffer': inp, 'GainMap': gm, 'ClipFlags': ft, 'Chroma8': c8, 'Chroma32': c32, 'Chroma128': c128},
                      dict(common, hlModeU=1, hlStrengthU=1.0, chromaU=1, blockU=block, chromaLimitU=0.35, defringeU=-1.0, signedU=1,
                           oppU=1, oppChromaU=tuple(map(float, chroma)), oppLocalU=int(local)), dtype='f4')
    res = cho.read_tex(out)
    for t in (inp, gm, ft, c8, c32, c128, out):
        t.release()
    return res


def flagged(true, wp):
    raw_true = true * wp
    raw = np.minimum(raw_true, 1.0).astype(np.float32)
    flags = np.zeros(true.shape[:2], np.uint8)
    for c in range(3):
        flags |= ((raw_true[..., c] >= 1.0).astype(np.uint8) << c)
    flags[flags != 0] |= 8
    return raw, flags


def oklab(lin):
    o = np.cbrt(np.maximum(np.asarray(lin, np.float64) @ cho.M1.T, 0)) @ cho.M2.T
    return o[..., 0], np.hypot(o[..., 1], o[..., 2]), np.degrees(np.arctan2(o[..., 2], o[..., 1])) % 360


def foliage():
    rng = np.random.default_rng(1)
    n = 128
    tex = np.exp(0.35 * rng.standard_normal((n, n)))
    tex[rng.random((n, n)) < 0.15] *= 0.1
    L0, C0, h0 = oklab(LEAF @ CCM.T)
    rel0 = C0 / L0
    for k in (1.2, 1.6, 2.0, 2.4, 3.0):
        true = LEAF[None, None, :] * (k * tex)[..., None]
        raw, flags = flagged(true, WP)
        chroma = cho.reference(raw.astype(np.float64), flags, WP, band=True)[0]
        rgb0 = scamrgb(raw, flags, WP, chroma, 0)
        rgb1 = scamrgb(raw, flags, WP, chroma, 1)
        clip = (flags & 7) != 0
        check(np.array_equal(rgb0[~clip], rgb1[~clip]), 'k %.1f: a pixel without a clipped channel changed' % k)
        check(np.all(rgb1[clip] >= rgb0[clip] - 1e-6), 'k %.1f: the local light lowered a channel' % k)
        sel = clip & (tex > 0.8) & (tex < 1.25)
        if sel.sum() < 50:
            if REPORT:
                print('k %.1f: %d leaves clipped' % (k, sel.sum()))
            continue
        res = []
        for tag, rgb in (('RT', rgb0), ('local', rgb1)):
            L, C, h = oklab(rgb[sel] @ CCM.T)
            dh = np.median(np.abs((h - h0 + 180) % 360 - 180))
            rel = np.median(C / np.maximum(L, 1e-6)) / rel0
            res.append((tag, dh, rel))
        (_, dh0, rel0_), (_, dh1, rel1) = res
        print('foliage k %.1f (G raw %.2f, R raw %.2f of the clip, %d clipped leaves): hue off %.1f -> %.1f deg, C / L x%.2f -> x%.2f'
              % (k, LEAF[1] * WP[1] * k, LEAF[0] * WP[0] * k, sel.sum(), dh0, dh1, rel0_, rel1))
        check(dh1 <= 6.0, 'k %.1f: the clipped leaves %.1f deg off their hue' % (k, dh1))
        check(0.6 <= rel1 <= 1.4, 'k %.1f: the clipped leaves C / L x%.2f of theirs' % (k, rel1))


def signed_off(rgb):
    """OKLab hue of white-balanced camera RGB (through the shot's matrix) past the leaves' true hue, deg (-180 .. 180)."""
    _, _, h = oklab(rgb @ CCM.T)
    _, _, h0 = oklab(LEAF @ CCM.T)
    return (h - h0 + 180) % 360 - 180


def leaves_on_sky():
    """The reviewer's scene: 3 - 6 px leaves over a bright blue sky (the light round a clipped leaf is the sky's)."""
    rng = np.random.default_rng(3)
    n = 128
    sky = np.array([0.55, 0.8, 1.25])
    yy, xx = np.mgrid[0:n, 0:n]
    leaf = np.zeros((n, n), bool)
    for _ in range(60):
        cy, cx, rr = rng.integers(0, n), rng.integers(0, n), rng.integers(3, 7)
        leaf |= (yy - cy) ** 2 + (xx - cx) ** 2 <= rr * rr
    for k in (2.4, 3.2):
        true = np.where(leaf[..., None], LEAF[None, None, :] * k, sky[None, None, :] * 0.6)
        true = true * np.exp(0.05 * rng.standard_normal((n, n)))[..., None]
        raw, flags = flagged(true, WP)
        chroma = cho.reference(raw.astype(np.float64), flags, WP, band=True)[0]
        rgb0 = scamrgb(raw, flags, WP, chroma, 0)
        rgb1 = scamrgb(raw, flags, WP, chroma, 1)
        sel = leaf & ((flags & 7) == 3)
        if sel.sum() < 50:
            continue
        o0, o1 = signed_off(rgb0[sel]), signed_off(rgb1[sel])
        print('leaves on a blue sky k %.1f (R and G clipped, %d px): hue past the true one median %+.1f (RT %+.1f), p95 %+.1f (RT %+.1f)'
              % (k, sel.sum(), np.median(o1), np.median(o0), np.percentile(o1, 95), np.percentile(o0, 95)))
        check(abs(np.median(o1) - np.median(o0)) <= 1.0, 'leaves on a blue sky k %.1f: the hue %+.1f against RT %+.1f (the sky\'s light taken)'
              % (k, np.median(o1), np.median(o0)))
        check(np.percentile(o1, 95) <= 15.0, 'leaves on a blue sky k %.1f: p95 of the hue %+.1f deg past the true one (green)' % (k, np.percentile(o1, 95)))


def mixed():
    """The leaves' clipped gradient (exposure 1.2 .. 3.2 x along x) beside another surface (rows 0 - 19): the light mixes both."""
    n = 256
    k = np.linspace(1.2, 3.2, n)
    for name, nb in (('green leaves', (0.55, 1.0, 0.35)), ('sky blue', (0.55, 0.8, 1.25)), ('red', (1.0, 0.3, 0.25)), ('white', (1.0, 1.0, 1.0)),
                     ('yellow', (1.0, 0.85, 0.3))):
        for tex in (False, True):
            true = LEAF[None, None, :] * k[None, :, None] * np.ones((64, 1, 1))
            true[:20] = np.asarray(nb) * k[None, :, None] * 0.7
            if tex:
                true = true * np.exp(0.2 * np.random.default_rng(1).standard_normal((64, n)))[..., None]
            raw, flags = flagged(true, WP)
            chroma = cho.reference(raw.astype(np.float64), flags, WP, band=True)[0]
            rgb0 = scamrgb(raw, flags, WP, chroma, 0)
            rgb1 = scamrgb(raw, flags, WP, chroma, 1)
            sel = np.zeros(flags.shape, bool)
            sel[21:] = True
            sel &= (flags & 7) != 0
            o0, o1 = signed_off(rgb0[sel]), signed_off(rgb1[sel])
            p0, p1 = np.percentile(o0, 95), np.percentile(o1, 95)
            m0, m1 = np.median(np.abs(o0)), np.median(np.abs(o1))
            tag = '%s, %s' % (name, 'textured' if tex else 'smooth')
            line = 'beside %s: hue past the true one p95 %+.1f (RT %+.1f), median |off| %.1f (RT %.1f)' % (tag, p1, p0, m1, m0)
            check(p1 <= max(p0, 0.0) + 6.0, 'beside %s: p95 of the hue %+.1f deg past the true one (RT %+.1f)' % (tag, p1, p0))
            check(m1 <= m0 + 3.0, 'beside %s: the median hue %.1f deg off (RT %.1f)' % (tag, m1, m0))
            if not tex:
                def steps(rgb):
                    L, C, h = oklab(rgb[22:] @ CCM.T)
                    lab = np.stack([L, C * np.cos(np.radians(h)), C * np.sin(np.radians(h))], -1)
                    return np.linalg.norm(np.diff(lab, axis=1), axis=-1).max()
                s0, s1 = steps(rgb0), steps(rgb1)
                line += ', largest step %.4f (RT %.4f)' % (s1, s0)
                check(s1 <= s0 + 0.008, 'beside %s: a step of %.4f OKLab between neighbours (RT %.4f)' % (tag, s1, s0))
            print(line)


def lights():
    cases = [('neutral sun', cho.sun_scene('neutral')), ('sun in a warm flare', cho.sun_scene('flare')),
             ('red rim + orange lamp', cho.sun_scene('colour')), ('lamp rows 16 px', cho.lamp_scene(16)[:2])]
    for name, (raw, flags) in cases:
        chroma = cho.reference(raw.astype(np.float64), flags, cho.SUN_WP, band=True)[0]
        rgb0 = scamrgb(raw, flags, cho.SUN_WP, chroma, 0)
        rgb1 = scamrgb(raw, flags, cho.SUN_WP, chroma, 1)
        c = np.maximum(raw, 0) / cho.SUN_WP
        clipw = 0.987 / cho.SUN_WP
        t = c / clipw
        core = (t.min(-1) >= 1.0) & ((flags & 7) != 0)
        band = (np.sort(t, -1)[..., 1] >= 1.0) & ~core
        cb0, hb0 = cho.wb_chroma(rgb0)
        cb1, hb1 = cho.wb_chroma(rgb1)
        if core.any():
            check(cb1[core].max() < 0.005, '%s: a blown core coloured (chroma %.4f)' % (name, cb1[core].max()))
        if name != 'lamp rows 16 px' and band.any():
            neutral0 = band & (cb0 < 0.005)
            if neutral0.any():
                check(cb1[neutral0].max() < 0.005, '%s: the neutral clip band coloured (chroma %.4f)' % (name, cb1[neutral0].max()))
        g = cho.guard(name, (flags & 7) != 0, cb0, hb0, cb1, hb1)
        if g and 'nothing coloured' not in g:
            check(False, g)
        changed = np.abs(rgb1 - rgb0).max(-1) > 1e-4
        dc = (cb1 - cb0)[changed] if changed.any() else np.zeros(1)
        print('%s: %.2f %% of the pixels changed (all clipped: %s), cores max chroma %.4f, the changed pixels\' chroma %+.4f median, '
              '%+.4f max' % (name, 100 * changed.mean(), bool(np.all(((flags & 7) != 0)[changed])), cb1[core].max() if core.any() else 0.0,
                             np.median(dc), dc.max()))
        if 'sun' in name:
            # P378: the glow where G alone clipped round a sun is no more coloured than before (the ring's zone)
            check(np.median(dc) <= 0.003 and dc.max() <= 0.01, '%s: the glow more coloured (chroma %+.4f median, %+.4f max)' % (
                name, np.median(dc), dc.max()))
        check(np.all(((flags & 7) != 0)[changed]), '%s: a pixel without a clipped channel changed' % name)


def main():
    foliage()
    leaves_on_sky()
    mixed()
    lights()
    if FAILURES:
        print('opposed foliage check FAIL (%d)' % len(FAILURES))
        sys.exit(1)
    print('opposed foliage check PASS')


if __name__ == '__main__':
    main()
