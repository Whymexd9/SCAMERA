#!/usr/bin/env python3
"""P154 «Мягкость засветов»: the highlight landing of why/combine.glsl and its mask why/hlmask.glsl on the GPU (moderngl).

why/hlmask.glsl: the max channel over the ceiling, then a gaussian along x and along y, equals numpy's separable gaussian.

Ramps up to the data ceiling (a grey, an orange light, a slightly blue white) go through why/combine.glsl with clipped
data in the frame (headroomU 1) without the landing (HL_LANDING 0) and with it (hlSoftU 0.7, WhyHl = the pixel's own share
of the ceiling, i.e. a large blown area):
- the blown grey ends on pure white (1.0 in every channel; without the landing it stays a grey below 0.99);
- below the start of the landing (0.3 of the ceiling) nothing changes;
- the output rises with the input and the last steps before the ceiling get smaller (the slope fades into the plateau);
- P159: the orange light lands on ITS colour at full brightness (the scene's channel ratios, display-encoded), not on the
  colour the tone's shoulder bleached; its chroma never drops below the toned one on the way up;
- a slightly blue near-white ends on pure white;
- a dark pixel inside a blown neighbourhood (WhyHl 1, own share below 0.15) is left alone (no glow on dark objects);
- without clipped data (headroomU 0) the landing does nothing.
A blown disc with a soft edge on a mid grey, with the real mask: the disc lands on white, and the steepest step across its
edge is not steeper than without the landing (owner: «очень резкие переходы с уходом в серый» of the per-pixel landing).
P378 HL_OPPOSED (the nearness of a light «Inpaint opposed» rebuilt, ScamRgb's map: scamhdr/suncore MODE 2's block weight
smoothstep(0.05, 0.15, share), MODE 1's max-gaussian): with a map of 0 everywhere (no such light: ultrashort data) every render
is HL_OPPOSED 0's within float rounding (1e-6); a map of 1 lands the low-saturation lavender LED letter (its neighbourhood's
colour, P163) on white and leaves a saturated red letter on its colour with its light's gate 0 (within 0.01); the lavender letter's
neutral dots rebuilt without ultrashort frames fill no 16 px block (a 3072-px frame's), so it still lands on its colour and
the dots with it (0.03).
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_colour_output import SH, expand_imports, oklab, set_define  # noqa: E402

FAILURES = []


def check(cond, msg):
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def main():
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: highlight landing check skipped')
        return 0
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    src_off = expand_imports((SH / 'why/combine.glsl').read_text(encoding='utf-8'))
    src = set_define(src_off, 'HL_LANDING', '1')
    src_opp = set_define(src, 'HL_OPPOSED', '1')
    mask_src = expand_imports((SH / 'why/hlmask.glsl').read_text(encoding='utf-8'))
    clip = 2.5
    soft = 0.7
    k = np.geomspace(0.02, 1.0, 96)                      # fraction of the ceiling
    tints = {'grey': np.array([1.0, 1.0, 1.0]), 'orange': np.array([1.0, 0.55, 0.2]), 'cool white': np.array([0.95, 0.97, 1.0])}
    # P163: each tint a band of its own (the ramp along x, the same in every row), so a pixel's neighbourhood is the same
    # light (the landing takes its colour from there); the band's middle row is measured
    W, BH = len(k), 33
    H = BH * len(tints)
    img = np.zeros((H, W, 4), np.float32)
    for i, t in enumerate(tints.values()):
        img[i * BH:(i + 1) * BH, :, :3] = np.outer(k * clip, t)[None].astype(np.float32)
    # the fused display luma whose ACES inversion gives the luminance back (as check_tone_ops builds it)
    grid = np.linspace(0, 2.51 / 2.43 - 0.0101, 40000)
    b, e = 0.0375, 0.132
    A, B, C = 2.43 * grid - 2.51, 0.59 * grid - b, e * grid
    table = np.maximum((-B - np.sqrt(np.maximum(B * B - 4 * A * C, 0))) / (2 * A - 1e-6) / 1.5, 0)
    lum = img[..., :3] @ np.array([0.2126, 0.7152, 0.0722])
    fused = np.zeros((H, W, 4), np.float32)
    fused[..., 0] = np.interp(np.minimum(lum, table.max()), table, grid) ** (1 / 2.2)

    def tex(a):
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t
    ones = tex(np.ones((1, 1, 4), np.float32))
    vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
    prog_on = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + src)
    prog_off = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + src_off)
    prog_mask = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + mask_src)
    prog_opp = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + src_opp)

    def run_mask(a, sigma):
        """why/hlmask.glsl: the share, then the gaussian along x and along y (WhyCombine's three passes)."""
        h, w = a.shape[:2]
        t_in = tex(a)
        outs = [ctx.texture((w, h), 4, dtype='f4') for _ in range(3)]
        src_t = t_in
        for d, o in zip([(0, 0), (1, 0), (0, 1)], outs):
            fb = ctx.framebuffer([o])
            fb.use()
            ctx.viewport = (0, 0, w, h)
            src_t.use(0)
            prog_mask['InputBuffer'].value = 0
            prog_mask['dirU'].value = d
            if 'invClipU' in prog_mask:
                prog_mask['invClipU'].value = 1 / clip
            if 'sigmaU' in prog_mask:
                prog_mask['sigmaU'].value = sigma
            ctx.vertex_array(prog_mask, []).render(vertices=3)
            fb.release()
            o.filter = (moderngl.NEAREST, moderngl.NEAREST)
            src_t = o
        res = np.frombuffer(outs[2].read(), np.float32).reshape(h, w, 4)[:, :, 0].copy()
        for o in outs:
            o.release()
        t_in.release()
        return res

    def gauss_np(m, sigma):
        r = int(np.ceil(3 * sigma))
        g = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
        g /= g.sum()
        pad = np.pad(m, ((0, 0), (r, r)), mode='edge')
        m = np.stack([np.convolve(row, g, 'valid') for row in pad])
        pad = np.pad(m, ((r, r), (0, 0)), mode='edge')
        return np.stack([np.convolve(col, g, 'valid') for col in pad.T]).T

    rng = np.random.default_rng(3)
    probe = np.zeros((40, 56, 4), np.float32)
    probe[..., :3] = rng.uniform(0, 1.2 * clip, (40, 56, 3)) * (rng.uniform(size=(40, 56, 1)) > 0.6)
    got = run_mask(probe, 3.0)
    want = gauss_np(np.clip(probe[..., :3].max(2) / clip, 0, 4), 3.0)
    err = np.abs(got - want).max()
    print('  hlmask: max |GPU - numpy gaussian| %.2e' % err)
    check(err < 1e-4, 'why/hlmask.glsl differs from the gaussian of the share (%.2e)' % err)

    def render(image, fz, share, hl, headroom=1, full=None, detail=0.0, opp=None):
        """opp: (P378) the share of the pixels «Inpaint opposed» left neutral on blocks of opp[1] px (HL_OPPOSED 1)."""
        h, w = image.shape[:2]
        prog = prog_opp if opp is not None else prog_on if hl > 0 else prog_off
        t_img, t_fused, t_hl = tex(image), tex(fz), tex(np.repeat(share[..., None], 4, 2))
        t_full = tex(full) if full is not None else t_img   # the full-size merge (the detail delta's source)
        out = ctx.texture((w, h), 4, dtype='f4')
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, w, h)
        units = {'InputBuffer': t_full, 'GainMap': ones, 'WhyLow': t_img, 'WhyColour': t_img, 'WhyFused': t_fused,
                 'WhyDetailRef': t_img, 'WhyLumaS': t_img, 'WhyHl': t_hl}
        t_opp = None
        if opp is not None:
            near = np.asarray(opp[0], np.float32)
            gate = np.full_like(near, opp[2] if len(opp) > 2 else 1.0)    # P378 review: .g the light's gate (suncore MODE 3)
            t_opp = tex(np.dstack([near, gate, near, near]))
            t_opp.filter = (moderngl.LINEAR, moderngl.LINEAR)
            units['OppShare'] = t_opp
        for i, (key, t) in enumerate(units.items()):
            if key in prog:
                t.use(i)
                prog[key].value = i
        eye = tuple(np.eye(3).flatten())
        vals = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0,
                'fU': 1, 'colourFU': 1, 'aeU': 1.0, 'clipU': clip, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.0,
                'vibU': (0.0, 0.0, 0.0), 'detailGainU': detail, 'deltaChromaU': 1.0, 'filmToeU': 0.1, 'ditherU': 0,
                'agxAU': (2.7, 1.35, 1.6, 1.0), 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'headroomU': headroom, 'hlWhiteU': 0.0,
                'hlSoftU': hl}
        if opp is not None:
            vals['oppGridU'] = (w / float(opp[0].shape[1] * opp[1]), h / float(opp[0].shape[0] * opp[1]))
        for key, v in vals.items():
            if key in prog:
                prog[key].value = v
        ctx.vertex_array(prog, []).render(vertices=3)
        res = np.frombuffer(out.read(), np.float32).reshape(h, w, 4)[:, :, :3].copy()
        for t in (fb, out, t_img, t_fused, t_hl):
            t.release()
        if full is not None:
            t_full.release()
        if t_opp is not None:
            t_opp.release()
        return res

    own = (img[..., :3].max(2) / clip).astype(np.float32)

    def ramps(hl, share=own, headroom=1):
        out = render(img, fused, share, hl, headroom)
        return np.stack([out[i * BH + BH // 2] for i in range(len(tints))])

    base, land = ramps(0.0), ramps(soft)
    check(np.all(np.isfinite(land)), 'not finite')
    below = k < 1 - soft
    for i, name in enumerate(tints):
        b0, l0 = base[i], land[i]
        print('  %-10s ceiling: without %s  with %s' % (name, np.round(b0[-1], 4), np.round(l0[-1], 4)))
        err = np.abs(l0[below] - b0[below]).max()
        check(err < 1e-6, '%s: changed below the start of the landing (%.2e)' % (name, err))
        peak = l0.max(1)
        check(np.all(np.diff(peak) >= -1e-5), '%s: does not rise with the input' % name)
        check(abs(l0[-1].max() - 1.0) < 1e-3, '%s: the max channel at the ceiling is %.4f, not 1' % (name, l0[-1].max()))
        steps = np.diff(peak[k > 0.9])
        check(steps[-1] < steps[0] * 0.5 + 1e-4, '%s: the last steps before the ceiling do not get smaller' % name)
    check(base[0][-1].max() < 0.99, 'the test is moot: without the landing the blown grey is already white')
    check(np.all(land[0][-1] > 0.999), 'the blown grey is not pure white: %s' % land[0][-1])
    check(np.all(land[2][-1] > 0.999), 'the slightly blue near-white is not pure white: %s' % land[2][-1])
    # P159: the orange light lands on its own (scene) colour at full brightness, never more bleached than the tone left it
    orange = tints['orange']
    want = (orange / orange.max()) ** (1 / 2.2)
    want = want / want.max()
    err = np.abs(land[1][-1] - want).max()
    print('  orange at the ceiling: %s, its scene colour %s (max diff %.4f)' % (np.round(land[1][-1], 4), np.round(want, 4), err))
    check(err < 0.01, 'the orange light does not land on its own colour (%.4f)' % err)

    def chroma(c):
        lab = oklab(np.maximum(c, 0) ** 2.2)
        return np.hypot(lab[:, 1], lab[:, 2])
    acting = k >= 1 - soft
    loss = (chroma(base[1][acting]) - chroma(land[1][acting])).max()
    print('  orange: most chroma lost against the tone %.4f' % loss)
    check(loss < 0.005, 'the landing bleaches the orange light (%.4f)' % loss)
    same = np.abs(ramps(soft, headroom=0) - ramps(0.0, headroom=0)).max()
    print('  without clipped data: max |with - without| %.2e' % same)
    check(same < 1e-6, 'the landing acts without clipped data')
    # a dark pixel in a blown neighbourhood (WhyHl 1) is left alone
    dark = np.abs(ramps(soft, share=np.ones_like(own))[:, k < 0.15] - base[:, k < 0.15]).max()
    print('  dark pixels inside a blown neighbourhood: max |with - without| %.2e' % dark)
    check(dark < 1e-6, 'the landing lifts dark pixels next to a light (%.2e)' % dark)

    # a blown disc with a soft edge on a mid grey, the real mask on the same grid
    S = 160
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
    rad = np.hypot(xx - S / 2, yy - S / 2)
    lin = 0.18 * clip + (1.3 * clip - 0.18 * clip) * np.clip((44 - rad) / 18, 0, 1) ** 1.5
    scene = np.zeros((S, S, 4), np.float32)
    scene[..., :3] = np.minimum(lin, clip)[..., None]
    lum_s = scene[..., :3] @ np.array([0.2126, 0.7152, 0.0722])
    fz = np.zeros((S, S, 4), np.float32)
    fz[..., 0] = np.interp(np.minimum(lum_s, table.max()), table, grid) ** (1 / 2.2)
    mask = run_mask(scene, max(1.0, 0.012 * S))
    d0 = render(scene, fz, mask, 0.0)
    d1 = render(scene, fz, mask, soft)
    centre = d1[S // 2, S // 2]
    print('  blown disc: centre without %s  with %s' % (np.round(d0[S // 2, S // 2], 4), np.round(centre, 4)))
    check(np.all(centre > 0.999), 'the blown disc is not pure white: %s' % centre)
    # the edge rises further (to 1 instead of the grey plateau), so its steepest step is measured against its whole rise:
    # the shape of the transition must not get sharper
    row0, row1 = d0[S // 2, :, 1], d1[S // 2, :, 1]
    s0 = np.abs(np.diff(row0)).max() / (row0.max() - row0.min())
    s1 = np.abs(np.diff(row1)).max() / (row1.max() - row1.min())
    print('  blown disc edge: steepest step / rise without %.4f  with %.4f' % (s0, s1))
    check(s1 <= s0 * 1.05, 'the landing makes the edge of the blown disc sharper (%.4f > %.4f)' % (s1, s0))
    check(np.all(np.diff(row1[:S // 2]) >= -1e-5), 'the edge of the blown disc does not rise monotonically')

    # P163: a tinted light (an LED wall's lavender letter on its blue ground) with neutral dots inside it (its diodes clipped
    # even in the ultrashort frames): the dots land on the letter's colour, not on white specks; the letter keeps its tint
    S2 = 96
    letter = np.array([0.79, 0.76, 1.0])
    led = np.zeros((S2, S2, 4), np.float32)
    led[..., :3] = np.array([0.09, 0.19, 1.0]) * 0.45 * clip
    led[24:72, 24:72, :3] = letter * clip
    dots = [(30, 30), (40, 52), (55, 35), (60, 64), (47, 47)]
    for y, x in dots:
        led[y - 1:y + 2, x - 1:x + 2, :3] = 1.3 * clip
    led[..., :3] = np.minimum(led[..., :3], clip)
    lum_l = led[..., :3] @ np.array([0.2126, 0.7152, 0.0722])
    fz_l = np.zeros_like(led)
    fz_l[..., 0] = np.interp(np.minimum(lum_l, table.max()), table, grid) ** (1 / 2.2)
    out_l = render(led, fz_l, run_mask(led, max(1.0, 0.012 * S2)), soft)
    inside = out_l[36:62, 36:62].reshape(-1, 3)
    ref = np.median(inside, 0)
    worst = max(np.abs(out_l[y, x] - ref).max() for y, x in dots)
    print('  LED letter: inside %s, its neutral dots differ by at most %.4f' % (np.round(ref, 4), worst))
    check(worst < 0.03, 'the neutral dots of a tinted light land apart from it (white specks): %.4f' % worst)
    check(ref[2] - ref[1] > 0.02, 'the lavender letter lost its tint: %s' % ref)

    # P378 HL_OPPOSED: a map of 0 (no light «Inpaint opposed» rebuilt) changes nothing (llvmpipe compiles the other program's
    # arithmetic a last bit apart: 6e-8 seen)
    worst0 = 0.0
    for name, (im, fz_, sh_) in (('ramps', (img, fused, own)), ('blown disc', (scene, fz, mask)),
                                 ('LED letter', (led, fz_l, run_mask(led, max(1.0, 0.012 * S2))))):
        h_, w_ = im.shape[:2]
        same = np.abs(render(im, fz_, sh_, soft, opp=(np.zeros(((h_ + 15) // 16, (w_ + 15) // 16)), 16))
                      - render(im, fz_, sh_, soft)).max()
        check(same < 1e-6, 'HL_OPPOSED with a share of 0 changes the %s (%.2e)' % (name, same))
        worst0 = max(worst0, same)
    print('  HL_OPPOSED with a map of 0: ramps, blown disc, LED letter max |diff| %.1e' % worst0)
    # a map of 1 (the letter clipped without ultrashort data): the lavender letter (saturation 0.24) on white, not its
    # neighbourhood's tint
    led_mask = run_mask(led, max(1.0, 0.012 * S2))
    out_w = render(led, fz_l, led_mask, soft, opp=(np.ones((S2, S2)), 1))
    inside_w = np.median(out_w[36:62, 36:62].reshape(-1, 3), 0)
    print('  LED letter with a map of 1: %s (without %s)' % (np.round(inside_w, 4), np.round(ref, 4)))
    check(np.all(inside_w > 0.995) and inside_w.max() - inside_w.min() < 0.005,
          'a map of 1 does not land the letter on white: %s' % inside_w)
    # a saturated letter (an LED wall's red: saturation 0.85) keeps its colour with a map of 1: its light's gate 0 (P378 review:
    # decided per light, scamhdr/suncore MODE 3 - a red LED's glow measures far over GATE_HI)
    red = led.copy()
    red[24:72, 24:72, :3] = np.array([1.0, 0.15, 0.1]) * clip
    lum_r = red[..., :3] @ np.array([0.2126, 0.7152, 0.0722])
    fz_r = np.zeros_like(red)
    fz_r[..., 0] = np.interp(np.minimum(lum_r, table.max()), table, grid) ** (1 / 2.2)
    mask_r = run_mask(red, max(1.0, 0.012 * S2))
    plain_r = np.median(render(red, fz_r, mask_r, soft)[36:62, 36:62].reshape(-1, 3), 0)
    opp_r = np.median(render(red, fz_r, mask_r, soft, opp=(np.ones((S2, S2)), 1, 0.0))[36:62, 36:62].reshape(-1, 3), 0)
    print('  red LED letter with a map of 1: %s (HL_OPPOSED 0: %s)' % (np.round(opp_r, 4), np.round(plain_r, 4)))
    check(np.abs(opp_r - plain_r).max() < 0.01, 'a map of 1 takes the red letter\'s colour: %s against %s' % (opp_r, plain_r))
    # the letter's neutral dots rebuilt without ultrashort frames: on 16-px blocks (a 3072-px frame's) 0.035 of a block, under
    # the block weight's 0.05 (scamhdr/suncore MODE 2) - no block filled, the map 0 after the spread (MODE 1, ~6.3 blocks): they
    # keep the letter's colour (P163)
    blk = 16
    dmap = np.zeros(((S2 + blk - 1) // blk * blk, (S2 + blk - 1) // blk * blk))
    for y, x in dots:
        dmap[y - 1:y + 2, x - 1:x + 2] = 1.0
    dshare = dmap.reshape(dmap.shape[0] // blk, blk, dmap.shape[1] // blk, blk).mean((1, 3))
    t = np.clip((dshare - 0.05) / 0.10, 0.0, 1.0)
    dweight = t * t * (3 - 2 * t)
    radius = 0.033 * 192
    rr = int(np.ceil(2.5 * radius))
    taps = np.exp(-(np.arange(-rr, rr + 1) / radius) ** 2)
    pw = np.pad(dweight, rr)
    dnear = np.max([[(taps[i] * taps[j]) * pw[j:j + dweight.shape[0], i:i + dweight.shape[1]] for i in range(2 * rr + 1)]
                    for j in range(2 * rr + 1)], (0, 1))
    out_d = render(led, fz_l, led_mask, soft, opp=(dnear, blk))
    ref_d = np.median(out_d[36:62, 36:62].reshape(-1, 3), 0)
    worst_d = max(np.abs(out_d[y, x] - ref_d).max() for y, x in dots)
    print('  LED letter, its dots rebuilt (16-px blocks: share max %.3f, map max %.3f): dots differ from the letter by at most %.4f'
          % (dshare.max(), dnear.max(), worst_d))
    check(dnear.max() == 0.0, 'the rebuilt dots fill a block: map %.3f' % dnear.max())
    check(worst_d < 0.03, 'the rebuilt neutral dots of a tinted light land apart from it: %.4f' % worst_d)
    check(ref_d[2] - ref_d[1] > 0.02, 'the lavender letter lost its tint with its rebuilt dots: %s' % ref_d)

    if FAILURES:
        print('highlight landing check FAILED (%d)' % len(FAILURES))
        return 1
    print('highlight landing check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
