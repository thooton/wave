"""Tests for tools/ship with throwaway Git repositories and a fake GitHub."""
import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ship  # noqa: E402


def git(cwd, *args):
    return subprocess.run(['git', *args], cwd=cwd, check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.strip()


def write(root, path, text):
    file = Path(root, path)
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(text)


def commit(root, message):
    git(root, 'add', '-A')
    git(root, 'commit', '-qm', message)
    return git(root, 'rev-parse', 'HEAD')


def gradle(version, code=1):
    return f'android {{\n    defaultConfig {{\n        versionCode {code}\n        versionName "{version}"\n    }}\n}}\n'


def manifest(section, name, version, url):
    return json.dumps({'type': 'apk', 'section': section, 'version': version, 'name': name,
                       'download': {'url': url, 'size': 1, 'sha256': '0' * 64}})


class FakeGitHub(ship.GitHub):
    def __init__(self):
        super().__init__(['fake'])
        self.runs, self.releases = {}, set()

    def get(self, path):
        if '/actions/runs?head_sha=' in path:
            repo = path.split('/')[2]
            sha = path.split('head_sha=')[1].split('&')[0]
            return {'workflow_runs': self.runs.get((repo, sha), [])}
        if '/actions/workflows/' in path:
            repo = path.split('/')[2]
            return {'workflow_runs': [r for (name, _), runs in self.runs.items() if name == repo
                                      for r in runs if r.get('conclusion') == 'success']}
        if '/releases/tags/' in path:
            repo, tag = path.split('/')[2], path.rsplit('/', 1)[1]
            if (repo, tag) in self.releases:
                return {'tag_name': tag, 'draft': False, 'assets': [{'name': tag + '.apk', 'size': 9}]}
            return None
        raise AssertionError('unexpected read ' + path)

    def ci(self, repo, sha, status='completed', conclusion='success', name='Build', run_id=1):
        self.runs.setdefault((repo, sha), []).append({
            'id': run_id, 'name': name, 'workflow_id': 7, 'head_branch': 'master', 'head_sha': sha,
            'status': status, 'conclusion': conclusion, 'html_url': f'https://example.invalid/{run_id}'})


class ShipTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.workspace = base / 'work'
        self.workspace.mkdir()
        os.environ.update(GIT_AUTHOR_NAME='t', GIT_AUTHOR_EMAIL='t@example.invalid',
                          GIT_COMMITTER_NAME='t', GIT_COMMITTER_EMAIL='t@example.invalid')
        seeds = {
            'wave_packages': {
                'gradle/retired-apps.txt': '# retired\nvideo\n',
                'list/wave/package.json': json.dumps({'section': 'essentials'}),
                'list/android/app/build.gradle': gradle('1.0.0'),
                'list/SPEC.md': 'spec\n',
                'mods/launcher/wave/package.json': json.dumps({'section': 'system'}),
                'mods/launcher/module/module.prop': 'id=launcher_improved\nversion=1.0.0\nversionCode=1\n',
                'AGENTS.md': 'agents\n',
            },
            'wave_repository': {
                'wave.json': '{}',
                'essentials/list/package.json': manifest(
                    'essentials', 'List', '1.0.0',
                    'https://github.com/thooton/wave_packages/releases/download/list-1.0.0/list-1.0.0.apk'),
                'system/launcher-improved/package.json': manifest(
                    'system', 'Launcher', '1.0.0',
                    'https://github.com/thooton/wave_packages/releases/download/'
                    'launcher-improved-1.0.0/launcher-improved-1.0.0.zip'),
            },
            'wave_private_packages': {
                'secret/android/wave/package.json': json.dumps({'section': 'applications'}),
                'secret/android/app/build.gradle': gradle('2.0.0'),
                'applications/secret/package.json': manifest(
                    'applications', 'Secret', '2.0.0', 'https://api.github.com/repos/x/y/releases/assets/1'),
            },
        }
        self.repos = {}
        for repo, files in seeds.items():
            origin = base / (repo + '.git')
            git(base, 'init', '-q', '--bare', '-b', 'master', str(origin))
            seed = base / ('seed-' + repo)
            git(base, 'init', '-q', '-b', 'master', str(seed))
            for path, text in files.items():
                write(seed, path, text)
            commit(seed, 'seed')
            git(seed, 'push', '-q', str(origin), 'master')
            git(self.workspace, 'clone', '-q', str(origin), repo)
            self.repos[repo] = self.workspace / repo
        self.github = FakeGitHub()

    def run_ship(self, *extra):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = ship.main(['check', '--workspace', str(self.workspace), '--no-fetch', *extra], self.github)
        return code, out.getvalue()

    def push(self, repo):
        git(self.repos[repo], 'push', '-q', 'origin', 'HEAD:master')
        git(self.repos[repo], 'fetch', '-q', 'origin')

    def bump_list(self, version='1.1.0'):
        write(self.repos['wave_packages'], 'list/android/app/build.gradle', gradle(version, 2))
        return commit(self.repos['wave_packages'], 'List ' + version)

    def mirror_list(self, version='1.1.0'):
        write(self.repos['wave_repository'], 'essentials/list/package.json', manifest(
            'essentials', 'List', version,
            f'https://github.com/thooton/wave_packages/releases/download/list-{version}/list-{version}.apk'))
        sha = commit(self.repos['wave_repository'], 'Publish List')
        self.push('wave_repository')
        return sha

    def test_fresh_clones_have_nothing_to_ship(self):
        code, out = self.run_ship()
        self.assertEqual(code, 0, out)
        self.assertIn('no changes made from this checkout', out)
        self.assertIn('SHIPPED', out)

    def test_uncommitted_and_unpushed_work_blocks(self):
        write(self.repos['wave_packages'], 'list/notes.txt', 'scratch')
        code, out = self.run_ship()
        self.assertEqual(code, 1)
        self.assertIn('uncommitted or untracked', out)
        Path(self.repos['wave_packages'], 'list/notes.txt').unlink()
        self.bump_list()
        code, out = self.run_ship()
        self.assertEqual(code, 1)
        self.assertIn('1 commit(s) not on origin/master', out)

    def test_push_is_not_publication(self):
        sha = self.bump_list()
        self.push('wave_packages')
        self.github.ci('wave_packages', sha)
        code, out = self.run_ship()
        self.assertEqual(code, 1, out)
        self.assertIn('pushed: 1 commit(s)', out)
        self.assertIn('not published: list 1.1.0: no release list-1.1.0', out)

        self.github.releases.add(('wave_packages', 'list-1.1.0'))
        code, out = self.run_ship()
        self.assertEqual(code, 1)
        self.assertIn('release list-1.1.0 exists but the default catalog lists 1.0.0', out)

        code, out = self.run_ship('--hold', 'owner said do not publish yet')
        self.assertEqual(code, 0, out)
        self.assertIn('HELD', out)
        self.assertIn('SHIPPED EXCEPT HELD PUBLICATION', out)

        catalog = self.mirror_list()
        self.github.ci('wave_repository', catalog, name='Lint source')
        code, out = self.run_ship()
        self.assertEqual(code, 0, out)
        self.assertIn('published: list 1.1.0 is published in the default catalog', out)

    def test_ci_must_finish_green(self):
        sha = self.bump_list()
        self.push('wave_packages')
        self.github.releases.add(('wave_packages', 'list-1.1.0'))
        self.mirror_list()
        code, out = self.run_ship()
        self.assertEqual(code, 1)
        self.assertIn('no master workflow run', out)
        self.github.ci('wave_packages', sha, status='in_progress', conclusion=None)
        code, out = self.run_ship()
        self.assertIn('CI still running', out)
        self.github.ci('wave_packages', sha, conclusion='failure', run_id=2)
        code, out = self.run_ship()
        self.assertEqual(code, 1)
        self.assertIn('CI failed', out)
        self.assertIn('https://example.invalid/2', out)

    def test_catalog_audit_does_not_block_a_commit(self):
        sha = self.bump_list()
        self.push('wave_packages')
        self.github.ci('wave_packages', sha)
        self.github.ci('wave_packages', sha, conclusion='failure', name='Catalog freshness', run_id=3)
        state, text = ship.ci_state(self.github, ship.Git(self.repos['wave_packages']), 'wave_packages', sha)
        self.assertEqual(state, 'success', text)

    def test_cancelled_run_superseded_by_later_green_run(self):
        first = self.bump_list('1.1.0')
        self.push('wave_packages')
        self.github.ci('wave_packages', first, conclusion='cancelled')
        write(self.repos['wave_packages'], 'list/android/app/build.gradle', gradle('1.1.1', 3))
        later_tree = commit(self.repos['wave_packages'], 'someone else')
        self.push('wave_packages')
        self.github.ci('wave_packages', later_tree, run_id=5)
        # Only the cancelled run belongs to the first commit's checker.
        state, text = ship.ci_state(self.github, ship.Git(self.repos['wave_packages']), 'wave_packages', first)
        self.assertEqual(state, 'success', text)
        self.assertIn('superseded', text)

    def test_documentation_only_change_needs_no_release(self):
        write(self.repos['wave_packages'], 'list/SPEC.md', 'new spec\n')
        commit(self.repos['wave_packages'], 'Spec')
        self.push('wave_packages')
        code, out = self.run_ship()
        self.assertEqual(code, 0, out)
        self.assertIn('no publishable package changed', out)
        self.assertIn('note', out)

    def test_service_change_asks_for_deployment_check(self):
        write(self.repos['wave_packages'], 'services/recs/main.go', 'package main\n')
        commit(self.repos['wave_packages'], 'Service')
        self.push('wave_packages')
        code, out = self.run_ship()
        self.assertIn('services/recs changed: ship does not check its deployment', out)

    def test_mod_needs_manual_release(self):
        write(self.repos['wave_packages'], 'mods/launcher/module/module.prop',
              'id=launcher_improved\nversion=1.1.0\nversionCode=2\n')
        sha = commit(self.repos['wave_packages'], 'Launcher 1.1.0')
        self.push('wave_packages')
        self.github.ci('wave_packages', sha)
        code, out = self.run_ship()
        self.assertEqual(code, 1)
        self.assertIn('launcher-improved 1.1.0', out)
        self.assertIn('workflow_dispatch', out)

    def test_private_publication_is_owner_gated(self):
        repo = self.repos['wave_private_packages']
        write(repo, 'secret/android/app/build.gradle', gradle('2.1.0', 2))
        sha = commit(repo, 'Secret 2.1.0')
        self.push('wave_private_packages')
        self.github.ci('wave_private_packages', sha)
        code, out = self.run_ship()
        self.assertEqual(code, 1)
        self.assertIn('PRIVATE_PUBLISH_ENABLED', out)
        self.assertIn('private catalog', out)
        self.assertNotIn('releases/assets', out)
        write(repo, 'applications/secret/package.json', manifest(
            'applications', 'Secret', '2.1.0', 'https://api.github.com/repos/x/y/releases/assets/2'))
        commit(repo, 'CI publish')
        self.push('wave_private_packages')
        code, out = self.run_ship()
        self.assertIn('secret 2.1.0 is published in the private catalog', out)

    def test_private_asset_url_in_public_repo_blocks(self):
        write(self.repos['wave_packages'], 'AGENTS.md',
              'see https://api.github.com/repos/thooton/wave_private_packages/releases/assets/5\n')
        sha = commit(self.repos['wave_packages'], 'Docs')
        self.push('wave_packages')
        self.github.ci('wave_packages', sha)
        code, out = self.run_ship()
        self.assertEqual(code, 1)
        self.assertIn('private boundary', out)

    def test_fast_forward_pulls_are_not_own_commits(self):
        other = Path(self.tmp.name) / 'other'
        git(self.tmp.name, 'clone', '-q', str(Path(self.tmp.name) / 'wave_packages.git'), str(other))
        write(other, 'list/android/app/build.gradle', gradle('1.2.0', 3))
        commit(other, 'another worker')
        git(other, 'push', '-q', 'origin', 'master')
        git(self.repos['wave_packages'], 'pull', '-q', '--ff-only')
        self.assertEqual(ship.own_commits(ship.Git(self.repos['wave_packages'])), [])
        code, out = self.run_ship()
        self.assertEqual(code, 0, out)

    def test_rebased_commits_stay_own(self):
        other = Path(self.tmp.name) / 'other'
        git(self.tmp.name, 'clone', '-q', str(Path(self.tmp.name) / 'wave_packages.git'), str(other))
        write(other, 'AGENTS.md', 'other\n')
        commit(other, 'another worker')
        git(other, 'push', '-q', 'origin', 'master')
        self.bump_list()
        git(self.repos['wave_packages'], 'pull', '-q', '--rebase')
        head = git(self.repos['wave_packages'], 'rev-parse', 'HEAD')
        self.assertIn(head, ship.own_commits(ship.Git(self.repos['wave_packages'])))

    def test_since_counts_restored_work(self):
        base = git(self.repos['wave_packages'], 'rev-parse', 'HEAD')
        other = Path(self.tmp.name) / 'other'
        git(self.tmp.name, 'clone', '-q', str(Path(self.tmp.name) / 'wave_packages.git'), str(other))
        write(other, 'list/android/app/build.gradle', gradle('1.1.0', 2))
        commit(other, 'before the VM was replaced')
        git(other, 'push', '-q', 'origin', 'master')
        git(self.repos['wave_packages'], 'pull', '-q', '--ff-only')
        code, out = self.run_ship('--since', 'wave_packages=' + base)
        self.assertEqual(code, 1)
        self.assertIn('list 1.1.0', out)

    def test_audit_lists_unmirrored_public_versions(self):
        wave = Path(self.tmp.name) / 'wave.git'
        git(self.tmp.name, 'init', '-q', '--bare', '-b', 'master', str(wave))
        seed = Path(self.tmp.name) / 'seed-wave'
        git(self.tmp.name, 'init', '-q', '-b', 'master', str(seed))
        write(seed, 'app/wave/package.json', '{}')
        write(seed, 'app/app/build.gradle', gradle('0.1.0'))
        commit(seed, 'seed')
        git(seed, 'tag', 'wave-0.1.0')
        git(seed, 'push', '-q', '--tags', str(wave), 'master')
        git(self.workspace, 'clone', '-q', str(wave), 'wave')
        self.bump_list()
        self.push('wave_packages')
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = ship.main(['audit', '--workspace', str(self.workspace)])
        self.assertEqual(code, 1)
        self.assertIn('GAP   wave 0.1.0: catalog lists nothing; released, not mirrored', out.getvalue())
        self.assertIn('GAP   list 1.1.0: catalog lists 1.0.0; no list-1.1.0 release', out.getvalue())
        self.assertIn('ok    launcher-improved 1.0.0', out.getvalue())


if __name__ == '__main__':
    unittest.main()
