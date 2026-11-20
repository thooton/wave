# 00: Mockups of the app's screens

**Repo** W. **Needs** nothing. **Blocks** 07.

## Read first
`../design.md` section 4, `../../cydia_images/` (the reference: Home,
Sources, Sections, Changes, Installed, Details, Search),
`../sonim/DESIGN.md` ("The rules" and "Updater: as the App Store shows it"),
and the Updater's layouts in `../sonim/updater/android/app/src/main/res/`.

## Goal
Static HTML mockups at exactly 240x320 of every screen, validated before
implementation. The look is the Updater's (the App Store's light
chrome, iOS blue, grey hairlines, focus drawn as a press), arranged as Cydia
is.

## Deliver
`site/mockups/app.html` (or `docs/mockups/`), one frame per screen, with the
focused row shown:

- The tab bar, in each of its four states.
- Wave tab: welcome header, Featured, What is Wave, Add a Source, device
  facts, the Reboot row.
- Sources: the list; Add a Source (typing a URL on a keypad); the confirm
  that says a source is trusted with root; a source's sections; a package
  list.
- Package details: not installed, installed, update available, a module,
  one that doesn't suit the phone, one that needs a newer Wave.
- Installed: with updates, with none, with the reboot banner.
- Search: empty, typing, results, nothing found.
- The queue: confirm (with a dependency and a replaced package), progress,
  Done, Reboot Now / Later.
- No root: the screen that points at the rooting guide.
- Errors: no network, a source that won't refresh, a download that fails its
  hash, a mod the watchdog turned off.

Beside each frame, a line on the keys: what Left/Right, Up/Down, OK, Back and
Menu do there.

## Done when
A screenshot of every frame has been checked at 240x320: essential content
and focus indicators fit, and the documented key paths reach every action
without touch. Record the results with the mockups and update
`../design.md` for any design changes. Task 07 can proceed once these checks
pass.

## Watch out
- 240x320 at mdpi means 1dp is 1px. A bottom tab bar, a title bar and the
  status bar leave about 210px: check that rows still fit a name, a line of
  detail and a capsule.
- There is no touch. Every control must be reachable with the d-pad and it
  must be obvious where focus is.
