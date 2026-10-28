#!/usr/bin/env python3
"""P369: the film simulation fed scene light - why/combine.glsl FILM_SIM 1 (WhyCombine, LiveWhyTone) - on the GPU (moderngl),
no device.

With a film the photo's film no longer takes our display output: why/combine taps the exposed scene colour (the colour source x
ae, before the fusion's gain, the shoulders, OKLab, AgX and the display chain), puts the full-size detail on it as the luminance
ratio the detail delta stands for, x filmExpU (the AE's grey at the film's mid grey 0.184), looks it up in a per-channel log
table of the model (FilmChain.scene: node i at 0.184 x 2^(lo + span i / (n - 1))) and replaces our output by the print (mixed
by the strength below 1). This check, with analytic tables (the real tables against the model: FilmSimTest):

  1. FILM_SIM 0 (unset) leaves the shader token for token as it is with every FILM_SIM block deleted - the default and every
     define WhyCombine and LiveWhyTone set, alone and together (POINT_FUSED, RAW_SOURCE included);
  2. a table that returns its own log coordinate: the decoded scene light equals the B-spline colour source x ae x filmExpU
     (within 0.5 %) at f 1, f 2 and f 4 / colourFU 2 (there at the luminance of whyLow's B-spline, the detail's own base);
  3. a textured image at f 2 and at f 4 / colourFU 2 with the bounded detail delta: the film's luminance is the full-size
     luminance x ae x filmExpU - its texture RMS within 3 %, its mean within 0.5 % - and a base at the colour grid's own
     luminance on the 2x grid (counting the band between whyMid and whyLow twice) fails it;
  4. strength 0.5 is the mean of our output (FILM_SIM 0) and the film alone (<= 1e-4), and the film alone does not move with
     our tone (fusion, macro contrast, vibrance, gamma, AgX, film toe, colour look, tone operator, highlight landing);
  5. P3_OUT 1: the P3 file decoded to sRGB equals the sRGB file within 0.5 / 255 (a colourful table);
  6. every FILM_SIM variant compiles - moderngl (the host GPU) and, when an NDK is found, glslc as GLProg hands it to the driver
     (#version 310 es, imports expanded); --strict fails without glslc.
The GPU parts 2 - 5 and 7 run with the P370 signed hue off (signedHueU 0 / unset) and on (the shipped default 1).

usage: python3 tools/check_film_sim.py [--strict]   (numpy, moderngl; LIBGL_ALWAYS_SOFTWARE=1 on a headless Linux)
"""
from __future__ import annotations

import itertools
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_colour_output import SH, expand_imports, set_define, token_hash  # noqa: E402
from check_vf_fused import find_glslc, read_program  # noqa: E402

FAILURES: list[str] = []
SKIPPED: list[str] = []
LUMA = np.array([0.2126, 0.7152, 0.0722])
GREY, LO, SPAN = 0.184, -10.0, 20.0          # FilmSim.FILM_GREY, LOG_LO, LOG_SPAN
METER_TO_FILM = 0.184 / 0.15                 # FilmSim.METER_TO_FILM
N = 41                                       # FilmSim.SCENE_SIZE
VS = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
# the defines WhyCombine sets (the photo) and LiveWhyTone's (the RAW viewfinder: POINT_FUSED, with RAW_SOURCE in the fused pass)
PHOTO = ('P3_OUT', 'COLOUR_LOOK', 'TONE_OP', 'FILM_LUT', 'HL_LANDING', 'HEADROOM_WIDE', 'SUN_BLOOM')
VIEW = ('P3_OUT', 'COLOUR_LOOK', 'TONE_OP', 'FILM_LUT')


def check(cond: bool, msg: str) -> None:
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def read(p: Path) -> str:
    return p.read_text(encoding='utf-8').replace('\r\n', '\n')


# ------------------------------------------------------------------------------------------------ 1. tokens

def without_film_sim(src: str) -> str:
    """The shader with its FILM_SIM define line and every #if FILM_SIM == 1 block (nested #if included) deleted."""
    out, depth, skipping = [], 0, False
    lines = src.split('\n')
    i = 0
    while i < len(lines):
        s = lines[i].strip()
        if not skipping and re.match(r'#\s*define\s+FILM_SIM\s', s):
            i += 1
            continue
        if not skipping and re.match(r'#\s*if\s+FILM_SIM\s*==\s*1\s*$', s):
            skipping, depth = True, 1
            i += 1
            continue
        if skipping:
            if re.match(r'#\s*if\b', s):
                depth += 1
            elif re.match(r'#\s*endif\b', s):
                depth -= 1
                if depth == 0:
                    skipping = False
            elif re.match(r'#\s*else\b', s) and depth == 1:
                raise SystemExit('a FILM_SIM block with #else: the check expects #if FILM_SIM == 1 ... #endif only')
            i += 1
            continue
        out.append(lines[i])
        i += 1
    if skipping:
        raise SystemExit('unterminated FILM_SIM block')
    return '\n'.join(out)


def with_defines(src: str, defines) -> str:
    for name in defines:
        src = set_define(src, name, '1')
    return src


def token_part(src: str) -> None:
    stripped = without_film_sim(src)
    check('FILM_SIM' not in re.sub(r'//[^\n]*', '', stripped), 'a FILM_SIM token outside its blocks')
    combos = []
    for k in range(len(PHOTO) + 1):
        combos += list(itertools.combinations(PHOTO, k))
    for raw in (False, True):
        for k in range(len(VIEW) + 1):
            for c in itertools.combinations(VIEW, k):
                combos.append(('POINT_FUSED',) + (('RAW_SOURCE',) if raw else ()) + c)
    bad = [c for c in combos if token_hash(with_defines(src, c)) != token_hash(with_defines(stripped, c))]
    print('1. FILM_SIM 0 against the shader without its FILM_SIM blocks: %d define sets, %d differ' % (len(combos), len(bad)))
    check(not bad, 'FILM_SIM 0 changes the shader with %s' % ', '.join('+'.join(c) or 'no define' for c in bad[:5]))
    blocks = len(re.findall(r'(?m)^#if FILM_SIM == 1$', src))
    check(blocks >= 4, 'expected the FILM_SIM blocks (uniforms, P3 codecs, the scene tap, the lookup): %d' % blocks)


# ------------------------------------------------------------------------------------------------ host references

def gridpos(x: np.ndarray, f: int):
    """why/combine gridPos: output pixel x on a grid reduced by a box of f -> base and fraction."""
    q = x // f
    r = x - q * f
    c = (2 * r + 1 - f) / (2.0 * f)
    return np.where(c < 0, q - 1, q), np.where(c < 0, c + 1, c)


def bweights(x: np.ndarray) -> np.ndarray:
    x2, x3 = x * x, x * x * x
    return np.stack([(1 - 3 * x + 3 * x2 - x3) / 6, (4 - 6 * x2 + 3 * x3) / 6, (1 + 3 * x + 3 * x2 - 3 * x3) / 6, x3 / 6])


def bspline(img: np.ndarray, f: int, H: int, W: int) -> np.ndarray:
    """The shader's cubic B-spline of a grid reduced by f, at every output pixel (taps clamped to the grid)."""
    if f == 1:
        return img.astype(np.float64)
    by, fy = gridpos(np.arange(H), f)
    bx, fx = gridpos(np.arange(W), f)
    wy, wx = bweights(fy), bweights(fx)
    h, w = img.shape[:2]
    out = np.zeros((H, W) + img.shape[2:])
    for dy in range(-1, 3):
        yy = np.clip(by + dy, 0, h - 1)
        for dx in range(-1, 3):
            xx = np.clip(bx + dx, 0, w - 1)
            wgt = wy[dy + 1][:, None] * wx[dx + 1][None, :]
            tap = img[yy][:, xx]
            out += (wgt[..., None] if img.ndim == 3 else wgt) * tap
    return out


def box(a: np.ndarray, f: int) -> np.ndarray:
    H, W = a.shape[:2]
    return a.reshape((H // f, f, W // f, f) + a.shape[2:]).mean((1, 3))


def smooth(a: np.ndarray, r: int) -> np.ndarray:
    """A (2r + 1)^2 box mean, edges clamped."""
    p = np.pad(a, r, mode='edge')
    c = np.cumsum(np.cumsum(p, 0), 1)
    c = np.pad(c, ((1, 0), (1, 0)))
    k = 2 * r + 1
    return (c[k:, k:] - c[:-k, k:] - c[k:, :-k] + c[:-k, :-k]) / (k * k)


def scene_expected(colour: np.ndarray, low: np.ndarray, f: int, fc: int, H: int, W: int, ae: float, exp: float) -> np.ndarray:
    """The FILM_SIM tap without detail: max(B-spline colour, 0) at the luminance of the delta's base, x ae x filmExpU."""
    orig = np.maximum(bspline(colour, fc, H, W), 0)
    cy = np.maximum(orig @ LUMA, 1e-6)
    if fc == f:
        base = cy
    else:
        base = np.maximum(bspline(np.maximum(low @ LUMA, 1e-6), f, H, W), 1e-6)
    return orig * (base / cy)[..., None] * ae * exp


def identity_u(n: int) -> np.ndarray:
    b, g, r = np.meshgrid(np.arange(n), np.arange(n), np.arange(n), indexing='ij')
    return (np.stack([r, g, b], -1) / (n - 1.0)).astype(np.float32)      # [b][g][r][rgb]: red fastest


def decode_u(u: np.ndarray) -> np.ndarray:
    return GREY * np.exp2(LO + SPAN * np.asarray(u, np.float64))


def colourful(n: int) -> np.ndarray:
    u = identity_u(n).astype(np.float64)
    t = np.stack([0.5 + 0.45 * np.sin(2 * np.pi * (0.9 * u[..., 0] + 0.3 * u[..., 2])),
                  0.5 + 0.45 * np.sin(2 * np.pi * (0.7 * u[..., 1] - 0.4 * u[..., 0]) + 1.0),
                  0.5 + 0.45 * np.cos(2 * np.pi * (0.8 * u[..., 2] + 0.2 * u[..., 1]))], -1)
    return np.clip(t, 0, 1).astype(np.float32)


def srgb_decode(v):
    v = np.asarray(v, np.float64)
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)


def srgb_encode(y):
    y = np.clip(np.asarray(y, np.float64), 0, 1)
    return np.where(y <= 0.0031308, 12.92 * y, 1.055 * y ** (1 / 2.4) - 0.055)


P3_TO_SRGB = np.array([[1.2249401763, -0.2249401763, 0.0], [-0.0420569547, 1.0420569547, 0.0],
                       [-0.0196375546, -0.0786360456, 1.0982736001]])


# ------------------------------------------------------------------------------------------------ GPU

class Gpu:
    def __init__(self, moderngl):
        self.mgl = moderngl
        try:
            self.ctx = moderngl.create_standalone_context(backend='egl', require=430)
        except Exception:
            self.ctx = moderngl.create_standalone_context(require=430)
        self.src = read(SH / 'why/combine.glsl')
        self.progs = {}
        self.signed = 0          # P370 signedHueU of every render (main runs the GPU parts with 0 and with the shipped 1)

    def program(self, defines, src=None):
        key = (tuple(sorted(defines.items())), src is not None)
        if key not in self.progs:
            text = self.src if src is None else src
            for k, v in defines.items():
                text = set_define(text, k, str(v))
            self.progs[key] = self.ctx.program(vertex_shader=VS, fragment_shader='#version 300 es\n' + expand_imports(text))
        return self.progs[key]

    def tex(self, a, linear=False):
        a = np.ascontiguousarray(a, np.float32)
        if a.ndim == 2:
            a = np.stack([a, np.zeros_like(a), np.zeros_like(a), np.ones_like(a)], -1)
        elif a.shape[2] == 3:
            a = np.concatenate([a, np.ones(a.shape[:2] + (1,), np.float32)], -1)
        t = self.ctx.texture((a.shape[1], a.shape[0]), 4, a.tobytes(), dtype='f4')
        f = self.mgl.LINEAR if linear else self.mgl.NEAREST
        t.filter = (f, f)
        return t

    def render(self, full, low=None, colour=None, fused=None, dref=None, defines=None, uni=None, lut=None, src=None, luma_s=None):
        """why/combine over full's grid; low / colour default to full (f 1); a FILM_SIM table when lut is given."""
        full = np.asarray(full, np.float32)
        H, W = full.shape[:2]
        low = full if low is None else low
        colour = low if colour is None else colour
        fused = np.full(low.shape[:2], 0.5, np.float32) if fused is None else fused
        dref = np.zeros(low.shape[:2], np.float32) if dref is None else dref
        p = self.program(dict(defines or {}), src)
        out = self.ctx.texture((W, H), 4, dtype='f4')
        fb = self.ctx.framebuffer([out])
        fb.use()
        self.ctx.viewport = (0, 0, W, H)
        ones = self.tex(np.ones((1, 1, 4), np.float32), True)
        made = [self.tex(full), self.tex(low), self.tex(colour), self.tex(fused), self.tex(dref), ones]
        if luma_s is not None:
            made.append(self.tex(luma_s))
        units = {'InputBuffer': made[0], 'GainMap': ones, 'WhyLow': made[1], 'WhyColour': made[2], 'WhyFused': made[3],
                 'WhyDetailRef': made[4], 'WhyLumaS': made[0] if luma_s is None else made[6], 'WhyHl': ones, 'SunBloom': ones}
        for i, (k, t) in enumerate(units.items()):
            if k in p:
                t.use(i)
                p[k].value = i
        vals = {'sensorToIntermediate': tuple(np.eye(3).flatten()), 'intermediateToSRGB': tuple(np.eye(3).flatten()),
                'neutralPointU': (1, 1, 1), 'inScaleU': 1.0, 'fU': 1, 'colourFU': 1, 'aeU': 1.0, 'clipU': 1.0, 'toeU': 0.05,
                'gammaInvU': 1 / 2.2, 'macroU': 1.1, 'vibU': (0.0, 0.40, 0.20), 'vibHlU': 1.0, 'chromaDnU': 0.0, 'clarityU': 0.0,
                'flatProtectU': 0.0, 'detailGainU': 0.0, 'deltaChromaU': 1.0, 'filmToeU': 0.10, 'ditherU': 0,
                'agxAU': (2.7, 1.35, 1.6, 1.0), 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'headroomU': 0, 'hlWhiteU': 0.15,
                'filmLogU': (LO, SPAN), 'fullInputU': 1, 'signedHueU': self.signed}
        vals.update(uni or {})
        t3 = None
        if lut is not None:
            n = lut.shape[0]
            t3 = self.ctx.texture3d((n, n, n), 3, np.ascontiguousarray(lut, np.float32).tobytes(), dtype='f4')
            t3.filter = (self.mgl.LINEAR, self.mgl.LINEAR)
            t3.repeat_x = t3.repeat_y = t3.repeat_z = False
            t3.use(12)
            p['FilmLut'].value = 12
            vals['lutU'] = (vals.pop('strength', 1.0), float(n))
        vals.pop('strength', None)
        for k, v in vals.items():
            if k in p:
                p[k].value = v
        self.ctx.vertex_array(p, []).render(vertices=3)
        res = np.frombuffer(out.read(), np.float32).reshape(H, W, 4)[..., :3].astype(np.float64)
        for t in made + [out]:
            t.release()
        fb.release()
        if t3 is not None:
            t3.release()
        return res


def tap_part(gpu: Gpu) -> None:
    """2. the identity-in-u table: the decoded scene light against the host's B-spline tap."""
    rng = np.random.default_rng(3)
    ae, deficit = 1.6, 1.3
    exp = deficit * METER_TO_FILM
    lut = identity_u(N)
    for f, fc in ((1, 1), (2, 2), (4, 2)):
        H, W = 64, 96
        # a smooth colour field (the B-spline's own interpolation is the reference, so any image works)
        yy, xx = np.mgrid[0:H, 0:W] / 16.0
        full = np.stack([0.04 + 0.5 * (0.5 + 0.5 * np.sin(xx + 0.3 * yy)), 0.03 + 0.4 * (0.5 + 0.5 * np.cos(0.7 * xx - yy)),
                         0.02 + 0.6 * (0.5 + 0.5 * np.sin(0.5 * yy + 2))], -1) * rng.uniform(0.6, 1.0, (H, W, 1))
        low = box(full, f) if f > 1 else full
        colour = box(full, fc) if fc > 1 else full
        out = gpu.render(full, low=low, colour=colour, defines={'FILM_SIM': 1},
                         uni={'aeU': ae, 'fU': f, 'colourFU': fc, 'filmExpU': exp, 'detailGainU': 0.0}, lut=lut)
        got = decode_u(out)
        want = scene_expected(colour, low, f, fc, H, W, ae, exp)
        rel = np.abs(got / want - 1)
        print('2. identity-in-u table at f %d / colourFU %d: max |decoded / (B-spline colour x ae x exp) - 1| %.5f (mean %.6f)'
              % (f, fc, rel.max(), rel.mean()))
        check(rel.max() <= 0.005, 'the film tap at f %d / colourFU %d is not the colour x ae x filmExpU (%.5f)' % (f, fc, rel.max()))


def textured(H, W, rng, ae):
    t = rng.normal(0, 1, (H + 8, W + 8))
    # 1 - 3 px structures (two box passes), unit RMS
    t = smooth(smooth(t, 1), 1)[4:-4, 4:-4]
    t /= t.std()
    Y = 0.15 / ae * (1 + 0.15 * t)
    col = np.array([1.05, 1.0, 0.92])
    return Y[..., None] * col / (col @ LUMA)


def detail_part(gpu: Gpu) -> None:
    """3. textured image: the film's luminance is the full-size luminance x ae x filmExpU; a colour-grid base fails."""
    rng = np.random.default_rng(11)
    ae, exp = 1.6, METER_TO_FILM
    lut = identity_u(N)
    H = W = 128
    full = textured(H, W, rng, ae)
    yfull = full @ LUMA
    ref = yfull * ae * exp
    c = (slice(12, -12), slice(12, -12))

    def hp(y):
        ly = np.log2(y)
        return np.sqrt(((ly - smooth(ly, 4))[c] ** 2).mean())

    alt_src = gpu.src.replace('float baseY = fc == f ? cy : max(yLow, 0.000001);', 'float baseY = cy;')
    check(alt_src != gpu.src, 'the FILM_SIM base line was not found (the colour-grid counter-check needs it)')
    for f, fc in ((2, 2), (4, 2)):
        low = box(full, f)
        colour = box(full, fc)
        dref = box(np.minimum(ae * yfull, 1.0), f)
        uni = {'aeU': ae, 'fU': f, 'colourFU': fc, 'detailGainU': 1.0, 'detailRefU': 1, 'sharpU': 0, 'filmExpU': exp}
        got = decode_u(gpu.render(full, low=low, colour=colour, dref=dref, defines={'FILM_SIM': 1}, uni=uni, lut=lut)) @ LUMA
        rms, mean = hp(got) / hp(ref) - 1, got[c].mean() / ref[c].mean() - 1
        print('3. textured, f %d / colourFU %d: film luminance against Y x ae x exp: texture RMS %+.2f %%, mean %+.3f %%, '
              'per pixel p99 %.3f %%' % (f, fc, 100 * rms, 100 * mean, 100 * np.percentile(np.abs(got[c] / ref[c] - 1), 99)))
        check(abs(rms) <= 0.03 and abs(mean) <= 0.005, 'the film tap loses or doubles the full-size detail at f %d (RMS %+.3f, mean %+.4f)'
              % (f, rms, mean))
        if fc != f:
            alt = decode_u(gpu.render(full, low=low, colour=colour, dref=dref, defines={'FILM_SIM': 1}, uni=uni, lut=lut,
                                      src=alt_src)) @ LUMA
            arms = hp(alt) / hp(ref) - 1
            print('   counter-check, the colour grid\'s own luminance as the base: texture RMS %+.1f %% (must fail)' % (100 * arms))
            check(abs(arms) > 0.03, 'the counter-check (a colour-grid base on the 2x grid) passes: the check sees no double band')


def edge_part(gpu: Gpu) -> None:
    """7. a dark edge and a 2 px dark bar next to light above the detail bound (a Bento sky, a window, a lamp): the film's
    luminance on the dark side is the full-size luminance x ae x filmExpU (the ratio is measured on the bounded reference, the base
    is not: before the fix the dark side took the bright side's energy, +7 .. +67 L* against the model)."""
    lut = identity_u(N)
    exp = METER_TO_FILM
    H, W = 32, 96
    worst = 0.0
    for sharp in (0, 1):
        for full_input in (1, 0):
            for f, fc in ((2, 2), (4, 2)):
                for ae in (0.6, 1.6):
                    for light in (1.5, 3.0, 8.0):        # x the bound: data units, m = 1 (G_CLEAN, the shipped default)
                        for x0 in (49, 51):
                            for bar in (False, True):
                                Y = np.full((H, W), light if bar else 0.15 / ae)
                                if bar:
                                    Y[:, x0:x0 + 2] = 0.03 / ae
                                else:
                                    Y[:, x0:] = light
                                full = Y[..., None] * np.ones(3)
                                low, colour = box(full, f), box(full, fc)
                                if sharp:
                                    ya = np.minimum(full @ LUMA, 1.0)        # S(Ya) of WhyLumaSharpen, unsharpened: min(m Y, 1)
                                    uni = {'sharpU': 1, 'sharpToneU': ae}
                                else:
                                    ya = np.minimum(ae * (full @ LUMA), 1.0)
                                    uni = {'sharpU': 0}
                                uni.update({'aeU': ae, 'fU': f, 'colourFU': fc, 'detailGainU': 1.0, 'detailRefU': 1, 'clipU': 8.0,
                                            'fullInputU': full_input, 'filmExpU': exp})
                                got = decode_u(gpu.render(full, low=low, colour=colour, dref=box(ya, f), defines={'FILM_SIM': 1},
                                                          uni=uni, lut=lut, luma_s=ya if sharp else None)) @ LUMA
                                dark = (slice(8, -8), slice(x0, x0 + 2) if bar else slice(x0 - 8, x0))
                                rel = np.abs(got[dark] / (Y[dark] * ae * exp) - 1).max()
                                worst = max(worst, rel)
                                check(rel <= 0.02, 'the film\'s dark %s next to light %.1f x the bound (sharpU %d, fullInput %d, f %d / '
                                      'colourFU %d, ae %.1f, x0 %d) is %.1f %% off the scene light'
                                      % ('bar' if bar else 'edge', light, sharp, full_input, f, fc, ae, x0, 100 * rel))
    print('7. dark edges and 2 px bars next to light 1.5 / 3 / 8 x the bound: max |film / (Y x ae x exp) - 1| %.4f' % worst)


def strength_part(gpu: Gpu) -> None:
    """4. strength 0.5 = mean of ours and the film; the film alone does not move with our tone."""
    rng = np.random.default_rng(7)
    H, W = 32, 48
    full = rng.uniform(0.003, 1.2, (H, W, 3)) * rng.uniform(0.2, 1.0, (H, W, 1))
    lut = colourful(N)
    uni = {'aeU': 1.4, 'filmExpU': METER_TO_FILM}
    ours = gpu.render(full, uni=uni)
    film = gpu.render(full, defines={'FILM_SIM': 1}, uni=dict(uni, strength=1.0), lut=lut)
    half = gpu.render(full, defines={'FILM_SIM': 1}, uni=dict(uni, strength=0.5), lut=lut)
    err = np.abs(half - 0.5 * (ours + film)).max()
    print('4. strength 0.5: max |out - (ours + film) / 2| %.2e; ours and the film differ by up to %.3f' % (err, np.abs(ours - film).max()))
    check(err <= 1e-4, 'strength 0.5 is not the mean of ours and the film (%.2e)' % err)
    check(np.abs(ours - film).max() > 0.05, 'the test table does not change the picture (the mix check would prove nothing)')
    fused = rng.uniform(0.1, 0.9, (H, W)).astype(np.float32)
    other = {'macroU': 1.5, 'vibU': (0.6, 0.8, 0.5), 'vibHlU': 2.0, 'gammaInvU': 1 / 2.8, 'filmToeU': 0.4, 'hlWhiteU': 0.8,
             'agxAU': (3.2, 1.1, 1.3, 1.4), 'agxBU': (-6.0, 2.5, 0.8, 3.0), 'toeU': 0.12, 'headroomU': 1, 'clipU': 3.0,
             'hlSoftU': 0.7, 'toneOpU': 1, 'acesU': (1.2, 2.4, 0.6, 0.06), 'monoU': (0.0, 0.0, 0.0), 'gamutU': 0.6, 'hueLockU': 1,
             'gradeAU': (0.02, -0.01, 0.01, 0.0), 'gradeBU': (0.0, 0.02, 0.3, 0.5), 'skinU': (0.5, 0.2, 0.3), 'vibLU': (0.3, -0.2)}
    alone = gpu.render(full, fused=fused, defines={'FILM_SIM': 1, 'COLOUR_LOOK': 1, 'TONE_OP': 1, 'HL_LANDING': 1},
                       uni=dict(uni, strength=1.0, **other), lut=lut)
    moved = np.abs(alone - film).max()
    print('   the film alone under another tone (fusion, macro, vibrance, gamma, AgX / ACES, toe, colour look, landing): max |d| %.2e'
          % moved)
    check(moved <= 1e-6, 'the film alone moves with our tone (%.2e)' % moved)


def p3_part(gpu: Gpu) -> None:
    """5. P3_OUT 1 decoded to sRGB equals the sRGB output (a colourful table)."""
    rng = np.random.default_rng(5)
    H, W = 32, 64
    full = rng.uniform(0.002, 1.2, (H, W, 3))
    lut = colourful(N)
    uni = {'aeU': 1.0, 'filmExpU': METER_TO_FILM, 'strength': 1.0}
    s = gpu.render(full, defines={'FILM_SIM': 1}, uni=uni, lut=lut)
    p = gpu.render(full, defines={'FILM_SIM': 1, 'P3_OUT': 1}, uni=uni, lut=lut)
    back = srgb_encode(srgb_decode(p) @ P3_TO_SRGB.T)
    d = np.abs(back - s).max() * 255
    print('5. P3_OUT 1 decoded through P3_TO_SRGB against P3_OUT 0: max |d| %.3f / 255' % d)
    check(d <= 0.5, 'the film\'s P3 file is not its sRGB print in P3 primaries (%.3f / 255)' % d)


VARIANTS = ((), ('P3_OUT',), ('COLOUR_LOOK',), ('TONE_OP',), ('HL_LANDING',), ('HEADROOM_WIDE',), ('SUN_BLOOM',),
            ('P3_OUT', 'COLOUR_LOOK', 'TONE_OP', 'HL_LANDING', 'HEADROOM_WIDE', 'SUN_BLOOM'), ('POINT_FUSED',),
            ('POINT_FUSED', 'P3_OUT', 'COLOUR_LOOK', 'TONE_OP'), ('POINT_FUSED', 'RAW_SOURCE'),
            ('POINT_FUSED', 'RAW_SOURCE', 'P3_OUT', 'COLOUR_LOOK', 'TONE_OP'))


def compile_part(gpu, strict: bool) -> None:
    """6. every FILM_SIM variant compiles: moderngl, and glslc as GLProg hands it to the driver."""
    src = read(SH / 'why/combine.glsl')
    if gpu is not None:
        vs_raw = read(SH / 'preview/rawdevelop_vs.glsl').replace('#version 300 es', '#version 310 es', 1)
        ok = 0
        for v in VARIANTS:
            defs = (('FILM_SIM', '1'),) + tuple((d, '1') for d in v)
            try:
                if 'RAW_SOURCE' in v:
                    gpu.ctx.program(vertex_shader=vs_raw, fragment_shader=read_program(src, defs)).release()
                else:
                    gpu.ctx.program(vertex_shader=VS, fragment_shader=read_program(src, defs).replace('#version 310 es', '#version 300 es', 1)).release()
                ok += 1
            except Exception as e:
                check(False, 'moderngl: FILM_SIM + %s does not compile: %s' % ('+'.join(v) or '-', str(e).strip()[:300]))
        print('6. moderngl: %d / %d FILM_SIM variants compile and link' % (ok, len(VARIANTS)))
    glslc = find_glslc()
    if not glslc:
        (FAILURES if strict else SKIPPED).append('glslc not found: the FILM_SIM variants were not compiled for the device')
        print('   glslc not found: the offline compile was skipped')
        return
    ok = 0
    with tempfile.TemporaryDirectory() as d:
        for i, v in enumerate(VARIANTS):
            f = Path(d) / ('%02d.frag' % i)
            f.write_text(read_program(src, (('FILM_SIM', '1'),) + tuple((x, '1') for x in v)), encoding='utf-8')
            r = subprocess.run([glslc, '--target-env=opengl', '-fshader-stage=fragment', '-std=310es', '-fauto-map-locations',
                                '-fauto-bind-uniforms', '-o', os.devnull, str(f)], capture_output=True, text=True)
            check(r.returncode == 0, 'glslc: FILM_SIM + %s does not compile:\n%s' % ('+'.join(v) or '-', r.stderr))
            ok += r.returncode == 0
    print('   glslc (310 es, as GLProg): %d / %d FILM_SIM variants compile' % (ok, len(VARIANTS)))


def main(argv) -> int:
    strict = '--strict' in argv
    token_part(read(SH / 'why/combine.glsl'))
    try:
        import moderngl
    except ImportError:
        moderngl = None
        FAILURES.append('moderngl missing: the GPU parts were not run')
        print('moderngl missing: the GPU parts were not run')
    gpu = Gpu(moderngl) if moderngl is not None else None
    if gpu is not None:
        print('GPU: %s (%s)' % (gpu.ctx.info['GL_RENDERER'], gpu.ctx.info['GL_VERSION']))
        # P370 (review): the GPU parts with the signed hue off (signedHueU 0 / unset) and on (the shipped default 1)
        for signed in (0, 1):
            gpu.signed = signed
            print('-- signedHueU %d' % signed)
            tap_part(gpu)
            detail_part(gpu)
            strength_part(gpu)
            p3_part(gpu)
            edge_part(gpu)
    compile_part(gpu, strict)
    for s in SKIPPED:
        print('skipped: ' + s)
    if FAILURES:
        print('film simulation check FAILED (%d)' % len(FAILURES))
        return 1
    print('film simulation check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
