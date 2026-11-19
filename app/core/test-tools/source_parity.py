#!/usr/bin/env python3
"""Compare source-reader and publisher decisions after Gradle :app:core:testClasses."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / 'app/core/build/classes/java'
COMMON = ROOT / 'common/core/build/classes/java'
CP = os.environ.get('WAVE_TEST_CLASSPATH') or os.pathsep.join(
    map(str, (APP / 'main', APP / 'test', COMMON / 'main', COMMON / 'testFixtures')))
EXAMPLE = ROOT / 'spec/example-source'
VARIANTS = ROOT / 'spec/source-fixtures'
FIXTURES = {
    'example': (None, None, (0, 0)),
    'bad-header': ('wave.json', 'wave.json', (1, 1)),
    'bad-package': ('package.json', 'applications/list/package.json', (1, 1)),
    'bad-date': ('package.json', 'applications/list/package.json', (1, 1)),
    'bad-icon': ('icon.png', 'applications/list/icon.png', (1, 1)),
    'huge-icon': ('icon.png', 'applications/list/icon.png', (1, 1)),
    'unknown-type': ('package.json', 'applications/list/package.json', (0, 1)),
}


def main():
    for name, (file, dest, expected) in FIXTURES.items():
        with tempfile.TemporaryDirectory(prefix='wave-parity-') as work:
            target = Path(work) / 'source'
            shutil.copytree(EXAMPLE, target)
            if file:
                shutil.copyfile(VARIANTS / name / file, target / dest)
            reader = subprocess.run(['java', '-cp', CP, 'com.wave.xp3.core.sources.SourcesSelfTest', str(target)],
                                    capture_output=True, text=True)
            writer = subprocess.run([str(ROOT / 'tools/wavesrc/wavesrc'), 'lint', str(target)],
                                    capture_output=True, text=True)
            actual = reader.returncode, writer.returncode
            if actual != expected or reader.stderr or writer.stderr:
                raise SystemExit(f'{name}: expected {expected}, got {actual}\n'
                                 f'Reader: {reader.stdout}{reader.stderr}\nWriter: {writer.stdout}{writer.stderr}')
            print(f'{name}: reader {actual[0]}, writer {actual[1]}')


if __name__ == '__main__':
    main()
