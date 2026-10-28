#!/usr/bin/env python3
"""P370 (B2): colours the camera matrix puts outside sRGB keep their hue - the SHIPPED why/combine.glsl on the GPU (moderngl).

The colour source after the camera matrix is signed: a saturated LED, neon or a deep sky has a negative channel. why/combine
clamped it per channel before OKLab, so the hue direction, the hue lock and «Сжатие охвата» worked on the clipped colour (470 nm
read +27 deg, 505 nm -20 deg). With signedHueU 1 the lightness and hue come from the signed colour (all of it from a channel
at -1 % of the largest, or where the clip changed a tap by that much; no luminance gate since the review) and the final gamut
mapping fades its chroma cut in. Checks (the kernel's full-resolution path fU 1 unless said; fU 2 = the photo's B-spline):
- LEDs 450 - 660 nm (CIE 1931 multi-lobe fit, 20 nm FWHM, luminance 0.12) and saturated camera colours through the OPPO X8U
  and vivo X200U ISP matrices (luminance 0.10), sRGB and Display P3 (there the colours outside P3): |hue error| < 1 deg
  against the signed colour's OKLab hue (the former shader's errors are printed);
- 310 colours inside sRGB (a hue circle, skin, greys up to 4 x white; the default vibrance) render bit for bit as with
  signedHueU 0, and signedHueU 0 is the shader without the P370 code on every colour - bit for bit for sRGB, within 2e-6 for
  P3 (llvmpipe contracts the P3 matrix products differently around the new branch: no 8-bit code moves);
- monochrome (COLOUR_LOOK 1, a filter) stays grey: |a|, |b| < 1e-4;
- shadow noise (sigma 0.0025, exposure x 4; dark red, teal, grey; at fU 2 a dim 470 nm patch too, where the weight is 1):
  OKLab chroma RMS <= 1.05 x the former (the per-pixel path without the gate: x1.048 on dark red);
- «Сжатие охвата» (gamutU 1) with signedHueU 1 acts on the colours outside sRGB, which keep their hue (< 1 deg), and the
  colours inside sRGB render with it as with signedHueU 0, bit for bit;
- the RAW viewfinder's linear frame (rawdevelop_fs u_signed_hue 1, the shipped line): every colour as color_transform made it
  (signed: why/combine takes it as the photo's), u_signed_hue 0 the clamp at 0 (tools/check_vf_fused.py: one pass = two);
- the review (P370): a luminance sweep of LEDs / an X8U camera blue at fU 1 and fU 2 - hue within 2 deg of the signed colour's
  wherever OKLab C > 0.02 at an exposed luminance >= 0.03 (below, the shadow tone moves every hue - an in-gamut green-cyan by up
  to 7 deg, printed), the largest step of a 4000-step ramp <= 8 codes (the luminance gate: 36); an edge of the colour on a
  lighter ground at fU 2: its rim within 1 deg of the signed B-spline mean's hue (the clipped taps: 14 - 28 deg); textures inside
  sRGB bit for bit at fU 2 too (sRGB and P3).
Usage: check_ccm_gamut.py [--report]
"""
from pathlib import Path
import re
import sys
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_colour_output import SH, P2S, expand_imports, set_define, without_signed_hue  # noqa: E402

REPORT = '--report' in sys.argv
failures = []


def check(cond, msg):
    if not cond:
        failures.append(msg)
        print('FAIL: ' + msg)


# ---------------------------------------------------------------------------------------------------------- colours
XYZ_TO_SRGB = np.array([[3.2404542, -1.5371385, -0.4985314], [-0.9692660, 1.8760108, 0.0415560], [0.0556434, -0.2040259, 1.0572252]])
LUMA = np.array([0.2126, 0.7152, 0.0722])
M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
               [0.0883024619, 0.2817188376, 0.6299787005]])
M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
               [0.0259040371, 0.7827717662, -0.8086757660]])


def oklab_signed(c):
    lms = c @ M1.T
    return np.cbrt(lms) @ M2.T     # numpy's cbrt keeps the sign


def hue(lab):
    return np.degrees(np.arctan2(lab[:, 2], lab[:, 1]))


def g(lam, mu, s1, s2):
    s = np.where(lam < mu, s1, s2)
    return np.exp(-0.5 * ((lam - mu) / s) ** 2)


def cmf(lam):
    x = 1.056 * g(lam, 599.8, 37.9, 31.0) + 0.362 * g(lam, 442.0, 16.0, 26.7) - 0.065 * g(lam, 501.1, 20.4, 26.2)
    y = 0.821 * g(lam, 568.8, 46.9, 40.5) + 0.286 * g(lam, 530.9, 16.3, 31.1)
    z = 1.217 * g(lam, 437.0, 11.8, 36.0) + 0.681 * g(lam, 459.0, 26.0, 13.8)
    return np.stack([x, y, z], -1)


LAM = np.arange(380.0, 781.0, 1.0)
CMF = cmf(LAM)
LEDS = np.unique(np.concatenate([np.arange(450, 661, 15), [470, 505, 525, 590]]))   # the plan's 470 / 505 / 525 / 590 nm too


def led(peak, y=0.12):
    s = np.exp(-0.5 * ((LAM - peak) / (20 / 2.3548)) ** 2)
    xyz = s @ CMF
    return XYZ_TO_SRGB @ (xyz / xyz[1] * y)


def rows1(m):
    m = np.array(m, float).reshape(3, 3)
    return m / m.sum(1, keepdims=True)


X8U = rows1([200, -62, -10, -17, 172, -27, 8, -70, 191])      # SCAM-20261027-175141 (camera 4)
X200U = rows1([153, -19, -6, -17, 175, -30, 0, -55, 184])     # camera 3's plain result (MosaicNeutralResolver.ispIdle note)
CAMERA = np.array([[1, .08, .06], [.06, 1, .08], [.05, .12, 1], [.9, .9, .05], [.05, .9, .9], [.9, .05, .9], [1, .45, .02], [.02, .5, 1]])
leds = np.array([led(p) for p in LEDS])
cams = np.concatenate([CAMERA @ X8U.T, CAMERA @ X200U.T])
cams = cams / (np.maximum(cams, 0) @ LUMA)[:, None] * 0.10   # a mid tone (a dim navy stays on the clip: the luminance ramp)
wide = np.concatenate([leds, cams])


def inside_set():
    out = []
    for L in (0.45, 0.62, 0.78):
        for h in np.radians(np.arange(0, 360, 5)):
            lab = np.array([L, 0.07 * np.cos(h), 0.07 * np.sin(h)])
            lms = (lab @ np.linalg.inv(M2).T) ** 3
            out.append(lms @ np.linalg.inv(M1).T)
    rng = np.random.default_rng(370)
    skin = np.array([0.55, 0.38, 0.30]) * (1 + 0.15 * rng.standard_normal((40, 3))) * rng.uniform(0.2, 1.2, (40, 1)) ** 2
    greys = np.repeat(np.geomspace(0.002, 4.0, 54)[:, None], 3, 1)
    c = np.concatenate([np.array(out), skin, greys])
    keep = c.min(1) >= 0.025 * c.max(1)          # no channel below the ramp's start (2 %)
    return c[keep][:310]


inside = inside_set()

# ---------------------------------------------------------------------------------------------------------- GPU
import moderngl  # noqa: E402

try:
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
except Exception:
    ctx = moderngl.create_standalone_context(require=430)
SRC = expand_imports((SH / 'why/combine.glsl').read_text(encoding='utf-8'))
VS = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
grid = np.linspace(0, 2.51 / 2.43 - 0.0101, 20000)


def inverse_aces(v, toe=0.05):
    b = max(toe * 0.75, 0.005)
    e = max(0.14 - (toe - 0.04) * 0.8, 0.03)
    A, B, C = 2.43 * v - 2.51, 0.59 * v - b, e * v
    return np.maximum((-B - np.sqrt(np.maximum(B * B - 4 * A * C, 0))) / (2 * A - 1e-6) / 1.5, 0)


TABLE = inverse_aces(grid)


def render(colours, p3=False, signed=1, ae=1.0, fused_from=None, look=None, shader=SRC):
    """why/combine at fU 1 on {@code colours} (linear sRGB, signed); the fused luma lands each at its own luminance x ae."""
    colours = np.asarray(colours, np.float32)
    n = len(colours)
    w = 64
    h = -(-n // w)
    img = np.zeros((h, w, 4), np.float32)
    img.reshape(-1, 4)[:n, :3] = colours
    base = colours if fused_from is None else np.asarray(fused_from, np.float32)
    y = np.maximum(np.maximum(np.maximum(base, 0) @ LUMA, np.maximum(base, 0).max(1) * 0.5), 1e-4) * ae
    fused = np.zeros((h, w, 4), np.float32)
    fused.reshape(-1, 4)[:n, 0] = np.interp(np.minimum(y, TABLE.max()), TABLE, grid) ** (1 / 2.2)

    def tex(a):
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t
    src = set_define(shader, 'P3_OUT', '1' if p3 else '0')
    if look is not None:
        src = set_define(src, 'COLOUR_LOOK', '1')
    prog = ctx.program(vertex_shader=VS, fragment_shader='#version 300 es\n' + src)
    out = ctx.texture((w, h), 4, dtype='f4')
    fb = ctx.framebuffer([out])
    fb.use()
    ctx.viewport = (0, 0, w, h)
    t_img, t_fused, ones = tex(img), tex(fused), tex(np.ones((1, 1, 4), np.float32))
    for i, (k, t) in enumerate({'InputBuffer': t_img, 'GainMap': ones, 'WhyLow': t_img, 'WhyColour': t_img, 'WhyFused': t_fused,
                                'WhyDetailRef': t_img, 'WhyLumaS': t_img}.items()):
        if k in prog:
            t.use(i)
            prog[k].value = i
    eye = tuple(np.eye(3).flatten())
    vals = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0, 'fU': 1,
            'colourFU': 1, 'aeU': ae, 'clipU': 1.0, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.1, 'vibU': (0.0, 0.4, 0.2),
            'detailGainU': 0.0, 'deltaChromaU': 1.0, 'filmToeU': 0.1, 'ditherU': 0, 'agxAU': (2.7, 1.35, 1.6, 1.0),
            'agxBU': (-8.5, 3.5, 0.3, 4.0), 'headroomU': 0, 'hlWhiteU': 0.15, 'signedHueU': signed}
    vals.update(look or {})
    for k, v in vals.items():
        if k in prog:
            prog[k].value = v
    ctx.vertex_array(prog, []).render(vertices=3)
    res = np.frombuffer(out.read(), np.float32).reshape(h, w, 4)[:, :, :3].reshape(-1, 3)[:n].astype(np.float64)
    for o in (fb, out, prog, t_img, t_fused, ones):
        o.release()
    lin = np.maximum(res, 0) ** 2.2
    return res, (lin @ P2S.T if p3 else lin)


def hue_error(colours, lin):
    d = hue(oklab_signed(lin)) - hue(oklab_signed(colours))
    return (d + 180) % 360 - 180


# 1. hue of the colours outside sRGB
for label, p3 in (('sRGB', False), ('Display P3', True)):
    _, before = render(wide, p3, signed=0)
    _, after = render(wide, p3, signed=1)
    eb, ea = hue_error(wide, before), hue_error(wide, after)
    names = ['%d nm' % p for p in LEDS] + ['X8U cam %d' % i for i in range(len(CAMERA))] + ['X200U cam %d' % i for i in range(len(CAMERA))]
    # a colour P3 holds already is no case for its render (clampP3 kept it): those are listed, not judged
    outside = (wide @ np.linalg.inv(P2S).T).min(1) < -0.002 if p3 else np.ones(len(wide), bool)
    print('%s: %d colours outside %s; hue error before: max %.1f deg (%s), after: max %.2f deg' % (
        label, outside.sum(), 'P3' if p3 else 'sRGB', np.abs(eb[outside]).max(), ', '.join(
            '%s %+.0f' % (names[i], eb[i]) for i in np.argsort(-np.abs(eb))[:5] if outside[i]), np.abs(ea[outside]).max()))
    if REPORT:
        for i, nm in enumerate(names):
            print('   %-14s before %+7.2f after %+6.2f%s' % (nm, eb[i], ea[i], '' if outside[i] else ' (inside P3)'))
    check(np.abs(ea[outside]).max() < 1.0, '%s: a colour outside the gamut is %.2f deg off its hue' % (label, np.abs(ea[outside]).max()))
    if not p3:
        check(np.abs(eb).max() > 5.0, 'the fixture shows no hue error of the former shader (%.1f deg)' % np.abs(eb).max())

# 2. inside sRGB: bit for bit; signedHueU 0 = the shader without the P370 code
for p3 in (False, True):
    r1, _ = render(inside, p3, signed=1)
    r0, _ = render(inside, p3, signed=0)
    check(np.array_equal(r1, r0), 'P3_OUT %d: a colour inside sRGB renders differently with signedHueU 1' % p3)
    allc = np.concatenate([inside, wide])
    z0, _ = render(allc, p3, signed=0)
    zb, _ = render(allc, p3, signed=0, shader=expand_imports(without_signed_hue((SH / 'why/combine.glsl').read_text(encoding='utf-8'))))
    if p3:
        check(np.abs(z0 - zb).max() < 2e-6, 'P3_OUT 1: signedHueU 0 is not the shader without the P370 code (%.2e)' % np.abs(z0 - zb).max())
    else:
        check(np.array_equal(z0, zb), 'P3_OUT 0: signedHueU 0 is not the shader without the P370 code')
    zmax = np.abs(z0 - zb).max()
print('inside sRGB: %d colours (hue circle, skin, greys to 4 x white) bit for bit with signedHueU 1, sRGB and P3; signedHueU 0 = '
      'the shader without P370 on %d colours (sRGB bit for bit, P3 within %.1e)' % (len(inside), len(inside) + len(wide), zmax))

# 3. monochrome stays grey
mono = {'monoU': (0.9, 0.7, 0.2)}
_, m = render(wide, False, signed=1, look=mono)
ab = np.abs(oklab_signed(m)[:, 1:]).max()
print('monochrome (COLOUR_LOOK 1, a filter): max |a|, |b| of the colours outside sRGB %.2e' % ab)
check(ab < 1e-4, 'monochrome is not grey with signedHueU 1 (%.2e)' % ab)

# 4. shadow noise
rng = np.random.default_rng(7)
ratios = []
for name, base in (('dark red', [0.020, 0.0040, 0.0035]), ('teal', [0.0035, 0.016, 0.014]), ('grey', [0.008, 0.008, 0.008])):
    b = np.tile(np.array(base), (4096, 1))
    noisy = b + 0.0025 * rng.standard_normal(b.shape)
    _, o0 = render(noisy, False, signed=0, ae=4.0, fused_from=b)
    _, o1 = render(noisy, False, signed=1, ae=4.0, fused_from=b)

    def rms(lin):
        lab = oklab_signed(lin)[:, 1:]
        return np.sqrt(np.mean(np.sum((lab - lab.mean(0)) ** 2, 1)))
    r = rms(o1) / rms(o0)
    ratios.append(r)
    print('shadow noise %-8s: chroma RMS %.4f -> %.4f (x%.3f), %.0f %% of the pixels with a negative channel'
          % (name, rms(o0), rms(o1), r, 100 * (noisy.min(1) < 0).mean()))
    check(r <= 1.05, 'shadow noise of %s: chroma RMS x%.3f (> 1.05)' % (name, r))

# 5. «Сжатие охвата»
gam = {'gamutU': 1.0}
w0, _ = render(wide, False, signed=1, look={})
w1, lw1 = render(wide, False, signed=1, look=gam)
i1, _ = render(inside, False, signed=1, look=gam)
i0, _ = render(inside, False, signed=0, look=gam)
moved = np.abs(w1 - w0).max(1) * 255
eg = np.abs(hue_error(wide, lw1)).max()
print('gamut compression with signedHueU 1: %d of %d colours outside sRGB change (max %.1f codes), hue error max %.2f deg; '
      'inside sRGB as with signedHueU 0: %s' % ((moved > 0.5).sum(), len(wide), moved.max(), eg, np.array_equal(i1, i0)))
check((moved > 0.5).sum() >= 5, 'gamut compression does not act on the colours outside sRGB')
check(eg < 1.0, 'gamut compression moved the hue of a colour outside sRGB (%.2f deg)' % eg)
check(np.array_equal(i1, i0), 'with gamut compression a colour inside sRGB renders differently with signedHueU 1')

# 6. the RAW viewfinder's linear frame (rawdevelop_fs u_signed_hue 1): the shipped line keeps every colour signed (the photo's
# signed OKLab then reaches the two-pass viewfinder as well), u_signed_hue 0 is the clamp at 0
dev = (SH / 'preview/rawdevelop_fs.glsl').read_text(encoding='utf-8').replace('\r\n', '\n')
m = re.search(r'        vec3 linOut = color_transform \* cC;\n'
              r'(        FragColor = vec4\(u_signed_hue == 1 \? linOut : max\(linOut, vec3\(0\.0\)\), 1\.0\);\n)', dev)
check(m is not None, 'rawdevelop_fs.glsl lost the P370 signed linear frame (u_signed_hue)')
if m:
    body = m.group(1)
    fs = ('#version 300 es\nprecision highp float;\nprecision highp sampler2D;\nuniform sampler2D C;\nuniform int u_signed_hue;\nout vec4 FragColor;\n'
          'void main(){vec3 linOut=texelFetch(C,ivec2(gl_FragCoord.xy),0).rgb;\n' + body + '}')
    cols = np.concatenate([inside, wide]).astype(np.float32)
    n = len(cols)
    w = 64
    h = -(-n // w)
    a = np.zeros((h, w, 4), np.float32)
    a.reshape(-1, 4)[:n, :3] = cols

    def dev_render(flag):
        prog = ctx.program(vertex_shader=VS, fragment_shader=fs)
        t = ctx.texture((w, h), 4, a.tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        out = ctx.texture((w, h), 4, dtype='f4')
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, w, h)
        t.use(0)
        prog['C'].value = 0
        prog['u_signed_hue'].value = flag
        ctx.vertex_array(prog, []).render(vertices=3)
        r = np.frombuffer(out.read(), np.float32).reshape(h, w, 4)[:, :, :3].reshape(-1, 3)[:n].astype(np.float64)
        for o in (fb, out, t, prog):
            o.release()
        return r
    on, off = dev_render(1), dev_render(0)
    k = len(inside)
    check(np.array_equal(on, cols.astype(np.float64)), 'rawdevelop_fs u_signed_hue 1 is not the signed colour')
    check(np.array_equal(off, np.maximum(cols, 0)), 'rawdevelop_fs u_signed_hue 0 is not the clamp at 0')
    print('RAW viewfinder linear frame: u_signed_hue 1 keeps the %d colours outside sRGB signed (min channel %.3f), u_signed_hue 0 '
          'is the clamp at 0' % (len(wide), on[k:].min()))


# ---------------------------------------------------------------------------------------------------------- the review (P370)
def render2(low, f, signed=1, ae=1.0, fused=None, p3=False):
    """why/combine on a 2-D colour grid {@code low} (h, w, 3: WhyColour = WhyLow, the fused luma from its clipped colour unless
    given): fU = colourFU = f, the output (h f, w f, 3) display values."""
    low = np.asarray(low, np.float32)
    h, w, _ = low.shape
    H, W = h * f, w * f
    c = np.maximum(low.astype(np.float64), 0)
    if fused is None:
        y = np.maximum(np.maximum(c @ LUMA, c.max(-1) * 0.5), 1e-4) * ae
        fused = np.interp(np.minimum(y, TABLE.max()), TABLE, grid) ** (1 / 2.2)
    fz = np.zeros((h, w, 4), np.float32)
    fz[..., 0] = fused

    def tex(a):
        a = np.ascontiguousarray(a, np.float32)
        if a.shape[-1] == 3:
            a = np.concatenate([a, np.ones(a.shape[:-1] + (1,), np.float32)], -1)
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t
    prog = ctx.program(vertex_shader=VS, fragment_shader='#version 300 es\n' + set_define(SRC, 'P3_OUT', '1' if p3 else '0'))
    out = ctx.texture((W, H), 4, dtype='f4')
    fb = ctx.framebuffer([out])
    fb.use()
    ctx.viewport = (0, 0, W, H)
    full = np.repeat(np.repeat(low, f, 0), f, 1)
    ts = {'InputBuffer': tex(full), 'GainMap': tex(np.ones((1, 1, 4))), 'WhyLow': tex(low), 'WhyColour': tex(low), 'WhyFused': tex(fz),
          'WhyDetailRef': tex(low), 'WhyLumaS': tex(full)}
    for i, (k, t) in enumerate(ts.items()):
        if k in prog:
            t.use(i)
            prog[k].value = i
    eye = tuple(np.eye(3).flatten())
    vals = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0, 'fU': f,
            'colourFU': f, 'aeU': ae, 'clipU': 1.0, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.1, 'vibU': (0.0, 0.4, 0.2),
            'detailGainU': 0.0, 'deltaChromaU': 1.0, 'filmToeU': 0.1, 'ditherU': 0, 'agxAU': (2.7, 1.35, 1.6, 1.0),
            'agxBU': (-8.5, 3.5, 0.3, 4.0), 'headroomU': 0, 'hlWhiteU': 0.15, 'signedHueU': signed}
    for k, v in vals.items():
        if k in prog:
            prog[k].value = v
    ctx.vertex_array(prog, []).render(vertices=3)
    res = np.frombuffer(out.read(), np.float32).reshape(H, W, 4)[..., :3].astype(np.float64)
    for o in [fb, out, prog] + list(ts.values()):
        o.release()
    return res


def lin_of(disp, p3=False):
    lin = np.maximum(disp, 0) ** 2.2
    return lin @ P2S.T if p3 else lin


def chroma_rms(lin):
    lab = oklab_signed(lin.reshape(-1, 3))[:, 1:]
    return np.sqrt(np.mean(np.sum((lab - lab.mean(0)) ** 2, 1)))


# 7. shadow noise on the photo's B-spline (fU 2), with a dim 470 nm patch whose signed weight is 1
rng = np.random.default_rng(11)
dim470 = led(470)
dim470 = dim470 / (np.maximum(dim470, 0) @ LUMA) * 0.012
for name, base in (('dark red', [0.020, 0.0040, 0.0035]), ('teal', [0.0035, 0.016, 0.014]), ('grey', [0.008, 0.008, 0.008]),
                   ('dim 470 nm', dim470)):
    b = np.tile(np.array(base, float), (48, 48, 1))
    noisy = b + 0.0025 * rng.standard_normal(b.shape)
    cb = np.maximum(b, 0)
    fz = np.interp(np.minimum(np.maximum(np.maximum(cb @ LUMA, cb.max(-1) * 0.5), 1e-4) * 4.0, TABLE.max()), TABLE, grid) ** (1 / 2.2)
    o0 = render2(noisy, 2, signed=0, ae=4.0, fused=fz)[8:-8, 8:-8]
    o1 = render2(noisy, 2, signed=1, ae=4.0, fused=fz)[8:-8, 8:-8]
    r = chroma_rms(lin_of(o1)) / chroma_rms(lin_of(o0))
    print('shadow noise fU 2 %-10s: chroma RMS x%.3f' % (name, r))
    check(r <= 1.05, 'shadow noise of %s at fU 2: chroma RMS x%.3f (> 1.05)' % (name, r))

# 8. a luminance sweep of one chromaticity: no hue change along a light's own falloff, no step
X8U_BLUE = X8U @ np.array([.05, .12, 1])
ys = np.geomspace(0.005, 0.3, 24)
for name, col in (('470 nm', led(470)), ('505 nm', led(505)), ('525 nm', led(525)), ('X8U deep blue', X8U_BLUE),
                  ('in-gamut green-cyan', np.array([0.02, 0.9, 0.35]))):
    col = col / (np.maximum(col, 0) @ LUMA)
    want = hue(oklab_signed(col[None]))[0]
    errs = []
    for f in (1, 2):
        low = np.repeat((col * ys[:, None])[None], 4, 0).repeat(4, 1)
        o = render2(low, f, signed=1)[2 * f - 1, 2 * f::4 * f]
        lab = oklab_signed(lin_of(o))
        err = (hue(lab) - want + 180) % 360 - 180
        c = np.hypot(lab[:, 1], lab[:, 2])
        sel = (c > 0.02) & (ys >= 0.03)
        errs.append('fU %d: %.2f deg at Y >= 0.03 (below: max %.1f)' % (f, np.abs(err[sel]).max(), np.abs(err[(c > 0.02) & (ys < 0.03)]).max()))
        if 'in-gamut' not in name:
            check(np.abs(err[sel]).max() < 2.0, '%s at fU %d: the hue moves %.2f deg along the luminance' % (name, f, np.abs(err[sel]).max()))
    ramp = (col * np.geomspace(0.005, 0.3, 4000)[:, None])
    o = render(ramp, False, signed=1)[0]
    step = np.abs(np.diff(o, axis=0)).max() * 255
    print('luminance sweep %-19s %s; 4000-step ramp max step %.1f codes' % (name, ', '.join(errs), step))
    if 'in-gamut' not in name:
        check(step <= 8.0, '%s: a luminance ramp steps by %.1f codes (> 8)' % (name, step))


# 9. an edge of the colour on a lighter ground (fU 2): its rim takes the hue of the signed B-spline mean, not of the clipped taps
def bspline(x):
    x2, x3 = x * x, x * x * x
    return np.array([(1 - 3 * x + 3 * x2 - x3) / 6, (4 - 6 * x2 + 3 * x3) / 6, (1 + 3 * x + 3 * x2 - 3 * x3) / 6, x3 / 6])


def signed_mean(row, f):
    """combine.glsl's B-spline colour (gridPos, bsplineWeights) of one row without the clamp."""
    out = []
    n = len(row)
    for x in range(n * f):
        q = x // f
        c = (2 * (x - q * f) + 1 - f) / (2 * f)
        base, frac = (q - 1, c + 1) if c < 0 else (q, c)
        w = bspline(frac)
        out.append(sum(w[k] * row[min(max(base - 1 + k, 0), n - 1)] for k in range(4)))
    return np.array(out)


rim_max = 0.0
for bgname, bg in (('white 0.6', np.full(3, 0.6)), ('warm wall', np.array([0.30, 0.22, 0.15])), ('grey 0.2', np.full(3, 0.2))):
    for name, col in (('470 nm', led(470)), ('525 nm', led(525)), ('X8U deep blue', X8U_BLUE)):
        col = col / (np.maximum(col, 0) @ LUMA) * 0.25
        low = np.tile(bg, (4, 32, 1))
        low[:, 16:] = col
        want = hue(oklab_signed(signed_mean(low[1], 2)))
        o = render2(low, 2, signed=1)[1]
        lab = oklab_signed(lin_of(o))
        sel = (np.hypot(lab[:, 1], lab[:, 2]) > 0.03) & (np.arange(64) > 20) & (np.arange(64) < 44)
        e = np.abs((hue(lab) - want + 180) % 360 - 180)[sel].max()
        rim_max = max(rim_max, e)
        check(e < 1.0, 'the rim of %s on %s is %.2f deg off the signed mean\'s hue' % (name, bgname, e))
print('edges of LEDs / the X8U blue on white, a warm wall and grey (fU 2): the rim within %.2f deg of the signed mean\'s hue' % rim_max)

# 10. textures inside sRGB at fU 1 and 2: bit for bit with signedHueU 1 (sRGB and P3)
rng = np.random.default_rng(5)
texture = rng.uniform(0, 1, (32, 32, 3)) ** 2 * rng.uniform(0.02, 2, (32, 32, 1))
same = []
for p3 in (False, True):
    for f in (1, 2):
        a, b = render2(texture, f, signed=1, p3=p3), render2(texture, f, signed=0, p3=p3)
        same.append(np.array_equal(a, b))
        check(same[-1], 'P3_OUT %d fU %d: a texture inside sRGB renders differently with signedHueU 1' % (p3, f))
print('a texture inside sRGB at fU 1 / 2, sRGB / P3: bit for bit with signedHueU 1: %s' % all(same))

ctx.release()
if failures:
    print('ccm gamut check FAIL (%d)' % len(failures))
    sys.exit(1)
print('ccm gamut check PASS')
