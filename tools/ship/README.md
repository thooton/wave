# ship: finish a Wave task

Every Wave worker finishes a task by shipping it: commit the work, push it to
`master`, see master CI pass, and publish each changed package through its
release channel. Pushing to `master` is not publication. A package is
published only once its catalog lists the new version, pointing at an
immutable release asset. `ship check` verifies all of this and prints the
report a worker includes in its final message.

```sh
../wave/tools/ship/ship check                       # from any Wave checkout
../wave/tools/ship/ship check --hold "task said: don't publish until review"
../wave/tools/ship/ship check --since wave_packages=<sha>   # after a VM restore
tools/ship/ship audit                               # every public package (CI)
python3 -m unittest discover -s tools/ship          # tests; no network
```

`check` looks at every sibling checkout of `wave`, `wave_packages`,
`wave_private_packages` and `wave_repository` in the worker's folder. It
fetches each one with `oak git`. Then it works out which commits are the
worker's from that checkout's `HEAD` reflog (commits, amends, rebased picks,
cherry-picks), so other workers' pushes and fast-forward pulls are not
counted. A checkout that was recloned after a VM replacement has lost its
reflog: name the first commit to count with `--since REPO=SHA`.

For each repository it reports:

| Check | Blocks when |
|---|---|
| Working tree | Files are uncommitted or untracked. Commit them or delete scratch files. |
| Pushed | `HEAD` has commits that are not on `origin/master`. |
| CI | The worker's newest pushed commit has a failed or unfinished master run, or no run although a package changed. A cancelled run passes only if a later master run of that workflow passed on a descendant commit. |
| Publication | A changed package's current version is missing from its catalog. The message says whether the release is missing (CI failure or no bump, or a mod waiting for its manual dispatch) or exists but was not mirrored. |
| Private boundary | A worker commit to a public repository adds an APK or AAB, or a private release asset URL. |

Changes to documentation, specs, mockups or tooling outside a package's
build inputs need no release; `check` says so. These are the publication
channels it checks:

- `wave_packages` apps and mods: an immutable `<name>-<version>` release in
  `thooton/wave_packages`, then `<section>/<name>/package.json` in
  `thooton/wave_repository`. If CI's mirror step is off
  (`WAVE_PUBLISH_ENABLED`), mirror the release with
  [`wavesrc publish`](../wavesrc/README.md) and push `wave_repository`. Mods are
  released by a manual `workflow_dispatch` of *Build and publish XP3800
  packages* on master.
- The Wave app: a `wave-<version>` release here, then `tools/wave/package.json`
  in `wave_repository`. The website deploys from master with the Build workflow.
- `wave_private_packages` apps: a private release and
  `applications/<app>/package.json` in that repository, written only by
  private CI while the owner has enabled `PRIVATE_PUBLISH_ENABLED`. Without
  that authorization, `check` reports a blocker. Never mirror a private app to a
  public catalog, and never paste private asset URLs, APKs or captures into a
  public repository or report.
- `wave_repository`: its commits are the publication; *Lint source* CI
  checks them.
- Deliverables outside these catalogs, such as `wave/services/*`, have
  their own deployment. `check` notes them; verify the deployment as the
  service's README says and report the result. The Wave website deploys
  to Pages in the Build workflow that `check` already waits for.

`--hold REASON` is only for a task that explicitly says not to publish. It
turns publication gaps into `HELD` and still requires commit, push and green
CI. Exit status 0 means `SHIPPED`, or shipped except a held publication. If
something cannot be fixed (permissions, owner-only switches, an unrelated
red build), report each `BLOCKED` line as an explicit blocker; do not call the
work published.

`audit` reads local checkouts only. It lists every public package whose master
version is not in the default catalog. The `Catalog freshness` workflow in
`thooton/wave_repository` runs it on each catalog push, nightly and on demand.
