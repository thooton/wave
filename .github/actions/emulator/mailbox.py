"""One compare-and-swap Git ref per emulator; no persistent credentials or service."""
import json
import os
import re
import subprocess

REPOSITORIES = {"thooton/wave", "thooton/wave_packages", "thooton/wave_private_packages"}
APP = re.compile(r"[a-z][a-z0-9-]{0,31}\Z")
SESSION = re.compile(r"([a-z][a-z0-9-]{0,31})-[a-f0-9]{16}\Z")
MAX_OUTPUT = 2 * 1024 * 1024
POLL = 5


def ref(session, name):
    if not SESSION.fullmatch(session):
        raise ValueError("invalid emulator session (use the ID printed by start)")
    return f"refs/heads/emulator/{session}/{name}"


def repository(url):
    name = url.removeprefix("https://github.com/").removesuffix("/").removesuffix(".git")
    if name not in REPOSITORIES:
        raise ValueError("select a Wave repository or checkout with --repo")
    return name


class GitError(RuntimeError):
    pass


class MissingMailbox(GitError):
    pass


class Mailbox:
    def __init__(self, path, url, session, relay=(), env=None):
        self.path = path
        self.url = url
        self.branch = ref(session, "io")
        self.relay = relay
        self.env = env

    def git(self, *args, data=None, network=False):
        prefix = self.relay if network else ()
        # The mailbox has no working files/index, hooks, submodules or changes to master.
        cmd = [*prefix, "git", "-c", "user.name=Wave emulator", "-c",
               "user.email=wave-emulator@users.noreply.github.com", "-c", "commit.gpgsign=false",
               "-C", str(self.path), *args]
        p = subprocess.run(cmd, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           env=self.env, timeout=60)
        if p.returncode:
            error = p.stderr.decode(errors="replace").strip()
            if "couldn't find remote ref" in error:
                raise MissingMailbox("session mailbox not found")
            raise GitError(f"git {args[0]} failed: {error}")
        return p.stdout

    def commit(self, frame, parent=None, output=None):
        def blob(data):
            return self.git("hash-object", "-w", "--stdin", data=data).strip()

        entries = b"100644 blob " + blob(json.dumps(frame).encode()) + b"\tframe.json\n"
        if output is not None:
            if len(output) > MAX_OUTPUT:
                raise ValueError("command output exceeded 2 MiB")
            entries += b"100644 blob " + blob(output) + b"\toutput\n"
        tree = self.git("mktree", data=entries).decode().strip()
        args = ["-p", parent] if parent else []
        return self.git("commit-tree", tree, *args, "-m", f"Emulator: {frame['kind']}").decode().strip()

    def read(self):
        # The previous frame is the compare-and-swap parent for the next writer.
        self.git("fetch", "-q", "--no-tags", "--depth=1", self.url, self.branch, network=True)
        head = self.git("rev-parse", "FETCH_HEAD").decode().strip()
        if int(self.git("cat-file", "-s", f"{head}:frame.json")) > 8192:
            raise GitError("invalid mailbox frame size")
        frame = json.loads(self.git("show", f"{head}:frame.json"))
        if not isinstance(frame, dict) or frame.get("kind") not in {
            "starting", "ready", "request", "result", "closed"
        }:
            raise GitError("invalid mailbox frame")
        return head, frame

    def output(self, head):
        if int(self.git("cat-file", "-s", f"{head}:output")) > MAX_OUTPUT:
            raise GitError("invalid mailbox output size")
        return self.git("show", f"{head}:output")

    def push(self, commit):
        self.git("push", "--porcelain", self.url, f"{commit}:{self.branch}", network=True)

    def delete(self, session):
        # Either ref may already be absent (for example if startup was interrupted).
        for branch in (ref(session, "start"), self.branch):
            try:
                self.git("push", self.url, f":{branch}", network=True)
            except GitError as exc:
                if "remote ref does not exist" not in str(exc):
                    raise


def runner_env(url):
    import base64

    # Pass the job token to Git alone, not to the APK build, SDK or adb.
    token = os.environ.pop("EMULATOR_GIT_TOKEN")
    basic = base64.b64encode(f"x-access-token:{token}".encode()).decode()
    return dict(os.environ, GIT_CONFIG_COUNT="1", GIT_CONFIG_KEY_0=f"http.{url}/.extraheader",
                GIT_CONFIG_VALUE_0=f"Authorization: Basic {basic}", GIT_TERMINAL_PROMPT="0")
