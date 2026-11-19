#!/usr/bin/env python3
"""Run every fixture scenario in order against one debug APK and index the reports."""
import argparse
from datetime import datetime, timezone
from html import escape
import json
from pathlib import Path
import re
import subprocess
import sys
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT / 'app/app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--wait-lock', type=int, default=600, metavar='SECONDS')
    parser.add_argument('--out', type=Path, help='new suite folder (default .oak/tmp/wave-qa/suite-<UTC>_<id>)')
    parser.add_argument('scenarios', nargs='*', type=Path, help='default: scenarios/07-*.json with a fixture')
    args = parser.parse_args()
    paths = args.scenarios or sorted(HERE.glob('scenarios/07-*.json'))
    paths = [p for p in paths if 'fixture' in json.loads(p.read_text(encoding='utf-8'))]
    if not paths:
        parser.error('no fixture scenarios')
    stamp = f'{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}_{uuid.uuid4().hex[:8]}'
    out = (args.out or ROOT / '.oak/tmp/wave-qa' / f'suite-{stamp}').resolve()
    out.mkdir(parents=True, mode=0o700, exist_ok=False)
    rows, worst = [], 0
    for path in paths:
        report = out / path.stem
        done = subprocess.run([sys.executable, str(HERE / 'run.py'), '--apk', str(args.apk), '--scenario', str(path),
                               '--wait-lock', str(args.wait_lock), '--out', str(report)],
                              capture_output=True, text=True, check=False)
        status = re.search(r'^(PASS|FAIL|INCOMPLETE):', done.stdout, re.M)
        detail = '; '.join(line for line in done.stdout.splitlines()[1:] if line and not line[:2].isdigit()
                           and ': ' not in line[:12])[:300] if done.returncode else ''
        rows.append((path.stem, status[1] if status else 'INCOMPLETE', detail or done.stderr.strip()[:300]))
        worst = max(worst, done.returncode)
        print(f'{rows[-1][1]:10} {path.stem}', flush=True)
    items = ''.join(f'<tr><td><a href="{escape(name)}/index.html">{escape(name)}</a></td>'
                    f'<td>{escape(status)}</td><td>{escape(detail)}</td></tr>' for name, status, detail in rows)
    (out / 'index.html').write_text(
        '<!doctype html><html lang="en"><meta charset="utf-8"><title>Wave QA suite</title>'
        '<body style="font:16px sans-serif;margin:2rem"><h1>Wave QA suite</h1>'
        '<table border="1" cellpadding="6"><thead><tr><th>Scenario</th><th>Status</th><th>Notes</th></tr></thead>'
        f'<tbody>{items}</tbody></table></body></html>\n', encoding='utf-8')
    print(out / 'index.html')
    return worst


if __name__ == '__main__':
    sys.exit(main())
