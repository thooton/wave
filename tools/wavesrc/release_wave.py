#!/usr/bin/env python3
"""Admit an immutable Wave APK to GitHub Releases, then pass it to publish."""

import argparse
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

from artifact import aapt2_command, run
from source import Invalid

VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
SCOPE = ("app/core", "app/app", "common", "gradle", "signing", "build.gradle",
         "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat")
VERSION_CODE = re.compile(r"<!-- wave-version-code: ([0-9]+) -->")


def apk(path):
    badging = run([aapt2_command(None), "dump", "badging", path]).splitlines()
    package = next((line for line in badging if line.startswith("package: ")), "")
    fields = dict(re.findall(r"(\w+)='([^']*)'", package))
    version, code = fields.get("versionName", ""), fields.get("versionCode", "")
    if (fields.get("name") != "com.wave.xp3" or not re.fullmatch(VERSION, version)
            or not code.isascii() or not code.isdecimal() or int(code) < 1):
        raise Invalid(f"{path}: expected com.wave.xp3 with a numeric x.y.z versionName and versionCode")
    return version, int(code)


def released_tags():
    refs = run(["git", "tag", "--list", "wave-*"]).splitlines()
    return sorted((tag for tag in refs if re.fullmatch("wave-" + VERSION, tag)),
                  key=lambda tag: tuple(map(int, tag[5:].split("."))))


def check_source(ref):
    status = subprocess.run(["git", "diff", "--quiet", ref + "^{commit}", "HEAD", "--", *SCOPE,
                             ":(glob,exclude)**/*.md", ":(glob,exclude)**/mockups/**"]).returncode
    if status:
        raise Invalid(f"{ref}: Wave changed; bump versionName and versionCode before releasing")


def view(tag, optional=False):
    fields = "assets,body,isDraft,targetCommitish"
    result = subprocess.run(["gh", "release", "view", tag, "--json", fields], capture_output=True,
                            text=True, timeout=60)
    if result.returncode:
        if optional and ("release not found" in result.stderr.lower() or "HTTP 404" in result.stderr):
            return None
        raise Invalid(result.stderr.strip() or f"could not read release {tag}")
    return json.loads(result.stdout)


def recorded_code(tag, release):
    match = VERSION_CODE.search(release["body"] or "")
    if not match:
        raise Invalid(f"{tag}: missing recorded APK versionCode")
    return int(match[1])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("binary", type=Path)
    parser.add_argument("--retry-publish", action="store_true")
    parser.add_argument("--check", action="store_true", help="check APK and local tags without GitHub calls")
    args = parser.parse_args()
    version, code = apk(args.binary)
    tag = f"wave-{version}"
    tags = released_tags()
    if sorted(tags + [tag], key=lambda t: tuple(map(int, t[5:].split("."))))[-1] != tag:
        raise Invalid(f"{tag}: older than the current Wave release {tags[-1]}")
    existing = tag in tags
    if existing:
        check_source(tag)
    if args.check:
        print(f"{'existing' if existing else 'new'} {tag} (versionCode {code})")
        return

    head = run(["git", "rev-parse", "HEAD"]).strip()
    if head != os.environ["GITHUB_SHA"]:
        raise Invalid("release must use the workflow's commit")
    release = view(tag, optional=not existing)
    destination = Path("release-assets") / f"{tag}.apk"
    if release is None:
        if tags and code <= recorded_code(tags[-1], view(tags[-1])):
            raise Invalid(f"{tag}: bump versionCode above the previous release")
        destination.parent.mkdir(exist_ok=True)
        shutil.copyfile(args.binary, destination)
        run(["gh", "release", "create", tag, destination, "--draft", "--target", head,
             "--title", f"Wave {version}", "--notes", f"<!-- wave-version-code: {code} -->"], timeout=180)
        release = view(tag)
    elif not existing:
        # A previous (possibly cancelled) run can leave a draft and no Git tag.
        check_source(release["targetCommitish"])
    assets = [asset for asset in release["assets"] if asset["name"] == destination.name]
    if len(assets) != 1 or not assets[0]["size"] or code != recorded_code(tag, release):
        raise Invalid(f"{tag}: expected unchanged asset {destination.name}; never replace release bytes")
    if release["isDraft"]:
        run(["gh", "release", "edit", tag, "--draft=false"])
    if existing and not args.retry_publish:
        return
    if not destination.is_file():
        run(["gh", "release", "download", tag, "--pattern", destination.name,
             "--dir", destination.parent], timeout=180)
    if apk(destination) != (version, code):
        raise Invalid(f"{destination}: hosted APK version differs from the built version")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        print(f"binary={destination.as_posix()}", file=output)
        print(f"url=https://github.com/{os.environ['GITHUB_REPOSITORY']}/releases/download/{tag}/{destination.name}",
              file=output)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.TimeoutExpired) as exc:
        raise SystemExit(str(exc)) from exc
