#!/usr/bin/env python3
"""Summarize trace-opaque.js output: per opaque Process pointer, readable extent,
printable strings, and byte stability between the two observed Process calls.

Stability is a heuristic for "config-like" vs "per-shot": identical bytes in two
shots of possibly identical scenes do not prove a constant. Addresses are
process-local and are reported only to show whether the same object was reused.
"""
import argparse, json, re, sys
from pathlib import Path


def events(path):
    for line in Path(path).read_text(encoding='utf-8', errors='replace').splitlines():
        if line.startswith('SCAMERA_TCE '):
            try:
                yield json.loads(line[len('SCAMERA_TCE '):])
            except json.JSONDecodeError:
                pass


def strings(data, minimum=5):
    return [m.group().decode() for m in re.finditer(rb'[\x20-\x7e]{%d,}' % minimum, data)][:6]


def compare(a, b):
    n = min(len(a), len(b))
    same = sum(1 for i in range(n) if a[i] == b[i])
    return same, n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('trace')
    args = ap.parse_args()
    enters = [e for e in events(args.trace) if e['event'] == 'opaque_enter']
    status = [e for e in events(args.trace) if e['event'] in ('process_leave', 'finished', 'observer_error')]
    for e in status:
        print(e['event'], {k: e[k] for k in ('index', 'status', 'reason', 'error') if k in e})
    if not enters:
        sys.exit('no opaque_enter events')
    print(f'{len(enters)} Process call(s) with opaque dumps')
    for e in enters:
        print('lutPath:', e.get('lutPath'))
        x = e.get('extraOutput') or {}
        print('extraOutput:', x.get('address'), 'size', x.get('size'), x.get('error', ''))
    by_name = {}
    for e in enters:
        for b in e['blocks']:
            by_name.setdefault(b['name'], []).append(b)
    for name, blocks in by_name.items():
        first = blocks[0]
        print(f"\n[{name}] P+0x{first['offset']:x}")
        datas = []
        for i, b in enumerate(blocks):
            data = bytes.fromhex(b['hex']) if 'hex' in b else b''
            datas.append(data)
            kids = b.get('children', [])
            print(f"  call{i+1}: addr={b['address']} readable={b.get('size')} {b.get('error','')}"
                  f" children={len(kids)} strings={strings(data)}")
            if data:
                print('    head:', data[:64].hex())
        if len(datas) == 2 and datas[0] and datas[1]:
            same, n = compare(datas[0], datas[1])
            print(f'  stability: {same}/{n} bytes identical; same address: '
                  f"{blocks[0]['address'] == blocks[1]['address']}")


if __name__ == '__main__':
    main()
