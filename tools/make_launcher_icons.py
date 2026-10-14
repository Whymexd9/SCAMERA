"""P131 / P131b: SCAMERA / SGallery launcher icons (python tools/make_launcher_icons.py [PREVIEW_DIR]).

The UI's dark surface as the background; filled glyphs close to the former icon (a light camera body, a dark glass lens with
a highlight, the shutter button and the LED in the accent; the gallery as two filled photo cards) in the UI's coral accent.
One geometry (108-unit adaptive-icon viewport; launchers show the centre 72 under their mask) is written as Android vector
drawables and drawn with PIL (8x supersampled) for the splash PNGs and the previews.
"""
import os
import sys
from PIL import Image, ImageDraw

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'res') + os.sep
PREVIEW = sys.argv[1] if len(sys.argv) > 1 else None

ACCENT = '#FFA99E'                       # the UI's «Коралловый»
BODY_TOP, BODY_BOTTOM = '#F3F4F8', '#C5C9D2'
LENS_RING = '#2A2E35'
GLASS_IN, GLASS_OUT = '#24324A', '#0C0E12'
HIGHLIGHT = '#8CFFFFFF'
BACK_CARD = '#7E8491'
WINDOW = '#232830'
HILLS = '#B4BAC6'
BG_TOP, BG_BOTTOM = '#23272E', '#121417'

# shapes: (kind, geometry, fill) with fill = colour, ('v', top, bottom) vertical gradient or ('r', centre, edge) radial
CAMERA = [
    ('rrect', (36, 33.5, 13, 9, 3), ACCENT),                         # shutter button
    ('rrect', (29, 39, 50, 35, 10), ('v', BODY_TOP, BODY_BOTTOM)),   # body
    ('circle', (54, 57, 13.5), LENS_RING),                           # lens barrel
    ('circle', (54, 57, 11.2), ACCENT),                              # accent ring
    ('circle', (54, 57, 9.6), ('r', GLASS_IN, GLASS_OUT)),           # glass
    ('circle', (50.6, 53.6, 2.6), HIGHLIGHT),                        # highlight
    ('circle', (71, 46.5, 2.4), ACCENT),                             # LED
]
GALLERY = [
    ('rrect', (38, 29, 42, 38, 8), BACK_CARD),                       # the photo behind
    ('rrect', (28, 39, 42, 38, 8), ('v', BODY_TOP, BODY_BOTTOM)),    # the photo in front
    ('rrect', (32.5, 43.5, 33, 29, 5), WINDOW),                      # its picture
    ('poly', [(32.5, 72.5), (32.5, 67), (43, 56.5), (50.5, 64), (55.5, 59), (65.5, 69), (65.5, 72.5)], HILLS),
    ('circle', (58.5, 50.5, 3.6), ACCENT),                           # sun
]


def scaled(shapes, f, c=54.0):
    """The glyph f times its size around the icon's centre."""
    out = []
    for kind, g, fill in shapes:
        if kind == 'rrect':
            x, y, w, h, r = g
            g = (c + (x - c) * f, c + (y - c) * f, w * f, h * f, r * f)
        elif kind == 'circle':
            cx, cy, r = g
            g = (c + (cx - c) * f, c + (cy - c) * f, r * f)
        else:
            g = [(c + (x - c) * f, c + (y - c) * f) for x, y in g]
        out.append((kind, g, fill))
    return out


CAMERA, GALLERY = scaled(CAMERA, 0.84), scaled(GALLERY, 0.8)


def rrect(x, y, w, h, r):
    return ('M%g,%gH%gA%g,%g 0 0 1 %g,%gV%gA%g,%g 0 0 1 %g,%gH%gA%g,%g 0 0 1 %g,%gV%gA%g,%g 0 0 1 %g,%gZ'
            % (x + r, y, x + w - r, r, r, x + w, y + r, y + h - r, r, r, x + w - r, y + h, x + r, r, r, x, y + h - r,
               y + r, r, r, x + r, y))


def circle(cx, cy, r):
    return 'M%g,%gA%g,%g 0 1 0 %g,%gA%g,%g 0 1 0 %g,%gZ' % (cx - r, cy, r, r, cx + r, cy, r, r, cx - r, cy)


def path_of(kind, g):
    if kind == 'rrect':
        return rrect(*g)
    if kind == 'circle':
        return circle(*g)
    return 'M' + 'L'.join('%g,%g' % p for p in g) + 'Z'


def bounds(kind, g):
    if kind == 'rrect':
        return g[0], g[1], g[0] + g[2], g[1] + g[3]
    if kind == 'circle':
        return g[0] - g[2], g[1] - g[2], g[0] + g[2], g[1] + g[2]
    xs, ys = [p[0] for p in g], [p[1] for p in g]
    return min(xs), min(ys), max(xs), max(ys)


def mono_vector(shapes):
    """Android 13 themed icon (only the alpha counts): the glyph's silhouette with the lens / picture cut out (evenOdd)."""
    def find(kind, fill):
        return [g for k, g, f in shapes if k == kind and f == fill]
    paths = []
    if find('circle', LENS_RING):                       # camera
        body = [g for k, g, f in shapes if k == 'rrect' and isinstance(f, tuple)][0]
        ring = find('circle', ACCENT)
        ring = [g for g in ring if g[2] > 5][0]
        glass = [g for k, g, f in shapes if k == 'circle' and isinstance(f, tuple)][0]
        paths.append((rrect(*body) + circle(*ring), True))
        paths.append((circle(glass[0], glass[1], glass[2] * 0.72), False))
        paths += [(rrect(*g), False) for g in find('rrect', ACCENT)]
        paths += [(circle(*g), False) for g in find('circle', ACCENT) if g[2] <= 5]
    else:                                               # gallery
        back = find('rrect', BACK_CARD)[0]
        front = [g for k, g, f in shapes if k == 'rrect' and isinstance(f, tuple)][0]
        window = find('rrect', WINDOW)[0]
        gap = 2.0
        grown = (front[0] - gap, front[1] - gap, front[2] + 2 * gap, front[3] + 2 * gap, front[4] + gap)
        paths.append((rrect(*back) + rrect(*grown), True))
        paths.append((rrect(*front) + rrect(*window), True))
        paths += [(path_of('poly', g), False) for k, g, f in shapes if k == 'poly']
        paths += [(circle(*g), False) for g in find('circle', ACCENT)]
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- P131b: generated by tools/make_launcher_icons.py (themed icon: the silhouette, lens / picture cut out). -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp"', '    android:height="108dp"',
           '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    for d, even in paths:
        out.append('    <path android:pathData="%s" android:fillColor="#FFFFFFFF"%s/>' % (d, ' android:fillType="evenOdd"' if even else ''))
    out.append('</vector>')
    return '\n'.join(out) + '\n'


def vector(shapes):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- P131b: generated by tools/make_launcher_icons.py (dark UI surface, filled glyph, coral accent). -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    xmlns:aapt="http://schemas.android.com/aapt"',
           '    android:width="108dp"', '    android:height="108dp"',
           '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    for kind, g, fill in shapes:
        d = path_of(kind, g)
        if isinstance(fill, tuple):
            x0, y0, x1, y1 = bounds(kind, g)
            if fill[0] == 'v':
                grad = ('<gradient android:type="linear" android:startX="%g" android:startY="%g" android:endX="%g" '
                        'android:endY="%g" android:startColor="%s" android:endColor="%s"/>'
                        % ((x0 + x1) / 2, y0, (x0 + x1) / 2, y1, fill[1], fill[2]))
            else:
                grad = ('<gradient android:type="radial" android:centerX="%g" android:centerY="%g" android:gradientRadius="%g" '
                        'android:startColor="%s" android:endColor="%s"/>'
                        % ((x0 + x1) / 2 - (x1 - x0) * 0.12, (y0 + y1) / 2 - (y1 - y0) * 0.12, (x1 - x0) * 0.62,
                           fill[1], fill[2]))
            out.append('    <path android:pathData="%s">\n        <aapt:attr name="android:fillColor">\n            %s\n'
                       '        </aapt:attr>\n    </path>' % (d, grad))
        else:
            out.append('    <path android:pathData="%s" android:fillColor="%s"/>' % (d, fill))
    out.append('</vector>')
    return '\n'.join(out) + '\n'


def rgba(c):
    c = c.lstrip('#')
    if len(c) == 8:
        return tuple(int(c[i:i + 2], 16) for i in (2, 4, 6)) + (int(c[0:2], 16),)
    return tuple(int(c[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def draw(shapes, size, background=True):
    s = 8
    n = size * s
    k = n / 108.0
    img = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    if background:
        bd = ImageDraw.Draw(img)
        t, b = rgba(BG_TOP), rgba(BG_BOTTOM)
        for y in range(n):
            a = y / (n - 1)
            bd.line([(0, y), (n, y)], fill=tuple(int(t[i] + (b[i] - t[i]) * a) for i in range(3)) + (255,))
    for kind, g, fill in shapes:
        mask = Image.new('L', (n, n), 0)
        md = ImageDraw.Draw(mask)
        if kind == 'rrect':
            x, y, w, h, r = g
            md.rounded_rectangle([x * k, y * k, (x + w) * k, (y + h) * k], radius=r * k, fill=255)
        elif kind == 'circle':
            cx, cy, r = g
            md.ellipse([(cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k], fill=255)
        else:
            md.polygon([(x * k, y * k) for x, y in g], fill=255)
        layer = Image.new('RGBA', (n, n), rgba(fill) if not isinstance(fill, tuple) else (0, 0, 0, 0))
        if isinstance(fill, tuple):
            x0, y0, x1, y1 = [v * k for v in bounds(kind, g)]
            ld = ImageDraw.Draw(layer)
            a, b = rgba(fill[1]), rgba(fill[2])
            if fill[0] == 'v':
                for y in range(int(y0), int(y1) + 1):
                    t = min(max((y - y0) / max(y1 - y0, 1), 0), 1)
                    ld.line([(x0, y), (x1, y)], fill=tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(4)))
            else:
                cx, cy = (x0 + x1) / 2 - (x1 - x0) * 0.12, (y0 + y1) / 2 - (y1 - y0) * 0.12
                rad = (x1 - x0) * 0.62
                for r in range(int(rad * 1.6), 0, -2):
                    t = min(r / rad, 1)
                    ld.ellipse([cx - r, cy - r, cx + r, cy + r], fill=tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(4)))
        alpha = Image.eval(mask, lambda v: v)
        if rgba(fill if not isinstance(fill, tuple) else fill[1])[3] < 255 and not isinstance(fill, tuple):
            alpha = Image.eval(mask, lambda v, a=rgba(fill)[3]: v * a // 255)
        img.paste(layer, (0, 0), alpha)
    return img.resize((size, size), Image.LANCZOS)


def visible(img, mask, size):
    """The launcher's view of an adaptive icon: the centre 72 of 108 units under its mask."""
    n = img.size[0]
    c = img.crop((n * 18 // 108, n * 18 // 108, n * 90 // 108, n * 90 // 108)).resize((size, size), Image.LANCZOS)
    m = Image.new('L', (size * 4, size * 4), 0)
    md = ImageDraw.Draw(m)
    if mask == 'circle':
        md.ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
    else:
        md.rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=int(size * 4 * 0.24), fill=255)
    out = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    out.paste(c, (0, 0), m.resize((size, size), Image.LANCZOS))
    return out


def write(rel, text):
    os.makedirs(os.path.dirname(RES + rel), exist_ok=True)
    with open(RES + rel, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


if PREVIEW:
    os.makedirs(PREVIEW, exist_ok=True)
    row = Image.new('RGB', (4 * 210 + 10, 220), (40, 44, 52))
    for i, (shapes, mask) in enumerate(((CAMERA, 'squircle'), (CAMERA, 'circle'), (GALLERY, 'squircle'), (GALLERY, 'circle'))):
        im = visible(draw(shapes, 288), mask, 192)
        row.paste(im, (10 + i * 210, 14), im)
    row.save(PREVIEW + '/preview.png')
    small = Image.new('RGB', (2 * 60 + 6, 60), (230, 230, 235))
    for i, shapes in enumerate((CAMERA, GALLERY)):
        im = visible(draw(shapes, 144), 'squircle', 48)
        small.paste(im, (6 + i * 60, 6), im)
    small.save(PREVIEW + '/preview48.png')
    sys.exit(0)

write('drawable/scamera_icon_fg.xml', vector(CAMERA))
write('drawable/sgallery_icon_fg.xml', vector(GALLERY))
write('drawable/scamera_launch_mono.xml', mono_vector(CAMERA))
write('drawable/gallery_launch_mono.xml', mono_vector(GALLERY))
write('drawable/scamera_icon_background.xml', '''<?xml version="1.0" encoding="utf-8"?>
<!-- P131: the launcher icons' background - the UI's dark surface, a little lighter at the top. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <gradient android:angle="270" android:startColor="%s" android:endColor="%s" />
</shape>
''' % (BG_TOP, BG_BOTTOM))
for name, fg, mono in (('scamera_launcher', 'scamera_icon_fg', 'scamera_launch_mono'),
                       ('scamera_launcher_round', 'scamera_icon_fg', 'scamera_launch_mono'),
                       ('ic_gallery_launcher', 'sgallery_icon_fg', 'gallery_launch_mono'),
                       ('ic_gallery_launcher_round', 'sgallery_icon_fg', 'gallery_launch_mono')):
    write('mipmap-anydpi-v26/%s.xml' % name, '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/scamera_icon_background" />
    <foreground android:drawable="@drawable/%s" />
    <monochrome android:drawable="@drawable/%s" />
</adaptive-icon>
''' % (fg, mono))
# the splash (a raster on black, 48 dp): the camera icon on its rounded surface
for dens, px in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
    im = visible(draw(CAMERA, px * 3), 'squircle', px)
    out = Image.new('RGB', (px, px), (0, 0, 0))
    out.paste(im, (0, 0), im)
    out.save(RES + 'drawable-%s/scamera_splash_icon.png' % dens, optimize=True)
print('written')
