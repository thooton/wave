"""Command line entry point for wavesrc."""

import argparse
from pathlib import Path

from artifact import verify_asset, verify_download
from release import publish
from scaffold import create
from source import Invalid, Report, lint


def main(argv=None):
    parser = argparse.ArgumentParser(prog="wavesrc", description="Create, lint, verify or publish a Wave source.")
    commands = parser.add_subparsers(dest="command", required=True)
    new = commands.add_parser("new", help="create a small source that lints clean, ready to fill in",
                              description="Write wave.json, a generated icon, an applications/ and a system/ section, "
                                          "one placeholder package (lint passes, verify fails until its download is "
                                          "real), a README and a .gitignore into DIR.")
    new.add_argument("directory", metavar="DIR", type=Path)
    new.add_argument("--name", required=True, help="the source's name, at most 40 characters")
    new.add_argument("--id", default="com.example.app", dest="package_id",
                     help="id of the example package, normally your app's package name (default com.example.app)")
    new.add_argument("--maintainer", help="who runs the source; shown when someone adds it")
    new.add_argument("--force", action="store_true", help="overwrite the files this command writes")
    check = commands.add_parser("lint", help="check a source checkout")
    check.add_argument("source", type=Path)
    check.add_argument("--download", action="store_true", help="download and verify every binary")
    verify = commands.add_parser("verify", help="audit asset URLs, availability, size and SHA-256")
    verify.add_argument("source", type=Path)
    put = commands.add_parser("publish", help="generate one package manifest in a source checkout")
    for field in ("source", "meta", "icon", "binary", "changes-from", "changes-path"):
        put.add_argument(f"--{field}", required=True, type=Path)
    put.add_argument("--section", required=True, help="section folder name")
    put.add_argument("--folder", help="package folder name (by default derived from the display name)")
    put.add_argument("--url", required=True, help="immutable HTTPS release asset URL")
    for command in (check, put):
        command.add_argument("--aapt2", help="path to Android SDK aapt2 (needed for APK inspection)")
    args = parser.parse_args(argv)
    report = Report()
    if args.command == "new":
        try:
            written = create(args.directory, name=args.name, package_id=args.package_id,
                             maintainer=args.maintainer, force=args.force)
        except Invalid as exc:
            parser.exit(1, f"wavesrc new: {exc}\n")
        print(f"Created {args.directory} ({len(written)} files). Next: read {args.directory / 'README.md'}, "
              f"then `wavesrc lint {args.directory}`.")
        return 0
    if args.command == "lint":
        lint(args.source, report,
             verify_binary=(lambda manifest: verify_download(manifest, args.aapt2)) if args.download else None)
    elif args.command == "verify":
        lint(args.source, report, verify_binary=verify_asset)
    else:
        publish(args, report)
    return int(bool(report.errors))


if __name__ == "__main__":
    raise SystemExit(main())
