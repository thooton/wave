# The work

One brief per piece. Each says which repo it is in, what it depends on, what
to read, what to deliver and how to tell it is done. Read `../design.md`
first whatever you pick up.

Repos: **W** = `thooton/wave` (this one), **P** = `thooton/wave_packages`
(`../sonim` today), **R** = `thooton/wave_repository`.

Paths in the briefs: `../design.md` and the like are relative to this
folder. `../sonim`, `../rphone` and `../*_argon` are the sibling folders of
this repo on Sam's machine, under their names before the renames in 01 and
11.

| # | Brief | Repo | Needs | Done |
| :- | :--- | :-- | :--- | :-: |
| 00 | [Mockups of the app's screens](00-mockups.md) | W | | [ ] |
| 01 | [Create and rename the repos](01-repos.md) | all | | [ ] |
| 02 | [wavesrc: lint and publish](02-wavesrc.md) | W | | [ ] |
| 03 | [The git client](03-git-client.md) | W | 04 | [ ] |
| 04 | [App foundation from the Updater](04-app-foundation.md) | W | | [ ] |
| 05 | [Sources, index and search](05-sources-index.md) | W | 03, 04 | [ ] |
| 06 | [Root and the install engine](06-root-install.md) | W | 04 | [ ] |
| 07 | [The app's screens](07-app-screens.md) | W | 00, 05, 06 | [ ] |
| 08 | [The boot watchdog](08-boot-watchdog.md) | W | 06 | [ ] |
| 09 | [The jailbreak page](09-site-jailbreak.md) | W | | [ ] |
| 10 | [Landing page and setup-code generator](10-site-pages.md) | W | 14 for the generator | [ ] |
| 11 | [Import the mods into wave_packages](11-mods-import.md) | P | 01 | [ ] |
| 12 | [Rename Argon to Wave in the mods](12-mods-rename.md) | P | 11 | [ ] |
| 13 | [Releases and publishing from wave_packages CI](13-packages-ci.md) | P | 01, 02 | [ ] |
| 14 | [Setup codes: apps without a baked login](14-app-setup-codes.md) | P | | [ ] |
| 15 | [The default source](15-default-source.md) | R | 01, 02 | [ ] |
| 16 | [Source and Tablet in their own repo and source](16-private-source.md) | new | 13 | [ ] |
| 17 | [First phone, end to end](17-first-phone.md) | all | 07, 08, 09, 12, 13, 15 | [ ] |

## What can run at the same time

```
start now:   00  01  02  04  09  14(spike first)
then:        03  06        (after 04)      11 -> 12   13 15  (after 01, 02)
then:        05  08
then:        07            (after 00 approved, 05, 06)
last:        10  16  17
```

Four agents can work without touching the same files: one on the app's core
(03, 05, 06, 08), one on the site (09, 10), one on `wave_packages` (11, 12,
13), one on the app reworks (14). 00 and 07 belong to whoever does the UI.

## Notes from finished briefs

(Add a line here when you finish one and the next agent should know
something.)
