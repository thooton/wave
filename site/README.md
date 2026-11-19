# Wave on the web

The public site includes the XP3800 landing page, platform notes and app setup.
It has no server-side component. `site/AGENTS.md` describes its layout,
local preview and tests. Pages must be enabled with **GitHub Actions** as the
source; no deployment is needed for a local preview.

Run these commands from the repository root with Node 22 and Python 3.
Output must be a new directory. Neither the site build nor the tests require
an npm install or sibling checkout.

```sh
node --test site/tests/*.test.mjs site/src/protocols/tests/*.test.mjs
python3 site/scripts/build.py /path/to/new/preview/wave
python3 site/scripts/check_pages.py /path/to/new/preview/wave
python3 -m unittest discover -s site/scripts
```

The setup form's descriptors are a snapshot of the six apps' `wave-setup.json`
files in wave_packages. `site/setup/UPSTREAM` records the commit they came
from (like `common/UPSTREAM`), and the snapshot's first line repeats it.
When those files change, run `python3 site/scripts/sync_setup.py ../sonim`
(reads that checkout's committed HEAD), review the diff and commit both
files. To verify, run `python3 site/scripts/sync_setup.py ../sonim --check`:
it regenerates from the recorded commit and fails on any difference, and
only notes when wave_packages has newer descriptor changes. Without a path,
`--check` verifies offline that the record and snapshot agree. The Pages
workflow runs the offline check always, and the full one once the
repository variable `WAVE_PACKAGES_CHECK` is `true` (secret
`WAVE_PACKAGES_READ_TOKEN` if the repository is private).

The checker covers relative URLs and fragments, HTML landmarks and label
references, static module imports, and reachability of all staged assets.
The landing page has no JavaScript and a 64 KiB asset budget; the whole
artifact has a 140 KiB budget (170 KiB with the optional backup), uncompressed. Development-only service and
ADB transfer code is not in the public dependency graph. The five real
phone screenshots on the landing page (`site/mockups/phone/`) are converted by
`python3 site/scripts/screens.py` to 256-colour lossless WebP (about 37 kB in all;
about 0.5% of pixels at most move by more than 4 levels, all anti-aliasing
fringes). The script refuses a screen that changes more, and `--check` fails if
the committed files are not what it would write. They are plain `<img>` elements
with captions, no script.

For a keyboard smoke test, serve the parent of the staged `wave` directory.
Use Tab, Shift+Tab and Enter to follow the first skip link into each page,
choose an app with the arrow keys, submit an empty form, and confirm focus
returns to the named invalid field. The 320px-and-narrower layout uses a
single column of navigation links for browsers with native directional
focus. A browser that does not implement directional focus still needs Tab
or equivalent access keys; the site does not override arrow-key scrolling.
