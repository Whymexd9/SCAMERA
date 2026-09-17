"""Headless shader regressions: pip install moderngl numpy; run from repo root.
Uses desktop GLSL translation on Mesa EGL; target-device GLES is a separate gate.
"""
from pathlib import Path
import re
import numpy as np
import moderngl

CTX = moderngl.create_standalone_context(backend='egl')
ROOT = Path('app/src/main/assets/shaders/remosaic')
VERT = '''#version 330
void main() {
 vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
 gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}'''

def run(name, inputs, uniforms, integer=False, output_size=None, half=False):
    src = (ROOT / (name + '.glsl')).read_text().replace('#version 300 es', '#version 330')
    src = re.sub(r'precision \w+ \w+;', '', src)
    prog = CTX.program(vertex_shader=VERT, fragment_shader=src)
    h, w = next(iter(inputs.values())).shape[:2]
    textures = []
    for i, (key, data) in enumerate(inputs.items()):
        data = np.ascontiguousarray(data)
        texture = CTX.texture((data.shape[1], data.shape[0]), data.shape[2], data.tobytes(),
                              dtype='u2' if data.dtype == np.uint16 else 'f4')
        texture.use(i); textures.append(texture)
        if key in prog: prog[key].value = i
    for key, value in uniforms.items():
        if key in prog: prog[key].value = value
    if output_size is not None: w, h = output_size
    target = CTX.texture((w, h), 4, dtype='u2' if integer else 'f2' if half else 'f4')
    fb = CTX.framebuffer([target]); fb.use(); CTX.viewport = (0, 0, w, h)
    vao = CTX.vertex_array(prog, []); vao.render(vertices=3)
    result = np.frombuffer(target.read(), dtype=np.uint16 if integer else np.float16 if half else np.float32).reshape(h, w, 4).copy()
    vao.release(); fb.release(); target.release(); prog.release()
    for t in textures: t.release()
    return result if integer else result.astype(np.float32)


def checks():
    h, w = 40, 48
    y, x = np.mgrid[:h, :w]
    worst = 0.0
    for block in (2, 4):
        for phase_y in range(2 * block):
            for phase_x in range(2 * block):
                mask = (((x + phase_x) // block + (y + phase_y) // block) % 2 == 1)
                plane = .2 + x * .003 + y * .002
                data = np.stack([plane * mask, mask], axis=-1).astype('f4')
                got = run('greensteer', {'InputBuffer': data}, {'size': (w,h), 'reach': block*2, 'steer': 8.0})[:,:,0]
                margin = 2 * block
                error = np.max(np.abs(got[margin:-margin,margin:-margin] - plane[margin:-margin,margin:-margin]))
                worst = max(worst, error)
                assert error < 2e-6, (block, phase_x, phase_y, error)
                assert np.all(np.isfinite(got)) and got.min() > 0
                assert np.max(np.abs(got[mask] - plane[mask])) < 1e-7
        # Full-support colour-difference interpolation must preserve constants,
        # including negative values and sparse corner quadrants.
        for q in range(4):
            mask = ((x//block)%2 + 2*((y//block)%2)) == q
            data = np.stack([-.125*mask, mask],axis=-1).astype('f4')
            u = {'size':(w,h), 'kernelSize':2*block+1, 'axis':0, 'divide':0}
            tmp = run('maskblur', {'InputBuffer':data}, u)
            u.update(axis=1, divide=1)
            got = run('maskblur', {'InputBuffer':tmp}, u)
            assert np.max(np.abs(got[:,:,0]+.125)) < 1e-6
    impulse = np.zeros((h,w,2),dtype='f4'); impulse[15,15] = (0.75,1)
    copied = run('copyfloat', {'InputBuffer':impulse}, {})
    assert np.array_equal(copied[:,:,:2],impulse)
    raw = np.full((h,w,1),264,dtype='u2')
    green = np.full((h,w,1),240/959,dtype='f4')
    zero = np.zeros_like(green)
    got = run('assemble', {'RawBuffer':raw,'GreenBuffer':green,'DiffBBuffer':zero,'DiffRBuffer':zero},
              {'blockSize':4,'phase':(0,0),'quadColors':(2,1,1,0),'blackLevel':64.,'whiteLevel':1023.,
               'gainB':1.,'gainR':1.,'blockGain':tuple([1.2]*64)}, integer=True)
    assert np.max(np.abs(got[:,:,0].astype(int)-304)) <= 1
    print(f'PASS: green ramps across all 2x2/4x4 phases, max error {worst:.3g}; constant chroma; impulse copy; corrected assembly')

if __name__ == '__main__':
    checks()
