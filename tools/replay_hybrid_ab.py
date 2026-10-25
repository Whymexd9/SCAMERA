#!/usr/bin/env python3
"""A/B replay of the SHDR merge on saved bursts (host worker, Mesa): every burst x every tuning variant.

Usage: replay_hybrid_ab.py <worker> <bursts dir> <out dir> [variants.txt]
- bursts: *.nch (NCH v12, e.g. a crop of a scam_burst-*.bin) or *.nch.xz (tools/replay_crop_nch.py pack: the frames' 16-bit
  words as low / high byte planes, xz);
- variants.txt: blocks "[name]" (optionally "[name day]": bursts whose file name starts with d, "[name night]": n, e or m),
  followed by tuning lines that go after the base tuning; the first block is the reference. Without the file: base only.
- the base tuning is tools/replay_tuning.txt (the app's hybrid_tuning.txt of a shot, "key value" per line).
Writes <out>/<burst>__<variant>.npz (merged camera RGB, float16, rows top-down) and .log (the worker's report), and summary.txt.
The worker runs as an offline replay: SCAM_HYBRID=1, SCAM_NO_EFFMAP=1, job marker cre-off (SCAMERA tile alignment, no vendor CRE).
"""
import lzma
import os
import struct
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]


def read_burst(path):
    data = path.read_bytes()
    if path.suffix == '.xz':
        d = lzma.decompress(data)
        n = struct.unpack('<I', d[20:24])[0]
        first = (128 + 32 * n + 4095) // 4096
        planes = np.frombuffer(d[first * 4096:], np.uint8)
        half = planes.size // 2
        body = planes[:half].astype(np.uint16) | (planes[half:].astype(np.uint16) << 8)
        data = d[:first * 4096] + body.astype('<u2').tobytes()
    w, h = struct.unpack('<2I', data[8:16])
    return data, w, h


def variants(path):
    out, cur = [], None
    for line in (path.read_text().splitlines() if path else []):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('['):
            parts = line.strip('[]').split()
            cur = {'name': parts[0], 'scope': parts[1] if len(parts) > 1 else '', 'lines': []}
            out.append(cur)
        elif cur is not None:
            cur['lines'].append(line)
    return out or [{'name': 'base', 'scope': '', 'lines': []}]


def main():
    worker, bursts, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    vs = variants(Path(sys.argv[4]) if len(sys.argv) > 4 else None)
    base = (ROOT / 'tools/replay_tuning.txt').read_text()
    out.mkdir(parents=True, exist_ok=True)
    summary = []
    for bpath in sorted(list(bursts.glob('*.nch')) + list(bursts.glob('*.nch.xz'))):
        name = bpath.name.split('.')[0]
        data, w, h = read_burst(bpath)
        raw = out / (name + '.nch')
        raw.write_bytes(data)
        for v in vs:
            if v['scope'] == 'day' and not name.startswith('d') or v['scope'] == 'night' and name[0] not in 'nem':
                continue
            job = out / ('job_' + name + '_' + v['name'])
            job.mkdir(exist_ok=True)
            (job / 'hybrid_tuning.txt').write_text(base.rstrip('\n') + '\n' + '\n'.join(v['lines']) + '\n')
            (job / 'cre-off').write_text('1\n')
            res = out / (name + '__' + v['name'] + '.f32')
            env = dict(os.environ, SCAM_HYBRID='1', SCAM_NO_EFFMAP='1')
            t0 = time.time()
            p = subprocess.run([str(worker), '--scam-capture', str(job), str(raw), str(res)], env=env, capture_output=True,
                               text=True, errors='replace', timeout=900)
            dt = time.time() - t0
            (out / (name + '__' + v['name'] + '.log')).write_text(p.stdout + '\n--- stderr\n' + p.stderr)
            ok = p.returncode == 0 and res.exists() and res.stat().st_size >= w * h * 12
            if ok:
                rgb = np.fromfile(res, np.float32, w * h * 3).reshape(h, w, 3)
                np.savez_compressed(out / (name + '__' + v['name'] + '.npz'), rgb=rgb.astype(np.float16))
                res.unlink()
            summary.append('%s %s rc=%d %s %.1fs' % (name, v['name'], p.returncode, 'ok' if ok else 'FAILED', dt))
            print(summary[-1], flush=True)
        raw.unlink()
    (out / 'summary.txt').write_text('\n'.join(summary) + '\n')
    sys.exit(0 if all(' ok ' in s for s in summary) else 1)


if __name__ == '__main__':
    main()
