# Security review of Wave

Written 2026-11-20 against Wave 0.7.28 (code 35); its fixes ship in 0.7.29, from the code, the release
APK's manifest and the test logs. **Nothing here was measured on the phone**:
file modes and SELinux labels are what the code and Android 8.1 give, with the
commands to confirm them at the end.

The model is the owner's: one person's phone, their own data, root granted to
Wave. The question asked is what another app, someone holding the phone, or
someone holding a copy of its backup can learn from Wave and make it do.

The short answer: an ordinary app on the phone learns almost nothing and can
make Wave do nothing. Wave's real exposure is the one it was built with: a
source you add can run anything as root, the signing key is public, and a
private source's token sits in one app-private file.

Severity is for the owner's model (High: root or the token to a stranger;
Medium: needs a second mistake or a published secret; Low: needs adb or a
rare setup; Info: worth knowing).

## 1. What Wave writes

All paths are under `/data/data/com.wave.xp3` unless stated. Wave asks for no
storage permission and writes nothing to shared storage.

| What | Path | Holds | Mode (expected) | `adb backup` | Uninstall |
|---|---|---|---|---|---|
| Source list | `files/sources.json` | each source's address, **with the token** for a private one, name, last commit | 0600 (set explicitly in `SourceStore.save`) | **excluded** (`res/xml/backup.xml`) | gone |
| Source trees | `files/sources/<sha256 of address>/tree/<commit>/` and `sources/damaged.json` | the source's own public files (wave.json, package.json, icons); a set-aside copy of an unreadable list, which can hold the token | 0600 files in 0700 folders | **excluded** | gone |
| Receipts | `files/receipts.json` | per installed package: id, version, source address (token removed), SHA-256, time | Disk.write, default 0600 | included | gone |
| Root flag | `shared_prefs/root.xml` | one boolean: root has worked | 0660 by Android, inside the 0700 data folder | included | gone |
| Downloads | `cache/downloads/<id>-<version>.apk/.zip` and `.part` | a package while it installs; deleted when the step ends or fails | 0600 | not backed up (cache) | gone |
| Old updater APKs | `files/apks/` | only the old Updater screen's downloads (public GitHub assets) | 0600 | included | gone |

Root-owned, outside the app (`/data/adb` is root-only 0700 on Magisk, and Wave
sets 0700 on its own folder in it):

| What | Path | Holds | Uninstall |
|---|---|---|---|
| Watchdog state | `/data/adb/wave/{pending,trying,disabled,forced,forced.last,log,self-update}` | module ids and times, one short log (kept to about 200 lines), pm's answer after a self-update | **stays** |
| Boot watchdog | `/data/adb/post-fs-data.d/wave-watchdog.sh`, `/data/adb/service.d/wave-watchdog.sh` (0755, root) | the two fixed scripts shipped in Wave's resources | **stays** and keeps running at every boot |
| Self-update copy | `/data/local/tmp/wave-self-update.apk` | Wave's next APK, for a moment; removed by the job | stays only if the job was killed |
| Modules and apps Wave installed | `/data/adb/modules*`, the package list | whatever the sources shipped | stays, as installed software does |

Who can read what:

- **Another ordinary app**: nothing. Android 8.1 gives every app a 0700 data
  folder, and Wave uses no world-readable mode, no external storage, no
  exported provider. It can list installed package names, as any app can on
  this Android version without Wave.
- **Someone with `adb backup` of the phone** (needs the unlocked phone, USB
  debugging authorized, and Android's own backup prompt): receipts.json (what
  Wave installed, from which source address, when) and the root flag. **Not**
  the sources or their tokens. This is separate from decision 11, which is
  about the other apps' logins.
- **Someone with the phone unlocked, USB debugging on and no root for them**:
  the same, plus the screen. Release builds are not debuggable, so `run-as`
  does not work on them.
- **Anyone with root on the phone** (including a root app Magisk lets in):
  everything, including the token. That is Magisk's gate, not Wave's.

## 2. Logs

Searched `app/`, `common/` and the root scripts for every log call, print,
stack trace, toast, clipboard use and error string that takes a URL, token,
path or package list.

- The release app logs nothing. The only `Log` call is the `WaveTime` screen
  timing (screen class name and milliseconds), and it runs only when the
  APK is debuggable (`WaveActivity` line 165).
- Failures reach the queue and the source screens as sentences, from
  `SourceFailure.Kind` or `Engine.message`. They name a package, a version,
  a host (from the system's own network error) or nothing. Sign-in problems
  say "the token in its address", never the token.
- A token never reaches a receipt (the address stored there is the redacted
  one), the sources screen, a source's id (a hash of the redacted address),
  "Copy Address" (copies the redacted address, so a private source's token
  cannot be copied back out), a download request to another host, or an
  exception message. New test `SourcesSelfTest.tokenInFailures` feeds eight
  hostile addresses (plain http, a space in the token, a query, a fragment,
  a bad host, ftp, a 5,000-character host, an unresolvable host) through
  `preview`, `add` and `refresh` and searches the message, the detail and the
  whole cause chain for the token. The existing tests cover the download path
  (credential only to the source's own host, no redirects with a sign-in, a
  download address that carries one is refused).
- Root scripts: the watchdog's `/data/adb/wave/log` holds lines like
  `1763500000 post-fs-data: disabled <module id> (boot-timeout)`. Module ids
  and times only, in a root-only folder. No script prints a source address,
  token or path outside the app.

No finding.

## 3. Intents and exports

From the release APK's manifest (`aapt dump xmltree`):

| Component | exported | Notes |
|---|---|---|
| `WaveActivity` | yes (launcher) | The only way in. In a release build it reads no extra: `fixture` and `query` are honored only when `FakeWaveData` exists, and that class is in `src/debug`. So an intent can open Wave and nothing else. |
| `MainActivity` (the old Updater screen) | no | |
| `StockApps` | no | Takes an inner intent only from Wave itself. |
| `EngineService` | no | |
| `ApkProvider` | no, `grantUriPermissions` true | Serves one name at a time (`[A-Za-z0-9_-]{1,64}.apk`, no path), read only, to whoever Wave grants a URI. |

No receivers, no other providers, no custom permission declared. Uses
`INTERNET`, `REQUEST_INSTALL_PACKAGES` (the stock fallback) and Installer's
signature permission. `usesCleartextTraffic` is false; only `https://` is
accepted for sources and downloads.

An ordinary app cannot make Wave install, remove or run anything. What is
left is Low: when there is no root and no Installer, Wave sends an implicit
`ACTION_INSTALL_PACKAGE` with a read grant on the downloaded APK. An app
that registers that action could be offered the file. It is a public,
SHA-pinned package (a private source's package would leak to it). The
choice screen shows if more than one handler exists.

## 4. Root

### What Wave can do once granted

Everything the scripts in `core/root/Scripts.java` say, and nothing else is
sent to `su`:

- `pm install -r -S <size>` fed from a file Wave downloaded and checked
  (SHA-256, size, package id, version, key against the phone's copy);
  `pm uninstall <package>`; `cmd package compile`; `am start` of Wave itself
  after a self-update.
- `magisk --install-module <zip>`: the module's own install script then runs
  as root, as Magisk always does. Wave has checked only the zip's SHA-256 and
  that its `module.prop` id and version match the manifest.
- Edits to `/data/adb/modules*` (a `remove` or `disable` file, deleting
  `modules_update/<id>`), Wave's folder in `/data/adb`, and the two watchdog
  scripts.
- Read-only: the module list and a few facts for Details.

So **a source's maintainer, or whoever controls the account its address
points at, can run any code as root on the phone** by shipping a module, and
can install any app or remove any installed package through `replaces`.
That is the design (docs/design.md, Trust), the dialog says it, and every
step of a plan is shown before it runs. Accepted.

### Strings the engine puts into a script

Every value that reaches a root shell, and what stops it:

| Value | From | Guard |
|---|---|---|
| Package id (`pm uninstall`, `compile`, `am start`) | source manifest or phone | `Scripts.isPackage`: `[A-Za-z][A-Za-z0-9_]*(.[A-Za-z][A-Za-z0-9_]*)+`, 255 max; else `IllegalArgumentException` |
| Module id (paths in `/data/adb`, `grep`, `rm -rf`, `touch`) | source manifest or phone folder name | `Scripts.isModuleId`: `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`, and `Verify.module` rejects a zip whose own id differs |
| Every path and every id used as a word | | `Scripts.quote`: single-quoted, `'` escaped, NUL refused |
| Download file name | id and version | `Engine.safe`: anything outside `[A-Za-z0-9._-]` becomes `_` |
| Version | source manifest | `Fields.VERSION`, digits and dots only, before it reaches the engine |
| Size, pid | numbers | typed `long` and `int` |
| Activity name | a constant in Wave | `activity()`: `[A-Za-z][A-Za-z0-9_.]*` |
| Watchdog script body | Wave's resources | a here-document with a fixed delimiter; refuses a body containing it |
| Ids the watchdog reads back from its own lists | root-owned files | it skips empty, `.`, `..` and anything with `/` |

No path from a manifest string, a package id, a version or a server file name
to command execution as root was found. Two real defects in the same area
were fixed (below). Nothing the server sends is ever a shell word without
`quote` or a regex of its own.

### Found and fixed

1. **Medium-Low, robustness: a module id of 65 to 100 characters**. The
   source format allows 100 (`Fields.ID`); `Scripts` allowed 64, and threw
   `IllegalArgumentException` from the engine thread, which nothing caught.
   Needs: a source with such an id, or one on the phone. Fix: the step now
   fails with a plain message (`Engine.step`).
2. **Low: ids starting with `-`, `.` or `_`**. `Scripts.MODULE_ID` accepted
   them. Inside a path they are harmless, but `grep -qxF '-f' file` and `rm`
   argument forms are where a leading dash turns into an option. The id now
   must start with a letter or digit, as the source format already requires.
   Tests in `RootTests` and `InstallTests.badIds`.
3. **Low: the self-update copy in `/data/local/tmp`**. That folder belongs to
   the shell user. A symlink left at `wave-self-update.apk` by anything
   running as shell would be followed by root's `cp`, overwriting its target
   with Wave's next APK. The script now `rm -f`s the name first. A rename race
   between `cp` and `pm install` is still possible for code running as shell
   (an adb session), which already has more than that. Accepted.

### Not fixed

- **Trust anchor** (High impact, accepted). The chain is TLS to the git host,
  then the SHA-256 in the manifest. There are no source signatures and no
  certificate pinning, and the phone's CA list is Android 8.1's plus whatever
  the owner installs. Compromising the GitHub account behind
  `thooton/wave_repository` (the built-in source) or any added source is root
  on every phone that refreshes it. Protection is the account's own (a hardware
  key or a TOTP, and a source that is only ever pushed to from one machine).
  Signed sources are listed in the design as later.
- **The signing key is a public debug key** (Medium; would be High once it is
  public in fact). `signing/debug.keystore` (password `android`) signs every
  app in the family, release too, and is committed to both `wave` and
  `wave_packages`. Decision 2 forbids changing it (every installed app would
  be uninstalled with its data). Consequences once the repositories are
  readable by others: anyone can build an APK that Android accepts as an
  update to Wave or any of the six apps, keeps their data and, for Wave,
  keeps its root grant (Magisk grants by uid); and any app signed with the key
  gets Installer's signature permission, which installs without a prompt on
  phones that have Installer. It still needs the owner to open a file and
  confirm Android's update dialog, or for Installer to be present with a
  hostile app on the phone. **Decide before the GitHub cutover whether the
  repositories that hold the keystore are public.** Keeping `signing/`
  private (a private `wave_packages`, with only the built APKs and the source
  public) removes the attack; making it public makes it available. This is
  an owner decision, not a code change.
- **A source can claim any name.** The add dialog now also shows the address
  the source was added from (new, `AddSourceScreen.trust`), because the name,
  icon and maintainer are the source's own text. A look-alike source still has
  to be typed in by its address. Low.
- **A private source's token is plain text** in `sources.json` (0600, private,
  excluded from backup). Wave has to send it, so there is nothing to hash.
  Moving it to the Android Keystore would not stop root, which is the only
  reader that matters, and would break the file-copy workflow. Accepted.
- **The token is visible while it is typed** (the address field is a URL
  field, not a password field), so it appears on screen, in a screenshot of
  Recents and in the phone's clipboard if it was pasted. `NO_SUGGESTIONS` is
  set so keyboards should not learn it. Android 8.1 lets any app read the
  clipboard, and Wave cannot clear it (API 27). Prefer the setup route, or
  type it. Low.
- **No tapjacking guard.** Wave does not filter touches when another app draws
  over it. The XP3800 is operated by keys, and any overlay needs the owner to
  have granted a draw-over permission. Low, not changed: filtering would
  break screen-dimming overlays the owner may use.
- **Plain http to localhost** is allowed by `Download.checkUrl` for the tests;
  `usesCleartextTraffic=false` blocks it on the phone. Info.

## 5. For someone about to install Wave and grant it root

- **Adding a source is giving it root.** Wave says so each time. A source's
  plan can install apps, flash Magisk modules (their install scripts run as
  root before anything of yours checks them), and remove other packages. Wave
  checks that what it downloaded is the file the source named; it cannot
  check that the source is honest.
- **The built-in source** is `github.com/thooton/wave_repository`. Whoever
  controls that account controls every phone that has Wave.
- **Wave updates itself** from a source. An update signed with the shared key
  installs as Wave and keeps the root grant.
- **After uninstalling Wave**: the root grant entry in Magisk (remove it
  there), `/data/adb/wave/` and the two `wave-watchdog.sh` scripts stay. The
  watchdog is the only thing that can switch a bad module off at boot; delete
  the scripts and the folder to remove it. Modules and apps Wave installed
  stay installed.
- **A private source's token** lives in Wave's private storage. It never
  goes into a backup, a receipt or a log, and it is sent only to the host the
  source came from. Use a token with the least scope (read-only on the one
  repository).
- **Backup**: `adb backup` of the phone carries receipts and the six other
  apps' logins (decision 11), not Wave's sources.
- Wave asks `su` for nothing until root has worked once. After that it uses
  root on open (module list, the boot check, a self-update's result) and for
  each install or removal you confirm.
- **Power cut or bad module**: the boot watchdog turns off the modules Wave
  installed if the first boot never settles. It is a net, not a guarantee
  (docs/next.md names the battery-pull test still to do).

## 6. To confirm on the phone

Not run (this audit did not touch it). With `tools/qa/phone-run.sh`, as root
(`su -c`), on `8516067e`:

```
ls -ld  /data/data/com.wave.xp3 /data/data/com.wave.xp3/files /data/data/com.wave.xp3/files/sources
ls -l   /data/data/com.wave.xp3/files /data/data/com.wave.xp3/shared_prefs
ls -ld  /data/adb /data/adb/wave; ls -l /data/adb/wave /data/adb/post-fs-data.d /data/adb/service.d
ls -l   /data/local/tmp
```

Expected: the app's folders `drwx------` owned by the app's uid; every file in
`files/` `-rw-------`; `/data/adb/wave` `drwx------ root`; the two scripts
`-rwxr-xr-x root`; nothing named `wave-*` in `/data/local/tmp`. A backup check:
`adb backup -f x.ab com.wave.xp3`, then unpack it and confirm `sources.json`
and `sources/` are absent (and whether a restore of a hand-built archive
honors the exclusion, which this review could not settle).
