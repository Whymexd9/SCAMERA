"""10-bit HEIC (HEVC Main10 grid in a HEIF container) check, no device.

  python3 tools/check_heic10.py FILE.heic ...   structure of photos pulled from the phone (stdlib); with pillow-heif
                                                installed they are decoded as well. Prints bit depth, grid and Exif.
  python3 tools/check_heic10.py --self-test     needs pillow-heif + numpy (a fresh venv: pip install pillow-heif numpy):
      1. a 10-bit source image is cut into tiles with edge replication (Heic10Encoder's grid), every tile is coded by x265
         through libheif (10-bit 4:2:0), its HEVC stream is turned into what MediaCodec gives (Annex-B codec config +
         one Annex-B access unit per tile, the parameter sets also repeated in front of some tiles);
      2. the app's own container code (HevcNal + HeifContainerWriter, compiled with javac) wraps the streams with an Exif
         block (tools/java/Heic10ContainerSample.java);
      3. the file must pass the structure check and libheif must decode it: 10 bits, the image size, pixels within the
         codec tolerance of the source (edges included: the padding is cropped away), the Exif tag readable;
      4. broken variants (wrong grid count, missing hvcC essential flag, iloc offset outside the file, no 'heic' brand)
         must be rejected;
      5. the 8-bit path stays as it was: PhotoOutput still writes the 8-bit HEIC through HeifWriter with the same builder
         and the post pipeline renders the 8-bit final image into the same RGBA8 target (source guard).

What a file must have (ISO/IEC 23008-12, ISO/IEC 14496-15):
  * ftyp with 'mif1' and 'heic' among the brands (androidx ExifInterface needs both, Skia / MediaExtractor take either);
  * meta: hdlr 'pict', pitm, iinf/infe v2, iloc (every extent inside the file), iprp/ipco/ipma;
  * primary item 'grid' whose ImageGrid rows x columns equals its 'dimg' references, every tile a hidden 'hvc1' item with
    an essential hvcC, the same ispe, and tiles that cover the grid's ispe; or a primary 'hvc1' item (HeifWriter, small);
  * hvcC: configurationVersion 1, lengthSizeMinusOne 3, VPS/SPS/PPS arrays; tile samples are length-prefixed NAL units
    with no parameter sets and at least one slice;
  * an 'Exif' item linked by 'cdsc' to the primary item, whose data is a 4-byte TIFF header offset and then
    "Exif\\0\\0" + a TIFF header (the layout of HeifWriter / MPEG4Writer that the platform's ExifInterface reads).
"""
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HEIF = ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/heif'


class Bad(Exception):
    pass


def need(cond, msg):
    if not cond:
        raise Bad(msg)


def boxes(data, start, end):
    """[(type, payload_start, box_end)] of the boxes in data[start:end]."""
    out, pos = [], start
    while pos < end:
        need(pos + 8 <= end, 'truncated box header at %d' % pos)
        size, typ = struct.unpack('>I4s', data[pos:pos + 8])
        hdr = 8
        if size == 1:
            need(pos + 16 <= end, 'truncated large box at %d' % pos)
            size = struct.unpack('>Q', data[pos + 8:pos + 16])[0]
            hdr = 16
        elif size == 0:
            size = end - pos
        need(size >= hdr and pos + size <= end, 'box %r at %d overruns its parent' % (typ, pos))
        out.append((typ.decode('latin1'), pos + hdr, pos + size))
        pos += size
    return out


def child(data, parent, name, required=True):
    """(payload_start, end) of the first child box called name; parent = (children_start, end)."""
    start, end = parent
    found = [(s, e) for t, s, e in boxes(data, start, end) if t == name]
    need(found or not required, 'no %s box' % name)
    return found[0] if found else None


def u(data, pos, n):
    return int.from_bytes(data[pos:pos + n], 'big')


def parse_hvcc(b):
    need(len(b) >= 23 and b[0] == 1, 'hvcC configurationVersion is not 1')
    info = {'profile': b[1] & 0x1F, 'tier': (b[1] >> 5) & 1, 'level': b[12], 'chroma': b[16] & 3,
            'luma_bits': (b[17] & 7) + 8, 'chroma_bits': (b[18] & 7) + 8, 'length_size': (b[21] & 3) + 1}
    need(info['length_size'] == 4, 'lengthSizeMinusOne is not 3')
    arrays, pos = {}, 23
    for _ in range(b[22]):
        need(pos + 3 <= len(b), 'hvcC array truncated')
        typ, count = b[pos] & 0x3F, u(b, pos + 1, 2)
        pos += 3
        for _ in range(count):
            n = u(b, pos, 2)
            need(pos + 2 + n <= len(b), 'hvcC NAL unit truncated')
            arrays.setdefault(typ, []).append(bytes(b[pos + 2:pos + 2 + n]))
            pos += 2 + n
    for typ, name in ((32, 'VPS'), (33, 'SPS'), (34, 'PPS')):
        need(typ in arrays, 'hvcC without a %s' % name)
    info['arrays'] = arrays
    return info


def check_file(path, data=None, app_brands=True):
    """Structure of a HEIF file; returns a summary dict. Raises Bad. app_brands: 'heic' must be listed (the app's files)."""
    data = Path(path).read_bytes() if data is None else data
    top = boxes(data, 0, len(data))
    need(top and top[0][0] == 'ftyp', 'the file does not start with ftyp')
    s, e = top[0][1], top[0][2]
    brands = [data[s:s + 4].decode('latin1')] + [data[p:p + 4].decode('latin1') for p in range(s + 8, e, 4)]
    need('mif1' in brands, "no 'mif1' brand")
    need('heic' in brands or not app_brands, "no 'heic' brand (androidx ExifInterface takes HEIF only with 'mif1' and 'heic')")
    metas = [(s, e) for t, s, e in top if t == 'meta']
    need(len(metas) == 1, 'need exactly one meta box')
    meta = (metas[0][0] + 4, metas[0][1])  # a FullBox: its children follow version / flags
    hdlr = child(data, meta, 'hdlr')
    need(data[hdlr[0] + 8:hdlr[0] + 12] == b'pict', "hdlr is not 'pict'")
    pitm = child(data, meta, 'pitm')
    primary = u(data, pitm[0] + 4, 2) if data[pitm[0]] == 0 else u(data, pitm[0] + 4, 4)
    # iinf
    iinf = child(data, meta, 'iinf')
    count_size = 2 if data[iinf[0]] == 0 else 4
    items = {}
    for t, s, e in boxes(data, iinf[0] + 4 + count_size, iinf[1]):
        need(t == 'infe', 'iinf holds a %s box' % t)
        version, flags = data[s], u(data, s + 1, 3)
        need(version >= 2, 'infe version %d' % version)
        id_size = 2 if version == 2 else 4
        item_id = u(data, s + 4, id_size)
        typ = data[s + 4 + id_size + 2:s + 4 + id_size + 6].decode('latin1')
        items[item_id] = {'type': typ, 'hidden': bool(flags & 1)}
    need(primary in items, 'pitm names no item')
    # iloc
    iloc = child(data, meta, 'iloc')
    p = iloc[0]
    version = data[p]
    need(version in (0, 1, 2), 'iloc version %d' % version)
    off_size, len_size = data[p + 4] >> 4, data[p + 4] & 15
    base_size, index_size = data[p + 5] >> 4, (data[p + 5] & 15) if version else 0
    p += 6
    n = u(data, p, 2 if version < 2 else 4)
    p += 2 if version < 2 else 4
    for _ in range(n):
        item_id = u(data, p, 2 if version < 2 else 4)
        p += 2 if version < 2 else 4
        method = (u(data, p, 2) & 15) if version else 0
        p += 2 if version else 0
        p += 2  # data_reference_index
        base = u(data, p, base_size)
        p += base_size
        extents = []
        for _ in range(u(data, p, 2)):
            p += 2 if not extents else 0
            p += index_size
            off, length = u(data, p, off_size), u(data, p + off_size, len_size)
            p += off_size + len_size
            extents.append((base + off, length))
        if not extents:
            p += 2
        need(item_id in items, 'iloc for unknown item %d' % item_id)
        need(method in (0, 1), 'construction_method %d' % method)
        if method == 0:
            for off, length in extents:
                need(0 < off and off + length <= len(data), 'item %d extent %d+%d outside the file' % (item_id, off, length))
        items[item_id]['method'] = method
        items[item_id]['extents'] = extents
    idat = child(data, meta, 'idat', required=False)

    def item_data(item_id):
        it = items[item_id]
        need('extents' in it, 'item %d has no iloc entry' % item_id)
        if it['method'] == 1:
            need(idat is not None, 'idat item without idat')
            return b''.join(data[idat[0] + o:idat[0] + o + n] for o, n in it['extents'])
        return b''.join(data[o:o + n] for o, n in it['extents'])

    # iref
    refs = {}
    iref = child(data, meta, 'iref', required=False)
    if iref:
        id_size = 2 if data[iref[0]] == 0 else 4
        for t, s, e in boxes(data, iref[0] + 4, iref[1]):
            src, cnt = u(data, s, id_size), u(data, s + id_size, 2)
            refs.setdefault(t, {})[src] = [u(data, s + id_size + 2 + i * id_size, id_size) for i in range(cnt)]
    # iprp
    iprp = child(data, meta, 'iprp')
    ipco = child(data, iprp, 'ipco')
    props = boxes(data, ipco[0], ipco[1])
    assoc = {}
    for _, s, e in [b for b in boxes(data, iprp[0], iprp[1]) if b[0] == 'ipma']:
        version, flags = data[s], u(data, s + 1, 3)
        p, count = s + 8, u(data, s + 4, 4)
        for _ in range(count):
            item_id = u(data, p, 2 if version < 1 else 4)
            p += 2 if version < 1 else 4
            entries = []
            for _ in range(data[p]):
                if flags & 1:
                    v = u(data, p + 1, 2)
                    entries.append((bool(v & 0x8000), v & 0x7FFF))
                    p += 2
                else:
                    entries.append((bool(data[p + 1] & 0x80), data[p + 1] & 0x7F))
                    p += 1
            p += 1
            assoc[item_id] = entries

    def prop(item_id, name):
        for essential, index in assoc.get(item_id, []):
            need(1 <= index <= len(props), 'item %d names property %d of %d' % (item_id, index, len(props)))
            t, s, e = props[index - 1]
            if t == name:
                return essential, data[s:e]
        return None

    def ispe(item_id):
        p = prop(item_id, 'ispe')
        need(p is not None, 'item %d has no ispe' % item_id)
        return u(p[1], 4, 4), u(p[1], 8, 4)

    def coded(item_id):
        p = prop(item_id, 'hvcC')
        need(p is not None, 'hvc1 item %d has no hvcC' % item_id)
        need(p[0], 'hvcC of item %d is not essential' % item_id)
        info = parse_hvcc(p[1])
        sample = item_data(item_id)
        pos, slices = 0, 0
        while pos < len(sample):
            need(pos + 4 <= len(sample), 'item %d: truncated NAL length' % item_id)
            n = u(sample, pos, 4)
            need(n >= 2 and pos + 4 + n <= len(sample), 'item %d: NAL unit of %d bytes overruns the sample' % (item_id, n))
            typ = (sample[pos + 4] >> 1) & 0x3F
            need(typ not in (32, 33, 34), 'item %d: parameter set in the sample (it belongs in hvcC)' % item_id)
            slices += typ < 32
            pos += 4 + n
        need(slices > 0, 'item %d: no slice in the sample' % item_id)
        return info

    summary = {'brands': brands}
    ptype = items[primary]['type']
    if ptype == 'grid':
        g = item_data(primary)
        need(len(g) in (8, 12) and g[0] == 0, 'bad ImageGrid of %d bytes' % len(g))
        rows, cols = g[2] + 1, g[3] + 1
        out_w, out_h = (u(g, 4, 2), u(g, 6, 2)) if not g[1] & 1 else (u(g, 4, 4), u(g, 8, 4))
        tiles = refs.get('dimg', {}).get(primary, [])
        need(len(tiles) == rows * cols, 'grid %dx%d with %d dimg references' % (cols, rows, len(tiles)))
        need(ispe(primary) == (out_w, out_h), 'grid ispe %s is not the ImageGrid size %dx%d' % (ispe(primary), out_w, out_h))
        tile_size, hvcc = None, None
        for t in tiles:
            need(t in items and items[t]['type'] == 'hvc1', 'grid tile %d is not an hvc1 item' % t)
            need(items[t]['hidden'], 'grid tile %d is not hidden' % t)
            size = ispe(t)
            need(tile_size in (None, size), 'tiles of different sizes')
            tile_size = size
            info = coded(t)
            hvcc = hvcc or info
        need(tile_size[0] * cols >= out_w and tile_size[0] * (cols - 1) < out_w, 'tile columns do not cover the width')
        need(tile_size[1] * rows >= out_h and tile_size[1] * (rows - 1) < out_h, 'tile rows do not cover the height')
        summary.update(width=out_w, height=out_h, grid=(cols, rows), tile=tile_size)
    else:
        need(ptype == 'hvc1', "primary item is a '%s'" % ptype)
        hvcc = coded(primary)
        w, h = ispe(primary)
        summary.update(width=w, height=h, grid=None, tile=(w, h))
    summary['bits'] = hvcc['luma_bits']
    summary['profile'] = hvcc['profile']
    summary['chroma'] = hvcc['chroma']
    pixi = prop(primary, 'pixi')
    if pixi is not None:
        need(pixi[1][4] == 3 and all(b == hvcc['luma_bits'] for b in pixi[1][5:8]), 'pixi does not match hvcC')
    colr = prop(primary, 'colr')
    if colr is not None and colr[1][:4] == b'nclx':
        summary['nclx'] = struct.unpack('>HHH', colr[1][4:10]) + (colr[1][10] >> 7,)
    exif_items = [i for i, it in items.items() if it['type'] == 'Exif']
    summary['exif'] = None
    for i in exif_items:
        need(primary in refs.get('cdsc', {}).get(i, []), 'Exif item %d does not describe the primary item' % i)
        x = item_data(i)
        need(len(x) > 4 + 6 + 8, 'Exif item too short')
        off = u(x, 0, 4)
        need(off >= 6 and x[4 + off - 6:4 + off] == b'Exif\0\0', 'Exif item without "Exif\\0\\0" before the TIFF header '
                                                                   '(the platform ExifInterface rejects it)')
        tiff = x[4 + off:]
        need(tiff[:4] in (b'II*\0', b'MM\0*'), 'no TIFF header at exif_tiff_header_offset')
        summary['exif'] = tiff
    return summary


def report(path):
    s = check_file(path)
    line = '%s: %dx%d, %d-bit, profile %d, chroma %d, %s, brands %s, Exif %s' % (
        path, s['width'], s['height'], s['bits'], s['profile'], s['chroma'],
        'grid %dx%d of %dx%d tiles' % (s['grid'] + s['tile']) if s['grid'] else 'single image',
        '/'.join(s['brands']), 'yes' if s['exif'] else 'no')
    if 'nclx' in s:
        line += ', nclx %d/%d/%d full=%d' % s['nclx']
    try:
        import pillow_heif
        im = pillow_heif.open_heif(str(path), convert_hdr_to_8bit=False)
        line += ', decodes (%s, %s)' % (im.mode, im.info.get('bit_depth'))
    except ImportError:
        pass
    print(line)
    return s


# --------------------------------------------------------------------------------------------- self-test

def tiff_exif(make):
    """'Exif\\0\\0' + a little-endian TIFF block with one IFD0 entry: Make."""
    value = make.encode() + b'\0'
    ifd = struct.pack('<H', 1) + struct.pack('<HHII', 0x010F, 2, len(value), 8 + 2 + 12 + 4) + struct.pack('<I', 0)
    return b'Exif\0\0' + b'II*\0' + struct.pack('<I', 8) + ifd + value


def hevc_of(heic_bytes):
    """(hvcC arrays, sample) of a single-image HEIF written by libheif."""
    s = check_file('libheif tile', heic_bytes, app_brands=False)  # libheif lists 'heix' without 'heic' for 10 bits
    need(s['grid'] is None, 'libheif wrote a grid for one tile')
    data = heic_bytes
    meta = [(s0 + 4, e0) for t, s0, e0 in boxes(data, 0, len(data)) if t == 'meta'][0]
    iprp = child(data, meta, 'iprp')
    ipco = child(data, iprp, 'ipco')
    hvcc = [data[s0:e0] for t, s0, e0 in boxes(data, ipco[0], ipco[1]) if t == 'hvcC'][0]
    arrays = parse_hvcc(hvcc)['arrays']
    mdat = [(s0, e0) for t, s0, e0 in boxes(data, 0, len(data)) if t == 'mdat'][0]
    return arrays, data[mdat[0]:mdat[1]]


def annex_b(nals):
    return b''.join(b'\0\0\0\1' + n for n in nals)


def length_prefixed_to_nals(sample):
    out, pos = [], 0
    while pos < len(sample):
        n = u(sample, pos, 4)
        out.append(sample[pos + 4:pos + 4 + n])
        pos += 4 + n
    return out


def must_fail(data, what, tmp):
    try:
        check_file(what, bytes(data))
    except Bad:
        return
    raise SystemExit('self-test: the broken variant "%s" was accepted' % what)


def guard_8bit_path():
    """The 8-bit HEIC / JPEG / WebP path is the one before the 10-bit HEIC: same writer calls, same render target."""
    po = (ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/PhotoOutput.java').read_text(encoding='utf-8')
    heif = po[po.index('static boolean saveHeic(Path file, Bitmap img, int quality, ParseExif.ExifData exif)'):]
    heif = heif[:heif.index('private static volatile Boolean heicEncoder')]
    for line in ('new androidx.heifwriter.HeifWriter.Builder(file.toString(), width, height,',
                 'androidx.heifwriter.HeifWriter.INPUT_MODE_BITMAP)', '.setQuality(Math.max(1, Math.min(100, quality)))',
                 '.setGridEnabled(true)', '.setMaxImages(1)', '.setPrimaryIndex(0)', '.setRotation(0)',
                 'writer.addBitmap(img);', 'if (exifBlock != null) writer.addExifData(0, exifBlock, 0, exifBlock.length);',
                 'exifBlock = ExifBlock.exifDataBlock(ExifBlock.app1Segment(exif, width, height));'):
        need(line in heif, 'PhotoOutput.saveHeic (8-bit HEIC) changed: %r missing' % line)
    pp = (ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/postpipeline/PostPipeline.java').read_text(encoding='utf-8')
    need('GLFormat format = new GLFormat(GLFormat.DataType.SIMPLE_8, 4);' in pp, 'PostPipeline output format changed')
    gl = (ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/opengl/GLCoreBlockProcessing.java').read_text(encoding='utf-8')
    eight = gl[gl.index('public Bitmap drawBlocksToBitmap() {'):]
    eight = eight[:eight.index('static final class TileBlitter')]
    for line in ('final Bitmap dst = Bitmap.createBitmap(mOutWidth, mOutHeight, Bitmap.Config.ARGB_8888);',
                 'glReadPixels(0, 0, mOutWidth, height, mglFormat.getGLFormatExternal(), mglFormat.getGLType(), mBlockBuffer);'):
        need(line in eight, 'the 8-bit readback changed: %r missing' % line)
    need('glRenderbufferStorage(GL_RENDERBUFFER, glFormat.getGLFormatInternal(), mOutWidth, rows);' in gl,
         'the 8-bit tile target changed')


def self_test():
    try:
        import numpy as np
        import pillow_heif
    except ImportError:
        raise SystemExit('self-test needs pillow-heif and numpy (python3 -m venv v && v/bin/pip install pillow-heif numpy)')
    javac, java = shutil.which('javac'), shutil.which('java')
    if not javac or not java:
        raise SystemExit('self-test needs a JDK (javac, java) on PATH')
    guard_8bit_path()
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        classes = tmp / 'classes'
        subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(classes), str(HEIF / 'HevcNal.java'), str(HEIF / 'TileGrid.java'),
                        str(HEIF / 'HeifContainerWriter.java'), str(ROOT / 'tools/java/Heic10ContainerSample.java')], check=True)
        cases = [(1100, 700, 512), (300, 200, 128), (256, 256, 256)]
        for width, height, tile in cases:
            work = tmp / ('%dx%d' % (width, height))
            work.mkdir()
            # 10-bit source: smooth gradients (the banding case) + a few sharp patches, not a multiple of the tile size.
            y, x = np.mgrid[0:height, 0:width]
            src = np.zeros((height, width, 3), np.int64)
            src[..., 0] = 40 + x * 900 // max(1, width - 1)
            src[..., 1] = 60 + y * 880 // max(1, height - 1)
            src[..., 2] = 200 + (600 * ((x / width) ** 2 + (y / height) ** 2) / 2).astype(np.int64)
            src[(x // 40 + y // 40) % 7 == 0] = (1000, 30, 200)
            cols, rows = -(-width // tile), -(-height // tile)
            padded = np.pad(src, ((0, rows * tile - height), (0, cols * tile - width), (0, 0)), mode='edge')
            first_sets = None
            for r in range(rows):
                for c in range(cols):
                    block = padded[r * tile:(r + 1) * tile, c * tile:(c + 1) * tile]
                    heif = pillow_heif.from_bytes(mode='RGB;16', size=(tile, tile),
                                                  data=(block.astype(np.uint16) << 6).tobytes())
                    out = tmp / 'enc.heic'
                    # BT.709 full range, the matrix Heic10Encoder converts with and the container's colr names.
                    heif.save(str(out), quality=96, chroma=420, matrix_coefficients=1, color_primaries=1,
                              transfer_characteristic=13, full_range_flag=1)
                    arrays, sample = hevc_of(out.read_bytes())
                    sets = arrays[32] + arrays[33] + arrays[34]
                    if first_sets is None:
                        first_sets = sets
                        (work / 'csd.bin').write_bytes(annex_b(sets))  # MediaCodec: BUFFER_FLAG_CODEC_CONFIG
                    need(sets == first_sets, 'x265 changed the parameter sets between tiles')
                    nals = length_prefixed_to_nals(sample)
                    i = r * cols + c
                    # Some encoders repeat VPS/SPS/PPS (and an AUD) in front of every IDR: they must not reach the sample.
                    if i % 2 == 1:
                        nals = [b'\x46\x01\x50'] + sets + nals
                    (work / ('tile_%d.bin' % i)).write_bytes(annex_b(nals))
            (work / 'exif.bin').write_bytes(tiff_exif('SCAMERA'))
            heic = work / 'out.heic'
            subprocess.run([java, '-cp', str(classes), 'Heic10ContainerSample', str(work), str(width), str(height), str(tile),
                            str(heic)], check=True)
            s = check_file(heic)
            need(s['bits'] == 10 and s['chroma'] == 1, 'not a 10-bit 4:2:0 stream: %s' % s)
            need((s['width'], s['height']) == (width, height), 'size %dx%d' % (s['width'], s['height']))
            need(s['grid'] == (cols, rows) and s['tile'] == (tile, tile), 'grid %s of %s' % (s['grid'], s['tile']))
            need(s.get('nclx') == (1, 13, 1, 1), 'colr nclx %s' % (s.get('nclx'),))
            decoded = pillow_heif.open_heif(str(heic), convert_hdr_to_8bit=False)
            need(decoded.info.get('bit_depth') == 10, 'libheif decodes %s bits' % decoded.info.get('bit_depth'))
            need(decoded.size == (width, height), 'libheif size %s' % (decoded.size,))
            got = np.asarray(decoded).astype(np.int64) >> 6
            err = np.abs(got - src)
            # 4:2:0 subsampling + lossy coding: the sharp patch edges carry the chroma error, the gradients stay close
            # (the grid's padding is cropped away: the right / bottom tiles must not show their replicated edge).
            patch = (x // 40 + y // 40) % 7 == 0
            near = np.zeros_like(patch)
            for dy in range(-4, 5):
                for dx in range(-4, 5):
                    near |= np.roll(np.roll(patch, dy, axis=0), dx, axis=1) != patch
            smooth = ~near
            need(err.mean() <= 3, 'mean error %.1f codes' % err.mean())
            need(np.percentile(err[smooth], 99) <= 8, 'gradient error p99 %.1f codes' % np.percentile(err[smooth], 99))
            # An 8-bit decode widened to 10 bits leaves only multiples of 4; the 10-bit one uses every code.
            fine = float((got[smooth] % 4 != 0).mean())
            need(fine > 0.5, 'only %.0f %% of the samples off the 8-bit grid: not a 10-bit decode' % (100 * fine))
            levels = len(np.unique(got[..., 0][smooth]))
            exif = decoded.info.get('exif')
            need(exif and b'SCAMERA' in exif, 'Exif not read back by libheif')
            from PIL import Image
            pillow_heif.register_heif_opener()
            with Image.open(heic) as im:
                need(im.getexif().get(0x010F) == 'SCAMERA', 'Pillow does not read Make from the Exif item')
            print('  %dx%d in %dx%d tiles of %d: 10-bit (%.0f %% off the 8-bit grid), mean error %.2f, gradient p99 %.0f,'
                  ' %d red levels, Exif OK, %d bytes' % (width, height, cols, rows, tile, 100 * fine, err.mean(),
                                                         np.percentile(err[smooth], 99), levels, heic.stat().st_size))
        good = bytearray((tmp / '1100x700' / 'out.heic').read_bytes())
        # Broken variants.
        bad = bytearray(good)
        meta_dimg = bad.index(b'dimg')
        cnt = meta_dimg + 4 + 2
        bad[cnt:cnt + 2] = struct.pack('>H', u(bad, cnt, 2) - 1)
        must_fail(bad, 'dimg count below the grid', tmp)
        bad = bytearray(good)
        ipma = bad.index(b'ipma')
        bad[ipma + 4 + 4 + 4 + 2 + 1] &= 0x7F  # first tile: hvcC association without the essential bit
        must_fail(bad, 'hvcC not essential', tmp)
        bad = bytearray(good)
        iloc = bad.index(b'iloc')
        first = iloc + 4 + 4 + 2 + 2 + 2 + 2 + 2 + 2
        bad[first:first + 4] = struct.pack('>I', len(good) + 10)
        must_fail(bad, 'iloc offset past the end', tmp)
        bad = bytearray(good)
        ftyp = bad.index(b'ftyp')
        bad[ftyp + 4:ftyp + 8] = b'heix'
        bad[ftyp + 16:ftyp + 20] = b'heix'
        must_fail(bad, "no 'heic' brand", tmp)
    print('HEIC 10-bit self-test PASS: 3 grids (padding, small tiles, one tile) decode at 10 bits with Exif, '
          '4 broken variants rejected, 8-bit path unchanged')


def main(argv):
    if not argv or argv == ['--self-test']:
        self_test()
        return 0
    failed = 0
    for path in argv:
        try:
            report(path)
        except Bad as e:
            failed += 1
            print('%s: NOT a valid HEIC: %s' % (path, e))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
