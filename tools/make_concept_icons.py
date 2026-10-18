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
}

NAMES = {
    'flash': 'torch', 'timer': 'timer', 'motion': 'Motion', 'gear': 'settings', 'sliders': 'Pro', 'apps': 'more (quick sheet)',
    'flip': 'front / back switch', 'sun': 'exposure sun', 'lock': 'focus / exposure lock', 'film': 'LUT', 'iso': 'ISO',
    'shutter': 'shutter speed', 'focus': 'focus', 'wb': 'white balance', 'auto': 'all on auto',
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
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- SIMPLE_UI: generated by tools/make_concept_icons.py - the concept\'s %s icon (24dp line, stroke 1.8). -->' % NAMES[name],
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
