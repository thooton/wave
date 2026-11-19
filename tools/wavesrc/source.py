"""Source checkout layout and the subset of JSON Schema used in spec/."""

import datetime
import json
import re
import stat
import struct
import zlib
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
from urllib.parse import urlsplit

LIMIT = 64 * 1024
FOLDER = re.compile(r"[a-z0-9][a-z0-9-]*")
SPEC = Path(__file__).resolve().parents[2] / "spec"


class Invalid(ValueError):
    """A user-facing validation error, without a traceback."""


@dataclass
class Report:
    errors: int = 0

    def emit(self, path, message, *, warning=False):
        if not warning:
            self.errors += 1
        line = f"{path}: {'warning: ' if warning else ''}{message}"
        print(line.replace("\r", "\\r").replace("\n", "\\n"))


def mode(path):
    try:
        return path.lstat().st_mode
    except FileNotFoundError:
        return 0


def small_file(path):
    if not stat.S_ISREG(mode(path)):
        raise Invalid("expected a regular file (not a symlink)")
    with path.open("rb") as stream:
        data = stream.read(LIMIT + 1)
    if len(data) > LIMIT:
        raise Invalid(f"larger than 64 KB ({LIMIT} bytes)")
    return data


def json_object(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise Invalid(f"duplicate JSON key {key!r}")
        obj[key] = value
    return obj


def non_json(token):
    raise Invalid(f"invalid JSON value {token!r}")


def read_json(data):
    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=json_object,
                           parse_float=Decimal, parse_constant=non_json)
        # Reject lone escaped surrogates, which are not Unicode code points.
        json.dumps(value, ensure_ascii=False, default=str).encode("utf-8")
        return value
    except (InvalidOperation, ValueError, RecursionError) as exc:
        raise Invalid(f"invalid UTF-8 JSON: {exc}") from exc


def read_schema(name):
    return read_json((SPEC / name).read_bytes())


SCHEMAS = {name: read_schema(name) for name in ("wave.schema.json", "package.schema.json")}


def schema_issues(value, rule, document, field=""):
    """Yield field-specific failures for the keywords used by the bundled schemas.

    JSON Schema patterns use search semantics; a supplied end anchor must be
    absolute if it is meant to reject a final newline as well.
    """
    where = field or "manifest"
    if "$ref" in rule:
        filename, pointer = rule["$ref"].split("#", 1)
        document = filename or document
        rule = SCHEMAS[document]
        for key in pointer.lstrip("/").split("/"):
            rule = rule[key]
        yield from schema_issues(value, rule, document, field)
        return
    if "oneOf" in rule:
        results = [list(schema_issues(value, branch, document, field))
                   for branch in rule["oneOf"]]
        if sum(not r for r in results) != 1:
            # Currently used only for a dependency id or object. Keep the
            # appropriate branch's more useful error (e.g. depends[0].min).
            issues = results[1 if isinstance(value, dict) else 0]
            yield from issues or [f"{where} must match exactly one allowed shape"]
        return
    if "enum" in rule and value not in rule["enum"]:
        yield f"{where} must be one of {', '.join(map(str, rule['enum']))}"
        return
    match = {
        "object": isinstance(value, dict),
        "array": isinstance(value, list),
        "string": isinstance(value, str),
        "integer": type(value) is int or (isinstance(value, Decimal) and value == value.to_integral_value()),
    }
    kind = rule.get("type")
    if kind and not match[kind]:
        yield f"{where} must be {'an' if kind[0] in 'aeiou' else 'a'} {kind}"
        return
    if isinstance(value, dict):
        properties = rule.get("properties", {})
        for key in rule.get("required", []):
            if key not in value:
                yield f"missing required field {field + '.' if field else ''}{key}"
        for key, child in value.items():
            child_field = f"{field}.{key}" if field else key
            if "propertyNames" in rule and not re.search(rule["propertyNames"]["pattern"], key):
                yield f"{where} has invalid section folder name {key!r}"
            child_rule = properties.get(key, rule.get("additionalProperties"))
            if child_rule:
                yield from schema_issues(child, child_rule, document, child_field)
    elif isinstance(value, list):
        if "maxItems" in rule and len(value) > rule["maxItems"]:
            yield f"{where} must have at most {rule['maxItems']} items"
        if "minItems" in rule and len(value) < rule["minItems"]:
            yield f"{where} must have at least {rule['minItems']} item(s)"
        if "items" in rule:
            for i, child in enumerate(value):
                yield from schema_issues(child, rule["items"], document, f"{field}[{i}]")
    elif isinstance(value, str):
        if "minLength" in rule and len(value) < rule["minLength"]:
            yield f"{where} must not be empty"
        if "maxLength" in rule and len(value) > rule["maxLength"]:
            yield f"{where} must have at most {rule['maxLength']} characters"
        if "pattern" in rule and not re.search(rule["pattern"], value):
            expected = rule.get("description", rule["pattern"])
            if rule["pattern"] == "^https://":
                expected = "an https:// URL"
            elif where == "summary":
                expected = "one line"
            elif where == "download.sha256":
                expected = "64 lowercase hexadecimal characters"
            elif where.endswith(".date"):
                expected = "YYYY-MM-DD"
            elif where == "id" or where.startswith(("depends[", "featured[", "conflicts[", "replaces[")):
                if not where.endswith(".min"):
                    expected = "a package id (letters, digits, '.', '_' or '-', starting with a letter or digit)"
            yield f"{where} must match {expected}"
    else:
        if "minimum" in rule and value < rule["minimum"]:
            yield f"{where} must be at least {rule['minimum']}"
        if "maximum" in rule and value > rule["maximum"]:
            yield f"{where} must be at most {rule['maximum']}"


def https_url(url):
    if not isinstance(url, str) or not url.startswith("https://") or any(
        char.isspace() or ord(char) < 32 or char in "\\\x7f" for char in url
    ):
        return False
    try:
        parts = urlsplit(url)
        _ = parts.port  # also rejects non-numeric or out-of-range ports
        return bool(parts.hostname)
    except ValueError:
        return False


def validate(value, document, path, report):
    before = report.errors
    for issue in schema_issues(value, SCHEMAS[document], document):
        report.emit(path, issue)
    if before != report.errors:
        return False
    urls = [("homepage", value.get("homepage"))]
    if "download" in value and document == "package.schema.json":
        urls.append(("download.url", value["download"]["url"]))
    for field, url in urls:
        if url is not None and not https_url(url):
            report.emit(path, f"{field} must be an https:// URL with a host and no whitespace")
    if document == "package.schema.json":
        for i, note in enumerate(value.get("changes", [])):
            if "date" in note:
                try:
                    datetime.date.fromisoformat(note["date"])
                except ValueError:
                    report.emit(path, f"changes[{i}].date is not a calendar date")
    return before == report.errors


ICON_MAX = 1024  # a 64 KB PNG can declare 65535x65535; the app refuses to decode more


def check_icon(data):
    if len(data) > LIMIT:
        raise Invalid(f"larger than 64 KB ({LIMIT} bytes)")
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise Invalid("expected a PNG image")
    offset, has_pixels = 8, False
    while offset + 12 <= len(data):
        length, kind = struct.unpack_from(">I4s", data, offset)
        end = offset + 8 + length
        if end + 4 > len(data) or zlib.crc32(data[offset + 4:end]) != struct.unpack_from(">I", data, end)[0]:
            raise Invalid("truncated or corrupt PNG chunk")
        if offset == 8:
            if kind != b"IHDR" or length != 13:
                raise Invalid("missing PNG IHDR")
            width, height, depth, color, compression, filters, interlace = struct.unpack_from(
                ">IIBBBBB", data, offset + 8)
            if width != height:
                raise Invalid(f"PNG must be square (got {width}x{height})")
            depths = {0: (1, 2, 4, 8, 16), 2: (8, 16), 3: (1, 2, 4, 8), 4: (8, 16), 6: (8, 16)}
            if (not 0 < width < 2**31 or depth not in depths.get(color, ()) or
                    compression or filters or interlace not in (0, 1)):
                raise Invalid("invalid PNG IHDR")
            if width > ICON_MAX:
                raise Invalid(f"PNG must be at most {ICON_MAX} pixels square (got {width}x{height})")
        elif kind == b"IDAT" and length:
            has_pixels = True
        elif kind == b"IEND":
            if has_pixels and length == 0 and end + 4 == len(data):
                return
            break
        offset = end + 4
    raise Invalid("incomplete PNG image")


def read_manifest(path, document, report, supplied=None):
    try:
        data = small_file(path) if supplied is None else supplied
        if len(data) > LIMIT:
            raise Invalid(f"larger than 64 KB ({LIMIT} bytes)")
        value = read_json(data)
        if validate(value, document, path, report):
            return value
    except (Invalid, OSError) as exc:
        report.emit(path, str(exc))
    return None


def read_icon(path, report, supplied=None):
    try:
        if supplied is not None or mode(path):
            check_icon(small_file(path) if supplied is None else supplied)
    except (Invalid, OSError) as exc:
        report.emit(path, str(exc))


def package_paths(root):
    """Exactly <section>/<folder>/package.json. Nothing else is a package."""
    for section in sorted(root.iterdir()):
        if section.name == ".git" or not stat.S_ISDIR(mode(section)):
            continue
        for folder in sorted(section.iterdir()):
            if stat.S_ISDIR(mode(folder)):
                manifest = folder / "package.json"
                if mode(manifest):
                    yield manifest


def lint(root, report, *, candidate=None, verify_binary=None):
    """Lint disk contents or the prospective result of publishing a pair."""
    if not root.is_dir():
        report.emit(root, "expected a source directory")
        return
    header = read_manifest(root / "wave.json", "wave.schema.json", report)
    read_icon(root / "icon.png", report)
    try:
        paths = set(package_paths(root))
    except OSError as exc:
        report.emit(root, str(exc))
        return
    if candidate:
        paths.add(candidate[0])
    by_id, packages = {}, []
    for path in sorted(paths):
        folder = path.parent
        for directory in (folder.parent, folder):
            if not FOLDER.fullmatch(directory.name):
                report.emit(directory, "folder name must start with a lowercase letter or digit and use only those characters and '-'")
        replaced = candidate is not None and candidate[0] == path
        manifest = read_manifest(path, "package.schema.json", report, candidate[1] if replaced else None)
        read_icon(path.with_name("icon.png"), report, candidate[2] if replaced else None)
        if manifest is None:
            continue
        package_id = manifest["id"]
        if package_id in by_id:
            report.emit(path, f"duplicate id {package_id!r}; also in {by_id[package_id]}")
        else:
            by_id[package_id] = path
        packages.append((path, manifest))
    for path, manifest in packages:
        for key in ("depends", "conflicts", "replaces"):
            for dep in manifest.get(key, []):
                package_id = dep["id"] if isinstance(dep, dict) else dep
                if package_id not in by_id:
                    report.emit(path, f"{key} id {package_id!r} not in this source", warning=True)
        if verify_binary:
            try:
                verify_binary(manifest)
            except (Invalid, OSError) as exc:
                report.emit(path, str(exc))
    if header:
        for package_id in header.get("featured", []):
            if package_id not in by_id:
                report.emit(root / "wave.json", f"featured id {package_id!r} not in this source", warning=True)
