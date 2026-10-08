"""Host check of the AVIF photo output: the app's encoder core (app/src/main/cpp/scamera-avif-core.h) with the same
pinned libavif / libaom (app/src/main/cpp/scamera-avif-deps.cmake), built for the host by tools/avif_host.

A synthetic image (10-bit gradients, a colour chart with saturated edges, fine detail) is encoded at 8 / 10 / 12 bit,
4:4:4 and 4:2:0, lossless, from RGBA_8888 and RGBA_1010102 pixels, with an EXIF block. Every file is then read back
independently: the ISOBMFF boxes are parsed here (brand, ispe size, pixi depth, av1C profile / depth / subsampling,
colr nclx, the Exif item) and the pixels are decoded by Pillow's AVIF plugin (libavif + dav1d, not libaom), which gives
PSNR against the source, exactness of the lossless files and the EXIF tags.

    python3 tools/check_avif.py                 # build (or reuse) the host encoder and run the checks
    python3 tools/check_avif.py --bench a.jpg   # also time 12 MP and 50 MP encodes (photo mosaics) at the defaults

Needs: CMake 3.18+, a C / C++ compiler (on Windows `zig cc` from the ziglang package is used when CC is unset), Perl,
nasm on x86 hosts, Pillow >= 11.3 with AVIF and numpy.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image, features

ROOT = Path(__file__).resolve().parents[1]
HOST_SOURCE = ROOT / 'tools' / 'avif_host'
EXE_NAME = 'avif_host_encode.exe' if os.name == 'nt' else 'avif_host_encode'
# The app's defaults (PhotoFormat / AvifEncoder): quality 90, 10-bit, 4:4:4, speed 6.
DEFAULTS = dict(quality=90, depth=10, yuv='444', speed=6)
MAKE = 'SCAMERA'
MODEL = 'AVIF host check'

failures: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        failures.append(message)
        print('FAIL: ' + message)


# ----------------------------------------------------------------------------------------------------------- build

def find_zig() -> str | None:
    zig = shutil.which('zig')
    if zig:
        return zig
    try:
        import ziglang  # type: ignore
        candidate = Path(ziglang.__file__).parent / ('zig.exe' if os.name == 'nt' else 'zig')
        if candidate.exists():
            return str(candidate)
    except ImportError:
        pass
    # A ziglang installed in another Python next to a cmake / ninja on PATH (e.g. a project venv).
    for tool in ('python-zig', 'cmake'):
        found = shutil.which(tool)
        if found:
            for site in Path(found).resolve().parent.parent.glob('Lib/site-packages/ziglang/zig.exe'):
                return str(site)
    return None


def find_llvm_ar() -> tuple[str, str] | None:
    """llvm-ar / llvm-ranlib of an Android NDK (they archive any object format)."""
    for env in ('ANDROID_NDK_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT'):
        base = os.environ.get(env)
        if not base:
            continue
        for ar in sorted(Path(base).glob('**/toolchains/llvm/prebuilt/*/bin/llvm-ar*'), reverse=True):
            ranlib = ar.with_name(ar.name.replace('llvm-ar', 'llvm-ranlib'))
            if ranlib.exists():
                return str(ar), str(ranlib)
    return None


def build(build_dir: Path, nasm: str | None) -> Path:
    cmake = shutil.which('cmake')
    if not cmake:
        sys.exit('cmake not found')
    env = dict(os.environ)
    args = [cmake, '-S', str(HOST_SOURCE), '-B', str(build_dir), '-DCMAKE_BUILD_TYPE=Release']
    ninja = shutil.which('ninja')
    if ninja:
        args += ['-G', 'Ninja', '-DCMAKE_MAKE_PROGRAM=' + Path(ninja).as_posix()]
    if os.name == 'nt' and 'CC' not in env:
        zig = find_zig()
        if not zig:
            sys.exit('no host C compiler: set CC / CXX or install the ziglang package')
        zig = Path(zig).as_posix()  # CMake splits CC at spaces and reads backslashes as escapes
        if ' ' in zig:
            sys.exit('the zig path must not contain spaces: ' + zig)
        env['CC'] = zig + ' cc'
        env['CXX'] = zig + ' c++'
        tools = find_llvm_ar()
        if tools:
            args += ['-DCMAKE_AR=' + Path(tools[0]).as_posix(), '-DCMAKE_RANLIB=' + Path(tools[1]).as_posix()]
        else:
            build_dir.mkdir(parents=True, exist_ok=True)
            for name in ('ar', 'ranlib'):
                (build_dir / f'zig-{name}.cmd').write_text(f'@"{zig}" {name} %*\n')
            args += ['-DCMAKE_AR=' + (build_dir / 'zig-ar.cmd').as_posix(), '-DCMAKE_RANLIB=' + (build_dir / 'zig-ranlib.cmd').as_posix()]
    nasm = nasm or os.environ.get('NASM') or shutil.which('nasm')
    if nasm:
        args.append('-DCMAKE_ASM_NASM_COMPILER=' + Path(nasm).resolve().as_posix())
    subprocess.run(args, check=True, env=env)
    subprocess.run([cmake, '--build', str(build_dir), '--target', 'avif_host_encode', '--parallel'], check=True, env=env)
    exe = build_dir / EXE_NAME
    if not exe.exists():
        sys.exit(f'{exe} was not built')
    return exe


# ----------------------------------------------------------------------------------------------------------- images

def synthetic(width: int, height: int) -> np.ndarray:
    """A 10-bit RGB test chart (uint16, 0..1023): ramps, colour chart patches with hard saturated edges, fine detail."""
    img = np.zeros((height, width, 3), np.float64)
    x = np.linspace(0.0, 1.0, width)[None, :]
    band = height // 3
    rows = [slice(i * band // 4, (i + 1) * band // 4) for i in range(4)]
    img[rows[0], :, 0] = x
    img[rows[1], :, 1] = x
    img[rows[2], :, 2] = x
    img[rows[3], :, :] = x[..., None]
    # Colour chart (Macbeth-like sRGB values) on a grey surround, then saturated primaries next to each other.
    chart = [(115, 82, 68), (194, 150, 130), (98, 122, 157), (87, 108, 67), (133, 128, 177), (103, 189, 170),
             (214, 126, 44), (80, 91, 166), (193, 90, 99), (94, 60, 108), (157, 188, 64), (224, 163, 46),
             (56, 61, 150), (70, 148, 73), (175, 54, 60), (231, 199, 31), (187, 86, 149), (8, 133, 161),
             (243, 243, 242), (200, 200, 200), (160, 160, 160), (122, 122, 121), (85, 85, 85), (52, 52, 52)]
    top, bottom = band, 2 * band
    img[top:bottom] = 0.5
    cols, rws = 8, 3
    pw, ph = width // (cols + 1), (bottom - top) // (rws + 1)
    for i, c in enumerate(chart):
        cx, cy = i % cols, i // cols
        y0 = top + ph // 2 + cy * ph
        x0 = pw // 2 + cx * pw
        img[y0 + 2:y0 + ph - 2, x0 + 2:x0 + pw - 2] = np.array(c) / 255.0
    # Fine detail: one-pixel checkerboard, a zone plate and red / blue / green stripes.
    yy, xx = np.mgrid[bottom:height, 0:width]
    third = width // 3
    checker = ((xx + yy) % 2).astype(np.float64)
    img[bottom:, :third] = checker[:, :third, None] * 0.8 + 0.1
    r = np.hypot(xx - width / 2, yy - (bottom + height) / 2)
    zone = 0.5 + 0.45 * np.cos(r * r / (width / 6))
    img[bottom:, third:2 * third] = zone[:, third:2 * third, None]
    stripes = (xx // 3) % 3
    sub = img[bottom:, 2 * third:]
    sub[...] = 0
    for k in range(3):
        sub[..., k][stripes[:, 2 * third:] == k] = 1.0
    return np.clip(np.round(img * 1023.0), 0, 1023).astype(np.uint16)


def to8(rgb10: np.ndarray) -> np.ndarray:
    """10-bit to 8-bit with rounding: libavif's depth rescaling, so a lossless 10-bit file decodes to exactly this."""
    return np.round(rgb10.astype(np.float64) * 255.0 / 1023.0).astype(np.uint8)


def pack8888(rgb8: np.ndarray) -> bytes:
    h, w, _ = rgb8.shape
    return np.concatenate([rgb8, np.full((h, w, 1), 255, np.uint8)], axis=2).tobytes()


def pack1010102(rgb10: np.ndarray) -> bytes:
    """Android RGBA_1010102: little-endian word, R bits 0-9, G 10-19, B 20-29, alpha 30-31."""
    r, g, b = (rgb10[..., i].astype(np.uint32) for i in range(3))
    return (r | g << 10 | b << 20 | np.uint32(3) << 30).astype('<u4').tobytes()


def exif_block() -> bytes:
    """"Exif\\0\\0" + a little-endian TIFF with Make, Model and Software: the layout ExifBlock.exifDataBlock gives."""
    entries = [(0x010F, MAKE), (0x0110, MODEL), (0x0131, 'check_avif.py')]
    count = len(entries)
    data_offset = 8 + 2 + count * 12 + 4
    ifd, data = b'', b''
    for tag, text in entries:
        value = text.encode('ascii') + b'\0'
        ifd += struct.pack('<HHI', tag, 2, len(value))
        if len(value) <= 4:
            ifd += value.ljust(4, b'\0')
        else:
            ifd += struct.pack('<I', data_offset + len(data))
            data += value + (b'\0' if len(value) % 2 else b'')
    tiff = b'II*\0' + struct.pack('<I', 8) + struct.pack('<H', count) + ifd + struct.pack('<I', 0) + data
    return b'Exif\0\0' + tiff


# ----------------------------------------------------------------------------------------------------------- AVIF boxes

def boxes(data: bytes, start: int = 0, end: int | None = None):
    end = len(data) if end is None else end
    pos = start
    while pos + 8 <= end:
        size, kind = struct.unpack('>I4s', data[pos:pos + 8])
        header = 8
        if size == 1:
            size = struct.unpack('>Q', data[pos + 8:pos + 16])[0]
            header = 16
        elif size == 0:
            size = end - pos
        if size < header or pos + size > end:
            raise ValueError(f'bad box {kind!r} at {pos}')
        yield kind.decode('latin-1'), pos + header, pos + size
        pos += size


def child(data: bytes, start: int, end: int, kind: str):
    for k, s, e in boxes(data, start, end):
        if k == kind:
            return s, e
    return None


def parse_avif(data: bytes) -> dict:
    """The properties of the primary item and the Exif payload, read straight from the boxes."""
    out: dict = {}
    top = {k: (s, e) for k, s, e in boxes(data)}
    s, e = top['ftyp']
    out['major_brand'] = data[s:s + 4].decode('latin-1')
    out['brands'] = [data[i:i + 4].decode('latin-1') for i in range(s + 8, e, 4)]
    ms, me = top['meta']
    ms += 4  # FullBox
    ps, pe = child(data, ms, me, 'pitm')
    out['primary'] = struct.unpack('>H', data[ps + 4:ps + 6])[0] if data[ps] == 0 else struct.unpack('>I', data[ps + 4:ps + 8])[0]
    # iinf: item id -> type
    items = {}
    is_, ie = child(data, ms, me, 'iinf')
    version = data[is_]
    pos = is_ + 4 + (2 if version == 0 else 4)
    for k, s2, e2 in boxes(data, pos, ie):
        if k != 'infe':
            continue
        v = data[s2]
        if v >= 2:
            item_id = struct.unpack('>H' if v == 2 else '>I', data[s2 + 4:s2 + (6 if v == 2 else 8)])[0]
            at = s2 + (6 if v == 2 else 8) + 2
            items[item_id] = data[at:at + 4].decode('latin-1')
    out['items'] = items
    # iloc: item id -> (offset, length) of its single extent
    ls, le = child(data, ms, me, 'iloc')
    v = data[ls]
    pos = ls + 4
    sizes = data[pos], data[pos + 1]
    offset_size, length_size = sizes[0] >> 4, sizes[0] & 15
    base_size, index_size = sizes[1] >> 4, (sizes[1] & 15) if v in (1, 2) else 0
    pos += 2

    def read(n: int) -> int:
        nonlocal pos
        value = int.from_bytes(data[pos:pos + n], 'big') if n else 0
        pos += n
        return value

    count = read(2 if v < 2 else 4)
    locations = {}
    for _ in range(count):
        item_id = read(2 if v < 2 else 4)
        if v in (1, 2):
            read(2)
        read(2)
        base = read(base_size)
        extents = read(2)
        spans = []
        for _ in range(extents):
            read(index_size)
            spans.append((base + read(offset_size), read(length_size)))
        locations[item_id] = spans
    out['locations'] = locations
    # iprp: properties of the primary item
    rs, re_ = child(data, ms, me, 'iprp')
    cs, ce = child(data, rs, re_, 'ipco')
    props = list(boxes(data, cs, ce))
    as_, ae = child(data, rs, re_, 'ipma')
    v, flags = data[as_], int.from_bytes(data[as_ + 1:as_ + 4], 'big')
    pos = as_ + 4
    entries = read(4)
    assoc = {}
    for _ in range(entries):
        item_id = read(2 if v < 1 else 4)
        n = read(1)
        indices = []
        for _ in range(n):
            raw = read(2 if flags & 1 else 1)
            indices.append(raw & (0x7FFF if flags & 1 else 0x7F))
        assoc[item_id] = indices
    for index in assoc.get(out['primary'], []):
        kind, s2, e2 = props[index - 1]
        body = data[s2:e2]
        if kind == 'ispe':
            out['ispe'] = struct.unpack('>II', body[4:12])
        elif kind == 'pixi':
            out['pixi'] = list(body[5:5 + body[4]])
        elif kind == 'av1C':
            b1, b2 = body[1], body[2]
            out['av1C'] = dict(profile=b1 >> 5, high_bitdepth=b2 >> 6 & 1, twelve_bit=b2 >> 5 & 1, monochrome=b2 >> 4 & 1,
                               subsampling=(b2 >> 3 & 1, b2 >> 2 & 1))
        elif kind == 'colr' and body[:4] == b'nclx':
            p, t, m = struct.unpack('>HHH', body[4:10])
            out['nclx'] = dict(primaries=p, transfer=t, matrix=m, full_range=body[10] >> 7)
    exif_ids = [i for i, t in items.items() if t == 'Exif']
    if exif_ids:
        offset, length = locations[exif_ids[0]][0]
        out['exif_payload'] = data[offset:offset + length]
    return out


def av1_depth(av1c: dict) -> int:
    if not av1c['high_bitdepth']:
        return 8
    return 12 if av1c['twelve_bit'] else 10


def psnr(a: np.ndarray, b: np.ndarray) -> float:
    mse = float(((a.astype(np.float64) - b.astype(np.float64)) ** 2).mean())
    return float('inf') if mse == 0 else 10.0 * np.log10(255.0 ** 2 / mse)


# ----------------------------------------------------------------------------------------------------------- checks

def encode(exe: Path, raw: Path, width: int, height: int, layout: str, out: Path, exif: Path | None = None, **options) -> dict:
    args = [str(exe), f'in={raw}', f'width={width}', f'height={height}', f'layout={layout}', f'out={out}']
    args += [f'{k}={v}' for k, v in options.items()]
    if exif:
        args.append(f'exif={exif}')
    run = subprocess.run(args, capture_output=True, text=True)
    line = run.stdout.strip().splitlines()[-1] if run.stdout.strip() else '{}'
    result = json.loads(line)
    if run.returncode != 0 or not result.get('ok'):
        raise RuntimeError(f'encode failed ({" ".join(args[5:])}): {result.get("error", run.stderr.strip())}')
    return result


# name, layout, options, expected (depth, yuv444, matrix), minimum PSNR in dB (None = exact). 4:2:0 cannot hold the
# chart's saturated edges and 3-pixel colour stripes (that is what 4:4:4 is for): its PSNR is taken on the grey detail.
CASES = [
    ('8-bit 4:4:4 q90', '8888', dict(quality=90, depth=8, yuv='444'), (8, True, 1), 45.0),
    ('10-bit 4:4:4 q90 (default)', '8888', dict(quality=90, depth=10, yuv='444'), (10, True, 1), 45.0),
    ('12-bit 4:4:4 q90', '8888', dict(quality=90, depth=12, yuv='444'), (12, True, 1), 45.0),
    ('10-bit 4:2:0 q90', '8888', dict(quality=90, depth=10, yuv='420'), (10, False, 1), 38.0),
    ('8-bit 4:2:0 q90', '8888', dict(quality=90, depth=8, yuv='420'), (8, False, 1), 38.0),
    ('10-bit 4:4:4 q90 from RGBA_1010102', '1010102', dict(quality=90, depth=10, yuv='444'), (10, True, 1), 45.0),
    ('12-bit 4:4:4 q90 from RGBA_1010102', '1010102', dict(quality=90, depth=12, yuv='444'), (12, True, 1), 45.0),
    ('8-bit 4:4:4 q90 from RGBA_1010102', '1010102', dict(quality=90, depth=8, yuv='444'), (8, True, 1), 45.0),
    ('10-bit 4:4:4 q60', '8888', dict(quality=60, depth=10, yuv='444'), (10, True, 1), 35.0),
    ('lossless from RGBA_8888 (8-bit)', '8888', dict(lossless=1, depth=12, yuv='420'), (8, True, 0), None),
    ('lossless from RGBA_1010102 (10-bit)', '1010102', dict(lossless=1, depth=8, yuv='420'), (10, True, 0), None),
]


def regions(width: int, height: int) -> dict:
    """The synthetic chart's parts: ramps + colour chart, grey detail (checkerboard, zone plate), colour stripes."""
    band, third = height // 3, width // 3
    return {'all': np.s_[:, :], 'gray': np.s_[2 * band:, :2 * third], 'stripes': np.s_[2 * band:, 2 * third:]}


def run_cases(exe: Path, work: Path, width: int, height: int) -> None:
    rgb10 = synthetic(width, height)
    rgb8 = to8(rgb10)
    raw = {'8888': work / f'src8888_{width}x{height}.raw', '1010102': work / f'src1010102_{width}x{height}.raw'}
    raw['8888'].write_bytes(pack8888(rgb8))
    raw['1010102'].write_bytes(pack1010102(rgb10))
    exif = work / 'exif.bin'
    exif.write_bytes(exif_block())
    sizes, stripes = {}, {}
    part = regions(width, height)
    for name, layout, options, (depth, yuv444, matrix), min_psnr in CASES:
        label = f'{width}x{height} {name}'
        out = work / (label.replace(' ', '_').replace(':', '').replace('(', '').replace(')', '') + '.avif')
        result = encode(exe, raw[layout], width, height, layout, out, exif, speed=6, threads=4, **options)
        data = out.read_bytes()
        box = parse_avif(data)
        check(box['major_brand'] == 'avif' and 'mif1' in box['brands'], f'{label}: brands {box["major_brand"]} {box["brands"]}')
        check(box['items'].get(box['primary']) == 'av01', f'{label}: primary item is {box["items"].get(box["primary"])}')
        check(box.get('ispe') == (width, height), f'{label}: ispe {box.get("ispe")}')
        check(box.get('pixi') == [depth] * 3, f'{label}: pixi {box.get("pixi")}, expected {depth}-bit x3')
        av1c = box.get('av1C', {})
        check(av1c and av1_depth(av1c) == depth, f'{label}: av1C depth {av1c}')
        check(av1c and av1c['subsampling'] == ((0, 0) if yuv444 else (1, 1)), f'{label}: av1C subsampling {av1c}')
        check(av1c and av1c['monochrome'] == 0, f'{label}: monochrome')
        # AV1 profiles: Main = 8/10-bit 4:2:0, High = 8/10-bit 4:4:4, Professional = 12-bit.
        check(av1c and av1c['profile'] == (2 if depth == 12 else 1 if yuv444 else 0), f'{label}: AV1 profile {av1c}')
        nclx = box.get('nclx')
        check(nclx == dict(primaries=1, transfer=13, matrix=matrix, full_range=1), f'{label}: nclx {nclx}')
        check(result['depth'] == depth and result['yuv444'] == yuv444 and result['exif'], f'{label}: encoder stats {result}')
        payload = box.get('exif_payload', b'')
        check(payload[:4] == struct.pack('>I', 6) and payload[4:10] == b'Exif\0\0' and payload[10:14] == b'II*\0',
              f'{label}: Exif item payload {payload[:14]!r}')
        with Image.open(out) as im:
            check(im.size == (width, height), f'{label}: decoded size {im.size}')
            exif_tags = im.getexif()
            check(exif_tags.get(0x010F) == MAKE and exif_tags.get(0x0110) == MODEL, f'{label}: EXIF tags {dict(exif_tags)}')
            decoded = np.asarray(im.convert('RGB'))
        reference = rgb8  # both sources decode to the same 8-bit picture
        if min_psnr is None:
            diff = int(np.abs(decoded.astype(int) - reference.astype(int)).max())
            check(diff == 0, f'{label}: lossless file differs from the source by up to {diff}')
            value = float('inf')
        else:
            region = part['all' if yuv444 else 'gray']
            value = psnr(decoded[region], reference[region])
            check(value >= min_psnr, f'{label}: PSNR {value:.2f} dB < {min_psnr} dB')
        stripes[name] = psnr(decoded[part['stripes']], reference[part['stripes']])
        sizes[name] = len(data)
        where = 'all' if yuv444 else 'grey detail'
        print(f'  {label:52s} {len(data):8d} B  {depth:2d}-bit {"4:4:4" if yuv444 else "4:2:0"}  PSNR {value:6.2f} dB ({where})'
              f'  {result["encode_ms"]:.0f} ms')
    check(sizes['lossless from RGBA_8888 (8-bit)'] > sizes['8-bit 4:4:4 q90'], 'lossless is not larger than q90')
    check(sizes['10-bit 4:4:4 q60'] < sizes['10-bit 4:4:4 q90 (default)'], 'q60 is not smaller than q90')
    # Full-resolution chroma keeps the 3-pixel red / green / blue stripes that 4:2:0 smears.
    gain = stripes['10-bit 4:4:4 q90 (default)'] - stripes['10-bit 4:2:0 q90']
    check(gain >= 20.0, f'{width}x{height}: 4:4:4 is only {gain:.1f} dB better than 4:2:0 on colour stripes')
    print(f'  {width}x{height} colour stripes: 4:4:4 {stripes["10-bit 4:4:4 q90 (default)"]:.1f} dB, '
          f'4:2:0 {stripes["10-bit 4:2:0 q90"]:.1f} dB')


def bench(exe: Path, work: Path, photos: list[Path]) -> None:
    """12 MP and 50 MP (2 x 2 mosaic) photos at the app's defaults, 8 threads (the phones' core count)."""
    tiles = []
    for p in photos[:4]:
        with Image.open(p) as im:
            im = im.convert('RGB')
            if im.height > im.width:
                im = im.rotate(90, expand=True)
            tiles.append(np.asarray(im.resize((4096, 3072), Image.LANCZOS)) if im.size != (4096, 3072) else np.asarray(im))
    while len(tiles) < 4:
        tiles.append(tiles[len(tiles) % max(1, len(photos))][:, ::-1])
    shots = {'12.6 MP (4096x3072)': tiles[0], '50.3 MP (8192x6144)': np.vstack([np.hstack(tiles[:2]), np.hstack(tiles[2:])])}
    print('Benchmark (threads=8):')
    for name, rgb in shots.items():
        h, w, _ = rgb.shape
        raw = work / f'bench_{w}x{h}.raw'
        raw.write_bytes(pack8888(rgb))
        for speed in (5, 6, 8, 9):
            for depth, yuv in ((10, '444'), (8, '420')):
                if speed != 6 and (depth, yuv) != (10, '444'):
                    continue
                out = work / f'bench_{w}x{h}_s{speed}_{depth}_{yuv}.avif'
                r = encode(exe, raw, w, h, '8888', out, None, quality=90, depth=depth, yuv=yuv, speed=speed, threads=8)
                with Image.open(out) as im:
                    value = psnr(np.asarray(im.convert('RGB')), rgb)
                print(f'  {name} q90 {depth}-bit {yuv} speed {speed}: convert {r["convert_ms"]:.0f} ms + encode '
                      f'{r["encode_ms"]:.0f} ms, {r["bytes"] / 1e6:.2f} MB, PSNR {value:.2f} dB, peak RSS {r["peak_rss_mb"]:.0f} MB')
        raw.unlink()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--build-dir', type=Path, default=ROOT / 'build' / 'avif-host')
    parser.add_argument('--exe', type=Path, help='use this host encoder instead of building it')
    parser.add_argument('--nasm', help='nasm for libaom on x86 hosts (default: $NASM or PATH)')
    parser.add_argument('--bench', nargs='+', type=Path, metavar='PHOTO', help='time 12 MP / 50 MP encodes of these photos')
    parser.add_argument('--keep', type=Path, help='keep the encoded files in this directory')
    args = parser.parse_args()

    if not features.check('avif'):
        sys.exit('Pillow has no AVIF support (Pillow >= 11.3 wheels include it)')
    print(f'Pillow {Image.__version__}, AVIF codecs: {features.version("avif")}')
    exe = args.exe or build(args.build_dir, args.nasm)
    with tempfile.TemporaryDirectory() as tmp:
        work = args.keep or Path(tmp)
        work.mkdir(parents=True, exist_ok=True)
        for width, height in ((512, 384), (333, 211)):
            run_cases(exe, work, width, height)
        if args.bench:
            bench(exe, work, args.bench)
    if failures:
        print(f'{len(failures)} AVIF check(s) failed')
        return 1
    print(f'AVIF output OK: {len(CASES) * 2} files (8 / 10 / 12-bit, 4:4:4 / 4:2:0, lossless, RGBA_8888 / RGBA_1010102)'
          ' decode independently with the expected depth, colour, EXIF and quality')
    return 0


if __name__ == '__main__':
    sys.exit(main())
