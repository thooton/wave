"""Inspect real artifacts without trusting filenames or their callers."""

import hashlib
import http.client
import os
import re
import shutil
import subprocess
import tempfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

from source import Invalid, LIMIT, https_url

BLOCK = 128 * 1024


def run(args, timeout=60):
    try:
        return subprocess.run([str(arg) for arg in args], capture_output=True, check=True,
                              encoding="utf-8", errors="replace", timeout=timeout).stdout
    except subprocess.CalledProcessError as exc:
        message = exc.stderr.strip() or f"exit status {exc.returncode}"
        raise Invalid(f"{args[0]}: {message}") from exc
    except subprocess.TimeoutExpired as exc:
        raise Invalid(f"{args[0]} did not finish in {timeout} seconds") from exc


def aapt2_command(explicit):
    if explicit:
        return explicit
    if os.environ.get("AAPT2"):
        return os.environ["AAPT2"]
    local = shutil.which("aapt2")
    if local:
        return local
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if not os.environ.get(var):
            continue
        candidates = Path(os.environ[var]).glob("build-tools/*/aapt2")
        installed = [p for p in candidates if p.is_file() and os.access(p, os.X_OK)]
        if installed:
            return max(installed, key=lambda p: tuple(map(int, re.findall(r"\d+", p.parent.name))))
    raise Invalid("aapt2 not found (pass --aapt2 or set ANDROID_HOME)")


def apk_identity(path, aapt2):
    out = run([aapt2_command(aapt2), "dump", "badging", path])
    package = next((line for line in out.splitlines() if line.startswith("package: ")), "")
    package_id = re.search(r"(?:^| )name='([^']+)'(?: |$)", package)
    version = re.search(r"(?:^| )versionName='([^']+)'(?: |$)", package)
    if not (package_id and version):
        raise Invalid("APK does not have a package name and versionName")
    return package_id[1], version[1]


def module_identity(path):
    try:
        with zipfile.ZipFile(path) as archive:
            entries = [entry for entry in archive.infolist() if entry.filename == "module.prop"]
            if len(entries) != 1:
                raise Invalid("module zip must contain exactly one top-level module.prop")
            if entries[0].file_size > LIMIT:
                raise Invalid("module.prop is larger than 64 KB")
            with archive.open(entries[0]) as stream:
                data = stream.read(LIMIT + 1)
            if len(data) > LIMIT:
                raise Invalid("module.prop is larger than 64 KB")
        text = data.decode("utf-8")
    except (zipfile.BadZipFile, RuntimeError, UnicodeError, EOFError) as exc:
        raise Invalid(f"cannot read module zip: {exc}") from exc
    props = {}
    for line in text.splitlines():
        key, eq, value = line.partition("=")
        if eq and key in ("id", "version"):
            if key in props:
                raise Invalid(f"duplicate {key} in module.prop")
            props[key] = value
    if not props.get("id") or not props.get("version"):
        raise Invalid("module.prop must have id and version")
    return props["id"], props["version"]


def identity(path, kind, aapt2):
    return apk_identity(path, aapt2) if kind == "apk" else module_identity(path)


def copy_and_digest(stream, dest=None, expected=None):
    digest, size = hashlib.sha256(), 0
    while True:
        count = BLOCK if expected is None else min(BLOCK, expected - size + 1)
        block = stream.read(count)
        if not block:
            break
        size += len(block)
        if expected is not None and size > expected:
            raise Invalid(f"download size exceeds {expected} bytes")
        digest.update(block)
        if dest is not None:
            dest.write(block)
    if expected is not None and size != expected:
        raise Invalid(f"download size is {size} bytes, expected {expected}")
    return size, digest.hexdigest()


class HttpsRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not https_url(newurl):
            raise Invalid("download redirect must use https://")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(url, dest, expected):
    opener = urllib.request.build_opener(HttpsRedirect())
    try:
        with opener.open(url, timeout=30) as stream:
            return copy_and_digest(stream, dest, expected)[1]
    except urllib.error.HTTPError as exc:
        exc.close()
        raise Invalid(f"download failed: HTTP {exc.code}") from exc
    except (urllib.error.URLError, http.client.HTTPException, TimeoutError, UnicodeError) as exc:
        reason = getattr(exc, "reason", exc)
        raise Invalid(f"download failed: {reason}") from exc


def verify_download(manifest, aapt2=None):
    download = manifest["download"]
    suffix = ".apk" if manifest["type"] == "apk" else ".zip"
    with tempfile.TemporaryDirectory(prefix="wavesrc-") as scratch:
        file = Path(scratch) / ("download" + suffix)
        with file.open("wb") as dest:
            digest = fetch(download["url"], dest, int(download["size"]))
        if digest != download["sha256"]:
            raise Invalid(f"download SHA-256 is {digest}, expected {download['sha256']}")
        binary_id, binary_version = identity(file, manifest["type"], aapt2)
        if binary_id != manifest["id"]:
            raise Invalid(f"binary id {binary_id!r} does not match manifest id {manifest['id']!r}")
        if binary_version != manifest["version"]:
            raise Invalid(f"binary version {binary_version!r} does not match manifest version {manifest['version']!r}")
