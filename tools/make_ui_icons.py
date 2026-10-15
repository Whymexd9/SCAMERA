"""P136: the UI icons in the style of the launcher icon (python tools/make_ui_icons.py [--preview DIR] [--write] [--check]).

Owner, 2026-10-15: «Переделай стиль иконок настроек под подобный стиль» (the P131b launcher icon: a light filled body with a
soft vertical gradient, dark inner parts, coral details) and «В шторке и видоискателе тоже сделай иконки в таком стиле».

One geometry per icon on a 24-unit viewport, built from primitives:
  body    - filled with the light gradient (the shade and the top bar tint the whole glyph with SRC_IN: then every filled
            part takes the tint, so the glyph must still read as a silhouette);
  hole    - cut out of the body (reverse winding under the non-zero rule; a hole must lie inside exactly one body shape):
            the card shows through - the dark inner parts of the launcher icon, and the details of the tinted silhouette;
  accent  - filled with ?android:attr/colorAccent (the accent theme of the activity: «Коралловый» by default);
  second  - the grey of the launcher icon's back card (#7E8491), for things behind;
  dim     - the body at 40 % opacity (a thing that is off, the back card of a pair): it stays dimmer when tinted.
Every shape is written as a polygon (circles and round caps as 48-gons), so the winding is exact, and drawn with the same
non-zero rule by PIL for the previews.
"""
import math
import os
import sys

try:   # only the previews need them; --check and --write run on a bare python3
    import numpy as np
    from PIL import Image, ImageDraw
except ImportError:
    np = Image = ImageDraw = None

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable') + os.sep
LIB_RES = os.path.join(ROOT, 'circularbarlib', 'src', 'main', 'res', 'drawable') + os.sep
BODY_TOP, BODY_BOTTOM = (0xF3, 0xF4, 0xF8), (0xC5, 0xC9, 0xD2)
ACCENT = (0xFF, 0xA9, 0x9E)
SECOND = (0x7E, 0x84, 0x91)
CARD = (0x1B, 0x20, 0x23)
N = 48


# ---------------------------------------------------------------- primitives: lists of polygons (counter-clockwise in screen y)

def _ccw(pts):
    a = 0.0
    for i in range(len(pts)):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % len(pts)]
        a += x1 * y2 - x2 * y1
    return pts if a > 0 else pts[::-1]


def circle(cx, cy, r, n=N):
    return [_ccw([(cx + r * math.cos(2 * math.pi * i / n), cy + r * math.sin(2 * math.pi * i / n)) for i in range(n)])]


def rrect(x, y, w, h, r):
    r = min(r, w / 2, h / 2)
    pts = []
    for cx, cy, a0 in ((x + w - r, y + r, -90), (x + w - r, y + h - r, 0), (x + r, y + h - r, 90), (x + r, y + r, 180)):
        for i in range(13):
            a = math.radians(a0 + 90 * i / 12)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return [_ccw(pts)]


def poly(*pts):
    return [_ccw(list(pts))]


def capsule(x1, y1, x2, y2, w):
    """A line of width w with round caps."""
    r = w / 2
    a = math.atan2(y2 - y1, x2 - x1)
    pts = []
    for i in range(N // 2 + 1):
        t = a + math.pi / 2 + math.pi * i / (N // 2)
        pts.append((x1 + r * math.cos(t), y1 + r * math.sin(t)))
    for i in range(N // 2 + 1):
        t = a - math.pi / 2 + math.pi * i / (N // 2)
        pts.append((x2 + r * math.cos(t), y2 + r * math.sin(t)))
    return [_ccw(pts)]


def polyline(pts, w):
    out = []
    for i in range(len(pts) - 1):
        out += capsule(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], w)
    return out


def arc(cx, cy, r, w, a0, a1, caps=True):
    """A band of width w along the circle r from angle a0 to a1 (degrees, screen y down: 90 = below), round caps."""
    n = max(8, int(abs(a1 - a0) / 6))
    outer = [(cx + (r + w / 2) * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
              cy + (r + w / 2) * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]
    inner = [(cx + (r - w / 2) * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
              cy + (r - w / 2) * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]
    out = [_ccw(outer + inner[::-1])]
    if caps:
        for a in (a0, a1):
            out += circle(cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a)), w / 2, 24)
    return out


def ring(cx, cy, r, w):
    return arc(cx, cy, r, w, 0, 359.99, caps=False)


def gear(cx, cy, r_out, r_in, teeth, tooth=0.5):
    pts = []
    k = 6
    for t in range(teeth):
        base = 2 * math.pi * t / teeth
        span = 2 * math.pi / teeth
        for i in range(k + 1):   # tooth top
            a = base - span * tooth / 2 + span * tooth * i / k
            pts.append((cx + r_out * math.cos(a), cy + r_out * math.sin(a)))
        for i in range(k + 1):   # valley
            a = base + span * tooth / 2 + span * (1 - tooth) * i / k
            pts.append((cx + r_in * math.cos(a), cy + r_in * math.sin(a)))
    return [_ccw(pts)]


def rotate(polys, cx, cy, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return [[(cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c) for x, y in p] for p in polys]


# ---------------------------------------------------------------- an icon: parts by role

class Icon:
    def __init__(self, name, note, res=None):
        self.name, self.note, self.res = name, note, res or RES
        self.body, self.holes, self.accent, self.second, self.accent_holes = [], [], [], [], []
        self.dim, self.dim_holes = [], []

    def b(self, *ps):
        for p in ps:
            self.body += p
        return self

    def h(self, *ps):
        for p in ps:
            self.holes += p
        return self

    def a(self, *ps):
        for p in ps:
            self.accent += p
        return self

    def ah(self, *ps):
        """Holes of the accent shape (e.g. an accent ring)."""
        for p in ps:
            self.accent_holes += p
        return self

    def s(self, *ps):
        for p in ps:
            self.second += p
        return self

    def d(self, *ps):
        for p in ps:
            self.dim += p
        return self

    def dh(self, *ps):
        for p in ps:
            self.dim_holes += p
        return self


def _d(polys, holes=()):
    out = []
    for p in polys:
        out.append('M' + ' L'.join('%.2f,%.2f' % (x, y) for x, y in p) + ' Z')
    for p in holes:
        q = p[::-1]
        out.append('M' + ' L'.join('%.2f,%.2f' % (x, y) for x, y in q) + ' Z')
    return ' '.join(out)


def vector(icon, accent_attr='?android:attr/colorAccent', accent_hex=None, mono=False, size=24):
    """Android vector XML. mono: one white silhouette (body + second + accent, holes cut) for a tinted / themed context."""
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- P136: generated by tools/make_ui_icons.py - %s. -->' % icon.note,
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    xmlns:aapt="http://schemas.android.com/aapt"',
             '    android:width="%ddp"' % size, '    android:height="%ddp"' % size,
             '    android:viewportWidth="24"', '    android:viewportHeight="24">']
    if mono:
        lines.append('    <path android:fillColor="#FFFFFFFF" android:pathData="%s" />' % _d(icon.second + icon.body + icon.accent,
                                                                                         icon.holes + icon.accent_holes))
    else:
        if icon.second:
            lines.append('    <path android:fillColor="#FF7E8491" android:pathData="%s" />' % _d(icon.second))
        if icon.dim:
            lines.append('    <path android:fillColor="#FFDCDEE5" android:fillAlpha="0.4" android:pathData="%s" />' % _d(icon.dim, icon.dim_holes))
        if icon.body:
            lines += ['    <path android:pathData="%s">' % _d(icon.body, icon.holes),
                      '        <aapt:attr name="android:fillColor">',
                      '            <gradient android:type="linear" android:startX="12" android:startY="2" android:endX="12"'
                      ' android:endY="22" android:startColor="#FFF3F4F8" android:endColor="#FFC5C9D2" />',
                      '        </aapt:attr>', '    </path>']
        if icon.accent:
            colour = accent_hex or accent_attr
            lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (colour, _d(icon.accent, icon.accent_holes)))
    lines.append('</vector>')
    return '\n'.join(lines) + '\n'


# ---------------------------------------------------------------- preview (the same non-zero rule)

def _mask(polys, holes, size, ss):
    acc = np.zeros((size * ss, size * ss), np.int16)
    for p, sign in [(p, 1) for p in polys] + [(q, -1) for q in holes]:
        m = Image.new('L', (size * ss, size * ss), 0)
        ImageDraw.Draw(m).polygon([(x * size * ss / 24, y * size * ss / 24) for x, y in p], fill=1)
        acc += sign * np.array(m, np.int16)
    return acc != 0


def render(icon, size=72, ss=6, tint=None, bg=CARD):
    s = size * ss
    img = np.zeros((s, s, 3), np.float32)
    img[:] = bg
    layers = [(icon.second, [], SECOND, 1.0), (icon.dim, icon.dim_holes, (0xDC, 0xDE, 0xE5), 0.4), (icon.body, icon.holes, 'body', 1.0),
              (icon.accent, icon.accent_holes, ACCENT, 1.0)]
    for polys, holes, colour, alpha in layers:
        if not polys:
            continue
        m = _mask(polys, holes, size, ss)
        if alpha < 1.0:
            c = np.array(tint if tint is not None else colour, np.float32)
            img[m] = img[m] * (1 - alpha) + c * alpha
        elif tint is not None:
            img[m] = tint
        elif colour == 'body':
            t = np.clip((np.arange(s) / s * 24 - 2) / 20, 0, 1)[:, None]
            grad = np.array(BODY_TOP)[None, :] * (1 - t) + np.array(BODY_BOTTOM)[None, :] * t
            img[m] = np.broadcast_to(grad[:, None, :], (s, s, 3))[m]
        else:
            img[m] = colour
    return Image.fromarray(img.astype(np.uint8)).resize((size, size), Image.LANCZOS)


def sheet(icons, path, tint=None, bg=CARD):
    cols, cell = 8, 104
    rows = (len(icons) + cols - 1) // cols
    out = Image.new('RGB', (cols * cell, rows * cell), bg)
    d = ImageDraw.Draw(out)
    for i, ic in enumerate(icons):
        x, y = (i % cols) * cell, (i // cols) * cell
        out.paste(render(ic, 72, tint=tint, bg=bg), (x + 16, y + 6))
        d.text((x + 6, y + cell - 18), ic.name.replace('settings_ic_', '').replace('ic_sheet_', '').replace('ic_shade_', ''), fill=(138, 147, 160))
    out.save(path)


# ---------------------------------------------------------------- glyphs

ICONS = []


def icon(name, note, res=None):
    i = Icon(name, note, res)
    ICONS.append(i)
    return i


# --- settings concepts (settings_ic_*): the rows of the settings, the Java pages and (tinted) the shade tiles

def _aperture():
    """A lens disc: the hexagonal opening and the six blade edges running on from its sides to the rim."""
    ic = icon('settings_ic_aperture', 'aperture: a lens disc with the iris opening and the blade edges').b(circle(12, 12, 9.6))
    v = [(12 + 4.4 * math.cos(math.radians(60 * i + 15)), 12 + 4.4 * math.sin(math.radians(60 * i + 15))) for i in range(6)]
    ic.h(poly(*v))
    for i in range(6):
        (x0, y0), (x1, y1) = v[i], v[(i + 1) % 6]
        dx, dy = x1 - x0, y1 - y0
        n = math.hypot(dx, dy)
        dx, dy = dx / n, dy / n
        ic.h(capsule(x1 + 1.3 * dx, y1 + 1.3 * dy, x1 + 4.4 * dx, y1 + 4.4 * dy, 1.0))
    ic.a(circle(12, 12, 1.7))


_aperture()
icon('settings_ic_back', 'back: an arrow to the left').b(capsule(5.5, 12, 19.5, 12, 2.8), capsule(5.5, 12, 11.2, 6.3, 2.8),
                                                        capsule(5.5, 12, 11.2, 17.7, 2.8))
icon('settings_ic_camera', 'camera: the launcher camera - light body, dark lens with an accent ring, shutter and LED') \
    .a(rrect(6.2, 4.4, 4.6, 3.2, 1.0)).b(rrect(2.6, 7, 18.8, 13.6, 3.6)).h(circle(12, 13.6, 4.9)) \
    .a(circle(12, 13.6, 3.7)).ah(circle(12, 13.6, 2.6)).a(circle(17.6, 9.8, 1.05))
icon('settings_ic_chevron', 'chevron: forward').b(capsule(9.2, 5.5, 15.6, 12, 2.8), capsule(15.6, 12, 9.2, 18.5, 2.8))
icon('settings_ic_chip', 'chip: a processor with pins and an accent core') \
    .b(rrect(5.6, 5.6, 12.8, 12.8, 2.6), *[capsule(x, 2.6, x, 5.8, 1.7) for x in (8.6, 12, 15.4)],
       *[capsule(x, 18.2, x, 21.4, 1.7) for x in (8.6, 12, 15.4)], *[capsule(2.6, y, 5.8, y, 1.7) for y in (8.6, 12, 15.4)],
       *[capsule(18.2, y, 21.4, y, 1.7) for y in (8.6, 12, 15.4)]) \
    .h(rrect(8.2, 8.2, 7.6, 7.6, 1.4)).a(rrect(9.8, 9.8, 4.4, 4.4, 1.0))
icon('settings_ic_config', 'config: a memory card with its shutter and label') \
    .b(rrect(3.4, 3.4, 17.2, 17.2, 3.2)).h(rrect(7.2, 5.4, 9.6, 5.2, 1.0)).a(rrect(13.2, 6.2, 2.2, 3.6, 0.6)) \
    .h(rrect(6.4, 13.2, 11.2, 5.6, 1.2))
icon('settings_ic_copy', 'copy: a card in front of another').s(rrect(8.2, 2.8, 13, 13, 2.8)).b(rrect(2.8, 8.2, 13, 13, 2.8)) \
    .h(rrect(5.4, 10.8, 7.8, 7.8, 1.4)).a(circle(9.3, 14.7, 1.7))
icon('settings_ic_corner', 'corner: a rounded frame with the corner in the accent') \
    .b(rrect(3, 3, 18, 18, 7)).h(rrect(5.9, 5.9, 12.2, 12.2, 4.3)).a(arc(10, 10, 5.9, 2.6, 180, 270, caps=False))
icon('settings_ic_diag', 'diagnostics: axes and an accent chart line') \
    .b(capsule(4, 3.8, 4, 20, 2.6), capsule(4, 20, 20.4, 20, 2.6)).a(polyline([(7.6, 15.6), (11, 10.6), (14, 13.4), (19.2, 6.6)], 2.6))
icon('settings_ic_edit', 'edit: a pencil with an accent cap') \
    .b(poly((6.9, 19.4), (4.6, 19.4), (4.6, 17.1), (14.9, 6.8), (17.2, 9.1))).a(poly((15.9, 5.8), (17.6, 4.1), (19.9, 6.4), (18.2, 8.1)))
icon('settings_ic_eye', 'eye: an almond with a dark iris and an accent pupil') \
    .b(poly(*([(2.4 + 19.2 * t / 40, 12 - 6.6 * math.sin(math.pi * t / 40)) for t in range(41)]
              + [(21.6 - 19.2 * t / 40, 12 + 6.6 * math.sin(math.pi * t / 40)) for t in range(1, 40)]))) \
    .h(circle(12, 12, 4.0)).a(circle(12, 12, 2.4))
icon('settings_ic_face', 'face: a face with accent eyes and a cut-out smile') \
    .b(circle(12, 12, 9.6)).h(circle(8.7, 9.9, 1.9), circle(15.3, 9.9, 1.9), arc(12, 12.4, 4.6, 1.9, 25, 155)) \
    .a(circle(8.7, 9.9, 1.05), circle(15.3, 9.9, 1.05))
icon('settings_ic_flash', 'flash: a lightning bolt').b(poly((14.4, 2), (4.8, 13.4), (11.2, 13.4), (9.6, 22), (19.2, 10), (12.9, 10), (15.2, 2)))
icon('settings_ic_frames', 'frames: a stack of photos').s(rrect(7.6, 2.8, 13.6, 13.6, 2.8)).b(rrect(2.8, 7.6, 13.6, 13.6, 2.8)) \
    .h(rrect(5.3, 10.1, 8.6, 8.6, 1.4)).a(circle(9.6, 14.4, 2.0))
icon('settings_ic_gear', 'gear: the settings gear with an accent hub').b(gear(12, 12, 10.2, 7.6, 8, 0.46)).h(circle(12, 12, 3.7)) \
    .a(circle(12, 12, 2.3))
grid = icon('settings_ic_grid', 'grid: a 3x3 frame with the centre cell in the accent').b(rrect(3, 3, 18, 18, 3))
cell = (18 - 2 * 2.0 - 2 * 1.6) / 3
for r in range(3):
    for c in range(3):
        x, y = 3 + 2.0 + c * (cell + 1.6), 3 + 2.0 + r * (cell + 1.6)
        grid.h(rrect(x, y, cell, cell, 0.7))
grid.a(rrect(3 + 2.0 + cell + 1.6 + 0.6, 3 + 2.0 + cell + 1.6 + 0.6, cell - 1.2, cell - 1.2, 0.5))
icon('settings_ic_hdr', 'HDR: two windows, minus and plus in the accent') \
    .b(rrect(2.4, 5, 19.2, 14, 3.2)).h(rrect(4.6, 7.2, 6.8, 9.6, 1.4), rrect(12.6, 7.2, 6.8, 9.6, 1.4)) \
    .a(capsule(6.3, 12, 9.7, 12, 1.8), capsule(14.3, 12, 17.7, 12, 1.8), capsule(16, 10.3, 16, 13.7, 1.8))
icon('settings_ic_info', 'info: a disc with the i cut out and an accent dot') \
    .b(circle(12, 12, 9.6)).h(capsule(12, 11, 12, 17, 2.6), circle(12, 7.3, 1.95)).a(circle(12, 7.3, 1.05))
icon('settings_ic_layers', 'layers: three stacked sheets, the middle one in the accent') \
    .s(poly((12, 11.4), (21.2, 15.8), (12, 20.4), (2.8, 15.8))).a(poly((12, 7.6), (21.2, 12), (12, 16.6), (2.8, 12))) \
    .b(poly((12, 3.6), (21.2, 8.1), (12, 12.6), (2.8, 8.1)))
icon('settings_ic_list', 'list: three lines with accent bullets') \
    .a(circle(4.8, 6, 1.75), circle(4.8, 12, 1.75), circle(4.8, 18, 1.75)) \
    .b(capsule(9.2, 6, 20.2, 6, 2.6), capsule(9.2, 12, 20.2, 12, 2.6), capsule(9.2, 18, 20.2, 18, 2.6))
icon('settings_ic_lock', 'lock: a padlock with a dark keyhole and an accent pin') \
    .b(arc(12, 10.6, 4.7, 2.6, 180, 360, caps=False), capsule(7.3, 10.6, 7.3, 11.6, 2.6), capsule(16.7, 10.6, 16.7, 11.6, 2.6),
       rrect(4.4, 10.4, 15.2, 11.2, 3.0)).h(circle(12, 15.6, 2.0)).a(circle(12, 15.6, 1.05))
icon('settings_ic_merge', 'merge: two strokes meet in an accent point').b(capsule(5.6, 3.8, 12, 12.4, 2.8), capsule(18.4, 3.8, 12, 12.4, 2.8),
                                                                        capsule(12, 12.4, 12, 20.6, 2.8)).a(circle(12, 12.4, 2.6))
icon('settings_ic_mosaic', 'mosaic: four tiles, one in the accent').b(rrect(3, 3, 8.2, 8.2, 2.2), rrect(12.8, 3, 8.2, 8.2, 2.2),
                                                                     rrect(3, 12.8, 8.2, 8.2, 2.2)).a(rrect(12.8, 12.8, 8.2, 8.2, 2.2))
icon('settings_ic_noise', 'noise: a waveform with an accent peak') \
    .b(polyline([(2.6, 12), (6, 12), (8.2, 7), (10.6, 17), (13, 8.6), (15.2, 14), (16.8, 12), (21.4, 12)], 2.4)).a(circle(8.2, 7, 1.9))
icon('settings_ic_palette', 'palette: a palette with a thumb hole and paint dots') \
    .b(circle(12, 12, 9.6)).h(circle(15.6, 15.8, 2.2), circle(7.8, 10, 2.2), circle(11.6, 6.6, 2.2), circle(16, 9.2, 2.2), circle(7.8, 15, 2.0)) \
    .a(circle(7.8, 10, 1.3), circle(16, 9.2, 1.3)).s(circle(11.6, 6.6, 1.3), circle(7.8, 15, 1.2))
icon('settings_ic_ui', 'interface: a window with title-bar dots, an accent switch and rows') \
    .b(rrect(2.4, 3.4, 19.2, 17.2, 3.4)).h(rrect(4.6, 8.4, 14.8, 10.0, 1.6)) \
    .a(circle(5.9, 5.9, 0.95)).h(circle(8.7, 5.9, 0.95), circle(11.5, 5.9, 0.95)) \
    .a(rrect(6.2, 10.0, 6.8, 3.4, 1.7)).s(circle(11.3, 11.7, 1.15)) \
    .s(capsule(14.8, 11.7, 17.6, 11.7, 1.4), capsule(6.6, 15.8, 17.6, 15.8, 1.4))
icon('settings_ic_ratio', 'ratio: a frame with an accent bar of the image').b(rrect(2.4, 5.6, 19.2, 12.8, 3.2)) \
    .h(rrect(4.9, 8.1, 14.2, 7.8, 1.6)).a(rrect(7, 10.3, 10, 3.4, 1.2))
def _reset():
    """A circular arrow: the band from the upper right round to the upper left, the head at its end (the warning colour)."""
    cx, cy, r, a1 = 12, 12.8, 7.2, 215
    ic = icon('settings_ic_reset', 'reset: a circular arrow (drawn in the warning colour)').b(arc(cx, cy, r, 2.8, -40, a1 - 12))
    a = math.radians(a1)
    px, py = cx + r * math.cos(a), cy + r * math.sin(a)
    tx, ty = -math.sin(a), math.cos(a)          # direction of travel (increasing angle)
    nx, ny = math.cos(a), math.sin(a)
    ic.b(poly((px + 4.0 * tx, py + 4.0 * ty), (px - 3.6 * nx - 0.6 * tx, py - 3.6 * ny - 0.6 * ty),
              (px + 3.6 * nx - 0.6 * tx, py + 3.6 * ny - 0.6 * ty)))


_reset()
icon('settings_ic_restore', 'restore: an arrow up out of an accent tray') \
    .b(capsule(12, 16.2, 12, 4.6, 2.8), capsule(12, 4.4, 7.2, 9.2, 2.8), capsule(12, 4.4, 16.8, 9.2, 2.8)).a(capsule(4, 20.2, 20, 20.2, 2.8))
icon('settings_ic_save', 'save: an arrow down into an accent tray') \
    .b(capsule(12, 3.8, 12, 15.4, 2.8), capsule(12, 15.6, 7.2, 10.8, 2.8), capsule(12, 15.6, 16.8, 10.8, 2.8)).a(capsule(4, 20.2, 20, 20.2, 2.8))
icon('settings_ic_search', 'search: a magnifier with an accent handle').b(ring(10.4, 10.4, 6.0, 2.8)).a(capsule(15.2, 15.2, 20.4, 20.4, 3.2))
icon('settings_ic_sharp', 'sharpness: a triangle with an accent mark').b(poly((12, 2.6), (21.8, 20.2), (2.2, 20.2))) \
    .h(poly((12, 7.6), (17.6, 17.8), (6.4, 17.8))).a(capsule(12, 10.6, 12, 13.6, 1.7), circle(12, 15.8, 1.0))
icon('settings_ic_sliders', 'sliders: three tracks with accent knobs') \
    .b(capsule(3.6, 6, 20.4, 6, 2.2), capsule(3.6, 12, 20.4, 12, 2.2), capsule(3.6, 18, 20.4, 18, 2.2)) \
    .a(circle(15.6, 6, 2.7), circle(8.4, 12, 2.7), circle(13.6, 18, 2.7))
icon('settings_ic_sound', 'sound: a speaker with accent waves').b(rrect(3, 8.8, 5.4, 6.4, 1.2), poly((7.2, 9.2), (12.8, 4.2), (12.8, 19.8), (7.2, 14.8))) \
    .a(arc(13.2, 12, 3.4, 2.0, -55, 55), arc(13.2, 12, 7.0, 2.0, -55, 55))
icon('settings_ic_sun', 'sun: a light disc with accent rays').b(circle(12, 12, 4.4)) \
    .a(*[capsule(12 + 6.9 * math.cos(math.radians(45 * i)), 12 + 6.9 * math.sin(math.radians(45 * i)),
                 12 + 9.5 * math.cos(math.radians(45 * i)), 12 + 9.5 * math.sin(math.radians(45 * i)), 2.2) for i in range(8)])
icon('settings_ic_tag', 'tag: a label with a dark hole and an accent eyelet') \
    .b(rotate(rrect(5.2, 6.6, 15.4, 10.8, 2.6), 12, 12, -45), rotate(poly((5.2, 6.6), (5.2, 17.4), (1.6, 12)), 12, 12, -45)) \
    .h(circle(15.4, 8.6, 2.0)).a(circle(15.4, 8.6, 1.05))
icon('settings_ic_toggle', 'toggle: a switch with an accent knob').b(rrect(2.2, 6.4, 19.6, 11.2, 5.6)).h(circle(16.2, 12, 4.1)) \
    .a(circle(16.2, 12, 3.0))
icon('settings_ic_view', 'viewfinder: a phone with an accent screen').b(rrect(5.8, 2.4, 12.4, 19.2, 3.2)).h(rrect(7.9, 5, 8.2, 12.2, 1.3)) \
    .a(rrect(9.4, 6.6, 5.2, 9, 0.9)).h(capsule(10.6, 19.2, 13.4, 19.2, 1.3))
icon('settings_ic_water', 'watermark: mountains with an accent sun').b(poly((3.4, 18.4), (8.8, 9.2), (11.6, 13.4), (14.6, 6.8), (20.6, 18.4)),
                                                                     capsule(3.6, 20.2, 20.4, 20.2, 2.2)).a(circle(6.6, 6.4, 2.0))
icon('settings_ic_zoom', 'zoom: a magnifier with an accent plus').b(ring(10.4, 10.4, 6.0, 2.8), capsule(15.2, 15.2, 20.4, 20.4, 3.2)) \
    .a(capsule(7.8, 10.4, 13, 10.4, 1.9), capsule(10.4, 7.8, 10.4, 13, 1.9))

# --- helpers of the shade / top bar / manual glyphs (tinted with SRC_IN there: silhouettes with cut-outs)

def frame(ic, x, y, w, h, r, t):
    """A rounded frame: the body with the inside cut out."""
    return ic.b(rrect(x, y, w, h, r)).h(rrect(x + t, y + t, w - 2 * t, h - 2 * t, max(r - t, 0.8)))


def xf(polys, s, dx, dy):
    return [[(x * s + dx, y * s + dy) for x, y in p] for p in polys]


def ellipse(cx, cy, rx, ry, n=N):
    return [_ccw([(cx + rx * math.cos(2 * math.pi * i / n), cy + ry * math.sin(2 * math.pi * i / n)) for i in range(n)])]


def lens(x0, y0, x1, y1, bulge):
    """A leaf / lens between two points: two circular arcs bulging by `bulge` on each side."""
    mx, my = (x0 + x1) / 2, (y0 + y1) / 2
    dx, dy = x1 - x0, y1 - y0
    L = math.hypot(dx, dy)
    nx, ny = -dy / L, dx / L
    pts = []
    for i in range(25):
        t = i / 24
        b = bulge * math.sin(math.pi * t)
        pts.append((x0 + dx * t + nx * b, y0 + dy * t + ny * b))
    for i in range(1, 24):
        t = 1 - i / 24
        b = bulge * math.sin(math.pi * t)
        pts.append((x0 + dx * t - nx * b, y0 + dy * t - ny * b))
    return [_ccw(pts)]


BOLT = poly((14.4, 2), (4.8, 13.4), (11.2, 13.4), (9.6, 22), (19.2, 10), (12.9, 10), (15.2, 2))
BRACKETS = polyline([(3.4, 8.4), (3.4, 3.4), (8.4, 3.4)], 2.4) + polyline([(15.6, 3.4), (20.6, 3.4), (20.6, 8.4)], 2.4) \
    + polyline([(20.6, 15.6), (20.6, 20.6), (15.6, 20.6)], 2.4) + polyline([(8.4, 20.6), (3.4, 20.6), (3.4, 15.6)], 2.4)


def stopwatch(name, note):
    ic = icon(name, note).b(circle(12, 13.4, 8.6), capsule(9.8, 2.6, 14.2, 2.6, 2.2), capsule(12, 2.6, 12, 5.4, 2.2),
                            capsule(18.3, 6.7, 19.6, 5.4, 2.0)).h(circle(12, 13.4, 6.4))
    return ic


def grid_icon(name, note, n):
    ic = icon(name, note).b(rrect(3, 3, 18, 18, 3))
    outer, inner = 2.0, 1.5
    c = (18 - 2 * outer - (n - 1) * inner) / n
    for r in range(n):
        for k in range(n):
            ic.h(rrect(3 + outer + k * (c + inner), 3 + outer + r * (c + inner), c, c, 0.6))
    return ic


def film(ic, x, y, w, h, dim=False):
    """A film frame: the window and the sprocket holes of both borders."""
    add, cut = (ic.d, ic.dh) if dim else (ic.b, ic.h)
    border = w * 0.23
    add(rrect(x, y, w, h, 2.4))
    cut(rrect(x + border, y + h * 0.13, w - 2 * border, h * 0.74, 1.0))
    k = 4
    for i in range(k):
        yy = y + h * (0.12 + 0.76 * i / (k - 1))
        for xx in (x + border * 0.32, x + w - border * 0.68):
            cut(rrect(xx, yy - h * 0.045, border * 0.36, h * 0.09, 0.3))


def jpeg_parts():
    return dict(frame=(2.8, 4, 18.4, 16, 3.2, 2.3), hills=poly((5.4, 17.6), (9.8, 11), (12.6, 14.6), (15.2, 10.8), (18.6, 17.6)),
                sun=circle(8.4, 8.8, 1.7))


def gem_parts():
    return dict(gem=poly((7.4, 4), (16.6, 4), (20.8, 9.2), (12, 20.6), (3.2, 9.2)),
                cut=[poly((6.6, 10.6), (17.4, 10.6), (12, 17.4)), rrect(5.6, 8.0, 12.8, 1.1, 0.5)])


def leaf_parts():
    return dict(frame=(2.8, 2.8, 18.4, 18.4, 3.4, 2.3), leaf=lens(7.2, 16.8, 17.0, 7.0, 3.4), vein=capsule(8.8, 15.2, 15.2, 8.8, 0.9),
                stem=capsule(6.2, 17.8, 8.4, 15.6, 1.8))


def globe_parts():
    return [ring(12, 12, 8.3, 2.3)] + [ellipse(12, 12, 4.6, 9.45)], [ellipse(12, 12, 2.7, 7.15)], capsule(3.8, 12, 20.2, 12, 2.0)


# --- quick-settings shade
icon('ic_flash_on', 'flash on: a lightning bolt').b(BOLT)
icon('ic_flash_off', 'flash off: a dimmed bolt crossed out').d(BOLT).b(capsule(4, 4, 20, 20, 2.6))
icon('ic_torch', 'torch: a flashlight with its light').b(poly((5.6, 8.2), (18.4, 8.2), (15.2, 13), (8.8, 13)), rrect(8.8, 12.4, 6.4, 9.6, 1.8)) \
    .h(circle(12, 16.6, 1.3)).a(capsule(12, 2.2, 12, 4.8, 2.0), capsule(6.4, 3.4, 7.8, 5.6, 2.0), capsule(17.6, 3.4, 16.2, 5.6, 2.0))
stopwatch('ic_sheet_timer', 'timer: a stopwatch').b(capsule(12, 13.4, 12, 9.2, 2.2), circle(12, 13.4, 1.6))
stopwatch('ic_timeroff', 'timer off: a stopwatch with its hands at rest').b(capsule(12, 13.4, 12, 9.4, 2.0), capsule(12, 13.4, 15.2, 13.4, 2.0))
stopwatch('ic_timer3s', 'timer 3 s: a stopwatch with a 3').b(capsule(10, 9.9, 13.6, 9.9, 1.8), capsule(13.6, 9.9, 11.9, 12.4, 1.8),
                                                             arc(12, 14.7, 2.3, 1.8, -105, 150))
stopwatch('ic_timer10s', 'timer 10 s: a stopwatch with a 10').b(capsule(8.6, 10.6, 9.8, 9.6, 1.7), capsule(9.8, 9.6, 9.8, 17.2, 1.7),
                                                                ellipse(14.1, 13.4, 2.65, 3.9)).h(ellipse(14.1, 13.4, 1.0, 2.25))
grid_icon('ic_grid_3x3', 'grid 3x3', 3)
grid_icon('ic_grid_4x4', 'grid 4x4', 4)
frame(icon('ic_grid_diagonal', 'grid: diagonals'), 3, 3, 18, 18, 3, 2.0).b(capsule(4.6, 4.6, 19.4, 19.4, 1.8), capsule(19.4, 4.6, 4.6, 19.4, 1.8))
frame(icon('ic_grid_golden', 'grid: golden ratio'), 3, 3, 18, 18, 3, 2.0).b(capsule(9.9, 4.2, 9.9, 19.8, 1.8), capsule(9.9, 9.9, 19.8, 9.9, 1.8),
                                                                              capsule(15.2, 9.9, 15.2, 19.8, 1.6))
g_off = icon('ic_sheet_grid_off', 'grid off: a dimmed grid crossed out').d(rrect(3, 3, 18, 18, 3))
for _r in range(3):
    for _k in range(3):
        g_off.dh(rrect(5 + _k * 5.17, 5 + _r * 5.17, 3.67, 3.67, 0.6))
g_off.b(capsule(3.6, 3.6, 20.4, 20.4, 2.6))
icon('ic_sheet_metering', 'centre metering: focus brackets and a centre spot').b(BRACKETS, circle(12, 12, 3.1)).h(circle(12, 12, 1.4))
icon('ic_shade_focus', 'focus peaking: focus brackets and a ring').b(BRACKETS, ring(12, 12, 3.4, 2.0)).a(circle(12, 12, 1.1))
icon('ic_shade_meter', 'exposure metering: a ring, its marks and the spot').b(ring(12, 12, 8.3, 2.4), circle(12, 12, 2.5),
                                                                                *[capsule(12 + 4.6 * math.cos(math.radians(90 * i)), 12 + 4.6 * math.sin(math.radians(90 * i)),
                                                                                          12 + 5.8 * math.cos(math.radians(90 * i)), 12 + 5.8 * math.sin(math.radians(90 * i)), 1.8) for i in range(4)])
frame(icon('ic_sheet_downsampler', 'downsampler: a frame and an arrow out of its corner'), 3, 3, 12.4, 12.4, 2.6, 2.2) \
    .b(capsule(11.6, 11.6, 20, 20, 2.6), capsule(20, 20, 20, 14.8, 2.6), capsule(20, 20, 14.8, 20, 2.6))
PIN = [rrect(8, 2.6, 8, 3.2, 1.4), poly((9, 5.4), (15, 5.4), (17.2, 13), (6.8, 13)), rrect(5.4, 12.4, 13.2, 2.6, 1.3)]
icon('ic_sheet_pin_on', 'pinned: a filled pushpin').b(*PIN, capsule(12, 14.8, 12, 21.4, 2.0))
icon('ic_sheet_pin', 'not pinned: a hollow pushpin').b(*PIN, capsule(12, 14.8, 12, 21.4, 2.0)).h(poly((10.5, 7.2), (13.5, 7.2), (14.8, 11.6), (9.2, 11.6)))
icon('ic_shade_plus', 'plus: the «Добавить» tile').b(capsule(12, 4.4, 12, 19.6, 2.8), capsule(4.4, 12, 19.6, 12, 2.8))
icon('ic_shade_slash', 'strike-through over the icon of a switch tile that is off').b(capsule(3.6, 3.6, 20.4, 20.4, 2.4))
_j = jpeg_parts()
frame(icon('ic_shade_jpeg', 'JPEG: a picture frame with hills and the sun'), *_j['frame']).b(_j['hills']).a(_j['sun'])
film(icon('ic_shade_raw', 'RAW: a film frame with its sprocket holes'), 3.4, 2.6, 17.2, 18.8)
_l = leaf_parts()
frame(icon('ic_shade_heic', 'HEIC: a frame with a leaf (efficient compression)'), *_l['frame']).b(_l['leaf'], _l['stem']).h(_l['vein'])
_gb, _gh, _eq = globe_parts()
icon('ic_shade_webp', 'WebP: a globe').b(*_gb, _eq).h(*_gh)
_g = gem_parts()
icon('ic_shade_avif', 'AVIF: a cut gem (the highest-quality format)').b(_g['gem']).h(*_g['cut'])


def pair(name, note, front):
    """RAW + a format: the film frame dimmed behind, the format's glyph in front (smaller, bottom left)."""
    ic = icon(name, note)
    film(ic, 9.6, 1.6, 12.6, 14.2, dim=True)
    s, dx, dy = 0.64, 0.4, 8.0
    front(ic, s, dx, dy)
    return ic


def _front_jpeg(ic, s, dx, dy):
    x, y, w, h, r, t = _j['frame']
    ic.b(xf(rrect(x, y, w, h, r), s, dx, dy), xf(_j['hills'], s, dx, dy)).h(xf(rrect(x + t, y + t, w - 2 * t, h - 2 * t, r - t), s, dx, dy))
    ic.a(xf(_j['sun'], s, dx, dy))


def _front_heic(ic, s, dx, dy):
    x, y, w, h, r, t = _l['frame']
    ic.b(xf(rrect(x, y, w, h, r), s, dx, dy), xf(_l['leaf'], s, dx, dy), xf(_l['stem'], s, dx, dy))
    ic.h(xf(rrect(x + t, y + t, w - 2 * t, h - 2 * t, r - t), s, dx, dy), xf(_l['vein'], s, dx, dy))


def _front_avif(ic, s, dx, dy):
    ic.b(xf(_g['gem'], s, dx, dy)).h(*[xf(c, s, dx, dy) for c in _g['cut']])


def _front_webp(ic, s, dx, dy):
    ic.b(*[xf(p, s, dx, dy) for p in _gb], xf(_eq, s, dx, dy)).h(*[xf(p, s, dx, dy) for p in _gh])


pair('ic_shade_rawjpeg', 'RAW + JPEG: a film frame behind a picture frame', _front_jpeg)
pair('ic_shade_rawheic', 'RAW + HEIC: a film frame behind a framed leaf', _front_heic)
pair('ic_shade_rawavif', 'RAW + AVIF: a film frame behind a gem', _front_avif)
pair('ic_shade_rawwebp', 'RAW + WebP: a film frame behind a globe', _front_webp)

# --- top bar
SUBJECT_X = 16.0  # the AVDs (topbar_avd_motion_*) move the subject between x 16 (on) and 12 (off)
icon('topbar_ic_motion_off', '«Движение» off: the subject stands').b(circle(12, 12, 5.0)).h(circle(12, 12, 2.6)).a(circle(12, 12, 1.5))
icon('topbar_ic_motion_on', '«Движение» on: the subject moves, speed lines behind it').b(circle(SUBJECT_X, 12, 5.0),
                                                                                          capsule(3.2, 8.2, 8.4, 8.2, 2.0), capsule(1.6, 12, 7.8, 12, 2.0),
                                                                                          capsule(4.6, 15.8, 8.4, 15.8, 2.0)) \
    .h(circle(SUBJECT_X, 12, 2.6)).a(circle(SUBJECT_X, 12, 1.5))
frame(icon('topbar_ic_route_hybrid', 'SHDR: a monogram S in a rounded frame'), 3, 3, 18, 18, 4.6, 2.2) \
    .b(arc(12, 9.7, 2.5, 2.1, -20, -270), arc(12, 14.4, 2.5, 2.1, -90, 160))
frame(icon('topbar_ic_route_scamhdr', 'old SCAM HDR shots (gallery): a monogram N in a rounded frame'), 3, 3, 18, 18, 4.6, 2.2) \
    .b(polyline([(8.6, 16.2), (8.6, 7.8), (15.4, 16.2), (15.4, 7.8)], 2.1))

# --- manual controls (circularbarlib)
frame(icon('manual_ic_ev', 'EV: a square split diagonally, plus and minus', LIB_RES), 3, 3, 18, 18, 3.2, 2.2) \
    .b(capsule(18.6, 5.4, 5.4, 18.6, 1.9), capsule(6.6, 8.6, 10.6, 8.6, 1.8), capsule(8.6, 6.6, 8.6, 10.6, 1.8), capsule(13.4, 15.6, 17.4, 15.6, 1.8))
icon('manual_ic_focus', 'focus: brackets and a ring', LIB_RES).b(BRACKETS, ring(12, 12, 3.4, 2.0), circle(12, 12, 1.1))
_iso = frame(icon('manual_ic_iso', 'ISO: a sensor with grain', LIB_RES), 3.4, 3.4, 17.2, 17.2, 3, 2.2)
for _x, _y in ((8.4, 8.4), (15.6, 8.4), (12, 12), (8.4, 15.6), (15.6, 15.6)):
    _iso.b(circle(_x, _y, 1.25))
_sh = icon('manual_ic_shutter', 'shutter: a lens disc with the blades', LIB_RES).b(circle(12, 12, 9.6))
_v = [(12 + 4.4 * math.cos(math.radians(60 * i)), 12 + 4.4 * math.sin(math.radians(60 * i))) for i in range(6)]
_sh.h(poly(*_v))
for _i in range(6):
    (_x0, _y0), (_x1, _y1) = _v[_i], _v[(_i + 1) % 6]
    _dx, _dy = _x1 - _x0, _y1 - _y0
    _n = math.hypot(_dx, _dy)
    _sh.h(capsule(_x1 + 1.3 * _dx / _n, _y1 + 1.3 * _dy / _n, _x1 + 4.4 * _dx / _n, _y1 + 4.4 * _dy / _n, 1.0))
icon('manual_ic_sliders', 'manual controls: three sliders', LIB_RES).b(capsule(3.6, 6, 20.4, 6, 2.2), capsule(3.6, 12, 20.4, 12, 2.2),
                                                                      capsule(3.6, 18, 20.4, 18, 2.2), circle(15.6, 6, 2.7), circle(8.4, 12, 2.7),
                                                                      circle(13.6, 18, 2.7))
icon('manual_ic_wb', 'WB: a thermometer with its scale', LIB_RES).b(circle(10, 17, 3.8), rrect(8, 2.8, 4, 13, 2.0)) \
    .h(rrect(9.3, 4.2, 1.4, 9.6, 0.7)).b(capsule(14.6, 5.4, 17.4, 5.4, 1.8), capsule(14.6, 8.6, 16.6, 8.6, 1.8), capsule(14.6, 11.8, 17.4, 11.8, 1.8))

# --- settings rows that used other icons
icon('settings_ic_motion', '«Движение»: the subject moves, speed lines behind it').b(circle(15.2, 12, 5.2), capsule(3.2, 8.2, 8.4, 8.2, 2.2),
                                                                                     capsule(1.8, 12, 7.8, 12, 2.2), capsule(4.6, 15.8, 8.4, 15.8, 2.2)) \
    .h(circle(15.2, 12, 2.8)).a(circle(15.2, 12, 1.7))
icon('settings_ic_focus', 'focus peaking: brackets and an accent ring').b(BRACKETS).a(ring(12, 12, 3.4, 2.0))
icon('settings_ic_plus', 'add: a disc with an accent plus').b(circle(12, 12, 9.6)).h(circle(12, 12, 7.0)) \
    .a(capsule(12, 8.4, 12, 15.6, 2.4), capsule(8.4, 12, 15.6, 12, 2.4))

# --- the launcher shortcut «Настройки» (P140): the settings gear with fixed colours (a launcher resolves no app theme)
SHORTCUT = Icon('shortcut_ic_gear', 'the gear of the «Настройки» shortcut, fixed coral (launchers apply no app theme)')
SHORTCUT.b(gear(12, 12, 10.2, 7.6, 8, 0.46)).h(circle(12, 12, 3.7)).a(circle(12, 12, 2.3))


def main(argv):
    preview = argv[argv.index('--preview') + 1] if '--preview' in argv else None
    write = '--write' in argv
    if '--check' in argv:
        # CI: the drawables are what this script makes (an icon edited by hand would drift from the style)
        expected = [(icon.res + icon.name + '.xml', vector(icon)) for icon in ICONS]
        expected += [(RES + 'shortcut_ic_gear.xml', vector(SHORTCUT, accent_hex='#FFFFA99E')),
                     (RES + 'shortcut_ic_gear_mono.xml', vector(SHORTCUT, mono=True))]
        bad = [os.path.basename(f) for f, text in expected
               if not os.path.isfile(f) or open(f, encoding='utf-8').read().replace(chr(13), '') != text]
        if bad:
            print('UI icons differ from tools/make_ui_icons.py: ' + ', '.join(bad))
            return 1
        print('UI icons PASS: %d drawables as generated' % len(expected))
        return 0
    if preview:
        os.makedirs(preview, exist_ok=True)
        settings = [i for i in ICONS if i.name.startswith('settings_ic_')]
        others = [i for i in ICONS if not i.name.startswith('settings_ic_')]
        sheet(settings, os.path.join(preview, 'settings.png'))
        sheet(settings, os.path.join(preview, 'settings_tinted.png'), tint=(255, 255, 255))
        if others:
            sheet(others, os.path.join(preview, 'others_tinted.png'), tint=(255, 255, 255))
            sheet(others, os.path.join(preview, 'others.png'))
        print('previews in', preview)
    if write:
        for icon in ICONS:
            with open(icon.res + icon.name + '.xml', 'w', encoding='utf-8', newline='\n') as f:
                f.write(vector(icon))
        with open(RES + 'shortcut_ic_gear.xml', 'w', encoding='utf-8', newline='\n') as f:
            f.write(vector(SHORTCUT, accent_hex='#FFFFA99E'))
        with open(RES + 'shortcut_ic_gear_mono.xml', 'w', encoding='utf-8', newline='\n') as f:
            f.write(vector(SHORTCUT, mono=True))
        print('wrote', len(ICONS) + 2, 'icons')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
