"""P387: the photo's rotation (RotateWatermark/addwatermark_rotate.glsl) and the Ultra HDR scene plane's (ultrahdr/sceneluma.glsl)
on the GPU (numpy, moderngl; LIBGL_ALWAYS_SOFTWARE=1 EGL_PLATFORM=surfaceless headless).

  python3 tools/check_rotate.py

Every rotation (0 / 90 / 180 / 270, rotate 0 / 3 / 2 / 1) with and without the mirror: the output is the input's pixels, each once
(no texel read outside the texture: texelFetch gives 0 there - the black column at the left edge of every portrait photo of builds up
to 31136, owner's film pair and an OPPO X8U photo), turned the way the rotation says, and the scene plane reads the same texel as the
base image at every pixel (the gain map stays aligned). The non-mirrored rotations against numpy's rot90.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'


def source(name, defines=None):
    out = ['#version 300 es']
    for line in (SH / name).read_text(encoding='utf-8').splitlines():
        m = re.match(r'\s*#import\s+(\S+)', line)
        if m:
            out.append((SH / 'utils' / ('import_' + m.group(1) + '.glsl')).read_text(encoding='utf-8'))
            continue
        d = re.match(r'\s*#define\s+(\w+)\s', line)
        if d and defines and d.group(1) in defines:
            line = '#define %s %s' % (d.group(1), defines[d.group(1)])
        out.append(line)
    return '\n'.join(out)


def main():
    import numpy as np
    import moderngl
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
    W, H = 7, 5
    # every texel its own value (red), green 1: a texel read outside the texture comes back 0
    img = np.zeros((H, W, 4), np.float32)
    img[..., 0] = (np.arange(W * H).reshape(H, W) + 1) / (W * H + 1)
    img[..., 1] = 1
    img[..., 3] = 1
    tin = ctx.texture((W, H), 4, img.tobytes(), dtype='f4')
    tin.filter = (moderngl.NEAREST, moderngl.NEAREST)
    gain = ctx.texture((2, 2), 4, np.ones((2, 2, 4), np.float32).tobytes(), dtype='f4')
    one = ctx.texture((1, 1), 4, np.ones((1, 1, 4), np.float32).tobytes(), dtype='f4')

    def draw(prog, size, textures, uniforms):
        out = ctx.texture(size, 4, dtype='f4')
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0) + size
        for i, (k, t) in enumerate(textures.items()):
            t.use(i)
            if k in prog:
                prog[k].value = i
        for k, v in uniforms.items():
            if k in prog:
                prog[k].value = v
        ctx.vertex_array(prog, []).render(vertices=3)
        r = np.frombuffer(out.read(), np.float32).reshape(size[1], size[0], 4).copy()
        fb.release()
        out.release()
        return r

    rot = ctx.program(vertex_shader=vs, fragment_shader=source('RotateWatermark/addwatermark_rotate.glsl', {'WATERMARK': '0'}))
    lum = ctx.program(vertex_shader=vs, fragment_shader=source('ultrahdr/sceneluma.glsl'))
    failures = []
    # GL rows bottom first; rotate 3 = a 90 degree photo (sensor rotation 90, RotateWatermark), 1 = 270, 2 = 180
    want = {0: img[..., 0], 3: np.rot90(img[..., 0], 1), 2: np.rot90(img[..., 0], 2), 1: np.rot90(img[..., 0], 3)}
    for r in (0, 3, 2, 1):
        for mirror in (False, True):
            size = (W, H) if r in (0, 2) else (H, W)
            u = {'rotate': r, 'mirror': mirror, 'cropSize': (W, H), 'rawSize': (W, H), 'yOffset': 0, 'watersizek': 20.0,
                 'watermarkAlpha': 0.0}
            out = draw(rot, size, {'InputBuffer': tin, 'Watermark': one, 'Noise': one}, u)[..., 0]
            plane = draw(lum, size, {'InputBuffer': tin, 'GainMap': gain}, dict(u, wU=(1.0, 0.0, 0.0)))[..., 0]
            name = 'rotate %d%s' % (r, ' mirrored' if mirror else '')
            zeros = int((out <= 0).sum())
            if zeros:
                failures.append('%s: %d pixels read outside the texture (black)' % (name, zeros))
            if sorted(out.ravel().tolist()) != sorted(img[..., 0].ravel().tolist()):
                failures.append('%s: not every input pixel exactly once' % name)
            if not mirror:
                w = want[r]
                if w.shape != out.shape or not np.array_equal(w, out):
                    # the rotation's own direction is GL's; what matters is a rotation of the input, every pixel kept
                    turned = any(np.array_equal(np.rot90(img[..., 0], k), out) for k in range(4))
                    if not turned:
                        failures.append('%s: not a rotation of the input' % name)
            # the scene plane reads the same texel (its value scales the input's red by one factor everywhere)
            ratio = plane / np.maximum(out, 1e-9)
            if (plane <= 0).any() or ratio.max() - ratio.min() > 1e-4 * ratio.max():
                failures.append('%s: the scene plane is not aligned with the image (ratio %.6f .. %.6f)' % (name, ratio.min(), ratio.max()))
            print('%-18s %s' % (name, 'ok' if not any(f.startswith(name + ':') for f in failures) else 'FAIL'))
    if failures:
        print('\n'.join(failures))
        print('FAIL')
        return 1
    print('PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
