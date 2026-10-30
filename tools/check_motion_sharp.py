#!/usr/bin/env python3
from pathlib import Path
import moderngl
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT / 'app/src/main/assets/shaders/scamdn'
VS = '#version 310 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
try:
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
except Exception:
    ctx = moderngl.create_standalone_context(require=430)


def texture(array, dtype='f4'):
    array = np.ascontiguousarray(array)
    t = ctx.texture((array.shape[1], array.shape[0]), 1 if array.ndim == 2 else array.shape[2], array.tobytes(), dtype=dtype)
    t.repeat_x = t.repeat_y = False
    t.filter = (moderngl.NEAREST, moderngl.NEAREST)
    return t


def draw(name, bindings, values, size, channels):
    program = ctx.program(vertex_shader=VS, fragment_shader='#version 310 es\n' + (SHADERS / name).read_text())
    out = ctx.texture(size, channels, dtype='f4')
    fb = ctx.framebuffer([out])
    vao = ctx.vertex_array(program, [])
    try:
        for unit, (key, tex) in enumerate(bindings.items()):
            if key in program:
                tex.use(unit)
                program[key] = unit
        for key, value in values.items():
            if key in program:
                program[key] = value
        fb.use()
        ctx.viewport = (0, 0, *size)
        vao.render(vertices=3)
        return np.frombuffer(out.read(), np.float32).reshape(size[1], size[0], channels).copy()
    finally:
        vao.release(); fb.release(); out.release(); program.release()


codes = np.array([[64, 16, 0, 64]], np.uint8)
eff = texture(codes, 'u1')
gain = texture(np.full((1, 4, 4), 3, np.float32))
values = {'factorU': 1, 'effRefU': 64., 'effMaxU': 3., 'noEffU': 0, 'flatU': 0., 'lscU': (1., 9.), 'fullInvU': (.25, 1.)}
result = draw('strmap.glsl', {'EffMap': eff, 'GainMap': gain}, values, (4, 1), 2)
np.testing.assert_allclose(result[0, :, 0], [9, 36, 9, 9], atol=1e-6)
np.testing.assert_allclose(result[0, :, 1], [0, .75, 0, 0], atol=1e-6)
print('PASS coverage is separate from shading and unknown/clip codes')
values['sharpRefU'] = 256.
short = draw('strmap.glsl', {'EffMap': eff, 'GainMap': gain}, values, (4, 1), 2)
np.testing.assert_array_equal(short[..., 0], result[..., 0])
np.testing.assert_allclose(short[0, :, 1], [.75, .9375, 0, .75], atol=1e-6)
print('PASS base-only and short merges retain detail without changing the noise multiplier')

rng = np.random.default_rng(42)
h, w = 64, 96
noisy = (.3 + rng.normal(0, .03, (h, w))).astype(np.float32)
source = texture(noisy)
strength = np.empty((h, w, 2), np.float32)
strength[..., 0] = 4
strength[..., 1] = 0
strength[:, w // 2:, 1] = 1
strmap = texture(strength)
bindings = {key: source for key in ['InputBuffer', 'Recon', 'Yin', 'Base', 'LowFreq', 'Chroma']}
bindings['StrMap'] = strmap
values = {'strideU': 1, 'filterU': 1, 'noiseU': (0., .0025), 'thrU': 8., 'stageU': 1,
          'rfU': .5, 'modeU': 0, 'useYinU': 0, 'useMapU': 1, 'mapInvU': (1 / w, 1 / h), 'sharpU': 0.}
normal = draw('lbf.glsl', bindings, values, (w, h), 4)[..., 0]
values['sharpU'] = .85
sharp = draw('lbf.glsl', bindings, values, (w, h), 4)[..., 0]
np.testing.assert_array_equal(normal[4:-4, 4:w // 2 - 4], sharp[4:-4, 4:w // 2 - 4])
ordinary_error = np.mean((normal[4:-4, w // 2 + 4:-4] - noisy[4:-4, w // 2 + 4:-4]) ** 2)
sharp_error = np.mean((sharp[4:-4, w // 2 + 4:-4] - noisy[4:-4, w // 2 + 4:-4]) ** 2)
assert sharp_error < ordinary_error * .05, (sharp_error, ordinary_error)
print('PASS rejected regions retain reconstruction detail; accepted regions stay identical')
values['useMapU'] = 0
without_map = draw('lbf.glsl', bindings, values, (w, h), 4)
values['sharpU'] = 0.
np.testing.assert_array_equal(without_map, draw('lbf.glsl', bindings, values, (w, h), 4))
print('PASS no coverage map: no invented rejection and no changed filter')
