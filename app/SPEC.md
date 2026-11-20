# Wave app behavior

Wave manages packages on the Sonim XP3800. The phone only changes when the
user asks. Browsing works without root; changing Magisk modules requires
approved root. The launcher opens `app.WaveActivity`. The old release-based
Updater (`MainActivity`) is not a launcher screen.

## Navigation

Each tab keeps its own screen stack and focus. Up/Down move between focus
stops, OK presses the selected action or opens the row, and Left/Right switch
between **Wave**, **Sources**, **Installed**, and **Search**. The tab bar never
takes focus. Back returns a screen or closes Wave from a tab's first screen
(while the queue works, it sends Wave to the background). Menu, on release,
opens actions for the focused row. Don't add text explaining which keys to press.

The bar shows the Back target in blue and the title centered. Actions are
words in title case (Get, not GET). They are black at rest, bold and underlined
when selected (3px underline), blue to go ahead and red to cancel, defer, or
remove. Unavailable actions are pale grey and never underlined. Reserve the
bold width so selection doesn't shift the layout.

Long lists keep focus and scroll position on refresh and return from another
screen. Up/Down scroll just enough to show the new focus; headers, headings
and gaps are not focus stops.

## Screens

- **Wave** (title Home): welcome, then root status. The orange **Root access
  not granted** row opens a screen with **Wave requires root access** and a
  **Grant access** button. The green **Root access granted** status is not
  actionable. Reboot to Finish appears when pending, followed by Device and
  Links. Add a Source lives in Sources, not Home.
- **Sources**: no title-bar actions; a spinner appears while refreshing.
  All Sources comes first, followed by each source's icon, name, address and
  refresh status. If nothing was fetched and a refresh failed, show **Cannot
  Load Sources**, the reason and **Try Again**. The menu offers Refresh, Copy
  Address, Remove Source (confirmed; not for the default source), Refresh
  Sources and Add a Source.
- **Source and section**: show the source's identity, All Packages, section
  counts and Featured. Browse rows open Details. A grey tick marks installed
  packages, a blue arrow marks updates, and incompatible packages are dimmed.
- **Details**: icon, name, author and the selected Get, Update or Open action.
  Show compatibility and reboot notes, then the description and Information.
  Leave package relationships out of Details; Confirm shows actual changes.
  An update puts What's New before the description. Up/Down scroll; OK presses
  the action; Reinstall and Remove are menu items.
- **Installed**: Updates, then Up to Date. The tab badge counts updates.
  **Update All** is menu-only and appears for multiple updates. OK presses a
  row's action; mod rows, Wave's own row and turned-off mods open Details.
- **Search**: the field starts focused and says **Search for packages** when
  empty. It supports Sonim's predictive T9, keeps the cursor at the end, and
  updates results across sources as the query changes. Back deletes in the
  field. Down enters results; Up on the first result returns to the field.
- **Add a Source**: starts at `https://github.com/`. Left/Right move the
  cursor, Back deletes and Down reaches Add. Fetch before asking for trust;
  the prompt shows the source name, maintainer and package count. Back
  abandons a fetch; failures appear below the field.
- **Confirm**: a sheet dims the page behind it. Show the action and version
  change, packages in a multi-package plan, dependencies or removals and why
  (**Also installs**, **Replaces**, **Removes**), download size and any reboot
  requirement. Cancel is on the left; the named action (Install, Update,
  Reinstall, Remove) is on the right. Start on the action unless the plan
  only removes, in which case start on Cancel. Left/Right switch between
  actions, Up/Down scroll a long plan, and Back cancels.
- **Installing / Done**: no tab bar. Show each package's progress (download
  ring, spinner, success tick or failure alert). Back while working sends Wave
  to the background. End with Done or, if a reboot is needed, Later and Reboot
  Now. A watchdog turn-off is an alert on launch with OK and Remove It.

## Catalog and install rules

- Fetch a source preview before persisting it. Keep the built-in source first
  and non-removable. A failed refresh keeps the last good cache and doesn't
  clear other sources' rows. Source generations are immutable; a damaged cache
  is refetched. Missing or invalid icons fall back to an initial tile.
- Merged candidates use highest version, then earliest source. Search ranks
  names before summaries and descriptions, returns no results for a blank
  query, and caps results at 200. A firmware advisory is not necessarily an
  install blocker. An incompatible winner can't be a plan target or dependency.
- Display addresses without credentials. Private source credentials stay in
  memory while downloading: Basic auth is limited to the source's scheme,
  host and port, with a Bearer token for the same repo's GitHub API release
  assets. Recheck on redirect; never send the token to the CDN. Manifest URLs
  can't contain credentials; never expose credentials in logs, messages or
  receipts. Exclude the credential-bearing `sources.json` from backups.
- Installed status comes from the phone. Keep still-installed packages found
  through Wave receipts even after removing their source. Magisk state supplies
  pending changes and turned-off status; acknowledging an alert doesn't enable
  a mod.
- Wave doesn't open an unapproved `su` shell at startup. Stock APK plans use
  Installer; module plans require approved root. Confirm only the latest
  preview for the current index and inventory. `EngineService` owns the queue
  independently of the open screen.

## Debug fixtures

The [handset smoke check](../tools/qa/README.md) uses `--es fixture` with
`browse`, `reboot`, `errors`, `offline`, `uptodate`, `big`, `noroot`, `newer`,
or `kept`. Add `--es query <text>` to open Search with a query. `big` supplies
500 packages; `newer` exercises a package that requires a Wave update; `kept`
shows packages retained after source removal. Release builds ignore both
extras. Debug screen timings use logcat tag `WaveTime`.
