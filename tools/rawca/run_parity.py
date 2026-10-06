"""P28 parity run: builds tools/rawca/parity (RawTherapee's original CA_correct_RT + the worker's port) for the desktop with the
zig C++ toolchain of SCAMERA-PC/.venv, makes the synthetic cases (gen_synth.py), extracts real frames (NCH bursts, DNGs) and runs
every case; for the synthetic cases it also measures the R / B error against the CA-free truth before and after the port.

run_parity.py <work dir> [--nch burst.nch ...] [--dng file.dng ...] [--dngpair frame0.dng frame1.dng ...] [--skip-synth]
Exit 0 when every case is bit-exact (output plane, work buffer, coefficients, green untouched).
"""
import json
import os
import subprocess
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(ROOT, 'tools', 'quad'))


def zig():
    for c in (os.path.join(ROOT, '..', '.venv', 'Lib', 'site-packages', 'ziglang', 'zig.exe'),
              os.path.join(ROOT, '.venv', 'Lib', 'site-packages', 'ziglang', 'zig.exe')):
        if os.path.exists(c):
            return [c, 'c++']
    return ['c++']


def build(out):
    exe = os.path.join(out, 'parity.exe' if os.name == 'nt' else 'parity')
    cmd = zig() + ['-std=c++17', '-O2', '-ffp-contract=off', '-I' + os.path.join(HERE, 'rt', 'stub'), '-I' + HERE,
                   os.path.join(HERE, 'parity.cpp'), os.path.join(HERE, 'rt_ref_scalar.cpp'), os.path.join(HERE, 'rt_ref_sse.cpp'), '-o', exe]
    subprocess.run(cmd, check=True)
    return exe


def read_r16(path):
    with open(path, 'rb') as f:
        assert f.read(4) == b'R16C'
        w, h, cfa = np.frombuffer(f.read(12), '<i4')
        black = np.frombuffer(f.read(16), '<f4')
        white = np.frombuffer(f.read(4), '<f4')[0]
        raw = np.frombuffer(f.read(), '<u2').reshape(h, w)
    return dict(w=int(w), h=int(h), cfa=int(cfa), black=black, white=float(white), raw=raw)


def write_r16(path, raw, cfa, black, white):
    h, w = raw.shape
    with open(path, 'wb') as f:
        f.write(b'R16C')
        f.write(np.array([w, h, cfa], '<i4').tobytes())
        f.write(np.array(list(black) + [white], '<f4').tobytes())
        f.write(np.ascontiguousarray(raw, dtype='<u2').tobytes())


def from_nch(path, out, frames=(0, 1)):
    import nch
    b = nch.read(path)
    names = []
    for i in frames:
        p = os.path.join(out, '%s_f%d.r16' % (os.path.splitext(os.path.basename(path))[0], i))
        if not os.path.exists(p):
            write_r16(p, np.asarray(b['planes'][i]), b['cfa'], b['black'], b['white'])
        names.append(p)
    return names


def from_dng(path, out):
    import rawpy
    p = os.path.join(out, os.path.splitext(os.path.basename(path))[0] + '.r16')
    if os.path.exists(p):
        return p
    with rawpy.imread(path) as r:
        raw = np.array(r.raw_image_visible, dtype=np.uint16)
        pat = np.array(r.raw_pattern)
        desc = r.color_desc.decode()
        blc = list(r.black_level_per_channel)
        white = float(r.white_level)
    h, w = raw.shape
    raw = raw[:h // 2 * 2, :w // 2 * 2]
    red = [(y, x) for y in range(2) for x in range(2) if desc[pat[y][x]] == 'R']
    if len(red) != 1 or len(desc) != 4:
        raise ValueError('not a Bayer RGBG DNG: %s %s' % (desc, pat.tolist()))
    cfa = (red[0][0] << 1) | red[0][1]
    black = [float(blc[pat[py][px]]) for py in range(2) for px in range(2)]
    write_r16(p, raw, cfa, black, white)
    return p


def run(exe, path, extra):
    r = subprocess.run([exe, path] + extra, capture_output=True, text=True)
    lines = [json.loads(l) for l in r.stdout.splitlines() if l.startswith('{')]
    if r.returncode not in (0, 1):
        print(r.stdout, r.stderr)
        raise RuntimeError('parity failed on ' + path)
    return lines, r.returncode == 0


def rb_error(case_dir, name, corrected):
    """RMS error of the R / B sites against the CA-free truth (codes), whole frame and outer ring (r > 0.6 of the half diagonal)."""
    src = read_r16(os.path.join(case_dir, name + '.r16'))
    truth = read_r16(os.path.join(case_dir, name + '.truth.r16'))
    cor = read_r16(corrected)
    h, w, cfa = src['h'], src['w'], src['cfa']
    y, x = np.mgrid[0:h, 0:w]
    r = np.hypot(x - (w - 1) / 2, y - (h - 1) / 2) / np.hypot((w - 1) / 2, (h - 1) / 2)
    phase = ((y & 1) << 1) | (x & 1)
    out = {}
    for col, ph in (('R', cfa), ('B', cfa ^ 3)):
        m = phase == ph
        for ring, sel in (('all', m), ('outer', m & (r > 0.6))):
            t = truth['raw'][sel].astype(np.float64)
            before = np.sqrt(np.mean((src['raw'][sel] - t) ** 2))
            after = np.sqrt(np.mean((cor['raw'][sel] - t) ** 2))
            out['%s_%s' % (col, ring)] = (round(float(before), 2), round(float(after), 2))
    # green must be untouched
    g = (phase != cfa) & (phase != (cfa ^ 3))
    out['green_changed'] = int(np.count_nonzero(src['raw'][g] != cor['raw'][g]))
    return out


def main():
    args = sys.argv[1:]
    work = args[0] if args else os.path.join(HERE, 'out')
    os.makedirs(work, exist_ok=True)
    nchs, dngs = [], []
    i = 1
    while i < len(args):
        if args[i] == '--nch':
            nchs.append(args[i + 1]); i += 2
        elif args[i] == '--dng':
            dngs.append((args[i + 1], None)); i += 2
        elif args[i] == '--dngpair':  # two frames of one burst: the second gets the first one's fit (fitParamsIn)
            dngs.append((args[i + 1], args[i + 2])); i += 3
        else:
            i += 1
    exe = build(work)
    synth = os.path.join(work, 'synth')
    if '--skip-synth' not in args and not os.path.exists(os.path.join(synth, 'syn_none.r16')):
        subprocess.run([sys.executable, os.path.join(HERE, 'gen_synth.py'), synth], check=True)
    cases = []
    if os.path.exists(synth):
        for n in ('syn12', 'syn_bggr', 'syn_grbg_edge', 'syn_gbrg_big', 'syn_small', 'syn_tiny', 'syn_none'):
            extra = ['--sse', '--iters', '2']
            if n == 'syn12':
                extra += ['--in2', os.path.join(synth, 'syn12b.r16'), '--time']
            extra += ['--out', os.path.join(work, n + '.corrected.r16')]
            cases.append((n, os.path.join(synth, n + '.r16'), extra, True))
    for p in nchs:
        f0, f1 = from_nch(p, work)
        cases.append((os.path.basename(f0), f0, ['--sse', '--iters', '2', '--in2', f1, '--time', '--out', os.path.join(work, os.path.basename(f0) + '.corrected.r16')], False))
    for p, p2 in dngs:
        try:
            r = from_dng(p, work)
            r2 = from_dng(p2, work) if p2 else None
        except Exception as e:  # not a Bayer DNG (e.g. linear)
            print('skip', p, e)
            continue
        cases.append((os.path.basename(r), r, ['--sse', '--iters', '2'] + (['--in2', r2] if r2 else []), False))
    results, ok = {}, True
    for name, path, extra, synthetic in cases:
        lines, exact = run(exe, path, extra)
        ok = ok and exact
        entry = {'exact': exact, 'checks': lines}
        if synthetic:
            entry['rb_rms_before_after'] = rb_error(synth, name, os.path.join(work, name + '.corrected.r16'))
        results[name] = entry
        print('==', name, 'EXACT' if exact else 'DIFFERS')
        for l in lines:
            print('  ', json.dumps(l))
        if synthetic:
            print('   R/B rms vs truth (before, after):', json.dumps(entry['rb_rms_before_after']))
    with open(os.path.join(work, 'parity_results.json'), 'w') as f:
        json.dump(results, f, indent=1)
    print('ALL EXACT' if ok else 'SOME CASES DIFFER')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
