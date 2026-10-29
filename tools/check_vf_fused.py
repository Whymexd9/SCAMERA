#!/usr/bin/env python3
"""P365: the RAW viewfinder's fused WHY tone pass against P314's passes, on the GPU (moderngl), no device.

The fused pass is why/combine.glsl with POINT_FUSED 1 and RAW_SOURCE 1 (utils/import_vfraw.glsl: the RAW frame's linear colour
straight from the R16UI texture, LiveWhyTone.drawFused); P314's path is preview/rawdevelop_fs.glsl u_linear_out 1 into an RGBA16F
linear frame, then why/combine.glsl POINT_FUSED 1 on it (LiveWhyTone.draw), both into an 8-bit target as on the phone.

- RAW_SOURCE 0 leaves why/combine.glsl token for token as it was before P365 (POINT_FUSED 0: the photo; POINT_FUSED 1: the
  viewfinder's tone pass of P314): its token streams against the stored ones of 7a920e8b, and that commit's file when git has it
  (the P370 signed hue and the P393 vibrance shape taken out as check_colour_output.py does);
- the rawdevelop_fs functions import_vfraw.glsl carries are rawdevelop_fs's own, token for token;
- synthetic RGGB / GRBG / GBRG / BGGR frames of a plain Bayer sensor (the tone at the viewport: the direct one-pass draw), a frame
  of Quad cell means and a Quad frame of block 2 (the tone at the mosaic's colour grid: the 2-pass case), with sensor noise,
  sharp saturated edges, a lens-shading map, white balance and a colour matrix, portrait and landscape rotations, the front
  mirror, a viewport origin off (0, 0) and a black level of its own at each CFA site (the order of u_black_level):
  mean |fused - P314| <= 1/255, its 99th percentile <= 3/255 and its maximum <= 12/255 of the 8-bit output in every case, and a
  flat frame within 1/255 everywhere - every case with the P370 signed hue off and on (signedHueU / u_signed_hue 0 and the
  shipped default 1, where the two-pass linear frame keeps a colour outside sRGB signed). What differs: the linear frame's half floats, the lens-shading gain of the 3 x 3 colour
  neighbourhood (the pixel's, not each neighbour's), and a neighbour's frame position from the derivatives of texCoord instead
  of the rasteriser's own value (~1e-4 px apart). A pixel centre exactly on a quad's border (every 15th row and column at
  1440 x 1920 of 3072 x 4096) takes the quad after it in both passes (import_vfraw VF_TIE = rawdevelop_fs RAW_TIE, checked
  equal): without it the neighbour took the adjacent quad, ~0.2 % of the pixels off by 21-133/255 beside sharp coloured edges.
  (Unfiltered quads in the neighbourhood, the plan's first idea, differed by p99 9-18/255 in noisy shadows; the fused pass takes
  the range-filtered quads from 5 gathers each.)
- glslc (an NDK found on PATH / $ANDROID_NDK_HOME / $ANDROID_HOME) compiles the fused variants as GLProg hands them to the
  driver (#version 310 es, the imports expanded).

usage: python3 tools/check_vf_fused.py   (numpy, moderngl; LIBGL_ALWAYS_SOFTWARE=1 on a headless Linux)
"""
from __future__ import annotations

import glob
import hashlib
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
FAILURES: list[str] = []
BASELINE_COMMIT = '7a920e8b'
# SHA-256 of why/combine.glsl's token stream at 7a920e8b (imports expanded, preprocessed, comments dropped) with POINT_FUSED 0
# (the photo) and POINT_FUSED 1 (the viewfinder's tone pass of P314)
BASELINE_TOKENS = {'0': '8eee80560928a0990c989d3c78ec173eaaf7f5c674de22152899ca7ff957fc50',
                   '1': 'bad492bd436d4977367e9d62ac16df9050b08e45c5eec931a066ecc0bb82d5ca'}
# FusedCurve: entry i at log2 I = LOG2_MIN + i / PER_EV
LOG2_MIN, LOG2_MAX, PER_EV = -16.0, 6.0, 16
SIZE = int((LOG2_MAX - LOG2_MIN) * PER_EV) + 1
MEAN_MAX, P99_MAX, ABS_MAX = 1.0, 3.0, 12  # 8-bit codes (ABS_MAX: a quad picked 2 px off beside an edge is 21-133)
VFRAW_COPIES = ('getUnpackedPixel', 'getMacroPixel', 'apply_wb_and_hl_clip', 'getFastColor', 'getFilteredBayerPixel',
                'getStandardBayerColor')


def check(cond: bool, msg: str) -> None:
    if not cond:
        FAILURES.append(msg)
        print('FAIL: ' + msg)


def read(p: Path) -> str:
    return p.read_text(encoding='utf-8').replace('\r\n', '\n')


# ------------------------------------------------------------------------------------------------ sources

def read_program(src: str, defines=()) -> str:
    """GLInterface.readProgram: '#import x' -> '#line 1', shaders/utils/import_x.glsl, '#line <next>'; a '#define NAME v' line
    becomes '#define NAME value' for each define; an unversioned shader gets GLProg.glVersion and P317's highp int."""
    lines = src.replace('\r\n', '\n').split('\n')
    if lines and lines[-1] == '':
        lines = lines[:-1]
    out, versioned = [], False
    for n, line in enumerate(lines, 1):
        if '#version' in line:
            versioned = True
        if '#import' in line:
            if '//' not in line:
                p = SH / 'utils' / (line.replace('#', '').replace(' ', '_') + '.glsl')
                if p.exists():
                    out.append('#line 1\n' + read(p) + '\n#line %d\n' % (n + 1))
            continue
        if '#define' in line:
            for name, value in defines:
                if (' ' + name + ' ') in line:
                    line = '#define %s %s' % (name, value)
                    break
        out.append(line + '\n')
    text = ''.join(out)
    return text if versioned else '#version 310 es\n\nprecision highp int;\n#line 1\n' + text


def expand_imports(src: str) -> str:
    out = []
    for line in src.replace('\r\n', '\n').split('\n'):
        if '#import' in line and '//' not in line:
            out.append(read(SH / 'utils' / (line.replace('#', '').replace(' ', '_').strip() + '.glsl')))
        elif '#import' not in line:
            out.append(line)
    return '\n'.join(out)


def set_define(src: str, name: str, value: str) -> str:
    return '\n'.join(('#define %s %s' % (name, value)) if ('#define' in l and (' ' + name + ' ') in l + ' ') else l
                     for l in src.split('\n'))


def preprocess(src: str) -> str:
    """The subset of the GLSL preprocessor the shader uses: object-like #define, #if NAME == N / #else / #endif."""
    defines, stack, out = {}, [], []
    for line in src.split('\n'):
        s = line.strip()
        active = all(stack)
        m = re.match(r'#\s*define\s+(\w+)\s*(.*)$', s)
        if m:
            if active:
                defines[m.group(1)] = m.group(2).strip()
            continue
        m = re.match(r'#\s*if\s+(\w+)\s*==\s*(\d+)\s*$', s)
        if m:
            stack.append(defines.get(m.group(1), '0') == m.group(2))
            continue
        if re.match(r'#\s*else\b', s):
            stack[-1] = not stack[-1]
            continue
        if re.match(r'#\s*endif\b', s):
            stack.pop()
            continue
        if s.startswith('#'):
            raise SystemExit('unsupported directive: ' + s)
        if active:
            out.append(line)
    if stack:
        raise SystemExit('unterminated #if')
    return '\n'.join(out)


def tokens(text: str) -> list[str]:
    text = re.sub(r'/\*.*?\*/', ' ', text, flags=re.S)
    text = re.sub(r'//[^\n]*', ' ', text)
    return re.findall(r'[A-Za-z_]\w*|\d+\.\d*(?:[eE][-+]?\d+)?|\.\d+(?:[eE][-+]?\d+)?|\d+|\S', text)


def token_hash(src: str, point: str) -> str:
    return hashlib.sha256(' '.join(tokens(preprocess(expand_imports(set_define(src, 'POINT_FUSED', point))))).encode()).hexdigest()


def function(src: str, name: str) -> str | None:
    """The definition of a function (its signature line to the matching brace) in a shader source, or None."""
    m = re.search(r'^[A-Za-z_][\w ]*\b' + name + r'\s*\([^;{]*\)\s*\{', src, flags=re.M)
    if not m:
        return None
    depth, i = 0, m.end() - 1
    while i < len(src):
        if src[i] == '{':
            depth += 1
        elif src[i] == '}':
            depth -= 1
            if depth == 0:
                return src[m.start():i + 1]
        i += 1
    return None


def source_part() -> None:
    combine = read(SH / 'why/combine.glsl')
    # P370 (B2): the signed hue (signedHueU, off when unset) is taken out as check_colour_output.py does, after checking it is there
    sys.path.insert(0, str(ROOT / 'tools'))
    from check_colour_output import has_signed_hue, without_signed_hue, has_p393, without_p393
    check(has_signed_hue(combine), 'combine.glsl lost the P370 signed hue')
    combine = without_signed_hue(combine)
    # P393: the light-tone vibrance's shape (vibHlShapeU) as well - P341's block in its place (tools/check_vibrance_hl.py renders it)
    check(has_p393(combine), 'combine.glsl lost the P393 shape of the light-tone vibrance')
    combine = without_p393(combine)
    check('#define RAW_SOURCE 0' in combine, 'combine.glsl has no "#define RAW_SOURCE 0"')
    hashes = {p: token_hash(combine, p) for p in ('0', '1')}
    for p, h in hashes.items():
        print('combine.glsl POINT_FUSED %s, RAW_SOURCE 0: token stream %s' % (p, h))
        check(h == BASELINE_TOKENS[p], 'combine.glsl POINT_FUSED %s with RAW_SOURCE 0 differs from %s token for token' % (p, BASELINE_COMMIT))
    try:
        base = subprocess.run(['git', '-C', str(ROOT), 'show', BASELINE_COMMIT + ':app/src/main/assets/shaders/why/combine.glsl'],
                              capture_output=True, check=True).stdout.decode('utf-8').replace('\r\n', '\n')
        for p in ('0', '1'):
            check(token_hash(base, p) == BASELINE_TOKENS[p], 'the stored POINT_FUSED %s hash is not the %s shader' % (p, BASELINE_COMMIT))
        print('  %s: the same token streams' % BASELINE_COMMIT)
    except (subprocess.CalledProcessError, FileNotFoundError):
        print('  (%s not in this checkout: the stored hashes are the reference)' % BASELINE_COMMIT)
    fused = preprocess(expand_imports(set_define(set_define(combine, 'POINT_FUSED', '1'), 'RAW_SOURCE', '1')))
    check('vfLinear(' in fused and 'vfColour(' in fused and 'texelFetch(WhyColour' not in fused.split('void main()')[1].split('if (f == 1)')[1].split('} else {')[0],
          'RAW_SOURCE 1 does not take the linear colour from the RAW frame')
    develop, vfraw = read(SH / 'preview/rawdevelop_fs.glsl'), read(SH / 'utils/import_vfraw.glsl')
    for name in VFRAW_COPIES:
        a, b = function(develop, name), function(vfraw, name)
        check(a is not None and b is not None and tokens(a) == tokens(b),
              'import_vfraw.glsl %s is not rawdevelop_fs.glsl\'s (keep the copies identical)' % name)
    print('import_vfraw.glsl: %s identical to rawdevelop_fs.glsl' % ', '.join(VFRAW_COPIES))
    # the tie-break of a pixel centre on a quad's border: one value in both, or the passes pick different quads on those rows
    ties = (re.findall(r'const\s+float\s+VF_TIE\s*=\s*([\d.]+)\s*;', vfraw),
            re.findall(r'const\s+float\s+RAW_TIE\s*=\s*([\d.]+)\s*;', develop))
    check(len(ties[0]) == 1 and ties[0] == ties[1],
          'import_vfraw.glsl VF_TIE %s is not rawdevelop_fs.glsl RAW_TIE %s' % (ties[0], ties[1]))
    print('quad-border tie-break: VF_TIE = RAW_TIE = %s' % (ties[0][0] if ties[0] else '?'))


# ------------------------------------------------------------------------------------------------ glslc

def find_glslc() -> str | None:
    found = shutil.which('glslc')
    if found:
        return found
    candidates = [ROOT.parent / 'local-tools/android-sdk']
    for env in ('ANDROID_NDK_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        if os.environ.get(env):
            candidates.append(Path(os.environ[env]))
    for base in candidates:
        if base.is_dir():
            for pattern in (('ndk', '*', 'shader-tools', '*', 'glslc*'), ('shader-tools', '*', 'glslc*')):
                hits = sorted(glob.glob(os.path.join(str(base), *pattern)), reverse=True)
                if hits:
                    return hits[0]
    return None


FUSED_VARIANTS = (('fused', ()), ('fused + COLOUR_LOOK', (('COLOUR_LOOK', '1'),)), ('fused + TONE_OP', (('TONE_OP', '1'),)),
                  ('fused + FILM_LUT', (('FILM_LUT', '1'),)),
                  ('fused + COLOUR_LOOK + TONE_OP + FILM_LUT', (('COLOUR_LOOK', '1'), ('TONE_OP', '1'), ('FILM_LUT', '1'))),
                  ('fused + P3_OUT', (('P3_OUT', '1'),)))


def glslc_part() -> None:
    glslc = find_glslc()
    if not glslc:
        print('glslc not found: the fused variants were not compiled offline')
        return
    combine = read(SH / 'why/combine.glsl')
    vs = read(SH / 'preview/rawdevelop_vs.glsl').replace('#version 300 es', '#version 310 es', 1)
    units = [('rawdevelop_vs (310 es, the fused pass)', 'vert', vs),
             ('combine POINT_FUSED 1 (P314)', 'frag', read_program(combine, (('POINT_FUSED', '1'),))),
             ('combine (the photo)', 'frag', read_program(combine))]
    for label, defs in FUSED_VARIANTS:
        units.append(('combine ' + label, 'frag', read_program(combine, (('POINT_FUSED', '1'), ('RAW_SOURCE', '1')) + defs)))
    with tempfile.TemporaryDirectory() as d:
        for i, (label, stage, text) in enumerate(units):
            f = Path(d) / ('%02d.%s' % (i, stage))
            f.write_text(text, encoding='utf-8')
            r = subprocess.run([glslc, '--target-env=opengl', '-std=310es', '-fshader-stage=' + stage, '-fauto-map-locations',
                                '-fauto-bind-uniforms', '-o', os.devnull, str(f)], capture_output=True, text=True)
            check(r.returncode == 0, 'glslc: %s does not compile:\n%s' % (label, r.stderr))
            print('glslc %s: %s' % (label, 'OK' if r.returncode == 0 else 'FAIL'))


# ------------------------------------------------------------------------------------------------ GPU

def rotate_m(deg: float) -> tuple:
    """android.opengl.Matrix.setRotateM(m, 0, deg, 0, 0, 1), column-major."""
    a = np.float32(math.radians(deg))
    c, s = float(np.float32(math.cos(a))), float(np.float32(math.sin(a)))
    m = [0.0] * 16
    m[0] = m[5] = c
    m[1], m[4] = s, -s
    m[10] = m[15] = 1.0
    return tuple(m)


def visible_crop(w: int, h: int, block: int, vp_w: int, vp_h: int, quarter: bool) -> tuple:
    """LiveRawRenderer.visibleCrop of the whole frame (float32 as Java)."""
    period = block * 2
    left = top = 0
    cw, ch = w // period * period, h // period * period
    f32 = np.float32
    if vp_w > 0 and vp_h > 0:
        aspect = f32(vp_w) / f32(vp_h) if quarter else f32(vp_h) / f32(vp_w)
        if f32(cw) / f32(max(ch, 1)) > aspect:
            fitted = max(period, int(f32(ch) * aspect) // period * period)
            left += (cw - fitted) // 2 // period * period
            cw = fitted
        else:
            fitted = max(period, int(f32(cw) / aspect) // period * period)
            top += (ch - fitted) // 2 // period * period
            ch = fitted
    return left, top, max(period, cw), max(period, ch)


def tone_size(vp_w: int, vp_h: int, crop_w: int, crop_h: int, block: int, quarter: bool) -> tuple:
    """LiveRawRenderer.toneSize."""
    b = max(1, block)
    gx, gy = (crop_w if quarter else crop_h) / (2.0 * b), (crop_h if quarter else crop_w) / (2.0 * b)
    need = max(gx / vp_w, gy / vp_h)
    if not need < 1.0:
        return vp_w, vp_h
    s = max(1, math.ceil(need * 16 - 1e-9)) / 16.0
    return max(1, int(math.floor(vp_w * s + 0.5))), max(1, int(math.floor(vp_h * s + 0.5)))


def fused_curve(em, lw, center=0.6, smooth=0.25, gamma_inv=1 / 2.2, toe=0.05) -> np.ndarray:
    """FusedCurve.fill without the envelope (mono 0): the same table for both paths."""
    def acescg(v):
        x = np.maximum(v, 0.0) * 1.5
        b, e = max(toe * 0.75, 0.005), max(0.14 - (toe - 0.04) * 0.8, 0.03)
        return (x * (2.51 * x + b)) / (x * (2.43 * x + 0.59) + e)
    i_ = 2.0 ** (LOG2_MIN + np.arange(SIZE) / PER_EV)
    sw = swl = 0.0
    for k in range(4):
        l_ = np.maximum(acescg(i_ * em[k]), 1e-6) ** gamma_inv
        w = np.exp(-0.5 * (l_ - center) ** 2 / max(smooth * smooth, 0.001)) * lw[k] + 1e-6
        sw, swl = sw + w, swl + w * l_
    return (swl / sw).astype(np.float32)


def scene(h: int, w: int, rng, flat=False) -> np.ndarray:
    """Linear scene RGB (0..~1.2 of white): gradients, sharp saturated shapes, fine texture; flat: one grey-green tone."""
    if flat:
        return np.broadcast_to(np.array([0.21, 0.33, 0.27]), (h, w, 3)).copy()
    y, x = np.mgrid[0:h, 0:w] / np.array([h, w])[:, None, None]
    img = np.stack([0.05 + 0.4 * x, 0.08 + 0.3 * y, 0.12 + 0.2 * (1 - x) * y], -1)
    cols = np.array([[0.9, 0.08, 0.05], [0.05, 0.6, 0.1], [0.06, 0.1, 0.85], [0.95, 0.85, 0.05], [0.8, 0.1, 0.7],
                     [0.02, 0.02, 0.02], [1.2, 1.2, 1.2], [0.4, 0.25, 0.18]])
    for i in range(26):
        c = cols[i % len(cols)] * rng.uniform(0.5, 1.0)
        cy, cx, r = rng.uniform(0, h), rng.uniform(0, w), rng.uniform(0.03, 0.12) * min(h, w)
        if i % 2:
            img[(np.abs(np.mgrid[0:h, 0:w][0] - cy) < r) & (np.abs(np.mgrid[0:h, 0:w][1] - cx) < r * 1.6)] = c
        else:
            img[(np.mgrid[0:h, 0:w][0] - cy) ** 2 + (np.mgrid[0:h, 0:w][1] - cx) ** 2 < r * r] = c
    stripes = (np.sin(np.mgrid[0:h, 0:w][1] * 0.9) > 0)[..., None] * np.array([0.25, 0.2, 0.15])
    band = (np.mgrid[0:h, 0:w][0] > h * 0.8) & (np.mgrid[0:h, 0:w][0] < h * 0.9)
    img[band] = 0.1 + stripes[band]
    return img


def mosaic(img: np.ndarray, cfa: int, block: int, rng, black, noise=True) -> np.ndarray:
    """The RAW of a scene: the CFA (RGGB, GRBG, GBRG, BGGR = 0..3; block 2: a Quad), black per CFA site (site order 00, 10, 01,
    11: LiveRawFrame.blackLevel's), white 1023, shot and read noise."""
    h, w, _ = img.shape
    layout = [[0, 1, 1, 2], [1, 0, 2, 1], [1, 2, 0, 1], [2, 1, 1, 0]][cfa]
    yy, xx = np.mgrid[0:h, 0:w]
    site = ((yy // block) % 2) * 2 + (xx // block) % 2
    chan = np.choose(site, layout)
    v = np.take_along_axis(img, chan[..., None], 2)[..., 0]
    wb = np.array([1 / 2.0, 1.0, 1 / 1.6])          # the camera's response: the white balance gains undo it
    b = np.asarray(black, np.float64)[site]
    v = v * wb[chan] * (1023 - b)
    if noise:
        v = v + rng.normal(0, 1, v.shape) * np.sqrt(0.8 * np.maximum(v, 0) + 4.0)
    return np.clip(np.round(b + v), 0, 1023).astype(np.uint16)


def gpu_part() -> None:
    try:
        import moderngl
    except ImportError:
        print('moderngl missing: the GPU comparison was skipped')
        FAILURES.append('moderngl missing')
        return
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    combine = read(SH / 'why/combine.glsl')
    vs_raw = read(SH / 'preview/rawdevelop_vs.glsl')
    vs_full = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
    fused_fs = read_program(combine, (('POINT_FUSED', '1'), ('RAW_SOURCE', '1')))
    try:
        prog_fused = ctx.program(vertex_shader=vs_raw.replace('#version 300 es', '#version 310 es', 1), fragment_shader=fused_fs)
    except Exception as e:
        # the fused pass is GLSL ES 3.10 (textureGather): a driver without ES 3.1 compatibility cannot render it
        check(False, 'the GPU has no GLSL ES 3.10 (textureGather): the fused pass cannot be rendered here (%s)'
              % str(e).strip().split('\n')[0])
        ctx.release()
        return
    prog_lin = ctx.program(vertex_shader=vs_raw, fragment_shader=read(SH / 'preview/rawdevelop_fs.glsl'))
    prog_tone = ctx.program(vertex_shader=vs_full, fragment_shader=read_program(combine, (('POINT_FUSED', '1'),))
                            .replace('#version 310 es', '#version 300 es', 1))
    print('GPU: %s (%s), the fused program as #version 310 es' % (ctx.info['GL_RENDERER'], ctx.info['GL_VERSION']))

    pos = ctx.buffer(np.array([1, -1, -1, -1, 1, 1, -1, 1], np.float32).tobytes())       # MainRenderer pVertex
    uv = ctx.buffer(np.array([1, 1, 0, 1, 1, 0, 0, 0], np.float32).tobytes())            # MainRenderer pTexCoord
    vao_lin = ctx.vertex_array(prog_lin, [(pos, '2f', 'vPosition'), (uv, '2f', 'vTexCoord')])
    vao_fused = ctx.vertex_array(prog_fused, [(pos, '2f', 'vPosition'), (uv, '2f', 'vTexCoord')])
    ones = ctx.texture((2, 2), 4, np.ones(16, np.float32).astype(np.float16).tobytes(), dtype='f2')
    curve = fused_curve([0.35, 0.7, 1.0, 2.6], [0.7, 0.8, 1.0, 0.5])
    t_curve = ctx.texture((SIZE, 1), 1, curve.tobytes(), dtype='f4')
    t_curve.filter = (moderngl.NEAREST, moderngl.NEAREST)
    # a lens-shading map (vignetting up to 1.8 x, a little colour), RGB16F 17 x 13 as LiveRawRenderer uploads it
    sy, sx = np.mgrid[0:13, 0:17] / np.array([12.0, 16.0])[:, None, None]
    r2 = (sx - 0.5) ** 2 + (sy - 0.5) ** 2
    lsc = np.stack([1 + 3.4 * r2, 1 + 3.0 * r2, 1 + 2.7 * r2], -1).astype(np.float16)
    t_lsc = ctx.texture((17, 13), 3, lsc.tobytes(), dtype='f2')
    t_lsc.filter = (moderngl.LINEAR, moderngl.LINEAR)
    t_lsc.repeat_x = t_lsc.repeat_y = False
    ccm = np.array([[1.62, -0.48, -0.14], [-0.21, 1.45, -0.24], [0.02, -0.55, 1.53]])
    orders = [[0, 1, 2, 3], [1, 0, 3, 2], [2, 3, 0, 1], [3, 2, 1, 0]]
    # a black level of its own at each CFA site (site order 00, 10, 01, 11, LiveRawFrame.blackLevel's): the four bayer_pattern
    # cases pin the order of u_black_level (LiveRawRenderer.bindFrame) and vfStandardQuad's site vector
    black = [58.0, 63.0, 67.0, 72.0]

    tone_vals = {'sensorToIntermediate': tuple(np.eye(3).flatten()), 'intermediateToSRGB': tuple(np.eye(3).flatten()),
                 'neutralPointU': (1.0, 1.0, 1.0), 'inScaleU': 1.0, 'fU': 1, 'colourFU': 1, 'FusedCurve': 8,
                 'fusedCurveU': (LOG2_MIN, float(PER_EV)), 'signedColourU': 0, 'aeU': 2.2, 'clipU': 1.0, 'toeU': 0.05,
                 'gammaInvU': 1 / 2.2, 'macroU': 1.1, 'vibU': (0.0, 0.4, 0.2), 'chromaDnU': 0.0, 'clarityU': 0.0,
                 'flatProtectU': 0.0, 'detailGainU': 0.0, 'detailRefU': 0, 'sharpU': 0, 'sharpToneU': 1.0, 'deltaChromaU': 1.0,
                 'filmToeU': 0.1, 'agxAU': (2.7, 1.35, 1.6, 1.0), 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'ditherU': 1, 'headroomU': 0,
                 'hlWhiteU': 0.15}

    def put(prog, vals):
        for k, v in vals.items():
            if k in prog:
                prog[k].value = v

    def raw_vals(f, crop, rot, mirror):
        o = orders[f['cfa']]
        return {'width': f['w'], 'height': f['h'], 'bayer_pattern': f['cfa'], 'cfa_mode': f['mode'], 'cfa_block_size': f['block'],
                'crop_left': crop[0], 'crop_top': crop[1], 'crop_width': crop[2], 'crop_height': crop[3],
                'u_black_level': tuple(black[i] for i in o), 'u_white_level': 1023.0, 'r_gain': 2.0, 'g_gain': 1.0, 'b_gain': 1.6,
                'color_transform': tuple(ccm.T.flatten()), 'u_lsc_intensity': 1.0, 'mirror': mirror, 'texRotate': rot,
                'u_ldc_enabled': 0}

    def p314(f, t_raw, w, h, crop, rot, mirror, origin, signed):
        """LiveWhyTone.draw (vf_why 1): rawdevelop_fs u_linear_out 1 into the RGBA16F linear frame, why/combine into RGBA8. The
        linear frame is rasterised at the fused pass's viewport origin: a pixel centre on a quad's border (every 15th row and
        column at 1440 x 1920 of 3072 x 4096) takes the quad the rasteriser's rounding gives it, which depends on the origin -
        the same pixel centres for both, so the comparison is of the passes, not of the rasteriser."""
        ox, oy = origin
        big = ctx.texture((w + ox + 3, h + oy + 2), 4, dtype='f2')
        fb = ctx.framebuffer([big])
        fb.use()
        ctx.viewport = (ox, oy, w, h)
        t_raw.use(2)
        t_lsc.use(3)
        put(prog_lin, dict(raw_vals(f, crop, rot, mirror), sTexture16=2, u_lsc_map=3, u_linear_out=1, u_signed_hue=signed, peaking_enabled=0,
                           u_auto_exposure=1.0, u_ef_enabled=1, u_lut_enabled=0, u_look_on=0))
        vao_lin.render(moderngl.TRIANGLE_STRIP)
        part = np.frombuffer(big.read(), np.float16).reshape(h + oy + 2, w + ox + 3, 4)[oy:oy + h, ox:ox + w]
        lin = ctx.texture((w, h), 4, np.ascontiguousarray(part).tobytes(), dtype='f2')
        lin.filter = (moderngl.NEAREST, moderngl.NEAREST)
        fb.release()
        big.release()
        out = ctx.texture((w, h), 4)
        fb2 = ctx.framebuffer([out])
        fb2.use()
        ctx.viewport = (0, 0, w, h)
        for i, name in enumerate(('InputBuffer', 'WhyLow', 'WhyColour', 'WhyFused', 'WhyDetailRef', 'WhyLumaS')):
            lin.use(i)
            if name in prog_tone:
                prog_tone[name].value = i
        ones.use(7)
        t_curve.use(8)
        put(prog_tone, dict(tone_vals, GainMap=7, signedHueU=signed))
        ctx.vertex_array(prog_tone, []).render(vertices=3)
        res = np.frombuffer(out.read(), np.uint8).reshape(h, w, 4)[..., :3].astype(np.int16)
        for o in (fb2, lin, out):
            o.release()
        return res

    def fused(f, t_raw, w, h, crop, rot, mirror, origin, signed):
        """LiveWhyTone.drawFused: why/combine RAW_SOURCE 1 into a w x h viewport at origin of a larger 8-bit target."""
        ox, oy = origin
        out = ctx.texture((w + ox + 3, h + oy + 2), 4)
        fb = ctx.framebuffer([out])
        fb.use()
        fb.clear(0.0, 0.0, 0.0, 1.0)
        ctx.viewport = (ox, oy, w, h)
        for i in range(6):
            ones.use(i)
        ones.use(7)
        t_curve.use(8)
        t_raw.use(9)
        t_lsc.use(10)
        put(prog_fused, dict(tone_vals, **raw_vals(f, crop, rot, mirror), signedHueU=signed, GainMap=7, InputBuffer=0, WhyLow=1, WhyColour=2,
                             WhyFused=3, WhyDetailRef=4, WhyLumaS=5, sTexture16=9, u_lsc_map=10, vpOriginU=(ox, oy), vpSizeU=(w, h)))
        vao_fused.render(moderngl.TRIANGLE_STRIP)
        res = np.frombuffer(out.read(), np.uint8).reshape(h + oy + 2, w + ox + 3, 4)[oy:oy + h, ox:ox + w, :3].astype(np.int16)
        fb.release()
        out.release()
        return res

    # name, frame w x h, CFA, block, cfa_mode (1: plain Bayer, 0: one colour per 2 x 2 macro pixels), viewport, rotation,
    # mirror, viewport origin, flat
    cases = [('1x RGGB portrait', 1024, 768, 0, 1, 1, (360, 480), 180, 0, (0, 0), False),
             ('1x RGGB portrait, origin (7, 5)', 1024, 768, 0, 1, 1, (360, 480), 180, 0, (7, 5), False),
             ('1x GRBG portrait, mirror', 1024, 768, 1, 1, 1, (360, 480), 0, 1, (0, 0), False),
             ('1x GBRG landscape', 1024, 768, 2, 1, 1, (480, 360), 90, 0, (3, 0), False),
             ('1x BGGR portrait 270', 1024, 768, 3, 1, 1, (360, 480), 270, 0, (0, 0), False),
             ('1x RGGB 16:9 viewport', 1024, 768, 0, 1, 1, (360, 640), 180, 0, (0, 0), False),
             ('1x RGGB flat', 1024, 768, 0, 1, 1, (360, 480), 180, 0, (0, 0), True),
             ('Quad cell means (2x ISZ)', 512, 384, 3, 1, 0, (360, 480), 180, 0, (0, 0), False),
             ('Quad cell means, mirror', 512, 384, 0, 1, 0, (360, 480), 0, 1, (0, 0), False),
             ('Quad block 2', 1024, 768, 3, 2, 0, (360, 480), 180, 0, (0, 0), False)]
    # P370 (B2, review): both passes with the signed hue off (signedHueU / u_signed_hue 0, the shaders before P370) and on (the
    # shipped default, scam_dev ccm_signed_hue 1: the linear frame keeps a colour outside sRGB signed), the same frames
    for signed, name, fw, fh, cfa, block, mode, vp, deg, mirror, origin, flat in [(sg,) + c for sg in (0, 1) for c in cases]:
        if name == cases[0][0]:
            rng = np.random.default_rng(365)
        name = '%s%s' % (name, ', signed hue' if signed else '')
        img = scene(fh, fw, rng, flat)
        raw = mosaic(img, cfa, block, rng, black, noise=not flat)
        t_raw = ctx.texture((fw, fh), 1, raw.tobytes(), dtype='u2')
        t_raw.filter = (moderngl.NEAREST, moderngl.NEAREST)
        rot = rotate_m(deg)
        quarter = abs(rot[1]) > abs(rot[0])
        f = {'w': fw, 'h': fh, 'cfa': cfa, 'block': block, 'mode': mode}
        crop0 = visible_crop(fw, fh, block, vp[0], vp[1], quarter)
        w, h = tone_size(vp[0], vp[1], crop0[2], crop0[3], 1 if mode == 0 and block == 1 else block, quarter)
        crop = visible_crop(fw, fh, block, w, h, quarter)
        old = p314(f, t_raw, w, h, crop, rot, mirror, origin, signed)
        new = fused(f, t_raw, w, h, crop, rot, mirror, origin, signed)
        d = np.abs(new - old)
        mean, p99, mx = d.mean(), np.percentile(d, 99), d.max()
        direct = (w, h) == vp
        print('%-34s tone %4dx%-4d (%s): mean |d| %.3f, p99 %.0f, max %d codes (%.3f %% > 10); output mean %.0f'
              % (name, w, h, 'direct, 1 pass' if direct else '2 passes', mean, p99, mx, 100.0 * (d.max(-1) > 10).mean(),
                 old.mean()))
        check(old.max() > 40, '%s: the P314 path renders nothing' % name)
        check(mean <= MEAN_MAX and p99 <= P99_MAX, '%s: the fused pass differs from P314 (mean %.3f, p99 %.1f codes)' % (name, mean, p99))
        check(mx <= ABS_MAX, '%s: a pixel differs from P314 by %d codes (a quad-border tie picked apart? VF_TIE / RAW_TIE)' % (name, mx))
        if flat:
            check(mx <= 1, '%s: a flat frame differs by %d codes' % (name, mx))
        if name.startswith('1x RGGB portrait') and 'origin' not in name:
            check(direct, '%s: the 1x Bayer tone is not the viewport (the direct pass)' % name)
        if name.startswith('Quad cell'):
            check(not direct, '%s: the mosaic tone is the viewport' % name)
        t_raw.release()
    ctx.release()


def main() -> int:
    source_part()
    glslc_part()
    gpu_part()
    if FAILURES:
        print('fused viewfinder check FAILED (%d)' % len(FAILURES))
        return 1
    print('fused viewfinder check PASS')
    return 0


if __name__ == '__main__':
    sys.exit(main())
