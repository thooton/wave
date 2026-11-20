#!/usr/bin/env python3
"""Build the rooted-QA Wave APK from a commit, in a scratch export (the checkout stays clean).

    apk.py [--commit REV] [--work DIR]    # prints the APK path

It is the commit's debug build with two changes, both only in the scratch copy:
the built-in source URL becomes https://localhost:4483/mods.git, and a
network-security config trusts rooted/source.py's throwaway localhost
certificate for localhost and 127.0.0.1. Nothing from it is shipped.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
import source  # noqa: E402

STORE = 'app/core/src/main/java/com/wave/xp3/core/sources/SourceStore.java'
MANIFEST = 'app/app/src/debug/AndroidManifest.xml'


def build(commit, work):
    cert, _ = source.certificate(work)
    scratch = work / 'apk-src'
    shutil.rmtree(scratch, ignore_errors=True)
    scratch.mkdir(parents=True)
    archive = subprocess.Popen(['git', '-C', str(ROOT), 'archive', commit], stdout=subprocess.PIPE)
    subprocess.run(['tar', '-x', '-C', str(scratch)], stdin=archive.stdout, check=True)
    if archive.wait():
        raise SystemExit(f'git archive {commit} failed')
    shutil.copy(ROOT / 'local.properties', scratch)
    store = scratch / STORE
    text = store.read_text()
    patched, n = re.subn(r'(DEFAULT_URL = )"[^"]*"', r'\1"https://localhost:4483/mods.git"', text)
    if n != 1:
        raise SystemExit('SourceStore.DEFAULT_URL not found; update rooted/apk.py')
    store.write_text(patched)
    manifest = scratch / MANIFEST
    text = manifest.read_text()
    if 'networkSecurityConfig' in text or text.count('android:usesCleartextTraffic="true"') != 1:
        raise SystemExit('debug manifest changed; update rooted/apk.py')
    manifest.write_text(text.replace('android:usesCleartextTraffic="true"',
                                     'android:usesCleartextTraffic="true"\n        android:networkSecurityConfig="@xml/rooted_qa"'))
    res = scratch / 'app/app/src/debug/res'
    (res / 'xml').mkdir(parents=True, exist_ok=True)
    (res / 'raw').mkdir(parents=True, exist_ok=True)
    shutil.copy(HERE / 'apk/rooted_qa.xml', res / 'xml/rooted_qa.xml')
    shutil.copy(cert, res / 'raw/rooted_qa_cert.pem')
    env = dict(os.environ, JAVA_HOME=os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-21-openjdk'))
    subprocess.run(['./gradlew', '--offline', '-q', ':app:app:assembleDebug'], cwd=scratch, env=env, check=True)
    out = work / f'wave-rooted-qa-{commit[:12]}.apk'
    shutil.copy(scratch / 'app/app/build/outputs/apk/debug/app-debug.apk', out)
    return out


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--commit', default='HEAD')
    parser.add_argument('--work', type=Path, default=ROOT / '.oak/tmp/wave-qa/rooted-work')
    args = parser.parse_args()
    sha = subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', args.commit], text=True).strip()
    print(build(sha, args.work.resolve()))
