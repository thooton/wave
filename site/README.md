# Wave on the web

Two static pages, served from GitHub Pages at `thooton.github.io/wave/`:

- `index.html` (the site root) roots the Sonim XP3800 and installs Wave
  (`src/root/rooting.mjs`).
- `recovery/` turns off broken Magisk modules (`src/root/rescue.mjs`) and
  writes the saved stock boot image back (`src/root/stock.mjs`);
  `src/root/recovery.mjs` switches its panes.

There is no server-side component. `site/AGENTS.md` has the rules for these
pages. The Build workflow (`.github/workflows/build.yml`) tests and stages the
site after building the APK, and deploys it from master; Pages must be enabled
with **GitHub Actions** as the source.

## Served files

The deployed site serves everything the flow needs, so the user chooses no
files. `build.py` stages them in `files/` with a `files.json` listing each
file's size and SHA-256. The pages check each download against that list, and
the programmer and Magisk against their pins as well:

| Role | `build.py` option | Source in CI |
| --- | --- | --- |
| Wave APK | `--wave-apk PATH --wave-version X` | the `wave-apk` artifact from the same run |
| Magisk 24.0 | `--magisk-apk PATH` (must be the official APK) | downloaded from the Magisk v24.0 release |
| Sonim programmer | `--programmer PATH` (must be the pinned file) | downloaded from the repository variable `SONIM_PROGRAMMER_URL` |

Anything not staged falls back to a file picker on the page. With all three
staged, the rooting page skips its **Choose files** step.

## Local build

Run these from the repository root with Node 22 and Python 3. The output must
be a new directory. Nothing needs an npm install.

```sh
node --test site/tests/*.test.mjs site/src/protocols/tests/*.test.mjs
python3 site/scripts/build.py /path/to/new/preview/wave [--programmer PATH --magisk-apk PATH --wave-apk PATH --wave-version X]
python3 site/scripts/check_pages.py /path/to/new/preview/wave
python3 -m http.server --directory /path/to/new/preview 8000   # http://localhost:8000/wave/
```

The checker covers relative URLs and fragments, HTML landmarks and label
references, static module and CSS imports, reachability of every staged asset
from `index.html`, and byte budgets. The site has a 300 KiB budget,
uncompressed, which doesn't count `files/`.
