# The 18-app launch sweep

Recorded 2026-11-19 from agent 6's run on the XP3800 `8516067e` (stock
Android at the start, Magisk 24.0 arriving mid-sweep). It checks that each
app's code starts and draws on the phone. It is a smoke test, not feature QA.

**Method, and what it does not prove.** The owner's 18 apps are installed with
the owner's data, so the release APKs were not installed over them. The same
release code was built as side-by-side `.qa` variants (applicationId suffix
`.qa`, scratch clone of `wave_packages` at `a361464`, nothing in P changed),
installed, launched, poked with d-pad keys and removed one by one. A `.qa` copy
tests the code, not the shipped identity or an update over the owner's copy.
The phone's package set after the sweep equals the one before (plus Magisk).
Print and AppDrawer first needed local patches for hard-coded package names;
that is fixed in P `bef44ad` (Print 0.3.5, AppDrawer 1.1.4) and re-checked as
`.qa` without patches.

Per app: it launched to its main screen, the screen looked right at 240x320,
Down, Right, Menu and Back reached the main actions, and the logcat diff showed
no FATAL or ANR from the app. The probe is shallow (those four keys, not every
feature).

| App | Version (code) | Launched | Screen ok | Crash or ANR |
| :--- | :--- | :-: | :-: | :-: |
| Authenticator | 1.2.2 (11) | yes | yes | none |
| Books | 1.1.7 (11) | yes | yes | none |
| Canvas (`com.todo.xp3`) | 1.8.3 (29) | yes | yes | none |
| Charge | 1.1.3 (13) | yes | yes | none |
| Dictionary | 1.3.6 (15) | yes | yes | none |
| Flashcards | 1.14.2 (31) | yes | **defect**: setup title white on near-white | none |
| List | 1.2.7 (15) | yes | yes | none |
| Mail | 1.5.2 (19) | yes | yes | none |
| Musica | 1.0.3 (4) | yes | yes | none |
| New Yorker | 1.2.5 (12) | yes | yes | none |
| Places | 1.7.2 (22) | yes | yes | none |
| Print | 0.3.4 (9), then 0.3.5 (10) | yes | yes | none |
| Serve | 1.1.9 (12) | yes | yes | none |
| Source | 1.4.7 (20) | yes | yes | none |
| Tablet | 0.11.4 (16) | yes | yes | none |
| Video | 1.0.3 (4) | yes | yes | none |
| Weather | 1.0.5 (6) | yes | yes | none |
| AppDrawer | 1.1.3 (9), then 1.1.4 (10) | yes | yes | none |

- The Flashcards defect was fixed in P `d905f90` (1.14.3, code 32). It was not
  re-run on the phone; the fix has a self-test.
- The six reworked apps (Authenticator, Canvas, Charge, Mail, Places,
  Flashcards) showed the setup screen on first launch, and dismissing it (Back,
  or "On this phone only") left the app usable.
- Signer audit: 17 apps are signed with the shared key (SHA-256
  `3a5643633e00450967b7674cded6b2cce46456561a6f2c1d48ae8344b1fadb0e`);
  AppDrawer has its own (`0a4c7a2fe861f04444dab12adaab57e93eb5edce2d1a1a15cf42e0a01bc0dd4c`).
  All match the installed copies.
- Caveats: the sweep's text summary log was truncated by accident before the
  fix re-check, so per-app launch lines survive only in the screenshots, the
  uiautomator dumps and the logcat files. The whole-buffer logcat files show 4
  FATAL lines, all from `com.topjohnwu.magisk` (the Magisk app's own
  ClassNotFoundException while it was being set up), none from these apps.
  Charge's first run overlapped a Magisk dialog, so its flow was redone.

## Evidence

Kept here (`docs/evidence/app-sweep/`, 424 kB):

- `m-rest.png`: Dictionary, List, Serve, Video and Weather launch screens.
- `m-fix.png`: Print 0.3.5 and AppDrawer 1.1.4, after the hard-coded-name fix.
- `flashcards-1.14.2-setup-title.png`: the defect.
- `qa-versions.txt`: the `.qa` packages and versions that were installed.
- `marker-module-test.log`: the harmless marker module (`wave_qa_marker`)
  installed through Magisk, run over two reboots and removed; `su` still works
  afterwards. This is the module-packaging check, with a marker, not a mod.

Left in scratch (gitignored, may be cleared):
`.oak/tmp/temp9/dev/shots/` (144 PNGs with matching uiautomator XML, 4.7 MB;
`<app>-0-launch`, `-1-down2`, `-2-right`, `-3-menu`, `-4-back`; flows are
`f-<app>-<step>`), the per-app contact sheets `m-<app>.png` (some show the
owner's mail and places data, so they are not committed), the flow sheets
`m-f-*.png`, `flow.log`, `installed-certs.txt`, `local-certs.txt`, the
`log-before-*` and `log-after-*` logcat files, and the `.qa` APKs in `qa/`.
