# 10: Landing page and setup-code generator

**Repo** W (`site/`). **Needs** 09's site skeleton; 14's payload format for
the generator.

## Goal
The public face of Wave, and the tool that gets credentials onto a phone
with no keyboard.

## Deliver
- **Landing page**: what Wave is, a picture of it on the phone, "Before you
  root" leading to 09's page, what is in the default source, how to
  publish a source (link to `docs/source-format.md` and the publishing
  page), credits and community links, and plain words about risk.
- **Setup-code generator**: pick an app (Charge, Mail, Places, Canvas,
  Authenticator, Flashcards), fill in what that app needs with a short guide
  to obtaining each value (for example, creating a Google Maps key), and get
  a setup file or a QR code, depending on the transport in 14. Everything
  happens in the page; nothing is sent anywhere, and the page says so. If
  scanning is supported, also generate QR codes for adding sources to Wave.
- The per-app forms are generated from the same description of each app's
  settings that 14 defines, so a new setting doesn't need the page edited by
  hand.

## Done when
Each app in 14 can be set up on a reserved test phone using the supported
transport and a payload made on the page.

## Watch out
- Setup payloads hold secrets. Don't put them in the URL or logs. Warn
  users not to share setup files or photograph codes.
- If QR is supported, size codes for the XP3's fixed-focus camera using 14's
  measurements.
