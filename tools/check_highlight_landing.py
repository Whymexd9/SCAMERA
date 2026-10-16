#!/usr/bin/env python3
"""P154 «Мягкость засветов»: the highlight landing of ark/combine.glsl and its mask ark/hlmask.glsl on the GPU (moderngl).

ark/hlmask.glsl: the max channel over the ceiling, then a gaussian along x and along y, equals numpy's separable gaussian.

Ramps up to the data ceiling (a grey, an orange light, a slightly blue white) go through ark/combine.glsl with clipped
data in the frame (headroomU 1) without the landing (HL_LANDING 0) and with it (hlSoftU 0.7, ArkHl = the pixel's own share
of the ceiling, i.e. a large blown area):
- the blown grey ends on pure white (1.0 in every channel; without the landing it stays a grey below 0.99);
- below the start of the landing (0.3 of the ceiling) nothing changes;
- the output rises with the input and the last steps before the ceiling get smaller (the slope fades into the plateau);
- P159: the orange light lands on ITS colour at full brightness (the scene's channel ratios, display-encoded), not on the
  colour the tone's shoulder bleached; its chroma never drops below the toned one on the way up;
- a slightly blue near-white ends on pure white;
- a dark pixel inside a blown neighbourhood (ArkHl 1, own share below 0.15) is left alone (no glow on dark objects);
- without clipped data (headroomU 0) the landing does nothing.
A blown disc with a soft edge on a mid grey, with the real mask: the disc lands on white, and the steepest step across its
edge is not steeper than without the landing (owner: «очень резкие переходы с уходом в серый» of the per-pixel landing).
P159: a negative detail delta (the sharpening's undershoot) no longer darkens a pixel the tone took to near white when the
frame has clipped data; without clipped data, or with the landing off, the same delta still darkens it.
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
    src_off = expand_imports((SH / 'ark/combine.glsl').read_text(encoding='utf-8'))
    src = set_define(src_off, 'HL_LANDING', '1')
    mask_src = expand_imports((SH / 'ark/hlmask.glsl').read_text(encoding='utf-8'))
    clip = 2.5
    soft = 0.7
    k = np.geomspace(0.02, 1.0, 96)                      # fraction of the ceiling
    tints = {'grey': np.array([1.0, 1.0, 1.0]), 'orange': np.array([1.0, 0.55, 0.2]), 'cool white': np.array([0.95, 0.97, 1.0])}
    rows = [np.outer(k * clip, t) for t in tints.values()]
    cols = np.concatenate(rows).astype(np.float32)
    n = len(cols)
    W = 32
    H = -(-n // W)
    img = np.zeros((H, W, 4), np.float32)
    img.reshape(-1, 4)[:n, :3] = cols
    # the fused display luma whose ACES inversion gives the luminance back (as check_tone_ops builds it)
    grid = np.linspace(0, 2.51 / 2.43 - 0.0101, 40000)
    b, e = 0.0375, 0.132
    A, B, C = 2.43 * grid - 2.51, 0.59 * grid - b, e * grid
    table = np.maximum((-B - np.sqrt(np.maximum(B * B - 4 * A * C, 0))) / (2 * A - 1e-6) / 1.5, 0)
    lum = cols @ np.array([0.2126, 0.7152, 0.0722])
    fused = np.zeros((H, W, 4), np.float32)
    fused.reshape(-1, 4)[:n, 0] = np.interp(np.minimum(lum, table.max()), table, grid) ** (1 / 2.2)

    def tex(a):
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t
    ones = tex(np.ones((1, 1, 4), np.float32))
    vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
    prog_on = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + src)
    prog_off = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + src_off)
    prog_mask = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + mask_src)

    def run_mask(a, sigma):
        """ark/hlmask.glsl: the share, then the gaussian along x and along y (ArkCombine's three passes)."""
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
    check(err < 1e-4, 'ark/hlmask.glsl differs from the gaussian of the share (%.2e)' % err)

    def render(image, fz, share, hl, headroom=1, full=None, detail=0.0):
        h, w = image.shape[:2]
        prog = prog_on if hl > 0 else prog_off
        t_img, t_fused, t_hl = tex(image), tex(fz), tex(np.repeat(share[..., None], 4, 2))
        t_full = tex(full) if full is not None else t_img   # the full-size merge (the detail delta's source)
        out = ctx.texture((w, h), 4, dtype='f4')
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, w, h)
        units = {'InputBuffer': t_full, 'GainMap': ones, 'ArkLow': t_img, 'ArkColour': t_img, 'ArkFused': t_fused,
                 'ArkDetailRef': t_img, 'ArkLumaS': t_img, 'ArkHl': t_hl}
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
        for key, v in vals.items():
            if key in prog:
                prog[key].value = v
        ctx.vertex_array(prog, []).render(vertices=3)
        res = np.frombuffer(out.read(), np.float32).reshape(h, w, 4)[:, :, :3].copy()
        for t in (fb, out, t_img, t_fused, t_hl):
            t.release()
        if full is not None:
            t_full.release()
        return res

    own = np.zeros((H, W), np.float32)
    own.reshape(-1)[:n] = cols.max(1) / clip

    def ramps(hl, share=own, headroom=1):
        return render(img, fused, share, hl, headroom).reshape(-1, 3)[:n].reshape(len(tints), len(k), 3)

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
    # a dark pixel in a blown neighbourhood (ArkHl 1) is left alone
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

    # P159: the sharpening's undershoot on a near-white pixel (the full-size merge darker than the colour source: a negative
    # detail delta)
    lv = np.geomspace(0.3 * clip, clip, 48).astype(np.float32)
    flat = np.zeros((1, len(lv), 4), np.float32)
    flat[0, :, :3] = lv[:, None]
    darker = flat.copy()
    darker[0, :, :3] *= 0.6
    lum_f = flat[0, :, :3] @ np.array([0.2126, 0.7152, 0.0722])
    fz_f = np.zeros_like(flat)
    fz_f[0, :, 0] = np.interp(np.minimum(lum_f, table.max()), table, grid) ** (1 / 2.2)
    # no blown neighbourhood (ArkHl 0): no landing, only the undershoot rule acts (it follows the pixel's own lightness)
    sh = np.zeros((1, len(lv)), np.float32)
    plain = render(flat, fz_f, sh, soft)[0]
    rimmed = render(flat, fz_f, sh, soft, full=darker, detail=1.0)[0]
    rimmed_off = render(flat, fz_f, sh, 0.0, full=darker, detail=1.0)[0]
    plain_off = render(flat, fz_f, sh, 0.0)[0]
    L = lambda c: oklab(np.maximum(c, 0) ** 2.2)[:, 0]
    white = L(plain) >= 0.9
    dark_on = (L(plain) - L(rimmed))[white].max() if white.any() else 0.0
    dark_off = (L(plain_off) - L(rimmed_off))[white].max() if white.any() else 0.0
    print('  undershoot on near-white pixels (L >= 0.9): L lost %.4f with P159, %.4f without' % (dark_on, dark_off))
    check(white.any(), 'the test is moot: no near-white pixel')
    check(dark_off > 0.01, 'the test is moot: the delta does not darken without the landing')
    check(dark_on < 0.35 * dark_off, 'a near-white pixel is still darkened by the undershoot (%.4f)' % dark_on)
    same = np.abs(render(flat, fz_f, sh, soft, headroom=0, full=darker, detail=1.0)[0]
                  - render(flat, fz_f, sh, 0.0, headroom=0, full=darker, detail=1.0)[0]).max()
    check(same < 1e-6, 'the undershoot rule acts without clipped data (%.2e)' % same)
    keep = L(plain) < 0.75
    if keep.any():
        kept = (L(plain) - L(rimmed))[keep].min()
        check(kept > 0.005, 'the undershoot on a darker pixel was removed too (%.4f)' % kept)
    if FAILURES:
        print('highlight landing check FAILED (%d)' % len(FAILURES))
        return 1
    print('highlight landing check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
