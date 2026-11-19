# 14: Setup codes: apps without a baked login

**Repo** P (`common/`, then one app at a time). **Needs** nothing. Start
with the spike.

## Read first
`../design.md` section 8 ("Apps that needed Delta"),
`../sonim/gradle/bundled.gradle`, `common/core`'s `Login`, and for each app
its `AGENTS.md`, `SPEC.md` and where it uses `Bundled` and `Login`: Canvas,
Charge, Mail, Places, Authenticator, Flashcards.

## Goal
Each of the six apps works for a stranger who brings their own credentials,
moved onto the phone by scanning a QR code, and still works through Delta
for a phone that is given a Delta login the same way. One code path per app,
two backends.

## Step 1, a spike, before any design is fixed
Can the XP3 read a QR code off a monitor? Fixed-focus 5 MP camera, no
library allowed. Find out: what module size and code version it reads
reliably, at what distance, from the camera preview and from a still. Report
to Sam with the numbers and a recommendation on `../decisions.md` open 1
(write a decoder, allow ZXing core as the one exception, or use a fallback
transport). Nothing below is built until that is settled.

## Step 2, shared pieces in `common/`
- **The payload**: a versioned, compact JSON document: which app it is for
  and that app's settings; or a Delta address and token. Chunked across
  several codes if one would be too dense. Specified in a short doc that
  10's generator also follows.
- **A description of each app's settings** (name, label, how to obtain it,
  secret or not), in one machine-readable file per app, from which both the
  app's setup screen and the generator's form are built.
- **`Setup` in `common/ui`**: "Scan setup code", the scanner, a summary of
  what was read, storing it privately; plus the fallbacks (a file on the SD
  card, manual entry for short values). And "Sign out" that forgets it.
- **`Config` in `common/core`**: what the app reads at runtime, replacing
  `Bundled` and the scattered login prefs.

## Step 3, each app
For each: define the direct backend (Charge: Tesla's API with the user's
tokens; Mail: Gmail with the user's OAuth client and refresh token; Places:
Google's Places API with the user's key; Canvas: the school's Canvas URL and
an access token; Authenticator and Flashcards: whatever they use Delta for
today, probably sync, which becomes optional or goes to a store the user
chooses), put it behind the same interface as the Delta backend, remove
`bundled(...)` from the build, and update `SPEC.md`. Bump versions.

Sam's own phones keep working: a Delta setup code replaces the baked login.

## Done when
For each app: a fresh install with no setup shows the setup screen and
nothing else that needs a login; a code from the generator configures it; a
Delta code configures it the old way; the published APK contains no
credentials.

## Watch out
- Each app's direct backend is a piece of work of its own, with its own
  API quirks, quotas and token refresh. Brief them separately once step 2
  exists; don't attempt six in one go.
- Tokens are secrets on a phone where other apps can run as root. Keep them
  in the app's private storage, never on the SD card after import, never in
  logs.
- Some of what Delta does can't be done from the phone alone (Places and
  Mail may lean on server-side work). Where the direct path can't match
  Delta, say what is missing in the app's setup screen rather than failing
  later.
