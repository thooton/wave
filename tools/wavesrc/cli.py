"""Command line entry point for wavesrc."""

import argparse
from pathlib import Path

from artifact import verify_download
from release import publish
from source import Report, lint


def main(argv=None):
    parser = argparse.ArgumentParser(prog="wavesrc", description="Lint or publish a Wave source.")
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("lint", help="check a source checkout")
    check.add_argument("source", type=Path)
    check.add_argument("--download", action="store_true", help="download and verify every binary")
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
    if args.command == "lint":
        lint(args.source, report,
             verify_binary=(lambda manifest: verify_download(manifest, args.aapt2)) if args.download else None)
    else:
        publish(args, report)
    return int(bool(report.errors))


if __name__ == "__main__":
    raise SystemExit(main())
