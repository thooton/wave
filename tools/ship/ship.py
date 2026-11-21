#!/usr/bin/env python3
"""Check that a Wave worker's changes are committed, pushed, green and published.

`ship check` inspects the sibling Wave checkouts in a worker's folder. It finds
the commits the worker made from each checkout's reflog, then reports, per
repository: uncommitted work, unpushed commits, the master CI result for the
worker's newest pushed commit, and, for every package those commits changed,
whether its version has an immutable release and is in its catalog. A push is
not publication: a package counts as published only when its catalog lists
the new version. Any gap is a blocker unless the task explicitly said not to
publish (`--hold REASON`). The exit status is 0 only when nothing blocks.

`ship audit` checks every public package in local checkouts (CI uses it):
each master version must be released and in the default catalog.
"""
import argparse
import base64
import fnmatch
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

OWNER = 'thooton'
REPOS = ('wave', 'wave_packages', 'wave_private_packages', 'wave_repository')
PUBLIC = {'wave', 'wave_packages', 'wave_repository'}
CATALOG = 'wave_repository'
BUILD_FILES = ['common/', 'gradle/', 'signing/', 'build.gradle', 'settings.gradle',
               'gradle.properties', 'gradlew', 'gradlew.bat']
# Reflog actions that record a commit this checkout made (a fast-forward, reset or
# rebase start only moves to commits that already existed).
OWN_ACTION = re.compile(r'^(commit( \((amend|initial|merge)\))?|cherry-pick|revert'
                        r'|.*\((pick|reword|edit|squash|fixup|continue)\))$')
GOOD_CONCLUSIONS = {'success', 'skipped', 'neutral'}
# Repository-wide audits, not the commit's own checks: reported, never blocking.
AUDIT_WORKFLOWS = {'Catalog freshness'}
VERSION_NAME = re.compile(r'^\s*versionName\s*[= ]\s*["\']([^"\']+)["\']', re.M)


class Git:
    """Read-only access to one checkout; `rev` trees are read without checking out."""

    def __init__(self, root):
        self.root = Path(root)

    def run(self, *args, check=True):
        result = subprocess.run(['git', *args], cwd=self.root, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if check and result.returncode:
            raise RuntimeError(f'git {" ".join(args)}: {result.stderr.strip()}')
        return result.stdout if result.returncode == 0 else None

    def files(self, rev):
        out = self.run('ls-tree', '-r', '--name-only', rev, check=False)
        return out.splitlines() if out else []

    def show(self, rev, path):
        return self.run('show', f'{rev}:{path}', check=False)

    def has(self, rev):
        return self.run('rev-parse', '-q', '--verify', rev + '^{commit}', check=False) is not None

    def tags(self):
        out = self.run('tag', '--list', check=False)
        return set(out.split()) if out else set()


class Package:
    def __init__(self, repo, name, path, version, tag, catalog_repo, catalog_path, scope,
                 private=False, kind='apk'):
        self.repo, self.name, self.path, self.version, self.tag = repo, name, path, version, tag
        self.catalog_repo, self.catalog_path, self.scope = catalog_repo, catalog_path, scope
        self.private, self.kind = private, kind

    def touches(self, path):
        if path.endswith('.md') or '/mockups/' in path or path == 'gradle/retired-apps.txt':
            return False
        return any(path == s or (s.endswith('/') and path.startswith(s)) for s in self.scope)


def gradle_version(text):
    match = VERSION_NAME.search(text or '')
    return match[1] if match else None


def prop_version(text):
    props = dict(line.split('=', 1) for line in (text or '').splitlines() if '=' in line)
    return props.get('version')


def section_of(git, rev, meta):
    try:
        return json.loads(git.show(rev, meta) or '{}').get('section')
    except ValueError:
        return None


def inventory(repo, git, rev):
    """The packages a build repository's tree at `rev` publishes."""
    files = git.files(rev)
    packages = []
    if repo == 'wave_packages':
        retired = {line.strip() for line in (git.show(rev, 'gradle/retired-apps.txt') or '').splitlines()
                   if line.strip() and not line.startswith('#')}
        for meta in sorted(fnmatch.filter(files, '*/wave/package.json')):
            parts = meta.split('/')
            if len(parts) == 3 and parts[0] not in retired:
                path = parts[0]
                gradles = [f for f in files if f.startswith(path + '/') and f.endswith('build.gradle')
                           and '/build/' not in f]
                version = next(filter(None, (gradle_version(git.show(rev, f)) for f in sorted(gradles))), None)
                packages.append(Package(repo, path, path, version, f'{path}-{version}', CATALOG,
                                        f'{section_of(git, rev, meta)}/{path}/package.json',
                                        [path + '/'] + BUILD_FILES))
            elif len(parts) == 4 and parts[0] == 'mods':
                path, name = 'mods/' + parts[1], parts[1] + '-improved'
                version = prop_version(git.show(rev, f'{path}/module/module.prop'))
                packages.append(Package(repo, name, path, version, f'{name}-{version}', CATALOG,
                                        f'{section_of(git, rev, meta)}/{name}/package.json',
                                        [path + '/', 'mods/firmware/', 'mods/tools/'], kind='mod'))
    elif repo == 'wave' and 'app/wave/package.json' in files:
        version = gradle_version(git.show(rev, 'app/app/build.gradle'))
        packages.append(Package(repo, 'wave', 'app', version, f'wave-{version}', CATALOG,
                                # Wave's build publishes it with `section: tools`.
                                f'{section_of(git, rev, "app/wave/package.json") or "tools"}/wave/package.json',
                                ['app/'] + BUILD_FILES))
    elif repo == 'wave_private_packages':
        for meta in sorted(fnmatch.filter(files, '*/android/wave/package.json')):
            app = meta.split('/')[0]
            if app == 'applications' or meta.count('/') != 3:
                continue
            version = gradle_version(git.show(rev, f'{app}/android/app/build.gradle'))
            packages.append(Package(repo, app, app, version, f'{app}-{version}', repo,
                                    f'applications/{app}/package.json', [app + '/'] + BUILD_FILES,
                                    private=True))
    return packages


def version_key(version):
    try:
        return tuple(int(part) for part in version.split('.'))
    except (AttributeError, ValueError):
        return ()


class GitHub:
    """Repository-scoped REST reads through `oak gh api` (or `gh api`)."""

    def __init__(self, command=None):
        self.command = command

    @classmethod
    def detect(cls):
        if shutil.which('oak'):
            return cls(['oak', 'gh', 'api'])
        if shutil.which('gh'):
            return cls(['gh', 'api'])
        return cls(None)

    def get(self, path):
        """The decoded JSON, None for 404, or raise for anything else."""
        if not self.command:
            raise RuntimeError('no `oak gh` or `gh` for GitHub reads')
        result = subprocess.run([*self.command, path], text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            if 'Not Found' in result.stdout + result.stderr or 'HTTP 404' in result.stderr:
                return None
            raise RuntimeError(f'GitHub read {path} failed: {(result.stderr or result.stdout).strip()[:200]}')
        return json.loads(result.stdout or 'null')


class Report:
    def __init__(self):
        self.lines, self.blockers, self.held = [], 0, 0

    def add(self, state, text):
        if state == 'BLOCKED':
            self.blockers += 1
        elif state == 'HELD':
            self.held += 1
        self.lines.append(f'  {state:<7} {text}')

    def head(self, text):
        self.lines.append(text)


def own_commits(git):
    """Commits this checkout created, newest first, from its HEAD reflog."""
    out = git.run('reflog', '--format=%H%x09%gs', 'HEAD', check=False) or ''
    seen, commits = set(), []
    for line in out.splitlines():
        sha, _, subject = line.partition('\t')
        action = subject.split(':', 1)[0]
        if OWN_ACTION.match(action) and sha not in seen:
            seen.add(sha)
            commits.append(sha)
    return commits


def changed_paths(git, sha):
    out = git.run('diff-tree', '--root', '-r', '-m', '--no-commit-id', '--name-only', sha, check=False)
    return set((out or '').split('\n')) - {''}


def is_ancestor(git, sha, rev):
    return subprocess.run(['git', 'merge-base', '--is-ancestor', sha, rev], cwd=git.root,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0


def fetch(git, repo):
    command = ['oak', 'git'] if shutil.which('oak') else ['git']
    for _ in range(3):
        result = subprocess.run([*command, 'fetch', '--quiet', '--tags', 'origin'], cwd=git.root,
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode == 0:
            return True, ''
    return False, (result.stderr or result.stdout).strip()[:200] or f'exit {result.returncode}'


def superseded(github, git, repo, run, sha):
    """A cancelled run counts if a later master run of its workflow passed on a descendant."""
    data = github.get(f'repos/{OWNER}/{repo}/actions/workflows/{run.get("workflow_id")}/runs'
                      '?branch=master&status=success&per_page=30')
    for later in (data or {}).get('workflow_runs', []):
        head = later.get('head_sha')
        if head and head != sha and git.has(head) and is_ancestor(git, sha, head):
            return later
    return None


def ci_state(github, git, repo, sha):
    """(state, text) for every master workflow run of `sha`."""
    data = github.get(f'repos/{OWNER}/{repo}/actions/runs?head_sha={sha}&branch=master&per_page=100')
    runs = [r for r in (data or {}).get('workflow_runs', []) if r.get('head_branch') == 'master'
            and r.get('name') not in AUDIT_WORKFLOWS]
    if not runs:
        return 'none', 'no master workflow run for this commit'
    latest = {}
    for run in sorted(runs, key=lambda r: (r.get('id') or 0, r.get('run_attempt') or 0)):
        latest[run.get('name')] = run
    for name, run in list(latest.items()):
        if run.get('status') == 'completed' and run.get('conclusion') == 'cancelled':
            later = superseded(github, git, repo, run, sha)
            if later:
                latest[name] = dict(later, name=f'{name} (superseded by {later["head_sha"][:7]})')
    pending = [r for r in latest.values() if r.get('status') != 'completed']
    failed = [r for r in latest.values() if r.get('status') == 'completed'
              and r.get('conclusion') not in GOOD_CONCLUSIONS]
    names = ', '.join(sorted(f'{r["name"]}={r.get("conclusion") or r.get("status")}' for r in latest.values()))
    if failed:
        return 'failed', names + ' ' + ' '.join(r.get('html_url', '') for r in failed)
    if pending:
        return 'pending', names
    return 'success', names


def release_state(github, git, package):
    """'released', 'missing' or 'unknown: why'."""
    if package.repo != 'wave_private_packages' or github.command:
        try:
            release = github.get(f'repos/{OWNER}/{package.repo}/releases/tags/{package.tag}')
        except RuntimeError as error:
            if package.tag in git.tags():
                return 'unknown: tag exists, but its assets could not be read: ' + str(error)
            return 'unknown: ' + str(error)
        if release is None:
            return 'missing'
        if release.get('draft') or not any(a.get('size', 0) > 0 for a in release.get('assets', [])):
            return 'unknown: release has no published asset'
        return 'released'
    return 'released' if package.tag in git.tags() else 'missing'


def catalog_entry(checkouts, github, package):
    """The catalog's package.json for `package` on its master, or None."""
    git = checkouts.get(package.catalog_repo)
    if git and git.has('origin/master'):
        text = git.show('origin/master', package.catalog_path)
    else:
        data = github.get(f'repos/{OWNER}/{package.catalog_repo}/contents/{package.catalog_path}?ref=master')
        text = base64.b64decode(data['content']).decode() if data else None
    try:
        return json.loads(text) if text else None
    except ValueError:
        return None


def publication(checkouts, github, git, package):
    """(state, text) for one package's current version."""
    where = 'private catalog' if package.private else 'default catalog'
    label = f'{package.name} {package.version}'
    if not package.version:
        return 'BLOCKED', f'{package.name}: could not read its version'
    try:
        entry = catalog_entry(checkouts, github, package)
    except RuntimeError as error:
        return 'BLOCKED', f'{label}: could not read the {where}: {error}'
    listed = (entry or {}).get('version')
    url = ((entry or {}).get('download') or {}).get('url', '')
    if listed == package.version and (package.private or package.tag in url):
        return 'ok', f'{label} is published in the {where}'
    if listed and version_key(listed) > version_key(package.version):
        return 'BLOCKED', f'{label}: the {where} lists newer {listed}; never lower a version'
    release = release_state(github, git, package)
    if release == 'released':
        if package.private:
            why = (f'released, but the {where} lists {listed or "nothing"}: private CI writes applications/ '
                   'only while the owner has enabled PRIVATE_PUBLISH_ENABLED; get owner authorization '
                   'or report this blocker')
        else:
            why = (f'release {package.tag} exists but the {where} lists {listed or "nothing"}: mirror it with '
                   'Wave publishing (CI publish step or `tools/wavesrc/wavesrc publish`), push wave_repository')
        return 'gap', f'{label}: {why}'
    if release == 'missing':
        if package.private:
            why = (f'no private release yet and the {where} lists {listed or "nothing"}: the private '
                   'publish job runs only when the owner has enabled PRIVATE_PUBLISH_ENABLED; get owner '
                   'authorization or report this blocker')
        elif package.kind == 'mod':
            why = (f'no release {package.tag}: mods release only from a manual workflow_dispatch of '
                   '"Build and publish XP3800 packages" on master, then mirror into wave_repository')
        else:
            why = (f'no release {package.tag}: wait for the master build to release it, or fix its failure '
                   '(a build change needs a versionName and versionCode bump)')
        return 'gap', f'{label}: {why}'
    return 'BLOCKED', f'{label}: release status {release}'


def private_leaks(git, commits):
    """Public-repo lines that would expose private release assets or binaries."""
    leaks = []
    for sha in commits:
        for path in changed_paths(git, sha):
            if re.search(r'\.(apk|aab)$', path):
                leaks.append(f'{sha[:7]} commits binary {path}')
        out = git.run('show', '--format=', '--unified=0', '--no-ext-diff', sha, check=False) or ''
        for line in out.splitlines():
            if line.startswith('+') and not line.startswith('+++') and re.search(
                    r'wave_private_packages/releases/(assets|download)', line):
                leaks.append(f'{sha[:7]} adds a private release asset URL')
    return leaks


def check_repo(repo, git, checkouts, github, args, report):
    branch = (git.run('branch', '--show-current', check=False) or '').strip() or '(detached)'
    head = (git.run('rev-parse', '--short', 'HEAD', check=False) or '').strip()
    report.head(f'{repo} ({branch} {head})')
    if not args.no_fetch:
        ok, error = fetch(git, repo)
        if not ok:
            report.add('BLOCKED', f'fetch failed ({error}); origin/master may be stale')
    status = git.run('status', '--porcelain', '--untracked-files=normal', check=False) or ''
    dirty = [line[3:] for line in status.splitlines() if line]
    if dirty:
        report.add('BLOCKED', f'{len(dirty)} uncommitted or untracked path(s): {", ".join(dirty[:5])}'
                   + (' …' if len(dirty) > 5 else '') + '; commit them, or delete scratch files')
    if not git.has('origin/master'):
        report.add('BLOCKED', 'no origin/master to compare with')
        return
    if git.run('rev-list', '--count', 'origin/master..HEAD', check=False).strip() != '0':
        count = git.run('rev-list', '--count', 'origin/master..HEAD').strip()
        report.add('BLOCKED', f'{count} commit(s) not on origin/master: rebase on origin/master, rerun checks, '
                   '`oak git push origin HEAD:master` (never force-push)')
    commits = [c for c in own_commits(git) if git.has(c)]
    if args.since:
        base = args.since.get(repo)
        if base:
            out = git.run('rev-list', f'{base}..origin/master', check=False) or ''
            commits = list(dict.fromkeys(commits + out.split()))
    pushed = [c for c in commits if is_ancestor(git, c, 'origin/master')]
    paths = set().union(*(changed_paths(git, c) for c in commits)) if commits else set()
    paths |= set(dirty)
    if not commits and not dirty:
        report.add('ok', 'no changes made from this checkout')
        return
    touched = [p for p in inventory(repo, git, 'origin/master') if any(map(p.touches, paths))]
    if pushed:
        newest = max(pushed, key=lambda c: int(git.run('rev-list', '--count', c).strip()))
        report.add('ok', f'pushed: {len(pushed)} commit(s) on origin/master, newest {newest[:7]}')
        if args.no_ci:
            report.add('BLOCKED', 'CI not checked (--no-ci)')
        else:
            try:
                state, text = ci_state(github, git, repo, newest)
            except RuntimeError as error:
                state, text = 'error', str(error)
            if state == 'success':
                report.add('ok', f'CI on master for {newest[:7]}: {text}')
            elif state == 'none':
                subject = git.run('log', '-1', '--format=%B', newest) or ''
                if '[skip ci]' in subject or '[ci skip]' in subject:
                    report.add('ok', f'CI skipped by [skip ci] on {newest[:7]}')
                elif touched:
                    report.add('BLOCKED', f'{newest[:7]}: {text}; a package changed, so its build must run')
                else:
                    report.add('note', f'{newest[:7]}: {text} (workflow path filters may skip this change; '
                               'if a run should start, wait and rerun)')
            elif state == 'pending':
                report.add('BLOCKED', f'CI still running for {newest[:7]}: {text}; wait, then rerun ship check')
            else:
                report.add('BLOCKED', f'CI {state} for {newest[:7]}: {text}')
    if repo in PUBLIC and commits:
        for leak in private_leaks(git, commits):
            report.add('BLOCKED', f'private boundary: {leak}')
    services = sorted({'/'.join(path.split('/')[:2]) for path in paths
                       if path.startswith('services/') and path.count('/') >= 2})
    for service in services:
        report.add('note', f'{service} changed: ship does not check its deployment; verify it as its '
                   'README says and report the result')
    if repo == CATALOG and pushed:
        report.add('ok', 'catalog commits are the publication; Lint source CI above verifies them')
    if not touched:
        if repo != CATALOG:
            report.add('ok', 'no publishable package changed (no release needed)')
        return
    for package in touched:
        try:
            state, text = publication(checkouts, github, git, package)
        except RuntimeError as error:
            state, text = 'BLOCKED', f'{package.name}: {error}'
        if state == 'gap':
            state = 'HELD' if args.hold else 'BLOCKED'
            if args.hold:
                text += f' (held: {args.hold})'
        report.add(state, ('published: ' if state == 'ok' else 'not published: ') + text)


def discover(workspace):
    checkouts = {}
    for repo in REPOS:
        root = Path(workspace, repo)
        if (root / '.git').exists():
            checkouts[repo] = Git(root)
    return checkouts


def default_workspace():
    here = Path(__file__).resolve()
    candidate = here.parents[3] if len(here.parents) > 3 else Path.cwd()
    top = subprocess.run(['git', 'rev-parse', '--show-toplevel'], text=True, stdout=subprocess.PIPE,
                         stderr=subprocess.DEVNULL)
    if top.returncode == 0 and Path(top.stdout.strip()).name in REPOS:
        return Path(top.stdout.strip()).parent
    return candidate


def check(args, github=None):
    workspace = Path(args.workspace or default_workspace())
    checkouts = discover(workspace)
    report = Report()
    report.head(f'Wave ship check in {workspace}' + (f' (publication held: {args.hold})' if args.hold else ''))
    if not checkouts:
        report.add('BLOCKED', 'no Wave checkouts (wave, wave_packages, wave_private_packages, wave_repository)')
    github = github or GitHub.detect()
    for repo, git in checkouts.items():
        try:
            check_repo(repo, git, checkouts, github, args, report)
        except RuntimeError as error:
            report.add('BLOCKED', f'{repo}: {error}')
    if report.blockers:
        result = f'BLOCKED: {report.blockers} blocker(s). Fix them, or report each one as a blocker.'
    elif report.held:
        result = f'SHIPPED EXCEPT HELD PUBLICATION ({report.held}): {args.hold}'
    else:
        result = 'SHIPPED: everything changed is committed, pushed, green and published (or needs no release).'
    report.head('Result: ' + result)
    print('\n'.join(report.lines))
    return 1 if report.blockers else 0


def audit(args):
    """Every public package on master is released and in the default catalog (local checkouts)."""
    workspace = Path(args.workspace or default_workspace())
    checkouts = discover(workspace)
    missing = [r for r in ('wave', 'wave_packages', CATALOG) if r not in checkouts]
    if missing:
        print('missing checkouts: ' + ', '.join(missing), file=sys.stderr)
        return 2
    github = GitHub(None)
    gaps = 0
    for repo in ('wave', 'wave_packages'):
        git = checkouts[repo]
        rev = 'origin/master' if git.has('origin/master') else 'HEAD'
        for package in inventory(repo, git, rev):
            if package.repo == CATALOG:
                continue
            entry_text = checkouts[CATALOG].show('origin/master' if checkouts[CATALOG].has('origin/master')
                                                 else 'HEAD', package.catalog_path)
            entry = json.loads(entry_text) if entry_text else {}
            listed = entry.get('version')
            if listed == package.version:
                print(f'ok    {package.name} {package.version}')
                continue
            released = package.tag in git.tags()
            gaps += 1
            print(f'GAP   {package.name} {package.version}: catalog lists {listed or "nothing"}; '
                  + ('released, not mirrored' if released else f'no {package.tag} release'))
    print(f'{gaps} unpublished public package version(s)' if gaps else 'catalog is current')
    return 1 if gaps else 0


def parse_since(values):
    since = {}
    for value in values or []:
        repo, sep, rev = value.partition('=')
        if not sep or repo not in REPOS:
            raise SystemExit(f'--since expects REPO=REV with REPO in {", ".join(REPOS)}')
        since[repo] = rev
    return since


def main(argv=None, github=None):
    parser = argparse.ArgumentParser(prog='ship', description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    c = sub.add_parser('check', help="verify this worker's changes are shipped")
    c.add_argument('--workspace', help='folder holding the Wave checkouts (default: next to this one)')
    c.add_argument('--hold', metavar='REASON',
                   help='the task explicitly said not to publish: report gaps as held, not blocked')
    c.add_argument('--since', action='append', metavar='REPO=REV',
                   help='also count origin/master commits after REV as yours (e.g. after a VM restore)')
    c.add_argument('--no-fetch', action='store_true', help='use the last fetched origin/master')
    c.add_argument('--no-ci', action='store_true', help='skip CI reads (reported as a blocker)')
    a = sub.add_parser('audit', help='every public master version is released and in the catalog')
    a.add_argument('--workspace')
    args = parser.parse_args(argv)
    if args.command == 'check':
        args.since = parse_since(args.since)
        if args.hold is not None and not args.hold.strip():
            raise SystemExit('--hold needs the reason the task gave')
        return check(args, github)
    return audit(args)


if __name__ == '__main__':
    sys.exit(main())
