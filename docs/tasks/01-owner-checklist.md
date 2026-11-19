# Going live: the owner's checklist

The one page to follow to take Wave from the local repos to GitHub. Do the
steps in order: each one assumes the ones before it worked. Only the owner
can do them; they need the `thooton` GitHub account. Where a command is
given, run it from `~/Documents/wave` (W) unless it says otherwise.

What exists today (checked 2026-11-19, read-only):

| Repo | Local folder | On GitHub |
| :--- | :--- | :--- |
| `thooton/wave` (W) | `wave` | not there |
| `thooton/wave_packages` (P) | `sonim` (keep this name, decision 6) | still `thooton/sonim`, 87 commits behind local `master` |
| `thooton/wave_repository` (R) | `wave_repository` | not there |
| `thooton/wave_private_packages` | `wave_private_packages` | not there |

The local `origin`s already point at the final URLs. So `git push` in a
folder whose repo doesn't exist yet just fails; it doesn't go anywhere else.

**Read this first.** Pushing P (step 9) is a release, not a backup:
- Every phone still on the old Updater gets the new app versions from the
  rolling `latest` release.
- Each app gets an immutable versioned release.
- Each app is published to the public default source.

Everything before step 9 can be undone or is harmless. Step 9 and the token
scopes are the steps to take slowly.

## 0. Before you start

1. Make sure everything is committed and the tests pass. Commands:
   ```sh
   git status --short; git -C ../sonim status --short; git -C ../wave_repository status --short
   JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew -q :app:core:selfTest
   python3 -m unittest discover -s tools/wavesrc -p 'test*.py'
   node --test site/tests/*.test.mjs site/src/protocols/tests/*.test.mjs
   python3 site/scripts/sync_setup.py ../sonim --check
   ```
   **Check:** no stray changes you didn't mean to publish (P has the old
   `.claude/worktrees` deletions and `places/wave/`; leave those
   uncommitted). Each suite says OK.
2. Review what goes public. Most of it is P's 87 new commits and the
   `stock-*` tags, which hold code derived from Sonim's stock APKs. Only you
   can judge the redistribution rights. **Check:** you are content for all
   of it to be public. If not, stop here.

## 1. Rename `sonim` to `wave_packages` (hard to undo)

1. Back up what old phones download today, in case step 9 needs undoing:
   ```sh
   mkdir -p ~/Documents/latest-backup && cd ~/Documents/latest-backup
   for f in $(curl -fsSL https://api.github.com/repos/thooton/sonim/releases/tags/latest | python3 -c "import json,sys;[print(a['browser_download_url']) for a in json.load(sys.stdin)['assets']]"); do curl -fLO "$f"; done
   ls | wc -l
   ```
2. On github.com/thooton/sonim, open Settings → General → Repository name.
   Type `wave_packages` and press Rename.
3. **Check:** the old address still answers through GitHub's redirect.
   Phones on the old Updater depend on it:
   ```sh
   curl -fsIL https://github.com/thooton/sonim/releases/download/latest/manifest.json | grep -i '^HTTP'
   git ls-remote https://github.com/thooton/sonim master
   ```
   The last `HTTP` line should say 200, and `ls-remote` should print a hash.
4. **If it doesn't:** rename it back to `sonim` at once, and the old URL
   works again. Then find out why before trying again.
5. **Irreversible hazard: never create a new repository named `sonim`.**
   That breaks the redirect for good, and every phone on the old Updater
   stops getting updates.

## 2. Create `thooton/wave` and push W

1. github.com/new: owner `thooton`, name `wave`, **Public**, no README,
   .gitignore or license.
2. Push it:
   ```sh
   git push -u origin master
   ```
3. **Check:**
   - `git ls-remote origin master` matches `git rev-parse master`.
   - In Actions, the **Build** run on `master` is green. Its "Publish Wave"
     job is skipped, which is expected.
   - **Public website** fails or is skipped until step 4; that's fine.
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

1. In `thooton/wave`, open Settings → Pages → Build and deployment →
   Source, and choose **GitHub Actions**.
2. In Actions, choose **Public website**, then Run workflow on `master`.
3. **Check:** each of these prints 200:
   ```sh
   for p in "" setup/ jailbreak/ rescue/ restore/; do curl -s -o /dev/null -w "%{http_code} $p\n" https://thooton.github.io/wave/$p; done
   ```
   Then open https://thooton.github.io/wave/setup/ and make a setup file
   for any app. It downloads, and nothing is sent anywhere. `backup/` is
   meant to answer 404: the EDL backup page is left out of the public build
   (`site/scripts/build.py --with-device-backup` adds it).
4. **If it shows 404 or the deploy job fails:**
   - In Settings → Environments → `github-pages`, the deployment branch rule
     must allow `master`.
   - Then run the workflow again. A Pages site can take a few minutes to
     appear the first time.

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

## 6. Make the source publishing token (scope matters)

CI in W and P pushes manifests to R with this token. The publish action
takes a token, not a deploy key.

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

1. In `thooton/wave`, open Settings → Secrets and variables → Actions →
   Variables → New repository variable: `WAVE_PUBLISH_ENABLED` = `true`.
2. In Actions, choose **Build**, then Run workflow on `master`.
3. **Check:**
   - The release `wave-0.7.5` exists in `thooton/wave` with one APK.
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

## 9. First package release: push P (a release to existing phones)

**Before you push, know what happens.** CI starts on the push:
1. It builds all 18 APKs.
2. For each of the 16 apps in the default source, it creates an
   **immutable** release `<app>-<version>` (for example `list-1.2.7`,
   `appdrawer-…`) and publishes it into R.
3. It **replaces the assets of the `latest` release.** Every phone on the
   old Updater then sees updates to every app changed in the 87 commits.
   That includes the six apps that no longer carry a built-in login: Places,
   Mail, Charge, Authenticator, Flashcards and Canvas. Each carries the old
   login over once on update.

The versioned releases can't be taken back. `latest` can, from the backup
in step 1.

1. Push from W:
   ```sh
   git -C ../sonim push -u origin master
   git -C ../sonim push origin refs/tags/stock-messages refs/tags/stock-launcher refs/tags/stock-music
   ```
2. **Check:**
   - Actions → **Build and publish XP3800 packages** is green. The
     `release-mods` job is skipped; see step 10.
   - Releases show one `<app>-<version>` per app, and `latest` with
     `manifest.json` and `versions.txt`.
   - The old URL from step 1 still answers 200, and its `versions.txt`
     lists the new versions.
   - Rerun the clone and `lint --download` from step 7: all 17 packages
     (16 apps plus Wave) pass.
   - On the phone, Wave lists them under Applications.
3. **If a job fails:**
   - **"changes … require a versionName and versionCode bump":** an app
     changed after its release tag. Bump it in P, commit, push.
   - **Release made but the manifest wasn't pushed:** Run workflow with
     **retry-publish**.
   - **An app on old phones misbehaves:** put the old files back on `latest`
     until it's fixed:
     ```sh
     cd ~/Documents/latest-backup && gh release upload latest * --clobber -R thooton/wave_packages
     ```
     The next push to P's `master` overwrites `latest` again, so fix the
     app first.

## 10. Mods (not possible yet)

The three mods only install when signed with Sonim's own keys. Then:
1. Upload the OEM-signed APKs as artifacts named
   `<mod>-oem-signed-apks` in a workflow run.
2. Run **Build and publish XP3800 packages** with that run's ID as
   `signed-run-id`.

There is no signing material, so skip this. Nothing else depends on it.

## 11. Old-name sweep

1. In `thooton/delta` (its nightly QA) and in W, P and R:
   ```sh
   git grep -nE 'thooton/sonim|\.\./sonim'
   ```
   Change live references to `thooton/wave_packages`. Keep:
   - old Updater URL tests;
   - historical notes;
   - the local folder `../sonim`.
2. In `wave_packages`, open Settings → Secrets and variables → Actions.
   **Check:** `QA_DELTA_SERVER` and `QA_DEVICE_TOKEN` are still listed (a
   rename keeps them). The **QA emulators** workflow runs when started by
   hand.

<a id="source-and-tablet-private-cutover"></a>

## 12. Private source for Source and Tablet (blocked: don't start yet)

**Stop condition:** Wave can't download a private APK yet. Private
releases need an authenticated asset download, which is designed but not
built (decisions open item 4). Until it is, steps 4 and 5 below would
leave both apps uninstallable from the private source. Steps 1 to 3 are
safe to do early.

1. **Review the history.** Review `../wave_private_packages` (`2262590`)
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
5. **Test on the migration phone,** once Wave has authenticated downloads:
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
