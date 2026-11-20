# Going live: the owner's checklist

The one page to follow to take Wave from the local repos to GitHub. Do the
steps in order: each one assumes the ones before it worked. Only the owner
can do them; they need the `thooton` GitHub account. Where a command is
given, run it from `~/Documents/wave` (W) unless it says otherwise.

What exists today (checked 2026-11-19, read-only):

| Repo | Local folder | On GitHub |
| :--- | :--- | :--- |
| `thooton/wave` (W) | `wave` | not there |
| `thooton/wave_packages` (P) | `wave_packages` | separate repo not hosted yet |
| `thooton/sonim` (legacy) | `sonim` | unchanged, `master` and `latest` at `1f8a7d6` |
| `thooton/wave_repository` (R) | `wave_repository` | not there |
| `thooton/wave_private_packages` | `wave_private_packages` | not there |

The local `origin`s already point at the final URLs. So `git push` in a
folder whose repo doesn't exist yet just fails; it doesn't go anywhere else.

**Read this first.** Pushing P (step 9) is a release, not a backup:
- Each eligible app gets an immutable versioned release in `wave_packages`
  and is published to the public default source.
- P's own rolling `latest` is separate. The original `sonim` repo and its
  `latest` remain untouched, so old Updater installs do not switch to Wave.

Everything before step 9 can be undone or is harmless. Step 9 and the token
scopes are the steps to take slowly.

## 0. Before you start

1. Make sure everything is committed and the tests pass. Commands:
   ```sh
   git status --short; git -C ../wave_packages status --short; git -C ../wave_repository status --short
   JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q :app:core:selfTest
   python3 -m unittest discover -s tools/wavesrc -p 'test*.py'
   node --test site/tests/*.test.mjs site/src/protocols/tests/*.test.mjs
   ```
   **Check:** no stray changes you didn't mean to publish (P has the old
   `.claude/worktrees` deletions and `places/wave/`; leave those
   uncommitted). Each suite says OK.
2. Review what goes public: P's unpublished history and any `stock-*` tags
   you choose to push. The tags hold code derived from Sonim's stock APKs.
   Only you can judge the redistribution rights. If the history is not
   ready to be public, do not push it to a public repo.

## 0a. APK signing (no setup)

Local builds and releases use the public `signing/debug.keystore` checked into
both build repos. AppDrawer keeps its own checked-in public key so existing
copies can update. There are no signing passwords to enter or Actions secrets
to set. Anybody can sign a replacement APK with these keys; only install APKs
from a source you trust. Sonim OEM signing for system mods is separate.

## 1. Create `thooton/wave_packages` separately

1. At https://github.com/new, create `thooton/wave_packages` as a new,
   empty repo (no README, .gitignore or license). Do not rename, push to,
   or change releases in `thooton/sonim` for this rollout.
2. Confirm the local `../wave_packages` origin is
   `https://github.com/thooton/wave_packages.git`. Leave its first push to
   step 9. No signing setup is required.
3. Verify the legacy branch and manifest independently:
   ```sh
   git ls-remote https://github.com/thooton/sonim.git refs/heads/master refs/tags/latest
   curl -fsL -o /dev/null https://github.com/thooton/sonim/releases/download/latest/manifest.json
   ```
   The old Updater depends on the original release URL, not a redirect.

## 2. Create `thooton/wave` and push W

1. github.com/new: owner `thooton`, name `wave`, **Public**, no README,
   .gitignore or license.
2. Push it:
   ```sh
   git push -u origin master
   ```
3. **Check:**
   - `git ls-remote origin master` matches `git rev-parse master`.
   - In Actions, the **Build** run on `master` is green. It creates a Wave release; catalog mirroring is skipped by default.
   - Its **Deploy the website** job fails until step 4; that's fine.
4. **If Build fails:** read the failing step's log, fix it locally, commit
   and push again. Never force-push `master`.

## 3. Tag the Actions version `v1`

The other repos' workflows call `thooton/wave/lint@v1` and
`thooton/wave/publish@v1`, so they fail until this tag exists.

1. Tag the commit you just pushed:
   ```sh
   git tag -a v1 -m "wavesrc Actions v1" master && git push origin refs/tags/v1
   ```
2. **Check:** `git ls-remote origin refs/tags/v1` prints a hash.
3. **Later:** moving `v1` changes what every caller runs on its next run.
   Move it only on purpose, to a commit that passed Build:
   ```sh
   git tag -fa v1 <commit> && git push -f origin refs/tags/v1
   ```

## 4. Turn on Pages

The Build workflow deploys the website (the rooting page and `recovery/`)
after it builds the APK, on every push to `master`. The site serves the APK it
just built, the official Magisk 24.0 and the Sonim programmer, so users choose
no files.

1. In `thooton/wave`, open Settings → Pages → Build and deployment →
   Source, and choose **GitHub Actions**.
2. Make the programmer available to CI. The programmer is Sonim's file, so
   only you can judge whether it may be redistributed. Host the verified copy
   (`prog_emmc_firehose_8909_ddr.mbn`, SHA-256 `e6f2dbcf…2767`) somewhere CI
   can download it, and in Settings → Secrets and variables → Actions →
   Variables set `SONIM_PROGRAMMER_URL` to that URL. `build.py` refuses any
   other file. Without the variable the site still deploys, with a warning,
   and the page asks users for the file.
3. In Actions, re-run **Build** on `master`.
4. **Check:** each of these prints 200:
   ```sh
   for p in "" recovery/ files/files.json; do curl -s -o /dev/null -w "%{http_code} $p\n" https://thooton.github.io/wave/$p; done
   curl -s https://thooton.github.io/wave/files/files.json
   ```
   `files.json` lists `programmer`, `magisk` and `wave`. Open
   https://thooton.github.io/wave/ in Chrome: after **Start** it goes
   straight to **Connect phone**, with no **Choose files** step.
5. **If it shows 404 or the deploy job fails:**
   - In Settings → Environments → `github-pages`, the deployment branch rule
     must allow `master`.
   - Then re-run Build. A Pages site can take a few minutes to appear the
     first time.

## 5. Create `thooton/wave_repository` (the default source) and push R

1. github.com/new: owner `thooton`, name `wave_repository`, **Public**,
   empty. Wave has this URL built in
   (`https://github.com/thooton/wave_repository.git`), so the name must be
   exact.
2. Push it:
   ```sh
   git -C ../wave_repository push -u origin master
   ```
3. **Check:** Actions → **Lint source** runs green on the push. It needs
   `v1` from step 3. Wave on a phone shows "Nothing featured yet" instead
   of "Could not connect to the Git host".
4. **If Lint fails with "unable to resolve action":** `v1` is missing or
   points at a commit without `lint/`. Redo step 3.

## 6. Optional: make the automatic catalog publishing token

Omit this step if you will publish catalog entries locally over SSH. APK
releases are created either way. For automatic mirroring from CI, the publish
action takes a token scoped to R, not a deploy key.

1. Go to github.com/settings/personal-access-tokens/new and make a
   **fine-grained** token:
   - name `wave-source-publisher`, resource owner `thooton`;
   - an expiry you'll remember. Put the renewal date in your calendar: when
     it expires, publishing fails;
   - Repository access: **Only select repositories → `wave_repository`**;
   - Permissions: Repository → **Contents: Read and write**. Nothing else.
     Metadata: read is added by itself.
2. **Scope hazard.** A classic token, or one for "All repositories", can
   rewrite every repo you own, including P's releases. If one leaks it
   can't be called back, only revoked, and anything it pushed meanwhile
   stays. Use exactly the scope above.
3. Store it as a secret, never in a file, URL or command line:
   - in `thooton/wave`, open Settings → Secrets and variables → Actions → New
     repository secret, name `WAVE_REPOSITORY_TOKEN`;
   - do the same in `thooton/wave_packages`.
4. **Check:** both repos list `WAVE_REPOSITORY_TOKEN` under Repository
   secrets. The token is really tested by step 7.
5. **If it leaks:**
   - revoke it on the same settings page;
   - make a new one and replace both secrets;
   - look through R's recent commits for anything you didn't publish.

## 7. First publication: Wave itself

1. For automatic mirroring, set `WAVE_PUBLISH_ENABLED` to `true` under
   Actions variables in each build repo where you set the token. Otherwise
   publish the manifest locally after CI, using the exact release APK (see
   [Publishing](../publishing.md)).
2. In Actions, choose **Build**, then Run workflow on `master`.
3. **Check:**
   - The release `wave-<versionName>` (from `app/app/build.gradle`) exists in `thooton/wave` with one APK.
   - R has a new commit adding `applications/wave/package.json`.
   - A full lint downloads and checks the APK:
     ```sh
     rm -rf /tmp/r && git clone -q https://github.com/thooton/wave_repository /tmp/r
     python3 tools/wavesrc/wavesrc lint --download /tmp/r
     python3 tools/wavesrc/wavesrc verify /tmp/r
     ```
   - On the phone, Wave's Sources tab lists Wave under Applications.
4. **If it fails:**
   - **Release made, manifest not pushed** (often a token problem):
     1. fix the secret;
     2. Run workflow again with **retry-publish** ticked. It reuses the
        original APK.
   - **Never delete a versioned release and upload a rebuild under the
     same tag.** Phones pin its SHA-256, so the rebuild would be refused as
     tampered. Bump the version instead.

## 8. Protect the branches

For each of `wave`, `wave_packages` and `wave_repository`, open Settings →
Rules → Rulesets → New branch ruleset. Target the default branch, set
Enforcement to Active, then:

1. Turn on **Restrict deletions** and **Block force pushes**.
2. **On `wave_repository`, do not require pull requests or status checks.**
   CI publishes by pushing straight to `master`, and a required PR stops
   every publication. To limit writers, keep collaborators off the repo;
   the token's owner is the only one who needs to push.
3. **On `wave_packages`, add no tag ruleset that covers `latest`.** The
   legacy job force-moves that tag on every build. You may protect the
   versioned tags (pattern `*-*.*.*`) against updates and deletion; they
   are never meant to move.

**Check:**
```sh
git push --dry-run -f origin master
```
A real force push is now refused (GitHub only refuses it on a real push;
don't test it on `master`). Then push an ordinary commit to R's `master`
through CI in step 9; it must still go through.

## 9. First package release: push P

**Before you push, know what happens.** CI builds the APKs, creates immutable
versioned releases in `wave_packages`, and publishes the eligible apps to R.
It also has a rolling `latest` job in P. Neither job updates the original
`thooton/sonim` repo or switches phones on the old Updater to the Wave channel.

1. From W, verify the destination and push:
   ```sh
   git -C ../wave_packages remote get-url --push origin
   git -C ../wave_packages push -u origin master
   ```
   The destination must be `https://github.com/thooton/wave_packages.git`.
   Push the `stock-*` tags separately only if they passed step 0's review:
   ```sh
   git -C ../wave_packages push origin refs/tags/stock-messages refs/tags/stock-launcher refs/tags/stock-music
   ```
2. **Check:**
   - Actions → **Build and publish XP3800 packages** is green. The
     `release-mods` job is skipped; see step 10.
   - Releases show one `<app>-<version>` per app, and `latest` with
     `manifest.json` and `versions.txt`.
   - The old `thooton/sonim` manifest still answers 200; it does not list
     the new Wave releases.
   - Rerun the clone and `lint --download` from step 7: all 17 packages
     (16 apps plus Wave) pass.
   - On the phone, Wave lists them under Applications.
3. **If a job fails:**
   - **"changes … require a versionName and versionCode bump":** an app
     changed after its release tag. Bump it in P, commit, push.
   - **Release made but the manifest wasn't pushed:** Run workflow with
     **retry-publish**.
   - **A Wave app misbehaves after release:** fix it, bump its version, and
     publish again. Do not change `thooton/sonim` as a Wave rollback.

## 10. Mods (not possible yet)

The three mods only install when signed with Sonim's own keys. Then:
1. Upload the OEM-signed APKs as artifacts named
   `<mod>-oem-signed-apks` in a workflow run.
2. Run **Build and publish XP3800 packages** with that run's ID as
   `signed-run-id`.

There is no signing material, so skip this. Nothing else depends on it.

## 11. Check the separation

Verify that Wave publishing and package-development paths point to the new
repos, not `thooton/sonim`. Leave Delta's existing Sonim nightly QA and the
old Updater URL alone. If enabling QA separately in `wave_packages`, set
`QA_DELTA_SERVER` and `QA_DEVICE_TOKEN` there. A new repo does not inherit
secrets from `sonim`.

<a id="source-and-tablet-private-cutover"></a>

## 12. Private source for Source and Tablet (blocked: don't start yet)

**Stop condition:** the real private asset has not been fetched on a phone
yet. Wave 0.7.14 and later send the token from the source address to that
repository's GitHub release-asset URLs (and nowhere else, redirect hops
included); this is covered by host tests only (decisions open item 4). Until
step 5 passes on the migration phone, steps 4 and 5 would risk leaving both
apps uninstallable from the private source. Steps 1 to 3 are safe to do early.

1. **Review the history.** Review `../wave_private_packages` (`bd56fd0`)
   for bundled credentials. An automated scan found none, which is not a
   guarantee. Rotate anything that ever shipped inside an APK.
2. **Create the repo.** Create `thooton/wave_private_packages` as
   **Private**, empty, then:
   ```sh
   git -C ../wave_private_packages push -u origin master
   ```
   **Check:** run this unauthenticated; it must print 404:
   ```sh
   curl -s -o /dev/null -w "%{http_code}\n" https://api.github.com/repos/thooton/wave_private_packages
   ```
   If it prints 200, the repo is public: make it private at once
   (Settings → General → Danger Zone), and treat everything in it as
   exposed.
3. **Allow the Actions.** In Settings → Actions → General, allow actions
   from `thooton/wave`. Give it the same branch ruleset as step 8, without
   required PRs.
4. **Make the phone's token** (scope matters): a fine-grained token, only
   `wave_private_packages`, **Contents: Read-only**.
   - Enter it only on the phone, in Wave's Add a Source, as
     `https://thooton:<token>@github.com/thooton/wave_private_packages`.
   - Never put it in a file, manifest, setup file or chat.
   - A phone lost with it means revoking it.
   - The Delta setup files for Source and Tablet are separate. They go
     straight to those apps, never into a repo.
5. **Test on the migration phone** (with Wave 0.7.14 or later installed):
   1. set the variable `PRIVATE_PUBLISH_ENABLED` = `true`;
   2. run **Build private Wave apps** on `master`;
   3. add the source on the reserved migration phone, refresh, and update
      both apps;
   4. confirm their Delta setup survived, and that neither app appears in
      the default source;
   5. confirm a request without the token can't fetch either APK.

   Only then remove Source and Tablet from P's legacy build. Keep `latest`
   until the device inventory in brief [17](17-first-phone.md) is done.

## Done when

- Steps 1 to 9 and 11 are checked.
- The old manifest URL answers.
- Pages serves the site.
- `wavesrc lint --download` passes on the live default source.
- Wave on the phone lists the published apps.

Record the date and anything that went differently in the work notes in
[README.md](README.md#work-notes).
