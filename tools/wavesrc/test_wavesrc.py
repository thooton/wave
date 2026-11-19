import contextlib
import hashlib
import io
import json
import os
import shutil
import struct
import subprocess
import tempfile
import unittest
import urllib.request
import zipfile
import zlib
from pathlib import Path
from unittest import mock

import artifact
import cli
from source import Invalid, LIMIT

ROOT = Path(__file__).resolve().parents[2]
EXAMPLE = ROOT / "spec/example-source"
LIST = Path("applications/list/package.json")
MODULE = Path("system/messages-improved/package.json")
ASSET = "https://example.invalid/releases/fixtures"


def chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))


def png(width, height):
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes((width * 3 + 1) * height))) + chunk(b"IEND", b""))


def write_json(path, data):
    path.write_text(json.dumps(data), encoding="utf-8")


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def git(repo, *options):
    return subprocess.run(["git", "-C", str(repo), *map(str, options)], check=True,
                          capture_output=True, text=True).stdout.strip()


def init(repo):
    git(repo, "init", "-b", "master")
    git(repo, "config", "user.name", "Test Publisher")
    git(repo, "config", "user.email", "publisher@example.invalid")


class Fixtures(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory(prefix="wavesrc-test-")
        self.addCleanup(scratch.cleanup)
        self.tmp = Path(scratch.name)
        self.source = self.tmp / "source"
        shutil.copytree(EXAMPLE, self.source)

    def cli(self, *args):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = cli.main(list(map(str, args)))
        return status, output.getvalue()

    def lint(self, *args):
        return self.cli("lint", self.source, *args)

    def mutate(self, path, fn):
        path = self.source / path
        value = read_json(path)
        fn(value)
        write_json(path, value)

    def assertLintError(self, path, message):
        status, output = self.lint()
        self.assertEqual(1, status, output)
        self.assertIn(f"{self.source / path}: {message}", output)


class LintTest(Fixtures):
    def test_example_and_reference_warnings(self):
        status, output = self.lint()
        self.assertEqual(0, status, output)
        self.assertIn("warning: replaces id 'messages_argon' not in this source", output)
        self.mutate(LIST, lambda p: p.update(depends=["missing_dep", {"id": "messages_improved", "min": "1.0"}],
                                            conflicts=["missing_conflict"], custom={"url": "http://ignored.example"}))
        status, output = self.lint()
        self.assertEqual(0, status, output)
        self.assertIn("warning: depends id 'missing_dep'", output)
        self.assertIn("warning: conflicts id 'missing_conflict'", output)
        self.assertNotIn("warning: depends id 'messages_improved'", output)

    def test_required_root_and_fields(self):
        path = self.source / "wave.json"
        write_json(path, {})
        self.assertLintError("wave.json", "missing required field name")
        path.unlink()
        self.assertLintError("wave.json", "expected a regular file")

    def test_bad_schema_types_and_constraints(self):
        samples = [
            ("wave.json", lambda s: s.update(wave=True), "wave must be an integer"),
            ("wave.json", lambda s: s.update(name="a" * 41), "name must have at most 40 characters"),
            ("wave.json", lambda s: s.update(sections={"system\n": "System"}), "sections has invalid section folder name"),
            ("wave.json", lambda s: s.update(featured=[".hidden"]), "featured[0] must match a package id"),
            (LIST, lambda p: p.pop("summary"), "missing required field summary"),
            (LIST, lambda p: p.update(type="new-type"), "type must be one of apk, module"),
            (LIST, lambda p: p.update(version="1.1-rc1"), "version must match Dot-separated numbers"),
            (LIST, lambda p: p.update(summary="two\nlines"), "summary must match one line"),
            (LIST, lambda p: p["download"].update(sha256="a" * 64 + "\n"), "download.sha256 must match 64 lowercase"),
            (LIST, lambda p: p["download"].update(size=-1), "download.size must be at least 1"),
            (LIST, lambda p: p.update(depends=[{"min": "1.0"}]), "missing required field depends[0].id"),
            (LIST, lambda p: p.update(depends=[{"id": "ok", "min": "v1"}]), "depends[0].min must match Dot-separated"),
            (LIST, lambda p: p.update(requires={"device": []}), "requires.device must have at least 1 item"),
            (LIST, lambda p: p["changes"][0].update(date="2026-02-30"), "changes[0].date is not a calendar date"),
            (LIST, lambda p: p.update(changes=p["changes"] * 11), "changes must have at most 10 items"),
        ]
        for path, mutate, issue in samples:
            with self.subTest(issue=issue):
                self.mutate(path, mutate)
                self.assertLintError(path, issue)
                shutil.copyfile(EXAMPLE / path, self.source / path)

    def test_exact_bounded_integers(self):
        path = self.source / "wave.json"
        for good in ("1e0", "9223372036854775807.0"):
            path.write_text('{"wave": ' + good + ', "name": "Source"}', encoding="utf-8")
            self.assertEqual(0, self.lint()[0])
        for bad, issue in [("9223372036854775808.0", "wave must be at most 9223372036854775807"),
                           ("1.00000000000000000001", "wave must be an integer")]:
            path.write_text('{"wave": ' + bad + ', "name": "Source"}', encoding="utf-8")
            self.assertLintError("wave.json", issue)
        shutil.copyfile(EXAMPLE / "wave.json", path)
        self.mutate(LIST, lambda p: p["download"].update(size=9223372036854775808))
        self.assertLintError(LIST, "download.size must be at most 9223372036854775807")

    def test_strict_json_and_limit(self):
        path = self.source / LIST
        for data, issue in [(b"{" * (LIMIT + 1), "larger than 64 KB"),
                            (b"\xff", "invalid UTF-8 JSON"),
                            (b'{"id":"one","id":"two"}', "invalid UTF-8 JSON: duplicate JSON key 'id'"),
                            (b'{"size":NaN}', "invalid UTF-8 JSON: invalid JSON value 'NaN'"),
                            (b'"\\ud800"', "invalid UTF-8 JSON"),
                            (b'[}', "invalid UTF-8 JSON")]:
            with self.subTest(issue=issue):
                path.write_bytes(data)
                self.assertLintError(LIST, issue)

    def test_layout_exact_depth_and_names(self):
        deeper = self.source / "other-files/another/deeper"
        deeper.mkdir(parents=True)
        (deeper / "package.json").write_text("not JSON", encoding="utf-8")
        (self.source / "other-files/package.json").write_text("not JSON", encoding="utf-8")
        (self.source / "README.md").write_text("ignored", encoding="utf-8")
        self.assertEqual(0, self.lint()[0])
        shutil.copytree(self.source / LIST.parent, self.source / "Bad-Section/-list")
        status, output = self.lint()
        self.assertEqual(1, status, output)
        self.assertIn("Bad-Section: folder name must start", output)
        self.assertIn("-list: folder name must start", output)
        self.assertIn("duplicate id 'com.list.xp3'", output)

    def test_symlink_manifest_and_optional_icons(self):
        path = self.source / LIST
        path.unlink()
        path.symlink_to(EXAMPLE / LIST)
        self.assertLintError(LIST, "expected a regular file (not a symlink)")
        path.unlink()
        shutil.copyfile(EXAMPLE / LIST, path)
        for icon in self.source.rglob("icon.png"):
            icon.unlink()
        self.assertEqual(0, self.lint()[0])

    def test_png_dimensions_signature_integrity_and_limit(self):
        path = self.source / "icon.png"
        square = png(3, 3)
        for data, issue in [(png(2, 3), "PNG must be square (got 2x3)"),
                            (b"GIF89a", "expected a PNG"),
                            (square[:-12], "incomplete PNG"),
                            (square[:-1] + b"\x00", "truncated or corrupt PNG"),
                            (square + bytes(LIMIT), "larger than 64 KB")]:
            with self.subTest(issue=issue):
                path.write_bytes(data)
                self.assertLintError("icon.png", issue)
        path.write_bytes(square)
        self.assertEqual(0, self.lint()[0])

    def test_https_urls(self):
        self.mutate(LIST, lambda p: p.update(homepage="http://example.invalid"))
        self.assertLintError(LIST, "homepage must match an https:// URL")
        for url in ("https://", "https:///no-host", "https://host:bogus", "https://host/a b", "https://host/a\\b"):
            with self.subTest(url=url):
                self.mutate(LIST, lambda p: (p.pop("homepage", None), p["download"].update(url=url)))
                self.assertLintError(LIST, "download.url must be an https:// URL with a host")


class PublishTest(Fixtures):
    def setUp(self):
        super().setUp()
        self.repo = self.tmp / "packages"
        self.package = self.repo / "messages source"
        self.package.mkdir(parents=True)
        init(self.repo)
        tracked = self.package / "app.txt"
        tracked.write_text("first", encoding="utf-8")
        self.commit("Messages Improved v1.1: open the thread")
        tracked.write_text("second", encoding="utf-8")
        self.commit("Other 2.0 & Messages Improved 1.3: fix the inbox")
        (self.package / "README.md").write_text("ignore", encoding="utf-8")
        (self.package / "mockups").mkdir()
        (self.package / "mockups" / "ignored.png").write_bytes(b"ignore")
        self.commit("Docs only: ignore this")
        tracked.write_text("third", encoding="utf-8")
        self.commit("restore notifications")
        self.binary = self.tmp / "messages.zip"
        self.zip("messages_improved", "1.3")
        self.meta = self.tmp / "meta.json"
        write_json(self.meta, {"type": "module", "id": "messages_improved", "name": "Messages Improved",
                               "summary": "Fixes and improvements", "version": "0.0.0", "download": "ignored",
                               "requires": {"sdk": 27}, "changes": "ignored"})
        self.icon = self.tmp / "icon.png"
        self.icon.write_bytes(png(3, 3))

    def commit(self, message):
        git(self.repo, "add", "--", self.package.name)
        git(self.repo, "commit", "-m", message)

    def zip(self, package_id, version):
        with zipfile.ZipFile(self.binary, "w") as archive:
            archive.writestr("module.prop", f"id={package_id}\r\nname=Messages Improved\r\nversion={version}\r\nversionCode=3\r\n")

    def publish(self, *options, section="system"):
        return self.cli("publish", "--source", self.source, f"--section={section}", "--meta", self.meta,
                        "--icon", self.icon, "--binary", self.binary, "--url", ASSET,
                        "--changes-from", self.repo, "--changes-path", self.package.name, *options)

    def test_module_publish_notes_and_idempotence(self):
        status, output = self.publish()
        self.assertEqual(0, status, output)
        data = read_json(self.source / MODULE)
        self.assertEqual("messages_improved", data["id"])
        self.assertEqual("1.3", data["version"])
        self.assertEqual({"url": ASSET, "size": self.binary.stat().st_size,
                          "sha256": hashlib.sha256(self.binary.read_bytes()).hexdigest()}, data["download"])
        self.assertEqual([("1.3", "Restore notifications."), ("1.3", "Fix the inbox."), ("1.1", "Open the thread.")],
                         [(note["version"], note["text"]) for note in data["changes"]])
        self.assertTrue(all(len(note["date"]) == 10 for note in data["changes"]))
        self.assertEqual(self.icon.read_bytes(), (self.source / MODULE.with_name("icon.png")).read_bytes())
        first = (self.source / MODULE).read_bytes()
        self.assertEqual(0, self.publish()[0])
        self.assertEqual(first, (self.source / MODULE).read_bytes())
        self.assertEqual(0, self.lint()[0])

    def test_publish_rejects_id_mismatch_without_writing(self):
        self.zip("incorrect_id", "1.3")
        status, output = self.publish()
        self.assertEqual(1, status, output)
        self.assertIn("static id 'messages_improved' does not match binary id 'incorrect_id'", output)
        self.assertEqual((EXAMPLE / MODULE).read_bytes(), (self.source / MODULE).read_bytes())

    def test_publish_checks_result_before_writing(self):
        self.icon.write_bytes(png(3, 5))
        status, output = self.publish()
        self.assertEqual(1, status, output)
        self.assertIn("PNG must be square", output)
        self.assertEqual((EXAMPLE / MODULE).read_bytes(), (self.source / MODULE).read_bytes())
        self.assertEqual((EXAMPLE / MODULE.with_name("icon.png")).read_bytes(),
                         (self.source / MODULE.with_name("icon.png")).read_bytes())

    def test_publish_no_downgrade_or_reuse(self):
        self.zip("messages_improved", "1.1.50")
        status, output = self.publish()
        self.assertEqual(1, status, output)
        self.assertIn("refusing to replace newer version", output)
        for version in ("1.2.0", "1.2"):
            self.zip("messages_improved", version)
            status, output = self.publish()
            self.assertEqual(1, status, output)
            self.assertIn("already points to a different release", output)

    def test_publish_new_directory_and_safe_names(self):
        self.zip("new_module", "1.0")
        self.mutate(self.meta, lambda p: p.pop("id"))
        for args in ({"section": "../unsafe"}, {"section": "-system"}):
            self.assertIn("invalid section name", self.publish(**args)[1])
        self.assertIn("invalid package folder name", self.publish("--folder", "..", section="another")[1])
        (self.source / "redirected").symlink_to(self.tmp)
        self.assertIn("expected a directory (not a symlink)", self.publish(section="redirected")[1])
        self.assertIn("refusing to overwrite another package", self.publish()[1])
        status, output = self.publish("--folder", "new-module")
        self.assertEqual(0, status, output)
        self.assertTrue((self.source / "system/new-module/package.json").is_file())

    def test_composite_push_retries_a_real_local_race(self):
        # Use only a disposable local bare repository; no network or credentials.
        init(self.source)
        git(self.source, "add", "--", "wave.json", "icon.png", "applications", "system")
        git(self.source, "commit", "-m", "Create source")
        remote = self.tmp / "remote.git"
        git(self.tmp, "clone", "--bare", self.source, remote)
        git(self.source, "remote", "add", "origin", remote)
        competitor = self.tmp / "competitor"
        git(self.tmp, "clone", remote, competitor)
        (competitor / "README.md").write_text("competing update\n", encoding="utf-8")
        git(competitor, "add", "--", "README.md")
        git(competitor, "-c", "user.name=Other Publisher", "-c", "user.email=other@example.invalid",
            "commit", "-m", "Publish another update")
        marker = self.tmp / "race-fired"
        hook = self.source / ".git/hooks/pre-push"
        hook.write_text('''#!/bin/sh
set -e
if [ ! -e "$RACE_MARKER" ]; then
    touch "$RACE_MARKER"
    git -C "$COMPETITOR" push origin HEAD:refs/heads/master
fi
''', encoding="utf-8")
        hook.chmod(0o755)
        git(self.source, "config", "core.hooksPath", hook.parent)
        env = {**os.environ, "SOURCE": str(self.source), "WAVESRC": str(ROOT / "tools/wavesrc/wavesrc"),
               "SECTION": "system", "FOLDER": "", "META": str(self.meta), "ICON": str(self.icon),
               "BINARY": str(self.binary), "URL": ASSET, "CHANGES_FROM": str(self.repo),
               "CHANGES_PATH": self.package.name, "AAPT2_PATH": "", "RACE_MARKER": str(marker),
               "COMPETITOR": str(competitor)}
        action = ["bash", ROOT / "publish/push.sh"]
        result = subprocess.run(action, env=env, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("default branch advanced", result.stderr)
        self.assertEqual("competing update", git(remote, "show", "HEAD:README.md"))
        self.assertEqual("1.3", json.loads(git(remote, "show", f"HEAD:{MODULE.as_posix()}"))["version"])
        head = git(remote, "rev-parse", "HEAD")
        result = subprocess.run(action, env=env, capture_output=True, text=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("nothing to publish", result.stdout)
        self.assertEqual(head, git(remote, "rev-parse", "HEAD"))

    def test_real_apk_publish(self):
        path = os.environ.get("WAVESRC_TEST_APK")
        if path:
            self.binary = Path(path).resolve()
            self.assertTrue(self.binary.is_file(), self.binary)
        else:
            self.binary = ROOT.parent / "sonim/list/android/app/build/outputs/apk/release/app-release.apk"
        try:
            aapt2 = artifact.aapt2_command(None)
        except Invalid:
            if path:
                raise
            self.skipTest("aapt2 required for real APK integration")
        if not self.binary.is_file():
            sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
            platforms = sorted((Path(sdk) / "platforms").glob("*/android.jar")) if sdk else []
            if not platforms:
                self.skipTest("set WAVESRC_TEST_APK or install an Android SDK platform")
            manifest = self.tmp / "AndroidManifest.xml"
            manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.wavesrc.fixture" android:versionCode="9" android:versionName="2.1.0">
            <uses-sdk android:minSdkVersion="27"/><application android:label="Fixture"/>
            </manifest>''', encoding="utf-8")
            self.binary = self.tmp / "fixture.apk"
            artifact.run([aapt2, "link", "-I", platforms[-1], "--manifest", manifest, "-o", self.binary])
        expected_id, expected_version = artifact.apk_identity(self.binary, aapt2)
        write_json(self.meta, {"type": "apk", "name": "APK fixture", "summary": "A real built APK"})
        status, output = self.publish("--aapt2", aapt2, section="applications")
        self.assertEqual(0, status, output)
        target = LIST if expected_id == "com.list.xp3" else Path("applications/apk-fixture/package.json")
        data = read_json(self.source / target)
        self.assertEqual((expected_id, expected_version), (data["id"], data["version"]))
        self.assertEqual(self.binary.stat().st_size, data["download"]["size"])
        self.assertEqual(0, self.lint()[0])
        with mock.patch("artifact.urllib.request.build_opener") as opener:
            opener.return_value.open.side_effect = lambda *a, **kw: self.binary.open("rb")
            artifact.verify_download(data, aapt2)


class DownloadTest(Fixtures):
    def test_download_lint_checks_size_hash_and_identity(self):
        (self.source / LIST).unlink()
        file = self.tmp / "fixture.zip"
        prop = "id=messages_improved\nversion=1.2.0\n"
        with zipfile.ZipFile(file, "w") as archive:
            archive.writestr("module.prop", prop)
        data = file.read_bytes()
        download = dict(url=ASSET, size=len(data), sha256=hashlib.sha256(data).hexdigest())
        self.mutate(MODULE, lambda p: p.update(download=download))
        with mock.patch("artifact.urllib.request.build_opener") as opener:
            open_ = opener.return_value.open
            open_.side_effect = lambda *a, **kw: io.BytesIO(data)
            self.assertEqual(0, self.lint("--download")[0])
            open_.assert_called_with(ASSET, timeout=30)
            for fields, diagnostic in [({"size": len(data) - 1}, "download size exceeds"),
                                       ({"size": len(data) + 1}, "download size is"),
                                       ({"sha256": "a" * 64}, "download SHA-256 is")]:
                self.mutate(MODULE, lambda p: p.update(download={**download, **fields}))
                status, output = self.lint("--download")
                self.assertEqual(1, status, output)
                self.assertIn(diagnostic, output)
            self.mutate(MODULE, lambda p: p.update(download=download, id="another"))
            self.assertIn("does not match manifest id 'another'", self.lint("--download")[1])
            self.mutate(MODULE, lambda p: p.update(id="messages_improved", version="1.3.0"))
            self.assertIn("does not match manifest version '1.3.0'", self.lint("--download")[1])

    def test_module_prop_and_invalid_zip(self):
        file = self.tmp / "module.zip"
        file.write_bytes(b"not a zip")
        with self.assertRaisesRegex(Invalid, "cannot read module zip"):
            artifact.module_identity(file)
        for name, props, message in [("folder/module.prop", "id=a\nversion=1", "top-level module.prop"),
                                     ("module.prop", "id=a\n", "must have id and version"),
                                     ("module.prop", "id=a\nid=b\nversion=1", "duplicate id"),
                                     ("module.prop", b"x" * (LIMIT + 1), "larger than 64 KB")]:
            with self.subTest(message=message):
                with zipfile.ZipFile(file, "w") as archive:
                    archive.writestr(name, props)
                with self.assertRaisesRegex(Invalid, message):
                    artifact.module_identity(file)

    def test_https_redirects(self):
        handler = artifact.HttpsRedirect()
        req = urllib.request.Request(ASSET)
        for url in ("http://example.invalid", "ftp://example.invalid"):
            with self.assertRaisesRegex(Invalid, "redirect must use https://"):
                handler.redirect_request(req, None, 302, "Found", {}, url)
        self.assertEqual(ASSET + "?x=1", handler.redirect_request(req, None, 302, "Found", {}, ASSET + "?x=1").full_url)


if __name__ == "__main__":
    unittest.main()
