# Wave on the web

The public site includes the XP3800 landing page, platform notes and app setup.
It has no server-side component. `site/AGENTS.md` describes its layout,
local preview and tests. Pages must be enabled with **GitHub Actions** as the
source; no deployment is needed for a local preview.

## Release checks

```sh
node --test site/tests/*.test.mjs site/src/protocols/tests/*.test.mjs
python3 site/scripts/build.py /path/to/new/preview/wave
python3 site/scripts/check_pages.py /path/to/new/preview/wave
```

The checker covers relative URLs and fragments, HTML landmarks and label
references, static module imports, and reachability of all staged assets.
The landing page has no JavaScript and a 32 KiB asset budget; the whole
artifact has a 110 KiB budget (uncompressed). Development-only service and
ADB transfer code is not in the public dependency graph. The PNG design
preview is converted to pixel-identical lossless WebP for this site.

For a keyboard smoke test, serve the parent of the staged `wave` directory.
Use Tab, Shift+Tab and Enter to follow the first skip link into each page,
choose an app with the arrow keys, submit an empty form, and confirm focus
returns to the named invalid field. The 320px-and-narrower layout uses a
single column of navigation links for browsers with native directional
focus. A browser that does not implement directional focus still needs Tab
or equivalent access keys; the site does not override arrow-key scrolling.
