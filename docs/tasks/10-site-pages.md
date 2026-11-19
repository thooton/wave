# 10: Landing page and setup-code generator

**Repo** W (`site/`). **Needs** 09's site skeleton; 14's payload format for
the generator.

## Goal
The public face of Wave, and the tool that gets credentials onto a phone
with no keyboard.

## Deliver
- **Landing page**: what Wave is, a picture of it on the phone, "Jailbreak
  your XP3800" leading to 09's page, what is in the default source, how to
  publish a source (link to `docs/source-format.md` and the publishing
  page), credits and community links, and plain words about risk.
- **Setup-code generator**: pick an app (Charge, Mail, Places, Canvas,
  Authenticator, Flashcards), fill in what that app needs with a short guide
  to obtaining each value (for example, creating a Google Maps key), and get
  a QR code to scan with the phone. Everything happens in the page; nothing
  is sent anywhere, and the page says so. Also: a QR code for adding a
  source URL to Wave.
- The per-app forms are generated from the same description of each app's
  settings that 14 defines, so a new setting doesn't need the page edited by
  hand.

## Done when
Each app in 14 can be set up from a code made on the page, on a real phone.

## Watch out
- A code holds secrets. Don't put them in the URL, don't log them, and say
  on the page not to photograph or share the code.
- Size the code for the XP3's fixed-focus camera reading a monitor: large
  modules, low error correction only if it still reads, and split into
  several codes if one would be too dense (14 decides how).
