"""`wavesrc new`: write a small source that lints clean and that `verify` refuses until it is filled in."""

import colorsys
import hashlib
import json
import math
import re
import struct
import zlib
from pathlib import Path

from source import Invalid

SIZE = 96
ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
# Reserved by RFC 6761: never resolves, so `wavesrc verify` fails on it the same way on every machine.
PLACEHOLDER_URL = "https://example.invalid/replace-me/example-app-1.0.0.apk"


def chunk(kind, data):
    body = kind + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))


def icon_png(seed, size=SIZE):
    """A rounded square in a colour taken from `seed`, with a white wave across it. 8-bit RGBA, no libraries."""
    hue = int.from_bytes(hashlib.sha256(seed.encode("utf-8")).digest()[:2], "big") / 65536
    top = [round(255 * v) for v in colorsys.hsv_to_rgb(hue, 0.65, 0.78)]
    bottom = [round(255 * v) for v in colorsys.hsv_to_rgb(hue, 0.75, 0.55)]
    radius, thickness = size * 0.18, size * 0.07
    rows = bytearray()
    for y in range(size):
        rows.append(0)  # filter type: none
        for x in range(size):
            cx, cy = min(x, size - 1 - x), min(y, size - 1 - y)
            corner = radius - cx, radius - cy
            if corner[0] > 0 and corner[1] > 0 and math.hypot(*corner) > radius:
                rows += b"\0\0\0\0"  # transparent outside the rounded corner
                continue
            wave = size * 0.5 + size * 0.12 * math.sin((x / size) * 2 * math.pi * 2)
            if abs(y - wave) <= thickness and size * 0.16 <= x <= size * 0.84:
                rows += b"\xff\xff\xff\xff"
                continue
            mix = y / (size - 1)
            rows += bytes(round(top[i] + (bottom[i] - top[i]) * mix) for i in range(3)) + b"\xff"
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) +
            chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b""))


def files(name, package_id, maintainer):
    """Relative path to bytes for everything `new` writes."""
    header = {"wave": 1, "name": name,
              "description": "Apps and system mods for the Sonim XP3800."}
    if maintainer:
        header["maintainer"] = maintainer
    header["sections"] = {"applications": "Applications", "system": "System"}
    header["featured"] = [package_id]
    package = {
        "id": package_id,
        "type": "apk",
        "name": "Example App",
        "summary": "A placeholder package: replace it with your own",
        "description": "This entry shows the shape of a package. Replace it with your own app, or delete "
                       "the folder, before you share this source.",
        "version": "1.0.0",
        "download": {"url": PLACEHOLDER_URL, "size": 1, "sha256": "0" * 64},
        "requires": {"sdk": 27},
    }
    dump = lambda value: (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    return {
        "wave.json": dump(header),
        "icon.png": icon_png(name),
        "applications/example-app/package.json": dump(package),
        "applications/example-app/icon.png": icon_png(package_id),
        "system/.gitkeep": b"",
        ".gitignore": GITIGNORE.encode(),
        "README.md": readme(name, package_id).encode("utf-8"),
    }


GITIGNORE = """# Keep binaries out of the source: Wave fetches the whole latest commit (8 MB at most).
# Host APKs and module zips as release assets and point package.json at them.
*.apk
*.zip
*.aab
*.jks
*.keystore
*.pem
.DS_Store
"""


def readme(name, package_id):
    return f"""# {name}

A [Wave](https://github.com/thooton/wave) source: a Git repository of
manifests and icons that Wave reads on the Sonim XP3800. Binaries live
elsewhere, at permanent URLs. Keep this repository for the source alone.

## Next steps

1. Build your APK (or Magisk module zip) and upload it as a release asset
   whose URL never changes, for example
   `https://github.com/OWNER/REPO/releases/download/v1.0.0/app-1.0.0.apk`.
2. Open `applications/example-app/package.json`: set `id` and `version` to
   the APK's package name and `versionName`, and `download.url`,
   `download.size` (`stat -c %s FILE`) and `download.sha256`
   (`sha256sum FILE`) to the uploaded file's. Rename the folder to suit, using
   lowercase letters, digits and `-`. `featured` in `wave.json` lists `{package_id}`: change it to your id.
3. Put modules in `system/<name>/package.json` the same way (`"type": "module"`).
4. Replace the icons with your own square PNGs (96x96, under 64 KB).
5. Check it, from a clone of Wave:

   ```sh
   tools/wavesrc/wavesrc lint .
   tools/wavesrc/wavesrc lint --download --aapt2=/path/to/aapt2 .
   tools/wavesrc/wavesrc verify .
   ```

   `lint` passes on this scaffold. `verify` fails until the placeholder
   download URL is real, and that is the point: do not publish before it passes.
6. Push to a host that serves Git over HTTPS and add the repository's address in
   Wave under Sources, Add a Source.

The full walkthrough is in
[publishing a source](https://github.com/thooton/wave/blob/master/docs/publishing-a-source.md)
and the rules are in the
[source format](https://github.com/thooton/wave/blob/master/docs/source-format.md).
"""


def create(target, *, name, package_id, maintainer=None, force=False):
    """Write the scaffold into `target`. Returns the relative paths written."""
    if not 1 <= len(name) <= 40:
        raise Invalid("--name must be 1 to 40 characters")
    if not ID_PATTERN.fullmatch(package_id) or len(package_id) > 100:
        raise Invalid("--id must be letters, digits, '.', '_' or '-', starting with a letter or digit, at most 100 characters")
    if maintainer is not None and not 1 <= len(maintainer) <= 60:
        raise Invalid("--maintainer must be 1 to 60 characters")
    target = Path(target)
    if target.exists() and not target.is_dir():
        raise Invalid(f"{target} exists and is not a directory")
    content = files(name, package_id, maintainer)
    clash = sorted(path for path in content if (target / path).exists() or (target / path).is_symlink())
    if clash and not force:
        raise Invalid(f"{target} already has {', '.join(clash)}; nothing written. Use --force to overwrite those files "
                      "(other files are left alone)")
    for path, data in content.items():
        destination = target / path
        if destination.is_symlink():
            destination.unlink()
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    return sorted(content)
