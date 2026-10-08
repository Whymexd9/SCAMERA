"""P46 colour output check (Display P3, HDR HLG), no device.

    python3 tools/check_colour_output.py            every part whose tools are installed (the rest is reported as skipped)
    python3 tools/check_colour_output.py --shader   ark/combine.glsl: P3_OUT 0 is the pre-P46 shader token for token
                                                    (+ glslc compiles both variants when an NDK is found)
    python3 tools/check_colour_output.py --gl       ark/combine.glsl on the GPU (moderngl): Display P3 renders the sRGB
                                                    picture exactly for colours inside sRGB and keeps wider colours
    python3 tools/check_colour_output.py --files    the app's ICC / JPEG / WebP / HEIF code (javac) on files of Pillow,
                                                    libwebp and libheif, read back by them; the HLG maths against an
                                                    independent BT.2100 implementation (needs android.jar: $ANDROID_HOME)
    add --strict to fail instead of skipping a part whose tools are missing.

Needs numpy; --gl moderngl (LIBGL_ALWAYS_SOFTWARE=1 on a headless Linux); --files Pillow (WebP, littlecms2), pillow-heif,
a JDK.
"""
from __future__ import annotations

import glob
import hashlib
import io
import json
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
JAVA = ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing'
# SHA-256 of the token stream of ark/combine.glsl at a3e94db (before P46), #import expanded, comments dropped.
BASELINE_COMBINE_TOKENS = 'ef87351cdb1f4119297bd776ad6639eb1fc09d1c8518c31ffe25be7c84497490'
BASELINE_COMMIT = 'a3e94db'

failures: list[str] = []
skipped: list[str] = []


def check(cond: bool, msg: str) -> None:
    if not cond:
        failures.append(msg)
        print('FAIL: ' + msg)


# ------------------------------------------------------------------------------------------------ colour reference

def rgb_to_xyz(xy, white=(0.3127, 0.3290)):
    p = np.array([[x / y, 1.0, (1 - x - y) / y] for x, y in zip(xy[0::2], xy[1::2])]).T
    w = np.array([white[0] / white[1], 1.0, (1 - white[0] - white[1]) / white[1]])
    return p * np.linalg.solve(p, w)


SRGB = (0.64, 0.33, 0.30, 0.60, 0.15, 0.06)
P3 = (0.680, 0.320, 0.265, 0.690, 0.150, 0.060)
BT2020 = (0.708, 0.292, 0.170, 0.797, 0.131, 0.046)
S2P = np.linalg.inv(rgb_to_xyz(P3)) @ rgb_to_xyz(SRGB)
P2S = np.linalg.inv(S2P)
S2B = np.linalg.inv(rgb_to_xyz(BT2020)) @ rgb_to_xyz(SRGB)
P2B = np.linalg.inv(rgb_to_xyz(BT2020)) @ rgb_to_xyz(P3)


def srgb_eotf(v):
    v = np.asarray(v, float)
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)


def srgb_oetf(v):
    v = np.clip(np.asarray(v, float), 0, None)
    return np.where(v <= 0.0031308, v * 12.92, 1.055 * v ** (1 / 2.4) - 0.055)


HLG_A = 0.17883277
HLG_B = 1 - 4 * HLG_A
HLG_C = 0.5 - HLG_A * np.log(4 * HLG_A)


def hlg_oetf(e):
    e = np.clip(np.asarray(e, float), 0, 1)
    return np.where(e <= 1 / 12, np.sqrt(3 * e), HLG_A * np.log(np.maximum(12 * e - HLG_B, 1e-12)) + HLG_C)


def hlg_from_display(rgb, peak=1000.0, ref=203.0, gamma=1.2, knee=0.8):
    """BT.2100 HLG signal of BT.2020 display light relative to SDR white (1 = 203 cd/m2), BT.2408 placement; the soft
    knee of the brightest channel above 80 % of the headroom is SCAMERA's own (HlgRendition.knee)."""
    rgb = np.clip(np.asarray(rgb, float), 0, None)
    p = peak / ref
    m = rgb.max(-1, keepdims=True)
    k = knee * p
    kneed = np.where(m > k, k + (p - k) * (1 - np.exp(-(m - k) / (p - k))), m)
    rgb = np.where(m > k, rgb * kneed / np.maximum(m, 1e-12), rgb)
    fd = rgb * ref / peak                      # display light, 1 = the nominal peak
    yd = fd @ np.array([0.2627, 0.6780, 0.0593])
    scale = np.where(yd > 0, np.maximum(yd, 1e-30) ** ((1 - gamma) / gamma), 0.0)
    return hlg_oetf(np.minimum(fd * scale[..., None], 1.0))


def oklab(lin):
    """OKLab of linear sRGB coordinates (negative ones allowed: wide colours)."""
    m1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566],
                   [0.0883024619, 0.2817188376, 0.6299787005]])
    m2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099],
                   [0.0259040371, 0.7827717662, -0.8086757660]])
    return np.cbrt(np.maximum(np.asarray(lin) @ m1.T, 0)) @ m2.T


# ------------------------------------------------------------------------------------------------ shader

def expand_imports(src: str) -> str:
    out = []
    for line in src.replace('\r\n', '\n').split('\n'):
        if '#import' in line and '//' not in line:
            name = line.replace('#', '').replace(' ', '_').strip()
            out.append((SH / 'utils' / (name + '.glsl')).read_text(encoding='utf-8').replace('\r\n', '\n'))
        else:
            out.append(line)
    return '\n'.join(out)


def set_define(src: str, name: str, value: str) -> str:
    """GLInterface.readProgram: a '#define NAME x' line becomes '#define NAME value'."""
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


def token_hash(src: str) -> str:
    return hashlib.sha256(' '.join(tokens(preprocess(expand_imports(src)))).encode()).hexdigest()


def find_glslc() -> str | None:
    found = shutil.which('glslc')
    if found:
        return found
    for env in ('ANDROID_NDK_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        base = os.environ.get(env)
        if base:
            hits = sorted(glob.glob(os.path.join(base, '**', 'shader-tools', '*', 'glslc*'), recursive=True), reverse=True)
            if hits:
                return hits[0]
    return None


def shader_part(strict: bool) -> None:
    new = (SH / 'ark/combine.glsl').read_text(encoding='utf-8')
    got = token_hash(new)
    print('combine.glsl P3_OUT 0 token stream: %s' % got)
    check(got == BASELINE_COMBINE_TOKENS, 'combine.glsl (P3_OUT 0) differs from the pre-P46 shader: %s' % got)
    try:
        base = subprocess.run(['git', '-C', str(ROOT), 'show', BASELINE_COMMIT + ':app/src/main/assets/shaders/ark/combine.glsl'],
                              capture_output=True, check=True).stdout.decode('utf-8')
        check(token_hash(base) == BASELINE_COMBINE_TOKENS, 'the stored baseline hash is not the %s shader' % BASELINE_COMMIT)
        print('  %s shader: the same token stream' % BASELINE_COMMIT)
    except (subprocess.CalledProcessError, FileNotFoundError):
        print('  (%s not in this checkout: the stored hash is the reference)' % BASELINE_COMMIT)
    p3 = preprocess(expand_imports(set_define(new, 'P3_OUT', '1')))
    check('clampP3' in p3 and 'fromOklabInGamutP3' in p3 and 'SRGB_TO_P3' in p3, 'P3_OUT 1 lost its P3 code')
    glslc = find_glslc()
    if not glslc:
        (failures if strict else skipped).append('glslc not found: the shader variants were not compiled')
        print('  glslc not found: compile skipped')
        return
    for label, define in (('P3_OUT 0', '0'), ('P3_OUT 1', '1')):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / 'combine.frag'
            f.write_text('#version 310 es\n' + expand_imports(set_define(new, 'P3_OUT', define)), encoding='utf-8')
            r = subprocess.run([glslc, '--target-env=opengl', '-fshader-stage=fragment', '-std=310es', '-fauto-map-locations',
                                '-fauto-bind-uniforms', '-o', os.devnull, str(f)], capture_output=True, text=True)
            check(r.returncode == 0, 'glslc: %s does not compile:\n%s' % (label, r.stderr))
            print('  glslc %s: %s' % (label, 'OK' if r.returncode == 0 else 'FAIL'))


# ------------------------------------------------------------------------------------------------ GPU

def gl_part(strict: bool) -> None:
    try:
        import moderngl
    except ImportError:
        (failures if strict else skipped).append('moderngl missing: GPU part skipped')
        print('moderngl missing: GPU part skipped')
        return
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    src = expand_imports((SH / 'ark/combine.glsl').read_text(encoding='utf-8'))
    rng = np.random.default_rng(46)
    # Colours inside sRGB (random linear values over 7 stops, saturated ones included) and colours outside it (P3 primaries
    # and mixtures: in sRGB coordinates some channels are negative), as the colour source and the full-size input.
    # ordinary colours (half the saturation of random ones: skin, foliage, sky, walls) and saturated ones inside sRGB
    plain = rng.random((600, 3))
    plain = (plain.mean(1, keepdims=True) + 0.45 * (plain - plain.mean(1, keepdims=True))) ** 2 * (2.0 ** rng.uniform(-6, 1, (600, 1)))
    vivid = rng.random((300, 3)) ** 2 * (2.0 ** rng.uniform(-6, 1, (300, 1)))
    inside = np.concatenate([plain, vivid])
    p3cols = np.array([[1, 0, 0], [0, 1, 0], [0, 0, 1], [1, 1, 0], [0, 1, 1], [1, 0, 1], [1, .5, 0], [.2, 1, .3], [0, .6, 1], [1, .2, .1]])
    outside = np.concatenate([p3cols * s for s in (0.05, 0.15, 0.4, 0.8)]) @ P2S.T
    colours = np.concatenate([inside, outside]).astype(np.float32)
    n = len(colours)
    W = 64
    H = -(-n // W)
    img = np.zeros((H, W, 4), np.float32)
    img.reshape(-1, 4)[:n, :3] = colours
    lum = np.array([0.2126, 0.7152, 0.0722])
    fused = np.zeros((H, W, 4), np.float32)
    y = np.maximum(np.maximum(colours @ lum, colours.max(1) * 0.5), 1e-4)
    # fused = target^(1/2.2) with the toe of the kernel's ACES: invert numerically on a grid
    grid = np.linspace(0, 2.51 / 2.43 - 0.0101, 20000)

    def inverse_aces(v, toe=0.05):
        b = max(toe * 0.75, 0.005)
        e = max(0.14 - (toe - 0.04) * 0.8, 0.03)
        A = 2.43 * v - 2.51
        B = 0.59 * v - b
        C = e * v
        disc = B * B - 4 * A * C
        x = (-B - np.sqrt(np.maximum(disc, 0))) / (2 * A - 1e-6)
        return np.maximum(x / 1.5, 0)
    table = inverse_aces(grid)
    fused.reshape(-1, 4)[:n, 0] = np.interp(np.minimum(y, table.max()), table, grid) ** (1 / 2.2)

    def tex(a):
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.astype('f4').tobytes(), dtype='f4')
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t
    ones = tex(np.ones((1, 1, 4), np.float32))
    t_img, t_fused = tex(img), tex(fused)
    vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'

    def render(define: str, shader: str = src) -> np.ndarray:
        prog = ctx.program(vertex_shader=vs, fragment_shader='#version 300 es\n' + set_define(shader, 'P3_OUT', define))
        out = ctx.texture((W, H), 4, dtype='f4')
        fb = ctx.framebuffer([out])
        fb.use()
        ctx.viewport = (0, 0, W, H)
        units = {'InputBuffer': t_img, 'GainMap': ones, 'ArkLow': t_img, 'ArkColour': t_img, 'ArkFused': t_fused,
                 'ArkDetailRef': t_img, 'ArkLumaS': t_img}
        for i, (k, t) in enumerate(units.items()):
            if k in prog:
                t.use(i)
                prog[k].value = i
        eye = tuple(np.eye(3).flatten())
        vals = {'sensorToIntermediate': eye, 'intermediateToSRGB': eye, 'neutralPointU': (1, 1, 1), 'inScaleU': 1.0,
                'fU': 1, 'colourFU': 1, 'aeU': 1.0, 'clipU': 1.0, 'toeU': 0.05, 'gammaInvU': 1 / 2.2, 'macroU': 1.1,
                'vibU': (0.0, 0.4, 0.2), 'detailGainU': 1.0, 'deltaChromaU': 1.0, 'filmToeU': 0.1, 'ditherU': 0,
                'agxAU': (2.7, 1.35, 1.6, 1.0), 'agxBU': (-8.5, 3.5, 0.3, 4.0), 'headroomU': 0, 'hlWhiteU': 0.15}
        for k, v in vals.items():
            if k in prog:
                prog[k].value = v
        ctx.vertex_array(prog, []).render(vertices=3)
        res = np.frombuffer(out.read(), np.float32).reshape(H, W, 4)[:, :, :3].reshape(-1, 3)[:n]
        fb.release()
        out.release()
        prog.release()
        return res

    srgb, p3 = render('0'), render('1')
    try:
        base = subprocess.run(['git', '-C', str(ROOT), 'show', BASELINE_COMMIT + ':app/src/main/assets/shaders/ark/combine.glsl'],
                              capture_output=True, check=True).stdout.decode('utf-8')
        before = render('0', expand_imports(base))
        check(np.array_equal(before, srgb), 'P3_OUT 0 renders differently from the %s shader' % BASELINE_COMMIT)
        print('GPU: P3_OUT 0 = the %s shader, bit for bit (%d colours)' % (BASELINE_COMMIT, n))
    except (subprocess.CalledProcessError, FileNotFoundError):
        print('GPU: (%s not in this checkout: the bit-exact comparison with it is skipped)' % BASELINE_COMMIT)
    # display-encoded (power 1/2.2) -> linear; the P3 picture in sRGB coordinates
    lin_s = np.maximum(srgb, 0) ** 2.2
    lin_p = (np.maximum(p3, 0) ** 2.2) @ P2S.T
    k, kp = len(inside), len(plain)
    # Colours inside sRGB render the same picture (the P3 file is the sRGB render in P3 primaries) wherever the sRGB render
    # clamps nothing on the way. Where it does - a saturated dark colour pushed past the gamut by the OKLab contrast, a
    # saturated light past it by AgX, the final limit - the sRGB render clamps a channel (or the chroma) and the P3 render
    # keeps the colour: never less chroma, about the same lightness.
    for label, a, b in (('ordinary', 0, kp), ('saturated', kp, k)):
        d = np.abs(np.clip(lin_p[a:b], 0, 1) ** (1 / 2.2) - lin_s[a:b] ** (1 / 2.2)).max(1) * 255
        ls, lp = oklab(lin_s[a:b]), oklab(lin_p[a:b])
        cs, cp = np.hypot(ls[:, 1], ls[:, 2]), np.hypot(lp[:, 1], lp[:, 2])
        dl = np.abs(lp[:, 0] - ls[:, 0])
        ratio = (cp / np.maximum(cs, 1e-6))[(d > 0.6) & (cs > 0.02)]
        print('GPU: %s colours inside sRGB (%d): %d rendered the same (<= 0.6 codes), p99 %.2f codes; the others keep chroma '
              'x%.2f median (min x%.2f), |dL| max %.4f' % (label, b - a, (d <= 0.6).sum(), np.percentile(d, 99),
                                                          np.median(ratio) if len(ratio) else 1, ratio.min() if len(ratio) else 1, dl.max()))
        if label == 'ordinary':
            check((d <= 0.6).mean() >= 0.95, 'ordinary colours render differently in Display P3 (%.0f %% the same)' % (100 * (d <= 0.6).mean()))
        check(len(ratio) == 0 or ratio.min() >= 0.97, 'the P3 render lost chroma of a %s colour' % label)
        check(dl.max() < 0.05, 'the P3 render changed the lightness of a %s colour (%.3f)' % (label, dl.max()))
    # outside sRGB: the sRGB render is clipped at the sRGB gamut, the P3 render keeps more chroma, inside P3
    out_p3 = np.maximum(p3[k:], 0) ** 2.2
    check(out_p3.min() >= -1e-6 and out_p3.max() <= 1 + 1e-5, 'Display P3 output outside [0, 1]')
    beyond = (lin_p[k:] < -0.002).any(1)
    print('GPU: outside sRGB: %d of %d wide colours stay outside sRGB in the P3 render (the sRGB render clips them all)'
          % (beyond.sum(), len(outside)))
    check(beyond.mean() > 0.5, 'Display P3 did not keep the wide colours (%d of %d)' % (beyond.sum(), len(outside)))

    def chroma(lin):
        lab = oklab(lin)
        return np.hypot(lab[:, 1], lab[:, 2])
    gain = chroma(lin_p[k:]) / np.maximum(chroma(lin_s[k:]), 1e-6)
    print('GPU: wide colours: OKLab chroma P3 / sRGB render median %.2f, max %.2f' % (np.median(gain), gain.max()))
    check(np.median(gain) > 1.05, 'the P3 render is not more chromatic for wide colours')
    ctx.release()


# ------------------------------------------------------------------------------------------------ files

def compile_java(tmp: Path, strict: bool) -> tuple[str, str] | None:
    javac, java = shutil.which('javac'), shutil.which('java')
    if not javac or not java:
        (failures if strict else skipped).append('no JDK: the file part was skipped')
        print('no JDK (javac / java): file part skipped')
        return None
    android = None
    for env in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        base = os.environ.get(env)
        if base:
            jars = sorted(glob.glob(os.path.join(base, 'platforms', 'android-*', 'android.jar')), reverse=True)
            if jars:
                android = jars[0]
                break
    if not android:
        (failures if strict else skipped).append('no android.jar ($ANDROID_HOME): the file part was skipped')
        print('no android.jar ($ANDROID_HOME): file part skipped')
        return None
    stub = tmp / 'stub/androidx/annotation'
    stub.mkdir(parents=True)
    (stub / 'RequiresApi.java').write_text('package androidx.annotation; public @interface RequiresApi { int value() default 1; int api() default 1; }')
    classes = tmp / 'classes'
    files = [JAVA / 'color/OutputColour.java', JAVA / 'color/IccProfiles.java', JAVA / 'color/IccEmbed.java',
             JAVA / 'color/HlgRendition.java', JAVA / 'heif/HeifColourPatch.java', JAVA / 'heif/TenBitBitmaps.java',
             JAVA / 'ultrahdr/GainMapComputer.java', stub / 'RequiresApi.java', ROOT / 'tools/java/ColourOutputSample.java']
    subprocess.run([javac, '-encoding', 'UTF-8', '-cp', android, '-d', str(classes)] + [str(f) for f in files], check=True)
    return java, os.pathsep.join([str(classes), android])


def run_sample(jc, *args):
    java, cp = jc
    subprocess.run([java, '-cp', cp, 'ColourOutputSample'] + [str(a) for a in args], check=True)


def files_part(strict: bool) -> None:
    from PIL import Image, ImageCms, features
    with tempfile.TemporaryDirectory() as d:
        tmp = Path(d)
        jc = compile_java(tmp, strict)
        if jc is None:
            return
        # 1. matrices and luma: Java vs the reference here
        out = subprocess.run([jc[0], '-cp', jc[1], 'ColourOutputSample', 'matrices'], capture_output=True, text=True, check=True).stdout
        m = json.loads(out)
        for name, ref in (('srgbToP3', S2P), ('p3ToSrgb', P2S), ('srgbToBt2020', S2B), ('p3ToBt2020', P2B)):
            check(np.allclose(np.array(m[name]).reshape(3, 3), ref, atol=1e-9), 'matrix %s differs' % name)
        check(np.allclose(m['lumaP3'], rgb_to_xyz(P3)[1], atol=1e-12), 'P3 luma differs')
        print('matrices: sRGB <-> P3, sRGB / P3 -> BT.2020 and the P3 luma agree with the reference')
        # 2. the ICC profile through littlecms
        run_sample(jc, 'icc', tmp / 'p3.icc')
        icc = (tmp / 'p3.icc').read_bytes()
        if features.check('littlecms2'):
            prof = ImageCms.ImageCmsProfile(io.BytesIO(icc))
            check(ImageCms.getProfileDescription(prof).strip() == 'Display P3', 'profile description')
            srgb = ImageCms.createProfile('sRGB')
            src = np.array([[[255, 0, 0], [0, 255, 0], [0, 0, 255], [200, 100, 50], [128, 128, 128], [255, 255, 255]]], np.uint8)
            got = np.asarray(ImageCms.applyTransform(Image.fromarray(src), ImageCms.buildTransform(srgb, prof, 'RGB', 'RGB', renderingIntent=1)), float)
            want = srgb_oetf(np.clip(srgb_eotf(src / 255.0) @ S2P.T, 0, 1)) * 255
            err = np.abs(got - want).max()
            print('ICC: littlecms sRGB -> our Display P3 profile vs the matrix: max %.2f codes' % err)
            check(err <= 2.0, 'littlecms conversion into the profile is %.1f codes off' % err)
        else:
            (failures if strict else skipped).append('Pillow without littlecms2: the profile was not interpreted')
        # 3. JPEG: Pillow's JPEG (with and without its own profile) -> our APP2 -> Pillow reads it, pixels unchanged
        pic = Image.fromarray((np.random.default_rng(3).random((48, 64, 3)) * 255).astype(np.uint8))
        for label, kw in (('plain', {}), ('sRGB-tagged', {'icc_profile': ImageCms.ImageCmsProfile(ImageCms.createProfile('sRGB')).tobytes()}),
                          ('Exif', {'exif': Image.Exif().tobytes()})):
            pic.save(tmp / 'a.jpg', quality=95, **kw)
            run_sample(jc, 'jpeg', tmp / 'a.jpg', tmp / 'b.jpg')
            with Image.open(tmp / 'a.jpg') as a, Image.open(tmp / 'b.jpg') as b:
                check(b.info.get('icc_profile') == icc, 'JPEG (%s): the profile does not read back' % label)
                check(np.array_equal(np.asarray(a), np.asarray(b)), 'JPEG (%s): pixels changed' % label)
        print('JPEG: the profile reads back (plain, sRGB-tagged: replaced, Exif), pixels unchanged')
        # 4. WebP: lossy / lossless / with Exif (VP8X) -> ICCP after VP8X
        if features.check('webp'):
            for label, kw in (('lossy', {'quality': 90}), ('lossless', {'lossless': True}), ('lossy + Exif', {'quality': 90, 'exif': b'Exif\0\0II*\0\x08\0\0\0\0\0'})):
                pic.save(tmp / 'a.webp', **kw)
                run_sample(jc, 'webp', tmp / 'a.webp', tmp / 'b.webp', 64, 48)
                with Image.open(tmp / 'a.webp') as a, Image.open(tmp / 'b.webp') as b:
                    check(b.info.get('icc_profile') == icc, 'WebP (%s): the profile does not read back' % label)
                    check(np.array_equal(np.asarray(a.convert('RGB')), np.asarray(b.convert('RGB'))), 'WebP (%s): pixels changed' % label)
                    if 'exif' in kw:
                        check(b.info.get('exif') is not None, 'WebP: Exif lost')
            print('WebP: the profile reads back (lossy, lossless, with Exif), pixels unchanged')
        else:
            (failures if strict else skipped).append('Pillow without WebP')
        # 5. HEIF (libheif's own file, meta before mdat): the profile added, decodes the same
        try:
            import pillow_heif
        except ImportError:
            pillow_heif = None
            (failures if strict else skipped).append('pillow-heif missing: the HEIF patch was not checked')
        if pillow_heif is not None:
            for label, kw in (('nclx', {'color_primaries': 1, 'transfer_characteristic': 13, 'matrix_coefficients': 6, 'full_range_flag': 1}),
                              ('no nclx', {})):
                h = pillow_heif.from_pillow(pic)
                h.save(str(tmp / 'a.heic'), quality=90, **kw)
                run_sample(jc, 'heif', tmp / 'a.heic', tmp / 'b.heic')
                a, b = pillow_heif.open_heif(str(tmp / 'a.heic')), pillow_heif.open_heif(str(tmp / 'b.heic'))
                check(b.info.get('icc_profile') == icc, 'HEIF (%s): the profile does not read back' % label)
                check(np.array_equal(np.asarray(a), np.asarray(b)), 'HEIF (%s): pixels changed' % label)
                # libheif reports the ICC profile when there is one: the nclx box is read from the boxes
                sys.path.insert(0, str(ROOT / 'tools'))
                import check_heic10
                summary = check_heic10.check_file(tmp / 'b.heic', app_brands=False)
                if 'color_primaries' in kw:
                    check(summary.get('nclx') == (12, 13, 6, 1), 'HEIF (%s): nclx %s' % (label, summary.get('nclx')))
            print('HEIF: the profile added to libheif files (with / without nclx: primaries -> 12, matrix kept), pixels unchanged')
        # 6. HLG: Java vs the BT.2100 reference here
        rng = np.random.default_rng(2100)
        light = np.concatenate([np.array([[1, 1, 1], [0, 0, 0], [0.18, 0.18, 0.18], [4.9, 4.9, 4.9], [20, 3, 1], [0.01, 0.5, 2]]),
                                rng.random((400, 3)) * (2.0 ** rng.uniform(-8, 3, (400, 1)))])
        (tmp / 'hlg_in.txt').write_text('\n'.join('%.17g %.17g %.17g' % tuple(c) for c in light))
        run_sample(jc, 'hlg', tmp / 'hlg_in.txt', tmp / 'hlg_out.txt')
        java_hlg = np.loadtxt(tmp / 'hlg_out.txt')
        ref = hlg_from_display(light)
        err = np.abs(java_hlg - ref).max()
        print('HLG: Java vs the BT.2100 reference: max %.2e; SDR white -> %.4f (BT.2408: 0.75)' % (err, java_hlg[0, 0]))
        check(err < 1e-9, 'HLG signal differs from the reference by %.3g' % err)
        check(abs(java_hlg[0, 0] - 0.75) < 0.001, 'SDR white is not 75 % HLG')
        # 7. the renderer: base pixel + gain map value -> HLG pixel, against the reference chain
        lines, want = [], []
        for _ in range(300):
            ten = rng.random() < 0.5
            space = 'p3' if rng.random() < 0.5 else 'srgb'
            gmax = float(rng.uniform(0.5, 6))
            levels = 1023 if ten else 255
            rgb = rng.integers(0, levels + 1, 3)
            px = (int(rgb[0]) | int(rgb[1]) << 10 | int(rgb[2]) << 20 | 3 << 30) if ten else (int(rgb[0]) | int(rgb[1]) << 8 | int(rgb[2]) << 16 | 255 << 24)
            g = int(rng.integers(0, 256))
            lines.append('%d %s 0 %r %r %d %d' % (ten, space, gmax, gmax, px - (1 << 32) if px >= 1 << 31 else px, g))
            lin = srgb_eotf(rgb / levels)
            w = min(1.0, np.log2(1000 / 203) / gmax)
            boost = 2.0 ** (gmax * g / 255 * w)
            hdr = (lin + 1 / 64) * boost - 1 / 64
            hdr = hdr @ (P2B if space == 'p3' else S2B).T
            want.append(np.round(np.clip(hlg_from_display(hdr), 0, 1) * 1023))
        (tmp / 'r_in.txt').write_text('\n'.join(lines))
        run_sample(jc, 'render', tmp / 'r_in.txt', tmp / 'r_out.txt')
        got = np.array([[v & 0x3FF, (v >> 10) & 0x3FF, (v >> 20) & 0x3FF] for v in (int(x) & 0xFFFFFFFF for x in (tmp / 'r_out.txt').read_text().split())])
        err = np.abs(got - np.array(want)).max()
        print('HLG renderer: Java vs the reference chain (sRGB / P3 base, 8 / 10 bit, gain map): max %d code(s)' % err)
        check(err <= 1, 'the HLG renderer differs from the reference by %d codes' % err)


def main(argv: list[str]) -> int:
    strict = '--strict' in argv
    parts = [a for a in argv if a in ('--shader', '--gl', '--files')] or ['--shader', '--gl', '--files']
    if '--shader' in parts:
        shader_part(strict)
    if '--gl' in parts:
        gl_part(strict)
    if '--files' in parts:
        files_part(strict)
    for s in skipped:
        print('SKIPPED: ' + s)
    if failures:
        print('colour output check FAIL (%d)' % len(failures))
        return 1
    print('colour output check PASS (%s)' % ' '.join(p.lstrip('-') for p in parts))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
