"""P28 frames mode: the GLSL compute passes of vivo-nice-rawca-gpu.h on desktop GL (moderngl, #version 430 instead of 310 es)
against the CPU port (correctFrameCpu, the worker's fallback) on the same frame, fits and factors (gpu_ref).

check_gpu.py <work dir> <base.r16> <frame.r16> [gpu_ref options: --passes N | --manual R B | --no-avoid]
Builds gpu_ref with the zig toolchain, runs it, then the four programs (init, green, correct x passes, final) exactly in the
worker's order and dispatch layout, and compares the R / B codes. GPU float division is not the CPU's (NVIDIA: ~2 ulp), so a
guard of the correction (|G - C| lowered or not, the 25 % branch, the overshoot sign) can flip at isolated sites. Exit 0 when green
is untouched, at most 0.1 % of the R / B sites differ, 99.99 % are within 1 code and none by more than 32 codes.
"""
import json
import os
import re
import subprocess
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
HEADER = os.path.join(ROOT, 'app', 'src', 'main', 'cpp', 'vivo-nice-rawca-gpu.h')
sys.path.insert(0, HERE)
from run_parity import zig  # noqa: E402


def glsl(src, name):
    m = re.search(r'static const char\* %s = R"\((.*?)\)";' % name, src, re.S)
    if not m:
        raise KeyError(name)
    return m.group(1)


def main():
    work, base, frame = sys.argv[1:4]
    opts = sys.argv[4:]
    os.makedirs(work, exist_ok=True)
    exe = os.path.join(work, 'gpu_ref.exe' if os.name == 'nt' else 'gpu_ref')
    subprocess.run(zig() + ['-std=c++17', '-O2', '-ffp-contract=off', os.path.join(HERE, 'gpu_ref.cpp'), '-o', exe], check=True)
    out = os.path.join(work, 'gpu_case')
    os.makedirs(out, exist_ok=True)
    r = subprocess.run([exe, base, frame, out] + opts, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout, r.stderr)
        return 2
    meta = json.loads(r.stdout.strip().splitlines()[-1])
    w, h = meta['w'], meta['h']
    tiles = np.fromfile(os.path.join(out, 'tiles.f32'), np.float32)
    fac = np.fromfile(os.path.join(out, 'factors.f32'), np.float32)
    codes = np.fromfile(os.path.join(out, 'frame.u16'), np.uint16)
    cpu = np.fromfile(os.path.join(out, 'cpu.u16'), np.uint16).reshape(h, w)

    import moderngl
    src = open(HEADER, encoding='utf-8').read()
    common = glsl(src, 'kRawCaGlslCommon')
    ctx = moderngl.create_standalone_context(require=430)
    progs = {n: ctx.compute_shader('#version 430\n' + common + glsl(src, n)) for n in
             ('kRawCaGlslInit', 'kRawCaGlslGreen', 'kRawCaGlslCorrect', 'kRawCaGlslFinal')}
    sites = w * h // 2
    bufs = [ctx.buffer(codes.tobytes()), ctx.buffer(reserve=sites * 4), ctx.buffer(reserve=sites * 4), ctx.buffer(reserve=sites * 4),
            ctx.buffer(tiles.tobytes()), ctx.buffer(fac.tobytes())]
    black, scale, white, clip = meta['black'], meta['scale'], meta['white'], meta['clip_level']
    clipc = [b + clip * (white - b) for b in black]
    passes = meta['passes']

    def bind(a, b):
        for slot, k in ((0, 0), (1, a), (2, b), (3, 3), (4, 4), (5, 5)):
            bufs[k].bind_to_storage_buffer(slot)

    def run(name, p):
        pr = progs[name]
        vals = {'size': (w, h), 'redPhase': meta['cfa'], 'black': tuple(black), 'scale': tuple(scale), 'clipCode': tuple(clipc),
                'blocks': (meta['hblsz'], meta['vblsz'], meta['fW'], meta['fH']),
                'base': (p * meta['vblsz'] * meta['hblsz'], p * 2 * meta['fW'] * meta['fH']), 'useFactors': int(meta['factors'])}
        for k, v in vals.items():
            if k in pr:
                pr[k].value = v
        for r0 in range(0, h, 512):
            if 'rowBase' in pr:
                pr['rowBase'].value = r0
            pr.run((w // 2 + 7) // 8, (min(512, h - r0) + 7) // 8, 1)
        ctx.memory_barrier()

    def frame():
        bufs[0].write(codes.tobytes())  # upload, passes, readback: what the worker does per frame
        a, b = 1, 2
        bind(a, b)
        run('kRawCaGlslInit', 0)
        for p in range(passes):
            bind(a, b)
            run('kRawCaGlslGreen', p)
            run('kRawCaGlslCorrect', p)
            a, b = b, a
        bind(a, b)
        run('kRawCaGlslFinal', 0)
        return np.frombuffer(bufs[0].read(), np.uint16).reshape(h, w)

    gpu = frame()  # first run: driver compiles / allocates
    times = []
    for _ in range(5):
        t0 = time.perf_counter()
        frame()
        ctx.finish()
        times.append((time.perf_counter() - t0) * 1000)
    gpu_ms = min(times)

    inp = codes.reshape(h, w)
    y, x = np.mgrid[0:h, 0:w]
    phase = ((y & 1) << 1) | (x & 1)
    rb = (phase == meta['cfa']) | (phase == (meta['cfa'] ^ 3))
    d = np.abs(gpu.astype(np.int32) - cpu.astype(np.int32))
    n_rb = int(rb.sum())
    diff = d[rb]
    green_gpu = int(np.count_nonzero(gpu[~rb] != inp[~rb]))
    changed_gpu = int(np.count_nonzero(gpu[rb] != inp[rb]))
    changed_cpu = int(np.count_nonzero(cpu[rb] != inp[rb]))
    res = {
        'w': w, 'h': h, 'passes': passes, 'factors': meta['factors'], 'summary': meta['summary'],
        'rb_sites': n_rb, 'changed_cpu': changed_cpu, 'changed_gpu': changed_gpu, 'green_changed_gpu': green_gpu,
        'differ': int(np.count_nonzero(diff)), 'differ_share': float(np.count_nonzero(diff)) / n_rb,
        'differ_gt1': int(np.count_nonzero(diff > 1)),
        'max_abs': int(diff.max()), 'p99_99_abs': float(np.percentile(diff, 99.99)),
        'cpu_ms': meta['cpu_ms'], 'gpu_ms_desktop': round(gpu_ms, 1), 'renderer': ctx.info['GL_RENDERER'],
    }
    print(json.dumps(res))
    ok = green_gpu == 0 and res['differ_share'] <= 1e-3 and res['p99_99_abs'] <= 1 and res['max_abs'] <= 32
    print('GPU CHECK', 'PASS' if ok else 'FAIL')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
