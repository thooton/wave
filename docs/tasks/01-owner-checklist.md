# GitHub cutover (owner only)

The local `origin`s already use the final HTTPS URLs. No push or GitHub
change was made. Keep the local directory `../sonim` while its worktree is
shared. W is already named `wave`.

1. In the existing `thooton/sonim` repository, Settings → General → Rename,
   choose `wave_packages`. Never reuse the `sonim` name. Verify the old
   client URL still returns HTTP 200 after redirects, before any roll-out:
   `curl -fIL https://github.com/thooton/sonim/releases/download/latest/manifest.json`.
2. Under `thooton`, create `wave` (unless it already exists) and
   `wave_repository`, public, without templates/README/license. If an
   existing remote has commits, fetch and reconcile before pushing; never
   force-push. From W, push W with `git push -u origin master`, R with
   `git -C ../wave_repository push -u origin master` after its first commit.
   Tag the reviewed Actions version in W with `git tag v1 <reviewed-commit>`
   and `git push origin refs/tags/v1` before enabling P's publishing.
3. W Settings → Pages → Build and deployment → Source: GitHub Actions.
   Confirm the Pages workflow deploys. R needs a default branch writable
   by the source publisher; restrict other writers rather than requiring a
   PR from the publisher.
4. Create a fine-grained PAT scoped **only to wave_repository**, Contents:
   read/write. In P Settings → Secrets and variables → Actions add the
   repository secret `WAVE_REPOSITORY_TOKEN`. Do not embed it in Git URLs,
   source metadata or logs. Configure W likewise for publishing Wave itself.
   Once R is on GitHub and the publishing identity can push to its `master`,
   set the W Actions variable `WAVE_PUBLISH_ENABLED` to `true`. Run W's Build
   workflow on `master`; use `retry-publish` if the release already exists.
   R validates manifests on every push and downloads releases on its nightly
   run.
5. Review commits and artifact redistribution rights first. From W:
   `git -C ../sonim push -u origin master`; then
   `git -C ../sonim push origin stock-messages stock-launcher stock-music`.
   Confirm the immutable versioned releases, source index, and `latest`
   legacy manifest. Do not retire `latest` until inventory is known.
6. In `thooton/delta`, audit nightly QA configuration, `AGENTS.md` and
   `cmd/delta/main.go` for the old slug with
   `git grep -nE 'thooton/sonim|\.\./sonim'`. Repeat in W/P/R. Keep old
   legacy-client URL tests, historical notes and intentional local paths.

## Source and Tablet private cutover

1. Review the local filtered import at `../wave_private_packages` and the
   public legacy history/releases for accidentally bundled credentials. The
   local content scan found no candidates in the imported app history and
   reported no secret values; it is not a guarantee. Rotate any exposed
   secrets before cutover. Create
   `thooton/wave_private_packages` as **private**, with `master` as default.
   Push the reviewed local `master` there only, after checking repository
   visibility and access. Do not push to a public fork or asset host.
2. Allow the private workflow to use `thooton/wave/{lint,publish}@v1`
   and its own `GITHUB_TOKEN` with Contents write for releases and direct
   manifest commits. Protect the branch from deletion and force pushes;
   permit the publishing identity's direct manifest commits. Leave
   `PRIVATE_PUBLISH_ENABLED` unset until ready for the controlled test.
3. Use a GitHub Contents read-only token scoped to the private repo for
   Wave Git refresh. Enter the private HTTPS URL through the app, never
   in a manifest or log; the shape is `https://user:token@host/owner/repo`.
   The Source and Tablet Delta setup files have their own credentials and
   are transferred directly to their apps, not checked into any repo.
4. Before enabling the cutover, finish and verify authenticated private
   downloads in Wave. The workflow writes token-free `api.github.com`
   release asset URLs. The client needs scoped Bearer auth and
   `Accept: application/octet-stream` for the exact GitHub repo's asset
   API, and must strip authorization before a CDN redirect. Verify that
   an uncredentialed request cannot fetch either APK. The current manifest
   and downloader alone do not provide this authentication.
5. On the reserved migration device, enable `PRIVATE_PUBLISH_ENABLED` for
   the test, run the private Build workflow on `master`, add the private
   source, refresh, update both apps, and verify their existing or imported
   Delta setup. Check that neither appears in the default source. Only
   then remove Source and Tablet from the legacy `wave_packages` build
   and rolling release. Leave `latest` itself available until the separate
   device migration inventory is confirmed.
