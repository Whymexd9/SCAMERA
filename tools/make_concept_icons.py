"""SIMPLE_UI: the concept's line icons (24 x 24, stroke 1.8, round caps and joins) as Android vector drawables.

The glyphs are the concept's own SVG (SIMPLE_UI_TASK, the interactive concept's ICONS table); circles and rounded
rectangles become path data. The stroke is white: every view tints it (the text colour, or the accent while on).
Run: python tools/make_concept_icons.py  (writes app/src/main/res/drawable/ui_ic_*.xml and, for the Pro panel in the
circularbar library, circularbarlib/src/main/res/drawable/pro_ic_*.xml)
"""
import os
import re

ICONS = {
    'flash': '<path d="M13 2 5 13h6l-1 9 8-11h-6l1-9z"/>',
    'timer': '<circle cx="12" cy="13" r="8"/><path d="M12 9v4l2.5 2M9.5 2.5h5"/>',
    'motion': '<circle cx="15" cy="12" r="5"/><path d="M2.5 8h6M1.5 12h6M3.5 16h5"/>',
    'gear': '<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38'
            'a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73'
            'l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25'
            'a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74'
            'l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4'
            'a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/>',
    'sliders': '<path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12"/><circle cx="16" cy="6" r="2"/><circle cx="10" cy="12" r="2"/>'
               '<circle cx="18" cy="18" r="2"/>',
    'apps': '<rect x="4" y="4" width="6.5" height="6.5" rx="2"/><rect x="13.5" y="4" width="6.5" height="6.5" rx="2"/>'
            '<rect x="4" y="13.5" width="6.5" height="6.5" rx="2"/><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="2"/>',
    'flip': '<path d="M4.5 10A8 8 0 0 1 18 6.5M19.5 14A8 8 0 0 1 6 17.5"/><path d="M18.5 2.5v4.2h-4.2M5.5 21.5v-4.2h4.2"/>',
    'sun': '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4'
           'M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"/>',
    'lock': '<rect x="5" y="11" width="14" height="9" rx="2.5"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
    'film': '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="M3 9.5h18M3 14.5h18M7 6v3.5M11 6v3.5M15 6v3.5M7 14.5V18'
            'M11 14.5V18M15 14.5V18"/>',
    'iso': '<rect x="4.5" y="4.5" width="15" height="15" rx="3"/><path d="M8.5 8.5h.1M12 8.5h.1M15.5 8.5h.1M8.5 12h.1M12 12h.1'
           'M15.5 12h.1M8.5 15.5h.1M12 15.5h.1M15.5 15.5h.1" stroke-width="2.4"/>',
    'shutter': '<circle cx="12" cy="12" r="8.5"/><path d="M12 3.5 15 10M20.5 12 14 15M12 20.5 9 14M3.5 12 10 9"/>',
    'focus': '<path d="M4 8V5.5A1.5 1.5 0 0 1 5.5 4H8M16 4h2.5A1.5 1.5 0 0 1 20 5.5V8M20 16v2.5a1.5 1.5 0 0 1-1.5 1.5H16M8 20H5.5'
             'A1.5 1.5 0 0 1 4 18.5V16"/><circle cx="12" cy="12" r="3"/>',
    'wb': '<path d="M10 14V5a2 2 0 0 1 4 0v9a4 4 0 1 1-4 0z"/><path d="M12 9v7"/>',
    'auto': '<circle cx="12" cy="12" r="9"/><path d="M8.6 16.2 12 7.6l3.4 8.6M9.8 13.3h4.4"/>',
    'chev': '<path d="m9 6 6 6-6 6"/>',
    'back': '<path d="m15 5-7 7 7 7"/>',
    'search': '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
    'grid': '<rect x="4" y="4" width="16" height="16" rx="3"/><path d="M9.3 4v16M14.7 4v16M4 9.3h16M4 14.7h16"/>',
    'level': '<path d="M2 13h6M16 13h6M8 13a4 4 0 0 1 8 0"/>',
    'format': '<rect x="3.5" y="4.5" width="17" height="15" rx="3"/><path d="m4 17 5-5 4 4 2-2 5 5"/><circle cx="15.5" cy="9" r="1.5"/>',
    'size': '<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>',
    'hdr': '<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"/><path d="M18.5 16.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z"/>',
    'sound': '<path d="M4 9v6h4l5 4V5L8 9z"/><path d="M16.5 9a4 4 0 0 1 0 6M19 6.5a7.5 7.5 0 0 1 0 11"/>',
    'close': '<path d="M6 6l12 12M18 6 6 18"/>',
    'plus': '<path d="M12 5v14M5 12h14"/>',
    'share': '<path d="M12 3v12M8 7l4-4 4 4M5 12v7a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-7"/>',
    'edit': '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="m13.5 6.5 4 4"/>',
    'trash': '<path d="M4 7h16M9.5 7V4.5h5V7M6 7l1 13h10l1-13"/>',
    'info': '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5v.2"/>',
    'more': '<circle cx="5.5" cy="12" r="1.3"/><circle cx="12" cy="12" r="1.3"/><circle cx="18.5" cy="12" r="1.3"/>',
    'camera': '<path d="M4 8h3l2-3h6l2 3h3v11H4z"/><circle cx="12" cy="13" r="3.5"/>',
    'folder': '<path d="M3.5 6.5a1.5 1.5 0 0 1 1.5-1.5h4.5l2 2.5H19a1.5 1.5 0 0 1 1.5 1.5v8.5A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5z"/>',
    'eye': '<path d="M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
    'drop': '<path d="M12 3c3 4 6 7.2 6 11a6 6 0 0 1-12 0c0-3.8 3-7 6-11z"/>',
    'palette': '<path d="M12 3a9 9 0 1 0 0 18c1 0 1.6-.8 1.6-1.6 0-.9-.7-1.4-.7-2.2 0-1 .8-1.7 1.8-1.7H17a4 4 0 0 0 4-4C21 6.8 17 3 12 3z"/><circle cx="7.5" cy="11" r="1"/><circle cx="10.5" cy="7" r="1"/><circle cx="15" cy="7.5" r="1"/>',
    'layers': '<path d="M12 3 3 8l9 5 9-5z"/><path d="m3 13 9 5 9-5"/>',
    'wrench': '<path d="M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.5 2.5-2.4-.6-.6-2.4z"/>',
    'face': '<circle cx="12" cy="12" r="9"/><path d="M9 10h.1M15 10h.1M8.5 15a5 5 0 0 0 7 0"/>',
    'target': '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="3"/>',
    'peak': '<path d="m3 17 5-8 4 5 3-3 6 6"/>',
    'lamp': '<path d="M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.7.7 1 1.5 1 2.5h6c0-1 .3-1.8 1-2.5A6 6 0 0 0 12 3z"/>',
    'code': '<path d="m8 8-4 4 4 4M16 8l4 4-4 4"/>',
    'chipm': '<rect x="6" y="6" width="12" height="12" rx="2"/><path d="M9 2.5V6M15 2.5V6M9 18v3.5M15 18v3.5M2.5 9H6M2.5 15H6M18 9h3.5M18 15h3.5"/>',
    'msg': '<path d="M4 5h16v11H8.5L4 20z"/>',
    'clock': '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    'lens': '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="4"/>',
    'compare': '<rect x="3" y="5" width="8" height="14" rx="2"/><rect x="13" y="5" width="8" height="14" rx="2"/>',
    'backup': '<path d="M4 12a8 8 0 1 0 2.3-5.7"/><path d="M4 4v4h4M12 8v4l3 2"/>',
    'star': '<path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3 6.4 20.2l1.1-6.2L3 9.6l6.2-.9z"/>',
    'aspect': '<rect x="7" y="3" width="10" height="18" rx="2.5"/>',
    'text': '<path d="M5 6V4.5h14V6M12 4.5V20M9 20h6"/>',
    'mirror': '<path d="M12 3v18M8 7 4 12l4 5zM16 7l4 5-4 5z"/>',
    'raw': '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M7 5v14M17 5v14M3 9h4M3 15h4M17 9h4M17 15h4"/>',
}

NAMES = {
    'flash': 'torch', 'timer': 'timer', 'motion': 'Motion', 'gear': 'settings', 'sliders': 'Pro', 'apps': 'more (quick sheet)',
    'flip': 'front / back switch', 'sun': 'exposure sun', 'lock': 'focus / exposure lock', 'film': 'LUT', 'iso': 'ISO',
    'shutter': 'shutter speed', 'focus': 'focus', 'wb': 'white balance', 'auto': 'all on auto', 'chev': 'chevron',
}


def num(v):
    return ('%.3f' % float(v)).rstrip('0').rstrip('.')


def circle(a):
    cx, cy, r = float(a['cx']), float(a['cy']), float(a['r'])
    return 'M%s,%s a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 %s,0 Z' % (num(cx - r), num(cy), num(r), num(r), num(2 * r),
                                                              num(r), num(r), num(-2 * r))


def rect(a):
    x, y, w, h = (float(a[k]) for k in ('x', 'y', 'width', 'height'))
    r = float(a.get('rx', 0))
    if r <= 0:
        return 'M%s,%s h%s v%s h%s Z' % (num(x), num(y), num(w), num(h), num(-w))
    return ('M%s,%s h%s a%s,%s 0 0 1 %s,%s v%s a%s,%s 0 0 1 %s,%s h%s a%s,%s 0 0 1 %s,%s v%s a%s,%s 0 0 1 %s,%s Z' % (
        num(x + r), num(y), num(w - 2 * r), num(r), num(r), num(r), num(r), num(h - 2 * r), num(r), num(r), num(-r), num(r),
        num(-(w - 2 * r)), num(r), num(r), num(-r), num(-r), num(-(h - 2 * r)), num(r), num(r), num(r), num(-r)))


def elements(svg):
    for tag, attrs in re.findall(r'<(\w+)\s+([^>]*?)/>', svg):
        a = dict(re.findall(r'([\w-]+)="([^"]*)"', attrs))
        width = a.get('stroke-width', '1.8')
        if tag == 'path':
            yield a['d'], width
        elif tag == 'circle':
            yield circle(a), width
        elif tag == 'rect':
            yield rect(a), width
        else:
            raise ValueError(tag)


def vector(name, svg):
    label = NAMES.get(name, name)
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- SIMPLE_UI: generated by tools/make_concept_icons.py - the concept\'s %s icon (24dp line, stroke 1.8). -->' % label,
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="24dp"',
           '    android:height="24dp"',
           '    android:viewportWidth="24"',
           '    android:viewportHeight="24">']
    for d, w in elements(svg):
        out += ['    <path',
                '        android:pathData="%s"' % d,
                '        android:fillColor="#00000000"',
                '        android:strokeColor="#FFFFFFFF"',
                '        android:strokeWidth="%s"' % w,
                '        android:strokeLineCap="round"',
                '        android:strokeLineJoin="round" />']
    out.append('</vector>')
    return '\n'.join(out) + '\n'


# the Pro panel's chips (the library cannot see the app's drawables): pro_ic_<chip> from the concept's glyph
PRO = {'iso': 'iso', 'shutter': 'shutter', 'ev': 'sun', 'focus': 'focus', 'wb': 'wb', 'auto': 'auto'}


def main():
    here = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
    root = os.path.join(here, 'app', 'src', 'main', 'res', 'drawable')
    for name, svg in ICONS.items():
        with open(os.path.join(root, 'ui_ic_%s.xml' % name), 'w', encoding='utf-8', newline='\n') as f:
            f.write(vector(name, svg))
    lib = os.path.join(here, 'circularbarlib', 'src', 'main', 'res', 'drawable')
    for chip, name in PRO.items():
        with open(os.path.join(lib, 'pro_ic_%s.xml' % chip), 'w', encoding='utf-8', newline='\n') as f:
            f.write(vector(name, ICONS[name]))
    print('%d icons, %d Pro chips' % (len(ICONS), len(PRO)))


if __name__ == '__main__':
    main()
