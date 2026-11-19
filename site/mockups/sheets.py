#!/usr/bin/env python3
"""Contact sheets of shots/*.png at 1:1, 4 across and 2 down, with each id
under its frame, into the given folder (for checking frames quickly)."""
import json, os, subprocess, sys
from PIL import Image, ImageDraw

here = os.path.dirname(os.path.abspath(__file__))
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, 'sheets')
os.makedirs(out, exist_ok=True)
ids = subprocess.run(['node', '-e', "for (const f of require('%s/frames.js').FRAMES) console.log(f.id)" % here],
                     capture_output=True, text=True, check=True).stdout.split()
per = 8
for n in range(0, len(ids), per):
    chunk = ids[n:n + per]
    sheet = Image.new('RGB', (4 * 250, 2 * 340), (120, 120, 128))
    d = ImageDraw.Draw(sheet)
    for i, fid in enumerate(chunk):
        x, y = (i % 4) * 250 + 5, (i // 4) * 340 + 2
        sheet.paste(Image.open(os.path.join(here, 'shots', fid + '.png')).convert('RGB'), (x, y))
        d.text((x, y + 322), fid, fill=(255, 255, 255))
    p = os.path.join(out, 'sheet%d.png' % (n // per + 1))
    sheet.save(p)
    print(p)
