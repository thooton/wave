# Setup payloads

How a Wave app that needs credentials (Canvas, Charge, Mail, Places,
Authenticator, Flashcards) is set up without typing tokens on a T9 keypad.
The site's generator (task 10) writes these payloads; the apps (in
`wave_packages`, `common/core` `Config` and `common/ui` `Setup`) read them.
This document is normative for both. `setup-payload.schema.json` beside it
is the payload's JSON Schema (draft 2020-12), `setup-descriptor.schema.json`
the schema of the per-app descriptors. Where text and schema differ, fix
both.

## The payload

A small UTF-8 JSON object, version 1:

```json
{"wave": 1, "app": "places", "settings": {"key": "AIza..."}}
```

or, for the same app through a Delta command center:

```json
{"wave": 1, "app": "places", "delta": {"server": "https://cc.example.com", "token": "..."}}
```

| Field | Meaning |
| :--- | :--- |
| `wave` | Payload format version, the integer `1`. An app refuses a higher version ("This setup file is for a newer version of the app"). |
| `app` | Which app it is for: the app's id, its directory name in `wave_packages` (`canvas`, `charge`, `mail`, `places`, `authenticator`, `flashcards`). An app refuses a payload for another app. |
| `settings` | The direct backend: an object of string values, named as the app's descriptor names them. Exactly one of `settings` and `delta`. |
| `delta` | The Delta backend: `server` (https only, a host and optional path, no query) and `token` (visible ASCII, no spaces). Only for apps whose descriptor has `delta.supported: true`. |
| `label` | Optional, at most 60 characters: what the setup screen shows the setup as ("sam@example.com", "ASU"). Not a secret. |

Rules a reader applies, and a writer must respect:

- Values in `settings` are strings, trimmed of surrounding white space by
  the reader. An empty string is the same as absent.
- Every setting the descriptor marks `required` must be present. A
  setting the descriptor doesn't name is refused ("Unknown setting: x"),
  so a typo is caught on import, not later.
- Each value must match the setting's `pattern` (when given) and be at
  most its `max` characters (default 2000).
- Unknown top-level fields are ignored, so a later minor addition doesn't
  break older apps.
- The whole payload is at most 8 KB.

A payload is a secret whenever it holds a secret setting or a Delta token.
The generator builds it in the browser and sends it nowhere; the app keeps
it only in its private storage.

### Settings that need no settings

Authenticator and Flashcards keep their data on the phone when set up
directly. Their direct payload is `{"wave": 1, "app": "authenticator",
"settings": {}}`; the setup screen offers it as one choice ("On this phone
only"), so they never need a file except for Delta.

## The descriptors: one per app

Each app's settings are described in one machine-readable file in
`wave_packages`, from which both the app's setup screen and the generator's
form are built:

```
wave_packages/<app>/android/app/src/main/assets/wave-setup.json
```

(`<app>` is the payload's `app`.) The app ships it in its APK and reads it
at run time; the site copies the six files when it is built. Fields (see
`setup-descriptor.schema.json`):

```json
{
  "format": 1,
  "app": "places",
  "name": "Places",
  "package": "com.places.xp3",
  "about": "Search Google Maps from the phone.",
  "settings": [
    {
      "name": "key",
      "label": "Maps API key",
      "secret": true,
      "required": true,
      "manual": false,
      "pattern": "^[A-Za-z0-9_-]{30,60}$",
      "max": 60,
      "example": "AIzaSy...",
      "obtain": "Google Cloud console, APIs & Services, Credentials, Create credentials, API key. Enable the Places API (New) and the Geocoding API for its project."
    }
  ],
  "limits": ["Billed to your Google Cloud project."],
  "delta": {"supported": true, "about": "The command center keeps the Maps key (GET /maps-key)."}
}
```

- `settings[]`: `name` (the payload key, `[a-z][A-Za-z0-9]*`), `label`
  (at most 24 characters, fits the phone's screen), `secret` (masked on
  the phone and on the site, never logged), `required`, `manual` (short
  enough to type on the phone; only these are offered for manual entry),
  `pattern` (an ECMAScript and Java compatible regular expression, matched
  against the whole trimmed value), `max`, `default` (the value the app
  uses when absent, shown by the generator as a placeholder), `example`
  and `obtain` (how to get it, a sentence or two for the generator's form
  and the phone's help), and `group` (a heading the generator shows
  settings under; presentational only).
- `alternatives[]`: settings of which exactly one set must be given.
  Each entry has a `label` and `sets` (lists of setting names); a payload
  must give every setting of exactly one set and none from the others
  ("Sign-in: give exactly one of: Access token; Cookie service + Cookie
  service token"). Settings named here are not `required` themselves.
  Canvas uses it for an access token or a cookie service.
- `limits[]`: what the direct backend can't do that the Delta backend can,
  or costs it brings. The phone's setup screen shows them before importing,
  and so does the generator.
- `delta.supported`: whether a Delta payload is accepted (false for
  Charge: the generator offers only the direct form). `delta.about`:
  what the command center does for the app. The direct backend is always
  available.

## Transports

Version 1 transport is the **setup file**: the payload as a file named
`wave-setup-<app>.json` (the generator's download name; any name starting
`wave-setup` and ending `.json` is accepted, so a browser's
`wave-setup-places (1).json` works too). The app's setup screen imports it
from either:

1. **The app's own folder** on the phone's storage,
   `Android/data/<package>/files/` (reachable over USB as "Phone > Android >
   data > <package> > files", or `adb push wave-setup-places.json
   /sdcard/Android/data/com.places.xp3/files/`). The setup screen looks
   there first, needs no permission, and lists every setup file there for
   this app.
2. **Choose a file**: Android's file picker (`ACTION_OPEN_DOCUMENT`), for a
   file put anywhere else (Download, Bluetooth, the SD card).

After a successful import the app deletes the file it read (through the
picker's provider when picked). If it can't, the setup screen says so and
names the file, since it holds secrets.

The **QR code** transport is reserved, not yet supported (see
`docs/decisions.md`, open item 1). When it is, a code holds the payload's
JSON text as is (byte mode). A payload too dense for one code is split into
chunks, each code holding

```
WS1/<i>/<n>/<id>/<part>
```

where `i` is 1 to `n`, `id` the first 8 hex digits of the SHA-256 of the
whole payload text, and the parts, in order, concatenate to that text. A
reader collects all `n` parts with the same `id`, checks the hash, and
parses the result as above. `common/core`'s `Config.Chunks` already
implements the reassembly, so the generator can be tested against it.

## On the phone

- The imported payload is kept in the app's private files
  (`files/wave-setup.json`, owner-only), never on shared storage, never in
  logs. The setup screen shows the backend (Direct, or Delta and the
  server's host), the `label`, and each setting by its label, secrets as
  "set" only.
- "Sign out" forgets the payload and whatever the app derived from it
  (cached tokens, the Delta login), after a confirmation, and returns to
  the setup screen.
- An app with no setup opens on its setup screen and shows nothing that
  needs one.
- Apps installed before setup payloads existed migrate their stored login
  once: a saved Delta login becomes a Delta setup, Charge's stored Tesla
  tokens and Canvas's saved settings become a direct setup. Nothing is
  baked into an APK any more.

## Per app

| App | Direct backend | Delta backend |
| :--- | :--- | :--- |
| `canvas` | The school's Canvas, with an access token the user makes in Canvas (Account, Settings, New access token). Optionally, instead of a token, a cookie service (`cookieUrl`, `cookieToken`) for schools that don't let students make tokens. | The command center's Canvas relay (`/canvas/...`, `/canvas-upload/...`). |
| `charge` | Tesla's owner API with the user's refresh token, rotated on the phone. Optional Maps key for geocoding (OpenStreetMap otherwise). | Not supported. |
| `mail` | Gmail, with the user's OAuth client (id, secret) and a refresh token for scope `gmail.modify`. | The command center keeps the Gmail login (`GET /gmail-login`). |
| `places` | Google's Places API (New) and Geocoding with the user's key. | The command center keeps the Maps key (`GET /maps-key`). |
| `authenticator` | Accounts kept on the phone only (no settings). | Accounts kept on the command center (`/otp`). |
| `flashcards` | Decks kept on the phone only, no sync (no settings). | Decks synced with the tablet through the command center (`/flashcards/sync`). |

The descriptors are the authority on each app's settings, names and
patterns.
