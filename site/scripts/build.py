#!/usr/bin/env python3
"""Stage only reviewed public assets in a *new* output directory."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

SITE = Path(__file__).resolve().parents[1]
FILES = (
    # The rooting page (site root) and the recovery page.
    'index.html', 'recovery/index.html',
    'assets/style.css', 'assets/wave-icon.svg',
    'assets/fonts/cascadia-code.woff2', 'assets/fonts/cascadia-code-OFL.txt',
    'src/root/common.mjs', 'src/root/served.mjs', 'src/root/backup-manifest.mjs',
    'src/root/rooting.mjs', 'src/root/recovery.mjs', 'src/root/stock.mjs', 'src/root/rescue.mjs',
    'src/root/device.mjs', 'src/root/magisk.mjs',
    'src/protocols/adb.mjs', 'src/protocols/adb-credential.mjs', 'src/protocols/adb-sync.mjs',
    'src/protocols/webusb.mjs', 'src/protocols/boot-image.mjs', 'src/protocols/boot-signature.mjs',
    'src/protocols/sahara.mjs', 'src/protocols/firehose.mjs', 'src/protocols/firehose-write.mjs',
    'src/protocols/gpt.mjs', 'src/protocols/zip.mjs',
)
# The Sonim-signed MSM8909 programmer the pages accept (src/protocols/sahara.mjs).
PROGRAMMER_SHA256 = 'e6f2dbcfbed3ca72f23b4e1f0a53c510a620108eee2ac6b18a7835f1440e2767'
MAGISK_SHA256 = 'f7ac4e2a1a528ebaa1cdc20c0bd06a785e17b226e97823389779bf7c4d44346a'


def file_entry(path, name):
    data = path.read_bytes()
    if name.endswith('.apk') and data[:2] != b'PK':
        raise ValueError(f'{path} is not an APK')
    return data, {'file': name, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def build(destination, programmer=None, magisk_apk=None, wave_apk=None, wave_version=None):
    sources = [(path, SITE / path) for path in FILES]
    for path, source in sources:
        if not source.is_file() or source.resolve() != source:
            raise ValueError(f'Missing or indirect site asset: {path}')
    staged = {}
    if programmer:
        data, entry = file_entry(programmer, 'prog_emmc_firehose_8909_ddr.mbn')
        if entry['sha256'] != PROGRAMMER_SHA256:
            raise ValueError('--programmer is not the pinned Sonim MSM8909 programmer')
        staged['programmer'] = (data, entry)
    if magisk_apk:
        data, entry = file_entry(magisk_apk, 'Magisk-v24.0.apk')
        if entry['sha256'] != MAGISK_SHA256:
            raise ValueError('--magisk-apk is not the official Magisk 24.0 APK')
        staged['magisk'] = (data, entry)
    if wave_apk:
        data, entry = file_entry(wave_apk, 'wave.apk')
        if wave_version:
            entry['version'] = wave_version
        staged['wave'] = (data, entry)
    destination.mkdir(parents=True, exist_ok=False)
    for path, source in sources:
        target = destination / path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    if staged:
        # The pages fetch these by name from files.json on their own origin.
        folder = destination / 'files'
        folder.mkdir()
        for data, entry in staged.values():
            (folder / entry['file']).write_bytes(data)
        (folder / 'files.json').write_text(json.dumps({k: v[1] for k, v in staged.items()}, indent=2) + '\n', encoding='utf-8')
    (destination / '.nojekyll').touch()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', type=Path)
    parser.add_argument('--programmer', type=Path, help='Serve this Sonim programmer from the site (must be the pinned file)')
    parser.add_argument('--magisk-apk', type=Path, help='Serve this Magisk 24.0 APK from the site (must be the official release)')
    parser.add_argument('--wave-apk', type=Path, help='Serve this Wave APK from the site')
    parser.add_argument('--wave-version', help='Wave versionName to show for --wave-apk')
    args = parser.parse_args()
    build(args.destination, args.programmer, args.magisk_apk, args.wave_apk, args.wave_version)
