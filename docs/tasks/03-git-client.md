# 03: The git client

**Repo** W (`app/core`, package `com.wave.xp3.core.git`). **Needs** 04.
**Blocks** 05.

## Read first
`../design.md` section 3. Git's own documentation: `gitprotocol-v2`,
`gitprotocol-http`, `gitformat-pack` (in git's `Documentation/`).
`../sonim/common/core` for `Http` and the test fixtures (`Check`,
`TestServer`).

## Goal
Fetch the tip of a repository's default branch over HTTPS into memory or a
folder, in plain Java 8 with no dependencies, small enough to read in one
sitting.

## Deliver
- `PktLine`: reading and writing pkt-lines, flush and delimiter packets.
- `SmartHttp`: capability advertisement (`Git-Protocol: version=2`),
  `lsRefs()` returning HEAD's commit id, `fetch(commit)` sending `want`,
  `deepen 1`, `filter blob:limit=262144` when advertised, `no-progress`,
  `done`, and demultiplexing the sideband. Basic auth from the URL's
  userinfo. `https://` only. Deadlines and a size cap (8 MB) on everything
  read.
- `Pack`: header, the six object types, zlib inflation, offset and ref
  deltas, SHA-1 of every object, the trailer checksum.
- `Tree`: from a commit to its tree; list and read entries by path, two
  levels deep, skipping blobs the filter left out.
- A `fetchTree(url, knownCommit)` entry point: returns "unchanged" after
  `ls-refs` when the commit is the known one, otherwise the files the source
  format needs.
- `SelfTest` cases: pkt-line edge cases; packs made with real `git` checked
  in as fixtures (plain, with deltas, with a filtered blob); corrupt and
  truncated packs; an oversized pack; a `TestServer` replaying a recorded
  exchange.
- A test that is run by hand, not in CI: fetch `spec/example-source` served
  by `git http-backend`, and one public repo on each of GitHub, GitLab and
  Codeberg.

## Done when
The self-tests pass on a desktop JVM, the manual test fetches from all three
hosts, and a 1000-package synthetic source fetches and parses in a few
seconds with memory that would fit the phone (measure; the XP3 has 1 GB and
a small heap).

## Watch out
- GitHub answers protocol v2 only when the header is sent; without it you
  get v0 and a different conversation.
- A depth-1 fetch still sends a `shallow-info` section before the pack.
- Deltas can chain and can refer to objects later in the pack. Resolve after
  reading everything, without recursion deep enough to overflow.
- Don't hold the pack and every inflated object at once: inflate blobs only
  for the paths the format reads.
- API 27's TLS is fine for all three hosts; don't add a trust manager.
