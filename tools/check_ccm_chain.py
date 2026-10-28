#!/usr/bin/env python3
"""P370 (B1): the camera -> sRGB chain of the photo applies the colour matrix exactly once, checked on a real shot.

Fixture tools/fixtures/ccm_chain_x8u_175141.json: the owner's diagnostic capture SCAM-20261027-175141 (OPPO Find X8 Ultra
camera 4, build 31132): 16 x 16 block means of 03-ScamRgb (white-balanced camera RGB, the input of why/low) and of 02-WhyLow
(the linear sRGB why/low wrote on the phone), the README's neutral and sensorToProPhoto (on this ISP route the diagonal
white balance diag(1 / n)) and the result's COLOR_CORRECTION_TRANSFORM (CCT.matrix = its rows normalised to 1).
Checks:
- the numpy model WhyLow = intermediateToSRGB . sensorToProPhoto . (n * ScamRgb): chromaticity ln(R/G), ln(B/G) within
  p90 0.01 of the phone's WhyLow (measured about 0.002 / 0.001);
- the SHIPPED why/low.glsl (moderngl, factorU 1, flat lens shading, the Java upload: row-major with transpose) on the fixture's
  ScamRgb gives the same chromaticity within the same bound, and equals the numpy model within 2e-3 ln;
- the fixture discriminates: without the colour matrix (identity) or with it applied twice, p90 > 0.03 (identity about 0.06 /
  0.12): a double or missing matrix in the chain fails the check.
Usage: check_ccm_chain.py [--shaders DIR] [--fixture FILE]
"""
from pathlib import Path
import json
import re
import sys
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
FIX = ROOT / 'tools/fixtures/ccm_chain_x8u_175141.json'
if '--shaders' in sys.argv:
    SH = Path(sys.argv[sys.argv.index('--shaders') + 1])
if '--fixture' in sys.argv:
    FIX = Path(sys.argv[sys.argv.index('--fixture') + 1])

BOUND = 0.01      # p90 |ln chromaticity error| of the right chain
CONTROL = 0.03    # a missing or doubled matrix exceeds this

fx = json.loads(FIX.read_text(encoding='utf-8'))
n = np.array(fx['neutral'], np.float64)
s2p = np.array(fx['sensorToProPhoto'], np.float64).reshape(3, 3)
cst = np.array(fx['cst'], np.float64).reshape(3, 3)
cct = cst / cst.sum(axis=1, keepdims=True)          # Parameters.normalize: CCT.matrix on the ISP route
rgb = np.array(fx['scamRgb'], np.float64)
low = np.array(fx['whyLow'], np.float64)
gw, gh = fx['grid']
# blocks whose chromaticity is defined and that are not near the clip (the merge's ceiling handling is not this chain)
valid = (rgb.min(axis=1) > 1e-4) & (low.min(axis=1) > 1e-4) & (rgb.max(axis=1) < 0.9)


def chroma_err(pred, ref):
    p, r = pred[valid], ref[valid]
    e = np.abs(np.log(p[:, [0, 2]] / p[:, [1]]) - np.log(r[:, [0, 2]] / r[:, [1]]))
    return np.percentile(e[:, 0], 90), np.percentile(e[:, 1], 90)


def model(i2s):
    return (i2s @ s2p @ (rgb * n).T).T


failures = []
right = chroma_err(model(cct), low)
ident = chroma_err(model(np.eye(3)), low)
double = chroma_err(model(cct @ cct), low)
print('numpy model, %d of %d blocks: p90 |ln R/G| %.4f, |ln B/G| %.4f (bound %.2f)' % (valid.sum(), len(valid), right[0], right[1], BOUND))
print('  without the matrix: %.4f / %.4f; the matrix twice: %.4f / %.4f (each must exceed %.2f)' % (ident + double + (CONTROL,)))
if max(right) > BOUND:
    failures.append('the numpy chain misses the phone WhyLow: %.4f / %.4f' % right)
if max(ident) < CONTROL or max(double) < CONTROL:
    failures.append('the fixture does not tell a missing or doubled matrix from the right one')

try:
    import moderngl
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
except Exception as e:  # no GL here: the numpy part above still ran
    ctx = None
    print('GL not available (%s): why/low.glsl not rendered' % e)

if ctx is not None:
    VS = '#version 430\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0,1);}'

    def source(name):
        out = ['#version 430']
        for line in (SH / name).read_text(encoding='utf-8').splitlines():
            m = re.match(r'\s*#import\s+(\S+)', line)
            if m:
                out.append((SH / 'utils' / ('import_' + m.group(1) + '.glsl')).read_text(encoding='utf-8'))
                continue
            out.append(line)
        return '\n'.join(out)

    def tex(a, comps):
        t = ctx.texture((a.shape[1], a.shape[0]), comps, np.ascontiguousarray(a, np.float32).tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        t.repeat_x = t.repeat_y = False
        return t

    def render(i2s):
        prog = ctx.program(vertex_shader=VS, fragment_shader=source('why/low.glsl'))
        inp = tex(rgb.reshape(gh, gw, 3), 3)
        gm = tex(np.ones((2, 2, 4)), 4)
        out = ctx.texture((gw, gh), 4, dtype='f4')
        fbo = ctx.framebuffer([out])
        fbo.use()
        ctx.viewport = (0, 0, gw, gh)
        inp.use(0)
        gm.use(1)
        uniforms = {'InputBuffer': 0, 'GainMap': 1, 'factorU': 1, 'inScaleU': 1.0, 'neutralPointU': tuple(n),
                    # GLProg.setVar(name, float[9]): row-major with transpose = column-major here
                    'sensorToIntermediate': tuple(s2p.T.reshape(-1)), 'intermediateToSRGB': tuple(i2s.T.reshape(-1))}
        for k, v in uniforms.items():
            if k in prog:
                prog[k].value = v
        ctx.vertex_array(prog, []).render(vertices=3)
        ctx.finish()
        data = np.frombuffer(out.read(), np.float32).astype(np.float64).reshape(gh, gw, 4)[..., :3].reshape(-1, 3)
        fbo.release()
        out.release()
        return data

    gl = render(cct)
    gl_right = chroma_err(gl, low)
    gl_model = chroma_err(gl, model(cct))
    gl_ident = chroma_err(render(np.eye(3)), low)
    print('why/low.glsl: p90 against the phone %.4f / %.4f, against the numpy model %.5f / %.5f; without the matrix %.4f / %.4f'
          % (gl_right + gl_model + gl_ident))
    if max(gl_right) > BOUND:
        failures.append('why/low.glsl misses the phone WhyLow: %.4f / %.4f' % gl_right)
    if max(gl_model) > 2e-3:
        failures.append('why/low.glsl is not intermediateToSRGB . sensorToIntermediate . (in * neutral): %.5f / %.5f' % gl_model)
    if max(gl_ident) < CONTROL:
        failures.append('why/low.glsl without the matrix is not told from the right chain')

if failures:
    for f in failures:
        print('FAIL:', f)
    print('ccm chain check FAIL')
    sys.exit(1)
print('ccm chain check PASS')
