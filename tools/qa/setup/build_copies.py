#!/usr/bin/env python3
"""Build side-by-side test copies of the six apps: their sonim sources with `.wavetest` appended to the applicationId.

    python3 tools/qa/setup/build_copies.py OUT_DIR [--sonim ../sonim] [--rev HEAD]

A clean `git archive` of the sonim repo is edited in a scratch folder (the checkout is untouched) and built with
`./gradlew --offline :<app>:app:assembleDebug`; OUT_DIR gets <app>.apk. An app whose source hard-codes its own id would
break as a copy (Print and AppDrawer did), so the script fails if a built APK is not `<package>.wavetest`.
About 8 minutes on a cold Gradle cache.
"""
import argparse, os, re, shutil, subprocess, sys, tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from apps import APPS, COPY_SUFFIX  # noqa: E402

ROOT = Path(__file__).resolve().parents[3]
AAPT = Path(os.environ.get('AAPT', '/opt/android-sdk/build-tools/35.0.1/aapt'))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('out', type=Path)
    parser.add_argument('--sonim', type=Path, default=ROOT.parent / 'sonim')
    parser.add_argument('--rev', default='HEAD')
    parser.add_argument('--keep', action='store_true', help='keep the scratch export')
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    temp = Path(tempfile.mkdtemp(prefix='wave-setup-build-'))
    try:
        archive = subprocess.Popen(['git', '-C', str(args.sonim), 'archive', args.rev], stdout=subprocess.PIPE)
        subprocess.run(['tar', '-x', '-C', str(temp)], stdin=archive.stdout, check=True)
        if archive.wait():
            raise SystemExit('git archive failed')
        shutil.copy(ROOT / 'local.properties', temp)
        for app, info in APPS.items():
            gradle = temp / app / 'android/app/build.gradle'
            text = gradle.read_text()
            text, n = re.subn(r'(?m)^(\s*applicationId ")' + re.escape(info['package']) + '"', r'\1' + info['package'] + COPY_SUFFIX + '"', text)
            if n != 1:
                raise SystemExit(f'{app}: applicationId {info["package"]} not found in build.gradle')
            gradle.write_text(text)
        tasks = [f':{app}:app:assembleDebug' for app in APPS]
        env = dict(os.environ, JAVA_HOME=os.environ.get('JAVA_HOME', '/usr/lib/jvm/java-21-openjdk'))
        subprocess.run(['./gradlew', '--offline', '-q', *tasks], cwd=temp, env=env, check=True)
        for app, info in APPS.items():
            apk = temp / app / 'android/app/build/outputs/apk/debug/app-debug.apk'
            badging = subprocess.run([str(AAPT), 'dump', 'badging', str(apk)], capture_output=True, text=True, check=True).stdout
            if f"package: name='{info['package']}{COPY_SUFFIX}'" not in badging:
                raise SystemExit(f'{app}: the built APK is not {info["package"]}{COPY_SUFFIX}')
            shutil.copy(apk, out / f'{app}.apk')
            print(app, re.search(r"versionName='([^']+)'", badging)[1], out / f'{app}.apk')
    finally:
        if not args.keep:
            shutil.rmtree(temp, ignore_errors=True)


if __name__ == '__main__':
    main()
