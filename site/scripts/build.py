#!/usr/bin/env python3
"""Stage only reviewed public assets in a *new* output directory."""
import argparse
from pathlib import Path
import shutil

SITE = Path(__file__).resolve().parents[1]
FILES = (
    'index.html', 'assets/site.css', 'assets/wave.svg', 'assets/handset.svg', 'assets/screens/home.webp', 'assets/screens/sources.webp', 'assets/screens/details.webp',
    'assets/screens/installed.webp', 'assets/screens/search.webp',
    'platforms/index.html', 'setup/index.html', 'setup/generator.mjs',
    'setup/descriptors.mjs', 'setup/payload.mjs',
    'jailbreak/index.html', 'rescue/index.html', 'restore/index.html',
    'src/jailbreak/common.mjs', 'src/jailbreak/probe.mjs',
    'src/jailbreak/rescue.mjs', 'src/jailbreak/restore.mjs', 'src/jailbreak/backup-manifest.mjs',
    'src/protocols/adb.mjs', 'src/protocols/adb-credential.mjs',
    'src/protocols/webusb.mjs', 'src/protocols/boot-image.mjs',
)

BACKUP_FILES = (
    'backup/index.html', 'src/jailbreak/backup.mjs',
    'src/protocols/sahara.mjs', 'src/protocols/firehose.mjs', 'src/protocols/gpt.mjs',
)
BACKUP_MARKER = '<!-- OPTIONAL_DEVICE_BACKUP -->'
BACKUP_LINK = (
    '<section class="box" aria-labelledby="backup-title"><h2 id="backup-title">Start here: read a boot backup</h2>'
    '<p>This build can read your phone\u2019s <code>boot</code> partition over USB, twice, compare the copies and '
    'save the image with a manifest. It only reads; no partition is written. Then '
    '<a href="../restore/#verify">verify the saved files</a>. Linux may need a driver detached first: see the '
    '<a href="../platforms/">USB guide</a>.</p>'
    '<p class="actions"><a class="button" href="../backup/">Read an XP3800 boot backup</a></p></section>'
)


def build(destination, with_device_backup=False):
    paths = FILES + (BACKUP_FILES if with_device_backup else ())
    sources = [(path, SITE / path) for path in paths]
    for path, source in sources:
        if not source.is_file() or source.resolve() != source:
            raise ValueError(f'Missing or indirect site asset: {path}')
    destination.mkdir(parents=True, exist_ok=False)
    for path, source in sources:
        target = destination / path
        target.parent.mkdir(parents=True, exist_ok=True)
        if path == 'jailbreak/index.html':
            page = source.read_text(encoding='utf-8')
            if page.count(BACKUP_MARKER) != 1:
                raise ValueError('Expected one optional device backup navigation marker')
            target.write_text(page.replace(BACKUP_MARKER, BACKUP_LINK if with_device_backup else ''), encoding='utf-8')
        else:
            shutil.copyfile(source, target)
    (destination / '.nojekyll').touch()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', type=Path)
    parser.add_argument('--with-device-backup', action='store_true', help='Include the read-only EDL backup page; no programmer binary')
    args = parser.parse_args()
    build(args.destination, args.with_device_backup)
