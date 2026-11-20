# Next: what needs the owner

Read [status](status.md) first. Everything below is blocked on a person with
a GitHub login, a signing key or the phone in their hand. Ordered by how much
each one unblocks. Nothing here has been pushed or published yet.

## 1. The GitHub cutover

**Do:** follow the numbered list in [the owner checklist](tasks/01-owner-checklist.md)
(renames, push W, tag `v1`, Pages, default source, publishing token, branch
protection, first publication, push P). Do step 0's test run and the review in
its step 2 before anything is pushed.

**Know it worked:** the checklist's "Done when" list: the old manifest URL
still answers, Pages serves the site, `wavesrc lint --download` passes on the
live default source, and Wave on the phone lists the published apps.

**Unblocks:** everything hosted: the site, the default source, Wave's
self-update, and the first-phone run against a real source
([brief 17](tasks/17-first-phone.md)).

**Know first:** pushing P (checklist step 9) is a real release. CI replaces the
assets of the `latest` release, so every phone still on the old Updater is
offered updates to the apps changed in P's 87 commits, including the six that
lose their built-in login (each carries the old login over once). The
immutable `<app>-<version>` releases can't be taken back; `latest` can, from the backup
the checklist has you make. The old channel must stay until the migration
checks in brief 17 pass ([decisions](decisions.md), working default 3).

## 2. Decide on the `stock-*` tags

**Do:** decide whether `stock-messages`, `stock-launcher` and `stock-music` go
public. They hold code derived from Sonim's stock APKs, and only you can judge
the redistribution rights (checklist step 0.2). Push them with the command in
step 9 or leave them local.

**Know it worked:** the choice is written down in [decisions](decisions.md).
If they stay private, the mods' regeneration scripts (`git diff stock-messages`
and friends, in `../sonim/mods/AGENTS.md`) only work in your clone.

**Unblocks:** the push of P (step 9), and anyone else rebuilding the mods.

## 3. OEM signing for the three mods

**Do:** get the mods' APKs signed with Sonim's own keys (`BMms`,
`TelephonyProvider`, `ContactsProvider`, `SonimLauncher`, `BMusic`), then
follow checklist step 10: upload them as `<mod>-oem-signed-apks` and run the
publish workflow with that run's ID. There is no signing material today and
the old build's signature was not valid ([decisions](decisions.md), decided 10).

**Know it worked:** the release gate verifies the real OEM signer and the
unchanged payload, a Magisk zip is published, and a mod installs through Wave
on the rooted phone and runs on its firmware.

**Unblocks:** the mods, which are the only thing between Wave and shipping its
point. Until then the install path is proven only with Wave's own test modules
([app-sweep.md](app-sweep.md), `app/root-tests`).

## 4. The private source (Source and Tablet)

**Do:** review the filtered history in `../wave_private_packages`, make a
read-only token, host the repo, and fetch one real private release asset on
the phone (checklist section 12; its stop condition applies).

**Know it worked:** on the migration phone, Wave adds the source with
`user:token@` and downloads and installs a real private asset; an update
follows.

**Unblocks:** retiring the legacy channel for those two apps. Until this passes
they stay on P's legacy channel ([decisions](decisions.md), open item 4).

## 5. Things only a person at the phone can do

The phone is rooted (Magisk 24.0). The rooted QA suite removes Wave when it ends, so build and install the current Wave (0.7.25) first; see [ROOT-TESTING.md](../app/ROOT-TESTING.md)
(its "Still open" list and sections 06 and 08). Use `tools/qa/phone-lock.sh`.

- **Volume Down safe mode.** Hold the side Volume Down key from power-on
  until the home screen. **Worked if** Magisk disables every module (a
  `disable` file in each), Wave shows "Turned off", and Turn On brings the
  module back. It was only tried through `persist.sys.safemode`.
- **A real power cut.** Pull the battery (or hold Power) during a mod's
  first boot. **Worked if** the next boot ends with `boot-incomplete` and the
  mod is turned off, not tried again. The runs so far used the kernel's
  immediate reboot, which loses unsynced writes the same way.
- **Details with real data: dependencies and replaced mods.** The real-data
  queue frames hold one mod, so the dependency and replaced-mod rows exist
  only in the fixture shots (`2ac1a56`, [CHECKLIST](../site/mockups/CHECKLIST.md)).
  Add a source with a mod that depends on, and one that replaces, another, and
  look at Details and Confirm. **Worked if** both rows read as in
  [`app/SPEC.md`](../app/SPEC.md).
- Also open: the "Wave was updated" toast on screen (ROOT-TESTING 06.8).

**Unblocks:** closing brief 08 and 07's last rows, and the claim that a bad
mod can't strand the phone.

## 6. Windows and macOS USB steps

**Do:** on each OS, follow [jailbreak.md](jailbreak.md) (Windows: Zadig and
WinUSB for `05C6 9008`; macOS: nothing expected) against the real phone, with
the hosted site. Report where a step fails or confuses.

**Know it worked:** Rescue lists the phone's modules and the (opt-in) backup
page reads `boot` twice with matching hashes. Linux and Chromium are the only
setup tried, and the browser's device chooser and first RSA prompt haven't
been seen live either.

**Unblocks:** the guide ([brief 09](tasks/09-site-jailbreak.md)) for owners who
aren't on Linux, and [decisions](decisions.md) open item 5 (a driver helper).
