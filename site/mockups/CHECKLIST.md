# Mockups: frame and d-pad checklist (brief 00)

`app.html` draws every screen of Wave at 240x320 with the keys under each;
`app.html?frame=<id>` draws one frame alone. `shoot.sh` screenshots every
frame into `shots/<id>.png` (headless Chromium, window 240x320, scale 1) and
the gallery into `shots/all.png`; `sheets.py <dir>` lays the shots out 8 to a
sheet at 1:1 for checking. `icons/vec2svg.py ../sonim` regenerates the app
icons from the apps' own vector launcher icons.

How the frames were made to match the phone (checked 2026-11-19 on the
attached XP3800, serial 8516067e, with the Updater open): density 160, so
1dp = 1px; status bar 24px and bar 34px, both `#F9F9F9`; hairline `#B2B2B2`
at y=58; the page starts at y=59; the Updater's message screen is a 17px
bold title over a 13px grey line. The fonts are the phone's own
`/system/fonts/Roboto-{Regular,Medium,Bold}.ttf` (in `fonts/`, Apache 2.0),
so text widths match what the phone draws. Real screens may differ by a
pixel or two in line height (Android's `includeFontPadding=false` versus
CSS `line-height: normal`).

`phone/<id>.png` is the same frame shot on that phone from Wave 0.7.5/0.7.6
(brief 07) with the debug fixtures (`app/SPEC.md`), by `phone-frames.py`.
Known differences: the menus are Android's own context menu (smaller text,
items about 32px); fixture icons are lettered tiles where the mockups have
the apps' art; the section's menu says Refresh Sources.

Budget: 24 status + 34 bar + 1 hairline + 36 tab bar leaves **225px** of
page on a tab. A browsed package row and an Installed row are 47px (name,
a line of detail, a capsule or chevron): 4.8 rows show. The Updater's row
with two lines of what's new is about 93px.

## 1. Frames (screenshots checked at 240x320)

Each shot was looked at full size. "Fits" means the essential content (what
the screen is, the focused thing and its action) is inside the frame without
clipping that hides meaning; a list that continues past the bottom is
intended (it scrolls), and is marked "scrolls".

| Frame | Fits | Focus visible | Notes |
| :--- | :-: | :-: | :--- |
| wave-home | yes, scrolls | row grey | Welcome, Featured (2), What is Wave peeks in |
| wave-home-more | yes, scrolls | row grey | Facts are text, not stops |
| wave-home-reboot | yes, scrolls | banner grey, REBOOT filled | |
| wave-about | yes, scrolls | none (scrolling text) | Up/Down scroll |
| sources | yes | row grey | 4 rows, URLs ellipsized |
| sources-bar | yes | Add filled blue | |
| sources-refreshing | yes | row grey | spinner replaces Refresh |
| sources-menu | yes | menu item washed | menu over its row |
| add-source | yes | field outlined blue, caret, T9 underline | IME mode "abc" in status bar |
| add-source-button | yes | Add filled | |
| add-source-fetching | yes | none (working) | |
| add-source-trust | yes | "Add Source" washed | source named, with maintainer and count |
| add-source-bad | yes | field outlined red, caret | reason under the field |
| source | yes, scrolls | row grey | sections with counts, then Featured |
| section | yes, scrolls | row grey | badges: grey tick installed, blue arrow update |
| section-menu | yes | menu item washed | |
| details-get | yes, scrolls | GET filled | |
| details-get-more | yes, scrolls | (GET scrolled away, still OK's target) | Information facts |
| details-installed | yes, scrolls | OPEN filled | |
| details-menu | yes | Remove washed | REMOVE only here |
| details-update | yes, scrolls | UPDATE filled | 2.3.1 → 2.4.0, What's New first |
| details-module | yes, scrolls | GET filled | reboot, replaces, firmware lines |
| details-unfit | yes | GET greyed (nothing to press) | red reason |
| details-newer | yes | GET greyed | |
| installed | yes, scrolls | row grey, UPDATE filled | Update All on bar, badge 2 on the tab |
| installed-updateall | yes, scrolls | Update All filled | no row focus |
| installed-more | yes, scrolls | row grey | mod row: chevron, no capsule |
| installed-none | yes, scrolls | row grey, OPEN filled | no Update All, no badge |
| installed-reboot | yes, scrolls | banner grey, REBOOT filled | |
| installed-menu | yes | menu item washed | |
| search-empty | yes | field outlined, caret | |
| search-typing | yes | field outlined, caret, T9 underline | live results |
| search-results | yes | row grey; field at rest | |
| search-none | yes | field outlined | "No Results" |
| queue-confirm | yes | Confirm filled | whole plan fits: 2 installs (one a dependency, said why), 1 removal (replaced, said why) |
| queue-progress | yes | none (working) | ring with %, spinner for waiting |
| queue-done | yes | Done filled | |
| queue-reboot | yes | Reboot Now filled | Later beside it |
| no-root | yes | Check Again filled | site address in blue |
| error-network | yes | Try Again filled | |
| error-network-toast | yes | row grey | toast over the list |
| error-source | yes | row grey | red reason, orange warning |
| error-hash | yes | OK washed | alert over the queue |
| error-watchdog | yes | OK washed | which mod, which version, why |
| error-watchdog-row | yes | row grey | "Turned off" in red |

Result: **all 46 frames pass**. Fixed during the check: back labels too long
for the bar ("Applic…") now read "Back"; bar titles are centred on the
screen and as wide as the bar's sides leave them (computed in `app.html`, as
the app should), which un-cut "Add a Source" and "What is Wave"; the
confirm's plan was 12px too tall and its rows are now 42px; a firmware note
that wrapped mid-build was shortened; the progress rows' ring margin
squeezed the names; the toast wrapped at half width; a failing source's
reason and the turned-off mod's line were cut and are now short enough to
read whole.

## 2. D-pad paths: every action without touch

From opening Wave (the Wave tab, focus on the first Featured row). Each step
lands on a focus shown in a frame above. "Menu > X" is Menu, Down to X, OK.

| Action | Keys | Frames |
| :--- | :--- | :--- |
| Switch tab | Left / Right from any row | every tab frame |
| Open a Featured package | OK | wave-home, details-get |
| Get it | OK on details (GET) > OK on Confirm | details-get, queue-confirm |
| Get without opening | Menu > Get on the row | section-menu |
| What is Wave | Down to it > OK; Back | wave-home-more, wave-about |
| Add a Source (from Wave) | Down to Add a Source > OK | wave-home-more, add-source |
| Add a Source (from Sources) | Right > Up from top row (Add) > OK | sources-bar |
| Type the address | keys 2-9 multi-tap, 1 for . / - _, # abc/123, Back deletes, Left/Right cursor | add-source |
| Submit it | Down (Add) > OK > (fetch) > OK on Add Source | add-source-button, -fetching, -trust |
| Correct a bad address | Up into field, edit, Down, OK | add-source-bad |
| Refresh every source | Sources: Up (bar) > Left (Refresh) > OK; or Menu > Refresh Sources | sources-bar, sources-refreshing |
| Refresh / remove one source | Menu on its row > Refresh / Remove Source | sources-menu |
| Browse a source | OK on source > OK on section > OK on package | source, section, details-get |
| Read a long details page | Up / Down scroll; OK stays on the capsule | details-get-more |
| Update one | Installed: OK on its UPDATE > OK on Confirm | installed |
| Update all | Installed: Up from top row (Update All) > OK > Confirm | installed-updateall |
| Open an installed app | Installed: OK on OPEN; or details OK | installed-none, details-installed |
| Details from Installed | Menu > Details; a mod's row: OK | installed-menu, installed-more |
| Remove a package | details: Menu > Remove > Confirm | details-menu |
| Reinstall | Menu > Reinstall | details-menu |
| What's New | details page; or Menu > What's New | details-update, installed-menu |
| Search | Right to Search, type, Down into results, OK | search-typing, search-results |
| Back to the field | Up on the first result, or Back | search-results |
| Cancel a plan | Left (Cancel) > OK, or Back | queue-confirm |
| Leave while it works | Back (work carries on) | queue-progress |
| Reboot after a mod | Done: OK (Reboot Now) or Left > OK (Later); later: Reboot row on Wave or Installed | queue-reboot, wave-home-reboot, installed-reboot |
| Dismiss a failure | OK | error-hash |
| Retry with no network | OK (Try Again) | error-network |
| Fix a failing source | Menu on its row > Refresh / Remove Source | error-source |
| Deal with a turned-off mod | Alert: OK, or Left > OK (Remove It); later Installed: Menu > Turn On / Remove | error-watchdog, error-watchdog-row |
| Without root | OK (Check Again); Back goes back | no-root |
| Leave Wave | Back on a tab's top screen | all tab tops |

Result: **every action is reachable** with Left/Right, Up/Down, OK, Back and
Menu (and the keypad for typing). Where Left/Right don't switch tabs, the
screen uses them and says so in its keys: the bar's two links on Sources,
the address field's cursor, two-button feet and dialogs. Nothing needs a
long press.

## 3. Notes for brief 07 (implementation)

- One rule tells the user what OK does: **a filled capsule (or button) is
  what OK presses; otherwise OK opens the row** (a chevron says so). Browse
  rows (sections, All Packages, Featured, search) have no capsule: their
  state is a badge on the icon. Installed rows are the Updater's and keep
  OK-presses-the-capsule. A mod's row and Wave's own have no capsule.
- Details: the capsule is the page's only focus stop; intercept Up/Down to
  scroll the ScrollView (about two lines) without moving focus. An
  installed mod has no capsule there; OK opens the menu.
- Home and Sources: a ListView with headings as part of the row (as
  `row_app.xml` does) keeps one position per focus stop; Home's facts and
  links are a footer below the last focus stop, scrolled in by Down.
- Bar links (Refresh, Add, Update All) are focusable views above the list,
  reached by Up from the top row, drawn with the Updater's `link` drawable.
- Back's label on the bar is drawn but never focused; over 9 characters it
  reads "Back".
- The tab bar is 36dp: 18dp icons over 10sp labels, the open tab `#007AFF`,
  others `#8E8E93`, the Installed tab carries a red badge with the number of
  updates. It never takes focus.
- The address field starts as `https://github.com/`, cursor at the end; Wave
  fetches the source before showing the trust dialog with its name,
  maintainer and package count.
- Search keeps the cursor at the end so Left/Right stay free for tabs; Back
  deletes; results update on each key.
- Queue screens (Confirm, Installing, Done) are modal: no tab bar, buttons
  in a foot on the bar colour; focus starts on the forward action.
- Sample data is illustrative (SQLite Upgrade stands in for any
  dependency, XP3 Games and Sonim Tweaks for third-party sources).
