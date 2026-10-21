#!/usr/bin/env python3
"""P317: no mediump (fp16 on Mali) float operation of an asset shader may use a constant outside the fp16 normal range.

A fragment shader's ints default to mediump (GLSL ES 3.10 4.7.4), and so does every float expression built from them and
literals only. Mali runs mediump as real fp16 (Adreno often as fp32, desktop GL always): film/noise.glsl's
float(h & 0xFFFFu) + 0.5) / 65536.0 was mediump, 65536.0 is +inf in fp16, the Box-Muller noise +inf in every pixel and every
film photo with grain came out pure white on the vivo X300 Pro (Mali-G1-Ultra). Host replays never see it.

Every asset shader is compiled as GLProg hands it to the driver (#version 310 es, #import from shaders/utils, the LAYOUT define
of a compute shader; the viewfinder's main_fs with the live lens filter spliced in and rawdevelop_fs) by the NDK's
glslc and disassembled; glslang marks every mediump operation RelaxedPrecision. A relaxed float operation fails the check when
one of its constants is above 65504 (+inf in fp16), non-zero below 2^-14 (an fp16 denormal, flushed to zero on many GPUs), or
when it divides by a constant above 16384 (its reciprocal is a denormal). Fix: `precision highp int;` (P305, P317) or highp
operands. A shader that does not compile on its own (one that needs the app's defines) is listed as not checked.

usage: python3 tools/check_shader_fp16.py [--glslc PATH]   (default: glslc on PATH or under $ANDROID_NDK_HOME / $ANDROID_HOME)
"""
from __future__ import annotations

import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SH = ROOT / 'app/src/main/assets/shaders'
FP16_MAX = 65504.0
FP16_MIN_NORMAL = 2.0 ** -14
LAYOUT = 'layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;'
LIVE_MARKER = '//#lensfilter_live'          # LiveLensFilter.MARKER
OES = '#extension GL_OES_EGL_image_external_essl3 : require'


def find_glslc(argv: list[str]) -> str | None:
    if '--glslc' in argv:
        return argv[argv.index('--glslc') + 1]
    found = shutil.which('glslc')
    if found:
        return found
    for env in ('ANDROID_NDK_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        base = os.environ.get(env)
        if base and os.path.isdir(base):
            for pattern in (('ndk', '*', 'shader-tools', '*', 'glslc*'), ('shader-tools', '*', 'glslc*')):
                hits = sorted(glob.glob(os.path.join(base, *pattern)), reverse=True)
                if hits:
                    return hits[0]
    return None


def read(p: Path) -> str:
    return p.read_text(encoding='utf-8').replace('\r\n', '\n')


def expand_imports(src: str) -> str:
    # GLInterface.readProgram: "#import x" -> "#line 1", shaders/utils/x.glsl, "#line <next>" (an import it cannot load is
    # dropped), so the reported lines are the shader file's own
    out = []
    for n, line in enumerate(src.split('\n'), 1):
        if '#import' in line and '//' not in line:
            p = SH / 'utils' / (line.replace('#', '').strip().replace(' ', '_') + '.glsl')
            if p.exists():
                out += ['#line 1', read(p), '#line %d' % (n + 1)]
            continue
        out.append(line)
    return '\n'.join(out)


def units() -> list[tuple[str, str, str]]:
    """(label, stage, source) as the app compiles them."""
    out = []
    for p in sorted(SH.rglob('*.glsl')):
        rel = p.relative_to(SH).as_posix()
        if rel.startswith('preview/') or rel.startswith('utils/import_'):
            continue
        src = read(p)
        compute = '\nLAYOUT' in src or 'gl_GlobalInvocationID' in src
        if compute:
            src = src.replace('\nLAYOUT\n', '\n' + LAYOUT + '\n', 1)
        text = expand_imports(src)
        if '#version' not in text:
            text = '#version 310 es\n#line 1\n' + text   # GLProg.glVersion (#line 1: the file's own line numbers)
        out.append((rel, 'comp' if compute else 'frag', text))
    # the viewfinder in its 2D form (LiveLensFilter.to2d): glslang has no texture() of a samplerExternalOES, the arithmetic
    # of the ISP form is the same
    main = read(SH / 'preview/main_fs.glsl').replace(LIVE_MARKER, read(SH / 'preview/lensfilter_live.glsl'))
    prep = read(SH / 'preview/lensfilter_prep.glsl')
    for label, text in (('preview/main_fs.glsl (2D)', main), ('preview/lensfilter_prep.glsl (2D)', prep)):
        out.append((label, 'frag', '#version 310 es\n#line 1\n' + text.replace(OES, '').replace('samplerExternalOES', 'sampler2D')))
    raw = read(SH / 'preview/rawdevelop_fs.glsl').replace('#version 300 es', '#version 310 es', 1)
    out.append(('preview/rawdevelop_fs.glsl', 'frag', raw))
    return out


def hazards(asm: list[str]) -> list[str]:
    relaxed = {m.group(1) for l in asm for m in [re.match(r'\s*OpDecorate %(\w+) RelaxedPrecision', l)] if m}
    consts: dict[str, list[float]] = {}
    for l in asm:
        m = re.match(r'\s*%(\w+) = OpConstant %float (\S+)', l)
        if m:
            consts[m.group(1)] = [float(m.group(2))]
            continue
        m = re.match(r'\s*%(\w+) = OpConstantComposite %v\dfloat (.*)', l)
        if m:
            ids = re.findall(r'%(\w+)', m.group(2))
            if all(i in consts for i in ids):
                consts[m.group(1)] = [v for i in ids for v in consts[i]]
    found, line = [], '?'
    for l in asm:
        m = re.match(r'\s*OpLine %\w+ (\d+)', l)
        if m:
            line = m.group(1)
            continue
        m = re.match(r'\s*%(\w+) = (Op\w+) %(\w+) (.*)', l)
        if not m or m.group(1) not in relaxed or 'float' not in m.group(3):
            continue
        op = m.group(2)
        for k, arg in enumerate(re.findall(r'%(\w+)', m.group(4))):
            for c in consts.get(arg, ()):
                if abs(c) > FP16_MAX or 0.0 < abs(c) < FP16_MIN_NORMAL or (op == 'OpFDiv' and k == 1 and abs(c) > 16384.0):
                    found.append('line %s: mediump %s with the constant %g' % (line, op[2:], c))
    return sorted(set(found), key=lambda s: int(s.split()[1].rstrip(':')) if s.split()[1].rstrip(':').isdigit() else 0)


def main(argv: list[str]) -> int:
    glslc = find_glslc(argv)
    if not glslc:
        print('glslc not found (PATH, $ANDROID_NDK_HOME, $ANDROID_HOME): shader fp16 check FAIL')
        return 1
    dis = str(Path(glslc).with_name(Path(glslc).name.replace('glslc', 'spirv-dis')))
    failures, unchecked, checked = [], [], 0
    with tempfile.TemporaryDirectory() as tmp:
        for i, (label, stage, text) in enumerate(units()):
            src =os.path.join(tmp, '%03d.%s' % (i, stage))
            Path(src).write_text(text, encoding='utf-8')
            spv = src + '.spv'
            r = subprocess.run([glslc, '--target-env=opengl', '-std=310es', '-fshader-stage=' + stage, '-fauto-map-locations',
                                '-fauto-bind-uniforms', '-g', '-o', spv, src], capture_output=True, encoding='utf-8', errors='replace')
            if r.returncode != 0:
                unchecked.append('%s (%s)' % (label, (r.stderr.strip().splitlines() or ['?'])[0].split(': ', 1)[-1][:90]))
                continue
            checked += 1
            # -g embeds the shader source: spirv-dis may print bytes that are not UTF-8 (the opcodes read below are ASCII)
            asm = subprocess.run([dis, spv], capture_output=True, encoding='utf-8', errors='replace', check=True).stdout.splitlines()
            for h in hazards(asm):
                failures.append('%s %s' % (label, h))
    for u in unchecked:
        print('not checked (does not compile on its own): ' + u)
    for f in failures:
        print('FAIL ' + f)
    print('%d shaders checked: %s' % (checked, 'shader fp16 check FAIL (%d)' % len(failures) if failures else 'shader fp16 check PASS'))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
