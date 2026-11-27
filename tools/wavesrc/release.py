"""Generate one manifest from the built binary, static metadata and Git history."""

import json
import os
import re
import stat
import tempfile
from pathlib import Path

from artifact import copy_and_digest, identity, run
from source import FOLDER, Invalid, SCHEMAS, mode, package_paths, read_json, schema_issues, small_file, lint

GENERATED = ("id", "version", "download", "changes")
DOCS = ("**/*.md", "**/mockups/**")
LEAD_SPLIT = re.compile(r"\s*(?:,|&|/|\band\b)\s*", re.I)
NOTE_VERSION = re.compile(r"(.+?)\s+v?([0-9]+(?:\.[0-9]+)*)", re.I)


def note(subject, names, version):
    lead, sep, rest = subject.partition(":")
    text = subject
    if sep and rest.strip():
        for part in LEAD_SPLIT.split(lead.strip()):
            match = NOTE_VERSION.fullmatch(part)
            name = match[1] if match else part
            if name.casefold() in names:
                text = rest.strip()
                if match:
                    version = match[2]
                break
    text = text[:1].upper() + text[1:]
    if len(text) > 500:
        text = text[:499].rstrip() + "…"
    elif text[-1:] not in ".!?…":
        text = text[:499].rstrip() + "."
    return version, text


def changes(repository, directory, name, folder, version):
    repo = repository.resolve()
    root = Path(run(["git", "-C", repo, "rev-parse", "--show-toplevel"]).rstrip("\n"))
    directory = (repo / directory).resolve()
    try:
        relative = directory.relative_to(root)
    except ValueError as exc:
        raise Invalid("changes path must be inside the changes repository") from exc
    if not directory.is_dir():
        raise Invalid("expected changes path to be a directory")
    prefix = "" if relative == Path(".") else re.sub(r"([\\*?\[\]])", r"\\\1", relative.as_posix()) + "/"
    scope = [f":(top,literal){relative.as_posix()}"] if prefix else ["."]
    exclusions = [f":(top,glob,exclude){prefix}{docs}" for docs in DOCS]
    git = ["git", "-C", root]
    tag_prefix = f"{folder}-"
    tag_list = [*git, "tag", "--list", f"{tag_prefix}*"]
    tags = {}
    for tag in run([*tag_list, "--merged", "HEAD"]).splitlines():
        tagged_version = tag[len(tag_prefix):]
        if re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", tagged_version) and version_key(tagged_version) <= version_key(version):
            tags[tag] = tagged_version
    current_tag = tag_prefix + version
    tip = f"refs/tags/{current_tag}" if current_tag in tags else "HEAD"
    history = run([*git, "log", "--no-merges", "-n", "10", "-z", "--format=%H%x00%cs%x00%s",
                   tip, "--", *scope, *exclusions])
    notes = []
    names = {name.casefold(), folder.casefold(), directory.name.casefold()}
    fields = history.split("\0")
    for commit, date, subject in zip(fields[::3], fields[1::3], fields[2::3]):
        if not subject.strip():
            continue
        release, text = note(subject.strip(), names, None)
        if release is None and tags:
            # Use the first release that contains the change, not the version
            # being published now. Off-branch and unrelated tags do not count.
            containing = run([*tag_list, "--merged", tip, "--contains", commit]).splitlines()
            release = min((tags[t] for t in containing if t in tags),
                          key=version_key, default=None)
        notes.append({"version": release or version, "date": date, "text": text})
    return notes


def version_key(version):
    parts = [p.lstrip("0") for p in version.split(".")]
    while parts and not parts[-1]:
        parts.pop()
    return [(len(part), part) for part in parts]


def destination(root, section, folder, manifest):
    existing = None
    for path in package_paths(root):
        try:
            current = read_json(small_file(path))
        except (Invalid, OSError):
            continue  # The final lint reports malformed manifests with their paths.
        if isinstance(current, dict) and current.get("id") == manifest["id"]:
            if path.parent.parent.name != section or (folder and path.parent.name != folder):
                raise Invalid(f"id {manifest['id']!r} is already published in {path.parent}; move it explicitly first")
            folder = path.parent.name
            existing = current
    folder = folder or re.sub(r"[^a-z0-9]+", "-", manifest["name"].lower()).strip("-")
    if not FOLDER.fullmatch(folder):
        raise Invalid("cannot choose a valid package folder; pass --folder")
    path = root / section / folder / "package.json"
    for directory in (path.parent.parent, path.parent):
        if mode(directory) and not stat.S_ISDIR(mode(directory)):
            raise Invalid(f"{directory}: expected a directory (not a symlink)")
    for target in (path, path.with_name("icon.png")):
        if mode(target) and not stat.S_ISREG(mode(target)):
            raise Invalid(f"{target}: expected a regular file (not a symlink)")
    if mode(path) and existing is None:
        raise Invalid(f"{path}: refusing to overwrite another package or an unreadable manifest")
    if existing:
        version = existing.get("version")
        doc = "package.schema.json"
        if not list(schema_issues(version, SCHEMAS[doc]["$defs"]["version"], doc)):
            old, new = version_key(version), version_key(manifest["version"])
            if new < old:
                raise Invalid(f"{path}: refusing to replace newer version {version}")
            if new == old and (version != manifest["version"] or existing.get("download") != manifest["download"]):
                raise Invalid(f"{path}: version {version} already points to a different release; bump the version")
    return path


def write_package(path, manifest, icon):
    path.parent.mkdir(parents=True, exist_ok=True)
    # Stage both files completely before replacing either. Write the icon
    # first, so a newly published manifest never precedes its icon.
    with tempfile.TemporaryDirectory(prefix=".wavesrc-", dir=path.parent) as scratch:
        files = [(path.with_name("icon.png"), icon), (path, manifest)]
        for target, data in files:
            (Path(scratch) / target.name).write_bytes(data)
        for target, _ in files:
            os.replace(Path(scratch) / target.name, target)


def publish(args, report):
    root = args.source
    if not root.is_dir():
        report.emit(root, "expected a source directory")
        return
    if not FOLDER.fullmatch(args.section):
        report.emit(root, "invalid section name; use lowercase letters, digits and '-' (not first)")
        return
    if args.folder and not FOLDER.fullmatch(args.folder):
        report.emit(root, "invalid package folder name; use lowercase letters, digits and '-' (not first)")
        return
    path = args.meta
    try:
        meta = read_json(small_file(path))
        if not isinstance(meta, dict) or meta.get("type") not in ("apk", "module"):
            raise Invalid("static metadata must be an object with type 'apk' or 'module'")
        if not isinstance(meta.get("name"), str) or not meta["name"]:
            raise Invalid("static metadata must have a name")
        manifest = {key: value for key, value in meta.items() if key not in GENERATED}
        path = args.binary
        if not stat.S_ISREG(mode(path)):
            raise Invalid("expected a regular binary file")
        package_id, version = identity(path.resolve(), meta["type"], args.aapt2)
        with path.open("rb") as stream:
            size, digest = copy_and_digest(stream)
        if "id" in meta and meta["id"] != package_id:
            path = args.meta
            raise Invalid(f"static id {meta['id']!r} does not match binary id {package_id!r}")
        manifest.update({"id": package_id, "version": version,
                         "download": {"url": args.url, "size": size, "sha256": digest}})
        # Validate the binary identity/version and URL before using them.
        for problem in schema_issues(manifest, SCHEMAS["package.schema.json"], "package.schema.json"):
            report.emit(args.meta, problem)
        if report.errors:
            return
        path = root
        target = destination(root, args.section, args.folder, manifest)
        path = args.icon
        icon = small_file(path)
        path = args.changes_from
        manifest["changes"] = changes(path, args.changes_path, meta["name"], target.parent.name, version)
        requires = manifest.get("requires", {})
        for field in ("sdk", "wave"):
            if field in requires:
                requires[field] = int(requires[field])
        try:
            data = (json.dumps(manifest, indent=2, ensure_ascii=False, default=float, allow_nan=False)
                    + "\n").encode("utf-8")
        except ValueError as exc:
            raise Invalid(f"cannot encode static metadata: {exc}") from exc
        lint(root, report, candidate=(target, data, icon))
        if not report.errors:
            path = target
            write_package(path, data, icon)
            print(f"{path}: published {package_id} {version}")
    except (Invalid, OSError) as exc:
        report.emit(path, str(exc))
