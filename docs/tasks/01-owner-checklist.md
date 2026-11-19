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
5. Review commits and artifact redistribution rights first. From W:
   `git -C ../sonim push -u origin master`; then
   `git -C ../sonim push origin stock-messages stock-launcher stock-music`.
   Confirm the immutable versioned releases, source index, and `latest`
   legacy manifest. Do not retire `latest` until inventory is known.
6. In `thooton/delta`, audit nightly QA configuration, `AGENTS.md` and
   `cmd/delta/main.go` for the old slug with
   `git grep -nE 'thooton/sonim|\.\./sonim'`. Repeat in W/P/R. Keep old
   legacy-client URL tests, historical notes and intentional local paths.
