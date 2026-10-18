#!/usr/bin/env python3
"""ARK tone with real Bento headroom (plan P216), on the production ark/combine.glsl.

The owner's X200 Ultra bridge window under the x8 ultrashort (2026-10-18) came out white with an orange smear: the 1.23
kernel's shoulders (everything above 1 into <= 1.35, then a roll-off from 0.5) put 1.5 .. 8 x white on one grey. With the
real ceiling hrU above the capped clip the combine uses ArkCam 1.58's shoulder on the tonemapper top (knee 0.5, room 0.3)
and adds the full-size highlight detail. Rows of hues are ramped from 0.5 to the ceiling (16 x white, the x16
ultrashort), every step as a +5 % / -5 % texture pair; the colour source is fed so that the fusion gain is 1. Checks:
- monotone output along every ramp, texture off;
- no hard white (owner: «нужно чтобы не было резких белых пятен»): no channel reaches 254 below a quarter of the ceiling,
  and from 2 to 16 x white the channel sum rises at most 5 codes per 1/3 stop (3.5 - 3.8 measured) - white is approached, never jumped to;
- the highlights keep a range: from 2 to 16 x white the channel sum rises >= 8 codes (the kernel's shoulders: ~3);
- texture survives in the highlights: the +-5 % pair differs by >= 0.6 code (luma) from 2 to 16 x white;
- hrU 0 (no Bento headroom) renders exactly as a program without it (the kernel's shoulders, as before P216).
Usage: check_ark_headroom.py [--report]
"""
from pathlib import Path
import sys
import numpy as np
import moderngl

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
HUES = {'neutral': (0.50, 0.50, 0.50), 'sky': (0.42, 0.50, 0.62), 'warm': (0.62, 0.50, 0.40)}
HR = 16.0           # real ceiling (x16 ultrashort)
CLIP = 4.0          # ArkAe's capped clip
STEPS = 60          # 12 per stop over 5 stops: max channel 0.5 .. 16
TEX = 0.05
LUMA = np.array([0.2126, 0.7152, 0.0722])


def inverse_aces(y, toe=0.05):
    b = max(toe * 0.75, 0.005)
    e = max(0.14 - (toe - 0.04) * 0.8, 0.03)
    y = min(max(y, 0.0), 2.51 / 2.43 - 0.01)
    A = 2.43 * y - 2.51; B = 0.59 * y - b; C = e * y
    disc = B * B - 4 * A * C
    if disc < 0: return 0.0
    x = (-B - np.sqrt(disc)) / (2 * A - 1e-6)
    return max(x / 1.5, 0.0)


def fused_for(target):
    lo, hi = 0.0, 2.51 / 2.43 - 0.0101
    for _ in range(60):
        mid = (lo + hi) / 2
        if inverse_aces(mid) < target: lo = mid
        else: hi = mid
    return lo ** (1 / 2.2)


def source():
    s = (SH / 'ark/combine.glsl').read_text(encoding='utf-8')
    for imp in ['interpolation', 'coords']:
        p = SH / ('utils/import_' + imp + '.glsl')
        if p.exists(): s = s.replace('#import ' + imp, p.read_text(encoding='utf-8'))
    return s


def render(hr, detail):
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    names = list(HUES)
    W, H = STEPS * 2, len(names)
    scales = 2.0 ** (np.arange(STEPS) / 12.0)                  # 1 .. ~31 x the hue's base (max channel 0.5 .. ~16)
    orig = np.zeros((H, W, 4), np.float32); full = np.zeros_like(orig); fused = np.zeros_like(orig)
    for r, n in enumerate(names):
        rgb = np.array(HUES[n])
        for i, s in enumerate(scales):
            c = rgb * s
            y = max(max(float(c @ LUMA), c.max() * 0.5), 1e-4)
            for k, t in enumerate((1 + TEX, 1 - TEX)):
                orig[r, 2 * i + k, :3] = c
                full[r, 2 * i + k, :3] = c * t
                fused[r, 2 * i + k, 0] = fused_for(y)

    def tex(a):
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST); return t
    ones = tex(np.ones((1, 1, 4), np.float32))
    t_full, t_orig, t_fused = tex(full), tex(orig), tex(fused)
    vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
    prog = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + source())
    out = ctx.texture((W, H), 4, dtype='f4'); fb = ctx.framebuffer([out]); fb.use(); ctx.viewport = (0, 0, W, H)
    units = {'InputBuffer': t_full, 'GainMap': ones, 'ArkLow': t_orig, 'ArkColour': t_orig, 'ArkFused': t_fused,
             'ArkDetailRef': t_orig, 'ArkLumaS': t_orig}
    for i, (k, t) in enumerate(units.items()):
        if k in prog: t.use(i); prog[k].value = i
    eye = tuple(np.eye(3).flatten())
    vals = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0,
            'fU': 1, 'colourFU': 1, 'aeU': 1.0, 'clipU': CLIP, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.1,
            'vibU': (0.0, 0.4, 0.2), 'detailGainU': 0.0, 'deltaChromaU': 1.0, 'filmToeU': 0.1, 'ditherU': 0,
            'agxAU': (2.7, 1.35, 1.6, 1.0), 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'headroomU': 1, 'hlWhiteU': 0.15}
    if hr is not None:
        vals.update({'hrU': hr, 'hrDetailU': detail})
    for k, v in vals.items():
        if k in prog: prog[k].value = v
    ctx.vertex_array(prog, []).render(vertices=3)
    img = np.frombuffer(out.read(), np.float32).reshape(H, W, 4)[:, :, :3] * 255.0
    ctx.release()
    return names, scales, img


def check(report=False):
    names, scales, flat = render(HR, 0.0)
    _, _, tex_on = render(HR, 0.35)
    _, _, old = render(0.0, 0.35)
    _, _, unset = render(None, 0.0)
    failures = []
    if not np.array_equal(old, unset):
        failures.append(f'hrU 0 differs from the program without it: max {np.abs(old - unset).max():.3f} codes')
    for r, n in enumerate(names):
        top = np.array(HUES[n]).max() * scales                    # max channel of each step
        mean = (flat[r, 0::2] + flat[r, 1::2]) / 2
        y = mean @ LUMA
        s = mean.sum(1)
        drops = np.where(np.diff(y) < -0.25)[0]
        if len(drops): failures.append(f'{n}: output luminance falls at steps {drops[:5].tolist()}')
        inside = top <= HR
        low = np.repeat(top <= HR / 4, 2)
        peak = max(tex_on[r][low].max(), flat[r][low].max())
        if peak >= 254.0: failures.append(f'{n}: white below a quarter of the ceiling (max {peak:.1f})')
        hi = (top >= 2.0) & inside
        rise = s[hi][-1] - s[hi][0]
        if rise < 8.0: failures.append(f'{n}: 2..16 x white rises only {rise:.1f} codes (channel sum)')
        step = (s[hi][4:] - s[hi][:-4]).max()
        if step > 5.0: failures.append(f'{n}: a jump towards white of {step:.1f} codes per 1/3 stop')
        amp = (tex_on[r, 0::2] - tex_on[r, 1::2]) @ LUMA
        if amp[hi].min() < 0.6: failures.append(f'{n}: highlight texture {amp[hi].min():.2f} code < 0.6')
        if report:
            om = (old[r, 0::2] + old[r, 1::2]) / 2
            print(f'{n:8s} 2x {mean[hi][0].round(1)} 16x {mean[hi][-1].round(1)} rise {rise:.1f} (kernel shoulders '
                  f'{om.sum(1)[hi][-1] - om.sum(1)[hi][0]:.1f}) texture min {amp[hi].min():.2f} peak(<HR/4) {peak:.1f} max step {step:.1f}')
    if failures:
        print('ARK headroom FAIL:\n  ' + '\n  '.join(failures))
        return 1
    print('ARK headroom PASS: monotone ramps to 16 x white, no hard white, highlight range and texture kept, hrU 0 unchanged')
    return 0


if __name__ == '__main__':
    sys.exit(check('--report' in sys.argv))
