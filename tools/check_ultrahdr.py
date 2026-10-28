"""Ultra HDR (JPEG/R, Ultra HDR v1 / ISO 21496-1 gain map) structure check, stdlib only, no device.

  python3 tools/check_ultrahdr.py [--strict] FILE.jpg ...    validate photos (e.g. pulled from the phone); --strict also
                                                              rejects the layout slack stock cameras use (see check_file)
  python3 tools/check_ultrahdr.py --self-test                 build samples with the app's own container code and validate them
  python3 tools/check_ultrahdr.py --scene-luma                P370 (B3): the SHIPPED ultrahdr/sceneluma.glsl and gainmap.glsl on
                                                              the GPU (numpy, moderngl; LIBGL_ALWAYS_SOFTWARE=1 headless): the scene
                                                              plane's luminance is the render's (the colour matrix and white
                                                              balance, PostPipeline.sceneLumaWeights) within 1 % on ColorChecker
                                                              colours through the OPPO X8U and vivo X200U ISP matrices, a grey
                                                              within 1e-6; a Display P3 base's luminance takes the P3 weights

What a file must have (Ultra HDR image format v1.0, CIPA DC-007 MPF):
  * primary JPEG: SOI, an APP1 XMP packet with hdrgm:Version="1.0" and a GContainer directory (Container:Directory /
    rdf:Seq) whose first item is Semantic=Primary, Mime=image/jpeg and second Semantic=GainMap, Mime=image/jpeg with
    Item:Length = the gain-map image's byte length; an APP2 MPF segment;
  * MPF: TIFF header, MPFVersion (B000) UNDEFINED x4 "0100", NumberOfImages (B001) = 2, MPEntry (B002) UNDEFINED 32 bytes;
    entry 0 = primary (type 0x030000, offset 0, size = primary length up to its EOI); entry 1 = gain map, offset relative to
    the MPF base (the byte after "MPF\\0"), pointing at an SOI, size = Item:Length, ending inside the file;
  * both images are complete JPEGs (frame header, Huffman / quantisation tables, scan, EOI); with Pillow installed they
    are decoded as well;
  * gain-map XMP: hdrgm:Version 1.0, GainMapMin, GainMapMax, Gamma, OffsetSDR, OffsetHDR, HDRCapacityMin, HDRCapacityMax
    (attributes or per-channel rdf:Seq), finite, GainMapMax >= GainMapMin, Gamma > 0, HDRCapacityMax > HDRCapacityMin >= 0,
    offsets >= 0; the gain map has the primary's aspect ratio (within 2 %) and is not larger than it.
The self-test also corrupts a sample in the ways the app got wrong before (MPF version type, gain-map offset from the
file start, wrong Item:Length) and requires each to be rejected.
"""
from pathlib import Path
import math
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {
    'x': 'adobe:ns:meta/',
    'rdf': 'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
    'Container': 'http://ns.google.com/photos/1.0/container/',
    'Item': 'http://ns.google.com/photos/1.0/container/item/',
    'hdrgm': 'http://ns.adobe.com/hdr-gain-map/1.0/',
}
XMP_ID = b'http://ns.adobe.com/xap/1.0/\x00'
GAIN_FIELDS = ('GainMapMin', 'GainMapMax', 'Gamma', 'OffsetSDR', 'OffsetHDR', 'HDRCapacityMin', 'HDRCapacityMax')
SOF = {0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF}


class Bad(Exception):
    pass


def need(cond, msg):
    if not cond:
        raise Bad(msg)


def segments(data, start):
    """Marker segments of the JPEG at data[start:] up to SOS: [(marker, payload_start, payload_end)], then the SOS end."""
    need(data[start:start + 2] == b'\xff\xd8', 'no SOI at %d' % start)
    pos, out = start + 2, []
    while True:
        need(pos + 4 <= len(data), 'truncated header at %d' % pos)
        need(data[pos] == 0xFF, 'no marker at %d' % pos)
        marker = data[pos + 1]
        if marker == 0xFF:
            pos += 1
            continue
        length = struct.unpack('>H', data[pos + 2:pos + 4])[0]
        need(length >= 2 and pos + 2 + length <= len(data), 'bad segment length at %d' % pos)
        out.append((marker, pos + 4, pos + 2 + length))
        pos += 2 + length
        if marker == 0xDA:
            return out, pos


def jpeg_end(data, start):
    """Validates a complete JPEG at data[start:] and returns (end offset after EOI, width, height, segment list)."""
    segs, pos = segments(data, start)
    markers = [m for m, _, _ in segs]
    sof = [s for s in segs if s[0] in SOF]
    need(sof, 'no frame header (SOF)')
    need(0xDB in markers, 'no quantisation table (DQT)')
    huffman = 0xC4 in markers
    _, a, _ = sof[0]
    height, width = struct.unpack('>HH', data[a + 1:a + 5])
    need(width > 0 and height > 0, 'zero image size')
    # Entropy-coded data until EOI; restart markers, stuffed bytes and further scans (progressive) are skipped.
    while pos + 1 < len(data):
        if data[pos] != 0xFF:
            pos += 1
            continue
        m = data[pos + 1]
        if m == 0x00 or 0xD0 <= m <= 0xD7 or m == 0xFF:
            pos += 2 if m != 0xFF else 1
            continue
        if m == 0xD9:
            # Huffman-coded frames need their tables (a progressive JPEG may define them between scans).
            need(huffman or sof[0][0] >= 0xC9, 'no Huffman table (DHT)')
            return pos + 2, width, height, segs
        # another marker segment between scans (DHT / SOS of a progressive JPEG)
        need(pos + 4 <= len(data), 'truncated segment in the scan data')
        huffman = huffman or m == 0xC4
        length = struct.unpack('>H', data[pos + 2:pos + 4])[0]
        pos += 2 + length
    raise Bad('no EOI')


def xmp_of(data, segs):
    for marker, a, b in segs:
        if marker == 0xE1 and data[a:a + len(XMP_ID)] == XMP_ID:
            text = data[a + len(XMP_ID):b].decode('utf-8', 'strict')
            text = re.sub(r'<\?xpacket[^>]*\?>', '', text).strip()
            return ET.fromstring(text)
    return None


def q(prefix, name):
    return '{%s}%s' % (NS[prefix], name)


def hdrgm_values(desc, field):
    """A hdrgm field as a list of floats: an attribute, or an element with text or an rdf:Seq (one value per channel)."""
    if q('hdrgm', field) in desc.attrib:
        return [float(desc.attrib[q('hdrgm', field)])]
    el = desc.find('hdrgm:' + field, NS)
    if el is None:
        return None
    seq = el.find('rdf:Seq', NS)
    if seq is not None:
        return [float(li.text) for li in seq.findall('rdf:li', NS)]
    return [float(el.text)]


def check_primary_xmp(root):
    descs = root.findall('.//rdf:Description', NS)
    need(descs, 'primary XMP has no rdf:Description')
    version = [d.attrib.get(q('hdrgm', 'Version')) for d in descs if d.attrib.get(q('hdrgm', 'Version'))]
    need(version == ['1.0'], 'primary XMP: hdrgm:Version must be 1.0, got %s' % version)
    seq = root.find('.//Container:Directory/rdf:Seq', NS)
    need(seq is not None, 'primary XMP: no Container:Directory / rdf:Seq')
    items = []
    for li in seq.findall('rdf:li', NS):
        item = li.find('Container:Item', NS)
        need(item is not None, 'directory entry without Container:Item')
        items.append({k.split('}')[1]: v for k, v in item.attrib.items() if k.startswith('{' + NS['Item'])})
    need(len(items) >= 2, 'directory needs Primary and GainMap items, has %d' % len(items))
    need(items[0].get('Semantic') == 'Primary' and items[0].get('Mime') == 'image/jpeg', 'first item must be the JPEG primary: %s' % items[0])
    gain = [i for i in items[1:] if i.get('Semantic') == 'GainMap']
    need(len(gain) == 1, 'exactly one GainMap item expected')
    need(gain[0].get('Mime') == 'image/jpeg', 'GainMap item Mime must be image/jpeg')
    need(gain[0].get('Length', '').isdigit() and int(gain[0]['Length']) > 0, 'GainMap item needs a positive Item:Length')
    for i in items[1:]:
        need('Length' in i, 'every item after the primary needs Item:Length')
    return int(gain[0]['Length']), int(items[0].get('Padding', '0') or 0)


def check_gain_xmp(root):
    desc = None
    for d in root.findall('.//rdf:Description', NS):
        if d.attrib.get(q('hdrgm', 'Version')) is not None or d.find('hdrgm:GainMapMax', NS) is not None:
            desc = d
    need(desc is not None, 'gain map XMP has no hdrgm description')
    need(desc.attrib.get(q('hdrgm', 'Version')) == '1.0', 'gain map XMP: hdrgm:Version must be 1.0')
    v = {}
    for f in GAIN_FIELDS:
        vals = hdrgm_values(desc, f)
        need(vals, 'gain map XMP: hdrgm:%s missing' % f)
        need(all(math.isfinite(x) for x in vals), 'gain map XMP: hdrgm:%s not finite' % f)
        v[f] = vals
    for lo, hi in zip(v['GainMapMin'], v['GainMapMax']):
        need(hi >= lo, 'GainMapMax < GainMapMin')
    need(all(g > 0 for g in v['Gamma']), 'Gamma must be > 0')
    need(all(o >= 0 for o in v['OffsetSDR'] + v['OffsetHDR']), 'offsets must be >= 0')
    need(v['HDRCapacityMin'][0] >= 0, 'HDRCapacityMin must be >= 0')
    need(v['HDRCapacityMax'][0] > v['HDRCapacityMin'][0], 'HDRCapacityMax must be > HDRCapacityMin')
    need(max(v['GainMapMax']) <= 16, 'GainMapMax above 16 stops is not a plausible photo')
    base_hdr = desc.attrib.get(q('hdrgm', 'BaseRenditionIsHDR'), 'False')
    need(base_hdr in ('False', 'false'), 'an SDR base must have BaseRenditionIsHDR False')
    return {k: (x[0] if len(x) == 1 else x) for k, x in v.items()}


def check_mpf(data, segs):
    mpf = [(a, b) for m, a, b in segs if m == 0xE2 and data[a:a + 4] == b'MPF\x00']
    need(len(mpf) == 1, 'primary needs exactly one MPF APP2 segment, has %d' % len(mpf))
    a, b = mpf[0]
    base = a + 4
    order = data[base:base + 2]
    need(order in (b'II', b'MM'), 'MPF: bad byte order')
    e = '<' if order == b'II' else '>'
    need(struct.unpack(e + 'H', data[base + 2:base + 4])[0] == 42, 'MPF: bad TIFF magic')
    ifd = struct.unpack(e + 'I', data[base + 4:base + 8])[0]
    count = struct.unpack(e + 'H', data[base + ifd:base + ifd + 2])[0]
    tags = {}
    for i in range(count):
        p = base + ifd + 2 + 12 * i
        tag, typ, cnt = struct.unpack(e + 'HHI', data[p:p + 8])
        tags[tag] = (typ, cnt, data[p + 8:p + 12])
    need(0xB000 in tags, 'MPF: no MPFVersion (B000)')
    typ, cnt, val = tags[0xB000]
    need(typ == 7 and cnt == 4, 'MPF: MPFVersion must be UNDEFINED x4 (type %d count %d)' % (typ, cnt))
    need(val == b'0100', 'MPF: MPFVersion must be "0100"')
    need(0xB001 in tags, 'MPF: no NumberOfImages (B001)')
    typ, cnt, val = tags[0xB001]
    need(typ == 4 and cnt == 1 and struct.unpack(e + 'I', val)[0] == 2, 'MPF: NumberOfImages must be LONG 2')
    need(0xB002 in tags, 'MPF: no MPEntry (B002)')
    typ, cnt, val = tags[0xB002]
    need(typ == 7 and cnt == 32, 'MPF: MPEntry must be UNDEFINED x32 (2 images)')
    off = struct.unpack(e + 'I', val)[0]
    entries = []
    for i in range(2):
        p = base + off + 16 * i
        need(p + 16 <= b, 'MPF: MPEntry outside the segment')
        entries.append(struct.unpack(e + 'III', data[p:p + 12]))  # attribute, size, offset (then 2 dependent entries)
    return base, entries


def check_file(path, quiet=False, strict=True):
    """Raises Bad on a broken file. strict (SCAMERA's own files, the self-test) also rejects what real phones tolerate and
    their stock cameras write: an MPF primary size that is not the primary's length (vivo) and padding after the gain
    map's EOI inside its declared size (OPPO); without strict these are printed as notes."""
    data = Path(path).read_bytes()
    notes = []

    def tolerate(cond, msg):
        if cond:
            return
        if strict:
            raise Bad(msg)
        notes.append(msg)

    end, w, h, segs = jpeg_end(data, 0)
    root = xmp_of(data, segs)
    need(root is not None, 'primary has no XMP')
    item_length, padding = check_primary_xmp(root)
    base, entries = check_mpf(data, segs)
    (pa, psize, poff), (ga, gsize, goff) = entries
    need(pa & 0x00FFFFFF == 0x030000 and pa & 0x07000000 == 0, 'MPF: entry 0 must be the JPEG primary (0x030000), got 0x%08X' % pa)
    need(poff == 0, 'MPF: primary offset must be 0')
    tolerate(psize == end + padding, 'MPF: primary size %d != primary length %d' % (psize, end + padding))
    need(ga & 0x07000000 == 0, 'MPF: gain map must be a JPEG entry')
    gstart = base + goff
    need(gstart == end + padding, 'MPF: gain map offset %d (base %d) points at %d, the primary ends at %d' % (goff, base, gstart, end))
    need(data[gstart:gstart + 2] == b'\xff\xd8', 'MPF: no SOI at the gain map offset')
    need(gsize == item_length, 'MPF gain map size %d != Item:Length %d' % (gsize, item_length))
    need(gstart + gsize <= len(data), 'gain map runs past the end of the file')
    gend, gw, gh, gsegs = jpeg_end(data, gstart)
    need(gend <= gstart + gsize, 'gain map JPEG ends at %d, after its MPF size (%d)' % (gend, gstart + gsize))
    tolerate(gend == gstart + gsize, 'gain map JPEG ends at %d, MPF size runs to %d (padding)' % (gend, gstart + gsize))
    groot = xmp_of(data, gsegs)
    need(groot is not None, 'gain map has no XMP')
    meta = check_gain_xmp(groot)
    need(gw <= w and gh <= h, 'gain map %dx%d larger than the primary %dx%d' % (gw, gh, w, h))
    need(abs(gw / gh - w / h) <= 0.02 * (w / h), 'gain map aspect %dx%d does not match the primary %dx%d' % (gw, gh, w, h))
    try:
        from PIL import Image  # optional: a full decode of both images
        import io
        for a, b in ((0, end), (gstart, gend)):
            with Image.open(io.BytesIO(data[a:b])) as im:
                im.load()
    except ImportError:
        pass
    if not quiet:
        print('%s: Ultra HDR OK - primary %dx%d (%d B), gain map %dx%d (%d B), %s' % (path, w, h, end, gw, gh, gsize,
              ', '.join('%s=%s' % (k, meta[k]) for k in ('GainMapMin', 'GainMapMax', 'HDRCapacityMax'))))
        for n in notes:
            print('  note (tolerated by decoders, not written by SCAMERA): ' + n)
    return True


def must_fail(data, what, tmp):
    p = Path(tmp) / 'broken.jpg'
    p.write_bytes(bytes(data))
    try:
        check_file(p, quiet=True)
    except Bad:
        return
    raise SystemExit('self-test: the check accepted a broken file (%s)' % what)


def self_test():
    javac = shutil.which('javac')
    java = shutil.which('java')
    if not javac or not java:
        raise SystemExit('self-test needs a JDK (javac, java) on PATH')
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / 'classes'
        src = ROOT / 'app/src/main/java/com/particlesdevs/photoncamera/processing/ultrahdr'
        files = [src / 'UltraHdrContainer.java', src / 'GainMapComputer.java',
                 ROOT / 'tools/java/ultrahdr-stubs/android/graphics/Bitmap.java', ROOT / 'tools/java/UltraHdrSample.java']
        subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(out)] + [str(f) for f in files], check=True)
        samples = Path(tmp) / 'samples'
        subprocess.run([java, '-Djava.awt.headless=true', '-cp', str(out), 'UltraHdrSample', str(samples)], check=True)
        for name in ('stream.jpg', 'memory.jpg'):
            check_file(samples / name)
        good = bytearray((samples / 'stream.jpg').read_bytes())
        base, _ = check_mpf(good, segments(good, 0)[0])
        e = '<' if good[base:base + 2] == b'II' else '>'
        ifd = struct.unpack(e + 'I', good[base + 4:base + 8])[0]
        # 1. MPFVersion typed ASCII (what the app wrote before): Skia rejects the MPF directory.
        bad = bytearray(good)
        bad[base + ifd + 2 + 2:base + ifd + 2 + 4] = struct.pack(e + 'H', 2)
        must_fail(bad, 'MPFVersion as ASCII', tmp)
        # 2. Gain-map offset measured from the file start instead of the MPF base.
        bad = bytearray(good)
        entry_off = struct.unpack(e + 'I', bad[base + ifd + 2 + 24 + 8:base + ifd + 2 + 24 + 12])[0]
        p = base + entry_off + 16 + 8
        goff = struct.unpack(e + 'I', bad[p:p + 4])[0]
        bad[p:p + 4] = struct.pack(e + 'I', goff + base)
        must_fail(bad, 'gain map offset from the file start', tmp)
        # 3. Item:Length that is not the gain map's length.
        text = bytes(good)
        m = re.search(rb'Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="(\d+)"', text)
        need(m is not None, 'self-test: no Item:Length in the sample')
        wrong = str(int(m.group(1)) + 1).encode()
        need(len(wrong) == len(m.group(1)), 'self-test: length digits changed')
        must_fail(text[:m.start(1)] + wrong + text[m.end(1):], 'wrong Item:Length', tmp)
        # 4. A gain map XMP without HDRCapacityMax.
        must_fail(text.replace(b'hdrgm:HDRCapacityMax=', b'hdrgm:HDRCapacityMaz='), 'missing HDRCapacityMax', tmp)
        # 5. Truncated file (gain map cut off).
        must_fail(text[:len(text) - 100], 'truncated gain map', tmp)
    print('Ultra HDR self-test PASS: streaming and in-memory containers valid, 5 broken variants rejected')


def scene_luma():
    """P370 (B3): ultrahdr/sceneluma.glsl with the render's luminance weights, ultrahdr/gainmap.glsl's P3 base."""
    import numpy as np
    import moderngl
    sh = ROOT / 'app/src/main/assets/shaders'

    def source(name):
        out = ['#version 300 es']
        for line in (sh / name).read_text(encoding='utf-8').splitlines():
            m = re.match(r'\s*#import\s+(\S+)', line)
            out.append((sh / 'utils' / ('import_' + m.group(1) + '.glsl')).read_text(encoding='utf-8') if m else line)
        return '\n'.join(out)
    try:
        ctx = moderngl.create_standalone_context(backend='egl', require=430)
    except Exception:
        ctx = moderngl.create_standalone_context(require=430)
    vs = '#version 300 es\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.-1.,0,1);}'
    luma = np.array([0.2126, 0.7152, 0.0722])

    def rows1(m):
        m = np.array(m, float).reshape(3, 3)
        return m / m.sum(1, keepdims=True)
    # the ISP routes (Parameters: CCT.matrix = the transform's rows normalised, sensorToProPhoto = diag(1 / n))
    cams = {'X8U': (rows1([200, -62, -10, -17, 172, -27, 8, -70, 191]), np.array([0.54785156, 1, 0.41796875])),
            'X200U': (rows1([153, -19, -6, -17, 175, -30, 0, -55, 184]), np.array([0.3945, 1, 0.6396]))}
    # ColorChecker patches (sRGB 8-bit) and greys; the camera's white-balanced RGB of each through the inverse of the matrix
    cc = np.array([[115, 82, 68], [194, 150, 130], [98, 122, 157], [87, 108, 67], [133, 128, 177], [103, 189, 170], [214, 126, 44],
                   [80, 91, 166], [193, 90, 99], [94, 60, 108], [157, 188, 64], [224, 163, 46], [56, 61, 150], [70, 148, 73],
                   [175, 54, 60], [231, 199, 31], [187, 86, 149], [8, 133, 161]]) / 255.0
    lin = np.where(cc <= 0.04045, cc / 12.92, ((cc + 0.055) / 1.055) ** 2.4)
    greys = np.repeat(np.array([[0.02], [0.18], [0.9], [3.0]]), 3, 1)
    failures = []

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
        r = np.frombuffer(out.read(), np.float32).reshape(size[1], size[0], 4).astype(np.float64)
        fb.release()
        out.release()
        return r

    def tex(a, dtype='f4'):
        a = np.ascontiguousarray(a, np.float32 if dtype == 'f4' else np.uint8)
        t = ctx.texture((a.shape[1], a.shape[0]), 4, a.tobytes(), dtype=dtype)
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        return t
    prog = ctx.program(vertex_shader=vs, fragment_shader=source('ultrahdr/sceneluma.glsl'))
    gain = tex(np.ones((2, 2, 4)))
    for name, (cct, n) in cams.items():
        s2p = np.diag(1 / n)
        m = cct @ s2p
        w = (luma @ m) * n                        # PostPipeline.sceneLumaWeights
        cam = np.concatenate([lin @ np.linalg.inv(cct).T, greys])   # white-balanced camera RGB the plane reads
        k = len(cam)
        img = np.zeros((1, k, 4))
        img[0, :, :3] = cam
        img[0, :, 3] = 1
        res = draw(prog, (k, 1), {'InputBuffer': tex(img), 'GainMap': gain},
                   {'rotate': 4, 'mirror': False, 'cropSize': (k, 1), 'rawSize': (k, 1), 'wU': tuple(w)})[0, :, 0]
        want = (cam * n) @ m.T @ luma            # Y of the render's linear sRGB
        err = np.abs(res[:len(lin)] / want[:len(lin)] - 1).max()
        gerr = np.abs(res[len(lin):] - greys[:, 0]).max() / greys[:, 0].max()
        old = draw(prog, (k, 1), {'InputBuffer': tex(img), 'GainMap': gain},
                   {'rotate': 4, 'mirror': False, 'cropSize': (k, 1), 'rawSize': (k, 1), 'wU': (0.0, 0.0, 0.0)})[0, :, 0]
        ev = np.log2(old[:len(lin)] / want[:len(lin)])
        print('%s: scene plane = the render\'s luminance within %.4f %% on %d ColorChecker colours, greys within %.1e; Rec.709 on '
              'camera RGB was %+.2f .. %+.2f EV (blue %+.2f, orange %+.2f)' % (name, 100 * err, len(lin), gerr, ev.min(), ev.max(),
                                                                         ev[12], ev[6]))
        if err > 0.01:
            failures.append('%s: the scene plane misses the render\'s luminance by %.2f %%' % (name, 100 * err))
        if gerr > 1e-6:
            failures.append('%s: a grey moved (%.1e)' % (name, gerr))
    # gainmap.glsl: the SDR luminance of a Display P3 base with the P3 weights (uP3 1), Rec.709 otherwise
    gprog = ctx.program(vertex_shader=vs, fragment_shader=source('ultrahdr/gainmap.glsl'))
    base = np.zeros((1, 3, 4), np.uint8)
    base[0, :, :3] = [[230, 60, 40], [40, 200, 60], [50, 70, 220]]
    base[0, :, 3] = 255
    decoded = base[0, :, :3] / 255.0
    lin_b = np.where(decoded <= 0.04045, decoded / 12.92, ((decoded + 0.055) / 1.055) ** 2.4)
    scene = np.full((1, 3, 4), 0.6)
    for p3, weights in ((1, np.array([0.2290, 0.6917, 0.0793])), (0, luma)):
        r = draw(gprog, (3, 1), {'InputBuffer': tex(base / 255.0), 'LBuffer': tex(scene)},
                 {'uAnchor': 1.0, 'uDown': 1, 'uScale': 4.0, 'uEps': 1 / 64, 'uP3': p3})[0, :, 0]
        sl = lin_b @ weights
        want = np.round(np.clip(np.log2((0.6 + 1 / 64) / (sl + 1 / 64)) * np.clip(((sl) / 0.02), 0, 1) ** 2 * (3 - 2 * np.clip(sl / 0.02, 0, 1)) / 4.0, 0, 1) * 255) / 255
        if np.abs(r - want).max() > 1.5 / 255:
            failures.append('gainmap.glsl uP3 %d: the SDR luminance is not the %s one (%s against %s)' % (p3, 'P3' if p3 else 'Rec.709', r, want))
    print('gainmap.glsl: the SDR luminance of a Display P3 base takes the P3 weights (uP3 1), of an sRGB base Rec.709')
    ctx.release()
    for f in failures:
        print('FAIL: ' + f)
    if failures:
        raise SystemExit('Ultra HDR scene luma FAIL (%d)' % len(failures))
    print('Ultra HDR scene luma PASS')


def main(argv):
    if argv == ['--scene-luma']:
        scene_luma()
        return 0
    if not argv or argv == ['--self-test']:
        self_test()
        return 0
    strict = '--strict' in argv
    failed = 0
    for path in [a for a in argv if a != '--strict']:
        try:
            check_file(path, strict=strict)
        except Bad as e:
            failed += 1
            print('%s: NOT a valid Ultra HDR JPEG: %s' % (path, e))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
