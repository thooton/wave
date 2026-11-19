#!/usr/bin/env python3
"""Convert the real phone screenshots used on the landing page to small WebP files.

Each 240x320 PNG in site/mockups/phone/ is reduced to a 256-colour palette (no
dithering) and saved as lossless WebP. That is not pixel-identical: the script
prints how many pixels move by more than 4 levels (anti-aliasing fringes) and
the largest change, and fails if either is above the limits, so the claim on the
page stays true. Needs Pillow (with libimagequant) at build time only.

    python3 site/scripts/screens.py          # rewrite site/assets/screens/
    python3 site/scripts/screens.py --check  # fail if the checked-in files differ
"""
import argparse
import io
from pathlib import Path
import sys

from PIL import Image, ImageChops

SITE = Path(__file__).resolve().parents[1]
SOURCE = SITE / 'mockups' / 'phone'
OUT = SITE / 'assets' / 'screens'
SCREENS = {'home': 'wave-home', 'sources': 'sources', 'details': 'details-get',
           'installed': 'installed', 'search': 'search-results'}
MAX_CHANGED = .01      # share of pixels that may move by more than 4 levels
MAX_LEVEL = 32


def convert(name):
    image = Image.open(SOURCE / f'{name}.png').convert('RGB')
    if image.size != (240, 320):
        raise SystemExit(f'{name}.png is {image.size}, expected 240x320')
    small = image.quantize(256, method=Image.Quantize.LIBIMAGEQUANT, dither=Image.Dither.NONE)
    out = io.BytesIO()
    small.save(out, 'WEBP', lossless=True, quality=100, method=4)
    # Largest per-channel change at each pixel, as a histogram of 0..255.
    diff = ImageChops.difference(image, small.convert('RGB'))
    worst = ImageChops.lighter(ImageChops.lighter(*diff.split()[:2]), diff.split()[2])
    histogram = worst.histogram()
    changed = sum(histogram[5:]) / (240 * 320)
    return out.getvalue(), changed, max(level for level, n in enumerate(histogram) if n)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    OUT.mkdir(exist_ok=True)
    total, failed = 0, False
    for site_name, shot in SCREENS.items():
        data, share, level = convert(shot)
        path = OUT / f'{site_name}.webp'
        total += len(data)
        note = f'{path.relative_to(SITE)} {len(data)} B, {share:.2%} of pixels moved by more than 4, largest move {level}'
        if share > MAX_CHANGED or level > MAX_LEVEL:
            print('TOO LOSSY:', note)
            failed = True
        elif args.check:
            # Palette choice can differ between Pillow builds, so --check compares the decoded picture.
            if not path.is_file() or ImageChops.difference(Image.open(path).convert('RGB'),
                                                           Image.open(io.BytesIO(data)).convert('RGB')).getbbox():
                print('DIFFERS:', note)
                failed = True
        else:
            path.write_bytes(data)
            print(note)
    print(f'{total} B in all')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
