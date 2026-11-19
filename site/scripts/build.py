#!/usr/bin/env python3
"""Stage only reviewed public assets in a *new* output directory."""
import argparse
from pathlib import Path
import shutil

SITE = Path(__file__).resolve().parents[1]
FILES = (
    'index.html', 'assets/site.css', 'assets/wave.svg', 'assets/handset.svg', 'assets/wave-home.png',
    'platforms/index.html', 'setup/index.html', 'setup/generator.mjs',
    'setup/descriptors.mjs', 'setup/payload.mjs',
    'jailbreak/index.html', 'rescue/index.html', 'restore/index.html',
    'src/jailbreak/common.mjs', 'src/jailbreak/probe.mjs',
    'src/jailbreak/rescue.mjs', 'src/jailbreak/restore.mjs',
    'src/protocols/adb.mjs', 'src/protocols/adb-credential.mjs',
    'src/protocols/webusb.mjs', 'src/protocols/gpt.mjs',
)


def build(destination):
    sources = [(path, SITE / path) for path in FILES]
    for path, source in sources:
        if not source.is_file() or source.resolve() != source:
            raise ValueError(f'Missing or indirect site asset: {path}')
    destination.mkdir(parents=True, exist_ok=False)
    for path, source in sources:
        target = destination / path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    (destination / '.nojekyll').touch()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', type=Path)
    build(parser.parse_args().destination)
