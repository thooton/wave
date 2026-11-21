#!/usr/bin/env python3
"""Wave agent-side client. The only remote operations are Oak Git fetch/push."""
import argparse
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time

from mailbox import APP, GitError, Mailbox, MissingMailbox, POLL, ref, repository

OPS = ("launch", "key", "text", "shot", "ui", "log", "stop", "reset", "shell", "close")


def wait(mailbox, predicate, seconds):
    deadline = time.monotonic() + seconds
    while True:
        head, frame = mailbox.read()
        if predicate(frame):
            return head, frame
        if time.monotonic() >= deadline:
            raise GitError(f"timed out ({frame['kind']}); use status before retrying")
        time.sleep(POLL)


def checkout_repository(path):
    cmd = ["git", "-C", str(path), "config", "--get", "remote.origin.url"]
    return repository(subprocess.check_output(cmd, text=True).strip())


def start(path, app):
    if not APP.fullmatch(app):
        raise ValueError("invalid app directory")
    repo = checkout_repository(path)
    session = f"{app}-{secrets.token_hex(8)}"
    box = Mailbox(path, f"https://github.com/{repo}", session, relay=("oak",))
    commit = box.commit({"kind": "starting"})
    # The job never sees a start branch without its mailbox. Push committed HEAD,
    # not the working directory; the launch branch is not a release branch.
    print(session, flush=True)
    box.git("push", "--atomic", box.url, f"{commit}:{box.branch}",
            f"HEAD:{ref(session, 'start')}", network=True)
    print(f"Started {repo} at local HEAD. Wait with: emulator --repo {repo} {session} wait",
          file=sys.stderr)


def result(box, head, frame, path):
    if frame["kind"] == "closed":
        print("closed")
        return
    if frame["kind"] != "result":
        raise GitError(f"no result available ({frame['kind']})")
    if not frame.get("ok"):
        raise GitError(str(frame.get("error", "emulator command failed")))
    op = frame["op"]
    data = box.output(head)
    if path or op in {"shot", "ui"}:
        path = Path(path or {"shot": "shot.png", "ui": "ui.xml"}[op])
        path.write_bytes(data)
        print(path)
    elif data:
        sys.stdout.buffer.write(data)


def drive(box, session, op, args, limit, out):
    if op == "clean":
        box.delete(session)
        return
    if op == "wait":
        _, frame = wait(box, lambda f: f["kind"] != "starting", limit)
        print(frame.get("summary", frame["kind"]))
        return
    head, frame = box.read()
    if op == "status":
        print(frame["kind"], frame.get("summary", frame.get("op", "")))
        return
    if op == "result":
        result(box, head, frame, out)
        return
    if op not in OPS:
        raise ValueError(f"unknown operation {op!r}")
    if frame["kind"] not in {"ready", "result"}:
        raise GitError(f"cannot send a command: {frame['kind']}")
    if sum(len(a.encode()) for a in args) > 2048 or len(args) > 40:
        raise ValueError("command arguments are too large")
    request = box.commit({"kind": "request", "op": op, "args": args}, parent=head)
    box.push(request)
    head, frame = wait(box, lambda f: f.get("request") == request, limit)
    result(box, head, frame, out)
    if op == "close" and frame["kind"] == "closed":
        box.delete(session)


def main():
    parser = argparse.ArgumentParser(description=__doc__, epilog=(
        "Use 'start APP' from a checkout containing committed code; then 'SESSION wait|status|"
        "launch|key|text|shot|ui|log|stop|reset|shell|result|close|clean [ARGS]'. "
        "Place --out and --timeout before SESSION."))
    parser.add_argument("--repo", default=".", help="checkout path, or owner/repo for existing sessions")
    parser.add_argument("--out", help="write a command result to this path")
    parser.add_argument("--timeout", type=int, help="seconds to wait (default 120; 1200 for wait)")
    parser.add_argument("session", help="session ID, or 'start'")
    parser.add_argument("operation", help="operation, or the app directory for 'start'")
    parser.add_argument("args", nargs=argparse.REMAINDER)
    opts = parser.parse_args()
    try:
        if opts.session == "start":
            if opts.args:
                raise ValueError("start takes one app directory")
            start(Path(opts.repo), opts.operation)
        else:
            path = Path(opts.repo)
            repo = checkout_repository(path) if path.is_dir() else repository(opts.repo)
            limit = opts.timeout or (1200 if opts.operation == "wait" else 120)
            with tempfile.TemporaryDirectory(prefix="wave-emu-") as scratch:
                box = Mailbox(scratch, f"https://github.com/{repo}", opts.session, relay=("oak",))
                box.git("init", "-q", "--bare")
                drive(box, opts.session, opts.operation, opts.args, limit, opts.out)
    except (GitError, ValueError, OSError, subprocess.SubprocessError) as exc:
        print(f"emulator: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
