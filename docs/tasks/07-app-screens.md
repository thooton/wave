# 07: The app's screens

**Repo** W (`app/app`). **Needs** 00 approved, 05, 06.

## Read first
The approved mockups (00), `../design.md` section 4, `../sonim/DESIGN.md`,
`common/ui` (`BaseActivity`, `Tabs`, `SelectableAdapter`, `Markdown`), the
Updater's `MainActivity` and `AppAdapter`.

## Goal
The four tabs and everything under them, as mocked up, on top of 05 and 06.

## Deliver
- The tab bar and the four tabs: Wave, Sources (list, add, remove, refresh,
  sections, package lists), Installed (the Updater's screen fed from the
  index), Search.
- Package details, the queue (confirm, progress, done, reboot), the no-root
  screen, and every error state in the mockups.
- First run: check root, load the default source, adopt what is installed.
- `app/SPEC.md` describing the behaviour of each screen and key, kept true
  from here on.

## Done when
Every mockup frame has a matching screenshot from the phone or the XP3
emulator, and a run-through with the d-pad alone reaches every action.
Installing Wave over the Updater-derived build from 04 keeps working.

## Watch out
- Split the Updater's 900-line `MainActivity` as you go: one class per
  screen, the queue owned by 06's `Engine`.
- Lists can be long (All Packages). Icons load off the main thread and rows
  recycle.
- Text entry on a keypad: the source URL field should start with
  `https://github.com/` and accept a QR scan once 14's scanner exists.
