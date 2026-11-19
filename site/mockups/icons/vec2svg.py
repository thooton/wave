#!/usr/bin/env python3
"""Turn the apps' Android vector launcher icons (in ../sonim, which becomes
wave_packages) into SVGs for the mockups, so the package rows show the icons
Wave will show. Run from the repo root:

    python3 site/mockups/icons/vec2svg.py ../sonim

It handles what the icons use: paths (fill, stroke, alpha, line caps, fill
type), groups (translate, scale, rotate about a pivot) and linear or radial
gradients given through aapt:attr. The Wave icon is drawn by hand in wave.svg.
"""
import os
import sys
import xml.etree.ElementTree as ET

A = '{http://schemas.android.com/apk/res/android}'
APPS = {
    'mail': 'mail/android', 'books': 'books/android', 'dictionary': 'dictionary',
    'list': 'list/android', 'musica': 'musica/android', 'print': 'print/android',
    'serve': 'serve/android', 'video': 'video/android', 'weather': 'weather/android',
    'canvas': 'canvas/android', 'charge': 'charge/android', 'places': 'places/android',
    'authenticator': 'authenticator/android', 'flashcards': 'flashcards/android',
    'updater': 'updater/android',
}


def color(c):
    """Android #AARRGGBB or #RRGGBB to an SVG colour and opacity."""
    if c is None:
        return None, 1.0
    c = c.strip()
    if c.startswith('@'):
        return '#000000', 1.0
    h = c[1:]
    if len(h) == 3:
        h = ''.join(x * 2 for x in h)
    if len(h) == 8:
        return '#' + h[2:], int(h[:2], 16) / 255.0
    if len(h) == 4:
        return '#' + ''.join(x * 2 for x in h[1:]), int(h[0] * 2, 16) / 255.0
    return '#' + h, 1.0


class Conv:
    def __init__(self, name):
        self.name = name
        self.defs = []
        self.n = 0

    def gradient(self, el):
        self.n += 1
        gid = '%s-g%d' % (self.name, self.n)
        kind = el.get(A + 'type', 'linear')
        stops = []
        items = el.findall('item')
        if items:
            for it in items:
                col, op = color(it.get(A + 'color'))
                stops.append((float(it.get(A + 'offset', '0')), col, op))
        else:
            for key, off in (('startColor', 0), ('centerColor', 0.5), ('endColor', 1)):
                if el.get(A + key):
                    col, op = color(el.get(A + key))
                    stops.append((off, col, op))
        s = ''.join('<stop offset="%g" stop-color="%s" stop-opacity="%g"/>' % st for st in stops)
        if kind == 'radial':
            self.defs.append('<radialGradient id="%s" gradientUnits="userSpaceOnUse" cx="%s" cy="%s" r="%s">%s</radialGradient>' % (
                gid, el.get(A + 'centerX', '0'), el.get(A + 'centerY', '0'), el.get(A + 'gradientRadius', '1'), s))
        else:
            self.defs.append('<linearGradient id="%s" gradientUnits="userSpaceOnUse" x1="%s" y1="%s" x2="%s" y2="%s">%s</linearGradient>' % (
                gid, el.get(A + 'startX', '0'), el.get(A + 'startY', '0'), el.get(A + 'endX', '0'), el.get(A + 'endY', '0'), s))
        return 'url(#%s)' % gid

    def paint(self, el, attr):
        val = el.get(A + attr)
        for sub in el.findall('{http://schemas.android.com/aapt}attr'):
            if sub.get('name') == 'android:' + attr:
                g = sub.find('gradient')
                if g is not None:
                    return self.gradient(g), 1.0
        if val is None:
            return None, 1.0
        return color(val)

    def path(self, el):
        out = ['<path d="%s"' % el.get(A + 'pathData')]
        fill, fop = self.paint(el, 'fillColor')
        out.append(' fill="%s"' % (fill or 'none'))
        fa = float(el.get(A + 'fillAlpha', '1')) * fop
        if fill and fa < 1:
            out.append(' fill-opacity="%g"' % fa)
        if el.get(A + 'fillType') == 'evenOdd':
            out.append(' fill-rule="evenodd"')
        stroke, sop = self.paint(el, 'strokeColor')
        if stroke:
            out.append(' stroke="%s" stroke-width="%s"' % (stroke, el.get(A + 'strokeWidth', '1')))
            sa = float(el.get(A + 'strokeAlpha', '1')) * sop
            if sa < 1:
                out.append(' stroke-opacity="%g"' % sa)
            if el.get(A + 'strokeLineCap'):
                out.append(' stroke-linecap="%s"' % el.get(A + 'strokeLineCap'))
            if el.get(A + 'strokeLineJoin'):
                out.append(' stroke-linejoin="%s"' % el.get(A + 'strokeLineJoin'))
        out.append('/>')
        return ''.join(out)

    def group(self, el):
        t = []
        px, py = el.get(A + 'pivotX', '0'), el.get(A + 'pivotY', '0')
        tx, ty = el.get(A + 'translateX', '0'), el.get(A + 'translateY', '0')
        if tx != '0' or ty != '0':
            t.append('translate(%s %s)' % (tx, ty))
        t.append('translate(%s %s)' % (px, py))
        if el.get(A + 'rotation'):
            t.append('rotate(%s)' % el.get(A + 'rotation'))
        sx, sy = el.get(A + 'scaleX', '1'), el.get(A + 'scaleY', '1')
        if sx != '1' or sy != '1':
            t.append('scale(%s %s)' % (sx, sy))
        t.append('translate(-%s -%s)' % (px, py))
        return '<g transform="%s">%s</g>' % (' '.join(t), self.children(el))

    def children(self, el):
        out = []
        for ch in el:
            if ch.tag == 'path':
                out.append(self.path(ch))
            elif ch.tag == 'group':
                out.append(self.group(ch))
        return ''.join(out)

    def svg(self, root):
        body = self.children(root)
        return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %s %s"><defs>%s</defs>%s</svg>\n' % (
            root.get(A + 'viewportWidth'), root.get(A + 'viewportHeight'), ''.join(self.defs), body))


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else '../sonim'
    here = os.path.dirname(os.path.abspath(__file__))
    for name, base in APPS.items():
        p = os.path.join(src, base, 'app/src/main/res/drawable/ic_launcher.xml')
        if not os.path.exists(p):
            print('missing', p)
            continue
        root = ET.parse(p).getroot()
        with open(os.path.join(here, name + '.svg'), 'w') as f:
            f.write(Conv(name).svg(root))
        print('wrote', name)


if __name__ == '__main__':
    main()
