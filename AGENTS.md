# AGENTS.md

Wave: a Cydia-like package manager and modding framework for the Sonim
XP3800 (Android 8.1, API 27, 240x320, d-pad only, rooted with Magisk 24.0).
Read `docs/design.md` before anything else, then the brief for your task in
`docs/tasks/`.

## Where things are

```
docs/design.md          the whole design
docs/source-format.md   what a source is (normative; spec/ has the schemas)
docs/jailbreak.md       the WebUSB jailbreak
docs/decisions.md       decided, assumed, open
docs/tasks/             one brief per piece of work; README.md has the order
spec/                   JSON Schemas and example-source/ (also the test fixture)
app/                    the Wave app (Gradle: :core plain Java, :app Android)
common/                 copy of wave_packages/common (don't edit here; sync it)
site/                   the website (jailbreak, landing, setup-code generator)
tools/wavesrc/          lint and publish for sources
cydia_images/           screenshots of Cydia, the reference for the app's screens
```

Sibling repositories on this machine: `../sonim` (becomes `wave_packages`),
`../messages_argon`, `../launcher_argon`, `../music_argon` (become
`wave_packages/mods/*`), and `../rphone` (the rooting notes and files). This
folder is `sonim_wave` locally and `thooton/wave` on GitHub.

## Rules

- **The design is the contract.** If your work needs the design to change,
  change `docs/design.md` (and `source-format.md`, the schemas and the
  example source if the format changes) in the same commit, and note it in
  `docs/decisions.md`. Don't let code and docs disagree.
- **Phone code**: Java 8 language level, nothing above API 27, no third-party
  dependencies in the app or its tests. `:core` is plain Java with a
  `SelfTest` main class, as in `wave_packages`. Read `../sonim/AGENTS.md` and
  `../sonim/DESIGN.md`; their conventions apply here.
- **The site** may use third-party JavaScript. Keep it a static site.
- **Every change to the app bumps its version** (`versionName` and
  `versionCode`), or it never reaches a phone.
- **Work autonomously.** Use the design and briefs to decide, implement,
  and verify work without personal sign-off. Record decisions and evidence.
  If access, credentials, or hardware are missing, record the affected step
  as blocked and continue independent work. Don't mark unverified work done.
- **The phone.** It has no touchscreen: keys only. Run device-changing
  steps only when the brief calls for them and an authorized device is
  reserved for the task by serial. Don't use an unreserved or busy phone.
  Never write to `/system`. Never flash a patched boot image from another
  phone, or restore anything other than the target phone's verified stock
  backup.
- **Commits**: author thooton. Never name Claude or Anthropic, and no
  AI attribution lines, in commits or pull requests.
- **Briefs**: when you finish one, tick it in `docs/tasks/README.md` and
  leave a line on anything the next agent needs to know.
